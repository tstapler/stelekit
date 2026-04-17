package dev.stapler.stelekit.model

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a Page entity in Logseq's knowledge graph.
 *
 * Pages are special blocks that serve as containers for other blocks.
 * They can have aliases, namespaces, and represent different types of content
 * (regular pages, journals, whiteboards, etc.)
 */
@Serializable
data class Page(
    @SerialName("id")
    val id: Long,

    @SerialName("uuid")
    val uuid: String,

    @SerialName("name")
    val name: String, // Lowercase page name (unique identifier)

    @SerialName("title")
    val title: String, // Display title

    @SerialName("namespace")
    val namespace: String? = null, // Hierarchical namespace

    @SerialName("file_path")
    val filePath: String? = null, // Associated file path

    @SerialName("created_at")
    val createdAt: Instant,

    @SerialName("updated_at")
    val updatedAt: Instant,

    @SerialName("version")
    val version: Long = 0,

    // UI state
    @SerialName("collapsed")
    val collapsed: Boolean = false,

    // Journal pages
    @SerialName("journal_day")
    val journalDay: Long? = null,

    // Relationships
    @SerialName("aliases")
    val aliases: List<Long> = emptyList(), // Page aliases

    @SerialName("tags")
    val tags: List<Long> = emptyList(), // Tag/class references

    @SerialName("refs")
    val refs: List<Long> = emptyList(), // Block references

    // Properties (key-value pairs)
    @SerialName("properties")
    val properties: Map<String, String> = emptyMap(),

    @SerialName("properties_order")
    val propertiesOrder: List<String> = emptyList(),

    @SerialName("properties_text_values")
    val propertiesTextValues: Map<String, String> = emptyMap()
) {
    init {
        require(id > 0) { "Page ID must be positive" }
        require(uuid.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))) {
            "Invalid UUID format"
        }
        require(name.isNotBlank()) { "Page name cannot be blank" }
        require(title.isNotBlank()) { "Page title cannot be blank" }
        require(!name.contains("/")) { "Page name cannot contain path separators" }
        require(!name.contains("\\")) { "Page name cannot contain backslashes" }
        require(!name.contains("..")) { "Page name cannot contain directory traversal patterns" }

        namespace?.let {
            require(it.isNotBlank()) { "Namespace cannot be blank if provided" }
            require(!it.contains("/")) { "Namespace cannot contain path separators" }
            require(!it.contains("\\")) { "Namespace cannot contain backslashes" }
        }

        // Validate references are positive IDs
        aliases.forEach { require(it > 0) { "Alias ID must be positive" } }
        tags.forEach { require(it > 0) { "Tag ID must be positive" } }
        refs.forEach { require(it > 0) { "Reference ID must be positive" } }
    }

    /**
     * Returns the full page name including namespace
     */
    val fullName: String
        get() = if (namespace != null) "$namespace/$name" else name

    /**
     * Returns true if this page represents a journal entry
     */
    val isJournal: Boolean
        get() = journalDay != null

    /**
     * Returns true if this page has a namespace
     */
    val hasNamespace: Boolean
        get() = namespace != null

    /**
     * Converts this Page to a Block representation
     * (useful for storage or when treating pages as blocks)
     */
    fun toBlock(): Block {
        return Block(
            id = id,
            uuid = uuid,
            pageId = id, // Pages reference themselves as their page
            content = "", // Pages don't have content directly
            position = 0,
            createdAt = createdAt,
            updatedAt = updatedAt,
            version = version,
            title = title,
            name = name,
            collapsed = collapsed,
            journalDay = journalDay,
            aliases = aliases,
            tags = tags,
            refs = refs,
            properties = properties,
            propertiesOrder = propertiesOrder,
            propertiesTextValues = propertiesTextValues
        )
    }

    companion object {
        /**
         * Creates a Page from a Block representation
         * Returns null if the block doesn't represent a valid page
         */
        fun fromBlock(block: Block): Page? {
            if (block.name == null || block.title == null) return null

            return Page(
                id = block.id,
                uuid = block.uuid,
                name = block.name,
                title = block.title,
                filePath = null, // Not stored in blocks
                createdAt = block.createdAt,
                updatedAt = block.updatedAt,
                version = block.version,
                collapsed = block.collapsed,
                journalDay = block.journalDay,
                aliases = block.aliases,
                tags = block.tags,
                refs = block.refs,
                properties = block.properties,
                propertiesOrder = block.propertiesOrder,
                propertiesTextValues = block.propertiesTextValues
            )
        }
    }
}