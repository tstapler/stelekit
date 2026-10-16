// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.stats

import java.io.File
import kotlinx.coroutines.runBlocking

/** JVM adapter retained for the graphStats CLI. */
class GraphStatsCollector {
    fun collect(graphDir: File): GraphStatsReport = runBlocking {
        MarkdownGraphStatsCollector().collect(
            graphPath = graphDir.absolutePath,
            pageFiles = File(graphDir, "pages").listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty(),
            journalFiles = File(graphDir, "journals").listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty(),
            readFile = { path -> runCatching { File(path).readText() }.getOrNull() },
        )
    }
}
