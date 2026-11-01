// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import dev.stapler.stelekit.console.views.SqlConsoleError
import dev.stapler.stelekit.console.views.SqlEnd
import dev.stapler.stelekit.console.views.SqlLimits
import dev.stapler.stelekit.console.views.SqlResultBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Story 3.2 "bounded results and time": row and cell caps by cursor, the 10 s deadline, user cancel. Real engine, real time. */
class ConsoleBoundedStreamingTest {

    private fun usedHeapAfterGc(): Long {
        repeat(3) { System.gc() }
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test
    fun sqlSelectStar_should_ReturnAtMost500RowsAnd2KbCellsUnderHeapBudget_When_100kRowsWith1MbBlob() = runBlocking<Unit> {
        SqlConsoleFixture().use { fx ->
            DriverManager.getConnection(fx.url).use { c ->
                c.createStatement().use { st ->
                    st.execute("PRAGMA busy_timeout=10000")
                    st.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, s TEXT, b BLOB)")
                    st.execute(
                        "WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<100000) " +
                            "INSERT INTO t(id, s, b) SELECT i, 'row ' || i, x'00' FROM n",
                    )
                    // Row 2 carries a 1 MB text cell and a 1 MB blob.
                    st.execute("UPDATE t SET s = replace(hex(zeroblob(524288)), '0', 'a'), b = zeroblob(1048576) WHERE id = 2")
                }
            }
            fx.reader().using { reader ->
                val heapBefore = usedHeapAfterGc()
                val buffer = SqlResultBuffer(500)
                val result = reader.query("select * from t", buffer)
                assertEquals(SqlEnd.RowCap, result.getOrNull(), "$result")
                assertEquals(500, buffer.rows.size)
                assertTrue(buffer.truncated)
                val longestCell = buffer.rows.flatten().filterNotNull().maxOf { it.length }
                assertTrue(longestCell <= 2_048, "a cell kept $longestCell chars")
                assertEquals("<blob 1048576 bytes>", buffer.rows[1][2])
                val retained = usedHeapAfterGc() - heapBefore
                assertTrue(retained < 16L * 1024 * 1024, "retained ${retained / 1024} KiB after the query; budget 16384 KiB")

                val out = CapturedOutput()
                runSql(reader, "select * from t", out)
                assertTrue(out.lines.any { it.startsWith("TRUNCATED at 500 rows") }, out.lines.takeLast(2).toString())
            }
        }
    }

    @Test
    fun deadline_should_EndBlockWithinOneSecond_KeepPartialRows_And_NextQuerySucceeds_When_RecursiveCteRuns() = runBlocking<Unit> {
        SqlConsoleFixture().use { fx ->
            fx.reader().using { reader ->
                val buffer = SqlResultBuffer(500)
                val started = System.nanoTime()
                val result = reader.query(PARTIAL_THEN_RUNAWAY, buffer, SqlLimits(deadlineMs = 1_000))
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                assertEquals(SqlEnd.DeadlineExceeded, result.getOrNull(), "$result")
                assertTrue(elapsedMs in 900..2_000, "deadline fired after $elapsedMs ms for a 1000 ms limit")
                assertTrue(buffer.rows.isNotEmpty(), "partial rows were discarded")

                val next = SqlResultBuffer(5)
                assertEquals(SqlEnd.Completed, reader.query("select count(*) from pages", next).getOrNull())
                assertEquals("3", next.rows.single().single())
            }
        }
    }

    @Test
    fun deadlineThroughCommand_should_PrintStoppedAfterTextAndPartialRows() = runBlocking<Unit> {
        SqlConsoleFixture().use { fx ->
            fx.reader().using { reader ->
                val out = CapturedOutput()
                val result = runSql(reader, PARTIAL_THEN_RUNAWAY, out, SqlLimits(deadlineMs = 1_000))
                assertEquals("Stopped after 1 s. Partial rows kept.", result.leftOrNull()?.message)
                assertTrue(out.lines.contains("1"), "partial row 1 missing from ${out.lines}")
            }
        }
    }

    @Test
    fun cancel_should_EndWithin1sOfTrigger_KeepPartialRows_And_NextQuerySucceeds_When_UserCancelsAt2s() = runBlocking<Unit> {
        SqlConsoleFixture().use { fx ->
            fx.reader().using { reader ->
                val buffer = SqlResultBuffer(500)
                var cancelledAt = 0L
                var endedAt = 0L
                val job = launch(Dispatchers.Default) {
                    try {
                        reader.query(PARTIAL_THEN_RUNAWAY, buffer)
                    } catch (e: CancellationException) {
                        endedAt = System.nanoTime()
                        throw e
                    }
                }
                // Rows 1-3 arrive as soon as the statement runs; cancelling after that is cancelling a live statement.
                awaitRows(buffer)
                cancelledAt = System.nanoTime()
                job.cancel()
                job.join()
                val msToEnd = (endedAt - cancelledAt) / 1_000_000
                assertTrue(endedAt != 0L && msToEnd < 1_000, "block ended $msToEnd ms after cancel")
                assertTrue(buffer.rows.isNotEmpty(), "partial rows were discarded")

                val next = SqlResultBuffer(5)
                assertEquals(SqlEnd.Completed, reader.query("select count(*) from pages", next).getOrNull())
            }
        }
    }

    @Test
    fun query_should_ReturnFailedNotCrash_When_SqlIsInvalid() = runBlocking<Unit> {
        SqlConsoleFixture().use { fx ->
            fx.reader().using { reader ->
                val result = reader.query("select * from no_such_table", SqlResultBuffer(5))
                val error = result.leftOrNull()
                assertTrue(error is SqlConsoleError.Failed && error.engineMessage.contains("no such table"), "$result")
            }
        }
    }

    private suspend fun awaitRows(buffer: SqlResultBuffer) = withTimeout(10_000) { while (buffer.rows.isEmpty()) yield() }

    companion object {
        // Rows 1-3 stream out at once; the scan for the next match then runs until cancelled.
        const val PARTIAL_THEN_RUNAWAY =
            "WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM r WHERE n<1000000000) SELECT n FROM r WHERE n IN (1,2,3) OR n = 999999999"
    }
}
