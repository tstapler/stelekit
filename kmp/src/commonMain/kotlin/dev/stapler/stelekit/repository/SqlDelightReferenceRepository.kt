package dev.stapler.stelekit.repository

import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.Result.Companion.success

/**
 * SQLDelight implementation of ReferenceRepository.
 * Updated to use UUID-native storage.
 */
class SqlDelightReferenceRepository(
    private val database: SteleDatabase
) : ReferenceRepository {

    private val queries = database.steleDatabaseQueries

    override fun getOutgoingReferences(blockUuid: String): Flow<Result<List<Block>>> = flow {
        try {
            val results = queries.selectOutgoingReferences(blockUuid).executeAsList().map { it.toBlockModel() }
            emit(success(results))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(PlatformDispatcher.IO)

    override fun getIncomingReferences(blockUuid: String): Flow<Result<List<Block>>> = flow {
        try {
            val results = queries.selectIncomingReferences(blockUuid).executeAsList().map { it.toBlockModel() }
            emit(success(results))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(PlatformDispatcher.IO)

    override fun getAllReferences(blockUuid: String): Flow<Result<BlockReferences>> = flow {
        try {
            val outgoing = queries.selectOutgoingReferences(blockUuid).executeAsList().map { it.toBlockModel() }
            val incoming = queries.selectIncomingReferences(blockUuid).executeAsList().map { it.toBlockModel() }
            emit(success(BlockReferences(outgoing, incoming)))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(PlatformDispatcher.IO)

    override suspend fun addReference(fromBlockUuid: String, toBlockUuid: String): Result<Unit> {
        return try {
            queries.insertBlockReference(fromBlockUuid, toBlockUuid, Clock.System.now().toEpochMilliseconds())
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeReference(fromBlockUuid: String, toBlockUuid: String): Result<Unit> {
        return try {
            queries.deleteBlockReference(fromBlockUuid, toBlockUuid)
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getOrphanedBlocks(): Flow<Result<List<Block>>> = flow {
        try {
            val results = queries.selectOrphanedBlocks().executeAsList().map { it.toBlockModel() }
            emit(success(results))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(PlatformDispatcher.IO)

    override fun getMostConnectedBlocks(limit: Int): Flow<Result<List<BlockWithReferenceCount>>> = flow {
        try {
            val results = queries.selectMostConnectedBlocks(limit.toLong()).executeAsList().map {
                BlockWithReferenceCount(it.toBlockModel(), it.reference_count.toInt())
            }
            emit(success(results))
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }.flowOn(PlatformDispatcher.IO)

    private fun dev.stapler.stelekit.db.Blocks.toBlockModel(): Block {
        return Block(
            uuid = this.uuid,
            pageUuid = this.page_uuid,
            parentUuid = this.parent_uuid,
            leftUuid = this.left_uuid,
            content = this.content,
            level = this.level.toInt(),
            position = this.position.toInt(),
            createdAt = Instant.fromEpochMilliseconds(this.created_at),
            updatedAt = Instant.fromEpochMilliseconds(this.updated_at),
            version = this.version,
            properties = emptyMap()
        )
    }
    
    // Explicit mapping for join result
    private fun dev.stapler.stelekit.db.SelectMostConnectedBlocks.toBlockModel(): Block {
        return Block(
            uuid = this.uuid,
            pageUuid = this.page_uuid,
            parentUuid = this.parent_uuid,
            leftUuid = this.left_uuid,
            content = this.content,
            level = this.level.toInt(),
            position = this.position.toInt(),
            createdAt = Instant.fromEpochMilliseconds(this.created_at),
            updatedAt = Instant.fromEpochMilliseconds(this.updated_at),
            version = this.version,
            properties = emptyMap()
        )
    }
}
