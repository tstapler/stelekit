package dev.stapler.stelekit.sketch

import arrow.core.Either
import dev.stapler.stelekit.fusion.DimensionStatus
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SketchHistoryTest {
    private fun <A> Either<*, A>.ok(): A = fold({ error("unexpected $it") }, { it })

    private fun pt(x: Long, y: Long) = SketchPoint(Inches(x), Inches(y))
    private fun line(id: String) = SketchPrimitive.Line(id, pt(0, 0), pt(16, 0))
    private fun add(doc: SketchDocument, id: String) = SketchCommand.AddPrimitive(line(id)).apply(doc).ok()

    @Test
    fun oneCommitIsOneStepAndUndoRedoRoundTrip() {
        val h0 = SketchHistory.of()
        val h1 = h0.commit(add(h0.present, "a"))
        assertEquals(1, h1.undoDepth)
        assertEquals(listOf("a"), h1.present.scene.primitives.map { it.id })
        val undone = h1.undo()
        assertTrue(undone.present.scene.primitives.isEmpty())
        assertTrue(undone.canRedo)
        assertEquals(listOf("a"), undone.redo().present.scene.primitives.map { it.id })
    }

    @Test
    fun thirtyTransientMovesCommitOnceAsOneEntry() {
        var h = SketchHistory.of()
        val moved = (1..30).fold(add(h.present, "a")) { doc, _ -> doc } // 30 moves change no committed state
        h = h.commit(moved)
        assertEquals(1, h.undoDepth)
    }

    @Test
    fun noChangeCommitIsIgnored() {
        val h = SketchHistory.of().commit(add(SketchDocument(), "a"))
        assertEquals(1, h.commit(SketchDocument(h.present.scene, h.present.zones)).undoDepth)
    }

    @Test
    fun committingAfterUndoDropsRedoBranch() {
        val h1 = SketchHistory.of().let { it.commit(add(it.present, "a")) }
        val h2 = h1.undo().let { it.commit(add(it.present, "b")) }
        assertFalse(h2.canRedo)
        assertEquals(listOf("b"), h2.present.scene.primitives.map { it.id })
    }

    @Test
    fun stackIsBoundedAtOneHundredAndOldestIsDropped() {
        var h = SketchHistory.of()
        for (i in 1..150) h = h.commit(add(h.present, "p$i"))
        assertEquals(SketchHistory.MAX_STEPS, h.undoDepth)
        var undone = h
        while (undone.canUndo) undone = undone.undo()
        assertEquals(50, undone.present.scene.size)
    }

    @Test
    fun commandsReportUnknownTargets() {
        assertTrue(SketchCommand.RemovePrimitive("nope").apply(SketchDocument()) is Either.Left)
        assertTrue(SketchCommand.SetZoneText("nope", "1").apply(SketchDocument()) is Either.Left)
    }

    private fun zonesDoc(): SketchDocument {
        var doc = SketchDocument()
        doc = SketchCommand.AddZone(Zone("total", isTotal = true)).apply(doc).ok()
        doc = SketchCommand.AddZone(Zone("a")).apply(doc).ok()
        doc = SketchCommand.AddZone(Zone("b")).apply(doc).ok()
        return doc
    }

    @Test
    fun typingLengthOnZoneDerivesTheMissingPartAndHoldsTheSum() {
        var doc = zonesDoc()
        doc = SketchCommand.SetZoneText("total", "36").apply(doc).ok()
        doc = SketchCommand.SetZoneText("a", "12 3/16").apply(doc).ok()
        val report = ZoneConstraints.resolve(doc.zones).ok()
        val b = report.dimensions.getValue("b")
        assertIs<DimensionStatus.Derivable>(b)
        assertEquals(Rational(36).subtract(Rational(195, 16)).ok(), b.value)
        assertFalse(report.sumViolated)
    }

    @Test
    fun violatedSumIsFlagged() {
        var doc = zonesDoc()
        doc = SketchCommand.SetZoneText("total", "36").apply(doc).ok()
        doc = SketchCommand.SetZoneText("a", "12 3/16").apply(doc).ok()
        doc = SketchCommand.SetZoneText("b", "20").apply(doc).ok()
        assertTrue(ZoneConstraints.resolve(doc.zones).ok().sumViolated)
    }

    @Test
    fun unparseableZoneTextIsReportedNotThrown() {
        val doc = SketchCommand.SetZoneText("a", "banana").apply(zonesDoc()).ok()
        assertEquals(setOf("a"), ZoneConstraints.resolve(doc.zones).ok().textErrors)
    }
}
