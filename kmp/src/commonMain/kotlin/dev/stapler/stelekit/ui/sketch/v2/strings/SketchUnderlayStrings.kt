// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

/** The words of the photo underlay: the Photo chip, its bar, the calibrate length field and the export option (epic E5). */
object SketchUnderlayStrings {
    const val CHIP = "Photo"
    const val CHIP_NAME = "Add a photo underlay"
    const val PICK_TITLE = "Choose a photo or plan"
    const val BAR_REGION = "Photo underlay controls"

    const val NOT_CALIBRATED = "Not calibrated"
    const val LOCK = "Lock"
    const val UNLOCK = "Unlock"
    const val LOCK_NAME = "Lock the photo so it cannot move"
    const val UNLOCK_NAME = "Unlock the photo to move it"
    const val ADJUST = "Move"
    const val ADJUST_NAME = "Move, turn and scale the photo with your fingers"
    const val ADJUST_LOCKED = "Unlock the photo to move it"
    const val DONE = "Done"
    const val CALIBRATE = "Calibrate"
    const val RECALIBRATE = "Recalibrate"
    const val CHECK = "Check"
    const val CHECK_NAME = "Measure a second length to check for perspective"
    const val REMOVE = "Remove"
    const val OPACITY_NAME = "Photo opacity"
    const val OPACITY_STEPS = "Opacity"

    const val CALIBRATE_HINT = "Tap the two ends of a length you know."
    const val CHECK_HINT = "Tap the two ends of a second known length."
    const val DISTANCE_LABEL = "Real distance"
    const val APPLY = "Set scale"
    const val CANCEL = "Cancel"
    const val LEVEL = "Level the photo"
    const val ADJUST_HINT = "Drag to move. Two fingers turn and scale."
    const val ADJUST_HINT_CALIBRATED = "Drag to move. Two fingers turn; scale stays calibrated."

    const val PLACED_TOAST_KEY = "underlay.placed"
    const val PLACED = "Photo placed. Calibrate it to set the scale."
    fun placedReduced(edge: Int) = "Photo reduced to $edge px and placed. Calibrate it to set the scale."

    const val CALIBRATED_TOAST_KEY = "underlay.calibrated"
    const val PICK_FAILED_KEY = "underlay.pickfailed"
    const val PICK_FAILED = "Could not read that photo."
    const val STORE_FAILED_KEY = "underlay.storefailed"
    const val NO_PICKER = "Photos need a file picker, which this device does not offer."
    const val NOT_A_LENGTH = "Type a length such as 3' or 36\"."

    const val INCLUDE_UNDERLAY = "Include photo underlay"
    const val INCLUDE_UNDERLAY_NAME = "Draw the photo underlay in the export"

    /** The calibration note, for example `calibrated: 3' 0" ref`. */
    fun calibrated(length: String) = "calibrated: $length ref"

    fun perspective(percent: Int) = "References differ by $percent%: the photo has perspective, so the scale is an average."

    fun outOfDate(length: String) = "Scale changed since calibrating to $length. Recalibrate."

    fun calibrationFailed(reason: String) = "Could not calibrate: $reason"
}
