// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.settings

import dev.stapler.stelekit.platform.Settings

/**
 * The "Finger drawing" setting and the per-device `StylusSeen` flag (Story 3.1.2, UX-80, D-1).
 *
 * With no explicit choice the effective value is ON until a stylus has been seen on this device, then OFF; an explicit
 * choice is stored and never overridden by [stylusSeen].
 */
class FingerDrawingSettings(private val settings: Settings) {
    val stylusSeen: Boolean get() = settings.getBoolean(STYLUS_SEEN_KEY, false)

    /** The user's own choice, or null while the default applies. */
    val explicitChoice: Boolean? get() = if (settings.containsKey(CHOICE_KEY)) settings.getBoolean(CHOICE_KEY, false) else null

    val effective: Boolean get() = explicitChoice ?: !stylusSeen

    fun choose(on: Boolean) = settings.putBoolean(CHOICE_KEY, on)

    /** Records the first stylus; true only when this call flipped the effective setting from the default ON to OFF. */
    fun markStylusSeen(): Boolean {
        if (stylusSeen) return false
        val flips = explicitChoice == null
        settings.putBoolean(STYLUS_SEEN_KEY, true)
        return flips
    }

    /** The touch magnifier switch: on unless the user turned it off (per device, with the other editor input settings). */
    val loupeOn: Boolean get() = settings.getBoolean(LOUPE_KEY, true)

    fun chooseLoupe(on: Boolean) = settings.putBoolean(LOUPE_KEY, on)

    /** Whether technical sheets draw their dimension annotations (on unless turned off); per device. */
    val dimensionsOn: Boolean get() = settings.getBoolean(DIMENSIONS_KEY, true)

    /** Whether they show on every shape (the default) or only on the selected ones. */
    val dimensionsAll: Boolean get() = settings.getBoolean(DIMENSIONS_ALL_KEY, true)

    fun chooseDimensions(on: Boolean, all: Boolean) {
        settings.putBoolean(DIMENSIONS_KEY, on)
        settings.putBoolean(DIMENSIONS_ALL_KEY, all)
    }

    companion object {
        const val DIMENSIONS_KEY = "sketch.dimensions_on"
        const val DIMENSIONS_ALL_KEY = "sketch.dimensions_all"
        const val LOUPE_KEY = "sketch.touch_loupe"

        const val CHOICE_KEY = "sketch.finger_drawing"
        const val STYLUS_SEEN_KEY = "sketch.stylus_seen"
    }
}
