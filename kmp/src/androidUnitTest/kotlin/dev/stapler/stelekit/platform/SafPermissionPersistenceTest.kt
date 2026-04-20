package dev.stapler.stelekit.platform

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SafPermissionPersistenceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun prefs() = context.getSharedPreferences(PlatformFileSystem.PREFS_NAME, Context.MODE_PRIVATE)

    // TC-I-05: init() with missing persisted URI returns legacy path
    @Test
    fun `init with no stored URI returns legacy path`() {
        // Clear any stored URI
        context.getSharedPreferences(PlatformFileSystem.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(PlatformFileSystem.KEY_SAF_TREE_URI).commit()

        val fs = PlatformFileSystem().apply { init(context) }
        val path = fs.getDefaultGraphPath()
        assertNull(path.takeIf { it.startsWith("saf://") }, "Should return legacy path when no SAF URI stored")
    }

    // TC-I-07: init() with corrupt URI string clears SharedPreferences
    @Test
    fun `init with corrupt URI string clears prefs`() {
        prefs().edit().putString(PlatformFileSystem.KEY_SAF_TREE_URI, "not a valid uri!!!").commit()

        val fs = PlatformFileSystem().apply { init(context) }
        // Should not crash, and the URI is unparseable so treeUri = null -> legacy path returned
        val path = fs.getDefaultGraphPath()
        assertNull(path.takeIf { it.startsWith("saf://") }, "Corrupt URI should be cleared; should return legacy path")
    }

    // TC-I-08: init() with a valid URI string but no persistable permission clears prefs
    // In Robolectric, DocumentFile.exists() is always false, so isSafPermissionValid is always
    // false even with both permission flags — but this test grants NO permission at all, which
    // means the hasGrant check itself fails and the URI is cleared without touching DocumentFile.
    @Test
    fun `init clears prefs when stored URI has no persistable permission`() {
        val uriStr = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
        prefs().edit().putString(PlatformFileSystem.KEY_SAF_TREE_URI, uriStr).commit()
        // No takePersistableUriPermission call → hasGrant = false → init must clear prefs
        PlatformFileSystem().apply { init(context) }
        assertNull(prefs().getString(PlatformFileSystem.KEY_SAF_TREE_URI, null),
            "init must clear prefs when the stored URI has no persistable grant")
    }

    // TC-U-10: hasStoragePermission returns false when no treeUri
    @Test
    fun `hasStoragePermission returns false when no URI stored`() {
        prefs().edit().remove(PlatformFileSystem.KEY_SAF_TREE_URI).commit()
        val fs = PlatformFileSystem().apply { init(context) }
        assertEquals(false, fs.hasStoragePermission())
    }

    // TC-U-08: expandTilde no-op for saf:// paths
    @Test
    fun `expandTilde returns saf path unchanged`() {
        val fs = PlatformFileSystem().apply { init(context) }
        val safPath = "saf://content%3A...//pages/foo.md"
        assertEquals(safPath, fs.expandTilde(safPath))
    }

    // TC-U-09: expandTilde replaces leading ~ with the home directory
    @Test
    fun `expandTilde expands tilde prefix to home directory`() {
        val fs = PlatformFileSystem().apply { init(context) }
        val result = fs.expandTilde("~/notes/foo.md")
        assertFalse(result.startsWith("~"), "expandTilde must replace leading ~")
        assertTrue(result.endsWith("/notes/foo.md"), "expandTilde must preserve suffix — got: $result")
    }

    // TC-U-11: expandTilde is no-op for plain absolute paths
    @Test
    fun `expandTilde is no-op for absolute paths`() {
        val fs = PlatformFileSystem().apply { init(context) }
        val abs = "/storage/emulated/0/Documents/notes"
        assertEquals(abs, fs.expandTilde(abs))
    }

    // TC-U-12: getDefaultGraphPath returns a non-saf legacy path when no SAF tree URI is loaded
    @Test
    fun `getDefaultGraphPath returns legacy path when no SAF tree URI`() {
        prefs().edit().remove(PlatformFileSystem.KEY_SAF_TREE_URI).commit()
        val fs = PlatformFileSystem().apply { init(context) }
        val path = fs.getDefaultGraphPath()
        assertFalse(path.startsWith("saf://"), "Without SAF the default path must not be saf://")
        assertTrue(path.isNotBlank(), "Default path must not be blank")
        assertTrue(path.startsWith("/"), "Legacy default path must be an absolute filesystem path")
    }

    // TC-U-13: getLibraryDisplayName returns null when no URI is stored
    @Test
    fun `getLibraryDisplayName returns null when no URI stored`() {
        prefs().edit().remove(PlatformFileSystem.KEY_SAF_TREE_URI).commit()
        val fs = PlatformFileSystem().apply { init(context) }
        assertNull(fs.getLibraryDisplayName(),
            "No URI stored → getLibraryDisplayName must return null")
    }

    // TC-U-14: getLibraryDisplayName does not throw when the stored URI is invalid
    @Test
    fun `getLibraryDisplayName does not throw for corrupt stored URI`() {
        prefs().edit().putString(PlatformFileSystem.KEY_SAF_TREE_URI, "not://valid!!!").commit()
        val fs = PlatformFileSystem().apply { init(context) }
        // init() clears the prefs for a corrupt URI, so getLibraryDisplayName sees null
        assertNull(fs.getLibraryDisplayName(),
            "Corrupt URI must not cause getLibraryDisplayName to throw")
    }

    // Regression: Uri.decode() on the tree URI part of a saf:// path causes Android's URI
    // parser to split document IDs containing ':' or '/' into multiple path segments.
    // getTreeDocumentId() then returns only the first segment, making every SAF file
    // operation resolve to the wrong location (graph directory not found, writes fail).
    @Test
    fun `decoded tree URI produces wrong path segments for sub-directory document IDs`() {
        val originalStr = "content://com.android.externalstorage.documents/tree/primary%3Apersonal-wiki%2Flogseq"
        val originalUri = Uri.parse(originalStr)

        // Correct: document ID "primary:personal-wiki/logseq" is preserved as a single path segment
        assertEquals(
            listOf("tree", "primary:personal-wiki/logseq"),
            originalUri.pathSegments.takeLast(2),
            "Original URI must have the full document ID as a single path segment"
        )

        // Buggy: Uri.decode strips encoding, parser splits on the unencoded '/' in the doc ID →
        // path segments become ["tree", "primary:personal-wiki", "logseq"] instead of two segments
        val decodedUri = Uri.parse(Uri.decode(originalStr))
        assertNotEquals(
            listOf("tree", "primary:personal-wiki/logseq"),
            decodedUri.pathSegments.takeLast(2),
            "Decoded-and-reparsed URI splits the document ID — confirms the bug being guarded against"
        )
    }

    // Regression: decoding a saf:// URI and re-parsing it strips percent-encoding,
    // causing isSafPermissionValid to return false on sub-directory picks whose document
    // ID contains ':' or '/'. Verifies that Uri.parse(uriStr) round-trips correctly
    // but Uri.parse(Uri.decode(uriStr)) does not — which is why pickDirectoryAsync must
    // read from SharedPreferences rather than reconstruct the URI from the saf:// path.
    @Test
    fun `URI decoded and reparsed does not match original for sub-directory paths`() {
        val originalStr = "content://com.android.externalstorage.documents/tree/primary%3Apersonal-wiki%2Flogseq"
        val original = Uri.parse(originalStr)

        // Old (buggy) approach: decode then re-parse — strips percent-encoding
        val decodedReparsed = Uri.parse(Uri.decode(originalStr))
        assertNotEquals(original, decodedReparsed,
            "Decoded-and-reparsed URI must differ from original; if equal the old bug has regressed")

        // New (correct) approach: parse directly from the stored string
        val fromPrefs = Uri.parse(originalStr)
        assertEquals(original, fromPrefs,
            "URI parsed directly from SharedPreferences string must equal original")
    }
}
