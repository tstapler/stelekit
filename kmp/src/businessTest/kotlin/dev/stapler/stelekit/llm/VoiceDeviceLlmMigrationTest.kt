// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.llm

import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.voice.VoiceSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Epic 8 Story 8.1d: regression-guards [LlmCredentialMigration]'s one-time preservation of an
 * existing user's `voice.use_device_llm` choice as an explicit `VOICE_FORMATTING` provider
 * selection (Story 8.1b), so upgrading a pre-Epic-8 install never silently changes its
 * effective voice-formatting provider (pitfalls §6.2).
 */
class VoiceDeviceLlmMigrationTest {

    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = key in map
    }

    private class NoOpCredentialAccess : CredentialAccess {
        override fun retrieve(key: String): String? = null
        override fun store(key: String, value: String) {}
        override fun delete(key: String) {}
        override fun storeBlocking(key: String, value: String): Boolean = true
    }

    private fun migration(
        platformSettings: Settings,
        llmSettings: LlmSettings,
        onDeviceProviderId: () -> String?,
    ) = LlmCredentialMigration(
        voiceSettings = VoiceSettings(platformSettings),
        llmCredentialStore = LlmCredentialStore(NoOpCredentialAccess()),
        platformSettings = platformSettings,
        llmSettings = llmSettings,
        platformOnDeviceProviderId = onDeviceProviderId,
    )

    @Test
    fun `migration should SetOnDeviceSelection when GetUseDeviceLlmWasTrue`() {
        val platformSettings = MapSettings()
        platformSettings.putBoolean(VoiceSettings.KEY_USE_DEVICE_LLM_LEGACY, true)
        val llmSettings = LlmSettings(platformSettings)

        migration(platformSettings, llmSettings) { "android-ondevice" }.runIfNeeded()

        assertEquals("android-ondevice", llmSettings.getSelectedProviderId(LlmFeature.VOICE_FORMATTING))
    }

    @Test
    fun `migration should LeaveSelectionNull when GetUseDeviceLlmWasFalse`() {
        val platformSettings = MapSettings()
        // Legacy flag never explicitly set -> defaults to false, matching pre-Epic-8 behavior.
        val llmSettings = LlmSettings(platformSettings)

        migration(platformSettings, llmSettings) { "android-ondevice" }.runIfNeeded()

        assertNull(llmSettings.getSelectedProviderId(LlmFeature.VOICE_FORMATTING))
    }

    @Test
    fun `migration should LeaveSelectionNull when flag was true but platform has no on-device provider`() {
        val platformSettings = MapSettings()
        platformSettings.putBoolean(VoiceSettings.KEY_USE_DEVICE_LLM_LEGACY, true)
        val llmSettings = LlmSettings(platformSettings)

        // e.g. desktop/JVM: platformOnDeviceLlmProvider() is permanently null.
        migration(platformSettings, llmSettings) { null }.runIfNeeded()

        assertNull(llmSettings.getSelectedProviderId(LlmFeature.VOICE_FORMATTING))
    }

    @Test
    fun `migration should be idempotent — second run does not re-touch the selection`() {
        val platformSettings = MapSettings()
        platformSettings.putBoolean(VoiceSettings.KEY_USE_DEVICE_LLM_LEGACY, true)
        val llmSettings = LlmSettings(platformSettings)
        val m = migration(platformSettings, llmSettings) { "android-ondevice" }

        m.runIfNeeded()
        // A user could subsequently choose a different explicit provider via Settings —
        // a second migration run must not clobber that later choice.
        llmSettings.setSelectedProviderId(LlmFeature.VOICE_FORMATTING, "anthropic")
        m.runIfNeeded()

        assertEquals("anthropic", llmSettings.getSelectedProviderId(LlmFeature.VOICE_FORMATTING))
        assertTrue(platformSettings.getBoolean(LlmCredentialMigration.KEY_VOICE_DEVICE_LLM_MIGRATED, false))
    }
}
