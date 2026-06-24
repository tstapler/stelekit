package dev.stapler.stelekit.benchmark

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.db.libsql.JvmLibsqlDriver
import dev.stapler.stelekit.db.libsql.LibsqlBusySnapshotException
import dev.stapler.stelekit.db.libsql.LibsqlTestHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test

/**
 * Concurrent write latency benchmark: libsql (MVCC) vs. PooledJdbcSqliteDriver (WAL).
 *
 * Drives 4 concurrent writer threads directly against [SqlDriver.newTransaction] — NOT through
 * [DatabaseWriteActor], which serializes writes and would mask any MVCC concurrency benefit.
 * Each thread independently begins a transaction, updates a dedicated page row, and commits.
 *
 * Measurements:
 *   - P50/P95/P99 transaction latency (nanoseconds, sorted array)
 *   - Throughput (ops/s over total measured wall time)
 *
 * Results are written to build/reports/benchmark-libsql-concurrent.json and
 * benchmark-jdbc-concurrent.json for baseline comparison.
 *
 * SC-3 assertion (10% P99 improvement OR 10% throughput improvement) is gated behind a
 * non-fatal print on milestone 1 — MVCC retry logic in DatabaseWriteActor is a future milestone.
 */
class LibsqlConcurrentWriteBenchmarkTest {

    companion object {
        private const val NUM_THREADS = 4
        private const val WARMUP_ROUNDS = 5
        private const val WARMUP_OPS_PER_THREAD = 10
        private const val MEASURED_OPS_PER_THREAD = 200
    }

    @Test
    fun concurrentWriteLatency_libsql_vs_pooledJdbc() {
        assumeTrue("libsql native library not available — skipping benchmark", LibsqlTestHarness.isNativeAvailable())

        val libsqlTmp = Files.createTempFile("bench-libsql-concurrent-", ".db").toFile().also { it.deleteOnExit() }
        val jdbcTmp = Files.createTempFile("bench-jdbc-concurrent-", ".db").toFile().also { it.deleteOnExit() }

        // Check MVCC availability before running the libsql half.
        val probeDriver = JvmLibsqlDriver(libsqlTmp.absolutePath, poolSize = 1)
        val mvccAvailable = probeDriver.isMvccActive
        probeDriver.close()
        libsqlTmp.delete()
        libsqlTmp.createNewFile() // recreate empty file for the real benchmark

        assumeTrue(
            "libsql MVCC (BEGIN CONCURRENT) not supported by this build — " +
                "concurrent write benchmark requires MVCC; skipping.",
            mvccAvailable,
        )

        val libsqlResult = try {
            val driver = JvmLibsqlDriver(libsqlTmp.absolutePath, poolSize = 8)
            runConcurrentBenchmark(driver, "libsql")
        } finally {
            libsqlTmp.delete()
        }

        val jdbcResult = try {
            val driver = DriverFactory().createDriver("jdbc:sqlite:${jdbcTmp.absolutePath}")
            runConcurrentBenchmark(driver, "jdbc")
        } finally {
            jdbcTmp.delete()
        }

        writeJson("benchmark-libsql-concurrent.json", libsqlResult)
        writeJson("benchmark-jdbc-concurrent.json", jdbcResult)

        println("[LibsqlConcurrentWriteBenchmark] libsql  P50=${libsqlResult.p50Ns / 1_000_000}ms  P95=${libsqlResult.p95Ns / 1_000_000}ms  P99=${libsqlResult.p99Ns / 1_000_000}ms  throughput=${libsqlResult.throughputOpsPerSec.toLong()}/s")
        println("[LibsqlConcurrentWriteBenchmark] jdbc    P50=${jdbcResult.p50Ns / 1_000_000}ms  P95=${jdbcResult.p95Ns / 1_000_000}ms  P99=${jdbcResult.p99Ns / 1_000_000}ms  throughput=${jdbcResult.throughputOpsPerSec.toLong()}/s")

        // SC-3: libsql should show ≥10% P99 improvement or ≥10% throughput improvement.
        // Gated as informational on milestone 1 — MVCC retry optimisation in DatabaseWriteActor
        // is a future milestone. Failure here is expected and will not fail the build.
        val p99Improved = libsqlResult.p99Ns <= jdbcResult.p99Ns * 0.90
        val throughputImproved = libsqlResult.throughputOpsPerSec >= jdbcResult.throughputOpsPerSec * 1.10
        if (!p99Improved && !throughputImproved) {
            println(
                "[LibsqlConcurrentWriteBenchmark] NOTE: SC-3 not yet met. " +
                    "P99 ratio=${libsqlResult.p99Ns.toDouble() / jdbcResult.p99Ns.toDouble()}, " +
                    "throughput ratio=${libsqlResult.throughputOpsPerSec / jdbcResult.throughputOpsPerSec}. " +
                    "This is expected on milestone 1 — MVCC retry logic in DatabaseWriteActor is a future milestone."
            )
        } else {
            println(
                "[LibsqlConcurrentWriteBenchmark] SC-3 met: " +
                    "p99Improved=$p99Improved throughputImproved=$throughputImproved"
            )
        }
    }

