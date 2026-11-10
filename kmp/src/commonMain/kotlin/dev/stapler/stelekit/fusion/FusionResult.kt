package dev.stapler.stelekit.fusion

import dev.stapler.stelekit.units.Rational

/** Two independent sources that disagree; [difference] is `second - first`, exact and non-negative. */
data class ConflictPair(val first: Observation, val second: Observation, val difference: Rational)

/** The ADR-003 output classes for one dimension. */
sealed interface DimensionStatus {
    /** At least two independent sources agree within tolerance; [value] is the most precise reading. */
    data class Confirmed(val value: Rational, val sources: Int) : DimensionStatus

    data class SingleSource(val value: Rational, val source: SourceRef) : DimensionStatus

    data class Conflict(val pairs: List<ConflictPair>) : DimensionStatus

    /** No reading, but the structure or a registry derivation forces this value. */
    data class Derivable(val value: Rational, val byRegistry: Boolean, val constraintIds: List<Int>) : DimensionStatus

    /** Derivable, but not a whole number of sixteenths; reported exactly, never rounded. */
    data class NotRepresentableValue(val exact: Rational, val remainder: Rational) : DimensionStatus

    /** Named by a constraint but neither read nor forced. */
    data object UnderConstrained : DimensionStatus
}

enum class FusionWarning { MIRRORED_VIEW }

enum class InvariantVerdict { HOLDS, FAILS, UNDETERMINED }

/**
 * A structural constraint checked against what each source kind says on its own: a table whose
 * zones do not add up to its total fails even if another source disagrees with the table.
 * [bySource] holds a verdict only for source kinds that read every dimension the constraint names.
 */
data class InvariantResult(
    val constraintId: Int,
    val verdict: InvariantVerdict,
    val bySource: Map<SourceKind, InvariantVerdict>,
)

data class FusionResult(
    val dimensions: Map<String, DimensionStatus>,
    val invariants: List<InvariantResult>,
    val warnings: Set<FusionWarning>,
    /** Observations dropped because their source carries `stelekit-digest::`. */
    val excludedSelf: Int,
)
