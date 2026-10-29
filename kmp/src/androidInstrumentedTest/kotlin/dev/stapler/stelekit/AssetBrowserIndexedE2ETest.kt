// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stapler.stelekit.asset.AssetIndexProgress
import dev.stapler.stelekit.asset.AssetIndexService
import dev.stapler.stelekit.asset.AssetMoveService
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.db.RestrictedDatabaseQueries
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.platform.SteleKitContext
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import dev.stapler.stelekit.ui.assets.AssetBrowserScreen
import dev.stapler.stelekit.ui.assets.AssetBrowserViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device proof for Story 4.1/4.2: files on the real Android file system are backfilled into
 * `asset_index` through the real Android SQLite driver, the Assets screen shows them, and a move
 * clears its WAL row.
 *
 * Run: ./gradlew :kmp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.stapler.stelekit.AssetBrowserIndexedE2ETest
 */
@RunWith(AndroidJUnit4::class)
class AssetBrowserIndexedE2ETest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val dbName = "asset-indexed-e2e-${System.nanoTime()}.db"
    private lateinit var root: File
    private lateinit var fs: PlatformFileSystem
    private lateinit var database: SteleDatabase
    private lateinit var repo: SqlDelightAssetRepository

    @Before
    fun setUp() {
        SteleKitContext.init(context)
        DriverFactory.setContext(context)
        // PlatformFileSystem only reaches paths under filesDir/graphs (app-owned graph storage).
        root = File(context.filesDir, "graphs/asset-indexed-e2e-${System.nanoTime()}").apply { mkdirs() }
        fs = PlatformFileSystem().apply { init(context) }
        database = SteleDatabase(DriverFactory().createDriver("jdbc:sqlite:$dbName"))
        repo = SqlDelightAssetRepository(database)
        File(root, "assets/photos").mkdirs()
        File(root, "pages").mkdirs()
        File(root, "assets/trip.png").writeBytes(PNG + 1)
        File(root, "assets/photos/beach.png").writeBytes(PNG + 2)
        File(root, "assets/readme.txt").writeText("hello")
        File(root, "pages/Trip.md").writeText("- ![trip](../assets/trip.png)\n")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        context.deleteDatabase(dbName)
    }

    private suspend fun backfill(service: AssetIndexService, notes: EffectiveNotesPath) {
        service.startBackfill(notes, writeActor = null, fileSystem = fs)
        withTimeout(30_000) { service.progress.first { it is AssetIndexProgress.Finished } }
    }

    @Test
    fun backfillIndexesRealFiles_andTheAssetsScreenShowsThem() {
        val notes = EffectiveNotesPath(root.absolutePath)
        val service = AssetIndexService(repo)
        runBlocking { backfill(service, notes) }

        assertEquals(3L, runBlocking { repo.countAssets().getOrNull() })
        val vm = AssetBrowserViewModel(repo)
        composeTestRule.setContent { MaterialTheme { AssetBrowserScreen(vm, onNavigateBack = {}) } }
        composeTestRule.waitUntil(10_000) {
            composeTestRule.onAllNodes(hasText("beach.png")).fetchSemanticsNodes().isNotEmpty()
        }
        service.close()
    }

    @Test
    fun secondBackfill_isIdempotent() = runBlocking {
        val notes = EffectiveNotesPath(root.absolutePath)
        val first = AssetIndexService(repo)
        backfill(first, notes)
        first.close()
        val second = AssetIndexService(repo)
        backfill(second, notes)
        second.close()

        assertEquals(3L, repo.countAssets().getOrNull())
    }

    @Test
    fun completedMove_updatesRowRewritesPageAndClearsWalOnTheAndroidDriver() = runBlocking {
        val notes = EffectiveNotesPath(root.absolutePath)
        val service = AssetIndexService(repo)
        backfill(service, notes)
        val trip = repo.getAssets(10, 0).first().getOrNull()!!.first { it.filePath.endsWith("trip.png") }
        val restricted = RestrictedDatabaseQueries(database.steleDatabaseQueries)

        val result = AssetMoveService(repo, restricted).moveAsset(trip, "images", notes, fs, GraphWriter(fileSystem = fs), writeActor = null)

        assertTrue(result.toString(), result.isRight())
        assertTrue(File(root, "assets/images/trip.png").exists())
        assertEquals("- ![trip](../assets/images/trip.png)\n", File(root, "pages/Trip.md").readText())
        assertEquals(0, database.steleDatabaseQueries.selectAllPendingMoves().executeAsList().size)
        service.close()
    }

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
