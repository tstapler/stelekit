// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.stats

import java.io.File
import kotlin.math.roundToInt

/**
 * Scans a Logseq/Stelekit graph directory and computes [GraphStatsReport] from raw markdown.
 * No SQLite or GraphLoader dependency — just file I/O and regex.
 *
 * Covers pages/ and journals/ sub-directories. Journal dates are parsed from filenames
 * in YYYY_MM_DD or YYYY-MM-DD format.
 */
class GraphStatsCollector {

    // Matches [[PageName]] and [[PageName|Alias]] — captures only the page name.
    private val wikiLinkRegex  = Regex("""\[\[([^\]|]+?)(?:\|[^\]]*)?]]""")
    // Matches #[[TagName]] and bare #word (up to next space/punctuation).
    private val hashtagRegex   = Regex("""#(?:\[\[([^\]]+)]]|(\w[\w\s-]*))""")
    // Matches any bullet block line.
    private val blockLineRegex = Regex("""^\s*-\s""")
    // Journal filename: YYYY_MM_DD or YYYY-MM-DD (with optional .md).
    private val journalDateRegex = Regex("""^(\d{4})[-_](\d{2})[-_](\d{2})""")

    private data class FileScan(
        val name: String,
        val blocks: Int,
        val blocksWithLinks: Int,
        val outgoing: List<String>,
        val hashtags: Int,
    )

    fun collect(graphDir: File): GraphStatsReport {
        val pagesDir    = File(graphDir, "pages")
        val journalsDir = File(graphDir, "journals")

        fun pageNameFromFile(f: File): String =
            f.nameWithoutExtension
                .replace('%', '/')
                .replace('_', ' ')

        fun scan(file: File, name: String): FileScan {
            val content = runCatching { file.readText() }.getOrElse { return FileScan(name, 0, 0, emptyList(), 0) }
            val lines   = content.lines()
            var blocks          = 0
            var blocksWithLinks = 0
            var hashtags        = 0
            val outgoing        = mutableListOf<String>()

            for (line in lines) {
                if (!blockLineRegex.containsMatchIn(line)) continue
                blocks++
                val links = wikiLinkRegex.findAll(line).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() && it != name }.toList()
                if (links.isNotEmpty()) { blocksWithLinks++; outgoing += links }
                hashtags += hashtagRegex.findAll(line).count()
            }

            return FileScan(name, blocks, blocksWithLinks, outgoing.distinct(), hashtags)
        }

        // Scan pages
        val pages = (pagesDir.listFiles { f -> f.extension == "md" } ?: emptyArray())
            .map { scan(it, pageNameFromFile(it)) }

