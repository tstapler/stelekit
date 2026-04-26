package dev.stapler.stelekit.db

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * Persistent undo/redo driven by the operation log.
 *
 * Undo inverts the most recent undoable operation for the current session.
 * Undo does not cross SYNC_BARRIER entries.
 * BATCH_START/END pairs are inverted as a single atomic unit.
 *
 * The undo stack is reconstructed from SQLite on every call, so it survives app restarts.
 */
class UndoManager(
    private val db: SteleDatabase,
    private val writeActor: DatabaseWriteActor,
    val sessionId: String,
) {
    private val logger = Logger("UndoManager")
    private val json = Json { ignoreUnknownKeys = true }

    // In-memory set of op_ids that have been undone in this session
    private val undoneOpIds = mutableSetOf<String>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    suspend fun undo() {
        val ops = db.steleDatabaseQueries
            .selectOperationsBySessionDesc(sessionId, 1000L)
            .executeAsList()

        val undoableOps = ops.filterNot { it.op_id in undoneOpIds }
        val target = findUndoTarget(undoableOps) ?: run {
            logger.info("Nothing to undo")
            return
        }

        applyInversion(target)
        updateCanUndoRedo(ops)
    }

    suspend fun redo() {
        val ops = db.steleDatabaseQueries
            .selectOperationsBySessionDesc(sessionId, 1000L)
            .executeAsList()

        val redoableOp = ops
            .filter { it.op_id in undoneOpIds }
            .maxByOrNull { it.seq }
            ?: run { logger.info("Nothing to redo"); return }

        replayOperation(redoableOp)
        undoneOpIds.remove(redoableOp.op_id)
        updateCanUndoRedo(ops)
    }

    fun refreshState() {
        val ops = db.steleDatabaseQueries
            .selectOperationsBySessionDesc(sessionId, 1000L)
            .executeAsList()
        updateCanUndoRedo(ops)
    }

    private fun findUndoTarget(ops: List<Operations>): List<Operations>? {
        if (ops.isEmpty()) return null

        val top = ops.first()

        // Stop at sync barrier
        if (top.op_type == OperationLogger.OpType.SYNC_BARRIER.name) return null

        // If this is a BATCH_END, collect all ops until matching BATCH_START
        if (top.op_type == OperationLogger.OpType.BATCH_END.name) {
            val batchId = parseBatchId(top.payload) ?: return listOf(top)
            val batchOps = ops.takeWhile { op ->
                !(op.op_type == OperationLogger.OpType.BATCH_START.name && parseBatchId(op.payload) == batchId)
            }.plus(ops.firstOrNull { op ->
                op.op_type == OperationLogger.OpType.BATCH_START.name && parseBatchId(op.payload) == batchId
            }).filterNotNull()
            return batchOps
        }

        return listOf(top)
    }

    private suspend fun applyInversion(ops: List<Operations>) {
        for (op in ops) {
            invertOperation(op)
            undoneOpIds.add(op.op_id)
        }
    }

    private suspend fun invertOperation(op: Operations) {
        val payload = try {
            json.decodeFromString(OperationLogger.OpPayload.serializer(), op.payload)
        } catch (e: Exception) {
            logger.error("UndoManager: failed to parse payload for op ${op.op_id}: ${e.message}")
            return
        }

        when (op.op_type) {
            OperationLogger.OpType.UPDATE_BLOCK.name -> {
                val before = payload.before ?: return
                val restored = snapshotToBlock(before, op.page_uuid ?: return)
                writeActor.saveBlock(restored).onLeft { e ->
                    logger.error("UndoManager: failed to restore block ${before.uuid}: ${e.message}")
                }
            }
            OperationLogger.OpType.INSERT_BLOCK.name -> {
                // Undo of insert = delete
                val after = payload.after ?: return
                writeActor.deleteBlock(after.uuid).onLeft { e ->
                    logger.error("UndoManager: failed to delete block ${after.uuid}: ${e.message}")
                }
            }
            OperationLogger.OpType.DELETE_BLOCK.name -> {
                // Undo of delete = re-insert
                val before = payload.before ?: return
                val restored = snapshotToBlock(before, op.page_uuid ?: return)
                writeActor.saveBlock(restored).onLeft { e ->
                    logger.error("UndoManager: failed to restore deleted block ${before.uuid}: ${e.message}")
                }
            }
            OperationLogger.OpType.BATCH_START.name,
            OperationLogger.OpType.BATCH_END.name,
            OperationLogger.OpType.SYNC_BARRIER.name -> {
                // No-op for structural markers
            }
        }
    }

    private suspend fun replayOperation(op: Operations) {
        val payload = try {
            json.decodeFromString(OperationLogger.OpPayload.serializer(), op.payload)
        } catch (e: Exception) {
            logger.error("UndoManager: failed to parse payload for redo op ${op.op_id}: ${e.message}")
            return
        }

        when (op.op_type) {
            OperationLogger.OpType.UPDATE_BLOCK.name -> {
                val after = payload.after ?: return
                val block = snapshotToBlock(after, op.page_uuid ?: return)
                writeActor.saveBlock(block)
            }
            OperationLogger.OpType.INSERT_BLOCK.name -> {
                val after = payload.after ?: return
                val block = snapshotToBlock(after, op.page_uuid ?: return)
                writeActor.saveBlock(block)
            }
            OperationLogger.OpType.DELETE_BLOCK.name -> {
                val before = payload.before ?: return
                writeActor.deleteBlock(before.uuid)
            }
            else -> { /* structural markers */ }
        }
    }

    private fun updateCanUndoRedo(ops: List<Operations>) {
        val undoable = ops
            .filterNot { it.op_id in undoneOpIds }
            .firstOrNull()
        _canUndo.value = undoable != null &&
            undoable.op_type != OperationLogger.OpType.SYNC_BARRIER.name

        _canRedo.value = ops.any { it.op_id in undoneOpIds }
    }

    private fun parseBatchId(payload: String): String? = try {
        json.decodeFromString(OperationLogger.OpPayload.serializer(), payload).batchId
    } catch (_: Exception) { null }

    private fun snapshotToBlock(snapshot: OperationLogger.BlockSnapshot, pageUuid: String): Block {
        val now = Clock.System.now()
        return Block(
            uuid = snapshot.uuid,
            pageUuid = pageUuid,
            content = snapshot.content,
            position = snapshot.position,
            parentUuid = snapshot.parentUuid,
            leftUuid = snapshot.leftUuid,
            properties = snapshot.properties,
            createdAt = now,
            updatedAt = now,
        )
    }
}
