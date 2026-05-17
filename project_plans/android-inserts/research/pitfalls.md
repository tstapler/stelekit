# Pitfalls Research: Android Block Insert Performance

## Overview

This document catalogues known failure modes, data-loss risks, Android-specific performance
traps, and things to avoid when diagnosing and fixing the Android write-latency regression.
All findings are grounded in the actual codebase state as of this research pass.

---

## 1. SAF Binder IPC Is Synchronous and Blocks the Calling Thread

### What the code does

`GraphWriter.savePageInternal` calls `fileSystem.writeFile(filePath, content)` (and
`fileSystem.fileExists`, `fileSystem.readFile` for the safety check) with no
`withContext(IO)` wrapper. On Android, `PlatformFileSystem.writeFile` opens a
`ContentResolver.openOutputStream` and writes through a Binder IPC call to the
DocumentsProvider process. This is a blocking Binder round-trip.

### Why it causes jank

`GraphWriter` runs on the ViewModel's `scope`, which is
`CoroutineScope(SupervisorJob() + Dispatchers.Default)`. `Dispatchers.Default` has a thread
pool sized to the CPU count (typically 4–8 threads on phones). Every SAF write occupies one
of those threads for the duration of the Binder call — empirically 50–500 ms per file write
on mid-range devices. Because there are only 4–8 Default threads, a single blocked thread
can stall other Default-dispatched coroutines, including UI state updates in the ViewModel.

The `DebounceManager` used by `BlockStateManager.queueDiskSave` also launches on
`BlockStateManager.scope` (which is also `Dispatchers.Default`). After the 300 ms debounce
fires, `graphWriter.queueSave` → `GraphWriter.queueSave` → `delay(500)` → `saveImmediately`
→ `savePageInternal` all execute sequentially on Default threads. During that window, any
SAF file-existence check (`fileExists` → `ContentResolver.query`) adds another Binder IPC
on top of the write.

### Fix direction

Wrap **all** blocking SAF calls inside `GraphWriter.savePageInternal` in
`withContext(PlatformDispatcher.IO)` (which maps to `Dispatchers.IO` on Android). IO has a
larger elastic thread pool (up to 64 threads by default) that can absorb blocking calls
without stealing CPU-bound Default threads. Alternatively, move the entire file-write saga
into a dedicated coroutine context.

### Do not do

- Do **not** move the write to `Dispatchers.Main` — that is the UI thread and will
  immediately cause ANRs.
- Do **not** bypass the debounce by calling `savePage` directly from the UI event handler —
  this would fire one full SAF write per keystroke.

---

## 2. Multiple Blocking Binder IPC Calls per Write (Safety Check Overhead)

### What the code does

Before every `savePageInternal`, the safety check reads the existing file content:

```kotlin
if (fileSystem.fileExists(filePath)) {   // Binder IPC #1: ContentResolver.query
    val oldContent = fileSystem.readFile(filePath)   // Binder IPC #2: openInputStream + read
    ...
}
```

On a new page that already exists on disk this fires **two** round-trip Binder calls before
the actual write Binder call. For an existing page on a slow external provider (e.g.
`primary:personal-wiki/logseq` on an SD-backed provider), each call can take 50–200 ms.
Three serial calls = 150–600 ms **before** the write even starts.

### Fix direction

- Cache the file's last-known byte-length or a hash in `ShadowFileCache` so the safety
  check can use the shadow rather than re-reading from SAF.
- Or defer the safety check to run asynchronously after the write rather than blocking the
  write path.
- **Do not remove the safety check entirely** — it protects against >50% bulk deletion
  accidents and must remain for correctness.

---

## 3. Data-Loss Risk: Async File Write + Process Kill Before Flush

### The planned approach (requirements FR-2)

The requirements propose deferring or batching file writes as the fix for SAF latency.
If the DB write succeeds but the file write has not yet executed when Android kills the
process, the markdown file will be stale.

### Current safeguard

`GraphWriter.resource` registers a `flush()` call in the Arrow `Resource` release block.
`StelekitViewModel.close()` cancels its scope, which releases the Resource and triggers
`flush()`. However:

- On Android, `Activity.onDestroy` is not guaranteed to be called before process kill
  (especially on OOM kills and crash kills).
