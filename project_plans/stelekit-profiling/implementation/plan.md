# Implementation Plan: stelekit-profiling

**Feature**: Comprehensive user-session benchmark with graph isolation, span capture, and per-action latency reporting
**Date**: 2026-05-29
**Status**: Ready for implementation
**ADRs**: None (all technology choices are existing project infrastructure)

---

## Dependency Visualization

```
Phase 1: Graph Isolation Safety
  Task 1.1.1a: Create BenchmarkGraphUtils.kt (copyGraphToTempDir)
        │
        ├──> Task 1.1.2a: Retrofit GraphLoadTimingTest test 3 (real graph load)
        └──> Task 1.1.2b: Retrofit GraphLoadTimingTest test 5 (write-latency real graph)

Phase 2: UserSessionBenchmarkTest
  [depends on Phase 1 completing BenchmarkGraphUtils.kt]
  Task 2.1.1a: ViewModel setup (InMemorySettings, ringBuffer, loadGraph + await)
        │
        ├──> Task 2.2.1a: Action A — navigate 20 pages (await uiState)
        ├──> Task 2.2.2a: Action B — type 30 blocks (addNewBlock/addBlockToPage)
        ├──> Task 2.2.3a: Action C — search 10 queries (searchPages.first())
        ├──> Task 2.2.4a: Action D — rename 5 pages (BacklinkRenamer via renamePage)
        ├──> Task 2.2.5a: Actions E-G — indent/outdent/moveBlockUp/Down (await uiState)
        ├──> Task 2.2.6a: Actions H — undo 5x
        ├──> Task 2.2.7a: Action I — goBack 5x
        └──> Task 2.2.8a: Action J — search 5 queries post-rename

Phase 3: Span & Query Stats Output
  [depends on Phase 2 ViewModel setup task]
  Task 3.1.1a: Enable RingBufferSpanExporter, drain to benchmark-session-spans.json
  Task 3.1.2a: Drain QueryStatsCollector, write benchmark-session-query-stats.json

Phase 4: Latency Reporting
  [depends on Phase 2 actions complete]
  Task 4.1.1a: Per-action timing collector (measureTime, p50/p95/p99/max)
  Task 4.1.2a: Emit benchmark-session.json + print latency table to stdout

Phase 5: Gradle Integration
  [independent]
  Task 5.1.1a: Add UserSessionBenchmarkTest to jvmTestProfile filter
```

---

## Phase 1: Graph Isolation Safety

### Epic 1.1: Graph Copy Infrastructure

**Goal**: Ensure no benchmark test ever reads or writes to the original graph path. All real-graph tests receive a copy in a temp directory that is cleaned up in `finally`.

#### Story 1.1.1: Shared copy-graph utility

**As a** benchmark author, **I want** a shared helper that copies a graph to a temp directory, **so that** both existing and new tests can use it without duplicating the logic.

**Acceptance Criteria**:
- `BenchmarkGraphUtils.copyGraphToTempDir(sourcePath, prefix)` creates a temp dir, copies recursively, and registers `deleteOnExit()`
- Function is importable from both `GraphLoadTimingTest` and `UserSessionBenchmarkTest`
- Existing helpers (`tempDir`, `writeJson`) are relocated to this file or re-exported from it

**Files**:
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/BenchmarkGraphUtils.kt` (new)

##### Task 1.1.1a: Create BenchmarkGraphUtils.kt (~3 min)
- Create `object BenchmarkGraphUtils` in `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/BenchmarkGraphUtils.kt`
- Add `fun copyGraphToTempDir(sourcePath: String, prefix: String = "stelekit-bench"): File`:
  ```kotlin
  val dest = Files.createTempDirectory(prefix).toFile()
  File(sourcePath).copyRecursively(dest, overwrite = true)
  dest.deleteOnExit()
  return dest
  ```
- Add `fun tempDir(prefix: String = "stelekit-bench"): File` (extracted from GraphLoadTimingTest)
- Add `fun writeJson(file: File, data: Map<String, Any>)` (extracted from GraphLoadTimingTest)
- Files: `BenchmarkGraphUtils.kt`

#### Story 1.1.2: Retrofit existing real-graph tests

**As a** benchmark runner, **I want** the existing GraphLoadTimingTest real-graph tests (3 and 5) to operate on copies, **so that** no accidental writes reach the original graph.

**Acceptance Criteria**:
- Test 3 (real graph load) copies graph to temp dir before `GraphLoader.loadGraph()`; original path is never opened for write
- Test 5 (write-latency real graph) copies graph before any writes occur; temp dir deleted in `finally`
- Tests 1, 2, 4 are unchanged
- All 5 tests still pass

**Files**:
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/GraphLoadTimingTest.kt`

