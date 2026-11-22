// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.SyncOrigin

/**
 * The consent trigger this origin carries into `sync()`; null for origins that call their own method (ADR-001
 * decision 1). Manual runs may lift guards the user confirmed; automatic ones never do.
 */
fun SyncOrigin.toTrigger(): SyncTrigger? = when (this) {
    SyncOrigin.UiPress, SyncOrigin.FollowUp -> SyncTrigger.Manual
    SyncOrigin.Scheduled, SyncOrigin.Worker, SyncOrigin.Resume, SyncOrigin.RateLimitRetry -> SyncTrigger.Automatic
    SyncOrigin.Cold, SyncOrigin.Resolve, SyncOrigin.Repair, SyncOrigin.Deepen, SyncOrigin.Setup,
    SyncOrigin.Clone, SyncOrigin.Cli, SyncOrigin.Quiesce -> null
}
