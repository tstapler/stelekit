// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.platform

private fun jsReloadWithEphemeralParam(): Unit =
    js("window.location.href = window.location.pathname + '?mode=ephemeral'")

actual fun isEphemeralWebModeAvailable(): Boolean = true

actual fun startEphemeralSession() {
    jsReloadWithEphemeralParam()
}

actual fun isCurrentSessionEphemeral(): Boolean = EphemeralSettingsMode.active
