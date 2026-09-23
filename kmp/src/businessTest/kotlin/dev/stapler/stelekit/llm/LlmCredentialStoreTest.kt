// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.llm

import dev.stapler.stelekit.platform.security.CredentialAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmCredentialStoreTest {

    /** In-memory [CredentialAccess] fake with a configurable [storeBlockingResult]. */
    private class FakeCredentialAccess(
        private val storeBlockingResult: Boolean = true,
    ) : CredentialAccess {
        private val map = mutableMapOf<String, String>()
        var storeBlockingCallCount = 0
            private set

        override fun retrieve(key: String): String? = map[key]
        override fun store(key: String, value: String) { map[key] = value }
        override fun delete(key: String) { map.remove(key) }
        override fun storeBlocking(key: String, value: String): Boolean {
            storeBlockingCallCount++
            if (storeBlockingResult) map[key] = value
            return storeBlockingResult
        }
    }

    @Test
    fun `apiKey should round-trip per provider id`() {
        val store = LlmCredentialStore(FakeCredentialAccess())

        store.setApiKey("anthropic", "sk-ant-test")

        assertEquals("sk-ant-test", store.getApiKey("anthropic"))
    }

    @Test
    fun `apiKey should be isolated across provider ids`() {
        val store = LlmCredentialStore(FakeCredentialAccess())

        store.setApiKey("anthropic", "sk-ant-test")
        store.setApiKey("openai", "sk-oai-test")
        store.setApiKey("custom.abc-123", "custom-key")

        assertEquals("sk-ant-test", store.getApiKey("anthropic"))
        assertEquals("sk-oai-test", store.getApiKey("openai"))
        assertEquals("custom-key", store.getApiKey("custom.abc-123"))

        store.deleteApiKey("openai")
        assertNull(store.getApiKey("openai"))
        // Deleting one provider's key must not disturb the others.
        assertEquals("sk-ant-test", store.getApiKey("anthropic"))
        assertEquals("custom-key", store.getApiKey("custom.abc-123"))
    }

    @Test
    fun `getApiKey should return null when never configured`() {
        val store = LlmCredentialStore(FakeCredentialAccess())

        assertNull(store.getApiKey("anthropic"))
    }

    @Test
    fun `setApiKeyBlocking should return false when storeBlocking fails`() {
        val fake = FakeCredentialAccess(storeBlockingResult = false)
        val store = LlmCredentialStore(fake)

        val result = store.setApiKeyBlocking("anthropic", "sk-ant-test")

        assertFalse(result)
        assertEquals(1, fake.storeBlockingCallCount)
        // A failed durable write must not silently appear to have succeeded.
        assertNull(store.getApiKey("anthropic"))
    }

    @Test
    fun `setApiKeyBlocking should return true and reflect in getApiKey when storeBlocking succeeds`() {
        val fake = FakeCredentialAccess(storeBlockingResult = true)
        val store = LlmCredentialStore(fake)

        val result = store.setApiKeyBlocking("anthropic", "sk-ant-test")

        assertTrue(result)
        assertEquals(1, fake.storeBlockingCallCount)
        assertEquals("sk-ant-test", store.getApiKey("anthropic"))
    }
}
