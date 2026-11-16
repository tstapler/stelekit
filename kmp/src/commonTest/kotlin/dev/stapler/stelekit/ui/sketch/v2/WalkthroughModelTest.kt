// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.ui.unit.LayoutDirection
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 3.6.8e (ux.md section 22): the walkthrough's steps, wording variants, focus return and popover placement, as pure logic. */
class WalkthroughModelTest {
    private val keyNames = Regex("""\b(Shift|Alt|Ctrl|Cmd|Esc|Enter|Tab|Home|Down|Up)\b""")

    private fun started(input: InputKind = InputKind.Mouse, grouped: Boolean = true, returnFocus: String = "overflow") =
        WalkthroughState().also { it.start(input, grouped, returnFocus) }

    @Test
    fun walkthrough_should_HaveExactlyThreeSteps_InTheOrderToolsZoomShortcuts() {
        assertEquals(listOf("Tools", "Zoom", "Shortcuts"), WalkthroughStep.entries.map { it.title })
        assertEquals(listOf(1, 2, 3), WalkthroughStep.entries.map { it.number })
        assertEquals(3, WalkthroughCopy.TOTAL)
    }

    @Test
    fun next_should_WalkTheStepsAndEndAfterTheLast_ReturningTheFocusToRestore() {
        val w = started()
        assertEquals(WalkthroughStep.Tools, w.step)
        assertNull(w.next(InputKind.Mouse, true))
        assertEquals(WalkthroughStep.Zoom, w.step)
        assertNull(w.next(InputKind.Mouse, true))
        assertEquals(WalkthroughStep.Shortcuts, w.step)
        assertEquals("overflow", w.next(InputKind.Mouse, true), "Done ends the tour and names where focus goes back")
        assertFalse(w.active)
    }

    @Test
    fun back_should_StopAtTheFirstStep() {
        val w = started()
        w.back(InputKind.Mouse, true)
        assertEquals(WalkthroughStep.Tools, w.step)
        w.next(InputKind.Mouse, true)
        w.back(InputKind.Mouse, true)
        assertEquals(WalkthroughStep.Tools, w.step)
    }

    @Test
    fun end_should_ReturnFocusToTheCanvas_When_NoControlHeldItBefore() {
        val w = started(returnFocus = WalkthroughState.CANVAS_FOCUS)
        assertEquals(WalkthroughState.CANVAS_FOCUS, w.end())
        assertEquals(WalkthroughState.CANVAS_FOCUS, WalkthroughState().end(), "ending a tour that is not shown is harmless")
    }

    @Test
    fun activatingTheAnchoredControl_should_EndTheTour_ButNotAnyOtherControl() {
        val w = started()
        assertNull(w.anchorActivated(WalkthroughStep.Zoom), "the zoom buttons are not the anchor of step 1")
        assertTrue(w.active)
        assertEquals("overflow", w.anchorActivated(WalkthroughStep.Tools))
        assertFalse(w.active)
    }

    @Test
    fun theVariant_should_BeChosenWhenAStepIsShown_AndStayFixedUntilTheNextChange() {
        val w = started(InputKind.TouchOrPen)
        val shown = WalkthroughCopy.sentence(w.step!!, w.variant, w.grouped)
        // The user touches the keyboard while step 1 is on screen: nothing re-reads the input until a step is shown.
        assertEquals(InputKind.TouchOrPen, w.variant)
        assertEquals(shown, WalkthroughCopy.sentence(w.step!!, w.variant, w.grouped))
        w.next(InputKind.Keyboard, false)
        assertEquals(InputKind.Keyboard, w.variant)
    }

    @Test
    fun touchAndMouseTexts_should_NameNoKey_And_KeyboardTextsOnlyShiftTabAndArrowKeys() {
        for (step in WalkthroughStep.entries) {
            for (grouped in listOf(true, false)) {
                for (variant in listOf(InputKind.TouchOrPen, InputKind.Mouse)) {
                    val text = WalkthroughCopy.sentence(step, variant, grouped)
                    assertFalse(keyNames.containsMatchIn(text), "$step $variant grouped=$grouped names a key: $text")
                }
                val keyboard = WalkthroughCopy.sentence(step, InputKind.Keyboard, grouped)
                assertFalse(keyboard.contains("Home") || keyboard.contains("Tab to cycle"), "a key that needs canvas focus: $keyboard")
            }
        }
    }

    @Test
    fun stepOne_should_FollowTheStripState_And_NeverMentionAChevronWhenFlat() {
        for (variant in InputKind.entries) {
            assertTrue(WalkthroughCopy.sentence(WalkthroughStep.Tools, variant, grouped = true).let { "chevron" in it || "Down opens" in it })
            assertFalse("chevron" in WalkthroughCopy.sentence(WalkthroughStep.Tools, variant, grouped = false))
            assertFalse("long press" in WalkthroughCopy.sentence(WalkthroughStep.Tools, variant, grouped = false))
        }
    }

