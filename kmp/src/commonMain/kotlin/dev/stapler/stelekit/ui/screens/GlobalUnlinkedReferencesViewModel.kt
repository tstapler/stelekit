package dev.stapler.stelekit.ui.screens

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ===== State models =====

data class GlobalUnlinkedRefsState(
    val results: List<UnlinkedRefEntry> = emptyList(),
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
    val errorMessage: String? = null,
    val successMessage: String? = null,
)

data class UnlinkedRefEntry(
    val block: Block,
    val targetPageName: String,
    val matchStart: Int,
    val matchEnd: Int,
    /** Snapshot of block.content at suggestion-capture time; used as a stale guard (ADR-002). */
    val capturedContent: String,
)

/**
 * ViewModel that aggregates unlinked references across **all** pages in the active graph.
 *
 * Pagination is page-cursor-based: [loadInitial] starts from the first page and fills up to
 * [BATCH_SIZE] entries; [loadMore] continues from the last fetched page cursor.
 *
 * Accepts a [matcher] to resolve exact match positions via Aho-Corasick so that the UI can
 * highlight the precise span of the matched term rather than relying on a regex search.
 *
 * Write dispatch follows the same pattern used by [dev.stapler.stelekit.ui.StelekitViewModel]:
 * if a [DatabaseWriteActor] is available it is preferred; otherwise the call falls back to
 * a direct [BlockRepository.saveBlock] invocation (in-memory / test mode).
 */
