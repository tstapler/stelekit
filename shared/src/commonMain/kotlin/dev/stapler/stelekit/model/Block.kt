package dev.stapler.stelekit.model

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a Block entity in Logseq's knowledge graph.
 *
 * Blocks are the fundamental content units that can represent:
 * - Content blocks with text and properties
 * - Pages (special blocks that serve as containers)
 * - Journal entries
 * - Whiteboard elements
 * - Properties and classes
 */
@Serializable
data class Block(
    @SerialName("id")
    val id: Long,

    @SerialName("uuid")
    val uuid: String,

    @SerialName("page_id")
    val pageId: Long,

    @SerialName("parent_id")
    val parentId: Long? = null,

    @SerialName("left_id")
    val leftId: Long? = null,

    @SerialName("content")
    val content: String,

    @SerialName("level")
    val level: Int = 0,

    @SerialName("position")
    val position: Int,

    @SerialName("created_at")
    val createdAt: Instant,

    @SerialName("updated_at")
    val updatedAt: Instant,

    @SerialName("version")
    val version: Long = 0,

    // Page-specific attributes (when block represents a page)
    @SerialName("title")
    val title: String? = null,

    @SerialName("name")
    val name: String? = null, // Lowercase page name

    // UI state
    @SerialName("collapsed")
    val collapsed: Boolean = false,

    // Task management
    @SerialName("marker")
    val marker: String? = null, // "TODO", "DOING", "DONE"

    @SerialName("priority")
    val priority: String? = null, // "A", "B", "C"

    // Scheduling
    @SerialName("scheduled")
    val scheduled: Long? = null, // Day number

    @SerialName("deadline")
    val deadline: Long? = null, // Day number

    @SerialName("repeated")
    val repeated: Boolean = false,

    // Journal pages
    @SerialName("journal_day")
    val journalDay: Long? = null,

    // Content format and type
    @SerialName("format")
    val format: String = "markdown", // "markdown", "org"

    @SerialName("type")
    val type: String? = null, // "whiteboard", "property", "class"

    @SerialName("pre_block")
    val preBlock: Boolean = false,

    // References and relationships
    @SerialName("refs")
    val refs: List<Long> = emptyList(), // Block references

    @SerialName("tags")
    val tags: List<Long> = emptyList(), // Tag/class references

    @SerialName("aliases")
    val aliases: List<Long> = emptyList(), // Page aliases

    @SerialName("macros")
    val macros: List<Long> = emptyList(), // Macro references

    // File association
    @SerialName("file_id")
    val fileId: Long? = null,

    // Transaction tracking
    @SerialName("tx_id")
    val txId: Long? = null,

    // Properties (key-value pairs)
    @SerialName("properties")
    val properties: Map<String, String> = emptyMap(),

    @SerialName("properties_order")
    val propertiesOrder: List<String> = emptyList(),

    @SerialName("properties_text_values")
    val propertiesTextValues: Map<String, String> = emptyMap()
) {
    init {
        require(id > 0) { "Block ID must be positive" }
        require(uuid.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))) {
            "Invalid UUID format"
        }
        require(pageId > 0) { "Page ID must be positive" }
        require(level >= 0) { "Level must be non-negative" }
        require(position >= 0) { "Position must be non-negative" }
        require(format in listOf("markdown", "org")) { "Format must be 'markdown' or 'org'" }
        require(marker == null || marker in listOf("TODO", "DOING", "DONE")) {
            "Marker must be null or one of: TODO, DOING, DONE"
        }
        require(priority == null || priority in listOf("A", "B", "C")) {
            "Priority must be null or one of: A, B, C"
        }

        // Validate references are positive IDs
        refs.forEach { require(it > 0) { "Reference ID must be positive" } }
        tags.forEach { require(it > 0) { "Tag ID must be positive" } }
        aliases.forEach { require(it > 0) { "Alias ID must be positive" } }
        macros.forEach { require(it > 0) { "Macro ID must be positive" } }
    }

    /**
     * Returns true if this block represents a page (has a name/title)
     */
    val isPage: Boolean
        get() = name != null || title != null

    /**
     * Returns true if this block represents a journal entry
     */
    val isJournal: Boolean
        get() = journalDay != null

    /**
     * Returns true if this block represents a task (has a marker)
     */
    val isTask: Boolean
        get() = marker != null

    companion object {
        const val TYPE_WHITEBOARD = "whiteboard"
        const val TYPE_PROPERTY = "property"
        const val TYPE_CLASS = "class"

        const val MARKER_TODO = "TODO"
        const val MARKER_DOING = "DOING"
        const val MARKER_DONE = "DONE"

        const val PRIORITY_A = "A"
        const val PRIORITY_B = "B"
        const val PRIORITY_C = "C"

        const val FORMAT_MARKDOWN = "markdown"
        const val FORMAT_ORG = "org"
    }
}