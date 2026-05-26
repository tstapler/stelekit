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

        val tempFile = try {
            File.createTempFile("spans_archive_tmp", null, cacheDir)
        } catch (e: Exception) {
            android.util.Log.w("SpanArchiver", "Failed to create temporary archive file", e)
            return
        }

        try {
            // Streaming copy from existing file (if any) to temp file
            FileOutputStream(tempFile).use { fos ->
                GZIPOutputStream(fos).use { gzos ->
                    val writer = gzos.bufferedWriter(Charsets.UTF_8)
                    
                    if (file.exists()) {
                        try {
                            java.io.FileInputStream(file).use { fis ->
                                java.util.zip.GZIPInputStream(fis).use { gzis ->
                                    gzis.bufferedReader(Charsets.UTF_8).useLines { lines ->
                                        lines.forEach { line ->
                                            writer.write(line)
                                            writer.newLine()
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            // If the existing file is corrupted or unreadable, we ignore it and start fresh
                        }
                    }

                    // Append the new spans
                    spans.forEach { span ->
                        writer.write(json.encodeToString(SerializedSpan.serializer(), span))
                        writer.newLine()
                    }
                    writer.flush()
                }
            }

            // Atomically replace the existing file with the temp file
            if (file.exists()) file.delete()
            if (!tempFile.renameTo(file)) {
                // Fallback if rename fails
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }

            // Post-write check: a single large drain can push the file past MAX_BYTES;
            // delete so the next drain starts fresh rather than appending to an over-limit file.
            if (file.exists() && file.length() > MAX_BYTES) file.delete()
        } catch (e: Exception) {
            android.util.Log.w("SpanArchiver", "Failed to archive spans", e)
            if (tempFile.exists()) tempFile.delete()
        }
    }
}
