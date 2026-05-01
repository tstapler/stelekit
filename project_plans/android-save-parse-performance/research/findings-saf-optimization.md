# SAF Performance Optimization Research

**Status**: Draft | **Created**: 2026-04-30
**Related to**: `android-save-parse-performance` — Phase 3 confirmed bottleneck

## Problem

Live session logs confirmed SAF (Storage Access Framework) as the dominant Android performance bottleneck. `parseAndSavePage` took 48,497ms for a single page. Phase 3 background indexing calls `fileSystem.readFile()` once per page, and every call crosses a Binder IPC boundary via `ContentResolver.openInputStream(docUri)`. At 1000+ pages, this serializes to minutes.

Current `PlatformFileSystem.readFile()` path:
```
GraphLoader.parseAndSavePage()
  → fileSystem.readFile(safPath)
  → parseDocumentUri(safPath)               // builds DocumentsContract URI
  → contentResolver.openInputStream(docUri) // Binder IPC → ExternalStorageProvider
  → readText()                              // reads file bytes across IPC
```

## What We Know

| Operation | Estimated overhead per call | Source |
|-----------|---------------------------|--------|
| `contentResolver.openInputStream()` via FileProvider | 5–15ms | `safIoOverhead` benchmark |
| `contentResolver.openInputStream()` via ExternalStorageProvider (real SAF) | 15–100ms+ | Android blog posts, user logs (48s for large page) |
| `DocumentFile.fromSingleUri().exists()` | ~5ms (extra query) | Android docs |
| `queryChildren()` cursor for directory listing | 20–200ms per directory | Android perf guides |
| Direct `File.readText()` (filesDir) | < 1ms | `safIoOverhead` benchmark baseline |

The 48-second `parseAndSavePage` is an outlier — likely a hub page with thousands of blocks whose markdown file is very large, combined with SAF overhead on a busy I/O path.

## Options

### Option A: Shadow Copy (recommended path for Phase 3)

Copy all markdown files from SAF to `context.filesDir/graphs/<id>/` on first graph open. Phase 3 reads from the shadow (fast). Writes go to both SAF (source of truth) and shadow (read cache). Shadow is invalidated on:
- Any file write by the app (GraphWriter already knows which pages were written)
- External change detection (`SafChangeDetector`) triggering `externalFileChanges`

**Complexity**: ~2 days  
**Impact**: Eliminates SAF overhead for all Phase 3 reads (1000× speedup on 1000-page graph)  
**Risk**: Shadow can diverge from SAF if the app crashes mid-write. Mitigate with: write to SAF first, then shadow; on startup re-sync any shadow file whose mtime differs from SAF.

**Sync strategy on startup**:
```
for each page in SAF:
  safMtime = fileSystem.getLastModifiedTime(safPath)
  shadowMtime = File(shadowDir, fileName).lastModified()
  if safMtime != shadowMtime:
    File(shadowDir, fileName).writeText(fileSystem.readFile(safPath))
```
This uses `listFilesWithModTimes()` (already a single batch cursor query in `PlatformFileSystem`) to get all SAF mtimes in one round trip, then copies only stale files.

### Option B: Lazy Phase 3 (alternative / complement)

Instead of indexing all pages eagerly at startup, only index a page when:
1. The user navigates to it
2. A search query hits an un-indexed page

**Complexity**: ~1 day  
**Impact**: Phase 3 startup time drops to near-zero; first navigation to an un-indexed page adds ~50ms latency  
**Risk**: Search results will be incomplete until all pages are indexed. Mitigate by running lazy indexing as background work on `Dispatchers.IO` with the lowest priority.

This is the fastest fix if shadow-copy is deferred.

### Option C: DocumentFile elimination

`DocumentFile.fromSingleUri(ctx, docUri)` is called in `fileExists()` and `directoryExists()` during Phase 3. Each call does an extra `query()` to the ContentProvider for metadata (mimeType, size). Replace with `DocumentsContract.getDocumentThumbnail()` ... actually, use `DocumentsContract` APIs directly without `DocumentFile` to save the extra metadata round trip.

