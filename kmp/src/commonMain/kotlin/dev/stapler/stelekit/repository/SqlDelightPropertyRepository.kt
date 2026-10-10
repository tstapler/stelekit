package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.model.Property
import dev.stapler.stelekit.coroutines.PlatformDispatcher
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
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

    override fun getPropertiesForBlock(blockUuid: BlockUuid): Flow<Either<DomainError, List<Property>>> = flow {
        try {
            val block = queries.selectBlockByUuid(blockUuid.value).asFlow().mapToOneOrNull(PlatformDispatcher.DB).first()
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

    override fun getProperty(blockUuid: BlockUuid, key: String): Flow<Either<DomainError, Property?>> = flow {
        try {
            val block = queries.selectBlockByUuid(blockUuid.value).asFlow().mapToOneOrNull(PlatformDispatcher.DB).first()
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
            val block = queries.selectBlockByUuid(property.blockUuid).asFlow().mapToOneOrNull(PlatformDispatcher.DB).first()  // Property.blockUuid is still String
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

    override suspend fun deleteProperty(blockUuid: BlockUuid, key: String): Either<DomainError, Unit> = withContext(PlatformDispatcher.DB) {
        try {
            val block = queries.selectBlockByUuid(blockUuid.value).asFlow().mapToOneOrNull(PlatformDispatcher.DB).first()
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
            val results = queries.selectAllBlocks().asFlow().mapToList(PlatformDispatcher.DB).first()
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
            val results = queries.selectAllBlocks().asFlow().mapToList(PlatformDispatcher.DB).first()
                .filter { it.properties?.contains("$key:$value") == true }
                .map { it.toBlockModel() }
            emit(results.right())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
        }
    }.flowOn(PlatformDispatcher.DB)

    override suspend fun getMergeConflicts(afterUuid: String?, limit: Int): Either<DomainError, List<MergeConflictEntry>> =
        withContext(PlatformDispatcher.DB) {
            try {
                val rows = queries
                    .selectMergeConflictBlocks(afterUuid ?: "", limit.coerceIn(1, PropertyRepository.MAX_CONFLICT_PAGE).toLong())
                    .executeAsList()
                    // LIKE is a substring prefilter; confirm the exact key and value.
                    .map { it.toConflictModel() }
                    .filter { it.properties["merge-conflict"] == "true" }
                val pageNames = HashMap<String, String>()
                rows.map { block ->
                    val pageName = pageNames.getOrPut(block.pageUuid.value) {
                        queries.selectPageByUuid(block.pageUuid.value).executeAsOneOrNull()?.name ?: ""
                    }
                    MergeConflictEntry(block, pageName, findOriginalNeighbor(block))
                }.right()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DomainError.DatabaseError.ReadFailed(e.message ?: "unknown").left()
            }
        }

    // Walks left past other flagged siblings (a few hops at most) to the unflagged block the conflict sits beside.
    private fun findOriginalNeighbor(conflict: Block): Block? {
        var leftUuid = conflict.leftUuid?.value
        repeat(CONFLICT_NEIGHBOR_HOPS) {
            val left = leftUuid?.let { queries.selectBlockByUuid(it).executeAsOneOrNull() }?.toConflictModel() ?: return null
            if (left.properties["merge-conflict"] != "true") return left
            leftUuid = left.leftUuid?.value
        }
        return null
    }

    private fun dev.stapler.stelekit.db.Blocks.toConflictModel(): Block = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(page_uuid),
        parentUuid = parent_uuid?.let { BlockUuid(it) },
        leftUuid = left_uuid?.let { BlockUuid(it) },
        content = content,
        level = level.toInt(),
        position = position,
        createdAt = Instant.fromEpochMilliseconds(created_at),
        updatedAt = Instant.fromEpochMilliseconds(updated_at),
        version = version,
        properties = parseProperties(uuid, properties).associate { it.key to it.value },
    )

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
            uuid = BlockUuid(this.uuid),
            pageUuid = PageUuid(this.page_uuid),
            parentUuid = this.parent_uuid?.let { BlockUuid(it) },
            leftUuid = this.left_uuid?.let { BlockUuid(it) },
            content = this.content,
            level = this.level.toInt(),
            position = this.position,
            createdAt = Instant.fromEpochMilliseconds(this.created_at),
            updatedAt = Instant.fromEpochMilliseconds(this.updated_at),
            version = this.version,
            properties = emptyMap()
        )
    }
}

private const val CONFLICT_NEIGHBOR_HOPS = 5
