# Agent 1 — Stack & Platform Research

## Subject
What reliable "has this file changed?" signals does Android SAF provide beyond mtime? How do SAF's
`COLUMN_LAST_MODIFIED`, `ContentObserver`, and related primitives behave across FAT/exFAT/cloud
providers compared to the JVM's `WatchService`?

---

## 1. `DocumentsContract.COLUMN_LAST_MODIFIED` — behaviour and reliability

### What it is
`COLUMN_LAST_MODIFIED` (type `long`) is a standard SAF cursor column defined in
`android.provider.DocumentsContract.Document`. It represents *last-modified epoch milliseconds* as
reported by the document provider, not by the kernel filesystem driver directly.

### Provider contract gaps
The Android API contract (`DocumentsContract` Javadoc) explicitly permits providers to return `null`
when the value is unknown. For providers backed by cloud storage (Google Drive, Nextcloud, Dropbox,
OneDrive) the value is sourced from server-side metadata, not from a local inode. Known failure
modes in practice:

- **Returns 0 / null on new or not-yet-synced files** — confirmed in Nextcloud Android issue #5660.
  Files uploaded via browser show correct mtime; files uploaded via the Android app showed the
  upload timestamp, not the actual file modification time.
- **Staleness on cold start** — when a cloud provider has not yet synced a remote change, the local
  cache of the cursor returns the old mtime. The new value is available only after the provider
  background-syncs.
- **No provider obligation to implement write** — `contentResolver.update(uri, COLUMN_LAST_MODIFIED,
  ...)` throws `UnsupportedOperationException` on ExternalStorageProvider (scoped storage path) and
  on most third-party providers. The column is read-only from the client perspective on all
  documented providers.

### FAT/exFAT specific
FAT32 has 2-second timestamp granularity and exFAT has 10ms granularity, but the granularity
exposed through SAF depends on the provider implementation
(`ExternalStorageProvider` for internal storage, OEM-supplied providers for SD cards). On FAT32
SD card paths, `COLUMN_LAST_MODIFIED` can return values rounded to 2-second boundaries, making
sub-2-second change detection impossible. Files written and read back within the same 2-second
window appear identical in mtime even when content changed.

### How SteleKit currently uses it
`PlatformFileSystem.getLastModifiedTime()` queries `COLUMN_LAST_MODIFIED` via
`queryDocumentLastModified()` for SAF paths, and the result feeds both:
1. The `shouldSkip` guard in `GraphLoader.loadDirectory()` (compare `page.updatedAt` ≥ `fileModTime`)
2. The `lookupExistingPageAndCheckFreshness()` check inside `parseAndSavePage()`

When `COLUMN_LAST_MODIFIED` returns 0, the guard `if (fileModTime != 0L && ...)` correctly falls
through and triggers a re-parse. However when it returns a **stale non-zero value** (cloud provider
not yet synced), the guard incorrectly suppresses the re-parse.

### MANAGE_EXTERNAL_STORAGE fast path
When `Environment.isExternalStorageManager()` is true (Android 11+ with MANAGE_EXTERNAL_STORAGE
permission), SteleKit already bypasses SAF entirely and uses `java.io.File.lastModified()`, which
reads the POSIX inode directly. This path is reliable on ext4 and F2FS (1ms resolution). The SAF
reliability problems only apply to the non-privileged path.

---

## 2. SAF provider flags — filesystem type detection

SAF documents expose `COLUMN_FLAGS` (an int bitmask). Relevant flags:
- `FLAG_SUPPORTS_WRITE` — provider allows writes
- `FLAG_SUPPORTS_METADATA` — provider supports thumbnail, metadata queries (API 29+)
- `FLAG_VIRTUAL_DOCUMENT` — virtual file (no direct byte stream)
- `FLAG_DIR_SUPPORTS_CREATE` — directory allows child creation

There is **no flag that identifies the underlying filesystem type** (FAT32, exFAT, ext4, cloud).
The closest proxy is checking `treeRootDocId`: if it starts with `"primary:"` the volume is the
device's internal storage (ext4/F2FS on modern Android); any other prefix suggests removable
storage or OEM storage, which may be FAT32. Cloud providers expose SAF trees with provider-specific
docId formats (e.g. Google Drive uses document IDs not paths).

Conclusion: there is no SAF API to reliably distinguish "this is a FAT volume" from "this is a
cloud provider", making provider-type-specific mtime workarounds fragile.

---

## 3. `ContentObserver` on SAF tree URIs

Android's `ContentObserver` can be registered on a SAF tree URI using
`contentResolver.registerContentObserver(treeUri, true, observer)`.

### When it fires reliably
- Works reliably for changes made through the same `ContentResolver` on the same device.
- `ExternalStorageProvider` notifies on writes via the standard provider change notification path.

### Limitations with SAF
- ContentObserver receives `onChange(selfChange=false)` for external changes, but it does **not**
  receive the specific URI that changed — only that *something* under the tree changed. This means
  a directory scan is required on every notification regardless.
