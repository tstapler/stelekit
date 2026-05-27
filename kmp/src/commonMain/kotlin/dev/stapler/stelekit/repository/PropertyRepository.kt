package dev.stapler.stelekit.repository

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Property
import kotlinx.coroutines.flow.Flow

interface PropertyRepository {
    fun getPropertiesForBlock(blockUuid: String): Flow<Either<DomainError, List<Property>>>
    fun getProperty(blockUuid: String, key: String): Flow<Either<DomainError, Property?>>

    @DirectRepositoryWrite
    suspend fun saveProperty(property: Property): Either<DomainError, Unit>

    @DirectRepositoryWrite
    suspend fun deleteProperty(blockUuid: String, key: String): Either<DomainError, Unit>

    fun getBlocksWithPropertyKey(key: String): Flow<Either<DomainError, List<Block>>>
    fun getBlocksWithPropertyValue(key: String, value: String): Flow<Either<DomainError, List<Block>>>
}
