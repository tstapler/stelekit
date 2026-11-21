// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.SyncOrigin

/** The operation that currently holds a repository gate; written only by the holder (ADR-001 decision 6). */
data class SyncRunInfo(
    val origin: SyncOrigin,
    val startedAtMs: Long,
    val graphId: String,
    val followUpQueued: Boolean = false,
)