##### Task 1.1.2a: Retrofit test 3 — real graph load (~3 min)
- Find the test annotated with the real-graph skip guard (checks `STELEKIT_GRAPH_PATH`)
- Replace `val graphDir = File(graphPath)` with:
  ```kotlin
  val tempDir = BenchmarkGraphUtils.copyGraphToTempDir(graphPath)
  try {
      // use tempDir instead of graphDir
  } finally {
      tempDir.deleteRecursively()
  }
  ```
- Pass `tempDir.absolutePath` to `GraphLoader` instead of `graphPath`
- Files: `GraphLoadTimingTest.kt`

##### Task 1.1.2b: Retrofit test 5 — write-latency real graph (~3 min)
- Same pattern as task 1.1.2a: wrap real-graph block in `try/finally` with `BenchmarkGraphUtils.copyGraphToTempDir`
- Ensure `GraphWriter` is constructed with `graphPath = tempDir.absolutePath`
- Files: `GraphLoadTimingTest.kt`

---

## Phase 2: UserSessionBenchmarkTest

### Epic 2.1: ViewModel Setup

**Goal**: Instantiate `StelekitViewModel` in a headless JVM test with full SQLite backend, span capture enabled, and graph pre-loaded — without triggering auto-load from persisted settings.

#### Story 2.1.1: Headless ViewModel construction and graph load

**As a** benchmark test, **I want** to create a fully-wired `StelekitViewModel` against a temp-dir copy of the real graph, **so that** all benchmark actions exercise the real stack.

**Acceptance Criteria**:
- Test skips (prints message, returns) when `System.getProperty("STELEKIT_GRAPH_PATH")` is null
- `RingBufferSpanExporter(capacity = 10_000)` is created with `.enabled = true` before ViewModel construction
- `InMemorySettings` returns `""` for `lastGraphPath` and `false` for `onboardingCompleted` to suppress auto-load
- `StelekitViewModelDependencies` is constructed with `writeActor = repoSet.writeActor` (required for rename)
- `viewModel.loadGraph(tempDir.absolutePath)` is called manually after construction
- `delay(100)` waits for `observeSpecialPages()` and `updateCommands()` to settle
- `viewModel.uiState.first { it.currentGraphPath.isNotEmpty() }` confirms graph is loaded before first benchmark action
- `viewModel.close()` + `scope.cancel()` + `tempDir.deleteRecursively()` in `finally`

**Files**:
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/UserSessionBenchmarkTest.kt` (new)
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/BenchmarkGraphUtils.kt`

