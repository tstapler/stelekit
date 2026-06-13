# Requirements: Comprehensive User-Session Benchmark

## Problem Statement

The existing benchmark suite covers graph load timing (phases 1–3) and write-latency jank during initial load. It does not cover the actions a user performs after the app is interactive: page navigation, search, rename, block structure edits (indent/outdent/move), or undo/redo. We also have no safety guarantee that benchmarks won't accidentally touch the user's real graph — the current real-graph tests pass the original path directly.

## Goals

1. **Graph isolation** — copy the real graph to a temp directory before any benchmark reads or writes occur. No benchmark may touch the original files.
2. **ViewModel-level session simulation** — drive `StelekitViewModel` through a realistic user session so the full stack (ViewModel → Repository → SQLite) is exercised.
3. **Exhaustive action coverage** — benchmark every feature the ViewModel exposes: navigation, search, typing, rename, block structure changes, undo/redo.
4. **JFR + flamegraph output** — the session benchmark runs under the existing `jvmTestProfile` task so the new actions appear in allocation and CPU flamegraphs.
5. **Per-action timing JSON** — emit a `benchmark-session.json` alongside the existing `benchmark-load.json` and `benchmark-jank.json`, suitable for CI regression tracking.

## Out of Scope

- Android or iOS profiling (separate benchmark pipelines exist)
- UI rendering / Compose frame timing (macrobenchmark covers this)
- Git sync actions
- Export / import
- Image attachment

## Functional Requirements

### FR-1: Graph Copy Safety

Before running any real-graph benchmark:
- The benchmark infrastructure must call `Files.createTempDirectory("stelekit-bench")` and copy the entire graph directory into it with `File.copyRecursively()`.
- The copied path (not the original) is passed to `GraphLoader` and `GraphWriter`.
- The temp directory is deleted in a `finally` block.
- This applies to: `GraphLoadTimingTest` (test 3 — real graph load), `GraphLoadTimingTest` (test 5 — write-latency real graph), and the new `UserSessionBenchmarkTest`.
- The existing `benchmark-local.sh` passes `GRAPH_PATH` to the Gradle task; the test code is responsible for copying, not the script.

### FR-2: UserSessionBenchmarkTest

A new test class `UserSessionBenchmarkTest` in `kmp/src/jvmTest/…/benchmark/` that:

1. **Setup**: copies the real graph to a temp dir, loads it fully (phases 1–3), waits for the write actor to drain, then instantiates `StelekitViewModel` via `StelekitViewModelDependencies`.
2. **Skips if no real graph**: honours `STELEKIT_GRAPH_PATH` system property; prints a skip message if absent.
3. **Runs a scripted user session** (in order):

   | Step | Action | ViewModel method | Repetitions |
   |---|---|---|---|
   | A | Navigate to 20 random pages | `navigateToPageByUuid(uuid)` | 20 |
   | B | Type at human pace (200ms) into current block | `saveBlock` via `BlockStateManager` or `actor.saveBlocks` | 30 keystrokes |
   | C | Search for 10 random 3-word queries drawn from block content | `searchRepository.searchWithFilters(...)` | 10 |
   | D | Rename 5 random pages | `renamePage(page, newName)` on `GraphWriter` + `pageRepository` | 5 |
   | E | Indent 10 random blocks | `viewModel.indentBlock(uuid)` | 10 |
   | F | Outdent 10 random blocks | `viewModel.outdentBlock(uuid)` | 10 |
   | G | Move block up / down (5 each) | `viewModel.moveBlockUp/Down(uuid)` | 10 |
   | H | Undo 5 operations | `viewModel.undo()` | 5 |
   | I | Navigate back through history (5 times) | `viewModel.goBack()` | 5 |
   | J | Search again (5 queries) after rename | `searchRepository.searchWithFilters(...)` | 5 |

4. **Measures**: p50 / p95 / p99 / max latency per action category (A–J) using `measureTime { }`.
5. **Reports**: prints a per-category latency table and emits `benchmark-session.json` to `benchmark.output.dir` (same convention as existing load/jank JSON).
6. **Runs under jvmTestProfile**: the test class must be included in the `jvmTestProfile` task's test filter so its hot paths appear in the flamegraphs.

### FR-3: benchmark-session.json Schema

