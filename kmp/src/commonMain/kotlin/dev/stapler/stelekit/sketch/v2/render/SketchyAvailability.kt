// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.render

/** The platform families the engine renders on; Sketchy availability is decided per family (Story 2.2.3, ADR-004). */
enum class SketchTarget { Desktop, Android, Web, Ios }

/** Whether Sketchy may draw on a target. [Unavailable] is the pivot of the abort criteria: the sheet draws Clean there. */
sealed interface SketchyStatus {
    data object Available : SketchyStatus

    /** [reason] is for the log and ADR-004, never shown to the user (the user sees [SketchyAvailability.NOTICE]). */
    data class Unavailable(val reason: String) : SketchyStatus
}

/** The evidence behind one target's status, as the measuring tests report it. */
data class SketchyMeasurement(
    /** Share of pixels of architecture-15 in Sketchy that differ from the JVM render, 0..1. */
    val pixelDiffFraction: Double,
    val sketchyBuildNanos: Long,
    val cleanBuildNanos: Long,
)

/**
 * Per-target Sketchy availability (Story 2.2.3). A target whose Sketchy render of architecture-15 misses the pixel
 * tolerance, or whose draw-list build for 500 elements takes more than [BUILD_RATIO_LIMIT] times the Clean build on the
 * same host, is marked [SketchyStatus.Unavailable]; [effective] then resolves every request to Clean there. Stored
 * `roughness` and `seed` are never touched, so a file drawn Sketchy elsewhere still draws Sketchy elsewhere.
 */
class SketchyAvailability(private val entries: Map<SketchTarget, SketchyStatus> = RECORDED) {
    fun status(target: SketchTarget): SketchyStatus = entries[target] ?: SketchyStatus.Available

    /** The style to draw with: Sketchy only where it is available. */
    fun effective(requested: RenderStyle, target: SketchTarget): RenderStyle =
        if (requested == RenderStyle.Sketchy && status(target) is SketchyStatus.Unavailable) RenderStyle.Clean else requested

    /** The notice to show when a Sketchy request was resolved to Clean because the target cannot draw it; null otherwise. */
    fun notice(requested: RenderStyle, target: SketchTarget): String? =
        if (effective(requested, target) != requested) NOTICE else null

    companion object {
        const val NOTICE = "Hand-drawn style is not available on this device yet"

        /** Share of differing pixels still counted as the same render across rasterisers (Task 2.1.3c). */
        const val PIXEL_TOLERANCE_FRACTION = 0.02

        /** The Sketchy draw-list build may take at most this many times the Clean build on the same host. */
        const val BUILD_RATIO_LIMIT = 2.0

        /** What the measuring tests found; ADR-004 "Sketchy availability per target" records the numbers. Every target draws Sketchy. */
        val RECORDED: Map<SketchTarget, SketchyStatus> = emptyMap()

        /** The abort rule as one function, so the tests and the ADR apply the same one. */
        fun assess(measurement: SketchyMeasurement): SketchyStatus {
            val ratio = if (measurement.cleanBuildNanos <= 0L) 0.0 else measurement.sketchyBuildNanos.toDouble() / measurement.cleanBuildNanos
            return when {
                measurement.pixelDiffFraction > PIXEL_TOLERANCE_FRACTION ->
                    SketchyStatus.Unavailable("pixel diff ${measurement.pixelDiffFraction} over $PIXEL_TOLERANCE_FRACTION")
                ratio > BUILD_RATIO_LIMIT -> SketchyStatus.Unavailable("Sketchy build $ratio times Clean, over $BUILD_RATIO_LIMIT")
                else -> SketchyStatus.Available
            }
        }
    }
}
