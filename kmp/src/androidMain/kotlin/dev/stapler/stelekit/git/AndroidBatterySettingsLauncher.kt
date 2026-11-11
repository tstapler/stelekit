// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.stapler.stelekit.logging.Logger

/**
 * Opens this app's system settings page (`ACTION_APPLICATION_DETAILS_SETTINGS`), where the user can
 * lift battery restrictions. Deliberately not `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. A missing handler
 * or a denied launch is reported as [BatterySettingsResult.Unavailable] and logged, never thrown.
 */
class AndroidBatterySettingsLauncher(private val context: Context) : BatterySettingsLauncher {
    private val logger = Logger("BatterySettingsLauncher")

    override fun open(): BatterySettingsResult {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            BatterySettingsResult.Launched
        } catch (e: ActivityNotFoundException) {
            logger.warn("No handler for the app settings page", e)
            BatterySettingsResult.Unavailable
        } catch (e: SecurityException) {
            logger.warn("Launching the app settings page was refused", e)
            BatterySettingsResult.Unavailable
        }
    }
}
