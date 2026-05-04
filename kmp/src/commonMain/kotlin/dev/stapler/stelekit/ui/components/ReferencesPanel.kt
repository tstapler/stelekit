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
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class PanelUnlinkedEntry(
    val block: Block,
    val targetPageName: String,
    val matchStart: Int,
    val matchEnd: Int,
    val capturedContent: String,
)

private fun blockToEntry(
    block: Block,
    pageName: String,
    matcher: AhoCorasickMatcher?,
): PanelUnlinkedEntry {
    val span = matcher?.findAll(block.content)
        ?.firstOrNull { it.canonicalName.equals(pageName, ignoreCase = true) }
    return PanelUnlinkedEntry(
        block = block,
        targetPageName = pageName,
        matchStart = span?.start ?: 0,
        matchEnd = span?.end ?: block.content.length,
        capturedContent = block.content,
    )
}

/**
 * Panel showing linked and unlinked references to a page.
 * Uses a tab row so linked references and unlinked mentions are browsed independently.
 */
@OptIn(DirectRepositoryWrite::class)
@Composable
fun ReferencesPanel(
    page: Page,
    blockRepository: BlockRepository,
    pageRepository: PageRepository,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    suggestionMatcher: AhoCorasickMatcher? = null,
    writeActor: DatabaseWriteActor? = null,
) {
    val pageSize = 20
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) }

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

    // UUIDs dismissed via Link or Skip in the unlinked tab
    var dismissedUuids by remember { mutableStateOf(setOf<String>()) }

    // Reset all state when navigating to a different page
    LaunchedEffect(page.name) {
        selectedTab = 0
        linkedBlocks = emptyList()
        linkedOffset = 0
        hasMoreLinked = true
        unlinkedBlocks = emptyList()
        unlinkedOffset = 0
        hasMoreUnlinked = true
        dismissedUuids = emptySet()
    }

    LaunchedEffect(page.name, linkedOffset) {
        isLoadingLinked = true
        blockRepository.getLinkedReferences(page.name, limit = pageSize, offset = linkedOffset)
            .collect { result ->
                result.onRight { blocks ->
                    linkedBlocks = if (linkedOffset == 0) blocks else linkedBlocks + blocks
                    hasMoreLinked = blocks.size == pageSize
                    isLoadingLinked = false
                }
            }
    }

    LaunchedEffect(page.name, unlinkedOffset) {
        isLoadingUnlinked = true
        blockRepository.getUnlinkedReferences(page.name, limit = pageSize, offset = unlinkedOffset)
            .collect { result ->
                result.onRight { blocks ->
                    unlinkedBlocks = if (unlinkedOffset == 0) blocks else unlinkedBlocks + blocks
                    hasMoreUnlinked = blocks.size == pageSize
                    isLoadingUnlinked = false
                }
            }
    }

    val unlinkedEntries = remember(unlinkedBlocks, dismissedUuids, suggestionMatcher) {
        unlinkedBlocks
            .filter { it.uuid !in dismissedUuids }
            .map { blockToEntry(it, page.name, suggestionMatcher) }
    }

    val linkedLabel = buildString {
        append("Linked")
        if (linkedBlocks.isNotEmpty() || isLoadingLinked) {
            append(" (${linkedBlocks.size}${if (hasMoreLinked) "+" else ""})")
        }
    }
    val unlinkedLabel = buildString {
        append("Unlinked")
        if (unlinkedEntries.isNotEmpty() || isLoadingUnlinked) {
            append(" (${unlinkedEntries.size}${if (hasMoreUnlinked) "+" else ""})")
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(linkedLabel, style = MaterialTheme.typography.labelMedium) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(unlinkedLabel, style = MaterialTheme.typography.labelMedium) },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (selectedTab) {
            0 -> {
                if (linkedBlocks.isNotEmpty() || isLoadingLinked) {
                    ReferenceSection(
                        title = "Linked References",
                        blocks = linkedBlocks,
                        pageRepository = pageRepository,
                        onLinkClick = onLinkClick,
                        initiallyExpanded = true,
                        hasMore = hasMoreLinked,
                        isLoading = isLoadingLinked,
                        onLoadMore = { linkedOffset += pageSize },
                    )
                } else {
                    EmptyTabMessage("No linked references to this page yet.")
                }
            }
            1 -> {
                if (unlinkedEntries.isNotEmpty() || isLoadingUnlinked) {
                    UnlinkedMentionsSection(
                        entries = unlinkedEntries,
                        pageRepository = pageRepository,
                        onLinkClick = onLinkClick,
                        suggestionMatcher = suggestionMatcher,
                        hasMore = hasMoreUnlinked,
                        isLoading = isLoadingUnlinked,
                        onLoadMore = { unlinkedOffset += pageSize },
                        onAccept = { entry ->
                            scope.launch {
                                val liveBlock = blockRepository.getBlockByUuid(entry.block.uuid)
                                    .first().getOrNull()
                                val currentContent = liveBlock?.content ?: entry.block.content
                                if (entry.capturedContent == currentContent) {
                                    val safeEnd = entry.matchEnd.coerceAtMost(currentContent.length)
                                    val safeStart = entry.matchStart.coerceIn(0, safeEnd)
                                    val newContent =
                                        currentContent.substring(0, safeStart) +
                                            "[[${entry.targetPageName}]]" +
                                            currentContent.substring(safeEnd)
                                    val updated = (liveBlock ?: entry.block).copy(content = newContent)
                                    val result = writeActor?.saveBlock(updated)
                                        ?: blockRepository.saveBlock(updated)
                                    if (result.isRight()) {
                                        dismissedUuids = dismissedUuids + entry.block.uuid
                                    }
                                } else {
                                    // Block was edited since the suggestion was captured — dismiss silently
                                    dismissedUuids = dismissedUuids + entry.block.uuid
                                }
                            }
                        },
                        onReject = { entry ->
                            dismissedUuids = dismissedUuids + entry.block.uuid
                        },
                    )
                } else if (!isLoadingUnlinked) {
                    EmptyTabMessage("No unlinked mentions of \"${page.name}\" found.")
                }
            }
        }
    }
}

