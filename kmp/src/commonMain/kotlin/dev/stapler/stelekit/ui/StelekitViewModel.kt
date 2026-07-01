package dev.stapler.stelekit.ui

import dev.stapler.stelekit.db.BacklinkRenamer
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.GraphLoaderPort
import dev.stapler.stelekit.db.GraphWriterPort
import dev.stapler.stelekit.vault.VaultManager
import dev.stapler.stelekit.db.RenameResult
import dev.stapler.stelekit.db.UndoManager
import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.DomainError.ExportError
import dev.stapler.stelekit.export.ClipboardProvider
import dev.stapler.stelekit.export.ExportService
import dev.stapler.stelekit.platform.google.GoogleAuthManager
import dev.stapler.stelekit.git.GitSyncService
import dev.stapler.stelekit.git.model.SyncState
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.model.NotificationType
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.outliner.BlockSorter
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.JournalService
import dev.stapler.stelekit.repository.SearchRepository
import dev.stapler.stelekit.repository.SearchRequest
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.ui.i18n.Language
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import dev.stapler.stelekit.editor.commands.CommandContext
import dev.stapler.stelekit.editor.commands.CommandManager
import dev.stapler.stelekit.editor.commands.EditorCommand
import dev.stapler.stelekit.editor.commands.CommandResult
import dev.stapler.stelekit.domain.AhoCorasickMatcher
import dev.stapler.stelekit.domain.PageNameIndex
import dev.stapler.stelekit.ui.screens.SearchResultItem
import dev.stapler.stelekit.ui.state.BlockStateManager
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.time.Clock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import dev.stapler.stelekit.sections.SectionState
import dev.stapler.stelekit.sections.getSectionStates
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

sealed class IndexingState {
    object Idle : IndexingState()
    data class InProgress(val message: String) : IndexingState()
    object Complete : IndexingState()
}

/**
 * Main application ViewModel orchestrating graph operations and UI state.
 * Updated to use UUID-native storage.
 *
 * Direct repository writes in this class are user-triggered (conflict resolution, page creation)
 * and not concurrent with the parallel graph loading that causes SQLITE_BUSY. The opt-in is
 * intentional; new writes should prefer going through [writeActor] where possible.
 */
