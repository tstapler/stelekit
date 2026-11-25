// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.sketch.v2.commands.AcceptAllProvenance
import dev.stapler.stelekit.sketch.v2.commands.AcceptProvenance
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.provenance
import dev.stapler.stelekit.sketch.v2.scene.reviewable
import dev.stapler.stelekit.sketch.v2.scene.unreviewed
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import dev.stapler.stelekit.sketch.v2.view.SketchViewport
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchProvenanceStrings
import kotlin.math.roundToInt

object ProvenanceTags {
    const val BAR = "sketch.provenance.bar"
    const val ACCEPT_ALL = "sketch.provenance.acceptall"
    const val ACCEPT_SELECTED = "sketch.provenance.acceptselected"
    const val LAYER = "sketch.provenance.marks"
    fun badge(id: ElementId) = "sketch.provenance.badge.${id.value}"
}

/** One agent-written, unreviewed element on screen: its box and the words a screen reader says for its badge. */
data class ProvenanceMark(val id: ElementId, val x: Double, val y: Double, val width: Double, val height: Double, val description: String)

/** Where the review ghosts and badges go (epic E8). Pure: scene and viewport in, screen rectangles out. */
object ProvenanceModel {
    /** Marks beyond this are left off the canvas; the bar still counts and accepts every one. */
    const val MARK_BUDGET = 300

    fun marks(scene: SketchSceneV2, viewport: SketchViewport, widthPx: Double, heightPx: Double): List<ProvenanceMark> {
        val hidden = scene.layers.filter { !it.visible }.map { it.id }.toSet()
        return scene.unreviewed().asSequence()
            .filter { it.base.layerId !in hidden }
            .mapNotNull { e ->
                val b = SceneBounds.of(listOf(e)) ?: return@mapNotNull null
                val x = b.minX * viewport.zoom + viewport.panX
                val y = b.minY * viewport.zoom + viewport.panY
                ProvenanceMark(e.id, x, y, b.width * viewport.zoom, b.height * viewport.zoom, SketchProvenanceStrings.badgeName(ElementNames.nameOf(e), e.base.provenance?.note))
            }
            .filter { it.x + it.width >= 0.0 && it.y + it.height >= 0.0 && it.x <= widthPx && it.y <= heightPx }
            .take(MARK_BUDGET)
            .toList()
    }
}

private const val GHOST_STROKE_DP = 1.5f
private const val DASH_ON = 8f
private const val DASH_OFF = 6f
private const val BADGE_RAISE_DP = 9
private const val BAR_PAD_DP = 8
private const val BAR_BOTTOM_DP = 72
private const val TARGET_DP = 44

/** Dashed ghost outlines and a small "AI" badge for every unreviewed agent element. Touch-transparent: it never takes the canvas's input. */
@Composable
fun ProvenanceMarks(marks: List<ProvenanceMark>, modifier: Modifier = Modifier) {
    val ghost = MaterialTheme.colorScheme.primary
    Box(modifier.fillMaxSize().testTag(ProvenanceTags.LAYER)) {
        Canvas(Modifier.fillMaxSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(DASH_ON, DASH_OFF))
            for (m in marks) {
                drawRect(ghost, Offset(m.x.toFloat(), m.y.toFloat()), Size(m.width.toFloat(), m.height.toFloat()), style = Stroke(GHOST_STROKE_DP.dp.toPx(), pathEffect = dash))
            }
        }
        for (m in marks) {
            Text(
                SketchProvenanceStrings.BADGE,
                Modifier.offset { IntOffset((m.x + m.width).roundToInt(), m.y.roundToInt() - BADGE_RAISE_DP.dp.roundToPx()) }
                    .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = 4.dp)
                    .testTag(ProvenanceTags.badge(m.id))
                    .semantics { contentDescription = m.description },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/**
 * The review bar: how many agent changes wait, [Accept all] for every one, and Accept for the selected ones. Accepting is one
 * undoable command through the controller. Draws nothing when there is nothing to review or the sheet is read-only.
 */
@Composable
fun BoxScope.ProvenanceBar(controller: SketchEditorController, modifier: Modifier = Modifier) {
    if (controller.readOnly) return
    val waiting = remember(controller.revision) { controller.doc.scene.reviewable().map { it.id } }
    if (waiting.isEmpty()) return
    val selected = controller.ui.selection.map(::ElementId).filter { it in waiting }
    Surface(
        modifier.align(Alignment.BottomCenter).padding(start = BAR_PAD_DP.dp, end = BAR_PAD_DP.dp, bottom = BAR_BOTTOM_DP.dp).fillMaxWidth().testTag(ProvenanceTags.BAR)
            .semantics { contentDescription = SketchProvenanceStrings.BAR_REGION },
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(horizontal = BAR_PAD_DP.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BAR_PAD_DP.dp)) {
            Text(SketchProvenanceStrings.summary(waiting.size), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (selected.isNotEmpty()) {
                TextButton(
                    { controller.commitNow(AcceptProvenance(selected)) },
                    Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(ProvenanceTags.ACCEPT_SELECTED).semantics { contentDescription = SketchProvenanceStrings.ACCEPT_SELECTED_NAME },
                ) { Text(SketchProvenanceStrings.ACCEPT_SELECTED) }
            }
            TextButton(
                { controller.commitNow(AcceptAllProvenance) },
                Modifier.defaultMinSize(minHeight = TARGET_DP.dp).testTag(ProvenanceTags.ACCEPT_ALL).semantics { contentDescription = SketchProvenanceStrings.ACCEPT_ALL_NAME },
            ) { Text(SketchProvenanceStrings.ACCEPT_ALL) }
        }
    }
}

/** The ghosts and badges over the canvas, recomputed when the scene or viewport changes. */
@Composable
fun BoxScope.ProvenanceMarksOverlay(controller: SketchEditorController, modifier: Modifier = Modifier) {
    val size = controller.view.canvasSize
    val viewport = controller.ui.viewport
    val marks = remember(controller.revision, viewport, size) { ProvenanceModel.marks(controller.doc.scene, viewport, size.x, size.y) }
    if (marks.isNotEmpty()) ProvenanceMarks(marks, modifier.align(Alignment.TopStart))
}
