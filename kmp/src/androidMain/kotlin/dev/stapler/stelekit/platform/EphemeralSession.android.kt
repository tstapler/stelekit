// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

actual fun isEphemeralWebModeAvailable(): Boolean = false

actual fun startEphemeralSession() {
    // No-op: Android always persists to a real SQLite file under app-private storage (or SAF) —
    // there is no in-memory-only mode to switch into. Gated out of the UI via
    // isEphemeralWebModeAvailable().
}

actual fun isCurrentSessionEphemeral(): Boolean = false