##### Task 2.1.1a: Scaffold UserSessionBenchmarkTest with ViewModel setup (~5 min)
- Create `class UserSessionBenchmarkTest` with `@Test fun runUserSession()`
- Add skip guard: `val graphPath = System.getProperty("STELEKIT_GRAPH_PATH") ?: run { println("STELEKIT_GRAPH_PATH not set — skipping UserSessionBenchmarkTest"); return }`
- Wire `CoroutineScope(SupervisorJob() + Dispatchers.Default)` as `scope`
- Create temp dir via `BenchmarkGraphUtils.copyGraphToTempDir(graphPath)`
- Create `RepositoryFactoryImpl` + `repoSet` (SQLDelight backend, same pattern as GraphLoadTimingTest)
- Create `RingBufferSpanExporter(capacity = 10_000).also { it.enabled = true }` as `ringBuffer`
- Create `GraphLoader(PlatformFileSystem(), repoSet.pageRepository, repoSet.blockRepository, externalWriteActor = repoSet.writeActor, histogramWriter = repoSet.histogramWriter)`
- Create `GraphWriter(PlatformFileSystem(), repoSet.writeActor, graphPath = tempDir.absolutePath)`
- Create `InMemorySettings` stub (inner class returning `""` / `false` for all keys)
- Construct `StelekitViewModelDependencies(pageRepository, blockRepository, searchRepository, graphLoader, graphWriter, PlatformFileSystem(), InMemorySettings(), scope, writeActor = repoSet.writeActor, histogramWriter, ringBuffer = ringBuffer)`
- Construct `StelekitViewModel(deps)`
- Call `viewModel.loadGraph(tempDir.absolutePath)` then `delay(100)`
- Await `viewModel.uiState.first { it.currentGraphPath.isNotEmpty() }` with `runBlocking`
- Wrap everything in `try { ... } finally { viewModel.close(); scope.cancel(); tempDir.deleteRecursively() }`
- Files: `UserSessionBenchmarkTest.kt`, `BenchmarkGraphUtils.kt`

##### Task 2.1.1b: Add InMemorySettings stub class (~2 min)
- Add `private class InMemorySettings : Settings` inside `UserSessionBenchmarkTest` (or in `BenchmarkGraphUtils.kt` as internal)
- Implement all `Settings` methods; override `getString` to return `defaultValue`, `getBoolean` to return `defaultValue`
- No mutable map needed — all reads return the default, writes are no-ops
- Files: `UserSessionBenchmarkTest.kt`

### Epic 2.2: Benchmark Actions A–J

**Goal**: Drive `StelekitViewModel` through the 10 action categories defined in FR-2, measuring wall-clock latency per invocation using `measureTime { }` and a proper await pattern.

#### Story 2.2.1: Action A — Navigate 20 random pages

**As a** benchmark, **I want** to navigate to 20 random pages and measure wall-clock latency per navigation, **so that** navigation DB read time is captured accurately.

**Acceptance Criteria**:
- 20 page UUIDs sampled from `viewModel.uiState.value` using `Random(seed = 42)`
- Each navigation: `viewModel.navigateToPageByUuid(uuid)` followed by `viewModel.uiState.first { it.currentScreen is Screen.PageView && (it.currentScreen as Screen.PageView).page.uuid == uuid }`
- Wall-clock time measured from before `navigateToPageByUuid` call to after `uiState.first` returns
- All 20 samples stored for p50/p95/p99/max computation

**Files**: `UserSessionBenchmarkTest.kt`

##### Task 2.2.1a: Implement Action A navigation loop (~4 min)
- After ViewModel is loaded, collect `val allPages = viewModel.uiState.value.pages.shuffled(Random(42)).take(20)`; fallback to `cachedAllPages` if `pages` is empty by awaiting a non-empty state
- Loop 20 times: `val elapsed = measureTime { viewModel.navigateToPageByUuid(uuid); runBlocking { viewModel.uiState.first { ... } } }`
- Accumulate `navigateSamples: MutableList<Long>` (ms)
- Files: `UserSessionBenchmarkTest.kt`

#### Story 2.2.2: Action B — Type 30 blocks at human pace

**As a** benchmark, **I want** to add 30 new blocks at 200ms intervals to simulate typing, **so that** the write path under realistic pacing is profiled.

**Acceptance Criteria**:
- Uses `viewModel.addBlockToPage(pageUuid)` on the currently open page (from uiState after Action A)
- 200ms `delay` between each of the 30 calls
- Wall-clock per block measured individually

**Files**: `UserSessionBenchmarkTest.kt`

##### Task 2.2.2a: Implement Action B typing loop (~3 min)
- Get `val currentPageUuid = (viewModel.uiState.value.currentScreen as? Screen.PageView)?.page?.uuid ?: allPages.first().uuid`
- Loop 30 times: `val elapsed = measureTime { viewModel.addBlockToPage(currentPageUuid); delay(200) }`
- Accumulate `typeSamples: MutableList<Long>`
- Files: `UserSessionBenchmarkTest.kt`

#### Story 2.2.3: Action C — Search 10 random queries