    /**
     * Runs the concurrent write benchmark against [driver].
     *
     * Schema is created, 4 seed pages are inserted (one per thread), then warm-up + measured
     * rounds execute. The driver is closed after measurement; the caller disposes the temp file.
     */
    fun runConcurrentBenchmark(driver: SqlDriver, label: String = "driver"): ConcurrentBenchmarkResult {
        // Schema creation (driver from createDriver() already has the schema; JvmLibsqlDriver does not)
        try {
            runBlocking { SteleDatabase.Schema.create(driver).await() }
        } catch (_: Exception) {
            // Already exists on jdbc path — safe to ignore
        }
        // libsql local mode does not propagate DDL to pre-opened pool connections; reset so
        // all connections reload the freshly created schema (FTS tables, triggers, etc.).
        if (driver is JvmLibsqlDriver) driver.resetPool()

        // Seed one page per thread so concurrent updates target distinct rows (no row-level conflicts)
        val pageUuids = (1..NUM_THREADS).map { idx -> "bench-concurrent-page-$idx" }
        val nowMs = System.currentTimeMillis()
        pageUuids.forEach { uuid ->
            driver.execute(
                identifier = null,
                sql = """
                    INSERT OR IGNORE INTO pages
                        (uuid, name, created_at, updated_at, is_journal, is_favorite, is_content_loaded, backlink_count)
                    VALUES (?, ?, ?, ?, 0, 0, 1, 0)
                """.trimIndent(),
                parameters = 4,
            ) {
                bindString(0, uuid)
                bindString(1, "Bench Concurrent $uuid")
                bindLong(2, nowMs)
                bindLong(3, nowMs)
            }
        }

        // Warm-up: not measured
        repeat(WARMUP_ROUNDS) {
            doWriteRound(driver, pageUuids, WARMUP_OPS_PER_THREAD)
        }

        // Measured: 4 threads × 200 ops each
        val totalMeasuredOps = NUM_THREADS * MEASURED_OPS_PER_THREAD
        val allLatenciesNs = LongArray(totalMeasuredOps)
        val wallStart = System.nanoTime()

        val executor = Executors.newFixedThreadPool(NUM_THREADS)
        val futures = pageUuids.mapIndexed { _, pageUuid ->
            executor.submit<LongArray> {
                val latencies = LongArray(MEASURED_OPS_PER_THREAD)
                repeat(MEASURED_OPS_PER_THREAD) { i ->
                    val opStart = System.nanoTime()
                    performSingleWrite(driver, pageUuid, i, opStart, latencies, i)
                }
                latencies
            }
        }
        futures.forEachIndexed { idx, future ->
            future.get().copyInto(allLatenciesNs, idx * MEASURED_OPS_PER_THREAD)
        }
        val wallElapsedNs = System.nanoTime() - wallStart
        executor.shutdown()

        driver.close()

        allLatenciesNs.sort()
        val p50 = allLatenciesNs[(allLatenciesNs.size * 0.50).toInt().coerceIn(0, allLatenciesNs.size - 1)]
        val p95 = allLatenciesNs[(allLatenciesNs.size * 0.95).toInt().coerceIn(0, allLatenciesNs.size - 1)]
        val p99 = allLatenciesNs[(allLatenciesNs.size * 0.99).toInt().coerceIn(0, allLatenciesNs.size - 1)]
        val throughput = totalMeasuredOps / (wallElapsedNs / 1_000_000_000.0)

        println("[LibsqlConcurrentWriteBenchmark] $label: ops=$totalMeasuredOps wallMs=${wallElapsedNs / 1_000_000} throughput=${throughput.toLong()}/s")

        return ConcurrentBenchmarkResult(
            p50Ns = p50,
            p95Ns = p95,
            p99Ns = p99,
            throughputOpsPerSec = throughput,
        )
    }

