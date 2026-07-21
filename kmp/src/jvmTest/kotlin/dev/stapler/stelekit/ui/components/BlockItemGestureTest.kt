package dev.stapler.stelekit.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockType
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

    private fun block(content: String = "Plain block text", blockType: BlockType = BlockType.Bullet) = Block(
        uuid = BlockUuid("00000000-0000-0000-0000-000000000001"),
        pageUuid = PageUuid("00000000-0000-0000-0000-000000000002"),
        content = content,
        position = "a0",
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
        blockType = blockType,
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

    /**
     * Regression test for a stale-`pointerInput`-closure bug found in review: [WikiLinkText]'s
     * gesture recognizer was originally keyed only on `annotatedString`, so when a long-press
     * flips `isInSelectionMode` to true on an already-composed row (content unchanged), the
     * running coroutine kept evaluating taps against the stale `isInSelectionMode == false` it
     * captured at launch -- the very next tap fell through to `onStartEditing` instead of
     * toggling selection, reintroducing this PR's own bug through a different mechanism. Unlike
     * [tapWhileInSelectionMode_togglesSelection_notEditMode] (which sets `isInSelectionMode`
     * only at initial composition and can't detect a stale closure), this drives the real
     * long-press-then-tap sequence within one composed instance.
     */
    @Test
    fun longPressThenTap_onSameRow_togglesSelection_notEditMode() {
        val recorder = Recorder()
        var selectionMode by mutableStateOf(false)
        composeTestRule.setContent {
            BlockItem(
                block = block(),
                isEditing = false,
                isInSelectionMode = selectionMode,
                onToggleSelect = { recorder.toggleSelectCalls++ },
                onEnterSelectionMode = {
                    recorder.enterSelectionModeCalls++
                    selectionMode = true
                },
                onStartEditing = { recorder.startEditingCalls++ },
                onStopEditing = {},
                onContentChange = { _, _ -> },
                onLinkClick = { recorder.linkClicked = it },
                onNewBlock = {},
                onSplitBlock = { _, _ -> },
            )
        }
        val node = composeTestRule.onNodeWithText("Plain block text")

        node.performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        assertEquals(1, recorder.enterSelectionModeCalls)

        node.performClick()
        assertEquals(1, recorder.toggleSelectCalls)
        assertEquals(0, recorder.startEditingCalls)
    }

    /**
     * Regression test for a lost tap target found in review: [OrderedListItemBlock]'s outer
     * `Row` previously carried `Modifier.clickable { onStartEditing() }` covering the whole
     * row including the "N." number marker; this fix's consolidation deleted that row-level
     * modifier without replacing it, leaving the marker as dead space (only the inner
     * [WikiLinkText] content -- not the marker -- got the new gesture wiring).
     */
    @Test
    fun orderedListItemNumberMarker_tap_entersEditMode() {
        val recorder = Recorder()
        composeTestRule.setContent {
            BlockItem(
                block = block(content = "1. Plain block text", blockType = BlockType.OrderedListItem(number = 1)),
                isEditing = false,
                onToggleSelect = { recorder.toggleSelectCalls++ },
                onEnterSelectionMode = { recorder.enterSelectionModeCalls++ },
                onStartEditing = { recorder.startEditingCalls++ },
                onStopEditing = {},
                onContentChange = { _, _ -> },
                onLinkClick = { recorder.linkClicked = it },
                onNewBlock = {},
                onSplitBlock = { _, _ -> },
            )
        }
        composeTestRule.onNodeWithText("1.").performClick()

        assertEquals(1, recorder.startEditingCalls)
        assertEquals(0, recorder.enterSelectionModeCalls)
    }

    /**
     * Regression test for a gesture-swallowing bug found in review: [HeadingBlock] always
     * registered a non-null `onLongPress` lambda with `detectTapGestures`, even when
     * `onLongPressSelect` itself is null (Android -- row-level long-press-to-select is
     * suppressed there in favor of the gutter's drag-after-long-press, see
     * `useLongPressForDrag`). Once `detectTapGestures` sees a non-null `onLongPress`, holding
     * past the long-press timeout resolves as "handled by onLongPress" and `onTap` never fires
     * for that gesture -- so a held/slow tap on a heading silently did nothing on Android,
     * regardless of what the (no-op) `onLongPressSelect?.invoke()` body did. This composes
     * [HeadingBlock] directly (bypassing [BlockItem], which cannot express `onLongPressSelect =
     * null` from a jvmTest context since `useLongPressForDrag()` is `false` on the JVM target)
     * to pin the desired fallback: a long-press with a null `onLongPressSelect` must still
     * dispatch as an ordinary tap.
     */
    @Test
    fun headingLongPress_withNullOnLongPressSelect_fallsBackToEditMode() {
        var startEditingCalls = 0
        composeTestRule.setContent {
            HeadingBlock(
                content = "# Heading text",
                level = 1,
                linkColor = Color.Blue,
                onStartEditing = { startEditingCalls++ },
                onLinkClick = {},
                onLongPressSelect = null,
            )
        }
        composeTestRule.onNodeWithText("Heading text").performTouchInput { longClick() }

        assertEquals(1, startEditingCalls)
    }

    /**
     * Regression test for the same gesture-swallowing bug as
     * [headingLongPress_withNullOnLongPressSelect_fallsBackToEditMode], but pinning
     * [WikiLinkText]'s own copy of the fallback -- [BlockViewer] delegates its rendering to
     * [WikiLinkText], which has an identical `onLongPressSelect?.invoke() ?: dispatchTap(tapOffset)`
     * fallback that, prior to this test, had no direct coverage (only [HeadingBlock]'s copy
     * did). This composes [WikiLinkText] directly for the same reason the heading test
     * bypasses [BlockItem]: `onLongPressSelect = null` can't be expressed through [BlockItem]
     * from jvmTest since `useLongPressForDrag()` is `false` on the JVM target.
     */
    @Test
    fun wikiLinkTextLongPress_withNullOnLongPressSelect_fallsBackToEditMode() {
        var startEditingCalls = 0
        composeTestRule.setContent {
            WikiLinkText(
                text = "Plain block text",
                textColor = Color.Black,
                linkColor = Color.Blue,
                onClick = { startEditingCalls++ },
                onLongPressSelect = null,
            )
        }
        composeTestRule.onNodeWithText("Plain block text").performTouchInput { longClick() }

        assertEquals(1, startEditingCalls)
    }

    /**
     * Regression coverage for [OrderedListItemBlock]'s number-marker `combinedClickable`
     * (see [orderedListItemNumberMarker_tap_entersEditMode]): tapping the marker while the
     * row is already in selection mode must toggle selection, not start editing -- mirrors
     * [tapWhileInSelectionMode_togglesSelection_notEditMode] but for the marker's own
     * recognizer rather than [WikiLinkText]'s.
     */
    @Test
    fun orderedListItemNumberMarker_tapWhileInSelectionMode_togglesSelection_notEditMode() {
        val recorder = Recorder()
        composeTestRule.setContent {
            BlockItem(
                block = block(content = "1. Plain block text", blockType = BlockType.OrderedListItem(number = 1)),
                isEditing = false,
                isInSelectionMode = true,
                onToggleSelect = { recorder.toggleSelectCalls++ },
                onEnterSelectionMode = { recorder.enterSelectionModeCalls++ },
                onStartEditing = { recorder.startEditingCalls++ },
                onStopEditing = {},
                onContentChange = { _, _ -> },
                onLinkClick = { recorder.linkClicked = it },
                onNewBlock = {},
                onSplitBlock = { _, _ -> },
            )
        }
        composeTestRule.onNodeWithText("1.").performClick()

        assertEquals(1, recorder.toggleSelectCalls)
        assertEquals(0, recorder.startEditingCalls)
    }

    /**
     * Regression coverage for [OrderedListItemBlock]'s number-marker `combinedClickable`
     * (see [orderedListItemNumberMarker_tap_entersEditMode]): a genuine long-press on the
     * marker (`onLongPressSelect` non-null, as on the JVM target where
     * `useLongPressForDrag()` is `false`) must enter selection mode, not start editing --
     * mirrors [genuineLongPress_entersSelectionMode_notEditMode] but for the marker itself.
     */
    @Test
    fun orderedListItemNumberMarker_longPress_entersSelectionMode_notEditMode() {
        val recorder = Recorder()
        composeTestRule.setContent {
            BlockItem(
                block = block(content = "1. Plain block text", blockType = BlockType.OrderedListItem(number = 1)),
                isEditing = false,
                onToggleSelect = { recorder.toggleSelectCalls++ },
                onEnterSelectionMode = { recorder.enterSelectionModeCalls++ },
                onStartEditing = { recorder.startEditingCalls++ },
                onStopEditing = {},
                onContentChange = { _, _ -> },
                onLinkClick = { recorder.linkClicked = it },
                onNewBlock = {},
                onSplitBlock = { _, _ -> },
            )
        }
        composeTestRule.onNodeWithText("1.").performTouchInput { longClick() }

        assertEquals(1, recorder.enterSelectionModeCalls)
        assertEquals(0, recorder.startEditingCalls)
    }

    /**
     * Regression test for the same gesture-swallowing bug as
     * [headingLongPress_withNullOnLongPressSelect_fallsBackToEditMode] and
     * [wikiLinkTextLongPress_withNullOnLongPressSelect_fallsBackToEditMode], but pinning
     * [OrderedListItemBlock]'s number-marker `combinedClickable` (see
     * [orderedListItemNumberMarker_longPress_entersSelectionMode_notEditMode]): unlike the
     * other two call sites, `combinedClickable`'s `onLongClick` parameter is forwarded
     * `onLongPressSelect` directly rather than through a wrapping lambda, so a null
     * `onLongPressSelect` reaches Compose Foundation's own tap detector as a true `null` --
     * it never registers a competing long-press branch in the first place, and a held/slow
     * tap on the marker should resolve as an ordinary click rather than being silently
     * swallowed. This composes [OrderedListItemBlock] directly for the same reason the other
     * two null-fallback tests bypass [BlockItem]: `onLongPressSelect = null` can't be
     * expressed through [BlockItem] from jvmTest since `useLongPressForDrag()` is `false` on
     * the JVM target.
     */
    @Test
    fun orderedListItemNumberMarker_longPress_withNullOnLongPressSelect_fallsBackToEditMode() {
        var startEditingCalls = 0
        composeTestRule.setContent {
            OrderedListItemBlock(
                content = "1. Plain block text",
                number = 1,
                linkColor = Color.Blue,
                onStartEditing = { startEditingCalls++ },
                onLinkClick = {},
                onLongPressSelect = null,
            )
        }
        composeTestRule.onNodeWithText("1.").performTouchInput { longClick() }

        assertEquals(1, startEditingCalls)
    }
}
