// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.Settings

/**
 * Posts the single low-importance "N remote commits are waiting" notification after a background tick, and clears
 * it once nothing is behind. What to do is decided by [UpdatesNotificationPolicy]; this class only talks to Android.
 * Reuses the existing low-importance git sync channel. A denied notification permission degrades to the chip only.
 */
class SyncNotifier(
    private val context: Context,
    private val settings: Settings,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val permissionState: () -> NotifyPermissionState = { currentNotifyPermissionState(context) },
) {
    private val logger = Logger("SyncNotifier")

    fun onBackgroundTick(
        graphId: String,
        graphName: String,
        staleness: SyncStaleness,
        intervalMinutes: Int,
        firstTypedError: Boolean = false,
    ) {
        val lastNotified = settings.getString(notifiedKey(graphId), "").toIntOrNull()
        val plan = UpdatesNotificationPolicy.plan(
            staleness = staleness,
            lastNotifiedBehind = lastNotified,
            nowMs = clock(),
            intervalMinutes = intervalMinutes,
            enabled = isEnabled(settings),
            permission = permissionState(),
            firstTypedErrorThisRun = firstTypedError,
        )
        when (plan) {
            NotificationPlan.None -> Unit
            NotificationPlan.Clear -> {
                NotificationManagerCompat.from(context).cancel(notificationId(graphId))
                settings.putString(notifiedKey(graphId), "")
            }
            is NotificationPlan.Post -> {
                if (post(graphId, graphName, plan)) settings.putString(notifiedKey(graphId), plan.behindCount.toString())
            }
        }
    }

    private fun post(graphId: String, graphName: String, plan: NotificationPlan.Post): Boolean {
        ensureChannel()
        val notification = NotificationCompat.Builder(context, GitCloneWorker.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(graphName.ifBlank { "SteleKit" })
            .setContentText(plan.text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(tapIntent(graphId, graphName))
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(notificationId(graphId), notification)
            true
        } catch (e: SecurityException) {
            logger.warn("notification permission denied", e)
            false
        }
    }

    private fun tapIntent(graphId: String, graphName: String): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.putExtra(EXTRA_OPEN_SYNC_STATUS_GRAPH_ID, graphId)
        launch.putExtra(EXTRA_OPEN_SYNC_STATUS_GRAPH_NAME, graphName)
        launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(context, notificationId(graphId), launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(GitCloneWorker.NOTIFICATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(GitCloneWorker.NOTIFICATION_CHANNEL_ID, "Git sync", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notifiedKey(graphId: String) = "git_sync_notified_behind_$graphId"

    companion object {
        const val SETTING_KEY = "git_sync_notify_enabled"
        const val EXTRA_OPEN_SYNC_STATUS_GRAPH_ID = "dev.stapler.stelekit.OPEN_SYNC_STATUS_GRAPH_ID"
        const val EXTRA_OPEN_SYNC_STATUS_GRAPH_NAME = "dev.stapler.stelekit.OPEN_SYNC_STATUS_GRAPH_NAME"

        /** Default on (the plan's Settings toggle); Android 13+ still needs the runtime permission. */
        fun isEnabled(settings: Settings): Boolean = settings.getBoolean(SETTING_KEY, true)

        fun notificationId(graphId: String): Int = SYNC_NOTIFICATION_BASE + (graphId.hashCode() and 0xFFFF)

        private const val SYNC_NOTIFICATION_BASE = 0x5100_0000
    }
}

/** API < 33 needs no permission; otherwise granted, or one of the denied states (the caller tracks permanence). */
fun currentNotifyPermissionState(context: Context, alreadyAsked: Boolean = false): NotifyPermissionState = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> NotifyPermissionState.NotRequired
    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> NotifyPermissionState.Granted
    alreadyAsked -> NotifyPermissionState.DeniedOnce
    else -> NotifyPermissionState.NotAsked
}
