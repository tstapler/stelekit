// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.fusion.Constraint
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.Equal
import dev.stapler.stelekit.fusion.Fixed
import dev.stapler.stelekit.fusion.FusionInput
import dev.stapler.stelekit.fusion.InvariantVerdict
import dev.stapler.stelekit.fusion.Observation
import dev.stapler.stelekit.fusion.Offset
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.fusion.Sum
import dev.stapler.stelekit.fusion.fuseObservations
import dev.stapler.stelekit.units.ParsedLength
import dev.stapler.stelekit.units.parseLength

/** The three shapes the editor offers; each maps onto one shipped [Constraint]. Tolerance is the engine's: the summed rounding of the readings. */
sealed interface ConstraintTemplate {
    /** `total = part1 + part2 + ...`; also adds a named digest check called [label]. */
    data class PartsSum(val total: String, val parts: List<String>, val label: String) : ConstraintTemplate

    /** `a = b`. */
    data class Equality(val a: String, val b: String) : ConstraintTemplate

    /** `a - b = offset`, the offset written as a length (`3/4`, `1.5`). */
    data class OffsetBy(val a: String, val b: String, val offsetText: String) : ConstraintTemplate
}

/** Pure constraint editing (Story 5.9e): template to [Constraint], validation and wording. */
object ConstraintEditing {
    const val MAX_CONSTRAINTS = 64
    private val LABEL_FORBIDDEN = Regex("[\\[\\]#|\\\\\\n\\r]")

    fun add(registry: DimensionRegistry, spec: FusionSpec, template: ConstraintTemplate): Either<EditError, FusionSpec> {
        if (spec.constraints.size >= MAX_CONSTRAINTS) return EditError.TooMany.left()
        val id = (spec.constraints.maxOfOrNull { it.id } ?: 0) + 1
        val built = build(id, template).fold({ return it.left() }, { it })
        built.dimensions.firstOrNull { registry.entry(it) == null }?.let { return EditError.UnknownKey(it).left() }
        if (spec.constraints.any { sameShape(it, built) }) return EditError.DuplicateConstraint.left()
        val checks = (template as? ConstraintTemplate.PartsSum)?.let { spec.checks + SumCheck(it.label.trim(), id, it.total, it.parts) }
        return spec.copy(constraints = spec.constraints + built, checks = checks ?: spec.checks).right()
    }

    /** Drops the constraint and any digest check that pointed at it. */
    fun remove(spec: FusionSpec, id: Int): FusionSpec =
        spec.copy(constraints = spec.constraints.filter { it.id != id }, checks = spec.checks.filter { it.constraintId != id })

    /** True when a constraint or digest check names [key]. */
    fun reads(spec: FusionSpec, key: String): Boolean =
        spec.constraints.any { key in it.dimensions } || spec.checks.any { it.total == key || key in it.parts }

    fun renameKey(spec: FusionSpec, old: String, new: String): FusionSpec {
        fun name(key: String) = if (key == old) new else key
        return FusionSpec(
            constraints = spec.constraints.map { c ->
                when (c) {
                    is Fixed -> c.copy(dimension = name(c.dimension))
                    is Sum -> c.copy(total = name(c.total), parts = c.parts.map(::name))
                    is Equal -> c.copy(a = name(c.a), b = name(c.b))
                    is Offset -> c.copy(a = name(c.a), b = name(c.b))
                }
            },
            checks = spec.checks.map { it.copy(total = name(it.total), parts = it.parts.map(::name)) },
            zoneOrder = spec.zoneOrder.map(::name),
        )
    }

    /**
     * The first structural problem, or null. Constraints may name keys outside the registry (the engine treats them as
     * intermediate unknowns), so only unique ids and checks that point at a real constraint are required.
     */
    fun validate(spec: FusionSpec): EditError? = when {
        spec.constraints.map { it.id }.distinct().size != spec.constraints.size -> EditError.DuplicateConstraint
        spec.checks.any { check -> spec.constraints.none { it.id == check.constraintId } } -> EditError.InvalidRegistry
        else -> null
    }

