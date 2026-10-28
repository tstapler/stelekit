// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.stapler.stelekit.console.spike.AndroidSpikeSupport.RECURSIVE_CTE
import dev.stapler.stelekit.console.spike.AndroidSpikeSupport.record
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertTrue

/**
 * Spike 0.3d, Android half: which mechanism stops a runaway `WITH RECURSIVE` on an `OPEN_READONLY` handle within 1 s,
 * for the requery fork (`androidx.core.os.CancellationSignal`) and for platform SQLite (`android.os.CancellationSignal`)?
 * Also measures abandon-and-close and plain `Thread.interrupt`, and checks the app's pooled driver afterwards.
 */
@RunWith(AndroidJUnit4::class)
class QueryCancelSpikeAndroidTest {

    private val spike = "0.3d-android"
    private val triggerAfterMs = 500L
    private val giveUpMs = 5000L

    private class Result(val returned: Boolean, val msAfterTrigger: Long, val error: String?)

    /** Runs [query] on a thread, calls [trigger] after 500 ms, and reports whether/when the thread ended. */
    private fun run(query: () -> Unit, trigger: () -> Unit): Result {
        val end = AtomicLong()
        val err = AtomicReference<String?>()
        val t = thread(isDaemon = true, name = "runaway") {
            try { query() } catch (e: Throwable) { err.set("${e.javaClass.simpleName}: ${e.message}") } finally { end.set(System.nanoTime()) }
        }
        Thread.sleep(triggerAfterMs)
        val trig = System.nanoTime()
        thread(isDaemon = true) { runCatching { trigger() } }
        t.join(giveUpMs)
        val returned = !t.isAlive
        return Result(returned, if (returned) (end.get() - trig) / 1_000_000 else -1, err.get())
    }

    private fun fmt(r: Result) = "returned=${r.returned} ms_after_trigger=${r.msAfterTrigger} error=${r.error}"

    private fun afterChecks(label: String, app: app.cash.sqldelight.db.SqlDriver, db: File) {
        AndroidSpikeSupport.appInsertPage(app, "after-$label")
        val fresh = AndroidSpikeSupport.requeryReadOnly(db)
        record(spike, "$label.fresh_handle_and_app_write_ok", AndroidSpikeSupport.scalarLong(fresh, "SELECT COUNT(*) FROM pages"))
        fresh.close()
    }

    private inline fun withDb(label: String, block: (File, app.cash.sqldelight.db.SqlDriver) -> Unit) {
        val dir = AndroidSpikeSupport.freshDir(label)
        val db = dir.resolve("app.db")
        val app = AndroidSpikeSupport.appDriver(db)
        try {
            AndroidSpikeSupport.appInsertPage(app, "seed")
            block(db, app)
        } finally {
            app.close(); dir.deleteRecursively()
        }
    }

    @Test
    fun spike0_3d_should_ReturnWithin1s_When_RequeryCancellationSignalFires() = withDb("rq-signal") { db, app ->
        val rq = AndroidSpikeSupport.requeryReadOnly(db)
        val signal = androidx.core.os.CancellationSignal()
        val r = run({ rq.rawQuery(RECURSIVE_CTE, null, signal).use { it.moveToFirst() } }, { signal.cancel() })
        record(spike, "requery.CancellationSignal", fmt(r))
        runCatching { signal.cancel() }
        runCatching { rq.close() }
        afterChecks("requery-signal", app, db)
        assertTrue(r.returned && r.msAfterTrigger < 1000, "requery CancellationSignal did not stop the query within 1s: ${fmt(r)}")
    }

    @Test
    fun spike0_3d_should_ReturnWithin1s_When_PlatformCancellationSignalFires() = withDb("pf-signal") { db, app ->
        val pf = AndroidSpikeSupport.platformReadOnly(db)
        val signal = android.os.CancellationSignal()
        val r = run({ pf.rawQuery(RECURSIVE_CTE, null, signal).use { it.moveToFirst() } }, { signal.cancel() })
        record(spike, "platform.CancellationSignal", fmt(r))
        runCatching { signal.cancel() }
        runCatching { pf.close() }
        afterChecks("platform-signal", app, db)
        assertTrue(r.returned && r.msAfterTrigger < 1000, "platform CancellationSignal did not stop the query within 1s: ${fmt(r)}")
    }

    @Test
    fun spike0_3d_should_RecordOutcome_When_RequeryHandleIsAbandonedAndClosedMidQuery() = withDb("rq-close") { db, app ->
        val rq = AndroidSpikeSupport.requeryReadOnly(db)
        val signal = androidx.core.os.CancellationSignal()
        val closeMs = AtomicLong(-1)
        val closeThrew = AtomicReference<String?>()
        val r = run({ rq.rawQuery(RECURSIVE_CTE, null, signal).use { it.moveToFirst() } }, {
            val (_, ms) = timedMs { try { rq.close() } catch (e: Throwable) { closeThrew.set("${e.javaClass.simpleName}: ${e.message}") } }
            closeMs.set(ms)
        })
        record(spike, "requery.abandon_and_close", "${fmt(r)} close_call_ms=${closeMs.get()} close_threw=${closeThrew.get()}")
        signal.cancel() // stop the runaway if close did not
        Thread.sleep(500)
        afterChecks("requery-close", app, db)
    }

    @Test
    fun spike0_3d_should_RecordOutcome_When_PlatformHandleIsAbandonedAndClosedMidQuery() = withDb("pf-close") { db, app ->
        val pf = AndroidSpikeSupport.platformReadOnly(db)
        val signal = android.os.CancellationSignal()
        val closeMs = AtomicLong(-1)
        val closeThrew = AtomicReference<String?>()
        val r = run({ pf.rawQuery(RECURSIVE_CTE, null, signal).use { it.moveToFirst() } }, {
            val (_, ms) = timedMs { try { pf.close() } catch (e: Throwable) { closeThrew.set("${e.javaClass.simpleName}: ${e.message}") } }
            closeMs.set(ms)
        })
        record(spike, "platform.abandon_and_close", "${fmt(r)} close_call_ms=${closeMs.get()} close_threw=${closeThrew.get()}")
        signal.cancel()
        Thread.sleep(500)
        afterChecks("platform-close", app, db)
    }

    @Test
    fun spike0_3d_should_RecordThreadInterruptIsNotEnough_When_OnlyInterruptingTheQueryThread() = withDb("interrupt") { db, app ->
        val rq = AndroidSpikeSupport.requeryReadOnly(db)
        val signal = androidx.core.os.CancellationSignal()
        val finished = AtomicBoolean(false)
        val target = AtomicReference<Thread>()
        val t = thread(isDaemon = true) {
            target.set(Thread.currentThread())
            runCatching { rq.rawQuery(RECURSIVE_CTE, null, signal).use { it.moveToFirst() } }
            finished.set(true)
        }
        Thread.sleep(triggerAfterMs)
        target.get()?.interrupt()
        t.join(giveUpMs)
        val stoppedByInterrupt = finished.get()
        record(spike, "requery.Thread.interrupt_only", "returned=$stoppedByInterrupt")
        signal.cancel()
        t.join(giveUpMs)
        runCatching { rq.close() }
        afterChecks("interrupt", app, db)
        assertTrue(!stoppedByInterrupt, "Thread.interrupt alone unexpectedly stopped the native step")
    }
}
