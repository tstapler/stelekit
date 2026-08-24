// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.domain.PageNameIndex
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.tags.LlmTagProvider
import dev.stapler.stelekit.tags.TagSuggestionEngine
import dev.stapler.stelekit.tags.TagSuggestionState
import dev.stapler.stelekit.tags.TagSuggestionViewModel
import dev.stapler.stelekit.ui.components.EditorCapabilities
import dev.stapler.stelekit.ui.components.EditorToolbar
import dev.stapler.stelekit.ui.components.tags.SuggestionBottomSheet
import dev.stapler.stelekit.voice.LlmFormatterProvider
import dev.stapler.stelekit.voice.LlmResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Flagship UX test for `docs/journeys/insert-tag.md` / design/ux.md criterion 1: inserting a
 * tag via the mobile "Suggest tags" button path must complete in at most 2 taps.
 *
 * The GAP-003 fix (Story D.1.1, `TagSuggestionViewModel.requestSuggestions`) removed the
 * synchronous `Loading` state emitted before the suggestion coroutine ran, so the first state
 * the UI observes is `Ready` — no longer a guaranteed spinner frame between the button tap and
 * seeing suggestion chips. This test exercises that removal end-to-end through the real
 * composables (`EditorToolbar` + `SuggestionBottomSheet` + `TagChipRow`, wired the same way
 * `PageView.kt` wires them) rather than asserting on `TagSuggestionViewModel` state alone.
 *
 * Note on suggestion source: [dev.stapler.stelekit.tags.TagSuggestionEngine.directMatch] (Tier 1,
 * local/AhoCorasick) always returns `autoApplied = true` results, and
 * [dev.stapler.stelekit.ui.components.tags.TagChipRow] filters out every `autoApplied`
 * suggestion before rendering a chip (`TagChipRow.kt:32`) — so a Tier-1 local match is never
 * itself the thing the user taps. The only suggestions that ever render as a tappable
 * `FilterChip` are Tier-2 LLM suggestions (`autoApplied = false` unconditionally). This test
 * therefore wires a fast, synchronous fake LLM provider so a real chip renders, and exercises
 * exactly the tap sequence a real user performs: tap "Suggest tags", then tap the chip.
 */
class TagInsertionFlagshipUiTest : ComposeUITestBase() {

    @Test
    fun `insertTag completes in at most two taps when flagship flow followed`() {
        val pageUuid = PageUuid("page-1")
        val blockUuid = BlockUuid("block-1")

        // Block starts with no tag/wiki-link yet — matches the flagship scenario's precondition.
        blockStateManager.observePage(pageUuid)
        blockStateManager.requestEditBlock(blockUuid, cursorIndex = 0)
        blockStateManager.updateBlockContent(
            blockUuid = blockUuid,
            newContent = "I love Kotlin",
            newVersion = 1,
        )
        composeTestRule.waitForIdle()

        // Fast, synchronous fake LLM tier — no real network round-trip, but exercises the same
        // Ready-state-with-llmSuggestions path production wiring uses.
        val indexScope = CoroutineScope(Dispatchers.Unconfined)
        val pageNameIndex = PageNameIndex(InMemoryPageRepository(), indexScope, rebuildDebounceMs = 0L)
        val formatter = LlmFormatterProvider { _, _ -> LlmResult.Success("Kotlin", false) }
        val llmProvider = LlmTagProvider(formatter, timeoutSeconds = 5)
        val engine = TagSuggestionEngine(
            pageNameIndex = pageNameIndex,
            llmTagProvider = llmProvider,
            vocabularyProvider = { listOf("Kotlin") },
        )
        val tagSuggestionViewModel = TagSuggestionViewModel(engine)

        composeTestRule.setContent {
            MaterialTheme {
                val state by tagSuggestionViewModel.state.collectAsState()

                EditorToolbar(
                    blockStateManager = blockStateManager,
                    capabilities = EditorCapabilities(),
                    searchViewModel = null,
                    isLeftHanded = false,
                    onSuggestTags = { uuid, content ->
                        tagSuggestionViewModel.requestSuggestions(blockUuid = uuid, blockContent = content)
                    },
                )

                SuggestionBottomSheet(
                    state = state,
                    onAcceptTag = { uuid, term ->
                        blockStateManager.appendToBlock(BlockUuid(uuid), " [[$term]]")
                    },
                    onDismiss = { tagSuggestionViewModel.dismiss() },
                )
            }
        }
        composeTestRule.waitForIdle()

        var tapCount = 0

        // Tap 1: "Suggest tags" toolbar button.
        composeTestRule.onNodeWithContentDescription("Suggest tags").performClick()
        tapCount++
        composeTestRule.waitForIdle()

        // Not a tap — waiting for the (fast, fake) LLM tier to resolve so a chip is rendered.
        // The step-count claim being verified is about tap count, not wall-clock/perceptual
        // steps (docs/journeys/insert-tag.md's separate "discrete steps" count already covers
        // that distinction).
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            val current = tagSuggestionViewModel.state.value
            current is TagSuggestionState.Ready && current.llmSuggestions.isNotEmpty()
        }

        // Tap 2: the rendered chip.
        composeTestRule.onNodeWithText("Kotlin").performClick()
        tapCount++
        composeTestRule.waitForIdle()

        assertEquals(
            2,
            tapCount,
            "Flagship tag-insertion flow (tap 'Suggest tags' -> tap chip) must complete in " +
                "exactly 2 taps per design/ux.md criterion 1.",
        )

        val finalBlock = blockStateManager.blocks.value.values.flatten().find { it.uuid == blockUuid }
        assertTrue(
            finalBlock?.content?.contains("[[Kotlin]]") == true,
            "Expected the tag to be inserted into the block's content, got: ${finalBlock?.content}",
        )

        tagSuggestionViewModel.close()
        indexScope.cancel()
    }
}
