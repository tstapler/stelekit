// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

/** Why the OS may be holding background work back; replaces the boolean probe. */
enum class RestrictionReason { None, BatterySaver, AppRestricted, StandbyRestricted }

/** Reports whether, and why, the OS is currently restricting background work. */
fun interface BackgroundRestrictionProbe {
    fun restriction(): RestrictionReason

    fun isRestricted(): Boolean = restriction() != RestrictionReason.None
}
