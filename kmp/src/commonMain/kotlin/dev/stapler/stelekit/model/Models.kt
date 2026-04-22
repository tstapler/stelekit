package dev.stapler.stelekit.model

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Security validation for input data
 */
object Validation {
    private const val MAX_STRING_LENGTH = 10000
    private const val MAX_NAME_LENGTH = 255
    private const val MAX_CONTENT_LENGTH = 10000000

    fun validateString(input: String?, maxLength: Int = MAX_STRING_LENGTH, allowWhitespace: Boolean = false): String {
        require(input != null) { "Input cannot be null" }
        require(input.length <= maxLength) { "Input exceeds maximum length of $maxLength" }
        require(!input.contains('\u0000')) { "Input contains null bytes" }
        if (!allowWhitespace) {
            require(!input.any { it.code in 0x00..0x1F || it.code in 0x80..0x9F }) { "Input contains control characters" }
        } else {
            require(!input.any { (it.code in 0x00..0x1F || it.code in 0x80..0x9F) && it != '\n' && it != '\r' && it != '\t' }) { "Input contains restricted control characters" }
        }
        return input.trim()
    }

    fun validateName(name: String?): String {
        val validated = validateString(name, MAX_NAME_LENGTH, allowWhitespace = false)
        require(validated.isNotBlank()) { "Name cannot be blank" }
        // Only reject ".." when it is a standalone path segment (e.g. "../foo", "/..").
        // "..." inside a book title like "Start with NO..." is a valid page name.
        val segments = validated.split('/', '\\')
        require(segments.none { it == ".." }) { "Name contains directory traversal patterns" }
        // Page names may contain / (e.g. "Cordless/Corded") — Logseq encodes them as %2F in filenames.
        // Only backslashes are rejected since they indicate Windows path issues.
        require(!validated.contains('\\')) { "Name contains backslashes" }
        return validated
    }

    fun validateContent(content: String?): String {
        return validateString(content, MAX_CONTENT_LENGTH, allowWhitespace = true)
    }

    fun validateUuid(uuid: String?): String {
        val validated = validateString(uuid, 36)
        require(validated.isNotBlank()) { "UUID cannot be blank" }
        // Relaxed validation to allow for human-readable IDs in tests (e.g. "page-1")
        // but still ensuring it's a reasonable string.
        require(validated.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }) {
            "Invalid UUID format: $validated"
        }
        return validated
    }
}

data class Page(
    val uuid: String,
    val name: String,
    val namespace: String? = null,
    val filePath: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long = 0,
    val properties: Map<String, String> = emptyMap(),
    val isFavorite: Boolean = false,
    val isJournal: Boolean = false,
    val journalDate: LocalDate? = null,
    /** True when page content (blocks) has been fully loaded from file */
    val isContentLoaded: Boolean = true
) {
    init {
        Validation.validateUuid(uuid)
        Validation.validateName(name)
        namespace?.let { Validation.validateName(it) }
        filePath?.let { Validation.validateContent(it) }
        properties.forEach { (key, value) ->
            Validation.validateName(key)
            Validation.validateContent(value)
        }
    }
}

private val validBlockTypes = setOf(
    "bullet", "paragraph", "heading", "code_fence", "blockquote",
    "ordered_list_item", "thematic_break", "table", "raw_html"
)

data class Block(
    val uuid: String,
    val pageUuid: String,
    val parentUuid: String? = null,
    val leftUuid: String? = null,
    val content: String,
    val level: Int = 0,
    val position: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long = 0,
    val properties: Map<String, String> = emptyMap(),
    val isLoaded: Boolean = true, // Indicates if the content is fully loaded
    val contentHash: String? = null, // SHA-256 of normalised content; null until first save
    val blockType: String = "bullet" // Structural discriminator for the block type
) {
    init {
        Validation.validateUuid(uuid)
        Validation.validateUuid(pageUuid)
        parentUuid?.let { Validation.validateUuid(it) }
        leftUuid?.let { Validation.validateUuid(it) }
        Validation.validateContent(content)
        require(level >= 0) { "Level must be non-negative" }
        require(position >= 0) { "Position must be non-negative" }
        properties.forEach { (key, value) ->
            Validation.validateName(key)
            Validation.validateContent(value)
        }
        require(blockType in validBlockTypes) { "Invalid blockType: $blockType" }
    }
}

data class Property(
    val uuid: String,
    val blockUuid: String,
    val key: String,
    val value: String,
    val createdAt: Instant
) {
    init {
        Validation.validateUuid(uuid)
        Validation.validateUuid(blockUuid)
        Validation.validateName(key)
        Validation.validateContent(value)
    }
}

enum class NotificationType {
    INFO, WARNING, ERROR, SUCCESS
}

data class Notification(
    val id: String,
    val content: String,
    val type: NotificationType = NotificationType.INFO,
    val timestamp: Instant,
    val timeout: Long? = 3000
) {
    init {
        Validation.validateContent(content)
        Validation.validateUuid(id)
    }
}
