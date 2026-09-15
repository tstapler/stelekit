package dev.stapler.stelekit.asset

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

    fun resolvePath(graphRoot: String, subfolder: String, filename: String): String =
        "$graphRoot/assets/$subfolder/$filename"

    fun relativeMarkdownPath(subfolder: String, filename: String): String =
        "../assets/$subfolder/$filename"
}
