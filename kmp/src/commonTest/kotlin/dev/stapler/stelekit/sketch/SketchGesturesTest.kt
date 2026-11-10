package dev.stapler.stelekit.sketch

import arrow.core.Either
import dev.stapler.stelekit.ui.sketch.SketchTool
import dev.stapler.stelekit.ui.sketch.TapAction
import dev.stapler.stelekit.ui.sketch.TapOrDragClassifier
import dev.stapler.stelekit.ui.sketch.pxToDp
import dev.stapler.stelekit.ui.sketch.resolveTap
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SketchGesturesTest {
    private fun <A> Either<*, A>.ok(): A = fold({ error("unexpected $it") }, { it })

    private val view = ViewTransform.of()
    private val grid = Inches(16)

    @Test
    fun lineToolTapPlacesAGridSnappedPoint() {
        val tap = ScreenPoint(Rational(100), Rational(95)) // about 1.04 in, 0.99 in
        val action = resolveTap(SketchTool.LINE, SketchScene.EMPTY, view, tap, grid, null).ok()
        assertEquals(TapAction.Place(SketchPoint(Inches(16), Inches(16))), action)
    }

    @Test
    fun zoneSelectTapPicksThePrimitiveUnderItOrNothing() {
        val line = SketchPrimitive.Line("l", SketchPoint(Inches(0), Inches(16)), SketchPoint(Inches(160), Inches(16)))
        val scene = SketchScene.EMPTY.add(line).ok()
        val on = resolveTap(SketchTool.ZONE_SELECT, scene, view, ScreenPoint(Rational(480), Rational(96)), grid, null).ok()
        assertEquals(TapAction.Select("l"), on)
        val off = resolveTap(SketchTool.ZONE_SELECT, scene, view, ScreenPoint(Rational(480), Rational(300)), grid, null).ok()
        assertEquals(TapAction.Select(null), off)
    }

    @Test
    fun classifierLatchesToDragBeyondSlopOnly() {
        val c = TapOrDragClassifier(8f)
        assertFalse(c.update(5f, -5f))
        assertTrue(c.update(9f, 0f))
        assertTrue(c.update(0f, 0f), "once latched it stays a drag")
    }

    @Test
    fun pixelConversionIsExactToAThousandthOfADp() {
        assertEquals(Rational(1, 2), pxToDp(1f, 2f))
    }
}
