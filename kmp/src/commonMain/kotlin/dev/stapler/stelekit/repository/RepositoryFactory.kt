package dev.stapler.stelekit.repository

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.pragmaOptimizeAndClose
import dev.stapler.stelekit.db.OperationLogger
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.db.TelemetryDatabase
import dev.stapler.stelekit.db.UndoManager
import dev.stapler.stelekit.db.createDatabase
import dev.stapler.stelekit.logging.LogManager
import dev.stapler.stelekit.performance.HistogramWriter
import dev.stapler.stelekit.performance.OtelProvider
import dev.stapler.stelekit.performance.QueryStatsCollector
import dev.stapler.stelekit.performance.QueryStatsRepository
import dev.stapler.stelekit.performance.RingBufferSpanExporter
import dev.stapler.stelekit.performance.SloChecker
import dev.stapler.stelekit.performance.SpanEmitter
import dev.stapler.stelekit.performance.SpanLogSink
import dev.stapler.stelekit.performance.TimingDriverWrapper
import dev.stapler.stelekit.performance.wrapWithOtelIfAvailable
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

enum class GraphBackend {
    SQLDELIGHT,
    DATASCRIPT,
    KUZU,
    NEO4J,
    IN_MEMORY
}

interface RepositoryFactory {
    fun createBlockRepository(backend: GraphBackend): BlockRepository
    fun createPageRepository(backend: GraphBackend): PageRepository
    fun createPropertyRepository(backend: GraphBackend): PropertyRepository
    fun createReferenceRepository(backend: GraphBackend): ReferenceRepository
    fun createSearchRepository(backend: GraphBackend): SearchRepository
    fun close()
}

data class RepositorySet(
    val blockRepository: BlockRepository,
    val pageRepository: PageRepository,
    /** Write-only view of [pageRepository] with cache population disabled. For background indexing. */
    val backgroundPageRepository: PageRepository = pageRepository,
    val propertyRepository: PropertyRepository,
    val referenceRepository: ReferenceRepository,
    val searchRepository: SearchRepository,
    val journalService: JournalService,
    val writeActor: dev.stapler.stelekit.db.DatabaseWriteActor? = null,
    val undoManager: dev.stapler.stelekit.db.UndoManager? = null,
    val histogramWriter: dev.stapler.stelekit.performance.HistogramWriter? = null,
    val debugFlagRepository: dev.stapler.stelekit.performance.DebugFlagRepository? = null,
    val ringBuffer: dev.stapler.stelekit.performance.RingBufferSpanExporter? = null,
    val spanRepository: dev.stapler.stelekit.performance.SpanRepository? = null,
    val bugReportBuilder: dev.stapler.stelekit.performance.BugReportBuilder? = null,
    val perfExporter: dev.stapler.stelekit.performance.PerfExporter? = null,
    val spanEmitter: dev.stapler.stelekit.performance.SpanEmitter? = null,
    val sloChecker: dev.stapler.stelekit.performance.SloChecker? = null,
    val spanLogSink: dev.stapler.stelekit.performance.SpanLogSink? = null,
    /** Callback that runs WAL checkpoint after bulk graph import. Pass to [GraphLoader.onBulkImportComplete]. */
    val onBulkImportComplete: (suspend () -> Unit)? = null,
    val queryStatsRepository: dev.stapler.stelekit.performance.QueryStatsRepository? = null,
    val queryStatsCollector: dev.stapler.stelekit.performance.QueryStatsCollector? = null,
    val queryPlanRepository: dev.stapler.stelekit.performance.QueryPlanRepository? = null,
    val imageAnnotationRepository: ImageAnnotationRepository = InMemoryImageAnnotationRepository(),
    val measurementAnnotationRepository: MeasurementAnnotationRepository = InMemoryMeasurementAnnotationRepository(),
    val assetRepository: dev.stapler.stelekit.repository.AssetRepository = InMemoryAssetRepository(),
)

/**
 * Creates a [dev.stapler.stelekit.db.GraphLoader] wired with all standard production callbacks
 * from this [RepositorySet]. Use this in both production code and tests to guarantee parity —
 * any future wiring added here is automatically picked up by all callers.
 */