- Cloud providers (Google Drive, Dropbox) fire ContentObserver notifications for locally-cached
  changes but may delay or batch notifications for remote-originated changes until after their
  background sync completes.
- Not available on iOS or WASM — platform-specific. SteleKit's `FileSystem.startExternalChangeDetection`
  already abstracts this correctly via the `SafChangeDetector` class, which uses ContentObserver +
  `FileObserver` on the direct-access (MANAGE_EXTERNAL_STORAGE) path.

### Current SteleKit architecture
`GraphFileWatcher` already uses a dual mechanism:
- A 5-second polling fallback via `checkDirectoryForChanges`
- A platform-native fast path via `FileSystem.startExternalChangeDetection` (ContentObserver on Android)

The polling fallback compares `COLUMN_LAST_MODIFIED` values from `listFilesWithModTimes()`, which
is a SAF children-cursor query — a single round-trip that reads all files' mtimes at once. This is
significantly cheaper than N individual `getLastModifiedTime()` calls.

---

## 4. JVM `WatchService` vs Android ContentObserver

| Dimension | JVM WatchService | Android ContentObserver |
|---|---|---|
| Underlying mechanism | `inotify` (Linux), FSEvents (macOS), `ReadDirectoryChangesW` (Windows) | Provider notification path via ContentResolver |
| Granularity | File-level: ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE | Tree-level: onChange fires for any change under the tree |
| Latency | ~1ms (inotify) | Varies; cloud providers: seconds |
| Works over SAF | No — SAF paths are not real filesystem paths | Yes — designed for ContentProvider |
| Works without file watcher | No | No (notification-only) |
| iOS/WASM | No | No |

JVM `WatchService` is only usable on JVM with real filesystem paths. The SteleKit desktop JVM path
already uses `legacyGetLastModifiedTime()` which calls `java.io.File.lastModified()` — this is
reliable on ext4/APFS/NTFS and is the correct approach for desktop.

---

## 5. Content URI vs SAF tree URI for change detection

`DocumentsContract.COLUMN_LAST_MODIFIED` behaves differently depending on the provider:
- **ExternalStorageProvider** (internal storage): reads from `MediaStore` metadata, which may
  lag inode mtime by a MediaScanner rescan interval (seconds to minutes on some devices).
- **DownloadsProvider**: generally reliable.
- **Google Drive provider**: reflects cloud server-side mtime, may be stale until sync.
- **Nextcloud SAF provider**: known to return upload timestamp instead of modification time
  (issue #7792 in nextcloud/android).

For a cursor query using `buildChildDocumentsUriUsingTree`, the `COLUMN_LAST_MODIFIED` column is
the best available bulk-read signal. There is no SAF API that returns a content-based hash — the
provider does not expose ETags or checksums.

---

## 6. Recommendations for SteleKit

1. **Do not rely on `COLUMN_LAST_MODIFIED` alone** for correctness on SAF paths. It is unreliable
   on FAT/cloud providers. The current 0-check fallback is a partial mitigation but misses stale
   non-zero values.

2. **Content hash (read-time)**: At navigation time, read the file and compare a fast hash (CRC32
   or xxHash32) against the stored hash in `FileRegistry.contentHashes`. This replaces the mtime
   guard with a content-based guard. The `FileRegistry` already stores `Int` (32-bit) content
   hashes via `contentHashes` map — the infrastructure is in place.

3. **ContentObserver remains valuable** as a fast trigger (fires on-device changes quickly), but it
   should trigger a content-hash comparison, not a mtime comparison.

4. **`invalidateStaleShadow` on cold start**: the current `freshProcess.getAndSet(false)` full
   shadow purge on first cold start is already the right mitigation for cloud provider stale mtime
   — it forces a full SAF read regardless of cached mtime.

5. **`MANAGE_EXTERNAL_STORAGE` fast path** is already reliable and should be kept as-is. The
   problem scope is limited to the SAF-only path.

---

## Sources

- [DocumentsContract.Document API reference](https://developer.android.com/reference/android/provider/DocumentsContract.Document)
- [Get last modified time from SAF files? · nextcloud/android #5660](https://github.com/nextcloud/android/issues/5660)
- [timestamps aren't preserved when uploading · nextcloud/android #7792](https://github.com/nextcloud/android/issues/7792)
- [Scoped Storage Stories: listFiles() Woe — CommonsWare](https://commonsware.com/blog/2019/12/14/scoped-storage-stories-listfiles-woe.html)
- [Android: Scoped Storage COLUMN_LAST_MODIFIED UnsupportedOperationException](https://www.javaallin.com/code/android-scoped-storage-getcontentresolver-update-column-last-modified.html)
- [Building a DocumentsProvider — Ian Lake/Android Developers](https://medium.com/androiddevelopers/building-a-documentsprovider-f7f2fb38e86a)
- SteleKit source: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`
- SteleKit source: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/FileRegistry.kt`
