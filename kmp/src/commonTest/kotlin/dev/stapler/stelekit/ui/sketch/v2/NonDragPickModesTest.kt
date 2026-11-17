// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.BindingEnd
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import dev.stapler.stelekit.sketch.v2.tools.BindArrowEndCommand
import dev.stapler.stelekit.sketch.v2.tools.RectSpec
import dev.stapler.stelekit.sketch.v2.tools.commitToolCommand
import dev.stapler.stelekit.sketch.v2.tools.elId
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.sketch.v2.tools.sceneOfElements
import dev.stapler.stelekit.sketch.v2.tools.sp
import dev.stapler.stelekit.sketch.v2.tools.su
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 3.6.3b (UX-40, WCAG 2.5.7): bind an arrow end and marquee-select without a drag. */
class NonDragPickModesTest {
    private fun named(e: RectElement, name: String) = e.copy(base = e.base.copy(name = name))

    private val boxA = named(filledRectEl("a", 0, 0, 100, 60), "Box A")
    private val boxB = named(filledRectEl("b", 300, 0, 100, 60), "Box B")
    private val label = TextElement(ElementBase(elId("t1")), su(10), su(10), su(40), su(20), text = "API", containerId = elId("a"))
    private val arrow = ArrowElement(ElementBase(elId("ar")), listOf(sp(110, 30), sp(250, 30)))
    private val scene = sceneOfElements(boxA, boxB, label, arrow)

    private fun bind(end: BindingEnd = BindingEnd.End, selection: List<String> = listOf("ar"), s: SketchSceneV2 = scene) =
        assertIs<BindStart.Started>(NonDragBindMode.begin(s, selection.map(::elId), end)).mode

    @Test
    fun bindEnd_should_ListShapesNearestFirst_LabelledByNameAndText_ExcludingTheArrowAndBoundLabels() {
        val atEnd = bind(BindingEnd.End).candidates
        assertEquals(listOf("Box B", "Box A: API"), atEnd.map { it.label })
        assertTrue(atEnd[0].distance <= atEnd[1].distance)
        assertEquals(listOf("Box A: API", "Box B"), bind(BindingEnd.Start).candidates.map { it.label })
    }

    @Test
    fun bindEnd_should_BindThePickedTarget_AsOneUndoStep_AndRunNothingBeforeAPick() {
        val picked = bind().pick(elId("b"))
        assertEquals("Bind the end to Box B", picked.previewText())
        val command = assertIs<BindArrowEndCommand>(picked.apply())
        val doc = SketchDocumentV2(scene)
        val history = commitToolCommand(doc, command)
        val result = history.present.scene[elId("ar")] as ArrowElement
        assertEquals(elId("b"), result.endBinding?.elementId)
        assertNull(result.startBinding)
        assertTrue(reconcile(history.present.scene).isClean)
        assertEquals(1, history.undoDepth)
        assertEquals(doc, history.undo().present)
        assertNull(bind().apply(), "nothing is picked yet")
    }

    @Test
    fun bindEnd_should_ChangeTheEndAndDropAPick_IgnoreAnIdThatIsNotOffered_AndEscapeInTwoSteps() {
        val picked = bind().pick(elId("b"))
        assertEquals(BindingEnd.Start, picked.withEnd(BindingEnd.Start).end)
        assertNull(picked.withEnd(BindingEnd.Start).picked)
        assertEquals(elId("b"), picked.pick(elId("missing")).picked)
        assertNull(picked.afterEscape()?.picked)
        assertNull(bind().afterEscape())
    }

    @Test
    fun bindEnd_should_OfferALockedShape() {
        val locked = filledRectEl("l", 300, 100, 50, 50, RectSpec(locked = true))
        val offered = bind(s = sceneOfElements(boxA, locked, arrow)).candidates.map { it.id }
        assertTrue(elId("l") in offered)
    }

    @Test
    fun bindEnd_should_RefuseWithAReason_When_SelectionIsNotOneEditableArrowOrNothingCanBeBound() {
        fun refused(s: SketchSceneV2, sel: List<String>) = assertIs<BindStart.Refused>(NonDragBindMode.begin(s, sel.map(::elId))).notice
        assertEquals(NonDragNotice.NothingSelected, refused(scene, emptyList()))
        assertEquals(NonDragNotice.NotAnArrow, refused(scene, listOf("a")))
        assertEquals(NonDragNotice.NotAnArrow, refused(scene, listOf("ar", "a")))
        val lockedArrow = arrow.copy(base = arrow.base.copy(locked = true))
        assertEquals(NonDragNotice.Locked, refused(sceneOfElements(boxA, lockedArrow), listOf("ar")))
        assertEquals(NonDragNotice.NoTargets, refused(sceneOfElements(arrow), listOf("ar")))
    }

    private fun marquee(step: NudgeStep = NudgeStep.Ten) = NonDragMarqueeMode.begin(scene, -10.0, -10.0, step)

    @Test
    fun marquee_should_SelectWhatTheTwoCornersEnclose_AndSummariseTheCount() {
        var m = marquee()
        assertEquals("Crosshair at -10, -10. Place the first corner.", m.summaryText())
        m = m.placeCorner()
        assertTrue(m.pending)
        assertTrue(m.summaryText().startsWith("Corner placed at -10, -10"))
        repeat(PAST_BOX_A) { m = m.nudge(1, 1) }
        m = m.placeCorner()
        assertEquals(listOf(elId("a"), elId("t1")), m.contained())
        assertEquals("2 elements in region", m.summaryText())
    }

    @Test
    fun marquee_should_PickUpEverythingFullyInside_AndSkipLockedElements() {
        val locked = filledRectEl("l", 10, 70, 20, 20, RectSpec(locked = true))
        val start = NonDragMarqueeMode.begin(sceneOfElements(boxA, locked, boxB), -10.0, -10.0).placeCorner()
        val wide = (0 until WIDE).fold(start) { acc, _ -> acc.nudgeFar(1, 1) }.placeCorner()
        assertEquals(listOf(elId("a")), wide.contained())
        val none = NonDragMarqueeMode.begin(scene, 400.0, 400.0).placeCorner().nudge(1, 1).placeCorner()
        assertEquals("No elements in region", none.summaryText())
        assertEquals(emptyList(), none.contained())
    }

    @Test
    fun marquee_should_StartOverAfterEscape_ThenLeave_AndStepsScale() {
        val placed = marquee(NudgeStep.One).nudgeFar(1, 0).placeCorner()
        assertEquals(0.0, placed.cursorX)
        assertTrue(placed.pending)
        val restarted = assertIs<NonDragMarqueeMode>(placed.afterEscape())
        assertTrue(!restarted.pending)
        assertNull(restarted.afterEscape())
        assertEquals(1.0, marquee(NudgeStep.One).nudge(1, 0).cursorX - marquee().cursorX)
        assertEquals(PERCENT_OF_CONTENT, marquee(NudgeStep.Percent).nudge(1, 0).cursorX - marquee().cursorX, 1e-9)
    }

    private companion object {
        /** Ten-unit nudges that carry the cursor from (-10, -10) past box A's far corner (100, 60). */
        const val PAST_BOX_A = 12

        /** Far nudges from (-10, -10) to (190, 190): the region holds box A and not box B at x 300. */
        const val WIDE = 20

        /** The drawing spans x 0..400, so one percent is 4 units. */
        const val PERCENT_OF_CONTENT = 4.0
    }
}
