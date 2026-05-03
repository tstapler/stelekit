// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

actual class CredentialStore actual constructor() {
    actual fun store(key: String, value: String) {}
    actual fun retrieve(key: String): String? = null
    actual fun delete(key: String) {}
}
