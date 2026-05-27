package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import kotlinx.coroutines.flow.Flow

interface ReferenceRepository {
    fun getOutgoingReferences(blockUuid: String): Flow<Either<DomainError, List<Block>>>
    fun getIncomingReferences(blockUuid: String): Flow<Either<DomainError, List<Block>>>
    fun getAllReferences(blockUuid: String): Flow<Either<DomainError, BlockReferences>>

    @DirectRepositoryWrite
    suspend fun addReference(fromBlockUuid: String, toBlockUuid: String): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun removeReference(fromBlockUuid: String, toBlockUuid: String): Either<DomainError, Unit>

    fun getOrphanedBlocks(): Flow<Either<DomainError, List<Block>>>
    fun getMostConnectedBlocks(limit: Int = 20): Flow<Either<DomainError, List<BlockWithReferenceCount>>>
}

data class BlockReferences(
    val outgoing: List<Block>,
    val incoming: List<Block>
)

data class BlockWithReferenceCount(
    val block: Block,
    val referenceCount: Int
)
