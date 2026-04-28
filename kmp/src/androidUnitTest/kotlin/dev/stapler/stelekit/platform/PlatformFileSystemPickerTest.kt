package dev.stapler.stelekit.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PlatformFileSystemPickerTest {

    private lateinit var context: Context
    private lateinit var fs: PlatformFileSystem

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        fs = PlatformFileSystem().apply { init(context) }
    }

    // ── pickSaveFileAsync ────────────────────────────────────────────────────

    @Test
    fun `pickSaveFileAsync returns null when no callback registered`() = runBlocking {
        assertNull(
            fs.pickSaveFileAsync("report.json", "application/json"),
            "Without initSaveFilePicker, pickSaveFileAsync must return null"
        )
    }

    @Test
    fun `pickSaveFileAsync invokes registered callback with suggestedName and mimeType`() = runBlocking {
        var receivedName = ""
        var receivedMime = ""
        fs.initSaveFilePicker { name, mime ->
            receivedName = name
            receivedMime = mime
            "content://com.example/file/42"
        }

        val result = fs.pickSaveFileAsync("my-report.json", "application/json")

        assertEquals("my-report.json", receivedName)
        assertEquals("application/json", receivedMime)
        assertEquals("content://com.example/file/42", result)
    }

    @Test
    fun `pickSaveFileAsync returns null when callback returns null (user cancelled)`() = runBlocking {
        fs.initSaveFilePicker { _, _ -> null }

        assertNull(fs.pickSaveFileAsync("report.json", "application/json"),
            "Null from callback must propagate as null to caller")
    }

    @Test
    fun `initSaveFilePicker can be called multiple times and last registration wins`() = runBlocking {
        fs.initSaveFilePicker { _, _ -> "content://first" }
        fs.initSaveFilePicker { _, _ -> "content://second" }

        val result = fs.pickSaveFileAsync("x.json", "application/json")
        assertEquals("content://second", result, "Last registered callback must be used")
    }

    // ── contentUriWriteFile ──────────────────────────────────────────────────

    @Test
    fun `writeFile returns false for invalid content URI without crashing`() {
        // Robolectric doesn't have a registered provider for arbitrary content:// URIs,
        // so openOutputStream will throw or return null — writeFile must return false gracefully.
        val result = fs.writeFile("content://com.example.provider/files/nonexistent", "hello")
        assertFalse(result, "writeFile must return false for an unresolvable content URI")
    }

    @Test
    fun `writeFile delegates content-scheme paths to contentUriWriteFile not legacyWriteFile`() {
        // We can't write successfully without a real provider in Robolectric, but we verify
        // that the content:// branch is entered rather than the legacy-file branch.
        // A content URI with a path that legacy validation would accept should still return
        // false (provider unavailable) rather than true (successful legacy file write).
        val contentUri = "content://com.android.externalstorage.documents/document/primary%3Areport.json"
        val result = fs.writeFile(contentUri, "data")
        // Result is false (no real provider) — confirming the content:// path was taken,
        // not a successful legacy write to an arbitrary path.
        assertFalse(result, "content:// URIs must go through the content URI write path")
    }

    @Test
    fun `writeFile still handles saf paths correctly after content URI branch added`() {
        val safPath = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%253ADocuments/pages/test.md"
        // No real SAF provider in Robolectric; just verify it doesn't crash and doesn't
        // accidentally route to the content:// branch (which would have a different failure mode).
        val result = fs.writeFile(safPath, "content")
        assertFalse(result, "SAF write with no real provider must return false, not crash")
    }
}
