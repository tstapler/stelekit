// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.stats.GraphStatsReport
import dev.stapler.stelekit.stats.LibraryStatsProvider
import dev.stapler.stelekit.stats.MarkdownGraphStatsCollector
import kotlinx.coroutines.withContext

class FileSystemLibraryStatsProvider(private val fileSystem: FileSystem) : LibraryStatsProvider {
    override suspend fun collect(graphPath: String): GraphStatsReport? = withContext(PlatformDispatcher.IO) {
        if (!fileSystem.directoryExists(graphPath)) return@withContext null
        MarkdownGraphStatsCollector().collect(
            graphPath = graphPath,
            pageFiles = fileSystem.listFiles("$graphPath/pages"),
            journalFiles = fileSystem.listFiles("$graphPath/journals"),
            readFile = fileSystem::readFileSuspend,
        )
    }
}
