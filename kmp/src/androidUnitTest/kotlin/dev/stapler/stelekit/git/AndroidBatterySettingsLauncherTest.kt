// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals

/** UX-17: the launcher opens the app's own settings page, and reports (not throws) when it cannot. */
@RunWith(RobolectricTestRunner::class)
class AndroidBatterySettingsLauncherTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private class Throwing(base: Context, private val error: RuntimeException) : ContextWrapper(base) {
        override fun startActivity(intent: Intent) = throw error
    }

    @Test
    fun open_should_launchAppDetailsSettingsForThisPackage_When_HandlerExists() {
        assertEquals(BatterySettingsResult.Launched, AndroidBatterySettingsLauncher(app).open())
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals("package:${app.packageName}", started.data.toString())
    }

    @Test
    fun open_should_reportUnavailable_When_NoActivityHandlesIt() {
        val launcher = AndroidBatterySettingsLauncher(Throwing(app, ActivityNotFoundException("none")))
        assertEquals(BatterySettingsResult.Unavailable, launcher.open())
    }

    @Test
    fun open_should_reportUnavailable_When_LaunchIsRefused() {
        val launcher = AndroidBatterySettingsLauncher(Throwing(app, SecurityException("denied")))
        assertEquals(BatterySettingsResult.Unavailable, launcher.open())
    }
}
