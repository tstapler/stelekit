// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

import dev.stapler.stelekit.calibration.LensMode
import dev.stapler.stelekit.calibration.MultiPointCalibration
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationTick
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.model.NormalizedPoint
import kotlin.math.abs
import kotlin.math.round

/** One tapped ruler tick and the position (as typed, in the session unit) it sits at on the ruler. */
data class RulerTickEntry(val point: NormalizedPoint, val valueText: String)

/** Ruler ticks being marked for a multi-point calibration, before the user confirms. */
data class RulerTickSession(
    val unit: MeasurementUnit = MeasurementUnit.CENTIMETERS,
    val entries: List<RulerTickEntry> = emptyList(),
    val lensMode: LensMode = LensMode.Auto,
)

/** What confirming a [RulerTickSession] would do right now; [calibration] is null while it cannot be applied. */
data class RulerTickPreview(
    val calibration: Calibration?,
    val usableTickCount: Int,
    val summary: String,
) {
    val canApply: Boolean get() = calibration != null
}

private const val MIN_TICKS = 2
private const val RESIDUAL_WARN_PERCENT = 1.0
private const val TENTH = 10.0
private const val HUNDREDTH = 100.0

/** Adds a tick at [point], pre-filling the next value when the last two ticks have an even step (0, 10 gives 20). */
fun RulerTickSession.withTick(point: NormalizedPoint): RulerTickSession =
    copy(entries = entries + RulerTickEntry(point, suggestedNextValue()))

fun RulerTickSession.withValue(index: Int, text: String): RulerTickSession =
    if (index !in entries.indices) this else copy(entries = entries.mapIndexed { i, e -> if (i == index) e.copy(valueText = text) else e })

fun RulerTickSession.withoutTick(index: Int): RulerTickSession =
    if (index !in entries.indices) this else copy(entries = entries.filterIndexed { i, _ -> i != index })

/** Next value to pre-fill: last + (last - previous) when both parse, else empty. */
fun RulerTickSession.suggestedNextValue(): String {
    if (entries.isEmpty()) return "0"
    if (entries.size < MIN_TICKS) return ""
    val last = entries[entries.size - 1].valueText.trim().toDoubleOrNull() ?: return ""
    val before = entries[entries.size - 2].valueText.trim().toDoubleOrNull() ?: return ""
    val step = last - before
    return if (step.isFinite() && step != 0.0) formatTickNumber(last + step) else ""
}

/** Typed position in metres. Unlike a length, a ruler position may be zero (or negative if the origin is mid-ruler). */
fun parseTickPositionToMeters(text: String, unit: MeasurementUnit): Double? {
    val value = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    val metersPerUnit = parseGridRefLengthToMeters("1", unit) ?: return null
    return value * metersPerUnit
}

/** Ticks whose value parses; entries with blank or invalid values are skipped (and reported by [preview]). */
fun RulerTickSession.usableTicks(): List<CalibrationTick> = entries.mapNotNull { entry ->
    parseTickPositionToMeters(entry.valueText, unit)?.let { CalibrationTick(entry.point.x, entry.point.y, it) }
}

/** Runs the same fit as confirming, so the user sees the scale, worst tick error and chosen k before applying. */
fun RulerTickSession.preview(widthPx: Double, heightPx: Double): RulerTickPreview {
    val ticks = usableTicks()
    val skipped = entries.size - ticks.size
    val calibration = if (ticks.size >= MIN_TICKS) MultiPointCalibration.calibrate(ticks, widthPx, heightPx, lensMode) else null
    val summary = when {
        ticks.size < MIN_TICKS -> "Mark and enter a value for at least 2 ruler ticks (${ticks.size} so far)."
        calibration == null -> "These ticks cannot define a scale: give them different positions, spread along a line."
        else -> describe(calibration, ticks, widthPx, heightPx, skipped)
    }
    return RulerTickPreview(calibration, ticks.size, summary)
}

private fun describe(calibration: Calibration, ticks: List<CalibrationTick>, w: Double, h: Double, skipped: Int): String {
    val lens = calibration.lens
    val k = lens?.k ?: 0.0
    val fit = MultiPointCalibration.fitScale(ticks, k, w, h)
    val residual = lens?.residualPercent ?: 0.0
    val lensText = when {
        k == 0.0 -> "no lens correction"
        lens?.kEstimated == true -> "lens correction k = ${formatDecimals(k, 3)} (estimated)"
        else -> "lens correction k = ${formatDecimals(k, 3)}"
    }
    val worstPx = fit?.let { " (about ${formatDecimals(it.maxResidualPx, 1)} px)" }.orEmpty()
    val warn = if (residual > RESIDUAL_WARN_PERCENT) " Marks disagree: re-tap the off ticks or check the camera was square-on." else ""
    val skippedText = if (skipped > 0) " $skipped tick(s) without a valid value are ignored." else ""
    return "${ticks.size} ticks, $lensText. Worst tick error ${formatDecimals(residual, 1)}% of the ruler$worstPx.$warn$skippedText"
}

internal fun formatTickNumber(value: Double): String {
    val rounded = round(value * HUNDREDTH) / HUNDREDTH
    return if (abs(rounded - round(rounded)) < 1e-9) round(rounded).toLong().toString() else formatDecimals(rounded, 2).trimEnd('0')
}

private fun formatDecimals(value: Double, decimals: Int): String {
    var factor = 1.0
    repeat(decimals) { factor *= TENTH }
    val scaled = round(abs(value) * factor).toLong()
    val whole = scaled / factor.toLong()
    val frac = (scaled % factor.toLong()).toString().padStart(decimals, '0')
    return (if (value < 0.0 && scaled != 0L) "-" else "") + "$whole.$frac"
}
