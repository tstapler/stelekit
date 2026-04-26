// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.stats

interface LibraryStatsProvider {
    suspend fun collect(graphPath: String): GraphStatsReport?
}

object NoOpLibraryStatsProvider : LibraryStatsProvider {
    override suspend fun collect(graphPath: String): GraphStatsReport? = null
}
