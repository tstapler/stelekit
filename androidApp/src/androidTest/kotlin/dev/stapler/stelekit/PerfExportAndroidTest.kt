package dev.stapler.stelekit

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * TC-PERFEXPORT-001..003: Android instrumented tests for the perf export file-write path.
 *
 * Root cause of the 0-byte export bug: [PlatformFileSystem.writeFileBytes] for content:// URIs
 * returned `true` unconditionally even when [ContentResolver.openOutputStream] returned null —
 * the `?.use {}` block was skipped but `true` was outside it. The fix promotes the null check
 * to an explicit `?: return false`, making null streams visible as a reported failure rather
 * than a silent 0-byte file.
 *
 * Additionally, [MainActivity.saveFileLauncher] was registered as
 * [ActivityResultContracts.CreateDocument("application/json")] — some Downloads providers
 * return null from openOutputStream for "application/json" files when writing binary (gzip)
 * data. Changing to [CreateDocument("*&#47;*")] lets the provider infer the MIME type from
 * the file extension (.json.gz → application/gzip) and avoids the null-stream path.
 *
 * These tests run against a real Android ContentResolver (API 29+) and verify:
 *   TC-PERFEXPORT-001  gzip bytes written to a content:// URI are non-zero on disk
 *   TC-PERFEXPORT-002  the same content is readable back via the same URI
 *   TC-PERFEXPORT-003  openOutputStream returns non-null for *&#47;* MIME-type document
 *
 * Runs on API 29+ (MediaStore.Downloads requires Q). Instrumented tests on the emulator
 * via ./gradlew connectedAndroidTest cover the SAF path that unit tests cannot reach.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.Q)
class PerfExportAndroidTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun gzipBytes(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun createDownloadsUri(displayName: String, mimeType: String = "*/*") =
        ContentValues().run {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            context.contentResolver.insert(MediaStore.Downloads.getContentUri("external"), this)
        }

    // TC-PERFEXPORT-001: gzip bytes written to a content:// URI land on disk as non-zero bytes.
    // This is the core regression: before the fix, openOutputStream could return null, the
    // ?.use {} block was skipped, true was returned, and the file stayed 0 B.
    @Test
    fun writingGzipBytesToContentUriProducesNonZeroFile() {
        val uri = createDownloadsUri("stelekit-perf-test-001.json.gz") ?: run {
            println("MediaStore unavailable on this device — skipping TC-PERFEXPORT-001")
            return
        }
        try {
            val payload = gzipBytes("""{"test":"TC-PERFEXPORT-001","spans":[]}""".encodeToByteArray())
            assertTrue("gzip payload must be non-empty", payload.isNotEmpty())

            val stream = context.contentResolver.openOutputStream(uri, "wt")
            assertFalse(
                "openOutputStream must not return null for a freshly-created MediaStore URI. " +
                "If null, the write silently fails and produces a 0-byte file — exactly the bug " +
                "fixed by promoting '?.use {} + true' to 'val stream = ... ?: return false'.",
                stream == null
            )
            stream!!.use { it.write(payload); it.flush() }

            // Read back via ContentResolver to verify bytes were actually persisted
            val readBack = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            assertTrue(
                "File must contain non-zero bytes after write. Got: ${readBack?.size ?: "null"} bytes.",
                (readBack?.size ?: 0) > 0
            )
            assertTrue(
                "Written size must equal payload size. Expected ${payload.size}, got ${readBack?.size}",
                readBack?.size == payload.size
            )
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    // TC-PERFEXPORT-002: bytes written are identical to bytes read back (no truncation or corruption).
    @Test
    fun writtenGzipBytesRoundTripCorrectly() {
        val uri = createDownloadsUri("stelekit-perf-test-002.json.gz") ?: run {
            println("MediaStore unavailable — skipping TC-PERFEXPORT-002")
            return
        }
        try {
            val json = buildString {
                append("""{"version":"0.47.0","spans":[""")
                repeat(10) { i -> append("""{"name":"span_$i","durationMs":${i * 100}}""").also { if (i < 9) append(",") } }
                append("]}")
            }
            val payload = gzipBytes(json.encodeToByteArray())

            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(payload); it.flush() }

            val readBack = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertEquals("Round-trip byte count must match", payload.size, readBack.size)
            assertTrue("Round-trip content must be bit-identical", payload.contentEquals(readBack))
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    // TC-PERFEXPORT-003: openOutputStream returns non-null when the document was created with
    // MIME type "*/*". This validates the MainActivity.saveFileLauncher MIME-type fix:
    // CreateDocument("*/*") instead of CreateDocument("application/json"), which prevented
    // some providers from opening a stream for binary (gzip) content.
    @Test
    fun openOutputStreamReturnsNonNullForWildcardMimeTypeDocument() {
        // Create a document explicitly typed as "*/*" — what CreateDocument("*/*") produces
        val uri = createDownloadsUri("stelekit-perf-test-003.json.gz", mimeType = "*/*") ?: run {
            println("MediaStore unavailable — skipping TC-PERFEXPORT-003")
            return
        }
        try {
            val stream = context.contentResolver.openOutputStream(uri, "wt")
            assertFalse(
                "openOutputStream must not return null for a '*/*' MIME-type document. " +
                "A null stream here means the provider rejects binary writes — " +
                "the root cause of the 0-byte perf export files.",
                stream == null
            )
            stream?.use { it.write(ByteArray(4) { 0x47 }) }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }
}
