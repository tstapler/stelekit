// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

/**
 * Platform-agnostic interface for reading and writing git credentials.
 *
 * Implemented by [CredentialStore] (PBKDF2 machine-bound fallback) and
 * [VaultCredentialStore] (Argon2id vault-integrated, active when paranoid mode is on).
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
}
