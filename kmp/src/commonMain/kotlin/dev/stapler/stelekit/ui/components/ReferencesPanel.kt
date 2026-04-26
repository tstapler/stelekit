package dev.stapler.stelekit.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.flow.first

/**
 * Panel showing linked and unlinked references to a page.
 * Similar to Logseq's backlinks panel.
 * 
 * Updated to use UUID-native storage.
 */
@Composable
fun ReferencesPanel(
    page: Page,
    blockRepository: BlockRepository,
    pageRepository: PageRepository,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    suggestionMatcher: AhoCorasickMatcher? = null,
) {
    val pageSize = 20
    
    // Linked references state
    var linkedOffset by remember { mutableIntStateOf(0) }
    var linkedBlocks by remember { mutableStateOf<List<Block>>(emptyList()) }
    var hasMoreLinked by remember { mutableStateOf(true) }
    var isLoadingLinked by remember { mutableStateOf(false) }

    // Unlinked references state
    var unlinkedOffset by remember { mutableIntStateOf(0) }
    var unlinkedBlocks by remember { mutableStateOf<List<Block>>(emptyList()) }
    var hasMoreUnlinked by remember { mutableStateOf(true) }
    var isLoadingUnlinked by remember { mutableStateOf(false) }

    // Initial load
    LaunchedEffect(page.name) {
        linkedBlocks = emptyList()
        linkedOffset = 0
        hasMoreLinked = true
        
        unlinkedBlocks = emptyList()
        unlinkedOffset = 0
        hasMoreUnlinked = true
    }

    // Actually using LaunchedEffect to trigger loads when offset changes
    LaunchedEffect(page.name, linkedOffset) {
        isLoadingLinked = true
        blockRepository.getLinkedReferences(page.name, limit = pageSize, offset = linkedOffset).collect { result ->
            result.onRight { blocks ->
                linkedBlocks = if (linkedOffset == 0) blocks else linkedBlocks + blocks
                hasMoreLinked = blocks.size == pageSize
                isLoadingLinked = false
            }
        }
    }

    LaunchedEffect(page.name, unlinkedOffset) {
        isLoadingUnlinked = true
        blockRepository.getUnlinkedReferences(page.name, limit = pageSize, offset = unlinkedOffset).collect { result ->
            result.onRight { blocks ->
                unlinkedBlocks = if (unlinkedOffset == 0) blocks else unlinkedBlocks + blocks
                hasMoreUnlinked = blocks.size == pageSize
                isLoadingUnlinked = false
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Linked References Section
        if (linkedBlocks.isNotEmpty() || isLoadingLinked) {
            ReferenceSection(
                title = "Linked References",
                blocks = linkedBlocks,
                pageRepository = pageRepository,
                onLinkClick = onLinkClick,
                initiallyExpanded = true,
                hasMore = hasMoreLinked,
                isLoading = isLoadingLinked,
                onLoadMore = { linkedOffset += pageSize }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Unlinked References Section
        if (unlinkedBlocks.isNotEmpty() || isLoadingUnlinked) {
            ReferenceSection(
                title = "Unlinked References",
                blocks = unlinkedBlocks,
                pageRepository = pageRepository,
                onLinkClick = onLinkClick,
                initiallyExpanded = false,
                hasMore = hasMoreUnlinked,
                isLoading = isLoadingUnlinked,
                onLoadMore = { unlinkedOffset += pageSize },
                suggestionMatcher = suggestionMatcher,
            )
        }
    }
}

@Composable
private fun ReferenceSection(
    title: String,
    blocks: List<Block>,
    pageRepository: PageRepository,
    onLinkClick: (String) -> Unit,
    initiallyExpanded: Boolean = true,
    hasMore: Boolean = false,
    isLoading: Boolean = false,
    onLoadMore: () -> Unit = {},
    suggestionMatcher: AhoCorasickMatcher? = null,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Column {
        // Section Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$title (${blocks.size}${if (hasMore) "+" else ""})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            if (isLoading) {
                Spacer(modifier = Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            }
        }

        // Section Content
        AnimatedVisibility(visible = expanded) {
            Column {
                // Group blocks by page for display
                val blocksByPage = blocks.groupBy { it.pageUuid }

                blocksByPage.forEach { (pageUuid, pageBlocks) ->
                    ReferencePageGroup(
                        pageUuid = pageUuid,
                        blocks = pageBlocks,
                        pageRepository = pageRepository,
                        onLinkClick = onLinkClick,
                        suggestionMatcher = suggestionMatcher,
                    )
                }
                
                if (hasMore) {
                    Button(
                        onClick = onLoadMore,
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Load More", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferencePageGroup(
    pageUuid: String,
    blocks: List<Block>,
    pageRepository: PageRepository,
    onLinkClick: (String) -> Unit,
    suggestionMatcher: AhoCorasickMatcher? = null,
) {
    // Look up the page name
    var pageName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pageUuid) {
        val pageResult = pageRepository.getPageByUuid(pageUuid).first()
        pageName = pageResult.getOrNull()?.name
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Page name header (clickable)
            pageName?.let { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable { onLinkClick(name) }
                        .padding(bottom = 8.dp)
                )
            }

            // Blocks from this page
            blocks.forEach { block ->
                Row(
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    WikiLinkText(
                        text = block.content,
                        textColor = MaterialTheme.colorScheme.onSurface,
                        linkColor = MaterialTheme.colorScheme.primary,
                        onLinkClick = onLinkClick,
                        onClick = { /* Could open this specific block */ },
                        modifier = Modifier.weight(1f),
                        suggestionMatcher = suggestionMatcher,
                    )
                }
            }
        }
    }
}
