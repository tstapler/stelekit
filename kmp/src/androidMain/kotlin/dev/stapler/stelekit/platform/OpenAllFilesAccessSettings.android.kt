// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

@Suppress("MagicNumber")
actual fun openAllFilesAccessSettings() {
    if (Build.VERSION.SDK_INT < 30) return
    val context = SteleKitContext.context
    val perAppIntent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(perAppIntent)
        return
    } catch (_: ActivityNotFoundException) {
        // Some OEM settings apps don't resolve the per-app deep link — fall back to the
        // general "All files access" list below, where the user picks SteleKit themselves.
    }
    try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        // No settings screen for this permission exists on this device/OEM build — nothing
        // more we can do; the in-app warning text still tells the user how to find it manually.
    }
}
