// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.constraints

import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.LengthFormatter
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.round

/** Which natural dimension a [DimensionSuggestion] offers (UX-115). */
enum class SuggestionKind { Length, Radius, CentreToEdge, Remaining }

/**
 * One smart-dimension chip (Story 4.2.7, Task 4.2.7c, D-21). [label] is the chip text without its action, for example the text Length 6 in;
 * [constraint] is the driving dimension it would add, already holding the current measure; [prefill] is that measure as the
 * `DimensionField` text (it re-parses with `DimensionInput`). Accepting the chip only opens the field: nothing is added until
 * a typed value is committed through the normal add path. [addable] is false when the constraint model cannot hold the
 * dimension yet (see [DimensionSuggestions.suggest]).
 */
data class DimensionSuggestion(
    val kind: SuggestionKind,
    val label: String,
    val constraint: Constraint,
    val prefill: String,
    val addable: Boolean,
)

/**
 * Which selection suggests which dimension (pure rules, Task 4.2.7c). One two-point line suggests its Length, one circle or
 * arc its Radius, a circle (or arc) and a two-point line the centre-to-edge Distance, and a selection with no natural
 * dimension (text, an arrow, two lines) suggests nothing. With nothing selected and degrees of freedom left, the first
 * element without a driving dimension of its own is suggested as "Suggested: Width of Line 1".
 *
 * Centre-to-edge is a point-to-line distance; `ConstraintParts` accepts a Distance only between two points or on one segment,
 * so that suggestion is reported not addable until the model gains the operand pair.
 */
object DimensionSuggestions {
    /** The kinds the owner exposes (A6 ADJUST narrows them, plan "Task 0.1.2d"); the default exposes both suggested kinds. */
    val DefaultKinds: Set<ConstraintKind> = setOf(ConstraintKind.Distance, ConstraintKind.Radius)

    /**
     * The suggestion for [selection] on [doc], or null. [degreesOfFreedom] is the status line's count (null when unknown);
     * [newId] mints the id of the suggested constraint; [exposed] narrows the kinds that may be suggested.
     */
    fun suggest(
        doc: SketchDocumentV2,
        selection: List<ElementId>,
        degreesOfFreedom: Int?,
        newId: () -> ConstraintId,
        exposed: Set<ConstraintKind> = DefaultKinds,
    ): DimensionSuggestion? {
        val picked = selection.distinct().mapNotNull { doc.scene[it] }
        if (picked.size != selection.distinct().size) return null
        val found = when (picked.size) {
            0 -> if ((degreesOfFreedom ?: 0) > 0) remaining(doc, newId) else null
            1 -> single(picked.first(), newId)
            PAIR -> centreToEdge(picked, newId)
            else -> null
        }
        return found?.takeIf { it.constraint.kind in exposed }
    }

    private fun single(e: SceneElement, newId: () -> ConstraintId): DimensionSuggestion? = when {
        isSimpleLine(e) -> length(e as LineElement, newId, "Length")
        radiusOf(e) != null -> radius(e, newId, "Radius", SuggestionKind.Radius)
        else -> null
    }

    private fun length(line: LineElement, newId: () -> ConstraintId, word: String, kind: SuggestionKind = SuggestionKind.Length): DimensionSuggestion {
        val (a, b) = line.points
        val value = hypot(b.x.value - a.x.value, b.y.value - a.y.value)
        return built(kind, "$word ${inchesText(value)}?", Constraint(newId(), ConstraintKind.Distance, listOf(ConstraintTarget(line.id)), value), value, listOf(line))
    }

    private fun radius(e: SceneElement, newId: () -> ConstraintId, word: String, kind: SuggestionKind): DimensionSuggestion {
        val value = radiusOf(e) ?: 0.0
        return built(kind, "$word ${inchesText(value)}?", Constraint(newId(), ConstraintKind.Radius, listOf(ConstraintTarget(e.id)), value), value, listOf(e))
    }

