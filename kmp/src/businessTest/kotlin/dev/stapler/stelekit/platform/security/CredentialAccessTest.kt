// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialAccessTest {

    private class PersistingCredentialAccess : CredentialAccess {
        private val map = mutableMapOf<String, String>()
        override fun retrieve(key: String): String? = map[key]
        override fun store(key: String, value: String) { map[key] = value }
        override fun delete(key: String) { map.remove(key) }
    }

    /** Mirrors the wasmJs no-op stub — `store()` is a no-op, nothing is ever persisted. */
    private class NonPersistingCredentialAccess : CredentialAccess {
        override fun retrieve(key: String): String? = null
        override fun store(key: String, value: String) { /* no-op */ }
        override fun delete(key: String) { /* no-op */ }
    }

    @Test
    fun `storeBlocking default should ReturnTrue When write actually round-trips`() {
        assertTrue(PersistingCredentialAccess().storeBlocking("key", "value"))
    }

    @Test
    fun `storeBlocking default should ReturnFalse When backend does not actually persist`() {
        assertFalse(NonPersistingCredentialAccess().storeBlocking("key", "value"))
    }
}
