# Architecture Research: Android Block Insert Write Path

## Overview

This document maps the complete write path for block insert operations from user action to disk on Android, and catalogs the existing benchmark infrastructure to guide new benchmark design.

---

## 1. Complete Write Path: User Action to Disk

### Data-Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  COMPOSE UI LAYER                                                           │
│  BlockItem (ui/screens/PageView.kt)                                         │
│  User presses Enter / types / pastes                                        │
└──────────────────────────┬──────────────────────────────────────────────────┘
                           │  fun call (coroutine launch on BSM.scope)
                           ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  BlockStateManager  (ui/state/BlockStateManager.kt)                         │
│                                                                             │
│  addBlockToPage(pageUuid)                                                   │
│  ├─ 1. Build new Block with UuidGenerator.generateV7()                      │
│  ├─ 2. Optimistically add to _blocks StateFlow (local UI state)             │
│  ├─ 3. requestEditBlock(newBlock.uuid)  ← keyboard focus, instant           │
│  ├─ 4. writeBlock(newBlock)            ← DB write (suspending)              │
│  │      └─ writeActor.saveBlock(block)                                      │
│  │           └─ writeActor.execute { blockRepository.saveBlock(block) }     │
│  └─ 5. queueDiskSave(pageUuid)         ← 300ms debounce                    │
│                                                                             │
│  splitBlock(blockUuid, cursorPosition)  [Enter mid-block]                   │
│  ├─ blockRepository.splitBlock(blockUuid, cursorPosition)                   │
│  │    (direct call — goes through writeActor.execute internally)            │
│  └─ queueDiskSave(pageUuid)                                                 │
│                                                                             │
│  applyContentChange(blockUuid, content, version)  [typing]                  │
│  ├─ dirtyBlocks[blockUuid] = version   ← mark dirty BEFORE write           │
│  ├─ writeContentOnly(blockUuid, content)                                    │
│  │      └─ writeActor.updateBlockContentOnly(blockUuid, content)            │
│  └─ queueDiskSave(pageUuid)                                                 │
└──────────────────────────┬──────────────────────────────────────────────────┘
                           │
               ┌───────────┴───────────┐
               │  FORK: two async paths │
               └───────────┬───────────┘
                           │
        ┌──────────────────┼──────────────────┐
        │                                     │
        ▼                                     ▼
┌──────────────────┐              ┌───────────────────────────┐
│  Path A: DB Write│              │  Path B: File Write       │
│  (immediate,     │              │  (debounced 300ms)        │
│   awaited)       │              │                           │
└──────┬───────────┘              └──────────┬────────────────┘
       │                                     │
       ▼                                     │
┌─────────────────────────────────┐          │
│  DatabaseWriteActor             │          │
│  (db/DatabaseWriteActor.kt)     │          │
│                                 │          │
│  Two channels (Channel.UNLIMITED)│         │
│  ├─ highPriority: user edits    │          │
│  └─ lowPriority: bulk load      │          │
│                                 │          │
│  Actor loop (single coroutine): │          │
│  ├─ poll highPriority first     │          │
│  ├─ select { high, low }        │          │
│  ├─ coalesce consecutive        │          │
│  │  SaveBlocks → one tx         │          │
│  └─ processRequest(req)         │          │
│       └─ blockRepository        │          │
│           .saveBlock(block)     │          │
└──────┬──────────────────────────┘          │
       │                                     │
       ▼                                     ▼
┌─────────────────────────────────┐  ┌───────────────────────────────────────┐
│  SqlDelightBlockRepository      │  │  GraphWriter                          │
│  (repository/SqlDelight*.kt)    │  │  (db/GraphWriter.kt)                  │
│                                 │  │                                       │
│  withContext(PlatformDispatcher │  │  queueSave(page, blocks, graphPath)   │
│    .DB) {                       │  │  ├─ cancel previous pending job       │
│    queries.upsertBlock(...)     │  │  └─ scope.launch {                    │
│  }                              │  │       delay(500ms)  ← second debounce │
│                                 │  │       savePageInternal(page,blocks)   │
│  JVM: Dispatchers.IO (pool=8)  │  │     }                                 │
│  Android: Dispatchers.IO        │  │                                       │
└──────┬──────────────────────────┘  └──────────────────┬────────────────────┘
       │                                                │
       ▼                                                ▼
