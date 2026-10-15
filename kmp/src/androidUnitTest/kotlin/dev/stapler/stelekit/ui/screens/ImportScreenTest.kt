// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.domain.FetchResult
import dev.stapler.stelekit.domain.TopicEnricher
import dev.stapler.stelekit.domain.TopicSuggestion
import dev.stapler.stelekit.domain.UrlFetcher
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Robolectric Compose tests for [ImportScreen]: input stage, URL tab, review stage and confirm. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h1600dp-xxhdpi")
class ImportScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class RecordingSaver : PageSaver {
        val saved = mutableListOf<Page>()
        override suspend fun save(page: Page, blocks: List<Block>, graphPath: String) {
            saved += page
        }
    }

    private class FixedFetcher(private val result: FetchResult) : UrlFetcher {
        override suspend fun fetch(url: String): FetchResult = result
    }

    private fun vm(
        saver: RecordingSaver = RecordingSaver(),
        fetcher: UrlFetcher = FixedFetcher(FetchResult.Failure.NetworkUnavailable),
        matcher: AhoCorasickMatcher? = null,
        enricher: TopicEnricher? = null,
    ) = ImportViewModel(
        pageRepository = InMemoryPageRepository(),
        pageSaver = saver,
        graphPath = "/graphs/test",
        urlFetcher = fetcher,
        matcherFlow = MutableStateFlow(matcher),
        coroutineScope = CoroutineScope(Dispatchers.Unconfined),
        topicEnricher = enricher ?: dev.stapler.stelekit.domain.NoOpTopicEnricher(),
    )

    private fun show(
        vm: ImportViewModel,
        onDismiss: () -> Unit = {},
        onHtmlPaste: (() -> Unit)? = null,
    ) {
        composeTestRule.setContent {
            MaterialTheme { ImportScreen(vm, onDismiss = onDismiss, onHtmlPaste = onHtmlPaste) }
        }
    }

    /** Types into the paste field and waits for the 300ms-debounced scan to finish. */
    private fun typeContent(vm: ImportViewModel, text: String) {
        composeTestRule.onNodeWithText("Paste your content here").performTextInput(text)
        composeTestRule.waitUntil(5_000) { vm.state.value.rawText == text && !vm.state.value.isScanning }
    }

    @Test
    fun importScreen_should_showInputStageWithDisabledNext_when_empty() {
        show(vm())
        composeTestRule.onNodeWithText("Import text").assertExists()
        composeTestRule.onNodeWithText("Paste text").assertExists()
        composeTestRule.onNodeWithText("From URL").assertExists()
        composeTestRule.onNodeWithText("Next: Review links").assertIsNotEnabled()
    }

    @Test
    fun importScreen_should_enableNext_when_textTyped() {
        val vm = vm()
        show(vm)
        typeContent(vm, "My new page content")
        composeTestRule.onNodeWithText("Next: Review links").assertIsEnabled()
    }

    @Test
    fun importScreen_should_callOnDismiss_when_closeClicked() {
        var dismissed = 0
        show(vm(), onDismiss = { dismissed++ })
        composeTestRule.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun importScreen_should_showHtmlPasteButton_when_callbackProvided() {
        var pasted = 0
        show(vm(), onHtmlPaste = { pasted++ })
        composeTestRule.onNodeWithText("Paste with formatting").performClick()
        assertEquals(1, pasted)
    }

    @Test
    fun importScreen_should_showUrlInputAndFetchError_when_fetchFails() {
        val vm = vm(fetcher = FixedFetcher(FetchResult.Failure.Timeout))
        show(vm)
        composeTestRule.onNodeWithText("From URL").performClick()
        composeTestRule.waitUntil(5_000) { vm.state.value.activeTab == ImportTab.URL }
        composeTestRule.onNodeWithText("Enter URL").performTextInput("https://example.com/a")
        composeTestRule.onNodeWithText("Fetch").performClick()
        composeTestRule.waitUntil(5_000) { vm.state.value.fetchError == FetchResult.Failure.Timeout }
    }

    @Test
    fun importScreen_should_fillContent_when_fetchSucceeds() {
        val vm = vm(fetcher = FixedFetcher(FetchResult.Success("Fetched body text", "Fetched Title")))
        show(vm)
        composeTestRule.onNodeWithText("From URL").performClick()
        composeTestRule.onNodeWithText("Enter URL").performTextInput("https://example.com/b")
        composeTestRule.onNodeWithText("Fetch").performClick()
        composeTestRule.waitUntil(5_000) { vm.state.value.rawText == "Fetched body text" }
        assertEquals("Fetched Title", vm.state.value.pageName)
    }

    @Test
    fun importScreen_should_moveToReviewAndBack_when_nextAndBackClicked() {
        val vm = vm()
        show(vm)
        typeContent(vm, "Some content")
        composeTestRule.onNodeWithText("Next: Review links").performClick()
        composeTestRule.onNodeWithText("Review links").assertExists()
        composeTestRule.onNodeWithText("Import page").assertExists()
        composeTestRule.onNodeWithText("Back").performClick()
        composeTestRule.onNodeWithText("Import text").assertExists()
    }

    @Test
    fun importScreen_should_listMatchedPages_when_matcherFindsKnownPage() {
        val vm = vm(matcher = AhoCorasickMatcher(mapOf("alpha" to "Alpha")))
        show(vm)
        typeContent(vm, "I met alpha yesterday")
        composeTestRule.waitUntil(5_000) { vm.state.value.matchedPageNames.isNotEmpty() }
        composeTestRule.onNodeWithText("Next: Review links").performClick()
        composeTestRule.onNodeWithText("Alpha").assertExists()
    }

    @Test
    fun importScreen_should_notSave_when_pageNameBlank() {
        val saver = RecordingSaver()
        val vm = vm(saver = saver)
        show(vm)
        typeContent(vm, "Some content")
        composeTestRule.onNodeWithText("Next: Review links").performClick()
        composeTestRule.onNodeWithText("Import page").performClick()
        composeTestRule.waitUntil(5_000) { vm.state.value.pageNameError == "Page name is required" }
        assertTrue(saver.saved.isEmpty())
    }

    @Test
    fun importScreen_should_saveAndDismiss_when_importConfirmed() {
        val saver = RecordingSaver()
        var dismissed = 0
        val vm = vm(saver = saver)
        show(vm, onDismiss = { dismissed++ })
        composeTestRule.onNodeWithText("Page name").performTextInput("Imported Page")
        typeContent(vm, "Some content")
        composeTestRule.onNodeWithText("Next: Review links").performClick()
        composeTestRule.onNodeWithText("Import page").performClick()
        composeTestRule.waitUntil(5_000) { saver.saved.isNotEmpty() }
        assertEquals("Imported Page", saver.saved.single().name)
        composeTestRule.waitUntil(5_000) { dismissed > 0 }
    }

    @Test
    fun importScreen_should_showSuggestionTrayAndAcceptAll_when_enricherSuggestsTopics() {
        val terms = (1..10).map { "Topic$it" }
        val enricher = object : TopicEnricher {
            override suspend fun enhance(rawText: String, localSuggestions: List<TopicSuggestion>) =
                terms.map { TopicSuggestion(it, 0.8f, TopicSuggestion.Source.AI_ENHANCED) }
        }
        val vm = vm(matcher = AhoCorasickMatcher(mapOf("zzz" to "Zzz")), enricher = enricher)
        show(vm)
        typeContent(vm, "Some content about topics")
        composeTestRule.waitUntil(5_000) { vm.state.value.claudeStatus == ClaudeStatus.Done }
        composeTestRule.onNodeWithText("Next: Review links").performClick()
        composeTestRule.onNodeWithText("Suggested new pages (10)").assertExists()
        composeTestRule.onNodeWithText("AI-enhanced").assertExists()
        composeTestRule.onNodeWithText("Show 2 more").performClick()
        composeTestRule.onNodeWithText("Accept All").performClick()
        composeTestRule.onNodeWithText("Accept all suggestions?").assertExists()
        composeTestRule.onNodeWithText("Cancel").performClick()
        composeTestRule.onNodeWithText("Accept All").performClick()
        composeTestRule.onNodeWithText("OK").performClick()
        composeTestRule.waitUntil(5_000) { vm.state.value.topicSuggestions.any { it.accepted } }
    }
}
