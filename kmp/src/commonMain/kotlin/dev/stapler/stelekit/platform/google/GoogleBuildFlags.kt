// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.google

/**
 * Build-variant gate for Google Photos import, Drive export and Drive browsing (Epic 8).
 *
 * Release builds never construct a [GoogleIntegration] until the owner's OAuth device pass
 * (plan item 12.5) is recorded and a follow-up PR flips [RELEASE_VISIBLE]. Debug/internal builds
 * expose the surfaces: the Android application sets [internalBuild] from `FLAG_DEBUGGABLE`.
 */
object GoogleBuildFlags {
    /** Flipped to true only after the plan's OAuth device pass (12.5) is recorded. */
    const val RELEASE_VISIBLE: Boolean = false

    @kotlin.concurrent.Volatile
    var internalBuild: Boolean = false

    val visible: Boolean
        get() = RELEASE_VISIBLE || internalBuild
}
