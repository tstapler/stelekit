package dev.stapler.stelekit.ui.library

import arrow.core.Either
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.asset.AssetIndexProgress
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.LibraryRepository
import dev.stapler.stelekit.ui.annotate.OpenForAnnotationError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Snapshot rendered by `LibraryScreen`. Items are the pages loaded so far, in sort order. */
data class LibraryState(
    val filters: LibraryFilterState = LibraryFilterState(),
    val sort: LibrarySort = LibrarySort.RECENTLY_ADDED,
    val items: List<LibraryItem> = emptyList(),
    val availableTags: List<String> = emptyList(),
    /** Capped count; null until the first count lands. */
    val totalCount: Long? = null,
    val isLoading: Boolean = true,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
    val error: String? = null,
    /** Set when a search of 1 character was ignored (LD-3). */
    val searchHint: Boolean = false,
    /** Ids of loaded items whose file is gone from disk (rendered as "File missing" tiles). */
    val missingIds: Set<String> = emptySet(),
    /** Last failed per-item action (delete); null when none or dismissed. */
    val actionError: String? = null,
    /** True while the asset file walk is filling the index, so an empty list reads as "not yet" (not "none"). */
    val indexing: Boolean = false,
) {
    val query: LibraryQuery get() = LibraryQuery(filters, sort)
}

/**
 * One ViewModel for the merged Library (replaces the retired Gallery and Assets screens).
 *
 * Owns its scope, guarded by a [CoroutineExceptionHandler] (an uncaught Throwable kills the process on
 * Android). Reads are one paged keyset query per page plus a debounced capped count; filters and search are
 * session state, the sort is the persisted `libraryDefaultSort` preference.
 */
