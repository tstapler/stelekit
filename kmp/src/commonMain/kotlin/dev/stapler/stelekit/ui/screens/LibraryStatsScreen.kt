// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.performance.NavigationTracingEffect
import dev.stapler.stelekit.stats.GraphStatsReport
import dev.stapler.stelekit.stats.NamespaceStat
import kotlin.math.roundToInt

@Composable
fun LibraryStatsScreen(
    viewModel: LibraryStatsViewModel,
    modifier: Modifier = Modifier,
) {
    NavigationTracingEffect("LibraryStats")
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        if (state == LibraryStatsState.Idle) viewModel.load()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Library Stats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            when (state) {
                LibraryStatsState.Loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                else -> IconButton(onClick = { viewModel.load() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
        }
        HorizontalDivider()

        when (val s = state) {
            LibraryStatsState.Idle -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Tap refresh to scan your library.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LibraryStatsState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("Scanning library…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            is LibraryStatsState.Error -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                }
            }
            is LibraryStatsState.Loaded -> StatsContent(s.report)
        }
    }
}

@Composable
private fun StatsContent(report: GraphStatsReport) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val span = if (report.firstJournalDate != null && report.lastJournalDate != null)
                "${report.firstJournalDate} → ${report.lastJournalDate}" else "(no dated journals)"
            Text(
                "${report.pageCount.withCommas()} pages  ·  ${report.journalCount.withCommas()} journals  ·  $span",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            StatsCard("Volume") {
                StatRow("Pages", report.pageCount.withCommas())
                StatRow("Journals", report.journalCount.withCommas())
                StatRow("Total blocks", report.totalBlocks.withCommas())
                StatRow("Avg blocks / page", report.avgBlocksPerPage.fmt1dp())
                StatRow("Empty pages", report.pagesWithNoContent.withCommas())
                StatRow("Hashtags", report.totalHashtags.withCommas())
            }
        }

        item {
            StatsCard("Link Topology") {
                StatRow("Total wiki links", report.totalOutgoingLinks.withCommas())
                StatRow("Block link density", report.blockLinkDensity.pct())
                StatRow("Pages with outgoing links", report.pagesWithOutgoingLinks.withPct(report.pageCount))
                StatRow("Pages with incoming links", report.pagesWithIncomingLinks.withPct(report.pageCount))
                StatRow("Avg outgoing / page", report.avgOutgoingLinksPerPage.fmt1dp())
                StatRow("Avg incoming / page", report.avgIncomingLinksPerPage.fmt1dp())
                StatRow("Max incoming links", report.maxIncomingLinks.withCommas())
                StatRow("Max outgoing links", report.maxOutgoingLinks.withCommas())
            }
        }

        if (report.topByIncomingLinks.isNotEmpty()) {
            item {
                StatsCard("Top Pages by Incoming Links") {
                    report.topByIncomingLinks.take(10).forEachIndexed { i, p ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(
                                "${i + 1}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(28.dp),
                            )
                            Text(p.name.take(36), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(
                                "← ${p.incomingLinks.withCommas()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(60.dp),
                            )
                            Text(
                                "→ ${p.outgoingLinks.withCommas()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(60.dp),
                            )
                        }
                    }
                }
            }
        }

        if (report.incomingLinkHistogram.isNotEmpty()) {
            item {
                StatsCard("Incoming Links per Page") {
                    IntHistogram(report.incomingLinkHistogram, "pages")
                }
            }
        }

        if (report.journalsByYear.isNotEmpty()) {
            item {
                StatsCard("Journal Activity by Year") {
                    StatRow("Fill rate", "${(report.journalFillRate * 100).roundToInt()}% of days")
                    Spacer(Modifier.height(8.dp))
                    val maxCount = report.journalsByYear.values.maxOrNull()?.coerceAtLeast(1) ?: 1
                    report.journalsByYear.forEach { (year, count) ->
                        BarChartRow(year, count, maxCount, "entries")
                    }
                }
            }
        }

        if (report.topNamespaces.isNotEmpty()) {
            item {
                StatsCard("Top Namespaces") {
                    report.topNamespaces.forEach { ns: NamespaceStat ->
                        StatRow(ns.namespace, "${ns.count.withCommas()} pages")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun IntHistogram(histogram: Map<Int, Int>, unit: String) {
    val maxCount = histogram.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    for (bucket in 0..20) {
        val count = histogram[bucket] ?: 0
        if (count == 0 && bucket > 0 && (histogram.keys.maxOrNull() ?: 0) < bucket) break
        BarChartRow(if (bucket == 20) "20+" else "$bucket", count, maxCount, unit)
    }
}

@Composable
private fun BarChartRow(label: String, count: Int, maxCount: Int, unit: String) {
    val fraction = if (maxCount > 0) (count.toFloat() / maxCount).coerceIn(0f, 1f) else 0f
    Row(
        modifier = Modifier.fillMaxWidth().height(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.width(40.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.weight(1f).height(14.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
            )
        }
        Text(
            "${count.withCommas()} $unit",
            modifier = Modifier.width(88.dp).padding(start = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── KMP-safe number formatting helpers ──────────────────────────────────────

private fun Int.withCommas(): String {
    if (this < 0) return "-${(-this).withCommas()}"
    if (this < 1000) return toString()
    val s = toString()
    val sb = StringBuilder()
    s.reversed().forEachIndexed { i, c ->
        if (i > 0 && i % 3 == 0) sb.append(',')
        sb.append(c)
    }
    return sb.reverse().toString()
}

private fun Float.fmt1dp(): String {
    val rounded = (this * 10).roundToInt()
    return "${rounded / 10}.${rounded % 10}"
}

private fun Float.pct(): String = "${(this * 100).roundToInt()}%"

private fun Int.withPct(total: Int): String {
    val p = if (total > 0) (this * 100f / total).roundToInt() else 0
    return "${withCommas()}  ($p%)"
}
