// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.git.GitShadowFlushActor
import dev.stapler.stelekit.git.GitShadowWorktree
import dev.stapler.stelekit.git.GitSyncBusyCounter
import dev.stapler.stelekit.git.GitWriteBackQueue
import dev.stapler.stelekit.git.RepoOpGate
import dev.stapler.stelekit.model.StorageLocation
import dev.stapler.stelekit.model.StorageMoveOperation
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.GitWorktreeLocks
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Architecture R1: the real [GitShadowFlushActor.flush] takes the worktree mutex, which quiesce already
 * holds, so a drain that called it would self-deadlock. A fake flush lambda hid that; this uses the real actor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class QuiesceRealFlushTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val safRoot = "saf://root"

    private class RecordingFileSystem : FileSystem {
        val written = mutableMapOf<String, String>()
        override fun getDefaultGraphPath() = "saf://root"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String): Boolean {
            written[path] = content
            return true
        }
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = false
        override fun deleteFile(path: String) = false
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    private val op = StorageMoveOperation.Relocate(
        graphId = "g1",
        source = StorageLocation.SafFolder("g1", "content://tree/x"),
        destination = StorageLocation.AppOwned("g1"),
        deleteSourceAfterVerify = true,
    )

    // runBlocking: the real-time withTimeout/polling guards would expire early on virtual time
    @Test
    fun quiesce_should_NotSelfDeadlock_When_RealGitShadowFlushActorFlushRunsInDrain() = runBlocking {
        val worktree = GitShadowWorktree(context, "quiesce-real-${System.nanoTime()}", safRoot)
        val queue = GitWriteBackQueue(tempFolder.newFile("queue-${System.nanoTime()}.txt"))
        val fs = RecordingFileSystem()
        worktree.writeShadowFile("pages/Foo.md", "shadow content")
        queue.enqueue("pages/Foo.md")
        val actor = GitShadowFlushActor(fs, worktree, queue, safRoot)

        val gate = RepoOpGate()
        val strategy = AndroidGraphMoveQuiesceStrategy(
            gitSyncBusyCounter = GitSyncBusyCounter(),
            gate = gate,
            gateKeyFor = { "real-flush-key" },
            shadowWorktreeTarget = {
                ShadowWorktreeQuiesceTarget(worktree.shadowKey, queue, flushWhileLocked = actor::flushLocked)
            },
        )

        withTimeout(10_000) { strategy.quiesce(op) } // real-clock hang guard: awaits real threads

        assertTrue(queue.isEmpty(), "the drain must empty the write-back queue")
        assertEquals("shadow content", fs.written["$safRoot/pages/Foo.md"])

        strategy.release(op)
        assertFalse(gate.isHeld("real-flush-key"))
        assertFalse(GitWorktreeLocks.lockFor(worktree.shadowKey).isLocked)
    }
}