fun RepositorySet.createGraphLoader(
    fileSystem: dev.stapler.stelekit.platform.FileSystem,
    sidecarManager: dev.stapler.stelekit.db.SidecarManager? = null,
): dev.stapler.stelekit.db.GraphLoader =
    dev.stapler.stelekit.db.GraphLoader(
        fileSystem = fileSystem,
        pageRepository = pageRepository,
        blockRepository = blockRepository,
        journalDateResolver = journalService,
        externalWriteActor = writeActor,
        backgroundPageRepository = backgroundPageRepository,
        histogramWriter = histogramWriter,
        spanRepository = spanRepository,
        sidecarManager = sidecarManager,
    ).also { it.onBulkImportComplete = onBulkImportComplete }

/**
 * Factory implementation for creating repository instances.
 * Supports cross-platform database initialization and backend switching.
 */
class RepositoryFactoryImpl(
    private val driverFactory: DriverFactory,
    private val jdbcUrl: String = "jdbc:sqlite:stelekit.db",
    private val graphId: String? = null,
) : RepositoryFactory {

    private var activeDriver: app.cash.sqldelight.db.SqlDriver? = null
    private var activeReadDriver: app.cash.sqldelight.db.SqlDriver? = null
    private var wrappedDriver: app.cash.sqldelight.db.SqlDriver? = null
    private var activeTelemetryDriver: app.cash.sqldelight.db.SqlDriver? = null
    private var activeTelemetryDb: TelemetryDatabase? = null

    // Set by createRepositorySet before repositories are instantiated so constructors receive
    // the live perf objects. Fields are written once before first use — not thread-safe by design.
    internal var tracingRingBuffer: RingBufferSpanExporter? = null
    internal var queryStatsCollector: QueryStatsCollector? = null
    private var searchRingBuffer: RingBufferSpanExporter? = null
    private var searchHistogramWriter: dev.stapler.stelekit.performance.HistogramWriter? = null

    private val database: SteleDatabase by lazy {
        val writeDriver = driverFactory.createDriver(jdbcUrl)
        activeDriver = writeDriver
        val readDriver = driverFactory.createReadDriver(jdbcUrl)
        activeReadDriver = readDriver
        // On Android: readDriver is a second connection; router gives reads their own WAL
        // snapshot and never blocks them behind write transactions.
        // On JVM/iOS/WASM: readDriver is null; pool or single-connection handles R/W.
        val baseDriver = if (readDriver != null) {
            dev.stapler.stelekit.db.ReadWriteRouterDriver(readDriver, writeDriver)
        } else writeDriver
        val effectiveDriver = if (tracingRingBuffer != null || queryStatsCollector != null) {
            TimingDriverWrapper(baseDriver, tracingRingBuffer, queryStatsCollector)
        } else baseDriver
        wrappedDriver = effectiveDriver
        SteleDatabase(effectiveDriver)
    }

    private val instances = mutableMapOf<String, Any>()

    override fun createBlockRepository(backend: GraphBackend): BlockRepository {
        return when (backend) {
            GraphBackend.IN_MEMORY -> getOrCreateInstance("block_in_memory") {
                InMemoryBlockRepository()
            }
            GraphBackend.DATASCRIPT -> getOrCreateInstance("block_datascript") {
                DatalogBlockRepository()
            }
            GraphBackend.SQLDELIGHT -> getOrCreateInstance("block_sqldelight") {
                val db = database // ensure wrappedDriver is populated
                val driver = requireNotNull(wrappedDriver) {
                    "wrappedDriver is null — database lazy initializer did not complete successfully"
                }
                SqlDelightBlockRepository(db, driver).also { it.ftsStartupHeal() }
            }
            else -> throw NotImplementedError("Backend $backend not implemented")
        }
    }

    override fun createPageRepository(backend: GraphBackend): PageRepository {
        return when (backend) {
            GraphBackend.IN_MEMORY -> getOrCreateInstance("page_in_memory") {
                InMemoryPageRepository()
            }
            GraphBackend.DATASCRIPT -> getOrCreateInstance("page_datascript") {
                DatalogPageRepository()
            }
            GraphBackend.SQLDELIGHT -> getOrCreateInstance("page_sqldelight") {
                val repo = SqlDelightPageRepository(database)
                wrapWithOtelIfAvailable(repo, "dev.stapler.stelekit.page")
            }
            else -> throw NotImplementedError("Backend $backend not implemented")
        }
    }

    override fun createPropertyRepository(backend: GraphBackend): PropertyRepository {
        return when (backend) {
            GraphBackend.IN_MEMORY -> getOrCreateInstance("property_in_memory") {
                InMemoryPropertyRepository()
            }
            GraphBackend.DATASCRIPT -> getOrCreateInstance("property_datascript") {
                DatalogPropertyRepository()
            }
            GraphBackend.SQLDELIGHT -> getOrCreateInstance("property_sqldelight") {
                SqlDelightPropertyRepository(database)
            }
            else -> throw NotImplementedError("Backend $backend not implemented")
        }
    }

    override fun createReferenceRepository(backend: GraphBackend): ReferenceRepository {
        return when (backend) {
            GraphBackend.IN_MEMORY -> getOrCreateInstance("reference_in_memory") {
                InMemoryReferenceRepository()
            }
            GraphBackend.DATASCRIPT -> getOrCreateInstance("reference_datascript") {
                DatalogReferenceRepository()
            }
            GraphBackend.SQLDELIGHT -> getOrCreateInstance("reference_sqldelight") {
                SqlDelightReferenceRepository(database)
            }
            else -> throw NotImplementedError("Backend $backend not implemented")
        }
    }

    override fun createSearchRepository(backend: GraphBackend): SearchRepository {
        return when (backend) {
            GraphBackend.IN_MEMORY -> getOrCreateInstance("search_in_memory") {
                InMemorySearchRepository(
                    createPageRepository(backend),
                    createBlockRepository(backend)
                )
            }
            GraphBackend.DATASCRIPT -> getOrCreateInstance("search_datascript") {
                InMemorySearchRepository(
                    createPageRepository(backend),
                    createBlockRepository(backend)
                )
            }
            GraphBackend.SQLDELIGHT -> getOrCreateInstance("search_sqldelight") {
                val repo = SqlDelightSearchRepository(database, searchHistogramWriter, searchRingBuffer)
                wrapWithOtelIfAvailable(repo, "dev.stapler.stelekit.search")
            }
            else -> throw NotImplementedError("Backend $backend not implemented")
        }
    }

    fun createJournalService(backend: GraphBackend, writeActor: DatabaseWriteActor? = null): JournalService =
        JournalService(
            pageRepository = createPageRepository(backend),
            blockRepository = createBlockRepository(backend),
            writeActor = writeActor
        )

    @OptIn(DirectRepositoryWrite::class)
    suspend fun createRepositorySet(
        backend: GraphBackend,
        scope: CoroutineScope? = null,
        fileSystem: dev.stapler.stelekit.platform.FileSystem? = null,
        appVersion: String = "unknown",
        platform: String = "unknown",
    ): RepositorySet {
        // Determine the ring buffer BEFORE the database lazy property is first accessed so
        // TimingDriverWrapper can be wired in at driver-creation time.
        val ringBuffer = dev.stapler.stelekit.performance.OtelProvider.ringBuffer
            ?: if (backend == GraphBackend.SQLDELIGHT && scope != null)
                dev.stapler.stelekit.performance.RingBufferSpanExporter() else null
        if (ringBuffer != null) tracingRingBuffer = ringBuffer

        // Create collector before database is first accessed so TimingDriverWrapper picks it up
        val collector = if (scope != null && backend == GraphBackend.SQLDELIGHT) {
            QueryStatsCollector(appVersion, scope)
        } else null
        if (collector != null) queryStatsCollector = collector

        // Kick off telemetry driver open in parallel with main DB init — no data dependency.
        // Awaited after blockRepo init; telemetry typically finishes before main DB init completes.
        val telemetryDeferred = if (graphId != null && backend == GraphBackend.SQLDELIGHT && scope != null) {
            scope.async(PlatformDispatcher.IO) {
                try {
                    val driver = driverFactory.createTelemetryDriver(graphId)
                    activeTelemetryDriver = driver
                    TelemetryDatabase(driver).also { activeTelemetryDb = it }
                } catch (_: Exception) { null }
            }
        } else null

        val blockRepo = createBlockRepository(backend) // triggers write + read driver opens

        // Await telemetry result; usually already done since main DB init takes longer
        val telemetryDb: TelemetryDatabase? = when {
            telemetryDeferred != null -> telemetryDeferred.await()
            graphId != null && backend == GraphBackend.SQLDELIGHT -> {
                // scope == null: synchronous fallback (test-only path)
                try {
                    val driver = driverFactory.createTelemetryDriver(graphId)
                    activeTelemetryDriver = driver
                    TelemetryDatabase(driver).also { activeTelemetryDb = it }
                } catch (_: Exception) { null }
            }
            else -> null
        }
        val pageRepo = createPageRepository(backend)
        val backgroundPageRepo: PageRepository = if (backend == GraphBackend.SQLDELIGHT) {
            SqlDelightPageRepository(database, cacheWrites = false)
        } else pageRepo
        val (actor, undoManager) = if (scope != null) {
            val sessionId = dev.stapler.stelekit.util.UuidGenerator.generateV7()
            val opLogger = if (backend == GraphBackend.SQLDELIGHT) OperationLogger(database, sessionId) else null
            val writeActor = DatabaseWriteActor(blockRepo, pageRepo, opLogger)
            wireCacheCallbacks(writeActor, blockRepo as? SqlDelightBlockRepository)
            val undo = if (opLogger != null) UndoManager(database, writeActor, sessionId) else null
            writeActor to undo
        } else {
            null to null
        }

        // One mutex shared across all telemetry writers — serializes concurrent writes to the
        // single-connection telemetry DB (poolSize=1) at the Kotlin level.
        val telemetryWriteMutex = if (telemetryDb != null) Mutex() else null

        val queryStatsRepo = if (telemetryDb != null) {
            QueryStatsRepository(telemetryDb, telemetryWriteMutex!!)
        } else null
        if (queryStatsRepo != null) collector?.setRepository(queryStatsRepo)

        // Wire performance objects only for SQLDelight + a live scope (i.e. production graphs)
        val histogramWriter = if (telemetryDb != null && scope != null) {
            dev.stapler.stelekit.performance.HistogramWriter(telemetryDb, scope, writeMutex = telemetryWriteMutex!!)
        } else null

        if (telemetryDb != null && scope != null) {
            dev.stapler.stelekit.performance.HistogramRetentionJob(telemetryDb, writeMutex = telemetryWriteMutex!!).start(scope)
        }

        // Wire histogramWriter into actor and repositories for queue depth/wait tracking
        if (histogramWriter != null) {
            actor?.histogramWriter = histogramWriter
            (blockRepo as? SqlDelightBlockRepository)?.histogramWriter = histogramWriter
            (pageRepo as? SqlDelightPageRepository)?.histogramWriter = histogramWriter
            (backgroundPageRepo as? SqlDelightPageRepository)?.histogramWriter = histogramWriter
        }

        val debugFlagRepo = if (telemetryDb != null) {
            dev.stapler.stelekit.performance.DebugFlagRepository(telemetryDb, telemetryWriteMutex!!)
        } else null

        val spanRepository = if (telemetryDb != null) {
            SqlDelightSpanRepository(telemetryDb, telemetryWriteMutex!!)
        } else null

        val bugReportBuilder = if (histogramWriter != null && ringBuffer != null) {
            dev.stapler.stelekit.performance.BugReportBuilder(ringBuffer, histogramWriter)
        } else null

        val queryPlanRepoShared = if (backend == GraphBackend.SQLDELIGHT) {
            activeDriver?.let { dev.stapler.stelekit.performance.QueryPlanRepository(it) }
        } else null

        val perfExporter = if (spanRepository != null && histogramWriter != null && fileSystem != null) {
            dev.stapler.stelekit.performance.PerfExporter(
                spanRepository = spanRepository,
                histogramWriter = histogramWriter,
                fileSystem = fileSystem,
                appVersion = appVersion,
                platform = platform,
                queryStatsRepository = queryStatsRepo,
                queryStatsCollector = collector,
                queryPlanRepository = queryPlanRepoShared,
            )
        } else null

        // Wire perf objects that depend on both histogramWriter and ringBuffer being ready
        val spanEmitter = SpanEmitter(ringBuffer)
        if (ringBuffer != null) actor?.ringBuffer = ringBuffer
        searchHistogramWriter = histogramWriter
        searchRingBuffer = ringBuffer

        val sloChecker = if (histogramWriter != null && scope != null) {
            SloChecker(histogramWriter, spanEmitter, scope)
        } else null

        // Register a log sink that bridges ERROR logs into spans
        val spanLogSink = if (ringBuffer != null) {
            SpanLogSink(spanEmitter).also { LogManager.addSink(it) }
        } else null

        val sqlBlockRepo = blockRepo as? SqlDelightBlockRepository
        val poolWaitMetrics = activeDriver as? dev.stapler.stelekit.performance.PoolWaitMetrics

        // Background drain: every 5s flush in-memory ring buffer to SQLite, prune old data,
        // and record cache hit/miss and pool wait-time stats into the histogram.
        // Route through the actor so span inserts are serialized with all other DB writes and
        // don't race with block saves on Dispatchers.IO (which causes SQLITE_BUSY at startup).
        if (scope != null && ringBuffer != null && spanRepository != null) {
            launchDrainLoop(scope, ringBuffer, spanRepository, sqlBlockRepo, histogramWriter, poolWaitMetrics)
        }

        val walCallback: (suspend () -> Unit)? = sqlBlockRepo?.let { repo -> suspend { repo.walCheckpoint() } }

        val queryPlanRepo = queryPlanRepoShared

        return RepositorySet(
            blockRepository = blockRepo,
            pageRepository = pageRepo,
            backgroundPageRepository = backgroundPageRepo,
            propertyRepository = createPropertyRepository(backend),
            referenceRepository = createReferenceRepository(backend),
            searchRepository = createSearchRepository(backend),
            journalService = createJournalService(backend, actor),
            writeActor = actor,
            undoManager = undoManager,
            histogramWriter = histogramWriter,
            debugFlagRepository = debugFlagRepo,
            ringBuffer = ringBuffer,
            spanRepository = spanRepository,
            bugReportBuilder = bugReportBuilder,
            perfExporter = perfExporter,
            spanEmitter = spanEmitter,
            sloChecker = sloChecker,
            spanLogSink = spanLogSink,
            onBulkImportComplete = walCallback,
            queryStatsRepository = queryStatsRepo,
            queryStatsCollector = collector,
            queryPlanRepository = queryPlanRepo,
            imageAnnotationRepository = if (backend == GraphBackend.SQLDELIGHT)
                SqlDelightImageAnnotationRepository(database)
            else InMemoryImageAnnotationRepository(),
            measurementAnnotationRepository = if (backend == GraphBackend.SQLDELIGHT)
                SqlDelightMeasurementAnnotationRepository(database)
            else InMemoryMeasurementAnnotationRepository(),
            assetRepository = if (backend == GraphBackend.SQLDELIGHT)
                SqlDelightAssetRepository(database)
            else InMemoryAssetRepository(),
        )
    }

    private fun wireCacheCallbacks(actor: DatabaseWriteActor, sqlBlockRepo: SqlDelightBlockRepository?) {
        if (sqlBlockRepo == null) return
        actor.onWriteSuccess = { request ->
            when (request) {
                is DatabaseWriteActor.WriteRequest.SaveBlocks ->
                    request.blocks.forEach { sqlBlockRepo.evictBlock(it.uuid.value) }
                is DatabaseWriteActor.WriteRequest.SaveBlocksDiff -> {
                    request.toInsert.forEach { sqlBlockRepo.evictBlock(it.uuid.value) }
                    request.toUpdate.forEach { sqlBlockRepo.evictBlock(it.uuid.value) }
                }
                is DatabaseWriteActor.WriteRequest.DeleteBlocksForPage ->
                    sqlBlockRepo.evictHierarchyForPage(request.pageUuid.value)
                is DatabaseWriteActor.WriteRequest.DeleteBlocksForPages ->
                    request.pageUuids.forEach { sqlBlockRepo.evictHierarchyForPage(it.value) }
                // Execute requests wrap arbitrary lambdas — no block UUID to extract.
                // Entries written via Execute (e.g. saveBlock) rely on TTL expiry for
                // cache invalidation rather than explicit eviction.
                else -> Unit
            }
        }
    }

    private fun launchDrainLoop(
        scope: CoroutineScope,
        ringBuffer: dev.stapler.stelekit.performance.RingBufferSpanExporter,
        spanRepository: dev.stapler.stelekit.performance.SpanRepository,
        sqlBlockRepo: SqlDelightBlockRepository?,
        histogramWriter: dev.stapler.stelekit.performance.HistogramWriter?,
        poolWaitMetrics: dev.stapler.stelekit.performance.PoolWaitMetrics?,
    ) {
        scope.launch {
            val sevenDaysMs = 7L * 24L * 60L * 60L * 1000L
            while (true) {
                delay(5_000)
                val drained = ringBuffer.drain()
                if (drained.isNotEmpty()) withContext(PlatformDispatcher.IO) {
                    dev.stapler.stelekit.performance.SpanArchiver.archive(drained)
                }
                // Auto-feed every drained span into the histogram so operations are discovered
                // without maintaining a hardcoded KNOWN_OPERATIONS list.
                if (histogramWriter != null) {
                    drained.forEach { span ->
                        if (span.name != "slo.violation") {
                            histogramWriter.record(span.name, span.durationMs, span.startEpochMs)
                        }
                    }
                }
                // Telemetry DB has poolSize=1 and its own SQLite file — no WAL contention with
                // content writes, so we call directly instead of routing through the write actor.
                @OptIn(DirectRepositoryWrite::class)
                run {
                    spanRepository.insertSpans(drained)
                    spanRepository.deleteSpansOlderThan(HistogramWriter.epochMs() - sevenDaysMs)
                    spanRepository.deleteExcessSpans(10_000)
                }
                if (sqlBlockRepo != null && histogramWriter != null) {
                    val stats = sqlBlockRepo.cacheStats()
                    stats.forEach { (name, s) ->
                        val total = s.hits + s.misses
                        if (total > 0) histogramWriter.record("cache_$name", (s.hits * 100L) / total)
                        if (s.evictions > 0) histogramWriter.record("cache_${name}_evict", s.evictions)
                    }
                    // pool_wait is only non-null for in-memory DBs (where pool.take() blocks).
                    // File-backed production DBs use a non-blocking poll() with overflow connections,
                    // so pool contention is zero and drainPoolWaitStats() always returns null there.
                    poolWaitMetrics?.drainPoolWaitStats()?.let { snap ->
                        if (snap.count > 0) histogramWriter.record("pool_wait", snap.totalWaitMs / snap.count)
                    }
                }
            }
        }
    }

    /**
     * Exposes the underlying [SteleDatabase] instance for operations that need direct
     * SQL access (e.g. one-shot migrations). Only valid after the first repository has
     * been created (the database is initialised lazily on first use).
     */
    fun steleDatabase(): SteleDatabase = database

    override fun close() {
        // PRAGMA optimize + driver close per SQLite docs recommendation at connection close.
        // Platform-specific: JVM/iOS use runBlocking; WASM/JS skips PRAGMA (in-memory only).
        // Close write driver first (flushes WAL), then read driver.
        pragmaOptimizeAndClose(activeDriver)
        activeReadDriver?.close()
        activeReadDriver = null
        pragmaOptimizeAndClose(activeTelemetryDriver)
        activeTelemetryDriver = null
        activeTelemetryDb = null
        instances.clear()
        queryStatsCollector = null
    }

    private inline fun <reified T : Any> getOrCreateInstance(key: String, factory: () -> T): T {
        val existing = instances[key]
        if (existing != null) return existing as T
        val newInstance = factory()
        instances[key] = newInstance
        return newInstance
    }
}

