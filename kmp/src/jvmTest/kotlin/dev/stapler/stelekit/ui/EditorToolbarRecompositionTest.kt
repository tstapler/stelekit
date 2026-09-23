package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.components.EditorCapabilities
import dev.stapler.stelekit.ui.components.EditorToolbar
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Regression tests for the recomposition-storm bug described in
 * `project_plans/rich-editing-experience/research/pitfalls.md` §1 and fixed in Task B.1.1a.
 *
 * Before the fix, `EditorToolbar`'s `onSuggestTags`/`onLinkPicker` closures captured `allBlocks`
 * — a `collectAsState()`-derived `Map` — at composable-body scope instead of reading
 * `blockStateManager.blocks.value` at click-time. `allBlocks` was only ever referenced *inside*
 * the deferred click lambdas, so (as verified empirically while building this test) it never
 * actually registered a snapshot-read dependency during composition, meaning a pure
 * recomposition-count probe cannot by itself distinguish the two versions — both compose exactly
 * once regardless of how many times the block's content changes. The two tests below therefore
 * cover both the literal acceptance-criteria behavior (no extra recompositions from keystrokes)
 * and the actual mechanism that differs pre/post fix: whether the click handler resolves content
 * from a live source ([dev.stapler.stelekit.ui.state.BlockStateManager.blocks], always current)
 * versus a `collectAsState()` snapshot that freezes once this composable leaves composition
 * (its backing `LaunchedEffect` collector is cancelled on dispose).
 */
class EditorToolbarRecompositionTest : ComposeUITestBase() {

    @Test
    fun `EditorToolbar does not recompose on keystrokes into the editing block`() {
        val pageUuid = PageUuid("page-1")
        val blockUuid = BlockUuid("block-1")

        // Populate blockStateManager's local block cache from the pre-populated fake repo
        // so updateBlockContent below has a page entry to patch in place.
        blockStateManager.observePage(pageUuid)
        blockStateManager.requestEditBlock(blockUuid, cursorIndex = 0)

        var recompositionCount = 0

        composeTestRule.setContent {
            MaterialTheme {
                EditorToolbar(
                    blockStateManager = blockStateManager,
                    capabilities = EditorCapabilities(),
                    searchViewModel = null,
                    isLeftHanded = false,
                    onRecompose = { recompositionCount++ },
                )
            }
        }
        composeTestRule.waitForIdle()

        val countAfterInitialComposition = recompositionCount
        assertTrue(
            countAfterInitialComposition >= 1,
            "Expected at least one initial composition of EditorToolbar",
        )

        // Simulate 10 keystroke-equivalent state updates into the actively-editing block.
        repeat(10) { i ->
            blockStateManager.updateBlockContent(
                blockUuid = blockUuid,
                newContent = "Block 1 edited $i",
                newVersion = (i + 1).toLong(),
            )
            composeTestRule.waitForIdle()
        }

        assertEquals(
            countAfterInitialComposition,
            recompositionCount,
            "EditorToolbar recomposed ${recompositionCount - countAfterInitialComposition} extra " +
                "time(s) due to 10 keystrokes into the editing block (went from " +
                "$countAfterInitialComposition to $recompositionCount).",
        )
    }

    @Test
    fun `onSuggestTags handler resolves live content even after EditorToolbar leaves composition`() {
        val pageUuid = PageUuid("page-1")
        val blockUuid = BlockUuid("block-1")

        blockStateManager.observePage(pageUuid)
        blockStateManager.requestEditBlock(blockUuid, cursorIndex = 0)

        val recordedContents = mutableListOf<String>()
        var capturedHandler: (() -> Unit)? = null
        var showToolbar by mutableStateOf(true)

        composeTestRule.setContent {
            MaterialTheme {
                if (showToolbar) {
                    EditorToolbar(
                        blockStateManager = blockStateManager,
                        capabilities = EditorCapabilities(),
                        searchViewModel = null,
                        isLeftHanded = false,
                        onSuggestTags = { _, content -> recordedContents.add(content) },
                        onSuggestTagsHandlerReady = { handler -> capturedHandler = handler },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
        assertNotNull(capturedHandler, "Expected onSuggestTags handler to be built while editing a block")

        // Remove EditorToolbar from composition entirely — this disposes any collectAsState()
        // subscription it held (cancels the backing LaunchedEffect collector), while our test
        // keeps its own reference to the already-built click handler.
        showToolbar = false
        composeTestRule.waitForIdle()

        // Change the block's content only *after* EditorToolbar has left composition.
        blockStateManager.updateBlockContent(
            blockUuid = blockUuid,
            newContent = "Content changed after dispose",
            newVersion = 1,
        )

        // Invoke the handler captured before dispose directly (bypassing the now-gone UI).
        capturedHandler?.invoke()

        assertEquals(
            listOf("Content changed after dispose"),
            recordedContents,
            "onSuggestTags handler must read block content from a live source " +
                "(blockStateManager.blocks.value) so it reflects the latest content even after " +
                "EditorToolbar has left composition — a collectAsState()-derived closure capture " +
                "would instead freeze at whatever value it last saw before its LaunchedEffect " +
                "collector was cancelled on dispose.",
        )
    }
}
