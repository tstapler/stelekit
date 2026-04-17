package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Serializes all database writes through a single coroutine, eliminating SQLite write-lock
 * contention that causes SQLITE_BUSY errors during parallel graph loading.
 *
 * Each request carries a [CompletableDeferred] so callers can await the exact result of
 * their write and react to failures (log, retry, surface to UI) rather than silently losing data.
 *
 * Consecutive [WriteRequest.SaveBlocks] requests are coalesced into one transaction for
 * efficiency. If the combined transaction fails, each original request is retried individually
 * so partial successes are preserved and each caller gets accurate per-request feedback.
 */
@OptIn(DirectRepositoryWrite::class)
class DatabaseWriteActor(
    private val blockRepository: BlockRepository,
    private val pageRepository: PageRepository,
    scope: CoroutineScope,
    private val opLogger: OperationLogger? = null,
) {
    sealed class WriteRequest {
        abstract val deferred: CompletableDeferred<Result<Unit>>

        class SavePage(
            val page: Page,
            override val deferred: CompletableDeferred<Result<Unit>> = CompletableDeferred(),
        ) : WriteRequest()

        class SaveBlocks(
            val blocks: List<Block>,
            override val deferred: CompletableDeferred<Result<Unit>> = CompletableDeferred(),
        ) : WriteRequest()

        class DeleteBlocksForPage(
            val pageUuid: String,
            override val deferred: CompletableDeferred<Result<Unit>> = CompletableDeferred(),
        ) : WriteRequest()

        class Execute(
            val op: suspend () -> Result<Unit>,
            override val deferred: CompletableDeferred<Result<Unit>> = CompletableDeferred(),
        ) : WriteRequest()
    }

    private val channel = Channel<WriteRequest>(capacity = Channel.UNLIMITED)

    init {
        scope.launch {
            for (request in channel) {
                processRequest(request)
            }
        }
    }

    private suspend fun processRequest(request: WriteRequest) {
        when (request) {
            is WriteRequest.SavePage ->
                request.deferred.complete(pageRepository.savePage(request.page))
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
            is WriteRequest.SaveBlocks -> processSaveBlocks(request)
            is WriteRequest.Execute ->
                request.deferred.complete(request.op())
        }
    }

    private suspend fun processSaveBlocks(first: WriteRequest.SaveBlocks) {
        // Drain all consecutive SaveBlocks from the channel — coalesce into one transaction.
        val batch = mutableListOf(first)
        var next = channel.tryReceive()
        while (next.isSuccess) {
            val value = next.getOrNull()!!
            if (value is WriteRequest.SaveBlocks) {
                batch.add(value)
                next = channel.tryReceive()
            } else {
                // A non-SaveBlocks request interrupted the run. Flush the batch first,
                // then handle the interrupting request.
                flushBatch(batch)
                processRequest(value)
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
            if (result.isSuccess) logSaveBlocks(blocks, existingByUuid)
            batch[0].deferred.complete(result)
            return
        }
        // Try one combined transaction: all blocks from all requests in one shot.
        val allBlocks = batch.flatMap { it.blocks }
        val existingByUuid = loadExistingBlocks(allBlocks)
        val batchResult = blockRepository.saveBlocks(allBlocks)
        if (batchResult.isSuccess) {
            logSaveBlocks(allBlocks, existingByUuid)
            batch.forEach { it.deferred.complete(Result.success(Unit)) }
        } else {
            // Combined transaction failed — retry each request individually so that
            // pages with valid blocks still succeed and each caller gets accurate feedback.
            batch.forEach { req ->
                val reqExisting = loadExistingBlocks(req.blocks)
                val reqResult = blockRepository.saveBlocks(req.blocks)
                if (reqResult.isSuccess) logSaveBlocks(req.blocks, reqExisting)
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

    suspend fun savePage(page: Page): Result<Unit> {
        val req = WriteRequest.SavePage(page)
        channel.send(req)
        return req.deferred.await()
    }

    suspend fun saveBlocks(blocks: List<Block>): Result<Unit> {
        val req = WriteRequest.SaveBlocks(blocks)
        channel.send(req)
        return req.deferred.await()
    }

    suspend fun deleteBlocksForPage(pageUuid: String): Result<Unit> {
        val req = WriteRequest.DeleteBlocksForPage(pageUuid)
        channel.send(req)
        return req.deferred.await()
    }

    /**
     * Execute an arbitrary write operation through the actor's serialized channel.
     * Use this for any write that doesn't have a dedicated typed method.
     */
    suspend fun execute(op: suspend () -> Result<Unit>): Result<Unit> {
        val req = WriteRequest.Execute(op)
        channel.send(req)
        return req.deferred.await()
    }

    suspend fun saveBlock(block: Block): Result<Unit> =
        execute { blockRepository.saveBlock(block) }

    suspend fun deleteBlock(blockUuid: String): Result<Unit> =
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
        opLogger?.logBatchStart(batchId)
        try {
            this.block()
        } finally {
            opLogger?.logBatchEnd(batchId)
        }
    }

    suspend fun deletePage(pageUuid: String): Result<Unit> =
        execute { pageRepository.deletePage(pageUuid) }

    fun close() { channel.close() }
}
