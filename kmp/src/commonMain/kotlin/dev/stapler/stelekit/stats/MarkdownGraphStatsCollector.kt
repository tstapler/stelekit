// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.stats

import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** Scans pages/ and journals/ directly from markdown without a database. */
class MarkdownGraphStatsCollector {
    private companion object {
        const val FILES_PER_CHECKPOINT = 32
        const val ITEMS_PER_CHECKPOINT = 256
    }

    private val wikiLinkRegex = Regex("""\[\[([^\]|]+?)(?:\|[^\]]*)?\]\]""")
    private val hashtagRegex = Regex("""#(?:\[\[([^\]]+)\]\]|(\w[\w\s-]*))""")
    private val blockLineRegex = Regex("""^\s*-\s""")
    private val journalDateRegex = Regex("""^(\d{4})[-_](\d{2})[-_](\d{2})""")

    private data class FileScan(
        val name: String,
        val blocks: Int,
        val blocksWithLinks: Int,
        val outgoing: List<String>,
        val hashtags: Int,
    )

    private data class JournalEntry(val scan: FileScan, val date: String?)

    suspend fun collect(
        graphPath: String,
        pageFiles: List<String>,
        journalFiles: List<String>,
        readFile: suspend (String) -> String?,
    ): GraphStatsReport {
        val context = currentCoroutineContext()
        context.ensureActive()
        val pages = mutableListOf<FileScan>()
        for ((index, name) in pageFiles.withIndex()) {
            if (index % FILES_PER_CHECKPOINT == 0) yield()
            if (!name.endsWith(".md")) continue
            pages += scan(readFile("$graphPath/pages/$name").orEmpty(), pageNameFromFile(name))
        }
        val journals = mutableListOf<JournalEntry>()
        for ((index, name) in journalFiles.withIndex()) {
            if (index % FILES_PER_CHECKPOINT == 0) yield()
            if (!name.endsWith(".md")) continue
            val m = journalDateRegex.find(name.removeSuffix(".md"))
            val date = m?.let { "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }
            journals += JournalEntry(scan(readFile("$graphPath/journals/$name").orEmpty(), pageNameFromFile(name)), date)
        }

        val allScans = pages + journals.map { it.scan }
        val incomingCount = mutableMapOf<String, Int>()
        var linkIndex = 0
        for ((index, s) in allScans.withIndex()) {
            if (index % FILES_PER_CHECKPOINT == 0) yield()
            for (target in s.outgoing) {
                if (linkIndex++ % ITEMS_PER_CHECKPOINT == 0) yield()
                incomingCount[target] = (incomingCount[target] ?: 0) + 1
            }
        }

        context.ensureActive()
        val totalBlocks = allScans.sumOf { it.blocks }
        val totalOutgoing = allScans.sumOf { it.outgoing.size }
        val totalHashtags = allScans.sumOf { it.hashtags }
        val blocksWithLinks = allScans.sumOf { it.blocksWithLinks }
        val blockLinkDensity = if (totalBlocks > 0) blocksWithLinks.toFloat() / totalBlocks else 0f
        val pageConnectivity = pages.map { connectivity(it, incomingCount) }
        yield()
        val topByIncoming = pageConnectivity.sortedByDescending { it.incomingLinks }.take(15)
        context.ensureActive()
        val topByOutgoing = pageConnectivity.sortedByDescending { it.outgoingLinks }.take(15)
        val incomingHistogram = histogram(pageConnectivity.map { it.incomingLinks })
        val outgoingHistogram = histogram(pageConnectivity.map { it.outgoingLinks })
        yield()

        val datedJournals = journals.mapNotNull { it.date }.sorted()
        val firstDate = datedJournals.firstOrNull()
        val lastDate = datedJournals.lastOrNull()
        val spanDays = if (firstDate != null && lastDate != null) daysBetween(firstDate, lastDate) else 0
        val fillRate = if (datedJournals.isNotEmpty()) datedJournals.size.toFloat() / (spanDays + 1) else 0f
        val journalsByYear = datedJournals.groupingBy { it.take(4) }.eachCount().entries.sortedBy { it.key }.associate { it.toPair() }
        val journalsByMonth = datedJournals.groupingBy { it.take(7) }.eachCount().entries.sortedBy { it.key }.associate { it.toPair() }
        yield()
        val topNamespaces = pages.filter { '/' in it.name }
            .groupingBy { it.name.substringBefore('/') }.eachCount().entries
            .sortedByDescending { it.value }.take(10).map { NamespaceStat(it.key, it.value) }
        val avgBlocks = if (pages.isNotEmpty()) totalBlocks.toFloat() / pages.size else 0f
        val avgOutgoing = if (pages.isNotEmpty()) totalOutgoing.toFloat() / pages.size else 0f
        val avgIncoming = if (pages.isNotEmpty()) incomingCount.values.sum().toFloat() / pages.size else 0f
        val maxIncoming = pageConnectivity.maxOfOrNull { it.incomingLinks } ?: 0
        val maxOutgoing = pageConnectivity.maxOfOrNull { it.outgoingLinks } ?: 0
        val pagesWithLinks = pageConnectivity.count { it.outgoingLinks > 0 }
        val pagesReferenced = pageConnectivity.count { it.incomingLinks > 0 }
        val pagesEmpty = pages.count { it.blocks == 0 }
        yield()

        // Quartiles keep very large pages from skewing the synthetic generator's range.
        val blocksPerPageP10 = percentile(pages.map { it.blocks }, 0.25).coerceAtLeast(1)
        context.ensureActive()
        val blocksPerPageP90 = percentile(pages.map { it.blocks }, 0.75).coerceAtLeast(blocksPerPageP10)
        val targets = BenchmarkTargets(
            pageCount = pages.size * 2,
            journalCount = journals.size * 2,
            linkDensity = (blockLinkDensity * 100).roundToInt() / 100f,
            blocksPerPageMin = blocksPerPageP10,
            blocksPerPageMax = blocksPerPageP90,
        )
        context.ensureActive()
        return GraphStatsReport(
            graphPath = graphPath,
            pageCount = pages.size,
            journalCount = journals.size,
            totalBlocks = totalBlocks,
            pagesWithNoContent = pagesEmpty,
            totalOutgoingLinks = totalOutgoing,
            totalHashtags = totalHashtags,
            pagesWithOutgoingLinks = pagesWithLinks,
            pagesWithIncomingLinks = pagesReferenced,
            avgOutgoingLinksPerPage = avgOutgoing,
            avgIncomingLinksPerPage = avgIncoming,
            maxIncomingLinks = maxIncoming,
            maxOutgoingLinks = maxOutgoing,
            blockLinkDensity = blockLinkDensity,
            incomingLinkHistogram = incomingHistogram,
            outgoingLinkHistogram = outgoingHistogram,
            topByIncomingLinks = topByIncoming,
            topByOutgoingLinks = topByOutgoing,
            firstJournalDate = firstDate,
            lastJournalDate = lastDate,
            journalDays = datedJournals.size,
            journalSpanDays = spanDays,
            journalFillRate = fillRate,
            avgBlocksPerPage = avgBlocks,
            journalsByYear = journalsByYear,
            journalsByMonth = journalsByMonth,
            topNamespaces = topNamespaces,
            benchmarkTargets = targets,
        )
    }

    private fun percentile(values: List<Int>, p: Double): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[(sorted.size * p).toInt().coerceIn(0, sorted.size - 1)]
    }

