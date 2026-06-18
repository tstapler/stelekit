package dev.stapler.stelekit.benchmark

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.time.Clock

/**
 * Unified JVM benchmark runner.
 *
 * Iterates all JVM-compatible [BenchmarkRegistry] descriptors, constructs [BenchmarkResult]
 * objects recording timing from existing benchmark tests, and writes them as a JSON array to
 * the output directory.
 *
 * Existing tests (GraphLoadTimingTest, GraphLoadJankTest, etc.) are NOT replaced — this runner
 * is additive. It produces a machine-readable output file for baseline comparison.
 *
 * Output location: ${benchmark.output.dir}/unified-benchmark-jvm.json
 *   Override via: -Dbenchmark.output.dir=/path/to/dir (Gradle: -Pbenchmark.output.dir=...)
 */
class UnifiedBenchmarkRunner {

    private val json = Json { prettyPrint = true }

    @Test
    fun runAllScenarios() {
        val gitSha = runCatching {
            ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .readLine()
                ?.trim()
                ?: "unknown"
        }.getOrElse { "unknown" }

        val outputDir = java.io.File(
            System.getProperty("benchmark.output.dir", "build/reports"),
        ).also { it.mkdirs() }

        val jvmDescriptors = BenchmarkRegistry.all.filter { "jvm" in it.supportedPlatforms }

        val results = jvmDescriptors.map { descriptor ->
            val startMs = Clock.System.now().toEpochMilliseconds()

            // Delegate to existing timing infrastructure: measure the same load path
            // via SyntheticGraphGenerator + GraphLoader to capture representative metrics.
            val metrics = runScenarioMetrics(descriptor.name, descriptor.preset)

            BenchmarkResult(
                platform = "jvm",
                scenario = descriptor.name,
                graphConfig = descriptor.preset.name,
                runAtEpochMs = startMs,
                gitSha = gitSha,
                metrics = metrics,
            )
        }

        val outputFile = java.io.File(outputDir, "unified-benchmark-jvm.json")
        outputFile.writeText(json.encodeToString(results))

        println("[UnifiedBenchmarkRunner] Wrote ${results.size} results to ${outputFile.absolutePath}")
        results.forEach { r ->
            println("  ${r.scenario}/${r.graphConfig}: ${r.metrics}")
        }
    }

    /**
     * Runs the appropriate benchmark scenario and returns a metrics map.
     *
     * For additive integration: this delegates to existing test helpers inline.
     * Scenario implementations that use JVM-only APIs (File, runBlocking) stay here
     * in jvmTest and are never referenced from commonMain.
     */
    private fun runScenarioMetrics(scenarioName: String, preset: GraphPreset): Map<String, Double> {
        return when (scenarioName) {
            "GraphLoad" -> runGraphLoadMetrics(preset)
            "WriteConcurrency" -> runWriteConcurrencyMetrics(preset)
            "NavigationLatency" -> runNavigationLatencyMetrics(preset)
            "UserSession" -> runUserSessionMetrics()
            else -> emptyMap()
        }
    }

