// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.jobs.schedule

/** [Unknown] means empty, unreadable or corrupt: destructive paths do nothing on it (ADR-005). */
sealed interface KnownScopesResult {
    data class Known(val scopes: Set<TaskScope>) : KnownScopesResult
    data object Unknown : KnownScopesResult
}

/** The scopes (graph ids) that currently exist. Implementations never throw. */
fun interface KnownScopes {
    suspend fun read(): KnownScopesResult
}
