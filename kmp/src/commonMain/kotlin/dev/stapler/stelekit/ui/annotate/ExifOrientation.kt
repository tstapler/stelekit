// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.annotate

/**
 * How to turn stored sensor pixels upright for an EXIF Orientation tag: mirror horizontally first
 * (when [flipped]), then rotate clockwise by [rotationDegrees]. Annotation points are normalized to
 * the upright image, so export must apply this before baking.
 */
data class ExifOrientation(val rotationDegrees: Int, val flipped: Boolean) {
    val swapsAxes: Boolean get() = rotationDegrees == QUARTER_TURN || rotationDegrees == THREE_QUARTER_TURN

    /** Dimensions after applying this orientation to a [width] x [height] image. */
    fun orientedSize(width: Int, height: Int): Pair<Int, Int> = if (swapsAxes) height to width else width to height

    companion object {
        private const val QUARTER_TURN = 90
        private const val HALF_TURN = 180
        private const val THREE_QUARTER_TURN = 270

        val UPRIGHT = ExifOrientation(0, false)

        /** Maps the TIFF/EXIF Orientation value (1..8); anything else is treated as upright. */
        fun fromTag(tag: Int): ExifOrientation = when (tag) {
            2 -> ExifOrientation(0, true)
            3 -> ExifOrientation(HALF_TURN, false)
            4 -> ExifOrientation(HALF_TURN, true)
            5 -> ExifOrientation(THREE_QUARTER_TURN, true)
            6 -> ExifOrientation(QUARTER_TURN, false)
            7 -> ExifOrientation(QUARTER_TURN, true)
            8 -> ExifOrientation(THREE_QUARTER_TURN, false)
            else -> UPRIGHT
        }
    }
}
