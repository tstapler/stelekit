# JMH Benchmarking in KMP/Gradle

## Research scope
How to add proper JMH-based microbenchmarks to SteleKit's existing KMP Gradle project,
given that `kotlinx-benchmark` is already configured, and how to benchmark SQLite-backed
search code effectively.

---

## 1. Current State: Already Partially Set Up

SteleKit already has `kotlinx-benchmark` configured in `kmp/build.gradle.kts`:
```kotlin
id("org.jetbrains.kotlinx.benchmark")    // plugin applied

benchmark {
    targets { register("jvm") }
    configurations {
        named("main") {
            warmups = 3
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
        }
    }
}
```
Run with: `./gradlew jvmBenchmark`
Output to: `kmp/build/reports/benchmarks/`

The `kotlinx-benchmark-runtime:0.4.13` dependency is in `commonMain` (line 113 of
`build.gradle.kts`). The `allopen` plugin is presumably applied (required for JMH class
generation); this should be verified.

There is also an existing `RepositoryBenchmark` class in `jvmTest` — but this is a plain
JUnit test harness measuring wall-clock time with `measureTime { }`, not a proper JMH
benchmark. It does not use `@Benchmark`, `@State`, or JMH warmup. It is useful for
smoke-testing but does not produce statistically valid p50/p95/p99 latency data.

The `jvmMain/kotlin/dev/stapler/stelekit/benchmarks/AhoCorasickBenchmark.kt` file appears
to contain actual JMH-style benchmarks for the Aho-Corasick implementation.

---

## 2. kotlinx-benchmark vs Manual JMH: Recommendation

**Use `kotlinx-benchmark`** (already in place). Reasons:
- JMH setup is handled by the plugin (bytecode generation, fork management, result reporting).
- Results are emitted as JSON compatible with JMH's format, viewable at jmh.morethan.io.
- Benchmark classes are discovered automatically from the registered `jvm` target.
- Multi-platform compilation works correctly: JVM-only `@Benchmark` methods do not need
  to exist in `commonMain`.

**Manual JMH** would require adding `jmh` Gradle plugin separately, configuring a custom
source set, and managing the JMH annotation processor — all already handled by
`kotlinx-benchmark`. No reason to switch.

---

## 3. Benchmark Source Set: Where to Put Search Benchmarks

### Option A: jvmMain source set (current AhoCorasick approach)
Files in `kmp/src/jvmMain/kotlin/.../benchmarks/`. These compile into the main JVM jar and
are always available. Benchmarks run via `jvmBenchmark` task.
- Pro: simple, no new source set configuration.
- Con: benchmark code ships in production jar.

### Option B: Separate `jvmBenchmark` source set
Following the pattern in kotlinx-benchmark issue #87/#70:
```kotlin
// in kmp/build.gradle.kts
sourceSets {
    val jvmBenchmark by creating {
        compileClasspath += sourceSets["jvmMain"].output + sourceSets["jvmTest"].output
        runtimeClasspath += output + compileClasspath
    }
}
benchmark {
    targets { register("jvmBenchmark") }
}
```
- Pro: benchmark code stays out of production and test JARs.
- Con: more Gradle wiring; dependencies (SQLite driver, test utilities) must be re-declared.

**Recommendation for search benchmarks**: Place in `jvmMain` alongside `AhoCorasickBenchmark`
(Option A) for the initial implementation, consistent with existing project structure. A
separate source set is a future cleanup if benchmark count grows significantly.

---

## 4. Benchmarking SQLite-Backed Code: Setup and Warmup Strategy

The key challenge: SQLite benchmarks measure both JVM JIT warmup and SQLite page-cache
warmup. Both must be separated from the signal.

### Setup pattern using @Setup and in-memory DB

