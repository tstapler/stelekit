package dev.stapler.stelekit.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.ui.components.BlockList
import dev.stapler.stelekit.ui.components.EditorCapabilities
import dev.stapler.stelekit.ui.components.EditorToolbar
import dev.stapler.stelekit.ui.components.asLazyKey
import dev.stapler.stelekit.ui.components.SuggestionItem
import dev.stapler.stelekit.ui.components.typedItems
import dev.stapler.stelekit.ui.components.SuggestionNavigatorPanel
import dev.stapler.stelekit.performance.NavigationTracingEffect
import kotlinx.coroutines.CancellationException
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
    searchViewModel: SearchViewModel? = null,
    onSearchPages: (String) -> kotlinx.coroutines.flow.Flow<List<SearchResultItem>> = { kotlinx.coroutines.flow.emptyFlow() },
    suggestionMatcher: AhoCorasickMatcher? = null,
    isLeftHanded: Boolean = false,
    onOpenAnnotationEditor: (imageAnnotationUuid: String) -> Unit = {},
    capabilities: EditorCapabilities = EditorCapabilities(),
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

    if (isDebugMode) {
        val recomposeCount = remember { androidx.compose.runtime.mutableIntStateOf(0) }
        SideEffect { println("[Recompose] JournalsView #${++recomposeCount.intValue}") }
    }

    // Navigator panel state — empty list means closed
    var navigatorSuggestions by remember { mutableStateOf<List<SuggestionItem>>(emptyList()) }
    var navigatorIndex by remember { mutableStateOf(0) }

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        LazyColumn(
            state = listState,
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
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { toolbarHeight = it.height },
        )
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
    suggestionMatcher: AhoCorasickMatcher? = null,
    onNavigateAllSuggestions: ((List<SuggestionItem>) -> Unit)? = null,
    onBlockSelectionChange: ((blockUuid: String, range: IntRange?) -> Unit)? = null,
    onOpenAnnotationEditor: (imageAnnotationUuid: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Journal date header (formatted nicely)
        Text(
            text = formatJournalDate(page.name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 16.dp, top = 8.dp)
        )

        // Blocks content
        if (blocks.isEmpty()) {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAddBlockToPage(page.uuid.value) }
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        text = "Click to write...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                    )
                }
            }
        } else {
            // Sort blocks hierarchically for display
            val sortedBlocks = remember(blocks) { 
                dev.stapler.stelekit.outliner.BlockSorter.sort(blocks)
            }
            
            BlockList(
                blocks = sortedBlocks,
                isDebugMode = isDebugMode,
                editingBlockUuid = editingBlockUuid,
                editingCursorIndex = editingCursorIndex,
                collapsedBlocks = collapsedBlocks,
                selectedBlockUuids = selectedBlockUuids,
                isInSelectionMode = isInSelectionMode,
                onToggleSelect = onToggleSelect,
                onEnterSelectionMode = onEnterSelectionMode,
                onShiftClick = onShiftClick,
                onShiftArrowUp = onShiftArrowUp,
                onShiftArrowDown = onShiftArrowDown,
                onStartEditing = onStartEditing,
                onStopEditing = onStopEditing,
                onContentChange = onContentChange,
                onLinkClick = onLinkClick,
                onNewBlock = onNewBlock,
                onSplitBlock = onSplitBlock,
                onMergeBlock = onMergeBlock,
                onIndent = onIndent,
                onOutdent = onOutdent,
                onMoveUp = onMoveUp,
                onMoveDown = onMoveDown,
                onLoadContent = onLoadContent,
                onBackspace = onBackspace,
                onToggleCollapse = onToggleCollapse,
                onFocusUp = onFocusUp,
                onFocusDown = onFocusDown,
                onSearchPages = onSearchPages,
                formatEvents = formatEvents,
                suggestionMatcher = suggestionMatcher,
                onNavigateAllSuggestions = onNavigateAllSuggestions,
                onBlockSelectionChange = onBlockSelectionChange,
                onOpenAnnotationEditor = onOpenAnnotationEditor,
            )

            // Clickable area below blocks to append new block
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable { onAddBlockToPage(page.uuid.value) }
            )
        }
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
