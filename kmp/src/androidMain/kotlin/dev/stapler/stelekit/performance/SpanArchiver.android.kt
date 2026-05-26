package dev.stapler.stelekit.performance

import dev.stapler.stelekit.db.DriverFactory
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

actual object SpanArchiver {
    private const val MAX_BYTES = 5 * 1024 * 1024  // 5MB
    private const val FILE_NAME = "spans_archive.jsonl.gz"
    private val json = Json { encodeDefaults = true }

    actual fun archive(spans: List<SerializedSpan>) {
        if (spans.isEmpty()) return
        val cacheDir = DriverFactory.staticContext?.cacheDir ?: return
        val file = File(cacheDir, FILE_NAME)
        if (file.exists() && file.length() > MAX_BYTES) file.delete()

        try {
            val existingLines = mutableListOf<String>()
            if (file.exists()) {
                try {
                    java.io.FileInputStream(file).use { fis ->
                        java.util.zip.GZIPInputStream(fis).use { gzis ->
                            gzis.bufferedReader().useLines { lines ->
                                existingLines.addAll(lines)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // If the existing file is corrupted or unreadable, start fresh
                    file.delete()
                }
            }

            // Append new spans
            spans.forEach { span ->
                existingLines.add(json.encodeToString(SerializedSpan.serializer(), span))
            }

            // Write all lines as a single GZIP stream
            FileOutputStream(file, false).use { fos ->
                GZIPOutputStream(fos).use { gzos ->
                    existingLines.forEach { line ->
                        gzos.write(line.toByteArray())
                        gzos.write('\n'.code)
                    }
                }
            }

            // Post-write check: a single large drain can push the file past MAX_BYTES;
            // delete so the next drain starts fresh rather than appending to an over-limit file.
            if (file.exists() && file.length() > MAX_BYTES) file.delete()
        } catch (e: Exception) {
            android.util.Log.w("SpanArchiver", "Failed to archive spans", e)
        }
    }
}
