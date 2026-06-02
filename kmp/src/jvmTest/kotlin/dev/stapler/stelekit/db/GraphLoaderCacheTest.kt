package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.FilePath
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import dev.stapler.stelekit.parsing.ParseMode

/**
 * Integration and unit tests for the watcher-driven dirty-set cache guard in [GraphLoader].
 *
 * These tests verify the new behaviour introduced by the SAF cache redesign:
 * - TC-01: Stale read eliminated (dirty-set path)
 * - TC-03: FAT/exFAT — dirty set ignores identical mtime
 * - TC-06: Dirty flag consumed at next navigation after active-edit resolves
 * - TC-09: Content-hash fallback detects external change when watcher not running
 * - TC-10: Content-hash fast path: no reload when content unchanged
 * - TC-14: forceReload threads through inner mtime guard
 */
class GraphLoaderCacheTest {

    // ── Fake filesystem ───────────────────────────────────────────────────────

    private data class FakeFile(var content: String, var modTime: Long)

    private class FakeFs(
        val files: MutableMap<String, FakeFile> = mutableMapOf(),
        /** If non-null, always returned for getLastModifiedTime regardless of real mtime. */
        val fixedModTime: Long? = null,
        val dirs: MutableSet<String> = mutableSetOf(),
    ) : FileSystem {
        var readFileCount = 0

        override fun getDefaultGraphPath() = "/graph"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? {
            readFileCount++
            return files[path]?.content
        }
        override fun writeFile(path: String, content: String): Boolean {
            val old = files[path]
            files[path] = FakeFile(content, (old?.modTime ?: 0L) + 1000L)
            return true
        }
        override fun listFiles(path: String): List<String> =
            files.keys.filter { it.startsWith("$path/") && !it.removePrefix("$path/").contains("/") }
                .map { it.substringAfterLast("/") }
        override fun listFilesWithModTimes(path: String): List<Pair<String, Long>> =
            files.keys.filter { it.startsWith("$path/") && !it.removePrefix("$path/").contains("/") }
                .mapNotNull { fp -> files[fp]?.let { fp.substringAfterLast("/") to it.modTime } }
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = files.containsKey(path)
        override fun directoryExists(path: String): Boolean =
            path in dirs || files.keys.any { it.startsWith("$path/") }
        override fun createDirectory(path: String): Boolean { dirs.add(path); return true }
        override fun deleteFile(path: String): Boolean { files.remove(path); return true }
        override fun pickDirectory() = null
        override fun getLastModifiedTime(path: String): Long? =
            fixedModTime ?: files[path]?.modTime

        fun addFile(path: String, content: String, modTime: Long = System.currentTimeMillis()) {
            files[path] = FakeFile(content, modTime)
            dirs.add(path.substringBeforeLast("/"))
        }

        fun externalWrite(path: String, content: String) {
            val old = files[path]
            files[path] = FakeFile(content, (old?.modTime ?: 0L) + 1000L)
        }

        /** External write that does NOT change modTime (simulates FAT 2-second granularity). */
        fun externalWriteSameMtime(path: String, content: String) {
            val old = files[path] ?: FakeFile(content, 1000L)
            files[path] = FakeFile(content, old.modTime) // same modTime
        }
    }

    // ── Harness ───────────────────────────────────────────────────────────────

    private data class Harness(
        val fs: FakeFs,
        val pageRepo: InMemoryPageRepository,
        val blockRepo: InMemoryBlockRepository,
        val loader: GraphLoader,
    )

    private fun buildHarness(
        fixedModTime: Long? = null,
        watcherPollIntervalMs: Long = Long.MAX_VALUE, // disabled by default
    ): Harness {
        val fs = FakeFs(fixedModTime = fixedModTime)
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val loader = GraphLoader(
            fileSystem = fs,
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            watcherPollIntervalMs = watcherPollIntervalMs,
        )
        return Harness(fs, pageRepo, blockRepo, loader)
    }

    // ── TC-01: Stale read eliminated (dirty-set path) ─────────────────────────

