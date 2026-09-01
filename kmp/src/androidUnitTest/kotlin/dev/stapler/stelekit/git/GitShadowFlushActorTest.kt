// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.FileSystem
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [GitShadowFlushActor]: draining a [GitWriteBackQueue] to a fake SAF [FileSystem]
 * target, mirroring `ShadowFlushActorTest`'s structure for its sibling
 * `dev.stapler.stelekit.platform.ShadowFlushActor`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GitShadowFlushActorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val safRoot = "saf://root"

    private class FakeFileSystem(
        private val writeResults: Map<String, Boolean> = emptyMap(),
        private val defaultWriteOk: Boolean = true,
    ) : FileSystem {
        val written = mutableMapOf<String, String>()
        private val lastModifiedTimes = mutableMapOf<String, Long>()

        fun setLastModified(path: String, mtime: Long) {
            lastModifiedTimes[path] = mtime
        }

        override fun getDefaultGraphPath() = "saf://root"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String): Boolean {
            val ok = writeResults[path] ?: defaultWriteOk
            if (ok) written[path] = content
            return ok
        }
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = false
        override fun deleteFile(path: String) = false
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = lastModifiedTimes[path]
    }

    /** Only used to satisfy [GitShadowWorktree]'s constructor — unused by the sync-free methods under test. */
    private class InertFileSystem : FileSystem {
        override fun getDefaultGraphPath() = "saf://root"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = false
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = false
        override fun deleteFile(path: String) = false
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    private fun newWorktree(): GitShadowWorktree =
        GitShadowWorktree(context, "flush-key-${System.nanoTime()}", safRoot, InertFileSystem())

    private fun newQueue(): GitWriteBackQueue = GitWriteBackQueue(tempFolder.newFile("queue-${System.nanoTime()}.txt"))

    // ── Task 8.1.2b: happy path ─────────────────────────────────────────────────────────────────

    @Test
    fun `flush writes shadow content to fake SAF target and dequeues on success`() = runBlocking {
        val worktree = newWorktree()
        val queue = newQueue()
        val safPath = "$safRoot/pages/Foo.md"
        val fs = FakeFileSystem(defaultWriteOk = true)
        fs.setLastModified(safPath, 5000L)

        worktree.writeShadowFile("pages/Foo.md", "shadow content")
        queue.enqueue("pages/Foo.md")

        val actor = GitShadowFlushActor(fs, worktree, queue, safRoot)
        val results = actor.flush()

        assertEquals(1, results.size)
        assertTrue(results.single().isRight(), "expected the single flushed path to succeed")
        assertEquals("shadow content", fs.written[safPath])
        assertTrue(queue.isEmpty(), "successfully-flushed path must be dequeued")
        assertEquals(
            5000L,
            worktree.manifestSafMtimeFor("pages/Foo.md"),
            "manifest must be stamped with the post-write SAF mtime so isFresh/ensureFresh don't see it as stale",
        )
    }

    @Test
    fun `flush dequeues without writing when the shadow file is missing`() = runBlocking {
        val worktree = newWorktree()
        val queue = newQueue()
        val fs = FakeFileSystem(defaultWriteOk = true)
        // No writeShadowFile call — simulates a crash between enqueue and the shadow write.
        queue.enqueue("pages/Missing.md")

        val actor = GitShadowFlushActor(fs, worktree, queue, safRoot)
        val results = actor.flush()

        assertEquals(1, results.size)
        assertTrue(results.single().isRight())
        assertTrue(fs.written.isEmpty(), "no SAF write should occur for a missing shadow file")
        assertTrue(queue.isEmpty(), "queue must still drain the entry even when there is nothing to flush")
    }

    // ── Task 8.1.2b: concurrent-edit detection ──────────────────────────────────────────────────

    @Test
    fun `flush returns WorkingTreeConcurrentEditDetected and leaves SAF content unchanged when live SAF mtime is newer than manifest`() = runBlocking {
        val worktree = newWorktree()
        val queue = newQueue()
        val safPath = "$safRoot/pages/Foo.md"
        val fs = FakeFileSystem(defaultWriteOk = true)

        // Manifest records the last-known SAF mtime from a prior SAF->shadow sync.
        worktree.updateManifestEntry("pages/Foo.md", 1000L, "original".encodeToByteArray().size.toLong())
        worktree.writeShadowFile("pages/Foo.md", "local shadow edit")
        queue.enqueue("pages/Foo.md")

        // Live SAF mtime is newer than the manifest — an external app/widget edited the SAF file
        // after the last sync recorded its mtime.
        fs.setLastModified(safPath, 2000L)

        val actor = GitShadowFlushActor(fs, worktree, queue, safRoot)
        val results = actor.flush()

        assertEquals(1, results.size)
        val result = results.single()
        assertTrue(result.isLeft())
        result.fold(
            { error ->
                assertEquals(DomainError.GitError.WorkingTreeConcurrentEditDetected(safPath), error)
            },
            { throw AssertionError("expected Left(WorkingTreeConcurrentEditDetected), got Right") },
        )
        assertNull(fs.written[safPath], "SAF content must be left unchanged when a concurrent edit is detected")
        assertEquals(listOf("pages/Foo.md"), queue.getAll(), "path must remain queued for retry after a detected race")
    }

    // ── Task 8.1.2c: non-race write failure (closes validation.md Gap #7 for this variant) ─────

    @Test
    fun `flush returns WorkingTreeWriteBackFailed and leaves the path queued when SAF write fails`() = runBlocking {
        val worktree = newWorktree()
        val queue = newQueue()
        val safPath = "$safRoot/pages/Foo.md"
        // writeFile returns false for this path — NOT a bumped mtime, so this is the distinct
        // non-race failure path from the concurrent-edit test above.
        val fs = FakeFileSystem(writeResults = mapOf(safPath to false), defaultWriteOk = true)

        worktree.writeShadowFile("pages/Foo.md", "shadow content")
        queue.enqueue("pages/Foo.md")
        // No manifest entry exists for this path, so there is nothing to race against — flushPage
        // proceeds straight to the (failing) write.

        val actor = GitShadowFlushActor(fs, worktree, queue, safRoot)
        val results = actor.flush()

        assertEquals(1, results.size)
        val result = results.single()
        assertTrue(result.isLeft())
        result.fold(
            { error ->
                assertTrue(
                    error is DomainError.GitError.WorkingTreeWriteBackFailed && error.path == safPath,
                    "expected WorkingTreeWriteBackFailed for $safPath, got $error",
                )
            },
            { throw AssertionError("expected Left(WorkingTreeWriteBackFailed), got Right") },
        )
        assertNull(fs.written[safPath], "no content should land in SAF on a write failure")
        assertEquals(listOf("pages/Foo.md"), queue.getAll(), "path must remain queued for retry after a write failure")
    }
}
