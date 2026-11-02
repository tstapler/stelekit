// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdatesNotificationToggleTest {

    private class MapSettings : Settings {
        val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = key in map
    }

    private class FakeAccess(var current: NotifyPermissionState, val afterRequest: NotifyPermissionState) : NotificationPermissionAccess {
        var requests = 0
        var settingsOpened = 0
        override fun state() = current
        override suspend fun request(): NotifyPermissionState {
            requests++
            current = afterRequest
            return current
        }
        override fun openSystemSettings() { settingsOpened++ }
    }

    private fun toggle(
        state: NotifyPermissionState,
        afterRequest: NotifyPermissionState = NotifyPermissionState.Granted,
        initiallyEnabled: Boolean = false,
    ): Triple<UpdatesNotificationToggle, FakeAccess, UpdatesNotificationPreference> {
        val prefs = UpdatesNotificationPreference(MapSettings()).also { it.setEnabled(initiallyEnabled) }
        val access = FakeAccess(state, afterRequest)
        return Triple(UpdatesNotificationToggle(access, prefs), access, prefs)
    }

    @Test
    fun `the preference defaults on and persists under the key the notifier reads`() {
        val settings = MapSettings()
        assertTrue(UpdatesNotificationPreference(settings).enabled.value)
        UpdatesNotificationPreference(settings).setEnabled(false)
        assertFalse(settings.getBoolean(UpdatesNotificationPreference.SETTING_KEY, true))
        assertFalse(UpdatesNotificationPreference(settings).enabled.value)
    }

    @Test
    fun `turning on when permission is not required or granted enables without any prompt`() = runTest {
        for (state in listOf(NotifyPermissionState.NotRequired, NotifyPermissionState.Granted)) {
            val (toggle, access, prefs) = toggle(state)
            assertEquals(ToggleOutcome.Enabled, toggle.onToggle(true))
            assertTrue(prefs.enabled.value)
            assertEquals(0, access.requests)
        }
    }

    @Test
    fun `turning on before asking shows the rationale first and requests nothing yet`() = runTest {
        for (state in listOf(NotifyPermissionState.NotAsked, NotifyPermissionState.DeniedOnce)) {
            val (toggle, access, prefs) = toggle(state)
            assertEquals(ToggleOutcome.NeedsRationale, toggle.onToggle(true))
            assertEquals(0, access.requests, "the system dialog only follows an accepted rationale")
            assertFalse(prefs.enabled.value, "the switch stays off until the permission is granted")
        }
    }

    @Test
    fun `accepting the rationale requests once and enables only when granted`() = runTest {
        val (granted, grantedAccess, grantedPrefs) = toggle(NotifyPermissionState.NotAsked, afterRequest = NotifyPermissionState.Granted)
        assertEquals(ToggleOutcome.Enabled, granted.continueAfterRationale())
        assertEquals(1, grantedAccess.requests)
        assertTrue(grantedPrefs.enabled.value)

        val (denied, _, deniedPrefs) = toggle(NotifyPermissionState.NotAsked, afterRequest = NotifyPermissionState.DeniedOnce)
        assertEquals(ToggleOutcome.Denied(NotifyPermissionState.DeniedOnce), denied.continueAfterRationale())
        assertFalse(deniedPrefs.enabled.value)

        val (permanent, _, _) = toggle(NotifyPermissionState.DeniedOnce, afterRequest = NotifyPermissionState.PermanentlyDenied)
        assertEquals(ToggleOutcome.Denied(NotifyPermissionState.PermanentlyDenied), permanent.continueAfterRationale())
    }

    @Test
    fun `permanent denial sends the user to system settings and never requests`() = runTest {
        val (toggle, access, prefs) = toggle(NotifyPermissionState.PermanentlyDenied)
        assertEquals(ToggleOutcome.OpenedSystemSettings, toggle.onToggle(true))
        assertEquals(1, access.settingsOpened)
        assertEquals(0, access.requests)
        assertFalse(prefs.enabled.value)
    }

    @Test
    fun `turning off never touches the permission`() = runTest {
        val (toggle, access, prefs) = toggle(NotifyPermissionState.Granted, initiallyEnabled = true)
        assertEquals(ToggleOutcome.Disabled, toggle.onToggle(false))
        assertFalse(prefs.enabled.value)
        assertEquals(0, access.requests + access.settingsOpened)
    }

    @Test
    fun `resolver maps platform facts onto the permission states`() {
        fun r(required: Boolean, granted: Boolean, asked: Boolean, rationale: Boolean) =
            resolveNotifyPermissionState(required, granted, asked, rationale)
        assertEquals(NotifyPermissionState.NotRequired, r(false, false, false, false))
        assertEquals(NotifyPermissionState.Granted, r(true, true, true, false))
        assertEquals(NotifyPermissionState.NotAsked, r(true, false, false, false))
        assertEquals(NotifyPermissionState.DeniedOnce, r(true, false, true, true))
        assertEquals(NotifyPermissionState.PermanentlyDenied, r(true, false, true, false))
    }

    @Test
    fun `the notifier posts only when the toggle is on and the resolved state can post`() = runTest {
        val prefs = UpdatesNotificationPreference(MapSettings()).also { it.setEnabled(false) }
        val access = FakeAccess(NotifyPermissionState.NotAsked, NotifyPermissionState.Granted)
        val toggle = UpdatesNotificationToggle(access, prefs)
        val staleness = SyncStaleness(lastMergedAtMs = 0L, behindCount = 2)
        fun plan() = UpdatesNotificationPolicy.plan(
            staleness, null, nowMs = 10 * 60 * 60_000L, intervalMinutes = 15, enabled = prefs.enabled.value, permission = access.state(),
        )
        assertEquals(NotificationPlan.None, plan())
        toggle.onToggle(true)
        toggle.continueAfterRationale()
        assertTrue(plan() is NotificationPlan.Post)
    }
}
