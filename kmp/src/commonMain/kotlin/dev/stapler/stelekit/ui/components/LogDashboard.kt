package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stapler.stelekit.logging.LogEntry
import dev.stapler.stelekit.logging.LogLevel
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.ui.rememberShareProvider
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * @param diagnostics Builds the graph diagnostics report shown by the info button. Null hides the
 *   button (e.g. when no graph is active).
 */
@Composable
fun LogDashboard(
    modifier: Modifier = Modifier,
    diagnostics: (suspend () -> String)? = null,
) {
    val logs by LogManager.logs.collectAsState()
    var filterLevel by remember { mutableStateOf<LogLevel?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val shareProvider = rememberShareProvider()
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var diagnosticsReport by remember { mutableStateOf<String?>(null) }
    var diagnosticsRunning by remember { mutableStateOf(false) }

    suspend fun saveToDownloads(body: String, baseName: String) {
        statusMessage = shareProvider.saveToDownloads(body, baseName, "txt")
            .fold({ "Save failed: ${it.message}" }, { "Saved to $it" })
    }

    val filteredLogs = remember(logs, filterLevel, searchQuery) {
        logs.filter { entry ->
            (filterLevel == null || entry.level == filterLevel) &&
            (searchQuery.isBlank() || 
             entry.message.contains(searchQuery, ignoreCase = true) || 
             entry.tag.contains(searchQuery, ignoreCase = true))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Text(
            "App Logs",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)
        )

        // Search sits on its own row: beside the icons it pushed the export buttons off-screen
        // on phone-width displays.
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search logs...") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            singleLine = true
        )

        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            // Scroll Buttons
            IconButton(onClick = { 
                scope.launch { listState.animateScrollToItem(0) }
            }) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Scroll to Top")
            }
            
            IconButton(onClick = { 
                scope.launch { listState.animateScrollToItem(filteredLogs.lastIndex.coerceAtLeast(0)) }
            }) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "Scroll to Bottom")
            }

            // Level Filter
            var filterExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { filterExpanded = true }) {
                    Icon(
                        Icons.Default.FilterList,
                        contentDescription = "Filter",
                        tint = if (filterLevel != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
                DropdownMenu(
                    expanded = filterExpanded,
                    onDismissRequest = { filterExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("All Levels") },
                        onClick = { 
                            filterLevel = null
                            filterExpanded = false
                        }
                    )
                    LogLevel.values().forEach { level ->
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    level.name,
                                    color = getLevelColor(level)
                                ) 
                            },
                            onClick = { 
                                filterLevel = level
                                filterExpanded = false
                            }
                        )
                    }
                }
            }

            // Export (share the currently filtered logs as a text file)
            IconButton(onClick = {
                scope.launch {
                    val body = filteredLogs.asReversed().joinToString("\n\n") { it.toExportText() }
                    shareProvider.saveToFile(
                        content = body,
                        suggestedName = "stelekit-logs-${Clock.System.now().epochSeconds}",
                        extension = "txt",
                    )
                }
            }) {
                Icon(Icons.Default.Share, contentDescription = "Share Logs")
            }

            // Save the currently filtered logs straight to the Downloads folder
            IconButton(onClick = {
                scope.launch {
                    val body = filteredLogs.asReversed().joinToString("\n\n") { it.toExportText() }
                    saveToDownloads(body, "stelekit-logs-${Clock.System.now().epochSeconds}")
                }
            }) {
                Icon(Icons.Default.FileDownload, contentDescription = "Save Logs to Downloads")
            }

            if (diagnostics != null) {
                IconButton(
                    enabled = !diagnosticsRunning,
                    onClick = {
                        scope.launch {
                            diagnosticsRunning = true
                            diagnosticsReport = try {
                                diagnostics()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                "Diagnostics failed: ${e::class.simpleName}: ${e.message}"
                            } finally {
                                diagnosticsRunning = false
                            }
                        }
                    }
                ) {
                    Icon(Icons.Default.Info, contentDescription = "Graph Diagnostics")
                }
            }

            // Clear
            IconButton(onClick = { LogManager.clearLogs() }) {
                Icon(Icons.Default.Delete, contentDescription = "Clear Logs")
            }
        }

        statusMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        HorizontalDivider()

        diagnosticsReport?.let { report ->
            DiagnosticsDialog(
                report = report,
                onShare = { scope.launch { shareProvider.shareText(report) } },
                onSave = {
                    scope.launch { saveToDownloads(report, "stelekit-diagnostics-${Clock.System.now().epochSeconds}") }
                    diagnosticsReport = null
                },
                onDismiss = { diagnosticsReport = null },
            )
        }

        // Log List
        SelectionContainer(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredLogs) { log ->
                    LogItem(log)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsDialog(
    report: String,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Graph diagnostics") },
        text = {
            SelectionContainer {
                Text(
                    text = report,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onShare) { Text("Share") }
                TextButton(onClick = onSave) { Text("Save to Downloads") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun LogItem(log: LogEntry) {
    val levelColor = getLevelColor(log.level)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Level Indicator
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(8.dp)
                    .background(levelColor, MaterialTheme.shapes.small)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = log.tag,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = log.timestamp.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Text(
                    text = log.message,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp)
                )

                if (log.throwable != null) {
                    Text(
                        text = log.throwable.stackTraceToString(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        ),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                            .padding(8.dp)
                            .fillMaxWidth()
                    )
                }
                }
            }
        }
}

private fun LogEntry.toExportText(): String {
    val header = "${timestamp} [$level] $tag: $message"
    return if (throwable != null) "$header\n${throwable.stackTraceToString()}" else header
}

@Composable
private fun getLevelColor(level: LogLevel): Color {
    val scheme = MaterialTheme.colorScheme
    return when (level) {
        LogLevel.DEBUG -> scheme.onSurfaceVariant
        LogLevel.INFO -> scheme.primary
        LogLevel.WARN -> scheme.secondary
        LogLevel.ERROR -> scheme.error
    }
}