```json
{
  "gitSha": "abc1234",
  "graphPageCount": 1234,
  "actions": [
    { "action": "navigate_page",   "n": 20, "p50Ms": 4,  "p95Ms": 12, "p99Ms": 25,  "maxMs": 40  },
    { "action": "type_block",      "n": 30, "p50Ms": 2,  "p95Ms":  8, "p99Ms": 15,  "maxMs": 22  },
    { "action": "search",          "n": 15, "p50Ms": 10, "p95Ms": 30, "p99Ms": 60,  "maxMs": 90  },
    { "action": "rename_page",     "n":  5, "p50Ms": 20, "p95Ms": 50, "p99Ms": 80,  "maxMs": 120 },
    { "action": "indent_block",    "n": 10, "p50Ms": 3,  "p95Ms":  8, "p99Ms": 12,  "maxMs": 18  },
    { "action": "outdent_block",   "n": 10, "p50Ms": 3,  "p95Ms":  8, "p99Ms": 12,  "maxMs": 18  },
    { "action": "move_block",      "n": 10, "p50Ms": 4,  "p95Ms": 10, "p99Ms": 16,  "maxMs": 25  },
    { "action": "undo",            "n":  5, "p50Ms": 2,  "p95Ms":  5, "p99Ms":  8,  "maxMs": 12  },
    { "action": "navigate_back",   "n":  5, "p50Ms": 3,  "p95Ms":  9, "p99Ms": 14,  "maxMs": 20  }
  ]
}
```

### FR-4: Retrofit Graph Copy into Existing Real-Graph Tests

`GraphLoadTimingTest` tests 3 and 5 must be updated to copy the graph before use, without changing any other behaviour or breaking tests 1, 2, 4.

### FR-5: benchmark-local.sh Unchanged Interface

The script signature and output file list do not change. The copy is an implementation detail inside the Kotlin tests.

## Non-Functional Requirements

- **NFR-1 Isolation**: no test in this suite may write to `STELEKIT_GRAPH_PATH`. Any accidental write to the original path is a blocking bug.
- **NFR-2 Determinism**: random page / block selection uses a seeded `Random(seed = 42)` so results are reproducible across runs on the same graph.
- **NFR-3 CI compatibility**: all new tests are tagged with `@Test` and included in the `jvmTest` source set. They self-skip when `STELEKIT_GRAPH_PATH` is unset.
- **NFR-4 No new dependencies**: uses only existing Kotlin stdlib, JDK, and project classes. No additional profiling libraries.
- **NFR-5 Flamegraph coverage**: the `jvmTestProfile` task must include `UserSessionBenchmarkTest` in its test class filter so that navigation, search, and rename hot paths appear in the CPU and allocation flamegraphs.

### FR-6: Span Capture During Session Benchmark

The `UserSessionBenchmarkTest` must enable and drain the app's built-in OpenTelemetry span pipeline so that all spans emitted by `StelekitViewModel`, `GraphLoader`, and the repository layer during the session are captured and written to disk.

Implementation:
1. Create a `RingBufferSpanExporter(capacity = 10_000)` and set `.enabled = true` before the session starts.
2. Pass it to `StelekitViewModelDependencies.ringBuffer` so `SpanEmitter` inside the ViewModel writes to it.
3. Also wire it as the `histogramWriter` ring buffer so histogram events appear alongside spans.
4. After the session completes, call `ringBuffer.drain()` to retrieve all `SerializedSpan` objects.
5. Write them to `benchmark-session-spans.json` in `benchmark.output.dir`, as a JSON array of `SerializedSpan` (same schema the app uses internally).
6. Also drain `QueryStatsCollector.drainNow()` and append the per-table SQL stats to `benchmark-query-stats.json` (same file already written by the load benchmark — append action-phase stats as a separate key `"sessionQueryStats"`).

This allows post-hoc analysis of which internal spans fired during navigation, search, rename, and typing — e.g., how long `db.page.select` takes vs `db.block.select` vs `fts.search` across action types.

## Acceptance Criteria

1. Running `./scripts/benchmark-local.sh ~/Documents/personal-wiki/logseq` produces all existing output files PLUS `benchmark-session.json` and `benchmark-session-spans.json`.
2. The original graph at `~/Documents/personal-wiki/logseq` is unchanged (verified by `diff -r` before and after).
3. The session benchmark flamegraph shows call stacks for `navigateToPageByUuid`, `searchWithFilters`, and `renamePage`.
4. Per-action latency table is printed to stdout with p50/p95/p99/max columns.
5. `benchmark-session-spans.json` is non-empty and contains span names from the ViewModel/repository layer (e.g., navigation, search, write spans).
6. All existing tests in `GraphLoadJankTest` and `GraphLoadTimingTest` still pass.
