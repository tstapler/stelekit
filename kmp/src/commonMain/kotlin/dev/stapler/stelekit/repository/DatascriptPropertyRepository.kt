package dev.stapler.stelekit.repository

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Property
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.Result.Companion.success

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

    override fun getPropertiesForBlock(blockUuid: String): Flow<Result<List<Property>>> {
        return properties.map { map ->
            val props = map[blockUuid]?.values?.toList() ?: emptyList()
            success(props)
        }
    }

    override fun getProperty(blockUuid: String, key: String): Flow<Result<Property?>> {
        return properties.map { map ->
            val prop = map[blockUuid]?.get(key)
            success(prop)
        }
    }

    override suspend fun saveProperty(property: Property): Result<Unit> {
        return try {
            val current = properties.value.toMutableMap()
            val blockProps = current.getOrPut(property.blockUuid) { mutableMapOf() }.toMutableMap()
            blockProps[property.key] = property
            current[property.blockUuid] = blockProps
            properties.value = current
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteProperty(blockUuid: String, key: String): Result<Unit> {
        return try {
            val current = properties.value.toMutableMap()
            val blockProps = current[blockUuid]?.toMutableMap() ?: return success(Unit)
            blockProps.remove(key)
            current[blockUuid] = blockProps
            properties.value = current
            success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getBlocksWithPropertyKey(key: String): Flow<Result<List<Block>>> {
        return properties.map { map ->
            val blockUuids = map.filter { it.value.containsKey(key) }.keys
            val allBlocks = blocks.value
            val blocksWithKey = blockUuids.mapNotNull { uuid ->
                allBlocks[uuid]
            }
            success(blocksWithKey)
        }
    }

    override fun getBlocksWithPropertyValue(key: String, value: String): Flow<Result<List<Block>>> {
        return properties.map { map ->
            val blockUuids = map.filter { blockProps ->
                blockProps.value[key]?.value == value
            }.keys
            val allBlocks = blocks.value
            val blocksWithValue = blockUuids.mapNotNull { uuid ->
                allBlocks[uuid]
            }
            success(blocksWithValue)
        }
    }
}
