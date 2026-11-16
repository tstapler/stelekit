// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.settings

import dev.stapler.stelekit.platform.Settings

/** "Edge pan side: Both / Left / Right / Off" (UX-121, D-18). */
enum class EdgePanSide { Off, Left, Right, Both }

/**
 * The persisted edge-pan choice and the `ExclusionFailed` marker (Task 3.1.2g). The marker is set when the system took
 * strip drags away twice in one session, so the next session starts Off through `EdgePanPolicy.defaultSide`; choosing a
 * side explicitly clears it.
 */
class EdgePanSettings(private val settings: Settings) {
    /** The user's choice, or null while the default applies. */
    val choice: EdgePanSide?
        get() = EdgePanSide.entries.firstOrNull { it.name == settings.getString(SIDE_KEY, "") }

    val exclusionFailed: Boolean get() = settings.getBoolean(FAILED_KEY, false)

    fun choose(side: EdgePanSide) {
        settings.putString(SIDE_KEY, side.name)
        settings.putBoolean(FAILED_KEY, false)
    }

    fun markExclusionFailed() = settings.putBoolean(FAILED_KEY, true)

    companion object {
        const val SIDE_KEY = "sketch.edge_pan_side"
        const val FAILED_KEY = "sketch.edge_pan_exclusion_failed"
    }
}