┌──────────────────────┐               ┌────────────────────────────────────────┐
│  SQLite (WAL mode)   │               │  savePageInternal()                    │
│  kmp/build/*.db      │               │  ├─ saveMutex.withLock { }             │
│                      │               │  ├─ buildMarkdown(page, blocks)        │
│  JVM: JDBC pooled    │               │  └─ Arrow Saga (transactional):        │
│  Android: native     │               │       Step 1: fileSystem.writeFile()   │
│  SQLite driver       │               │         (or writeFileBytes if paranoid) │
└──────────────────────┘               │       Step 2: onFileWritten?.invoke()  │
                                       │         (suppress external-change det) │
                                       │       Step 3: sidecar write (optional) │
                                       │       Step 4: DB filePath update       │
                                       │         (fire-and-forget via actor)    │
                                       └──────────────────┬─────────────────────┘
                                                          │
                                                          ▼
                              ┌──────────────────────────────────────────────────┐
                              │  FileSystem.writeFile(path, content)             │
                              │  (platform/PlatformFileSystem.kt)                │
                              │                                                  │
                              │  JVM actual:                                     │
                              │    File(path).writeText(content)                 │
                              │    → ~1ms on local FS                            │
                              │                                                  │
                              │  Android actual:                                 │
                              │    if path.startsWith("saf://"):                 │
                              │      parseDocumentUri(path) → DocumentsContract  │
                              │      contentResolver.openOutputStream(docUri,"wt")│
                              │      stream.bufferedWriter().write(content)       │
                              │    → 10–200ms on SAF (SUSPECTED BOTTLENECK)      │
                              └──────────────────────────────────────────────────┘
                                                          │
                                                          ▼
                              ┌──────────────────────────────────────────────────┐
                              │  DISK (Android internal storage or external SAF) │
                              │  Android SAF path: content:// URI via Binder IPC │
                              │  then MediaProvider write to actual .md file      │
                              └──────────────────────────────────────────────────┘
```

### Key Timing Observations

| Step | JVM latency | Android latency | Notes |
|---|---|---|---|
| optimistic UI update | ~0ms | ~0ms | Instant, no DB involved |
| DB write (DatabaseWriteActor) | ~1–5ms | ~5–20ms | SQLite WAL write |
| File write debounce delay | 300ms BSM + 500ms GraphWriter | same | Two debounce stages |
| SAF writeFile (the big one) | ~1ms (native FS) | **10–200ms** | ContentResolver Binder IPC |
| Total "committed" latency | ~1–5ms | ~15–220ms | DB done, file dispatched |

The two debounce layers mean:
1. `BlockStateManager.queueDiskSave` → `DebounceManager` (300ms)
2. `GraphWriter.queueSave` → `delay(500ms)` internal debounce

So the file write fires ~800ms after the last keystroke at minimum. This means the 1–2 second user-perceived lag is likely the **combination of the 500ms debounce inside GraphWriter + the SAF write itself**.

---

## 2. Android SAF Write Path (Suspected Bottleneck)

The Android `PlatformFileSystem.writeFile()` for SAF paths:
1. Calls `parseDocumentUri(path)` — resolves `saf://...` → `content://...` URI via `DocumentsContract`
2. Checks if the file exists via `DocumentFile.fromSingleUri(ctx, docUri)?.exists()` — this is a **ContentResolver query** (Binder IPC round-trip)
3. If file doesn't exist: calls `createMarkdownFile()` → `DocumentsContract.createDocument()` — another Binder IPC
4. Opens `contentResolver.openOutputStream(docUri, "wt")` — Binder IPC to MediaProvider
5. Writes content via `bufferedWriter`
6. Closes stream (triggers flush + MediaProvider notification)

Each Binder IPC call can take 5–50ms. A single new-block write can trigger 3–4 Binder calls, summing to 15–200ms.

### Android fileExists() cost

`fileExists()` issues a `ContentResolver.query()` for `COLUMN_MIME_TYPE`. This happens in `savePageInternal()` step 0 (safety check for large deletions), which queries `fileSystem.fileExists(filePath)` and then `fileSystem.readFile(filePath)` on the existing content. This adds another 2 ContentResolver round-trips per save.

---

## 3. BlockStateManager Insert Operations

### `addBlockToPage(pageUuid)` — new block (Enter/+)

```
1. Build Block in memory (UuidGenerator.generateV7())
2. Optimistic update: _blocks.update { ... + newBlock }
3. pendingNewBlockUuids.update { it + newBlock.uuid }
4. requestEditBlock(newBlock.uuid)    ← keyboard focus (immediate)
5. writeBlock(newBlock)               ← awaited (suspending)
   └─ writeActor.saveBlock(block)
6. pendingNewBlockUuids.update { it - newBlock.uuid }
7. queueDiskSave(pageUuid)           ← 300ms debounce start
```

User sees keyboard focus immediately (step 4), but step 5 blocks the coroutine until the DB write completes.

### `splitBlock(blockUuid, cursorPosition)` — Enter mid-block

```
blockRepository.splitBlock(blockUuid, cursorPosition)
  → direct call (NOT through writeActor)
  → relies on repository's own withContext(DB) internally
queueDiskSave(pageUuid)
```

Note: `splitBlock` and other structural ops (`indentBlock`, `outdentBlock`, `mergeBlock`, `handleBackspace`) call `blockRepository.*` **directly**, not via `writeActor`. This means they bypass the priority queue and could contend with bulk load writes.

### `insertLinkAtCursor(blockUuid, pageName, ...)` — page reference

```
blockRepository.getBlockByUuid(blockUuid).first()
updateBlockContent(blockUuid, newContent, newVersion)
  └─ applyContentChange(...)
       ├─ dirtyBlocks[blockUuid] = version
       ├─ writeContentOnly(blockUuid, content)
       │    └─ writeActor.updateBlockContentOnly(blockUuid, content)
       └─ queueDiskSave(pageUuid)
```

---

## 4. Existing Benchmark Infrastructure

### Benchmark files

| File | Type | What it measures |
|---|---|---|
| `jvmTest/benchmark/GraphLoadTimingTest.kt` | JVM test | Graph load TTI (phases 1–3), write latency under concurrent load |
| `jvmTest/benchmark/RepositoryBenchmark.kt` | JVM helper | Basic CRUD latency (block/page save, query) |
| `jvmTest/benchmark/RepositoryBenchmarkTest.kt` | JVM test | Framework structure only (no real perf assertions) |
| `jvmTest/benchmark/RepositoryBenchmarkRunnerTest.kt` | JVM test | Runs RepositoryBenchmark with real repos |
| `androidInstrumentedTest/benchmark/AndroidGraphBenchmark.kt` | Android instrumented | Real-device graph load via DatabaseWriteActor |
| `jvmMain/benchmarks/AhoCorasickBenchmark.kt` | JMH benchmark | Search pattern matching |
| `jvmMain/benchmarks/MarkdownEngineBenchmark.kt` | JMH benchmark | Markdown parse throughput |
| `jvmMain/benchmarks/SearchBenchmark.kt` | JMH benchmark | Full-text search |

### GraphLoadTimingTest — key patterns to reuse

`GraphLoadTimingTest` is the most relevant prior art. It already:
- Creates a temp directory + SQLite DB using `RepositoryFactoryImpl` + `DriverFactory()`
- Builds a `GraphLoader` with `externalWriteActor`
- Measures `actor.saveBlocks(listOf(block))` latency with `measureTime { }`
- Reports p50/p95/p99/max using sorted-list percentile
- Writes JSON results to `build/reports/benchmark-*.json`
- Is included in the `jvmTestProfile` Gradle task (with JFR recording)

The `runWriteLatencyBenchmark` function in `GraphLoadTimingTest` is the closest template for a block-insert benchmark.

### Gradle tasks

```
./gradlew :kmp:jvmTest                     # runs all jvmTest tests including benchmarks
./gradlew :kmp:jvmTestProfile              # runs GraphLoadTimingTest with JFR + async-profiler
  -PgraphPath=/path                        # include real-graph test
  -PbenchConfig=TINY|SMALL|MEDIUM|LARGE    # graph size preset (default: XLARGE for profile task)

./gradlew :kmp:ciCheck                     # detekt + jvmTest + Android unit tests + assembleDebug
```

### SyntheticGraphGenerator

`SyntheticGraphGenerator.kt` (found in `jvmTest/benchmark/`) generates markdown files in a temp dir with configurable:
- `pageCount`, `journalCount`, `linkDensity`
- Presets: TINY (50 pages, 14 journals), SMALL (200/30), MEDIUM, LARGE, MESH

### JSON output convention

Existing benchmarks write to `build/reports/benchmark-load.json` and `benchmark-jank.json`. CI reads these for the benchmark summary PR comment. New insert benchmarks should write to `build/reports/benchmark-insert.json` to follow the same pattern.

### FileSystem abstraction — inject point for SAF latency shim

The `FileSystem` interface (expect `PlatformFileSystem`) is injected into `GraphWriter` and `GraphLoader`. On JVM tests, `PlatformFileSystem()` uses direct `File.*` calls. To simulate Android SAF latency, implement a `LatencyShimFileSystem` that wraps `PlatformFileSystem` and adds a `delay()` to `writeFile()` and `fileExists()` calls.

```kotlin
class LatencyShimFileSystem(
    private val delegate: FileSystem,
    private val writeLatencyMs: Long = 50L,   // typical SAF
    private val existsLatencyMs: Long = 10L,
) : FileSystem by delegate {
    override fun writeFile(path: String, content: String): Boolean {
        Thread.sleep(writeLatencyMs)  // or kotlinx.coroutines.delay in suspend version
        return delegate.writeFile(path, content)
    }
    override fun fileExists(path: String): Boolean {
        Thread.sleep(existsLatencyMs)
        return delegate.fileExists(path)
    }
}
```

This shim can be injected into `GraphWriter` without any `expect/actual` changes.

---

## 5. Platform FileSystem — expect/actual

The `FileSystem` interface has the following `expect` declarations in `commonMain`:

- `PlatformFileSystem` — expect class with JVM and Android actuals
- Key methods affecting write latency:
  - `writeFile(path, content): Boolean` — JVM: `File.writeText()`, Android: SAF `openOutputStream`
  - `fileExists(path): Boolean` — JVM: `File.exists()`, Android: SAF ContentResolver query
  - `readFile(path): String?` — JVM: `File.readText()`, Android: SAF `openInputStream` (with shadow cache)
  - `writeFileBytes(path, bytes): Boolean` — for encrypted files (paranoid mode)

The Android SAF implementation is in:
- `/kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`
- The shadow cache (`ShadowFileCache`) is used for reads but **not for writes** — every write goes through SAF Binder IPC.

---

## 6. Key Architectural Constraints for the Fix

Per `requirements.md` and `CLAUDE.md`:

1. **`DatabaseWriteActor` must be preserved** — do not bypass it
2. **Arrow `Either` error model at all repository boundaries**
3. **`expect/actual` for platform differences** — Android-specific fix goes in `androidMain`
4. **NFR-2: Data integrity** — if async file write, DB is source of truth on crash

The DB write completing = "state committed" for the purpose of the FR-3 benchmark measurement. The file write can be deferred (async) as long as DB is the source of truth.

---

## 7. Root Cause Hypothesis

Based on the code paths above:

**Primary suspect: `GraphWriter.savePageInternal()` running on the same coroutine that awaits `writeActor.saveBlock()` in `BlockStateManager.addBlockToPage()`.**

The sequence for a single block insert on Android:
1. `addBlockToPage` → `writeActor.saveBlock()` → SQLite write (~5–20ms on Android)  
2. Meanwhile, `queueDiskSave` fires after 300ms + 500ms = 800ms debounce minimum
3. `savePageInternal` → `fileSystem.fileExists()` (SAF query ~10ms) + `fileSystem.readFile()` (SAF read ~30ms, safety check) + `fileSystem.writeFile()` (SAF write ~50–200ms)

Total perceived lag from user action to file committed: **~1–1.5 seconds** — matching the reported symptom.

**Secondary suspect: `splitBlock` and structural ops bypassing `writeActor`**, which means they can't be prioritized over bulk background loads and contend on raw SQLite write lock.

---

## 8. Files to Modify for the Fix

| File | Change needed |
|---|---|
| `androidMain/platform/PlatformFileSystem.kt` | Async/batch SAF writes; defer `fileExists` check |
| `db/GraphWriter.kt` | Accept async file write strategy; decouple file write from DB write timing |
| `commonMain/platform/FileSystem.kt` | Add `suspend writeFileAsync()` or write-queue interface if needed |
| `jvmTest/benchmark/` (new file) | `BlockInsertBenchmarkTest.kt` measuring P50/P99 insert latency |

---

## 9. New Benchmark Design (FR-3)

The new `BlockInsertBenchmarkTest` should live in `jvmTest/benchmark/` and:

1. Create a temp dir + SQLite backend (same pattern as `GraphLoadTimingTest`)
2. Inject a `LatencyShimFileSystem` wrapping `PlatformFileSystem`
3. Wire: `BlockStateManager` → `writeActor` → `SqlDelightBlockRepository` + `GraphWriter(fileSystem=shim)`
4. Measure wall-clock from `addBlockToPage()` call start to `writeActor.saveBlock()` completion (DB committed)
5. Measure wall-clock from `queueDiskSave()` to `GraphWriter.savePageInternal()` completion (file committed)
6. Run N=100 iterations, collect latencies, assert P99 ≤ 200ms (FR-5)
7. Write to `build/reports/benchmark-insert.json`

The benchmark runs in `./gradlew jvmTest` without any Android device.
