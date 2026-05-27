package dev.stapler.stelekit.db

import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [GraphFileWatcher].
 *
 * Coverage:
 * 1. Suppression mechanism (C13 fix): a subscriber that calls suppress() prevents onReloadFile
 * 2. close() cancels the owned scope (C14 fix)
 * 3. beginGitMerge / endGitMerge: merge-suppressed files are skipped; post-endGitMerge files reload
 *
 * Real-time vs virtual time: GraphFileWatcher owns a Dispatchers.Default scope, so its delays
 * are wall-clock time.  withTimeout / delay inside runTest would use virtual time (instant).
 * All waits that need to synchronise with the watcher use withContext(Dispatchers.Default) so
 * they run against the real clock.
 *
 * Registry priming: FileRegistry starts empty.  Without a prior scanDirectory call the first
 * poll would classify files as NEW (calls onReloadFile directly, skips SharedFlow).  Each test
 * that needs to detect a CHANGED event must call fixture.registry.scanDirectory() after
 * startWatching and before mutating the file.
 */
class GraphFileWatcherTest {

    // ─── helpers ───────────────────────────────────────────────────────────────

    /**
     * Minimal in-memory FileSystem for GraphFileWatcher tests.
     * Supports mod-time tracking (needed for FileRegistry.detectChanges).
     */
    private class WatcherFakeFileSystem : FileSystem {
        private val textFiles = mutableMapOf<String, String>()
        private val modTimes = mutableMapOf<String, Long>()
        private val dirs = mutableSetOf<String>()

        fun addFile(path: String, content: String, modTime: Long = System.currentTimeMillis()) {
            textFiles[path] = content
            modTimes[path] = modTime
            val dir = path.substringBeforeLast("/")
            dirs.add(dir)
        }

