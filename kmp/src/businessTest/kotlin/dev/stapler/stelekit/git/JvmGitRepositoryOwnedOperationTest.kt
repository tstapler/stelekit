// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.SyncOrigin
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JvmGitRepositoryOwnedOperationTest {
    private val dirs = mutableListOf<File>()
    private val now = 1_000_000_000_000L
    private val self = ProcessIdentity(InstanceId("self"), pid = 11, startTicks = 5, pidNs = null, host = "h", bootId = null, machineId = "m")
    private val info = OwnedOperationInfo("sync", SyncOrigin.UiPress)

    @AfterTest
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    private class Fixture(val root: File, val gitDir: File, val config: GitConfig)

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("owned-op").toFile().also { dirs += it }
        Git.init().setDirectory(root).setInitialBranch("main").call().close()
        val config = GitConfig(graphId = "g", repoRoot = RepoRoot(root.absolutePath), wikiSubdir = "", authType = GitAuthType.NONE)
        return Fixture(root, File(root, ".git"), config)
    }

    private fun repo(liveness: Liveness) = JvmGitRepository(
        ownedOperations = OwnedOperationSupport(
            liveness = { _, _, _, _ -> liveness },
            identity = self,
            registry = ActiveRunRegistry(),
            nowMs = { now },
        ),
    )

    private fun writeRecord(f: Fixture, instance: String, startedAtMs: Long, mtimeMs: Long) {
        val record = LockOwnerRecord(InstanceId(instance), 99, 7, null, "h", null, "sync", "UiPress", startedAtMs, "m")
        val file = File(f.gitDir, JavaIoLockFiles.RECORD_NAME).apply { writeText(record.encode()) }
        file.setLastModified(mtimeMs)
    }

    @Test
    fun removeStaleLockFile_should_NeverDelete_EvenWhenTheLockIsOlderThanTheOldSixtySecondRule() = runTest {
        val f = fixture()
        val lock = File(f.gitDir, "index.lock").apply { writeText(""); setLastModified(now - 3_600_000) }

        val result = repo(Liveness.Dead).removeStaleLockFile(f.config)

        val left = assertIs<Either.Left<DomainError.GitError>>(result).value
        assertIs<DomainError.GitError.StaleLockFile>(left)
        assertEquals(LockVerdict.NoRecord, left.verdict)
        assertTrue(lock.exists(), "removeStaleLockFile must never delete a lock")
    }

    @Test
    fun removeStaleLockFile_should_ReturnRight_When_NoLockExists() = runTest {
        assertEquals(Either.Right(Unit), repo(Liveness.Dead).removeStaleLockFile(fixture().config))
    }

    @Test
    fun beginOwnedOperation_should_ClearDeadOwnersLockAndPublishOurRecord_ThenEndRemovesIt() = runTest {
        val f = fixture()
        writeRecord(f, "dead", startedAtMs = now - 100_000, mtimeMs = now - 60_000)
        val lock = File(f.gitDir, "index.lock").apply { writeText(""); setLastModified(now - 60_000) }
        val repo = repo(Liveness.Dead)

        val verdict = repo.beginOwnedOperation(f.config, info)

        assertEquals(Either.Right(LockVerdict.ClearedDeadOwner), verdict)
        assertFalse(lock.exists())
        val record = File(f.gitDir, JavaIoLockFiles.RECORD_NAME)
        assertTrue((LockOwnerRecord.decode(record.readText()) as RecordDecode.Valid).record.instanceId == InstanceId("self"))

        repo.endOwnedOperation(f.config)
        assertFalse(record.exists())
    }

    @Test
    fun touchOwnedOperation_should_NotTouchARecordThatIsNoLongerOurs() = runTest {
        val f = fixture()
        val repo = repo(Liveness.Dead)
        repo.beginOwnedOperation(f.config, info)
        writeRecord(f, "intruder", startedAtMs = now + 5, mtimeMs = 1_234_000)

        repo.touchOwnedOperation(f.config)

        assertEquals(1_234_000L, File(f.gitDir, JavaIoLockFiles.RECORD_NAME).lastModified(), "must not refresh a stranger's mtime")
    }

    @Test
    fun beginOwnedOperation_should_LeaveLiveOwnersFilesAndReportOwnerAlive() = runTest {
        val f = fixture()
        writeRecord(f, "other", startedAtMs = now - 100_000, mtimeMs = now - 1_000)
        val lock = File(f.gitDir, "index.lock").apply { writeText(""); setLastModified(now - 10_000) }

        val verdict = repo(Liveness.Alive).beginOwnedOperation(f.config, info)

        assertEquals(Either.Right(LockVerdict.OwnerAlive), verdict)
        assertTrue(lock.exists())
        assertEquals("other", (LockOwnerRecord.decode(File(f.gitDir, JavaIoLockFiles.RECORD_NAME).readText()) as RecordDecode.Valid).record.instanceId.value)
    }

    @Test
    fun forceRemoveLock_should_RefuseOnLiveOwnerAndRemoveWhenDead() = runTest {
        val f = fixture()
        writeRecord(f, "other", startedAtMs = now - 100_000, mtimeMs = now - 60_000)
        val lock = File(f.gitDir, "index.lock").apply { writeText(""); setLastModified(now - 60_000) }

        assertTrue(repo(Liveness.Alive).forceRemoveLock(f.config).isLeft())
        assertTrue(lock.exists())

        assertEquals(Either.Right(Unit), repo(Liveness.Dead).forceRemoveLock(f.config))
        assertFalse(lock.exists())
    }
}
