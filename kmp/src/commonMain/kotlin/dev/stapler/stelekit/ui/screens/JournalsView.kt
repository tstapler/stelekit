package dev.stapler.stelekit.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.tags.BulkScanState
import dev.stapler.stelekit.tags.JournalScanEntry
import dev.stapler.stelekit.tags.TagSuggestionState
import dev.stapler.stelekit.tags.TagSuggestionViewModel
import dev.stapler.stelekit.tags.WikiLinkExtractor
import dev.stapler.stelekit.ui.components.tags.SuggestionBottomSheet
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.model.SectionId
import dev.stapler.stelekit.ui.components.PageContent
import dev.stapler.stelekit.ui.components.EditorCapabilities
import dev.stapler.stelekit.ui.components.EditorToolbar
import dev.stapler.stelekit.ui.components.LocalGraphRootPath
import dev.stapler.stelekit.ui.components.asLazyKey
import dev.stapler.stelekit.ui.components.SuggestionItem
import dev.stapler.stelekit.ui.components.typedItems
import dev.stapler.stelekit.ui.components.SuggestionNavigatorPanel
import dev.stapler.stelekit.performance.NavigationTracingEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate

/**
 * Journals view that displays multiple journal entries with their content
 * in a scrollable list, similar to Logseq's journals page.
 * 
 * Updated to use UUID-native storage.
 */
