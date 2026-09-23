package dev.stapler.stelekit.ui.screens

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Formatting actions that wrap selected text (or insert markers at cursor).
 * Each action defines the prefix and suffix to insert around the selection.
 */
enum class FormatAction(val prefix: String, val suffix: String) {
    BOLD("**", "**"),
    ITALIC("*", "*"),
    STRIKETHROUGH("~~", "~~"),
    HIGHLIGHT("^^", "^^"),
    CODE("`", "`"),
    LINK("[[", "]]"),
    // Line-prefix actions: suffix is empty, applied/toggled at the start of the block content
    QUOTE("> ", ""),
    NUMBERED_LIST("1. ", ""),
    HEADING("# ", ""),
    // Structural inserts (Phase C.2, GAP-006/GAP-007) — applyFormatAction dispatches these to
    // dedicated branches rather than the generic prefix/suffix wrap logic above. The prefix/
    // suffix values are unused by those branches; CODE_BLOCK's non-empty suffix and
    // TABLE_INSERT's empty prefix keep both excluded from the line-prefix strip-group filter
    // (suffix.isEmpty() && prefix.isNotEmpty()) so neither contaminates QUOTE/NUMBERED_LIST/HEADING.
    CODE_BLOCK("```\n", "\n```"),
    TABLE_INSERT("", ""),
}

/**
 * ViewModel for Journals screen.
 * Delegates all block state and operations to [BlockStateManager].
 * Retains only journal-page-level concerns: pagination, page loading.
 */
