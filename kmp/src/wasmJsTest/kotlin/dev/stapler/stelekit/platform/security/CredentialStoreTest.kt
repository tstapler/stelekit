// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.security

import dev.stapler.stelekit.platform.EphemeralSettingsMode
import kotlinx.browser.localStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * web-credential-persistence (Epic 2.1, Story 2.1.1): proves the real wasmJs [CredentialStore]
 * actual — real `crypto.subtle`/`localStorage`, not a fake — via
 * project_plans/web-credential-persistence/implementation/validation.md's Requirement → Test
 * Mapping table (SM1/SM6 rows).
 */
class CredentialStoreTest {

    @BeforeTest
    fun setUp() {
        CredentialStore.resetForTest()
    }

    @AfterTest
    fun tearDown() {
        CredentialStore.resetForTest()
    }

    @Test
    fun `preload generates a key on first run against empty localStorage and reuses the same key bytes on a second preload call`() = runTest {
        assertNull(localStorage.getItem(CredentialStore.CREDENTIAL_KEY_STORAGE_KEY))

        CredentialStore.preload()
        val firstKey = localStorage.getItem(CredentialStore.CREDENTIAL_KEY_STORAGE_KEY)
        assertTrue(!firstKey.isNullOrBlank())

        CredentialStore.preload()
        val secondKey = localStorage.getItem(CredentialStore.CREDENTIAL_KEY_STORAGE_KEY)
        assertEquals(firstKey, secondKey)
    }

    @Test
    fun `preload decrypts pre-existing ciphertext into decryptedCache so retrieve returns it synchronously`() = runTest {
        CredentialStore.preload()
        CredentialStore().store("llm.anthropic.api_key", "sk-ant-preexisting")
        CredentialStore.flushForTest()

        // Simulate a fresh page load against the same localStorage: clear in-memory state only,
        // then preload() again — no further store()/flushForTest() call in between.
        CredentialStore.resetInMemoryOnlyForTest()
        CredentialStore.preload()

        assertEquals("sk-ant-preexisting", CredentialStore().retrieve("llm.anthropic.api_key"))
    }

    @Test
    fun `store writes to decryptedCache synchronously so an immediate retrieve in the same call stack returns the value`() = runTest {
        val store = CredentialStore()
        store.store("git_https_token_abc123", "ghp_xyz")
        assertEquals("ghp_xyz", store.retrieve("git_https_token_abc123"))
    }

    @Test
    fun `delete on a never-set key is a safe no-op that does not throw`() = runTest {
        CredentialStore.preload()
        CredentialStore().delete("git_ssh_passphrase_neverset")
        CredentialStore.flushForTest()
    }

    @Test
    fun `store's background persistCredential coroutine writes ciphertext to localStorage that never contains the plaintext`() = runTest {
        CredentialStore.preload()
        CredentialStore().store("llm.anthropic.api_key", "sk-ant-abc123")
        CredentialStore.flushForTest()

        val ciphertext = localStorage.getItem(CredentialStore.CIPHERTEXT_KEY_PREFIX + "llm.anthropic.api_key")
        assertTrue(!ciphertext.isNullOrBlank())
        assertFalse(ciphertext.contains("sk-ant-abc123"))
    }

    @Test
    fun `CredentialStore persists across a simulated reload store flush reset preload retrieve still returns the original value`() = runTest {
        CredentialStore.preload()
        CredentialStore().store("git_https_token_abc123", "ghp_xyz")
        CredentialStore.flushForTest()

        // Simulated reload: clear in-memory cache/key but keep real localStorage, then preload again.
        CredentialStore.resetInMemoryOnlyForTest()
        CredentialStore.preload()

        assertEquals("ghp_xyz", CredentialStore().retrieve("git_https_token_abc123"))
    }

    @Test
    fun `preload skips a corrupted credential_enc entry, logs, and still decrypts the remaining valid entries`() = runTest {
        CredentialStore.preload()
        CredentialStore().store("git_ssh_passphrase_valid", "correct-passphrase")
        CredentialStore.flushForTest()
        localStorage.setItem(CredentialStore.CIPHERTEXT_KEY_PREFIX + "git_ssh_passphrase_corrupt", "not-a-valid-payload")

        CredentialStore.resetInMemoryOnlyForTest()
        CredentialStore.preload()

        assertNull(CredentialStore().retrieve("git_ssh_passphrase_corrupt"))
        assertEquals("correct-passphrase", CredentialStore().retrieve("git_ssh_passphrase_valid"))
    }

    @Test
    fun `preload never enumerates or reads credential_enc entries from real localStorage when EphemeralSettingsMode is active`() = runTest {
        CredentialStore.preload()
        CredentialStore().store("git_https_token_realuser", "ghp_realuser_secret")
        CredentialStore.flushForTest()
        CredentialStore.resetInMemoryOnlyForTest()

        try {
            EphemeralSettingsMode.enable()
            CredentialStore.preload()
            assertNull(CredentialStore().retrieve("git_https_token_realuser"))
        } finally {
            EphemeralSettingsMode.resetForTest()
        }
    }

    @Test
    fun `isAvailable returns true when subtleCryptoAvailable reports SubtleCrypto is present`() = runTest {
        assertTrue(CredentialStore().isAvailable())
    }

    @Test
    fun `storeBlocking always returns false even though it still writes the value into the in-memory cache via store`() = runTest {
        CredentialStore.preload()
        val store = CredentialStore()
        val result = store.storeBlocking("llm.openai.api_key", "sk-openai-abc")
        assertFalse(result)
        assertEquals("sk-openai-abc", store.retrieve("llm.openai.api_key"))
    }

    @Test
    fun `store never writes to localStorage when EphemeralSettingsMode is active though retrieve still returns the value from the in-memory-only path`() = runTest {
        try {
            EphemeralSettingsMode.enable()
            CredentialStore.preload()
            val store = CredentialStore()
            store.store("git_https_token_abc123", "ghp_xyz")
            CredentialStore.flushForTest()

            assertNull(localStorage.getItem(CredentialStore.CIPHERTEXT_KEY_PREFIX + "git_https_token_abc123"))
            assertEquals("ghp_xyz", store.retrieve("git_https_token_abc123"))
        } finally {
            EphemeralSettingsMode.resetForTest()
        }
    }

    @Test
    fun `preload does not crash app boot when the stored key is malformed base64, leaving decryptedCache empty and retrieve returning null`() = runTest {
        localStorage.setItem(CredentialStore.CREDENTIAL_KEY_STORAGE_KEY, "not-valid-base64!!!")

        CredentialStore.preload()

        assertNull(CredentialStore().retrieve("anything"))
    }
}
