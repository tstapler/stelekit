package dev.stapler.stelekit.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.outliner.BlockSorter
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.performance.NavigationTracingEffect
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.ui.state.BlockStateManager
import dev.stapler.stelekit.ui.StelekitViewModel
import androidx.compose.runtime.CompositionLocalProvider
import dev.stapler.stelekit.ui.components.BlockList
import dev.stapler.stelekit.ui.components.EditorCapabilities
import dev.stapler.stelekit.ui.components.EditorToolbar
import dev.stapler.stelekit.ui.components.LocalGraphRootPath
import dev.stapler.stelekit.ui.components.parseMarkdownWithStyling
import dev.stapler.stelekit.ui.components.pageDropTarget
import dev.stapler.stelekit.ui.components.ReferencesPanel
import dev.stapler.stelekit.ui.components.SuggestionItem
import dev.stapler.stelekit.ui.components.SuggestionNavigatorPanel
import dev.stapler.stelekit.ui.i18n.t
import dev.stapler.stelekit.tags.TagSuggestionViewModel
import dev.stapler.stelekit.tags.TagSuggestionState
import dev.stapler.stelekit.tags.WikiLinkExtractor
import dev.stapler.stelekit.ui.components.tags.SuggestionBottomSheet

/**
 * Page view screen.
 * Uses BlockStateManager as the single source of truth for block state.
 */
