package dev.stapler.stelekit.util

object FileUtils {
    private val RESERVED_WINDOWS_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    /**
     * Sanitizes a page title to be safe for filenames across platforms (Android, Windows, etc.).
     * 
     * Replaces reserved characters with their percent-encoded equivalents:
     * - / -> %2F (Directory separator)
     * - : -> %3A (Reserved in Windows paths)
     * - \ -> %5C (Windows path separator)
     * - * -> %2A (Wildcard)
     * - ? -> %3F (Wildcard)
     * - " -> %22 (Reserved)
     * - < -> %3C (Redirection)
     * - > -> %3E (Redirection)
     * - | -> %7C (Pipe)
     * 
     * Also handles Windows reserved names and trailing dots/spaces.
     */
    fun sanitizeFileName(name: String): String {
        // 1. Basic character replacement
        var sanitized = name
            .replace("%", "%25") // Encode % first to avoid double-encoding
            .replace("/", "%2F")
            .replace(":", "%3A")
            .replace("\\", "%5C")
            .replace("*", "%2A")
            .replace("?", "%3F")
            .replace("\"", "%22")
            .replace("<", "%3C")
            .replace(">", "%3E")
            .replace("|", "%7C")

        // 2. Handle Windows reserved names (case-insensitive)
        if (RESERVED_WINDOWS_NAMES.contains(sanitized.uppercase())) {
            sanitized += "_"
        }

        // 3. Handle trailing dots and spaces (Windows restriction)
        if (sanitized.endsWith(" ") || sanitized.endsWith(".")) {
             val lastChar = sanitized.last()
             sanitized = sanitized.dropLast(1) + if (lastChar == ' ') "%20" else "%2E"
        }

        return sanitized.ifEmpty { "Untitled" }
    }

    /**
     * Decodes a sanitized filename back to the original page title.
     * Reverses the percent-encoding applied by sanitizeFileName.
     * 
     * Handles standard percent-encoding (%XX).
     * Note: This implementation focuses on the specific characters encoded by sanitizeFileName
     * and assumes UTF-8 compatibility for standard URL encoding if present.
     */
    fun decodeFileName(name: String): String {
        val sb = StringBuilder()
        var i = 0
        val len = name.length
        while (i < len) {
            val c = name[i]
            if (c == '%' && i + 2 < len) {
                val hex = name.substring(i + 1, i + 3)
                try {
                    // Parse hex to int
                    val code = hex.toInt(16)
                    // Check if it's a valid ASCII char we likely encoded
                    // Simple cast to Char works for ASCII (0-127) and extended Latin-1 (0-255)
                    sb.append(code.toChar())
                    i += 3
                    continue
                } catch (e: NumberFormatException) {
                    // Not valid hex, treat as literal %
                }
            }
            sb.append(c)
            i++
        }
        
        // Remove Windows trailing underscore if it looks like a reserved name was sanitized
        var decoded = sb.toString()
        // If we appended '_', removing it is ambiguous unless we know it was reserved.
        // e.g. "CON_" -> "CON". "CON_TEXT" -> "CON_TEXT".
        // sanitizeFileName adds "_" only if name IS reserved.
        // So if decoded name is "CON_", we should revert to "CON"? 
        // But user might have named page "CON_".
        // Ideally, we shouldn't strip it blindly. 
        // However, Logseq usually treats filename as source of truth.
        // If "CON_" exists, page name is "CON_".
        // If we map "CON" -> "CON_", then reading "CON_" -> "CON_" is safe/stable.
        // It just means you can't have a page named "CON" exactly in the UI, it becomes "CON_".
        // This is a common trade-off. I will NOT attempt to strip the underscore to avoid ambiguity.
        
        // Handle trailing encoded dots/spaces?
        // My decoder handles %20 -> ' ' and %2E -> '.'.
        // So "Page%20" decodes to "Page ".
        // This restores the original trailing space.
        
        return decoded
    }
}
