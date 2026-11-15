// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationConstraint
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ConstraintFitRecord
import dev.stapler.stelekit.model.FitReport
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.round

/** Glyph shown with the pill text; state is never carried by colour alone (UX-31). */
enum class PillIcon { NONE, CHECK, INFO, WARNING }

data class PillState(
    val text: String,
    val icon: PillIcon,
    /** One-line second text: the model tier and estimated accuracy; null when there is nothing to add. */
    val detail: String? = null,
    /** Action text shown beside the pill ("Add another"), or null. */
    val actionLabel: String? = null,
    /** The existing confidence badge is shown (3 or more distances, not fell back). */
    val showConfidenceBadge: Boolean = false,
)

data class ConstraintRow(
    val id: String,
    val name: String,
    val distanceMeters: Double,
    val residualPct: Double?,
    val flagged: Boolean,
    /** "disagrees by 6% - re-measure or remove?" for a flagged row; null otherwise. */
    val flagText: String?,
)

data class EmptyState(val heading: String, val explanation: String, val primaryAction: String, val secondaryAction: String)

data class CalibrationPresentation(
    val pill: PillState,
    val rows: List<ConstraintRow>,
    val hints: List<String>,
    val emptyState: EmptyState?,
    val knownDistancesEnabled: Boolean,
    /** Why adding known distances is disabled (older calibration basis); null when enabled. */
    val disabledReason: String?,
)

/** Pure presentation model of the calibration pill and the Known distances sheet (Story 3.6, ADR-003). */
object CalibrationStatePresenter {
    const val NOT_CALIBRATED = "Not calibrated - add a known distance"
    const val LEGACY_BASIS_REASON = "Calibrated with an older method. Re-calibrate to add distances."
    const val MIXED_SURFACES_HINT = "These distances may be on different surfaces"
    const val POSITION_HINT = "Add one in a different part of the photo to check tilt"
    const val DIRECTION_HINT = "Add one in a different direction to check stretching"
    const val DUPLICATE_MESSAGE = "This segment is already a known distance"
    const val EMPTY_EXPLANATION =
        "A known distance is a length you know, like a door width or a ruler, between two points in the photo."

    /** A residual this many times the mean, and above [FLAG_MIN_PCT], marks a worst offender. */
    const val FLAG_MEAN_FACTOR = 3.0
    const val FLAG_MIN_PCT = 3.0
    const val POSITION_CLUSTER_FRACTION = 0.25
    const val DIRECTION_CLUSTER_DEGREES = 15.0
    const val DUPLICATE_TOLERANCE_PX = 2.0

    private const val DEGREES_PER_HALF_TURN = 180.0
    private const val TENTH = 10.0

    /**
     * @param report the full last fit report when available; the persisted [ConstraintFitRecord] alone still renders every rung
     * @param gradientTierEnabled the position hint shows only while the GRADIENT tier is on
     * @param perAxisTierEnabled the direction hint shows only while the optional PER_AXIS tier is on
     */
    fun present(
        calibration: Calibration?,
        constraints: List<CalibrationConstraint>,
        fit: ConstraintFitRecord?,
        report: FitReport? = null,
        gradientTierEnabled: Boolean = false,
        perAxisTierEnabled: Boolean = false,
        imageWidthPx: Double = DEFAULT_WIDTH,
        imageHeightPx: Double = DEFAULT_HEIGHT,
    ): CalibrationPresentation {
        val legacy = legacyLabel(calibration)
        val disabled = if (calibration != null && calibration.method != CalibrationMethod.NONE && calibration.isLegacyBasis) {
            LEGACY_BASIS_REASON
        } else {
            null
        }
        val rows = rows(constraints, report)
        val mixed = report?.mixedSurfaces == true
        val hints = buildList {
            if (mixed) add(MIXED_SURFACES_HINT)
            if (gradientTierEnabled && positionsClustered(constraints, imageWidthPx, imageHeightPx)) {
                add(POSITION_HINT)
            } else if (perAxisTierEnabled && directionsClustered(constraints, imageWidthPx, imageHeightPx)) {
                add(DIRECTION_HINT)
            }
        }
        val empty = if (constraints.isEmpty()) {
            EmptyState(legacy?.text ?: "Known distances", EMPTY_EXPLANATION, "Draw a known distance", "Use a card or sheet of paper")
        } else {
            null
        }
        return CalibrationPresentation(
            pill = pill(constraints, fit, report, legacy),
            rows = if (mixed) rows.map { it.copy(flagged = false, flagText = null) } else rows,
            hints = hints,
            emptyState = empty,
            knownDistancesEnabled = disabled == null,
            disabledReason = disabled,
        )
    }

    private fun legacyLabel(calibration: Calibration?): PillState? = when (calibration?.method) {
        null, CalibrationMethod.NONE -> null
        CalibrationMethod.BLE_LASER, CalibrationMethod.MANUAL_REFERENCE, CalibrationMethod.LIDAR_DEPTH ->
            PillState("Scale set", PillIcon.CHECK)
        CalibrationMethod.ARCORE_DEPTH -> PillState("Scale estimated", PillIcon.WARNING)
        CalibrationMethod.EXIF_FOCAL, CalibrationMethod.MONOCULAR_ML -> PillState("Scale uncertain", PillIcon.INFO)
    }

