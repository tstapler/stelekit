// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.sketch.v2.binding.BindArrowEnd
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.ArrowLabels
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.ContainerText
import dev.stapler.stelekit.sketch.v2.commands.DeleteElements
import dev.stapler.stelekit.sketch.v2.commands.HistoryV2
import dev.stapler.stelekit.sketch.v2.commands.MoveElements
import dev.stapler.stelekit.sketch.v2.commands.SetArrowLabel
import dev.stapler.stelekit.sketch.v2.commands.SetContainerText
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.commands.relayoutLabels
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.BindingEnd
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.FontStatus
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Story 3.3.3: text in shapes (the container grows, the label goes with it) and arrow labels at the midpoint. */
class ContainerTextTest {
    private val metrics = FixedAdvanceTextMetricsProvider(advancePerCodePoint = 8.0, lineHeightUnits = 20.0)

    private fun <L, R> Either<L, R>.right(): R = fold({ error("unexpected Left: $it") }, { it })

    private fun <L, R> Either<L, R>.left(): L = fold({ it }, { error("unexpected Right: $it") })

    private fun u(d: Double) = SheetFixtures.u(d)

    private fun id(s: String) = ElementId(s)

    private fun rect(s: String, x: Double, y: Double, w: Double, h: Double) = RectElement(ElementBase(id = id(s)), u(x), u(y), u(w), u(h))

    private fun arrow(s: String, vararg pts: Pair<Double, Double>) =
        ArrowElement(ElementBase(id = id(s)), pts.map { SheetFixtures.pt(it.first, it.second) })

    private fun docOf(vararg elements: SceneElement): SketchDocumentV2 = AddElements(elements.toList()).apply(SketchDocumentV2()).right().doc

    private fun SketchDocumentV2.with(vararg commands: Command): SketchDocumentV2 = commands.fold(this) { doc, c -> c.apply(doc).right().doc }

    private fun label(doc: SketchDocumentV2, owner: String): TextElement = assertNotNull(ContainerText.labelOf(doc.scene, id(owner)))

    private fun near(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < EPS, "expected $expected but was $actual")

    @Test
    fun commit_should_GrowTheContainerToTheWrappedHeightPlusPadding_When_TextIsTooTallForIt() {
        val doc = docOf(rect("r1", 0.0, 0.0, 80.0, 40.0)).with(SetContainerText(id("r1"), id("t1"), "Authentication Service", metrics))

        val t1 = label(doc, "r1")
        val r1 = doc.scene[id("r1")] as RectElement
        assertEquals(3, t1.text.lines().size, "wrapped to the 70-wide usable area, 8 per glyph")
        assertEquals("Authentication Service", t1.originalText)
        near(t1.height.value + 2 * ContainerText.PADDING, r1.height.value)
        assertEquals(80.0, r1.width.value, "only the height grows")
        assertEquals(0.0, r1.y.value, "the top edge stays put")
    }

    @Test
    fun label_should_BeCentredInTheContainer_And_BoundThroughBoundElements() {
        val doc = docOf(rect("r1", 10.0, 20.0, 100.0, 60.0)).with(SetContainerText(id("r1"), id("t1"), "Hi", metrics))

        val t1 = label(doc, "r1")
        near(60.0, t1.x.value + t1.width.value / 2)
        near(50.0, t1.y.value + t1.height.value / 2)
        assertEquals(60.0, (doc.scene[id("r1")] as RectElement).height.value, "a roomy container is left alone")
        assertTrue((doc.scene[id("r1")] as RectElement).boundElements.any { it.id == id("t1") })
    }

    @Test
    fun delete_should_RemoveTheLabelInTheSameStep_And_OneUndoRestoresBoth() {
        val doc = docOf(rect("r1", 0.0, 0.0, 80.0, 40.0)).with(SetContainerText(id("r1"), id("t1"), "Authentication Service", metrics))
        val tx = Transaction.begin(doc).apply(DeleteElements(listOf(id("r1")))).right()
        val step = tx.commit().right()

        assertEquals(setOf(id("r1"), id("t1")), step.changedIds)
        assertTrue(step.after.scene[id("t1")] == null && step.after.scene[id("r1")] == null)
        val history = HistoryV2.of(doc).commit(step).right()
        assertEquals(doc, history.undo().present)
    }

    @Test
    fun settingTheSameText_should_ChangeNothing_SoTheAutoResizeLoopSettles() {
        val doc = docOf(rect("r1", 0.0, 0.0, 80.0, 40.0)).with(SetContainerText(id("r1"), id("t1"), "Authentication Service", metrics))

        val again = Transaction.begin(doc).apply(SetContainerText(id("r1"), id("t1"), "Authentication Service", metrics)).right().commit()

        assertEquals(CommandError.NoChange, again.left())
    }

    @Test
    fun editingToShorterText_should_KeepTheGrownHeight_And_ReuseTheLabel() {
        val long = docOf(rect("r1", 0.0, 0.0, 80.0, 40.0)).with(SetContainerText(id("r1"), id("t1"), "Authentication Service", metrics))
        val short = long.with(SetContainerText(id("r1"), id("t2"), "OK", metrics))

        assertEquals(id("t1"), label(short, "r1").id, "the existing label is updated, not duplicated")
        assertEquals(1, short.scene.elementsInOrder.count { it is TextElement })
        assertEquals((long.scene[id("r1")] as RectElement).height, (short.scene[id("r1")] as RectElement).height)
    }

