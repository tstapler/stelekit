// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform

import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.stats.GraphStatsReport
import dev.stapler.stelekit.stats.MarkdownGraphStatsCollector
import kotlinx.coroutines.withContext

class FileSystemLibraryStatsProvider(private val fileSystem: FileSystem) : LibraryStatsProvider {
    override suspend fun collect(notesPath: EffectiveNotesPath): GraphStatsReport? = withContext(PlatformDispatcher.IO) {
        if (!fileSystem.directoryExists(notesPath.directory())) return@withContext null
        val pagesDir = notesPath.pagesDir()
        val journalsDir = notesPath.journalsDir()
        MarkdownGraphStatsCollector().collect(
            graphPath = notesPath.directory(),
            pageFiles = fileSystem.listFiles(pagesDir).map { "$pagesDir/$it" },
            journalFiles = fileSystem.listFiles(journalsDir).map { "$journalsDir/$it" },
            readFile = fileSystem::readFileSuspend,
        )
    }
}