**As a** benchmark, **I want** to issue 10 search queries drawn from block content and await the first result, **so that** FTS5 search latency is captured.

**Acceptance Criteria**:
- 10 queries of ≥3 characters, drawn from page titles using `Random(seed = 42)` (take first 4 chars of random page titles)
- Each query uses `viewModel.searchPages(query).first()` collected with `runBlocking`
- Wall-clock measured from before `searchPages` to after `.first()` returns

**Files**: `UserSessionBenchmarkTest.kt`

##### Task 2.2.3a: Implement Action C search loop (~3 min)
- Build `val queries = allPages.shuffled(Random(42)).take(10).map { it.name.take(4).trim() }.filter { it.length >= 2 }`; pad to 10 with fallback queries `["the", "is", "of", "and", "in"]` if needed
- Loop: `val elapsed = measureTime { runBlocking { viewModel.searchPages(query).first() } }`
- Accumulate `searchSamplesC: MutableList<Long>`
- Files: `UserSessionBenchmarkTest.kt`

#### Story 2.2.4: Action D — Rename 5 pages

**As a** benchmark, **I want** to rename 5 random pages via `viewModel.renamePage`, **so that** the `BacklinkRenamer` + disk write path is profiled.

**Acceptance Criteria**:
- 5 pages sampled with `Random(seed = 42)` (different slice from Action A)
- Each rename: `viewModel.renamePage(page, "bench-renamed-${i}-${page.name}")`
- Await uiState update confirming the rename (page name changed) OR use `delay(500)` as fallback
- `writeActor` is non-null in deps (asserted in task 2.1.1a)

**Files**: `UserSessionBenchmarkTest.kt`

##### Task 2.2.4a: Implement Action D rename loop (~4 min)
- `val renamePages = allPages.shuffled(Random(43)).take(5)`
- Loop: record `startMs`, call `viewModel.renamePage(page, "bench-${i}")`, await `viewModel.uiState.first { state -> state.pages.any { it.uuid == page.uuid && it.name.startsWith("bench-${i}") } }` with a 2s timeout fallback using `withTimeoutOrNull(2000) { ... } ?: delay(500)`
- Accumulate `renameSamples: MutableList<Long>`
- Files: `UserSessionBenchmarkTest.kt`

#### Story 2.2.5: Actions E-G — Indent, outdent, move blocks

**As a** benchmark, **I want** to indent 10, outdent 10, and move up/down 5+5 blocks that are loaded in the current page's uiState, **so that** the direct block write paths are profiled.

**Acceptance Criteria**:
- Blocks are taken from `uiState.value.currentScreen` (PageView.blocks), ensuring they are visible and have siblings/parents
- Each action: call the ViewModel method then `delay(50)` to let the coroutine complete
- Latency measured with `measureTime { viewModel.indentBlock(uuid); delay(50) }` etc.

**Files**: `UserSessionBenchmarkTest.kt`

##### Task 2.2.5a: Navigate to a page with enough blocks, collect block UUIDs (~2 min)
- After Action D, navigate to a page known to have ≥ 20 blocks (pick page with most blocks from allPages if block count is tracked, else pick first page and verify)
- Await PageView state: `viewModel.uiState.first { it.currentScreen is Screen.PageView }`
- Extract `val pageBlocks = (viewModel.uiState.value.currentScreen as Screen.PageView).blocks.map { it.uuid }`
- Files: `UserSessionBenchmarkTest.kt`

##### Task 2.2.5b: Implement Actions E (indent) and F (outdent) loops (~3 min)
- Loop 10 times for indent: `val elapsed = measureTime { viewModel.indentBlock(pageBlocks[i]); runBlocking { delay(50) } }`
- Loop 10 times for outdent: same pattern with `viewModel.outdentBlock`
- Accumulate `indentSamples`, `outdentSamples`
- Files: `UserSessionBenchmarkTest.kt`

##### Task 2.2.5c: Implement Action G (moveBlockUp/Down) loop (~2 min)
- Loop 5 times for `viewModel.moveBlockUp(uuid)` and 5 times for `viewModel.moveBlockDown(uuid)` with `delay(50)`
- Accumulate `moveSamples: MutableList<Long>` (combined)
- Files: `UserSessionBenchmarkTest.kt`

