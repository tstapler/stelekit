// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

/**
 * Secure storage for git credentials (SSH passphrases, HTTPS tokens).
 * Each platform provides an appropriate secure backend.
 */
expect class CredentialStore() : CredentialAccess {
    override fun store(key: String, value: String)
    override fun retrieve(key: String): String?
    override fun delete(key: String)
}