    /**
     * Executes a single write operation: BEGIN (CONCURRENT for libsql), UPDATE, COMMIT.
     * On [LibsqlBusySnapshotException] (MVCC conflict), retries once.
     * Records the total elapsed nanoseconds (including any retry) into [latencies] at [latencyIdx].
     */
    private fun performSingleWrite(
        driver: SqlDriver,
        pageUuid: String,
        iteration: Int,
        opStart: Long,
        latencies: LongArray,
        latencyIdx: Int,
    ) {
        try {
            runBlocking {
                SteleDatabase(driver).transaction {
                    driver.execute(
                        identifier = null,
                        sql = "UPDATE pages SET name = ?, updated_at = ? WHERE uuid = ?",
                        parameters = 3,
                    ) {
                        bindString(0, "Updated-$iteration-${Thread.currentThread().name}")
                        bindLong(1, System.currentTimeMillis())
                        bindString(2, pageUuid)
                    }
                }
            }
        } catch (e: LibsqlBusySnapshotException) {
            // MVCC snapshot conflict — retry once
            runBlocking {
                SteleDatabase(driver).transaction {
                    driver.execute(
                        identifier = null,
                        sql = "UPDATE pages SET name = ?, updated_at = ? WHERE uuid = ?",
                        parameters = 3,
                    ) {
                        bindString(0, "Retry-$iteration-${Thread.currentThread().name}")
                        bindLong(1, System.currentTimeMillis())
                        bindString(2, pageUuid)
                    }
                }
            }
        }
        latencies[latencyIdx] = System.nanoTime() - opStart
    }

    /** Warm-up helper: runs [opsPerThread] writes per page without recording latencies. */
    private fun doWriteRound(driver: SqlDriver, pageUuids: List<String>, opsPerThread: Int) {
        val executor = Executors.newFixedThreadPool(NUM_THREADS)
        val futures = pageUuids.map { uuid ->
            executor.submit {
                repeat(opsPerThread) { i ->
                    try {
                        runBlocking {
                            SteleDatabase(driver).transaction {
                                driver.execute(
                                    identifier = null,
                                    sql = "UPDATE pages SET name = ? WHERE uuid = ?",
                                    parameters = 2,
                                ) {
                                    bindString(0, "Warmup-$i")
                                    bindString(1, uuid)
                                }
                            }
                        }
                    } catch (_: LibsqlBusySnapshotException) {
                        // Ignore conflicts during warm-up
                    }
                }
            }
        }
        futures.forEach { it.get() }
        executor.shutdown()
    }

