// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.platform.Settings
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SyncNotifierTest {

    private class MapSettings : Settings {
        private val store = mutableMapOf<String, String>()
        override fun getBoolean(key: String, defaultValue: Boolean) = store[key]?.toBoolean() ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { store[key] = value.toString() }
        override fun getString(key: String, defaultValue: String) = store[key] ?: defaultValue
        override fun putString(key: String, value: String) { store[key] = value }
        override fun containsKey(key: String) = store.containsKey(key)
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val minute = 60_000L
    private val now = 1_000_000_000L
    private val settings = MapSettings()
    private var permission = NotifyPermissionState.Granted

    private fun notifier() = SyncNotifier(context, settings, clock = { now }, permissionState = { permission })

    private fun staleness(behind: Int, mergedAgoMin: Long = 90) =
        SyncStaleness(lastMergedAtMs = now - mergedAgoMin * minute, behindCount = behind)

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    @Before
    fun clear() {
        manager.cancelAll()
    }

    @Test
    fun `posts once and only again when the behind count changes`() {
        val n = notifier()
        n.onBackgroundTick("g", "Notes", staleness(3), 15)
        n.onBackgroundTick("g", "Notes", staleness(3), 15)
        assertEquals(1, posted().size)
        assertEquals("3 remote commits are waiting — open SteleKit to merge", posted().single().extras.getCharSequence(Notification.EXTRA_TEXT).toString())

        n.onBackgroundTick("g", "Notes", staleness(5), 15)
        assertEquals(1, posted().size, "the same notification id is updated, not duplicated")
        assertTrue(posted().single().extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("5 remote commits"))
    }

    @Test
    fun `clears the notification once nothing is behind`() {
        val n = notifier()
        n.onBackgroundTick("g", "Notes", staleness(2), 15)
        assertEquals(1, posted().size)

        n.onBackgroundTick("g", "Notes", staleness(0), 15)

        assertEquals(0, posted().size)
    }

    @Test
    fun `a recent merge posts nothing yet`() {
        notifier().onBackgroundTick("g", "Notes", staleness(2, mergedAgoMin = 10), 15)
        assertEquals(0, posted().size)
    }

    @Test
    fun `denied permission, or the toggle off, posts nothing`() {
        permission = NotifyPermissionState.PermanentlyDenied
        notifier().onBackgroundTick("g", "Notes", staleness(3), 15)
        assertEquals(0, posted().size)

        permission = NotifyPermissionState.Granted
        settings.putBoolean(SyncNotifier.SETTING_KEY, false)
        notifier().onBackgroundTick("g", "Notes", staleness(3), 15)
        assertEquals(0, posted().size)
    }

    @Test
    fun `the toggle defaults on`() {
        assertTrue(SyncNotifier.isEnabled(MapSettings()))
    }

    @Test
    fun `tapping carries the graph id and name and uses the low-importance git sync channel`() {
        val launcher = android.content.ComponentName(context.packageName, "dev.stapler.stelekit.FakeLauncher")
        shadowOf(context.packageManager).addActivityIfNotPresent(launcher)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            launcher,
            android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply { addCategory(android.content.Intent.CATEGORY_LAUNCHER) },
        )
        notifier().onBackgroundTick("graph-7", "Work notes", staleness(3), 15)

        val notification = posted().single()
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals("graph-7", intent.getStringExtra(SyncNotifier.EXTRA_OPEN_SYNC_STATUS_GRAPH_ID))
        assertEquals("Work notes", intent.getStringExtra(SyncNotifier.EXTRA_OPEN_SYNC_STATUS_GRAPH_NAME))
        assertEquals(GitCloneWorker.NOTIFICATION_CHANNEL_ID, notification.channelId)
        assertNotNull(manager.getNotificationChannel(GitCloneWorker.NOTIFICATION_CHANNEL_ID))
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(GitCloneWorker.NOTIFICATION_CHANNEL_ID).importance)
    }

    @Test
    fun `permission state is not required below API 33`() {
        assertEquals(NotifyPermissionState.NotRequired, currentNotifyPermissionState(context))
    }
}
