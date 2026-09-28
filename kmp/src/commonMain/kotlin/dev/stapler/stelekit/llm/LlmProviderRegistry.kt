// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

/**
 * Flat registry over N simultaneously-configured [LlmProvider]s. A user may have both an
 * Anthropic API key configured *and* Android on-device (ML Kit) eligible at the same time —
 * these are independently available capabilities, not mutually exclusive alternatives to a
 * single active backend (see ADR-014). Plain, constructor-injected class — test-injectable
 * like `RepositoryFactoryImpl`, not a global singleton.
 */
class LlmProviderRegistry(private val providers: List<LlmProvider>) {

    companion object {
        /**
         * Sentinel value for [LlmSettings.setSelectedProviderId] meaning "this feature is
         * explicitly disabled" — distinct from `null` ("Auto"). Overloads the persisted
         * provider-id string rather than adding a third enum state to [LlmSettings], since
         * every persisted selection is already a plain string (Epic 8 Story 8.2's
         * existing-install default-behavior guard: an upgrading user who had tag suggestion's
         * LLM tier configured under the old Anthropic/OpenAI-only scheme must not silently
         * start getting on-device suggestions just because "Auto" now resolves differently).
         * [find] never resolves this id to a real provider — callers must check for it
         * explicitly before falling back to Auto.
         */
        const val DISABLED_SENTINEL = "__disabled__"
    }

    /** Static list, no suspend — safe to call from composition. */
    fun all(): List<LlmProvider> = providers

    fun find(id: String): LlmProvider? = providers.firstOrNull { it.id == id }

    /**
     * Providers that are usable or soon-usable right now: [LlmProviderAvailability.Available]
     * or [LlmProviderAvailability.Preparing] both count for display purposes. Callers that
     * need strictly-ready-now should additionally filter for
     * `checkAvailability() is LlmProviderAvailability.Available`.
     *
     * Re-evaluated on every call, never a cached snapshot — this is what makes key rotation
     * and dynamic on-device eligibility changes visible without a separate cache-invalidation
     * mechanism.
     */
    suspend fun availableProviders(): List<LlmProvider> =
        providers.filter { it.checkAvailability() !is LlmProviderAvailability.Unavailable }

    /**
     * Same as [availableProviders], additionally excluding providers with
     * `supportsLongFormOutput == false` when [excludeShortFormOnly] is `true` — used by
     * features (e.g. graph-edit synthesis) that cannot use the token-capped on-device
     * providers.
     *
     * [feature] is currently unused by this filtering logic itself — it is part of the
     * public signature per plan.md Story 1.3 so call sites are already feature-scoped when
     * per-feature filtering beyond [excludeShortFormOnly] is introduced later (e.g. Epic 8's
     * feature-specific fallback precedence), without an API-breaking signature change.
     */
    @Suppress("UnusedParameter")
    suspend fun availableForFeature(
        feature: LlmFeature,
        excludeShortFormOnly: Boolean = false,
    ): List<LlmProvider> {
        val available = availableProviders()
        return if (excludeShortFormOnly) {
            available.filter { it.supportsLongFormOutput }
        } else {
            available
        }
    }
}
