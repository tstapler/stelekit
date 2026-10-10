package dev.stapler.stelekit.platform

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Story 1.7 / B2-fix regression: the Android [PlatformSettings.containsKey] actual must
 * reflect genuine key presence (`SharedPreferences.contains`), not be implemented in terms of
 * the typed getters' default-comparison — which would make it useless for Story 8.2's
 * install-vs-upgrade guard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PlatformSettingsContainsKeyTest {

    private fun settings(): PlatformSettings {
        SteleKitContext.init(ApplicationProvider.getApplicationContext())
        return PlatformSettings()
    }

    @Test
    fun `containsKey_should_ReturnFalse_When_KeyNeverPut`() {
        assertFalse(settings().containsKey("never.put.key"))
    }

    @Test
    fun `containsKey_should_ReturnTrue_When_KeyPutEvenIfValueEqualsTypedDefault`() {
        val s = settings()
        // Put a boolean value equal to getBoolean()'s default (false) — containsKey() must
        // still return true, proving it is NOT implemented via default-comparison.
        s.putBoolean("explicit.false.key", false)
        assertTrue(s.containsKey("explicit.false.key"))
        assertEquals(false, s.getBoolean("explicit.false.key", true))
    }

    @Test
    fun `containsKey_should_ReturnTrue_When_StringKeyPutEvenIfValueEqualsTypedDefault`() {
        val s = settings()
        s.putString("explicit.default.string", "")
        assertTrue(s.containsKey("explicit.default.string"))
    }
}
