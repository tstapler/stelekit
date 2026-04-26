package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.db.Blocks
import dev.stapler.stelekit.db.SelectMostConnectedBlocks
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * SQLDelight implementation of ReferenceRepository.
 * Updated to use UUID-native storage.
 */
@OptIn(DirectRepositoryWrite::class)
class SqlDelightReferenceRepository(
    private val database: SteleDatabase
) : ReferenceRepository {

    private val queries = database.steleDatabaseQueries

    override fun getOutgoingReferences(blockUuid: String): Flow<Either<DomainError, List<Block>>> = flow {
        try {
            val results = queries.selectOutgoingReferences(blockUuid).executeAsList().map { it.toBlockModel() }
            emit(results.right())
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun getIncomingReferences(blockUuid: String): Flow<Either<DomainError, List<Block>>> = flow {
        try {
            val results = queries.selectIncomingReferences(blockUuid).executeAsList().map { it.toBlockModel() }
            emit(results.right())
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun getAllReferences(blockUuid: String): Flow<Either<DomainError, BlockReferences>> = flow {
        try {
            val outgoing = queries.selectOutgoingReferences(blockUuid).executeAsList().map { it.toBlockModel() }
            val incoming = queries.selectIncomingReferences(blockUuid).executeAsList().map { it.toBlockModel() }
            emit(BlockReferences(outgoing, incoming).right())
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override suspend fun addReference(fromBlockUuid: String, toBlockUuid: String): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.insertBlockReference(fromBlockUuid, toBlockUuid, Clock.System.now().toEpochMilliseconds())
                Unit.right()
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

    override suspend fun removeReference(fromBlockUuid: String, toBlockUuid: String): Either<DomainError, Unit> =
        withContext(PlatformDispatcher.DB) {
            try {
                queries.deleteBlockReference(fromBlockUuid, toBlockUuid)
                Unit.right()
            } catch (e: Exception) {
                DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
            }
        }

    override fun getOrphanedBlocks(): Flow<Either<DomainError, List<Block>>> = flow {
        try {
            val results = queries.selectOrphanedBlocks().executeAsList().map { it.toBlockModel() }
            emit(results.right())
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun getMostConnectedBlocks(limit: Int): Flow<Either<DomainError, List<BlockWithReferenceCount>>> = flow {
        try {
            val results = queries.selectMostConnectedBlocks(limit.toLong()).executeAsList().map {
                BlockWithReferenceCount(it.toBlockModel(), it.reference_count.toInt())
            }
            emit(results.right())
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    // Shared mapping logic — properties are not loaded in reference queries (they require
    // a separate join against the block_properties table, not needed for link traversal).
    private fun toBlock(
        uuid: String,
        pageUuid: String,
        parentUuid: String?,
        leftUuid: String?,
        content: String,
        level: Long,
        position: Long,
        createdAt: Long,
        updatedAt: Long,
        version: Long?,
    ): Block = Block(
        uuid = uuid,
        pageUuid = pageUuid,
        parentUuid = parentUuid,
        leftUuid = leftUuid,
        content = content,
        level = level.toInt(),
        position = position.toInt(),
        createdAt = Instant.fromEpochMilliseconds(createdAt),
        updatedAt = Instant.fromEpochMilliseconds(updatedAt),
        version = version ?: 0,
        properties = emptyMap(),
    )

    private fun Blocks.toBlockModel(): Block = toBlock(
        uuid = uuid, pageUuid = page_uuid, parentUuid = parent_uuid, leftUuid = left_uuid,
        content = content, level = level, position = position,
        createdAt = created_at, updatedAt = updated_at, version = version,
    )

    private fun SelectMostConnectedBlocks.toBlockModel(): Block = toBlock(
        uuid = uuid, pageUuid = page_uuid, parentUuid = parent_uuid, leftUuid = left_uuid,
        content = content, level = level, position = position,
        createdAt = created_at, updatedAt = updated_at, version = version,
    )
}
