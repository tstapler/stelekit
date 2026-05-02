// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

/**
 * Secure storage for git credentials (SSH passphrases, HTTPS tokens).
 * Each platform provides an appropriate secure backend.
 */
expect class CredentialStore() {
    fun store(key: String, value: String)
    fun retrieve(key: String): String?
    fun delete(key: String)
}
