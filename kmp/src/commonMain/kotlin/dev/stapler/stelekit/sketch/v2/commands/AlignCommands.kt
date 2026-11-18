// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.right
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.view.SceneBounds

// Align, distribute and equal spacing (Story 3.5.3a; the 8 FB-AL baseline ids). Each command moves whole elements
// by a delta through the same shift as MoveElements, so frames carry members and rotation is honoured by the bounds.

enum class AlignEdge { Left, CenterHorizontal, Right, Top, CenterVertical, Bottom }

enum class DistributeAxis { Horizontal, Vertical }

/** The id of the baseline command each align or distribute entry implements (`feature-baseline.txt`, Task 3.5.4c). */
object AlignBaseline {
    val alignIds: Map<AlignEdge, String> = mapOf(
        AlignEdge.Left to "FB-AL-01", AlignEdge.CenterHorizontal to "FB-AL-02", AlignEdge.Right to "FB-AL-03",
        AlignEdge.Top to "FB-AL-04", AlignEdge.CenterVertical to "FB-AL-05", AlignEdge.Bottom to "FB-AL-06",
    )
    val distributeIds: Map<DistributeAxis, String> = mapOf(DistributeAxis.Horizontal to "FB-AL-07", DistributeAxis.Vertical to "FB-AL-08")
}

private const val HALF = 2.0

private fun SketchSceneV2.boundsOf(ids: List<ElementId>): Either<CommandError, List<Pair<SceneElement, SceneBounds>>> = either {
    ids.distinct().map { id ->
        val e = this@boundsOf[id] ?: raise(CommandError.UnknownElement(id.value))
        e to (SceneBounds.of(e) ?: raise(CommandError.Unsupported(id.value, "align")))
    }
}

/** Applies one delta per element as a single batched move; zero deltas are left out so a no-op is [CommandError.NoChange]-clean. */
private fun SketchDocumentV2.shiftEach(deltas: Map<ElementId, Pair<Double, Double>>): Either<CommandError, Applied> =
    if (deltas.values.all { it.first == 0.0 && it.second == 0.0 }) Applied(this, emptySet()).right() else moveEach(deltas)

/** Moves every element so the chosen edge or centre of its bounds lines up with the selection's extreme or middle. */
data class AlignElements(val ids: List<ElementId>, val edge: AlignEdge) : Command {
    private companion object {
        const val MIN_TO_ALIGN = 2
    }

    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val items = doc.scene.boundsOf(ids).bind()
        if (items.size < MIN_TO_ALIGN) return@either Applied(doc, emptySet())
        val union = SceneBounds(items.minOf { it.second.minX }, items.minOf { it.second.minY }, items.maxOf { it.second.maxX }, items.maxOf { it.second.maxY })
        val deltas = items.associate { (e, b) ->
            e.id to when (edge) {
                AlignEdge.Left -> union.minX - b.minX to 0.0
                AlignEdge.Right -> union.maxX - b.maxX to 0.0
                AlignEdge.CenterHorizontal -> (union.minX + union.maxX) / HALF - (b.minX + b.maxX) / HALF to 0.0
                AlignEdge.Top -> 0.0 to union.minY - b.minY
                AlignEdge.Bottom -> 0.0 to union.maxY - b.maxY
                AlignEdge.CenterVertical -> 0.0 to (union.minY + union.maxY) / HALF - (b.minY + b.maxY) / HALF
            }
        }
        doc.shiftEach(deltas).bind()
    }
}

/**
 * Spreads the elements so the gaps between neighbours along [axis] are equal, keeping the two outermost where they are.
 * With fewer than three elements nothing changes. This is also "equal spacing": the gap, not the centre, is equalised.
 */
data class DistributeElements(val ids: List<ElementId>, val axis: DistributeAxis) : Command {
    override fun apply(doc: SketchDocumentV2): Either<CommandError, Applied> = either {
        val items = doc.scene.boundsOf(ids).bind()
        if (items.size < MIN_ELEMENTS) return@either Applied(doc, emptySet())
        fun lo(b: SceneBounds) = if (axis == DistributeAxis.Horizontal) b.minX else b.minY
        fun hi(b: SceneBounds) = if (axis == DistributeAxis.Horizontal) b.maxX else b.maxY
        val sorted = items.sortedBy { lo(it.second) }
        val span = hi(sorted.maxBy { hi(it.second) }.second) - lo(sorted.first().second)
        val gap = (span - sorted.sumOf { hi(it.second) - lo(it.second) }) / (sorted.size - 1)
        var cursor = lo(sorted.first().second)
        val deltas = LinkedHashMap<ElementId, Pair<Double, Double>>()
        for ((e, b) in sorted) {
            val d = cursor - lo(b)
            deltas[e.id] = if (axis == DistributeAxis.Horizontal) d to 0.0 else 0.0 to d
            cursor += hi(b) - lo(b) + gap
        }
        doc.shiftEach(deltas).bind()
    }

    private companion object {
        const val MIN_ELEMENTS = 3
    }
}