class StelekitViewModel(
    deps: StelekitViewModelDependencies,
) {
    private val fileSystem: FileSystem = deps.fileSystem
    private val pageRepository: PageRepository = deps.pageRepository
    private val blockRepository: BlockRepository = deps.blockRepository
    private val searchRepository: SearchRepository = deps.searchRepository
    private val graphLoader: GraphLoaderPort = deps.graphLoader
    private val graphWriter: GraphWriterPort = deps.graphWriter
    private val platformSettings: Settings = deps.platformSettings
    private val notificationManager: NotificationManager? = deps.notificationManager
    private val journalService: JournalService =
        deps.journalService ?: JournalService(deps.pageRepository, deps.blockRepository)
    private val blockStateManager: BlockStateManager? = deps.blockStateManager
    private val writeActor: DatabaseWriteActor? = deps.writeActor
    private val undoManager: UndoManager? = deps.undoManager
    private val exportService: ExportService? = deps.exportService
    private val histogramWriter: dev.stapler.stelekit.performance.HistogramWriter? = deps.histogramWriter
    private val bugReportBuilder: dev.stapler.stelekit.performance.BugReportBuilder? = deps.bugReportBuilder
    private val debugFlagRepository: dev.stapler.stelekit.performance.DebugFlagRepository? = deps.debugFlagRepository
    private val activeGitSyncService: StateFlow<GitSyncService?> = deps.activeGitSyncService
    private val activeGraphIdProvider: () -> String? = deps.activeGraphIdProvider
    private val onDismissGitDetection: (suspend (graphId: String) -> Unit)? = deps.onDismissGitDetection
    private val spanEmitter = dev.stapler.stelekit.performance.SpanEmitter(deps.ringBuffer)
    // Default scope owns its lifecycle; callers in remember{} must not pass rememberCoroutineScope()
    // which is cancelled when the composable leaves composition. Tests inject a TestCoroutineScope.
    //
    // The CoroutineExceptionHandler is the last line of defense for every coroutine launched on
    // this scope (standing collectors, fire-and-forget launches, stateIn upstreams). Without it,
    // an OutOfMemoryError — which under heap pressure is thrown in whichever coroutine allocates
    // next, not necessarily the one doing the heavy work — reaches the platform default handler.
    // On Android that kills the process ("SteleKit keeps stopping"); on desktop it merely prints,
    // which is why large-graph crashes reproduced only on Android. Surface as fatalError instead
    // so the user gets the recoverable error screen.
    private val scope = CoroutineScope(
        deps.scope.coroutineContext + CoroutineExceptionHandler { _, e ->
            if (e !is CancellationException) {
                logger.error("Uncaught Throwable in ViewModel coroutine — ${e::class.simpleName}: ${e.message}")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        fatalError = "${e::class.simpleName ?: "UnknownError"}: ${sanitizeErrorMessage(e.message)}"
                    )
                }
            }
        }
    )
    private val recentMutex = Mutex()
    private val logger = Logger("StelekitViewModel")

    private fun sanitizeErrorMessage(message: String?): String =
        message
            ?.replace(Regex("/[^\\s,;:]+"), "<path>")
            ?.replace(Regex("[A-Za-z]:\\\\[^\\s,;:]*"), "<path>")
            ?.take(200) ?: "unknown"

    /**
     * Platform-provided callback that opens the image picker and attaches the selected image
     * to the currently editing block. Registered by the host composable (App.kt) once the
     * attachment service is wired. Null when no attachment service is available.
     */
    private var attachImageCallback: (() -> Unit)? = null

    /**
     * Registers the platform callback for attaching images from the command palette.
     * Called from App.kt after the attachment service is set up.
     */
    fun registerAttachImageCallback(callback: (() -> Unit)?) {
        attachImageCallback = callback
        updateCommands()
    }

    // --- Undo / Redo ---
    private val _falseFlow = MutableStateFlow(false)

    /** True when there is at least one undoable operation in the current session. */
    val canUndo: StateFlow<Boolean> = undoManager?.canUndo ?: _falseFlow.asStateFlow()

    /** True when there is at least one redoable operation in the current session. */
    val canRedo: StateFlow<Boolean> = undoManager?.canRedo ?: _falseFlow.asStateFlow()

    /** Undo the most recent block mutation (or batch). No-op when there is nothing to undo. */
    fun undo() {
        val manager = undoManager ?: return
        scope.launch { manager.undo() }
    }

    /** Redo the most recently undone operation. No-op when there is nothing to redo. */
    fun redo() {
        val manager = undoManager ?: return
        scope.launch { manager.redo() }
    }

    // --- Git Sync ---

    /**
     * Emits the current [SyncState] from the active [GitSyncService].
     * Falls back to [SyncState.Idle] when no git sync service is configured.
     */
    val syncState: StateFlow<SyncState> = activeGitSyncService
        .flatMapLatest { service -> service?.syncState ?: flowOf(SyncState.Idle) }
        .stateIn(scope, SharingStarted.Eagerly, SyncState.Idle)

    private fun observeSyncState() {
        scope.launch {
            syncState.collect { state ->
                when (state) {
                    is SyncState.ConflictPending -> _uiState.update { it.copy(conflictResolutionVisible = true) }
                    is SyncState.JournalMergeReady -> _uiState.update { it.copy(journalMergeReviewVisible = true) }
                    // Do NOT auto-dismiss journalMergeReviewVisible here — dismissal is handled
                    // explicitly by abortJournalMerge() and acceptJournalMerge(). Auto-dismissal
                    // races with fetchOnly background calls that emit Fetching/Pushing states.
                    else -> Unit
                }
            }
        }
    }

    /** Triggers a full sync (commit → fetch → merge → push) on the active graph. */
    fun triggerSync() {
        val graphId = activeGraphIdProvider() ?: _uiState.value.currentGraphId ?: return
        scope.launch {
            activeGitSyncService.value?.sync(graphId)
        }
    }

    /** Triggers a fetch-only check for remote changes on the active graph. */
    fun triggerFetchOnly() {
        val graphId = activeGraphIdProvider() ?: _uiState.value.currentGraphId ?: return
        scope.launch {
            activeGitSyncService.value?.fetchOnly(graphId)
        }
    }

    /** Opens the git setup wizard. */
    fun openGitSetup() {
        _uiState.update { it.copy(gitSetupVisible = true) }
    }

    /** Dismisses the git setup wizard. */
    fun dismissGitSetup() {
        _uiState.update { it.copy(gitSetupVisible = false, gitSetupInitialStep = 1, gitSetupOpenForClone = false) }
    }

    /** Opens the git setup wizard pre-navigated to Step 3 (credentials). */
    fun openGitSetupForCredentials() {
        _uiState.update { it.copy(gitSetupVisible = true, gitSetupInitialStep = 3) }
    }

    /** Opens the git setup wizard in clone-from-URL mode (pre-selects clone, starts at step 2). */
    fun openGitSetupForClone() {
        _uiState.update { it.copy(gitSetupVisible = true, gitSetupInitialStep = 2, gitSetupOpenForClone = true) }
    }

    /** Dismisses the conflict resolution screen. */
    fun dismissConflictResolution() {
        _uiState.update { it.copy(conflictResolutionVisible = false) }
    }

    /** Dismisses the journal merge review screen without applying the merge. */
    fun dismissJournalMergeReview() {
        _uiState.update { it.copy(journalMergeReviewVisible = false) }
    }

    /**
     * Aborts the in-progress git merge and dismisses the review screen.
     * Called when the user dismisses or falls back to manual resolution.
     */
    fun abortJournalMerge() {
        val state = syncState.value as? SyncState.JournalMergeReady ?: run {
            _uiState.update { it.copy(journalMergeReviewVisible = false) }
            return
        }
        _uiState.update { it.copy(journalMergeReviewVisible = false) }
        scope.launch {
            // Re-validate: syncState may have advanced (e.g. auto-completed) between capture and execution
            if (syncState.value !is SyncState.JournalMergeReady) return@launch
            activeGitSyncService.value?.abortActiveMerge(state.graphId)
        }
    }

    /**
     * Applies the user-approved merged content for a journal conflict: writes to disk,
     * marks resolved, commits, reloads, and pushes.
     */
    fun acceptJournalMerge(mergedContent: String) {
        val state = syncState.value as? SyncState.JournalMergeReady ?: return
        _uiState.update { it.copy(journalMergeReviewVisible = false) }
        scope.launch {
            // Re-validate: syncState may have advanced between capture and execution
            if (syncState.value !is SyncState.JournalMergeReady) return@launch
            activeGitSyncService.value?.applyJournalMerge(
                graphId = state.graphId,
                filePath = state.proposal.filePath,
                mergedContent = mergedContent,
            )
        }
    }

    /** Dismisses the git auto-detection banner for the given graph. */
    fun dismissGitDetection(graphId: String) {
        scope.launch {
            onDismissGitDetection?.invoke(graphId)
        }
    }

    // Track recent pages manually to avoid "recently loaded" issues
    private var recentPageUuids: MutableList<String> = mutableListOf()

    private val recentPagesKey: String
        get() = "recent_pages_${_uiState.value.currentGraphPath}"

    // Resolved Page objects for the recent-pages list, keyed by UUID and bounded by
    // recentPageUuids (≤20 entries). Replaces the former cachedAllPages field, which
    // pinned the entire pages table (8 000+ Page objects on large graphs) in memory
    // for the lifetime of the ViewModel.
    private val recentPagesByUuid = mutableMapOf<String, Page>()

    // Initialize command system
    private val commandManager = CommandManager.create(scope) { message, type, timeout ->
        notificationManager?.show(message, type, timeout)
    }
    
    // Rename orchestrator — coordinates DB updates + disk file rewrites.
    // Lazy so tests that don't exercise rename don't fail on a missing actor at construction time.
    private val backlinkRenamer by lazy {
        BacklinkRenamer(
            pageRepository, blockRepository,
            graphWriter,
            requireNotNull(writeActor) { "writeActor is required for rename operations" }
        )
    }


    // Page-name suggestion index — drives highlight/link suggestion feature
    val pageNameIndex = PageNameIndex(pageRepository, scope)

    /** Pre-built matcher for the current graph's page names. Null until pages are loaded. */
    val suggestionMatcher: StateFlow<AhoCorasickMatcher?> = pageNameIndex.matcher

    private val _uiState = MutableStateFlow(
        AppState(
            isLoading = true,
            onboardingCompleted = platformSettings.getBoolean("onboardingCompleted", false),
            currentGraphPath = platformSettings.getString("lastGraphPath", ""),
            isLeftHanded = platformSettings.getBoolean("isLeftHanded", false),
            isLibsqlDriverEnabled = platformSettings.getBoolean("db.libsql.enabled", false),
        )
    )
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    private val _indexingProgress = MutableStateFlow<IndexingState>(IndexingState.Idle)
    val indexingProgress: StateFlow<IndexingState> = _indexingProgress.asStateFlow()

    init {
        blockStateManager?.let { graphLoader.setActivePageUuids(it.activePageUuids) }
        blockStateManager?.let { graphLoader.setUnsavedPageUuids(it.dirtyPageUuids) }

        updateCommands()
        observeSyncState()

        // Initialize graph if path exists
        val path = _uiState.value.currentGraphPath
        val onboarded = _uiState.value.onboardingCompleted
        logger.info("init: lastGraphPath='$path' onboardingCompleted=$onboarded")
        if (path.isNotEmpty() && onboarded) {
            loadGraph(path)
        }
        
        // Observe repository changes for favorites and reactive updates
        // Note: For large graphs, we avoid observing getAllPages()
        observeSpecialPages()
    }

    private val pageSize = 50

    private fun observeSpecialPages() {
        scope.launch {
            // Load recents for the current graph before starting collection
            recentMutex.withLock {
                recentPageUuids = platformSettings.getString(recentPagesKey, "")
                    .split(",")
                    .filter { it.isNotEmpty() }
                    .toMutableList()
            }
            refreshRecentPages()

            // Favorites for the sidebar via the dedicated bounded query. Never collect
            // getAllPages() from a standing observer: every DB write invalidates that query,
            // so during graph import/reconcile the collector re-materializes the entire
            // pages table over and over — on 8 000+ page graphs this causes GC thrash
            // (UI hang) and eventually OutOfMemoryError on Android.
            pageRepository.getFavoritePages().collect { result ->
                val favorites = result.getOrNull() ?: emptyList()
                _uiState.update { it.copy(favoritePages = favorites) }
            }
        }

        // Initial load of regular pages and journals
        loadMoreRegularPages(reset = true)
        loadMoreJournalPages(reset = true)
    }

    /**
     * Re-resolves [recentPageUuids] into Page objects via point lookups (≤10 indexed
     * queries) and publishes them to the UI state. Cheap by construction — never scans
     * the pages table.
     *
     * Snapshots the UUID list under [recentMutex], releases the lock, performs DB work
     * outside the lock to avoid starving [addToRecent], then re-acquires to write results.
     */
    private suspend fun refreshRecentPages() {
        val uuidsToResolve = recentMutex.withLock { recentPageUuids.take(10).toList() }

        val resolved = uuidsToResolve.mapNotNull { uuid ->
            val cached = recentMutex.withLock { recentPagesByUuid[uuid] }
            cached ?: pageRepository.getPageByUuid(PageUuid(uuid)).first().getOrNull()
                ?.also { page -> recentMutex.withLock { recentPagesByUuid[uuid] = page } }
        }

        recentMutex.withLock {
            trimRecentPagesCache()
        }
        _uiState.update { it.copy(recentPages = resolved) }
    }

    private fun trimRecentPagesCache() {
        recentPagesByUuid.keys.retainAll(recentPageUuids.toSet())
    }

    fun loadMoreRegularPages(reset: Boolean = false) {
        if (_uiState.value.isLoadingMorePages) return  // already loading
        val currentOffset = if (reset) 0 else _uiState.value.regularPagesOffset
        if (!reset && !_uiState.value.hasMoreRegularPages) return

        _uiState.update { it.copy(isLoadingMorePages = true) }
        scope.launch {
            pageRepository.getPages(limit = pageSize, offset = currentOffset).first().onRight { newPages ->
                _uiState.update { state ->
                    val updatedList = if (reset) newPages else state.regularPages + newPages
                    state.copy(
                        regularPages = updatedList,
                        regularPagesOffset = currentOffset + newPages.size,
                        hasMoreRegularPages = newPages.size == pageSize,
                        isLoadingMorePages = false
                    )
                }
            }.onLeft {
                _uiState.update { it.copy(isLoadingMorePages = false) }
            }
        }
    }

    fun loadMoreJournalPages(reset: Boolean = false) {
        // Implementation for journals if needed, similar to regular pages
        // Currently JournalsViewModel handles its own pagination, but StelekitViewModel 
        // also has journalPages in AppState for the sidebar/summary.
        scope.launch {
            journalService.getJournalPages(limit = 20, offset = 0).first().onRight { pages ->
                _uiState.update { it.copy(journalPages = pages) }
            }
        }
    }

    private fun addToRecent(page: Page) {
        scope.launch {
            recentMutex.withLock {
                recentPageUuids.remove(page.uuid.value)
                recentPageUuids.add(0, page.uuid.value)
                if (recentPageUuids.size > 20) {
                    recentPageUuids.removeAt(recentPageUuids.lastIndex)
                }
                platformSettings.putString(recentPagesKey, recentPageUuids.joinToString(","))
                recentPagesByUuid[page.uuid.value] = page
                trimRecentPagesCache()
                val recent = recentPageUuids.mapNotNull { recentPagesByUuid[it] }.take(10)
                _uiState.update { it.copy(recentPages = recent) }
            }
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    fun triggerReindex() {
        val path = _uiState.value.currentGraphPath
        if (path.isEmpty()) return
        
        scope.launch {
            logger.info("Manually triggering re-index for $path")
            _uiState.update { it.copy(statusMessage = "Clearing database...") }
            
            // Clear repositories
            pageRepository.clear()
            blockRepository.clear()
            
            // Clear cached path to force GraphLoader to do a full scan
            platformSettings.putString("cached_graph_path", "")
            
            // Reload
            loadGraph(path)
        }
    }

    fun setGraphPath(path: String) {
        logger.info("setGraphPath: '$path'")
        platformSettings.putString("lastGraphPath", path)
        _uiState.update { it.copy(currentGraphPath = path) }
        scope.launch {
            recentMutex.withLock {
                recentPageUuids = platformSettings.getString(recentPagesKey, "")
                    .split(",").filter { it.isNotEmpty() }.toMutableList()
                recentPagesByUuid.clear()
            }
            // refreshRecentPages() is deferred to onPhase1Complete inside loadGraph so that
            // getPageByUuid lookups run after the new graph's DB is populated, not while
            // loadGraph is mid-clear.
        }
        loadGraph(path)
    }

    @OptIn(DirectRepositoryWrite::class)
    fun loadGraph(path: String) {
        // Set loading state synchronously so callers observe isFullyLoaded=false immediately,
        // eliminating the race where StateFlow.first{isFullyLoaded} catches the initial default.
        // fatalError is cleared so a stale error overlay never persists over a new successful load.
        _uiState.update { it.copy(isLoading = true, isFullyLoaded = false, statusMessage = "Loading graph from $path...", fatalError = null) }
        _indexingProgress.value = IndexingState.Idle
        val job = scope.launch {
            try {
                var graphExists = fileSystem.directoryExists(path)

                if (!graphExists) {
                    _uiState.update { it.copy(statusMessage = "Creating directory $path...") }
                    logger.info("Creating graph directory: $path")
                    val created = fileSystem.createDirectory(path)
                    if (created) graphExists = true
                }

                if (graphExists) {
                    _uiState.update { it.copy(statusMessage = "Checking database...") }
                    
                    val pageCountResult = pageRepository.countPages().first()
                    val pageCount = pageCountResult.getOrNull() ?: 0L
                    
                    logger.info("Loading graph progressively from: $path (Page count: $pageCount)")
                    
                    var cachedPath = platformSettings.getString("cached_graph_path", "")
                    
                    if (pageCount == 0L) {
                        logger.info("Database is empty - forcing full re-index")
                        cachedPath = ""
                    }
                    
                    if (path != cachedPath) {
                        logger.info("Switching graph from '$cachedPath' to '$path' - Clearing persistent cache")
                        pageRepository.clear()
                        blockRepository.clear()
                        platformSettings.putString("cached_graph_path", path)
                    } else {
                        logger.info("Loading same graph '$path' - Keeping persistent cache for incremental load")
                    }

                    withContext(Dispatchers.Default) {
                        graphLoader.loadGraphProgressive(
                            graphPath = path,
                            immediateJournalCount = 10,
                            onProgress = { status ->
                                _uiState.update { it.copy(statusMessage = status) }
                            },
                            onPhase1Complete = {
                                logger.info("Phase 1 complete - UI is now interactive")
                                _uiState.update { it.copy(isLoading = false, statusMessage = "Ready") }

                                // Resolve saved recents now that Phase 1 has populated the DB.
                                // Running before loadGraph is finished would race with clear().
                                scope.launch { refreshRecentPages() }

                                // Ensure today's journal exists so it appears at the top of the
                                // journals list. No navigation — the list updates reactively.
                                scope.launch { journalService.ensureTodayJournal() }

                                startMidnightBoundaryWatcher()
                            },
                            onFullyLoaded = {
                                logger.info("Graph fully loaded")
                                _uiState.update { it.copy(isFullyLoaded = true, statusMessage = "Graph loaded completely.") }

                                // Start background full-indexing only after loadDirectory(METADATA_ONLY)
                                // has finished. Launching this earlier races with the batch loader:
                                // both paths generate identical deterministic UUIDs and interleaved
                                // delete+insert sequences cause UNIQUE constraint violations.
                                scope.launch(CoroutineName("lazy-phase3")) {
                                    delay(500) // let UI settle after Phase 1
                                    _indexingProgress.value = IndexingState.InProgress("Indexing pages...")
                                    try {
                                        graphLoader.indexRemainingPages { progress ->
                                            _indexingProgress.value = IndexingState.InProgress(progress)
                                        }
                                        _indexingProgress.value = IndexingState.Complete
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        _indexingProgress.value = IndexingState.Idle
                                        throw e
                                    } catch (e: Throwable) {
                                        // Catch Throwable (not just Exception) so OutOfMemoryError
                                        // during 8000+ page indexing doesn't crash via the default
                                        // uncaught exception handler.
                                        logger.error("Background indexing failed: ${e::class.simpleName}: ${e.message}")
                                        _indexingProgress.value = IndexingState.Idle
                                    }
                                }
                            }
                        )
                    }

                    logger.info("Graph loaded successfully")
                } else {
                    logger.error("Failed to load graph at $path — clearing stale path, graph_registry, and resetting onboarding")
                    platformSettings.putString("lastGraphPath", "")
                    platformSettings.putBoolean("onboardingCompleted", false)
                    // Also clear the graph_registry so GraphManager doesn't restore
                    // a GraphInfo that points to this invalid path on the next launch.
                    platformSettings.putString("graph_registry", "")
                    _uiState.update {
                        it.copy(
                            currentGraphPath = "",
                            onboardingCompleted = false,
                            isLoading = false,
                            isFullyLoaded = true,
                            statusMessage = ""
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal structured-concurrency cancellation (e.g. scope shut down when
                // GraphContent leaves composition). Reset loading state so the UI doesn't
                // spin forever if this ViewModel is somehow still observed, then rethrow
                // so the coroutine machinery can clean up correctly.
                logger.info("loadGraph cancelled for path '$path' — scope is shutting down")
                _uiState.update { it.copy(isLoading = false) }
                throw e
            } catch (e: Exception) {
                val errorText = buildString {
                    append(e::class.simpleName ?: e::class.qualifiedName ?: "UnknownError")
                    e.message?.let { append(": ", sanitizeErrorMessage(it)) }
                }
                e.printStackTrace()
                logger.error("Error loading graph: $errorText")
                _uiState.update { it.copy(isLoading = false, isFullyLoaded = true, statusMessage = "Error: $errorText", fatalError = errorText) }
            } catch (e: Throwable) {
                // OutOfMemoryError, NoClassDefFoundError, and other JVM errors must not crash
                // the app silently. Surface as a recoverable error state so the user sees
                // a message instead of a black screen. fatalError is shown on the error
                // report screen where the user can copy the full message for filing a bug.
                val errorText = buildString {
                    append(e::class.simpleName ?: e::class.qualifiedName ?: "UnknownError")
                    e.message?.let { append(": ", sanitizeErrorMessage(it)) }
                }
                logger.error("Fatal error loading graph (Throwable): $errorText")
                _uiState.update { it.copy(isLoading = false, isFullyLoaded = true, statusMessage = "Error: $errorText", fatalError = errorText) }
            }
        }
        // Guarantee isLoading resets if the job is cancelled before reaching its first
        // suspension point (i.e. scope.cancel() called before the coroutine starts running).
        job.invokeOnCompletion { cause ->
            if (cause is kotlinx.coroutines.CancellationException) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun refreshCurrentPage() {
        val currentScreen = _uiState.value.currentScreen
        if (currentScreen is Screen.PageView) {
            scope.launch {
                pageRepository.getPageByUuid(currentScreen.page.uuid).first().getOrNull()?.let { updatedPage ->
                    _uiState.update { it.copy(currentScreen = Screen.PageView(updatedPage)) }
                }
            }
        }
    }

    fun reloadCurrentPageFromDisk() {
        val currentScreen = _uiState.value.currentScreen
        if (currentScreen is Screen.PageView) {
            scope.launch {
                _uiState.update { it.copy(isContentFetching = true) }
                try {
                    graphLoader.loadFullPage(currentScreen.page.uuid.value, force = true)
                    refreshCurrentPage()
                } finally {
                    _uiState.update { it.copy(isContentFetching = false) }
                }
            }
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    fun toggleFavorite(page: Page) {
        scope.launch {
            pageRepository.toggleFavorite(page.uuid)
            _uiState.update { it.copy(statusMessage = "Toggled favorite: ${page.name}") }
            refreshCurrentPage()
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    fun toggleFavorite(pageUuid: String) {
        scope.launch {
            pageRepository.toggleFavorite(PageUuid(pageUuid))
            refreshCurrentPage()
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    fun clear() {
        scope.launch {
            pageRepository.clear()
            blockRepository.clear()
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    fun indentBlock(blockUuid: String) {
        scope.launch {
            blockRepository.indentBlock(BlockUuid(blockUuid))
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    fun outdentBlock(blockUuid: String) {
        scope.launch {
            blockRepository.outdentBlock(BlockUuid(blockUuid))
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    fun moveBlockUp(blockUuid: String) {
        scope.launch {
            blockRepository.moveBlockUp(BlockUuid(blockUuid))
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    fun moveBlockDown(blockUuid: String) {
        scope.launch {
            blockRepository.moveBlockDown(BlockUuid(blockUuid))
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    fun moveBlock(blockUuid: String, newParentUuid: String?, newPosition: String) {
        scope.launch {
            blockRepository.moveBlock(BlockUuid(blockUuid), newParentUuid?.let { BlockUuid(it) }, newPosition)
        }
    }

    fun requestEditBlock(blockUuid: String?, cursorIndex: Int? = null) {
        _uiState.update { it.copy(editingBlockId = blockUuid, editingCursorIndex = cursorIndex) }
    }

    @OptIn(DirectRepositoryWrite::class)
    fun addNewBlock(currentBlockUuid: String) {
        scope.launch {
            val currentBlockResult = blockRepository.getBlockByUuid(BlockUuid(currentBlockUuid)).first()
            val currentBlock = currentBlockResult.getOrNull() ?: return@launch

            val siblingsResult = blockRepository.getBlockSiblings(BlockUuid(currentBlockUuid)).first()
            val siblings = siblingsResult.getOrNull() ?: emptyList()

            val newPosition = currentBlock.position + 1
            
            // Shift siblings
            val siblingsToShift = siblings.filter { it.position >= newPosition }
            val updatedSiblings = siblingsToShift.map { it.copy(position = it.position + 1) }

            val now = kotlin.time.Clock.System.now()
            val newBlock = Block(
                uuid = BlockUuid(generateUuid()),
                pageUuid = currentBlock.pageUuid,
                parentUuid = currentBlock.parentUuid,
                leftUuid = currentBlock.uuid.value,
                content = "",
                level = currentBlock.level,
                position = newPosition,
                createdAt = now,
                updatedAt = now,
                properties = emptyMap(),
                isLoaded = true
            )

            val blocksToSave = updatedSiblings + newBlock
            blockRepository.saveBlocks(blocksToSave)

            requestEditBlock(newBlock.uuid.value)
        }
    }

    /**
     * Add a new block to the end of a page
     */
    @OptIn(DirectRepositoryWrite::class)
    fun addBlockToPage(pageUuid: String) {
        scope.launch {
            val pageResult = pageRepository.getPageByUuid(PageUuid(pageUuid)).first()
            val page = pageResult.getOrNull() ?: return@launch

            val blocksResult = blockRepository.getBlocksForPage(page.uuid).first()
            val blocks = blocksResult.getOrNull() ?: emptyList()
            
            // Filter only top-level blocks (no parent)
            val topLevelBlocks = blocks.filter { it.parentUuid == null }.sortedBy { it.position }
            val lastBlock = topLevelBlocks.lastOrNull()
            
            val newPosition = dev.stapler.stelekit.util.FractionalIndexing.generateKeyBetween(lastBlock?.position, null)
            val now = kotlin.time.Clock.System.now()
            
            val newBlock = Block(
                uuid = BlockUuid(generateUuid()),
                pageUuid = page.uuid,
                parentUuid = null,
                leftUuid = lastBlock?.uuid?.value,
                content = "",
                level = 0,
                position = newPosition,
                createdAt = now,
                updatedAt = now,
                properties = emptyMap(),
                isLoaded = true
            )

            blockRepository.saveBlock(newBlock)
            requestEditBlock(newBlock.uuid.value)
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    fun splitBlock(blockUuid: String, cursorPosition: Int) {
        scope.launch {
            blockRepository.splitBlock(BlockUuid(blockUuid), cursorPosition).onRight { newBlock ->
                requestEditBlock(newBlock.uuid.value)
            }
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    fun mergeBlock(blockUuid: String) {
        scope.launch {
            val currentBlockResult = blockRepository.getBlockByUuid(BlockUuid(blockUuid)).first()
            val currentBlock = currentBlockResult.getOrNull() ?: return@launch

            // Get ALL siblings including current block
            val pageBlocksResult = blockRepository.getBlocksForPage(currentBlock.pageUuid).first()
            val allBlocks = pageBlocksResult.getOrNull() ?: return@launch
            val siblings = allBlocks
                .filter { it.parentUuid == currentBlock.parentUuid }
                .sortedBy { it.position }

            val currentIndex = siblings.indexOfFirst { it.uuid == currentBlock.uuid }

            if (currentIndex > 0) {
                val prevBlock = siblings[currentIndex - 1]
                blockRepository.mergeBlocks(prevBlock.uuid, BlockUuid(blockUuid), "").onRight {
                    requestEditBlock(prevBlock.uuid.value, prevBlock.content.length)
                }
            }
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    fun handleBackspace(blockUuid: String) {
        scope.launch {
            val blockUuidTyped = BlockUuid(blockUuid)
            val currentBlockResult = blockRepository.getBlockByUuid(blockUuidTyped).first()
            val currentBlock = currentBlockResult.getOrNull() ?: return@launch

            val pageBlocksResult = blockRepository.getBlocksForPage(currentBlock.pageUuid).first()
            val allBlocks = pageBlocksResult.getOrNull() ?: return@launch
            val siblings = allBlocks
                .filter { it.parentUuid == currentBlock.parentUuid }
                .sortedBy { it.position }

            val currentIndex = siblings.indexOfFirst { it.uuid == currentBlock.uuid }

            if (currentIndex > 0) {
                val previousBlock = siblings[currentIndex - 1]
                blockRepository.deleteBlock(blockUuidTyped)
                requestEditBlock(previousBlock.uuid.value, previousBlock.content.length)
            } else if (currentBlock.parentUuid != null) {
                val parent = allBlocks.find { it.uuid.value == currentBlock.parentUuid }
                blockRepository.deleteBlock(blockUuidTyped)
                if (parent != null) {
                    requestEditBlock(parent.uuid.value, parent.content.length)
                }
            } else if (siblings.size > 1) {
                val nextBlock = siblings[1]
                blockRepository.deleteBlock(blockUuidTyped)
                requestEditBlock(nextBlock.uuid.value, 0)
            }
        }
    }

    fun focusPreviousBlock(blockUuid: String) {
        scope.launch {
            val currentBlockResult = blockRepository.getBlockByUuid(BlockUuid(blockUuid)).first()
            val currentBlock = currentBlockResult.getOrNull() ?: return@launch

            val pageBlocksResult = blockRepository.getBlocksForPage(currentBlock.pageUuid).first()
            val allBlocks = pageBlocksResult.getOrNull() ?: return@launch
            val sortedBlocks = dev.stapler.stelekit.outliner.BlockSorter.sort(allBlocks)

            val currentIndex = sortedBlocks.indexOfFirst { it.uuid.value == blockUuid }

            if (currentIndex > 0) {
                val prevBlock = sortedBlocks[currentIndex - 1]
                requestEditBlock(prevBlock.uuid.value, prevBlock.content.length)
            }
        }
    }

    fun focusNextBlock(blockUuid: String) {
        scope.launch {
            val currentBlockResult = blockRepository.getBlockByUuid(BlockUuid(blockUuid)).first()
            val currentBlock = currentBlockResult.getOrNull() ?: return@launch

            val pageBlocksResult = blockRepository.getBlocksForPage(currentBlock.pageUuid).first()
            val allBlocks = pageBlocksResult.getOrNull() ?: return@launch
            val sortedBlocks = dev.stapler.stelekit.outliner.BlockSorter.sort(allBlocks)

            val currentIndex = sortedBlocks.indexOfFirst { it.uuid.value == blockUuid }

            if (currentIndex != -1 && currentIndex < sortedBlocks.size - 1) {
                val nextBlock = sortedBlocks[currentIndex + 1]
                requestEditBlock(nextBlock.uuid.value, 0)
            }
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    fun navigateTo(screen: Screen, addToHistory: Boolean = true) {
        val navStart = kotlin.time.Clock.System.now().toEpochMilliseconds()
        // addToRecent must run outside the update lambda — it has side effects (launches a
        // coroutine, calls platformSettings) and calls _uiState.update itself, which would
        // create a nested update.
        if (screen is Screen.PageView) addToRecent(screen.page)
        _uiState.update { state ->
            val newHistory = if (addToHistory) {
                // Trim any forward history and add new screen
                val trimmed = state.navigationHistory.take(state.historyIndex + 1)
                trimmed + screen
            } else {
                state.navigationHistory
            }
            val newIndex = if (addToHistory) newHistory.size - 1 else state.historyIndex

            state.copy(
                currentScreen = screen,
                currentPage = if (screen is Screen.PageView) screen.page else null,
                navigationHistory = newHistory,
                historyIndex = newIndex,
                statusMessage = when(screen) {
                    is Screen.PageView -> "Opened page: ${screen.page.name}"
                    is Screen.Journals -> "Opened Journals"
                    is Screen.Flashcards -> "Opened Flashcards"
                    is Screen.AllPages -> "Opened All Pages"
                    is Screen.Notifications -> "Opened Notifications"
                    is Screen.Logs -> "Opened Logs"
                    is Screen.Performance -> "Opened Performance"
                    is Screen.GlobalUnlinkedReferences -> "Opened Unlinked References"
                    is Screen.Import -> "Import text as new page"
                    is Screen.LibraryStats -> "Opened Library Stats"
                    is Screen.VaultUnlock -> "Vault locked"
                    is Screen.Gallery -> "Opened Gallery"
                    is Screen.AssetBrowser -> "Opened Asset Browser"
                    is Screen.AnnotationEditor -> "Opened Annotation Editor"
                }
            )
        }
        val navEnd = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val navDurationMs = navEnd - navStart
        histogramWriter?.record("navigation", navDurationMs)
        spanEmitter.emit(
            name = "navigation",
            startMs = navStart,
            endMs = navEnd,
            attrs = mapOf(
                "screen" to screen::class.simpleName.orEmpty(),
                "duration.ms" to navDurationMs.toString(),
            )
        )
        if (screen is Screen.PageView) {
            refreshCurrentPage()
            // Skip loadFullPage when a pending conflict exists — the DB has the user's edits
            // and loading from disk would overwrite them before the conflict dialog appears.
            if (!checkAndShowPendingConflict(screen)) {
                scope.launch {
                    _uiState.update { it.copy(isContentFetching = true) }
                    try {
                        // Re-read from disk on every navigation so stale in-memory copies are evicted.
                        // Uses the mtime guard internally so this is cheap when nothing changed.
                        graphLoader.loadFullPage(screen.page.uuid.value)
                    } finally {
                        _uiState.update { it.copy(isContentFetching = false) }
                    }
                }
            }
            // Fire-and-forget visit tracking — does not block navigation
            scope.launch {
                searchRepository.recordPageVisit(screen.page.uuid)
            }
        }
        updateCommands()
    }

    /**
     * Navigate back in history (Alt+Left or Cmd+[)
     */
    fun goBack(): Boolean {
        val state = _uiState.value
        if (!state.canGoBack) return false

        val newIndex = state.historyIndex - 1
        val screen = state.navigationHistory[newIndex]
        _uiState.update {
            it.copy(
                currentScreen = screen,
                currentPage = if (screen is Screen.PageView) screen.page else null,
                historyIndex = newIndex,
                statusMessage = "Back"
            )
        }
        updateCommands()
        checkAndShowPendingConflict(screen)
        return true
    }

    /**
     * Navigate forward in history (Alt+Right or Cmd+])
     */
    fun goForward(): Boolean {
        val state = _uiState.value
        if (!state.canGoForward) return false

        val newIndex = state.historyIndex + 1
        val screen = state.navigationHistory[newIndex]
        _uiState.update {
            it.copy(
                currentScreen = screen,
                currentPage = if (screen is Screen.PageView) screen.page else null,
                historyIndex = newIndex,
                statusMessage = "Forward"
            )
        }
        updateCommands()
        checkAndShowPendingConflict(screen)
        return true
    }

    fun navigateTo(destination: String) {
        val newScreen = when (destination) {
            "journals" -> Screen.Journals
            "flashcards" -> Screen.Flashcards
            "all-pages" -> Screen.AllPages
            "notifications" -> Screen.Notifications
            "logs" -> Screen.Logs
            else -> Screen.Journals
        }
        navigateTo(newScreen)
    }

    /**
     * Navigate to a page by its name (used for wiki links)
     * Creates the page if it doesn't exist
     */
    fun navigateToPageByName(pageName: String) {
        scope.launch {
            // Try to find the page in the DB first
            val existingPage = pageRepository.getPageByName(pageName).first().getOrNull()
            if (existingPage != null) {
                navigateTo(Screen.PageView(existingPage))
                return@launch
            }

            // Page not in DB — check if it exists on disk and priority-load it
            val loadedPage = graphLoader.loadPageByName(PageName(pageName))
            if (loadedPage != null) {
                navigateTo(Screen.PageView(loadedPage))
                return@launch
            }

            // File doesn't exist on disk either — create a new empty page
            val newPage = createPage(pageName)
            if (newPage != null) {
                navigateTo(Screen.PageView(newPage))
            } else {
                _uiState.update { it.copy(statusMessage = "Failed to create page: $pageName") }
            }
        }
    }
    
    fun navigateToPageByUuid(pageUuid: String) {
        scope.launch {
            val page = pageRepository.getPageByUuid(PageUuid(pageUuid)).first().getOrNull()
            if (page != null) {
                navigateTo(Screen.PageView(page))
            } else {
                _uiState.update { it.copy(statusMessage = "Page not found: $pageUuid") }
            }
        }
    }
    
    /**
     * Navigate to the annotation editor for [imageAnnotationUuid].
     *
     * [pageUuid] is optional — if provided it is stored in the screen so the editor can
     * expose a "Go to page" action that returns here.
     */
    fun navigateToAnnotationEditor(imageAnnotationUuid: String, pageUuid: String? = null) {
        navigateTo(Screen.AnnotationEditor(imageAnnotationUuid = imageAnnotationUuid, pageUuid = pageUuid))
    }

    /** Navigate to the image gallery screen. */
    fun navigateToGallery() {
        navigateTo(Screen.Gallery)
    }

    fun navigateToBlock(blockUuid: String) {
        scope.launch {
            val block = blockRepository.getBlockByUuid(BlockUuid(blockUuid)).first().getOrNull()
            if (block != null) {
                val page = pageRepository.getPageByUuid(block.pageUuid).first().getOrNull()
                if (page != null) {
                    navigateTo(Screen.PageView(page))
                    // TODO: Scroll to block
                }
            }
        }
    }

    /**
     * Bulk delete pages by UUID. Deletes from database and removes disk files.
     * Refreshes the regular pages list after deletion.
     */
    @OptIn(DirectRepositoryWrite::class)
    fun bulkDeletePages(uuids: List<String>) {
        scope.launch {
            uuids.forEach { uuid ->
                try {
                    // Look up page to get file path before deleting
                    val page = pageRepository.getPageByUuid(PageUuid(uuid)).first().getOrNull()
                    pageRepository.deletePage(PageUuid(uuid))
                    // Remove from disk if file path is known
                    page?.filePath?.takeIf { it.isNotBlank() }?.let { path ->
                        fileSystem.deleteFile(path)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Failed to delete page $uuid", e)
                }
            }
            loadMoreRegularPages(reset = true)
        }
    }

    /**
     * Create a new page with the given name
     */
    @OptIn(DirectRepositoryWrite::class)
    private suspend fun createPage(pageName: String): Page? {
        return try {
            val now = kotlin.time.Clock.System.now()
            val uuid = generateUuid()

            // Detect if this is a journal page (matches date patterns like 2026-01-21 or 2026_01_21)
            val isJournal = pageName.matches(Regex("^\\d{4}[-_]\\d{2}[-_]\\d{2}$"))

            val newPage = Page(
                uuid = PageUuid(uuid),
                name = pageName,
                namespace = null,
                filePath = null, // Will be set when saving
                createdAt = now,
                updatedAt = now,
                properties = emptyMap(),
                isFavorite = false,
                isJournal = isJournal
            )

            if (writeActor != null) {
                writeActor.savePage(newPage)
            } else {
                pageRepository.savePage(newPage)
            }
            logger.info("Created new page: $pageName")
            newPage
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to create page: $pageName", e)
            null
        }
    }

    /**
     * Generate a UUID for new entities
     */
    private fun generateUuid(): String {
        return dev.stapler.stelekit.util.UuidGenerator.generateV7()
    }

    /**
     * Get the content of a block by its UUID
     */
    suspend fun getBlockContent(blockUuid: String): String? {
        val blockResult = blockRepository.getBlockByUuid(BlockUuid(blockUuid)).first()
        return blockResult.getOrNull()?.content
    }

    /**
     * Start the auto-save processor and subscribe to external file-change events
     * from GraphLoader to detect editing conflicts.
     */
    fun startAutoSave() {
        graphWriter.startAutoSave()
        observeExternalFileChanges()
        observeWriteErrors()
        logger.info("Auto-save started")
    }

    /**
     * Called by the host Activity when the OS signals memory pressure
     * (TRIM_MEMORY_RUNNING_CRITICAL). Cancels any in-flight background indexing so the
     * coroutine and its parse buffers can be reclaimed immediately.
     */
    fun onMemoryPressure() {
        graphLoader.cancelBackgroundWork()
    }

    fun close() {
        scope.cancel()
    }

    /**
     * Collects [GraphLoader.externalFileChanges] and intercepts changes to pages
     * the user is currently editing, surfacing a conflict dialog instead of
     * silently reloading.
     *
     * Using a SharedFlow keeps GraphLoader free of any UI/ViewModel dependency
     * (ISP: GraphLoader only knows about file events, not editing state).
     */
    private fun observeExternalFileChanges() {
        scope.launch {
            graphLoader.externalFileChanges.collect { event ->
                val state = _uiState.value
                val editingBlockUuid = state.editingBlockId
                val currentPage = (state.currentScreen as? Screen.PageView)?.page
                if (currentPage == null || currentPage.filePath != event.filePath) {
                    // User is not currently viewing this page. Suppress auto-reimport so the
                    // DB keeps the user's edits, store the disk content, and notify via snackbar.
                    val existing = state.pendingConflicts[event.filePath]
                    event.suppress()
                    if (existing == null || existing.diskContent != event.content) {
                        val pageName = event.filePath
                            .substringAfterLast('/').removeSuffix(".md").replace("_", " ")
                        _uiState.update { it.copy(
                            pendingConflicts = it.pendingConflicts + (event.filePath to PendingConflict(
                                filePath = event.filePath,
                                pageName = pageName,
                                diskContent = event.content,
                            ))
                        )}
                        if (existing == null) {
                            sendSnackbar("\"$pageName\" was modified on disk — open it to review")
                        }
                    }
                    return@collect
                }

                // Evict only this page's hierarchy cache so unrelated pages stay warm.
                blockStateManager?.cacheEvictPage(currentPage.uuid)

                // Four-tier protection:
                // 1. Actively editing a block right now.
                // 2. Page has dirty blocks (locally-modified, DB save confirmed but not yet
                //    written to disk within the ~300ms debounce window).
                // 3. A debounced disk write is pending — dirty flag was already cleared by DB
                //    confirmation, but the file hasn't landed on disk yet. Without this tier
                //    an external change arriving in that 300ms window bypasses the dialog and
                //    silently overwrites local content.
                // 4. A structural op (split/merge/delete) is in the actor queue but has not
                //    yet committed to the DB. Without this tier an external change arriving
                //    in the actor-queue window silently races with the structural op.
                val dirtyUuids = blockStateManager?.dirtyBlockUuids ?: emptySet()
                val pageHasDirtyBlocks = blockStateManager
                    ?.blocks?.value?.get(currentPage.uuid.value)
                    ?.any { it.uuid.value in dirtyUuids }
                    ?: false
                val hasPendingDiskWrite = blockStateManager?.hasPendingDiskWrite(currentPage.uuid.value) ?: false
                val hasActorPending = blockStateManager?.hasActorPendingWrites ?: false
                val shouldProtect = editingBlockUuid != null || pageHasDirtyBlocks || hasPendingDiskWrite || hasActorPending
                if (!shouldProtect) return@collect

                // Cancel the pending disk write so auto-save cannot overwrite the disk file
                // while the conflict dialog is open. If the user picks "Keep Local", we
                // re-queue the write explicitly in keepLocalChanges().
                blockStateManager?.cancelPendingDiskSave(currentPage.uuid.value)

                // Suppress GraphLoader's automatic re-import — we handle it via the dialog
                event.suppress()

                // Use the actively-editing block for the conflict dialog; fall back to first
                // dirty block on the page if the user has clicked away.
                val conflictBlockUuid = editingBlockUuid
                    ?: dirtyUuids.firstOrNull { uuid ->
                        blockStateManager?.blocks?.value?.get(currentPage.uuid.value)
                            ?.any { it.uuid.value == uuid } == true
                    }
                    ?: return@collect

                // Read the latest local content from BlockStateManager's optimistic state
                val localContent = blockStateManager
                    ?.blocks?.value?.get(currentPage.uuid.value)
                    ?.find { it.uuid.value == conflictBlockUuid }?.content
                    ?: blockRepository.getBlockByUuid(BlockUuid(conflictBlockUuid)).first().getOrNull()?.content
                    ?: ""

                _uiState.update { it.copy(
                    diskConflict = DiskConflict(
                        pageUuid = currentPage.uuid.value,
                        pageName = currentPage.name,
                        filePath = event.filePath,
                        editingBlockUuid = conflictBlockUuid,
                        localContent = localContent,
                        diskContent = event.content
                    )
                )}
            }
        }
    }

    /**
     * If [screen] is a [Screen.PageView] with a stored [PendingConflict], removes it from
     * state, builds a [DiskConflict] from current DB blocks, and shows the conflict dialog.
     * Also skips the normal [GraphLoader.loadFullPage] call so the DB is not overwritten
     * with the disk content before the user gets a chance to choose.
     * Returns true if a pending conflict was found (caller should skip loadFullPage).
     */
    private fun checkAndShowPendingConflict(screen: Screen): Boolean {
        if (screen !is Screen.PageView) return false
        val filePath = screen.page.filePath ?: return false
        val pending = _uiState.value.pendingConflicts[filePath] ?: return false
        _uiState.update { it.copy(pendingConflicts = it.pendingConflicts - filePath) }
        scope.launch {
            val firstBlock = blockRepository.getBlocksForPage(screen.page.uuid)
                .first().getOrNull()?.minByOrNull { it.position }
            _uiState.update { state ->
                state.copy(diskConflict = DiskConflict(
                    pageUuid = screen.page.uuid.value,
                    pageName = screen.page.name,
                    filePath = filePath,
                    editingBlockUuid = firstBlock?.uuid?.value ?: "",
                    localContent = firstBlock?.content ?: "",
                    diskContent = pending.diskContent,
                ))
            }
        }
        return true
    }

    /**
     * Collects [GraphLoader.writeErrors] and surfaces a dismissable error banner in
     * the UI so the user can see that data failed to persist and trigger a retry.
     */
    private fun observeWriteErrors() {
        scope.launch {
            graphLoader.writeErrors.collect { error ->
                val pageName = error.filePath.substringAfterLast("/").removeSuffix(".md")
                val message = if (error.blockCount > 0) {
                    "Failed to save ${error.blockCount} blocks from '$pageName'. Tap to retry indexing."
                } else {
                    "Failed to save page '$pageName'. Tap to retry indexing."
                }
                _uiState.update { it.copy(indexingError = message) }
            }
        }
    }

    fun dismissIndexingError() {
        _uiState.update { it.copy(indexingError = null) }
    }

    fun clearFatalError() {
        _uiState.update { it.copy(fatalError = null) }
    }

    fun retryIndexing() {
        _uiState.update { it.copy(indexingError = null) }
        scope.launch {
            _indexingProgress.value = IndexingState.InProgress("Re-indexing...")
            try {
                graphLoader.indexRemainingPages { /* progress updates can be ignored here */ }
                _indexingProgress.value = IndexingState.Complete
            } catch (e: kotlinx.coroutines.CancellationException) {
                _indexingProgress.value = IndexingState.Idle
                throw e
            } catch (e: Exception) {
                _indexingProgress.value = IndexingState.Idle
            }
        }
    }

    /**
     * Resolve disk conflict: keep the user's in-progress edits and re-queue a
     * save so the local version wins on disk.
     */
    fun keepLocalChanges() {
        val conflict = _uiState.value.diskConflict ?: return
        _uiState.update { it.copy(diskConflict = null) }
        // Re-queue a save for the current page so local content overwrites the disk file
        val currentPage = (uiState.value.currentScreen as? Screen.PageView)?.page ?: return
        val bsm = blockStateManager ?: return
        scope.launch { bsm.queuePageSave(currentPage.uuid.value) }
    }

    /**
     * Resolve disk conflict: discard in-progress edits and reload from the disk
     * version.
     */
    fun acceptDiskVersion() {
        val conflict = _uiState.value.diskConflict ?: return
        _uiState.update { it.copy(diskConflict = null) }
        scope.launch {
            graphLoader.parseAndSavePage(FilePath(conflict.filePath), conflict.diskContent, dev.stapler.stelekit.parsing.ParseMode.FULL)
            // Flush the accepted disk content back to disk immediately. Without this,
            // any auto-save that ran during the dialog would have written local content
            // to disk, leaving disk/DB out of sync after we update the DB here.
            blockStateManager?.savePageNow(conflict.pageUuid)
        }
    }

    /**
     * Resolve disk conflict: inline both versions as git-style conflict markers
     * inside the editing block so the user can manually choose what to keep.
     *
     * The block content becomes:
     *   <<<<<<< Your edit
     *   <local content>
     *   =======
     *   <disk content — first changed line>
     *   >>>>>>> Disk
     *
     * The conflict dialog is dismissed and the block enters edit mode so the user
     * can resolve the markers immediately.
     */
    @OptIn(DirectRepositoryWrite::class)
    fun manualResolve() {
        val conflict = _uiState.value.diskConflict ?: return
        if (conflict.editingBlockUuid.isBlank()) {
            // No specific block to merge into — fall back to accepting the local version
            _uiState.update { it.copy(diskConflict = null) }
            return
        }
        _uiState.update { it.copy(diskConflict = null) }
        scope.launch {
            val conflictContent = buildString {
                appendLine("<<<<<<< Your edit")
                append(conflict.localContent)
                if (!conflict.localContent.endsWith("\n")) appendLine()
                appendLine("=======")
                append(conflict.diskContent.lines().firstOrNull { it.startsWith("- ") }
                    ?.removePrefix("- ") ?: conflict.diskContent.take(200))
                if (!conflict.diskContent.endsWith("\n")) appendLine()
                append(">>>>>>> Disk")
            }
            val blockResult = blockRepository.getBlockByUuid(BlockUuid(conflict.editingBlockUuid ?: return@launch)).first()
            val block = blockResult.getOrNull() ?: return@launch
            val updatedBlock = block.copy(content = conflictContent, updatedAt = kotlin.time.Clock.System.now())
            writeActor?.execute { blockRepository.saveBlock(updatedBlock) }
                ?: blockRepository.saveBlock(updatedBlock)
            // Focus the block so the user can start editing immediately
            requestEditBlock(conflict.editingBlockUuid, 0)
        }
    }

    /**
     * Resolve disk conflict: preserve the user's content by appending it as a
     * new block on the page, then load the disk version.
     */
    @OptIn(DirectRepositoryWrite::class)
    fun saveAsNewBlock() {
        val conflict = _uiState.value.diskConflict ?: return
        if (conflict.localContent.isBlank()) {
            // Nothing to save — just accept disk version
            acceptDiskVersion()
            return
        }
        _uiState.update { it.copy(diskConflict = null) }
        scope.launch {
            // First reload from disk so the page structure is up-to-date
            graphLoader.parseAndSavePage(FilePath(conflict.filePath), conflict.diskContent, dev.stapler.stelekit.parsing.ParseMode.FULL)
            // Then append the user's content as a new block
            val now = kotlin.time.Clock.System.now()
            val lastBlockPos = blockRepository.getBlocksForPage(PageUuid(conflict.pageUuid)).first().getOrNull()?.maxByOrNull { it.position }?.position
            val newBlock = dev.stapler.stelekit.model.Block(
                uuid = BlockUuid(dev.stapler.stelekit.util.UuidGenerator.generateV7()),
                pageUuid = PageUuid(conflict.pageUuid),
                content = conflict.localContent,
                position = dev.stapler.stelekit.util.FractionalIndexing.generateKeyBetween(lastBlockPos, null),
                createdAt = now,
                updatedAt = now
            )
            writeActor?.execute { blockRepository.saveBlock(newBlock) }
                ?: blockRepository.saveBlock(newBlock)
            // Persist the new block to disk
            blockStateManager?.savePageNow(conflict.pageUuid)
        }
    }

    /**
     * Stop the auto-save processor
     */
    fun stopAutoSave() {
        graphWriter.stopAutoSave()
        logger.info("Auto-save stopped")
    }

    fun toggleSidebar() {
        _uiState.update { it.copy(sidebarExpanded = !it.sidebarExpanded) }
        updateCommands()
    }

    fun toggleRightSidebar() {
        _uiState.update { it.copy(rightSidebarExpanded = !it.rightSidebarExpanded) }
        updateCommands()
    }

    fun setSettingsVisible(visible: Boolean) {
        _uiState.update { it.copy(settingsVisible = visible) }
    }

    fun setCommandPaletteVisible(visible: Boolean) {
        _uiState.update { it.copy(commandPaletteVisible = visible) }
    }
    
    fun setSearchDialogVisible(visible: Boolean, initialQuery: String = "") {
        _uiState.update { it.copy(
            searchDialogVisible = visible,
            searchDialogInitialQuery = if (visible) initialQuery else ""
        )}
    }

    // ===== Share Dialog =====

    /** Opens the share dialog. */
    fun showShareDialog() {
        _uiState.update { it.copy(shareDialogVisible = true) }
    }

    /** Closes the share dialog. */
    fun hideShareDialog() {
        _uiState.update { it.copy(shareDialogVisible = false) }
    }

    /** Updates the share format selection (persists across dialog invocations in the session). */
    fun setShareFormat(format: String) {
        _uiState.update { it.copy(shareFormat = format) }
    }

    /** Updates the share scope selection (persists across dialog invocations in the session). */
    fun setShareScope(scope: ShareScope) {
        _uiState.update { it.copy(shareScope = scope) }
    }

    /**
     * Exports the current page as HTML and uploads it to Google Docs.
     * Runs on the ViewModel's own scope (never rememberCoroutineScope — that scope
     * is cancelled when the composable leaves composition).
     * On success: opens the created document in the browser.
     * On error: shows a snackbar notification.
     */
    /**
     * Resolve export content for any [ShareScope].
     *
     * Routes to the appropriate ExportService method based on scope.
     * Requires [journalFrom]/[journalTo] (non-null) when scope is [ShareScope.JournalRange].
     */
    suspend fun resolveExportContent(
        shareScope: ShareScope,
        page: Page,
        allBlocks: List<Block>,
        selectedUuids: Set<String>,
        formatId: String,
        journalFrom: LocalDate? = null,
        journalTo: LocalDate? = null,
    ): Either<DomainError, String> {
        val svc = exportService
            ?: return ExportError.SerializationFailed("Export service unavailable").left()
        return when (shareScope) {
            ShareScope.CurrentPage -> svc.exportToString(page, allBlocks, formatId)
            ShareScope.SelectedBlocks -> svc.exportToString(
                page,
                svc.subtreeBlocks(allBlocks, selectedUuids),
                formatId,
            )
            ShareScope.PageAndLinks -> svc.exportPageWithLinks(
                page, allBlocks, formatId, pageRepository, blockRepository,
            )
            ShareScope.JournalRange -> {
                val from = journalFrom
                    ?: return ExportError.SerializationFailed("Start date not set for journal export").left()
                val to = journalTo
                    ?: return ExportError.SerializationFailed("End date not set for journal export").left()
                svc.exportJournalRange(from, to, formatId, pageRepository, blockRepository)
            }
        }
    }

    /** Exports the resolved scope content to the clipboard. Errors surface via [notificationManager]. */
    fun exportScopeToClipboard(
        shareScope: ShareScope,
        page: Page,
        allBlocks: List<Block>,
        selectedUuids: Set<String>,
        formatId: String,
        journalFrom: LocalDate? = null,
        journalTo: LocalDate? = null,
        onDone: () -> Unit = {},
    ) {
        val svc = exportService ?: return
        scope.launch {
            try {
                when (shareScope) {
                    ShareScope.CurrentPage ->
                        svc.exportToClipboard(page, allBlocks, formatId)
                    ShareScope.SelectedBlocks ->
                        svc.exportToClipboard(
                            page,
                            svc.subtreeBlocks(allBlocks, selectedUuids),
                            formatId,
                        )
                    else -> {
                        val result = resolveExportContent(
                            shareScope, page, allBlocks, selectedUuids, formatId,
                            journalFrom, journalTo,
                        )
                        result.fold(
                            ifLeft = { err ->
                                withContext(Dispatchers.Main) {
                                    notificationManager?.show(
                                        "Export failed: ${err.message}",
                                        NotificationType.ERROR,
                                    )
                                }
                            },
                            ifRight = { content ->
                                if (formatId == "html") {
                                    val plainResult = resolveExportContent(
                                        shareScope, page, allBlocks, selectedUuids,
                                        "plain-text", journalFrom, journalTo,
                                    )
                                    svc.clipboard.writeHtml(content, plainResult.getOrNull() ?: content)
                                } else {
                                    svc.clipboard.writeText(content)
                                }
                            },
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    notificationManager?.show("Clipboard export failed: ${e.message}", NotificationType.ERROR)
                }
            } finally {
                withContext(Dispatchers.Main) { onDone() }
            }
        }
    }

    /** Launches Google OAuth in the ViewModel scope, updates [AppState.shareIsGoogleAuthenticated] on completion. */
    fun launchGoogleAuth(manager: GoogleAuthManager) {
        scope.launch {
            val result = manager.authenticate()
            val authenticated = result.isRight()
            val email = if (authenticated) manager.getConnectedEmail() else null
            _uiState.update { it.copy(
                shareIsGoogleAuthenticated = authenticated,
                shareGoogleEmail = email,
            ) }
            if (!authenticated) {
                withContext(Dispatchers.Main) {
                    notificationManager?.show(
                        "Google sign-in failed: ${result.fold({ it.message }, { "" })}",
                        NotificationType.ERROR,
                    )
                }
            }
        }
    }

    /** Re-queries auth state from [manager] and syncs to [AppState]. Call when the dialog opens. */
    fun refreshShareGoogleAuthState(manager: GoogleAuthManager) {
        scope.launch {
            val authenticated = manager.isAuthenticated()
            val email = if (authenticated) manager.getConnectedEmail() else null
            _uiState.update { it.copy(
                shareIsGoogleAuthenticated = authenticated,
                shareGoogleEmail = email,
            ) }
        }
    }

    /** Updates the journal date range used by the [ShareScope.JournalRange] export path. */
    fun setShareJournalDateRange(from: LocalDate?, to: LocalDate?) {
        _uiState.update { it.copy(shareJournalFromDate = from, shareJournalToDate = to) }
    }

    fun shareToGoogleDocs(
        shareScope: ShareScope,
        page: Page,
        allBlocks: List<Block>,
        selectedUuids: Set<String>,
        driveClient: dev.stapler.stelekit.platform.google.DriveUploader,
        journalFrom: LocalDate? = null,
        journalTo: LocalDate? = null,
    ) {
        _uiState.update { it.copy(isExportingToDrive = true) }
        scope.launch(Dispatchers.Default) {
            try {
                val htmlResult = resolveExportContent(
                    shareScope, page, allBlocks, selectedUuids, "html", journalFrom, journalTo,
                )
                htmlResult.fold(
                    ifLeft = { err ->
                        withContext(Dispatchers.Main) {
                            notificationManager?.show("Export failed: ${err.message}", NotificationType.ERROR)
                        }
                    },
                    ifRight = { html ->
                        val uploadResult = driveClient.uploadFile(
                            fileName = page.name,
                            mimeType = "application/vnd.google-apps.document",
                            bytes = html.encodeToByteArray(),
                            parentFolderId = null,
                        )
                        uploadResult.fold(
                            ifLeft = { err ->
                                withContext(Dispatchers.Main) {
                                    notificationManager?.show(
                                        "Google Docs upload failed: ${err.message}",
                                        NotificationType.ERROR,
                                    )
                                }
                            },
                            ifRight = { fileId ->
                                dev.stapler.stelekit.platform.openInBrowser(
                                    "https://docs.google.com/document/d/$fileId/edit",
                                )
                            }
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    notificationManager?.show("Google Docs export failed: ${e.message}", NotificationType.ERROR)
                }
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    _uiState.update { it.copy(isExportingToDrive = false) }
                }
            }
        }
    }

    fun setThemeMode(mode: StelekitThemeMode) {
        _uiState.update { it.copy(themeMode = mode) }
        updateCommands()
    }

    fun setLanguage(language: Language) {
        _uiState.update { it.copy(language = language) }
    }

    fun setOnboardingCompleted(completed: Boolean) {
        platformSettings.putBoolean("onboardingCompleted", completed)
        _uiState.update { it.copy(onboardingCompleted = completed) }
    }

    fun setStatusMessage(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    private val _snackbarEvents = Channel<String>(Channel.BUFFERED)
    val snackbarEvents: Flow<String> = _snackbarEvents.receiveAsFlow()

    fun sendSnackbar(message: String) {
        val result = _snackbarEvents.trySend(message)
        if (!result.isSuccess) logger.warn("sendSnackbar: channel full, message dropped: $message")
    }

    fun toggleDebugMode() {
        _uiState.update { state ->
            val newDebugMode = !state.isDebugMode
            state.copy(
                isDebugMode = newDebugMode,
                // Opening debug mode also opens the debug menu (when debug builds only).
                // Turning debug mode off closes the menu to leave a clean state.
                isDebugMenuVisible = if (newDebugMode && dev.stapler.stelekit.performance.DebugBuildConfig.isDebugBuild) true else false
            )
        }
    }

    fun setLeftHanded(value: Boolean) {
        platformSettings.putBoolean("isLeftHanded", value)
        _uiState.update { it.copy(isLeftHanded = value) }
    }

    fun setLibsqlDriverEnabled(value: Boolean) {
        platformSettings.putBoolean("db.libsql.enabled", value)
        _uiState.update { it.copy(isLibsqlDriverEnabled = value) }
    }

    fun showDebugMenu() {
        if (!dev.stapler.stelekit.performance.DebugBuildConfig.isDebugBuild) return
        _uiState.update { it.copy(isDebugMenuVisible = true) }
    }

    fun dismissDebugMenu() {
        _uiState.update { it.copy(isDebugMenuVisible = false) }
    }

    fun onDebugMenuStateChange(state: dev.stapler.stelekit.performance.DebugMenuState) {
        scope.launch(PlatformDispatcher.DB) {
            debugFlagRepository?.saveDebugMenuState(state)
        }
    }

    /**
     * Assembles a bug report JSON string and returns it.
     * Returns null if [BugReportBuilder] is not wired (non-debug builds).
     */
    fun exportBugReport(reproductionSteps: String = ""): String? =
        bugReportBuilder?.buildJson(reproductionSteps)


    /**
     * Execute a command by ID
     */
    suspend fun executeCommand(commandId: String, context: CommandContext = CommandContext()): CommandResult {
        return commandManager.executeCommand(commandId, context)
    }
    
    /**
     * Execute a slash command
     */
    suspend fun executeSlashCommand(input: String, context: CommandContext = CommandContext()): CommandResult {
        return commandManager.executeSlashCommand(input, context)
    }
    
    /**
     * Get command suggestions for the command palette
     */
    suspend fun getCommandSuggestions(query: String, context: CommandContext = CommandContext()): List<EditorCommand> {
        return commandManager.getCommandSuggestions(query, context).map { it.command }
    }
    
    /**
     * Check if input is a slash command
     */
    suspend fun isSlashCommand(input: String): Boolean {
        return commandManager.isSlashCommand(input)
    }
    
    /**
     * Get available commands for current context
     */
    suspend fun getAvailableCommands(): List<EditorCommand> {
        val context = CommandContext(
            currentPageId = _uiState.value.currentPage?.uuid?.value,
            currentBlockId = _uiState.value.currentPage?.uuid?.value // This would be updated by actual editor
        )
        return commandManager.getAvailableCommands(context)
    }

    fun newSectionJournalForToday(sectionId: String) {
        scope.launch {
            val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            val result = graphLoader.createSectionJournalPage(sectionId, today)
            result.onRight { page -> navigateTo(Screen.PageView(page)) }
        }
    }

    private fun updateCommands() {
        scope.launch {
            try {
                val availableCommands = getAvailableCommands()
                val legacyCommands = availableCommands.map { cmd ->
                    Command(cmd.id, cmd.label, cmd.shortcut) {
                        val attachCallback = attachImageCallback
                        if (cmd.id == "media.image" && attachCallback != null) {
                            attachCallback()
                        } else {
                            scope.launch {
                                executeCommand(cmd.id)
                            }
                        }
                    }
                }.toMutableList()

                // Add rename/export commands only when a non-journal page is open
                val currentPage = _uiState.value.currentPage
                if (currentPage != null && !currentPage.isJournal) {
                    legacyCommands += Command(
                        id = "page.rename",
                        label = "Rename page",
                        shortcut = null,
                        action = { showRenameDialog(currentPage) }
                    )
                }

                if (currentPage != null) {
                    legacyCommands += Command(
                        id = "export.page.markdown",
                        label = "Export page as Markdown",
                        shortcut = "Ctrl+Shift+E",
                        action = { exportPage("markdown") }
                    )
                    legacyCommands += Command(
                        id = "export.page.plain-text",
                        label = "Export page as Plain Text",
                        action = { exportPage("plain-text") }
                    )
                    legacyCommands += Command(
                        id = "export.page.html",
                        label = "Export page as HTML",
                        action = { exportPage("html") }
                    )
                    legacyCommands += Command(
                        id = "export.page.json",
                        label = "Export page as JSON",
                        action = { exportPage("json") }
                    )
                }

                legacyCommands += Command(
                    id = "global-unlinked-refs",
                    label = "Open Unlinked References",
                    shortcut = null,
                    action = { navigateTo(Screen.GlobalUnlinkedReferences) }
                )

                if (_uiState.value.currentGraphPath.isNotEmpty()) {
                    legacyCommands += Command(
                        id = "import.paste-text",
                        label = "Import text as new page",
                        shortcut = null,
                        action = { navigateTo(Screen.Import) }
                    )
                }

                val activeSections = platformSettings.getSectionStates()
                    .filterValues { it == SectionState.ACTIVE }.keys
                for (sectionId in activeSections) {
                    legacyCommands += Command(
                        id = "journal.new.$sectionId",
                        label = "New $sectionId journal for today",
                        shortcut = null,
                        action = { newSectionJournalForToday(sectionId) }
                    )
                }

                _uiState.update { it.copy(commands = legacyCommands) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to update commands", e)
            }
        }
    }
    
    /**
     * Get the command manager for advanced usage
     */
    fun getCommandManager(): CommandManager = commandManager
    
    /**
     * Search pages for autocomplete
     */
    fun searchPages(query: String): Flow<List<SearchResultItem>> {
        val request = SearchRequest(query = query, limit = 10)
        return searchRepository.searchWithFilters(request).map {
            val searchResult = it.getOrNull()
            if (searchResult != null) {
                val items = mutableListOf<SearchResultItem>()
                // Add Pages
                if (searchResult.pages.isNotEmpty()) {
                    items.addAll(searchResult.pages.map { SearchResultItem.PageItem(it) })
                }
                
                // Add "Create Page" option at the top if no exact match
                val exactMatch = items.any { it is SearchResultItem.PageItem && it.page.name.equals(query, ignoreCase = true) }
                if (!exactMatch && query.isNotBlank()) {
                    items.add(0, SearchResultItem.CreatePageItem(query))
                }
                items
            } else {
                emptyList()
            }
        }
    }

    fun savePendingChanges() {
        scope.launch {
            blockStateManager?.flush()
        }
    }

    /**
     * Flush pending writes, null out CryptoLayer references, and lock the vault.
     * Launched on the ViewModel's own scope to avoid [ForgottenCoroutineScopeException]
     * when called from a lifecycle observer that may fire after composition teardown.
     */
    fun flushAndLockVault(graphLoader: GraphLoaderPort, graphWriter: GraphWriterPort, vaultManager: VaultManager) {
        scope.launch {
            blockStateManager?.flush()  // drain in-memory block edits before DEK is zeroed
            graphWriter.flush()
            // closeAndClearCryptoLayer() zeroes the CryptoLayer's owned DEK copy before nulling
            // the port's reference, independently of session.dek that vaultManager.lock() zeroes.
            graphLoader.closeAndClearCryptoLayer()
            graphWriter.closeAndClearCryptoLayer()
            vaultManager.lock()
        }
    }

    // ===== Export =====

    /**
     * Injects the platform-specific [ClipboardProvider] so export operations can write
     * to the system clipboard. Called once from the composable root after construction.
     */
    fun setClipboardProvider(provider: ClipboardProvider) {
        exportService?.clipboard = provider
    }

    /**
     * Exports the current page to [formatId] and copies the result to the clipboard.
     * No-op when there is no current page or no [ExportService] configured.
     */
    fun exportPage(formatId: String) {
        val page = _uiState.value.currentPage ?: return
        val blocks = blockStateManager?.blocksForPage(page.uuid.value) ?: return
        val sortedBlocks = BlockSorter.sort(blocks)
        if (exportService == null) {
            notificationManager?.show("Export unavailable", NotificationType.ERROR)
            return
        }
        if (_uiState.value.isExporting) return
        _uiState.update { it.copy(isExporting = true) }
        scope.launch(Dispatchers.Default) {
            try {
                val result = exportService.exportToClipboard(page, sortedBlocks, formatId)
                withContext(Dispatchers.Main) {
                    result.onRight {
                        notificationManager?.show("Copied as ${formatDisplayName(formatId)}", NotificationType.SUCCESS)
                    }.onLeft { e ->
                        notificationManager?.show("Export failed: ${e.message}", NotificationType.ERROR)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isExporting = false) }
                }
            }
        }
    }

    /**
     * Exports the currently selected blocks (and their subtrees) to [formatId].
     * Falls back to [exportPage] when no blocks are selected.
     */
    fun exportSelectedBlocks(formatId: String) {
        val page = _uiState.value.currentPage ?: return
        val selectedUuids = blockStateManager?.selectedBlockUuids?.value ?: emptySet()
        if (selectedUuids.isEmpty()) {
            exportPage(formatId)
            return
        }
        val allBlocks = blockStateManager?.blocksForPage(page.uuid.value) ?: return
        if (exportService == null) {
            notificationManager?.show("Export unavailable", NotificationType.ERROR)
            return
        }
        if (_uiState.value.isExporting) return
        _uiState.update { it.copy(isExporting = true) }
        scope.launch(Dispatchers.Default) {
            try {
                val subtreeBlocks = exportService.subtreeBlocks(allBlocks, selectedUuids)
                val result = exportService.exportToClipboard(page, subtreeBlocks, formatId)
                withContext(Dispatchers.Main) {
                    result.onRight {
                        notificationManager?.show("Copied as ${formatDisplayName(formatId)}", NotificationType.SUCCESS)
                    }.onLeft { e ->
                        notificationManager?.show("Export failed: ${e.message}", NotificationType.ERROR)
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isExporting = false) }
                }
            }
        }
    }

    private fun formatDisplayName(formatId: String): String = when (formatId) {
        "markdown" -> "Markdown"
        "plain-text" -> "Plain Text"
        "html" -> "HTML"
        "json" -> "JSON"
        else -> formatId
    }

    // ===== Rename Page =====

    fun showRenameDialog(page: Page) {
        _uiState.update { it.copy(renameDialogPage = page, renameDialogBusy = false, renameDialogError = null) }
    }

    fun dismissRenameDialog() {
        _uiState.update { it.copy(renameDialogPage = null, renameDialogBusy = false, renameDialogError = null) }
    }

    fun renamePage(page: Page, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank() || trimmed == page.name) return
        val graphPath = _uiState.value.currentGraphPath
        scope.launch {
            _uiState.update { it.copy(renameDialogBusy = true, renameDialogError = null) }
            // Guard: reject rename if a page with the target name already exists.
            val existing = pageRepository.getPageByName(trimmed).first().getOrNull()
            if (existing != null && existing.uuid != page.uuid) {
                _uiState.update { it.copy(renameDialogBusy = false, renameDialogError = "A page named \"$trimmed\" already exists") }
                return@launch
            }
            when (val result = backlinkRenamer.execute(page, trimmed, graphPath)) {
                is RenameResult.Success -> {
                    val renamedPage = page.copy(name = trimmed)
                    _uiState.update { state ->
                        val updatedHistory = state.navigationHistory.map { screen ->
                            if (screen is Screen.PageView && screen.page.uuid == page.uuid) {
                                Screen.PageView(renamedPage)
                            } else screen
                        }
                        state.copy(
                            renameDialogPage = null,
                            renameDialogBusy = false,
                            renameDialogError = null,
                            currentScreen = if (state.currentScreen is Screen.PageView &&
                                state.currentScreen.page.uuid == page.uuid
                            ) Screen.PageView(renamedPage) else state.currentScreen,
                            currentPage = if (state.currentPage?.uuid == page.uuid) renamedPage else state.currentPage,
                            navigationHistory = updatedHistory,
                            statusMessage = "Renamed '${page.name}' → '$trimmed'"
                        )
                    }
                    val linkWord = if (result.updatedBlockCount == 1) "link" else "links"
                    notificationManager?.show("Renamed \"${page.name}\" → \"$trimmed\" (${result.updatedBlockCount} $linkWord updated)")
                    // Refresh page lists so sidebar and AllPages reflect the new name
                    loadMoreRegularPages(reset = true)
                }
                is RenameResult.Failure -> {
                    _uiState.update { it.copy(
                        renameDialogBusy = false,
                        renameDialogError = result.error.message ?: "Rename failed"
                    ) }
                }
            }
        }
    }

    private var midnightWatcherJob: kotlinx.coroutines.Job? = null
    private var lastJournalDate: kotlinx.datetime.LocalDate? = null

    internal fun millisUntilNextMidnight(clock: Clock = Clock.System): Long {
        val tz = TimeZone.currentSystemDefault()
        val now = clock.now()
        val today = now.toLocalDateTime(tz).date
        val tomorrowMidnight = today.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz)
        return (tomorrowMidnight - now).inWholeMilliseconds.coerceAtLeast(MIN_MIDNIGHT_DELAY_MS)
    }

    internal fun startMidnightBoundaryWatcher(clock: Clock = Clock.System) {
        midnightWatcherJob?.cancel()
        midnightWatcherJob = scope.launch(CoroutineName("midnight-boundary-watcher")) {
            // Seed lastJournalDate from startup before entering the loop, so the first
            // midnight crossing skips if today's journal was already created at startup.
            val tz = TimeZone.currentSystemDefault()
            lastJournalDate = clock.now().toLocalDateTime(tz).date
            while (isActive) {
                val delayMs = millisUntilNextMidnight(clock)
                logger.info("Next journal boundary check in ${delayMs / 1000}s")
                delay(delayMs)
                val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                if (today == lastJournalDate) continue
                logger.info("Day boundary crossed — ensuring today's journal exists")
                try {
                    journalService.ensureTodayJournal()
                    lastJournalDate = today
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("ensureTodayJournal failed at midnight boundary: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val MIN_MIDNIGHT_DELAY_MS = 1_000L
    }
}
