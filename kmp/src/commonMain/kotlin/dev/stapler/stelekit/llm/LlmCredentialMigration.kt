// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.llm

import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.tags.TagSettings
import dev.stapler.stelekit.voice.VoiceSettings

/**
 * One-shot migrations that run at app start, modeled on `db/UuidMigration.kt` — idempotent,
 * safe to call every launch via [runIfNeeded]. Bundles three independent one-shot steps that
 * all touch the legacy [VoiceSettings]/[Settings]-backed configuration on the way to the
 * unified [LlmProviderRegistry]/[LlmSettings] model:
 *
 *  1. **Credential migration** (Epic 2 Story 2.3): [VoiceSettings]' plaintext Anthropic/OpenAI
 *     API keys move into [LlmCredentialStore] (ADR-011).
 *  2. **Voice on-device flag migration** (Epic 8 Story 8.1b): a pre-Epic-8 user's explicit
 *     `voice.use_device_llm=true` choice becomes an explicit `VOICE_FORMATTING` provider
 *     selection in [LlmSettings], so upgrading doesn't silently change their effective
 *     provider.
 *  3. **Tag suggestion default guard** (Epic 8 Story 8.2b): an existing install (one where the
 *     `tags.llm_tier_enabled` key has ever been touched) gets `TAG_SUGGESTION` explicitly
 *     disabled rather than defaulting to "Auto" and silently gaining on-device suggestions;
 *     fresh installs default to Auto with no notice needed.
 *
 * **Each step has its own one-shot flag** (not a single shared flag) — critically, this means
 * an install that already completed step 1 in a previous release (its [KEY_MIGRATED] flag is
 * already `true`) still runs steps 2 and 3 the first time it launches Epic 8's code, instead of
 * short-circuiting on the old flag and never running the new steps at all.
 *
 * Crash-safety (adversarial-review B1 fix, applies to step 1): the write to
 * [LlmCredentialStore] uses [LlmCredentialStore.setApiKeyBlocking] — a synchronous,
 * durable-before-return write — NOT [LlmCredentialStore.setApiKey]. `setApiKey`'s default path
 * (`store()` → Android's `apply()`) updates the in-memory `SharedPreferences` cache
 * synchronously but flushes to disk asynchronously; an immediate read-back after a plain
 * `setApiKey()` call would read the in-memory cache and report success even if the disk write
 * hasn't landed — a crash in that window would lose the key while this migration believed it
 * succeeded. Do not "simplify" this back to `setApiKey()` + read-back — the read-back alone was
 * the original, insufficient mitigation `setApiKeyBlocking()` replaces; the read-back here is a
 * secondary corrupted-write check layered on top of the durable write, not a substitute for it.
 */
