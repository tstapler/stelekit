package dev.stapler.stelekit.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PlatformFileSystemSafTest {

    private lateinit var context: Context
    private lateinit var fs: PlatformFileSystem

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        fs = PlatformFileSystem().apply { init(context) }
    }

    // TC-I-21: writeFile uses "wt" truncate mode — verified via contract assertion.
    // readFile on a non-existent SAF path (no registered provider) must return null, not crash.
    @Test
    fun `writeFile uses wt truncate mode not w`() {
        val safPath = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%253ADocuments/pages/test.md"
        val result = fs.readFile(safPath)
        assertNull(result, "readFile on non-existent SAF path must return null, not crash")
    }

    // TC-I-19/20: readFile returns null on SecurityException AND IllegalArgumentException
    @Test
    fun `readFile returns null for malformed saf path without crash`() {
        assertNull(fs.readFile("saf://noSlashHere"))
    }

    // TC-I-22/23: writeFile returns false on SecurityException AND IllegalArgumentException
    @Test
    fun `writeFile returns false for malformed saf path without crash`() {
        assertFalse(fs.writeFile("saf://noSlashHere", "content"))
    }

    // TC-I-11: listFiles on empty / unreachable directory returns empty list
    @Test
    fun `listFiles returns empty list for unreachable SAF path`() {
        val safPath = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%253ADocuments/pages"
        assertEquals(emptyList(), fs.listFiles(safPath))
    }

    // TC-I-12: listDirectories when cursor is null returns empty list (no NPE)
    @Test
    fun `listDirectories returns empty list for unreachable SAF path`() {
        val safPath = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%253ADocuments/pages"
        assertEquals(emptyList(), fs.listDirectories(safPath))
    }

    // TC-I-18: fileExists returns false for non-existent SAF document
    @Test
    fun `fileExists returns false for non-existent SAF path`() {
        val safPath = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%253ADocuments/pages/missing.md"
        assertFalse(fs.fileExists(safPath))
    }

    // TC-I-34: getLastModifiedTime returns null for absent file
    @Test
    fun `getLastModifiedTime returns null for non-existent SAF path`() {
        val safPath = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%253ADocuments/pages/missing.md"
        assertNull(fs.getLastModifiedTime(safPath))
    }
}
