package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import kotlin.concurrent.Volatile

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.model.ParsedBlock
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
import dev.stapler.stelekit.performance.heapSummary
import dev.stapler.stelekit.util.FileUtils
import dev.stapler.stelekit.util.UuidGenerator
import dev.stapler.stelekit.vault.CryptoLayer
import dev.stapler.stelekit.vault.VaultError
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * GraphLoader handles loading markdown files from disk into the database.
 *
 * Updated to use UUID-native storage for all references.
 * Includes file system watching for auto-reload.
 *
 * Paranoid-mode invariant: [cryptoLayer] must be set to a [CryptoLayer] initialized with the
 * current DEK before reading encrypted files. Setting it to null switches to plaintext mode.
 * The caller is responsible for keeping [cryptoLayer] in sync with the vault's lock/unlock
 * lifecycle — reading with a stale [CryptoLayer] after a DEK rotation will produce an
 * [AuthenticationFailed] error from AEAD tag verification.
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
    /** Initial CryptoLayer value — use [setCryptoLayer] to change at runtime. */
    initialCryptoLayer: CryptoLayer? = null,
    /** Poll interval for the file watcher in milliseconds. Override in tests to speed up cycles. */
    private val watcherPollIntervalMs: Long = 5_000L,
    /** When non-null, files matching the filter's excluded prefixes are skipped during loading. */
    private val sectionFilter: dev.stapler.stelekit.sections.SectionFilter? = null,
) : GraphLoaderPort {
    private val logger = Logger("GraphLoader")
    private val markdownParser = MarkdownParser()

    private fun String.stripPageExtension() = removeSuffix(".md.stek").removeSuffix(".md")

    /**
     * Resolve relative file path for CryptoLayer AAD.
     * Uses graph-root-relative path (e.g. "pages/Note.md.stek") per plan OPEN-1 decision.
     */
    private fun relativePathFor(absoluteFilePath: String): String {
        val graphPath = currentGraphPath
        if (graphPath.isEmpty()) return absoluteFilePath
        val base = if (graphPath.endsWith("/")) graphPath else "$graphPath/"
        return if (absoluteFilePath.startsWith(base)) {
            absoluteFilePath.removePrefix(base)
        } else {
            logger.error("relativePathFor: '$absoluteFilePath' is outside graph root '$graphPath' — AAD may be non-portable")
            absoluteFilePath
        }
    }

    /**
     * Read a file from disk, decrypting via [cryptoLayer] if paranoid mode is active.
     * Returns null if the file does not exist or decryption fails with a non-recoverable error.
     * Returns the plaintext string on success.
     */
    private fun readFileDecrypted(filePath: String): String? {
        val layer = cryptoLayer
        if (layer == null) {
            return fileSystem.readFile(filePath)
        }
        if (currentGraphPath.isEmpty()) {
            logger.error("readFileDecrypted: cryptoLayer is set but graphPath is empty — refusing to decrypt (wrong AAD)")
            return null
        }
        val rawBytes = fileSystem.readFileBytes(filePath) ?: return null
        val relPath = relativePathFor(filePath)
        return when (val result = layer.decrypt(relPath, rawBytes)) {
            is Either.Right -> result.value.decodeToString()
            is Either.Left -> when (val err = result.value) {
                is VaultError.NotEncrypted -> {
                    // .md.stek files MUST be encrypted — reject plaintext to prevent downgrade injection.
                    if (filePath.endsWith(".md.stek")) {
                        logger.error("Decryption failed for $filePath: file lacks STEK magic but has .md.stek extension — possible tampering or corruption. Refusing plaintext fallback.")
                        null
                    } else {
                        logger.warn("Paranoid mode active but $filePath has no STEK magic — reading as plaintext. Re-encrypt to clear this warning.")
                        fileSystem.readFile(filePath)
                    }
                }
                else -> {
                    logger.warn("Decryption failed for $filePath: ${err.message}")
                    null
                }
            }
        }
    }

    /** Called after a full bulk import completes. Used to trigger WAL checkpoint. */
    var onBulkImportComplete: (suspend () -> Unit)? = null

    /**
     * Backing field for the CryptoLayer used to decrypt/encrypt files in paranoid mode.
     * Volatile so changes published by [setCryptoLayer] are visible across coroutine threads.
     */
    @Volatile private var cryptoLayer: CryptoLayer? = initialCryptoLayer

    override fun setCryptoLayer(layer: CryptoLayer?) { cryptoLayer = layer }

    override fun closeAndClearCryptoLayer() { cryptoLayer?.close(); cryptoLayer = null }

    /**
     * Backing field for page UUIDs currently open in an active edit session.
     * Set via [setActivePageUuids] after construction to break the circular dependency
     * (BlockStateManager depends on GraphLoader).
     */
    private var activePageUuids: StateFlow<Set<String>>? = null

    /**
     * Derived set of file paths for actively-edited pages. Updated whenever [activePageUuids]
     * emits a new set. Passed to [GraphFileWatcher] as a lambda so the watcher can skip
     * auto-reload for pages being edited — without creating a new cross-layer dependency.
     *
     * UUID→path resolution happens here (inside GraphLoader which has PageRepository access);
     * GraphFileWatcher never imports PageRepository.
     */
    @Volatile private var activePageFilePaths: Set<FilePath> = emptySet()

    /** Job for the activePageFilePaths collector. Cancelled and replaced by each [setActivePageUuids] call. */
    private var activePageFilePathsJob: Job? = null

    /**
     * Derived set of file paths for pages with unsaved block edits. Populated from
     * [setUnsavedPageUuids]. The watcher guards only this set from auto-reload — pages
     * that are open but not being edited (e.g. the journals page being viewed) are excluded
     * and will be reloaded when an external change arrives.
     */
    @Volatile private var unsavedPageFilePaths: Set<FilePath> = emptySet()

    /** Job for the unsavedPageFilePaths collector. */
    private var unsavedPageFilePathsJob: Job? = null

    override fun setActivePageUuids(uuids: StateFlow<Set<String>>?) {
        activePageUuids = uuids
        // Cancel any existing collector before starting a new one to prevent coroutine leaks
        // on graph close / vault lock. Without this, the previous collector keeps running,
        // holds a reference to the old StateFlow, and calls pageRepository on a closed scope.
        activePageFilePathsJob?.cancel()
        activePageFilePathsJob = null
        if (uuids != null) {
            activePageFilePathsJob = parallelScope.launch {
                uuids.collect { uuidSet ->
                    activePageFilePaths = uuidSet.mapNotNull { uuid ->
                        pageRepository.getPageByUuid(PageUuid(uuid)).first().getOrNull()?.filePath?.let { FilePath(it) }
                    }.toSet()
                }
            }
        } else {
            activePageFilePaths = emptySet()
        }
    }

    override fun setUnsavedPageUuids(uuids: StateFlow<Set<String>>?) {
        unsavedPageFilePathsJob?.cancel()
        unsavedPageFilePathsJob = null
        if (uuids != null) {
            unsavedPageFilePathsJob = parallelScope.launch {
                uuids.collectLatest { uuidSet ->
                    unsavedPageFilePaths = uuidSet.mapNotNull { uuid ->
                        pageRepository.getPageByUuid(PageUuid(uuid)).first().getOrNull()?.filePath?.let { FilePath(it) }
                    }.toSet()
                }
            }
        } else {
            unsavedPageFilePaths = emptySet()
        }
    }

    // ---- Dirty set for cache invalidation ----------------------------------------

    /**
     * Paths that the watcher has flagged as externally changed. Checked in [loadFullPage]
     * to bypass the mtime guard when the watcher has confirmed an external edit.
     *
     * Guarded by [dirtyMutex] — both the watcher coroutine (via [addDirty]) and the
     * loadFullPage coroutine (via [checkAndClearDirty]) access this set concurrently.
     */
    private val dirtyPaths = mutableSetOf<FilePath>()
    private val dirtyMutex = kotlinx.coroutines.sync.Mutex()

    /** Adds [path] to the dirty set (called from the watcher when an external change is detected). */
    internal suspend fun addDirty(path: FilePath) = dirtyMutex.withLock { dirtyPaths.add(path) }

    /**
     * Checks whether [path] is in the dirty set and removes it atomically.
     * Returns true if the path was present (and has now been consumed).
     */
    internal suspend fun checkAndClearDirty(path: FilePath): Boolean = dirtyMutex.withLock {
        dirtyPaths.remove(path)
    }

    // Lightweight span tracking for the Spans waterfall tab.
    private fun genId(): String =
        kotlin.random.Random.nextLong().toULong().toString(16).padStart(16, '0')

    /**
     * Replaces a string with a stable opaque token for span attributes when the graph is
     * in paranoid (encrypted) mode — `cryptoLayer != null`.
     *
     * For unencrypted graphs the value is passed through unchanged: the data is already
     * in plaintext on disk, so showing real paths in telemetry adds no additional exposure.
     *
     * When redacting, uses the first 8 hex chars of SHA-256 (via [ContentHasher]) so the
     * token is deterministic and spans remain linkable, without leaking the original value
     * to brute-force via `String.hashCode()`.
     */
    private fun String.redactPath(): String {
        if (isEmpty() || cryptoLayer == null) return this
        val hash = dev.stapler.stelekit.util.ContentHasher.sha256ForContent(this).take(8)
        return "<redacted:$hash>"
    }

    private inner class Span(val name: String, val traceId: String, val parentSpanId: String = "") {
        val spanId: String = genId()
        private val startMs: Long = Clock.System.now().toEpochMilliseconds()
        @OptIn(DirectRepositoryWrite::class)
        fun finish(statusCode: String = "OK", vararg attrs: Pair<String, String>) {
            val endMs = Clock.System.now().toEpochMilliseconds()
            val allAttrs = mapOf(*attrs) + dev.stapler.stelekit.performance.AppSession.autoAttributes()
            val serialized = SerializedSpan(
                name = name, startEpochMs = startMs, endEpochMs = endMs,
                durationMs = endMs - startMs, attributes = allAttrs,
                statusCode = statusCode, traceId = traceId,
                spanId = spanId, parentSpanId = parentSpanId,
            )
            // Write to in-memory ring buffer immediately — visible in the Spans UI without
            // waiting for the actor queue. The actor path below persists to the DB.
            writeActor.ringBuffer?.record(serialized)
            if (spanRepository != null) {
                // Fire-and-forget: suspending here inflates parent span duration by the
                // queue wait time of every child's finish() call, making spans look 10–60×
                // slower than reality. parallelScope outlives graph close so spans are
                // persisted even if the caller returns before the actor drains.
                parallelScope.launch {
                    writeActor.execute(DatabaseWriteActor.Priority.LOW) {
                        spanRepository.insertSpan(serialized)
                        Unit.right()
                    }
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

    // Platform-agnostic coroutine scope for parallel processing.
    // CoroutineExceptionHandler logs unhandled exceptions (OOM, Error subclasses) instead of
    // crashing the app via the default uncaught exception handler.
    private val parallelScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
        CoroutineExceptionHandler { _, e ->
            if (e !is CancellationException) {
                logger.error("parallelScope: unhandled exception — ${e::class.simpleName}: ${e.message}", e)
            }
        }
    )

    // Serializes all DB writes to avoid SQLITE_BUSY under concurrent graph loading.
    // If an external actor is provided (from RepositorySet), reuse it; otherwise create one locally.
    private val writeActor = externalWriteActor ?: DatabaseWriteActor(blockRepository, pageRepository)

    // File-change watcher — owns its own CoroutineScope internally.
    private val fileWatcher = GraphFileWatcher(
        fileSystem = fileSystem,
        fileRegistry = fileRegistry,
        readFile = ::readFileDecrypted,
        onReloadFile = { filePath, content ->
            // forceReload=true: the watcher already confirmed the file changed and read the
            // current content. Re-querying getLastModifiedTime inside lookupExistingPageAndCheckFreshness
            // is unreliable on Android — some SAF providers cache the old mod time even after
            // an external write, causing the freshness guard to incorrectly skip the reload.
            parseAndSavePage(FilePath(filePath), content, dev.stapler.stelekit.parsing.ParseMode.FULL, forceReload = true)
        },
        pollIntervalMs = watcherPollIntervalMs,
        // Suspend lambda: called directly from checkDirectoryForChanges (already suspend) to
        // guarantee dirty flag is set before onReloadFile is called — no ordering race possible.
        onDirtyFile = { filePath -> addDirty(FilePath(filePath)) },
        // Guard only pages with unsaved block edits — not all open pages. Open-but-unedited
        // pages (e.g. the journals page being viewed) must still be reloaded on external change.
        activePageFilePaths = { unsavedPageFilePaths.map { it.value }.toSet() },
    )

    // Tracks the in-flight background indexing job so it can be cancelled under memory pressure.
    private var backgroundIndexJob: Job? = null

    /**
     * Pre-sets the graph path used by [relativePathFor] for AEAD-AAD computation.
     * Must be called before [cryptoLayer] is assigned so the first decryption uses
     * the correct relative path rather than falling back to the absolute path.
     */
    fun setGraphPath(path: String) {
        currentGraphPath = path
    }

    /**
     * Cancels any in-flight background indexing (Phase 2) immediately.
     * Safe to call from any thread. No-op if no indexing is running.
     */
    override fun cancelBackgroundWork() {
        backgroundIndexJob?.cancel()
        backgroundIndexJob = null
    }

    /**
     * Called by GraphWriter after it writes a file, so the watcher doesn't treat
     * our own write as an external change.
     *
     * [FileRegistry.markWrittenByUs] sets modTimes to 0 (the own-write sentinel) and stores
     * the content hash synchronously. The next [detectChanges] poll will see modTime > 0,
     * read the file, find a matching hash, suppress it as our own write, and update modTimes
     * to the real mtime — so only ONE extra readFile call occurs before the file is stable.
     *
     * A previous implementation tried to eliminate that extra readFile by firing a background
     * getLastModifiedTime coroutine. This introduced a race: if an external write occurred
     * between our write and the coroutine's getLastModifiedTime call, the coroutine would
     * store the EXTERNAL file's mtime. detectChanges would then see modTime == lastKnown
     * and silently skip the external change. Correctness beats the one-readFile optimization.
     */
    suspend fun markFileWrittenByUs(filePath: String) {
        fileRegistry.markWrittenByUs(filePath)
    }

    /**
     * Called before a write-behind SAF flush begins. Sets the [Long.MAX_VALUE] sentinel in
     * [FileRegistry] so that any concurrent [FileRegistry.detectChanges] poll skips this path
     * during the write window. Paired with [clearFilePendingWrite] on failure or replaced by
     * [markFileWrittenByUs] on success.
     */
    suspend fun preMarkFileWrite(filePath: String) {
        fileRegistry.preMarkPendingWrite(FilePath(filePath))
    }

    /**
     * Called when a write-behind SAF flush fails after [preMarkFileWrite] was called.
     * Removes the [Long.MAX_VALUE] sentinel so the file is not permanently suppressed.
     */
    suspend fun clearFilePendingWrite(filePath: String) {
        fileRegistry.clearPendingWrite(FilePath(filePath))
    }

    /**
     * Emits a synthetic external-file-change event for [filePath] with [content] as the
     * on-disk version. Called by the pre-write conflict check to surface a conflict
     * immediately when GraphWriter detects a hash mismatch before writing.
     */
    fun emitExternalFileChange(filePath: String, content: String) {
        fileWatcher.emitSyntheticChange(filePath, content)
    }

    /**
     * Emitted when the file watcher detects an external modification to a file.
     * Consumers (e.g. StelekitViewModel) can collect this flow and decide whether to
     * treat the change as a conflict.  If the event's [suppress] callback is invoked
     * before the next watcher tick, the automatic re-import is skipped.
     */
    override val externalFileChanges: SharedFlow<ExternalFileChange> = fileWatcher.externalFileChanges

    /**
     * Emitted when a DB write fails after all retries. Consumers (e.g. the ViewModel) can
     * surface an error banner and offer a retry action (re-calling [indexRemainingPages]).
     */
    private val _writeErrors = MutableSharedFlow<WriteError>(extraBufferCapacity = 16)
    override val writeErrors: SharedFlow<WriteError> = _writeErrors.asSharedFlow()

    private fun generateUuid(
        parsedBlock: ParsedBlock,
        pagePath: String,
        blockIndex: Int,
        parentUuid: String? = null,
        sidecarMap: Map<String, SidecarManager.SidecarEntry>? = null,
    ): String = MarkdownPageParser.generateUuid(parsedBlock, pagePath, blockIndex, parentUuid, sidecarMap)

    /**
     * Tries to find the page file by searching pages/ and journals/ directories.
     * Encrypted files (.md.stek) are checked before plaintext (.md) so paranoid-mode
     * graphs resolve correctly.
     */
    fun resolvePageFilePath(pageName: String): String? {
        if (currentGraphPath.isEmpty()) return null
        val candidates = listOf(
            "$currentGraphPath/pages/$pageName.md.stek",
            "$currentGraphPath/journals/$pageName.md.stek",
            "$currentGraphPath/pages/$pageName.md",
            "$currentGraphPath/journals/$pageName.md"
        )
        return candidates.firstOrNull { fileSystem.fileExists(it) }
    }

    /**
     * Priority-loads a page by name directly from disk.
     */
    override suspend fun loadPageByName(pageName: PageName): Page? {
        val filePathStr = resolvePageFilePath(pageName.value) ?: return null
        val filePath = FilePath(filePathStr)
        if (!tryAddPriorityFile(filePathStr)) {
            // Already loading — wait for it by polling the DB
            repeat(10) {
                val page = pageRepository.getPageByName(pageName.value).first().getOrNull()
                if (page?.isContentLoaded == true) return page
                kotlinx.coroutines.delay(200)
            }
            return pageRepository.getPageByName(pageName.value).first().getOrNull()
        }
        try {
            val content = readFileDecrypted(filePathStr) ?: return null
            parseAndSavePage(filePath, content, ParseMode.FULL)
            return pageRepository.getPageByName(pageName.value).first().getOrNull()
        } finally {
            removePriorityFile(filePathStr)
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
            rootSpan.finish("OK", "graph.path" to graphPath.redactPath(), "duration.ms" to duration.inWholeMilliseconds.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            rootSpan.finish("ERROR", "graph.path" to graphPath.redactPath(), "error.message" to (e.message ?: "unknown"))
            throw e
        } finally {
            CurrentSpanContext.set(null)
            PerformanceMonitor.endTrace("loadGraph")
        }
    }

    override suspend fun loadGraphProgressive(
        graphPath: String,
        immediateJournalCount: Int,
        onProgress: (String) -> Unit,
        onPhase1Complete: () -> Unit,
        onFullyLoaded: () -> Unit,
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
                        val heapAtStart = heapSummary()
                        logger.info("Warm reconcile starting ($heapAtStart)")
                        // Sanitize must run before re-scanning so any renamed files are visible
                        // to loadJournalsImmediate and loadDirectory below.
                        sanitizeDirectory(pagesDir)
                        sanitizeDirectory(journalsDir)
                        // Invalidate stale shadow entries before parsing reads so externally-
                        // changed files are not served from the old on-device cache.
                        // Uses a batch mtime cursor — no SAF content reads on this path.
                        // Failure is non-fatal: proceed with potentially stale shadow rather than
                        // aborting the reconcile entirely.
                        invalidateStaleShadowNonFatal(graphPath, "warm reconcile")
                        loadJournalsImmediate(journalsDir, immediateJournalCount, onProgress, DatabaseWriteActor.Priority.LOW)
                        coroutineScope {
                            launch { loadRemainingJournals(journalsDir, immediateJournalCount, onProgress) }
                            launch { loadDirectory(pagesDir, onProgress, ParseMode.METADATA_ONLY) }
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
                    } catch (e: Throwable) {
                        // Catch Throwable (not just Exception) so OutOfMemoryError and other
                        // JVM Error subclasses don't propagate to the default uncaught handler
                        // and crash the app. The CoroutineExceptionHandler on parallelScope is
                        // a backstop, but explicit catch here keeps the warmSpan finished.
                        warmSpan.finish("ERROR", "error.message" to (e.message ?: "unknown"))
                        logger.error("Warm reconcile failed — ${e::class.simpleName}: ${e.message}", e)
                    } finally {
                        backgroundIndexJob = null
                    }
                }
                rootSpan.finish("OK", "graph.path" to graphPath.redactPath(), "warm_start" to "true")
                return
            }

            val phase1Span = Span("graph_load.phase1_journals", traceId, rootSpan.spanId)
            // Invalidate stale shadow entries before Phase 1 reads so externally-changed
            // journals show current content. Uses a batch mtime cursor — no SAF content
            // reads here; stale files are read from SAF lazily during journal loading.
            // No-op on JVM. Failure is non-fatal.
            invalidateStaleShadowNonFatal(graphPath, "cold start")
            // phase1Start is placed after syncShadow so the span measures only journal
            // parse time, not shadow-sync latency.
            val phase1Start = Clock.System.now()
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
            rootSpan.finish("OK", "graph.path" to graphPath.redactPath(),
                "duration.ms" to totalDuration.inWholeMilliseconds.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            rootSpan.finish("ERROR", "graph.path" to graphPath.redactPath(), "error.message" to (e.message ?: "unknown"))
            throw e
        } finally {
            CurrentSpanContext.set(null)
            PerformanceMonitor.endTrace("loadGraphProgressive")
        }
    }

    fun startWatching(graphPath: String) {
        fileWatcher.startWatching(graphPath)
    }

    /**
     * Finds and fully indexes all pages that were only partially loaded (METADATA_ONLY).
     * This should be run in the background after Phase 1 completion.
     *
     * Mirrors loadDirectory's batch-write pattern: parse all pages in a chunk in parallel,
     * then flush all DB writes for the chunk in a single flushChunkWritesPreemptible call.
     * This reduces DatabaseWriteActor round-trips by ~90% vs. one-at-a-time parseAndSavePage.
     */
    override suspend fun indexRemainingPages(onProgress: (String) -> Unit) {
        // Track the calling coroutine's job so cancelBackgroundWork() can cancel it.
        backgroundIndexJob = currentCoroutineContext()[Job]
        PerformanceMonitor.startTrace("indexRemainingPages")
        try {
            val total = pageRepository.countUnloadedPages().getOrNull() ?: 0L
            if (total == 0L) return

            logger.info("Background indexing $total pages... (${heapSummary()})")

            coroutineScope {
                var processed = 0L
                // Drain in bounded batches instead of materializing every unloaded Page up
                // front (8 000+ objects on a first warm start — an Android OOM contributor).
                // Successfully indexed pages leave the unloaded set, so each fetch re-reads
                // at a fixed limit. Pages that stay unloaded after an attempt (missing file,
                // parse error, active edit session, zero-block parse) are remembered in
                // `attempted` — UUID strings only, a few hundred KB worst case — and the
                // offset advances past them when they are re-fetched. Termination is
                // guaranteed: every iteration either indexes a fresh page or grows the
                // offset by the full batch of stuck rows.
                val attempted = HashSet<String>()
                var offset = 0
                while (true) {
                    val batch = pageRepository
                        .getUnloadedPages(INDEX_BATCH_SIZE, offset)
                        .first().getOrNull().orEmpty()
                    if (batch.isEmpty()) break

                    val fresh = batch.filter { attempted.add(it.uuid.value) }
                    // Re-fetched rows we already attempted are stuck for this run — move the
                    // drain window past them so a fetch can never return only stuck rows.
                    offset += batch.size - fresh.size
                    if (fresh.isEmpty()) continue

                    fresh.chunked(10).forEach { chunk ->
                        val pagesToSave = mutableListOf<Page>()
                        val blocksToSaveByPage = mutableMapOf<PageUuid, MutableList<Block>>()
                        val pageUuidsToDelete = mutableSetOf<PageUuid>()

                        chunk.map { page ->
                            async(backgroundIndexDispatcher) {
                                if (page.uuid.value in (activePageUuids?.value ?: emptySet())) {
                                    logger.debug("Phase 3: skipping ${page.name} — active edit session")
                                    return@async null
                                }
                                val path = page.filePath ?: resolvePageFilePath(page.name)
                                if (path == null) return@async null
                                val content = readFileDecrypted(path) ?: return@async null
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
                        onProgress("Indexing pages... (${processed.coerceAtMost(total)}/$total)")
                    }
                }
            }
            logger.info("Background indexing complete.")
            compactFtsAfterBulkIndex()
        } finally {
            backgroundIndexJob = null
            PerformanceMonitor.endTrace("indexRemainingPages")
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
    // One controlled FTS merge pass after the full bulk-index batch, not per-page-save.
    // saveBlocks intentionally skips ftsMerge to avoid reading a large index on every
    // navigation; bulk callers compact once here when all inserts are done.
    @OptIn(DirectRepositoryWrite::class)
    private suspend fun compactFtsAfterBulkIndex() {
        writeActor.execute(DatabaseWriteActor.Priority.LOW) {
            blockRepository.compactFtsIndex()
            Unit.right()
        }
    }

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun flushChunkWritesPreemptible(
        pagesToSave: List<Page>,
        pageUuidsToDelete: Set<PageUuid>,
        blocksToSaveByPage: Map<PageUuid, List<Block>>,
    ) {
        val failedPageUuids = mutableSetOf<PageUuid>()

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
                    _writeErrors.tryEmit(WriteError(pageUuid.value, blocks.size, e))
                }
            }
        }
    }

    fun stopWatching() {
        fileWatcher.close()
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
    suspend fun beginGitMerge(pathsBeingMerged: List<String>) {
        fileWatcher.beginGitMerge(pathsBeingMerged)
    }

    /**
     * Clears the git-merge suppression set, restoring normal file-watcher behaviour.
     * Must be called after [reloadFiles] completes (or if merge is aborted).
     */
    suspend fun endGitMerge() {
        fileWatcher.endGitMerge()
    }

    /**
     * Explicitly reloads [filePaths] from disk and saves them to the database,
     * bypassing the file-watcher change-detection loop.
     *
     * Used after a successful git merge to push merged content into the DB.
     * Files with conflict markers are skipped by the existing [ConflictMarkerDetector]
     * guard inside [parseAndSavePage].
     */
    suspend fun reloadFiles(filePaths: List<FilePath>) {
        for (path in filePaths) {
            val content = readFileDecrypted(path.value) ?: continue
            parseAndSavePage(path, content, ParseMode.FULL, DatabaseWriteActor.Priority.HIGH, forceReload = true)
        }
    }

    override suspend fun loadFullPage(pageUuid: String, force: Boolean) {
        PerformanceMonitor.startTrace("loadFullPage")
        var filePath: String? = null
        try {
            val pageResult = pageRepository.getPageByUuid(PageUuid(pageUuid)).first()
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

            // OPTIMIZATION: If page is already loaded and file hasn't changed, skip reload.
            // New guard: watcher-driven dirty set replaces the unreliable mtime check.
            val blocksResult = blockRepository.getBlocksForPage(page.uuid).first()
            val blocks = blocksResult.getOrNull() ?: emptyList()
            // A page is fully loaded if all its blocks are loaded
            val allBlocksLoaded = blocks.isNotEmpty() && blocks.all { it.isLoaded }

            var forceReload = force
            if (!forceReload && allBlocksLoaded) {
                val isDirty = checkAndClearDirty(FilePath(filePath))
                if (!isDirty) {
                    if (fileWatcher.isRunning) {
                        // Watcher is running (JVM/Android): trust the dirty set.
                        // No dirty entry means the watcher has not detected an external change.
                        // NOTE: isRunning is false during the short startup window between
                        // onPhase1Complete and startWatching completing — during this window
                        // we fall through to the content-hash path below, which is correct.
                        logger.debug("Skipping loadFullPage, watcher reports no change: $filePath")
                        return
                    } else if (checkContentHashAndLoad(filePath)) {
                        return
                    }
                }
                // isDirty == true → set forceReload so both inner guards are also bypassed
                forceReload = true
            } else if (blocks.isNotEmpty() && !forceReload) {
                logger.warn("Force reloading page ${page.name} because blocks are not fully loaded (inconsistency detected)")
                forceReload = true
            }

            if (!tryAddPriorityFile(filePath)) {
                logger.debug("Coalescing load request for $filePath")
                return
            }

            // Drop any stale shadow so readFile goes to the real source (SAF on Android).
            // Reached when the page needs (re-)loading: dirty-set hit, missing blocks, or
            // content-hash mismatch (iOS/WASM).
            fileSystem.invalidateShadow(filePath)
            val fileReadTraceId = genId()
            val fileReadSpan = Span("file.read", fileReadTraceId, "")
            val content = readFileDecrypted(filePath)
            if (content == null) {
                fileReadSpan.finish("ERROR", "file.path" to filePath.redactPath())
                logger.warn("Failed to read file: $filePath")
                return
            }
            fileReadSpan.finish("OK", "file.path" to filePath.redactPath(), "content.bytes" to content.length.toString())

            parseAndSavePage(FilePath(filePath), content, ParseMode.FULL, forceReload = forceReload)
        } finally {
            filePath?.let { removePriorityFile(it) }
            PerformanceMonitor.endTrace("loadFullPage")
        }
    }

    /**
     * Content-hash guard for platforms without a file watcher (iOS/WASM).
     *
     * Returns `true` when the page has been fully handled (the caller should return immediately),
     * `false` when the caller should fall through to a full reload via [readFileDecrypted].
     */
    private suspend fun checkContentHashAndLoad(filePath: String): Boolean {
        // No watcher (iOS/WASM): fall back to content-hash check at navigation time.
        val storedHash = fileRegistry.getContentHash(FilePath(filePath)) ?: return false // No stored hash → fall through
        // Read the file once for the hash comparison.
        fileSystem.invalidateShadow(filePath)
        val diskContent = fileSystem.readFile(filePath)
        if (diskContent != null && diskContent.hashCode() == storedHash) {
            logger.debug("Skipping loadFullPage, content hash unchanged: $filePath")
            return true
        }
        // Hash mismatch — pass the already-read content to parseAndSavePage
        // to avoid a second disk read (halves I/O on iOS/WASM reload path).
        if (diskContent != null) {
            if (!tryAddPriorityFile(filePath)) {
                logger.debug("Coalescing load request for $filePath")
                return true
            }
            try {
                parseAndSavePage(FilePath(filePath), diskContent, ParseMode.FULL, forceReload = true)
            } finally {
                removePriorityFile(filePath)
            }
            return true
        }
        // diskContent == null (read failed) → fall through to reload via readFileDecrypted
        return false
    }

    private suspend fun loadJournalsImmediate(
        journalsDir: String,
        count: Int,
        onProgress: (String) -> Unit,
        priority: DatabaseWriteActor.Priority = DatabaseWriteActor.Priority.HIGH,
    ): Int {
        PerformanceMonitor.startTrace("loadJournalsImmediate")
        try {
            // Single scan registers ALL journal files with the watcher
            fileRegistry.scanDirectory(journalsDir)
            val immediateFiles = fileRegistry.recentJournals(journalsDir, count)

            onProgress("Loading recent journals...")

            var loadedCount = 0
            for (entry in immediateFiles) {
                fileSystem.invalidateShadow(entry.filePath)
                val content = readFileDecrypted(entry.filePath) ?: continue
                try {
                    parseAndSavePage(FilePath(entry.filePath), content, ParseMode.FULL, priority)
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
                            fileSystem.invalidateShadow(entry.filePath)
                            val content = readFileDecrypted(entry.filePath) ?: return@count false
                            try {
                                parseAndSavePage(FilePath(entry.filePath), content, ParseMode.METADATA_ONLY, DatabaseWriteActor.Priority.LOW)
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
        // Never traverse the hidden-volume reserve directory
        if (path.endsWith("/_hidden_reserve") || path.contains("/_hidden_reserve/")) return

        val files = fileSystem.listFiles(path).filter { fileName ->
            fileName.endsWith(".md") &&
            fileName != ".stele-vault" &&
            !fileName.endsWith(".md.stek")  // Never rename encrypted files
        }
        for (fileName in files) {
            val nameWithoutExt = fileName.removeSuffix(".md")
            val decodedName = FileUtils.decodeFileName(nameWithoutExt)
            val expectedName = FileUtils.sanitizeFileName(decodedName)
            
            if (nameWithoutExt != expectedName) {
                val oldPath = "$path/$fileName"
                val newPath = "$path/$expectedName.md"
                
                if (!fileSystem.fileExists(newPath) && !fileSystem.fileExists("$path/$expectedName.md.stek")) {
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

            val rawEntries = if (path.endsWith("/journals")) {
                fileRegistry.recentJournals(path, 30)
            } else {
                fileRegistry.pageFiles(path)
            }

            // When both a plaintext .md and its encrypted .md.stek counterpart exist
            // (e.g. after a partial migration), prefer the encrypted file and skip the
            // plaintext to avoid duplicate page entries in the database.
            val encryptedStems = rawEntries
                .filter { it.fileName.endsWith(".md.stek") }
                .mapTo(HashSet()) { it.fileName.stripPageExtension() }
            val fileEntries = if (encryptedStems.isEmpty()) rawEntries else {
                rawEntries.filter { entry ->
                    val keep = !entry.fileName.endsWith(".md") ||
                        entry.fileName.stripPageExtension() !in encryptedStems
                    if (!keep) logger.warn(
                        "Skipping plaintext ${entry.filePath} — encrypted .md.stek counterpart exists"
                    )
                    keep
                }
            }

            val isJournalDir = path.endsWith("/journals")

            val loadedCount = coroutineScope {
                var processedCount = 0
                val total = fileEntries.size

                val chunkSize = if (ioThreads >= 8) 100 else 50
                fileEntries.chunked(chunkSize).map { chunk ->
                    async(parallelScope.coroutineContext) {
                        PerformanceMonitor.startTrace("processChunk")
                        try {
                            // Per-chunk bounded existence lookups (one IN query per ≤100 files)
                            // instead of preloading the entire pages table. The former
                            // getAllPages() preload materialized every Page object plus two
                            // full-size maps for the duration of the load — on 8 000+ page
                            // graphs that contributed to the Android OOM. Peak memory here is
                            // now O(chunk), independent of graph size.
                            val chunkTitles = chunk.map {
                                FileUtils.decodeFileName(it.fileName.stripPageExtension())
                            }
                            val pagesByName = pageRepository
                                .getPagesByNames(chunkTitles)
                                .getOrNull().orEmpty()
                                .associateBy { it.name.lowercase() }
                            val pagesByJournalDate = if (isJournalDir) {
                                val dates = chunkTitles.mapNotNull { JournalUtils.parseJournalDate(it) }
                                pageRepository.getJournalPagesByDates(dates)
                                    .getOrNull().orEmpty()
                                    .filter { it.journalDate != null }
                                    .associateBy { it.journalDate!! }
                            } else {
                                emptyMap()
                            }

                            val pagesToSave = mutableListOf<Page>()
                            val blocksToSaveByPage = mutableMapOf<PageUuid, MutableList<Block>>()
                            val pageUuidsToDelete = mutableSetOf<PageUuid>()

                            val count = chunk.count { entry ->
                                val fileName = entry.fileName
                                val filePath = entry.filePath
                                val fileModTime = entry.modTime
                                
                                val title = FileUtils.decodeFileName(fileName.stripPageExtension())
                                // Skip Logseq-internal file: protocol artifacts (e.g. file%3A..%2F%2F...)
                                if (title.startsWith("file:")) return@count false
                                val name = title
                                val isJournalFile = isJournalDir
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

                                // Always drop any stale shadow before reading so the reconcile
                                // cannot serve old cached content for files it has determined
                                // need re-parsing. Mirrors loadFullPage's own-invalidate pattern.
                                fileSystem.invalidateShadow(filePath)
                                val content = readFileDecrypted(filePath) ?: return@count false
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

    private suspend fun invalidateStaleShadowNonFatal(graphPath: String, context: String) {
        try { withTimeout(SHADOW_STARTUP_TIMEOUT_MS) { fileSystem.invalidateStaleShadow(graphPath) } }
        catch (e: TimeoutCancellationException) {
            logger.warn("invalidateStaleShadow timed out on $context (${SHADOW_STARTUP_TIMEOUT_MS}ms) — proceeding with potentially stale shadow")
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { logger.warn("invalidateStaleShadow failed on $context: ${e.message}") }
    }

    companion object {
        // Timeout for the batch mtime cursor on startup. Two SAF cursor queries should
        // complete well under 500ms; 2s is a conservative ceiling for slow providers.
        private const val SHADOW_STARTUP_TIMEOUT_MS = 2_000L

        // Phase 3 drain-batch size: bounds how many unloaded Page rows are materialized at
        // once during background indexing, independent of graph size.
        private const val INDEX_BATCH_SIZE = 100
    }

    private data class ParseResult(
        val page: Page,
        val blocks: List<Block>
    )
    
    private suspend fun parsePageWithoutSaving(filePath: String, content: String, mode: ParseMode = ParseMode.FULL): ParseResult {
        val fileName = filePath.replace("\\", "/").substringAfterLast("/")
        val title = FileUtils.decodeFileName(fileName.stripPageExtension())
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

        val pageUuid: PageUuid = existingPage?.uuid ?: PageUuid(UuidGenerator.generateV7())
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
        forceReload: Boolean = false,
    ): PageLookupResult {
        val lookupSpan = Span("db.lookupPage", traceId, parentSpanId)
        val existingPage = if (isJournal && journalDate != null) {
            journalDateResolver.getPageByJournalDate(journalDate)
        } else {
            pageRepository.getPageByName(name).first().getOrNull()
        }
        lookupSpan.finish("OK", "page.name" to name.redactPath(), "page.found" to (existingPage != null).toString())

        // Skip METADATA_ONLY if page is already fully loaded (don't overwrite full content)
        if (mode == ParseMode.METADATA_ONLY && existingPage != null) {
            return PageLookupResult(skip = true)
        }

        // OPTIMIZATION: If mode is FULL, but page is already loaded and fresh, skip.
        // Blocks are cached here to avoid a second DB round-trip at the diff-merge step.
        // When forceReload=true (dirty-set hit from loadFullPage), skip this guard entirely —
        // the caller has already determined the page is stale and must be reloaded.
        if (mode == ParseMode.FULL && existingPage != null) {
            val fileModTime = fileSystem.getLastModifiedTime(filePath) ?: 0L
            val getBlocksSpan = Span("db.getBlocks", traceId, parentSpanId)
            val blocks = blockRepository.getBlocksForPage(existingPage.uuid).first().getOrNull() ?: emptyList()
            getBlocksSpan.finish(
                "OK",
                "block.count" to blocks.size.toString(),
                "page.name" to name.redactPath(),
                "page.is_journal" to isJournal.toString(),
            )
            val allBlocksLoaded = blocks.isNotEmpty() && blocks.all { it.isLoaded }
            val pageIsUpToDate = !forceReload && fileModTime != 0L &&
                existingPage.updatedAt.toEpochMilliseconds() >= fileModTime
            if (pageIsUpToDate && allBlocksLoaded) {
                return PageLookupResult(skip = true)
            }
            return PageLookupResult(skip = false, existingPage = existingPage, cachedBlocks = blocks)
        }

        return PageLookupResult(skip = false, existingPage = existingPage, cachedBlocks = null)
    }

    private fun buildPageModel(
        filePath: String,
        name: String,
        isJournal: Boolean,
        journalDate: kotlinx.datetime.LocalDate?,
        existingPage: Page?,
        now: kotlin.time.Instant,
        mode: ParseMode,
        parsedPage: dev.stapler.stelekit.model.ParsedPage,
    ): PageBuildResult = MarkdownPageParser.buildPageModel(
        filePath = filePath,
        name = name,
        isJournal = isJournal,
        journalDate = journalDate,
        existingPage = existingPage,
        now = now,
        mode = mode,
        parsedPage = parsedPage,
        fileModTime = fileSystem.getLastModifiedTime(filePath),
    )

    /**
     * Handles the METADATA_ONLY write path: creates stub blocks and dispatches them to
     * the write actor in a single execute (savePage + deleteBlocksForPage + saveBlocks = 1 RT).
     */
    @OptIn(DirectRepositoryWrite::class)
    private suspend fun saveMetadataOnlyBlocks(
        filePath: String,
        page: Page,
        rootBlocks: List<ParsedBlock>,
        priority: DatabaseWriteActor.Priority,
        traceId: String,
        parentSpanId: String,
    ) {
        val pageUuid = page.uuid
        val stubs = mutableListOf<Block>()
        createStubBlocks(rootBlocks, filePath, pageUuid, null, 0, page.updatedAt, stubs)
        writeActor.execute(priority) {
            val span = Span("db.saveMetadata", traceId, parentSpanId)
            val result = run {
                val r = pageRepository.savePage(page)
                if (r.isLeft()) return@run r
                blockRepository.deleteBlocksForPage(pageUuid)
                if (stubs.isNotEmpty()) blockRepository.saveBlocks(stubs)
                Unit.right()
            }
            span.finish(if (result.isLeft()) "ERROR" else "OK", "stub.count" to stubs.size.toString())
            result
        }.onLeft { e ->
            logger.warn("saveMetadata failed for $filePath (${stubs.size} stubs): ${e.message}")
            _writeErrors.tryEmit(WriteError(filePath, stubs.size, e))
        }
    }

    /**
     * Handles the FULL-mode block write path: applies the diff-merge strategy and
     * dispatches deletes + inserts/updates to the write actor.  Includes safety guards
     * for empty-parse and blank-file scenarios.
     *
     * savePage + saveBlocks + saveBlocksUpdate are executed inside a single composite
     * actor.execute { } lambda to eliminate two CompletableDeferred.await() suspension
     * points (Fix B from android-trace-db-fixes).
     */
    @OptIn(DirectRepositoryWrite::class)
    private suspend fun dispatchFullBlockWrites(
        filePath: String,
        content: String,
        existingBlocks: List<Block>,
        blocksToSave: List<Block>,
        page: Page,
        priority: DatabaseWriteActor.Priority,
        traceId: String,
        parentSpanId: String,
    ) {
        val pageUuid = page.uuid
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
                val blocksToInsert = diff.toInsert
                val blocksToUpdate = diff.toUpdate
                // Single actor round-trip: deletes + savePage + inserts/updates.
                // Batching eliminates N separate deleteBlock round-trips (was N+1 actor
                // enqueues; now always 1). Deletions run before inserts inside the execute
                // to avoid UNIQUE constraint violations on position reuse.
                // Span is created inside execute so it measures SQL execution time only,
                // not actor queue wait — queue wait is recorded separately as db.queue_wait.
                val writeResult = writeActor.execute(priority) {
                    val writeBlocksSpan = Span("db.writeBlocks", traceId, parentSpanId)
                    val result = run {
                        diff.toDelete.forEach { uuid ->
                            blockRepository.deleteBlock(uuid).onLeft { e ->
                                logger.warn("deleteBlock failed for $uuid in $filePath: ${e.message}")
                            }
                        }
                        val pageResult = pageRepository.savePage(page)
                        if (pageResult.isLeft()) return@run pageResult
                        if (blocksToInsert.isNotEmpty()) {
                            val r = blockRepository.saveBlocks(blocksToInsert)
                            if (r.isLeft()) return@run r
                        }
                        if (blocksToUpdate.isNotEmpty()) {
                            val r = blockRepository.saveBlocksUpdate(blocksToUpdate)
                            if (r.isLeft()) return@run r
                        }
                        Unit.right()
                    }
                    writeBlocksSpan.finish(
                        if (result.isLeft()) "ERROR" else "OK",
                        "delete.count" to diff.toDelete.size.toString(),
                        "insert.count" to blocksToInsert.size.toString(),
                        "update.count" to blocksToUpdate.size.toString()
                    )
                    result
                }
                writeResult.onLeft { e ->
                    logger.warn("composite delete+savePage+saveBlocks failed for $filePath: ${e.message}")
                    _writeErrors.tryEmit(WriteError(filePath, blocksToInsert.size + blocksToUpdate.size, e))
                }
                if (blocksToInsert.isNotEmpty() || blocksToUpdate.isNotEmpty()) {
                    (blockRepository as? dev.stapler.stelekit.repository.SqlDelightBlockRepository)?.evictHierarchyForPage(pageUuid.value)
                }
            }
        }
    }

    /**
     * Internal overload that accepts [forceReload] to bypass the inner mtime guard in
     * [lookupExistingPageAndCheckFreshness]. Called from [loadFullPage] when the dirty-set
     * path triggers a reload (both the outer and inner mtime guards must be bypassed together).
     *
     * The public [GraphLoaderPort.parseAndSavePage] interface does not expose [forceReload] —
     * it is an internal implementation detail of the cache-invalidation pipeline.
     */
    override suspend fun parseAndSavePage(
        filePath: FilePath,
        content: String,
        mode: ParseMode,
        priority: DatabaseWriteActor.Priority,
    ) = parseAndSavePage(filePath, content, mode, priority, forceReload = false)

    private suspend fun parseAndSavePage(
        filePath: FilePath,
        content: String,
        mode: ParseMode,
        priority: DatabaseWriteActor.Priority = DatabaseWriteActor.Priority.HIGH,
        forceReload: Boolean,
    ) {
        val filePathStr = filePath.value
        if (mode == ParseMode.METADATA_ONLY && isPriorityFile(filePathStr)) return

        val lock = getFileLock(filePathStr)
        lock.withLock {
            if (mode == ParseMode.METADATA_ONLY && isPriorityFile(filePathStr)) return

            PerformanceMonitor.startTrace("parseAndSavePage")
            val traceId = genId()
            val rootSpan = Span("parseAndSavePage", traceId)
            CurrentSpanContext.set(ActiveSpanContext(traceId = traceId, parentSpanId = rootSpan.spanId))
            try {
                val fileName = filePathStr.replace("\\", "/").substringAfterLast("/")
                val title = FileUtils.decodeFileName(fileName.stripPageExtension())
                // Skip Logseq-internal file: protocol artifacts silently
                if (title.startsWith("file:")) return
                // Reject files with git conflict markers — importing them would corrupt the graph.
                if (ConflictMarkerDetector.hasConflictMarkers(content)) {
                    logger.warn("Git conflict markers detected in '$filePathStr' — import suppressed")
                    _writeErrors.tryEmit(WriteError(
                        filePathStr, 0,
                        dev.stapler.stelekit.error.DomainError.ParseError.InvalidSyntax(
                            "Git conflict markers detected in '$filePathStr' — resolve conflicts before importing"
                        )
                    ))
                    return@withLock
                }

                val name = title
                val journalDate = if (filePathStr.contains("/journals/")) JournalUtils.parseJournalDate(title) else null
                val isJournal = journalDate != null
                val now = Clock.System.now()

                val lookup = lookupExistingPageAndCheckFreshness(
                    filePathStr, name, isJournal, journalDate, mode, traceId, rootSpan.spanId, forceReload
                )
                if (lookup.skip) return@withLock

                val existingPage = lookup.existingPage

                val parseSpan = Span("parse.markdown", traceId, rootSpan.spanId)
                val parsedPage = markdownParser.parsePage(content)
                parseSpan.finish("OK", "content.bytes" to content.length.toString())

                val (page, firstBlockSkipped) = buildPageModel(
                    filePathStr, name, isJournal, journalDate, existingPage, now, mode, parsedPage
                )
                val pageUuid = page.uuid
                val updatedAt = page.updatedAt

                val rootBlocks = if (firstBlockSkipped) parsedPage.blocks.drop(1) else parsedPage.blocks

                // For METADATA_ONLY, save page + lightweight stub blocks and return.
                if (mode == ParseMode.METADATA_ONLY) {
                    saveMetadataOnlyBlocks(filePathStr, page, rootBlocks, priority, traceId, rootSpan.spanId)
                    return@withLock
                }

                // FULL mode: fetch existing blocks (may already be cached from freshness check)
                val existingBlocks = lookup.cachedBlocks
                    ?: run {
                        val uncachedBlocksSpan = Span("db.getBlocks", traceId, rootSpan.spanId)
                        val blocks = blockRepository.getBlocksForPage(pageUuid).first().getOrNull() ?: emptyList()
                        uncachedBlocksSpan.finish("OK", "block.count" to blocks.size.toString(), "cached" to "false")
                        blocks
                    }
                val existingVersions = existingBlocks.associate { it.uuid to it.version }
                val existingContent = existingBlocks.associate { it.uuid to it.content }

                // Load sidecar for content-hash → UUID recovery (e.g. after a git pull that reordered blocks)
                val pageSlug = FileUtils.sanitizeFileName(name)
                val sidecarReadSpan = Span("sidecar.read", traceId, rootSpan.spanId)
                val sidecarMap = sidecarManager?.read(pageSlug)
                sidecarReadSpan.finish("OK", "has_sidecar" to (sidecarMap != null).toString())

                val blocksToSave = mutableListOf<Block>()
                val processBlocksSpan = Span("parse.processBlocks", traceId, rootSpan.spanId)
                processParsedBlocks(
                    parsedBlocks = rootBlocks,
                    pagePath = filePathStr,
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
                    filePathStr, content, existingBlocks, blocksToSave, page, priority, traceId, rootSpan.spanId
                )
            } finally {
                CurrentSpanContext.set(null)
                rootSpan.finish("OK", "file.path" to filePathStr.redactPath())
                PerformanceMonitor.endTrace("parseAndSavePage")
            }
        }
    }

    private fun processParsedBlocks(
        parsedBlocks: List<ParsedBlock>,
        pagePath: String,
        pageUuid: PageUuid,
        parentUuid: String?,
        baseLevel: Int,
        now: kotlin.time.Instant,
        destinationList: MutableList<Block>,
        mode: ParseMode,
        existingVersions: Map<BlockUuid, Long> = emptyMap(),
        existingContent: Map<BlockUuid, String> = emptyMap(),
        sidecarMap: Map<String, SidecarManager.SidecarEntry>? = null,
    ) = MarkdownPageParser.processParsedBlocks(
        parsedBlocks = parsedBlocks,
        pagePath = pagePath,
        pageUuid = pageUuid,
        parentUuid = parentUuid,
        baseLevel = baseLevel,
        now = now,
        destinationList = destinationList,
        mode = mode,
        existingVersions = existingVersions,
        existingContent = existingContent,
        sidecarMap = sidecarMap,
    )

    private fun createStubBlocks(
        parsedBlocks: List<ParsedBlock>,
        pagePath: String,
        pageUuid: PageUuid,
        parentUuid: String?,
        baseLevel: Int,
        now: kotlin.time.Instant,
        destination: MutableList<Block>
    ) = MarkdownPageParser.createStubBlocks(parsedBlocks, pagePath, pageUuid, parentUuid, baseLevel, now, destination)
}
