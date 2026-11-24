// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.settings

import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.sketch.v2.snap.GridPreset
import dev.stapler.stelekit.sketch.v2.snap.PointSnapKind
import dev.stapler.stelekit.sketch.v2.snap.PointSnapKinds

/**
 * Which snaps the user has switched on and the coarse grid step, kept per device. Every kind and the edge-and-centre
 * alignment are on until turned off (object snap is on by default); the grid itself is a per-session view toggle, off by
 * default, so only its step is stored.
 */
class SnapPreferences(private val settings: Settings) {
    val kinds: PointSnapKinds
        get() = PointSnapKinds(PointSnapKind.entries.filterTo(HashSet()) { settings.getBoolean(kindKey(it), true) })

    fun choose(kind: PointSnapKind, on: Boolean) = settings.putBoolean(kindKey(kind), on)

    /** Snapping a moved selection to the edges and centres of other shapes. */
    val alignment: Boolean get() = settings.getBoolean(ALIGNMENT_KEY, true)

    fun chooseAlignment(on: Boolean) = settings.putBoolean(ALIGNMENT_KEY, on)

    val gridPreset: GridPreset get() = GridPreset.named(settings.getString(GRID_KEY, ""))

    fun chooseGrid(preset: GridPreset) = settings.putString(GRID_KEY, preset.name)

    companion object {
        const val ALIGNMENT_KEY = "sketch.snap.alignment"
        const val GRID_KEY = "sketch.snap.grid_preset"

        fun kindKey(kind: PointSnapKind) = "sketch.snap.kind.${kind.name.lowercase()}"
    }
}
