// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.git.NotificationPermissionAccess
import dev.stapler.stelekit.git.NotifyPermissionState
import dev.stapler.stelekit.git.UpdatesNotificationPreference
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.ui.components.settings.NOTIFY_BLOCKED
import dev.stapler.stelekit.ui.components.settings.NOTIFY_CANCEL
import dev.stapler.stelekit.ui.components.settings.NOTIFY_CONTINUE
import dev.stapler.stelekit.ui.components.settings.NOTIFY_DENIED
import dev.stapler.stelekit.ui.components.settings.NOTIFY_OPEN_SETTINGS
import dev.stapler.stelekit.ui.components.settings.NOTIFY_RATIONALE_TITLE
import dev.stapler.stelekit.ui.components.settings.NOTIFY_SWITCH_LABEL
import dev.stapler.stelekit.ui.components.settings.UpdatesNotificationSection
import dev.stapler.stelekit.ui.components.settings.UpdatesNotificationSettingsState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Device verification of the real system dialog is not possible here; the access seam is faked. */
@RunWith(RobolectricTestRunner::class)
class UpdatesNotificationSectionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
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
        override suspend fun request(): NotifyPermissionState { requests++; current = afterRequest; return current }
        override fun openSystemSettings() { settingsOpened++ }
    }

    private fun show(access: FakeAccess, enabled: Boolean): UpdatesNotificationPreference {
        val prefs = UpdatesNotificationPreference(MapSettings()).also { it.setEnabled(enabled) }
        composeTestRule.setContent { MaterialTheme { UpdatesNotificationSection(UpdatesNotificationSettingsState(access, prefs)) } }
        return prefs
    }

    @Test
    fun grantedAndEnabled_showsOnAndTurnsOffWithoutAnyDialog() {
        val access = FakeAccess(NotifyPermissionState.Granted, NotifyPermissionState.Granted)
        val prefs = show(access, enabled = true)

        composeTestRule.onNodeWithText(NOTIFY_SWITCH_LABEL).assertIsOn()
        composeTestRule.onNodeWithText(NOTIFY_SWITCH_LABEL).performClick()
        assertFalse(prefs.enabled.value)
        composeTestRule.onNodeWithText(NOTIFY_SWITCH_LABEL).assertIsOff()
        assertEquals(0, access.requests)
    }

    @Test
    fun enabledButNotYetAsked_showsOffBecauseNothingCanPost() {
        show(FakeAccess(NotifyPermissionState.NotAsked, NotifyPermissionState.Granted), enabled = true)
        composeTestRule.onNodeWithText(NOTIFY_SWITCH_LABEL).assertIsOff()
    }

    @Test
    fun turningOnShowsTheRationaleFirstThenRequestsAndEnables() {
        val access = FakeAccess(NotifyPermissionState.NotAsked, NotifyPermissionState.Granted)
        val prefs = show(access, enabled = false)

        composeTestRule.onNodeWithText(NOTIFY_SWITCH_LABEL).performClick()
        composeTestRule.onNodeWithText(NOTIFY_RATIONALE_TITLE).assertExists()
        assertEquals(0, access.requests, "no system dialog before the rationale is accepted")

        composeTestRule.onNodeWithText(NOTIFY_CONTINUE).performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, access.requests)
        assertTrue(prefs.enabled.value)
        composeTestRule.onNodeWithText(NOTIFY_SWITCH_LABEL).assertIsOn()
    }

    @Test
    fun decliningTheRationaleChangesNothing() {
        val access = FakeAccess(NotifyPermissionState.NotAsked, NotifyPermissionState.Granted)
        val prefs = show(access, enabled = false)

        composeTestRule.onNodeWithText(NOTIFY_SWITCH_LABEL).performClick()
        composeTestRule.onNodeWithText(NOTIFY_CANCEL).performClick()
        composeTestRule.onNodeWithText(NOTIFY_RATIONALE_TITLE).assertDoesNotExist()
        assertEquals(0, access.requests)
        assertFalse(prefs.enabled.value)
    }

    @Test
    fun aDeniedRequestKeepsTheSwitchOffAndSaysWhy() {
        val access = FakeAccess(NotifyPermissionState.NotAsked, NotifyPermissionState.DeniedOnce)
        val prefs = show(access, enabled = false)

        composeTestRule.onNodeWithText(NOTIFY_SWITCH_LABEL).performClick()
        composeTestRule.onNodeWithText(NOTIFY_CONTINUE).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(NOTIFY_DENIED).assertExists()
        assertFalse(prefs.enabled.value)
    }

    @Test
    fun permanentDenialDisablesTheSwitchAndOffersSystemSettings() {
        val access = FakeAccess(NotifyPermissionState.PermanentlyDenied, NotifyPermissionState.PermanentlyDenied)
        show(access, enabled = true)

        composeTestRule.onNodeWithText(NOTIFY_SWITCH_LABEL).assertIsNotEnabled()
        composeTestRule.onNodeWithText(NOTIFY_BLOCKED).assertExists()
        composeTestRule.onNodeWithText(NOTIFY_OPEN_SETTINGS).performClick()
        assertEquals(1, access.settingsOpened)
        assertEquals(0, access.requests)
    }
}
