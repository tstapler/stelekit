package dev.stapler.stelekit.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.ui.screens.PreviewPanelContent
import kotlinx.coroutines.flow.Flow

@Composable
fun PreviewPanel(
    content: PreviewPanelContent,
    loadPageBlocks: (String) -> Flow<Either<DomainError, List<Block>>>,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.padding(16.dp)) {
        when (content) {
            is PreviewPanelContent.Empty -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Select a result to preview",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            is PreviewPanelContent.PagePreview -> {
                val blocksResult by loadPageBlocks(content.pageUuid).collectAsState(initial = null)
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = content.pageTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    HorizontalDivider()
                    when {
                        blocksResult == null -> SearchSkeletonList(rowCount = 4)
                        blocksResult is Either.Left -> Text(
                            text = "Could not load preview",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        else -> {
                            val blocks = (blocksResult as Either.Right<List<Block>>).value
                            if (blocks.isEmpty()) {
                                Text(
                                    text = "No content",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(blocks.take(10)) { block ->
                                        if (block.content.isNotBlank()) {
                                            Text(
                                                text = block.content,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(
                                                        start = (block.level * 12).dp,
                                                        top = 4.dp,
                                                        bottom = 4.dp
                                                    )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            is PreviewPanelContent.BlockPreview -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = content.pageTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = content.blockSnippet,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}
