package dev.stapler.stelekit.db

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.util.UuidGenerator
import kotlin.time.Clock
import kotlin.time.Clock.System
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

/**
 * Records all block mutations to the append-only `operations` table.
 *
 * Manages a per-session Lamport clock: every logged operation gets a monotonically
 * increasing `seq` value. The clock is persisted in `logical_clock` so it survives
 * app restarts.
 *
 * All calls are expected to come from [DatabaseWriteActor]'s serialized coroutine,
 * so there is no additional synchronization here.
 */
class OperationLogger(
    private val db: SteleDatabase,
    val sessionId: String,
    private val clock: Clock = System,
) {
    private val logger = Logger("OperationLogger")
    private val json = Json { ignoreUnknownKeys = true }
    private val restricted = RestrictedDatabaseQueries(db.steleDatabaseQueries)

    enum class OpType {
        INSERT_BLOCK, UPDATE_BLOCK, DELETE_BLOCK, MOVE_BLOCK,
        BATCH_START, BATCH_END, SYNC_BARRIER
    }

    @Serializable
    data class BlockSnapshot(
        val uuid: String,
        val content: String,
        val position: Int,
        val parentUuid: String?,
        val leftUuid: String?,
        val properties: Map<String, String> = emptyMap(),
    )

    @Serializable
    data class OpPayload(
        val before: BlockSnapshot? = null,
        val after: BlockSnapshot? = null,
        val batchId: String? = null,  // for BATCH_START/END
    )

    // In-memory Lamport clock; loaded from DB on first use
    private var seq: Long = -1L

    @OptIn(DirectSqlWrite::class)
    private fun nextSeq(): Long {
        if (seq < 0) {
            seq = db.steleDatabaseQueries.selectLogicalClock(sessionId)
                .executeAsOneOrNull() ?: 0L
        }
        seq += 1
        restricted.upsertLogicalClock(sessionId, seq)
        return seq
    }

    fun logInsert(block: Block) = log(
        opType = OpType.INSERT_BLOCK,
        entityUuid = block.uuid,
        pageUuid = block.pageUuid,
        payload = OpPayload(after = block.toSnapshot()),
    )

    fun logUpdate(before: Block, after: Block) = log(
        opType = OpType.UPDATE_BLOCK,
        entityUuid = after.uuid,
        pageUuid = after.pageUuid,
        payload = OpPayload(before = before.toSnapshot(), after = after.toSnapshot()),
    )

    fun logDelete(block: Block) = log(
        opType = OpType.DELETE_BLOCK,
        entityUuid = block.uuid,
        pageUuid = block.pageUuid,
        payload = OpPayload(before = block.toSnapshot()),
    )

    fun logSyncBarrier() = log(
        opType = OpType.SYNC_BARRIER,
        entityUuid = null,
        pageUuid = null,
        payload = OpPayload(),
    )

    fun logBatchStart(batchId: String) = log(
        opType = OpType.BATCH_START,
        entityUuid = null,
        pageUuid = null,
        payload = OpPayload(batchId = batchId),
    )

    fun logBatchEnd(batchId: String) = log(
        opType = OpType.BATCH_END,
        entityUuid = null,
        pageUuid = null,
        payload = OpPayload(batchId = batchId),
    )

    @OptIn(DirectSqlWrite::class)
    private fun log(opType: OpType, entityUuid: String?, pageUuid: String?, payload: OpPayload) {
        try {
            val opId = UuidGenerator.generateV7()
            val payloadJson = json.encodeToString(payload)
            val now = clock.now().toEpochMilliseconds()
            // Wrap clock bump + operation insert in one transaction so the two writes
            // share a single lock acquisition rather than two back-to-back ones.
            restricted.transaction {
                val seq = nextSeq()
                restricted.insertOperation(
                    op_id = opId,
                    session_id = sessionId,
                    seq = seq,
                    op_type = opType.name,
                    entity_uuid = entityUuid,
                    page_uuid = pageUuid,
                    payload = payloadJson,
                    created_at = now,
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to log $opType entity=$entityUuid page=$pageUuid session=$sessionId seq=$seq", e)
            // Non-fatal: if op log fails, the underlying write already succeeded
        }
    }

    private fun Block.toSnapshot() = BlockSnapshot(
        uuid = uuid,
        content = content,
        position = position,
        parentUuid = parentUuid,
        leftUuid = leftUuid,
        properties = properties.mapValues { it.value },
    )
}