#### Story 2.2.6: Action H — Undo 5 operations

**As a** benchmark, **I want** to call `viewModel.undo()` 5 times, **so that** the undo path is exercised (even if undoManager is null, the call is measured as a no-op baseline).

**Acceptance Criteria**:
- 5 calls to `viewModel.undo()` with `delay(50)` each
- Samples recorded regardless of whether an actual undo occurs

**Files**: `UserSessionBenchmarkTest.kt`

##### Task 2.2.6a: Implement Action H undo loop (~2 min)
- Loop 5: `val elapsed = measureTime { viewModel.undo(); runBlocking { delay(50) } }`
- Accumulate `undoSamples: MutableList<Long>`
- Files: `UserSessionBenchmarkTest.kt`

#### Story 2.2.7: Action I — Navigate back 5 times

**As a** benchmark, **I want** to call `viewModel.goBack()` 5 times, **so that** navigation history traversal is measured.

**Acceptance Criteria**:
- 5 calls to `viewModel.goBack()` with `delay(20)` each (pure state mutation, no DB round-trip)

**Files**: `UserSessionBenchmarkTest.kt`

##### Task 2.2.7a: Implement Action I goBack loop (~2 min)
- Loop 5: `val elapsed = measureTime { viewModel.goBack(); runBlocking { delay(20) } }`
- Accumulate `backSamples: MutableList<Long>`
- Files: `UserSessionBenchmarkTest.kt`

#### Story 2.2.8: Action J — Search 5 post-rename queries

**As a** benchmark, **I want** to run 5 additional search queries after rename, **so that** the FTS index state after writes is captured.

**Acceptance Criteria**:
- 5 queries using same pattern as Action C but with `Random(seed = 44)`
- Results appended to `searchSamplesC` (combined search latency, `n=15` in JSON)

**Files**: `UserSessionBenchmarkTest.kt`

##### Task 2.2.8a: Implement Action J post-rename search (~2 min)
- Reuse search loop from Action C task with 5 iterations and `Random(44)` seed
- Append to `searchSamplesC` (total becomes 15)
- Files: `UserSessionBenchmarkTest.kt`

---

## Phase 3: Span and Query Stats Output

### Epic 3.1: Telemetry Drain and JSON Write

**Goal**: After the session completes, drain both the span ring buffer and the query stats collector and write them to the benchmark output directory.

#### Story 3.1.1: Write benchmark-session-spans.json

**As a** benchmark runner, **I want** all spans captured during the session written to `benchmark-session-spans.json`, **so that** per-span timing is available for post-hoc analysis.

**Acceptance Criteria**:
- `ringBuffer.drain()` called after all actions complete, before teardown
- Result serialized with `Json { prettyPrint = true }.encodeToString(spans)`
- Written to `File(outputDir, "benchmark-session-spans.json")`
- File is non-empty when session runs on a real graph

**Files**: `UserSessionBenchmarkTest.kt`

##### Task 3.1.1a: Drain ring buffer and write spans JSON (~3 min)
- `val outputDir = File(System.getProperty("benchmark.output.dir") ?: "build/reports")`
- `val spans: List<SerializedSpan> = ringBuffer.drain()`
- `File(outputDir, "benchmark-session-spans.json").writeText(Json { prettyPrint = true }.encodeToString(spans))`
- Print span count to stdout: `println("Captured ${spans.size} spans")`
- Files: `UserSessionBenchmarkTest.kt`

#### Story 3.1.2: Write benchmark-session-query-stats.json

**As a** benchmark runner, **I want** SQL query stats drained and written to a JSON file, **so that** per-table call counts and latencies are recorded alongside the session.

**Acceptance Criteria**:
- `repoSet.queryStatsCollector?.drainNow()` called after session actions
- Stats fetched from `repoSet.queryStatsRepository?.getTopByTotalMs("unknown", 20)` (same pattern as load benchmark)
- Written to `File(outputDir, "benchmark-session-query-stats.json")` as a JSON map

**Files**: `UserSessionBenchmarkTest.kt`

