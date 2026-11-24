// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.settings

import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.units.LengthFormat

/** The length display the user last chose on a technical sheet, kept per device; feet and inches until they choose. */
class LengthFormatSettings(private val settings: Settings) {
    val format: LengthFormat
        get() = LengthFormat.entries.firstOrNull { it.name == settings.getString(KEY, "") } ?: DEFAULT

    fun choose(format: LengthFormat) = settings.putString(KEY, format.name)

    companion object {
        const val KEY = "sketch.length_format"
        val DEFAULT = LengthFormat.FEET_INCHES
    }
}
