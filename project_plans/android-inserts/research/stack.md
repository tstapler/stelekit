# Stack Research: Android Block Insert Performance

## 1. Current Write Stack (from source code)

### Write path for a block insert (e.g. Enter key / addBlockToPage)

```
BlockStateManager.addBlockToPage / splitBlock
  └─ writeBlock(block)                    ← optimistic local state update first
       └─ DatabaseWriteActor.saveBlock()  ← HIGH-priority channel send, awaits deferred
            └─ blockRepository.saveBlock() / splitBlock()
                 └─ SqlDelightBlockRepository.splitBlock()
                      └─ withContext(PlatformDispatcher.DB) { queries.transaction { … } }
                           └─ AndroidSqliteDriver (RequerySQLiteOpenHelper + WAL)
  └─ queueDiskSave(pageUuid)              ← debounced 300ms (DebounceManager)
       └─ graphWriter.queueSave()         ← debounced additional 500ms
            └─ savePageInternal()         ← under saveMutex
                 └─ fileSystem.writeFile(safPath, content)
                      └─ PlatformFileSystem.writeFile() — SAF branch
                           └─ DocumentFile.fromSingleUri().exists()    ← 1 Binder IPC
                           └─ createMarkdownFile() if new             ← 1 Binder IPC
                           └─ contentResolver.openOutputStream(docUri, "wt") ← 1 Binder IPC
                           └─ stream.write + flush
                      └─ fileSystem.updateShadow()   ← writes to filesDir (fast)
```

**Critical path for UI responsiveness**: The DB write (via actor channel) is on the critical path — the user perceives the new block appearing only after `writeBlock` returns. The file write is debounced (300ms + 500ms) and runs asynchronously, so it does NOT directly cause the 1–2 second UI lag on its own.

### The real bottleneck candidates

**Candidate A — DatabaseWriteActor queue wait**: `saveBlock` is implemented as `execute { blockRepository.saveBlock(block) }`, which sends to the HIGH-priority channel and `await()`s the deferred. If the actor is busy (e.g. draining a coalesced LOW-priority Phase-3 batch), the caller blocks until the current batch flushes. The actor does check for high-priority preemption between items in a batch (per the coalescing logic in `processSaveBlocks`), but a running `transaction {}` block cannot be interrupted mid-flight.

**Candidate B — splitBlock transaction cost**: `splitBlock` in `SqlDelightBlockRepository` runs a SQLite transaction that: reads the current block, updates content, generates a new UUID, queries all siblings to shift positions, inserts the new block, and repairs the linked-list chain (`left_uuid`). This is 4–7 SQL statements in a single transaction. On Android with WAL + `synchronous=NORMAL`, this should be fast (~1–5ms), but on a cold DB or a page with many siblings it could be slower.

**Candidate C — SAF file write latency (deferred, but cumulative)**: Each `writeFile()` on a SAF path involves at least 3 Binder IPC round-trips (exist check, optional create, openOutputStream). Binder round-trips add ~70–214 µs per call for small payloads (kernel-measured), but `ExternalStorageProvider` adds NAND + provider-thread scheduling overhead on top, pushing per-file write to **10–200ms** on real devices per community benchmarks. Because the file write is debounced ~800ms after the keystroke, it does not block input directly — but if the debounced save is still in progress when the user presses Enter again (back-to-back inserts), `saveMutex` in `GraphWriter.savePageInternal` will serialize them, and the second write waits for the first SAF write to complete. On a 200ms SAF write + 500ms debounce this means successive inserts could be delayed.

**Candidate D — Shadow cache not updated on write path**: `ShadowFileCache.update()` is called synchronously after the SAF write in `savePageInternal`. If the SAF write takes 200ms (held under `saveMutex`), the next read in GraphLoader's Phase 3 can read stale shadow until the write completes. This is not a latency issue but could cause lost-update confusion.

---

## 2. Android SQLite Driver

**Driver**: `AndroidSqliteDriver` (SQLDelight 2.3.2) backed by `RequerySQLiteOpenHelperFactory` (sqlite-android 3.49.0 — a bundled SQLite that is newer than the system SQLite on most Android versions).

