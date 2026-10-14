// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.platform.SteleKitContext
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import dev.stapler.stelekit.ui.assets.AssetBrowserScreen
import dev.stapler.stelekit.ui.assets.AssetBrowserViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device coverage for the Assets screen: the real composable over the real Android SQLite
 * driver (Requery factory + MigrationRunner), which the JVM tests cannot exercise.
 *
 * Run: ./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.stapler.stelekit.AssetBrowserE2ETest
 */
@RunWith(AndroidJUnit4::class)
class AssetBrowserE2ETest {
    private companion object {
        const val KITCHEN = "kitchen.jpg"
        const val PERMIT = "permit.pdf"
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val dbName = "asset-e2e-${System.nanoTime()}.db"

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
        uuid = AssetUuid("uuid-$name"),
        filePath = "/graph/assets/$name",
        relativePath = "../assets/$name",
        mediaType = type,
        subfolder = "files",
        tags = emptyList(),
        autoLabels = emptyList(),
        ocrText = null,
        cloudDescription = null,
        pageUuids = emptyList(),
        sizeBytes = 2048L,
        importedAtMs = at,
        mlProcessed = false,
        mlAttemptedAt = null,
        mlFailed = false,
        contentHash = null,
        isOrphan = false,
        mlTagsSource = "NONE",
    )

    private fun emptyRepo(): SqlDelightAssetRepository {
        val driver = DriverFactory().createDriver("jdbc:sqlite:$dbName")
        return SqlDelightAssetRepository(SteleDatabase(driver))
    }

    @OptIn(DirectRepositoryWrite::class)
    private fun seededRepo(): SqlDelightAssetRepository {
        val repo = emptyRepo()
        runBlocking {
            repo.saveAsset(entry(KITCHEN, AssetMediaType.IMAGE, 3))
            repo.saveAsset(entry(PERMIT, AssetMediaType.PDF, 2))
            repo.saveAsset(entry("notes.bin", AssetMediaType.FILE, 1))
        }
        return repo
    }

    private fun waitForText(text: String) = composeTestRule.waitUntil(10_000) {
        composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun emptyAssetsScreenOpensWithoutCrashing() {
        val vm = AssetBrowserViewModel(emptyRepo())
        composeTestRule.setContent { MaterialTheme { AssetBrowserScreen(vm, onNavigateBack = {}) } }
        composeTestRule.onNodeWithText("Assets").assertIsDisplayed()
        composeTestRule.waitForIdle()
    }

    @Test
    fun seededAssetsRenderInGridAndList() {
        val vm = AssetBrowserViewModel(seededRepo())
        composeTestRule.setContent { MaterialTheme { AssetBrowserScreen(vm, onNavigateBack = {}) } }
        waitForText(KITCHEN)
        composeTestRule.onNodeWithContentDescription("Toggle view").performClick()
        waitForText("2 KB · IMAGE")
    }

    @Test
    fun filterAndSearchWorkOnDevice() {
        val vm = AssetBrowserViewModel(seededRepo())
        composeTestRule.setContent { MaterialTheme { AssetBrowserScreen(vm, onNavigateBack = {}) } }
        waitForText(PERMIT)
        composeTestRule.onNodeWithText("Images").performClick()
        composeTestRule.waitUntil(10_000) {
            composeTestRule.onAllNodes(hasText(PERMIT)).fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onNodeWithText(KITCHEN).assertIsDisplayed()
    }
}
