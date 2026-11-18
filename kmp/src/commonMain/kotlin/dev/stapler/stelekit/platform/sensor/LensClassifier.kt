// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.platform.sensor

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Pure lens classification (ADR-004 decision 3). The ratio bands are provisional until the owner-phone fixture
 * (Task 4.1b) tightens them; nothing here is keyed on camera-id order.
 */
object LensClassifier {
    private const val FULL_FRAME_DIAGONAL_MM = 43.2666
    private const val DUPLICATE_FOCAL_TOLERANCE = 0.02
    private const val ULTRA_WIDE_BELOW = 0.75
    private const val TELE_ABOVE = 1.4
    private const val REFERENCE_EQ_MM = 26.0
    private val ZOOM_STOPS = listOf(1.0, 2.0, 5.0, 10.0)

    fun focal35mmEq(focalMm: Double?, sensorWidthMm: Double?, sensorHeightMm: Double?): Double? {
        if (focalMm == null || sensorWidthMm == null || sensorHeightMm == null) return null
        if (focalMm <= 0.0 || sensorWidthMm <= 0.0 || sensorHeightMm <= 0.0) return null
        return focalMm * FULL_FRAME_DIAGONAL_MM / hypot(sensorWidthMm, sensorHeightMm)
    }

    fun classify(infos: List<CameraLensInfo>): List<CameraLens> {
        val back = infos.filter { it.facing == LensFacing.BACK }.sortedBy { it.cameraId }
        val candidates = collapseDuplicates(back.flatMap(::candidatesOf))
        if (candidates.size >= 2) return lensChips(candidates)
        return zoomStops(back)
    }

    private class Candidate(val info: CameraLensInfo, val physicalId: String?, val focalMm: Double, val eq: Double?)

    private fun candidatesOf(info: CameraLensInfo): List<Candidate> {
        val own = info.focalLengthMm?.takeIf { it.isFinite() && it > 0.0 }
        val own35 = focal35mmEq(own, info.sensorWidthMm, info.sensorHeightMm)
        val result = mutableListOf<Candidate>()
        if (own != null) result += Candidate(info, null, own, own35)
        for (pid in info.physicalIds.sorted()) {
            val focal = info.physicalFocalsMm[pid]?.takeIf { it.isFinite() && it > 0.0 }
            // Physical sensor size is unknown here, so a physical lens carries no 35 mm equivalent.
            if (pid in info.boundPhysicalIds && focal != null) result += Candidate(info, pid, focal, null)
        }
        return result
    }

    private fun collapseDuplicates(all: List<Candidate>): List<Candidate> {
        val kept = mutableListOf<Candidate>()
        val sorted = all.sortedWith(compareBy({ it.focalMm }, { it.info.cameraId }, { it.physicalId ?: "" }))
        for (c in sorted) {
            if (kept.none { abs(it.focalMm - c.focalMm) / it.focalMm <= DUPLICATE_FOCAL_TOLERANCE }) kept += c
        }
        return kept
    }

    private fun lensChips(candidates: List<Candidate>): List<CameraLens> {
        val useEq = candidates.all { it.eq != null }
        fun measure(c: Candidate): Double = if (useEq) c.eq!! else c.focalMm
        // A logical camera's own focal is its main sensor; bound physical lenses are the ultra-wide or tele beside it.
        val logicalMain = candidates.firstOrNull { it.physicalId == null && it.info.physicalIds.isNotEmpty() }
        val main = when {
            logicalMain != null -> logicalMain
            useEq -> candidates.minWith(compareBy({ abs(it.eq!! - REFERENCE_EQ_MM) }, { it.focalMm }))
            else -> candidates[(candidates.size - 1) / 2]
        }
        val mainMeasure = measure(main)
        return candidates.map { c ->
            val ratio = measure(c) / mainMeasure
            val kind = when {
                ratio < ULTRA_WIDE_BELOW -> LensKind.ULTRA_WIDE
                ratio > TELE_ABOVE -> LensKind.TELE
                else -> LensKind.WIDE
            }
            val secondary = c.eq?.let { "${it.roundToInt()} mm eq." } ?: "${trim(c.focalMm)} mm"
            CameraLens(
                id = c.info.cameraId + (c.physicalId?.let { "/$it" } ?: ""),
                cameraId = c.info.cameraId,
                physicalId = c.physicalId,
                facing = c.info.facing,
                focalLengthMm = c.focalMm,
                focal35mmEq = c.eq,
                zoomRatio = 1.0,
                labelRatio = ratio,
                isLogicalMulti = c.info.physicalIds.isNotEmpty(),
                kind = kind,
                optical = true,
                label = ratioLabel(ratio),
                secondaryLabel = secondary,
                hint = null,
            )
        }
    }

    private fun zoomStops(back: List<CameraLensInfo>): List<CameraLens> {
        val source = back
            .filter { (it.maxZoomRatio ?: 0.0) > (it.minZoomRatio ?: 1.0) && it.minZoomRatio != null }
            .sortedWith(compareBy({ -((it.maxZoomRatio ?: 0.0) - (it.minZoomRatio ?: 0.0)) }, { it.cameraId }))
            .firstOrNull() ?: return emptyList()
        val min = source.minZoomRatio ?: return emptyList()
        val max = source.maxZoomRatio ?: return emptyList()
        val longestOptical = longestOpticalRatio(source)
        val ratios = (listOf(min) + ZOOM_STOPS.filter { it > min && it <= max }).distinct().sorted()
        return ratios.map { r ->
            val optical = r <= longestOptical + 1e-9
            CameraLens(
                id = "${source.cameraId}@${trim(r)}",
                cameraId = source.cameraId,
                physicalId = null,
                facing = source.facing,
                focalLengthMm = source.focalLengthMm,
                focal35mmEq = null,
                zoomRatio = r,
                labelRatio = r,
                isLogicalMulti = source.physicalIds.isNotEmpty(),
                kind = LensKind.ZOOM_STOP,
                optical = optical,
                label = ratioLabel(r),
                secondaryLabel = null,
                hint = if (optical) null else "Digital",
            )
        }
    }

    /** Zoom ratio at which the longest known physical lens takes over; 1.0 when no physical focal is known. */
    private fun longestOpticalRatio(info: CameraLensInfo): Double {
        val main = info.focalLengthMm?.takeIf { it > 0.0 } ?: return 1.0
        val longest = info.physicalFocalsMm.values.filter { it > 0.0 }.maxOrNull() ?: return 1.0
        return maxOf(1.0, longest / main)
    }

    private fun ratioLabel(ratio: Double): String = "${trim(ratio)}x"

    private fun trim(value: Double): String {
        val tenth = (value * 10).roundToInt() / 10.0
        return if (tenth == floor(tenth)) tenth.toInt().toString() else tenth.toString()
    }
}
