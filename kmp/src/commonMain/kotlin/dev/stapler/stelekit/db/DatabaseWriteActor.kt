package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.performance.AppSession
import dev.stapler.stelekit.performance.HistogramWriter
import dev.stapler.stelekit.performance.RingBufferSpanExporter
import dev.stapler.stelekit.performance.SerializedSpan
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Serializes all database writes through a single coroutine, eliminating SQLite write-lock
 * contention that causes SQLITE_BUSY errors during parallel graph loading.
 *
 * Each request carries a [CompletableDeferred] so callers can await the exact result of
 * their write and react to failures (log, retry, surface to UI) rather than silently losing data.
 *
 * ## Priority queue
 * Two channels are maintained: [highPriority] for user-initiated writes (editor saves, deletes)
 * and [lowPriority] for bulk background loads (graph import, index rebuild). The actor always
 * drains the high-priority channel before touching the low-priority one, so a user typing during
 * a 4,000-page load never waits behind bulk writes.
 *
 * ## Coalescing
 * Consecutive [WriteRequest.SaveBlocks] requests on the same priority lane are coalesced into
 * one transaction. While coalescing a low-priority batch the actor checks high-priority between
 * each item; if anything urgent arrives it flushes the batch immediately and services the urgent
 * request first. If the combined transaction fails, each original request is retried individually
 * so partial successes are preserved.
 */
