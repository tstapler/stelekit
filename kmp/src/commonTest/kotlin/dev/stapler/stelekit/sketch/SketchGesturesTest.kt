package dev.stapler.stelekit.sketch

import arrow.core.Either
import dev.stapler.stelekit.ui.sketch.SketchTool
import dev.stapler.stelekit.ui.sketch.TapAction
import dev.stapler.stelekit.ui.sketch.TapOrDragClassifier
import dev.stapler.stelekit.ui.sketch.applyDrag
import dev.stapler.stelekit.ui.sketch.applyPinch
import dev.stapler.stelekit.ui.sketch.drawFrameOf
import dev.stapler.stelekit.ui.sketch.toFloat
import dev.stapler.stelekit.ui.sketch.toInchesFloat
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

class SketchViewMathTest {
    private val origin = ScreenPoint(Rational(16), Rational(16))

    @Test
    fun dragMovesTheOriginByTheFingerDelta() {
        val moved = applyDrag(ViewTransform.of(origin = origin), 20f, -10f, 2f)
        assertEquals(Rational(26), moved.origin.x)
        assertEquals(Rational(11), moved.origin.y)
    }

    @Test
    fun pinchZoomsAboutTheCentroidKeepingItFixed() {
        val v0 = ViewTransform.of(origin = origin)
        val centroid = ScreenPoint(Rational(112), Rational(112))
        val before = v0.toSheet(centroid).getOrNull()
        val v1 = applyPinch(v0, 0f, 0f, 2f, 112f, 112f, 1f)
        assertEquals(Rational(2), v1.zoom)
        assertEquals(before, v1.toSheet(centroid).getOrNull())
    }

    @Test
    fun pinchIsClampedToTheLegalZoomRange() {
        val v = applyPinch(ViewTransform.of(), 0f, 0f, 1000f, 0f, 0f, 1f)
        assertEquals(ViewTransform.MAX_ZOOM, v.zoom)
    }

    @Test
    fun drawFrameMapsInchesToPixelsAndKeepsStrokeWidthInDp() {
        val frame = drawFrameOf(ViewTransform.of(origin = origin), 2f)
        assertEquals(32f, frame.originX)
        assertEquals(192f, frame.scale)
        assertEquals(32f + 192f to 32f + 2 * 192f, frame.px(1f, 2f))
        assertEquals(2f * 2f / 192f, frame.strokeInSheetUnits(2f, 2f))
    }

    @Test
    fun toInchesFloatAndRationalFloatConvert() {
        assertEquals(1.5f, Inches(24).toInchesFloat())
        assertEquals(0.25f, Rational(1, 4).toFloat())
    }
}