class LibraryViewModel(
    private val repository: LibraryRepository,
    private val settings: Settings? = null,
    initialFilters: LibraryFilterState = LibraryFilterState(),
    private val pageSize: Int = LibraryRepository.MAX_PAGE,
    private val searchDebounceMs: Long = SEARCH_DEBOUNCE_MS,
    /** Null where no file system is wired: nothing is then flagged missing. */
    private val fileExists: ((String) -> Boolean)? = null,
    /** Null where deletion is not wired: [deleteItem] is then a no-op. Runs through the write actor. */
    private val deleteAsset: (suspend (String) -> Either<DomainError, Unit>)? = null,
    /** Null where no index walk is wired. A finished walk reloads the list; a running one shows "Indexing". */
    private val indexProgress: StateFlow<AssetIndexProgress>? = null,
) {
    private val logger = Logger("LibraryViewModel")
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            logger.error("Library coroutine failed: ${e.message}")
            _state.update { it.copy(isLoading = false, isLoadingMore = false, error = e.message ?: "unknown") }
        },
    )

    private val _state = MutableStateFlow(
        LibraryState(filters = LibraryFilterRules.normalize(initialFilters), sort = readSort()),
    )
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private var pageJob: Job? = null
    private var countJob: Job? = null
    private var searchJob: Job? = null
    private var generation = 0

    init {
        reload()
        loadTags()
        observeIndexing()
    }

    private fun observeIndexing() {
        val progress = indexProgress ?: return
        scope.launch {
            var wasRunning = false
            progress.collect { p ->
                val running = isRunning(p)
                _state.update { it.copy(indexing = running) }
                if (wasRunning && p is AssetIndexProgress.Finished) {
                    logger.info("Library reload after index walk finished (${p.total} files)")
                    reload()
                    loadTags()
                }
                wasRunning = running
            }
        }
    }

    // -- Filters and sort -------------------------------------------------------------------------------

    fun toggleChip(chip: LibraryChip) = applyFilters(LibraryFilterRules.toggle(_state.value.filters, chip))

    /** Debounced (300 ms); 1 character is ignored and flagged as a hint, 0 clears the search. */
    fun setSearch(text: String) {
        val current = _state.value.filters
        _state.update { it.copy(filters = current.copy(search = text), searchHint = text.trim().length == 1) }
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(searchDebounceMs)
            reload()
        }
    }

    fun clearFilters() = applyFilters(LibraryFilterState())

    /**
     * Entry-point rules (LD-8): "Show annotated photos" clears everything else, a search sets search only.
     * A plain open (both null) keeps the session's filters.
     */
    fun applyEntryPoint(filter: LibraryFilter?, search: String?) {
        if (filter == null && search == null) return
        applyFilters(LibraryFilterRules.fromEntryPoint(filter, search))
    }

    /** First visible grid index, kept so Back from the editor returns to the same scroll position. */
    @kotlin.concurrent.Volatile
    var scrollIndex: Int = 0

    fun setSort(sort: LibrarySort) {
        settings?.putString(SORT_KEY, sort.name)
        _state.update { it.copy(sort = sort) }
        reload()
    }

    private fun applyFilters(next: LibraryFilterState) {
        val normalized = LibraryFilterRules.normalize(next)
        if (normalized == _state.value.filters) return
        _state.update { it.copy(filters = normalized, searchHint = false) }
        reload()
    }

    // -- Loading ----------------------------------------------------------------------------------------

    /** Re-runs the first page and the count; also the Retry action. */
    fun reload() {
        pageJob?.cancel()
        countJob?.cancel()
        val gen = ++generation
        _state.update { it.copy(isLoading = true, isLoadingMore = false, error = null, loadMoreError = null) }
        val query = _state.value.query
        pageJob = scope.launch {
            repository.getLibraryPage(query, null, pageSize).fold(
                { e -> if (gen == generation) _state.update { it.copy(isLoading = false, error = e.message) } },
                { items ->
                    if (gen == generation) logger.info("Library page loaded: ${items.size} items")
                    if (gen == generation) _state.update {
                        it.copy(items = items, isLoading = false, hasMore = items.size >= pageSize, missingIds = emptySet())
                    }
                    if (gen == generation) flagMissing(items, gen)
                },
            )
        }
        countJob = scope.launch {
            repository.countLibrary(query).onRightIf(gen) { n -> _state.update { it.copy(totalCount = n) } }
        }
    }

    /** Appends the next keyset page; a failure keeps the loaded pages and offers an inline Retry. */
    fun loadMore() {
        val s = _state.value
        if (s.isLoading || s.isLoadingMore || !s.hasMore) return
        val last = s.items.lastOrNull() ?: return
        val gen = generation
        val cursor = LibraryCursor.of(last, s.sort)
        _state.update { it.copy(isLoadingMore = true, loadMoreError = null) }
        scope.launch {
            repository.getLibraryPage(s.query, cursor, pageSize).fold(
                { e -> if (gen == generation) _state.update { it.copy(isLoadingMore = false, loadMoreError = e.message ?: "unknown") } },
                { page ->
                    if (gen == generation) _state.update {
                        it.copy(items = it.items + page, isLoadingMore = false, hasMore = page.size >= pageSize)
                    }
                    if (gen == generation) flagMissing(page, gen)
                },
            )
        }
    }

    /** One bounded file-existence check per loaded page, off the main thread. */
    private suspend fun flagMissing(items: List<LibraryItem>, gen: Int) {
        val exists = fileExists ?: return
        val gone = withContext(PlatformDispatcher.IO) { items.filterNot { exists(it.filePath) }.mapTo(mutableSetOf()) { it.id } }
        if (gone.isNotEmpty() && gen == generation) _state.update { it.copy(missingIds = it.missingIds + gone) }
    }

    /** Deletes the asset (and so its Library row), then reloads. A failure keeps the item and sets [LibraryState.actionError]. */
    fun deleteItem(id: String) {
        val delete = deleteAsset ?: return
        scope.launch {
            delete(id).fold(
                { e -> _state.update { it.copy(actionError = e.message) } },
                { reload() },
            )
        }
    }

    /**
     * Opens an existing image in the annotation editor. [open] finds or creates its annotation;
     * a failure (missing file, no page) sets [LibraryState.actionError] instead of navigating.
     */
    fun annotateItem(
        item: LibraryItem,
        open: suspend (LibraryItem) -> Either<OpenForAnnotationError, String>,
        onOpened: (annotationUuid: String) -> Unit,
    ) {
        scope.launch {
            open(item).fold(
                { e -> _state.update { it.copy(actionError = e.message) } },
                { annotationUuid -> onOpened(annotationUuid) },
            )
        }
    }

    fun dismissActionError() = _state.update { it.copy(actionError = null) }

    private fun loadTags() {
        scope.launch {
            repository.getDistinctAnnotationTags().onRight { tags -> _state.update { it.copy(availableTags = tags) } }
        }
    }

    private inline fun <E, A> arrow.core.Either<E, A>.onRightIf(gen: Int, block: (A) -> Unit) {
        if (this is arrow.core.Either.Right && gen == generation) block(value)
    }

    private fun readSort(): LibrarySort =
        settings?.getString(SORT_KEY, LibrarySort.RECENTLY_ADDED.name)
            ?.let { name -> LibrarySort.entries.firstOrNull { it.name == name } }
            ?: LibrarySort.RECENTLY_ADDED

    /** Cancels the internal scope; call when the Library is permanently dismissed. */
    fun close() {
        try { scope.cancel() } catch (_: CancellationException) { /* already cancelled */ }
    }

    companion object {
        const val SORT_KEY = "libraryDefaultSort"
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

internal fun isRunning(p: AssetIndexProgress): Boolean =
    p is AssetIndexProgress.Counting || p is AssetIndexProgress.Indexing
