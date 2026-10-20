// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.stats

import dev.stapler.stelekit.model.EffectiveNotesPath
import java.io.File
import kotlinx.coroutines.runBlocking

/** JVM adapter retained for the graphStats CLI. */
class GraphStatsCollector {
    fun collect(graphDir: File): GraphStatsReport = runBlocking {
        val notesPath = EffectiveNotesPath(graphDir.absolutePath)
        MarkdownGraphStatsCollector().collect(
            graphPath = notesPath.directory(),
            pageFiles = File(notesPath.pagesDir()).listFiles()?.filter { it.isFile }?.map { it.path }.orEmpty(),
            journalFiles = File(notesPath.journalsDir()).listFiles()?.filter { it.isFile }?.map { it.path }.orEmpty(),
            readFile = { path -> runCatching { File(path).readText() }.getOrNull() },
        )
    }
}
