package dev.stapler.stelekit.platform

import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PlatformFileSystemUriLogicTest {

    private val externalTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2Flogseq"

    // TC-U-01: Round-trip encode/decode — local storage URI
    @Test
    fun `toSafRoot encodes tree URI correctly`() {
        val treeUri = Uri.parse(externalTreeUri)
        val safRoot = PlatformFileSystem.toSafRoot(treeUri)
        assertTrue(safRoot.startsWith("saf://"))
        assertFalse(safRoot.contains("%2525"), "Must not double-encode")
    }

    // TC-U-02: Round-trip with spaces in folder name
    @Test
    fun `toSafRoot with spaces does not double-encode`() {
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMy%20Notes")
        val safRoot = PlatformFileSystem.toSafRoot(treeUri)
        assertFalse(safRoot.contains("%2520"), "Space must not become %2520 (double-encode)")
    }

    // TC-U-03: Round-trip with Unicode folder name
    @Test
    fun `toSafRoot with unicode folder name is stable`() {
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A%E7%AD%86%E8%A8%98%E6%9C%AC")
        val safRoot1 = PlatformFileSystem.toSafRoot(treeUri)
        val safRoot2 = PlatformFileSystem.toSafRoot(treeUri)
        assertEquals(safRoot1, safRoot2, "toSafRoot must be idempotent")
        assertTrue(safRoot1.startsWith("saf://"))
    }

    // TC-U-06: Malformed saf:// URI — readFile with no slash returns null gracefully (not NPE)
    @Test
    fun `readFile with malformed saf path returns null not crash`() {
        val fs = PlatformFileSystem()
        val result = fs.readFile("saf://noSlashHere")
        assertEquals(null, result, "Malformed saf:// path must return null, not crash")
    }

    // TC-U-08: expandTilde is a no-op for saf:// paths
    @Test
    fun `expandTilde is no-op for saf paths`() {
        val fs = PlatformFileSystem()
        val safPath = "saf://content%3A%2F%2F.../pages/foo.md"
        assertEquals(safPath, fs.expandTilde(safPath))
    }
}