@Composable
private fun EmptyTabMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UnlinkedMentionsSection(
    entries: List<PanelUnlinkedEntry>,
    pageRepository: PageRepository,
    onLinkClick: (String) -> Unit,
    suggestionMatcher: AhoCorasickMatcher?,
    hasMore: Boolean,
    isLoading: Boolean,
    onLoadMore: () -> Unit,
    onAccept: (PanelUnlinkedEntry) -> Unit,
    onReject: (PanelUnlinkedEntry) -> Unit,
) {
    val linkColor = MaterialTheme.colorScheme.primary

    Column {
        entries.forEach { entry ->
            UnlinkedMentionCard(
                entry = entry,
                pageRepository = pageRepository,
                onLinkClick = onLinkClick,
                linkColor = linkColor,
                suggestionMatcher = suggestionMatcher,
                onAccept = { onAccept(entry) },
                onReject = { onReject(entry) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (hasMore) {
            Button(
                onClick = onLoadMore,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Load More", style = MaterialTheme.typography.labelMedium)
            }
        } else if (isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun UnlinkedMentionCard(
    entry: PanelUnlinkedEntry,
    pageRepository: PageRepository,
    onLinkClick: (String) -> Unit,
    linkColor: androidx.compose.ui.graphics.Color,
    suggestionMatcher: AhoCorasickMatcher?,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    var sourcePage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.block.pageUuid) {
        sourcePage = pageRepository.getPageByUuid(entry.block.pageUuid).first().getOrNull()?.name
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            sourcePage?.let { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelLarge,
                    color = linkColor,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable { onLinkClick(name) }
                        .padding(bottom = 6.dp),
                )
            }

            val annotated = parseMarkdownWithStyling(
                text = entry.block.content,
                linkColor = linkColor,
                textColor = MaterialTheme.colorScheme.onSurface,
                suggestionSpans = listOf(
                    AhoCorasickMatcher.MatchSpan(
                        entry.matchStart,
                        entry.matchEnd,
                        entry.targetPageName,
                    )
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

        AnimatedVisibility(visible = expanded) {
            Column {
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
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = 8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
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
                        onClick = {},
                        modifier = Modifier.weight(1f),
                        suggestionMatcher = suggestionMatcher,
                    )
                }
            }
        }
    }
}
