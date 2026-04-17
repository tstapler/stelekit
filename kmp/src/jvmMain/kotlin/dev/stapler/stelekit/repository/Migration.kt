package dev.stapler.stelekit.repository

import dev.stapler.stelekit.model.PluginData
import dev.stapler.stelekit.model.PluginMetadata
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class EavTuple(
    val entity: String,
    val attribute: String,
    val value: String
)

object PluginMigration {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun convertEavToPluginData(eavTuples: List<EavTuple>): List<PluginData> {
        val groupedByEntity = eavTuples.groupBy { it.entity }

        return groupedByEntity.mapNotNull { (_, tuples) ->
            val pluginId = tuples.find { it.attribute == "plugin-id" }?.value ?: return@mapNotNull null
            val entityType = tuples.find { it.attribute == "entity-type" }?.value ?: return@mapNotNull null
            val entityUuid = tuples.find { it.attribute == "entity-uuid" }?.value ?: return@mapNotNull null

            val metadata = tuples
                .filter { it.attribute != "plugin-id" && it.attribute != "entity-type" && it.attribute != "entity-uuid" }
                .associate { it.attribute to it.value }

            PluginData(
                pluginId = pluginId,
                entityType = entityType,
                entityUuid = entityUuid,
                metadata = metadata,
                createdAt = Clock.System.now()
            )
        }
    }

    fun convertPluginDataToEav(pluginData: PluginData): List<EavTuple> {
        val baseTuples = listOf(
            EavTuple(pluginData.pluginId, "plugin-id", pluginData.pluginId),
            EavTuple(pluginData.pluginId, "entity-type", pluginData.entityType),
            EavTuple(pluginData.pluginId, "entity-uuid", pluginData.entityUuid)
        )

        val metadataTuples = pluginData.metadata.map { (key, value) ->
            EavTuple(pluginData.pluginId, key, value)
        }

        return baseTuples + metadataTuples
    }

    fun convertToPluginMetadata(
        pluginData: PluginData,
        id: Long,
        createdAt: Instant? = null
    ): List<PluginMetadata> {
        val timestamp = createdAt ?: Clock.System.now()
        return pluginData.metadata.map { (key, value) ->
            PluginMetadata(
                id = id,
                pluginId = pluginData.pluginId,
                entityType = pluginData.entityType,
                entityUuid = pluginData.entityUuid,
                key = key,
                value = value,
                createdAt = timestamp,
                updatedAt = null
            )
        }
    }

    fun serializeMetadataToJson(metadata: Map<String, String>): String {
        return buildJsonObject {
            metadata.forEach { (key, value) ->
                put(key, value)
            }
        }.toString()
    }

    fun deserializeMetadataFromJson(jsonString: String): Map<String, String> {
        if (jsonString.isBlank() || jsonString == "null") {
            return emptyMap()
        }
        return try {
            val jsonElement = json.parseToJsonElement(jsonString)
            val jsonObject = jsonElement.jsonObject
            val result = mutableMapOf<String, String>()
            for ((key, value) in jsonObject) {
                result[key] = value.jsonPrimitive.content
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
