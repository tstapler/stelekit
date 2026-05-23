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
            // Each call appends a new GZIP stream. Concatenated GZIP streams are valid
            // per RFC 1952 and readable by gunzip / GZIPInputStream without extra tooling.
            FileOutputStream(file, true).use { fos ->
                GZIPOutputStream(fos).use { gzos ->
                    spans.forEach { span ->
                        gzos.write(json.encodeToString(SerializedSpan.serializer(), span).toByteArray())
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