    @Test
    fun stepTwo_should_NameTheViewButtonsBeforeTheGesture() {
        for (variant in listOf(InputKind.TouchOrPen, InputKind.Mouse)) {
            val text = WalkthroughCopy.sentence(WalkthroughStep.Zoom, variant, true)
            assertTrue(text.indexOf("view buttons") in 0 until text.indexOf("also"), text)
        }
    }

    @Test
    fun noText_should_NameAScreenPosition_Or_TellTheUserToOperateThePointedAtControl() {
        val position = Regex("""(?<![-\w])(bottom|top|left|right|corner)(?![-\w])""", RegexOption.IGNORE_CASE)
        val instruct = Regex("""(?:^|\.\s)(tap|click|press|open|select)\b""", RegexOption.IGNORE_CASE)
        for (step in WalkthroughStep.entries) for (variant in InputKind.entries) for (grouped in listOf(true, false)) {
            val text = WalkthroughCopy.sentence(step, variant, grouped)
            assertFalse(position.containsMatchIn(text), "screen position: $text")
            assertFalse(instruct.containsMatchIn(text), "instructs the pointed-at control: $text")
        }
    }

    @Test
    fun announcement_should_BeTheIndicatorTheTitleAndTheSentenceAsOneString() {
        val text = WalkthroughCopy.announcement(WalkthroughStep.Zoom, InputKind.Mouse, true)
        assertEquals("Step 2 of 3. Zoom. ${SketchStrings.Walkthrough.ZOOM_MOUSE}", text)
        assertEquals("Step 3 of 3. Shortcuts. Shortcuts are listed in the menu, under Keyboard shortcuts.", WalkthroughCopy.announcement(WalkthroughStep.Shortcuts, InputKind.Keyboard, false))
    }

    // ---- placement --------------------------------------------------------------------------------------------------

    private val container = PlaceRect(0f, 0f, 400f, 800f)

    @Test
    fun popover_should_SitAboveAStripAtTheBottom_AndBelowAControlAtTheTop() {
        val strip = PlaceRect(0f, 740f, 400f, 800f)
        assertEquals(PopoverSide.Above, PopoverPlacement.side(strip, 300f, 120f, container))
        val overflow = PlaceRect(350f, 0f, 400f, 48f)
        assertEquals(PopoverSide.Below, PopoverPlacement.side(overflow, 300f, 120f, container))
    }

    @Test
    fun popover_should_MoveToTheOppositeSide_When_ItWouldNotFitOnTheRoomierOne() {
        val tight = PlaceRect(0f, 60f, 400f, 120f)
        assertEquals(PopoverSide.Below, PopoverPlacement.side(tight, 300f, 200f, container))
    }

    @Test
    fun logicalSides_should_MirrorInRightToLeft() {
        val anchor = PlaceRect(150f, 300f, 250f, 360f)
        val ltr = PopoverPlacement.origin(PopoverSide.Start, anchor, 100f, 80f, container, LayoutDirection.Ltr)
        val rtl = PopoverPlacement.origin(PopoverSide.Start, anchor, 100f, 80f, container, LayoutDirection.Rtl)
        assertEquals(50f, ltr.first)
        assertEquals(250f, rtl.first)
    }

    @Test
    fun popover_should_NeverCoverItsAnchor_Nor_LeaveTheContainer() = runTest {
        val edge = Arb.int(0..300).map { it.toFloat() }
        val size = Arb.int(20..200).map { it.toFloat() }
        checkAll(ITERATIONS, edge, edge, size, size, size, size) { x, y, w, h, pw, ph ->
            val anchor = PlaceRect(x, y, x + w, y + h)
            val side = PopoverPlacement.side(anchor, pw, ph, container)
            val (px, py) = PopoverPlacement.origin(side, anchor, pw, ph, container, LayoutDirection.Ltr)
            assertTrue(px >= container.left && py >= container.top && px + pw <= container.right + EPS && py + ph <= container.bottom + EPS, "left the container: $px,$py")
            val fits = when (side) {
                PopoverSide.Above -> anchor.top >= ph
                PopoverSide.Below -> container.bottom - anchor.bottom >= ph
                PopoverSide.Start -> anchor.left >= pw
                PopoverSide.End -> container.right - anchor.right >= pw
            }
            if (fits) {
                val overlapsX = px < anchor.right && px + pw > anchor.left
                val overlapsY = py < anchor.bottom && py + ph > anchor.top
                assertFalse(overlapsX && overlapsY, "covers its anchor at $side")
            }
        }
    }

    private companion object {
        const val EPS = 0.01f
        const val ITERATIONS = 200
    }
}
