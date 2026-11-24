// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.sketch.v2.runtime.settings.LengthFormatSettings
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchDimensionStrings.Units as U
import dev.stapler.stelekit.units.LengthFormat

/** The next display in the cycle a one-tap chip walks: feet and inches, inches, decimal inches, and round again. */
fun LengthFormat.next(): LengthFormat = LengthFormat.entries[(ordinal + 1) % LengthFormat.entries.size]

val LengthFormat.chipLabel: String
    get() = when (this) {
        LengthFormat.FEET_INCHES -> U.CHIP_FEET_INCHES
        LengthFormat.INCHES_MIXED -> U.CHIP_INCHES
        LengthFormat.DECIMAL_INCHES -> U.CHIP_DECIMAL
    }

val LengthFormat.displayName: String
    get() = when (this) {
        LengthFormat.FEET_INCHES -> U.NAME_FEET_INCHES
        LengthFormat.INCHES_MIXED -> U.NAME_INCHES
        LengthFormat.DECIMAL_INCHES -> U.NAME_DECIMAL
    }

/** What the unit chips speak: the current display and the one a tap switches to. */
val LengthFormat.chipDescription: String get() = U.chipDescription(displayName, next().displayName)

/**
 * The length display of the open technical sheet: pills, the size field's prefill, the Technical data panel and the Stats fields all
 * read [format]. It starts from the per-device choice in [store] and writes each change back; without a store (a host that keeps
 * none) the choice lasts for the session.
 */
class LengthUnitState(private val store: LengthFormatSettings?) {
    var format: LengthFormat by mutableStateOf(store?.format ?: LengthFormatSettings.DEFAULT)
        private set

    fun choose(next: LengthFormat) {
        format = next
        store?.choose(next)
    }

    fun cycle() = choose(format.next())
}
