package dev.stapler.stelekit.asset

/** Recognises an exported sketch SVG by the marker `SketchSvgWriter` writes into its `<metadata>`. */
object DrawingContentSniffer {
    const val MARKER = "stelekit-sketch-export"
    const val SNIFF_BYTES = 4096

    fun isSketchExport(bytes: ByteArray?, filename: String, mime: String): Boolean {
        if (bytes == null) return false
        if (mime != "image/svg+xml" && !filename.endsWith(".svg", ignoreCase = true)) return false
        val head = bytes.copyOf(minOf(SNIFF_BYTES, bytes.size)).decodeToString()
        return MARKER in head
    }
}