@OptIn(DirectRepositoryWrite::class)
class DatabaseWriteActor(
    private val blockRepository: BlockRepository,
    private val pageRepository: PageRepository,
    private val opLogger: OperationLogger? = null,
) {
    private val logger = Logger("DatabaseWriteActor")
    /** Controls which channel a write request enters. */
    enum class Priority { HIGH, LOW }

    sealed class WriteRequest {
        abstract val deferred: CompletableDeferred<Either<DomainError, Unit>>
        abstract val priority: Priority

        class SavePage(
            val page: Page,
            override val priority: Priority = Priority.HIGH,
            override val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
        ) : WriteRequest()

        class SavePages(
            val pages: List<Page>,
            override val priority: Priority = Priority.LOW,
            override val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
        ) : WriteRequest()

        class SaveBlocks(
            val blocks: List<Block>,
            override val priority: Priority = Priority.HIGH,
            override val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
        ) : WriteRequest()

        class DeleteBlocksForPage(
            val pageUuid: String,
            override val priority: Priority = Priority.HIGH,
            override val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
        ) : WriteRequest()

        class DeleteBlocksForPages(
            val pageUuids: List<String>,
            override val priority: Priority = Priority.LOW,
            override val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
        ) : WriteRequest()

        class Execute(
            val op: suspend () -> Either<DomainError, Unit>,
            override val priority: Priority = Priority.HIGH,
            override val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
            val enqueueMs: Long = HistogramWriter.epochMs(),
        ) : WriteRequest()
    }

    /** Set after construction once the ring buffer is available. Not thread-safe — set once before use. */
    var ringBuffer: RingBufferSpanExporter? = null

    private val highPriority = Channel<WriteRequest>(capacity = Channel.UNLIMITED)
    private val lowPriority = Channel<WriteRequest>(capacity = Channel.UNLIMITED)

    private fun channelFor(priority: Priority) =
        if (priority == Priority.HIGH) highPriority else lowPriority

    // Own scope so the actor loop survives Compose scope cancellation (e.g. key(activeGraphId)
    // graph switch). Callers must call close() to stop the actor.
    private val actorScope = CoroutineScope(SupervisorJob() + PlatformDispatcher.Default)

    init {
        actorScope.launch {
            try {
                while (isActive) {
                    // Prefer high-priority: non-blocking poll first, then suspend on either.
                    val request = highPriority.tryReceive().getOrNull()
                        ?: select {
                            highPriority.onReceive { it }
                            lowPriority.onReceive { it }
                        }
                    try {
                        processRequest(request)
                    } catch (e: Exception) {
                        // Unexpected throw — complete deferred so caller doesn't hang.
                        // Guard against double-completion: processRequest may have already
                        // completed the deferred on its happy path before the exception escaped.
                        if (!request.deferred.isCompleted) {
                            request.deferred.complete(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
                        }
                    }
                }
            } catch (_: Exception) {
                // Channel closed or coroutine cancelled — exit cleanly.
            }
        }
    }

    private suspend fun processRequest(request: WriteRequest) {
        when (request) {
            is WriteRequest.SavePage ->
                request.deferred.complete(pageRepository.savePage(request.page))
            is WriteRequest.SavePages ->
                request.deferred.complete(pageRepository.savePages(request.pages))
            is WriteRequest.DeleteBlocksForPage -> {
                if (opLogger != null) {
                    try {
                        val pageBlocks = blockRepository.getBlocksForPage(request.pageUuid).first().getOrNull()
                        pageBlocks?.forEach { opLogger.logDelete(it) }
                    } catch (_: Exception) {
                        // Non-fatal: op log failure must not block the delete
                    }
                }
                request.deferred.complete(blockRepository.deleteBlocksForPage(request.pageUuid))
            }
            is WriteRequest.DeleteBlocksForPages -> {
                if (opLogger != null) {
                    try {
                        for (uuid in request.pageUuids) {
                            val pageBlocks = blockRepository.getBlocksForPage(uuid).first().getOrNull()
                            pageBlocks?.forEach { opLogger.logDelete(it) }
                        }
                    } catch (e: Exception) {
                        logger.warn("Op log pre-delete read failed (non-fatal)", e)
                    }
                }
                request.deferred.complete(blockRepository.deleteBlocksForPages(request.pageUuids))
            }
            is WriteRequest.SaveBlocks -> processSaveBlocks(request)
            is WriteRequest.Execute -> {
                val waitMs = HistogramWriter.epochMs() - request.enqueueMs
                if (waitMs > 10L) {
                    ringBuffer?.record(SerializedSpan(
                        name = "db.queue_wait",
                        startEpochMs = request.enqueueMs,
                        endEpochMs = request.enqueueMs + waitMs,
                        durationMs = waitMs,
                        attributes = mapOf(
                            "priority" to request.priority.name,
                            "session.id" to AppSession.id,
                        ),
                        statusCode = if (waitMs > 500L) "ERROR" else "OK",
                        traceId = UuidGenerator.generateV7(),
                        spanId = UuidGenerator.generateV7(),
                    ))
                }
                request.deferred.complete(request.op())
            }
        }
    }

    private suspend fun processSaveBlocks(first: WriteRequest.SaveBlocks) {
        val sourceChannel = channelFor(first.priority)
        // Drain all consecutive same-priority SaveBlocks — coalesce into one transaction.
        val batch = mutableListOf(first)
        while (true) {
            // While building a low-priority batch, yield immediately if high-priority arrives.
            if (first.priority == Priority.LOW) {
                val urgent = highPriority.tryReceive().getOrNull()
                if (urgent != null) {
                    flushBatch(batch)
                    processRequest(urgent)
                    return
                }
            }
            val next = sourceChannel.tryReceive().getOrNull() ?: break
            if (next is WriteRequest.SaveBlocks) {
                batch.add(next)
            } else {
                // A non-SaveBlocks request interrupted the run — flush then handle it.
                flushBatch(batch)
                processRequest(next)
                return
            }
        }
        flushBatch(batch)
    }

    private suspend fun flushBatch(batch: List<WriteRequest.SaveBlocks>) {
        if (batch.size == 1) {
            val blocks = batch[0].blocks
            val existingByUuid = loadExistingBlocks(blocks)
            val result = blockRepository.saveBlocks(blocks)
            if (result.isRight()) logSaveBlocks(blocks, existingByUuid)
            batch[0].deferred.complete(result)
            return
        }
        // Try one combined transaction: all blocks from all requests in one shot.
        val allBlocks = batch.flatMap { it.blocks }
        val existingByUuid = loadExistingBlocks(allBlocks)
        val batchResult = blockRepository.saveBlocks(allBlocks)
        if (batchResult.isRight()) {
            logSaveBlocks(allBlocks, existingByUuid)
            batch.forEach { it.deferred.complete(Unit.right()) }
        } else {
            logger.warn(
                "Combined batch of ${allBlocks.size} blocks failed (${batchResult.leftOrNull()?.message}), retrying ${batch.size} requests individually"
            )
            // Combined transaction failed — retry each request individually so that
            // pages with valid blocks still succeed and each caller gets accurate feedback.
            batch.forEach { req ->
                val reqExisting = loadExistingBlocks(req.blocks)
                val reqResult = blockRepository.saveBlocks(req.blocks)
                if (reqResult.isRight()) logSaveBlocks(req.blocks, reqExisting)
                req.deferred.complete(reqResult)
            }
        }
    }

    /**
     * Load existing blocks by UUID for INSERT vs UPDATE classification.
     * Returns a map of uuid -> existing Block (only for blocks that already exist).
     */
    private suspend fun loadExistingBlocks(blocks: List<Block>): Map<String, Block> {
        if (opLogger == null || blocks.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Block>()
        for (block in blocks) {
            try {
                val existing = blockRepository.getBlockByUuid(block.uuid).first().getOrNull()
                if (existing != null) result[block.uuid] = existing
            } catch (_: Exception) {
                // Non-fatal: if lookup fails we skip logging for this block
            }
        }
        return result
    }

    /**
     * Log INSERT or UPDATE operations for each block in the batch.
     * Blocks not present in [existingByUuid] are treated as inserts.
     */
    private fun logSaveBlocks(blocks: List<Block>, existingByUuid: Map<String, Block>) {
        val logger = opLogger ?: return
        for (block in blocks) {
            val existing = existingByUuid[block.uuid]
            if (existing == null) {
                logger.logInsert(block)
            } else {
                logger.logUpdate(existing, block)
            }
        }
    }

    suspend fun savePage(page: Page, priority: Priority = Priority.HIGH): Either<DomainError, Unit> {
        val req = WriteRequest.SavePage(page, priority)
        channelFor(priority).send(req)
        return req.deferred.await()
    }

    suspend fun savePages(pages: List<Page>, priority: Priority = Priority.LOW): Either<DomainError, Unit> {
        if (pages.isEmpty()) return Unit.right()
        val req = WriteRequest.SavePages(pages, priority)
        channelFor(priority).send(req)
        return req.deferred.await()
    }

    suspend fun saveBlocks(blocks: List<Block>, priority: Priority = Priority.HIGH): Either<DomainError, Unit> {
        val req = WriteRequest.SaveBlocks(blocks, priority)
        channelFor(priority).send(req)
        return req.deferred.await()
    }

    suspend fun deleteBlocksForPage(pageUuid: String, priority: Priority = Priority.HIGH): Either<DomainError, Unit> {
        val req = WriteRequest.DeleteBlocksForPage(pageUuid, priority)
        channelFor(priority).send(req)
        return req.deferred.await()
    }

    suspend fun deleteBlocksForPages(pageUuids: List<String>, priority: Priority = Priority.LOW): Either<DomainError, Unit> {
        if (pageUuids.isEmpty()) return Unit.right()
        val req = WriteRequest.DeleteBlocksForPages(pageUuids, priority)
        channelFor(priority).send(req)
        return req.deferred.await()
    }

    /**
     * Execute an arbitrary write operation through the actor's serialized channel.
     * Use this for any write that doesn't have a dedicated typed method.
     */
    suspend fun execute(priority: Priority = Priority.HIGH, op: suspend () -> Either<DomainError, Unit>): Either<DomainError, Unit> {
        val req = WriteRequest.Execute(op, priority)
        channelFor(priority).send(req)
        return req.deferred.await()
    }

    suspend fun saveBlock(block: Block): Either<DomainError, Unit> =
        execute { blockRepository.saveBlock(block) }

    suspend fun deleteBlock(blockUuid: String): Either<DomainError, Unit> =
        execute {
            if (opLogger != null) {
                try {
                    val block = blockRepository.getBlockByUuid(blockUuid).first().getOrNull()
                    block?.let { opLogger.logDelete(it) }
                } catch (_: Exception) {
                    // Non-fatal: op log failure must not block the delete
                }
            }
            blockRepository.deleteBlock(blockUuid)
        }

    /**
     * Executes [block] with all writes wrapped in a BATCH_START / BATCH_END log entry.
     * A single Ctrl+Z undoes all operations in the batch atomically.
     *
     * The [batchId] is stored in each marker's payload so [UndoManager] can
     * correlate start and end markers.
     */
    suspend fun executeBatch(batchId: String, block: suspend DatabaseWriteActor.() -> Unit) {
        // Route markers through the actor channel so they share the serialized Lamport clock
        // with the writes they bracket. Calling logBatchStart/End directly from the caller's
        // coroutine would race with actor-channel writes and corrupt seq ordering.
        execute { opLogger?.logBatchStart(batchId); Unit.right() }
        try {
            this.block()
        } finally {
            execute { opLogger?.logBatchEnd(batchId); Unit.right() }
        }
    }

    suspend fun deletePage(pageUuid: String): Either<DomainError, Unit> =
        execute { pageRepository.deletePage(pageUuid) }

    fun close() {
        highPriority.close()
        lowPriority.close()
        actorScope.cancel()
    }

    companion object {
        /**
         * Creates a [Resource]-managed [DatabaseWriteActor].
         * The actor's [close] method is guaranteed to be called when the resource is released,
         * even on [kotlinx.coroutines.CancellationException] or exceptions.
         *
         * Usage:
         * ```kotlin
         * DatabaseWriteActor.resource(blockRepo, pageRepo).use { actor ->
         *     actor.saveBlocks(blocks)
         * }
         * ```
         */
        fun resource(
            blockRepository: BlockRepository,
            pageRepository: PageRepository,
            opLogger: OperationLogger? = null,
        ): Resource<DatabaseWriteActor> = resource {
            val actor = DatabaseWriteActor(blockRepository, pageRepository, opLogger)
            onRelease { actor.close() }
            actor
        }
    }
}
