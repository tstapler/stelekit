// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.stats

import java.io.File
import kotlin.test.Test

/**
 * Prints a "Spotify Wrapped"-style summary of a Logseq/Stelekit library.
 *
 * Usage:
 *   ./gradlew :kmp:graphStats -PgraphPath=/path/to/your/logseq
 *
 * Outputs:
 *   - Volume: pages, journals, blocks, links
 *   - Link topology: incoming/outgoing distribution, top hub pages
 *   - Journal activity by year (bar chart)
 *   - Benchmark targets calibrated to 2× this library
 */
class LibraryWrappedTest {

    @Test
    fun `library wrapped`() {
        val graphPath = System.getProperty("STELEKIT_GRAPH_PATH")
        if (graphPath.isNullOrBlank()) {
            println("[library-wrapped] SKIPPED — run with: ./gradlew :kmp:graphStats -PgraphPath=/your/logseq")
            return
        }
        val dir = File(graphPath)
        require(dir.isDirectory) { "Graph path not found: $graphPath" }

        println("\nScanning $graphPath …")
        val report = GraphStatsCollector().collect(dir)
        printWrapped(report)
    }

    // ── formatting ─────────────────────────────────────────────────────────

    private fun printWrapped(r: GraphStatsReport) {
        val w  = 66
        val hr = "─".repeat(w)
        val eq = "═".repeat(w)

        fun fmt(n: Int)   = "%,d".format(n)
        fun fmtF(f: Float) = "%.2f".format(f)
        fun pct(f: Float)  = "%.0f%%".format(f * 100)
        fun row(label: String, value: String) = println("  %-34s %s".format(label, value))

        println()
        println(eq)
        println("  STELEKIT LIBRARY STATS")
        println("  ${r.graphPath}")
        println(eq)

        // ── Overview line ─────────────────────────────────────────────────
        val span = if (r.firstJournalDate != null && r.lastJournalDate != null)
            "${r.firstJournalDate} → ${r.lastJournalDate}"
        else "(no dated journals)"
        println()
        println("  ${fmt(r.pageCount)} pages  ·  ${fmt(r.journalCount)} journals  ·  $span")
        println()

        // ── Volume ────────────────────────────────────────────────────────
        println("  VOLUME")
        println("  $hr")
        row("Pages",              fmt(r.pageCount))
        row("Journals",           fmt(r.journalCount))
        row("Total blocks",       fmt(r.totalBlocks))
        row("Avg blocks/page",    fmtF(r.avgBlocksPerPage))
        row("Pages with no content", fmt(r.pagesWithNoContent))
        println()

        // ── Link topology ─────────────────────────────────────────────────
        println("  LINK TOPOLOGY")
        println("  $hr")
        row("Total wiki links (outgoing)", fmt(r.totalOutgoingLinks))
        row("Total hashtags",             fmt(r.totalHashtags))
        row("Pages with outgoing links",  "${fmt(r.pagesWithOutgoingLinks)}  (${pct(r.pagesWithOutgoingLinks.toFloat() / r.pageCount.coerceAtLeast(1))})")
        row("Pages with incoming links",  "${fmt(r.pagesWithIncomingLinks)}  (${pct(r.pagesWithIncomingLinks.toFloat() / r.pageCount.coerceAtLeast(1))})")
        row("Avg outgoing links/page",    fmtF(r.avgOutgoingLinksPerPage))
        row("Avg incoming links/page",    fmtF(r.avgIncomingLinksPerPage))
        row("Max incoming links",         fmt(r.maxIncomingLinks))
        row("Max outgoing links",         fmt(r.maxOutgoingLinks))
        row("Block link density",         "${pct(r.blockLinkDensity)}  (blocks containing ≥1 link)")
        println()

        // ── Outgoing link distribution ────────────────────────────────────
        println("  OUTGOING LINKS PER PAGE  (pages grouped by link count)")
        println("  $hr")
        printHistogram(r.outgoingLinkHistogram, label20 = "20+")
        println()

        // ── Incoming link distribution ────────────────────────────────────
        println("  INCOMING LINKS PER PAGE  (how often each page is referenced)")
        println("  $hr")
        printHistogram(r.incomingLinkHistogram, label20 = "20+")
        println()

        // ── Top hub pages ─────────────────────────────────────────────────
        println("  TOP PAGES BY INCOMING LINKS  (hub / index pages)")
        println("  $hr")
        r.topByIncomingLinks.forEachIndexed { i, p ->
            println("  %2d. %-40s ← %4d  → %4d".format(i + 1, p.name.take(40), p.incomingLinks, p.outgoingLinks))
        }
        println()

        println("  TOP PAGES BY OUTGOING LINKS  (pages that reference the most)")
        println("  $hr")
        r.topByOutgoingLinks.forEachIndexed { i, p ->
            println("  %2d. %-40s → %4d  ← %4d".format(i + 1, p.name.take(40), p.outgoingLinks, p.incomingLinks))
        }
        println()

        // ── Journal activity ──────────────────────────────────────────────
        if (r.journalsByYear.isNotEmpty()) {
            println("  JOURNAL ACTIVITY BY YEAR")
            println("  $hr")
            println("  Fill rate: ${pct(r.journalFillRate)} of days have a journal entry")
            println()
            val maxCount = r.journalsByYear.values.maxOrNull() ?: 1
            val barWidth = 28
            r.journalsByYear.forEach { (year, count) ->
                val filled = (count.toFloat() / maxCount * barWidth).toInt()
                val empty  = barWidth - filled
                val bar    = "█".repeat(filled) + "░".repeat(empty)
                println("  $year  $bar  ${fmt(count)}")
            }
            println()
        }

        // ── Namespaces ────────────────────────────────────────────────────
        if (r.topNamespaces.isNotEmpty()) {
            println("  TOP NAMESPACES")
            println("  $hr")
            r.topNamespaces.forEach { ns ->
                println("  %-30s  ${fmt(ns.count)} pages".format(ns.namespace))
            }
            println()
        }

        // ── Benchmark targets ─────────────────────────────────────────────
        val t = r.benchmarkTargets
        println("  BENCHMARK TARGETS  (2× this library)")
        println("  $hr")
        row("Pages",         "${fmt(t.pageCount)}  (2× ${fmt(r.pageCount)})")
        row("Journals",      "${fmt(t.journalCount)}  (2× ${fmt(r.journalCount)})")
        row("Link density",  fmtF(t.linkDensity))
        row("blocksPerPage", "${t.blocksPerPageMin}..${t.blocksPerPageMax}")
        println()
        println("  Paste into SyntheticGraphGenerator.XLARGE:")
        println()
        println("    val XLARGE = Config(")
        println("        pageCount         = ${t.pageCount},")
        println("        journalCount      = ${t.journalCount},")
        println("        linkDensity       = ${t.linkDensity}f,")
        println("        blocksPerPage     = ${t.blocksPerPageMin}..${t.blocksPerPageMax},")
        println("        hubFraction       = 0.05f,")
        println("        hubLinkWeight     = 15.0f,")
        println("    )")
        println()

        println(eq)
        println()
    }

    private fun printHistogram(histogram: Map<Int, Int>, label20: String) {
        if (histogram.isEmpty()) return
        val maxCount = histogram.values.maxOrNull() ?: 1
        val barWidth = 24
        for (bucket in 0..20) {
            val count = histogram[bucket] ?: 0
            if (count == 0 && bucket > 0 && histogram.keys.maxOrNull() ?: 0 < bucket) break
            val label = if (bucket == 20) label20 else "$bucket"
            val filled = (count.toFloat() / maxCount * barWidth).toInt()
            val bar    = "▓".repeat(filled)
            println("  %4s  %-${barWidth}s  %,d pages".format(label, bar, count))
        }
    }
}
