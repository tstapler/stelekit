// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.security

import android.content.SharedPreferences
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B1 adversarial-review-fix regression guard (Epic 2 Story 2.1 Task 2.1d): asserts
 * [AndroidCredentialStore.storeBlocking] uses [SharedPreferences.Editor.commit], not
 * [SharedPreferences.Editor.apply]. `apply()` updates the in-memory cache synchronously but
 * flushes to disk asynchronously — a crash in that window silently loses the write even
 * though an immediate read-back would report success. `commit()` blocks until the write is
 * durable (or definitively failed) before returning, which is the property the one-shot
 * `VoiceSettings` credential migration (Story 2.3) depends on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AndroidCredentialStoreCommitTest {

    /** Records whether commit()/apply() was called, and lets tests control commit()'s result. */
    private class RecordingEditor(
        private val backing: MutableMap<String, String>,
        private val commitResult: Boolean,
        private val calls: MutableList<String>,
    ) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (value != null) pending[key] = value
            return this
        }

        override fun commit(): Boolean {
            calls += "commit"
            if (commitResult) backing.putAll(pending)
            return commitResult
        }

        override fun apply() {
            calls += "apply"
            backing.putAll(pending)
        }

        // Unused by CredentialStore — minimal stub implementations.
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
        override fun putStringSet(key: String, values: MutableSet<String>?) = this
        override fun remove(key: String): SharedPreferences.Editor { backing.remove(key); return this }
        override fun clear(): SharedPreferences.Editor { backing.clear(); return this }
    }

    private class RecordingSharedPreferences(
        private val commitResult: Boolean,
        val calls: MutableList<String> = mutableListOf(),
    ) : SharedPreferences {
        val backing = mutableMapOf<String, String>()

        override fun edit(): SharedPreferences.Editor = RecordingEditor(backing, commitResult, calls)

        override fun getString(key: String, defValue: String?): String? = backing[key] ?: defValue
        override fun getAll(): MutableMap<String, *> = backing
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = defValue
        override fun getLong(key: String, defValue: Long) = defValue
        override fun getFloat(key: String, defValue: Float) = defValue
        override fun getBoolean(key: String, defValue: Boolean) = defValue
        override fun contains(key: String) = backing.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private fun credentialStore(commitResult: Boolean): Pair<CredentialStore, RecordingSharedPreferences> {
        val fake = RecordingSharedPreferences(commitResult)
        val store = CredentialStore(prefsFactory = { fake })
        return store to fake
    }

    @Test
    fun `storeBlocking should call commit not apply on SharedPreferences editor`() {
        val (store, fake) = credentialStore(commitResult = true)

        store.storeBlocking("llm.anthropic.api_key", "sk-ant-test")

        assertTrue("commit" in fake.calls, "storeBlocking() must call commit()")
        assertFalse("apply" in fake.calls, "storeBlocking() must NOT call apply()")
    }

    @Test
    fun `storeBlocking should return commit's boolean result not assume success`() {
        val (store, _) = credentialStore(commitResult = false)

        val result = store.storeBlocking("llm.openai.api_key", "sk-test")

        assertFalse(result, "storeBlocking() must propagate a failed commit() as false")
    }

    @Test
    fun `storeBlocking should return true and persist value when commit succeeds`() {
        val (store, fake) = credentialStore(commitResult = true)

        val result = store.storeBlocking("llm.anthropic.api_key", "sk-ant-test")

        assertTrue(result)
        assertEquals("sk-ant-test", fake.backing["llm.anthropic.api_key"])
    }

    @Test
    fun `store should still use apply not commit — unrelated call sites are unaffected`() {
        val (store, fake) = credentialStore(commitResult = true)

        store.store("git_https_token_1", "ghp_test")

        assertTrue("apply" in fake.calls, "store() must keep using apply() — fire-and-forget for interactive entry")
        assertFalse("commit" in fake.calls, "store() must NOT switch to commit()")
    }
}
