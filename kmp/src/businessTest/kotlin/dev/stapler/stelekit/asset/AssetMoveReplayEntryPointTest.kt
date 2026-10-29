@file:OptIn(DirectRepositoryWrite::class, DirectSqlWrite::class)

package dev.stapler.stelekit.asset

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DirectSqlWrite
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.db.RestrictedDatabaseQueries
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.ui.assets.AssetDetailViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 4.2 through the same objects `GraphContentAssetSetup` constructs: the move writes through
 * `GraphWriter` and the actor, the WAL row is cleared, a crash between rename and rewrite heals at
 * open, and a double tap on Move performs one move.
 */
class AssetMoveReplayEntryPointTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: SteleDatabase
    private lateinit var restricted: RestrictedDatabaseQueries
    private val repo = InMemoryAssetRepository()
    private val fs = FakeFileSystem()
    private val notes = EffectiveNotesPath("/graph")
    private val actor = DatabaseWriteActor(InMemoryBlockRepository(), InMemoryPageRepository())

    private val oldPath = "/graph/assets/a.png"
    private val oldRel = "../assets/a.png"
    private val newPath = "/graph/assets/photos/a.png"
    private val newRel = "../assets/photos/a.png"
    private val pageFile = "/graph/pages/Trip.md"

    @BeforeTest
    fun setup() {
        driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        database = SteleDatabase(driver)
        restricted = RestrictedDatabaseQueries(database.steleDatabaseQueries)
        fs.writeFile(pageFile, "- ![shot]($oldRel)\n")
    }

    @AfterTest
    fun teardown() {
        driver.close()
    }

    private fun walRows() = database.steleDatabaseQueries.selectAllPendingMoves().executeAsList().size
    private suspend fun storedPath(uuid: String) = repo.getAssetByUuid(AssetUuid(uuid)).first().getOrNull()?.filePath

    private suspend fun seedAsset(): AssetEntry =
        testAsset("m1", filePath = oldPath, subfolder = "images").copy(relativePath = oldRel).also {
            repo.saveAsset(it)
            fs.writeFileBytes(oldPath, byteArrayOf(1))
        }

    @Test
    fun moveAsset_throughActor_movesFile_rewritesPageViaGraphWriter_andClearsWal() = runBlocking {
        val asset = seedAsset()
        val service = AssetMoveService(repo, restricted)

        val result = service.moveAsset(asset, "images", notes, fs, GraphWriter(fileSystem = fs), actor)
        assertTrue(result.isRight())
        // "images" is a different target from the file's flat location, so the file really moves.
        assertEquals("/graph/assets/images/a.png", storedPath("m1"))
        assertTrue(fs.fileExists("/graph/assets/images/a.png"))
        assertFalse(fs.fileExists(oldPath))
        assertEquals("- ![shot](../assets/images/a.png)\n", fs.readFile(pageFile))
        assertEquals(0, walRows(), "a completed move must clear its WAL row")
    }

    @Test
    fun replayAtOpen_completesInterruptedMove_rewritesReferencesThroughGraphWriter_andClearsWal() = runBlocking {
        seedAsset()
        // Crash after the WAL insert, before the rename.
        restricted.insertPendingMove("m1", oldPath, newPath, oldRel, newRel, 1L)
        assertEquals(1, walRows())

        AssetMoveService(repo, restricted).replayPendingMoves(notes, fs, GraphWriter(fileSystem = fs), actor)

        assertTrue(fs.fileExists(newPath))
        assertFalse(fs.fileExists(oldPath))
        assertEquals(newPath, storedPath("m1"))
        assertEquals("- ![shot]($newRel)\n", fs.readFile(pageFile))
        assertEquals(0, walRows())
    }

    @Test
    fun doubleTapOnMove_performsOneMove() = runBlocking {
        val asset = seedAsset()
        val calls = AtomicInteger()
        val release = CompletableDeferred<Unit>()
        val vm = AssetDetailViewModel(
            assetRepository = repo,
            assetUuid = asset.uuid,
            mover = { _, _ ->
                calls.incrementAndGet()
                release.await()
                arrow.core.Either.Right(Unit)
            },
        )
        withTimeout(5_000) { while (vm.uiState.value.asset == null) delay(10) }

        vm.moveTo("pdfs")
        vm.moveTo("pdfs")
        withTimeout(5_000) { while (calls.get() == 0) delay(5) }
        delay(100)
        assertEquals(1, calls.get(), "second tap while a move is in flight is ignored")

        release.complete(Unit)
        withTimeout(5_000) { while (vm.uiState.value.isMoving) delay(5) }
        vm.moveTo("pdfs")
        withTimeout(5_000) { while (calls.get() < 2) delay(5) }
        vm.onForgotten()
    }
}
