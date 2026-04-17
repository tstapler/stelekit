package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
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
import kotlinx.coroutines.launch

@Composable
fun LogDashboard(
    modifier: Modifier = Modifier
) {
    val logs by LogManager.logs.collectAsState()
    var filterLevel by remember { mutableStateOf<LogLevel?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

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
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "App Logs",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )

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

            // Search
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search logs...") },
                modifier = Modifier.width(200.dp),
                singleLine = true
            )

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

            // Clear
            IconButton(onClick = { LogManager.clearLogs() }) {
                Icon(Icons.Default.Delete, contentDescription = "Clear Logs")
            }
        }

        HorizontalDivider()

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
