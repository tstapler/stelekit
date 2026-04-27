package dev.stapler.stelekit.repository

import arrow.core.right
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.OperationLogger
import dev.stapler.stelekit.db.SteleDatabase
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Factory implementation for creating repository instances.
 * Supports cross-platform database initialization and backend switching.
 */
class RepositoryFactoryImpl(
    private val driverFactory: DriverFactory,
    private val jdbcUrl: String = "jdbc:sqlite:stelekit.db"
) : RepositoryFactory {

    private var activeDriver: app.cash.sqldelight.db.SqlDriver? = null

    // Set by createRepositorySet before repositories are instantiated so constructors receive
    // the live perf objects. Fields are written once before first use — not thread-safe by design.
    internal var tracingRingBuffer: RingBufferSpanExporter? = null
    internal var queryStatsCollector: QueryStatsCollector? = null
    private var searchRingBuffer: RingBufferSpanExporter? = null
    private var searchHistogramWriter: dev.stapler.stelekit.performance.HistogramWriter? = null

    private val database: SteleDatabase by lazy {
        val driver = driverFactory.createDriver(jdbcUrl)
        activeDriver = driver
        val effectiveDriver = if (tracingRingBuffer != null || queryStatsCollector != null) {
            TimingDriverWrapper(driver, tracingRingBuffer, queryStatsCollector)
        } else driver
        SteleDatabase(effectiveDriver)
    }

    private val instances = mutableMapOf<String, Any>()

    override fun createBlockRepository(backend: GraphBackend): BlockRepository {
        return when (backend) {
            GraphBackend.IN_MEMORY -> getOrCreateInstance("block_in_memory") {
                InMemoryBlockRepository()
            }
            GraphBackend.DATASCRIPT -> getOrCreateInstance("block_datascript") {
                DatascriptBlockRepository()
            }
            GraphBackend.SQLDELIGHT -> getOrCreateInstance("block_sqldelight") {
                SqlDelightBlockRepository(database)
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
                DatascriptPageRepository()
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
                DatascriptPropertyRepository()
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
                DatascriptReferenceRepository()
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
    fun createRepositorySet(
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

        val blockRepo = createBlockRepository(backend)
        val pageRepo = createPageRepository(backend)
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

        val queryStatsRepo = if (backend == GraphBackend.SQLDELIGHT) {
            QueryStatsRepository(database, actor)
        } else null
        if (queryStatsRepo != null) collector?.setRepository(queryStatsRepo)

        // Wire performance objects only for SQLDelight + a live scope (i.e. production graphs)
        val histogramWriter = if (backend == GraphBackend.SQLDELIGHT && scope != null) {
            dev.stapler.stelekit.performance.HistogramWriter(database, scope, actor)
        } else null

        val debugFlagRepo = if (backend == GraphBackend.SQLDELIGHT) {
            dev.stapler.stelekit.performance.DebugFlagRepository(database)
        } else null

        val spanRepository = if (backend == GraphBackend.SQLDELIGHT) {
            SqlDelightSpanRepository(database)
        } else null

        val bugReportBuilder = if (histogramWriter != null && ringBuffer != null) {
            dev.stapler.stelekit.performance.BugReportBuilder(ringBuffer, histogramWriter)
        } else null

        val perfExporter = if (spanRepository != null && histogramWriter != null && fileSystem != null) {
            dev.stapler.stelekit.performance.PerfExporter(
                spanRepository = spanRepository,
                histogramWriter = histogramWriter,
                fileSystem = fileSystem,
                appVersion = appVersion,
                platform = platform,
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
            launchDrainLoop(scope, ringBuffer, actor, spanRepository, sqlBlockRepo, histogramWriter, poolWaitMetrics)
        }

        val walCallback: (suspend () -> Unit)? = sqlBlockRepo?.let { repo -> suspend { repo.walCheckpoint() } }

        return RepositorySet(
            blockRepository = blockRepo,
            pageRepository = pageRepo,
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
        )
    }

    private fun wireCacheCallbacks(actor: DatabaseWriteActor, sqlBlockRepo: SqlDelightBlockRepository?) {
        if (sqlBlockRepo == null) return
        actor.onWriteSuccess = { request ->
            when (request) {
                is DatabaseWriteActor.WriteRequest.SaveBlocks ->
                    request.blocks.forEach { sqlBlockRepo.evictBlock(it.uuid) }
                is DatabaseWriteActor.WriteRequest.DeleteBlocksForPage ->
                    sqlBlockRepo.evictHierarchyForPage(request.pageUuid)
                is DatabaseWriteActor.WriteRequest.DeleteBlocksForPages ->
                    request.pageUuids.forEach { sqlBlockRepo.evictHierarchyForPage(it) }
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
        actor: DatabaseWriteActor?,
        spanRepository: SqlDelightSpanRepository,
        sqlBlockRepo: SqlDelightBlockRepository?,
        histogramWriter: dev.stapler.stelekit.performance.HistogramWriter?,
        poolWaitMetrics: dev.stapler.stelekit.performance.PoolWaitMetrics?,
    ) {
        scope.launch {
            val sevenDaysMs = 7L * 24L * 60L * 60L * 1000L
            while (true) {
                delay(5_000)
                val drained = ringBuffer.drain()
                val drainBlock: suspend () -> Either<DomainError, Unit> = {
                    drained.forEach { span -> spanRepository.insertSpan(span) }
                    spanRepository.deleteSpansOlderThan(HistogramWriter.epochMs() - sevenDaysMs)
                    spanRepository.deleteExcessSpans(10_000)
                    Unit.right()
                }
                if (actor != null) {
                    actor.execute(DatabaseWriteActor.Priority.LOW, drainBlock)
                } else {
                    drainBlock()
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
        // SQLDelight driver must be closed
        try {
            activeDriver?.close()
        } catch (e: Exception) {
            // Driver might not be initialized or already closed
        }
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
