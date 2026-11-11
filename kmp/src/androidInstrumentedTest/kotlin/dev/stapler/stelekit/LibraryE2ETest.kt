// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.platform.SteleKitContext
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import dev.stapler.stelekit.repository.SqlDelightImageAnnotationRepository
import dev.stapler.stelekit.repository.SqlDelightLibraryRepository
import dev.stapler.stelekit.ui.library.LibraryScreen
import dev.stapler.stelekit.ui.library.LibraryViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device coverage for the Library screen: the real composable over the real Android SQLite driver
 * (Requery factory + MigrationRunner), which the JVM tests cannot exercise. The paged query uses
 * REPLACE/RTRIM/LIKE ... ESCAPE, so this is also the two-driver check of ADR-008's search predicate.
 *
 * Run: ./gradlew :kmp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.stapler.stelekit.LibraryE2ETest
 */
@RunWith(AndroidJUnit4::class)
class LibraryE2ETest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val dbName = "library-e2e-${System.nanoTime()}.db"

    @Before
    fun setUp() {
        SteleKitContext.init(context)
        DriverFactory.setContext(context)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun entry(name: String, type: AssetMediaType, at: Long) = AssetEntry(
        uuid = AssetUuid("uuid-$name"), filePath = "/graph/assets/$name", relativePath = "../assets/$name", mediaType = type,
        subfolder = "files", tags = emptyList(), autoLabels = emptyList(), ocrText = null, cloudDescription = null,
        pageUuids = emptyList(), sizeBytes = 2048L, importedAtMs = at, mlProcessed = false, mlAttemptedAt = null,
        mlFailed = false, contentHash = null, isOrphan = false, mlTagsSource = "NONE",
    )

    @OptIn(DirectRepositoryWrite::class)
    private fun viewModel(seed: Boolean): LibraryViewModel {
        val driver = DriverFactory().createDriver("jdbc:sqlite:$dbName")
        val db = SteleDatabase(driver)
        if (seed) {
            runBlocking {
                val assets = SqlDelightAssetRepository(db)
                assets.saveAsset(entry("kitchen.jpg", AssetMediaType.IMAGE, 3))
                assets.saveAsset(entry("permit.pdf", AssetMediaType.PDF, 2))
                assets.saveAsset(entry("notes.bin", AssetMediaType.FILE, 1))
                SqlDelightImageAnnotationRepository(db).saveImageAnnotation(
                    ImageAnnotation(
                        uuid = "ann-1", blockUuid = "blk-1", pageUuid = "page-1", graphPath = "/graph",
                        filePath = "/graph/assets/kitchen.jpg", importedAtMs = 10L, tags = listOf("site"),
                    ),
                )
            }
        }
        return LibraryViewModel(SqlDelightLibraryRepository(db, driver))
    }

    private fun waitForText(text: String) = composeTestRule.waitUntil(10_000) {
        composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun emptyLibraryOpensWithoutCrashing() {
        val vm = viewModel(seed = false)
        composeTestRule.setContent { MaterialTheme { LibraryScreen(vm, onOpenAnnotationEditor = {}, onOpenAsset = {}) } }
        composeTestRule.waitUntil(10_000) { composeTestRule.onAllNodes(hasTestTag("library-empty")).fetchSemanticsNodes().isNotEmpty() }
        composeTestRule.onNodeWithTag("library-empty").assertIsDisplayed()
    }

    @Test
    fun seededItemsRender_andTheAnnotatedFilterNarrowsToOne() {
        val vm = viewModel(seed = true)
        composeTestRule.setContent { MaterialTheme { LibraryScreen(vm, onOpenAnnotationEditor = {}, onOpenAsset = {}) } }
        waitForText("3 items")
        // The Pixel Fold inner display is wider than 600 dp (chips inline); the cover display is compact (behind Filters).
        if (composeTestRule.onAllNodes(hasTestTag("library-filters-button")).fetchSemanticsNodes().isNotEmpty()) {
            composeTestRule.onNodeWithTag("library-filters-button").performClick()
        }
        composeTestRule.onNodeWithTag("library-chip-annotated").performClick()
        waitForText("1 item")
    }

    @Test
    fun wordPrefixSearchWorksOnTheAndroidDriver() {
        val vm = viewModel(seed = true)
        composeTestRule.setContent { MaterialTheme { LibraryScreen(vm, onOpenAnnotationEditor = {}, onOpenAsset = {}) } }
        waitForText("3 items")
        vm.setSearch("kit")
        waitForText("1 item")
        vm.setSearch("itchen")
        waitForText("0 items")
    }
}
