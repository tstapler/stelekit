// jvmTest-reason: real SQLDelight driver (in-memory SQLite), no compose.ui.test import
package dev.stapler.stelekit.table

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.SqlDelightBlockRepository
import dev.stapler.stelekit.repository.SqlDelightPageRepository
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * A real [BlockStateManager] wired like production (write actor, invalidation and push sources)
 * over the SQLDelight repositories on in-memory SQLite, with one Table block.
 * Records every `getBlockByUuid` emission in arrival order.
 */
@OptIn(FlowPreview::class)
class TableSheetRealFeedHarness(initialContent: String) {
    data class Emission(val index: Int, val content: String, val dbVersion: Long)

    val pageUuid = PageUuid("table-feed-page")
    val blockUuid = BlockUuid("table-feed-block")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
    private val database = SteleDatabase(driver)
    val blockRepo = SqlDelightBlockRepository(database, driver)
    val pageRepo = SqlDelightPageRepository(database)
    val actor = DatabaseWriteActor(blockRepo, pageRepo, scope = scope)
    val manager = BlockStateManager(
        blockRepository = blockRepo,
        graphLoader = GraphLoader(NoopFileSystem, pageRepo, blockRepo),
        scope = scope,
        writeActor = actor,
        invalidationSource = actor.blockInvalidations,
        pushSource = actor.blocksPushed,
    )

    private val lock = Any()
    private val ticks = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    private val _emissions = mutableListOf<Emission>()
    val emissions: List<Emission> get() = synchronized(lock) { _emissions.toList() }
    private val _events = mutableListOf<String>()

    /** Emissions and [event] markers in one arrival-ordered log, for interleaving analysis. */
    val orderedLog: List<String> get() = synchronized(lock) { _events.toList() }

    fun event(text: String) { synchronized(lock) { _events += text } }

    suspend fun start(initial: String) {
        val now = Clock.System.now()
        pageRepo.savePage(Page(uuid = pageUuid, name = "P", createdAt = now, updatedAt = now))
        blockRepo.saveBlock(
            Block(uuid = blockUuid, pageUuid = pageUuid, content = initial, position = "a0",
                blockType = BlockType.Table, version = 1, createdAt = now, updatedAt = now)
        )
        manager.observePage(pageUuid)
        manager.blocks.first { it.containsKey(pageUuid.value) }
        scope.launch {
            blockRepo.getBlockByUuid(blockUuid).collect { either ->
                val b = either.getOrNull() ?: return@collect
                synchronized(lock) {
                    val e = Emission(_emissions.size + 1, b.content, b.version)
                    _emissions += e
                    ticks.tryEmit(Unit)
                    _events += "EMISSION #${e.index} content=${labelOf(b.content)} (dbVersion=${b.version})"
                }
            }
        }
        settle()
    }

    var labelOf: (String?) -> String = { it.orEmpty() }

    fun managerContent(): String? =
        manager.blocks.value[pageUuid.value]?.firstOrNull { it.uuid == blockUuid }?.content

    /** Waits until no new emission has arrived for [quietMs] (bounded by [maxMs]). */
    suspend fun settle(quietMs: Long = 400, maxMs: Long = 6000) {
        // Quiet-period wait on the emission ticks (debounce), not a polling delay loop.
        withTimeoutOrNull(maxMs) {
            ticks.onStart { emit(Unit) }.debounce(quietMs).first()
        }
    }

    fun close() {
        actor.close()
        scope.cancel()
    }

    private object NoopFileSystem : FileSystem {
        override fun getDefaultGraphPath(): String = "/tmp"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String): Boolean = true
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String): Boolean = false
        override fun directoryExists(path: String): Boolean = true
        override fun createDirectory(path: String): Boolean = true
        override fun deleteFile(path: String): Boolean = true
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }
}
