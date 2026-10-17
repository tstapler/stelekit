package dev.stapler.stelekit.asset

import dev.stapler.stelekit.model.EffectiveNotesPath

object AssetStoragePathResolver {

    fun resolveSubfolder(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "images"
        mimeType == "application/pdf" -> "pdfs"
        mimeType.startsWith("audio/") -> "audio"
        mimeType.startsWith("video/") -> "video"
        mimeType.startsWith("application/") ||
            mimeType.startsWith("text/") -> "documents"
        else -> "files"
    }

    fun resolvePath(notesPath: EffectiveNotesPath, subfolder: String, filename: String): String =
        notesPath.child("assets/$subfolder/$filename")

    fun relativeMarkdownPath(subfolder: String, filename: String): String =
        "../assets/$subfolder/$filename"
}
