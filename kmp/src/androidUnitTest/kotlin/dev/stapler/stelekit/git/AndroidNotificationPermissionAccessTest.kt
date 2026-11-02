// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.platform.Settings
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Device verification is not possible here: these tests drive the real [AndroidNotificationPermissionAccess] under
 * Robolectric with the Activity's launcher replaced by a lambda, so the system dialog itself is untested.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidNotificationPermissionAccessTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = key in map
    }

    private fun access(
        settings: Settings = MapSettings(),
        rationale: () -> Boolean = { false },
        request: suspend () -> Boolean = { false },
        sdk: Int = 33,
    ) = AndroidNotificationPermissionAccess(app, settings, request, rationale, sdk)

    @Test
    @Config(sdk = [33])
    fun `before the first ask the state is NotAsked`() {
        assertEquals(NotifyPermissionState.NotAsked, access().state())
    }

    @Test
    @Config(sdk = [33])
    fun `granted wins over every other fact`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(NotifyPermissionState.Granted, access(rationale = { true }).state())
    }

    @Test
    @Config(sdk = [33])
    fun `a denial that still allows a rationale is DeniedOnce, and one that does not is permanent`() = runTest {
        val settings = MapSettings()
        assertEquals(NotifyPermissionState.DeniedOnce, access(settings, rationale = { true }, request = { false }).request())
        assertEquals(NotifyPermissionState.PermanentlyDenied, access(settings, rationale = { false }, request = { false }).request())
    }

    @Test
    @Config(sdk = [33])
    fun `request launches the system dialog exactly once and reports the granted result`() = runTest {
        var launches = 0
        val access = access(request = {
            launches++
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            true
        })
        assertEquals(NotifyPermissionState.Granted, access.request())
        assertEquals(NotifyPermissionState.Granted, access.request(), "an already granted permission is not requested again")
        assertEquals(1, launches)
    }

    @Test
    @Config(sdk = [32])
    fun `below API 33 no permission is required and the toggle enables straight away`() {
        val state = access(sdk = 32).state()
        assertEquals(NotifyPermissionState.NotRequired, state)
        assertTrue(NotifyPermissionFlow.canPost(state))
    }
}
