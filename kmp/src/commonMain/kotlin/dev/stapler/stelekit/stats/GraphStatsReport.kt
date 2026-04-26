// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.stats

/**
 * A snapshot of a graph's size, connectivity, growth, and link topology.
 * Computed by GraphStatsCollector from raw markdown files — no SQLite required.
 * Intended for use by both the CLI tool and the future in-app Library Stats screen.
 */
data class GraphStatsReport(
    val graphPath: String,

    // ── Volume ────────────────────────────────────────────────────────────
    val pageCount: Int,
    val journalCount: Int,
    val totalBlocks: Int,
    val pagesWithNoContent: Int,

    // ── Link topology ─────────────────────────────────────────────────────
    val totalOutgoingLinks: Int,
    val totalHashtags: Int,
    /** Pages that reference at least one other page. */
    val pagesWithOutgoingLinks: Int,
    /** Pages that are referenced by at least one other page. */
    val pagesWithIncomingLinks: Int,
    val avgOutgoingLinksPerPage: Float,
    val avgIncomingLinksPerPage: Float,
    val maxIncomingLinks: Int,
    val maxOutgoingLinks: Int,
    /** Fraction of blocks that contain at least one [[link]]. Equivalent to SyntheticGraphGenerator.Config.linkDensity. */
    val blockLinkDensity: Float,
    /** Distribution: how many pages have exactly N incoming links (capped at 20 for display). */
    val incomingLinkHistogram: Map<Int, Int>,
    /** Distribution: how many pages have exactly N outgoing links (capped at 20 for display). */
    val outgoingLinkHistogram: Map<Int, Int>,
    /** Top 15 pages ranked by incoming link count. */
    val topByIncomingLinks: List<PageConnectivity>,
    /** Top 15 pages ranked by outgoing link count. */
    val topByOutgoingLinks: List<PageConnectivity>,

    // ── Time span ─────────────────────────────────────────────────────────
    /** ISO date string "YYYY-MM-DD", or null if no dated journals found. */
    val firstJournalDate: String?,
    val lastJournalDate: String?,
    /** Distinct days with journal entries. */
    val journalDays: Int,
    /** Calendar days between first and last journal. */
    val journalSpanDays: Int,
    /** journalDays / journalSpanDays — fraction of days that have a journal entry. */
    val journalFillRate: Float,

    // ── Density ───────────────────────────────────────────────────────────
    val avgBlocksPerPage: Float,

    // ── Growth over time ──────────────────────────────────────────────────
    /** "YYYY" → count of journals in that year. */
    val journalsByYear: Map<String, Int>,
    /** "YYYY-MM" → count of journals in that month. */
    val journalsByMonth: Map<String, Int>,

    // ── Namespaces ────────────────────────────────────────────────────────
    val topNamespaces: List<NamespaceStat>,

    // ── Benchmark targets ─────────────────────────────────────────────────
    /** Suggested SyntheticGraphGenerator.Config values calibrated to 2× this library. */
    val benchmarkTargets: BenchmarkTargets,
)

data class PageConnectivity(
    val name: String,
    val incomingLinks: Int,
    val outgoingLinks: Int,
)

data class NamespaceStat(val namespace: String, val count: Int)

data class BenchmarkTargets(
    val pageCount: Int,
    val journalCount: Int,
    val linkDensity: Float,
    val blocksPerPageMin: Int,
    val blocksPerPageMax: Int,
)
