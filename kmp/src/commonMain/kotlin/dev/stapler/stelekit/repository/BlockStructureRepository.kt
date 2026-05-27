package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError

/**
 * Structural rearrangement operations on the block tree.
 * All methods are gated behind [DirectRepositoryWrite] and must be routed
 * through [dev.stapler.stelekit.db.DatabaseWriteActor].
 */
interface BlockStructureRepository {

    /**
     * Move a block to a new parent and/or position
     */
    @DirectRepositoryWrite
    suspend fun moveBlock(blockUuid: String, newParentUuid: String?, newPosition: Int): Either<DomainError, Unit>

    /**
     * Indent a block (move it to be a child of its preceding sibling)
     */
    @DirectRepositoryWrite
    suspend fun indentBlock(blockUuid: String): Either<DomainError, Unit>

    /**
     * Outdent a block (move it to be a sibling of its parent)
     */
    @DirectRepositoryWrite
    suspend fun outdentBlock(blockUuid: String): Either<DomainError, Unit>

    /**
     * Move a block up among its siblings
     */
    @DirectRepositoryWrite
    suspend fun moveBlockUp(blockUuid: String): Either<DomainError, Unit>

    /**
     * Move a block down among its siblings
     */
    @DirectRepositoryWrite
    suspend fun moveBlockDown(blockUuid: String): Either<DomainError, Unit>
}
