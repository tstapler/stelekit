// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import platform.Foundation.NSUserDefaults

/**
 * iOS implementation of [CredentialStore] using [NSUserDefaults] with a key prefix.
 *
 * TODO: Replace with iOS Keychain (SecItemAdd/SecItemCopyMatching) for production-grade
 * security. NSUserDefaults is not encrypted and should not be used for sensitive tokens
 * in a shipping build. This implementation unblocks compilation; replace before shipping.
 */
actual class CredentialStore actual constructor() {

    private val defaults = NSUserDefaults.standardUserDefaults
    private val keyPrefix = "stelekit_cred_"

    actual fun store(key: String, value: String) {
        defaults.setObject(value, "$keyPrefix$key")
    }

    actual fun retrieve(key: String): String? =
        defaults.stringForKey("$keyPrefix$key")

    actual fun delete(key: String) {
        defaults.removeObjectForKey("$keyPrefix$key")
    }
}