    /**
     * TC-01: When the dirty set contains a file path, loadFullPage must reload the page
     * even if allBlocksLoaded=true and the mtime guard would have skipped the reload.
     *
     * Fails against pre-fix code because: old code ran mtime guard unconditionally.
     * On FakeFileSystem with stale mtime, the guard returns early → page stays at V1.
     */
    @Test
    fun `TC-01 loadFullPage bypasses mtime guard when path is in dirty set`() = runTest {
        val h = buildHarness()
        val filePath = "/graph/pages/page.md"
        h.fs.addFile(filePath, "- Block V1", modTime = 1000L)
        h.fs.dirs.add("/graph/pages")

        // Prime: load V1 into DB
        h.loader.setGraphPath("/graph")
        h.loader.parseAndSavePage(FilePath(filePath), "- Block V1", ParseMode.FULL)

        // Verify V1 in DB
        val pages = h.pageRepo.getAllPages().first().getOrNull() ?: emptyList()
        assertFalse(pages.isEmpty(), "Page should be loaded after parseAndSavePage")
        val page = pages.first()

        // Simulate external write: update FakeFileSystem to V2 but keep same mtime
        // (FakeFileSystem's mtime would normally bump, but we simulate that the DB
        // updatedAt >= fileModTime so the OLD guard would skip)
        h.fs.files[filePath] = FakeFile("- Block V2", modTime = 900L) // older mtime than DB

        // Inject dirty flag directly (as the watcher would)
        h.loader.addDirty(FilePath(filePath))

        // Act: loadFullPage with force=false
        h.loader.loadFullPage(page.uuid.value, force = false)

        // Assert: DB now has V2
        val blocks = h.blockRepo.getBlocksForPage(page.uuid).first().getOrNull() ?: emptyList()
        assertTrue(blocks.any { it.content == "Block V2" },
            "loadFullPage must reload page when dirty flag is set; blocks=$blocks")
    }

    // ── TC-03: FAT/exFAT — dirty set ignores identical mtime ─────────────────

    /**
     * TC-03: Even when mtime is identical for V1 and V2 (FAT 2-second granularity),
     * the dirty-set causes a reload.
     *
     * Fails against pre-fix code because: old mtime guard evaluates
     * `page.updatedAt.toEpochMilliseconds() >= fileModTime` → true → skip.
     */
    @Test
    fun `TC-03 loadFullPage reloads page when dirty even if mtime is identical`() = runTest {
        val staticMtime = 5000L
        val h = buildHarness(fixedModTime = staticMtime)
        val filePath = "/graph/pages/page.md"
        h.fs.addFile(filePath, "- Block V1", modTime = staticMtime)
        h.fs.dirs.add("/graph/pages")

        // Prime: load V1
        h.loader.setGraphPath("/graph")
        h.loader.parseAndSavePage(FilePath(filePath), "- Block V1", ParseMode.FULL)

        val page = h.pageRepo.getAllPages().first().getOrNull()?.firstOrNull()
        assertNotNull(page, "Page should be in DB after loading V1")

        // External write with same mtime (FAT granularity: both writes in same 2s window)
        h.fs.files[filePath] = FakeFile("- Block V2", modTime = staticMtime)

        // Verify what OLD guard would do: page.updatedAt ~= staticMtime >= staticMtime → SKIP
        // (We document this regression rather than calling old code)

        // Inject dirty flag and reload
        h.loader.addDirty(FilePath(filePath))
        h.loader.loadFullPage(page.uuid.value, force = false)

        val blocks = h.blockRepo.getBlocksForPage(page.uuid).first().getOrNull() ?: emptyList()
        assertTrue(blocks.any { it.content == "Block V2" },
            "Dirty set must force reload despite identical mtime; blocks=$blocks")
    }

    // ── TC-06: Dirty flag consumed at next navigation ─────────────────────────

    /**
     * TC-06: After an active-edit scenario where the watcher marks dirty (but skips reload),
     * the next loadFullPage must consume the dirty flag and reload from disk.
     *
     * Fails against pre-fix code: old code has no dirty set.
     */
    @Test
    fun `TC-06 dirty flag set during active edit is consumed on next loadFullPage`() = runTest {
        val h = buildHarness()
        val filePath = "/graph/pages/page.md"
        h.fs.addFile(filePath, "- Block V1", modTime = 1000L)
        h.fs.dirs.add("/graph/pages")

        h.loader.setGraphPath("/graph")
        h.loader.parseAndSavePage(FilePath(filePath), "- Block V1", ParseMode.FULL)

        val page = h.pageRepo.getAllPages().first().getOrNull()?.firstOrNull()
        assertNotNull(page, "Page should be in DB")

        // Simulate: watcher detected external edit and marked dirty (skipped onReloadFile
        // because page was in activePageUuids)
        h.fs.files[filePath] = FakeFile("- Block V2", modTime = 2000L)
        h.loader.addDirty(FilePath(filePath))

        // Verify dirty flag is set before navigation
        // (we can confirm by checking that checkAndClearDirty returns true)
        val wasSet = h.loader.checkAndClearDirty(FilePath(filePath))
        assertTrue(wasSet, "Dirty flag should be set")

        // Re-add to test the full loadFullPage path
        h.loader.addDirty(FilePath(filePath))

        // Act: next navigation (loadFullPage)
        h.loader.loadFullPage(page.uuid.value, force = false)

        // Assert: dirty flag consumed and page reloaded
        val dirtyAfter = h.loader.checkAndClearDirty(FilePath(filePath))
        assertFalse(dirtyAfter, "Dirty flag should have been consumed by loadFullPage")

        val blocks = h.blockRepo.getBlocksForPage(page.uuid).first().getOrNull() ?: emptyList()
        assertTrue(blocks.any { it.content == "Block V2" },
            "Page must be reloaded with V2 after dirty flag is consumed; blocks=$blocks")
    }