- `flush()` in `GraphWriter` cancels pending debounce jobs and fires the saves
  synchronously, but this happens on the scope that was just cancelled — if the scope is
  already cancelled before `flush()` executes, the SAF writes will not happen.
- **The requirements (NFR-2) require that the DB is the source of truth and the next launch
  must regenerate the markdown file.** This regeneration path does not currently exist.

### What to implement (and what to avoid)

**Implement**: On app startup, compare the DB `updated_at` timestamp for each page against
the file's last-modified timestamp from SAF. If the DB is newer, regenerate the markdown
file before opening the page. This recovery path must survive an interrupted flush.

**Avoid**: Trusting `onPause`/`onStop` for flush. Android may kill the process immediately
after `onStop` without calling `onDestroy`. Use `androidx.lifecycle.ProcessLifecycleOwner`
`ON_STOP` to trigger an immediate synchronous flush (or use Android `WorkManager` for a
guaranteed background write).

**Avoid**: Using `GlobalScope.launch` for deferred writes — `GlobalScope` outlives the
Activity but not the process, and the write will still be lost on process kill.

---

## 4. DiskConflict Detection Becomes Stale with Async File Writes

### Current mechanism

`GraphLoader.externalFileChanges` is a `SharedFlow` that emits when the `SafChangeDetector`
observes a mtime change on the tree URI. `GraphWriter.onFileWritten` callback suppresses
conflict detection for writes made by the app itself by pre-registering the path in
`FileRegistry`.

### The pitfall

If file writes are made async (deferred after DB commit), the window between the DB write
and the file write is a blind spot:

