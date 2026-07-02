// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.llm

import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.platform.security.CredentialAccess
import dev.stapler.stelekit.voice.VoiceSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The highest-value test in Epic 2 (pitfalls §6.1): regression-guards the one-shot
 * `VoiceSettings` → `LlmCredentialStore` migration (Story 2.3) and its B1 crash-safety fix
 * (ADR-011 Decision step 2 — `setApiKeyBlocking()`/`commit()`, not `setApiKey()`/`apply()`).
 */
class LlmCredentialMigrationTest {

    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = key in map
    }

    /**
     * In-memory [CredentialAccess] fake that records every method invoked (regression guard
     * against a future edit silently reverting the migration write from `storeBlocking()`
     * back to `store()`), and whose `storeBlocking()`/read-back behavior is independently
     * controllable to simulate a crash-between-write-and-flush or a corrupted-write.
     */
    private class FakeCredentialAccess(
        private val storeBlockingResult: Boolean = true,
        private val corruptReadBack: Boolean = false,
    ) : CredentialAccess {
        private val map = mutableMapOf<String, String>()
        val calls = mutableListOf<String>()

        override fun retrieve(key: String): String? {
            calls += "retrieve"
            val stored = map[key] ?: return null
            return if (corruptReadBack) stored + "-CORRUPTED" else stored
        }

        override fun store(key: String, value: String) {
            calls += "store"
            map[key] = value
        }

        override fun delete(key: String) {
            calls += "delete"
            map.remove(key)
        }

        override fun storeBlocking(key: String, value: String): Boolean {
            calls += "storeBlocking"
            if (storeBlockingResult) map[key] = value
            return storeBlockingResult
        }
    }

    private fun migration(
        voiceSettings: VoiceSettings,
        credentialAccess: FakeCredentialAccess,
        platformSettings: Settings,
    ) = LlmCredentialMigration(
        voiceSettings = voiceSettings,
        llmCredentialStore = LlmCredentialStore(credentialAccess),
        platformSettings = platformSettings,
    )

    @Test
    fun `migration should migrate both keys and clear VoiceSettings and set migrated flag`() {
        val voiceSettingsBacking = MapSettings()
        val voiceSettings = VoiceSettings(voiceSettingsBacking)
        voiceSettings.setAnthropicKey("sk-ant-test")
        voiceSettings.setOpenAiKey("sk-oai-test")
        val credentialAccess = FakeCredentialAccess()
        val platformSettings = MapSettings()
        val credentialStore = LlmCredentialStore(credentialAccess)

        LlmCredentialMigration(voiceSettings, credentialStore, platformSettings).runIfNeeded()

        assertEquals("sk-ant-test", credentialStore.getApiKey("anthropic"))
        assertEquals("sk-oai-test", credentialStore.getApiKey("openai"))
        assertNull(voiceSettings.getAnthropicKey())
        assertNull(voiceSettings.getOpenAiKey())
        assertTrue(platformSettings.getBoolean(LlmCredentialMigration.KEY_MIGRATED, false))
    }

    @Test
    fun `migration should be no-op when already migrated`() {
        val voiceSettings = VoiceSettings(MapSettings())
        voiceSettings.setAnthropicKey("sk-ant-test")
        val credentialAccess = FakeCredentialAccess()
        val platformSettings = MapSettings()
        val migration = migration(voiceSettings, credentialAccess, platformSettings)

        migration.runIfNeeded()
        val callsAfterFirstRun = credentialAccess.calls.size

        migration.runIfNeeded()

        assertEquals(callsAfterFirstRun, credentialAccess.calls.size, "Second run must be a true no-op — no further CredentialAccess calls")
        assertEquals("sk-ant-test", LlmCredentialStore(credentialAccess).getApiKey("anthropic"))
    }

    @Test
    fun `migration should migrate only anthropic when openai never configured`() {
        val voiceSettings = VoiceSettings(MapSettings())
        voiceSettings.setAnthropicKey("sk-ant-test")
        // OpenAI never configured — getOpenAiKey() returns null
        val credentialAccess = FakeCredentialAccess()
        val platformSettings = MapSettings()

        migration(voiceSettings, credentialAccess, platformSettings).runIfNeeded()

        assertEquals("sk-ant-test", LlmCredentialStore(credentialAccess).getApiKey("anthropic"))
        assertNull(LlmCredentialStore(credentialAccess).getApiKey("openai"))
        assertNull(voiceSettings.getAnthropicKey())
        assertTrue(platformSettings.getBoolean(LlmCredentialMigration.KEY_MIGRATED, false), "OpenAI absence must not be treated as a migration failure")
    }

    @Test
    fun `migration should call setApiKeyBlocking not setApiKey for the write`() {
        val voiceSettings = VoiceSettings(MapSettings())
        voiceSettings.setAnthropicKey("sk-ant-test")
        val credentialAccess = FakeCredentialAccess()
        val platformSettings = MapSettings()

        migration(voiceSettings, credentialAccess, platformSettings).runIfNeeded()

        assertTrue("storeBlocking" in credentialAccess.calls, "Migration must call storeBlocking() (the durable path)")
        assertFalse("store" in credentialAccess.calls, "Migration must NOT call the fire-and-forget store()")
    }

    @Test
    fun `migration should simulate crash between write and flush — not clear plaintext when storeBlocking returns false`() {
        val voiceSettings = VoiceSettings(MapSettings())
        voiceSettings.setAnthropicKey("sk-ant-test")
        val credentialAccess = FakeCredentialAccess(storeBlockingResult = false)
        val platformSettings = MapSettings()

        migration(voiceSettings, credentialAccess, platformSettings).runIfNeeded()

        assertEquals("sk-ant-test", voiceSettings.getAnthropicKey(), "Plaintext must survive a failed durable write")
        assertFalse(platformSettings.getBoolean(LlmCredentialMigration.KEY_MIGRATED, false), "Migrated flag must not be set on a failed write")
        assertNull(LlmCredentialStore(credentialAccess).getApiKey("anthropic"))
    }

    @Test
    fun `migration should not clear plaintext when storeBlocking returns true but read-back mismatches`() {
        val voiceSettings = VoiceSettings(MapSettings())
        voiceSettings.setAnthropicKey("sk-ant-test")
        val credentialAccess = FakeCredentialAccess(storeBlockingResult = true, corruptReadBack = true)
        val platformSettings = MapSettings()

        migration(voiceSettings, credentialAccess, platformSettings).runIfNeeded()

        assertEquals("sk-ant-test", voiceSettings.getAnthropicKey(), "Plaintext must survive a corrupted-write read-back mismatch")
        assertFalse(platformSettings.getBoolean(LlmCredentialMigration.KEY_MIGRATED, false), "Migrated flag must not be set on a read-back mismatch")
    }

    @Test
    fun `migration should resume and complete when run again after store is fixed`() {
        val voiceSettings = VoiceSettings(MapSettings())
        voiceSettings.setAnthropicKey("sk-ant-test")
        voiceSettings.setOpenAiKey("sk-oai-test")
        val platformSettings = MapSettings()

        // First attempt: durable write fails for everything.
        val failingAccess = FakeCredentialAccess(storeBlockingResult = false)
        migration(voiceSettings, failingAccess, platformSettings).runIfNeeded()
        assertFalse(platformSettings.getBoolean(LlmCredentialMigration.KEY_MIGRATED, false))
        assertEquals("sk-ant-test", voiceSettings.getAnthropicKey())
        assertEquals("sk-oai-test", voiceSettings.getOpenAiKey())

        // Second attempt: store is fixed — migration should complete without needing to
        // distinguish "never started" from "failed midway."
        val workingAccess = FakeCredentialAccess(storeBlockingResult = true)
        migration(voiceSettings, workingAccess, platformSettings).runIfNeeded()

        assertTrue(platformSettings.getBoolean(LlmCredentialMigration.KEY_MIGRATED, false))
        assertNull(voiceSettings.getAnthropicKey())
        assertNull(voiceSettings.getOpenAiKey())
        assertEquals("sk-ant-test", LlmCredentialStore(workingAccess).getApiKey("anthropic"))
        assertEquals("sk-oai-test", LlmCredentialStore(workingAccess).getApiKey("openai"))
    }

    @Test
    fun `migration should clear plaintext when the new store already has the key — crash-after-write-before-clear recovery (MA8)`() {
        val voiceSettings = VoiceSettings(MapSettings())
        voiceSettings.setAnthropicKey("sk-ant-test")
        voiceSettings.setOpenAiKey("sk-oai-test")
        val credentialAccess = FakeCredentialAccess()
        val platformSettings = MapSettings()

        // Simulate a previous run whose durable write to the new store succeeded, but the app
        // crashed before clearPlaintext() ran (and before KEY_MIGRATED was ever set) — so the
        // new store already holds the anthropic key, but VoiceSettings' plaintext copy is
        // still present too.
        LlmCredentialStore(credentialAccess).setApiKeyBlocking("anthropic", "sk-ant-test")

        migration(voiceSettings, credentialAccess, platformSettings).runIfNeeded()

        assertNull(
            voiceSettings.getAnthropicKey(),
            "clearPlaintext() must fire even when the key was already present in the new store from a prior crashed attempt",
        )
        assertNull(voiceSettings.getOpenAiKey(), "openai should migrate normally in the same run")
        assertTrue(platformSettings.getBoolean(LlmCredentialMigration.KEY_MIGRATED, false))
        assertEquals("sk-ant-test", LlmCredentialStore(credentialAccess).getApiKey("anthropic"))
        assertEquals("sk-oai-test", LlmCredentialStore(credentialAccess).getApiKey("openai"))
    }

    @Test
    fun `migration should be a no-op when neither key was ever configured`() {
        val voiceSettings = VoiceSettings(MapSettings())
        val credentialAccess = FakeCredentialAccess()
        val platformSettings = MapSettings()

        migration(voiceSettings, credentialAccess, platformSettings).runIfNeeded()

        assertTrue(platformSettings.getBoolean(LlmCredentialMigration.KEY_MIGRATED, false))
        assertNull(LlmCredentialStore(credentialAccess).getApiKey("anthropic"))
        assertNull(LlmCredentialStore(credentialAccess).getApiKey("openai"))
    }
}