        // Scan journals, extract dates
        data class JournalEntry(val scan: FileScan, val date: String?)
        val journals = (journalsDir.listFiles { f -> f.extension == "md" } ?: emptyArray())
            .map { f ->
                val m    = journalDateRegex.find(f.nameWithoutExtension)
                val date = m?.let { "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }
                JournalEntry(scan(f, pageNameFromFile(f)), date)
            }

        // Build incoming link index across pages + journals
        val incomingCount = mutableMapOf<String, Int>()
        for (s in pages + journals.map { it.scan }) {
            for (target in s.outgoing) incomingCount[target] = (incomingCount[target] ?: 0) + 1
        }

        val allScans        = pages + journals.map { it.scan }
        val totalBlocks     = allScans.sumOf { it.blocks }
        val totalOutgoing   = allScans.sumOf { it.outgoing.size }
        val totalHashtags   = allScans.sumOf { it.hashtags }
        val blocksWithLinks = allScans.sumOf { it.blocksWithLinks }
        val blockLinkDensity = if (totalBlocks > 0) blocksWithLinks.toFloat() / totalBlocks else 0f

        // Per-page connectivity
        fun connectivity(s: FileScan) = PageConnectivity(s.name, incomingCount[s.name] ?: 0, s.outgoing.size)

        val pageConnectivity = pages.map { connectivity(it) }

        val topByIncoming = pageConnectivity.sortedByDescending { it.incomingLinks }.take(15)
        val topByOutgoing = pageConnectivity.sortedByDescending { it.outgoingLinks }.take(15)

        // Histograms — bucket anything > 20 into the "20" bin for display
        fun histogram(values: Iterable<Int>): Map<Int, Int> =
            values.groupingBy { it.coerceAtMost(20) }.eachCount().toSortedMap()

        val incomingHistogram = histogram(pageConnectivity.map { it.incomingLinks })
        val outgoingHistogram = histogram(pageConnectivity.map { it.outgoingLinks })

        // Time span
        val datedJournals = journals.mapNotNull { it.date }.sorted()
        val firstDate     = datedJournals.firstOrNull()
        val lastDate      = datedJournals.lastOrNull()
        val spanDays      = if (firstDate != null && lastDate != null) daysBetween(firstDate, lastDate) else 0
        val fillRate      = if (spanDays > 0) datedJournals.size.toFloat() / spanDays else 0f

        // Growth
        val journalsByYear  = datedJournals.groupingBy { it.take(4) }.eachCount().toSortedMap()
        val journalsByMonth = datedJournals.groupingBy { it.take(7) }.eachCount().toSortedMap()

        // Namespaces (pages whose name contains '/')
        val topNamespaces = pages
            .filter { '/' in it.name }
            .groupingBy { it.name.substringBefore('/') }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(10)
            .map { NamespaceStat(it.key, it.value) }

        val avgBlocks        = if (pages.isNotEmpty()) totalBlocks.toFloat() / pages.size else 0f
        val avgOutgoing      = if (pages.isNotEmpty()) totalOutgoing.toFloat() / pages.size else 0f
        val avgIncoming      = if (pages.isNotEmpty()) incomingCount.values.sum().toFloat() / pages.size else 0f
        val maxIncoming      = pageConnectivity.maxOfOrNull { it.incomingLinks } ?: 0
        val maxOutgoing      = pageConnectivity.maxOfOrNull { it.outgoingLinks } ?: 0
        val pagesWithLinks   = pageConnectivity.count { it.outgoingLinks > 0 }
        val pagesReferenced  = pageConnectivity.count { it.incomingLinks > 0 }
        val pagesEmpty       = pages.count { it.blocks == 0 }

        // Use p25/p75 so the suggested blocksPerPage range produces a realistic average
        // when the generator picks uniformly. p10/p90 are too wide — the library has pages
        // with 100+ blocks (synthesis/MOC pages) that would skew the synthetic average up.
        val blocksPerPageP10 = percentile(pages.map { it.blocks }, 0.25).coerceAtLeast(1)
        val blocksPerPageP90 = percentile(pages.map { it.blocks }, 0.75)

        val targets = BenchmarkTargets(
            pageCount        = pages.size * 2,
            journalCount     = journals.size * 2,
            linkDensity      = (blockLinkDensity * 100).roundToInt() / 100f,
            blocksPerPageMin = blocksPerPageP10,
            blocksPerPageMax = blocksPerPageP90,
        )

        return GraphStatsReport(
            graphPath                = graphDir.absolutePath,
            pageCount                = pages.size,
            journalCount             = journals.size,
            totalBlocks              = totalBlocks,
            pagesWithNoContent       = pagesEmpty,
            totalOutgoingLinks       = totalOutgoing,
            totalHashtags            = totalHashtags,
            pagesWithOutgoingLinks   = pagesWithLinks,
            pagesWithIncomingLinks   = pagesReferenced,
            avgOutgoingLinksPerPage  = avgOutgoing,
            avgIncomingLinksPerPage  = avgIncoming,
            maxIncomingLinks         = maxIncoming,
            maxOutgoingLinks         = maxOutgoing,
            blockLinkDensity         = blockLinkDensity,
            incomingLinkHistogram    = incomingHistogram,
            outgoingLinkHistogram    = outgoingHistogram,
            topByIncomingLinks       = topByIncoming,
            topByOutgoingLinks       = topByOutgoing,
            firstJournalDate         = firstDate,
            lastJournalDate          = lastDate,
            journalDays              = datedJournals.size,
            journalSpanDays          = spanDays,
            journalFillRate          = fillRate,
            avgBlocksPerPage         = avgBlocks,
            journalsByYear           = journalsByYear,
            journalsByMonth          = journalsByMonth,
            topNamespaces            = topNamespaces,
            benchmarkTargets         = targets,
        )
    }

    private fun percentile(values: List<Int>, p: Double): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[(sorted.size * p).toInt().coerceIn(0, sorted.size - 1)]
    }

    private fun daysBetween(a: String, b: String): Int {
        fun epochDay(s: String): Long {
            val (y, m, d) = s.split("-").map { it.toInt() }
            // Julian Day Number formula
            val jdn = (1461L * (y + 4800 + (m - 14) / 12)) / 4 +
                (367L * (m - 2 - 12 * ((m - 14) / 12))) / 12 -
                (3L * ((y + 4900 + (m - 14) / 12) / 100)) / 4 + d - 32075
            return jdn
        }
        return (epochDay(b) - epochDay(a)).toInt()
    }
}