1. User types in block → DB write commits → conflict suppression window starts.
2. Git sync or another device writes the same file remotely.
3. SAF change detector fires for the remote write.
4. At this point `FileRegistry` does **not** have the path pre-registered (the local write
   hasn't happened yet), so the change is treated as external.
5. A `DiskConflict` dialog appears even though the local version (in DB) is correct.

### Mitigation

Keep the `FileRegistry` pre-registration at DB-write time (not file-write time), so the
conflict detector suppresses incoming changes during the async write window. The
suppression should persist until the file write actually completes and
`onFileWritten?.invoke(filePath)` fires.

---

## 5. SAF URI Permission Expiration

### The risk

Android persists SAF URI permissions across reboots via `takePersistableUriPermission`, but
these can expire or be revoked:
- The user clears app data.
- A system OTA reboots with a changed document provider.
- The SD card is removed and reinserted.
- The user explicitly revokes permission via Settings → Apps → SteleKit → Permissions.

### Current state

`PlatformFileSystem.isSafPermissionValid` correctly checks
`contentResolver.persistedUriPermissions` and returns `false` on revocation. However, a
write failure (from an expired permission) inside `savePageInternal` returns `false` from
`fileSystem.writeFile` and logs an error, but the saga's compensation path does not
propagate the permission-expired error to the UI. The user sees no notification that their
data was not saved to disk.

### Mitigation

Add a specific `DomainError.FileSystemError.PermissionExpired` variant and surface it as a
persistent notification when `writeFile` fails with `SecurityException`. The existing
`notificationManager.show(...)` pattern in `PersistenceManager` is a good model.

---

## 6. RequerySQLiteOpenHelperFactory: WAL Applied via `driver.execute`, Not Connection Property

### The risk

On JVM, WAL is applied via JDBC connection properties:
```kotlin
setProperty("journal_mode", "WAL")
```
On Android, WAL is applied post-construction via `driver.execute(null, "PRAGMA journal_mode=WAL;", 0)`.

The `AndroidSqliteDriver` wraps `RequerySQLiteOpenHelperFactory`. When the database is
first opened, `onConfigure` (the recommended place for PRAGMAs) is not called from the
DriverFactory — PRAGMAs are applied after `schema.create`. If the schema is large and the
PRAGMAs fail silently (the `try { } catch (_: Exception) { }` pattern), the database may
run in DELETE journal mode for the entire session. DELETE journal mode is
significantly slower for write-heavy workloads because every write fsync is a full-file
sync rather than an append to the WAL file.

### Verification

Instrument the driver startup to confirm WAL was actually applied:
```kotlin
driver.execute(null, "PRAGMA journal_mode;", 0)
// log the result — should be "wal"
```
If it returns "delete", the PRAGMA failed silently.

### Mitigation

Apply PRAGMAs inside `RequerySQLiteOpenHelperFactory`'s `onConfigure` callback, which fires
before the schema is touched. Use a custom `SupportSQLiteOpenHelper.Callback` subclass to
override `onConfigure`.

---

## 7. Actor Queue Serialization Depth During Block Insert

### What happens on a block insert

`BlockStateManager.addBlockToPage` calls `writeBlock(newBlock)` which routes to
`writeActor.saveBlock(newBlock)`. The actor's HIGH priority channel is processed before
LOW, but the actor is single-threaded by design. If a Phase-3 bulk load is in progress (LOW
priority), the actor will complete the current LOW batch before draining HIGH. The
coalescing logic interrupts LOW batches on HIGH arrival, but there is still a round-trip
through the `CompletableDeferred.await()` call before `addBlockToPage` returns.

On Android with `PlatformDispatcher.DB = Dispatchers.IO`, the DB write itself is fast
(WAL + IO thread). The bottleneck is not the actor queue per se, but the file write that
follows (`queueDiskSave` fires 300 ms later then calls `graphWriter.queueSave` → 500 ms
debounce → `savePageInternal`).

### The pitfall when decoupling DB and file write

If the fix decouples DB write from file write (DB commits immediately; file write is batched
or deferred), ensure the actor's `onWriteSuccess` callback is used to trigger the file
write notification rather than a second coroutine launch from `BlockStateManager`. Launching
a separate coroutine from `BlockStateManager.scope` creates an ordering dependency that is
hard to test.

---

## 8. `PersistenceManager` Is a Parallel Write Path (Not Currently Wired)

### Observation

`PersistenceManager` (`editor/persistence/PersistenceManager.kt`) is a complete alternative
persistence system with its own `autoSaveJob`, conflict detection, and `BlockRepository`
write path. It is **not currently wired** into the block insert path (BlockStateManager
does not instantiate or use it), but its presence creates confusion about which path is
authoritative.

### The pitfall

If a future fix wires `PersistenceManager` into the block insert path alongside the
existing `BlockStateManager` → `DatabaseWriteActor` path, blocks will be written twice
per insert. The `PersistenceManager.saveBlock` calls `blockRepository.saveBlock` directly
(with `@OptIn(DirectRepositoryWrite::class)` at file level), bypassing the actor's
serialization and potentially causing `SQLITE_BUSY` under concurrent inserts.

### Recommendation

Either deprecate and remove `PersistenceManager` or ensure it is strictly gated so it
cannot be wired in parallel with the actor path. Do not add new wiring to
`PersistenceManager` as part of the performance fix.

---

## 9. `runBlocking` in DriverFactory.android.kt on App Startup

### Location

```kotlin
// DriverFactory.android.kt line 62
runBlocking { MigrationRunner.applyAll(driver) }
```

This `runBlocking` call happens on whatever thread calls `createDriver` (typically the main
thread via `DriverFactory().init(context)` from `Application.onCreate`). If
`MigrationRunner.applyAll` is slow (many migrations, large schema), this blocks the main
thread during app startup and can trigger an ANR.

This is not directly related to the block insert latency bug, but it is a fragile pattern
that could regress if migrations grow.

### Mitigation

Move `createDriver` off the main thread (call it from a `withContext(IO)` block in
`Application.onCreate`) or use `AndroidSqliteDriver`'s async schema API.

---

## Summary of Key Pitfalls

| # | Risk | Severity | Direct cause of insert lag? |
|---|------|----------|-----------------------------|
| 1 | SAF Binder calls on Default dispatcher (no IO context) | Critical | **Yes** |
| 2 | Three serial Binder IPCs per write (fileExists + readFile + write) | High | **Yes** |
| 3 | Data loss on process kill if file write is async | Critical | No (correctness) |
| 4 | Stale DiskConflict detection during async write window | High | No (correctness) |
| 5 | SAF permission expiry silently drops writes | Medium | No (correctness) |
| 6 | WAL PRAGMA silently failing → DELETE journal | High | Potential |
| 7 | Actor queue depth misattributed as bottleneck | Low | No |
| 8 | PersistenceManager wired in parallel would cause double writes | Medium | No (future risk) |
| 9 | `runBlocking` in DriverFactory on main thread | Low | No |
