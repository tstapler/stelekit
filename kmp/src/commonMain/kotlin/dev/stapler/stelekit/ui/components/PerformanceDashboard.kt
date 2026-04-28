package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stapler.stelekit.performance.HistogramWriter
import dev.stapler.stelekit.performance.PerfExporter
import dev.stapler.stelekit.performance.PercentileSummary
import dev.stapler.stelekit.performance.PerformanceMonitor
import dev.stapler.stelekit.performance.RingBufferSpanExporter
import dev.stapler.stelekit.performance.SerializedSpan
import dev.stapler.stelekit.performance.SpanRepository
import dev.stapler.stelekit.performance.TraceEvent
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PerformanceDashboard(
    histogramWriter: HistogramWriter? = null,
    ringBuffer: RingBufferSpanExporter? = null,
    spanRepository: SpanRepository? = null,
    perfExporter: PerfExporter? = null,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Histograms", "Spans", "Traces")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> HistogramsTab(histogramWriter)
            1 -> SpansTab(spanRepository, ringBuffer, perfExporter)
            2 -> TracesTab()
        }
    }
}

@Composable
private fun HistogramsTab(histogramWriter: HistogramWriter?) {
    val operations = listOf("frame_duration", "graph_load", "navigation", "search")

    val summaries by produceState<Map<String, PercentileSummary>>(emptyMap(), histogramWriter) {
        while (true) {
            if (histogramWriter != null) {
                val result = withContext(PlatformDispatcher.DB) {
                    operations
                        .mapNotNull { op -> histogramWriter.queryPercentiles(op)?.let { op to it } }
                        .toMap()
                }
                value = result
            }
            delay(2000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Operation", modifier = Modifier.weight(2f), style = MaterialTheme.typography.labelMedium)
                Text("p50", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("p95", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("p99", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                Text("Samples", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }
        items(operations) { op ->
            val summary = summaries[op]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(op, modifier = Modifier.weight(2f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                if (summary != null) {
                    Text("${summary.p50Ms}ms", modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text("${summary.p95Ms}ms", modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text(
                        text = "${summary.p99Ms}ms",
                        modifier = Modifier.weight(1f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = p99Color(summary.p99Ms)
                    )
                    Text("${summary.sampleCount}", modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                } else {
                    Text("—", modifier = Modifier.weight(1f))
                    Text("—", modifier = Modifier.weight(1f))
                    Text("—", modifier = Modifier.weight(1f))
                    Text("—", modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun p99Color(p99Ms: Long): Color = when {
    p99Ms > 100 -> Color.Red
    p99Ms > 33 -> Color(0xFFFF9800)
    else -> Color(0xFF4CAF50)
}

private val durationPresets = listOf(0L, 50L, 100L, 250L, 500L)

@OptIn(DirectRepositoryWrite::class)
@Composable
private fun SpansTab(
    spanRepository: SpanRepository?,
    ringBuffer: RingBufferSpanExporter?,
    perfExporter: PerfExporter? = null,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // SQLite-persisted spans (reactive flow); fall back to polling ring buffer if no repo.
    val liveSpans by if (spanRepository != null) {
        spanRepository.getRecentSpans(500).collectAsState(emptyList())
    } else {
        produceState(emptyList<SerializedSpan>()) {
            while (true) {
                value = ringBuffer?.snapshot()?.reversed() ?: emptyList()
                delay(1000)
            }
        }
    }

    var paused by remember { mutableStateOf(false) }
    var frozenSpans by remember { mutableStateOf<List<SerializedSpan>?>(null) }
    var maxDepth by remember { mutableIntStateOf(8) }

    // Filter state
    var filterName by remember { mutableStateOf("") }
    var minDurationMs by remember { mutableLongStateOf(0L) }
    var errorsOnly by remember { mutableStateOf(false) }

    // Detail panel state
    var selectedSpan by remember { mutableStateOf<SerializedSpan?>(null) }

    val displaySpans = frozenSpans ?: liveSpans
    val allTraces = remember(displaySpans, maxDepth) { groupIntoTraces(displaySpans, maxDepth) }
    val traces = remember(allTraces, filterName, minDurationMs, errorsOnly) {
        allTraces.filter { trace ->
            (filterName.isBlank() || trace.rows.any { it.span.name.contains(filterName, ignoreCase = true) }) &&
            trace.durationMs >= minDurationMs &&
            (!errorsOnly || trace.hasError)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Toolbar row 1: controls ──────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Traces (${traces.size}/${allTraces.size})", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text("Depth:", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { if (maxDepth > 1) maxDepth-- }, modifier = Modifier.size(32.dp)) {
                    Text("−", style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    text = maxDepth.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.widthIn(min = 20.dp),
                )
                IconButton(onClick = { if (maxDepth < 32) maxDepth++ }, modifier = Modifier.size(32.dp)) {
                    Text("+", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = {
                    if (paused) { frozenSpans = null } else { frozenSpans = liveSpans }
                    paused = !paused
                }) {
                    Icon(
                        imageVector = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (paused) "Resume" else "Pause",
                        tint = if (paused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (perfExporter != null) {
                    IconButton(onClick = {
                        scope.launch {
                            val msg = try {
                                val path = perfExporter.exportWithPicker()
                                if (path != null) "Performance report exported" else "Export cancelled"
                            } catch (e: Exception) {
                                "Export failed. Check storage permissions."
                            }
                            snackbarHostState.showSnackbar(msg)
                        }
                    }) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Export JSON")
                    }
                }
                IconButton(onClick = {
                    scope.launch { spanRepository?.clear(); ringBuffer?.clear() }
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear")
                }
            }
            // ── Toolbar row 2: filters ───────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = filterName,
                    onValueChange = { filterName = it },
                    placeholder = { Text("Filter by span name…", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                )
                durationPresets.forEach { preset ->
                    val selected = minDurationMs == preset
                    FilterChip(
                        selected = selected,
                        onClick = { minDurationMs = preset },
                        label = {
                            Text(
                                if (preset == 0L) "Any" else "≥${preset}ms",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                    )
                }
                FilterChip(
                    selected = errorsOnly,
                    onClick = { errorsOnly = !errorsOnly },
                    label = { Text("Errors", style = MaterialTheme.typography.labelSmall) },
                )
            }
            HorizontalDivider()
            if (traces.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (allTraces.isEmpty()) "No spans recorded yet" else "No traces match the current filter",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(traces) { trace ->
                        TraceWaterfallRow(trace, onSpanClick = { selectedSpan = it })
                    }
                }
            }
        }
        // ── Span detail panel overlay ────────────────────────────────────
        val span = selectedSpan
        if (span != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                SpanDetailPanel(span = span, onDismiss = { selectedSpan = null })
            }
        }
    }
    } // Scaffold
}

private data class TraceGroup(
    val traceId: String,
    val rows: List<SpanRow>,      // DFS-ordered, root first
    val startMs: Long,
    val durationMs: Long,
    val spanCount: Int,
    val slowestChildName: String,
    val slowestChildMs: Long,
    val hasError: Boolean,
)

private data class SpanRow(val span: SerializedSpan, val depth: Int)

private fun groupIntoTraces(spans: List<SerializedSpan>, maxDepth: Int = 8): List<TraceGroup> {
    val byTrace = spans.groupBy { it.traceId.ifEmpty { "jank-${it.startEpochMs}" } }
    return byTrace.values.map { group ->
        val startMs = group.minOf { it.startEpochMs }
        val endMs = group.maxOf { it.endEpochMs }
        val byParent = group.groupBy { it.parentSpanId }
        val rows = mutableListOf<SpanRow>()
        val visited = mutableSetOf<String>()
        // Iterative pre-order DFS — avoids stack overflow on deep or cyclic span trees.
        // Children are pushed in reverse startEpochMs order so the earliest is popped first.
        val stack = ArrayDeque<Pair<SerializedSpan, Int>>()
        byParent[""]?.sortedByDescending { it.startEpochMs }?.forEach { stack.addLast(it to 0) }
        while (stack.isNotEmpty()) {
            val (span, depth) = stack.removeLast()
            if (span.spanId in visited) continue
            visited += span.spanId
            rows += SpanRow(span, depth)
            if (depth < maxDepth) {
                byParent[span.spanId]?.sortedByDescending { it.startEpochMs }?.forEach { child ->
                    if (child.spanId !in visited) stack.addLast(child to depth + 1)
                }
            }
        }
        // Append any orphaned spans (parentSpanId set but parent not in this trace window)
        group.filter { it.spanId !in visited }.forEach { rows += SpanRow(it, 1) }
        // Slowest child = deepest-depth span (depth > 0) with max durationMs
        val slowestChild = rows.filter { it.depth > 0 }.maxByOrNull { it.span.durationMs }
        TraceGroup(
            traceId = group.first().traceId,
            rows = rows,
            startMs = startMs,
            durationMs = (endMs - startMs).coerceAtLeast(1),
            spanCount = rows.size,
            slowestChildName = slowestChild?.span?.name ?: "",
            slowestChildMs = slowestChild?.span?.durationMs ?: 0L,
            hasError = rows.any { it.span.statusCode == "ERROR" },
        )
    // Error traces first, then newest first within each tier
    }.sortedWith(compareByDescending<TraceGroup> { it.hasError }.thenByDescending { it.startMs })
}

@Composable
private fun TraceWaterfallRow(
    trace: TraceGroup,
    onSpanClick: (SerializedSpan) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(true) }
    val rootSpan = trace.rows.firstOrNull()?.span
    val statusColor = when {
        trace.hasError -> MaterialTheme.colorScheme.error
        trace.durationMs > 500 -> Color(0xFFFF9800)
        else -> Color(0xFF4CAF50)
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Column {
            // Trace header
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rootSpan?.name ?: "trace",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    val subtitle = buildString {
                        append("${trace.spanCount} spans")
                        if (trace.slowestChildName.isNotEmpty()) {
                            append(" · slowest: ${trace.slowestChildName} ${trace.slowestChildMs}ms")
                        }
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (trace.traceId.isNotEmpty()) {
                    Text(
                        text = trace.traceId.takeLast(8),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Text(
                    text = "${trace.durationMs}ms",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = statusColor
                )
            }

            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                TimelineRuler(durationMs = trace.durationMs)
                trace.rows.forEach { row ->
                    SpanWaterfallRow(row, trace.startMs, trace.durationMs, onSpanClick)
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun SpanWaterfallRow(
    row: SpanRow,
    traceStartMs: Long,
    traceDurationMs: Long,
    onSpanClick: (SerializedSpan) -> Unit = {},
) {
    val span = row.span
    val isError = span.statusCode == "ERROR"
    val barColor = when {
        isError -> MaterialTheme.colorScheme.error
        span.durationMs > 100 -> Color(0xFFFF9800)
        else -> Color(0xFF4CAF50)
    }
    val offsetFraction = ((span.startEpochMs - traceStartMs).toFloat() / traceDurationMs).coerceIn(0f, 1f)
    val widthFraction = (span.durationMs.toFloat() / traceDurationMs).coerceAtLeast(0.004f).coerceAtMost(1f - offsetFraction)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSpanClick(span) }
            .padding(vertical = 2.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Span name with depth indent — fixed 40% width
        Row(
            modifier = Modifier.weight(0.4f).padding(start = (row.depth * 12).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isError) {
                Text(
                    text = "●",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = span.name,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Timeline bar — 60% width
        BoxWithConstraints(modifier = Modifier.weight(0.6f).height(18.dp)) {
            // Use Canvas for pixel-accurate positioning
            Canvas(modifier = Modifier.fillMaxSize()) {
                val barStart = offsetFraction * size.width
                val barWidth = (widthFraction * size.width).coerceAtLeast(2f)
                drawRect(
                    color = barColor,
                    topLeft = Offset(barStart, size.height * 0.2f),
                    size = Size(barWidth, size.height * 0.6f)
                )
            }
            Text(
                text = "${span.durationMs}ms",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.CenterEnd),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Mirrors the Row(weight(0.4f) + weight(0.6f)) layout of SpanWaterfallRow so the ruler
// tick marks align with the start of the timeline bar column.
@Composable
private fun TimelineRuler(durationMs: Long, modifier: Modifier = Modifier) {
    val tickCount = 5
    val onSurface = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(18.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        // Empty name column — same 40% as SpanWaterfallRow
        Spacer(Modifier.weight(0.4f))
        // Ruler column — same 60%
        BoxWithConstraints(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                for (i in 0..tickCount) {
                    val x = (i.toFloat() / tickCount) * size.width
                    drawLine(
                        color = onSurface.copy(alpha = 0.35f),
                        start = Offset(x, size.height * 0.4f),
                        end = Offset(x, size.height),
                        strokeWidth = 1f
                    )
                }
            }
            for (i in 0..tickCount) {
                val fraction = i.toFloat() / tickCount
                val labelMs = (fraction * durationMs).toLong()
                Text(
                    text = "${labelMs}ms",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.TopStart).offset(x = (fraction * maxWidth.value).dp)
                )
            }
        }
    }
}

@Composable
private fun SpanDetailPanel(span: SerializedSpan, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(span.name, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val badgeColor = if (span.statusCode == "ERROR") MaterialTheme.colorScheme.error
                                     else Color(0xFF4CAF50)
                    Surface(
                        color = badgeColor.copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.extraSmall
                    ) {
                        Text(
                            text = span.statusCode,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            // Timing row
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailRow("duration", "${span.durationMs}ms")
                    DetailRow("start", "${span.startEpochMs}ms epoch")
                    DetailRow("end", "${span.endEpochMs}ms epoch")
                    DetailRow("trace_id", span.traceId)
                    DetailRow("span_id", span.spanId)
                    DetailRow("parent_span_id", span.parentSpanId.ifEmpty { "(root)" })
                    if (span.attributes.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(4.dp))
                        Text("Attributes", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        span.attributes.entries.sortedBy { it.key }.forEach { (k, v) ->
                            DetailRow(k, v)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 110.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
        )
    }
}

@Composable
private fun TracesTab() {
    val events by PerformanceMonitor.events.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val filteredEvents = remember(events, searchQuery) {
        events.filter { event ->
            searchQuery.isBlank() ||
            event.name.contains(searchQuery, ignoreCase = true) ||
            event.thread.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Trace Events",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Scroll to Top")
            }
            IconButton(onClick = {
                scope.launch { listState.animateScrollToItem(filteredEvents.lastIndex.coerceAtLeast(0)) }
            }) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "Scroll to Bottom")
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search events...") },
                modifier = Modifier.width(200.dp),
                singleLine = true
            )
            IconButton(onClick = { PerformanceMonitor.clear() }) {
                Icon(Icons.Default.Delete, contentDescription = "Clear Events")
            }
        }
        HorizontalDivider()
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredEvents) { event ->
                    PerformanceEventItem(event)
                }
            }
        }
    }
}

@Composable
fun PerformanceEventItem(event: TraceEvent) {
    val durationColor = getDurationColor(event.duration)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        SelectionContainer {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(durationColor, MaterialTheme.shapes.small)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = event.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "[${event.thread}]",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (event.type == "trace") {
                        Text(
                            text = "${event.duration} ms",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            ),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    } else {
                        Text(
                            text = "MARK",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            modifier = Modifier.padding(top = 4.dp),
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun getDurationColor(duration: Long): Color {
    val scheme = MaterialTheme.colorScheme
    return when {
        duration > 1000 -> scheme.error
        duration > 100 -> scheme.secondary
        else -> scheme.primary
    }
}
