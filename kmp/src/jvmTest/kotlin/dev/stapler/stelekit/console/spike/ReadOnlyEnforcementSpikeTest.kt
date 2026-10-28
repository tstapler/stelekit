// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console.spike

import app.cash.sqldelight.db.QueryResult
import dev.stapler.stelekit.db.DriverFactory
import kotlinx.coroutines.runBlocking
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spike 0.3a (dev plan 0.2a), JVM: is a dedicated `SQLiteConfig.setReadOnly(true)` connection engine-enforced,
 * and does using it leak into the 8 pooled app connections? Results are `SPIKE-RESULT 0.3a` lines.
 */
class ReadOnlyEnforcementSpikeTest {

    private fun appCount(driver: app.cash.sqldelight.db.SqlDriver): Long = runBlocking {
        driver.executeQuery(null, "SELECT COUNT(*) FROM pages", { c -> c.next(); QueryResult.Value(c.getLong(0)!!) }, 0).value
    }

    private fun appWrite(driver: app.cash.sqldelight.db.SqlDriver, id: String) {
        driver.execute(null, "INSERT INTO pages(uuid,name,created_at,updated_at) VALUES ('$id','$id',1,1)", 0).value
    }

    private fun appQueryOnly(driver: app.cash.sqldelight.db.SqlDriver): Long = runBlocking {
        driver.executeQuery(null, "PRAGMA query_only", { c -> c.next(); QueryResult.Value(c.getLong(0)!!) }, 0).value
    }

    @Test
    fun spike0_3a_should_RecordReadOnlyErrorAndUnchangedCounts_When_UpdateAndCteDeleteRunOnReadOnlyJdbc() {
        val dir = SpikeSupport.tempDir("spike03a")
        try {
            val db = dir.resolve("app.db")
            val app = DriverFactory().createDriver(SpikeSupport.url(db))
            try {
                SpikeSupport.seed(db, 1L, blocksPerPage = 1, contentBytes = 40)
                val before = appCount(app)
                val ro = SpikeSupport.readOnlyConnection(db)
                try {
                    val updateMsg = assertFailsReadonly(ro, "UPDATE pages SET name='x'")
                    val cteMsg = assertFailsReadonly(ro, "WITH c AS (SELECT 1) DELETE FROM pages")
                    assertEquals(before, SpikeSupport.scalarLong(ro, "SELECT COUNT(*) FROM pages"))
                    assertEquals(before, appCount(app))
                    SpikeSupport.record("0.3a", "update_error", updateMsg)
                    SpikeSupport.record("0.3a", "cte_delete_error", cteMsg)
                    SpikeSupport.record("0.3a", "count_before_after", "$before/${appCount(app)}")

                    // The read-only open flag survives the in-band attempts to lift it.
                    ro.createStatement().use { it.execute("PRAGMA query_only=0") }
                    val afterLift = assertFailsReadonly(ro, "UPDATE pages SET name='y'")
                    SpikeSupport.record("0.3a", "update_after_query_only_0", afterLift)

                    // PRAGMA query_only=1 on the console connection must not leak into the 8 pooled connections.
                    ro.createStatement().use { it.execute("PRAGMA query_only=1") }
                    repeat(24) { appWrite(app, "leak-$it") } // 24 > 8, so every pooled connection serves a write
                    assertEquals(before + 24, appCount(app))
                    assertEquals(0L, appQueryOnly(app))
                    SpikeSupport.record("0.3a", "pooled_writes_after_console_query_only", "24 ok; app query_only=${appQueryOnly(app)}")
                } finally {
                    ro.close()
                }
            } finally {
                app.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun spike0_3a_should_RecordConcurrentReadsSucceeding_When_PooledWritesRunInWal() {
        val dir = SpikeSupport.tempDir("spike03a-wal")
        try {
            val db = dir.resolve("app.db")
            val app = DriverFactory().createDriver(SpikeSupport.url(db))
            try {
                SpikeSupport.seed(db, 1L, blocksPerPage = 1, contentBytes = 40)
                val ro = SpikeSupport.readOnlyConnection(db)
                val reads = AtomicInteger()
                val errors = AtomicInteger()
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                val reader = thread(name = "ro-reader") {
                    while (!done.get()) {
                        try { SpikeSupport.scalarLong(ro, "SELECT COUNT(*) FROM pages"); reads.incrementAndGet() } catch (_: SQLException) { errors.incrementAndGet() }
                    }
                }
                repeat(300) { appWrite(app, "w-$it") }
                done.set(true)
                reader.join(5000)
                ro.close()
                assertEquals(0, errors.get(), "reads on the read-only connection failed during WAL writes")
                assertTrue(reads.get() > 0)
                SpikeSupport.record("0.3a", "concurrent_reads_during_300_wal_writes", "reads=${reads.get()} errors=${errors.get()}")
            } finally {
                app.close()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun spike0_3a_should_ConfirmJvmCreateReadDriverReturnsNull_When_CalledForAFileDb() {
        val result = DriverFactory().createReadDriver("jdbc:sqlite:/nonexistent/x.db")
        assertNull(result)
        SpikeSupport.record("0.3a", "jvm_createReadDriver", "null (console cannot reuse it; opens its own handle)")
    }

    private fun assertFailsReadonly(conn: java.sql.Connection, sql: String): String {
        val e = runCatching { conn.createStatement().use { it.executeUpdate(sql) } }.exceptionOrNull()
        assertTrue(e is SQLException, "expected SQLException for: $sql, got $e")
        assertTrue(e.message.orEmpty().contains("readonly", ignoreCase = true), "not a readonly error: ${e.message}")
        return e.message.orEmpty()
    }
}
