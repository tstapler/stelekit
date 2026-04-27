package dev.stapler.stelekit.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.performance.NavigationTracingEffect
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.ui.Screen
import dev.stapler.stelekit.ui.components.parseMarkdownWithStyling

@Composable
fun GlobalUnlinkedReferencesScreen(
    pageRepository: PageRepository,
    blockRepository: BlockRepository,
    writeActor: DatabaseWriteActor?,
    graphPath: String,
    suggestionMatcher: AhoCorasickMatcher?,
    onNavigateTo: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationTracingEffect("GlobalUnlinkedReferences")
    val viewModel = remember {
        GlobalUnlinkedReferencesViewModel(
            pageRepository = pageRepository,
            blockRepository = blockRepository,
            writeActor = writeActor,
            matcher = suggestionMatcher,
        )
    }

    // Cancel all in-flight searches when the screen leaves composition
    DisposableEffect(Unit) {
        onDispose { viewModel.cancel() }
    }

    LaunchedEffect(Unit) {
        viewModel.loadInitial()
    }

    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    // Trigger loadMore automatically when within 3 items of the list end
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = state.results.size
            total > 0 && lastVisible >= total - 3 && state.hasMore && !state.isLoading
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    val linkColor = MaterialTheme.colorScheme.primary

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Unlinked References",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        when {
            state.isLoading && state.results.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            !state.isLoading && state.results.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No unlinked references found across all pages.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> {
                val countLabel = if (state.hasMore) {
                    "${state.results.size}+ unlinked references"
                } else {
                    "${state.results.size} unlinked reference${if (state.results.size == 1) "" else "s"}"
                }
                Text(
                    text = countLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = state.results,
                        key = { "${it.block.uuid}::${it.targetPageName}::${it.matchStart}" },
                    ) { entry ->
                        UnlinkedRefCard(
                            entry = entry,
                            suggestionMatcher = suggestionMatcher,
                            linkColor = linkColor,
                            onAccept = { viewModel.acceptSuggestion(entry) },
                            onReject = { viewModel.rejectSuggestion(entry) },
                        )
                    }

                    if (state.isLoading) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }

        state.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        state.successMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun UnlinkedRefCard(
    entry: UnlinkedRefEntry,
    suggestionMatcher: AhoCorasickMatcher?,
    linkColor: Color,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = entry.targetPageName,
                style = MaterialTheme.typography.labelLarge,
                color = linkColor,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            val annotated = parseMarkdownWithStyling(
                text = entry.block.content,
                linkColor = linkColor,
                textColor = MaterialTheme.colorScheme.onSurface,
                suggestionSpans = listOf(
                    AhoCorasickMatcher.MatchSpan(entry.matchStart, entry.matchEnd, entry.targetPageName)
                ),
                suggestionColor = linkColor.copy(alpha = 0.7f),
            )
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Row {
                FilledTonalButton(onClick = onAccept) {
                    Text("Link")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onReject) {
                    Text("Skip")
                }
            }
        }
    }
}
