package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Property
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant

/**
 * SQLDelight implementation of PropertyRepository.
 * Updated to use UUID-native storage.
 */
@OptIn(DirectRepositoryWrite::class)
class SqlDelightPropertyRepository(
    private val database: SteleDatabase
) : PropertyRepository {

    private val queries = database.steleDatabaseQueries

    override fun getPropertiesForBlock(blockUuid: String): Flow<Either<DomainError, List<Property>>> = flow {
        try {
            val block = queries.selectBlockByUuid(blockUuid).executeAsOneOrNull()
            if (block == null) {
                emit(emptyList<Property>().right())
            } else {
                val properties = parseProperties(block.uuid, block.properties)
                emit(properties.right())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun getProperty(blockUuid: String, key: String): Flow<Either<DomainError, Property?>> = flow {
        try {
            val block = queries.selectBlockByUuid(blockUuid).executeAsOneOrNull()
            if (block == null) {
                emit(null.right())
            } else {
                val property = parseProperties(block.uuid, block.properties).find { it.key == key }
                emit(property.right())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override suspend fun saveProperty(property: Property): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val block = queries.selectBlockByUuid(property.blockUuid).executeAsOneOrNull()
            if (block != null) {
                val existing = parseProperties(block.uuid, block.properties).associate { it.key to it.value }.toMutableMap()
                existing[property.key] = property.value
                val updatedString = existing.entries.joinToString(",") { "${it.key}:${it.value}" }
                queries.updateBlockProperties(updatedString, block.uuid)
            }
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun deleteProperty(blockUuid: String, key: String): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val block = queries.selectBlockByUuid(blockUuid).executeAsOneOrNull()
            if (block != null) {
                val existing = parseProperties(block.uuid, block.properties).associate { it.key to it.value }.toMutableMap()
                existing.remove(key)
                val updatedString = existing.entries.joinToString(",") { "${it.key}:${it.value}" }
                queries.updateBlockProperties(updatedString, block.uuid)
            }
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override fun getBlocksWithPropertyKey(key: String): Flow<Either<DomainError, List<Block>>> = flow {
        try {
            val results = queries.selectAllBlocks().executeAsList()
                .filter { it.properties?.contains(key) == true }
                .map { it.toBlockModel() }
            emit(results.right())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override fun getBlocksWithPropertyValue(key: String, value: String): Flow<Either<DomainError, List<Block>>> = flow {
        try {
            val results = queries.selectAllBlocks().executeAsList()
                .filter { it.properties?.contains("$key:$value") == true }
                .map { it.toBlockModel() }
            emit(results.right())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    private fun parseProperties(blockUuid: String, propertiesString: String?): List<Property> {
        return propertiesString?.split(",")?.mapNotNull {
            val parts = it.split(":", limit = 2)
            if (parts.size == 2) {
                Property(
                    uuid = dev.stapler.stelekit.util.UuidGenerator.generateDeterministic("$blockUuid:${parts[0]}"),
                    blockUuid = blockUuid,
                    key = parts[0],
                    value = parts[1],
                    createdAt = kotlin.time.Clock.System.now()
                )
            } else null
        } ?: emptyList()
    }

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
}
