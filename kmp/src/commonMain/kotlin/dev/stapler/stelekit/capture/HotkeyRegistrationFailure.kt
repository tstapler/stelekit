// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

/**
 * Best-effort classification of why a global hotkey failed to register — surfaced by
 * `JKeymasterHotkeyListener` (jvmMain) and rendered by `HotkeyConflictNotice` (commonMain).
 * Lives in commonMain (rather than alongside the jvmMain-only listener) purely so the
 * commonMain notice composable can reference it without depending on jvmMain.
 */
enum class HotkeyRegistrationFailure {
    /** Another application already holds the same key combo. */
    AlreadyInUse,

    /** The desktop session doesn't support global hotkeys at all (e.g. plain Wayland). */
    UnsupportedSession,

    /** Registration failed for a reason that doesn't match a known pattern. */
    Unknown,
}
