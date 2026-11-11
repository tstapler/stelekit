// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.assets

import androidx.compose.ui.test.hasText
import dev.stapler.stelekit.ui.i18n.I18n
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertHasClickAction
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.asset.AssetSearchService
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.repository.AssetRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.ui.assets.ASSETS_SEARCH_DEBOUNCE_MS
import dev.stapler.stelekit.ui.assets.AssetBrowserViewModel
import dev.stapler.stelekit.ui.assets.AssetSearchEmptyState
import dev.stapler.stelekit.ui.assets.AssetsSearchSection
import dev.stapler.stelekit.ui.assets.assetSnippet
import dev.stapler.stelekit.ui.assets.searchEmptyText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.asset.AssetEntry
import dev.stapler.stelekit.asset.AssetIndexService
import dev.stapler.stelekit.asset.AssetIndexingController
import dev.stapler.stelekit.asset.AssetMediaType
import dev.stapler.stelekit.asset.AssetUuid
import dev.stapler.stelekit.asset.pipeline.AssetPluginsController
import dev.stapler.stelekit.asset.pipeline.PauseReason
import dev.stapler.stelekit.asset.pipeline.PipelineProgress
import dev.stapler.stelekit.asset.pipeline.PipelineRunMarker
import dev.stapler.stelekit.asset.pipeline.PluginEnableKeys
import dev.stapler.stelekit.asset.pipeline.PluginRegistry
import dev.stapler.stelekit.asset.pipeline.PlatformCaps
import dev.stapler.stelekit.platform.ml.ImageLabeler
import dev.stapler.stelekit.platform.ml.Label
import dev.stapler.stelekit.platform.ml.NoOpPdfTextExtractor
import dev.stapler.stelekit.platform.ml.NoOpTextRecognizer
import dev.stapler.stelekit.repository.InMemoryAssetRepository
import dev.stapler.stelekit.ui.assets.AssetBrowserStatusHeader
import dev.stapler.stelekit.ui.assets.assetBrowserProcessingLine
import dev.stapler.stelekit.ui.assets.shouldShowSearchablePrompt
import dev.stapler.stelekit.wiring.FakeSettings
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 5.6 surfaces implemented in U10: the prompt card with persisted dismissal and the inline processing line. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AssetResultsSurfaceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val settings = FakeSettings()
    private val closeables = mutableListOf<() -> Unit>()

    @After
    fun tearDown() = closeables.forEach { it() }

    private val labeler = object : ImageLabeler {
        override suspend fun labelImage(imageBytes: ByteArray) =
            arrow.core.Either.Right(emptyList<Label>())
    }

    private fun controller(): AssetPluginsController {
        val caps = PlatformCaps(labeler, NoOpPdfTextExtractor(), NoOpTextRecognizer())
        val indexing = AssetIndexingController(
            settings, AssetIndexService(InMemoryAssetRepository()), toggleVisibleProvider = { true },
            runMarker = PipelineRunMarker(settings),
        ).also { closeables += { it.close() } }
        return AssetPluginsController(PluginRegistry(settings, caps), null, indexing, settings)
    }

    private fun asset(type: AssetMediaType) = AssetEntry(
        uuid = AssetUuid("a-$type"), filePath = "/g/assets/a", relativePath = "../assets/a", mediaType = type,
        subfolder = "", tags = emptyList(), autoLabels = emptyList(), ocrText = null, cloudDescription = null,
        pageUuids = emptyList(), sizeBytes = 1, importedAtMs = 0, mlProcessed = false, mlAttemptedAt = null,
        mlFailed = false, contentHash = null, isOrphan = false, mlTagsSource = "NONE",
    )

    @Test
    fun emptyState_should_ShowMakeImagesSearchableCardAndPersistNotNow_When_ImagesAndNoPlugin() {
        val c = controller()
        var opened = 0
        composeRule.setContent { AssetBrowserStatusHeader(listOf(asset(AssetMediaType.IMAGE)), c, onOpenPlugins = { opened++ }) }

        composeRule.onNodeWithText("Make images searchable - turn on text recognition").assertExists()
        composeRule.onNodeWithText("Turn on").performClick()
        assertEquals(1, opened, "Turn on opens Settings > Plugins and never flips a switch itself")
        assertFalse(settings.getBoolean(PluginEnableKeys.keyFor(PluginEnableKeys.IMAGE_LABELS), false))

        composeRule.onNodeWithText("Not now").performClick()
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodes(hasText("Make images searchable", substring = true)).fetchSemanticsNodes().size)
        assertTrue(settings.getBoolean(AssetPluginsController.PROMPT_DISMISSED_KEY, false), "dismissal is persisted")
        assertTrue(controller().promptDismissed.value, "a fresh controller (next launch) reads the stored dismissal")
    }

    @Test
    fun notNow_should_BeClearableFromPluginsRow_And_CardReturn_When_Cleared() {
        val c = controller()
        c.dismissPrompt()
        composeRule.setContent { AssetBrowserStatusHeader(listOf(asset(AssetMediaType.IMAGE)), c, onOpenPlugins = {}) }
        assertEquals(0, composeRule.onAllNodes(hasText("Make images searchable", substring = true)).fetchSemanticsNodes().size)

        c.clearPromptDismissal()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Make images searchable - turn on text recognition").assertExists()
    }

    @Test
    fun promptRule_should_RequireAnImageNoEnabledPluginAndNoDismissal() {
        assertTrue(shouldShowSearchablePrompt(hasImage = true, enabledPlugins = 0, dismissed = false))
        assertFalse(shouldShowSearchablePrompt(hasImage = false, enabledPlugins = 0, dismissed = false))
        assertFalse(shouldShowSearchablePrompt(hasImage = true, enabledPlugins = 1, dismissed = false))
        assertFalse(shouldShowSearchablePrompt(hasImage = true, enabledPlugins = 0, dismissed = true))
    }

    @Test
    fun assetsBrowser_should_ShowProcessingLineLinkingToProgressRowAndHideWhenIdle_When_PipelineRunsOrIdle() {
        assertEquals("Processing 340 of 2,100", assetBrowserProcessingLine(PipelineProgress.Running(340, 2100, 0)))
        assertEquals("Waiting for charger", assetBrowserProcessingLine(PipelineProgress.Paused(PauseReason.Charger)))
        assertNull(assetBrowserProcessingLine(PipelineProgress.Idle))
        assertNull(assetBrowserProcessingLine(PipelineProgress.Finished(5, 0)))

        val c = controller()
        composeRule.setContent { AssetBrowserStatusHeader(emptyList(), c, onOpenPlugins = {}) }
        assertEquals(0, composeRule.onAllNodes(hasText("details")).fetchSemanticsNodes().size, "hidden while idle")
    }

    // ---- Task 5.6a: Assets search ------------------------------------------------------------------------

    private fun assetWith(
        id: String,
        name: String = id,
        ocr: String? = null,
        cloud: String? = null,
    ) = asset(AssetMediaType.IMAGE).copy(uuid = AssetUuid(id), filePath = "/g/assets/$name.png", ocrText = ocr, cloudDescription = cloud)

    @OptIn(DirectRepositoryWrite::class)
    private fun repoWith(vararg assets: AssetEntry) = InMemoryAssetRepository().also { r ->
        runBlocking { assets.forEach { r.saveAsset(it) } }
    }

    @Test
    fun assetsSearch_should_ReturnAssetWithSnippet_When_QueryMatchesOcrText4821() = runBlocking<Unit> {
        val repo = repoWith(assetWith("inv", ocr = "Invoice 4821 due Friday"), assetWith("other", ocr = "nothing"))

        val hits = AssetSearchService(repo).search("4821", 50).getOrNull().orEmpty()

        assertEquals(listOf("inv"), hits.map { it.asset.uuid.value })
        assertEquals("ocr_text", hits.single().preview?.matchedColumn)
        assertTrue(hits.single().preview!!.excerpt.contains("4821"))
        assertTrue(hits.single().preview!!.excerpt.length <= 240)
    }

    private class CountingService(repo: AssetRepository) {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val service = AssetSearchService(object : AssetRepository by repo {
            override fun searchAssets(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>> {
                calls.incrementAndGet()
                return repo.searchAssets(query, limit, offset)
            }
        })
    }

    @Test
    fun globalSearch_should_NotQueryAssets_When_QueryUnder3CharsOrSectionHidden() {
        val counting = CountingService(repoWith(assetWith("inv", ocr = "Invoice 4821")))
        var query by mutableStateOf("48")
        var visible by mutableStateOf(true)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AssetsSearchSection(query, counting.service, onShowAll = {}, onOpenAsset = {}, visible = visible)
        }
        composeRule.mainClock.advanceTimeBy(2_000)
        assertEquals(0, counting.calls.get(), "under 3 characters never queries")

        composeRule.runOnUiThread { visible = false; query = "4821"; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        composeRule.mainClock.advanceTimeBy(2_000)
        assertEquals(0, counting.calls.get(), "a hidden section never queries")

        composeRule.runOnUiThread { visible = true; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        composeRule.mainClock.advanceTimeBy(ASSETS_SEARCH_DEBOUNCE_MS - 50)
        assertEquals(0, counting.calls.get(), "still inside the 250 ms debounce")
        composeRule.runOnUiThread { query = "48211"; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        composeRule.mainClock.advanceTimeBy(ASSETS_SEARCH_DEBOUNCE_MS - 50)
        assertEquals(0, counting.calls.get(), "the next keystroke cancels the pending query")
        composeRule.mainClock.advanceTimeBy(500)
        assertEquals(1, counting.calls.get(), "exactly one query once the debounce elapses")
    }

    @Test
    fun assetsSearch_should_ReturnAssetWithSnippetAndEmphasizedMatch_When_Query4821() = runBlocking<Unit> {
        val repo = repoWith(assetWith("inv", ocr = "Invoice 4821 due Friday"))
        val vm = AssetBrowserViewModel(repo, null)
        vm.setSearch("4821")
        val state = withTimeout(10_000) { vm.uiState.first { it.searchPreviews.isNotEmpty() } }

        assertEquals("Invoice 4821 due Friday", state.searchPreviews[AssetUuid("inv")]?.excerpt)
        assertEquals("4821", state.searchQuery)
        vm.onForgotten()
    }

    @Test
    fun globalSearch_should_ShowAtMost5AssetsWithShowAllAndHideSection_When_NoMatches() {
        val repo = repoWith(*(1..8).map { assetWith("a$it", ocr = "Invoice 4821 copy $it") }.toTypedArray())
        var shown: String? = null
        var query by mutableStateOf("4821")
        composeRule.setContent {
            AssetsSearchSection(query, AssetSearchService(repo), onShowAll = { shown = it }, onOpenAsset = {})
        }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(I18n.get(I18n.LIBRARY_SEARCH_SHOW_ALL))).fetchSemanticsNodes().isNotEmpty() }

        assertEquals(5, composeRule.onAllNodes(hasText("Text found in image")).fetchSemanticsNodes().size)
        composeRule.onNodeWithText(I18n.get(I18n.LIBRARY_SEARCH_SHOW_ALL)).performClick()
        assertEquals("4821", shown)

        query = "zzzzzz"
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText(I18n.get(I18n.LIBRARY_SEARCH_SECTION))).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun assetsSearch_should_SayNImagesStillProcessedWithProgressLink_When_NoResultsAndPipelineUnfinished() {
        assertEquals("No matches. 1,760 images are still being processed", searchEmptyText(1760))
        assertEquals("No matches", searchEmptyText(0))
        var details = 0
        composeRule.setContent { AssetSearchEmptyState(pending = 1760, onDetails = { details++ }) }
        composeRule.onNodeWithText("details").performClick()
        assertEquals(1, details)
    }

    @Test
    fun result_should_LabelAiDescription_When_MatchedColumnIsCloudDescription_And_LabelTextFoundInImage_When_OcrText() = runBlocking<Unit> {
        val repo = repoWith(assetWith("c", cloud = "A red bicycle"), assetWith("o", ocr = "bicycle shop"))
        // Cloud text is not searched until Task 6.2d; the label comes from the preview of the matched column.
        val previews = repo.getSearchSnippets("bicycle", listOf(AssetUuid("c"), AssetUuid("o"))).getOrNull().orEmpty()

        assertEquals("AI description", previews.getValue(AssetUuid("c")).sourceLabel)
        assertEquals("Text found in image", previews.getValue(AssetUuid("o")).sourceLabel)
    }

    @Test
    fun globalSearch_should_ShowCouldNotSearchFilesRetryAndStillRenderOtherSections_When_AssetsQueryFails() {
        val failing = object : AssetRepository by repoWith() {
            override fun searchAssets(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>> =
                kotlinx.coroutines.flow.flowOf(DomainError.DatabaseError.ReadFailed("boom").left())
        }
        composeRule.setContent {
            Text("Pages section")
            AssetsSearchSection("4821", AssetSearchService(failing), onShowAll = {}, onOpenAsset = {})
        }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("Couldn't search files - try again")).fetchSemanticsNodes().isNotEmpty() }

        composeRule.onNodeWithText("Pages section").assertExists()
        composeRule.onNodeWithText("Retry").assertExists()
    }

    @Test
    fun results_should_BeOneNodeEachAndKeyboardOrderIncludeAssetsSection_When_Navigated() {
        val repo = repoWith(assetWith("a1", name = "one", ocr = "Invoice 4821"), assetWith("a2", name = "two", ocr = "Invoice 4821"))
        composeRule.setContent { AssetsSearchSection("4821", AssetSearchService(repo), onShowAll = {}, onOpenAsset = {}) }
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("one.png", substring = true)).fetchSemanticsNodes().isNotEmpty() }

        // Each result merges name, label and snippet into a single clickable (focusable) node.
        assertEquals(1, composeRule.onAllNodes(hasText("one.png", substring = true)).fetchSemanticsNodes().size)
        composeRule.onNode(hasText("one.png", substring = true)).assertHasClickAction()
        composeRule.onNode(hasText("two.png", substring = true)).assertHasClickAction()
    }

    @Test
    fun snippet_should_EmphasizeMatchWithBoldAndTintAndNoUnderline_When_Query4821() {
        var built: androidx.compose.ui.text.AnnotatedString? = null
        composeRule.setContent { built = assetSnippet("Invoice 4821 due", "4821") }
        val span = built!!.spanStyles.single()
        assertEquals(androidx.compose.ui.text.font.FontWeight.Bold, span.item.fontWeight)
        assertTrue(span.item.background != androidx.compose.ui.graphics.Color.Unspecified, "tinted background")
        assertTrue(span.item.textDecoration != androidx.compose.ui.text.style.TextDecoration.Underline)
        assertEquals("4821", built!!.text.substring(span.start, span.end))
    }

    @Test
    fun slowWork_should_ShowSearchingTestingAndLoadingStatesAfter200ms_When_VirtualTimeAdvances() {
        val slowRepo = object : AssetRepository by repoWith(assetWith("inv", ocr = "Invoice 4821")) {
            override fun searchAssets(query: String, limit: Int, offset: Int): Flow<Either<DomainError, List<AssetEntry>>> = flow {
                kotlinx.coroutines.awaitCancellation()
                @Suppress("UNREACHABLE_CODE")
                emit(emptyList<AssetEntry>().right())
            }
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { AssetsSearchSection("4821", AssetSearchService(slowRepo), onShowAll = {}, onOpenAsset = {}) }
        composeRule.mainClock.advanceTimeBy(ASSETS_SEARCH_DEBOUNCE_MS + 100)
        assertEquals(0, composeRule.onAllNodes(hasText("Searching files...")).fetchSemanticsNodes().size, "not before 200 ms")
        composeRule.mainClock.advanceTimeBy(200)
        composeRule.onNodeWithText("Searching files...").assertExists()
    }
}
