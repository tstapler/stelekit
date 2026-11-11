// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

/** Whether the system battery settings page opened; [Unavailable] makes the hint row show the manual path instead. */
enum class BatterySettingsResult { Launched, Unavailable }

/**
 * Opens the app's system settings page where the user can lift battery restrictions
 * (Android `ACTION_APPLICATION_DETAILS_SETTINGS`; never `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).
 */
fun interface BatterySettingsLauncher {
    fun open(): BatterySettingsResult
}