@Composable
fun JournalsView(
    viewModel: JournalsViewModel,
    isDebugMode: Boolean,
    onLinkClick: (String) -> Unit,
    graphPath: String = "",
    searchViewModel: SearchViewModel? = null,
    onSearchPages: (String) -> kotlinx.coroutines.flow.Flow<List<SearchResultItem>> = { kotlinx.coroutines.flow.emptyFlow() },
    suggestionMatcher: AhoCorasickMatcher? = null,
    isLeftHanded: Boolean = false,
    onOpenAnnotationEditor: (imageAnnotationUuid: String) -> Unit = {},
    capabilities: EditorCapabilities = EditorCapabilities(),
    tagSuggestionViewModel: TagSuggestionViewModel? = null,
    currentGraphId: String? = null,
    conflictFilePaths: Set<String> = emptySet(),
    /**
     * ux.md (i)/criterion 14: true while `AppState.diskConflict` is non-null. Passed straight
     * through to [EditorToolbar]/`MobileBlockToolbar`'s Undo/Redo — must be a live value (see
     * `ScreenRouter`'s `appState.diskConflict != null` call site) so Undo/Redo re-enable
     * automatically once the conflict resolves.
     */
    hasDiskConflictPending: Boolean = false,
    onExportEntry: ((page: Page, blocks: List<Block>, formatId: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    NavigationTracingEffect("Journals")
    val uiState by viewModel.uiState.collectAsState()
    val allBlocks by viewModel.blocks.collectAsState()
    val loadingPageUuids by viewModel.loadingPageUuids.collectAsState()
    val editingBlockUuid by viewModel.editingBlockUuid.collectAsState()
    val editingCursorIndex by viewModel.editingCursorIndex.collectAsState()
    val collapsedBlockUuids by viewModel.collapsedBlockUuids.collectAsState()
    val selectedBlockUuids by viewModel.selectedBlockUuids.collectAsState()
    val isInSelectionMode by viewModel.isInSelectionMode.collectAsState()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    var toolbarHeight by remember { mutableStateOf(0) }
    val tagSuggestionState by tagSuggestionViewModel?.state?.collectAsState()
        ?: remember { mutableStateOf(TagSuggestionState.Idle) }
    val scanState by tagSuggestionViewModel?.scanState?.collectAsState()
        ?: remember { mutableStateOf<BulkScanState>(BulkScanState.Idle) }

    LaunchedEffect(scanState) {
        if (scanState is BulkScanState.Complete) {
            delay(3_000)
            tagSuggestionViewModel?.resetScan()
        }
    }

    if (isDebugMode) {
        val recomposeCount = remember { androidx.compose.runtime.mutableIntStateOf(0) }
        SideEffect { println("[Recompose] JournalsView #${++recomposeCount.intValue}") }
    }

    // Navigator panel state — empty list means closed
    var navigatorSuggestions by remember { mutableStateOf<List<SuggestionItem>>(emptyList()) }
    var navigatorIndex by remember { mutableStateOf(0) }

    // GAP-010 (Story D.3.1): true while any page's BlockList has an active block drag — used to
    // suspend this LazyColumn's own scrolling so blockBounds (cached relative to each BlockList's
    // internal Column) can never desynchronize from the drop-target computation mid-drag.
    val isAnyBlockDragging = remember { mutableStateOf(false) }

    // Infinite scroll detection
    val shouldLoadMore = remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItemsNumber = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 1

            lastVisibleItemIndex > (totalItemsNumber - 2)
        }
    }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) {
            viewModel.loadMore()
        }
    }

    val toolbarHeightDp = with(LocalDensity.current) { toolbarHeight.toDp() }

    CompositionLocalProvider(LocalGraphRootPath provides graphPath.ifEmpty { null }) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        LazyColumn(
            state = listState,
            // GAP-010: suspend scroll for the duration of any active block drag (see
            // isAnyBlockDragging's doc above and BlockList.onDragStateChange).
            userScrollEnabled = !isAnyBlockDragging.value,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                    })
                },
            contentPadding = PaddingValues(top = 16.dp, bottom = toolbarHeightDp + 8.dp)
        ) {
            if (tagSuggestionViewModel != null && tagSuggestionViewModel.hasLlmProvider) {
                item(key = "scan_banner") {
                    ScanBanner(
                        scanState = scanState,
                        enabled = uiState.pages.isNotEmpty(),
                        onScan = {
                            val entries = uiState.pages.mapNotNull { page ->
                                val blocks = allBlocks[page.uuid.value] ?: return@mapNotNull null
                                val firstBlock = blocks.firstOrNull { it.content.isNotBlank() }
                                    ?: return@mapNotNull null
                                JournalScanEntry(
                                    pageUuid = page.uuid.value,
                                    targetBlockUuid = firstBlock.uuid.value,
                                    contentSnapshot = firstBlock.content,
                                    fullContent = blocks.take(20).joinToString("\n") { it.content }.take(500),
                                    alreadyLinked = WikiLinkExtractor.extractPageNames(
                                        blocks.joinToString("\n") { it.content }
                                    ),
                                    graphId = currentGraphId ?: "",
                                )
                            }
                            tagSuggestionViewModel.scanEntries(entries)
                        },
                        onCancel = { tagSuggestionViewModel.cancelScan() },
                    )
                }
            }
            typedItems(
                items = uiState.pages,
                key = { page -> page.uuid.asLazyKey() },
                contentType = { "journal_entry" }
            ) { page ->
                val blockList = allBlocks[page.uuid.value] ?: emptyList()

                JournalEntry(
                    page = page,
                    blocks = blockList,
                    isLoading = !page.isContentLoaded || page.uuid.value in loadingPageUuids,
                    isDebugMode = isDebugMode,
                    hasConflict = page.filePath in conflictFilePaths,
                    onTitleClick = { onLinkClick(page.name) },
                    onExport = onExportEntry?.let { export -> { formatId -> export(page, blockList, formatId) } },
                    onSuggestTags = if (tagSuggestionViewModel != null) {
                        {
                            val firstBlock = blockList.firstOrNull { it.content.isNotBlank() }
                                ?: blockList.firstOrNull()
                            if (firstBlock != null) {
                                val content = blockList.take(20).joinToString("\n") { it.content }.take(500)
                                val linked = WikiLinkExtractor.extractPageNames(blockList.joinToString("\n") { it.content })
                                tagSuggestionViewModel.requestSuggestions(firstBlock.uuid.value, content, linked)
                            }
                        }
                    } else null,
                    editingBlockUuid = editingBlockUuid?.value,
                    editingCursorIndex = editingCursorIndex,
                    collapsedBlocks = collapsedBlockUuids,
                    selectedBlockUuids = selectedBlockUuids,
                    isInSelectionMode = isInSelectionMode,
                    onToggleSelect = { blockUuid -> viewModel.toggleBlockSelection(BlockUuid(blockUuid)) },
                    onEnterSelectionMode = { blockUuid -> viewModel.enterSelectionMode(BlockUuid(blockUuid)) },
                    onShiftClick = { blockUuid -> viewModel.extendSelectionTo(BlockUuid(blockUuid)) },
                    onShiftArrowUp = { viewModel.extendSelectionByOne(up = true) },
                    onShiftArrowDown = { viewModel.extendSelectionByOne(up = false) },
                    onStartEditing = { blockUuid -> viewModel.requestEditBlock(BlockUuid(blockUuid)) },
                    onStopEditing = { blockUuid -> viewModel.stopEditingBlock(BlockUuid(blockUuid)) },
                    onContentChange = { blockUuid, newContent, version ->
                        viewModel.updateBlockContent(BlockUuid(blockUuid), newContent, version)
                    },
                    onLinkClick = onLinkClick,
                    onNewBlock = { uuid -> viewModel.addNewBlock(BlockUuid(uuid)) },
                    onSplitBlock = { uuid, pos -> viewModel.splitBlock(BlockUuid(uuid), pos) },
                    onMergeBlock = { uuid -> viewModel.mergeBlock(BlockUuid(uuid)) },
                    onIndent = { blockUuid ->
                        viewModel.indentBlock(BlockUuid(blockUuid))
                    },
                    onOutdent = { blockUuid ->
                        viewModel.outdentBlock(BlockUuid(blockUuid))
                    },
                    onMoveUp = { blockUuid ->
                        viewModel.moveBlockUp(BlockUuid(blockUuid))
                    },
                    onMoveDown = { blockUuid ->
                        viewModel.moveBlockDown(BlockUuid(blockUuid))
                    },
                    onLoadContent = { pageUuid -> viewModel.loadPageContent(PageUuid(pageUuid)) },
                    onBackspace = { blockUuid -> viewModel.handleBackspace(BlockUuid(blockUuid)) },
                    onAddBlockToPage = { pageUuid -> viewModel.addBlockToPage(PageUuid(pageUuid)) },
                    onToggleCollapse = { blockUuid -> viewModel.toggleBlockCollapse(BlockUuid(blockUuid)) },
                    onFocusUp = { blockUuid -> viewModel.focusPreviousBlock(BlockUuid(blockUuid)) },
                    onFocusDown = { blockUuid -> viewModel.focusNextBlock(BlockUuid(blockUuid)) },
                    onSearchPages = onSearchPages,
                    formatEvents = viewModel.formatEvents,
                    todoToggleEvents = viewModel.todoToggleEvents,
                    onDragStateChange = { isDragging -> isAnyBlockDragging.value = isDragging },
                    suggestionMatcher = suggestionMatcher,
                    onNavigateAllSuggestions = { suggestions ->
                        navigatorSuggestions = suggestions
                        navigatorIndex = 0
                    },
                    onBlockSelectionChange = { blockUuid, range ->
                        viewModel.updateEditingSelection(
                            if (blockUuid == editingBlockUuid?.value) range else null
                        )
                    },
                    onOpenAnnotationEditor = onOpenAnnotationEditor,
                )

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
            
            // Loading indicator at the bottom
            item {
                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }

        // Suggestion navigator panel — shown above toolbar when active
        if (navigatorSuggestions.isNotEmpty()) {
            SuggestionNavigatorPanel(
                suggestions = navigatorSuggestions,
                currentIndex = navigatorIndex,
                onLink = {
                    val item = navigatorSuggestions[navigatorIndex]
                    val block = allBlocks.values.flatten().find { it.uuid.value == item.blockUuid }
                    if (block != null) {
                        val safeEnd = item.contentEnd.coerceAtMost(block.content.length)
                        val safeStart = item.contentStart.coerceIn(0, safeEnd)
                        val newContent = block.content.substring(0, safeStart) +
                            "[[${item.canonicalName}]]" +
                            block.content.substring(safeEnd)
                        viewModel.updateBlockContent(BlockUuid(item.blockUuid), newContent, block.version)
                    }
                    val updated = navigatorSuggestions.toMutableList().also { it.removeAt(navigatorIndex) }
                    navigatorSuggestions = updated
                    if (updated.isNotEmpty()) navigatorIndex = navigatorIndex.coerceAtMost(updated.size - 1)
                },
                onSkip = {
                    val updated = navigatorSuggestions.toMutableList().also { it.removeAt(navigatorIndex) }
                    navigatorSuggestions = updated
                    if (updated.isNotEmpty()) navigatorIndex = navigatorIndex.coerceAtMost(updated.size - 1)
                },
                onPrevious = { if (navigatorIndex > 0) navigatorIndex-- },
                onNext = { if (navigatorIndex < navigatorSuggestions.size - 1) navigatorIndex++ },
                onClose = { navigatorSuggestions = emptyList() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        EditorToolbar(
            blockStateManager = viewModel.blockStateManager,
            capabilities = capabilities,
            searchViewModel = searchViewModel,
            isLeftHanded = isLeftHanded,
            hasDiskConflictPending = hasDiskConflictPending,
            onSuggestTags = if (tagSuggestionViewModel != null) { blockUuid, content ->
                if (content.isNotBlank()) {
                    val alreadyLinked = WikiLinkExtractor.extractPageNames(content)
                    tagSuggestionViewModel.requestSuggestions(
                        blockUuid = blockUuid,
                        blockContent = content,
                        alreadyLinkedTerms = alreadyLinked,
                    )
                }
            } else null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { toolbarHeight = it.height },
        )

        if (tagSuggestionViewModel != null) {
            SuggestionBottomSheet(
                state = tagSuggestionState,
                onAcceptTag = { uuid, term ->
                    viewModel.blockStateManager.appendToBlock(
                        dev.stapler.stelekit.model.BlockUuid(uuid), " [[$term]]"
                    )
                },
                onDismiss = { tagSuggestionViewModel.dismiss() },
            )
        }
    }
    } // CompositionLocalProvider(LocalGraphRootPath)
}

@Composable
private fun ScanBanner(
    scanState: BulkScanState,
    enabled: Boolean,
    onScan: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (scanState) {
        is BulkScanState.Idle -> if (enabled) {
            TextButton(onClick = onScan, modifier = modifier.fillMaxWidth()) {
                Text("Scan entries for tag suggestions")
            }
        }
        is BulkScanState.Scanning -> Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Scanning entries… (${scanState.done}/${scanState.total})",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            LinearProgressIndicator(
                progress = {
                    if (scanState.total > 0) scanState.done.toFloat() / scanState.total else 0f
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is BulkScanState.Complete -> {
            val msg = if (scanState.found > 0)
                "Found ${scanState.found} tag suggestion${if (scanState.found != 1) "s" else ""} — opening review…"
            else
                "No new tag suggestions found"
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
    }
}

/**
 * A single journal entry with its date header and blocks
 */
@Composable
private fun JournalEntry(
    page: Page,
    blocks: List<Block>,
    isLoading: Boolean,
    isDebugMode: Boolean,
    hasConflict: Boolean = false,
    onTitleClick: () -> Unit,
    onExport: ((formatId: String) -> Unit)? = null,
    onSuggestTags: (() -> Unit)? = null,
    editingBlockUuid: String?,
    editingCursorIndex: Int?,
    collapsedBlocks: Set<String>,
    selectedBlockUuids: Set<String> = emptySet(),
    isInSelectionMode: Boolean = false,
    onToggleSelect: (String) -> Unit = {},
    onEnterSelectionMode: (String) -> Unit = {},
    onShiftClick: (String) -> Unit = {},
    onShiftArrowUp: () -> Unit = {},
    onShiftArrowDown: () -> Unit = {},
    onStartEditing: (String) -> Unit,
    onStopEditing: (blockUuid: String) -> Unit,
    onContentChange: (String, String, Long) -> Unit,
    onLinkClick: (String) -> Unit,
    onNewBlock: (String) -> Unit,
    onSplitBlock: (String, Int) -> Unit,
    onMergeBlock: (String) -> Unit,
    onIndent: (String) -> Unit,
    onOutdent: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onLoadContent: (String) -> Unit,
    onBackspace: (String) -> Unit,
    onAddBlockToPage: (String) -> Unit,
    onToggleCollapse: (String) -> Unit,
    onFocusUp: (String) -> Unit,
    onFocusDown: (String) -> Unit,
    onSearchPages: (String) -> kotlinx.coroutines.flow.Flow<List<SearchResultItem>> = { kotlinx.coroutines.flow.emptyFlow() },
    formatEvents: kotlinx.coroutines.flow.SharedFlow<FormatAction>? = null,
    todoToggleEvents: kotlinx.coroutines.flow.SharedFlow<Unit>? = null,
    onDragStateChange: (Boolean) -> Unit = {},
    suggestionMatcher: AhoCorasickMatcher? = null,
    onNavigateAllSuggestions: ((List<SuggestionItem>) -> Unit)? = null,
    onBlockSelectionChange: ((blockUuid: String, range: IntRange?) -> Unit)? = null,
    onOpenAnnotationEditor: (imageAnnotationUuid: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Journal date header with optional conflict indicator and overflow menu
        var menuExpanded by remember { mutableStateOf(false) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp, top = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTitleClick() }
            ) {
                Text(
                    text = formatJournalDate(page.name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                val sid = page.sectionId
                if (sid is SectionId.Named) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "[${sid.id}]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (hasConflict) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Page modified on disk — tap to review",
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (onExport != null || onSuggestTags != null) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Entry options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        if (onExport != null) {
                            listOf(
                                "markdown" to "Copy as Markdown",
                                "plain-text" to "Copy as Plain Text",
                                "html" to "Copy as HTML",
                                "json" to "Copy as JSON",
                            ).forEach { (formatId, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { menuExpanded = false; onExport(formatId) }
                                )
                            }
                        }
                        if (onSuggestTags != null) {
                            if (onExport != null) HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Suggest tags for entry") },
                                onClick = { menuExpanded = false; onSuggestTags() }
                            )
                        }
                    }
                }
            }
        }

        // Blocks content
        PageContent(
            blocks = blocks,
            isLoading = isLoading,
            isDebugMode = isDebugMode,
            editingBlockUuid = editingBlockUuid,
            editingCursorIndex = editingCursorIndex,
            collapsedBlocks = collapsedBlocks,
            selectedBlockUuids = selectedBlockUuids,
            isInSelectionMode = isInSelectionMode,
            suggestionMatcher = suggestionMatcher,
            formatEvents = formatEvents,
            onAddBlockToPage = { onAddBlockToPage(page.uuid.value) },
            onStartEditing = onStartEditing,
            onStopEditing = onStopEditing,
            onContentChange = onContentChange,
            onLinkClick = onLinkClick,
            onNewBlock = onNewBlock,
            onSplitBlock = onSplitBlock,
            onMergeBlock = onMergeBlock,
            onBackspace = onBackspace,
            onLoadContent = onLoadContent,
            onToggleCollapse = onToggleCollapse,
            onIndent = onIndent,
            onOutdent = onOutdent,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onFocusUp = onFocusUp,
            onFocusDown = onFocusDown,
            onToggleSelect = onToggleSelect,
            onEnterSelectionMode = onEnterSelectionMode,
            onShiftClick = onShiftClick,
            onShiftArrowUp = onShiftArrowUp,
            onShiftArrowDown = onShiftArrowDown,
            onSearchPages = onSearchPages,
            onNavigateAllSuggestions = onNavigateAllSuggestions,
            onBlockSelectionChange = onBlockSelectionChange,
            onOpenAnnotationEditor = onOpenAnnotationEditor,
        )
    }
}

/**
 * Format journal page name to a nicer date format
 * Input: "2026_01_21" or "2026-01-21"
 * Output: "Wednesday, January 21, 2026"
 */
private fun formatJournalDate(pageName: String): String {
    return try {
        val normalized = pageName.replace("_", "-")
        val date = LocalDate.parse(normalized)
        val dayOfWeek = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        val month = date.month.name.lowercase().replaceFirstChar { it.uppercase() }
        "$dayOfWeek, $month ${date.dayOfMonth}, ${date.year}"
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        pageName.replace("_", "-") // fallback to ISO format
    }
}
