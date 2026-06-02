package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Property
import kotlinx.coroutines.flow.Flow

interface PropertyRepository {
    fun getPropertiesForBlock(blockUuid: BlockUuid): Flow<Either<DomainError, List<Property>>>
    fun getProperty(blockUuid: BlockUuid, key: String): Flow<Either<DomainError, Property?>>

    @DirectRepositoryWrite
    suspend fun saveProperty(property: Property): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun deleteProperty(blockUuid: BlockUuid, key: String): Either<DomainError, Unit>

    fun getBlocksWithPropertyKey(key: String): Flow<Either<DomainError, List<Block>>>
    fun getBlocksWithPropertyValue(key: String, value: String): Flow<Either<DomainError, List<Block>>>
}
