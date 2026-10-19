// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.stats

import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.platform.LibraryStatsProvider
import java.io.File

class FileLibraryStatsProvider : LibraryStatsProvider {
    private val collector = GraphStatsCollector()

    override suspend fun collect(notesPath: EffectiveNotesPath): GraphStatsReport? {
        val dir = File(notesPath.directory())
        if (!dir.isDirectory) return null
        return collector.collect(dir)
    }
}