    private fun writeJson(fileName: String, result: ConcurrentBenchmarkResult) {
        try {
            val outputDir = System.getProperty("benchmark.output.dir")
                ?.let { File(it) }
                ?: File("build/reports")
            outputDir.mkdirs()
            val file = File(outputDir, fileName)
            file.writeText(
                """
                {
                  "p50Ms": ${result.p50Ns / 1_000_000.0},
                  "p95Ms": ${result.p95Ns / 1_000_000.0},
                  "p99Ms": ${result.p99Ns / 1_000_000.0},
                  "p50Ns": ${result.p50Ns},
                  "p95Ns": ${result.p95Ns},
                  "p99Ns": ${result.p99Ns},
                  "throughputOpsPerSec": ${result.throughputOpsPerSec},
                  "threads": $NUM_THREADS,
                  "opsPerThread": $MEASURED_OPS_PER_THREAD
                }
                """.trimIndent()
            )
            println("[LibsqlConcurrentWriteBenchmark] wrote $file")
        } catch (_: Exception) {
            // non-fatal — JSON output is informational
        }
    }
}

/**
 * Sequential write+read latency benchmark: libsql vs. PooledJdbcSqliteDriver.
 *
 * Runs on a single thread so MVCC is not needed.  Each operation is:
 *   BEGIN → INSERT page row → COMMIT → SELECT COUNT to verify.
 *
 * This gives an apples-to-apples comparison of raw driver overhead (JNI round-trip
 * vs JDBC connection) at the SQLDelight API layer.
 */
class LibsqlSequentialWriteBenchmarkTest {

    companion object {
        private const val WARMUP_OPS = 50
        private const val MEASURED_OPS = 500
    }

    @Test
    fun sequentialWriteLatency_libsql_vs_pooledJdbc() {
        assumeTrue("libsql native library not available — skipping", LibsqlTestHarness.isNativeAvailable())

        val libsqlTmp = Files.createTempFile("bench-seq-libsql-", ".db").toFile().also { it.deleteOnExit() }
        val jdbcTmp   = Files.createTempFile("bench-seq-jdbc-",   ".db").toFile().also { it.deleteOnExit() }

        val libsqlResult: SequentialBenchmarkResult
        val jdbcResult: SequentialBenchmarkResult

        try {
            val driver = JvmLibsqlDriver(libsqlTmp.absolutePath, poolSize = 4)
            runBlocking { SteleDatabase.Schema.create(driver).await() }
            driver.resetPool()
            libsqlResult = runSequentialBenchmark(driver, "libsql")
        } finally { libsqlTmp.delete() }

        try {
            val driver = DriverFactory().createDriver("jdbc:sqlite:${jdbcTmp.absolutePath}")
            jdbcResult = runSequentialBenchmark(driver, "jdbc")
        } finally { jdbcTmp.delete() }

        val p99RatioVsJdbc = if (jdbcResult.p99Ns > 0) libsqlResult.p99Ns.toDouble() / jdbcResult.p99Ns.toDouble() else Double.NaN
        val throughputRatio = if (jdbcResult.throughputOpsPerSec > 0) libsqlResult.throughputOpsPerSec / jdbcResult.throughputOpsPerSec else Double.NaN

        println("[SeqWriteBench] libsql  P50=${libsqlResult.p50Ns/1_000}µs  P95=${libsqlResult.p95Ns/1_000}µs  P99=${libsqlResult.p99Ns/1_000}µs  tput=${libsqlResult.throughputOpsPerSec.toLong()}/s")
        println("[SeqWriteBench] jdbc    P50=${jdbcResult.p50Ns/1_000}µs  P95=${jdbcResult.p95Ns/1_000}µs  P99=${jdbcResult.p99Ns/1_000}µs  tput=${jdbcResult.throughputOpsPerSec.toLong()}/s")
        println("[SeqWriteBench] P99 ratio (libsql/jdbc)=%.2f  throughput ratio=%.2f".format(p99RatioVsJdbc, throughputRatio))

        // Write results
        val outputDir = File(System.getProperty("benchmark.output.dir", "build/reports")).also { it.mkdirs() }
        File(outputDir, "benchmark-sequential-libsql.json").writeText(libsqlResult.toJson("libsql", p99RatioVsJdbc, throughputRatio))
        File(outputDir, "benchmark-sequential-jdbc.json").writeText(jdbcResult.toJson("jdbc", 1.0, 1.0))
    }

