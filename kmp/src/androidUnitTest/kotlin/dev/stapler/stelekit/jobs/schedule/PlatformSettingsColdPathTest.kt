package dev.stapler.stelekit.jobs.schedule

import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.platform.PlatformSettings
import dev.stapler.stelekit.platform.SteleKitContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spike 0.1.2: a `PlatformSettings()` built with no UI init round-trips a value across instances, as
 * a cold WorkManager process would see it.
 *
 * FALLBACK PATH ONLY: Robolectric has no Android Keystore, so `EncryptedSharedPreferences` creation
 * fails and `PlatformSettings` falls back to plain prefs. This does NOT verify the production
 * Keystore-backed path (cold-worker read/write); that is on the owner device checklist (Story 5.1.3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PlatformSettingsColdPathTest {

    @Test
    fun putString_should_RoundTripAcrossInstances_When_OnlyContextInitHasRun_FallbackPathOnly() {
        SteleKitContext.init(ApplicationProvider.getApplicationContext())

        PlatformSettings().putString("task_entry_probe", "v1|1")

        val second = PlatformSettings()
        assertEquals("v1|1", second.getString("task_entry_probe", "missing"))
        assertTrue(second.containsKey("task_entry_probe"))
    }
}
