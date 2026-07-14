package dev.stapler.stelekit.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import kotlin.time.Clock
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for the tap-vs-selection race: [BlockItem] used to run two independent
 * `detectTapGestures` recognizers (an outer row-level one for long-press-to-select, an inner
 * one per block-type for tap-to-edit/link dispatch), so under jank either one could "win"
 * non-deterministically. Now every block type resolves tap vs. long-press through a single
 * recognizer. This can't reproduce the wasmJs jank timing itself (see validation.md), but it
 * pins the desired resolution given a clean down/up stream.
 */
@OptIn(ExperimentalTestApi::class)
class BlockItemGestureTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun block(content: String = "Plain block text") = Block(
        uuid = BlockUuid("00000000-0000-0000-0000-000000000001"),
        pageUuid = PageUuid("00000000-0000-0000-0000-000000000002"),
        content = content,
        position = "a0",
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private class Recorder {
        var startEditingCalls = 0
        var enterSelectionModeCalls = 0
        var toggleSelectCalls = 0
        var shiftClickCalls = 0
        var linkClicked: String? = null
    }

    private fun render(
        content: String = "Plain block text",
        isInSelectionMode: Boolean = false,
        isShiftDown: Boolean = false,
    ): Recorder {
        val recorder = Recorder()
        composeTestRule.setContent {
            BlockItem(
                block = block(content),
                isEditing = false,
                isInSelectionMode = isInSelectionMode,
                onToggleSelect = { recorder.toggleSelectCalls++ },
                onEnterSelectionMode = { recorder.enterSelectionModeCalls++ },
                isShiftDown = isShiftDown,
                onShiftClick = { recorder.shiftClickCalls++ },
                onStartEditing = { recorder.startEditingCalls++ },
                onStopEditing = {},
                onContentChange = { _, _ -> },
                onLinkClick = { recorder.linkClicked = it },
                onNewBlock = {},
                onSplitBlock = { _, _ -> },
            )
        }
        return recorder
    }

    @Test
    fun fastTap_entersEditMode_notSelectionMode() {
        val recorder = render()
        composeTestRule.onNodeWithText("Plain block text").performClick()

        assertEquals(1, recorder.startEditingCalls)
        assertEquals(0, recorder.enterSelectionModeCalls)
        assertEquals(0, recorder.toggleSelectCalls)
    }

    @Test
    fun genuineLongPress_entersSelectionMode_notEditMode() {
        val recorder = render()
        composeTestRule.onNodeWithText("Plain block text").performTouchInput { longClick() }

        assertEquals(1, recorder.enterSelectionModeCalls)
        assertEquals(0, recorder.startEditingCalls)
    }

    @Test
    fun shiftClick_extendsSelection_notEditMode() {
        val recorder = render(isShiftDown = true)
        composeTestRule.onNodeWithText("Plain block text").performClick()

        assertEquals(1, recorder.shiftClickCalls)
        assertEquals(0, recorder.startEditingCalls)
    }

    @Test
    fun tapWhileInSelectionMode_togglesSelection_notEditMode() {
        val recorder = render(isInSelectionMode = true)
        composeTestRule.onNodeWithText("Plain block text").performClick()

        assertEquals(1, recorder.toggleSelectCalls)
        assertEquals(0, recorder.startEditingCalls)
    }

    /**
     * Pixel-accurate click-on-a-specific-span testing isn't reliable under this repo's
     * headless jvmTest font metrics (see TESTING_README.md), so this pins the piece that
     * *is* environment-independent: [WikiLinkText]'s tap handler resolves onLinkClick from
     * a WIKI_LINK_TAG annotation at the tapped offset — confirm the parser actually produces
     * that annotation (with the right target) for a `[[Page Name]]` span, unaffected by this
     * fix's isInSelectionMode gate (which only short-circuits ahead of this lookup).
     */
    @Test
    fun wikiLinkSpan_isAnnotatedForTapDispatch() {
        val parsed = parseMarkdownWithStyling(
            text = "See [[Another Page]]",
            linkColor = Color.Blue,
            textColor = Color.Black,
        )
        val annotation = parsed.getStringAnnotations(WIKI_LINK_TAG, 0, parsed.length).single()
        assertEquals("Another Page", annotation.item)
    }
}