    private fun centreToEdge(picked: List<SceneElement>, newId: () -> ConstraintId): DimensionSuggestion? {
        val curve = picked.firstOrNull { radiusOf(it) != null } ?: return null
        val line = picked.firstOrNull { isSimpleLine(it) } as? LineElement ?: return null
        val (cx, cy) = centreOf(curve) ?: return null
        val (a, b) = line.points
        val dx = b.x.value - a.x.value
        val dy = b.y.value - a.y.value
        val span = hypot(dx, dy)
        if (span == 0.0) return null
        val value = abs(dx * (cy - a.y.value) - dy * (cx - a.x.value)) / span
        val targets = listOf(ConstraintTarget(curve.id, TargetPart.Centre), ConstraintTarget(line.id, TargetPart.Edge(0)))
        return built(SuggestionKind.CentreToEdge, "Centre to edge ${inchesText(value)}?", Constraint(newId(), ConstraintKind.Distance, targets, value), value, listOf(curve, line))
    }

    private fun remaining(doc: SketchDocumentV2, newId: () -> ConstraintId): DimensionSuggestion? {
        for (e in doc.scene.elementsInOrder) {
            val candidate = candidateFor(e, newId) ?: continue
            if (DimensionAdd.plan(doc, candidate.constraint) !is DimensionAddPlan.Add) continue
            val name = ElementNames.nameOf(e)
            val word = candidate.word
            return candidate.suggestion.copy(kind = SuggestionKind.Remaining, label = "Suggested: $word of $name")
        }
        return null
    }

    private class Candidate(val suggestion: DimensionSuggestion, val word: String) {
        val constraint: Constraint get() = suggestion.constraint
    }

    private fun candidateFor(e: SceneElement, newId: () -> ConstraintId): Candidate? = when {
        isSimpleLine(e) -> (e as LineElement).let { line ->
            val (a, b) = line.points
            val word = when {
                a.y.value == b.y.value -> "Width"
                a.x.value == b.x.value -> "Height"
                else -> "Length"
            }
            Candidate(length(line, newId, word), word)
        }
        e is RectElement -> rectWidth(e, newId)
        radiusOf(e) != null -> Candidate(radius(e, newId, "Radius", SuggestionKind.Radius), "Radius")
        else -> null
    }

    private fun rectWidth(rect: RectElement, newId: () -> ConstraintId): Candidate {
        val value = rect.width.value
        val constraint = Constraint(newId(), ConstraintKind.Distance, listOf(ConstraintTarget(rect.id, TargetPart.Edge(0))), value)
        return Candidate(built(SuggestionKind.Length, "Width ${inchesText(value)}?", constraint, value, listOf(rect)), "Width")
    }

    /** [elements] line up with the constraint's targets; the parts table decides [DimensionSuggestion.addable] from them. */
    private fun built(kind: SuggestionKind, label: String, constraint: Constraint, value: Double, elements: List<SceneElement>): DimensionSuggestion {
        val refs = constraint.targets.zip(elements) { target, element -> PartRef(element, target.part) }
        return DimensionSuggestion(kind, label, constraint, prefillText(value), addable = ConstraintParts.accepts(constraint.kind, refs))
    }

    private fun isSimpleLine(e: SceneElement): Boolean = e is LineElement && !e.polygon && e.points.size == PAIR

    private fun radiusOf(e: SceneElement): Double? = when {
        e is EllipseElement && e.isCircle -> e.rx.value
        e is ArcElement -> e.radius.value
        else -> null
    }

    private fun centreOf(e: SceneElement): Pair<Double, Double>? = when (e) {
        is EllipseElement -> if (e.isCircle) e.cx.value to e.cy.value else null
        is ArcElement -> e.cx.value to e.cy.value
        else -> null
    }

    /** The measure as whole sixteenths of an inch (the sheet's unit), as the field text: `6"`, `1/2"`, `33 7/16"`. */
    private fun prefillText(units: Double): String = LengthFormatter.format(Inches(round(units).toLong()), LengthFormat.INCHES_MIXED)

    private fun inchesText(units: Double): String = prefillText(units).removeSuffix("\"") + " in"

    private const val PAIR = 2
}
