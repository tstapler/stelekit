// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Epic 8.3, Story 8.3.1: scale and crash-resilience regression test for [GitShadowWorktree].
 *
 * `SyntheticGraphGenerator` (`kmp/src/jvmTest/.../benchmark/SyntheticGraphGenerator.kt`), the
 * plan's cited generator for its `XLARGE` (7 978-page) config, lives in the `jvmTest` source set
 * and is not reachable from `androidUnitTest` (separate KMP source sets, no shared dependency
 * edge). This test builds an equivalent flat synthetic SAF tree directly instead — small
 * deterministic markdown content per file is all `syncFromSafRoot`'s listing/staleness logic
 * needs to exercise the O(repo-size) code path realistically.
 *
 * Scale: 7 978 files, matching `XLARGE` exactly. This full-scale run (two `syncFromSafRoot`
 * passes plus two JGit `add`+`commit` cycles over the same tree) completes in a few seconds
 * under Robolectric — well inside the 60-90s budget called out in this story's task description
 * — so no scale-down was needed.
 *
 * Design choices, both explicitly sanctioned by plan.md Task 8.3.1a's "use your judgment" note:
 * - The "merge" leg is a plain JGit `init` + `add` + `commit` operating directly on
 *   [GitShadowWorktree.worktreeRootPath], not a full `AndroidGitRepository.merge()` (which would
 *   require standing up a bare remote repo and a real clone/fetch just to reach the same
 *   `listRecursive`/`readSafFile` code path this test actually cares about).
 * - The one deliberately O(repo-size) operation in the whole design — the single recursive SAF
 *   listing call per sync (plan.md design decision #5) — is asserted to return the correct
 *   count and to run exactly once per [GitShadowWorktree.syncFromSafRoot] call; it is excluded
 *   from the O(K) SAF-IO bound this test asserts (Task 8.3.1b) because its own cost is
 *   intentionally O(graph), not O(K) — only the read/write *work* the listing feeds into must
 *   stay bounded.
 */
@RunWith(RobolectricTestRunner::class)
class GitShadowWorktreeLargeGraphTest {

    private companion object {
        /** Matches SyntheticGraphGenerator.XLARGE's page count (see class doc). */
        const val FILE_COUNT = 7_978
        /** Files "changed remotely" between the two syncs — must drive an O(K) re-read, not O(FILE_COUNT). */
        const val CHANGED_FILE_COUNT = 20
        /** Files "edited locally" and queued for write-back — must drive an O(K) SAF write, not O(FILE_COUNT). */
        const val WRITE_BACK_FILE_COUNT = 12
        const val BASE_MTIME = 1_700_000_000_000L
        const val MTIME_STEP = 2_000L // comfortably above any FS mtime-resolution truncation
        val SAF_ROOT = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Awiki-large"
    }

    private data class SafEntry(val content: String, val mtime: Long)

    /** Recording [CoroutineExceptionHandler] — mirrors `LargeGraphWarmStartCrashTest`'s uncaught-Throwable recorder. */
    private class RecordingExceptionHandler : CoroutineExceptionHandler {
        val caught = CopyOnWriteArrayList<Throwable>()
        override val key: CoroutineContext.Key<*> get() = CoroutineExceptionHandler
        override fun handleException(context: CoroutineContext, exception: Throwable) {
            caught += exception
        }
    }

    /**
     * Minimal [FileSystem] fake used only by [GitShadowFlushActor] in the write-back leg — counts
     * [writeFile] calls so the test can assert write-back stays O(K). [getLastModifiedTime]
     * always returns null (no live-SAF-mtime concurrent-edit race to simulate here — that path
     * is covered by Epic 8.1/8.2's dedicated flush-actor tests).
     */
    private class RecordingFakeFileSystem : FileSystem {
        val writeFileCallCount = AtomicInteger(0)
        val writtenPaths = CopyOnWriteArrayList<String>()

        override fun getDefaultGraphPath(): String = "/fake"
        override fun expandTilde(path: String): String = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String): Boolean {
            writeFileCallCount.incrementAndGet()
            writtenPaths += path
            return true
        }
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String): Boolean = false
        override fun directoryExists(path: String): Boolean = true
        override fun createDirectory(path: String): Boolean = true
        override fun deleteFile(path: String): Boolean = false
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `full sync-commit-writeback cycle over a large synthetic tree completes with no uncaught Throwable and bounded SAF IO`() {
        // ── Fixture: a flat FILE_COUNT-entry synthetic SAF tree ─────────────────────────────
        val safTree = ConcurrentHashMap<String, SafEntry>()
        for (i in 1..FILE_COUNT) {
            val path = "pages/page-$i.md"
            val content = "- first block of page $i\n- second block with [[page-1]] link\n"
            safTree[path] = SafEntry(content, BASE_MTIME + i * MTIME_STEP)
        }

        val listRecursiveCallCount = AtomicInteger(0)
        val lastListingSize = AtomicInteger(-1)
        val readSafFileCallCount = AtomicInteger(0)

        val listRecursive: suspend (String) -> List<Pair<String, Long>> = {
            listRecursiveCallCount.incrementAndGet()
            val listing = safTree.entries.map { (path, entry) -> path to entry.mtime }
            lastListingSize.set(listing.size)
            listing
        }
        val readSafFile: suspend (String) -> String? = { path ->
            readSafFileCallCount.incrementAndGet()
            safTree[path]?.content
        }

        val shadowKey = GitShadowWorktree.shadowKeyForSafPath(SAF_ROOT)
        val worktreeFileSystem = RecordingFakeFileSystem()
        val worktree = GitShadowWorktree(context, shadowKey, SAF_ROOT)

        // ── State captured inside the launched coroutine, asserted on afterward ─────────────
        var firstSyncListingSize = -1
        var firstSyncReadCount = -1
        var shadowFileCountAfterFirstSync = -1
        var secondSyncListingCallCountDelta = -1
        var secondSyncReadCount = -1
        var flushResults: List<Either<DomainError.GitError, Unit>> = emptyList()

        val handler = RecordingExceptionHandler()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + handler)

        val job = scope.launch {
            // ── Task 8.3.1a: initial full sync from an empty shadow tree ────────────────────
            worktree.syncFromSafRoot(listRecursive, readSafFile)
            firstSyncListingSize = lastListingSize.get()
            firstSyncReadCount = readSafFileCallCount.get()
            shadowFileCountAfterFirstSync = countShadowFiles(worktree.worktreeRootPath)

            // "openGit / merge (or commit)" leg — a direct JGit init+add+commit on the shadow
            // worktree (see class doc for why not a full AndroidGitRepository.merge()).
            val identity = PersonIdent("Test", "test@example.com")
            Git.init().setDirectory(File(worktree.worktreeRootPath)).call().use { git ->
                worktree.disableFileModeTracking(git.repository)
                git.add().addFilepattern(".").call()
                git.commit()
                    .setMessage("initial synthetic import ($FILE_COUNT files)")
                    .setAuthor(identity)
                    .setCommitter(identity)
                    .call()
            }

            // ── Task 8.3.1b: mutate CHANGED_FILE_COUNT files "remotely", re-sync, assert O(K) reads ──
            val changedPaths = (1..CHANGED_FILE_COUNT).map { "pages/page-$it.md" }
            for (path in changedPaths) {
                val old = safTree.getValue(path)
                safTree[path] = SafEntry(old.content + "- externally-edited line\n", old.mtime + 10_000_000L)
            }
            val listCallsBefore = listRecursiveCallCount.get()
            readSafFileCallCount.set(0)
            worktree.syncFromSafRoot(listRecursive, readSafFile)
            secondSyncListingCallCountDelta = listRecursiveCallCount.get() - listCallsBefore
            secondSyncReadCount = readSafFileCallCount.get()

            // "merge touching K files" leg — commit only the K files the second sync actually
            // rewrote in the shadow tree.
            Git.open(File(worktree.worktreeRootPath)).use { git ->
                git.add().addFilepattern(".").call()
                git.commit()
                    .setMessage("merge touching $CHANGED_FILE_COUNT files")
                    .setAuthor(identity)
                    .setCommitter(identity)
                    .call()
            }

            // ── Write-back leg: WRITE_BACK_FILE_COUNT local edits queued and flushed to SAF ──
            val queueFile = File(File(worktree.worktreeRootPath).parentFile, ".writeback-queue-test")
            val queue = GitWriteBackQueue(queueFile)
            val writeBackPaths = (1..WRITE_BACK_FILE_COUNT).map { "pages/page-$it.md" }
            for (path in writeBackPaths) {
                worktree.writeShadowFile(path, "- locally-edited content for $path\n")
                queue.enqueue(path)
            }
            val flushActor = GitShadowFlushActor(worktreeFileSystem, worktree, queue, SAF_ROOT)
            flushResults = flushActor.flush()
        }
        runBlocking { job.join() }

        // ── Assertions (outside the launched coroutine, so a real failure here is a normal ──
        // ── JUnit assertion failure rather than a swallowed CoroutineExceptionHandler entry) ─
        assertTrue(
            handler.caught.isEmpty(),
            "Throwable(s) reached the recording CoroutineExceptionHandler during the " +
                "$FILE_COUNT-file large-graph cycle — on Android this class of escape kills the " +
                "process. Caught: ${handler.caught.map { it::class.simpleName + ": " + it.message }}",
        )

        // Task 8.3.1a: the single recursive listRecursive walk is correct and non-crashing at scale.
        assertEquals(FILE_COUNT, firstSyncListingSize, "First sync's listRecursive walk must enumerate all $FILE_COUNT SAF entries")
        assertEquals(FILE_COUNT, firstSyncReadCount, "First sync must read every file once (shadow tree starts empty)")
        assertEquals(FILE_COUNT, shadowFileCountAfterFirstSync, "Shadow tree must contain all $FILE_COUNT files after the first sync")

        // Task 8.3.1b: listRecursive itself is called exactly once per sync (excluded from the O(K) bound below).
        assertEquals(1, secondSyncListingCallCountDelta, "syncFromSafRoot must call listRecursive exactly once per sync")
        // The mtime-skip logic must keep the *read* work O(K), not O(FILE_COUNT).
        assertEquals(
            CHANGED_FILE_COUNT,
            secondSyncReadCount,
            "Second sync must re-read only the $CHANGED_FILE_COUNT changed files, not all $FILE_COUNT " +
                "(mtime-skip logic regression — syncFromSafRoot is re-reading unchanged files)",
        )

        // The write-back leg's SAF IPC must stay O(K), not O(FILE_COUNT).
        assertEquals(WRITE_BACK_FILE_COUNT, flushResults.size, "flush() must process exactly the $WRITE_BACK_FILE_COUNT queued paths")
        assertTrue(flushResults.all { it.isRight() }, "All $WRITE_BACK_FILE_COUNT queued write-backs must succeed: $flushResults")
        assertEquals(
            WRITE_BACK_FILE_COUNT,
            worktreeFileSystem.writeFileCallCount.get(),
            "Write-back must call FileSystem.writeFile exactly $WRITE_BACK_FILE_COUNT times (O(K)), not O($FILE_COUNT)",
        )
    }

    /** Counts files under [rootPath], excluding `.git/` and the sync manifest. */
    private fun countShadowFiles(rootPath: String): Int {
        var count = 0
        fun walk(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (dir == File(rootPath) && child.name == ".git") continue
                if (child.name == ".sync-manifest.json") continue
                if (child.isDirectory) walk(child) else count++
            }
        }
        walk(File(rootPath))
        return count
    }
}