    private fun pill(
        constraints: List<CalibrationConstraint>,
        fit: ConstraintFitRecord?,
        report: FitReport?,
        legacy: PillState?,
    ): PillState {
        val n = constraints.size
        return when {
            n == 0 -> legacy ?: PillState(NOT_CALIBRATED, PillIcon.NONE)
            report?.fallbackReason != null ->
                PillState("Scale from $n ${distances(n)} (basic)", PillIcon.INFO, detail = report.fallbackReason)
            n == 1 -> PillState("Scale from 1 distance (approx.)", PillIcon.INFO, actionLabel = "Add another")
            n == 2 -> PillState(
                "Scale from 2 distances" + (report?.agreementPct?.let { ", agree within ${pct(it)}%" } ?: ""),
                PillIcon.INFO,
            )
            else -> {
                val accuracy = fit?.let { it.looPct ?: it.residualMeanPct } ?: report?.let { it.looPct ?: it.residualMeanPct }
                val tier = (fit?.tier ?: report?.tier)?.name?.lowercase()?.replace('_', ' ')
                PillState(
                    "Calibrated from $n distances" + (accuracy?.let { ", +/-${pct(it)}%" } ?: ""),
                    PillIcon.CHECK,
                    detail = if (accuracy != null && tier != null) "Model: $tier. Estimated accuracy +/-${pct(accuracy)}%" else null,
                    showConfidenceBadge = true,
                )
            }
        }
    }

    private fun distances(n: Int) = if (n == 1) "distance" else "distances"

    private fun rows(constraints: List<CalibrationConstraint>, report: FitReport?): List<ConstraintRow> {
        val byId = report?.perConstraint.orEmpty().associateBy { it.id }
        val mean = report?.residualMeanPct ?: 0.0
        return constraints.mapIndexed { index, c ->
            val residual = byId[c.id]?.residualPct
            val flagged = residual != null && residual > FLAG_MIN_PCT && residual >= FLAG_MEAN_FACTOR * mean
            ConstraintRow(
                id = c.id,
                name = c.label ?: "Distance ${index + 1}",
                distanceMeters = c.distanceMeters,
                residualPct = residual,
                flagged = flagged,
                flagText = if (flagged) "disagrees by ${pct(residual!!)}% - re-measure or remove?" else null,
            )
        }
    }

    private fun mid(c: CalibrationConstraint, w: Double, h: Double) =
        ((c.p1.x + c.p2.x) / 2.0 * w) to ((c.p1.y + c.p2.y) / 2.0 * h)

    /** True when every pair of segment midpoints lies within a quarter of the image diagonal. */
    private fun positionsClustered(constraints: List<CalibrationConstraint>, w: Double, h: Double): Boolean {
        if (constraints.size < 2) return false
        val mids = constraints.map { mid(it, w, h) }
        val limit = POSITION_CLUSTER_FRACTION * hypot(w, h)
        return mids.all { a -> mids.all { b -> hypot(a.first - b.first, a.second - b.second) <= limit } }
    }

    /** True when every pair of segment directions differs by at most 15 degrees (lines, so modulo 180). */
    private fun directionsClustered(constraints: List<CalibrationConstraint>, w: Double, h: Double): Boolean {
        if (constraints.size < 2) return false
        val angles = constraints.map { c ->
            val deg = atan2((c.p2.y - c.p1.y) * h, (c.p2.x - c.p1.x) * w) * DEGREES_PER_HALF_TURN / PI
            ((deg % DEGREES_PER_HALF_TURN) + DEGREES_PER_HALF_TURN) % DEGREES_PER_HALF_TURN
        }
        return angles.all { a ->
            angles.all { b ->
                val d = abs(a - b)
                minOf(d, DEGREES_PER_HALF_TURN - d) <= DIRECTION_CLUSTER_DEGREES
            }
        }
    }

    /** An existing constraint whose endpoints are within [DUPLICATE_TOLERANCE_PX] of the new segment, in either order. */
    fun findDuplicate(
        constraints: List<CalibrationConstraint>,
        p1: dev.stapler.stelekit.model.NormalizedPoint,
        p2: dev.stapler.stelekit.model.NormalizedPoint,
        imageWidthPx: Double,
        imageHeightPx: Double,
    ): CalibrationConstraint? {
        fun near(a: dev.stapler.stelekit.model.NormalizedPoint, b: dev.stapler.stelekit.model.NormalizedPoint) =
            hypot((a.x - b.x) * imageWidthPx, (a.y - b.y) * imageHeightPx) <= DUPLICATE_TOLERANCE_PX
        return constraints.firstOrNull { c -> (near(c.p1, p1) && near(c.p2, p2)) || (near(c.p1, p2) && near(c.p2, p1)) }
    }

    private fun pct(v: Double): String {
        val tenths = round(v * TENTH).toLong()
        return "${tenths / TENTH.toLong()}.${tenths % TENTH.toLong()}"
    }

    private const val DEFAULT_WIDTH = 4000.0
    private const val DEFAULT_HEIGHT = 3000.0
}