    fun describe(constraint: Constraint): String = when (constraint) {
        is Fixed -> "${constraint.dimension} = ${constraint.value}"
        is Sum -> "${constraint.total} = ${constraint.parts.joinToString(" + ")}"
        is Equal -> "${constraint.a} = ${constraint.b}"
        is Offset -> "${constraint.a} - ${constraint.b} = ${constraint.k}"
    }

    private fun build(id: Int, template: ConstraintTemplate): Either<EditError, Constraint> = when (template) {
        is ConstraintTemplate.PartsSum -> when {
            template.parts.isEmpty() -> EditError.NeedsParts.left()
            template.parts.distinct().size != template.parts.size || template.total in template.parts -> EditError.RepeatedKey.left()
            !validLabel(template.label) -> EditError.BadLabel.left()
            else -> Sum(id, template.total, template.parts).right()
        }
        is ConstraintTemplate.Equality ->
            if (template.a == template.b) EditError.RepeatedKey.left() else Equal(id, template.a, template.b).right()
        is ConstraintTemplate.OffsetBy -> when {
            template.a == template.b -> EditError.RepeatedKey.left()
            else -> (parseLength(template.offsetText.trim()).getOrNull() as? ParsedLength.Measured)
                ?.let { Offset(id, template.a, template.b, it.value).right() } ?: EditError.BadOffset.left()
        }
    }

    private fun validLabel(label: String): Boolean {
        val text = label.trim()
        return text.isNotEmpty() && text.length <= RegistryEditing.MAX_LABEL && !LABEL_FORBIDDEN.containsMatchIn(text)
    }

    private fun sameShape(a: Constraint, b: Constraint): Boolean = when (a) {
        is Fixed -> b is Fixed && a.copy(id = 0) == b.copy(id = 0)
        is Sum -> b is Sum && a.copy(id = 0) == b.copy(id = 0)
        is Equal -> b is Equal && a.copy(id = 0) == b.copy(id = 0)
        is Offset -> b is Offset && a.copy(id = 0) == b.copy(id = 0)
    }
}

/** The solver's reading of one constraint, in the editor's words. */
enum class PreviewVerdict(val label: String) { HOLD("HOLD"), FAIL("FAIL"), UNDETERMINED("UNDETERMINED") }

data class ConstraintPreviewRow(val id: Int, val text: String, val verdict: PreviewVerdict)

/** Runs the shipped FusionEngine over the readings available so far; the preview writes nothing. */
object ConstraintPreview {
    /** One row per constraint; every row is UNDETERMINED when the engine refuses the input. */
    fun rows(registry: DimensionRegistry, spec: FusionSpec, observations: List<Observation>): List<ConstraintPreviewRow> {
        val result = fuseObservations(FusionInput(observations, spec.constraints, spec.zoneOrder), registry).getOrNull()
        return spec.constraints.map { constraint ->
            val verdict = when (result?.invariants?.firstOrNull { it.constraintId == constraint.id }?.verdict) {
                InvariantVerdict.HOLDS -> PreviewVerdict.HOLD
                InvariantVerdict.FAILS -> PreviewVerdict.FAIL
                InvariantVerdict.UNDETERMINED, null -> PreviewVerdict.UNDETERMINED
            }
            ConstraintPreviewRow(constraint.id, ConstraintEditing.describe(constraint), verdict)
        }
    }

    /** The readings the project's YAML snapshot gives under [registry]'s yaml aliases. */
    fun yamlObservations(registry: DimensionRegistry, yamlText: String?): List<Observation> =
        yamlText?.let { YamlMeasurementExtractor(registry, RefTable()).extract(it, PREVIEW_LOCATOR).observations }.orEmpty()

    private const val PREVIEW_LOCATOR = "preview"
}
