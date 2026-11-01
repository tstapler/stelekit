// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import android.content.ContentValues
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.console.commands.SqlCommand
import dev.stapler.stelekit.console.views.SqlConsoleError
import dev.stapler.stelekit.console.views.SqlEnd
import dev.stapler.stelekit.console.views.SqlLimits
import dev.stapler.stelekit.console.views.SqlResultBuffer
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SqlConsoleReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * IT-11: the Story 3.2 bypass suite on the Android console handle (requery `OPEN_READONLY`), agreeing with the JVM
 * `SqlConsoleBypassTest`. Evidence lines are published to `Download/sql-console-android-<runId>.txt` because the result XML
 * has no stdout and the owner cannot use logcat.
 */
@RunWith(AndroidJUnit4::class)
class SqlConsoleReaderAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var url: String
    private lateinit var app: SqlDriver

    @Before
    fun setUp() {
        DriverFactory.setContext(context)
        dir = File(context.cacheDir, "sql-console-${System.nanoTime()}").also { it.mkdirs() }
        url = "jdbc:sqlite:${dir.resolve("graph.db").absolutePath}"
        app = DriverFactory().createDriver(url)
        repeat(3) { insertPage("page-$it", "Page $it") }
    }

    @After
    fun tearDown() {
        app.close()
        dir.deleteRecursively()
    }

    private fun insertPage(uuid: String, name: String) {
        app.execute(null, "INSERT INTO pages(uuid,name,created_at,updated_at) VALUES ('$uuid','$name',1,1)", 0).value
    }

    private fun tableCounts(): Map<String, Long> {
        val names = mutableListOf<String>()
        app.executeQuery(null, "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", { c ->
            while (c.next().value) names += c.getString(0)!!
            QueryResult.Value(Unit)
        }, 0).value
        return names.associateWith { n -> app.executeQuery(null, "SELECT COUNT(*) FROM \"$n\"", { c -> c.next(); QueryResult.Value(c.getLong(0)!!) }, 0).value }
    }

    private fun reader(): SqlConsoleReader = SqlConsoleReader(url)

    private fun mutations() = listOf(
        "UPDATE pages SET name='x'", "DELETE FROM blocks", "WITH c AS (SELECT 1) DELETE FROM pages",
        "INSERT INTO pages(uuid,name,created_at,updated_at) VALUES('u','n',1,1) RETURNING uuid", "SELECT 1; DELETE FROM pages",
        "PRAGMA writable_schema=1", "PRAGMA query_only=0", "PRAGMA journal_mode=DELETE",
        "ATTACH DATABASE '${dir.resolve("x.db").absolutePath}' AS x", "VACUUM INTO '${dir.resolve("y.db").absolutePath}'",
        "SELECT load_extension('x')", "CREATE TABLE t(a)", "DROP TABLE pages",
        "REPLACE INTO pages(uuid,name,created_at,updated_at) VALUES('u','n',1,1)", "/**/dElEtE/**/ FROM pages",
    )

    private suspend fun run(r: SqlConsoleReader, statement: String) =
        SqlCommand { r }.run(listOf(statement), ConsoleContext(ConsoleRegistry(emptyList())), object : ConsoleOutput {
            override fun info(text: String) = Unit
            override fun error(text: String) = Unit
        })

    @Test
    fun bypassSuite_should_AgreeWithJvm_When_RunOnAndroidOpenReadonlyHandle() = runBlocking {
        val r = reader()
        try {
            val before = tableCounts()
            for (s in mutations()) {
                val error = run(r, s).leftOrNull()
                record("through_sql", "${s.take(40)} -> ${error?.message?.take(80)}")
                assertTrue(error != null, "accepted: $s")
                assertEquals(before, tableCounts(), "counts changed after: $s")
            }
            assertTrue(!dir.resolve("x.db").exists() && !dir.resolve("y.db").exists(), "side file created")
            assertEquals("Denied: plain sql is read-only. Use sql! for writes.", run(r, "UPDATE pages SET name='x'").leftOrNull()?.message)
            assertEquals("Denied: plain sql is read-only. Use sql! for writes.", run(r, "WITH c AS (SELECT 1) DELETE FROM pages").leftOrNull()?.message)
            record("bypass", "15 statements rejected, counts unchanged, no side files")
        } finally {
            r.close()
        }
    }

    @Test
    fun engineAlone_should_RejectDmlAndRecordWhatItLeavesOpen_When_VerbGuardIsOff() = runBlocking {
        val r = SqlConsoleReader(open = { dev.stapler.stelekit.db.openSqlReadHandle(url) }, useStatementGuard = false)
        try {
            val before = tableCounts()
            for (s in listOf("UPDATE pages SET name='x'", "DELETE FROM blocks", "WITH c AS (SELECT 1) DELETE FROM pages", "CREATE TABLE t(a)", "DROP TABLE pages")) {
                val result = r.query(s, SqlResultBuffer(5))
                assertIs<SqlConsoleError.WriteRefused>(result.leftOrNull(), "engine accepted $s -> $result")
            }
            val copy = dir.resolve("copy.db")
            val vacuum = r.query("VACUUM INTO '${copy.absolutePath}'", SqlResultBuffer(5))
            val pragma = r.query("PRAGMA query_only=0", SqlResultBuffer(5))
            record("engine_alone", "vacuum_into=$vacuum file_created=${copy.exists()} pragma_assign=$pragma")
            assertEquals(before, tableCounts())
        } finally {
            r.close()
        }
    }

    @Test
    fun rows_should_BeCapped_And_AppPoolStayWritable_When_ConsoleHandleOpenThenClosed() = runBlocking {
        val r = reader()
        val buffer = SqlResultBuffer(2)
        assertEquals(SqlEnd.RowCap, r.query("select name from pages order by name", buffer).getOrNull())
        assertEquals(listOf(listOf("Page 0"), listOf("Page 1")), buffer.rows)
        assertTrue(buffer.truncated)
        r.close()
        repeat(16) { insertPage("after-$it", "After $it") }
        assertEquals(19L, tableCounts().getValue("pages"))
        record("pool", "16 app writes after console handle closed ok")
    }

    @Test
    fun deadlineAndCancelAndClose_should_StopRunningStatementViaCancellationSignal() = runBlocking {
        val runaway = "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM r WHERE n<1000000000) SELECT n FROM r WHERE n IN (1,2,3) OR n = 999999999"
        val r = reader()
        try {
            val deadlineBuffer = SqlResultBuffer(500)
            val t0 = System.nanoTime()
            val ended = r.query(runaway, deadlineBuffer, SqlLimits(deadlineMs = 1_000))
            val deadlineMs = (System.nanoTime() - t0) / 1_000_000
            record("deadline", "end=${ended.getOrNull()} ms=$deadlineMs partial_rows=${deadlineBuffer.rows.size}")
            assertEquals(SqlEnd.DeadlineExceeded, ended.getOrNull())
            assertTrue(deadlineMs in 900..2_000, "deadline at $deadlineMs ms")
            // Android fills a CursorWindow before returning the first row, so a long scan delivers no partial rows (JVM does).

            val cancelBuffer = SqlResultBuffer(500)
            var endedAt = 0L
            val job = launch(Dispatchers.Default) {
                try { r.query(runaway, cancelBuffer) } catch (e: kotlinx.coroutines.CancellationException) { endedAt = System.nanoTime(); throw e }
            }
            spinFor(2_000)
            val cancelAt = System.nanoTime()
            job.cancel(); job.join()
            val cancelMs = (endedAt - cancelAt) / 1_000_000
            record("cancel", "ms_after_trigger=$cancelMs partial_rows=${cancelBuffer.rows.size}")
            assertTrue(endedAt != 0L && cancelMs < 1_000, "cancel took $cancelMs ms")
            assertEquals(SqlEnd.Completed, r.query("select count(*) from pages", SqlResultBuffer(5)).getOrNull())

            val closing = CompletableDeferred<arrow.core.Either<SqlConsoleError, SqlEnd>>()
            val closeBuffer = SqlResultBuffer(5)
            launch(Dispatchers.Default) { closing.complete(r.query(runaway, closeBuffer)) }
            spinFor(500)
            val c0 = System.nanoTime()
            r.close()
            val result = withTimeout(5_000) { closing.await() }
            val closeMs = (System.nanoTime() - c0) / 1_000_000
            record("close_mid_query", "ms=$closeMs result=$result")
            assertEquals(SqlConsoleError.GraphClosed, result.leftOrNull())
            assertTrue(closeMs < 2_000)
            insertPage("still-writable", "ok")
        } finally {
            r.close()
        }
    }

    /** Nothing observable says the statement is mid-scan on Android (no partial rows), so let real time pass without a delay(). */
    private suspend fun spinFor(ms: Long) {
        val until = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < until) yield()
    }

    private val runId = System.currentTimeMillis()

    private fun record(key: String, value: Any?) {
        val line = "SQL-CONSOLE-ANDROID $key=$value"
        synchronized(lines) { lines += line }
        Log.i("SqlConsoleAndroid", line)
        runCatching { publish() }.onFailure { Log.w("SqlConsoleAndroid", "publish failed: $it") }
    }

    private fun publish() {
        val name = "sql-console-android-$runId.txt"
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.delete(collection, "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
        }
        val uri = checkNotNull(resolver.insert(collection, values))
        resolver.openOutputStream(uri)!!.use { it.write(synchronized(lines) { lines.joinToString("\n") }.toByteArray()) }
    }

    private companion object {
        val lines = mutableListOf<String>()
    }
}
