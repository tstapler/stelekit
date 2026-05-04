package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.ParsedBlock
import dev.stapler.stelekit.model.toDiscriminatorString
import kotlin.time.Instant
import dev.stapler.stelekit.outliner.JournalUtils
import dev.stapler.stelekit.parser.MarkdownParser
import dev.stapler.stelekit.parsing.ParseMode
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.JournalDateResolver
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.performance.ActiveSpanContext
import dev.stapler.stelekit.performance.CurrentSpanContext
import dev.stapler.stelekit.performance.PerformanceMonitor
import dev.stapler.stelekit.performance.SerializedSpan
import dev.stapler.stelekit.performance.SpanRepository
import dev.stapler.stelekit.util.ContentHasher
import dev.stapler.stelekit.util.FileUtils
import dev.stapler.stelekit.util.UuidGenerator
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * GraphLoader handles loading markdown files from disk into the database.
 * 
 * Updated to use UUID-native storage for all references.
 * Includes file system watching for auto-reload.
 */
class GraphLoader(
    private val fileSystem: FileSystem,
    private val pageRepository: PageRepository,
    private val blockRepository: BlockRepository,
    private val journalDateResolver: JournalDateResolver = JournalDateResolver { date ->
        pageRepository.getJournalPageByDate(date).first().getOrNull()
    },
    val fileRegistry: FileRegistry = FileRegistry(fileSystem),
    externalWriteActor: DatabaseWriteActor? = null,
    /** Background-safe variant of [pageRepository] that skips write-side cache population. */
    private val backgroundPageRepository: PageRepository = pageRepository,
    private val sidecarManager: SidecarManager? = null,
    private val histogramWriter: dev.stapler.stelekit.performance.HistogramWriter? = null,
    private val spanRepository: SpanRepository? = null,
) {
    private val logger = Logger("GraphLoader")
    private val markdownParser = MarkdownParser()

    /** Called after a full bulk import completes. Used to trigger WAL checkpoint. */
    var onBulkImportComplete: (suspend () -> Unit)? = null

    /**
     * Set of page UUIDs currently open in an active edit session. When non-null,
     * [indexRemainingPages] skips these pages to avoid clobbering in-progress edits.
     * Set by [dev.stapler.stelekit.ui.StelekitViewModel] after construction to break the
     * circular dependency (BlockStateManager depends on GraphLoader).
     */
    var activePageUuids: StateFlow<Set<String>>? = null

    // Lightweight span tracking for the Spans waterfall tab.
    private fun genId(): String =
        kotlin.random.Random.nextLong().toULong().toString(16).padStart(16, '0')

    private inner class Span(val name: String, val traceId: String, val parentSpanId: String = "") {
        val spanId: String = genId()
        private val startMs: Long = Clock.System.now().toEpochMilliseconds()
        @OptIn(DirectRepositoryWrite::class)
        suspend fun finish(statusCode: String = "OK", vararg attrs: Pair<String, String>) {
            val endMs = Clock.System.now().toEpochMilliseconds()
            val allAttrs = mapOf(*attrs) + ("session.id" to dev.stapler.stelekit.performance.AppSession.id)
            val serialized = SerializedSpan(
                name = name, startEpochMs = startMs, endEpochMs = endMs,
                durationMs = endMs - startMs, attributes = allAttrs,
                statusCode = statusCode, traceId = traceId,
                spanId = spanId, parentSpanId = parentSpanId,
            )
            if (spanRepository != null) {
                writeActor.execute(DatabaseWriteActor.Priority.LOW) {
                    spanRepository.insertSpan(serialized)
                    Unit.right()
                }
            }
        }
    }

    // Tracks the currently loaded graph path so on-demand loads can resolve file paths
    var currentGraphPath: String = ""
        private set

    // Platform-agnostic parallelism configuration
    private val ioThreads = 4

    // Background indexing is capped below the full Default thread pool so user-triggered
    // high-priority saves always have threads available for parse work. Write throughput
    // is serialized by DatabaseWriteActor regardless, so capping parse concurrency here
    // doesn't meaningfully slow total indexing time.
    private val backgroundIndexDispatcher = Dispatchers.Default.limitedParallelism(ioThreads)

    // Platform-agnostic coroutine scope for parallel processing
    private val parallelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Serializes all DB writes to avoid SQLITE_BUSY under concurrent graph loading.
    // If an external actor is provided (from RepositorySet), reuse it; otherwise create one locally.
    private val writeActor = externalWriteActor ?: DatabaseWriteActor(blockRepository, pageRepository)

    // Watcher job
    private var watcherJob: Job? = null

    // Tracks the in-flight background indexing job so it can be cancelled under memory pressure.
    private var backgroundIndexJob: Job? = null

    /**
     * Cancels any in-flight background indexing (Phase 2) immediately.
     * Safe to call from any thread. No-op if no indexing is running.
     */
    fun cancelBackgroundWork() {
        backgroundIndexJob?.cancel()
        backgroundIndexJob = null
    }

    /**
     * Called by GraphWriter after it writes a file, so the watcher doesn't treat
     * our own write as an external change.
     */
    fun markFileWrittenByUs(filePath: String) {
        fileRegistry.markWrittenByUs(filePath)
    }

    /**
     * Emitted when the file watcher detects an external modification to a file.
     * Consumers (e.g. StelekitViewModel) can collect this flow and decide whether to
     * treat the change as a conflict.  If the event's [suppress] callback is invoked
     * before the next watcher tick, the automatic re-import is skipped.
     */
    private val _externalFileChanges = MutableSharedFlow<ExternalFileChange>(extraBufferCapacity = 8)
    val externalFileChanges: SharedFlow<ExternalFileChange> = _externalFileChanges.asSharedFlow()

    /**
     * Emitted when a DB write fails after all retries. Consumers (e.g. the ViewModel) can
     * surface an error banner and offer a retry action (re-calling [indexRemainingPages]).
     */
    private val _writeErrors = MutableSharedFlow<WriteError>(extraBufferCapacity = 16)
    val writeErrors: SharedFlow<WriteError> = _writeErrors.asSharedFlow()

    // Files suppressed from external-change processing.
    // Two modes:
    //  1. Single-shot: subscriber calls suppress() in ExternalFileChange handler; the path is
    //     added here and then removed by checkDirectoryForChanges via .remove().
    //  2. Sticky (git merge): beginGitMerge() pre-adds paths; they are checked with .contains()
    //     and not removed until endGitMerge() calls .clear().
    // Both modes share the same set; sticky paths survive across multiple watcher ticks.
    private val suppressedFiles = mutableSetOf<String>()

    // Paths added by beginGitMerge() — kept for sticky suppression across watcher ticks.
    private val gitMergeSuppressedFiles = mutableSetOf<String>()

    /**
     * Derives a stable UUID for a block from its position in the page tree.
     *
     * The UUID is derived from position only (filePath + parentUuid + siblingIndex),
     * making it stable across content edits. If the block already carries an explicit
     * `id` property (written by Logseq or the user), that value is used verbatim.
     */
    private fun generateUuid(
        parsedBlock: ParsedBlock,
        pagePath: String,
        blockIndex: Int,
        parentUuid: String? = null,
        sidecarMap: Map<String, SidecarManager.SidecarEntry>? = null,
    ): String {
        // If block has an ID property, use it
        val existingId = parsedBlock.properties["id"]
        if (existingId != null && existingId.isNotBlank()) {
            return existingId
        }

        // If sidecar exists, look up by content hash for UUID recovery after git pull.
        // This preserves stable UUIDs for blocks whose content hasn't changed but whose
        // position shifted (e.g. after a git merge that reordered bullets).
        if (sidecarMap != null) {
            val hash = ContentHasher.sha256ForContent(parsedBlock.content)
            val sidecarEntry = sidecarMap[hash]
            if (sidecarEntry != null) return sidecarEntry.uuid
        }

        // Include parentUuid in seed so blocks at different nesting levels with the
        // same sibling index don't collide (e.g. two bullets both at index 2 but
        // under different parents would otherwise share a UUID, causing a UNIQUE
        // constraint violation in saveBlocks).
        val seed = "$pagePath:${parentUuid ?: "root"}:$blockIndex"
        return UuidGenerator.generateDeterministic(seed)
    }

    /**
     * Tries to find the .md file for a page by searching pages/ and journals/ directories.
     */
    fun resolvePageFilePath(pageName: String): String? {
        if (currentGraphPath.isEmpty()) return null
        val candidates = listOf(
            "$currentGraphPath/pages/$pageName.md",
            "$currentGraphPath/journals/$pageName.md"
        )
        return candidates.firstOrNull { fileSystem.fileExists(it) }
    }

    /**
     * Priority-loads a page by name directly from disk.
     */
    suspend fun loadPageByName(pageName: String): Page? {
        val filePath = resolvePageFilePath(pageName) ?: return null
        if (!tryAddPriorityFile(filePath)) {
            // Already loading — wait for it by polling the DB
            repeat(10) {
                val page = pageRepository.getPageByName(pageName).first().getOrNull()
                if (page?.isContentLoaded == true) return page
                kotlinx.coroutines.delay(200)
            }
            return pageRepository.getPageByName(pageName).first().getOrNull()
        }
        try {
            val content = fileSystem.readFile(filePath) ?: return null
            parseAndSavePage(filePath, content, ParseMode.FULL)
            return pageRepository.getPageByName(pageName).first().getOrNull()
        } finally {
            removePriorityFile(filePath)
        }
    }

    suspend fun loadGraph(graphPath: String, onProgress: (String) -> Unit) {
        currentGraphPath = graphPath
        PerformanceMonitor.startTrace("loadGraph")
        val traceId = genId()
        val rootSpan = Span("graph_load", traceId)
        CurrentSpanContext.set(ActiveSpanContext(traceId = traceId, parentSpanId = rootSpan.spanId))
        try {
            if (!fileSystem.directoryExists(graphPath)) {
                logger.warn("Graph directory not found: $graphPath")
                return
            }

            logger.info("Starting graph load from: $graphPath")
            val startTime = Clock.System.now()

            val pagesDir = "$graphPath/pages"
            val journalsDir = "$graphPath/journals"

            sanitizeDirectory(pagesDir)
            sanitizeDirectory(journalsDir)

            coroutineScope {
                val loadPagesJob = async {
                    loadDirectory(pagesDir, onProgress, ParseMode.FULL)
                }

                val loadJournalsJob = async {
                    loadDirectory(journalsDir, onProgress, ParseMode.FULL)
                }

                awaitAll(loadPagesJob, loadJournalsJob)
            }

            val duration = Clock.System.now() - startTime
            logger.info("Graph load complete. Duration: $duration")
            histogramWriter?.record("graph_load", duration.inWholeMilliseconds)
            if (duration.inWholeMilliseconds > 2000) {
                logger.warn("Slow graph load: ${duration.inWholeMilliseconds}ms")
            }

            // Start watching after initial load
            startWatching(graphPath)
            rootSpan.finish("OK", "graph.path" to graphPath, "duration.ms" to duration.inWholeMilliseconds.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            rootSpan.finish("ERROR", "graph.path" to graphPath, "error.message" to (e.message ?: "unknown"))
            throw e
        } finally {
            CurrentSpanContext.set(null)
            PerformanceMonitor.endTrace("loadGraph")
        }
    }

    suspend fun loadGraphProgressive(
        graphPath: String,
        immediateJournalCount: Int = 10,
        onProgress: (String) -> Unit,
        onPhase1Complete: () -> Unit,
        onFullyLoaded: () -> Unit
    ) {
        currentGraphPath = graphPath
        PerformanceMonitor.startTrace("loadGraphProgressive")
        val traceId = genId()
        val rootSpan = Span("graph_load.progressive", traceId)
        CurrentSpanContext.set(ActiveSpanContext(traceId = traceId, parentSpanId = rootSpan.spanId))
        try {
            if (!fileSystem.directoryExists(graphPath)) {
                logger.warn("Graph directory not found: $graphPath")
                onPhase1Complete()
                onFullyLoaded()
                return
            }

            logger.info("Starting progressive graph load from: $graphPath")
            val startTime = Clock.System.now()

            val pagesDir = "$graphPath/pages"
            val journalsDir = "$graphPath/journals"

            // Warm-start fast path: DB already has journals from a previous session on the
            // same graph (ViewModel clears the DB before calling us when the graph path changes,
            // so a non-empty result here always means "same graph, valid cached data").
            // Signal Phase 1 complete immediately so the UI is interactive in <100ms, then
            // reconcile the filesystem in the background. Any changed files are re-parsed and
            // written to DB; the reactive flows in the repository layer push updates to the UI
            // automatically — no explicit refresh needed.
            val warmStartJournals = pageRepository.getJournalPages(immediateJournalCount, 0)
                .first().getOrNull().orEmpty()
            if (warmStartJournals.isNotEmpty()) {
                logger.info("Warm start: ${warmStartJournals.size} journals in DB — skipping blocking Phase 1")
                onProgress("Ready")
                onPhase1Complete()
                val warmSpan = Span("graph_load.warm_reconcile", traceId, rootSpan.spanId)
                // Track in backgroundIndexJob so cancelBackgroundWork() (called from onTrimMemory)
                // cancels this job before it can write to a closed DB or exhaust memory.
                backgroundIndexJob = parallelScope.launch {
                    try {
                        // Sanitize must run before re-scanning so any renamed files are visible
                        // to loadJournalsImmediate and loadDirectory below.
                        sanitizeDirectory(pagesDir)
                        sanitizeDirectory(journalsDir)
                        loadJournalsImmediate(journalsDir, immediateJournalCount, onProgress)
                        coroutineScope {
                            launch { loadRemainingJournals(journalsDir, immediateJournalCount, onProgress) }
                            launch { loadDirectory(pagesDir, onProgress, ParseMode.METADATA_ONLY) }
                            launch { fileSystem.syncShadow(graphPath) }
                        }
                        val totalDuration = Clock.System.now() - startTime
                        logger.info("Warm reconcile complete. Duration: $totalDuration")
                        histogramWriter?.record("graph_load", totalDuration.inWholeMilliseconds)
                        warmSpan.finish("OK", "duration.ms" to totalDuration.inWholeMilliseconds.toString())
                        onFullyLoaded()
                        onBulkImportComplete?.invoke()
                        startWatching(graphPath)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        warmSpan.finish("ERROR", "error.message" to (e.message ?: "unknown"))
                    } finally {
                        backgroundIndexJob = null
                    }
                }
                rootSpan.finish("OK", "graph.path" to graphPath, "warm_start" to "true")
                return
            }

            val phase1Start = Clock.System.now()
            val phase1Span = Span("graph_load.phase1_journals", traceId, rootSpan.spanId)
            val loadedImmediateCount = loadJournalsImmediate(journalsDir, immediateJournalCount, onProgress)
            val phase1Duration = Clock.System.now() - phase1Start
            phase1Span.finish("OK", "journal.count" to loadedImmediateCount.toString(),
                "duration.ms" to phase1Duration.inWholeMilliseconds.toString())
            logger.info("Phase 1 complete: Loaded $loadedImmediateCount journals in $phase1Duration")

            onProgress("Ready - loading remaining content...")
            onPhase1Complete()

            // Sanitize runs after Phase 1 so the UI is interactive before any rename I/O.
            // Pages are always fully re-scanned by loadDirectory so renamed files are picked
            // up cleanly. Journals are almost never renamed (date filenames are always valid).
            sanitizeDirectory(pagesDir)
            sanitizeDirectory(journalsDir)

            val phase2Span = Span("graph_load.phase2_background", traceId, rootSpan.spanId)
            coroutineScope {
                launch(Dispatchers.Default) {
                    loadRemainingJournals(journalsDir, immediateJournalCount, onProgress)
                }

                launch(Dispatchers.Default) {
                    loadDirectory(pagesDir, onProgress, ParseMode.METADATA_ONLY)
                }

                launch {
                    fileSystem.syncShadow(graphPath)
                }
            }
            phase2Span.finish("OK")

            val totalDuration = Clock.System.now() - startTime
            logger.info("Progressive graph load complete. Total duration: $totalDuration")
            histogramWriter?.record("graph_load", totalDuration.inWholeMilliseconds)
            onProgress("Graph loaded completely.")
            onFullyLoaded()
            onBulkImportComplete?.invoke()

            // Start watching after initial load
            startWatching(graphPath)
            rootSpan.finish("OK", "graph.path" to graphPath,
                "duration.ms" to totalDuration.inWholeMilliseconds.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            rootSpan.finish("ERROR", "graph.path" to graphPath, "error.message" to (e.message ?: "unknown"))
            throw e
        } finally {
            CurrentSpanContext.set(null)
            PerformanceMonitor.endTrace("loadGraphProgressive")
        }
    }

    fun startWatching(graphPath: String) {
        fileSystem.stopExternalChangeDetection()
        watcherJob?.cancel()
        val externalChangeTrigger = Channel<Unit>(Channel.CONFLATED)
        watcherJob = parallelScope.launch {
            // 5-second polling fallback
            launch {
                logger.info("Started watching graph for changes: $graphPath")
                while (isActive) {
                    try {
                        delay(5000) // Poll every 5 seconds
                        val pagesDir = "$graphPath/pages"
                        val journalsDir = "$graphPath/journals"

                        checkDirectoryForChanges(pagesDir)
                        checkDirectoryForChanges(journalsDir)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logger.warn("Error in graph watcher", e)
                    }
                }
            }
            // Platform-native fast-path (e.g. Android ContentObserver).
            // Channel.CONFLATED coalesces rapid callback storms into at most one
            // pending scan so we never queue up redundant directory scans.
            launch {
                for (ignored in externalChangeTrigger) {
                    try {
                        checkDirectoryForChanges("$graphPath/pages")
                        checkDirectoryForChanges("$graphPath/journals")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logger.warn("Error in external change handler", e)
                    }
                }
            }
        }
        fileSystem.startExternalChangeDetection(parallelScope) {
            externalChangeTrigger.trySend(Unit)
        }
    }

    /**
     * Finds and fully indexes all pages that were only partially loaded (METADATA_ONLY).
     * This should be run in the background after Phase 1 completion.
     *
     * Mirrors loadDirectory's batch-write pattern: parse all pages in a chunk in parallel,
     * then flush all DB writes for the chunk in a single flushChunkWritesPreemptible call.
     * This reduces DatabaseWriteActor round-trips by ~90% vs. one-at-a-time parseAndSavePage.
     */
    suspend fun indexRemainingPages(onProgress: (String) -> Unit) {
        // Track the calling coroutine's job so cancelBackgroundWork() can cancel it.
        backgroundIndexJob = currentCoroutineContext()[Job]
        PerformanceMonitor.startTrace("indexRemainingPages")
        try {
            val unloadedPages = pageRepository.getUnloadedPages().first().getOrNull() ?: emptyList()
            if (unloadedPages.isEmpty()) return

            logger.info("Background indexing ${unloadedPages.size} pages...")

            coroutineScope {
                var processed = 0
                val total = unloadedPages.size

                unloadedPages.chunked(10).forEach { chunk ->
                    val pagesToSave = mutableListOf<Page>()
                    val blocksToSaveByPage = mutableMapOf<String, MutableList<Block>>()
                    val pageUuidsToDelete = mutableSetOf<String>()

                    chunk.map { page ->
                        async(backgroundIndexDispatcher) {
                            if (page.uuid in (activePageUuids?.value ?: emptySet())) {
                                logger.debug("Phase 3: skipping ${page.name} — active edit session")
                                return@async null
                            }
                            val path = page.filePath ?: resolvePageFilePath(page.name)
                            if (path == null) return@async null
                            val content = fileSystem.readFile(path) ?: return@async null
                            try {
                                parsePageWithoutSaving(path, content, ParseMode.FULL)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.warn("Failed to parse file: $path: ${e.message}")
                                null
                            }
                        }
                    }.awaitAll().forEach { result ->
                        if (result != null) {
                            pagesToSave.add(result.page)
                            if (result.blocks.isNotEmpty()) {
                                blocksToSaveByPage[result.page.uuid] = result.blocks.toMutableList()
                            }
                            pageUuidsToDelete.add(result.page.uuid)
                        }
                    }

                    if (pagesToSave.isNotEmpty() || pageUuidsToDelete.isNotEmpty()) {
                        flushChunkWritesPreemptible(pagesToSave, pageUuidsToDelete, blocksToSaveByPage)
                    }

                    processed += chunk.size
                    onProgress("Indexing pages... ($processed/$total)")
                }
            }
            logger.info("Background indexing complete.")
        } finally {
            backgroundIndexJob = null
            PerformanceMonitor.endTrace("indexRemainingPages")
        }
    }

    private suspend fun checkDirectoryForChanges(dirPath: String) {
        val changeSet = fileRegistry.detectChanges(dirPath)

        for (changed in changeSet.newFiles) {
            logger.info("New file detected: ${changed.entry.filePath}")
            fileSystem.invalidateShadow(changed.entry.filePath)
            parseAndSavePage(changed.entry.filePath, changed.content, ParseMode.FULL)
        }

        for (changed in changeSet.changedFiles) {
            logger.info("File modification detected: ${changed.entry.filePath}")
            fileSystem.invalidateShadow(changed.entry.filePath)

            // Sticky git-merge suppression: if the path was added by beginGitMerge,
            // skip it without consuming the entry (it remains suppressed until endGitMerge).
            if (gitMergeSuppressedFiles.contains(changed.entry.filePath)) {
                logger.debug("Skipping watcher reload for git-merge-suppressed file: ${changed.entry.filePath}")
                continue
            }

            // Emit event so subscribers can suppress the re-import
            _externalFileChanges.tryEmit(ExternalFileChange(changed.entry.filePath, changed.content) {
                suppressedFiles.add(changed.entry.filePath)
            })
            yield()
            if (suppressedFiles.remove(changed.entry.filePath)) {
                continue
            }

            parseAndSavePage(changed.entry.filePath, changed.content, ParseMode.FULL)
        }

        for (filePath in changeSet.deletedPaths) {
            logger.info("File deletion detected: $filePath")
        }
    }

    /**
     * Flush chunk writes with per-page atomicity.
     *
     * savePages runs as one typed LOW batch (HIGH can preempt after it completes).
     * For each page, deleteBlocksForPages + saveBlocks run inside a single Execute(LOW)
     * so a HIGH write cannot interleave between removing a page's old blocks and inserting
     * its replacements — which would transiently leave the page with no blocks.
     * HIGH requests can still preempt between pages (after each Execute completes),
     * giving sub-page granularity rather than the old sub-chunk (10-page) granularity.
     */
    @OptIn(DirectRepositoryWrite::class)
    private suspend fun flushChunkWritesPreemptible(
        pagesToSave: List<Page>,
        pageUuidsToDelete: Set<String>,
        blocksToSaveByPage: Map<String, List<Block>>,
    ) {
        val failedPageUuids = mutableSetOf<String>()

        if (pagesToSave.isNotEmpty()) {
            val bulkResult = writeActor.execute(DatabaseWriteActor.Priority.LOW) {
                backgroundPageRepository.savePages(pagesToSave)
            }
            if (bulkResult.isLeft()) {
                for (page in pagesToSave) {
                    writeActor.execute(DatabaseWriteActor.Priority.LOW) {
                        backgroundPageRepository.savePage(page)
                    }.onLeft { e ->
                        logger.warn("savePage failed for ${page.name}: ${e.message}")
                        _writeErrors.tryEmit(WriteError(page.filePath ?: page.name, 0, e))
                        failedPageUuids.add(page.uuid)
                    }
                }
            }
        }

        for ((pageUuid, blocks) in blocksToSaveByPage) {
            if (pageUuid in failedPageUuids) continue
            val shouldDelete = pageUuid in pageUuidsToDelete
            writeActor.execute(DatabaseWriteActor.Priority.LOW) {
                if (shouldDelete) {
                    blockRepository.deleteBlocksForPages(listOf(pageUuid))
                }
                blockRepository.saveBlocks(blocks).onLeft { e ->
                    logger.warn("saveBlocks failed for pageUuid=$pageUuid (${blocks.size} blocks): ${e.message}")
                    _writeErrors.tryEmit(WriteError(pageUuid, blocks.size, e))
                }
            }
        }
    }

    fun stopWatching() {
        watcherJob?.cancel()
        watcherJob = null
        writeActor.close()
    }

    /**
     * Adds [pathsBeingMerged] to the sticky git-merge suppression set so the 5-second
     * polling watcher ignores changes to these files during a git merge operation.
     *
     * Unlike single-shot suppression, these entries persist across multiple watcher
     * ticks until [endGitMerge] is called.
     *
     * Call this immediately before [reloadFiles] after git merge completes.
     * Always paired with [endGitMerge].
     */
    fun beginGitMerge(pathsBeingMerged: List<String>) {
        gitMergeSuppressedFiles.addAll(pathsBeingMerged)
    }

    /**
     * Clears the git-merge suppression set, restoring normal file-watcher behaviour.
     * Must be called after [reloadFiles] completes (or if merge is aborted).
     */
    fun endGitMerge() {
        gitMergeSuppressedFiles.clear()
    }

    /**
     * Explicitly reloads [filePaths] from disk and saves them to the database,
     * bypassing the file-watcher change-detection loop.
     *
     * Used after a successful git merge to push merged content into the DB.
     * Files with conflict markers are skipped by the existing [ConflictMarkerDetector]
     * guard inside [parseAndSavePage].
     */
    suspend fun reloadFiles(filePaths: List<String>) {
        for (path in filePaths) {
            val content = fileSystem.readFile(path) ?: continue
            parseAndSavePage(path, content, ParseMode.FULL, DatabaseWriteActor.Priority.HIGH)
        }
    }

    suspend fun loadFullPage(pageUuid: String) {
        PerformanceMonitor.startTrace("loadFullPage")
        var filePath: String? = null
        try {
            val pageResult = pageRepository.getPageByUuid(pageUuid).first()
            val page = pageResult.getOrNull()

            if (page == null) {
                logger.warn("Page not found for UUID: $pageUuid")
                return
            }

            filePath = page.filePath ?: resolvePageFilePath(page.name)
            if (filePath == null) {
                logger.warn("Page has no file path and could not be found on disk: ${page.name}")
                return
            }

            // OPTIMIZATION: If page is already loaded and file hasn't changed, skip reload
            val blocksResult = blockRepository.getBlocksForPage(page.uuid).first()
            val blocks = blocksResult.getOrNull() ?: emptyList()
            // A page is fully loaded if all its blocks are loaded
            val allBlocksLoaded = blocks.isNotEmpty() && blocks.all { it.isLoaded }

            if (allBlocksLoaded) {
                val fileModTime = fileSystem.getLastModifiedTime(filePath) ?: 0L
                
                if (fileModTime != 0L && 
                    page.updatedAt.toEpochMilliseconds() >= fileModTime) {
                    logger.debug("Skipping loadFullPage, already up to date: $filePath")
                    return
                }
            } else if (blocks.isNotEmpty()) {
                 logger.warn("Force reloading page ${page.name} because blocks are not fully loaded (inconsistency detected)")
            }

            if (!tryAddPriorityFile(filePath)) {
                logger.debug("Coalescing load request for $filePath")
                return
            }

            val content = fileSystem.readFile(filePath)
            if (content == null) {
                logger.warn("Failed to read file: $filePath")
                return
            }

            parseAndSavePage(filePath, content, ParseMode.FULL)
        } finally {
            filePath?.let { removePriorityFile(it) }
            PerformanceMonitor.endTrace("loadFullPage")
        }
    }

    private suspend fun loadJournalsImmediate(
        journalsDir: String,
        count: Int,
        onProgress: (String) -> Unit
    ): Int {
        PerformanceMonitor.startTrace("loadJournalsImmediate")
        try {
            // Single scan registers ALL journal files with the watcher
            fileRegistry.scanDirectory(journalsDir)
            val immediateFiles = fileRegistry.recentJournals(journalsDir, count)

            onProgress("Loading recent journals...")

            var loadedCount = 0
            for (entry in immediateFiles) {
                val content = fileSystem.readFile(entry.filePath) ?: continue
                try {
                    parseAndSavePage(entry.filePath, content, ParseMode.FULL)
                    loadedCount++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Failed to parse journal: ${entry.filePath}: ${e.message}")
                }
            }
            return loadedCount
        } finally {
            PerformanceMonitor.endTrace("loadJournalsImmediate")
        }
    }

    private suspend fun loadRemainingJournals(
        journalsDir: String,
        skipCount: Int,
        onProgress: (String) -> Unit
    ) {
        PerformanceMonitor.startTrace("loadRemainingJournals")
        try {
            // Uses the cached scan from loadJournalsImmediate — no re-scan needed
            val remainingFiles = fileRegistry.remainingJournals(journalsDir, skipCount, 30 - skipCount)
            if (remainingFiles.isEmpty()) return

            coroutineScope {
                var processedCount = 0
                val total = remainingFiles.size

                remainingFiles.chunked(50).map { chunk ->
                    async(Dispatchers.Default) {
                        val count = chunk.count { entry ->
                            val content = fileSystem.readFile(entry.filePath) ?: return@count false
                            try {
                                parseAndSavePage(entry.filePath, content, ParseMode.METADATA_ONLY)
                                true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                logger.warn("Failed to parse journal: ${entry.filePath}: ${e.message}")
                                false
                            }
                        }

                        processedCount += chunk.size
                        onProgress("Loading journals... ($processedCount/$total remaining)")
                        count
                    }
                }.awaitAll().sum()
            }
        } finally {
            PerformanceMonitor.endTrace("loadRemainingJournals")
        }
    }

    private suspend fun sanitizeDirectory(path: String) {
        if (!fileSystem.directoryExists(path)) return
        
        val files = fileSystem.listFiles(path).filter { it.endsWith(".md") }
        for (fileName in files) {
            val nameWithoutExt = fileName.removeSuffix(".md")
            val decodedName = FileUtils.decodeFileName(nameWithoutExt)
            val expectedName = FileUtils.sanitizeFileName(decodedName)
            
            if (nameWithoutExt != expectedName) {
                val oldPath = "$path/$fileName"
                val newPath = "$path/$expectedName.md"
                
                if (!fileSystem.fileExists(newPath)) {
                    try {
                        val content = fileSystem.readFile(oldPath)
                        if (content != null) {
                            if (fileSystem.writeFile(newPath, content)) {
                                if (fileSystem.deleteFile(oldPath)) {
                                    logger.info("Sanitized filename: '$fileName' -> '$expectedName.md'")
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("Error sanitizing file: $oldPath: ${e.message}")
                    }
                }
            }
        }
    }

    private suspend fun loadDirectory(path: String, onProgress: (String) -> Unit, mode: ParseMode = ParseMode.METADATA_ONLY) {
        PerformanceMonitor.startTrace("loadDirectory")
        try {
            if (!fileSystem.directoryExists(path)) return

            // Single scan registers ALL files and provides filtered views
            fileRegistry.scanDirectory(path)

            val fileEntries = if (path.endsWith("/journals")) {
                fileRegistry.recentJournals(path, 30)
            } else {
                fileRegistry.pageFiles(path)
            }

            // Pre-load all existing pages in one query. Replaces one getPageByName DB call per
            // file (up to 4 000 round-trips on a warm restart) with a single bulk read whose
            // result is shared across all parallel chunks read-only.
            val allPages = pageRepository.getAllPages().first().getOrNull() ?: emptyList()
            val pagesByName = allPages.associateBy { it.name.lowercase() }
            val pagesByJournalDate = allPages.filter { it.journalDate != null }
                .associateBy { it.journalDate!! }

            val loadedCount = coroutineScope {
                var processedCount = 0
                val total = fileEntries.size

                val chunkSize = if (ioThreads >= 8) 100 else 50
                fileEntries.chunked(chunkSize).map { chunk ->
                    async(parallelScope.coroutineContext) {
                        PerformanceMonitor.startTrace("processChunk")
                        try {
                            val pagesToSave = mutableListOf<Page>()
                            val blocksToSaveByPage = mutableMapOf<String, MutableList<Block>>()
                            val pageUuidsToDelete = mutableSetOf<String>()

                            val count = chunk.count { entry ->
                                val fileName = entry.fileName
                                val filePath = entry.filePath
                                val fileModTime = entry.modTime
                                
                                val title = FileUtils.decodeFileName(fileName.removeSuffix(".md"))
                                // Skip Logseq-internal file: protocol artifacts (e.g. file%3A..%2F%2F...)
                                if (title.startsWith("file:")) return@count false
                                val name = title
                                val isJournalFile = path.endsWith("/journals")
                                val existingPage = if (isJournalFile) {
                                    val journalDate = JournalUtils.parseJournalDate(title)
                                    if (journalDate != null) pagesByJournalDate[journalDate]
                                    else pagesByName[name.lowercase()]
                                } else {
                                    pagesByName[name.lowercase()]
                                }

                                // isContentLoaded is set atomically with block saves, so it reliably
                                // reflects whether blocks are present without a second DB round-trip.
                                val shouldSkip = existingPage != null && fileModTime != 0L &&
                                    existingPage.updatedAt.toEpochMilliseconds() >= fileModTime &&
                                    existingPage.isContentLoaded
                                
                                if (shouldSkip) {
                                    return@count true  // Count as processed but skip actual parsing
                                }

                                if (isPriorityFile(filePath)) return@count true
                                
                                val content = fileSystem.readFile(filePath) ?: return@count false
                                try {
                                    val parseResult = parsePageWithoutSaving(filePath, content, mode)
                                    val updatedPage = parseResult.page
                                    pagesToSave.add(updatedPage)
                                    if (parseResult.blocks.isNotEmpty()) {
                                        blocksToSaveByPage[updatedPage.uuid] = parseResult.blocks.toMutableList()
                                    }
                                    pageUuidsToDelete.add(updatedPage.uuid)
                                    true
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    logger.warn("Failed to parse file: $filePath: ${e.message}")
                                    false
                                }
                            }
                            
                            if (pagesToSave.isNotEmpty() || pageUuidsToDelete.isNotEmpty()) {
                                PerformanceMonitor.startTrace("batchDeleteBlocks")
                                flushChunkWritesPreemptible(pagesToSave, pageUuidsToDelete, blocksToSaveByPage)
                                PerformanceMonitor.endTrace("batchDeleteBlocks")
                            }
                            
                            processedCount += chunk.size
                            onProgress("Loading $path... ($processedCount/$total)")
                            count
                        } finally {
                            PerformanceMonitor.endTrace("processChunk")
                        }
                    }
                }.awaitAll().sum()
            }
        } finally {
            PerformanceMonitor.endTrace("loadDirectory")
        }
    }
    
    private val fileLocksMutex = Mutex()
    private val fileLocks = mutableMapOf<String, Mutex>()
    private val priorityFilesMutex = Mutex()
    private val priorityFiles = mutableSetOf<String>()

    private suspend fun getFileLock(path: String): Mutex {
        return fileLocksMutex.withLock { fileLocks.getOrPut(path) { Mutex() } }
    }

    private suspend fun tryAddPriorityFile(path: String): Boolean {
        return priorityFilesMutex.withLock {
            if (priorityFiles.contains(path)) false else {
                priorityFiles.add(path)
                true
            }
        }
    }

    private suspend fun removePriorityFile(path: String) {
        priorityFilesMutex.withLock { priorityFiles.remove(path) }
    }

    private suspend fun isPriorityFile(path: String): Boolean {
        return priorityFilesMutex.withLock { priorityFiles.contains(path) }
    }

    private data class ParseResult(
        val page: Page,
        val blocks: List<Block>
    )
    
    private suspend fun parsePageWithoutSaving(filePath: String, content: String, mode: ParseMode = ParseMode.FULL): ParseResult {
        val fileName = filePath.replace("\\", "/").substringAfterLast("/")
        val title = FileUtils.decodeFileName(fileName.removeSuffix(".md"))
        val name = title
        val journalDate = if (filePath.contains("/journals/")) JournalUtils.parseJournalDate(title) else null
        val isJournal = journalDate != null

        // Use file modification time if available
        val fileModTime = fileSystem.getLastModifiedTime(filePath)
        val updatedAt = fileModTime?.let { Instant.fromEpochMilliseconds(it) } ?: Clock.System.now()

        val existingPage = if (isJournal && journalDate != null) {
            journalDateResolver.getPageByJournalDate(journalDate)
        } else {
            pageRepository.getPageByName(name).first().getOrNull()
        }

        val pageUuid = existingPage?.uuid ?: UuidGenerator.generateV7()
        val createdAt = existingPage?.createdAt ?: updatedAt
        val currentVersion = existingPage?.version ?: 0L
        
        // Parse markdown content
        val parsedPage = markdownParser.parsePage(content)
        
        // Extract page properties
        var firstBlockSkipped = false
        var properties = parsedPage.properties
        if (parsedPage.blocks.isNotEmpty()) {
            val firstBlock = parsedPage.blocks.first()
            if (firstBlock.content.trim().isEmpty() && firstBlock.properties.isNotEmpty()) {
                properties = firstBlock.properties
                firstBlockSkipped = true
            }
        }

        // Only mark as fully loaded if we actually got some blocks, 
        // OR if the content is truly empty (whitespace only)
        val isLoaded = if (mode == ParseMode.FULL && parsedPage.blocks.isEmpty() && content.trim().isNotEmpty()) {
            false
        } else {
            mode == ParseMode.FULL
        }

        val pageWithMetadata = Page(
            uuid = pageUuid,
            name = name,
            namespace = null,
            filePath = filePath,
            createdAt = createdAt,
            updatedAt = updatedAt,
            version = currentVersion,
            properties = properties,
            isJournal = isJournal,
            journalDate = journalDate,
            isContentLoaded = isLoaded
        )
        
        // For METADATA_ONLY, create lightweight stub blocks (isLoaded = false)
        // without the expensive version-preservation pipeline or DB lookups.
        if (mode == ParseMode.METADATA_ONLY) {
            val rootBlocks = if (firstBlockSkipped) parsedPage.blocks.drop(1) else parsedPage.blocks
            val stubs = mutableListOf<Block>()
            createStubBlocks(rootBlocks, filePath, pageUuid, null, 0, updatedAt, stubs)
            return ParseResult(page = pageWithMetadata, blocks = stubs)
        }

        // Fetch existing blocks to preserve versions
        val existingBlocksResult = blockRepository.getBlocksForPage(pageUuid).first()
        val existingBlocks = existingBlocksResult.getOrNull() ?: emptyList()
        val existingVersions = existingBlocks.associate { it.uuid to it.version }
        val existingContent = existingBlocks.associate { it.uuid to it.content }

        // Process blocks based on mode
        val rootBlocks = if (firstBlockSkipped) parsedPage.blocks.drop(1) else parsedPage.blocks
        val blocksList = mutableListOf<Block>()

        // Load sidecar for content-hash → UUID recovery (e.g. after a git pull that reordered blocks)
        val pageSlug = FileUtils.sanitizeFileName(name)
        val sidecarMap = sidecarManager?.read(pageSlug)

        processParsedBlocks(
            parsedBlocks = rootBlocks,
            pagePath = filePath,
            pageUuid = pageUuid,
            parentUuid = null,
            baseLevel = 0,
            now = updatedAt,
            destinationList = blocksList,
            mode = mode,
            existingVersions = existingVersions,
            existingContent = existingContent,
            sidecarMap = sidecarMap,
        )

        return ParseResult(page = pageWithMetadata, blocks = blocksList)
    }
    
    /**
     * Result type for [lookupExistingPageAndCheckFreshness].
     * [skip] = true means the caller should bail out (page is already up-to-date).
     * [existingPage] and [cachedBlocks] are populated when [skip] = false.
     */
    private data class PageLookupResult(
        val skip: Boolean,
        val existingPage: Page? = null,
        val cachedBlocks: List<Block>? = null,
    )

    /**
     * Looks up the existing page record, performs the METADATA_ONLY early-exit check,
     * and (for FULL mode) fetches cached blocks + the freshness check so we can skip
     * re-parsing files that haven't changed on disk.
     */
    private suspend fun lookupExistingPageAndCheckFreshness(
        filePath: String,
        name: String,
        isJournal: Boolean,
        journalDate: kotlinx.datetime.LocalDate?,
        mode: ParseMode,
        traceId: String,
        parentSpanId: String,
    ): PageLookupResult {
        val lookupSpan = Span("db.lookupPage", traceId, parentSpanId)
        val existingPage = if (isJournal && journalDate != null) {
            journalDateResolver.getPageByJournalDate(journalDate)
        } else {
            pageRepository.getPageByName(name).first().getOrNull()
        }
        lookupSpan.finish("OK", "page.name" to name, "page.found" to (existingPage != null).toString())

        // Skip METADATA_ONLY if page is already fully loaded (don't overwrite full content)
        if (mode == ParseMode.METADATA_ONLY && existingPage != null) {
            return PageLookupResult(skip = true)
        }

        // OPTIMIZATION: If mode is FULL, but page is already loaded and fresh, skip.
        // Blocks are cached here to avoid a second DB round-trip at the diff-merge step.
        if (mode == ParseMode.FULL && existingPage != null) {
            val fileModTime = fileSystem.getLastModifiedTime(filePath) ?: 0L
            val getBlocksSpan = Span("db.getBlocks", traceId, parentSpanId)
            val blocks = blockRepository.getBlocksForPage(existingPage.uuid).first().getOrNull() ?: emptyList()
            getBlocksSpan.finish("OK", "block.count" to blocks.size.toString())
            val allBlocksLoaded = blocks.isNotEmpty() && blocks.all { it.isLoaded }
            if (fileModTime != 0L &&
                existingPage.updatedAt.toEpochMilliseconds() >= fileModTime &&
                allBlocksLoaded) {
                return PageLookupResult(skip = true)
            }
            return PageLookupResult(skip = false, existingPage = existingPage, cachedBlocks = blocks)
        }

        return PageLookupResult(skip = false, existingPage = existingPage, cachedBlocks = null)
    }

    /**
     * Constructs the [Page] model and extracts page-level properties from the first
     * (property) block when applicable.  Returns the built [Page] and whether the
     * property block was consumed (firstBlockSkipped).
     */
    private fun buildPageModel(
        filePath: String,
        name: String,
        isJournal: Boolean,
        journalDate: kotlinx.datetime.LocalDate?,
        existingPage: Page?,
        now: kotlin.time.Instant,
        mode: ParseMode,
        parsedPage: dev.stapler.stelekit.model.ParsedPage,
    ): Pair<Page, Boolean> {
        val fileModTime = fileSystem.getLastModifiedTime(filePath) ?: 0L
        val updatedAt = if (fileModTime != 0L) Instant.fromEpochMilliseconds(fileModTime) else now
        val createdAt = existingPage?.createdAt ?: updatedAt

        var page = Page(
            uuid = existingPage?.uuid ?: UuidGenerator.generateV7(),
            name = name,
            createdAt = createdAt,
            updatedAt = updatedAt,
            version = existingPage?.version ?: 0L,
            properties = emptyMap(),
            isJournal = isJournal,
            journalDate = journalDate,
            filePath = filePath,
            isContentLoaded = mode == ParseMode.FULL
        )

        var firstBlockSkipped = false
        if (parsedPage.blocks.isNotEmpty()) {
            val firstBlock = parsedPage.blocks.first()
            if (firstBlock.content.trim().isEmpty() && firstBlock.properties.isNotEmpty()) {
                page = page.copy(properties = firstBlock.properties)
                firstBlockSkipped = true
            }
        }

        return Pair(page, firstBlockSkipped)
    }

    /**
     * Handles the METADATA_ONLY write path: creates stub blocks and dispatches them to
     * the write actor.
     */
    private suspend fun saveMetadataOnlyBlocks(
        filePath: String,
        pageUuid: String,
        updatedAt: kotlin.time.Instant,
        rootBlocks: List<ParsedBlock>,
        priority: DatabaseWriteActor.Priority,
    ) {
        val stubs = mutableListOf<Block>()
        createStubBlocks(rootBlocks, filePath, pageUuid, null, 0, updatedAt, stubs)
        if (stubs.isNotEmpty()) {
            writeActor.deleteBlocksForPage(pageUuid, priority)
            writeActor.saveBlocks(stubs, priority).onLeft { e ->
                logger.warn("saveBlocks (stubs) failed for $filePath (${stubs.size} blocks): ${e.message}")
                _writeErrors.tryEmit(WriteError(filePath, stubs.size, e))
            }
        }
    }

    /**
     * Handles the FULL-mode block write path: applies the diff-merge strategy and
     * dispatches deletes + inserts/updates to the write actor.  Includes safety guards
     * for empty-parse and blank-file scenarios.
     */
    private suspend fun dispatchFullBlockWrites(
        filePath: String,
        content: String,
        existingBlocks: List<Block>,
        blocksToSave: List<Block>,
        priority: DatabaseWriteActor.Priority,
        traceId: String,
        parentSpanId: String,
    ) {
        val fileHasContent = content.trim().isNotEmpty()
        val existingBlockCount = existingBlocks.size
        when {
            blocksToSave.isEmpty() && fileHasContent -> {
                logger.warn("Parser returned no blocks for non-empty file '$filePath' " +
                    "(${content.length} chars) — skipping block update to prevent data loss")
            }
            blocksToSave.isEmpty() && !fileHasContent && existingBlockCount > 0 -> {
                // Blank-file guard: refuse to destroy non-empty in-memory state with an
                // empty file. This is the B2 fix for the 16:55 production incident where
                // an external process blanked the file and parseAndSavePage wiped the page.
                logger.warn(
                    "Blank-file parse for '$filePath' would destroy $existingBlockCount " +
                    "existing block(s) — skipping destructive write to prevent data loss"
                )
                _writeErrors.tryEmit(WriteError(
                    filePath, existingBlockCount,
                    dev.stapler.stelekit.error.DomainError.FileSystemError.WriteFailed(
                        filePath, "Blank external overwrite suppressed to prevent data loss"
                    )
                ))
            }
            else -> {
                // FULL mode: diff-based merge instead of delete-all + insert-all
                val existingSummaries = existingBlocks.map { b ->
                    DiffMerge.ExistingBlockSummary(uuid = b.uuid, contentHash = b.contentHash, isLoaded = b.isLoaded)
                }
                val diffSpan = Span("diff", traceId, parentSpanId)
                val diff = DiffMerge.diff(existingSummaries, blocksToSave)
                diffSpan.finish(
                    "OK",
                    "to.insert" to diff.toInsert.size.toString(),
                    "to.delete" to diff.toDelete.size.toString()
                )
                diff.toDelete.forEach { uuid ->
                    writeActor.deleteBlock(uuid).onLeft { e ->
                        logger.warn("deleteBlock failed for $uuid in $filePath: ${e.message}")
                    }
                }
                val blocksToWrite = diff.toInsert + diff.toUpdate
                if (blocksToWrite.isNotEmpty()) {
                    val saveBlocksSpan = Span("db.saveBlocks", traceId, parentSpanId)
                    writeActor.saveBlocks(blocksToWrite, priority).onLeft { e ->
                        logger.warn("saveBlocks failed for $filePath (${blocksToWrite.size} blocks): ${e.message}")
                        _writeErrors.tryEmit(WriteError(filePath, blocksToWrite.size, e))
                    }
                    saveBlocksSpan.finish("OK", "block.count" to blocksToWrite.size.toString())
                }
            }
        }
    }

    suspend fun parseAndSavePage(
        filePath: String,
        content: String,
        mode: ParseMode = ParseMode.FULL,
        priority: DatabaseWriteActor.Priority = DatabaseWriteActor.Priority.HIGH,
    ) {
        if (mode == ParseMode.METADATA_ONLY && isPriorityFile(filePath)) return

        val lock = getFileLock(filePath)
        lock.withLock {
            if (mode == ParseMode.METADATA_ONLY && isPriorityFile(filePath)) return

            PerformanceMonitor.startTrace("parseAndSavePage")
            val traceId = genId()
            val rootSpan = Span("parseAndSavePage", traceId)
            CurrentSpanContext.set(ActiveSpanContext(traceId = traceId, parentSpanId = rootSpan.spanId))
            try {
                val fileName = filePath.replace("\\", "/").substringAfterLast("/")
                val title = FileUtils.decodeFileName(fileName.removeSuffix(".md"))
                // Skip Logseq-internal file: protocol artifacts silently
                if (title.startsWith("file:")) return
                // Reject files with git conflict markers — importing them would corrupt the graph.
                if (ConflictMarkerDetector.hasConflictMarkers(content)) {
                    logger.warn("Git conflict markers detected in '$filePath' — import suppressed")
                    _writeErrors.tryEmit(WriteError(
                        filePath, 0,
                        dev.stapler.stelekit.error.DomainError.ParseError.InvalidSyntax(
                            "Git conflict markers detected in '$filePath' — resolve conflicts before importing"
                        )
                    ))
                    return@withLock
                }

                val name = title
                val journalDate = if (filePath.contains("/journals/")) JournalUtils.parseJournalDate(title) else null
                val isJournal = journalDate != null
                val now = Clock.System.now()

                val lookup = lookupExistingPageAndCheckFreshness(
                    filePath, name, isJournal, journalDate, mode, traceId, rootSpan.spanId
                )
                if (lookup.skip) return@withLock

                val existingPage = lookup.existingPage

                val parseSpan = Span("parse.markdown", traceId, rootSpan.spanId)
                val parsedPage = markdownParser.parsePage(content)
                parseSpan.finish("OK", "content.bytes" to content.length.toString())

                val (page, firstBlockSkipped) = buildPageModel(
                    filePath, name, isJournal, journalDate, existingPage, now, mode, parsedPage
                )
                val pageUuid = page.uuid
                val updatedAt = page.updatedAt

                val savePageSpan = Span("db.savePage", traceId, rootSpan.spanId)
                val savePageResult = writeActor.savePage(page, priority)
                savePageSpan.finish()
                if (savePageResult.isLeft()) {
                    val e = savePageResult.leftOrNull()!!
                    logger.warn("savePage failed for $filePath — skipping block writes to prevent FK violation: ${e.message}")
                    _writeErrors.tryEmit(WriteError(filePath, 0, e))
                    return@withLock
                }

                val rootBlocks = if (firstBlockSkipped) parsedPage.blocks.drop(1) else parsedPage.blocks

                // For METADATA_ONLY, save lightweight stub blocks and return.
                if (mode == ParseMode.METADATA_ONLY) {
                    saveMetadataOnlyBlocks(filePath, pageUuid, updatedAt, rootBlocks, priority)
                    return@withLock
                }

                // FULL mode: fetch existing blocks (may already be cached from freshness check)
                val existingBlocks = lookup.cachedBlocks
                    ?: blockRepository.getBlocksForPage(pageUuid).first().getOrNull() ?: emptyList()
                val existingVersions = existingBlocks.associate { it.uuid to it.version }
                val existingContent = existingBlocks.associate { it.uuid to it.content }

                // Load sidecar for content-hash → UUID recovery (e.g. after a git pull that reordered blocks)
                val pageSlug = FileUtils.sanitizeFileName(name)
                val sidecarMap = sidecarManager?.read(pageSlug)

                val blocksToSave = mutableListOf<Block>()
                val processBlocksSpan = Span("parse.processBlocks", traceId, rootSpan.spanId)
                processParsedBlocks(
                    parsedBlocks = rootBlocks,
                    pagePath = filePath,
                    pageUuid = pageUuid,
                    parentUuid = null,
                    baseLevel = 0,
                    now = updatedAt,
                    destinationList = blocksToSave,
                    mode = mode,
                    existingVersions = existingVersions,
                    existingContent = existingContent,
                    sidecarMap = sidecarMap,
                )
                processBlocksSpan.finish("OK", "block.count" to blocksToSave.size.toString())

                dispatchFullBlockWrites(
                    filePath, content, existingBlocks, blocksToSave, priority, traceId, rootSpan.spanId
                )

                // Update mod time in watcher cache so we don't re-trigger from our own write
                val updatedModTime = fileSystem.getLastModifiedTime(filePath) ?: 0L
                if (updatedModTime != 0L) {
                    fileRegistry.updateModTime(filePath, updatedModTime)
                }
            } finally {
                CurrentSpanContext.set(null)
                rootSpan.finish("OK", "file.path" to filePath)
                PerformanceMonitor.endTrace("parseAndSavePage")
            }
        }
    }

    private suspend fun processParsedBlocks(
        parsedBlocks: List<ParsedBlock>,
        pagePath: String,
        pageUuid: String,
        parentUuid: String?,
        baseLevel: Int,
        now: kotlin.time.Instant,
        destinationList: MutableList<Block>,
        mode: ParseMode,
        existingVersions: Map<String, Long> = emptyMap(),
        existingContent: Map<String, String> = emptyMap(),
        sidecarMap: Map<String, SidecarManager.SidecarEntry>? = null,
    ) {
        var previousSiblingUuid: String? = null

        parsedBlocks.forEachIndexed { index, parsedBlock ->
            val blockUuid = generateUuid(parsedBlock, pagePath, index, parentUuid, sidecarMap)
            val currentVersion = existingVersions[blockUuid] ?: 0L
            val oldContent = existingContent[blockUuid]

            val versionToSave = if (oldContent == parsedBlock.content) currentVersion else {
                if (currentVersion > 0) currentVersion + 1 else 0L
            }

            val mergedProperties = parsedBlock.properties.toMutableMap()
            parsedBlock.scheduled?.let { mergedProperties["scheduled"] = it }
            parsedBlock.deadline?.let { mergedProperties["deadline"] = it }

            val block = Block(
                uuid = blockUuid,
                pageUuid = pageUuid,
                parentUuid = parentUuid,
                leftUuid = previousSiblingUuid,
                content = parsedBlock.content,
                level = baseLevel,
                position = index,
                createdAt = now,
                updatedAt = now,
                version = versionToSave,
                properties = mergedProperties,
                isLoaded = mode == ParseMode.FULL,
                contentHash = ContentHasher.sha256ForContent(parsedBlock.content),
                blockType = parsedBlock.blockType.toDiscriminatorString()
            )

            destinationList.add(block)
            previousSiblingUuid = blockUuid
            
            if (parsedBlock.children.isNotEmpty()) {
                processParsedBlocks(
                    parsedBlocks = parsedBlock.children,
                    pagePath = pagePath,
                    pageUuid = pageUuid,
                    parentUuid = blockUuid,
                    baseLevel = baseLevel + 1,
                    now = now,
                    destinationList = destinationList,
                    mode = mode,
                    existingVersions = existingVersions,
                    existingContent = existingContent,
                    sidecarMap = sidecarMap,
                )
            }
        }
    }

    /**
     * Creates lightweight stub blocks with [isLoaded] = false for METADATA_ONLY
     * mode.  Avoids the DB round-trips that [processParsedBlocks] requires
     * (existing version lookups, content comparisons) so background loading
     * stays fast.
     */
    private fun createStubBlocks(
        parsedBlocks: List<ParsedBlock>,
        pagePath: String,
        pageUuid: String,
        parentUuid: String?,
        baseLevel: Int,
        now: kotlin.time.Instant,
        destination: MutableList<Block>
    ) {
        parsedBlocks.forEachIndexed { index, parsedBlock ->
            val blockUuid = generateUuid(parsedBlock, pagePath, index, parentUuid)
            val mergedProperties = parsedBlock.properties.toMutableMap()
            parsedBlock.scheduled?.let { mergedProperties["scheduled"] = it }
            parsedBlock.deadline?.let { mergedProperties["deadline"] = it }

            destination.add(
                Block(
                    uuid = blockUuid,
                    pageUuid = pageUuid,
                    parentUuid = parentUuid,
                    content = parsedBlock.content,
                    level = baseLevel,
                    position = index,
                    createdAt = now,
                    updatedAt = now,
                    properties = mergedProperties,
                    isLoaded = false,
                    contentHash = ContentHasher.sha256ForContent(parsedBlock.content),
                    blockType = parsedBlock.blockType.toDiscriminatorString()
                )
            )

            if (parsedBlock.children.isNotEmpty()) {
                createStubBlocks(parsedBlock.children, pagePath, pageUuid, blockUuid, baseLevel + 1, now, destination)
            }
        }
    }
}

/**
 * An external (non-app-initiated) change detected by the file watcher.
 *
 * @param filePath  Absolute path of the file that changed.
 * @param content   New content read from disk.
 * @param suppress  Call this to tell GraphLoader to skip automatic re-import for
 *                  this event.  Must be called synchronously during the collector's
 *                  handling of the event (before the next watcher tick).
 */
data class ExternalFileChange(
    val filePath: String,
    val content: String,
    val suppress: () -> Unit
)

/**
 * Emitted when a database write fails persistently (i.e. even the per-request individual
 * retry in [DatabaseWriteActor] could not save the data). Consumers (e.g. the ViewModel)
 * should surface this to the user and offer a retry action.
 *
 * @param filePath   Source markdown file path, or page UUID if a file path is unavailable.
 * @param blockCount Number of blocks that could not be saved (0 for page-level failures).
 * @param cause      The domain error.
 */
data class WriteError(
    val filePath: String,
    val blockCount: Int,
    val cause: dev.stapler.stelekit.error.DomainError,
)
