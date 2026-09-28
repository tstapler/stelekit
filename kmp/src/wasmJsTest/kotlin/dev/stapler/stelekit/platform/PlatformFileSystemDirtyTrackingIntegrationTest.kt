package dev.stapler.stelekit.platform

import dev.stapler.stelekit.git.model.DirtyEntry
import dev.stapler.stelekit.git.model.DirtyOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests exercising the *real* wasmJs `PlatformFileSystem` actual — real OPFS
 * (`navigator.storage.getDirectory()`), real dirty-set-marker scheduler — as opposed to
 * `DirtyTrackingAlgorithmsTest` (commonTest)'s pure-Kotlin double. Runs in headless Chrome via
 * the `wasmJsBrowserTest` Karma/webpack runner (see `WasmBenchmarkTest`'s doc comment for
 * precedent in this repo). Each test uses a fresh, randomly-suffixed graphId so tests don't
 * clobber each other's persisted OPFS state.
 *
 * NOTE: at the time these tests were authored, this sandboxed dev environment had no headless
 * Chrome / puppeteer cache available (`~/.cache/puppeteer` empty, no `chromium`/`google-chrome`
 * binary on PATH), so `./gradlew :kmp:wasmJsBrowserTest` could not actually be executed here.
 * These tests compile against the real `PlatformFileSystem` API but have only been verified by
 * inspection, not by a real browser run — run `./gradlew :kmp:wasmJsBrowserTest` in an
 * environment with a browser available before relying on them as a regression gate.
 */
class PlatformFileSystemDirtyTrackingIntegrationTest {

    private fun freshGraphId(): String = "it-dirty-${Random.nextInt(0, Int.MAX_VALUE)}"

    /** Polls [block] on a real (non-test-scheduler) dispatcher until true or the timeout elapses. */
    private suspend fun awaitCondition(timeoutMs: Long = 2000, stepMs: Long = 25, block: () -> Boolean) {
        var waited = 0L
        while (!block() && waited < timeoutMs) {
            withContext(Dispatchers.Default) { delay(stepMs) }
            waited += stepMs
        }
    }

    /**
     * Repeatedly constructs a fresh [PlatformFileSystem] against [graphId]'s OPFS root and calls
     * [PlatformFileSystem.preload] until the restored dirty set contains [expectedKeys] or the
     * timeout elapses — the only reliable way to assert the marker scheduler's async OPFS write
     * actually landed on disk (as opposed to merely updating the in-memory dirty set).
     */
    private suspend fun awaitReloadedSnapshot(
        graphId: String,
        expectedKeys: Set<String>,
        timeoutMs: Long = 3000,
        stepMs: Long = 50,
    ): Pair<Map<String, DirtyEntry>, Int> {
        var waited = 0L
        while (true) {
            val fs = PlatformFileSystem()
            fs.preload("/stelekit/$graphId")
            val snapshot = fs.getDirtySnapshot()
            if (snapshot.keys.containsAll(expectedKeys) || waited >= timeoutMs) {
                return snapshot to fs.dirtyFileCountFlow.value
            }
            withContext(Dispatchers.Default) { delay(stepMs) }
            waited += stepMs
        }
    }

    @Test
    fun `IT-2_1_1-A writeFile and deleteFile against the real wasmJs actual update dirtyFileCountFlow and getDirtySnapshot`() = runTest {
        val graphId = freshGraphId()
        val fs = PlatformFileSystem()
        fs.preload("/stelekit/$graphId")

        fs.writeFile("/stelekit/$graphId/pages/Foo.md", "# Foo")
        awaitCondition { fs.getDirtySnapshot().containsKey("pages/Foo.md") }

        assertEquals(1, fs.dirtyFileCountFlow.value)
        assertEquals(DirtyOp.WRITE, fs.getDirtySnapshot().getValue("pages/Foo.md").op)

        fs.deleteFile("/stelekit/$graphId/pages/Foo.md")
        awaitCondition { fs.getDirtySnapshot()["pages/Foo.md"]?.op == DirtyOp.DELETE }

        assertEquals(DirtyOp.DELETE, fs.getDirtySnapshot().getValue("pages/Foo.md").op)
        assertEquals(1, fs.dirtyFileCountFlow.value)
    }

    @Test
    fun `IT-2_1_2-A coalesced marker write survives 3 rapid writeFile calls and persists all 3 paths`() = runTest {
        val graphId = freshGraphId()
        val fs = PlatformFileSystem()
        fs.preload("/stelekit/$graphId")

        // Fired back-to-back, before any single marker write is guaranteed to have completed —
        // exercises the coalesce-while-busy scheduler (Task 2.1.2b), not a fixed idle debounce.
        fs.writeFile("/stelekit/$graphId/pages/A.md", "a")
        fs.writeFile("/stelekit/$graphId/pages/B.md", "b")
        fs.writeFile("/stelekit/$graphId/pages/C.md", "c")

        val expected = setOf("pages/A.md", "pages/B.md", "pages/C.md")
        val (restored, count) = awaitReloadedSnapshot(graphId, expected)

        assertTrue(restored.keys.containsAll(expected), "restored=$restored")
        assertEquals(3, count)
    }

    @Test
    fun `IT-2_1_2-B preload restores the in-memory dirty set from a real OPFS marker after a fresh PlatformFileSystem instantiation`() = runTest {
        val graphId = freshGraphId()
        val fs = PlatformFileSystem()
        fs.preload("/stelekit/$graphId")
        fs.writeFile("/stelekit/$graphId/pages/Foo.md", "# Foo")

        val (restored, count) = awaitReloadedSnapshot(graphId, setOf("pages/Foo.md"))

        assertEquals(setOf("pages/Foo.md"), restored.keys)
        assertEquals(1, count)
    }

    @Test
    fun `IT-2_1_2-C preload with a malformed or absent marker starts empty and does not throw`() = runTest {
        // Absent marker: a graphId that has never had anything written to it.
        val absentGraphId = freshGraphId()
        val fsAbsent = PlatformFileSystem()
        fsAbsent.preload("/stelekit/$absentGraphId") // must not throw
        assertEquals(emptyMap(), fsAbsent.getDirtySnapshot())
        assertEquals(0, fsAbsent.dirtyFileCountFlow.value)

        // Malformed marker: seed invalid JSON directly at the marker's OPFS path via the internal
        // opfsWriteFile helper (not PlatformFileSystem.writeFile — going through the public API
        // would itself record a dirty entry for the marker path and immediately overwrite our
        // deliberately-corrupt content with a freshly-serialized valid marker).
        val malformedGraphId = freshGraphId()
        opfsWriteFile("/stelekit/$malformedGraphId/.stele-dirty-set.json", "{ not valid json")

        val reader = PlatformFileSystem()
        reader.preload("/stelekit/$malformedGraphId") // must not throw
        assertEquals(emptyMap(), reader.getDirtySnapshot())
        assertEquals(0, reader.dirtyFileCountFlow.value)
    }
}