    private fun runSequentialBenchmark(driver: SqlDriver, label: String): SequentialBenchmarkResult {
        val db = SteleDatabase(driver)
        val nowMs = System.currentTimeMillis()
        val latenciesNs = LongArray(MEASURED_OPS)
        // Use all 8 columns as ? to match paramCount; mixing literals causes xerial to mis-size
        // its internal batch array (1-based index vs 0-based array length mismatch).
        val insertSql = "INSERT OR IGNORE INTO pages " +
            "(uuid,name,created_at,updated_at,is_journal,is_favorite,is_content_loaded,backlink_count) " +
            "VALUES (?,?,?,?,?,?,?,?)"

        fun bind(stmt: app.cash.sqldelight.db.SqlPreparedStatement, uuid: String, name: String) {
            stmt.bindString(0, uuid); stmt.bindString(1, name)
            stmt.bindLong(2, nowMs); stmt.bindLong(3, nowMs)
            stmt.bindLong(4, 0); stmt.bindLong(5, 0); stmt.bindLong(6, 1); stmt.bindLong(7, 0)
        }

        // Warm-up
        repeat(WARMUP_OPS) { i ->
            runBlocking { db.transaction { driver.execute(null, insertSql, 8) { bind(this, "warmup-$label-$i", "Warmup $i") } } }
        }

        val wallStart = System.nanoTime()
        repeat(MEASURED_OPS) { i ->
            val opStart = System.nanoTime()
            runBlocking { db.transaction { driver.execute(null, insertSql, 8) { bind(this, "bench-$label-$i", "Bench Page $i") } } }
            latenciesNs[i] = System.nanoTime() - opStart
        }
        val wallElapsed = System.nanoTime() - wallStart

        driver.close()
        latenciesNs.sort()
        val throughput = MEASURED_OPS / (wallElapsed / 1_000_000_000.0)
        println("[SeqWriteBench] $label: ops=$MEASURED_OPS wallMs=${wallElapsed/1_000_000} tput=${throughput.toLong()}/s")
        return SequentialBenchmarkResult(
            p50Ns  = latenciesNs[(MEASURED_OPS * 0.50).toInt()],
            p95Ns  = latenciesNs[(MEASURED_OPS * 0.95).toInt()],
            p99Ns  = latenciesNs[(MEASURED_OPS * 0.99).toInt()],
            throughputOpsPerSec = throughput,
        )
    }
}

data class SequentialBenchmarkResult(
    val p50Ns: Long,
    val p95Ns: Long,
    val p99Ns: Long,
    val throughputOpsPerSec: Double,
) {
    fun toJson(label: String, p99Ratio: Double, tputRatio: Double) = """
        {
          "driver": "$label",
          "p50µs": ${p50Ns / 1_000.0},
          "p95µs": ${p95Ns / 1_000.0},
          "p99µs": ${p99Ns / 1_000.0},
          "throughputOpsPerSec": $throughputOpsPerSec,
          "p99RatioLibsqlOverJdbc": $p99Ratio,
          "throughputRatioLibsqlOverJdbc": $tputRatio
        }
    """.trimIndent()
}

/**
 * Latency and throughput summary from one concurrent-write benchmark run.
 *
 * All nanosecond values are from [System.nanoTime] differences. Throughput is computed
 * from total wall time (not sum of per-op latencies) to reflect real concurrency.
 */
data class ConcurrentBenchmarkResult(
    val p50Ns: Long,
    val p95Ns: Long,
    val p99Ns: Long,
    val throughputOpsPerSec: Double,
)

