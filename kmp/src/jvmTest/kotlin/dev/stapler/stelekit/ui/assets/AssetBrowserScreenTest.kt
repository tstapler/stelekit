package dev.stapler.stelekit.ui.assets

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SqlDelightAssetRepository
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * Drives the real [AssetBrowserScreen] over a real SQLDelight [SqlDelightAssetRepository]
 * (migrations applied by [DriverFactory]) so schema/mapper/render regressions surface here
 * rather than on a device.
 */
class AssetBrowserScreenTest {
    private companion object {
        const val KITCHEN = "kitchen.jpg"
        const val PERMIT = "permit.pdf"
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun entry(name: String, type: AssetMediaType, at: Long, tags: List<String> = emptyList()) = AssetEntry(
        uuid = AssetUuid("uuid-$name"),
        filePath = "/graph/assets/$name",
        relativePath = "../assets/$name",
        mediaType = type,
        subfolder = "files",
        tags = tags,
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

    @OptIn(DirectRepositoryWrite::class)
    private fun seededViewModel(): AssetBrowserViewModel {
        val database = SteleDatabase(DriverFactory().createDriver("jdbc:sqlite::memory:"))
        val repo = SqlDelightAssetRepository(database)
        runBlocking {
            repo.saveAsset(entry(KITCHEN, AssetMediaType.IMAGE, 6, tags = listOf("remodel")))
            repo.saveAsset(entry(PERMIT, AssetMediaType.PDF, 5))
            repo.saveAsset(entry("memo.m4a", AssetMediaType.AUDIO, 4))
            repo.saveAsset(entry("walkthrough.mp4", AssetMediaType.VIDEO, 3))
            repo.saveAsset(entry("quote.docx", AssetMediaType.DOCUMENT, 2))
            repo.saveAsset(entry("notes.bin", AssetMediaType.FILE, 1))
        }
        return AssetBrowserViewModel(repo)
    }

    private fun show(vm: AssetBrowserViewModel) {
        composeTestRule.setContent {
            MaterialTheme { AssetBrowserScreen(viewModel = vm, onNavigateBack = {}) }
        }
    }

    private fun waitForText(text: String) = composeTestRule.waitUntil(5_000) {
        composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitGone(text: String) = composeTestRule.waitUntil(5_000) {
        composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty()
    }

    @Test
    fun opensAndRendersEveryMediaTypeInGrid() {
        show(seededViewModel())
        for (name in listOf(KITCHEN, PERMIT, "memo.m4a", "walkthrough.mp4", "quote.docx", "notes.bin")) {
            waitForText(name)
        }
        composeTestRule.onNodeWithText(KITCHEN).assertIsDisplayed()
    }

    @Test
    fun toggleToListViewRendersRows() {
        show(seededViewModel())
        waitForText(KITCHEN)
        composeTestRule.onNodeWithContentDescription("Toggle view").performClick()
        waitForText("2 KB · IMAGE")
    }

    @Test
    fun imagesFilterHidesNonImages() {
        show(seededViewModel())
        waitForText(PERMIT)
        composeTestRule.onNodeWithText("Images").performClick()
        waitGone(PERMIT)
        composeTestRule.onNodeWithText(KITCHEN).assertIsDisplayed()
    }

    @Test
    fun searchByTagNarrowsResults() {
        show(seededViewModel())
        waitForText(PERMIT)
        composeTestRule.onNodeWithText("Search", substring = true).performTextInput("remodel")
        waitGone(PERMIT)
        composeTestRule.onNodeWithText(KITCHEN).assertIsDisplayed()
    }

    @Test
    fun emptyDatabaseRendersWithoutError() {
        val database = SteleDatabase(DriverFactory().createDriver("jdbc:sqlite::memory:"))
        show(AssetBrowserViewModel(SqlDelightAssetRepository(database)))
        composeTestRule.onNodeWithText("Assets").assertIsDisplayed()
        composeTestRule.waitForIdle()
        check(composeTestRule.onAllNodes(hasText("Error", substring = true)).fetchSemanticsNodes().isEmpty())
    }
}
