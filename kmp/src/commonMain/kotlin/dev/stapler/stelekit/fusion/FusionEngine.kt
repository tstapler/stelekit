package dev.stapler.stelekit.fusion

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import dev.stapler.stelekit.units.ArithmeticError
import dev.stapler.stelekit.units.Rational

/**
 * Everything one fusion run reads. [zoneOrder] is the left-to-right zone dimension keys as the
 * table or yaml list them; [svgLabels] are the drawing's zone-width labels with their x position,
 * used only to detect a mirrored view by value.
 */
data class FusionInput(
    val observations: List<Observation>,
    val constraints: List<Constraint> = emptyList(),
    val zoneOrder: List<String> = emptyList(),
    val svgLabels: List<SvgLabel> = emptyList(),
)

sealed interface FusionError {
    data class Solver(val error: SolverError) : FusionError

    data class DerivationFailed(val key: String, val error: DerivationError) : FusionError

    data class Arithmetic(val error: ArithmeticError) : FusionError
}

/** Entry point for the collector's fuse stage: ADR-003 phase B over [input]. */
fun fuseObservations(input: FusionInput, registry: DimensionRegistry): Either<FusionError, FusionResult> =
    FusionEngine.fuse(input, registry)

/**
 * Phase B of ADR-003 and the independence rule. Observations are exact; two readings agree when
 * they differ by no more than their summed rounding precision (counts: exactly equal). The result
 * does not depend on the order observations are supplied in.
 */
object FusionEngine {
    fun fuse(input: FusionInput, registry: DimensionRegistry): Either<FusionError, FusionResult> =
        either { with(Fusion(input, registry)) { execute() } }
}

private val READING_ORDER: Comparator<Observation> =
    compareBy<Observation>({ it.dimension }, { it.source.ref.normalised }, { it.value }, { it.precision })

private class Fusion(private val input: FusionInput, private val registry: DimensionRegistry) {
    private val statuses = LinkedHashMap<String, DimensionStatus>()
    private val consensus = HashMap<String, Quantity>()
    private val readings = HashMap<String, List<Observation>>()

    private fun <R> Raise<FusionError>.ok(result: Either<ArithmeticError, R>): R =
        result.fold({ raise(FusionError.Arithmetic(it)) }, { it })

    fun Raise<FusionError>.execute(): FusionResult {
        val (excludedReadings, kept) = input.observations.partition { it.source.origin.carriesDigestMarker }
        val sorted = kept.sortedWith(READING_ORDER)
        val mirrored = isMirrored(sorted)
        val usable = if (mirrored) sorted.filterNot { it.source.kind == SourceKind.SVG_LABEL && it.dimension in input.zoneOrder } else sorted

        val derivedKeys = registry.dimensions.filter { it.derivation != null }.map { it.key }.toSet()
        val byDimension = usable.groupBy { it.dimension }
        for (dimension in byDimension.keys.sorted().filter { it !in derivedKeys }) {
            record(dimension, independent(byDimension.getValue(dimension)))
        }
        for (entry in registry.derivedInOrder()) {
            val synthetic = derive(entry)
            record(entry.key, independent(byDimension[entry.key].orEmpty() + listOfNotNull(synthetic)))
        }
        solveStructure()
        return FusionResult(
            dimensions = statuses.entries.sortedBy { it.key }.associate { it.key to it.value },
            invariants = input.constraints.sortedBy { it.id }.mapNotNull { invariant(it) },
            warnings = if (mirrored) setOf(FusionWarning.MIRRORED_VIEW) else emptySet(),
            excludedSelf = excludedReadings.size,
        )
    }

    /** One reading per normalised ref (a restated scalar is the same source), in canonical order. */
    private fun independent(list: List<Observation>): List<Observation> =
        list.sortedWith(READING_ORDER).distinctBy { it.source.ref.normalised }

    private fun Raise<FusionError>.record(dimension: String, list: List<Observation>) {
        if (list.isEmpty()) return
        readings[dimension] = list
        val status = classify(list)
        statuses[dimension] = status
        val best = mostPrecise(list)
        when (status) {
            is DimensionStatus.Confirmed, is DimensionStatus.SingleSource -> consensus[dimension] = Quantity(best.value, best.precision)
            is DimensionStatus.Derivable -> consensus[dimension] = Quantity(status.value, best.precision)
            else -> Unit
        }
    }

    private fun mostPrecise(list: List<Observation>): Observation =
        list.minWith(compareBy<Observation>({ it.precision }, { it.value }, { it.source.ref.normalised }))

    private fun Raise<FusionError>.agree(a: Observation, b: Observation): Boolean {
        val gap = ok(a.value.subtract(b.value)).let { if (it.signum < 0) ok(it.negate()) else it }
        val tolerance = if (a.kind == DimensionKind.COUNT) Rational.ZERO else ok(a.precision.add(b.precision))
        return gap <= tolerance
    }