##### Task 3.1.2a: Drain query stats and write JSON (~3 min)
- `repoSet.queryStatsCollector?.drainNow()`
- `val queryStats = runBlocking { repoSet.queryStatsRepository?.getTopByTotalMs("unknown", 20)?.getOrNull() ?: emptyList() }`
- Use `writeJson` helper from `BenchmarkGraphUtils` to write `mapOf("sessionQueryStats" to queryStats.map { ... })` to `benchmark-session-query-stats.json`
- Files: `UserSessionBenchmarkTest.kt`

---

## Phase 4: Latency Reporting

### Epic 4.1: Per-Action Statistics and Output

**Goal**: Compute p50/p95/p99/max per action category from collected samples and emit them as both a stdout table and `benchmark-session.json`.

#### Story 4.1.1: Latency statistics computation

**As a** benchmark, **I want** to compute p50/p95/p99/max from a list of millisecond samples, **so that** the JSON and table outputs are accurate.

**Acceptance Criteria**:
- `percentile(samples, p)` function computes the Pth percentile from a sorted list
- Used for all 9 action categories (A–I, with J merged into C)

**Files**: `UserSessionBenchmarkTest.kt` or `BenchmarkGraphUtils.kt`

##### Task 4.1.1a: Add percentile helper function (~2 min)
- Add `fun percentile(samples: List<Long>, p: Int): Long` to `BenchmarkGraphUtils`:
  ```kotlin
  val sorted = samples.sorted()
  val idx = ((p / 100.0) * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.lastIndex)
  return sorted[idx]
  ```
- Files: `BenchmarkGraphUtils.kt`

#### Story 4.1.2: JSON and stdout output

**As a** benchmark runner, **I want** a `benchmark-session.json` file and a stdout table matching the FR-3 schema, **so that** CI can track regressions per action.

**Acceptance Criteria**:
- JSON matches FR-3 schema exactly: `gitSha`, `graphPageCount`, `actions` array
- Each action entry: `action` (string key from table), `n`, `p50Ms`, `p95Ms`, `p99Ms`, `maxMs`
- Stdout table has aligned columns with headers: `Action | n | p50 | p95 | p99 | max`
- Written to `File(outputDir, "benchmark-session.json")`

**Files**: `UserSessionBenchmarkTest.kt`

##### Task 4.1.2a: Build action stats list and write benchmark-session.json (~4 min)
- Compute git SHA: `ProcessBuilder("git", "rev-parse", "--short", "HEAD").start().inputStream.bufferedReader().readLine() ?: "unknown"`
- Build stats for each action category using `percentile()` helper
- Construct JSON string manually or via `kotlinx.serialization` `buildJsonObject` / `Json.encodeToString`
- Write to `File(outputDir, "benchmark-session.json")`
- Print table to stdout with `println` using `String.format("%-20s %5d %6d %6d %6d %6d", ...)`
- Files: `UserSessionBenchmarkTest.kt`

---

## Phase 5: Gradle Integration

### Epic 5.1: jvmTestProfile Task Filter

**Goal**: Add `UserSessionBenchmarkTest` to the `jvmTestProfile` Gradle task so its hot paths appear in JFR and async-profiler flamegraphs.

#### Story 5.1.1: Register new test class in profiling filter

**As a** build engineer, **I want** `UserSessionBenchmarkTest` to run under `jvmTestProfile`, **so that** navigation, search, rename, and write paths appear in the CPU and allocation flamegraphs.

**Acceptance Criteria**:
- `filter { includeTestsMatching("dev.stapler.stelekit.benchmark.UserSessionBenchmarkTest") }` added to `jvmTestProfile` task
- Existing `GraphLoadTimingTest` filter line is unchanged
- Running `./gradlew :kmp:jvmTestProfile -PgraphPath=...` executes both test classes

**Files**: `kmp/build.gradle.kts`

##### Task 5.1.1a: Add includeTestsMatching line to jvmTestProfile (~2 min)
- Open `kmp/build.gradle.kts`, find the `jvmTestProfile` task's `filter` block (around line 501 per stack research)
- Add `includeTestsMatching("dev.stapler.stelekit.benchmark.UserSessionBenchmarkTest")` after the existing `GraphLoadTimingTest` line
- Files: `kmp/build.gradle.kts`