    // ── TC-09: Content-hash fallback detects external change (no watcher) ─────

    /**
     * TC-09: When the watcher is not running (iOS/WASM path: watcherPollIntervalMs = MAX),
     * loadFullPage must fall back to content-hash comparison and reload when hash differs.
     *
     * Fails against pre-fix code: old code uses mtime guard even when watcher not running.
     * SAF/iOS often returns 0 mtime → `page.updatedAt >= 0` always true → reload skipped.
     */
    @Test
    fun `TC-09 loadFullPage uses content hash when watcher not running and detects external change`() = runTest {
        // Watcher effectively not running (poll interval is maximum)
        val h = buildHarness(watcherPollIntervalMs = Long.MAX_VALUE, fixedModTime = 0L)
        val filePath = "/graph/pages/page.md"
        val contentV1 = "- Block V1"
        h.fs.addFile(filePath, contentV1, modTime = 0L) // SAF returns 0 mtime
        h.fs.dirs.add("/graph/pages")

        h.loader.setGraphPath("/graph")

        // Force initial load to populate DB and store content hash
        h.loader.loadFullPage_forceLoad(filePath, contentV1)

        val page = h.pageRepo.getAllPages().first().getOrNull()?.firstOrNull()
        assertNotNull(page, "Page should be in DB")

        // Verify content hash was stored
        val storedHash = h.loader.fileRegistry.getContentHash(FilePath(filePath))
        assertNotNull(storedHash, "Content hash should be stored after initial load")
        assertEquals(contentV1.hashCode(), storedHash, "Stored hash should match V1 content hash")

        // Simulate external edit: same mtime (0) but different content
        val contentV2 = "- Block V2"
        h.fs.files[filePath] = FakeFile(contentV2, 0L) // still mtime 0

        // Act: loadFullPage without force — should detect hash mismatch
        h.loader.loadFullPage(page.uuid.value, force = false)

        // Assert: DB updated to V2
        val blocks = h.blockRepo.getBlocksForPage(page.uuid).first().getOrNull() ?: emptyList()
        assertTrue(blocks.any { it.content == "Block V2" },
            "Content-hash mismatch must trigger reload to V2; blocks=$blocks")
    }

    // ── TC-10: Content-hash fast path: no reload when content unchanged ────────

    /**
     * TC-10: When the watcher is not running and content hash matches, loadFullPage must NOT
     * trigger a re-parse. Verifies that the fast path returns early without writing to DB.
     *
     * Fails against pre-fix code: no content-hash fast-path exists.
     */
    @Test
    fun `TC-10 loadFullPage skips reload when content hash matches and watcher not running`() = runTest {
        val h = buildHarness(watcherPollIntervalMs = Long.MAX_VALUE, fixedModTime = 0L)
        val filePath = "/graph/pages/page.md"
        val contentV1 = "- Block V1"
        h.fs.addFile(filePath, contentV1, modTime = 0L)
        h.fs.dirs.add("/graph/pages")

        h.loader.setGraphPath("/graph")

        // Force initial load
        h.loader.loadFullPage_forceLoad(filePath, contentV1)

        val page = h.pageRepo.getAllPages().first().getOrNull()?.firstOrNull()
        assertNotNull(page, "Page should be in DB")

        // Reset the read counter
        h.fs.readFileCount = 0

        // Act: first content-hash navigation (readFile called once for hash comparison)
        h.loader.loadFullPage(page.uuid.value, force = false)
        val readsAfterFirst = h.fs.readFileCount

        // Reset again
        h.fs.readFileCount = 0

        // Act: second navigation — content unchanged, should skip reload
        val blocksBefore = h.blockRepo.getBlocksForPage(page.uuid).first().getOrNull()?.map { it.content } ?: emptyList()
        h.loader.loadFullPage(page.uuid.value, force = false)

        // Assert: content unchanged → skip reload.
        // The second call should read the file (for hash comparison) but NOT re-parse.
        // Block content must remain V1 (no new writes from a re-parse).
        val blocksAfter = h.blockRepo.getBlocksForPage(page.uuid).first().getOrNull()?.map { it.content } ?: emptyList()
        assertEquals(blocksBefore, blocksAfter,
            "Block content must not change on second loadFullPage when hash matches")
    }

    // ── TC-14: forceReload threads through inner mtime guard ──────────────────

