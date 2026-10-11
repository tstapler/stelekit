package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Property
import kotlinx.coroutines.flow.Flow

/** A flagged copy-conflict [block], its page name, and the nearest unflagged left sibling when one exists. */
data class MergeConflictEntry(val block: Block, val pageName: String, val original: Block?)

interface PropertyRepository {
    fun getPropertiesForBlock(blockUuid: BlockUuid): Flow<Either<DomainError, List<Property>>>
    fun getProperty(blockUuid: BlockUuid, key: String): Flow<Either<DomainError, Property?>>

    @DirectRepositoryWrite
    suspend fun saveProperty(property: Property): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun deleteProperty(blockUuid: BlockUuid, key: String): Either<DomainError, Unit>

    fun getBlocksWithPropertyKey(key: String): Flow<Either<DomainError, List<Block>>>
    fun getBlocksWithPropertyValue(key: String, value: String): Flow<Either<DomainError, List<Block>>>

    /**
     * Keyset page (<= [MAX_CONFLICT_PAGE] rows, ordered by block uuid, uuid > [afterUuid]) of blocks flagged
     * `merge-conflict:: true`, each with its page name and bounded neighbor-original lookup.
     * Default is empty for backends without a conflict index (in-memory/Datalog).
     */
    suspend fun getMergeConflicts(afterUuid: String?, limit: Int): Either<DomainError, List<MergeConflictEntry>> =
        emptyList<MergeConflictEntry>().right()

    companion object {
        const val MAX_CONFLICT_PAGE = 100
    }
}
