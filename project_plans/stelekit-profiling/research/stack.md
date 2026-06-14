# Stack Research: Existing Test Infrastructure

## Key files read
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/GraphLoadTimingTest.kt`
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/SyntheticGraphGenerator.kt`
- `kmp/build.gradle.kts` (jvmTestProfile task)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModelDependencies.kt`

---

## 1. RepositorySet wiring pattern (reusable verbatim)

`GraphLoadTimingTest` already shows the exact pattern for creating a fully-wired SQLite backend:

```kotlin
val factory = RepositoryFactoryImpl(DriverFactory(), "jdbc:sqlite:${File(dir, "bench.db").absolutePath}")
val repoSet  = factory.createRepositorySet(GraphBackend.SQLDELIGHT, scope)
```

`repoSet` exposes:
- `repoSet.pageRepository`  — `PageRepository`
- `repoSet.blockRepository` — `BlockRepository`
- `repoSet.writeActor`      — `DatabaseWriteActor?` (non-null for SQLDelight backend)
- `repoSet.histogramWriter` — `HistogramWriter?`
- `repoSet.queryStatsCollector` — `QueryStatsCollector?`
- `repoSet.queryStatsRepository` — `QueryStatsRepository?`

The `scope` is always `CoroutineScope(SupervisorJob() + Dispatchers.Default)` and is cancelled in `finally`.

## 2. GraphLoader construction (reusable verbatim)

```kotlin
val loader = GraphLoader(
    fileSystem,
    repoSet.pageRepository,
    repoSet.blockRepository,
    externalWriteActor = repoSet.writeActor,
    histogramWriter    = repoSet.histogramWriter,
)
```

`PlatformFileSystem()` is the concrete JVM implementation already used in every benchmark test.

## 3. jvmTestProfile task — how new tests are registered

The Gradle task at line 501 uses a `filter { includeTestsMatching("...GraphLoadTimingTest") }` block. To add `UserSessionBenchmarkTest`, the filter simply needs to also include the new class:

```kotlin
filter {
    includeTestsMatching("dev.stapler.stelekit.benchmark.GraphLoadTimingTest")
    includeTestsMatching("dev.stapler.stelekit.benchmark.UserSessionBenchmarkTest")
}
```

The task already sets:
- `systemProperty("benchmark.output.dir", ...)` — the new test should write its JSON there
- `systemProperty("STELEKIT_GRAPH_PATH", ...)` — real-graph path for FR-4

## 4. StelekitViewModelDependencies construction in tests

`StelekitViewModelDependencies` is a Kotlin `data class`. The minimum viable instantiation for a headless session benchmark:

```kotlin
val deps = StelekitViewModelDependencies(
    pageRepository    = repoSet.pageRepository,
    blockRepository   = repoSet.blockRepository,
    searchRepository  = repoSet.searchRepository,   // from repoSet
    graphLoader       = loader,
    graphWriter       = graphWriter,                // GraphWriter(fileSystem, repoSet.writeActor, graphPath = dir.absolutePath)
    fileSystem        = PlatformFileSystem(),
    platformSettings  = InMemorySettings(),         // or NoOpSettings — needs a stub
    scope             = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    writeActor        = repoSet.writeActor,
    histogramWriter   = repoSet.histogramWriter,
    ringBuffer        = RingBufferSpanExporter(capacity = 5000).also { it.enabled = true },
)
```

All optional fields (`notificationManager`, `blockStateManager`, `undoManager`, etc.) default to `null` and are safe to omit.

## 5. Helpers that can be reused directly

| Helper | Location | What it provides |
|--------|----------|-----------------|
| `tempDir(prefix)` | `GraphLoadTimingTest` (private) | `Files.createTempDirectory(...).toFile().also { it.deleteOnExit() }` |
| `syntheticConfig()` | `GraphLoadTimingTest` (private) | Reads `STELEKIT_BENCH_CONFIG` system property → `SyntheticGraphGenerator.Config` |
| `writeJson(file, data)` | `GraphLoadTimingTest` (private) | Simple flat JSON writer for `Map<String, Any>` |
| `SyntheticGraphGenerator.SMALL` | companion | Default CI preset (200 pages, 30 journals) |
| `SyntheticGraphGenerator(cfg).generate(dir)` | public API | Writes markdown to `dir/pages/` and `dir/journals/` |

These should be extracted to a shared `BenchmarkTestUtils` object in the `benchmark` package so both tests can use them without duplication.

## 6. Filesystem copy helper (FR-1, FR-4)

There is **no existing** graph-copy utility in the benchmark package. A new helper is needed:

```kotlin
fun copyGraphToTempDir(sourcePath: String, prefix: String): File {
    val dest = Files.createTempDirectory(prefix).toFile()
    File(sourcePath).copyRecursively(dest, overwrite = true)
    dest.deleteOnExit()
    return dest
}
```

This should live in `BenchmarkTestUtils` alongside the extracted helpers.

---

## Summary

- The RepositorySet + GraphLoader wiring in `GraphLoadTimingTest` is directly reusable; copy the 4-line setup verbatim.
- `StelekitViewModelDependencies` is a plain data class with all optional fields defaulting to null — minimal construction requires only repositories, graphLoader, graphWriter, fileSystem, platformSettings, and scope.
- The `jvmTestProfile` Gradle task needs one additional `includeTestsMatching` line to cover the new benchmark class; all JFR/async-profiler/output-dir wiring is already in place.