**Dispatcher**: `PlatformDispatcher.DB = Dispatchers.IO` on Android (unlike JVM which uses the 8-thread JDBC pool; Android's native driver manages its own connection pool).

**PRAGMAs applied at init**:
- `journal_mode=WAL` — concurrent reads during write, reduces SQLITE_BUSY
- `synchronous=NORMAL` — no fsync on every transaction commit (only on WAL checkpoint)
- `busy_timeout=10000` — retry up to 10s before SQLITE_BUSY
- `wal_autocheckpoint=4000` — reduced checkpoint frequency
- `temp_store=MEMORY` — temp tables in RAM
- `cache_size=-8000` — 8MB page cache

**Performance implication**: With WAL + NORMAL synchronous, individual SQLite write transactions on Android should be in the **1–10ms** range for small block inserts (no fsync, WAL append-only write). The `DatabaseWriteActor` serializes writes to a single coroutine, so there is no write-lock contention. The actor's HIGH-priority channel drains before LOW-priority, so user-initiated `saveBlock` should not be blocked by Phase-3 bulk loads for more than one in-flight LOW-priority transaction.

**JVM contrast**: JVM uses `sqlite-driver` (SQLDelight JDBC, sqlite-jdbc 3.51.3+) with `PlatformDispatcher.DB = Dispatchers.IO` backed by 8-thread pool. The JDBC driver uses `Dispatchers.IO` for all DB work; there is no WAL on JVM by default unless explicitly set. Android's bundled SQLite 3.49.0 is newer than many JVM sqlite-jdbc builds, but the fundamental write characteristics should be similar given both use WAL.

---

## 3. SAF Performance Characteristics

### Per-call overhead

Every `PlatformFileSystem` method that operates on a SAF path goes through `ContentResolver`, which routes through Binder IPC to `ExternalStorageProvider` (system process). Each call has:

- **Binder base latency**: ~70–214 µs per IPC round-trip for small payloads (kernel-measured, Google I/O data)
- **ExternalStorageProvider overhead**: NAND latency + provider thread scheduling; adds 5–50ms per call on real devices with NAND-backed external storage
- **Two IPC layers**: app → MediaProvider/ExternalStorageProvider → kernel filesystem

For a single `writeFile()` call, the code path is:
1. `DocumentFile.fromSingleUri(ctx, docUri).exists()` — 1 ContentResolver query (Binder IPC)
2. `createMarkdownFile(parentDocUri, fileName)` — 1 `DocumentsContract.createDocument` call (Binder IPC), only if file is new
3. `contentResolver.openOutputStream(docUri, "wt")` — 1 Binder IPC to open the file descriptor
4. `stream.write(content)` + `flush()` — direct write to the file descriptor (fast, ~1–5ms for a typical markdown page)

**Estimated wall-clock per SAF write**: 15–200ms on a real Android device (10–50ms typical mid-range phone, up to 200ms worst-case on slow NAND or provider contention).

### Directory operations (not the hot path for block inserts)

SAF directory listing (`queryChildren`) is 25–50x slower than `java.io.File.listFiles()` in community benchmarks (e.g. tliebeck/saftest shows 3,000%+ overhead for directory traversal). This is the dominant cost during Phase-3 background indexing, not during individual block inserts.

### Shadow cache mitigates reads

`ShadowFileCache` mirrors SAF-backed files to `filesDir` (direct filesystem, no IPC). Phase-3 background indexing reads the shadow, bypassing SAF IPC on reads. However, **writes still go through SAF** — the shadow is only updated after a successful SAF write.

---

## 4. Existing Benchmark Infrastructure

### JVM benchmarks (CI-runnable)

- `GraphLoadTimingTest` in `jvmTest` — measures graph load phases
- `SyntheticGraphGenerator` — generates deterministic 500-page / 90-journal test graph

### Android instrumented benchmarks (device-required)

`AndroidGraphBenchmark` in `androidInstrumentedTest` covers:
- `loadPhaseTimings` — Phase 1 + Phase 3 load timing
- `writeLatencyDuringPhase3` — HIGH vs LOW priority actor contention (p95 must be < 5s)
- `editingOperationsLatency` — `saveBlock` + `splitBlock` latency on fully loaded graph (p95 must be < 500ms)
- `safIoOverhead` — ContentProvider (FileProvider proxy) read overhead vs direct `File.readText()` (measures lower bound of SAF read cost; does not measure write cost)

**Gap**: There is no existing benchmark that measures the full insert-to-committed wall-clock latency including the SAF file write, nor a JVM-side shim that simulates SAF write latency for CI enforcement.

---

## 5. Relevant KMP/Android Libraries

| Library | Version | Role |
|---|---|---|
| `app.cash.sqldelight:android-driver` | 2.3.2 | Android SQLite driver via `AndroidSqliteDriver` |
| `com.github.requery:sqlite-android` | 3.49.0 | Bundled SQLite (newer than system) via `RequerySQLiteOpenHelperFactory` |
| `androidx.documentfile:documentfile` | 1.0.1 | `DocumentFile` wrapper for SAF operations |
| `io.arrow-kt:arrow-fx-coroutines` | 2.2.1.1 | `Resource`, `Saga` — used in `GraphWriter` write pipeline |
| `io.arrow-kt:arrow-resilience` | 2.2.1.1 | `saga { }.transact()` — transactional rollback in `savePageInternal` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.10.2 | `Dispatchers.IO` / `Dispatchers.Main` on Android |
| `androidx.benchmark:benchmark-junit4` | 1.3.4 | Android instrumented benchmark runner |

**Note on `DocumentFileCompat`** (ItzNotABug/DocumentFileCompat): Community library claiming up to 14x faster directory listing over `DocumentFile` by batching `queryChildren` calls. Not currently used. Would help Phase-3 bulk listing but not per-file write latency (write path bypasses `DocumentFile` and goes directly through `DocumentsContract` + `ContentResolver`).

---

## 6. Key Findings Summary

1. **The 1–2s UI lag is most likely the DatabaseWriteActor queue wait, not the SAF file write.** The actor serializes all writes; if a LOW-priority batch is mid-transaction when the user presses Enter, the HIGH-priority `saveBlock` call waits until that transaction commits. The `splitBlock` transaction itself is multi-statement (4–7 SQL ops) and runs under `PlatformDispatcher.DB` (`Dispatchers.IO`). The actor does implement HIGH-priority preemption between batch items but cannot interrupt a running `transaction {}` block.

2. **SAF file writes are slow (15–200ms each) but are debounced ~800ms off the critical path.** The `saveMutex` in `GraphWriter.savePageInternal` can serialize back-to-back page saves. If a user types fast (multiple inserts within 800ms), the first SAF write may still be holding `saveMutex` when the debounced second write fires, causing a visible stall. This is a secondary bottleneck.

3. **No CI-runnable benchmark currently measures the full insert latency including the SAF write path.** The existing `editingOperationsLatency` test measures only DB writes (500ms budget) and uses a direct `File`-based `FileSystem`, not a SAF-simulating shim. FR-3 requires building a JVM-side latency shim that injects configurable SAF-like delays into `FileSystem.writeFile()` to exercise the `saveMutex` contention scenario.

---

## Sources

- [GitHub - tliebeck/saftest: Android SAF vs conventional file API performance test](https://github.com/tliebeck/saftest)
- [GitHub - ItzNotABug/DocumentFileCompat: Up to ~14x faster DocumentFile alternative](https://github.com/ItzNotABug/DocumentFileCompat)
- [Android Developers: Access documents and other files from shared storage](https://developer.android.com/training/data-storage/shared/documents-files)
- [Storage Access Framework Performance discussion](https://magicbox.imejl.sk/forums/topic/storage-access-framework-performance/)
- [CommonsWare: Scoped Storage Stories — DocumentFile](https://commonsware.com/blog/2019/11/02/scoped-storage-stories-documentfile.html)
- [Android Developers: Best practices for SQLite performance](https://developer.android.com/topic/performance/sqlite-performance-best-practices)
- [Android AOSP: Compatibility WAL for apps](https://source.android.com/docs/core/perf/compatibility-wal)
- [SQLite performance tuning — phiresky's blog](https://phiresky.github.io/blog/2020/sqlite-performance-tuning/)
- [Android Perfetto Series 10: Binder Scheduling and Lock Contention](https://androidperformance.com/en/2025/11/16/Android-Perfetto-10-Binder/)
- [SQLDelight Android Driver — Getting Started](https://sqldelight.github.io/sqldelight/2.1.0/jvm_sqlite/)
