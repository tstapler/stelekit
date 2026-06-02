package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import kotlinx.coroutines.flow.Flow

interface ReferenceRepository {
    fun getOutgoingReferences(blockUuid: BlockUuid): Flow<Either<DomainError, List<Block>>>
    fun getIncomingReferences(blockUuid: BlockUuid): Flow<Either<DomainError, List<Block>>>
    fun getAllReferences(blockUuid: BlockUuid): Flow<Either<DomainError, BlockReferences>>

    @DirectRepositoryWrite
    suspend fun addReference(fromBlockUuid: BlockUuid, toBlockUuid: BlockUuid): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun removeReference(fromBlockUuid: BlockUuid, toBlockUuid: BlockUuid): Either<DomainError, Unit>

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