    private fun daysBetween(a: String, b: String): Int = (epochDay(b) - epochDay(a)).toInt()

    private fun pageNameFromFile(name: String): String =
        name.removeSuffix(".md").replace('%', '/').replace('_', ' ')

    private suspend fun scan(content: String, name: String): FileScan {
        currentCoroutineContext().ensureActive()
        var blocks = 0
        var blocksWithLinks = 0
        var hashtags = 0
        val outgoing = mutableSetOf<String>()
        for ((index, line) in content.lineSequence().withIndex()) {
            // Cached browser reads need not suspend, even for a very large file.
            if (index > 0 && index % ITEMS_PER_CHECKPOINT == 0) yield()
            if (!blockLineRegex.containsMatchIn(line)) continue
            blocks++
            val links = wikiLinkRegex.findAll(line).map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() && it != name }.toList()
            if (links.isNotEmpty()) { blocksWithLinks++; outgoing += links }
            hashtags += hashtagRegex.findAll(line).count()
        }
        currentCoroutineContext().ensureActive()
        return FileScan(name, blocks, blocksWithLinks, outgoing.toList(), hashtags)
    }

    private fun connectivity(s: FileScan, incomingCount: Map<String, Int>) =
        PageConnectivity(s.name, incomingCount[s.name] ?: 0, s.outgoing.size)

    private fun histogram(values: Iterable<Int>): Map<Int, Int> =
        values.groupingBy { it.coerceAtMost(20) }.eachCount().entries.sortedBy { it.key }.associate { it.toPair() }

    private fun epochDay(s: String): Long {
        val (y, m, d) = s.split("-").map { it.toInt() }
        val jdn = (1461L * (y + 4800 + (m - 14) / 12)) / 4 +
            (367L * (m - 2 - 12 * ((m - 14) / 12))) / 12 -
            (3L * ((y + 4900 + (m - 14) / 12) / 100)) / 4 + d - 32075
        return jdn
    }
}
