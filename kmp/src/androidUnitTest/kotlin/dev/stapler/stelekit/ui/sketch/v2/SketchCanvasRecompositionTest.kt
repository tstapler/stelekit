// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 2.1.3, Task 2.1.3b: dragging one element of a 500-element scene recomposes the live layer and never the static
 * one (a pan does not recompose either layer). Robolectric; Gradle-only like the other Compose host tests.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class SketchCanvasRecompositionTest {
    @get:Rule
    val rule = createComposeRule()

    private class Probe : RecompositionProbe {
        var static = 0
        var live = 0
        override fun onStaticLayerComposed() { static++ }
        override fun onLiveLayerComposed() { live++ }
    }

    private fun unit(v: Int) = SceneUnit.ofInt(v)

    private fun rect(i: Int, x: Int = (i % COLUMNS) * STEP, version: Int = 1) = RectElement(
        ElementBase(ElementId("r$i"), style = ElementStyle(backgroundColor = "#e8f0fe", roughness = 0), version = version),
        unit(x), unit((i / COLUMNS) * STEP), unit(SIZE), unit(SIZE),
    )

    private fun scene(movedX: Int? = null): SketchSceneV2 =
        (0 until ELEMENTS).fold(SketchSceneV2.empty()) { s, i -> s.add(if (i == 0 && movedX != null) rect(0, movedX, version = movedX + 1) else rect(i)).getOrNull()!! }

    @Test
    fun dragging_should_RecomposeTheLiveLayerOnly_And_PanShouldRecomposeNeither() {
        val builder = DrawListBuilder(FixedAdvanceTextMetricsProvider())
        val base = scene()
        val index = SpatialIndex().also { it.rebuild(base) }
        val dragged = ElementId("r0")
        val handle = StaticLayerHandle()
        val split = splitForGesture(builder.build(base, RenderStyle.Clean), setOf(dragged))
        handle.set(split.static, index)
        val probe = Probe()
        val painter = ScenePainter()
        var live by mutableStateOf<List<DrawOp>>(split.live)
        var viewport by mutableStateOf(SketchViewport.of())
        val viewportState = androidx.compose.runtime.derivedStateOf { viewport }
        rule.setContent { SketchCanvasV2(handle, live, viewportState, painter, probe = probe) }
        rule.waitForIdle()
        assertEquals(1, probe.static)
        val liveBefore = probe.live
        assertTrue(liveBefore >= 1)

        for (step in 1..MOVES) {
            live = splitForGesture(builder.build(scene(movedX = step), RenderStyle.Clean), setOf(dragged)).live
            rule.waitForIdle()
        }
        assertEquals(1, probe.static, "the static layer recomposed during the drag")
        assertTrue(probe.live >= liveBefore + MOVES, "the live layer recomposes with every move (${probe.live})")
        assertTrue(ELEMENTS - 1 <= split.static.size, "the static list holds the other elements")

        for (step in 1..MOVES) {
            viewport = SketchViewport.of(panX = step * 2.0)
            rule.waitForIdle()
        }
        assertEquals(1, probe.static, "a pan recomposed the static layer")
    }

    @Test
    fun builder_should_ConvertOnlyTheDraggedElement_OnEveryMove() {
        val builder = DrawListBuilder(FixedAdvanceTextMetricsProvider())
        builder.build(scene(), RenderStyle.Clean)
        val before = builder.builtElementCount
        builder.build(scene(movedX = 3), RenderStyle.Clean)
        assertEquals(1, builder.builtElementCount - before)
    }

    private companion object {
        const val ELEMENTS = 500
        const val COLUMNS = 25
        const val STEP = 50
        const val SIZE = 40
        const val MOVES = 60
    }
}
