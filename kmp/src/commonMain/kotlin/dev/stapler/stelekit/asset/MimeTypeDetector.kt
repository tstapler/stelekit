package dev.stapler.stelekit.asset

object MimeTypeDetector {
    fun detect(bytes: ByteArray, filename: String): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return "image/jpeg"
        }
        if (bytes.size >= 4 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        ) {
            return "image/png"
        }
        if (bytes.size >= 4 &&
            bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte()
        ) {
            return "application/pdf"
        }
        // MP4/M4V: check for ftyp at offset 4 (bytes 4-7)
        if (bytes.size >= 8 &&
            bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() &&
            bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()
        ) {
            return "video/mp4"
        }
        // ZIP (also covers DOCX, XLSX, etc.)
        if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            return "application/zip"
        }
        // GIF
        if (bytes.size >= 3 &&
            bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()
        ) {
            return "image/gif"
        }
        // WebP
        if (bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        ) {
            return "image/webp"
        }
        return fromExtension(filename.substringAfterLast('.', "").lowercase())
    }

    private fun fromExtension(ext: String): String = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "svg" -> "image/svg+xml"
        "bmp" -> "image/bmp"
        "pdf" -> "application/pdf"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        "doc", "docx" -> "application/msword"
        "xls", "xlsx" -> "application/vnd.ms-excel"
        "ppt", "pptx" -> "application/vnd.ms-powerpoint"
        "txt" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "zip" -> "application/zip"
        "tar" -> "application/x-tar"
        "gz" -> "application/gzip"
        else -> "application/octet-stream"
    }
}
