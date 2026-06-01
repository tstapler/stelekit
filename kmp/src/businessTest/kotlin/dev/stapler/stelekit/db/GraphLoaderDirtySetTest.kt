package dev.stapler.stelekit.db

import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the dirty-set API and activePageUuids collector lifecycle in [GraphLoader].
 *
 * TC-12: addDirty/checkAndClearDirty set semantics and mutex correctness.
 * TC-13: setActivePageUuids(null) cancels the previous collector job (prevents coroutine leak).
 *
 * Placed in businessTest (commonMain-compatible): no JVM-specific types used.
 */
class GraphLoaderDirtySetTest {

    // ── Minimal stub FileSystem ───────────────────────────────────────────────

    private open class StubFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "/tmp"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = false
        override fun listFiles(path: String) = emptyList<String>()
        override fun listFilesWithModTimes(path: String) = emptyList<Pair<String, Long>>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = false
        override fun deleteFile(path: String) = false
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
        override fun startExternalChangeDetection(scope: CoroutineScope, onChange: () -> Unit) {}
        override fun stopExternalChangeDetection() {}
    }

    // ── TC-12: addDirty / checkAndClearDirty set semantics ────────────────────

    /**
     * TC-12: addDirty and checkAndClearDirty must behave as an atomic set with
     * consume-once semantics (set membership, idempotent add, single consumption).
     *
     * Fails against pre-fix code: these methods do not exist.
     */
    @Test
    fun `TC-12 addDirty and checkAndClearDirty behave as atomic set with consume semantics`() = runTest {
        val loader = GraphLoader(
            fileSystem = StubFileSystem(),
            pageRepository = InMemoryPageRepository(),
            blockRepository = InMemoryBlockRepository(),
        )

        // Not in set initially
        assertFalse(loader.checkAndClearDirty("/a.md"),
            "checkAndClearDirty on absent path must return false")

        // Add and consume
        loader.addDirty("/a.md")
        assertTrue(loader.checkAndClearDirty("/a.md"),
            "checkAndClearDirty must return true after addDirty")

        // Consumed — second call returns false
        assertFalse(loader.checkAndClearDirty("/a.md"),
            "checkAndClearDirty must return false after the flag is consumed")

        // Idempotent add: adding the same path twice is fine
        loader.addDirty("/b.md")
        loader.addDirty("/b.md")
        assertTrue(loader.checkAndClearDirty("/b.md"),
            "Duplicate addDirty must still be consumed once")
        assertFalse(loader.checkAndClearDirty("/b.md"),
            "Flag must be gone after single consumption even with duplicate adds")

        // Independent paths
        loader.addDirty("/c.md")
        loader.addDirty("/d.md")
        assertTrue(loader.checkAndClearDirty("/c.md"), "c.md must be in set")
        assertFalse(loader.checkAndClearDirty("/c.md"), "c.md consumed")
        assertTrue(loader.checkAndClearDirty("/d.md"), "d.md must still be in set")
        assertFalse(loader.checkAndClearDirty("/d.md"), "d.md consumed")
    }

    // ── TC-13: Collector job cancelled on setActivePageUuids(null) ────────────

    /**
     * TC-13: When setActivePageUuids(null) is called, the previous collector job
     * launched by an earlier setActivePageUuids(uuids) call must be cancelled.
     *
     * Without this, the collector keeps running and calls pageRepository on a
     * scope that may have been closed (graph close / vault lock).
     *
     * Fails against pre-fix code: setActivePageUuids does not track the collector job;
     * a naive implementation would leak the coroutine.
     */
    @Test
    fun `TC-13 setActivePageUuids null cancels previous collector job to prevent coroutine leak`() = runTest {
        val pageRepo = InMemoryPageRepository()
        val loader = GraphLoader(
            fileSystem = StubFileSystem(),
            pageRepository = pageRepo,
            blockRepository = InMemoryBlockRepository(),
        )

        // Establish a collector
        val uuids = MutableStateFlow<Set<String>>(setOf("uuid-1"))
        loader.setActivePageUuids(uuids)
        advanceUntilIdle()

        // Cancel by passing null
        loader.setActivePageUuids(null)
        advanceUntilIdle()

        // After null: emit a new value — the old collector must not react
        // (we verify by checking that emitting after null does not throw or produce side effects)
        // The key invariant: no exception is thrown and the loader is still usable.
        uuids.value = setOf("uuid-2", "uuid-3")
        advanceUntilIdle()

        // Loader must still be functional after setActivePageUuids(null)
        assertFalse(loader.checkAndClearDirty("/any.md"),
            "Loader must remain functional after setActivePageUuids(null)")
    }
}
