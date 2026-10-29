// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.platform.SCHEDULED_SYNC_ENABLED_KEY
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.wiring.FakeSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UT-97 for `scheduled_sync_enabled`: inert when unset or when the settings read throws. */
class ScheduledSyncPreferencesTest {

    private class ThrowingSettings : Settings {
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = error("settings unavailable")
        override fun putBoolean(key: String, value: Boolean) = error("settings unavailable")
        override fun getString(key: String, defaultValue: String): String = error("settings unavailable")
        override fun putString(key: String, value: String) = error("settings unavailable")
        override fun containsKey(key: String): Boolean = error("settings unavailable")
    }

    @Test
    fun flag_should_BeOff_When_Unset() {
        assertFalse(ScheduledSyncPreferences(FakeSettings()).enabled.value)
    }

    @Test
    fun flag_should_BeOff_When_SettingsReadThrows() {
        assertFalse(ScheduledSyncPreferences(ThrowingSettings()).enabled.value)
    }

    @Test
    fun setEnabled_should_PersistAndPublish_When_Called() {
        val settings = FakeSettings()
        val prefs = ScheduledSyncPreferences(settings)
        prefs.setEnabled(true)
        assertTrue(prefs.enabled.value)
        assertTrue(settings.getBoolean(SCHEDULED_SYNC_ENABLED_KEY, false))
    }

    @Test
    fun setEnabled_should_KeepChoiceForSession_When_PersistingThrows() {
        val prefs = ScheduledSyncPreferences(ThrowingSettings())
        prefs.setEnabled(true)
        assertEquals(true, prefs.enabled.value)
    }
}