@Composable
fun PageView(
    page: Page,
    blockRepository: BlockRepository,
    pageRepository: PageRepository,
    blockStateManager: BlockStateManager,
    currentGraphPath: String,
    onToggleFavorite: (Page) -> Unit,
    onRefresh: () -> Unit,
    onLinkClick: (String) -> Unit,
    viewModel: StelekitViewModel,
    searchViewModel: SearchViewModel? = null,
    writeActor: DatabaseWriteActor? = null,
    isDebugMode: Boolean = false,
    isLeftHanded: Boolean = false,
    capabilities: EditorCapabilities = EditorCapabilities(),
    onReloadFromDisk: (() -> Unit)? = null,
    isExporting: Boolean = false,
    tagSuggestionViewModel: TagSuggestionViewModel? = null,
) {
    NavigationTracingEffect("PageView/${page.name}")
    val focusManager = LocalFocusManager.current
    var toolbarHeight by remember { mutableStateOf(0) }

    if (isDebugMode) {
        val recomposeCount = remember { androidx.compose.runtime.mutableIntStateOf(0) }
        androidx.compose.runtime.SideEffect { println("[Recompose] PageView #${++recomposeCount.intValue}") }
    }

    // All state from BlockStateManager
    val allBlocks by blockStateManager.blocks.collectAsState()
    val editingBlockUuid by blockStateManager.editingBlockUuid.collectAsState()
    val editingCursorIndex by blockStateManager.editingCursorIndex.collectAsState()
    val collapsedBlockUuids by blockStateManager.collapsedBlockUuids.collectAsState()
    val selectedBlockUuids by blockStateManager.selectedBlockUuids.collectAsState()
    val isInSelectionMode by blockStateManager.isInSelectionMode.collectAsState()
    val loadingPageUuids by blockStateManager.loadingPageUuids.collectAsState()
    val suggestionMatcher by viewModel.suggestionMatcher.collectAsState()

    val blockClipboard by blockStateManager.blockClipboard.collectAsState()

    val tagSuggestionState by tagSuggestionViewModel?.state?.collectAsState()
        ?: remember { mutableStateOf(TagSuggestionState.Idle) }

    // Navigator panel state — empty list means closed
    var navigatorSuggestions by remember { mutableStateOf<List<SuggestionItem>>(emptyList()) }
    var navigatorIndex by remember { mutableStateOf(0) }

    val blocks = allBlocks[page.uuid.value] ?: emptyList()

    // Start observing this page's blocks on enter, stop on leave
    DisposableEffect(page.uuid.value) {
        blockStateManager.observePage(page.uuid, page.isContentLoaded)
        onDispose {
            blockStateManager.unobservePage(page.uuid)
        }
    }

    val toolbarHeightDp = with(LocalDensity.current) { toolbarHeight.toDp() }

    // Provide the graph root path so that ImageBlock / rememberSteleKitImageLoader can resolve
    // relative Logseq asset paths (e.g. `../assets/image.png`).
    // currentGraphPath is the graph root directory as stored in AppState.
    CompositionLocalProvider(LocalGraphRootPath provides currentGraphPath.ifEmpty { null }) {

    Box(modifier = Modifier.fillMaxSize().imePadding()
        .let { m -> if (capabilities.onPasteImage != null) m.onPreviewKeyEvent { event ->
            event.type == KeyEventType.KeyDown &&
            (event.isCtrlPressed || event.isMetaPressed) &&
            event.key == Key.V &&
            capabilities.onPasteImage.invoke(editingBlockUuid)
        } else m }
        .onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        when {
            event.key == Key.A && event.isCtrlPressed && !isInSelectionMode -> {
                blockStateManager.selectAll(page.uuid)
                true
            }
            (event.key == Key.Delete || event.key == Key.Backspace) && isInSelectionMode && editingBlockUuid == null -> {
                blockStateManager.deleteSelectedBlocks()
                true
            }
            event.key == Key.Escape && isInSelectionMode -> {
                blockStateManager.clearSelection()
                true
            }
            event.key == Key.C && event.isCtrlPressed && isInSelectionMode && selectedBlockUuids.isNotEmpty() -> {
                blockStateManager.copySelectedBlocks("")
                true
            }
            event.key == Key.V && event.isCtrlPressed && !isInSelectionMode && editingBlockUuid != null && !blockClipboard.isEmpty -> {
                editingBlockUuid?.let { blockStateManager.pasteBlocks(it) }
                true
            }
            event.key == Key.E && event.isCtrlPressed && event.isShiftPressed -> {
                if (isInSelectionMode) {
                    viewModel.exportSelectedBlocks("markdown")
                } else {
                    viewModel.exportPage("markdown")
                }
                true
            }
            else -> false
        }
    }) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .let { m -> if (capabilities.onFileDrop != null) m.pageDropTarget(capabilities.onFileDrop) else m }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                    })
                },
            contentPadding = PaddingValues(top = 16.dp, bottom = toolbarHeightDp + 8.dp)
        ) {
            // Page header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val pageTitleLinkColor = MaterialTheme.colorScheme.primary
                    val pageTitleTextColor = MaterialTheme.colorScheme.onBackground
                    val annotatedPageTitle = remember(page.name, pageTitleLinkColor, pageTitleTextColor) {
                        parseMarkdownWithStyling(page.name, linkColor = pageTitleLinkColor, textColor = pageTitleTextColor)
                    }
                    BasicText(
                        text = annotatedPageTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (onReloadFromDisk != null) {
                        IconButton(onClick = onReloadFromDisk) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reload from disk",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.showRenameDialog(page) }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Rename page",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onToggleFavorite(page) }) {
                        Icon(
                            imageVector = if (page.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = if (page.isFavorite) "Unfavorite" else "Favorite",
                            tint = if (page.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    var exportMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { exportMenuExpanded = true },
                            enabled = !isExporting
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Export page",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = exportMenuExpanded,
                            onDismissRequest = { exportMenuExpanded = false }
                        ) {
                            listOf(
                                "markdown" to "Copy as Markdown",
                                "plain-text" to "Copy as Plain Text",
                                "html" to "Copy as HTML",
                                "json" to "Copy as JSON"
                            ).forEach { (formatId, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    enabled = !isExporting,
                                    onClick = {
                                        exportMenuExpanded = false
                                        viewModel.exportPage(formatId)
                                    }
                                )
                            }
                            if (tagSuggestionViewModel != null) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Suggest tags for page") },
                                    onClick = {
                                        exportMenuExpanded = false
                                        val pageContent = blocks
                                            .take(20)
                                            .joinToString("\n") { it.content }
                                            .take(500)
                                        val alreadyLinked = WikiLinkExtractor.extractPageNames(
                                            blocks.joinToString("\n") { it.content }
                                        )
                                        tagSuggestionViewModel.requestSuggestions(
                                            blockUuid = page.uuid.value,
                                            blockContent = pageContent,
                                            alreadyLinkedTerms = alreadyLinked,
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                if (page.namespace != null) {
                    Text(
                        text = "${t("common.namespace")}: ${page.namespace}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Blocks content
            item {
                if (blocks.isEmpty()) {
                    if (!page.isContentLoaded || page.uuid.value in loadingPageUuids) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { blockStateManager.addBlockToPage(page.uuid) }
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
                    val sortedBlocks = remember(blocks) { BlockSorter.sort(blocks) }

                    BlockList(
                        blocks = sortedBlocks,
                        isDebugMode = isDebugMode,
                        editingBlockUuid = editingBlockUuid?.value,
                        editingCursorIndex = editingCursorIndex,
                        collapsedBlocks = collapsedBlockUuids,
                        selectedBlockUuids = selectedBlockUuids,
                        isInSelectionMode = isInSelectionMode,
                        onToggleSelect = { uuid -> blockStateManager.toggleBlockSelection(BlockUuid(uuid)) },
                        onEnterSelectionMode = { uuid -> blockStateManager.enterSelectionMode(BlockUuid(uuid)) },
                        onShiftClick = { uuid -> blockStateManager.extendSelectionTo(BlockUuid(uuid)) },
                        onShiftArrowUp = { blockStateManager.extendSelectionByOne(up = true) },
                        onShiftArrowDown = { blockStateManager.extendSelectionByOne(up = false) },
                        onStartEditing = { uuid -> blockStateManager.requestEditBlock(BlockUuid(uuid)) },
                        onStopEditing = { blockUuid -> blockStateManager.stopEditingBlock(BlockUuid(blockUuid)) },
                        onContentChange = { blockUuid, newContent, version ->
                            blockStateManager.updateBlockContent(BlockUuid(blockUuid), newContent, version)
                        },
                        onLinkClick = onLinkClick,
                        onNewBlock = { uuid -> blockStateManager.addNewBlock(BlockUuid(uuid)) },
                        onSplitBlock = { uuid, pos -> blockStateManager.splitBlock(BlockUuid(uuid), pos) },
                        onMergeBlock = { uuid -> blockStateManager.mergeBlock(BlockUuid(uuid)) },
                        onIndent = { blockUuid -> blockStateManager.indentBlock(BlockUuid(blockUuid)) },
                        onOutdent = { blockUuid -> blockStateManager.outdentBlock(BlockUuid(blockUuid)) },
                        onMoveUp = { blockUuid -> blockStateManager.moveBlockUp(BlockUuid(blockUuid)) },
                        onMoveDown = { blockUuid -> blockStateManager.moveBlockDown(BlockUuid(blockUuid)) },
                        onLoadContent = { pageUuid -> blockStateManager.loadPageContent(PageUuid(pageUuid)) },
                        onBackspace = { blockUuid -> blockStateManager.handleBackspace(BlockUuid(blockUuid)) },
                        onToggleCollapse = { blockUuid -> blockStateManager.toggleBlockCollapse(BlockUuid(blockUuid)) },
                        onFocusUp = { blockUuid -> blockStateManager.focusPreviousBlock(BlockUuid(blockUuid)) },
                        onFocusDown = { blockUuid -> blockStateManager.focusNextBlock(BlockUuid(blockUuid)) },
                        onResolveContent = { uuid -> viewModel.getBlockContent(uuid) },
                        onSearchPages = { query -> viewModel.searchPages(query) },
                        formatEvents = blockStateManager.formatEvents,
                        suggestionMatcher = suggestionMatcher,
                        onNavigateAllSuggestions = { suggestions ->
                            navigatorSuggestions = suggestions
                            navigatorIndex = 0
                        },
                        onMoveSelectedBlocks = { newParentUuid, insertAfterUuid ->
                            blockStateManager.moveSelectedBlocks(
                                newParentUuid?.let { BlockUuid(it) },
                                insertAfterUuid?.let { BlockUuid(it) }
                            )
                        },
                        onAutoSelectForDrag = { uuid -> blockStateManager.enterSelectionMode(BlockUuid(uuid)) },
                        onBlockSelectionChange = { blockUuid, range ->
                            blockStateManager.updateEditingSelection(
                                if (blockUuid == editingBlockUuid?.value) range else null
                            )
                        },
                        onOpenAnnotationEditor = { uuid ->
                            viewModel.navigateToAnnotationEditor(uuid, page.uuid.value)
                        },
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable { blockStateManager.addBlockToPage(page.uuid) }
                    )
                }
            }

            // References section
            item {
                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                ReferencesPanel(
                    page = page,
                    blockRepository = blockRepository,
                    pageRepository = pageRepository,
                    onLinkClick = onLinkClick,
                    suggestionMatcher = suggestionMatcher,
                    writeActor = writeActor,
                )
            }
        }

        // Suggestion navigator panel — shown above toolbar when active
        if (navigatorSuggestions.isNotEmpty()) {
            SuggestionNavigatorPanel(
                suggestions = navigatorSuggestions,
                currentIndex = navigatorIndex,
                onLink = {
                    val item = navigatorSuggestions[navigatorIndex]
                    val block = blocks.find { it.uuid.value == item.blockUuid }
                    if (block != null) {
                        val safeEnd = item.contentEnd.coerceAtMost(block.content.length)
                        val safeStart = item.contentStart.coerceIn(0, safeEnd)
                        val newContent = block.content.substring(0, safeStart) +
                            "[[${item.canonicalName}]]" +
                            block.content.substring(safeEnd)
                        blockStateManager.updateBlockContent(BlockUuid(item.blockUuid), newContent, block.version)
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
            blockStateManager = blockStateManager,
            capabilities = capabilities,
            searchViewModel = searchViewModel,
            isLeftHanded = isLeftHanded,
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
                    // When page-scope was triggered, uuid is the page uuid (not a block uuid).
                    // Find the actual target block: use the block matching uuid, or fall back to first block.
                    val targetBlockUuid = blocks.firstOrNull { it.uuid.value == uuid }?.uuid
                        ?: blocks.firstOrNull()?.uuid
                    targetBlockUuid?.let {
                        blockStateManager.appendToBlock(it, " [[$term]]")
                    }
                },
                onDismiss = { tagSuggestionViewModel.dismiss() },
            )
        }
    }

    } // CompositionLocalProvider(LocalGraphRootPath)
}
