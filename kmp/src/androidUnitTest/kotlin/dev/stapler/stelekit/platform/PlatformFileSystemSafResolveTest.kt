package dev.stapler.stelekit.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [PlatformFileSystem.resolveSafToRealPath].
 *
 * Uses sdk=30 so API-30-only paths (StorageManager.storageVolumes) are available.
 * isStorageManager is injectable because Robolectric cannot shadow
 * Environment.isExternalStorageManager().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PlatformFileSystemSafResolveTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ─── Guard conditions ─────────────────────────────────────────────────────

    @Test
    fun `returns null for non-saf path`() {
        assertNull(
            PlatformFileSystem.resolveSafToRealPath(
                "/storage/emulated/0/personal-wiki", context, isStorageManager = { true },
            )
        )
    }

    @Test
    fun `returns null when storage manager permission not granted`() {
        val saf = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Apersonal-wiki"
        assertNull(
            PlatformFileSystem.resolveSafToRealPath(saf, context, isStorageManager = { false })
        )
    }

    @Test
    fun `returns null for malformed saf uri`() {
        assertNull(
            PlatformFileSystem.resolveSafToRealPath(
                "saf://not%20a%20valid%20content%20uri", context, isStorageManager = { true },
            )
        )
    }

    @Test
    fun `returns null for saf uri with no colon in doc id`() {
        // getTreeDocumentId returns "novolume" with no ':' — colonIdx < 0 path
        val saf = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fnovolume"
        assertNull(
            PlatformFileSystem.resolveSafToRealPath(saf, context, isStorageManager = { true })
        )
    }

    // ─── Happy path — primary volume ─────────────────────────────────────────

    @Test
    fun `resolves primary volume saf root to real path`() {
        val fakeRoot = kotlin.io.path.createTempDirectory().toFile()
        ShadowEnvironment.setExternalStorageDirectory(fakeRoot.toPath())

        val saf = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Apersonal-wiki"
        val result = PlatformFileSystem.resolveSafToRealPath(saf, context, isStorageManager = { true })

        assertEquals("${fakeRoot.absolutePath}/personal-wiki", result)
    }

    @Test
    fun `resolves primary volume saf path with sub-directory`() {
        val fakeRoot = kotlin.io.path.createTempDirectory().toFile()
        ShadowEnvironment.setExternalStorageDirectory(fakeRoot.toPath())

        // saf://{encodedTreeUri}/{relativePath}
        // tree = content://…/tree/primary:personal-wiki, relative = logseq/pages/foo.md
        val encodedTree = "content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Apersonal-wiki"
        val saf = "saf://$encodedTree/logseq/pages/foo.md"
        val result = PlatformFileSystem.resolveSafToRealPath(saf, context, isStorageManager = { true })

        assertEquals("${fakeRoot.absolutePath}/personal-wiki/logseq/pages/foo.md", result)
    }

    @Test
    fun `resolves flat primary volume path with no subdirectory in doc id`() {
        val fakeRoot = kotlin.io.path.createTempDirectory().toFile()
        ShadowEnvironment.setExternalStorageDirectory(fakeRoot.toPath())

        val saf = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3ADocuments"
        val result = PlatformFileSystem.resolveSafToRealPath(saf, context, isStorageManager = { true })

        assertEquals("${fakeRoot.absolutePath}/Documents", result)
    }
}
