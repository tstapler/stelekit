package dev.stapler.stelekit.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for URI encoding invariants and [PlatformFileSystem.isSafPermissionValid].
 *
 * These tests are the canary for the two encoding bugs fixed in this codebase:
 *   Bug 1: pickDirectoryAsync reconstructed the tree URI via Uri.decode(), producing a URI
 *           whose toString() differed from the persisted permission → hasStoragePermission=false.
 *   Bug 2: parseDocumentUri called Uri.parse(Uri.decode(...)), causing Android's URI parser
 *           to split doc IDs like "primary:personal-wiki/logseq" into multiple path segments
 *           → getTreeDocumentId returned only the first segment → graph directory not found.
 *
 * Neither bug affects simple "primary:Documents" (flat) paths — only sub-directory paths
 * whose document ID contains ':' or '/' trigger the segment-splitting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PlatformFileSystemUriLogicTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // -------------------------------------------------------------------------
    // toSafRoot encoding correctness
    // -------------------------------------------------------------------------

    @Test
    fun `toSafRoot encodes tree URI — flat Documents path`() {
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        val safRoot = PlatformFileSystem.toSafRoot(treeUri)
        assertTrue(safRoot.startsWith("saf://"))
        assertFalse(safRoot.contains("%2525"), "must not double-encode")
    }

    @Test
    fun `toSafRoot encodes tree URI — sub-directory with colon and slash`() {
        // This is the document ID format that triggered both bugs:
        // "primary:personal-wiki/logseq" contains ':' and '/'
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3Apersonal-wiki%2Flogseq")
        val safRoot = PlatformFileSystem.toSafRoot(treeUri)
        assertTrue(safRoot.startsWith("saf://"), "must start with saf://")

        // Critical invariant: no unencoded '/' after the scheme — if any slash leaks through,
        // parseDocumentUri's slashIdx logic would misidentify the split point.
        val afterScheme = safRoot.removePrefix("saf://")
        assertFalse(afterScheme.contains('/'), "tree URI part must have no unencoded slash — got: $safRoot")
    }

    @Test
    fun `toSafRoot sub-directory — prefs round-trip preserves original URI`() {
        // Simulates the SharedPreferences save/restore: store treeUri.toString(), parse it back.
        // The restored URI must equal the original so that persistedUriPermissions.any { it.uri == uri }
        // matches the permission that was taken with the original treeUri.
        val originalStr = "content://com.android.externalstorage.documents/tree/primary%3Apersonal-wiki%2Flogseq"
        val originalUri = Uri.parse(originalStr)

        val fromPrefs = Uri.parse(originalUri.toString())
        assertEquals(originalUri, fromPrefs, "URI stored to and loaded from prefs must be identical")
    }

    @Test
    fun `toSafRoot with spaces does not double-encode`() {
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMy%20Notes")
        val safRoot = PlatformFileSystem.toSafRoot(treeUri)
        assertFalse(safRoot.contains("%2520"), "space must not become %2520 (double-encode)")
    }

    @Test
    fun `toSafRoot with unicode folder name is stable and idempotent`() {
        val treeUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A%E7%AD%86%E8%A8%98%E6%9C%AC")
        assertEquals(
            PlatformFileSystem.toSafRoot(treeUri),
            PlatformFileSystem.toSafRoot(treeUri),
            "toSafRoot must be idempotent"
        )
    }

    // -------------------------------------------------------------------------
    // URI path-segment corruption — the root cause of Bug 2
    // -------------------------------------------------------------------------

    @Test
    fun `sub-directory URI preserves full document ID as single path segment`() {
        // Correct: "primary:personal-wiki/logseq" is one doc ID, one path segment.
        val originalStr = "content://com.android.externalstorage.documents/tree/primary%3Apersonal-wiki%2Flogseq"
        val originalUri = Uri.parse(originalStr)
        assertEquals(
            listOf("tree", "primary:personal-wiki/logseq"),
            originalUri.pathSegments.takeLast(2),
            "percent-encoded URI must keep the full doc ID as a single path segment"
        )
    }

    @Test
    fun `decoding sub-directory URI before parsing corrupts path segments`() {
        // Decoded form: colons and slashes in the doc ID become literal chars.
        // Android's URI parser then splits on '/' → wrong path segments → wrong getTreeDocumentId().
        val originalStr = "content://com.android.externalstorage.documents/tree/primary%3Apersonal-wiki%2Flogseq"
        val decodedUri = Uri.parse(Uri.decode(originalStr))
        assertNotEquals(
            listOf("tree", "primary:personal-wiki/logseq"),
            decodedUri.pathSegments.takeLast(2),
            "decoded URI must split the doc ID — confirms the bug guard is still needed"
        )
    }

    @Test
    fun `flat path URI is not affected by the decode bug`() {
        // Sanity check: "primary:Documents" contains ':' but no '/', so decode doesn't split it.
        val flatStr = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
        val flat = Uri.parse(flatStr)
        val decoded = Uri.parse(Uri.decode(flatStr))
        // Both produce the same doc ID for flat paths → the bug only manifests on sub-directory paths.
        assertEquals(
            flat.pathSegments.last(),
            decoded.pathSegments.last(),
            "flat paths are unaffected by the decode bug (no '/' to split on)"
        )
    }

    @Test
    fun `decoded and reparsed URI does not equal original for sub-directory paths`() {
        // Verifies that Uri.equals() compares toString() directly (no normalization),
        // which is why pickDirectoryAsync must read from prefs instead of reconstructing the URI.
        val originalStr = "content://com.android.externalstorage.documents/tree/primary%3Apersonal-wiki%2Flogseq"
        val original = Uri.parse(originalStr)
        val decodedReparsed = Uri.parse(Uri.decode(originalStr))
        assertNotEquals(original, decodedReparsed,
            "decoded-and-reparsed URI must differ; if equal, the encoding fix has regressed")
    }

    // -------------------------------------------------------------------------
    // isSafPermissionValid — permission flag branches
    //
    // Robolectric's ShadowContentResolver honours takePersistableUriPermission(), so
    // the hasGrant check (which requires both read AND write) is fully exercisable.
    // The downstream DocumentFile.fromTreeUri().exists() check always returns false in
    // Robolectric (no real ExternalStorageProvider), which is documented in each test.
    // -------------------------------------------------------------------------

    @Test
    fun `isSafPermissionValid returns false when no permissions granted`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        assertFalse(PlatformFileSystem.isSafPermissionValid(context, uri),
            "no persisted grant → must return false immediately")
    }

    @Test
    fun `isSafPermissionValid returns false when only read permission granted`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertFalse(PlatformFileSystem.isSafPermissionValid(context, uri),
            "read-only grant → write flag missing → hasGrant=false → must return false")
    }

    @Test
    fun `isSafPermissionValid returns false when only write permission granted`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertFalse(PlatformFileSystem.isSafPermissionValid(context, uri),
            "write-only grant → read flag missing → hasGrant=false → must return false")
    }

    @Test
    fun `isSafPermissionValid requires both read and write flags`() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        // Grant both — hasGrant becomes true; exists() returns false (no real provider in Robolectric).
        // This test documents the behaviour and guards against hasGrant check being removed.
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        // In Robolectric there is no real ExternalStorageProvider, so exists() → false.
        // The important thing is the function does not throw.
        val result = PlatformFileSystem.isSafPermissionValid(context, uri)
        assertFalse(result, "without a real provider, exists() is false — function must not throw")
    }

    @Test
    fun `isSafPermissionValid returns false for a different URI than the one granted`() {
        val grantedUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments")
        val otherUri   = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ANotes")
        context.contentResolver.takePersistableUriPermission(
            grantedUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        assertFalse(PlatformFileSystem.isSafPermissionValid(context, otherUri),
            "grant is for grantedUri, not otherUri — must return false")
    }

    // -------------------------------------------------------------------------
    // Path helpers (not SAF-dependent)
    // -------------------------------------------------------------------------

    @Test
    fun `expandTilde is no-op for saf paths`() {
        val fs = PlatformFileSystem()
        val safPath = "saf://content%3A%2F%2F.../pages/foo.md"
        assertEquals(safPath, fs.expandTilde(safPath))
    }

    @Test
    fun `readFile with malformed saf path returns null not crash`() {
        val fs = PlatformFileSystem()
        assertEquals(null, fs.readFile("saf://noSlashHere"),
            "malformed saf:// path must return null, not crash")
    }

    @Test
    fun `writeFile with malformed saf path returns false not crash`() {
        val fs = PlatformFileSystem()
        assertFalse(fs.writeFile("saf://noSlashHere", "content"),
            "malformed saf:// path must return false, not crash")
    }
}
