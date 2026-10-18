// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.db

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.RepoRoot
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The database half of [ShareWritePathMigration]: row repointing and the retry state in `metadata`. */
class ShareWritePathMigrationTest {
    private val root = "/graphs/g"
    private val notes = "$root/logseq"
    private val graphId = "share-write-path-migration-${System.nanoTime()}"
    private val fs = FakeRelocationFileSystem()
    private lateinit var driver: SqlDriver
    private lateinit var db: SteleDatabase
    private lateinit var actor: DatabaseWriteActor

    @BeforeTest
    fun setUp() {
        driver = DriverFactory().createDriver(DriverFactory().getDatabaseUrl(graphId))
        db = SteleDatabase(driver)
        actor = DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository())
    }

    @AfterTest
    fun tearDown() {
        actor.close()
        driver.close()
    }

    private fun insertPage(uuid: String, filePath: String) {
        driver.execute(
            null,
            "INSERT INTO pages (uuid, name, file_path, created_at, updated_at) VALUES (?, ?, ?, 0, 0)",
            3,
        ) {
            bindString(0, uuid)
            bindString(1, uuid)
            bindString(2, filePath)
        }
    }

    private fun filePathOf(uuid: String): String? =
        db.steleDatabaseQueries.selectPageUuidAndFilePath().executeAsList().firstOrNull { it.uuid == uuid }?.file_path

    private fun state(): String? = db.steleDatabaseQueries.selectMetadata(ShareWritePathMigration.METADATA_KEY).executeAsOneOrNull()

    private fun migrate() = runBlocking { ShareWritePathMigration(actor, fs).runIfNeeded(db, RepoRoot(root), EffectiveNotesPath(notes)) }

    @Test
    fun runIfNeeded_should_RepointRowAndMarkDone_When_FileMoves() {
        fs.writeFile("$root/journals/2026_10_16.md", "- shared\n")
        insertPage("p1", "$root/journals/2026_10_16.md")

        migrate()

        assertEquals("$notes/journals/2026_10_16.md", filePathOf("p1"))
        assertEquals(ShareWritePathMigration.STATE_DONE, state())
    }

    @Test
    fun runIfNeeded_should_LeaveStateUnsetAndRowUntouched_When_CopiesConflict() {
        fs.writeFile("$root/journals/2026_10_16.md", "title:: phone\n- x\n")
        fs.writeFile("$notes/journals/2026_10_16.md", "title:: desktop\n- y\n")
        insertPage("p1", "$root/journals/2026_10_16.md")

        migrate()

        assertEquals("$root/journals/2026_10_16.md", filePathOf("p1"))
        assertEquals(ShareWritePathMigration.STATE_OPEN, state(), "a conflict must retry the whole pass on the next open")
    }

    @Test
    fun runIfNeeded_should_StoreUnresolvedAssets_AndRetryThemWithoutPageRows() {
        fs.writeFile("$root/journals/2026_10_16.md", "- ![x](../assets/a.jpg)\n")
        fs.writeFileBytes("$root/assets/a.jpg", byteArrayOf(1))
        fs.writeFileBytes("$notes/assets/a.jpg", byteArrayOf(2))
        insertPage("p1", "$root/journals/2026_10_16.md")

        migrate()
        assertEquals("$notes/journals/2026_10_16.md", filePathOf("p1"))
        assertEquals(ShareWritePathMigration.STATE_DONE + "\na.jpg", state())

        fs.deleteFile("$notes/assets/a.jpg")
        migrate()

        assertTrue(fs.fileExists("$notes/assets/a.jpg"))
        assertEquals(ShareWritePathMigration.STATE_DONE, state())
    }

    @Test
    fun runIfNeeded_should_DoNothing_When_GraphHasNoWikiSubdir() {
        fs.writeFile("$root/journals/2026_10_16.md", "- x\n")
        insertPage("p1", "$root/journals/2026_10_16.md")

        runBlocking { ShareWritePathMigration(actor, fs).runIfNeeded(db, RepoRoot(root), EffectiveNotesPath(root)) }

        assertEquals("$root/journals/2026_10_16.md", filePathOf("p1"))
        assertNull(state(), "the subdir may not be detected yet, so nothing may be stored")
    }

    @Test
    fun runIfNeeded_should_NotTouchFiles_When_AlreadyDone() = runBlocking {
        db.steleDatabaseQueries.upsertMetadata(ShareWritePathMigration.METADATA_KEY, ShareWritePathMigration.STATE_DONE)
        fs.writeFile("$root/journals/2026_10_16.md", "- x\n")
        insertPage("p1", "$root/journals/2026_10_16.md")

        ShareWritePathMigration(actor, fs).runIfNeeded(db, RepoRoot(root), EffectiveNotesPath(notes))

        assertEquals("$root/journals/2026_10_16.md", filePathOf("p1"))
        assertTrue(fs.fileExists("$root/journals/2026_10_16.md"))
    }

    @Test
    fun runIfNeeded_should_RetryPendingAssetOnNextOpen_When_AnotherPageStillConflicts() {
        fs.writeFile("$root/journals/a.md", "- ![x](../assets/x.jpg)\n")
        fs.writeFileBytes("$root/assets/x.jpg", byteArrayOf(1))
        fs.writeFileBytes("$notes/assets/x.jpg", byteArrayOf(2))
        fs.writeFile("$root/journals/b.md", "title:: phone\n- x\n")
        fs.writeFile("$notes/journals/b.md", "title:: desktop\n- y\n")
        insertPage("a", "$root/journals/a.md")
        insertPage("b", "$root/journals/b.md")

        migrate()
        assertEquals(ShareWritePathMigration.STATE_OPEN + "\nx.jpg", state())

        fs.deleteFile("$notes/assets/x.jpg")
        fs.deleteFile("$notes/journals/b.md")
        migrate()

        assertTrue(fs.fileExists("$notes/assets/x.jpg"), "the asset must survive a page conflict in the first run")
        assertEquals(ShareWritePathMigration.STATE_DONE, state())
    }

    @Test
    fun runIfNeeded_should_MergeBothCopiesRepointRowAndMarkDone_When_PagesDifferButCombine() {
        fs.writeFile("$root/journals/2026_10_16.md", "- phone note\n")
        fs.writeFile("$notes/journals/2026_10_16.md", "- desktop note\n")
        insertPage("p1", "$root/journals/2026_10_16.md")

        migrate()

        assertEquals("$notes/journals/2026_10_16.md", filePathOf("p1"))
        assertEquals(ShareWritePathMigration.STATE_DONE, state())
        val merged = fs.readFile("$notes/journals/2026_10_16.md")!!
        assertTrue(merged.contains("- desktop note") && merged.contains("- phone note"), merged)
        assertFalse(fs.fileExists("$root/journals/2026_10_16.md"))
    }
}
