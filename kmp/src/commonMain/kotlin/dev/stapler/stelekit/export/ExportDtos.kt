package dev.stapler.stelekit.export

import kotlinx.serialization.Serializable

@Serializable
data class ExportRoot(
    val version: Int = 1,
    val exportedAt: String, // ISO-8601
    val page: PageDto,
    val blocks: List<BlockDto>
)

@Serializable
data class PageDto(
    val uuid: String,
    val name: String,
    val isJournal: Boolean,
    val journalDate: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val properties: Map<String, String> // id:: key filtered out
)

@Serializable
data class BlockDto(
    val uuid: String,
    val parentUuid: String?,
    val position: Int,
    val level: Int,
    val content: String, // raw Logseq syntax preserved
    val properties: Map<String, String>, // id:: key filtered out
    val children: List<BlockDto>
)
