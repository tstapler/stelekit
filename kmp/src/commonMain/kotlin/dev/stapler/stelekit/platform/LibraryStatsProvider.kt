// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.stats.GraphStatsReport

interface LibraryStatsProvider {
    suspend fun collect(notesPath: EffectiveNotesPath): GraphStatsReport?
}

object NoOpLibraryStatsProvider : LibraryStatsProvider {
    override suspend fun collect(notesPath: EffectiveNotesPath): GraphStatsReport? = null
}
