// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

/**
 * Minimal typed credential accessor for LLM provider API keys, consumed by
 * [buildLlmProviderRegistry].
 *
 * Epic 1 stub: this interface exists only so the registry composition root (Story 1.6) has
 * something to compile against without blocking on Epic 2. Epic 2 (ADR-011) relocates/
 * consolidates real credential storage onto the existing `CredentialStore` expect/actual
 * (namespaced keys: `llm.anthropic.api_key`, `llm.openai.api_key`, ...) and is expected to
 * implement this same shape — `getApiKey(providerId): String?` — so this call site does not
 * need to change when Epic 2 lands.
 */
interface LlmCredentialStore {
    /** Returns the stored API key for [providerId], or `null` if none is configured. */
    fun getApiKey(providerId: String): String?
}
