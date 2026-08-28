package dev.stapler.stelekit.platform

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.db.FileRegistry
import dev.stapler.stelekit.platform.testsupport.FakeExternalStorageProvider
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reproduces the user-reported bug: journal/page markdown files added to the synced graph
 * directory by another app (a sync client) on Android sometimes fail to load, or take a long
 * time to be discovered, because SAF/DocumentsProvider directory-listing and single-document
 * existence queries can lag behind a write made by another process.
 *
 * Uses [FakeExternalStorageProvider] — a fake `DocumentsProvider` that models this eventual
 * consistency explicitly (ground truth vs. indexed/visible) — registered under the same
 * `com.android.externalstorage.documents` authority the app's `saf://` paths already assume
 * (see [PlatformFileSystemSafTest]).
 *
 * These tests assert the CURRENT (buggy) behavior during the lag window and the CURRENT
 * (correct, self-healing) behavior once indexing catches up. They are expected to start
 * failing once a real fix (e.g. a fallback parent-listing check, or a retry) lands — that is
 * the point: they pin down today's behavior so a future fix has to consciously change them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SafIndexingLagReproductionTest {

    private lateinit var context: Context
    private lateinit var fs: PlatformFileSystem
    private lateinit var provider: FakeExternalStorageProvider

    // Tree URI intentionally left unregistered on the PlatformFileSystem instance (no stored
    // SAF permission) — parseDocumentUri() falls back to parsing the tree URI straight out of
    // the saf:// path itself, exactly like the existing PlatformFileSystemSafTest fixtures.
    private val treeUri: Uri =
        Uri.parse("content://com.android.externalstorage.documents/tree/" + Uri.encode("primary:test-graph"))
    private val rootDocId = "primary:test-graph"
    private val graphSafPath = PlatformFileSystem.toSafRoot(treeUri)
    private val journalsDocId = "$rootDocId/journals"
    private val journalsPath = "$graphSafPath/journals"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        fs = PlatformFileSystem().apply { init(context) }
        provider = Robolectric.buildContentProvider(FakeExternalStorageProvider::class.java)
            .create("com.android.externalstorage.documents")
            .get()
        provider.addDirectory(rootDocId, "pages")
        provider.addDirectory(rootDocId, "journals")
    }

    // (a) Baseline sanity — proves the fake provider itself works end-to-end, not just
    // always-empty like the pre-existing no-registered-provider SAF tests.
    @Test
    fun `baseline - immediately visible file is found by fileExists and listFiles`() {
        provider.addFileVisible(journalsDocId, "2026_08_22.md", content = "- baseline entry")
        val filePath = "$journalsPath/2026_08_22.md"

        assertTrue(fs.fileExists(filePath), "immediately-visible file should exist")
        assertEquals(listOf("2026_08_22.md"), fs.listFiles(journalsPath))
    }

    // (b) Repro: fileExists() during the lag window — feeds directly into
    // GraphLoader.resolvePageFilePath() -> "Page has no file path and could not be found on disk".
    @Test
    fun `repro - fileExists returns false while file is pending indexing`() {
        provider.addFileHidden(journalsDocId, "2026_08_23.md", content = "- written by sync client")
        val filePath = "$journalsPath/2026_08_23.md"

        assertFalse(fs.fileExists(filePath), "a file the provider hasn't indexed yet must read as absent")
    }

    // (c) Repro: listFiles()/listDirectories() during the lag window.
    @Test
    fun `repro - listFiles omits file while it is pending indexing`() {
        provider.addFileHidden(journalsDocId, "2026_08_23.md", content = "- written by sync client")

        assertEquals(emptyList(), fs.listFiles(journalsPath))
    }

    // (d) Recovery / self-heals after indexing catches up — same PlatformFileSystem instance,
    // no re-init. Proves this is a transient window, not a permanent loss.
    @Test
    fun `recovery - fileExists and listFiles self-heal once the provider catches up`() {
        provider.addFileHidden(journalsDocId, "2026_08_23.md", content = "- written by sync client")
        val filePath = "$journalsPath/2026_08_23.md"
        assertFalse(fs.fileExists(filePath))
        assertEquals(emptyList(), fs.listFiles(journalsPath))

        provider.advanceIndex()

        assertTrue(fs.fileExists(filePath), "file must become visible once the provider indexes it")
        assertEquals(listOf("2026_08_23.md"), fs.listFiles(journalsPath))
    }

    // (e) Repro through FileRegistry.detectChanges — the real, non-mocked watcher code path
    // closest to the user-visible bug. First poll must NOT see the file as a new-file
    // candidate at all; a second poll after the provider catches up must discover it.
    @Test
    fun `repro through FileRegistry - new file invisible on first poll, discovered once indexed`() = runTest {
        val registry = FileRegistry(fs)
        // Establish a baseline scan while the directory is empty, mirroring a prior watcher cycle.
        registry.scanDirectory(journalsPath)

        provider.addFileHidden(journalsDocId, "2026_08_23.md", content = "- written by sync client")

        val firstPoll = registry.detectChanges(journalsPath)
        assertTrue(
            firstPoll.newFiles.none { it.entry.fileName == "2026_08_23.md" },
            "a not-yet-indexed file must not appear as a new-file candidate on this poll",
        )

        provider.advanceIndex()

        val secondPoll = registry.detectChanges(journalsPath)
        assertTrue(
            secondPoll.newFiles.any { it.entry.fileName == "2026_08_23.md" },
            "once the provider catches up, the next poll must discover the file as new",
        )
    }
}
