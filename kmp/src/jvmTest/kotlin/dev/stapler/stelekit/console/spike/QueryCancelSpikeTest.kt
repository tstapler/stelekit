// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console.spike

import dev.stapler.stelekit.db.DriverFactory
import java.io.File
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spike 0.3d, JVM half: which mechanism stops a runaway `WITH RECURSIVE` on a console-owned sqlite-jdbc
 * connection within 1 s, and does the app's pooled driver stay usable afterwards? The Android half is
 * `QueryCancelSpikeAndroidTest`. Each variant records elapsed ms or `NOT_RETURNED`.
 */
class QueryCancelSpikeTest {

    private val triggerAfterMs = 500L
    private val returnBudgetMs = 1000L
    private val giveUpMs = 4000L

    private class Outcome(val returned: Boolean, val elapsedSinceTriggerMs: Long, val error: String?, val stmt: java.sql.Statement?)

    /** Runs [SpikeSupport.RECURSIVE_CTE] on [conn], fires [trigger] after 500 ms, waits up to [giveUpMs] for the query thread. */
    private fun runVariant(conn: Connection, trigger: (Connection, java.sql.Statement) -> Unit): Outcome {
        val stmtRef = AtomicReference<java.sql.Statement>()
        val error = AtomicReference<String?>()
        val endNs = java.util.concurrent.atomic.AtomicLong()
        val query = thread(name = "runaway-query", isDaemon = true) {
            try {
                conn.createStatement().use { st ->
                    stmtRef.set(st)
                    st.executeQuery(SpikeSupport.RECURSIVE_CTE).use { it.next() }
                }
            } catch (e: SQLException) {
                error.set(e.message)
            } finally {
                endNs.set(System.nanoTime())
            }
        }
        Thread.sleep(triggerAfterMs)
        val triggerNs = System.nanoTime()
        thread(name = "trigger", isDaemon = true) { runCatching { trigger(conn, stmtRef.get()) } }
        query.join(giveUpMs)
        val returned = !query.isAlive
        return Outcome(returned, if (returned) (endNs.get() - triggerNs) / 1_000_000 else -1, error.get(), stmtRef.get())
    }

    private fun seeded(): Triple<File, File, app.cash.sqldelight.db.SqlDriver> {
        val dir = SpikeSupport.tempDir("spike03d")
        val db = dir.resolve("app.db")
        val app = DriverFactory().createDriver(SpikeSupport.url(db))
        SpikeSupport.seed(db, 1L, blocksPerPage = 1, contentBytes = 40)
        return Triple(dir, db, app)
    }

    private fun appStillUsable(app: app.cash.sqldelight.db.SqlDriver, id: String) {
        app.execute(null, "INSERT INTO pages(uuid,name,created_at,updated_at) VALUES ('$id','$id',1,1)", 0).value
    }

    @Test
    fun spike0_3d_should_ReturnWithin1s_When_StatementCancelFiresOnRecursiveCte() {
        val (dir, db, app) = seeded()
        try {
            val ro = SpikeSupport.readOnlyConnection(db)
            val o = runVariant(ro) { _, st -> st.cancel() }
            SpikeSupport.record("0.3d-jvm", "Statement.cancel", "returned=${o.returned} ms_after_trigger=${o.elapsedSinceTriggerMs} error=${o.error}")
            assertTrue(o.returned && o.elapsedSinceTriggerMs < returnBudgetMs, "Statement.cancel() did not stop the query within 1s: $o")
            // The same console connection is reusable after cancel; so is the app pool.
            val after = SpikeSupport.scalarLong(ro, "SELECT COUNT(*) FROM pages")
            appStillUsable(app, "after-cancel")
            SpikeSupport.record("0.3d-jvm", "Statement.cancel_connection_reusable", "count_after=$after")
            ro.close()
            val fresh = SpikeSupport.readOnlyConnection(db)
            assertEquals(after + 1, SpikeSupport.scalarLong(fresh, "SELECT COUNT(*) FROM pages"))
            fresh.close()
        } finally {
            app.close(); dir.deleteRecursively()
        }
    }

    @Test
    fun spike0_3d_should_RecordOutcome_When_ConnectionIsAbandonedAndClosedMidQuery() {
        val (dir, db, app) = seeded()
        try {
            val ro = SpikeSupport.readOnlyConnection(db)
            val closeMs = java.util.concurrent.atomic.AtomicLong(-1)
            val o = runVariant(ro) { c, _ ->
                val (_, ms) = SpikeSupport.timed { c.close() }
                closeMs.set(ms)
            }
            SpikeSupport.record("0.3d-jvm", "abandon_and_close", "returned=${o.returned} ms_after_trigger=${o.elapsedSinceTriggerMs} close_call_ms=${closeMs.get()} error=${o.error}")
            // Whatever happened, the app pool must be unaffected, and a fresh console handle must work.
            appStillUsable(app, "after-close")
            val fresh = SpikeSupport.readOnlyConnection(db)
            SpikeSupport.record("0.3d-jvm", "fresh_handle_after_abandon", SpikeSupport.scalarLong(fresh, "SELECT COUNT(*) FROM pages"))
            fresh.close()
            if (!o.returned) {
                // Do not leave a runaway query burning a core for the rest of the suite.
                runCatching { o.stmt?.cancel() }
            }
        } finally {
            app.close(); dir.deleteRecursively()
        }
    }

    @Test
    fun spike0_3d_should_RecordThreadInterruptIsNotEnough_When_OnlyInterruptingTheQueryThread() {
        val (dir, db, app) = seeded()
        try {
            val ro = SpikeSupport.readOnlyConnection(db)
            val stmtRef = AtomicReference<java.sql.Statement>()
            var queryThread: Thread? = null
            val finished = AtomicBoolean(false)
            val t = thread(isDaemon = true, name = "interrupt-target") {
                queryThread = Thread.currentThread()
                runCatching { ro.createStatement().also { stmtRef.set(it) }.use { it.executeQuery(SpikeSupport.RECURSIVE_CTE).use { rs -> rs.next() } } }
                finished.set(true)
            }
            Thread.sleep(triggerAfterMs)
            queryThread?.interrupt()
            t.join(giveUpMs)
            SpikeSupport.record("0.3d-jvm", "Thread.interrupt_only", "returned=${finished.get()}")
            assertTrue(!finished.get(), "Thread.interrupt alone unexpectedly stopped the native step")
            // Clean up with the mechanism that works.
            runCatching { stmtRef.get()?.cancel() }
            t.join(giveUpMs)
            ro.close()
        } finally {
            app.close(); dir.deleteRecursively()
        }
    }
}
