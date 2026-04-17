package dev.stapler.stelekit.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class PluginMetadata(
    val id: Long,
    val pluginId: String,
    val entityType: String,
    val entityUuid: String,
    val key: String,
    val value: String,
    val createdAt: Instant,
    val updatedAt: Instant? = null
) {
    init {
        require(id >= 0) { "ID must be non-negative" }
        Validation.validateName(pluginId)
        require(entityType.isNotBlank()) { "Entity type cannot be blank" }
        require(entityUuid.isNotBlank()) { "Entity UUID cannot be blank" }
        Validation.validateName(key)
        Validation.validateContent(value)
    }
}

@Serializable
data class PluginData(
    val pluginId: String,
    val entityType: String,
    val entityUuid: String,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Instant,
    val updatedAt: Instant? = null
) {
    init {
        Validation.validateName(pluginId)
        require(entityType.isNotBlank()) { "Entity type cannot be blank" }
        require(entityUuid.isNotBlank()) { "Entity UUID cannot be blank" }
        metadata.forEach { (key, value) ->
            Validation.validateName(key)
            Validation.validateContent(value)
        }
    }
}