    private fun Raise<FusionError>.classify(list: List<Observation>): DimensionStatus {
        val only = list.singleOrNull()
        if (only != null) {
            return if (only.source.kind == SourceKind.DERIVED) {
                DimensionStatus.Derivable(only.value, byRegistry = true, constraintIds = emptyList())
            } else {
                DimensionStatus.SingleSource(only.value, only.source.ref)
            }
        }
        val pairs = ArrayList<ConflictPair>()
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                if (agree(list[i], list[j])) continue
                val (low, high) = if (list[i].value <= list[j].value) list[i] to list[j] else list[j] to list[i]
                pairs.add(ConflictPair(low, high, ok(high.value.subtract(low.value))))
            }
        }
        return if (pairs.isEmpty()) DimensionStatus.Confirmed(mostPrecise(list).value, list.size) else DimensionStatus.Conflict(pairs)
    }

    /** The registry derivation evaluated over agreed inputs, or null while any input is unknown. */
    private fun Raise<FusionError>.derive(entry: DimensionEntry): Observation? {
        val expr = Derivation.parse(entry.derivation.orEmpty()).fold({ raise(FusionError.DerivationFailed(entry.key, it)) }, { it })
        val quantity = when (val result = Derivation.evaluate(expr) { consensus[it] }) {
            is Either.Right -> result.value
            is Either.Left -> if (result.value is DerivationError.UnknownKey) return null else raise(FusionError.DerivationFailed(entry.key, result.value))
        }
        val ref = SourceRef(SourceKind.DERIVED, entry.key)
        return Observation(entry.key, quantity.value, quantity.precision, entry.kind, Source(SourceKind.DERIVED, ref))
    }

    private fun Raise<FusionError>.isMirrored(sorted: List<Observation>): Boolean {
        val zones = input.zoneOrder
        if (zones.size < 2 || input.svgLabels.size != zones.size) return false
        val zoneReadings = zones.map { zone ->
            sorted.filter { it.dimension == zone && (it.source.kind == SourceKind.TABLE || it.source.kind == SourceKind.YAML) }
                .ifEmpty { return false }
                .let { mostPrecise(it) }
        }
        val labels = input.svgLabels.sortedBy { it.x }
        fun matches(order: List<Observation>) = labels.indices.all { i ->
            val gap = ok(labels[i].value.subtract(order[i].value)).let { if (it.signum < 0) ok(it.negate()) else it }
            gap <= ok(labels[i].precision.add(order[i].precision))
        }
        return matches(zoneReadings.asReversed()) && !matches(zoneReadings)
    }

    private fun Raise<FusionError>.solveStructure() {
        if (input.constraints.isEmpty()) return
        val structuralIds = input.constraints.map { it.id }.toSet()
        var nextId = (input.constraints.maxOf { it.id }) + 1
        val fixed = consensus.entries
            .filter { statuses[it.key].let { s -> s is DimensionStatus.Confirmed || s is DimensionStatus.SingleSource } && it.value.precision.isZero }
            .sortedBy { it.key }
            .map { Fixed(nextId++, it.key, it.value.value) }
        val solution = LinearSolver.solve(input.constraints + fixed).fold({ raise(FusionError.Solver(it)) }, { it })
        val consistent = solution.inconsistencies.isEmpty()
        val named = input.constraints.flatMap { it.dimensions }.distinct().sorted()
        for (dimension in named.filter { it !in statuses }) {
            val determined = solution.dimensions[dimension] as? DimensionResult.Determined
            val unrepresentable = solution.notRepresentable.firstOrNull { it.dimension == dimension }
            statuses[dimension] = when {
                !consistent || determined == null -> DimensionStatus.UnderConstrained
                unrepresentable != null -> DimensionStatus.NotRepresentableValue(unrepresentable.exact, unrepresentable.remainder)
                else -> DimensionStatus.Derivable(determined.value, byRegistry = false, constraintIds = determined.provenance.ids().filter { it in structuralIds })
            }
        }
    }

    private fun Raise<FusionError>.invariant(constraint: Constraint): InvariantResult? {
        val terms: List<Pair<String, Int>>
        var constant = Rational.ZERO
        when (constraint) {
            is Sum -> terms = listOf(constraint.total to 1) + constraint.parts.map { it to -1 }
            is Equal -> terms = listOf(constraint.a to 1, constraint.b to -1)
            is Offset -> {
                terms = listOf(constraint.a to 1, constraint.b to -1)
                constant = constraint.k
            }
            is Fixed -> return null
        }
        val bySource = SourceKind.entries.filter { it != SourceKind.DERIVED }.mapNotNull { kind ->
            val picks = terms.map { (dimension, _) -> readings[dimension]?.firstOrNull { it.source.kind == kind } ?: return@mapNotNull null }
            var residual = ok(Rational.ZERO.subtract(constant))
            var tolerance = Rational.ZERO
            terms.forEachIndexed { i, (_, coefficient) ->
                residual = ok(residual.add(ok(picks[i].value.multiply(Rational(coefficient.toLong())))))
                tolerance = ok(tolerance.add(picks[i].precision))
            }
            val gap = if (residual.signum < 0) ok(residual.negate()) else residual
            kind to if (gap <= tolerance) InvariantVerdict.HOLDS else InvariantVerdict.FAILS
        }.toMap()
        val verdict = when {
            InvariantVerdict.FAILS in bySource.values -> InvariantVerdict.FAILS
            InvariantVerdict.HOLDS in bySource.values -> InvariantVerdict.HOLDS
            else -> InvariantVerdict.UNDETERMINED
        }
        return InvariantResult(constraint.id, verdict, bySource)
    }
}
