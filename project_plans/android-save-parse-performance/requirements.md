# Requirements: Android Save & Parse Performance

**Status**: Draft | **Phase**: 1 — Ideation complete  
**Created**: 2026-04-23

## Problem Statement

On Android, saving a page and re-parsing it takes several seconds — far above the ~22ms seen on JVM. This creates visible lag during editing and after navigation. The root cause is unknown because all existing benchmarks run on JVM; there is no Android-specific measurement infrastructure.

Users: anyone editing the Android app with a real Logseq graph.

## Current Benchmark Baseline (JVM only)

From CI (`benchmarks/history/2026-04-22_21h37m29s_4abaf60.json`, SMALL graph — 200 pages, 30 journals):

| Metric | Value | Budget |
|--------|-------|--------|
| Phase 1 TTI | 9 ms | < 5 s |
| Phase 2 metadata load | 3 ms | — |
| Phase 3 full indexing | 11 ms | — |
| Total | 22 ms | — |
| Write p95 baseline | 25 ms | < 32 ms |
| Jank factor | -0.04 (invalid, no concurrent writes captured) | < 2× |

**Gap**: No equivalent numbers for Android. User reports several-second latency on Android for save + parse.

## Success Criteria

- Android Phase 1 TTI measured and below 3 s on a mid-range device (Pixel 6 class)
- Single-page save (after 500ms debounce fires) completes in < 500ms on Android
- Android benchmark exists in CI, producing the same JSON format as JVM benchmarks
- No regression on JVM numbers (TTI, write p95)

## Scope

### Must Have (MoSCoW)
- Instrument and measure Android save + parse latency (reproduce the "several seconds" case)
- Identify the dominant bottleneck(s): file I/O, SQLite writes, markdown parsing, or DB actor contention
- Fix at least the highest-impact bottleneck
- Add Android instrumentation test or Macrobenchmark that gates future regressions

### Out of Scope
- iOS performance (separate platform)
- JVM performance (already excellent per benchmarks)
- Search performance (separate concern)
- UI rendering / jank (JankStats already wired for Android)

## Constraints

- **Tech stack**: Kotlin Multiplatform, Android SQLite via `AndroidSqliteDriver` + `RequerySQLiteOpenHelperFactory`, WAL mode enabled, `DatabaseWriteActor` serializes all writes
- **Android DB dispatcher**: `Dispatchers.IO` (single-threaded access to Android SQLite is different from JVM's 8-connection pool)
- **Save path**: `queueSave` → 500ms debounce → `savePageInternal` → read-before-write safety check → markdown serialization → file write → DB write via actor
- **Parse path**: `GraphLoader.parseAndSavePage` → `OutlinerPipeline` → `BlockParser` → `InlineParser` → `AhoCorasickMatcher` → DB writes
- **Timeline**: active investigation; no hard deadline
- **Dependencies**: observability infrastructure just landed on `stelekit-more-more-performance` branch (OpenTelemetry spans, SLO checker, `TimingDriverWrapper` for query-level spans) — use this to diagnose

## Context

### What the Observability Branch Provides
`feat(observability)` (a9770de16) added:
- `TimingDriverWrapper` — captures query-level child spans (actual SQL time on Android)
- `db.queue_wait` spans — time spent waiting in the `DatabaseWriteActor` queue
- `graph_load.phase1` / `phase2` spans
- `PerfExportReport` with `SessionSummary` — exportable JSON for analysis

This means we can extract a session export from the running Android app and see which spans are slow before writing any new code.

### Known Hot Paths
1. **Read-before-write in `savePageInternal`** — reads the old file to count blocks (safety check), then writes the new content. On Android with slow storage this doubles file I/O per save.
2. **`DatabaseWriteActor` channel** — single-coroutine serialization of all DB writes; any phase-3 indexing running concurrently will queue writes from save, increasing `db.queue_wait`.
3. **`AhoCorasickMatcher`** — rebuilt at load time from all page names; search is O(text-length + matches); cost unknown on Android with large graphs.
4. **`synchronous=NORMAL` + `journal_mode=WAL`** — already set. But Android's internal storage may still have higher fsync latency than desktop SSD.

### Existing Infrastructure
- JVM benchmarks: `GraphLoadTimingTest`, `GraphLoadJankTest`, `RepositoryBenchmark` (all JVM, no Android equivalent)
- JFR profiling: `./gradlew :kmp:jvmTestProfile` (JVM only)
- Android JankStats: wired via `JankStatsHolder` (frame timing, not operation timing)
- Macrobenchmark module: **does not exist yet**

### Stakeholders
- Tyler Stapler (developer + user)

## Research Dimensions Needed

- [ ] Stack — Android Macrobenchmark library vs. instrumentation test timing for measuring save/parse on-device
- [ ] Features — how other KMP apps instrument Android SQLite performance; WAL tuning options on Android
- [ ] Architecture — whether `DatabaseWriteActor` contention or file I/O dominates the Android bottleneck; whether a read-free save path is safe
- [ ] Pitfalls — Android storage access edge cases (SAF paths, external storage), known `AndroidSqliteDriver` performance quirks, emulator vs. device variance
