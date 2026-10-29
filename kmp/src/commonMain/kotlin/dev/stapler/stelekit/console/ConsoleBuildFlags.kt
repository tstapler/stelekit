// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.console

/**
 * Build-variant gate for the console write path (`sql!`, later `graph restore-backup`).
 *
 * Release builds keep it off until owner pass 12.1 succeeds on a copy of the real database; the one-line
 * flip PR (U18) sets [RELEASE_WRITES_ENABLED]. Debug/internal builds turn it on: the Android application
 * sets [internalBuild] from `FLAG_DEBUGGABLE` before any UI is composed. Desktop leaves both false.
 */
object ConsoleBuildFlags {
    /** Flipped to true only by the U18 flip PR, citing the recorded pass 12.1. */
    const val RELEASE_WRITES_ENABLED: Boolean = false

    @kotlin.concurrent.Volatile
    var internalBuild: Boolean = false

    val WRITES_ENABLED: Boolean
        get() = RELEASE_WRITES_ENABLED || internalBuild

    /** What `help` and a run attempt say when [WRITES_ENABLED] is false. */
    const val WRITES_DISABLED_REASON: String = "writes not enabled in this build"
}