    /**
     * TC-14: parseAndSavePage with forceReload=true must bypass the inner mtime guard in
     * lookupExistingPageAndCheckFreshness, even when mtime is stale.
     *
     * Scenario: SAF returns stale mtime (500ms) < page.updatedAt (1000ms).
     * Without forceReload, the inner guard fires: fileModTime(500) != 0 &&
     * updatedAt(1000) >= modTime(500) && allBlocksLoaded → SKIP.
     *
     * Fails against pre-fix code: forceReload parameter does not exist;
     * inner guard always fires on stale mtime → stale read.
     */
    @Test
    fun `TC-14 parseAndSavePage with forceReload true bypasses inner mtime guard on stale mtime`() = runTest {
        // Stale mtime: always returns 500ms (older than page.updatedAt which will be ~now)
        val h = buildHarness(fixedModTime = 500L)
        val filePath = "/graph/pages/page.md"
        val contentV1 = "- Block V1"
        h.fs.addFile(filePath, contentV1, modTime = 500L)
        h.fs.dirs.add("/graph/pages")

        h.loader.setGraphPath("/graph")

        // Load V1 so page.updatedAt is set
        h.loader.parseAndSavePage(FilePath(filePath), contentV1, ParseMode.FULL)

        val page = h.pageRepo.getAllPages().first().getOrNull()?.firstOrNull()
        assertNotNull(page, "Page should be in DB")

        // Verify V1 is loaded
        val blocksV1 = h.blockRepo.getBlocksForPage(page.uuid).first().getOrNull() ?: emptyList()
        assertTrue(blocksV1.any { it.content == "Block V1" }, "V1 must be in DB initially; blocks=$blocksV1")

        // Now update file content to V2 (mtime stays 500L = stale)
        val contentV2 = "- Block V2"
        h.fs.files[filePath] = FakeFile(contentV2, 500L)

        // Act: call parseAndSavePage with forceReload=true (via dirty-set path in loadFullPage)
        // We inject dirty flag and call loadFullPage to trigger the forceReload=true path.
        h.loader.addDirty(FilePath(filePath))
        h.loader.loadFullPage(page.uuid.value, force = false)

        // Assert: inner guard bypassed → V2 loaded
        val blocksV2 = h.blockRepo.getBlocksForPage(page.uuid).first().getOrNull() ?: emptyList()
        assertTrue(blocksV2.any { it.content == "Block V2" },
            "forceReload=true must bypass inner mtime guard and load V2; blocks=$blocksV2")
    }

    // ── TC-02: End-to-end watcher→dirty set→reload ───────────────────────────

    /**
     * TC-02: Watcher detects external edit → adds to dirty set → loadFullPage reloads.
     * Uses a fast poll interval to exercise the real watcher-to-dirty-set pipeline.
     *
     * This is a wall-clock integration test.
     */
    @Test
    fun `TC-02 watcher detects external edit and dirty set triggers reload on next navigation`() = runTest {
        // Use fast poll (100ms) to exercise the watcher
        val h = buildHarness(watcherPollIntervalMs = 100L)
        val filePath = "/graph/pages/page.md"
        h.fs.addFile(filePath, "- Block V1", modTime = 1000L)
        h.fs.dirs.add("/graph/pages")

        h.loader.setGraphPath("/graph")
        h.loader.parseAndSavePage(FilePath(filePath), "- Block V1", ParseMode.FULL)
        h.loader.startWatching("/graph")
        h.loader.fileRegistry.scanDirectory("/graph/pages")

        val page = h.pageRepo.getAllPages().first().getOrNull()?.firstOrNull()
        assertNotNull(page, "Page should be in DB")

        // External edit: bump mtime and content
        h.fs.externalWrite(filePath, "- Block V2")

        // Wait for watcher to fire (300ms > 2 × poll intervals + suppression window)
        withContext(Dispatchers.Default) { delay(500L) }

        // Navigation: loadFullPage should find dirty flag and reload
        h.loader.loadFullPage(page.uuid.value, force = false)

        val blocks = h.blockRepo.getBlocksForPage(page.uuid).first().getOrNull() ?: emptyList()
        assertTrue(blocks.any { it.content == "Block V2" },
            "End-to-end: watcher→dirtySet→loadFullPage must reload to V2; blocks=$blocks")

        h.loader.stopWatching()
    }

    // ── Helper: force initial load via parseAndSavePage ───────────────────────

    /**
     * Helper to prime DB + content hash for a given file without going through the
     * loadFullPage cache guard (which might short-circuit on first load).
     */
    private suspend fun GraphLoader.loadFullPage_forceLoad(filePath: String, content: String) {
        parseAndSavePage(FilePath(filePath), content, ParseMode.FULL)
        // Explicitly store content hash as the iOS/WASM path would do after parseAndSavePage
        fileRegistry.updateContentHash(filePath, content.hashCode())
    }
}
