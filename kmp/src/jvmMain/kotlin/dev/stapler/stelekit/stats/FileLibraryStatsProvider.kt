// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.stats

import java.io.File

class FileLibraryStatsProvider : LibraryStatsProvider {
    private val collector = GraphStatsCollector()

    override suspend fun collect(graphPath: String): GraphStatsReport? {
        val dir = File(graphPath)
        if (!dir.isDirectory) return null
        return collector.collect(dir)
    }
}
