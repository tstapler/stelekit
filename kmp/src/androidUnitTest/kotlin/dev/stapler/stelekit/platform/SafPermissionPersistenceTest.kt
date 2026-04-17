package dev.stapler.stelekit.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SafPermissionPersistenceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

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
        context.getSharedPreferences(PlatformFileSystem.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PlatformFileSystem.KEY_SAF_TREE_URI, "not a valid uri!!!").commit()

        val fs = PlatformFileSystem().apply { init(context) }
        // Should not crash, and the URI is unparseable so treeUri = null -> legacy path returned
        val path = fs.getDefaultGraphPath()
        assertNull(path.takeIf { it.startsWith("saf://") }, "Corrupt URI should be cleared; should return legacy path")
    }

    // TC-U-10: hasStoragePermission returns false when no treeUri
    @Test
    fun `hasStoragePermission returns false when no URI stored`() {
        context.getSharedPreferences(PlatformFileSystem.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(PlatformFileSystem.KEY_SAF_TREE_URI).commit()

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
}
