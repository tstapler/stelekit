// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.baseline

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FontStatus
import dev.stapler.stelekit.sketch.v2.text.TextMetricsProvider
import dev.stapler.stelekit.sketch.v2.text.TextStyle
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.annotate.performanceNowMs
import dev.stapler.stelekit.ui.sketch.v2.ScenePainter
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Task 6.2.9c on wasmJs: p95 of 120 pan frames of the real `ScenePainter` over 500 rectangles, the same scene as
 * `ScenePainterTest.pan500Elements_should_StayWithinTheFrameBudget` on the JVM, into a software ImageBitmap in headless Chrome.
 * Prints one `SKETCH-PAINTER-WASM-RESULT {json}` line. The 24 ms ceiling is the pivot trigger (with 1.5x the empty canvas), recorded
 * in `implementation/pivots.md`, so this test asserts only that it measured something.
 */
class ScenePainterWasmBenchmark {
    private object StubMetrics : TextMetricsProvider {
        override val fontStatus: FontStatus = FontStatus.Loaded
        override fun lineHeight(style: TextStyle): Double = style.fontSize
        override fun advance(text: String, style: TextStyle): Double = text.length * style.fontSize
        override fun hasGlyph(codePoint: Int, style: TextStyle): Boolean = true
    }

    private fun sceneOf(count: Int): SketchSceneV2 = (0 until count).fold(SketchSceneV2.empty()) { s, i ->
        val rect = RectElement(
            ElementBase(ElementId("r$i"), style = ElementStyle(backgroundColor = "#e8f0fe", roughness = 0)),
            SceneUnit.ofInt((i % COLUMNS) * STEP_X), SceneUnit.ofInt((i / COLUMNS) * STEP_Y), SceneUnit.ofInt(RECT_W), SceneUnit.ofInt(RECT_H),
        )
        s.add(rect).getOrNull()!!
    }

    private fun framesMs(count: Int): List<Double> {
        val ops = DrawListBuilder(StubMetrics).build(sceneOf(count), RenderStyle.Clean)
        val painter = ScenePainter()
        val bitmap = ImageBitmap(WIDTH, HEIGHT, ImageBitmapConfig.Argb8888)
        val times = ArrayList<Double>(FRAMES)
        for (frame in 0 until WARMUP + FRAMES) {
            val t0 = performanceNowMs()
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(WIDTH.toFloat(), HEIGHT.toFloat())) {
                drawRect(Color.White)
                painter.paint(this, ops, SketchViewport.of(panX = -frame * PAN_X, panY = -frame * PAN_Y), null)
            }
            val ms = performanceNowMs() - t0
            if (frame >= WARMUP) times += ms
        }
        return times
    }

    private fun p95(xs: List<Double>): Double = xs.sorted()[((xs.size * P95).toInt() - 1).coerceAtLeast(0)]

    @Test
    fun recordsPainterP95AtFiveHundredElements() {
        val empty = p95(framesMs(0))
        val rects = p95(framesMs(ELEMENTS))
        println("SKETCH-PAINTER-WASM-RESULT {\"emptyP95Ms\":$empty,\"painterRect500P95Ms\":$rects,\"frames\":$FRAMES,\"canvas\":\"${WIDTH}x$HEIGHT\",\"host\":\"wasmJs headless chrome\"}")
        assertTrue(rects > 0.0)
    }

    private companion object {
        const val ELEMENTS = 500
        const val COLUMNS = 25
        const val STEP_X = 50
        const val STEP_Y = 38
        const val RECT_W = 44
        const val RECT_H = 32
        const val WIDTH = 1280
        const val HEIGHT = 800
        const val FRAMES = 120
        const val WARMUP = 60
        const val PAN_X = 3.0
        const val PAN_Y = 2.0
        const val P95 = 0.95
    }
}