        override fun getDefaultGraphPath(): String = "/graph"
        override fun expandTilde(path: String): String = path
        override fun readFile(path: String): String? = textFiles[path]
        override fun writeFile(path: String, content: String): Boolean {
            textFiles[path] = content
            modTimes[path] = System.currentTimeMillis()
            return true
        }
        override fun listFiles(path: String): List<String> {
            val prefix = if (path.endsWith("/")) path else "$path/"
            return textFiles.keys
                .filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains("/") }
                .map { it.removePrefix(prefix) }
        }
        override fun listFilesWithModTimes(path: String): List<Pair<String, Long>> {
            val prefix = if (path.endsWith("/")) path else "$path/"
            return textFiles.keys
                .filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains("/") }
                .mapNotNull { filePath ->
                    val name = filePath.removePrefix(prefix)
                    val mt = modTimes[filePath] ?: return@mapNotNull null
                    name to mt
                }
        }
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String): Boolean = path in textFiles
        override fun directoryExists(path: String): Boolean =
            path in dirs || textFiles.keys.any { it.startsWith("$path/") }
        override fun createDirectory(path: String): Boolean { dirs.add(path); return true }
        override fun deleteFile(path: String): Boolean { textFiles.remove(path); modTimes.remove(path); return true }
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = modTimes[path]
    }

    private data class WatcherFixture(val watcher: GraphFileWatcher, val registry: FileRegistry)

    /**
     * Builds a [GraphFileWatcher] wired to an in-memory filesystem.
     * Returns both the watcher and the registry so tests can prime the registry via
     * [FileRegistry.scanDirectory] before simulating external changes.
     */
    private fun buildWatcher(
        fileSystem: WatcherFakeFileSystem,
        onReloadFile: suspend (filePath: String, content: String) -> Unit,
    ): WatcherFixture {
        val registry = FileRegistry(fileSystem)
        val watcher = GraphFileWatcher(
            fileSystem = fileSystem,
            fileRegistry = registry,
            readFile = { path -> fileSystem.readFile(path) },
            onReloadFile = onReloadFile,
            pollIntervalMs = 50L, // fast polling for tests
        )
        return WatcherFixture(watcher, registry)
    }

    // ─── Test 1: suppression mechanism (C13 fix) ──────────────────────────────

    /**
     * When a subscriber calls suppress() on the emitted [ExternalFileChange], onReloadFile
     * must NOT be called for that file.
     */
    @Test
    fun suppress_preventsReloadFile() = runTest {
        val fileSystem = WatcherFakeFileSystem()
        val graphPath = "/graph"
        val filePath = "$graphPath/pages/Note.md"
        val initialModTime = 1000L
        fileSystem.addFile(filePath, "- Hello\n", initialModTime)

        var reloadCalled = false
        val fixture = buildWatcher(fileSystem) { _, _ -> reloadCalled = true }
        val watcher = fixture.watcher

        watcher.startWatching(graphPath)
        // Prime the registry: without this, the first poll classifies the file as NEW and
        // calls onReloadFile directly, bypassing externalFileChanges entirely.
        fixture.registry.scanDirectory("$graphPath/pages")

        fileSystem.addFile(filePath, "- Changed externally\n", initialModTime + 5000L)

        var eventReceived = false
        val collectJob = launch {
            watcher.externalFileChanges.first { event ->
                eventReceived = true
                event.suppress()
                true
            }
        }

        // withTimeout(3000L) in runTest uses virtual time (expires instantly).
        // withContext(Dispatchers.Default) gives a real-clock timeout so the 50ms watcher poll fires.
        withContext(Dispatchers.Default) {
            withTimeout(3000L) { collectJob.join() }
        }

        assertTrue(eventReceived, "ExternalFileChange should have been emitted")
        assertFalse(reloadCalled, "onReloadFile must not be called when suppress() is invoked")

        watcher.close()
    }

    /**
     * Without suppression, onReloadFile IS called when an external change is detected.
     * Baseline sanity check to confirm the watcher works end-to-end.
     */
    @Test
    fun noSuppress_callsReloadFile() = runTest {
        val fileSystem = WatcherFakeFileSystem()
        val graphPath = "/graph"
        val filePath = "$graphPath/pages/Note.md"
        val initialModTime = 1000L
        fileSystem.addFile(filePath, "- Hello\n", initialModTime)

        var reloadCalled = false
        val fixture = buildWatcher(fileSystem) { _, _ -> reloadCalled = true }
        val watcher = fixture.watcher

        watcher.startWatching(graphPath)
        fixture.registry.scanDirectory("$graphPath/pages")

        fileSystem.addFile(filePath, "- Changed externally\n", initialModTime + 5000L)

        val collectJob = launch {
            watcher.externalFileChanges.first()
        }
        withContext(Dispatchers.Default) {
            withTimeout(3000L) { collectJob.join() }
            // Give the watcher coroutine 300ms real time to proceed past the 200ms suppression window
            delay(300L)
        }

        assertTrue(reloadCalled, "onReloadFile must be called when suppress() is not invoked")

        watcher.close()
    }

    // ─── Test 2: close() cancels the owned scope (C14 fix) ────────────────────

    @Test
    fun close_cancelsOwnedScope() = runTest {
        val fileSystem = WatcherFakeFileSystem()
        val fixture = buildWatcher(fileSystem) { _, _ -> }

        fixture.watcher.startWatching("/graph")
        fixture.watcher.close()
        fixture.watcher.close() // second close must not throw
    }

    /**
     * After close(), no reload events are delivered even if the file changes.
     */
    @Test
    fun close_stopsWatcherJob() = runTest {
        val fileSystem = WatcherFakeFileSystem()
        val graphPath = "/graph"
        val filePath = "$graphPath/pages/Stopped.md"
        fileSystem.addFile(filePath, "- Initial\n", 1000L)

        var reloadCalled = false
        val fixture = buildWatcher(fileSystem) { _, _ -> reloadCalled = true }
        val watcher = fixture.watcher

        watcher.startWatching(graphPath)
        fixture.registry.scanDirectory("$graphPath/pages")
        watcher.close()

        fileSystem.addFile(filePath, "- After close\n", 9999L)

        // Real-time wait — the watcher job is cancelled so no poll should fire
        withContext(Dispatchers.Default) { delay(300L) }

        assertFalse(reloadCalled, "No reload should fire after close()")
    }

    // ─── Test 3: beginGitMerge / endGitMerge ──────────────────────────────────

    /**
     * Files in the merge-suppressed set are NOT reloaded while the merge is in progress.
     */
    @Test
    fun beginGitMerge_suppressesFiles() = runTest {
        val fileSystem = WatcherFakeFileSystem()
        val graphPath = "/graph"
        val filePath = "$graphPath/pages/Merged.md"
        fileSystem.addFile(filePath, "- Original\n", 1000L)

        var reloadCalled = false
        val fixture = buildWatcher(fileSystem) { _, _ -> reloadCalled = true }
        val watcher = fixture.watcher

        watcher.startWatching(graphPath)
        fixture.registry.scanDirectory("$graphPath/pages")
        watcher.beginGitMerge(listOf(filePath))

        fileSystem.addFile(filePath, "- Merged externally\n", 2000L)

        // Real-time wait — give the watcher time to poll; it should suppress the change
        withContext(Dispatchers.Default) { delay(200L) }

        assertFalse(reloadCalled, "Merge-suppressed file must not trigger onReloadFile")

        watcher.close()
    }

    /**
     * After endGitMerge(), files that change again ARE reloaded normally.
     */
    @Test
    fun endGitMerge_restoresNormalBehavior() = runTest {
        val fileSystem = WatcherFakeFileSystem()
        val graphPath = "/graph"
        val filePath = "$graphPath/pages/PostMerge.md"
        fileSystem.addFile(filePath, "- Original\n", 1000L)

        val fixture = buildWatcher(fileSystem) { _, _ -> }
        val watcher = fixture.watcher

        watcher.startWatching(graphPath)
        fixture.registry.scanDirectory("$graphPath/pages")
        watcher.beginGitMerge(listOf(filePath))
        watcher.endGitMerge()

        fileSystem.addFile(filePath, "- Post-merge external change\n", 9000L)

        var eventEmitted = false
        val collectJob = launch {
            watcher.externalFileChanges.first()
            eventEmitted = true
        }
        withContext(Dispatchers.Default) {
            withTimeout(3000L) { collectJob.join() }
        }

        assertTrue(eventEmitted, "ExternalFileChange should fire after endGitMerge()")

        watcher.close()
    }
}
