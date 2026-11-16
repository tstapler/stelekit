// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.CssColor
import dev.stapler.stelekit.ui.sketch.v2.PathCache
import dev.stapler.stelekit.ui.sketch.v2.ScenePainter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 2.1.3, Tasks 2.1.3a and 2.1.3c: the painter draws the draw list with the right colours and places (a pixel
 * tolerance, not an exact hash, so JVM, wasmJs and Robolectric rasterisers may differ by anti-aliasing), culls by
 * viewport, and keeps its Compose paths while the draw list is unchanged. Runs on every target.
 */
class ScenePainterPixelTest {
    private fun at(px: IntArray, x: Int, y: Int) = px[y * ScenePainterFixture.WIDTH + x]

    private fun luma(argb: Int): Int = (((argb shr 16) and 0xFF) + ((argb shr 8) and 0xFF) + (argb and 0xFF)) / 3

    private fun assertColour(expected: Int, actual: Int, what: String) =
        assertTrue(ScenePainterFixture.close(expected, actual), "$what: expected ${expected.toUInt().toString(16)} but was ${actual.toUInt().toString(16)}")

    @Test
    fun shapes_should_BeFilledAndStroked_When_PaintedAtZoomOne() {
        val px = ScenePainterFixture.pixels(ScenePainterFixture.render(ScenePainterFixture.ops()))
        assertColour(ScenePainterFixture.RECT_FILL, at(px, 50, 35), "rectangle fill")
        assertColour(ScenePainterFixture.ELLIPSE_FILL, at(px, 150, 35), "ellipse fill")
        assertColour(WHITE, at(px, 195, 115), "background")
        assertTrue(luma(at(px, 10, 35)) < DARK_LUMA, "rectangle stroke on its left edge")
        assertTrue(luma(at(px, 50, 10)) < DARK_LUMA, "rectangle stroke on its top edge")
    }

    @Test
    fun hachuredDiamond_should_ShowHatchLinesNotASolidFill_When_Sketchy() {
        val px = ScenePainterFixture.pixels(ScenePainterFixture.render(ScenePainterFixture.ops()))
        var inked = 0
        var total = 0
        for (y in DIAMOND_TOP..DIAMOND_BOTTOM) {
            for (x in DIAMOND_LEFT..DIAMOND_RIGHT) {
                total++
                if (!ScenePainterFixture.close(WHITE, at(px, x, y))) inked++
            }
        }
        val share = inked.toDouble() / total
        assertTrue(share in MIN_HATCH_SHARE..MAX_HATCH_SHARE, "hatch coverage $share must be neither blank nor solid")
    }

    @Test
    fun render_should_BeIdentical_When_PaintedTwiceWithTheSameInputs() {
        val a = ScenePainterFixture.pixels(ScenePainterFixture.render(ScenePainterFixture.ops()))
        val b = ScenePainterFixture.pixels(ScenePainterFixture.render(ScenePainterFixture.ops()))
        assertEquals(0.0, ScenePainterFixture.differingFraction(a, b))
    }

    @Test
    fun zoomAndPan_should_MoveAndScaleTheShapes_When_ViewportChanges() {
        val ops = ScenePainterFixture.ops(RenderStyle.Clean)
        val panned = ScenePainterFixture.pixels(ScenePainterFixture.render(ops, SketchViewport.of(zoom = 1.0, panX = PAN, panY = 0.0)))
        assertColour(ScenePainterFixture.RECT_FILL, at(panned, 50 + PAN.toInt(), 35), "panned rectangle fill")
        assertColour(WHITE, at(panned, 20, 35), "where the rectangle was")
        val zoomed = ScenePainterFixture.pixels(ScenePainterFixture.render(ops, SketchViewport.of(zoom = 2.0)))
        assertColour(ScenePainterFixture.RECT_FILL, at(zoomed, 150, 60), "zoomed rectangle fill")
    }

    @Test
    fun culling_should_SkipOpsOfElementsOutsideTheVisibleSet() {
        val ops = ScenePainterFixture.ops(RenderStyle.Clean)
        val painter = ScenePainter()
        val all = ScenePainterFixture.render(ops, painter = painter)
        assertEquals(0, painter.culledOps)
        val drawnAll = painter.drawnOps
        ScenePainterFixture.render(ops, painter = painter, visible = setOf(ElementId("rect")))
        assertTrue(painter.culledOps > 0 && painter.drawnOps < drawnAll, "culled ${painter.culledOps}, drawn ${painter.drawnOps} of $drawnAll")
        assertTrue(all.width > 0)
    }

    @Test
    fun visibleIds_should_ReturnOnlyElementsInTheViewport() {
        val index = SpatialIndex()
        index.rebuild(ScenePainterFixture.scene())
        val everything = ScenePainter.visibleIds(index, SketchViewport.of(), ScenePainterFixture.WIDTH.toDouble(), ScenePainterFixture.HEIGHT.toDouble())
        assertEquals(setOf(ElementId("rect"), ElementId("ellipse"), ElementId("diamond")), everything)
        val farAway = ScenePainter.visibleIds(index, SketchViewport.of(panX = -FAR, panY = -FAR), ScenePainterFixture.WIDTH.toDouble(), ScenePainterFixture.HEIGHT.toDouble())
        assertEquals(emptySet(), farAway)
    }

    @Test
    fun pathCache_should_ReuseBuiltPaths_When_TheSameOpsArePaintedAgainAndRebuildWhenTheyChange() {
        val ops = ScenePainterFixture.ops(RenderStyle.Clean)
        val cache = PathCache()
        val painter = ScenePainter(cache)
        ScenePainterFixture.render(ops, painter = painter)
        val built = cache.builtCount
        assertTrue(built > 0)
        ScenePainterFixture.render(ops, SketchViewport.of(panX = PAN), painter)
        assertEquals(built, cache.builtCount, "a pan must not rebuild any path")
        val changed = ops.map { op -> if (op is DrawOp.PathOp && op.elementId == ElementId("rect")) op.copy(segments = op.segments.toList()) else op }
        ScenePainterFixture.render(changed, painter = painter)
        assertTrue(cache.builtCount > built, "a changed segment list rebuilds the path of that element")
    }

    @Test
    fun cssColor_should_ParseTheSheetFormats() {
        assertEquals(androidx.compose.ui.graphics.Color.Transparent, CssColor.parse("transparent"))
        assertEquals(androidx.compose.ui.graphics.Color(0xFF, 0xC9, 0xC9), CssColor.parse("#ffc9c9"))
        assertEquals(androidx.compose.ui.graphics.Color(0xFF, 0x00, 0x00), CssColor.parse("#f00"))
        assertEquals(androidx.compose.ui.graphics.Color.Black, CssColor.parse("not a colour"))
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val DARK_LUMA = 110
        const val PAN = 30.0
        const val FAR = 100_000.0
        const val DIAMOND_LEFT = 30
        const val DIAMOND_RIGHT = 70
        const val DIAMOND_TOP = 85
        const val DIAMOND_BOTTOM = 95
        const val MIN_HATCH_SHARE = 0.05
        const val MAX_HATCH_SHARE = 0.85
    }
}
