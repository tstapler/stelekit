// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// jvmTest-reason: desktop-only

package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Story 1.2 ordering AC through the real repository method: the owner record is read before it is overwritten, so a
 * second instance never deletes or replaces a live owner's lock or record. A separate [ActiveRunRegistry] and
 * [InstanceId] per repository stand in for separate processes.
 */
class BeginOwnedOperationOrderingTest {
    private val root = Files.createTempDirectory("begin-ordering").toFile().also { Git.init().setDirectory(it).call().close() }
    private val gitDir = File(root, ".git")
    private val config = GitConfig(graphId = "g", repoRoot = RepoRoot(root.absolutePath), wikiSubdir = null, authType = GitAuthType.NONE)
    private val info = OwnedOperationInfo("sync", SyncOrigin.UiPress)

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun instance(name: String) = JvmGitRepository(
        ownedOperations = OwnedOperationSupport(
            JvmProcessLiveness(),
            JvmOwnedOperations.identity().copy(instanceId = InstanceId(name)),
            registry = ActiveRunRegistry(),
        ),
    )

    private fun recordOwner(): String? =
        File(gitDir, JavaIoLockFiles.RECORD_NAME).takeIf { it.exists() }
            ?.let { (LockOwnerRecord.decode(it.readText()) as? RecordDecode.Valid)?.record?.instanceId?.value }

    @Test
    fun liveOwnersLockAndRecord_should_SurviveASecondInstancesBegin_WhichReturnsOwnerAlive() = runBlocking<Unit> {
        val a = instance("A")
        assertEquals(LockVerdict.NoLock, a.beginOwnedOperation(config, info).getOrNull())
        val lock = File(gitDir, "index.lock").apply { writeText("live") }

        val b = instance("B").beginOwnedOperation(config, info)

        assertEquals(LockVerdict.OwnerAlive, b.getOrNull())
        assertTrue(lock.exists(), "the live owner's index.lock survives")
        assertEquals("A", recordOwner(), "the live owner's record is not overwritten")
        a.endOwnedOperation(config)
    }

    @Test
    fun leftoverRecordFromTheClaimersOwnEarlierRun_should_BeReadBeforeBeingOverwritten() = runBlocking<Unit> {
        instance("A").beginOwnedOperation(config, info)

        val result = instance("A").beginOwnedOperation(config, info)

        assertEquals(LockVerdict.ClearedOwn, result.getOrNull(), "result=$result")
        assertEquals("A", recordOwner())
    }

    @Test
    fun beginAfterEnd_should_ReportNoLock_AndSecondBeginInTheSameRunIsRefused() = runBlocking<Unit> {
        val a = instance("A")
        assertEquals(LockVerdict.NoLock, a.beginOwnedOperation(config, info).getOrNull())
        val again = a.beginOwnedOperation(config, info)
        assertTrue(again.isLeft() || again.getOrNull() == LockVerdict.OwnerAlive, "a run already in progress is never re-claimed: $again")
        a.endOwnedOperation(config)
        assertEquals(LockVerdict.NoLock, a.beginOwnedOperation(config, info).getOrNull())
        a.endOwnedOperation(config)
        again.onLeft { assertIs<DomainError.GitError.SyncInProgress>(it) }
    }
}
