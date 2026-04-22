package dev.stapler.stelekit.repository

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.OperationLogger
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.db.UndoManager
import dev.stapler.stelekit.db.createDatabase
import dev.stapler.stelekit.performance.HistogramWriter
import dev.stapler.stelekit.performance.OtelProvider
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

    private val database: SteleDatabase by lazy {
        val driver = driverFactory.createDriver(jdbcUrl)
        activeDriver = driver
        SteleDatabase(driver)
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
                val repo = SqlDelightSearchRepository(database)
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

    fun createRepositorySet(backend: GraphBackend, scope: CoroutineScope? = null): RepositorySet {
        val blockRepo = createBlockRepository(backend)
        val pageRepo = createPageRepository(backend)
        val (actor, undoManager) = if (scope != null) {
            val sessionId = dev.stapler.stelekit.util.UuidGenerator.generateV7()
            val opLogger = if (backend == GraphBackend.SQLDELIGHT) OperationLogger(database, sessionId) else null
            val writeActor = DatabaseWriteActor(blockRepo, pageRepo, opLogger)
            val undo = if (opLogger != null) UndoManager(database, writeActor, sessionId) else null
            writeActor to undo
        } else {
            null to null
        }

        // Wire performance objects only for SQLDelight + a live scope (i.e. production graphs)
        val histogramWriter = if (backend == GraphBackend.SQLDELIGHT && scope != null) {
            dev.stapler.stelekit.performance.HistogramWriter(database, scope, actor)
        } else null

        val debugFlagRepo = if (backend == GraphBackend.SQLDELIGHT) {
            dev.stapler.stelekit.performance.DebugFlagRepository(database)
        } else null

        // Prefer the OtelProvider's ring buffer (already wired to the OTel SDK span pipeline).
        // Fall back to a standalone buffer if OTel is not initialized (e.g. tests, iOS).
        val ringBuffer = dev.stapler.stelekit.performance.OtelProvider.ringBuffer
            ?: if (histogramWriter != null) dev.stapler.stelekit.performance.RingBufferSpanExporter() else null

        val spanRepository = if (backend == GraphBackend.SQLDELIGHT) {
            SqlDelightSpanRepository(database)
        } else null

        val bugReportBuilder = if (histogramWriter != null && ringBuffer != null) {
            dev.stapler.stelekit.performance.BugReportBuilder(ringBuffer, histogramWriter)
        } else null

        // Background drain: every 5s flush in-memory ring buffer to SQLite and prune old data
        if (scope != null && ringBuffer != null && spanRepository != null) {
            scope.launch {
                val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
                while (true) {
                    delay(5_000)
                    val drained = ringBuffer.drain()
                    drained.forEach { span -> spanRepository.insertSpan(span) }
                    spanRepository.deleteSpansOlderThan(HistogramWriter.epochMs() - sevenDaysMs)
                    spanRepository.deleteExcessSpans(10_000)
                }
            }
        }

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
            bugReportBuilder = bugReportBuilder
        )
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
