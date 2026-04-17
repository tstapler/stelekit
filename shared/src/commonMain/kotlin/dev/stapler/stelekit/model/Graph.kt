package dev.stapler.stelekit.model

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a Graph entity in Logseq's knowledge graph system.
 *
 * A Graph is the top-level container that holds all pages, blocks, and metadata
 * for a Logseq knowledge base. It includes configuration settings and metadata
 * about the graph itself.
 */
@Serializable
data class Graph(
    @SerialName("uuid")
    val uuid: String,

    @SerialName("name")
    val name: String,

    @SerialName("created_at")
    val createdAt: Instant,

    @SerialName("schema_version")
    val schemaVersion: String = "65.18",

    @SerialName("backup_folder")
    val backupFolder: String? = null,

    // Feature flags
    @SerialName("rtc_enabled")
    val rtcEnabled: Boolean = false,

    @SerialName("e2ee_enabled")
    val e2eeEnabled: Boolean = false,

    // AI/ML features
    @SerialName("text_embedding_model")
    val textEmbeddingModel: String? = null,

    // Maintenance
    @SerialName("last_gc_at")
    val lastGcAt: Instant? = null,

    // Additional metadata
    @SerialName("description")
    val description: String? = null,

    @SerialName("author")
    val author: String? = null,

    @SerialName("version")
    val version: String? = null,

    // File paths
    @SerialName("root_path")
    val rootPath: String,

    @SerialName("config_path")
    val configPath: String? = null,

    // Statistics (computed fields)
    @SerialName("total_pages")
    val totalPages: Int = 0,

    @SerialName("total_blocks")
    val totalBlocks: Int = 0,

    @SerialName("total_files")
    val totalFiles: Int = 0
) {
    init {
        require(uuid.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))) {
            "Invalid Graph UUID format"
        }
        require(name.isNotBlank()) { "Graph name cannot be blank" }
        require(rootPath.isNotBlank()) { "Root path cannot be blank" }
        require(schemaVersion.matches(Regex("^\\d+\\.\\d+$"))) {
            "Schema version must be in format X.Y"
        }
        require(totalPages >= 0) { "Total pages cannot be negative" }
        require(totalBlocks >= 0) { "Total blocks cannot be negative" }
        require(totalFiles >= 0) { "Total files cannot be negative" }

        // Validate paths don't contain dangerous patterns
        require(!rootPath.contains("..")) { "Root path contains directory traversal" }
        configPath?.let {
            require(!it.contains("..")) { "Config path contains directory traversal" }
        }
    }

    /**
     * Returns true if real-time collaboration is enabled
     */
    val isRtcEnabled: Boolean
        get() = rtcEnabled

    /**
     * Returns true if end-to-end encryption is enabled
     */
    val isE2eeEnabled: Boolean
        get() = e2eeEnabled

    /**
     * Returns true if AI/ML features are enabled
     */
    val hasAiFeatures: Boolean
        get() = textEmbeddingModel != null

    /**
     * Returns the display name for the graph
     */
    val displayName: String
        get() = name

    /**
     * Returns true if the graph needs garbage collection
     */
    val needsGc: Boolean
        get() = lastGcAt?.let {
            val daysSinceGc = (Instant.fromEpochMilliseconds(System.currentTimeMillis()) - it).inWholeDays
            daysSinceGc > 30 // GC needed if more than 30 days
        } ?: true

    companion object {
        const val DEFAULT_SCHEMA_VERSION = "65.18"

        /**
         * Creates a new graph with default settings
         */
        fun create(
            name: String,
            rootPath: String,
            author: String? = null
        ): Graph {
            return Graph(
                uuid = generateUuid(),
                name = name,
                createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                rootPath = rootPath,
                author = author
            )
        }

        private fun generateUuid(): String {
            // Simple UUID generation for demo - in real implementation,
            // use proper UUID generation library
            return "00000000-0000-0000-0000-000000000001"
        }
    }
}

/**
 * Represents a File entity associated with the graph.
 *
 * Files can be markdown pages, images, PDFs, or other assets
 * that are part of the knowledge graph.
 */
@Serializable
data class File(
    @SerialName("path")
    val path: String,

    @SerialName("content")
    val content: String,

    @SerialName("created_at")
    val createdAt: Instant,

    @SerialName("last_modified_at")
    val lastModifiedAt: Instant,

    @SerialName("size")
    val size: Long,

    @SerialName("checksum")
    val checksum: String? = null,

    @SerialName("mime_type")
    val mimeType: String? = null
) {
    init {
        require(path.isNotBlank()) { "File path cannot be blank" }
        require(size >= 0) { "File size cannot be negative" }
        require(!path.contains("..")) { "File path contains directory traversal" }
        checksum?.let {
            require(it.matches(Regex("^[a-f0-9]{32,128}$"))) { "Invalid checksum format" }
        }
    }

    /**
     * Returns the file extension (without the dot)
     */
    val extension: String?
        get() = path.substringAfterLast('.', "").takeIf { it.isNotEmpty() }

    /**
     * Returns the file name without path
     */
    val fileName: String
        get() = path.substringAfterLast('/').substringAfterLast('\\')

    /**
     * Returns true if this is a markdown file
     */
    val isMarkdown: Boolean
        get() = extension?.lowercase() in listOf("md", "markdown")

    /**
     * Returns true if this is an image file
     */
    val isImage: Boolean
        get() = mimeType?.startsWith("image/") == true ||
                extension?.lowercase() in listOf("jpg", "jpeg", "png", "gif", "svg", "webp")

    /**
     * Returns true if this is a PDF file
     */
    val isPdf: Boolean
        get() = mimeType == "application/pdf" || extension?.lowercase() == "pdf"
}