---

## Full Task Index

| ID | Task | Epic | Est. |
|----|------|------|------|
| 1.1.1a | Create BenchmarkGraphUtils.kt | Graph Copy Infra | 3 min |
| 1.1.2a | Retrofit GraphLoadTimingTest test 3 | Graph Copy Infra | 3 min |
| 1.1.2b | Retrofit GraphLoadTimingTest test 5 | Graph Copy Infra | 3 min |
| 2.1.1a | Scaffold UserSessionBenchmarkTest ViewModel setup | ViewModel Setup | 5 min |
| 2.1.1b | Add InMemorySettings stub | ViewModel Setup | 2 min |
| 2.2.1a | Action A — navigate 20 pages | Session Actions | 4 min |
| 2.2.2a | Action B — type 30 blocks | Session Actions | 3 min |
| 2.2.3a | Action C — search 10 queries | Session Actions | 3 min |
| 2.2.4a | Action D — rename 5 pages | Session Actions | 4 min |
| 2.2.5a | Block collection setup for E-G | Session Actions | 2 min |
| 2.2.5b | Actions E (indent) and F (outdent) | Session Actions | 3 min |
| 2.2.5c | Action G (moveBlockUp/Down) | Session Actions | 2 min |
| 2.2.6a | Action H — undo 5x | Session Actions | 2 min |
| 2.2.7a | Action I — goBack 5x | Session Actions | 2 min |
| 2.2.8a | Action J — post-rename search | Session Actions | 2 min |
| 3.1.1a | Drain ring buffer, write spans JSON | Telemetry | 3 min |
| 3.1.2a | Drain query stats, write query stats JSON | Telemetry | 3 min |
| 4.1.1a | Add percentile helper function | Latency Reporting | 2 min |
| 4.1.2a | Build action stats and write benchmark-session.json | Latency Reporting | 4 min |
| 5.1.1a | Add UserSessionBenchmarkTest to jvmTestProfile filter | Gradle | 2 min |

**Totals: 4 Epics, 13 Stories, 20 Tasks**

---

## Key Implementation Notes

### Navigation timing (FR-2 Step A)

`navigateToPageByUuid` is fire-and-forget. Do NOT use `delay(50)` alone — use `uiState.first { }` to await the actual DB completion:

```kotlin
val startMs = System.currentTimeMillis()
viewModel.navigateToPageByUuid(uuid)
runBlocking {
    viewModel.uiState.first { state ->
        state.currentScreen is Screen.PageView &&
        (state.currentScreen as Screen.PageView).page.uuid == uuid
    }
}
val endMs = System.currentTimeMillis()
```

### Rename preconditions (FR-2 Step D)

`viewModel.renamePage` requires:
1. `writeActor = repoSet.writeActor` passed in `StelekitViewModelDependencies` (non-null, else NPE on first rename)
2. `graphWriter` constructed with `graphPath = tempDir.absolutePath`
3. `viewModel.uiState.value.currentGraphPath` equals `tempDir.absolutePath` (set by `loadGraph`)

### Span capture ordering

`ringBuffer.enabled = true` must be set **before** `StelekitViewModel(deps)` is constructed — the `SpanEmitter` is created in the ViewModel constructor and captures the `ringBuffer` reference at that point. Setting `enabled` after construction still works because `enabled` is checked per-call, but the ring buffer itself must exist at construction.

### Teardown order

```kotlin
finally {
    ringBuffer.clear()       // optional; drain already empties it
    viewModel.close()        // cancels ViewModel scope, stops midnight watcher
    scope.cancel()           // cancels repoSet scope
    tempDir.deleteRecursively()
}
```

`viewModel.close()` before `scope.cancel()` ensures the ViewModel's internal jobs are cancelled cleanly before the repositories they hold are torn down.

### Seeded randomness

All random selection uses `Random(seed = 42)` (or 43, 44 for different slices) to guarantee reproducibility. Different seeds for different action categories avoid selecting the same page for navigation, rename, and search.
