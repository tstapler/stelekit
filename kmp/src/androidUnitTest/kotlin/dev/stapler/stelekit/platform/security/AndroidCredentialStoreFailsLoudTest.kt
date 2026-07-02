// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.security

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.GeneralSecurityException
import kotlin.test.assertFailsWith

/**
 * Epic 2 Story 2.4c: locks in [CredentialStore]'s existing fail-loud behavior — if
 * `EncryptedSharedPreferences.create(...)` (or Keystore init generally) throws, the exception
 * must propagate from the `by lazy` `prefs` initializer rather than being caught and silently
 * degraded to plaintext `SharedPreferences`. This is the correct, pre-existing behavior (unlike
 * [dev.stapler.stelekit.platform.PlatformSettings], which does have a plaintext fallback — see
 * ADR-011) and this test guards against a future "helpful" refactor reintroducing one here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidCredentialStoreFailsLoudTest {

    @Test
    fun `credentialStoreInit should propagate exception when keystore init throws not fallback to plaintext`() {
        val store = CredentialStore(prefsFactory = {
            throw GeneralSecurityException("Keystore init failed — simulated")
        })

        // Any operation touching `prefs` (the `by lazy` seam) must propagate, not swallow.
        assertFailsWith<GeneralSecurityException> {
            store.store("llm.anthropic.api_key", "sk-ant-test")
        }
    }

    @Test
    fun `credentialStoreInit should propagate on retrieve too not just store`() {
        val store = CredentialStore(prefsFactory = {
            throw GeneralSecurityException("Keystore init failed — simulated")
        })

        assertFailsWith<GeneralSecurityException> {
            store.retrieve("llm.anthropic.api_key")
        }
    }

    @Test
    fun `credentialStoreInit should propagate for storeBlocking too — migration path must also fail loud`() {
        val store = CredentialStore(prefsFactory = {
            throw GeneralSecurityException("Keystore init failed — simulated")
        })

        assertFailsWith<GeneralSecurityException> {
            store.storeBlocking("llm.anthropic.api_key", "sk-ant-test")
        }
    }
}