class LlmCredentialMigration(
    private val voiceSettings: VoiceSettings,
    private val llmCredentialStore: LlmCredentialStore,
    private val platformSettings: Settings,
    private val llmSettings: LlmSettings = LlmSettings(platformSettings),
    /**
     * Injectable seam for "what is this platform's on-device provider id, if any" — defaults
     * to the real [platformOnDeviceLlmProvider] hook. Not called through the registry (a
     * registry may filter providers by other criteria) — this only needs the *identity* of the
     * platform's on-device provider, not its live availability, since we're preserving a
     * previously-made user choice, not re-validating it.
     */
    private val platformOnDeviceProviderId: () -> String? = { platformOnDeviceLlmProvider()?.id },
) {

    /**
     * Runs all three migration steps (each independently idempotent).
     *
     * @return `true` if the tag-suggestion existing-install notice should be shown this app
     *   session (Epic 8 Story 8.2's "On-device tag suggestions are now available — enable in
     *   Settings" one-time notice) — `true` only the one time [migrateTagSuggestionDefaultIfNeeded]
     *   actually determines this is an existing install; `false` on every other call (fresh
     *   install, or the step already ran in a previous session).
     */
    fun runIfNeeded(): Boolean {
        migrateCredentialsIfNeeded()
        migrateVoiceDeviceLlmFlagIfNeeded()
        return migrateTagSuggestionDefaultIfNeeded()
    }

    private fun migrateCredentialsIfNeeded() {
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

    /**
     * Epic 8 Story 8.1b: one-time preservation of an existing user's explicit
     * `voice.use_device_llm=true` choice as an explicit `VOICE_FORMATTING` provider selection.
     * Reads the legacy flag directly via [platformSettings] (not through a
     * [VoiceSettings] accessor — Story 8.1c deletes `getUseDeviceLlm()`/`setUseDeviceLlm()` in
     * this same story, so the raw, now-public [VoiceSettings.KEY_USE_DEVICE_LLM_LEGACY] key is
     * the only remaining way to read it).
     *
     * If the flag was `true` and this platform has a known on-device provider id, that id
     * becomes the explicit `VOICE_FORMATTING` selection. If the flag was `false` (the default)
     * — or this platform has no on-device provider — the selection is left at `null` ("Auto"),
     * matching today's effective default behavior (remote-preferred when a key exists).
     */
    private fun migrateVoiceDeviceLlmFlagIfNeeded() {
        if (platformSettings.getBoolean(KEY_VOICE_DEVICE_LLM_MIGRATED, false)) return

        val hadDeviceLlmEnabled = platformSettings.getBoolean(VoiceSettings.KEY_USE_DEVICE_LLM_LEGACY, false)
        if (hadDeviceLlmEnabled) {
            platformOnDeviceProviderId()?.let { onDeviceId ->
                llmSettings.setSelectedProviderId(LlmFeature.VOICE_FORMATTING, onDeviceId)
            }
        }

        platformSettings.putBoolean(KEY_VOICE_DEVICE_LLM_MIGRATED, true)
    }

    /**
     * Epic 8 Story 8.2b: existing-install default-behavior guard. Uses
     * [Settings.containsKey] (Epic 1 Story 1.7) — not [TagSettings.isLlmTierEnabled]'s typed
     * getter, whose default-`true` return is indistinguishable at the type level from "key
     * present and happens to be `true`" — to tell "existing install" (key was ever written,
     * e.g. by the Settings screen that has always exposed this toggle) from "fresh install"
     * (key never touched).
     *
     * @return `true` if this call just determined the install is an existing one (and
     *   therefore just set the [LlmProviderRegistry.DISABLED_SENTINEL] selection) — the signal
     *   the caller uses to decide whether to show the one-time in-app notice this session.
     *   `false` on a fresh install, or on any call after the first (the step is a no-op then).
     */
    private fun migrateTagSuggestionDefaultIfNeeded(): Boolean {
        if (platformSettings.getBoolean(KEY_TAG_SUGGESTION_DEFAULT_MIGRATED, false)) return false

        val isExistingInstall = platformSettings.containsKey(TagSettings.KEY_LLM_TIER_ENABLED)
        if (isExistingInstall) {
            llmSettings.setSelectedProviderId(LlmFeature.TAG_SUGGESTION, LlmProviderRegistry.DISABLED_SENTINEL)
        }
        // Fresh install: leave TAG_SUGGESTION's selection at null ("Auto") — no prior behavior
        // to preserve, so the new on-device-capable Auto resolution applies immediately.

        platformSettings.putBoolean(KEY_TAG_SUGGESTION_DEFAULT_MIGRATED, true)
        return isExistingInstall
    }

    companion object {
        const val KEY_MIGRATED = "llm.migration.voice_settings_migrated_v1"
        const val KEY_VOICE_DEVICE_LLM_MIGRATED = "llm.migration.voice_device_llm_migrated_v1"
        const val KEY_TAG_SUGGESTION_DEFAULT_MIGRATED = "llm.migration.tag_suggestion_default_migrated_v1"
    }
}