```kotlin
@State(Scope.Benchmark)
open class SearchBenchmark {

    private lateinit var db: SteleDatabase
    private lateinit var repo: SqlDelightSearchRepository
    private val generator = SyntheticGraphGenerator(SyntheticGraphGenerator.LARGE)

    @Setup(Level.Trial)
    fun setup() {
        // In-memory SQLite: avoids disk I/O noise, reproducible across runs
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SteleDatabase.Schema.create(driver)
        db = SteleDatabase(driver)
        repo = SqlDelightSearchRepository(db)

        // Load 2000 pages + ~50k blocks
        val tmpDir = Files.createTempDirectory("stelekit-bench").toFile()
        val graphDir = generator.generate(tmpDir).graphDir
        // GraphLoader.loadFromDirectory(graphDir, ...)  — or directly via repo
        tmpDir.deleteRecursively()
    }

    @TearDown(Level.Trial)
    fun teardown() {
        db.sqlDriver.close()
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    fun searchWithFilters_singleToken() = runBlocking {
        repo.searchWithFilters(SearchRequest(query = "programming", limit = 50)).first()
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    fun ftsQueryBuilder_complexQuery() {
        FtsQueryBuilder.build('"meeting notes" 2025 taxes programming')
    }
}
```

Key decisions:
- `@Setup(Level.Trial)`: runs once per benchmark trial (after JVM JIT warmup iterations),
  so the DB is populated before measurement begins.
- In-memory SQLite (`JdbcSqliteDriver.IN_MEMORY`): eliminates disk I/O noise, ensures
  reproducibility. Represents best-case latency; real-world will be slightly slower.
- `kotlinx-benchmark`'s default `warmups = 3` allows JIT to compile hot paths before
  measurement iterations begin.
- `runBlocking` is acceptable in `@Benchmark` methods — JMH runs each iteration
  synchronously. Coroutine overhead is included in the measurement, which is correct.

### Warmup strategy for SQLite page cache
SQLite's page cache starts cold. After 3 JMH warmup iterations, the page cache is warm
and subsequent measurements are stable. This is why `warmups = 3` (current config) is
appropriate — do not lower it.

---

## 5. Capturing p50/p95/p99

`kotlinx-benchmark` uses JMH's `Mode.SampleTime` for percentile reporting:

```kotlin
@Benchmark
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
fun searchWithFilters_singleToken(): ...
```

With `Mode.SampleTime`, JMH records individual iteration times and reports p50/p90/p99/p99.9
in the console output and JSON results file. This is the correct mode for latency SLO testing.

The current config uses `iterations = 5, iterationTime = 1s` — with `Mode.SampleTime`, each
1-second iteration collects hundreds of samples. p99 will be statistically meaningful.

Output JSON at `kmp/build/reports/benchmarks/main/jvm.json` is compatible with
https://jmh.morethan.io for visualisation.

---

## 6. CI Integration: Output Without Regression Gating

As per requirements (AC7): benchmarks run and produce output; no regression gate yet.

```yaml
# In GitHub Actions workflow
- name: Run JMH Benchmarks
  run: ./gradlew :kmp:jvmBenchmark
  continue-on-error: false  # Benchmarks must complete successfully

- name: Upload benchmark results
  uses: actions/upload-artifact@v4
  with:
    name: benchmark-results
    path: kmp/build/reports/benchmarks/
```

The existing `generateBenchmarkSummary` Gradle task (line 483 of build.gradle.kts) already
produces a JSON summary. The CI job just needs to run `jvmBenchmark` and archive the output.

**Regression gating** (future): `jmh-github-action` or `benchmark-action` can compare JSON
results across commits and fail if p99 increases by more than a threshold. Not required for
the initial implementation.

---

## 7. Summary: What to Add for Search Benchmarks

1. Create `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/benchmarks/SearchBenchmark.kt`
   with `@State(Scope.Benchmark)`, `@Setup(Level.Trial)`, and these `@Benchmark` methods:
   - `searchWithFilters_singleToken` — BM25 + ranking pipeline
   - `searchWithFilters_multiToken` — AND query with ranking
   - `searchPagesByTitle_singleToken` — pages FTS only
   - `buildRankedList_50results` — Kotlin ranking step in isolation
   - `ftsQueryBuilder_complexQuery` — parser only
2. Use `@BenchmarkMode(Mode.SampleTime)` for p50/p95/p99 visibility.
3. Use `SyntheticGraphGenerator.LARGE` (2000 pages) as the baseline; add XLARGE (10k) variant.
4. No new dependencies required: SQLite driver and test utilities are already on
   `jvmTest` classpath; benchmark code in `jvmMain` can access them via shared classpath.
5. The existing `benchmark { configurations { named("main") { warmups = 3 } } }` config is
   sufficient — no changes to `build.gradle.kts` needed.
