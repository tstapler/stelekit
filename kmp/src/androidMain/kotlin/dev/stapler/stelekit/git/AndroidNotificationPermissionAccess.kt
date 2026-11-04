// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import dev.stapler.stelekit.platform.Settings

/**
 * Android 13+ `POST_NOTIFICATIONS` for the updates-waiting notification (UX-89). The Activity owns the
 * `ActivityResultLauncher`, so it passes [requestPermission] and [shouldShowRationale] in; this class only turns
 * them into a [NotifyPermissionState]. "Already asked" is persisted because Android reports a first-time and a
 * permanent denial identically.
 */
class AndroidNotificationPermissionAccess(
    private val context: Context,
    private val settings: Settings,
    private val requestPermission: suspend () -> Boolean,
    private val shouldShowRationale: () -> Boolean,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : NotificationPermissionAccess {

    override fun state(): NotifyPermissionState = resolveNotifyPermissionState(
        runtimePermissionRequired = sdkInt >= Build.VERSION_CODES.TIRAMISU,
        granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        askedBefore = settings.getBoolean(ASKED_KEY, false),
        shouldShowRationale = shouldShowRationale(),
    )

    override suspend fun request(): NotifyPermissionState {
        if (NotifyPermissionFlow.canPost(state())) return state()
        settings.putBoolean(ASKED_KEY, true)
        requestPermission()
        return state()
    }

    override fun openSystemSettings() {
        val intent = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            context.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    companion object {
        const val ASKED_KEY = "git_sync_notify_permission_asked"
    }
}
