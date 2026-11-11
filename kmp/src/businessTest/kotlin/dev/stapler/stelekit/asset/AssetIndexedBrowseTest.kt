@file:OptIn(DirectRepositoryWrite::class)

package dev.stapler.stelekit.asset

import app.cash.sqldelight.db.SqlDriver
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import dev.stapler.stelekit.repository.SqlDelightLibraryRepository
import dev.stapler.stelekit.ui.library.LibraryQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 4.1 "Library reads real data": backfill into real SQLDelight, then the Library query returns the rows. */
class AssetIndexedBrowseTest {
    private lateinit var driver: SqlDriver
    private lateinit var repo: SqlDelightAssetRepository
    private lateinit var library: SqlDelightLibraryRepository
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)

    @BeforeTest
    fun setup() {
        driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val db = SteleDatabase(driver)
        repo = SqlDelightAssetRepository(db)
        library = SqlDelightLibraryRepository(db, driver)
    }

    @AfterTest
    fun teardown() = driver.close()

    @Test
    fun backfilledRows_appearInTheLibrary() = runBlocking {
        val fs = FakeFileSystem()
        repeat(7) { fs.writeFileBytes("/graph/assets/p$it.png", png + byteArrayOf(it.toByte())) }
        val service = AssetIndexService(repo)

        service.startBackfill(EffectiveNotesPath("/graph"), writeActor = null, fileSystem = fs)
        withTimeout(10_000) { service.progress.first { it is AssetIndexProgress.Finished } }
        val items = library.getLibraryPage(LibraryQuery(), null, 100).getOrNull()!!

        assertEquals(7, items.size)
        assertTrue(items.all { it.relativePath.startsWith("../assets/p") })
        assertEquals(7L, library.countLibrary(LibraryQuery()).getOrNull())
        service.close()
    }

    /** Pre-0.108 user in a release build: images sit in assets/ and the pipeline switch was never on. */
    @Test
    fun releaseBuildWithUnsetSwitch_existingImagesStillAppearInTheLibrary() = runBlocking {
        val fs = FakeFileSystem()
        repeat(4) { fs.writeFileBytes("/graph/assets/old$it.png", png + byteArrayOf(it.toByte())) }
        assertEquals(0L, library.countLibrary(LibraryQuery()).getOrNull(), "nothing is indexed before the walk")
        val controller = AssetIndexingController(
            dev.stapler.stelekit.wiring.FakeSettings(), AssetIndexService(repo), toggleVisibleProvider = { false },
        )

        controller.onGraphReady(EffectiveNotesPath("/graph"), writeActor = null, fileSystem = fs)
        withTimeout(10_000) { controller.progress.first { it is AssetIndexProgress.Finished } }

        assertEquals(4, library.getLibraryPage(LibraryQuery(), null, 100).getOrNull()!!.size)
        controller.close()
    }

    @Test
    fun indexedPathAndHashLookups_workOverRealSql_includingMoreThanOneChunk() = runBlocking {
        val fs = FakeFileSystem()
        fs.writeFileBytes("/graph/assets/one.png", png)
        val service = AssetIndexService(repo)
        val entry = service.registerAsset("/graph/assets/one.png", EffectiveNotesPath("/graph"), writeActor = null, fileSystem = fs).getOrNull()!!

        val probe = (1..1_200).map { "/graph/assets/missing$it.png" } + "/graph/assets/one.png"
        assertEquals(setOf("/graph/assets/one.png"), repo.getIndexedFilePaths(probe).getOrNull())
        assertEquals(entry.uuid, repo.findUuidByContentHash(entry.contentHash!!).getOrNull())
        assertNull(repo.findUuidByContentHash("0".repeat(64)).getOrNull())
        service.close()
    }
}
