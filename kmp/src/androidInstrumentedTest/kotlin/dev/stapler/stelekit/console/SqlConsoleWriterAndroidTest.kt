// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.db.ConsoleBackup
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.DryRunResult
import dev.stapler.stelekit.db.RestrictedDatabaseQueries
import dev.stapler.stelekit.db.SqlConsoleWriter
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.db.WriteResult
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The `sql!` write path on the Android driver: dry run rolls back, counts include cascades, backup verifies. */
@RunWith(AndroidJUnit4::class)
class SqlConsoleWriterAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var url: String
    private lateinit var app: SqlDriver
    private lateinit var actor: DatabaseWriteActor
    private lateinit var writer: SqlConsoleWriter

    @Before
    fun setUp() {
        DriverFactory.setContext(context)
        dir = File(context.cacheDir, "sql-writer-${System.nanoTime()}").also { it.mkdirs() }
        url = "jdbc:sqlite:${dir.resolve("graph.db").absolutePath}"
        app = DriverFactory().createDriver(url)
        actor = DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository())
        writer = SqlConsoleWriter(
            RestrictedDatabaseQueries(SteleDatabase(app).steleDatabaseQueries, app),
            { invalidate, op -> actor.execute(invalidate = invalidate, op = op) },
        )
        repeat(PAGES) { exec("INSERT INTO pages(uuid,name,created_at,updated_at) VALUES ('page-$it','Page $it',1,1)") }
        repeat(BLOCKS) { exec("INSERT INTO blocks(uuid,page_uuid,content,position,created_at,updated_at) VALUES ('b$it','page-0','zebra $it','a0',1,1)") }
    }

    @After
    fun tearDown() {
        actor.close()
        app.close()
        dir.deleteRecursively()
    }

    private fun exec(sql: String) {
        app.execute(null, sql, 0).value
    }

    private fun count(sql: String): Long = app.executeQuery(null, sql, { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) }, 0).value

    @Test
    fun dryRun_should_CountDirectAndCascadedRowsAndRollBack_When_PageDeleteRunsOnAndroidDriver() = runBlocking<Unit> {
        val result = writer.dryRun("delete from pages where uuid = 'page-0'", "pages")
        val m = assertIs<DryRunResult.Measured>(result).measurement
        assertEquals(1, m.changes)
        assertEquals(PAGES.toLong(), m.targetRows)
        assertEquals(listOf(TableDelta("blocks", -BLOCKS.toLong())), m.otherTables)
        assertEquals(PAGES.toLong(), count("SELECT COUNT(*) FROM pages"))
        assertEquals(BLOCKS.toLong(), count("SELECT COUNT(*) FROM blocks"))
        assertEquals(BLOCKS.toLong(), count("SELECT COUNT(*) FROM blocks_fts WHERE blocks_fts MATCH 'zebra'"))
    }

    @Test
    fun write_should_KeepFtsInSyncAndRollBackWhenStale_When_RunOnAndroidDriver() = runBlocking<Unit> {
        val standard = ConfirmDecision.Confirm(ConfirmTier.Standard, 1, triggered = false, tables = emptyList())
        assertEquals(WriteResult.Done(1), writer.write("update blocks set content = 'quokka' where uuid = 'b0'", "blocks", BLOCKS.toLong(), standard))
        assertEquals(1, count("SELECT COUNT(*) FROM blocks_fts WHERE blocks_fts MATCH 'quokka'"))
        assertEquals(BLOCKS - 1L, count("SELECT COUNT(*) FROM blocks_fts WHERE blocks_fts MATCH 'zebra'"), "the replaced content leaves no stale index row")

        val stale = writer.write("update blocks set content = 'emu'", "blocks", BLOCKS.toLong(), standard)
        assertEquals(WriteResult.Stale(now = BLOCKS.toLong(), confirmed = 1), stale)
        assertEquals(0, count("SELECT COUNT(*) FROM blocks WHERE content = 'emu'"))
    }

    @Test
    fun backup_should_BeVerifiedAndPruned_When_RunOnAndroidHandles() = runBlocking<Unit> {
        val backup = checkNotNull(ConsoleBackup.forJdbcUrl(url))
        val first = backup.backup().getOrNull() ?: error("first backup failed")
        val second = backup.backup().getOrNull() ?: error("second backup failed")
        val third = backup.backup().getOrNull() ?: error("third backup failed")
        val kept = File(dir, ConsoleBackup.DIRECTORY_NAME).listFiles().orEmpty().map { it.path }.sorted()
        assertEquals(listOf(second, third).sorted(), kept)
        assertTrue(!File(first).exists())
        assertTrue(File(third).length() > 0)
    }

    private companion object {
        const val PAGES = 3
        const val BLOCKS = 12
    }
}