class JournalsViewModel(
    private val journalService: JournalService,
    val blockStateManager: BlockStateManager,
    // Default scope owns its lifecycle; callers in remember{} must not pass rememberCoroutineScope()
    // which is cancelled when the composable leaves composition. Tests inject a TestCoroutineScope.
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val activeSectionIds: List<String>? = null,
) {
    private val scope = scope
    private val logger = Logger("JournalsViewModel")
    private val _uiState = MutableStateFlow(JournalsUiState())
    val uiState: StateFlow<JournalsUiState> = _uiState.asStateFlow()

    // ---- Delegated state from BlockStateManager ----

    val blocks: StateFlow<Map<String, List<Block>>> = blockStateManager.blocks
    val editingBlockUuid: StateFlow<BlockUuid?> = blockStateManager.editingBlockUuid
    val editingCursorIndex: StateFlow<Int?> = blockStateManager.editingCursorIndex
    val editingSelectionRange: StateFlow<IntRange?> = blockStateManager.editingSelectionRange
    val collapsedBlockUuids: StateFlow<Set<String>> = blockStateManager.collapsedBlockUuids
    val selectedBlockUuids: StateFlow<Set<String>> = blockStateManager.selectedBlockUuids
    val isInSelectionMode: StateFlow<Boolean> = blockStateManager.isInSelectionMode
    val formatEvents: SharedFlow<FormatAction> = blockStateManager.formatEvents
    val todoToggleEvents: SharedFlow<Unit> = blockStateManager.todoToggleEvents
    val canUndo: StateFlow<Boolean> = blockStateManager.canUndo
    val canRedo: StateFlow<Boolean> = blockStateManager.canRedo
    val loadingPageUuids: StateFlow<Set<String>> = blockStateManager.loadingPageUuids

    // ---- Pagination ----

    private val pageSize = 10
    private var totalVisibleCount = pageSize
    private var isLoading = false
    private var hasMore = true
    private var paginationJob: kotlinx.coroutines.Job? = null

    init {
        startPaginationObserver()
        // Note: generateTodayJournal() is NOT called here — it runs after
        // graph loading completes via StelekitViewModel.onPhase1Complete.
    }

    fun generateTodayJournal(): Job = scope.launch {
        val page = journalService.ensureTodayJournal()
        logger.info("Today's journal ready: ${page.name}")
    }

    private fun startPaginationObserver() {
        paginationJob?.cancel()
        paginationJob = scope.launch {
            val sectionsFlow = activeSectionIds
            val journalFlow = if (sectionsFlow != null) {
                journalService.getJournalPagesBySections(sectionsFlow, totalVisibleCount, 0)
            } else {
                journalService.getJournalPages(totalVisibleCount, 0)
            }
            journalFlow.collect { result ->
                val journals = result.getOrNull() ?: emptyList()

                _uiState.update { it.copy(
                    pages = journals,
                    isLoading = false,
                    hasMore = journals.size >= totalVisibleCount
                ) }

                // Observe blocks for visible pages via BlockStateManager
                observeBlocksForPages(journals)
            }
        }
    }

    private fun observeBlocksForPages(pages: List<Page>) {
        val currentUuids = pages.map { it.uuid.value }.toSet()

        // Unobserve pages that are no longer visible
        val observed = blocks.value.keys
        for (uuid in observed) {
            if (uuid !in currentUuids) {
                blockStateManager.unobservePage(PageUuid(uuid))
            }
        }

        // Observe new pages
        for (page in pages) {
            blockStateManager.observePage(page.uuid, page.isContentLoaded)
        }
    }

    fun loadMore() {
        if (isLoading || !hasMore) return
        totalVisibleCount += pageSize
        startPaginationObserver()
    }

    fun refresh() {
        totalVisibleCount = pageSize
        hasMore = true
        startPaginationObserver()
    }

    // ---- Delegated operations to BlockStateManager ----

    fun updateBlockContent(blockUuid: BlockUuid, newContent: String, newVersion: Long): Job =
        blockStateManager.updateBlockContent(blockUuid, newContent, newVersion)

    fun indentBlock(blockUuid: BlockUuid): Job = blockStateManager.indentBlock(blockUuid)
    fun outdentBlock(blockUuid: BlockUuid): Job = blockStateManager.outdentBlock(blockUuid)
    fun moveBlockUp(blockUuid: BlockUuid): Job = blockStateManager.moveBlockUp(blockUuid)
    fun moveBlockDown(blockUuid: BlockUuid): Job = blockStateManager.moveBlockDown(blockUuid)
    fun addNewBlock(currentBlockUuid: BlockUuid): Job = blockStateManager.addNewBlock(currentBlockUuid)
    fun splitBlock(blockUuid: BlockUuid, cursorPosition: Int): Job = blockStateManager.splitBlock(blockUuid, cursorPosition)
    fun addBlockToPage(pageUuid: PageUuid): Job = blockStateManager.addBlockToPage(pageUuid)
    fun mergeBlock(blockUuid: BlockUuid): Job = blockStateManager.mergeBlock(blockUuid)
    fun handleBackspace(blockUuid: BlockUuid): Job = blockStateManager.handleBackspace(blockUuid)
    fun focusPreviousBlock(blockUuid: BlockUuid): Job = blockStateManager.focusPreviousBlock(blockUuid)
    fun focusNextBlock(blockUuid: BlockUuid): Job = blockStateManager.focusNextBlock(blockUuid)
    fun requestEditBlock(blockUuid: BlockUuid?, cursorIndex: Int? = null) = blockStateManager.requestEditBlock(blockUuid, cursorIndex)
    fun stopEditingBlock(blockUuid: BlockUuid) = blockStateManager.stopEditingBlock(blockUuid)
    fun toggleBlockCollapse(blockUuid: BlockUuid) = blockStateManager.toggleBlockCollapse(blockUuid)
    fun requestFormat(action: FormatAction) = blockStateManager.requestFormat(action)
    fun undo(): Job = blockStateManager.undo()
    fun redo(): Job = blockStateManager.redo()

    fun loadPageContent(pageUuid: PageUuid): Job = blockStateManager.loadPageContent(pageUuid)
    fun enterSelectionMode(uuid: BlockUuid) = blockStateManager.enterSelectionMode(uuid)
    fun toggleBlockSelection(uuid: BlockUuid) = blockStateManager.toggleBlockSelection(uuid)
    fun extendSelectionTo(uuid: BlockUuid) = blockStateManager.extendSelectionTo(uuid)
    fun extendSelectionByOne(up: Boolean) = blockStateManager.extendSelectionByOne(up)
    fun selectAll(pageUuid: PageUuid) = blockStateManager.selectAll(pageUuid)
    fun clearSelection() = blockStateManager.clearSelection()
    fun deleteSelectedBlocks(): Job = blockStateManager.deleteSelectedBlocks()
    fun moveSelectedBlocks(newParentUuid: BlockUuid?, insertAfterUuid: BlockUuid?): Job =
        blockStateManager.moveSelectedBlocks(newParentUuid, insertAfterUuid)
    fun insertLinkAtCursor(blockUuid: BlockUuid, pageName: String, overrideCursorIndex: Int? = null) =
        blockStateManager.insertLinkAtCursor(blockUuid, pageName, overrideCursorIndex)

    fun replaceSelectionWithLink(blockUuid: BlockUuid, selectionStart: Int, selectionEnd: Int, pageName: String) =
        blockStateManager.replaceSelectionWithLink(blockUuid, selectionStart, selectionEnd, pageName)

    fun acceptLinkPickerResult(blockUuid: BlockUuid, pageName: String, selectionRange: IntRange?, overrideCursorIndex: Int?) =
        blockStateManager.acceptLinkPickerResult(blockUuid, pageName, selectionRange, overrideCursorIndex)

    fun updateEditingSelection(range: IntRange?) = blockStateManager.updateEditingSelection(range)

    fun close() {
        scope.cancel()
    }
}

data class JournalsUiState(
    val pages: List<Page> = emptyList(),
    val isLoading: Boolean = false,
    val hasMore: Boolean = true
)
