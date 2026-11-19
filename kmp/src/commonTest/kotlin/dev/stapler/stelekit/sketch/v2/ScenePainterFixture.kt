// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.ScenePainter

/**
 * The painter's pixel fixture (Task 2.1.3c): a filled rectangle, a filled ellipse and a hachured diamond rendered
 * offscreen at 200 x 120. The same scene and the same checks run on every target (JVM, wasmJs, Robolectric), so a
 * target whose rasteriser drifts fails here instead of silently differing.
 */
object ScenePainterFixture {
    const val WIDTH = 200
    const val HEIGHT = 120
    const val RECT_FILL = 0xFFFFC9C9.toInt()
    const val ELLIPSE_FILL = 0xFFA5D8FF.toInt()

    /** Per-channel difference, out of 255, that still counts as the same colour across rasterisers. */
    const val CHANNEL_TOLERANCE = 3

    /** Share of pixels allowed to differ between two renders of the same fixture on different targets. */
    const val MAX_DIFFERING_FRACTION = 0.02

    private fun unit(v: Int) = SceneUnit.ofInt(v)

    private fun base(id: String, style: ElementStyle) = ElementBase(ElementId(id), style = style, seed = SEED)

    fun elements(): List<SceneElement> = listOf(
        RectElement(base("rect", ElementStyle(backgroundColor = "#ffc9c9", roughness = 0)), unit(10), unit(10), unit(80), unit(50)),
        EllipseElement(base("ellipse", ElementStyle(backgroundColor = "#a5d8ff", roughness = 0)), unit(150), unit(35), unit(30), unit(20)),
        DiamondElement(
            base("diamond", ElementStyle(backgroundColor = "#b2f2bb", fillStyle = FillStyle.Hachure, roughness = 1)),
            unit(20), unit(70), unit(60), unit(40),
        ),
    )

    fun scene(): SketchSceneV2 = elements().fold(SketchSceneV2.empty()) { s, e -> s.add(e).getOrNull()!! }

    fun ops(style: RenderStyle = RenderStyle.Sketchy): List<DrawOp> = DrawListBuilder(FixedAdvanceTextMetricsProvider()).build(scene(), style)

    fun render(
        ops: List<DrawOp>,
        viewport: SketchViewport = SketchViewport.of(),
        painter: ScenePainter = ScenePainter(),
        visible: Set<ElementId>? = null,
    ): ImageBitmap {
        val bitmap = ImageBitmap(WIDTH, HEIGHT, ImageBitmapConfig.Argb8888)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(WIDTH.toFloat(), HEIGHT.toFloat())) {
            drawRect(Color.White)
            painter.paint(this, ops, viewport, visible)
        }
        return bitmap
    }

    fun pixels(bitmap: ImageBitmap): IntArray = IntArray(bitmap.width * bitmap.height).also { bitmap.readPixels(it) }

    /** True when every channel of [actual] is within [CHANNEL_TOLERANCE] of [expected] (ARGB ints). */
    fun close(expected: Int, actual: Int): Boolean = (0..3).all { shift ->
        val e = (expected ushr (shift * 8)) and 0xFF
        val a = (actual ushr (shift * 8)) and 0xFF
        kotlin.math.abs(e - a) <= CHANNEL_TOLERANCE
    }

    /** Fraction of pixels of [a] and [b] that differ by more than the channel tolerance. */
    fun differingFraction(a: IntArray, b: IntArray): Double = a.indices.count { !close(a[it], b[it]) }.toDouble() / a.size

    private const val SEED = 1337
}
