package dev.stapler.stelekit.asset

import kotlin.jvm.JvmInline

@JvmInline
value class AssetUuid(val value: String)

enum class AssetMediaType {
    IMAGE, PDF, AUDIO, VIDEO, DOCUMENT, FILE,

    /** Sketch drawings and exports: never sent to the cloud image pipeline (no plugin admits this class). */
    DRAWING;

    companion object {
        /** Tolerant decode for stored values; an unknown name (a newer build's class after a downgrade) reads as [FILE]. */
        fun fromStored(value: String): AssetMediaType = entries.firstOrNull { it.name == value } ?: FILE

        fun fromMimeType(mime: String): AssetMediaType = when {
            mime.startsWith("image/") -> IMAGE
            mime == "application/pdf" -> PDF
            mime.startsWith("audio/") -> AUDIO
            mime.startsWith("video/") -> VIDEO
            mime.startsWith("application/") && mime != "application/pdf" -> DOCUMENT
            else -> FILE
        }
    }
}

/** How the caller classifies a file it registers; [DRAWING] forces the [AssetMediaType.DRAWING] class whatever the MIME type. */
enum class AssetIntent { AUTO, DRAWING }

data class AssetEntry(
    val uuid: AssetUuid,
    val filePath: String,
    val relativePath: String,
    val mediaType: AssetMediaType,
    val subfolder: String,
    val tags: List<String>,
    val autoLabels: List<String>,
    val ocrText: String?,
    val cloudDescription: String?,
    val pageUuids: List<String>,
    val sizeBytes: Long,
    val importedAtMs: Long,
    val mlProcessed: Boolean,
    val mlAttemptedAt: Long?,
    val mlFailed: Boolean,
    val contentHash: String?,
    val isOrphan: Boolean,
    val mlTagsSource: String,
)

data class AssetPendingMove(
    val id: Long,
    val assetUuid: AssetUuid,
    val oldFilePath: String,
    val newFilePath: String,
    val oldRelativePath: String,
    val newRelativePath: String,
    val createdAtMs: Long,
)
