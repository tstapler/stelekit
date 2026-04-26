# Research Synthesis: Android Save & Parse Performance

## Decision Required

Choose the bottleneck diagnosis strategy and fix sequence for Android page save/parse latency that is currently several seconds but should be < 500ms per save and < 3s Phase 1 TTI.

## Context

JVM benchmarks show 22ms total for a 200-page graph (9ms Phase 1 TTI, 25ms write p95). On Android the user reports several-second latency for saving and parsing pages. No Android-specific benchmarks exist. The observability branch (`stelekit-more-more-performance`) just landed `TimingDriverWrapper`, `db.queue_wait` spans, and `PerfExportReport` — tooling that can diagnose the bottleneck without writing new code.

## Options Considered

| Option | Summary | Key Trade-off |
|--------|---------|---------------|
| SAF shadow-copy (copy graph to `filesDir`) | Eliminate SAF IPC overhead for all file reads/writes | High impact if SAF is the bottleneck; wasted effort if internal storage is the default |
| Read-before-write removal | Remove redundant file read in `savePageInternal` safety check for autosave path | Low risk, ~10 lines; always applicable |
| WAL PRAGMA tuning | Add `wal_autocheckpoint=4000`, `temp_store=MEMORY`, `cache_size=-8000` | 3 lines in `DriverFactory.android.kt`; addresses checkpoint spikes and temp I/O |
| Actor chunk write decomposition | Replace `Execute`-wrapped 10-page chunk with typed actor calls | Eliminates HIGH-priority wait behind LOW `Execute`; medium complexity, medium risk |
| Microbenchmark CI gate | Add Android instrumented benchmark to gate future regressions | Required to validate any fix; no fix is verified without it |

## Dominant Trade-off

**Diagnose before optimizing.** The several-second latency could be caused by (A) SAF IPC overhead, (B) read-before-write file I/O, (C) `DatabaseWriteActor` contention, or (D) WAL checkpoint stalls — and these require different fixes. The observability branch provides exactly the tooling needed to attribute latency without guessing. However, two OTel spans are missing that are required to diagnose the autosave path: a `file.readCheck` span and `db.queue_wait` for HIGH-priority typed requests.

One fix is safe regardless of diagnosis: removing the read-before-write safety check on the autosave path (~10 lines). Everything else should wait for a session export from a real Android device.

## Recommendation

**Choose**: Instrument first, then fix in order of confirmed impact.

**Sequence**:

### Step 1: Add two missing OTel spans (~15 lines total, zero risk)

1. Add `"file.readCheck"` span around the `fileSystem.readFile` call in `savePageInternal` (lines 181–192 in `GraphWriter.kt`). This is the only way to measure Suspect A (read-before-write cost) in a session export.
2. Add `db.queue_wait` recording to `SavePage` and `SaveBlocks` typed requests in `DatabaseWriteActor` (same pattern as the existing `Execute` span). This is the only way to measure Suspect B (actor contention for user saves).

### Step 2: Three PRAGMA additions to `DriverFactory.android.kt` (3 lines, immediate, zero risk)

```kotlin
try { driver.execute(null, "PRAGMA temp_store=MEMORY;", 0) } catch (_: Exception) { }
try { driver.execute(null, "PRAGMA cache_size=-8000;", 0) } catch (_: Exception) { }
try { driver.execute(null, "PRAGMA wal_autocheckpoint=4000;", 0) } catch (_: Exception) { }
```

WAL autocheckpoint confirmed as a significant bottleneck by multiple sources including the loke.dev post-mortem and SQLite forum. These are the only safe SQLite tuning changes: `synchronous=OFF` is not justified (marginal gain over WAL+NORMAL, non-zero OS-crash corruption risk); `mmap_size` is unverified on Android (no SELinux block confirmed, but also no affirmative support in Android docs — test empirically).

### Step 3: Remove read-before-write on the autosave path (~10 lines, `GraphWriter.kt`)

Add `skipSafetyCheck: Boolean = false` to `savePageInternal`. Pass `true` from `saveImmediately` and `flush()`. Leave the check active for `savePage()` (direct/import callers). This removes one unconditional `fileSystem.readFile` call (full file read) from every debounced autosave — the single highest-confidence quick win regardless of whether SAF is confirmed.

### Step 4: Build the Microbenchmark bridge test (0.5 days)

Write a plain `@RunWith(AndroidJUnit4::class)` instrumented test that reproduces the problem in a CI-runnable environment. Use `filesDir` (not SAF paths) for the graph root. Use `SyntheticGraphGenerator` with the SMALL preset. This is the gate for claiming any fix is verified. Upgrade to `BenchmarkRule` (`benchmark-junit4:1.3.4`) once the scaffolding is validated.

