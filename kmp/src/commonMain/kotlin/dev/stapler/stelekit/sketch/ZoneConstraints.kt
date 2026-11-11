package dev.stapler.stelekit.sketch

import arrow.core.Either
import arrow.core.raise.either
import dev.stapler.stelekit.fusion.Constraint
import dev.stapler.stelekit.fusion.DimensionEntry
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.DimensionStatus
import dev.stapler.stelekit.fusion.FusionEngine
import dev.stapler.stelekit.fusion.FusionError
import dev.stapler.stelekit.fusion.FusionInput
import dev.stapler.stelekit.fusion.InvariantVerdict
import dev.stapler.stelekit.fusion.Observation
import dev.stapler.stelekit.fusion.Source
import dev.stapler.stelekit.fusion.SourceKind
import dev.stapler.stelekit.fusion.SourceRef
import dev.stapler.stelekit.fusion.Sum
import dev.stapler.stelekit.units.ParsedLength
import dev.stapler.stelekit.units.parseLength

/** A named zone. [text] is the length the user typed; [isTotal] marks the zone the others must add up to. */
data class Zone(val key: String, val text: String? = null, val isTotal: Boolean = false)

data class ZoneSet(val zones: List<Zone>) {
    /** Zone keys as they read left to right in [direction]; the total is not part of the row. */
    fun displayKeys(direction: ViewDirection): List<String> = direction.labelOrder(zones.filterNot { it.isTotal }.map { it.key })

    companion object {
        val EMPTY = ZoneSet(emptyList())
    }
}

/**
 * [dimensions] is the fusion status per zone (a zone with no typed value can come back
 * `Derivable`); [textErrors] holds zone keys whose text is not a single length; [sumViolated]
 * is true when the typed parts do not add up to the typed total.
 */
data class ZoneReport(
    val dimensions: Map<String, DimensionStatus>,
    val textErrors: Set<String>,
    val sumViolated: Boolean,
)

/** Re-solves a sheet's named zones with the fusion solver; the sketch editor runs it after each committed command. */
object ZoneConstraints {
    private const val SUM_CONSTRAINT_ID = 0

    fun resolve(zones: ZoneSet, direction: ViewDirection = ViewDirection.TOP): Either<FusionError, ZoneReport> = either {
        if (zones.zones.isEmpty()) return@either ZoneReport(emptyMap(), emptySet(), false)
        val textErrors = mutableSetOf<String>()
        val observations = zones.zones.mapNotNull { zone ->
            val text = zone.text?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val measured = parseLength(text).getOrNull() as? ParsedLength.Measured
            if (measured == null) {
                textErrors += zone.key
                return@mapNotNull null
            }
            val ref = SourceRef(SourceKind.SVG_LABEL, zone.key)
            Observation(zone.key, measured.value, measured.roundingPrecision, DimensionKind.LENGTH, Source(SourceKind.SVG_LABEL, ref))
        }
        val total = zones.zones.firstOrNull { it.isTotal }
        val parts = zones.zones.filterNot { it.isTotal }.map { it.key }
        val constraints: List<Constraint> =
            if (total != null && parts.isNotEmpty()) listOf(Sum(SUM_CONSTRAINT_ID, total.key, parts)) else emptyList()
        val registry = DimensionRegistry(dimensions = zones.zones.map { DimensionEntry(it.key, DimensionKind.LENGTH, it.key) })
        val result = FusionEngine.fuse(FusionInput(observations, constraints, zoneOrder = direction.labelOrder(parts)), registry).bind()
        ZoneReport(
            dimensions = result.dimensions,
            textErrors = textErrors,
            sumViolated = result.invariants.any { it.constraintId == SUM_CONSTRAINT_ID && it.verdict == InvariantVerdict.FAILS },
        )
    }
}
