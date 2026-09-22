// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.llm

import dev.stapler.stelekit.platform.security.CredentialStore
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * web-credential-persistence (Epic 2.1, Story 2.1.3): proves LLM provider API keys persist on web
 * through the real wasmJs [CredentialStore] — closing requirements.md's success metric #3.
 */
class LlmCredentialStoreWasmJsTest {

    @BeforeTest
    fun setUp() = runTest {
        CredentialStore.resetForTest()
        CredentialStore.preload()
    }

    @AfterTest
    fun tearDown() {
        CredentialStore.resetForTest()
    }

    @Test
    fun `getApiKey returns null for a provider that was never configured`() {
        val store = LlmCredentialStore(CredentialStore())
        assertNull(store.getApiKey("mistral"))
    }

    @Test
    fun `setApiKey for anthropic persisted through flushForTest is visible via getApiKey on a fresh LlmCredentialStore instance`() = runTest {
        LlmCredentialStore(CredentialStore()).setApiKey("anthropic", "sk-ant-realkey")
        CredentialStore.flushForTest()

        val fresh = LlmCredentialStore(CredentialStore())
        assertEquals("sk-ant-realkey", fresh.getApiKey("anthropic"))
    }
}