Already partially done for `listFilesWithModTimes` (uses cursor batch). Apply the same pattern to `fileExists` and `directoryExists`.

**Complexity**: ~2 hours  
**Impact**: Reduces per-file overhead during Phase 3 graph enumeration  
**Risk**: Low

### Option D: Parallel SAF reads

Phase 3 currently reads pages sequentially. Launch multiple coroutines reading via SAF in parallel (`Dispatchers.IO` allows many concurrent reads). SAF's `ContentResolver` is thread-safe.

**Complexity**: ~3 hours  
**Impact**: If I/O is the bottleneck (not CPU), parallel reads reduce total Phase 3 time proportionally to parallelism. On a 4-core device with 4 concurrent reads: 4× speedup.  
**Risk**: Medium — DatabaseWriteActor still serializes DB writes, so parallelizing reads doesn't help if the actor is the bottleneck. May also increase SAF provider contention.

### Option E: BundledSQLiteDriver migration

Replace `AndroidSqliteDriver` + `RequerySQLiteOpenHelperFactory` with the bundled SQLite driver via `sqldelight-androidx-driver` (eygraber/GitHub). Bundles SQLite 3.46+ with Android, bypassing the OS-bundled SQLite (3.22 on API 30).

**Complexity**: 1 day  
**Impact**: 20–26% improvement in SQLite throughput (newer SQLite optimizer). Does not address SAF I/O.  
**Risk**: Third-party bridge library; migration required.

### Option F: ParcelFileDescriptor zero-copy reads

For very large markdown files (hub pages with thousands of blocks), the cost may be in copying bytes across Binder IPC. `contentResolver.openFileDescriptor(uri, "r")` returns a `ParcelFileDescriptor` backed by a shared memory region, which avoids a copy. Wrap in `FileInputStream` to read.

**Complexity**: 1 hour  
**Impact**: Unknown — only helps for large files (>64KB, above Binder transaction limit). For typical pages (< 10KB), no difference.  
**Risk**: Low

## Open Questions for Investigation

- [ ] What fraction of the 1000+ page graph is large files (>64KB)? `adb shell run-as dev.stapler.stelekit find filesDir -name "*.md" -size +64k | wc -l`
- [ ] Does `contentResolver.openFileDescriptor` (Option F) reduce latency for large pages vs `openInputStream`?
- [ ] What is the `listFilesWithModTimes()` round-trip time for 1000 files via SAF on the user's device? (one `queryChildren()` cursor vs 1000 individual `getLastModifiedTime()` calls)
- [ ] Does parallel SAF read (Option D) reduce Phase 3 time, or does `ExternalStorageProvider` serialize concurrent requests?
- [ ] Can `DocumentsContract.copyDocument()` bulk-copy from SAF to filesDir in one IPC call, or does it still do per-file round trips?

## Recommended Sequence

1. **Now (0 risk)**: PRAGMA tuning — already done in `DriverFactory.android.kt`
2. **Now (low risk)**: Eliminate `DocumentFile` in `fileExists`/`directoryExists` (Option C) — ~2 hours
3. **Next (medium effort)**: Lazy Phase 3 (Option B) — removes the SAF bottleneck from the critical path without a shadow copy
4. **After measurement**: Shadow copy (Option A) — only justified once Option B is in place and we can measure whether writes (not Phase 3 reads) are still the bottleneck

Do not implement Option D (parallel reads) before Option B — parallel SAF reads of 1000 pages will saturate the actor's write queue, making interactive write latency worse.

## Sources

- `project_plans/android-save-parse-performance/research/synthesis.md`
- [Android SAF performance](https://developer.android.com/guide/topics/providers/document-provider)
- [ContentResolver threading model](https://developer.android.com/reference/android/content/ContentResolver)
- [ParcelFileDescriptor and Binder IPC](https://developer.android.com/reference/android/os/ParcelFileDescriptor)
- [Best practices for SQLite on Android](https://developer.android.com/topic/performance/sqlite-performance-best-practices)
- Live session logs, 2026-04-30: `parseAndSavePage took 48497ms` at `saf://...personal-wiki/logseq/journals/2026_04_29.md`