/**
 * Global access to repositories.
 * 
 * Note: For multi-graph support, use GraphManager instead.
 */
object Repositories {
    private lateinit var factory: RepositoryFactoryImpl
    private var isInitialized = false

    fun initialize(driverFactory: DriverFactory, jdbcUrl: String = "jdbc:sqlite:stelekit.db") {
        factory = RepositoryFactoryImpl(driverFactory, jdbcUrl)
        isInitialized = true
    }

    fun getFactory(): RepositoryFactory = factory

    fun block(backend: GraphBackend = GraphBackend.SQLDELIGHT): BlockRepository =
        factory.createBlockRepository(backend)

    fun page(backend: GraphBackend = GraphBackend.SQLDELIGHT): PageRepository =
        factory.createPageRepository(backend)

    fun property(backend: GraphBackend = GraphBackend.SQLDELIGHT): PropertyRepository =
        factory.createPropertyRepository(backend)

    fun reference(backend: GraphBackend = GraphBackend.SQLDELIGHT): ReferenceRepository =
        factory.createReferenceRepository(backend)

    fun search(backend: GraphBackend = GraphBackend.SQLDELIGHT): SearchRepository =
        factory.createSearchRepository(backend)

    fun journal(backend: GraphBackend = GraphBackend.SQLDELIGHT): JournalService =
        factory.createJournalService(backend)
}