    private fun runGraphLoadMetrics(preset: GraphPreset): Map<String, Double> {
        val cfg = when (preset) {
            GraphPreset.TINY   -> SyntheticGraphGenerator.TINY
            GraphPreset.SMALL  -> SyntheticGraphGenerator.SMALL
            GraphPreset.MEDIUM -> SyntheticGraphGenerator.MEDIUM
            GraphPreset.LARGE  -> SyntheticGraphGenerator.LARGE
        }

        val dir = java.nio.file.Files.createTempDirectory("unified-bench-load-${preset.name.lowercase()}").toFile()
        try {
            val stats = SyntheticGraphGenerator(cfg).generate(dir)
            println("[UnifiedBenchmarkRunner] GraphLoad/${preset.name}: ${stats.pageCount} pages, ${stats.journalCount} journals")

            val start = Clock.System.now()
            var phase1Ms = -1L

            val fileSystem = dev.stapler.stelekit.platform.PlatformFileSystem()
            val pageRepo = dev.stapler.stelekit.repository.InMemoryPageRepository()
            val blockRepo = dev.stapler.stelekit.repository.InMemoryBlockRepository()
            val loader = dev.stapler.stelekit.db.GraphLoader(fileSystem, pageRepo, blockRepo)

            return kotlinx.coroutines.runBlocking {
                val totalMs = kotlin.time.measureTime {
                    loader.loadGraphProgressive(
                        graphPath = dir.absolutePath,
                        immediateJournalCount = 10,
                        onProgress = {},
                        onPhase1Complete = {
                            phase1Ms = (kotlin.time.Clock.System.now() - start).inWholeMilliseconds
                        },
                        onFullyLoaded = {},
                    )
                }.inWholeMilliseconds

                val phase3Ms = kotlin.time.measureTime {
                    loader.indexRemainingPages {}
                }.inWholeMilliseconds

                mapOf(
                    "phase1TtiMs" to phase1Ms.toDouble(),
                    "totalMs" to (totalMs + phase3Ms).toDouble(),
                    "phase3Ms" to phase3Ms.toDouble(),
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun runWriteConcurrencyMetrics(preset: GraphPreset): Map<String, Double> {
        if (!dev.stapler.stelekit.db.libsql.LibsqlTestHarness.isNativeAvailable()) {
            println("[UnifiedBenchmarkRunner] WriteConcurrency/${preset.name}: libsql native not available — using sentinel metrics")
            return mapOf(
                "p50Ms" to -1.0,
                "p95Ms" to -1.0,
                "p99Ms" to -1.0,
            )
        }

        println("[UnifiedBenchmarkRunner] WriteConcurrency/${preset.name}: running libsql vs jdbc concurrent benchmark")
        val bench = LibsqlConcurrentWriteBenchmarkTest()

        val libsqlTmp = java.nio.file.Files.createTempFile("unified-bench-libsql-", ".db").toFile()
            .also { it.deleteOnExit() }
        val jdbcTmp = java.nio.file.Files.createTempFile("unified-bench-jdbc-", ".db").toFile()
            .also { it.deleteOnExit() }

        return try {
            val libsqlDriver = dev.stapler.stelekit.db.libsql.JvmLibsqlDriver(libsqlTmp.absolutePath, poolSize = 8)
            val libsqlResult = bench.runConcurrentBenchmark(libsqlDriver, "libsql")

            val jdbcDriver = dev.stapler.stelekit.db.DriverFactory().createDriver("jdbc:sqlite:${jdbcTmp.absolutePath}")
            val jdbcResult = bench.runConcurrentBenchmark(jdbcDriver, "jdbc")

            val p99RatioLibsqlOverJdbc = if (jdbcResult.p99Ns > 0) libsqlResult.p99Ns.toDouble() / jdbcResult.p99Ns.toDouble() else -1.0
            val throughputRatio = if (jdbcResult.throughputOpsPerSec > 0) libsqlResult.throughputOpsPerSec / jdbcResult.throughputOpsPerSec else -1.0

            mapOf(
                "libsqlP50Ms"          to libsqlResult.p50Ns / 1_000_000.0,
                "libsqlP95Ms"          to libsqlResult.p95Ns / 1_000_000.0,
                "libsqlP99Ms"          to libsqlResult.p99Ns / 1_000_000.0,
                "libsqlThroughput"     to libsqlResult.throughputOpsPerSec,
                "jdbcP50Ms"            to jdbcResult.p50Ns / 1_000_000.0,
                "jdbcP95Ms"            to jdbcResult.p95Ns / 1_000_000.0,
                "jdbcP99Ms"            to jdbcResult.p99Ns / 1_000_000.0,
                "jdbcThroughput"       to jdbcResult.throughputOpsPerSec,
                "p99RatioLibsqlJdbc"   to p99RatioLibsqlOverJdbc,
                "throughputRatio"      to throughputRatio,
            )
        } finally {
            libsqlTmp.delete()
            jdbcTmp.delete()
        }
    }

    private fun runNavigationLatencyMetrics(preset: GraphPreset): Map<String, Double> {
        // Placeholder: navigation latency is covered by GraphLoadTimingTest's large-page test.
        println("[UnifiedBenchmarkRunner] NavigationLatency/${preset.name}: using sentinel metrics (see GraphLoadTimingTest)")
        return mapOf(
            "p95Ms" to -1.0,
        )
    }

    private fun runUserSessionMetrics(): Map<String, Double> {
        val graphPath = System.getProperty("STELEKIT_GRAPH_PATH")
        if (graphPath.isNullOrBlank()) {
            println("[UnifiedBenchmarkRunner] UserSession: SKIPPED — set -DSTELEKIT_GRAPH_PATH=/your/graph to run")
            return mapOf("skipped" to 1.0)
        }
        // Real-graph UserSession is handled by UserSessionBenchmarkTest.
        // Sentinel here so baseline comparisons exclude this scenario when path is unset.
        return mapOf("skipped" to 0.0)
    }
}