Macrobenchmark is the wrong tool here: it runs in a separate process and cannot call `GraphWriter`/`GraphLoader` directly; it also fails/gives errors on standard emulator images (confirmed by Android Developers documentation). Microbenchmark runs in-process, supports standard `x86_64` AVDs, and integrates with `reactivecircus/android-emulator-runner`.

### Step 5: Get a session export from a real Android device

Use the `PerfExportReport` / Export button from the observability branch. With Steps 1–2 in place, the export will show:
- `file.readCheck` duration → confirms or rules out Suspect A as dominant
- `db.queue_wait` for HIGH saves → confirms or rules out Suspect B
- `sql.insert` / `sql.update` durations from `TimingDriverWrapper` → shows if Android fsync is the primary cost
- Whether SAF Binder IPC appears in file operation spans

**Only after Step 5** should SAF shadow-copy (high effort) or actor chunk decomposition (medium effort, medium risk) be committed to.

**Accept these costs**:
- Read-before-write removal means `saveImmediately` no longer catches cases where the in-memory block list was corrupted before reaching the writer. Acceptable because `BlockStateManager` is the sole source of truth for the autosave path, and the safety check only fires for pages with >50 blocks where >50% were deleted — an extremely rare case that should be detected at the UI layer instead.
- `wal_autocheckpoint=4000` means the WAL file can grow to ~16MB between auto-checkpoints. Pair with an explicit `PRAGMA wal_checkpoint(PASSIVE)` triggered after >30 seconds of editor idle time to prevent unbounded growth.

**Reject these alternatives at this stage**:
- **SAF shadow-copy**: High effort (~2 days), only justified if session export confirms SAF IPC is the dominant cost. Do not implement before measurement.
- **`BundledSQLiteDriver` migration**: `sqldelight-androidx-driver` (third-party bridge) is required since SQLDelight 2.3.2 doesn't have native BundledSQLiteDriver support. Benefit is 20–26% system-wide; not a fix for several-second latency.
- **Actor chunk decomposition**: Medium effort, medium correctness risk (partial-chunk visibility during Phase 3). Only justified if `db.queue_wait` spans confirm HIGH saves are waiting >200ms behind LOW `Execute` chunks.
- **`synchronous=OFF`**: 5–15% improvement over NORMAL in WAL mode; non-zero OS-crash corruption risk. Not justified.

## Open Questions Before Committing to Steps 4+ 

- [ ] Is the latency on page load (Phase 1/Phase 3) or on save (after 500ms debounce fires)? — determines whether `GraphLoader` or `GraphWriter` is the primary target
- [ ] What device + Android version is the user seeing several-second latency on? — budget device NAND vs. SAF IPC have different fixes
- [ ] Does `getDefaultGraphPath()` in `PlatformFileSystem.kt` return a `saf://` URI for production graphs, or internal storage? — the single biggest determinant of whether SAF is the bottleneck
- [ ] Does `SyntheticGraphGenerator` build correctly from `androidInstrumentedTest` source set? — blocks the Microbenchmark CI step

## Sources

- `project_plans/android-save-parse-performance/research/findings-stack.md`
- `project_plans/android-save-parse-performance/research/findings-features.md`
- `project_plans/android-save-parse-performance/research/findings-architecture.md`
- `project_plans/android-save-parse-performance/research/findings-pitfalls.md`
- [Benchmark in Continuous Integration — Android Developers](https://developer.android.com/topic/performance/benchmarking/benchmarking-in-ci)
- [Write a Macrobenchmark — Android Developers](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)
- [The 20GB WAL File — loke.dev](https://loke.dev/blog/sqlite-checkpoint-starvation-wal-growth)
- [SQLite WAL Mode and Connection Strategies for High-Throughput Mobile Apps](https://dev.to/software_mvp-factory/sqlite-wal-mode-and-connection-strategies-for-high-throughput-mobile-apps-beyond-the-basics-eh0)
- [Best practices for SQLite performance — Android Developers](https://developer.android.com/topic/performance/sqlite-performance-best-practices)
- [SQLite performance tuning — phiresky](https://gist.github.com/phiresky/978d8e204f77feaa0ab5cca08d2d5b27)
- [androidx.benchmark releases](https://developer.android.com/jetpack/androidx/releases/benchmark)
- [sqldelight-androidx-driver — eygraber/GitHub](https://github.com/eygraber/sqldelight-androidx-driver)
