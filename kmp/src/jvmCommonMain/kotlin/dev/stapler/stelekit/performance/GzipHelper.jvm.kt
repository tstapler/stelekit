package dev.stapler.stelekit.performance

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

actual fun gzipBytes(data: ByteArray): ByteArray? = try {
    val out = ByteArrayOutputStream(data.size / 2 + 64)
    GZIPOutputStream(out).use { it.write(data) }
    out.toByteArray()
} catch (_: Exception) {
    null
}
