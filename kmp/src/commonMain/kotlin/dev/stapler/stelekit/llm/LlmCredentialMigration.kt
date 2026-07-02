// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.llm

import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.voice.VoiceSettings

/**
 * One-shot migration of [VoiceSettings]' plaintext Anthropic/OpenAI API keys into
 * [LlmCredentialStore] (ADR-011). Modeled on `db/UuidMigration.kt` — idempotent, safe to
 * call on every app start via [runIfNeeded].
 *
 * **No permanent dual-read/plaintext-fallback path.** Once this migration has completed for
 * an install, [VoiceSettings]' Anthropic/OpenAI getters return null and are never
 * meaningfully consulted again for provider credentials.
 *
 * Crash-safety (adversarial-review B1 fix): the write to [LlmCredentialStore] uses
 * [LlmCredentialStore.setApiKeyBlocking] — a synchronous, durable-before-return write — NOT
 * [LlmCredentialStore.setApiKey]. `setApiKey`'s default path (`store()` → Android's `apply()`)
 * updates the in-memory `SharedPreferences` cache synchronously but flushes to disk
 * asynchronously; an immediate read-back after a plain `setApiKey()` call would read the
 * in-memory cache and report success even if the disk write hasn't landed — a crash in that
 * window would lose the key while this migration believed it succeeded. Do not "simplify"
 * this back to `setApiKey()` + read-back — the read-back alone was the original, insufficient
 * mitigation `setApiKeyBlocking()` replaces; the read-back here is a secondary
 * corrupted-write check layered on top of the durable write, not a substitute for it.
 */
class LlmCredentialMigration(
    private val voiceSettings: VoiceSettings,
    private val llmCredentialStore: LlmCredentialStore,
    private val platformSettings: Settings,
) {

    fun runIfNeeded() {
        if (platformSettings.getBoolean(KEY_MIGRATED, false)) return

        val anthropicResolved = migrateKey(
            providerId = "anthropic",
            plaintextValue = voiceSettings.getAnthropicKey(),
            clearPlaintext = voiceSettings::clearAnthropicKey,
        )
        val openAiResolved = migrateKey(
            providerId = "openai",
            plaintextValue = voiceSettings.getOpenAiKey(),
            clearPlaintext = voiceSettings::clearOpenAiKey,
        )

        // Only mark done once BOTH keys are in a resolved state (migrated, never-configured,
        // or already-migrated) — a crash between processing the two keys must not mark the
        // migration done prematurely and permanently strand the second key unmigrated.
        if (anthropicResolved && openAiResolved) {
            platformSettings.putBoolean(KEY_MIGRATED, true)
        }
    }

    /**
     * @return true if [providerId]'s key is now in a safely-resolved state (nothing to do,
     *   already migrated, or successfully migrated this call) — false if a migration was
     *   attempted and failed, in which case the plaintext source is left untouched so the
     *   next [runIfNeeded] call retries it.
     */
    private fun migrateKey(
        providerId: String,
        plaintextValue: String?,
        clearPlaintext: () -> Unit,
    ): Boolean {
        if (plaintextValue == null) return true // nothing to migrate
        if (llmCredentialStore.getApiKey(providerId) != null) return true // already migrated

        val wroteDurably = llmCredentialStore.setApiKeyBlocking(providerId, plaintextValue)
        if (!wroteDurably) return false

        // Defense-in-depth: verify the durable write actually round-trips correctly before
        // touching the plaintext source (corrupted-write edge case).
        val readBack = llmCredentialStore.getApiKey(providerId)
        if (readBack != plaintextValue) return false

        clearPlaintext()
        return true
    }

    companion object {
        const val KEY_MIGRATED = "llm.migration.voice_settings_migrated_v1"
    }
}
