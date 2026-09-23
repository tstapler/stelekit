package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.platform.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Story 3.1.1: [QrTransferSettings.framesPerSecond] rejects a request above ADR-004's ≤3fps WCAG
 * 2.3.1 hard ceiling at construction time — it must never silently clamp to a safe value, since
 * that would hide a misconfiguration from logs/support.
 */
class QrTransferSettingsTest {

    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = map.containsKey(key)
    }

    @Test
    fun qrTransferSettings_should_DefaultEnabledToFalse_When_ConstructedWithoutOverrides() {
        val settings = QrTransferSettings(MapSettings())
        assertFalse(settings.enabled)
    }

    @Test
    fun constructor_should_RejectFramesPerSecond_When_RequestedValueExceeds3_NotClamp() {
        val backing = MapSettings()
        val settings = QrTransferSettings(backing)

        assertFailsWith<IllegalArgumentException> {
            settings.framesPerSecond = 8.0
        }

        // Rejected — not silently clamped to 3 — so the stored value is still the default.
        assertEquals(QrTransferSettings.DEFAULT_FRAMES_PER_SECOND, settings.framesPerSecond)
        assertFalse(backing.containsKey("qr_transfer.frames_per_second"))
    }

    @Test
    fun framesPerSecond_should_Accept_When_RequestedValueIsAtOrBelow3() {
        val settings = QrTransferSettings(MapSettings())
        settings.framesPerSecond = 3.0
        assertEquals(3.0, settings.framesPerSecond)
    }

    @Test
    fun seenEncoderExplainer_should_RoundTrip_When_SetThenGet() {
        val settings = QrTransferSettings(MapSettings())
        assertFalse(settings.seenEncoderExplainer)
        settings.seenEncoderExplainer = true
        assertEquals(true, settings.seenEncoderExplainer)
    }
}
