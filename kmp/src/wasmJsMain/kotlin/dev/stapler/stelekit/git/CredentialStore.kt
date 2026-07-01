// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

// ponytail: localStorage would work here; deferred until git operations are implemented
actual class CredentialStore actual constructor() : dev.stapler.stelekit.git.CredentialAccess {
    actual override fun store(key: String, value: String) {
        println("[CredentialStore] credential storage unavailable on web (key=$key)")
    }
    actual override fun retrieve(key: String): String? = null
    actual override fun delete(key: String) {}
}
