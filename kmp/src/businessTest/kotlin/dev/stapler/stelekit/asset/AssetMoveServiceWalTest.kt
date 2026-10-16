@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.asset

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.db.DirectSqlWrite
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.db.RestrictedDatabaseQueries
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** WAL crash-recovery paths of [AssetMoveService.replayPendingMoves] over a real in-memory SQLite. */
class AssetMoveServiceWalTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: SteleDatabase
    private lateinit var restricted: RestrictedDatabaseQueries
    private val repo = InMemoryAssetRepository()
    private val fs = FakeFileSystem()

    private val oldPath = "/graph/assets/images/doc.pdf"
    private val newPath = "/graph/assets/pdfs/doc.pdf"
    private val newRel = "../assets/pdfs/doc.pdf"

    @BeforeTest
    fun setup() {
        driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        database = SteleDatabase(driver)
        restricted = RestrictedDatabaseQueries(database.steleDatabaseQueries)
    }

    @AfterTest
    fun teardown() = driver.close()

    @OptIn(DirectSqlWrite::class)
    private suspend fun seedWalRowAndAsset() {
        repo.saveAsset(testAsset("w1", filePath = oldPath, mediaType = AssetMediaType.PDF))
        restricted.insertPendingMove("w1", oldPath, newPath, "../assets/images/doc.pdf", newRel, 1L)
    }

    private fun walRowCount() = database.steleDatabaseQueries.selectAllPendingMoves().executeAsList().size

    private suspend fun replay() =
        AssetMoveService(repo, restricted).replayPendingMoves("/graph", fs, GraphWriter(fileSystem = fs), writeActor = null)

    private suspend fun storedPath() = repo.getAssetByUuid(AssetUuid("w1")).first().getOrNull()?.filePath

    @Test
    fun replay_onlyOldExists_completesRenameUpdatesDbAndClearsWal() = runBlocking {
        seedWalRowAndAsset()
        fs.writeFileBytes(oldPath, byteArrayOf(1))

        replay()

        assertTrue(fs.fileExists(newPath))
        assertFalse(fs.fileExists(oldPath))
        assertEquals(newPath, storedPath())
        assertEquals(0, walRowCount())
    }

    @Test
    fun replay_onlyNewExists_updatesDbAndClearsWalWithoutSecondRename() = runBlocking {
        seedWalRowAndAsset()
        fs.writeFileBytes(newPath, byteArrayOf(9))

        replay()

        assertEquals(newPath, storedPath())
        assertEquals(0, walRowCount())
        assertTrue(fs.readFileBytes(newPath)!!.contentEquals(byteArrayOf(9)))
    }

    @Test
    fun replay_neitherExists_clearsWalRowWithoutError() = runBlocking {
        seedWalRowAndAsset()

        replay()

        assertEquals(0, walRowCount())
        assertEquals(oldPath, storedPath())
    }

    @Test
    fun replay_bothExist_leavesWalRowAndFilesUntouched() = runBlocking {
        seedWalRowAndAsset()
        fs.writeFileBytes(oldPath, byteArrayOf(1))
        fs.writeFileBytes(newPath, byteArrayOf(2))

        replay()

        assertEquals(1, walRowCount())
        assertTrue(fs.fileExists(oldPath))
        assertTrue(fs.fileExists(newPath))
        assertEquals(oldPath, storedPath())
    }

    // --- WAL bookkeeping around moveAsset itself (restrictedQueries != null) ---

    private suspend fun move(subfolder: String): arrow.core.Either<dev.stapler.stelekit.error.DomainError, Unit> {
        val asset = testAsset("w1", filePath = oldPath, mediaType = AssetMediaType.PDF)
        repo.saveAsset(asset)
        return AssetMoveService(repo, restricted).moveAsset(asset, subfolder, "/graph", fs, GraphWriter(fileSystem = fs), writeActor = null)
    }

    // No "success clears the WAL row" test: RestrictedDatabaseQueries.lastInsertRowId() throws ClassCastException
    // (SimpleExecutableQuery -> Query), so insertWalEntry returns null and moveAsset never deletes the row.
    // Production bug, out of scope for this test-only change; add that test when it is fixed.

    @Test
    fun moveAsset_renameFails_leavesWalRowForReplay() = runBlocking {
        // old file absent -> rename returns false after the WAL row was written
        assertTrue(move("pdfs").isLeft())

        assertEquals(1, walRowCount())
        assertEquals(oldPath, storedPath())
    }

    @Test
    fun moveAsset_samePath_writesNoWalRow() = runBlocking {
        fs.writeFileBytes(oldPath, byteArrayOf(1))

        assertTrue(move("images").isRight())

        assertEquals(0, walRowCount())
    }
}
