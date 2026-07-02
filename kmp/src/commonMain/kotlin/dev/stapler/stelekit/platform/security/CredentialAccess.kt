// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.security

/**
 * Platform-agnostic interface for reading and writing secrets (git credentials,
 * LLM provider API keys, etc).
 *
 * Implemented by [CredentialStore] (PBKDF2 machine-bound fallback) and
 * [dev.stapler.stelekit.git.VaultCredentialStore] (Argon2id vault-integrated, active when
 * paranoid mode is on).
 *
 * [isAvailable] returns false when the backing store is locked (e.g. vault is locked).
 * Callers should check [isAvailable] before attempting operations that require credentials
 * and surface [dev.stapler.stelekit.git.model.SyncState.CredentialVaultLocked] if false.
 */
interface CredentialAccess {
    fun retrieve(key: String): String?
    fun store(key: String, value: String)
    fun delete(key: String)
    /** Returns false if the backing store is locked and credentials are temporarily unavailable. */
    fun isAvailable(): Boolean = true

    /**
     * Synchronous, durable-before-return write. Default implementation delegates to [store],
     * which is correct as-is for every implementation whose underlying write is already
     * synchronous (JVM file I/O, iOS Keychain, the wasmJs no-op stub, and the vault-backed
     * store). Only [AndroidCredentialStore] overrides this with a genuinely different
     * durability contract — see its kdoc for why.
     *
     * **Migration-only.** Not intended for interactive credential entry call sites, which
     * should continue to use [store]'s fire-and-forget semantics.
     *
     * @return true if the write is confirmed durable, false otherwise. Never throws for a
     *   failed write — callers must check the return value.
     */
    fun storeBlocking(key: String, value: String): Boolean {
        store(key, value)
        return true
    }
}
