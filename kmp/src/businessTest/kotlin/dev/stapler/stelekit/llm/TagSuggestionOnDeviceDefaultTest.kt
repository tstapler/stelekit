// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.llm

import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.tags.TagSettings
import dev.stapler.stelekit.voice.VoiceSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Epic 8 Story 8.2c: regression-guards [LlmCredentialMigration]'s existing-install
 * default-behavior guard for tag suggestion — an upgrading user who already had the
 * `tags.llm_tier_enabled` key present must not silently start getting on-device tag
 * suggestions just because "Auto" now resolves differently (B2/pitfalls §6.2); a fresh
 * install has no such key and gets Auto (on-device-capable) immediately.
 */
class TagSuggestionOnDeviceDefaultTest {

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

    private fun migration(platformSettings: Settings, llmSettings: LlmSettings) = LlmCredentialMigration(
        voiceSettings = VoiceSettings(platformSettings),
        llmCredentialStore = LlmCredentialStore(NoOpCredentialAccess()),
        platformSettings = platformSettings,
        llmSettings = llmSettings,
        platformOnDeviceProviderId = { null },
    )

    @Test
    fun `selection should DefaultToExplicitlyDisabled when ExistingInstall_TagsLlmTierKeyPresent`() {
        val platformSettings = MapSettings()
        // Simulate a pre-Epic-8 install: the Settings screen's toggle has been rendered/saved
        // at least once, so the key is present regardless of its boolean value.
        TagSettings(platformSettings).setLlmTierEnabled(true)
        val llmSettings = LlmSettings(platformSettings)

        val noticeShown = migration(platformSettings, llmSettings).runIfNeeded()

        assertEquals(LlmProviderRegistry.DISABLED_SENTINEL, llmSettings.getSelectedProviderId(LlmFeature.TAG_SUGGESTION))
        assertTrue(noticeShown, "Existing install should trigger the one-time in-app notice")
    }

    @Test
    fun `selection should DefaultToExplicitlyDisabled when key present and explicitly false`() {
        val platformSettings = MapSettings()
        TagSettings(platformSettings).setLlmTierEnabled(false)
        val llmSettings = LlmSettings(platformSettings)

        migration(platformSettings, llmSettings).runIfNeeded()

        assertEquals(LlmProviderRegistry.DISABLED_SENTINEL, llmSettings.getSelectedProviderId(LlmFeature.TAG_SUGGESTION))
    }

    @Test
    fun `selection should DefaultToAuto when FreshInstall_TagsLlmTierKeyAbsent`() {
        val platformSettings = MapSettings()
        // Fresh install: tags.llm_tier_enabled was never written.
        val llmSettings = LlmSettings(platformSettings)

        val noticeShown = migration(platformSettings, llmSettings).runIfNeeded()

        assertNull(llmSettings.getSelectedProviderId(LlmFeature.TAG_SUGGESTION))
        assertFalse(noticeShown, "Fresh install should not trigger the existing-install notice")
    }

    @Test
    fun `migration should be idempotent — second run does not re-touch a later explicit choice`() {
        val platformSettings = MapSettings()
        TagSettings(platformSettings).setLlmTierEnabled(true)
        val llmSettings = LlmSettings(platformSettings)
        val m = migration(platformSettings, llmSettings)

        val firstNotice = m.runIfNeeded()
        assertTrue(firstNotice)

        // User later explicitly opts into a provider via Settings.
        llmSettings.setSelectedProviderId(LlmFeature.TAG_SUGGESTION, "anthropic")
        val secondNotice = m.runIfNeeded()

        assertFalse(secondNotice, "Second run must not re-fire the notice")
        assertEquals("anthropic", llmSettings.getSelectedProviderId(LlmFeature.TAG_SUGGESTION))
    }
}
