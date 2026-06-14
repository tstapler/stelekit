package dev.stapler.stelekit.asset

object MimeTypeDetector {
    fun detect(bytes: ByteArray, filename: String): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return "image/jpeg"
        }
        if (isPng(bytes)) return "image/png"
        if (isPdf(bytes)) return "application/pdf"
        if (isMp4(bytes)) return "video/mp4"
        // ZIP (also covers DOCX, XLSX, etc.)
        if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            return "application/zip"
        }
        if (isGif(bytes)) return "image/gif"
        if (isWebP(bytes)) return "image/webp"
        return fromExtension(filename.substringAfterLast('.', "").lowercase())
    }

    private fun isPng(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val headerMatch = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()
        val trailerMatch = bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        return headerMatch && trailerMatch
    }

    private fun isPdf(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val firstPair = bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte()
        val secondPair = bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte()
        return firstPair && secondPair
    }

    private fun isMp4(bytes: ByteArray): Boolean {
        // MP4/M4V: check for ftyp at offset 4 (bytes 4-7)
        if (bytes.size < 8) return false
        val firstPair = bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte()
        val secondPair = bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()
        return firstPair && secondPair
    }

    private fun isGif(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        return bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()
    }

    private fun isWebP(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val riffMatch = bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte()
        val webpMatch = bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        return riffMatch && webpMatch
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
