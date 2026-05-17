package dev.stapler.stelekit.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
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
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.outliner.BlockSorter
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.performance.NavigationTracingEffect
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.ui.state.BlockStateManager
import dev.stapler.stelekit.ui.StelekitViewModel
import androidx.compose.runtime.CompositionLocalProvider
import dev.stapler.stelekit.ui.components.BlockList
import dev.stapler.stelekit.ui.components.LocalGraphRootPath
import dev.stapler.stelekit.ui.components.pageDropTarget
import dev.stapler.stelekit.ui.components.MobileBlockToolbar
import dev.stapler.stelekit.ui.components.ReferencesPanel
import dev.stapler.stelekit.ui.components.SearchDialog
import dev.stapler.stelekit.ui.components.SuggestionItem
import dev.stapler.stelekit.ui.components.SuggestionNavigatorPanel
import dev.stapler.stelekit.ui.i18n.t

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
    /**
     * Platform-provided callback to open a file picker and attach an image.
     * Supply a non-null lambda from the platform-specific screen wrapper
     * (e.g. JvmMediaAttachmentService on desktop, rememberAndroidMediaAttachmentService on Android).
     * When null, the attach-image toolbar button is hidden.
     *
     * The lambda receives the currently-editing block UUID (or null if no block is focused).
     * It is responsible for:
     *   1. Opening the platform file picker.
     *   2. Copying the file to `<graphRoot>/assets/`.
     *   3. Calling blockStateManager.insertTextAtCursor(blockUuid, "![altText](relativePath)")
     *      on the editing block if one is active.
     */
    onAttachImage: ((editingBlockUuid: String?) -> Unit)? = null,
    /**
     * Platform-provided callback invoked when files are drag-and-dropped onto the page area.
     * The list contains opaque file handles (typed as [Any] to stay platform-agnostic in
     * commonMain; on JVM they are [java.io.File] instances).
     * When null, drop events are ignored.
     */
    onFileDrop: ((List<Any>) -> Unit)? = null,
    onPasteImage: ((editingBlockUuid: String?) -> Boolean)? = null,
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

    // Navigator panel state — empty list means closed
    var navigatorSuggestions by remember { mutableStateOf<List<SuggestionItem>>(emptyList()) }
    var navigatorIndex by remember { mutableStateOf(0) }

    // Link picker state
    var showLinkPicker by remember { mutableStateOf(false) }
    var linkPickerBlockUuid by remember { mutableStateOf<String?>(null) }
    var linkPickerCursorIndex by remember { mutableStateOf<Int?>(null) }
    var linkPickerSelectionRange by remember { mutableStateOf<IntRange?>(null) }
    var linkPickerInitialQuery by remember { mutableStateOf<String?>(null) }

    val blocks = allBlocks[page.uuid] ?: emptyList()

    // Start observing this page's blocks on enter, stop on leave
    DisposableEffect(page.uuid) {
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
        .let { m -> if (onPasteImage != null) m.onPreviewKeyEvent { event ->
            event.type == KeyEventType.KeyDown &&
            (event.isCtrlPressed || event.isMetaPressed) &&
            event.key == Key.V &&
            onPasteImage(editingBlockUuid)
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
                .let { m -> if (onFileDrop != null) m.pageDropTarget(onFileDrop) else m }
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
                    Text(
                        text = page.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
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
                    if (!page.isContentLoaded || page.uuid in loadingPageUuids) {
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
                        editingBlockUuid = editingBlockUuid,
                        editingCursorIndex = editingCursorIndex,
                        collapsedBlocks = collapsedBlockUuids,
                        selectedBlockUuids = selectedBlockUuids,
                        isInSelectionMode = isInSelectionMode,
                        onToggleSelect = { uuid -> blockStateManager.toggleBlockSelection(uuid) },
                        onEnterSelectionMode = { uuid -> blockStateManager.enterSelectionMode(uuid) },
                        onShiftClick = { uuid -> blockStateManager.extendSelectionTo(uuid) },
                        onShiftArrowUp = { blockStateManager.extendSelectionByOne(up = true) },
                        onShiftArrowDown = { blockStateManager.extendSelectionByOne(up = false) },
                        onStartEditing = { uuid -> blockStateManager.requestEditBlock(uuid) },
                        onStopEditing = { blockUuid -> blockStateManager.stopEditingBlock(blockUuid) },
                        onContentChange = { blockUuid, newContent, version ->
                            blockStateManager.updateBlockContent(blockUuid, newContent, version)
                        },
                        onLinkClick = onLinkClick,
                        onNewBlock = { uuid -> blockStateManager.addNewBlock(uuid) },
                        onSplitBlock = { uuid, pos -> blockStateManager.splitBlock(uuid, pos) },
                        onMergeBlock = { uuid -> blockStateManager.mergeBlock(uuid) },
                        onIndent = { blockUuid -> blockStateManager.indentBlock(blockUuid) },
                        onOutdent = { blockUuid -> blockStateManager.outdentBlock(blockUuid) },
                        onMoveUp = { blockUuid -> blockStateManager.moveBlockUp(blockUuid) },
                        onMoveDown = { blockUuid -> blockStateManager.moveBlockDown(blockUuid) },
                        onLoadContent = { pageUuid -> blockStateManager.loadPageContent(pageUuid) },
                        onBackspace = { blockUuid -> blockStateManager.handleBackspace(blockUuid) },
                        onToggleCollapse = { blockUuid -> blockStateManager.toggleBlockCollapse(blockUuid) },
                        onFocusUp = { blockUuid -> blockStateManager.focusPreviousBlock(blockUuid) },
                        onFocusDown = { blockUuid -> blockStateManager.focusNextBlock(blockUuid) },
                        onResolveContent = { uuid -> viewModel.getBlockContent(uuid) },
                        onSearchPages = { query -> viewModel.searchPages(query) },
                        formatEvents = blockStateManager.formatEvents,
                        suggestionMatcher = suggestionMatcher,
                        onNavigateAllSuggestions = { suggestions ->
                            navigatorSuggestions = suggestions
                            navigatorIndex = 0
                        },
                        onMoveSelectedBlocks = { newParentUuid, insertAfterUuid ->
                            blockStateManager.moveSelectedBlocks(newParentUuid, insertAfterUuid)
                        },
                        onAutoSelectForDrag = { uuid -> blockStateManager.enterSelectionMode(uuid) },
                        onBlockSelectionChange = { blockUuid, range ->
                            blockStateManager.updateEditingSelection(
                                if (blockUuid == editingBlockUuid) range else null
                            )
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
                    val block = blocks.find { it.uuid == item.blockUuid }
                    if (block != null) {
                        val safeEnd = item.contentEnd.coerceAtMost(block.content.length)
                        val safeStart = item.contentStart.coerceIn(0, safeEnd)
                        val newContent = block.content.substring(0, safeStart) +
                            "[[${item.canonicalName}]]" +
                            block.content.substring(safeEnd)
                        blockStateManager.updateBlockContent(item.blockUuid, newContent, block.version)
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

        // Link picker dialog
        if (showLinkPicker && searchViewModel != null) {
            SearchDialog(
                visible = true,
                onDismiss = { showLinkPicker = false },
                onNavigateToPage = { /* not used in link picker mode */ },
                onNavigateToBlock = { /* not used in link picker mode */ },
                onCreatePage = { pageName ->
                    linkPickerBlockUuid?.let { blockUuid ->
                        blockStateManager.acceptLinkPickerResult(blockUuid, pageName, linkPickerSelectionRange, linkPickerCursorIndex)
                    }
                    showLinkPicker = false
                },
                viewModel = searchViewModel,
                initialQuery = linkPickerInitialQuery ?: "",
                onPageSelected = { pageName ->
                    linkPickerBlockUuid?.let { blockUuid ->
                        blockStateManager.acceptLinkPickerResult(blockUuid, pageName, linkPickerSelectionRange, linkPickerCursorIndex)
                    }
                    showLinkPicker = false
                }
            )
        }

        MobileBlockToolbar(
            editingBlockId = editingBlockUuid,
            onIndent = { blockUuid -> blockStateManager.indentBlock(blockUuid) },
            onOutdent = { blockUuid -> blockStateManager.outdentBlock(blockUuid) },
            onMoveUp = { blockUuid -> blockStateManager.moveBlockUp(blockUuid) },
            onMoveDown = { blockUuid -> blockStateManager.moveBlockDown(blockUuid) },
            onAddBlock = { blockUuid -> blockStateManager.addNewBlock(blockUuid) },
            onUndo = { blockStateManager.undo() },
            onRedo = { blockStateManager.redo() },
            onFormat = { action -> blockStateManager.requestFormat(action) },
            onAttachImage = if (onAttachImage != null) {
                { onAttachImage(editingBlockUuid) }
            } else null,
            onLinkPicker = if (searchViewModel != null) {
                {
                    val curBlockUuid = editingBlockUuid
                    // Read selection directly from StateFlow — avoids root-scope recomposition
                    val sel = blockStateManager.editingSelectionRange.value
                    linkPickerBlockUuid = curBlockUuid
                    // editingCursorIndex is only set via requestEditBlock; fall back to
                    // selection start so cursor-move link insertion lands at the caret
                    linkPickerCursorIndex = editingCursorIndex ?: sel?.first
                    linkPickerSelectionRange = sel
                    linkPickerInitialQuery = if (sel != null && sel.first < sel.last && curBlockUuid != null) {
                        val block = allBlocks.values.flatten().find { it.uuid == curBlockUuid }
                        block?.content?.substring(
                            sel.first.coerceAtMost(block.content.length),
                            sel.last.coerceAtMost(block.content.length)
                        )
                    } else null
                    showLinkPicker = true
                }
            } else null,
            isInSelectionMode = isInSelectionMode,
            selectedCount = selectedBlockUuids.size,
            onDeleteSelected = { blockStateManager.deleteSelectedBlocks() },
            onClearSelection = { blockStateManager.clearSelection() },
            isLeftHanded = isLeftHanded,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { toolbarHeight = it.height }
        )
    }

    } // CompositionLocalProvider(LocalGraphRootPath)
}
