// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.SyncOrigin
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * I-E1-08/09: a process killed while holding a real JGit `index.lock` is recovered by the next claimer without user
 * action. The child takes `index.lock` through JGit itself (a `commit` holds the same file), because killing a live
 * commit at the instant the lock exists is not deterministic.
 */
class ProcessKillOrphanLockTest {
    private val infoUi = OwnedOperationInfo("sync", SyncOrigin.UiPress)

    // The foreign-lock rule wants a lock at least 5 s old; a clock 60 s ahead stands in for waiting.
    private fun claimer(instance: String) = JvmGitRepositoryHolder(instance)

    private class JvmGitRepositoryHolder(instance: String) {
        val repo = JvmGitRepository(
            ownedOperations = OwnedOperationSupport(
                JvmProcessLiveness(),
                JvmOwnedOperations.identity().copy(instanceId = InstanceId(instance)),
                registry = ActiveRunRegistry(),
                nowMs = { System.currentTimeMillis() + FUTURE_MS },
            ),
        )
    }

    private fun gitDir(fx: JgitCancelFixture) = File(fx.work, ".git")

    private fun assertRecoveredAndCommits(fx: JgitCancelFixture, instance: String) = runBlocking {
        val repo = claimer(instance).repo
        assertEquals(LockVerdict.ClearedDeadOwner, repo.beginOwnedOperation(fx.config, infoUi).getOrNull())
        assertEquals(emptyList(), fx.locksUnderGitDir(), "the dead owner's lock is gone")
        assertTrue(repo.repositoryHealth(fx.config).safe, "index healthy, no auto-reset")
        assertTrue(repo.stageSubdir(fx.config).isRight())
        assertTrue(repo.commit(fx.config, "after recovery").isRight(), "a real commit succeeds over the recovered repo")
        repo.endOwnedOperation(fx.config)
    }

    @Test
    fun sigkillDuringRealCommit_should_BeClearedAsDeadOwnerBySubsequentSync_WithoutUserAction() {
        JgitCancelFixture(fileCount = 5).use { fx ->
            val child = ChildJvm.spawnMain(ProcessKillChildMain::class.java.name, fx.work.absolutePath)
            child.awaitLine { it == "LOCK_HELD" }
            assertTrue(File(gitDir(fx), "index.lock").exists(), "the child holds index.lock")
            child.kill()
            assertTrue(File(gitDir(fx), "index.lock").exists(), "SIGKILL leaves the orphan lock")
            assertRecoveredAndCommits(fx, "parent")
        }
    }

    @Test
    fun sigkillWithPidReuseSimulated_should_StillClear_When_StartTicksDiffer() {
        JgitCancelFixture(fileCount = 5).use { fx ->
            val now = System.currentTimeMillis()
            // The record names a pid that is alive (this JVM) but with another start stamp: a reused pid.
            val reused = selfRecordFor("ghost", now - 100_000).copy(processStartTicks = (JvmProcessLiveness.selfStartStamp() ?: 0L) + 12_345L)
            val record = File(gitDir(fx), JavaIoLockFiles.RECORD_NAME).apply { writeText(reused.encode()) }
            record.setLastModified(now - 60_000)
            File(gitDir(fx), "index.lock").apply { writeText("orphan"); setLastModified(now - 60_000) }
            assertRecoveredAndCommits(fx, "parent")
        }
    }

    @Test
    fun sigkillWhileHoldingRecoveryLock_should_LetTheNextClaimerRecover() {
        JgitCancelFixture(fileCount = 5).use { fx ->
            val exited = ProcessBuilder("true").start().also { it.waitFor(30, TimeUnit.SECONDS) }.pid()
            val now = System.currentTimeMillis()
            val dead = selfRecordFor("dead-owner", now - 100_000).copy(pid = exited, processStartTicks = 1L)
            File(gitDir(fx), JavaIoLockFiles.RECORD_NAME).apply { writeText(dead.encode()); setLastModified(now - 60_000) }
            File(gitDir(fx), "index.lock").apply { writeText("orphan"); setLastModified(now - 60_000) }

            val holder = ChildJvm.spawn("hold", gitDir(fx).absolutePath)
            holder.awaitLine { it == "HELD" }
            assertFalse(
                claimer("blocked").repo.let { runBlocking { it.beginOwnedOperation(fx.config, infoUi).getOrNull() } } == LockVerdict.ClearedDeadOwner,
                "a claimer must not recover while another process holds the recovery lock",
            )
            assertTrue(File(gitDir(fx), "index.lock").exists(), "nothing deleted while the recovery lock is contended")
            holder.kill()
            assertRecoveredAndCommits(fx, "parent")
        }
    }

    private companion object {
        const val FUTURE_MS = 60_000L
    }
}
