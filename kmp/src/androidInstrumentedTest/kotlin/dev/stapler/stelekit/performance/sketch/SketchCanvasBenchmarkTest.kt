// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance.sketch

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stapler.stelekit.performance.probe.FrameSampler
import dev.stapler.stelekit.performance.probe.FrameStats
import dev.stapler.stelekit.performance.probe.Mutations
import dev.stapler.stelekit.performance.probe.Pause
import dev.stapler.stelekit.performance.probe.ProbeReporter
import dev.stapler.stelekit.performance.probe.ProbeRun
import dev.stapler.stelekit.sketch.SketchDocument
import dev.stapler.stelekit.sketch.SketchHistory
import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.SketchScene
import dev.stapler.stelekit.ui.sketch.SKETCH_CANVAS_TAG
import dev.stapler.stelekit.ui.sketch.SketchCanvas
import dev.stapler.stelekit.ui.sketch.SketchPhase
import dev.stapler.stelekit.ui.sketch.SketchUiState
import dev.stapler.stelekit.units.Inches
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * Story 3.8: `SketchCanvasPan200`. Pans the real [SketchCanvas] for [FRAMES] scripted frames over an
 * empty scene and then over [PRIMITIVES] primitives in the same run, so the verdict is the RATIO of
 * the two median frame times (emulator speed cancels), plus the absolute p95 against the 24 ms
 * Pixel_Fold budget. Records go to [ProbeReporter]; `scripts/bench-budget-check.js` decides, and
 * both entries stay report-only until the B.7b promotion.
 *
 * The mutation probe adds a per-draw text `measure()` storm around the canvas (test code only) and
 * asserts the ratio then exceeds [RATIO_BUDGET], proving the probe can fail. The same slow path
 * runs for the whole scenario when `annotateMutation=perDrawMeasure`, giving a red CI run.
 *
 * Run: ./gradlew :kmp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.stapler.stelekit.performance.sketch.SketchCanvasBenchmarkTest
 */
@RunWith(AndroidJUnit4::class)
class SketchCanvasBenchmarkTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun sketchCanvasPan200_should_ReportFrameTimeRelativeToEmptyCanvas() {
        val slow = Mutations.isActive(Mutations.PER_DRAW_MEASURE)
        val empty = panFrames(SketchScene.EMPTY, slow)
        val run = ProbeRun(context, SCENARIO).begin()
        val loaded = panFrames(fixtureScene(), slow)
        val record = run.withFrames(loaded).finish(
            mapOf(
                "frameP50RatioToEmpty" to ratio(loaded, empty),
                "frameP95Ms" to loaded.p95Ms,
            ),
        )
        ProbeReporter.report(context, record)
        assertTrue(empty.frames > MIN_FRAMES && loaded.frames > MIN_FRAMES, "too few frames: ${empty.frames}/${loaded.frames}")
    }

    @Test
    fun mutationProbe_should_ExceedTheRatioBudgetWhenDrawIsDeliberatelySlow() {
        val scene = fixtureScene()
        val healthy = ratio(panFrames(scene, slowDraw = false), panFrames(SketchScene.EMPTY, slowDraw = false))
        val slow = ratio(panFrames(scene, slowDraw = true), panFrames(SketchScene.EMPTY, slowDraw = false))
        assertTrue(slow > RATIO_BUDGET, "mutated ratio $slow did not exceed the budget $RATIO_BUDGET (healthy $healthy): the probe cannot fail")
    }

    private fun ratio(loaded: FrameStats, empty: FrameStats): Double = loaded.p50Ms / maxOf(empty.p50Ms, MIN_BASELINE_MS)

    private fun panFrames(scene: SketchScene, slowDraw: Boolean): FrameStats {
        val state = SketchUiState(phase = SketchPhase.READY, history = SketchHistory.of(SketchDocument(scene = scene)))
        rule.setContent { Host(state, slowDraw) }
        rule.waitForIdle()
        val sampler = FrameSampler(rule.activity).also { it.start() }
        val node = rule.onNodeWithTag(SKETCH_CANVAS_TAG)
        node.performTouchInput { down(center) }
        for (i in 0 until FRAMES) {
            val dx = if ((i / DIRECTION_FLIP_EVERY) % 2 == 0) PAN_STEP_PX else -PAN_STEP_PX
            node.performTouchInput { moveBy(Offset(dx, 0f)) }
            rule.waitForIdle()
            Pause.ms(FRAME_PERIOD_MS)
        }
        node.performTouchInput { up() }
        return sampler.stop()
    }

    @Composable
    private fun Host(state: SketchUiState, slowDraw: Boolean) {
        val measurer = rememberTextMeasurer()
        var counter = 0
        val modifier = if (!slowDraw) {
            Modifier.fillMaxSize()
        } else {
            // Unique text defeats the measurer cache, so every frame pays full layout cost.
            Modifier.fillMaxSize().drawWithContent {
                repeat(SLOW_MEASURES_PER_FRAME) { measurer.measure("mutation ${counter++}") }
                drawContent()
            }
        }
        SketchCanvas(state = state, onPlace = {}, onSelect = {}, modifier = modifier)
    }

    private fun fixtureScene(): SketchScene {
        var scene = SketchScene.EMPTY
        for (i in 0 until PRIMITIVES) {
            val x = (i % GRID_COLUMNS) * CELL_INCHES
            val y = (i / GRID_COLUMNS) * CELL_INCHES
            val a = SketchPoint(inches(x), inches(y))
            val b = SketchPoint(inches(x + CELL_INCHES - 1), inches(y + CELL_INCHES - 1))
            val primitive = if (i % 2 == 0) SketchPrimitive.Line("l$i", a, b) else SketchPrimitive.Rect("r$i", a, b)
            scene = scene.add(primitive).getOrNull() ?: error("fixture primitive $i rejected")
        }
        check(scene.size == PRIMITIVES)
        return scene
    }

    private fun inches(whole: Int) = Inches(whole.toLong() * Inches.SIXTEENTHS_PER_INCH)

    private companion object {
        const val SCENARIO = "SketchCanvasPan200"
        const val PRIMITIVES = 200
        const val FRAMES = 600
        const val RATIO_BUDGET = 1.5
        const val MIN_FRAMES = 100
        const val MIN_BASELINE_MS = 1.0
        const val PAN_STEP_PX = 6f
        const val DIRECTION_FLIP_EVERY = 50
        const val FRAME_PERIOD_MS = 16L
        const val SLOW_MEASURES_PER_FRAME = 200
        const val GRID_COLUMNS = 20
        const val CELL_INCHES = 6
    }
}