    @Test
    fun blankText_should_RemoveTheLabel_And_BlankOnNoLabelShouldChangeNothing() {
        val labelled = docOf(rect("r1", 0.0, 0.0, 80.0, 40.0)).with(SetContainerText(id("r1"), id("t1"), "Hi", metrics))
        val cleared = labelled.with(SetContainerText(id("r1"), id("t1"), "  ", metrics))
        assertTrue(cleared.scene.elementsInOrder.none { it is TextElement })
        assertTrue((cleared.scene[id("r1")] as RectElement).boundElements.isEmpty())

        val none = Transaction.begin(cleared).apply(SetContainerText(id("r1"), id("t1"), "", metrics)).right().commit()
        assertEquals(CommandError.NoChange, none.left())
    }

    @Test
    fun overlongText_should_BeRefusedWithoutTruncation() {
        val doc = docOf(rect("r1", 0.0, 0.0, 80.0, 40.0))
        val limit = SceneLimits.Default.maxTextChars

        val refused = SetContainerText(id("r1"), id("t1"), "x".repeat(limit + 1), metrics).apply(doc)

        assertEquals(CommandError.TextTooLong(limit), refused.left())
        val ok = SetContainerText(id("r1"), id("t1"), "x".repeat(limit), metrics).apply(doc)
        assertTrue(ok.isRight(), "exactly the cap is accepted")
    }

    @Test
    fun unloadedFont_should_PersistNothing() {
        val doc = docOf(rect("r1", 0.0, 0.0, 80.0, 40.0))
        val loading = FixedAdvanceTextMetricsProvider(fontStatus = FontStatus.Loading)

        assertEquals(CommandError.FontNotLoaded, SetContainerText(id("r1"), id("t1"), "Hi", loading).apply(doc).left())
    }

    @Test
    fun unsupportedOwners_should_BeRefused() {
        val doc = docOf(arrow("a", 0.0 to 0.0, 10.0 to 0.0))

        assertIs<CommandError.Unsupported>(SetContainerText(id("a"), id("t"), "x", metrics).apply(doc).left())
        assertIs<CommandError.UnknownElement>(SetContainerText(id("missing"), id("t"), "x", metrics).apply(doc).left())
        assertIs<CommandError.UnknownElement>(SetArrowLabel(id("missing"), id("t"), "x", metrics).apply(doc).left())
    }

    @Test
    fun ellipse_should_GrowWithItsTopFixed_UsingTheInscribedRectangle() {
        val circle = EllipseElement(ElementBase(id = id("e")), u(50.0), u(50.0), u(40.0), u(20.0))
        val doc = docOf(circle).with(SetContainerText(id("e"), id("t"), "Authentication Service", metrics))

        val grown = doc.scene[id("e")] as EllipseElement
        near(30.0, grown.cy.value - grown.ry.value)
        assertTrue(grown.ry.value > 20.0)
        assertEquals(40.0, grown.rx.value)
        val t = label(doc, "e")
        near(grown.cx.value, t.x.value + t.width.value / 2)
        assertTrue(t.width.value <= 80.0 * ContainerText.ELLIPSE_INSET - 2 * ContainerText.PADDING + EPS)
    }

    @Test
    fun arrowBoundToTheContainer_should_FollowItsGrowth_InTheSameStep() {
        val base = docOf(rect("r1", 0.0, 0.0, 80.0, 40.0), arrow("a", 40.0 to 100.0, 40.0 to 40.0))
        val bound = base.with(BindArrowEnd(id("a"), BindingEnd.End, id("r1")))
        val before = (bound.scene[id("a")] as ArrowElement).points.last()

        val step = Transaction.begin(bound).apply(SetContainerText(id("r1"), id("t1"), "Authentication Service", metrics)).right().commit().right()

        val after = (step.after.scene[id("a")] as ArrowElement).points.last()
        assertTrue(after.y.value > before.y.value, "the end rides the grown bottom edge")
        assertTrue(id("a") in step.changedIds)
    }

    @Test
    fun arrowLabel_should_SitAtTheArcLengthMidpoint() {
        val doc = docOf(arrow("a", 0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0)).with(SetArrowLabel(id("a"), id("t"), "go", metrics))

        val t = label(doc, "a")
        near(100.0, t.x.value + t.width.value / 2)
        near(0.0, t.y.value + t.height.value / 2)
        assertEquals(id("a"), t.containerId)
    }

    @Test
    fun midpoint_should_HandleEmptyAndZeroLengthArrows() {
        assertEquals(null, ArrowLabels.midpoint(emptyList()))
        val flat = arrow("z", 5.0 to 5.0, 5.0 to 5.0).points
        near(5.0, ArrowLabels.midpoint(flat)!!.x.value)
    }

    @Test
    fun relayout_should_MoveLabelsWithTheirOwner_AndLeaveSettledOnesAlone() {
        val doc = docOf(rect("r1", 0.0, 0.0, 100.0, 60.0)).with(SetContainerText(id("r1"), id("t1"), "Hi", metrics))
        val untouched = relayoutLabels(doc.scene, setOf(id("r1"))).right()
        assertTrue(untouched.labels.isEmpty(), "a label already in place keeps its bytes")

        val moved = doc.scene.replace((doc.scene[id("r1")] as RectElement).copy(x = u(30.0))).right()
        val result = relayoutLabels(moved, setOf(id("r1"))).right()

        assertEquals(setOf(id("t1")), result.labels)
        val t = result.scene[id("t1")] as TextElement
        near(80.0, t.x.value + t.width.value / 2)
    }

    @Test
    fun movingTheContainer_should_CarryTheLabel() {
        val doc = docOf(rect("r1", 0.0, 0.0, 100.0, 60.0)).with(SetContainerText(id("r1"), id("t1"), "Hi", metrics), MoveElements(listOf(id("r1")), 10.0, 0.0))

        val t = label(doc, "r1")
        near(60.0, t.x.value + t.width.value / 2)
    }

    private companion object {
        const val EPS = 1e-9
    }
}
