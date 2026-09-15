// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.security

actual class CredentialStore actual constructor() : CredentialAccess {
    actual override fun store(key: String, value: String) {}
    actual override fun retrieve(key: String): String? = null
    actual override fun delete(key: String) {}
}
