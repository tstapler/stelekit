package dev.stapler.stelekit.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Property
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException

/**
 * Datalog-style in-memory repository for properties.
 * Updated to use UUID-native storage.
 */
@OptIn(DirectRepositoryWrite::class)
class DatascriptPropertyRepository : PropertyRepository {

    private val properties = MutableStateFlow<Map<String, Map<String, Property>>>(emptyMap())

    private val blocks = MutableStateFlow<Map<String, Block>>(emptyMap())

    fun setBlocks(blocksMap: Map<String, Block>) {
        blocks.value = blocksMap
    }

    override fun getPropertiesForBlock(blockUuid: String): Flow<Either<DomainError, List<Property>>> {
        return properties.map { map ->
            val props = map[blockUuid]?.values?.toList() ?: emptyList()
            props.right()
        }
    }

    override fun getProperty(blockUuid: String, key: String): Flow<Either<DomainError, Property?>> {
        return properties.map { map ->
            val prop = map[blockUuid]?.get(key)
            prop.right()
        }
    }

    override suspend fun saveProperty(property: Property): Either<DomainError, Unit> {
        return try {
            val current = properties.value.toMutableMap()
            val blockProps = current.getOrPut(property.blockUuid) { mutableMapOf() }.toMutableMap()
            blockProps[property.key] = property
            current[property.blockUuid] = blockProps
            properties.value = current
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun deleteProperty(blockUuid: String, key: String): Either<DomainError, Unit> {
        return try {
            val current = properties.value.toMutableMap()
            val blockProps = current[blockUuid]?.toMutableMap() ?: return Unit.right()
            blockProps.remove(key)
            current[blockUuid] = blockProps
            properties.value = current
            Unit.right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override fun getBlocksWithPropertyKey(key: String): Flow<Either<DomainError, List<Block>>> {
        return properties.map { map ->
            val blockUuids = map.filter { it.value.containsKey(key) }.keys
            val allBlocks = blocks.value
            val blocksWithKey = blockUuids.mapNotNull { uuid ->
                allBlocks[uuid]
            }
            blocksWithKey.right()
        }
    }

    override fun getBlocksWithPropertyValue(key: String, value: String): Flow<Either<DomainError, List<Block>>> {
        return properties.map { map ->
            val blockUuids = map.filter { blockProps ->
                blockProps.value[key]?.value == value
            }.keys
            val allBlocks = blocks.value
            val blocksWithValue = blockUuids.mapNotNull { uuid ->
                allBlocks[uuid]
            }
            blocksWithValue.right()
        }
    }
}
