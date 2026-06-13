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
            // Construct wrapping stream chain; closing the outermost writer automatically
            // flushes and closes the underlying GZIP and File Output streams.
            val writer = GZIPOutputStream(FileOutputStream(tempFile)).bufferedWriter(Charsets.UTF_8)
            writer.use { w ->
                copyExistingArchive(file, w)

                // Append the new spans
                for (span in spans) {
                    w.write(json.encodeToString(SerializedSpan.serializer(), span))
                    w.newLine()
                }
                w.flush()
            }

            // Atomically replace the existing file with the temp file
            if (file.exists()) file.delete()
            if (!tempFile.renameTo(file)) {
                // Fallback if rename fails
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }

            // Post-write check: a single large drain can push the file past MAX_BYTES
            if (file.exists() && file.length() > MAX_BYTES) file.delete()
        } catch (e: Exception) {
            android.util.Log.w("SpanArchiver", "Failed to archive spans", e)
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun copyExistingArchive(file: File, writer: java.io.BufferedWriter) {
        if (!file.exists()) return
        try {
            // Closing the wrapping BufferedReader closes GZIPInputStream and FileInputStream
            val reader = java.util.zip.GZIPInputStream(java.io.FileInputStream(file))
                .bufferedReader(Charsets.UTF_8)
            reader.use { r ->
                for (line in r.lineSequence()) {
                    writer.write(line)
                    writer.newLine()
                }
            }
        } catch (e: Exception) {
            // If the existing file is corrupted or unreadable, ignore and start fresh
        }
    }
}
