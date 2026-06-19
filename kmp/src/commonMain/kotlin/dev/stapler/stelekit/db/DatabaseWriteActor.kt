package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import arrow.fx.coroutines.Resource
import arrow.fx.coroutines.resource
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.performance.AppSession
import dev.stapler.stelekit.performance.HistogramWriter
import dev.stapler.stelekit.performance.RingBufferSpanExporter
import dev.stapler.stelekit.performance.SerializedSpan
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import dev.stapler.stelekit.util.UuidGenerator
import arrow.atomic.AtomicInt
import arrow.atomic.update
import arrow.atomic.value
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    scope: CoroutineScope? = null,
) {
    private val logger = Logger("DatabaseWriteActor")

    /** Called after a successful write, before the caller's deferred is completed. */
    @Volatile var onWriteSuccess: (suspend (WriteRequest) -> Unit)? = null

    private val _blockInvalidations = MutableSharedFlow<Set<PageUuid>>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val blockInvalidations: SharedFlow<Set<PageUuid>> = _blockInvalidations.asSharedFlow()

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
            val enqueueMs: Long = HistogramWriter.epochMs(),
        ) : WriteRequest()

        class SaveBlocksDiff(
            val toInsert: List<Block>,
            val toUpdate: List<Block>,
            override val priority: Priority = Priority.HIGH,
            override val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
        ) : WriteRequest()

        class DeleteBlocksForPage(
            val pageUuid: PageUuid,
            override val priority: Priority = Priority.HIGH,
            override val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
        ) : WriteRequest()

        class DeleteBlocksForPages(
            val pageUuids: List<PageUuid>,
            override val priority: Priority = Priority.LOW,
            override val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
        ) : WriteRequest()

        class Execute(
            val op: suspend () -> Either<DomainError, Unit>,
            override val priority: Priority = Priority.HIGH,
            override val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
            val enqueueMs: Long = HistogramWriter.epochMs(),
        ) : WriteRequest()

        // Typed hot-path subclasses — carry pageUuid so blockInvalidations emits a targeted set
        // instead of the wildcard sentinel, preventing N re-queries across all observed pages.

        data class WriteBlockContent(
            val blockUuid: BlockUuid,
            val pageUuid: PageUuid,
            val content: String,
            override val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
        ) : WriteRequest() {
            override val priority: Priority = Priority.HIGH
        }

        data class WriteBlock(
            val block: Block,
            override val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
        ) : WriteRequest() {
            override val priority: Priority = Priority.HIGH
        }

        data class DeleteBlock(
            val blockUuid: BlockUuid,
            val pageUuid: PageUuid,
            override val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
        ) : WriteRequest() {
            override val priority: Priority = Priority.HIGH
        }

        data class MergeBlocks(
            val blockUuid: BlockUuid,
            val pageUuid: PageUuid,
            val nextBlockUuid: BlockUuid,
            val separator: String,
            override val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
        ) : WriteRequest() {
            override val priority: Priority = Priority.HIGH
        }

        data class DeleteBlockStructural(
            val blockUuid: BlockUuid,
            val pageUuid: PageUuid,
            override val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
        ) : WriteRequest() {
            override val priority: Priority = Priority.HIGH
        }

        data class WriteBlockProperties(
            val blockUuid: BlockUuid,
            val pageUuid: PageUuid,
            val properties: Map<String, String>,
            override val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
        ) : WriteRequest() {
            override val priority: Priority = Priority.HIGH
        }
    }

    /** Set after construction once the ring buffer is available. Written once before first channel send. */
    @Volatile var ringBuffer: RingBufferSpanExporter? = null

    /** Set after construction once the histogram writer is available. Written once before first use. */
    @Volatile var histogramWriter: dev.stapler.stelekit.performance.HistogramWriter? = null

    private val highPriority = Channel<WriteRequest>(capacity = Channel.UNLIMITED)
    private val lowPriority = Channel<WriteRequest>(capacity = Channel.UNLIMITED)

    private fun channelFor(priority: Priority) =
        if (priority == Priority.HIGH) highPriority else lowPriority

    // True while the actor coroutine is executing a processRequest call.
    // Written exclusively by the single actor coroutine; read by callers.
    // @Volatile gives the required single-writer/multi-reader visibility without atomics.
    @Volatile private var _isActorProcessing: Boolean = false

    /**
     * Counts callers that have successfully sent a request but whose [CompletableDeferred.await]
     * has not yet returned. Incremented just before [Channel.send] and decremented in the
     * finally-block that wraps [CompletableDeferred.await], so the counter stays balanced even
     * when [send] throws (e.g. channel closed) or when the calling coroutine is cancelled while
     * suspended in [await].
     *
     * Uses [AtomicInt] (Arrow KMP-safe typealias for [java.util.concurrent.atomic.AtomicInteger]
     * on JVM/Android, native atomics on iOS/WASM) to avoid the lost-update race that would occur
     * with a plain @Volatile Int incremented by concurrent callers.
     */
    private val _activeOps = AtomicInt(0)

    /**
     * True while any write is queued in the channels or currently being processed.
     *
     * Thread-safe: [Channel.isEmpty] is safe to call from any thread/coroutine;
     * [_isActorProcessing] is written only by the actor coroutine (@Volatile suffices
     * for single-writer/multi-reader visibility); [_activeOps] is an atomic counter
     * incremented by every caller before [send] and decremented after [await] returns.
     * Together they cover the full lifecycle of a write request from caller to result.
     *
     * Used by conflict detection: an external file change arriving while a split/merge is
     * in-flight must trigger the conflict dialog rather than silently overwriting local data.
     */
    val hasPendingWrites: Boolean
        get() = !highPriority.isEmpty || !lowPriority.isEmpty || _isActorProcessing || _activeOps.value != 0

    // Own scope so the actor loop survives Compose scope cancellation (e.g. key(activeGraphId)
    // graph switch). Callers must call close() to stop the actor.
    // When a scope is injected (e.g. from tests with UnconfinedTestDispatcher), use it directly
    // so that virtual-time control works correctly in tests.
    private val injectedScope: Boolean = scope != null
    private val actorScope = scope ?: CoroutineScope(SupervisorJob() + PlatformDispatcher.Default)

    init {
        actorScope.launch {
            try {
                while (isActive) {
                    // Prefer high-priority: non-blocking poll first, then suspend on either.
                    val request = highPriority.tryReceive().getOrNull()
                        ?.also { _isActorProcessing = true }
                        ?: select {
                            highPriority.onReceive { _isActorProcessing = true; it }
                            lowPriority.onReceive { _isActorProcessing = true; it }
                        }
                    try {
                        processRequest(request)
                    } catch (e: CancellationException) {
                        // Complete the deferred so the caller doesn't hang, then rethrow so the
                        // actor loop exits cleanly via the outer catch instead of converting
                        // cancellation into a spurious WriteFailed result.
                        if (!request.deferred.isCompleted) {
                            request.deferred.complete(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
                        }
                        throw e
                    } catch (e: Exception) {
                        // Unexpected throw — complete deferred so caller doesn't hang.
                        // Guard against double-completion: processRequest may have already
                        // completed the deferred on its happy path before the exception escaped.
                        if (!request.deferred.isCompleted) {
                            request.deferred.complete(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
                        }
                    } finally {
                        _isActorProcessing = false
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Channel closed or coroutine cancelled — exit cleanly.
            }
        }
    }

    private suspend fun processRequest(request: WriteRequest) {
        when (request) {
            is WriteRequest.SavePage -> {
                val result = pageRepository.savePage(request.page)
                if (result.isRight()) onWriteSuccess?.invoke(request)
                request.deferred.complete(result)
            }
            is WriteRequest.SavePages -> {
                val result = pageRepository.savePages(request.pages)
                if (result.isRight()) onWriteSuccess?.invoke(request)
                request.deferred.complete(result)
            }
            is WriteRequest.DeleteBlocksForPage -> processDeleteBlocksForPage(request)
            is WriteRequest.DeleteBlocksForPages -> processDeleteBlocksForPages(request)
            is WriteRequest.SaveBlocks -> processSaveBlocks(request)
            is WriteRequest.SaveBlocksDiff -> processSaveBlocksDiff(request)
            is WriteRequest.Execute -> processExecute(request)
            is WriteRequest.WriteBlockContent -> processWriteBlockContent(request)
            is WriteRequest.WriteBlock -> processWriteBlock(request)
            is WriteRequest.DeleteBlock -> processDeleteBlock(request)
            is WriteRequest.MergeBlocks -> processMergeBlocks(request)
            is WriteRequest.DeleteBlockStructural -> processDeleteBlockStructural(request)
            is WriteRequest.WriteBlockProperties -> processWriteBlockProperties(request)
        }
    }

    private suspend fun processDeleteBlocksForPage(request: WriteRequest.DeleteBlocksForPage) {
        if (opLogger != null) {
            try {
                val pageBlocks = blockRepository.getBlocksForPage(request.pageUuid).first().getOrNull()
                pageBlocks?.forEach { opLogger.logDelete(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Non-fatal: op log failure must not block the delete
            }
        }
        val result = blockRepository.deleteBlocksForPage(request.pageUuid)
        if (result.isRight()) {
            onWriteSuccess?.invoke(request)
            _blockInvalidations.tryEmit(setOf(request.pageUuid))
        }
        request.deferred.complete(result)
    }

    private suspend fun processExecute(request: WriteRequest.Execute) {
        val waitMs = HistogramWriter.epochMs() - request.enqueueMs
        histogramWriter?.record("db.write_queue_depth", _activeOps.value.toLong())
        if (waitMs > 10L) {
            recordQueueWaitSpan(request, waitMs)
        }
        // Wildcard emitted BEFORE deferred.complete so subscribers receive the signal before
        // the caller's await() returns. processExecute has no onWriteSuccess call (unlike typed
        // arms) — the emit must be unconditional here.
        _blockInvalidations.tryEmit(setOf(WILDCARD_PAGE_UUID))
        request.deferred.complete(request.op())
    }

    private suspend fun processWriteBlockContent(request: WriteRequest.WriteBlockContent) {
        val result = blockRepository.updateBlockContentOnly(request.blockUuid, request.content)
        if (result.isRight()) {
            onWriteSuccess?.invoke(request)
            _blockInvalidations.tryEmit(setOf(request.pageUuid))
        }
        request.deferred.complete(result)
    }

    private suspend fun processWriteBlock(request: WriteRequest.WriteBlock) {
        val result = blockRepository.saveBlock(request.block)
        if (result.isRight()) {
            onWriteSuccess?.invoke(request)
            _blockInvalidations.tryEmit(setOf(request.block.pageUuid))
        }
        request.deferred.complete(result)
    }

    private suspend fun processDeleteBlock(request: WriteRequest.DeleteBlock) {
        if (opLogger != null) {
            try {
                val block = blockRepository.getBlockByUuid(request.blockUuid).first().getOrNull()
                block?.let { opLogger.logDelete(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Non-fatal: op log failure must not block the delete
            }
        }
        val result = blockRepository.deleteBlock(request.blockUuid)
        if (result.isRight()) {
            onWriteSuccess?.invoke(request)
            _blockInvalidations.tryEmit(setOf(request.pageUuid))
        }
        request.deferred.complete(result)
    }

    private suspend fun processMergeBlocks(request: WriteRequest.MergeBlocks) {
        val result = blockRepository.mergeBlocks(request.blockUuid, request.nextBlockUuid, request.separator)
        if (result.isRight()) {
            onWriteSuccess?.invoke(request)
            _blockInvalidations.tryEmit(setOf(request.pageUuid))
        }
        request.deferred.complete(result)
    }

    private suspend fun processDeleteBlockStructural(request: WriteRequest.DeleteBlockStructural) {
        val result = blockRepository.deleteBlock(request.blockUuid)
        if (result.isRight()) {
            onWriteSuccess?.invoke(request)
            _blockInvalidations.tryEmit(setOf(request.pageUuid))
        }
        request.deferred.complete(result)
    }

    private suspend fun processWriteBlockProperties(request: WriteRequest.WriteBlockProperties) {
        val result = blockRepository.updateBlockPropertiesOnly(request.blockUuid, request.properties)
        if (result.isRight()) {
            onWriteSuccess?.invoke(request)
            _blockInvalidations.tryEmit(setOf(request.pageUuid))
        }
        request.deferred.complete(result)
    }

    private fun recordQueueWaitSpan(request: WriteRequest.Execute, waitMs: Long) {
        ringBuffer?.record(SerializedSpan(
            name = "db.queue_wait",
            startEpochMs = request.enqueueMs,
            endEpochMs = request.enqueueMs + waitMs,
            durationMs = waitMs,
            attributes = mapOf(
                "priority" to request.priority.name,
                "queue.depth" to _activeOps.value.toString(),
            ) + AppSession.autoAttributes(),
            statusCode = if (waitMs > 500L) "ERROR" else "OK",
            traceId = UuidGenerator.generateV7(),
            spanId = UuidGenerator.generateV7(),
        ))
    }

    private suspend fun processDeleteBlocksForPages(request: WriteRequest.DeleteBlocksForPages) {
        if (opLogger != null) {
            // Chunk page UUIDs so we fetch and delete together per chunk rather than
            // materializing all blocks across every page before the first delete runs.
            // Bounds peak memory to PAGE_DELETE_CHUNK × (blocks per page).
            for (chunk in request.pageUuids.chunked(PAGE_DELETE_CHUNK)) {
                logDeletesForChunk(chunk)
                val chunkResult = blockRepository.deleteBlocksForPages(chunk)
                if (chunkResult.isLeft()) {
                    request.deferred.complete(chunkResult)
                    return
                }
            }
            onWriteSuccess?.invoke(request)
            _blockInvalidations.tryEmit(request.pageUuids.toSet())
            request.deferred.complete(Unit.right())
        } else {
            val result = blockRepository.deleteBlocksForPages(request.pageUuids)
            if (result.isRight()) {
                onWriteSuccess?.invoke(request)
                _blockInvalidations.tryEmit(request.pageUuids.toSet())
            }
            request.deferred.complete(result)
        }
    }

    /** Log deletes for each UUID in [chunk] via the op-logger (non-fatal). */
    private suspend fun logDeletesForChunk(chunk: List<PageUuid>) {
        try {
            for (uuid in chunk) {
                val pageBlocks = blockRepository.getBlocksForPage(uuid).first().getOrNull()
                pageBlocks?.forEach { opLogger?.logDelete(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Op log pre-delete read failed (non-fatal)", e)
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

    private fun recordSaveBlocksQueueWait(batch: List<WriteRequest.SaveBlocks>, waitMs: Long) {
        ringBuffer?.record(SerializedSpan(
            name = "db.queue_wait",
            startEpochMs = batch[0].enqueueMs,
            endEpochMs = batch[0].enqueueMs + waitMs,
            durationMs = waitMs,
            attributes = mapOf(
                "priority" to batch[0].priority.name,
                "request.type" to "SaveBlocks",
                "batch.size" to batch.size.toString(),
                "queue.depth" to _activeOps.value.toString(),
            ) + AppSession.autoAttributes(),
            statusCode = if (waitMs > 500L) "ERROR" else "OK",
            traceId = UuidGenerator.generateV7(),
            spanId = UuidGenerator.generateV7(),
        ))
    }

    private suspend fun flushBatch(batch: List<WriteRequest.SaveBlocks>) {
        val batchEnqueueMs = batch.minOf { it.enqueueMs }
        val waitMs = HistogramWriter.epochMs() - batchEnqueueMs
        if (waitMs > 10L) recordSaveBlocksQueueWait(batch, waitMs)
        if (batch.size == 1) {
            val blocks = batch[0].blocks
            val existingByUuid = loadExistingBlocks(blocks)
            val result = blockRepository.saveBlocks(blocks)
            if (result.isRight()) {
                logSaveBlocks(blocks, existingByUuid)
                onWriteSuccess?.invoke(batch[0])
                _blockInvalidations.tryEmit(batch[0].blocks.mapTo(mutableSetOf()) { it.pageUuid })
            }
            batch[0].deferred.complete(result)
            return
        }
        // Try one combined transaction: all blocks from all requests in one shot.
        val allBlocks = batch.flatMap { it.blocks }
        val existingByUuid = loadExistingBlocks(allBlocks)
        val batchResult = blockRepository.saveBlocks(allBlocks)
        if (batchResult.isRight()) {
            logSaveBlocks(allBlocks, existingByUuid)
            _blockInvalidations.tryEmit(allBlocks.mapTo(mutableSetOf()) { it.pageUuid })
            batch.forEach {
                onWriteSuccess?.invoke(it)
                it.deferred.complete(Unit.right())
            }
        } else {
            logger.warn(
                "Combined batch of ${allBlocks.size} blocks failed (${batchResult.leftOrNull()?.message}), retrying ${batch.size} requests individually"
            )
            // Combined transaction failed — retry each request individually so that
            // pages with valid blocks still succeed and each caller gets accurate feedback.
            // Reuse the already-fetched existingByUuid map — no additional DB round-trip needed.
            batch.forEach { req ->
                val reqResult = blockRepository.saveBlocks(req.blocks)
                if (reqResult.isRight()) {
                    logSaveBlocks(req.blocks, existingByUuid)
                    onWriteSuccess?.invoke(req)
                    _blockInvalidations.tryEmit(req.blocks.mapTo(mutableSetOf()) { it.pageUuid })
                }
                req.deferred.complete(reqResult)
            }
        }
    }

    private suspend fun processSaveBlocksDiff(first: WriteRequest.SaveBlocksDiff) {
        val sourceChannel = channelFor(first.priority)
        val batch = mutableListOf(first)
        while (true) {
            if (first.priority == Priority.LOW) {
                val urgent = highPriority.tryReceive().getOrNull()
                if (urgent != null) {
                    flushDiffBatch(batch)
                    processRequest(urgent)
                    return
                }
            }
            val next = sourceChannel.tryReceive().getOrNull() ?: break
            if (next is WriteRequest.SaveBlocksDiff) {
                batch.add(next)
            } else {
                flushDiffBatch(batch)
                processRequest(next)
                return
            }
        }
        flushDiffBatch(batch)
    }

    private suspend fun flushDiffBatch(batch: List<WriteRequest.SaveBlocksDiff>) {
        val allInserts = batch.flatMap { it.toInsert }
        val allUpdates = batch.flatMap { it.toUpdate }
        val existingByUuid = loadExistingBlocks(allInserts + allUpdates)
        val batchResult = blockRepository.saveBlocksDiff(allInserts, allUpdates)
        if (batchResult.isRight()) {
            logSaveBlocks(allInserts + allUpdates, existingByUuid)
            _blockInvalidations.tryEmit((allInserts + allUpdates).mapTo(mutableSetOf()) { it.pageUuid })
            batch.forEach {
                onWriteSuccess?.invoke(it)
                it.deferred.complete(Unit.right())
            }
        } else if (batch.size > 1) {
            logger.warn(
                "Combined diff batch failed (${batchResult.leftOrNull()?.message}), retrying ${batch.size} requests individually"
            )
            batch.forEach { req ->
                val reqExisting = loadExistingBlocks(req.toInsert + req.toUpdate)
                val reqResult = blockRepository.saveBlocksDiff(req.toInsert, req.toUpdate)
                if (reqResult.isRight()) {
                    logSaveBlocks(req.toInsert + req.toUpdate, reqExisting)
                    onWriteSuccess?.invoke(req)
                    _blockInvalidations.tryEmit((req.toInsert + req.toUpdate).mapTo(mutableSetOf()) { it.pageUuid })
                }
                req.deferred.complete(reqResult)
            }
        } else {
            batch[0].deferred.complete(batchResult)
        }
    }

    /**
     * Batch-fetch existing blocks by UUID for INSERT vs UPDATE classification.
     * Uses a single [BlockRepository.getBlocksByUuids] round-trip (chunked internally to
     * respect SQLite's 999-variable limit) instead of N individual lookups.
     *
     * Always runs regardless of [opLogger] — the batch fetch is a performance fix, not
     * just a logging aid. The [opLogger] guard is applied only inside [logSaveBlocks].
     */
    private suspend fun loadExistingBlocks(blocks: List<Block>): Map<BlockUuid, Block> {
        if (blocks.isEmpty()) return emptyMap()
        return try {
            blockRepository.getBlocksByUuids(blocks.map { it.uuid })
                .getOrNull().orEmpty()
                .associateBy { it.uuid }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Log INSERT or UPDATE operations for each block in the batch.
     * Blocks not present in [existingByUuid] are treated as inserts.
     */
    private suspend fun logSaveBlocks(blocks: List<Block>, existingByUuid: Map<BlockUuid, Block>) {
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
        return sendAndAwait(req)
    }

    suspend fun savePages(pages: List<Page>, priority: Priority = Priority.LOW): Either<DomainError, Unit> {
        if (pages.isEmpty()) return Unit.right()
        val req = WriteRequest.SavePages(pages, priority)
        return sendAndAwait(req)
    }

    suspend fun saveBlocks(blocks: List<Block>, priority: Priority = Priority.HIGH): Either<DomainError, Unit> {
        val req = WriteRequest.SaveBlocks(blocks, priority)
        return sendAndAwait(req)
    }

    suspend fun saveBlocksDiff(
        toInsert: List<Block>,
        toUpdate: List<Block>,
        priority: Priority = Priority.HIGH,
    ): Either<DomainError, Unit> {
        if (toInsert.isEmpty() && toUpdate.isEmpty()) return Unit.right()
        val req = WriteRequest.SaveBlocksDiff(toInsert, toUpdate, priority)
        channelFor(priority).send(req)
        return req.deferred.await()
    }

    suspend fun deleteBlocksForPage(pageUuid: PageUuid, priority: Priority = Priority.HIGH): Either<DomainError, Unit> {
        val req = WriteRequest.DeleteBlocksForPage(pageUuid, priority)
        return sendAndAwait(req)
    }

    suspend fun deleteBlocksForPages(pageUuids: List<PageUuid>, priority: Priority = Priority.LOW): Either<DomainError, Unit> {
        if (pageUuids.isEmpty()) return Unit.right()
        val req = WriteRequest.DeleteBlocksForPages(pageUuids, priority)
        return sendAndAwait(req)
    }

    /**
     * Execute an arbitrary write operation through the actor's serialized channel.
     * Use this for any write that doesn't have a dedicated typed method.
     */
    suspend fun execute(priority: Priority = Priority.HIGH, op: suspend () -> Either<DomainError, Unit>): Either<DomainError, Unit> {
        val req = WriteRequest.Execute(op, priority)
        return sendAndAwait(req)
    }

    /**
     * Increments [_activeOps], sends [req] to its priority channel, then awaits the result.
     *
     * The counter is decremented in all exit paths:
     * - If [Channel.send] throws (channel closed / coroutine cancelled in send), the
     *   increment is undone immediately and the exception propagates.
     * - Once [send] returns, a finally-block around [CompletableDeferred.await] guarantees
     *   the decrement regardless of whether [await] completes normally, throws, or is
     *   cancelled — so [_activeOps] can never leak above zero.
     */
    private suspend fun sendAndAwait(req: WriteRequest): Either<DomainError, Unit> {
        _activeOps.update { it + 1 }
        try {
            channelFor(req.priority).send(req)
        } catch (e: Exception) {
            _activeOps.update { it - 1 }
            throw e
        }
        try {
            return req.deferred.await()
        } finally {
            _activeOps.update { it - 1 }
        }
    }

    /**
     * Save a full block record. Routes through [WriteRequest.WriteBlock] for targeted
     * page-UUID invalidation instead of the wildcard sentinel.
     */
    suspend fun saveBlock(block: Block): Either<DomainError, Unit> {
        val req = WriteRequest.WriteBlock(block)
        return sendAndAwait(req)
    }

    /**
     * Update only block content. Routes through [WriteRequest.WriteBlockContent] for targeted
     * page-UUID invalidation. The caller must supply [pageUuid] — BlockStateManager always
     * has it in scope (Option A from the plan).
     */
    suspend fun updateBlockContentOnly(blockUuid: BlockUuid, content: String, pageUuid: PageUuid): Either<DomainError, Unit> {
        val req = WriteRequest.WriteBlockContent(blockUuid, pageUuid, content)
        return sendAndAwait(req)
    }

    /**
     * Update only block properties. Routes through [WriteRequest.WriteBlockProperties] for
     * targeted page-UUID invalidation.
     */
    suspend fun updateBlockPropertiesOnly(blockUuid: BlockUuid, properties: Map<String, String>, pageUuid: PageUuid): Either<DomainError, Unit> {
        val req = WriteRequest.WriteBlockProperties(blockUuid, pageUuid, properties)
        return sendAndAwait(req)
    }

    /**
     * Delete a block (with op-logger support). Routes through [WriteRequest.DeleteBlock] for
     * targeted page-UUID invalidation.
     */
    suspend fun deleteBlock(blockUuid: BlockUuid, pageUuid: PageUuid): Either<DomainError, Unit> {
        val req = WriteRequest.DeleteBlock(blockUuid, pageUuid)
        return sendAndAwait(req)
    }

    /**
     * Splits [blockUuid] at [cursorPosition] through the actor's serialized queue.
     * Guaranteed to execute after any pending [updateBlockContentOnly] for the same block.
     *
     * Returns the newly created [Block] on success, or a [DomainError] on failure.
     *
     * splitBlock keeps using Execute{} because it returns Either<DomainError, Block> (not Unit),
     * which cannot fit the WriteRequest.deferred: CompletableDeferred<Either<DomainError, Unit>>
     * abstraction without a more invasive refactor. The wildcard invalidation is acceptable here
     * since splitBlock fires only on Enter keypress (low frequency). Structural ops via execute{}
     * (indent, outdent, move, split) all take this path.
     */
    suspend fun splitBlock(
        blockUuid: BlockUuid,
        cursorPosition: Int,
        newBlockUuid: BlockUuid?,
    ): Either<DomainError, Block> {
        var newBlock: Block? = null
        val opResult = execute {
            blockRepository.splitBlock(blockUuid, cursorPosition, newBlockUuid)
                .onRight { newBlock = it }
                .map { }
        }
        return opResult.fold(
            ifLeft = { it.left() },
            ifRight = {
                newBlock?.right()
                    ?: DomainError.DatabaseError.WriteFailed(
                        "splitBlock returned no block for $blockUuid"
                    ).left()
            }
        )
    }

    /**
     * Merges [nextBlockUuid] into [blockUuid] through the actor's serialized queue.
     * Guaranteed to execute after any pending [updateBlockContentOnly] for either block.
     * Routes through [WriteRequest.MergeBlocks] for targeted page-UUID invalidation.
     */
    suspend fun mergeBlocks(
        blockUuid: BlockUuid,
        nextBlockUuid: BlockUuid,
        separator: String,
        pageUuid: PageUuid,
    ): Either<DomainError, Unit> {
        val req = WriteRequest.MergeBlocks(blockUuid, pageUuid, nextBlockUuid, separator)
        return sendAndAwait(req)
    }

    /**
     * Deletes [blockUuid] through the actor's serialized queue.
     * Distinct from [deleteBlock] (which logs to op-logger); this variant is for
     * structural deletions triggered by backspace/merge where the block being removed
     * has no content to log. Routes through [WriteRequest.DeleteBlockStructural] for
     * targeted page-UUID invalidation.
     */
    suspend fun deleteBlockStructural(blockUuid: BlockUuid, pageUuid: PageUuid): Either<DomainError, Unit> {
        val req = WriteRequest.DeleteBlockStructural(blockUuid, pageUuid)
        return sendAndAwait(req)
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

    suspend fun deletePage(pageUuid: PageUuid): Either<DomainError, Unit> =
        execute { pageRepository.deletePage(pageUuid) }

    fun close() {
        highPriority.close()
        lowPriority.close()
        // Only cancel the scope if we created it; injected scopes (e.g. test schedulers)
        // are owned by the caller.
        if (!injectedScope) actorScope.cancel()
    }

    companion object {
        /**
         * Page UUIDs processed per iteration in [DeleteBlocksForPages] when the op-logger is
         * active. Bounds peak memory to PAGE_DELETE_CHUNK × (blocks per page) instead of
         * materializing all blocks across every page before the first delete runs.
         */
        private const val PAGE_DELETE_CHUNK = 25

        /**
         * Sentinel page UUID emitted by [blockInvalidations] when the write came from a generic
         * [Execute] call where the exact affected page UUID is unknown. Subscribers should treat
         * this as a signal to re-query all observed pages.
         */
        val WILDCARD_PAGE_UUID = PageUuid("*")

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
