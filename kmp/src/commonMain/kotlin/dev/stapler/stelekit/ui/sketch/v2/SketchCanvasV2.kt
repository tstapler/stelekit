// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.stapler.stelekit.sketch.v2.index.SpatialIndex
import dev.stapler.stelekit.sketch.v2.render.DrawOp
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.view.SketchViewport

/** Which layer of the canvas recomposed; tests count these (Story 2.1.3: dragging one element recomposes only the live layer). */
interface RecompositionProbe {
    fun onStaticLayerComposed()

    fun onLiveLayerComposed()
}

/** A draw list split into what never changes during a gesture and what does (Task 2.1.3b). */
@Immutable
class SplitDrawList(val static: List<DrawOp>, val live: List<DrawOp>)

/**
 * The ops the static layer paints, held in state read only by the draw phase, so replacing them redraws without
 * recomposing. With an [index] the layer culls to the viewport on every draw; without one it draws everything.
 */
class StaticLayerHandle {
    private var state by mutableStateOf(StaticContent(emptyList(), null))

    val ops: List<DrawOp> get() = state.ops
    val index: SpatialIndex? get() = state.index

    fun set(ops: List<DrawOp>, index: SpatialIndex?) {
        state = StaticContent(ops, index)
    }

    private class StaticContent(val ops: List<DrawOp>, val index: SpatialIndex?)
}

/** Splits [all] so the elements in [liveIds] (being dragged or previewed) are in the live list and the rest in the static one. */
fun splitForGesture(all: List<DrawOp>, liveIds: Set<ElementId>): SplitDrawList {
    if (liveIds.isEmpty()) return SplitDrawList(all, emptyList())
    val static = ArrayList<DrawOp>(all.size)
    val live = ArrayList<DrawOp>()
    for (op in all) (if (opOwner(op) in liveIds) live else static).add(op)
    return SplitDrawList(static, live)
}

private fun opOwner(op: DrawOp): ElementId? = when (op) {
    is DrawOp.PathOp -> op.elementId
    is DrawOp.TextRun -> op.elementId
    is DrawOp.ImageOp -> op.elementId
    is DrawOp.ClipOp -> op.ops.firstNotNullOfOrNull { opOwner(it) }
}

/**
 * The v2 canvas (Story 2.1.3): a static layer for the scene and a live layer for the gesture in progress, both drawn
 * with one [ScenePainter]. The static layer takes its ops from a [StaticLayerHandle] and the viewport from a `State`,
 * both read while drawing, so a pan or a drag never recomposes it; the live layer takes its ops as a parameter and
 * recomposes with them.
 */
@Composable
fun SketchCanvasV2(
    staticLayer: StaticLayerHandle,
    liveOps: List<DrawOp>,
    viewport: State<SketchViewport>,
    painter: ScenePainter,
    modifier: Modifier = Modifier,
    probe: RecompositionProbe? = null,
    region: (() -> ScreenRect)? = null,
) {
    Box(modifier.fillMaxSize()) {
        StaticSceneLayer(staticLayer, viewport, painter, probe, region)
        LiveSceneLayer(liveOps, viewport, painter, probe)
    }
}

@Composable
private fun StaticSceneLayer(layer: StaticLayerHandle, viewport: State<SketchViewport>, painter: ScenePainter, probe: RecompositionProbe?, region: (() -> ScreenRect)?) {
    SideEffect { probe?.onStaticLayerComposed() }
    Canvas(Modifier.fillMaxSize()) {
        val vp = viewport.value
        // A [region] (the loupe) culls to the small area it shows, so its cost follows that area and not the visible scene.
        val area = region?.invoke() ?: ScreenRect(0.0, 0.0, size.width.toDouble(), size.height.toDouble())
        val visible = layer.index?.let { ScenePainter.visibleIdsIn(it, vp, area) }
        painter.paintAndPrune(this, layer.ops, vp, visible)
    }
}

@Composable
private fun LiveSceneLayer(ops: List<DrawOp>, viewport: State<SketchViewport>, painter: ScenePainter, probe: RecompositionProbe?) {
    SideEffect { probe?.onLiveLayerComposed() }
    Canvas(Modifier.fillMaxSize()) {
        painter.paint(this, ops, viewport.value, null)
    }
}