@OptIn(DirectRepositoryWrite::class)
class GlobalUnlinkedReferencesViewModel(
    private val pageRepository: PageRepository,
    private val blockRepository: BlockRepository,
    private val scope: CoroutineScope,
    private val writeActor: DatabaseWriteActor? = null,
    private val matcher: AhoCorasickMatcher? = null,
) {

    private val logger = Logger("GlobalUnlinkedReferencesViewModel")

    private val _state = MutableStateFlow(GlobalUnlinkedRefsState())
    val state: StateFlow<GlobalUnlinkedRefsState> = _state.asStateFlow()

    companion object {
        private const val BATCH_SIZE = 50
        /** How many unlinked-reference blocks to request per page per fetch pass. */
        private const val PAGE_CHUNK = 20
        private const val SUCCESS_DISMISS_MS = 2000L
    }

    // ===== Page cursor state (protected by single-threaded coroutine) =====

    /** All page names in order, populated once on [loadInitial]. */
    private var allPageNames: List<String> = emptyList()

    /**
     * Index into [allPageNames] pointing to the next page to query.
     * A value equal to [allPageNames].size means all pages have been exhausted.
     */
    private var pageCursorIndex: Int = 0

    /**
     * Accumulated results waiting to be included in the next batch emission.
     * Preserved between [loadMore] calls so partial page fetches are not lost.
     */
    private var pendingEntries: MutableList<UnlinkedRefEntry> = mutableListOf()

    // ===== Public API =====

    /**
     * Clears existing state and fetches the first [BATCH_SIZE] entries across all pages.
     */
    fun loadInitial() {
        scope.launch {
            _state.update { it.copy(isLoading = true, results = emptyList(), errorMessage = null) }
            try {
                val pages = pageRepository.getAllPages().first().getOrNull() ?: emptyList()
                allPageNames = pages.map { it.name }
                pageCursorIndex = 0
                pendingEntries = mutableListOf()

                val batch = fetchNextBatch()
                _state.update {
                    it.copy(
                        results = batch,
                        isLoading = false,
                        hasMore = pageCursorIndex < allPageNames.size || pendingEntries.isNotEmpty(),
                    )
                }
            } catch (e: Exception) {
                logger.error("loadInitial failed", e)
                _state.update { it.copy(isLoading = false, errorMessage = e.message ?: "Load failed") }
            }
        }
    }

    /**
     * Continues fetching from where [loadInitial] (or the previous [loadMore]) left off.
     * No-op if [isLoading] is already true or [hasMore] is false.
     */
    fun loadMore() {
        val current = _state.value
        if (current.isLoading || !current.hasMore) return
        scope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val batch = fetchNextBatch()
                _state.update {
                    it.copy(
                        results = it.results + batch,
                        isLoading = false,
                        hasMore = pageCursorIndex < allPageNames.size || pendingEntries.isNotEmpty(),
                    )
                }
            } catch (e: Exception) {
                logger.error("loadMore failed", e)
                _state.update { it.copy(isLoading = false, errorMessage = e.message ?: "Load failed") }
            }
        }
    }

    /**
     * Wraps the matched text in `[[...]]` and saves the block.
     *
     * Stale-guard (ADR-002): if [entry].capturedContent no longer matches the live block
     * content, the entry is removed without writing and an error message is shown.
     */
    fun acceptSuggestion(entry: UnlinkedRefEntry) {
        scope.launch {
            // Re-fetch live block to perform stale check
            val liveBlock = blockRepository.getBlockByUuid(entry.block.uuid).first().getOrNull()
            val currentContent = liveBlock?.content ?: entry.block.content

            if (entry.capturedContent != currentContent) {
                _state.update {
                    it.copy(
                        results = it.results - entry,
                        errorMessage = "Block was edited; suggestion dismissed.",
                    )
                }
                return@launch
            }

            val safeEnd = entry.matchEnd.coerceAtMost(currentContent.length)
            val safeStart = entry.matchStart.coerceIn(0, safeEnd)
            val newContent =
                currentContent.substring(0, safeStart) +
                    "[[${entry.targetPageName}]]" +
                    currentContent.substring(safeEnd)

            val updatedBlock = (liveBlock ?: entry.block).copy(content = newContent)

            val result = if (writeActor != null) {
                writeActor.saveBlock(updatedBlock)
            } else {
                blockRepository.saveBlock(updatedBlock)
            }

            if (result.isSuccess) {
                _state.update {
                    it.copy(
                        results = it.results - entry,
                        successMessage = "Linked \"${entry.targetPageName}\"",
                    )
                }
                // Auto-dismiss success banner after 2 s
                scope.launch {
                    delay(SUCCESS_DISMISS_MS)
                    _state.update { it.copy(successMessage = null) }
                }
            } else {
                val msg = result.exceptionOrNull()?.message ?: "Save failed"
                _state.update { it.copy(errorMessage = msg) }
            }
        }
    }

    /**
     * Removes the entry from the displayed results without any write.
     */
    fun rejectSuggestion(entry: UnlinkedRefEntry) {
        _state.update { it.copy(results = it.results - entry) }
    }

    // ===== Internal helpers =====

    /**
     * Iterates through [allPageNames] starting at [pageCursorIndex] and fills a list of up to
     * [BATCH_SIZE] [UnlinkedRefEntry] items.  Any overflow from partial-page fetches is stored
     * in [pendingEntries] so the next call can drain it before querying new pages.
     */
    private suspend fun fetchNextBatch(): List<UnlinkedRefEntry> {
        val collected = mutableListOf<UnlinkedRefEntry>()

        // Drain any pending entries from the previous pass first
        if (pendingEntries.isNotEmpty()) {
            val take = pendingEntries.take(BATCH_SIZE)
            collected.addAll(take)
            val remaining = pendingEntries.drop(BATCH_SIZE)
            pendingEntries = remaining.toMutableList()
            if (collected.size >= BATCH_SIZE) return collected
        }

        // Iterate pages until we fill the batch or exhaust all pages
        while (pageCursorIndex < allPageNames.size && collected.size < BATCH_SIZE) {
            val pageName = allPageNames[pageCursorIndex]
            pageCursorIndex++

            val blocks = try {
                blockRepository.getUnlinkedReferences(pageName, limit = PAGE_CHUNK, offset = 0)
                    .first()
                    .getOrNull() ?: continue
            } catch (e: Exception) {
                logger.error("getUnlinkedReferences failed for page '$pageName'", e)
                continue
            }

            val entries = blocks.flatMap { block -> toEntries(block, pageName) }

            val needed = BATCH_SIZE - collected.size
            collected.addAll(entries.take(needed))
            if (entries.size > needed) {
                pendingEntries.addAll(0, entries.drop(needed))
            }
        }

        return collected
    }

    /**
     * Converts a single [block] into zero or more [UnlinkedRefEntry] items.
     *
     * If an [AhoCorasickMatcher] is available it is used to resolve exact character spans for
     * [targetPageName] matches.  Only spans whose [AhoCorasickMatcher.MatchSpan.canonicalName]
     * equals [targetPageName] (case-insensitive) are included, so one block can produce
     * multiple entries when it contains multiple distinct matches of the same term.
     *
     * When no matcher is available a single entry spanning the entire block is produced as a
     * fallback (matchStart = 0, matchEnd = content.length).
     */
    private fun toEntries(block: Block, targetPageName: String): List<UnlinkedRefEntry> {
        val content = block.content
        val m = matcher

        if (m != null) {
            val spans = m.findAll(content).filter {
                it.canonicalName.equals(targetPageName, ignoreCase = true)
            }
            if (spans.isEmpty()) return emptyList()
            return spans.map { span ->
                UnlinkedRefEntry(
                    block = block,
                    targetPageName = targetPageName,
                    matchStart = span.start,
                    matchEnd = span.end,
                    capturedContent = content,
                )
            }
        }

        // Fallback: no matcher — emit one entry per block with full-span coordinates
        return listOf(
            UnlinkedRefEntry(
                block = block,
                targetPageName = targetPageName,
                matchStart = 0,
                matchEnd = content.length,
                capturedContent = content,
            )
        )
    }
}
