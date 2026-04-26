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
                "%,d pages  ·  %,d journals  ·  %s".format(report.pageCount, report.journalCount, span),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            StatsCard("Volume") {
                StatRow("Pages", "%,d".format(report.pageCount))
                StatRow("Journals", "%,d".format(report.journalCount))
                StatRow("Total blocks", "%,d".format(report.totalBlocks))
                StatRow("Avg blocks / page", "%.1f".format(report.avgBlocksPerPage))
                StatRow("Empty pages", "%,d".format(report.pagesWithNoContent))
                StatRow("Hashtags", "%,d".format(report.totalHashtags))
            }
        }

        item {
            StatsCard("Link Topology") {
                StatRow("Total wiki links", "%,d".format(report.totalOutgoingLinks))
                StatRow("Block link density", "%.0f%%".format(report.blockLinkDensity * 100))
                StatRow("Pages with outgoing links", pagesPercent(report.pagesWithOutgoingLinks, report.pageCount))
                StatRow("Pages with incoming links", pagesPercent(report.pagesWithIncomingLinks, report.pageCount))
                StatRow("Avg outgoing / page", "%.1f".format(report.avgOutgoingLinksPerPage))
                StatRow("Avg incoming / page", "%.1f".format(report.avgIncomingLinksPerPage))
                StatRow("Max incoming links", "%,d".format(report.maxIncomingLinks))
                StatRow("Max outgoing links", "%,d".format(report.maxOutgoingLinks))
            }
        }

        if (report.topByIncomingLinks.isNotEmpty()) {
            item {
                StatsCard("Top Pages by Incoming Links") {
                    report.topByIncomingLinks.take(10).forEachIndexed { i, p ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(
                                "%2d.".format(i + 1),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(28.dp),
                            )
                            Text(p.name.take(36), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(
                                "← %,d".format(p.incomingLinks),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(60.dp),
                            )
                            Text(
                                "→ %,d".format(p.outgoingLinks),
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
                    StatRow("Fill rate", "%.0f%% of days".format(report.journalFillRate * 100))
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
                        StatRow(ns.namespace, "%,d pages".format(ns.count))
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
            "%,d $unit".format(count),
            modifier = Modifier.width(80.dp).padding(start = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun pagesPercent(count: Int, total: Int): String =
    "%,d  (%.0f%%)".format(count, count.toFloat() / total.coerceAtLeast(1) * 100)
