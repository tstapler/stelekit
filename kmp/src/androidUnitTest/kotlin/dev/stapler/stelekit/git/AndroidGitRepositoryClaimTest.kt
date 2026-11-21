// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.SyncOrigin
import dev.stapler.stelekit.git.model.GitAuthType
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.FakeCredentialAccess
import dev.stapler.stelekit.git.testsupport.FakeSafFileSystem
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Story 1.2 claim over [AndroidGitRepository], with a fake [ProcessLiveness] and a fixed clock. */
@RunWith(RobolectricTestRunner::class)
class AndroidGitRepositoryClaimTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val now = 1_000_000_000_000L
    private val self = ProcessIdentity(InstanceId("self"), pid = 11, startTicks = 5, pidNs = "pid:[1]", host = AndroidProcessLiveness.HOST, bootId = null)
    private val info = OwnedOperationInfo("sync", SyncOrigin.UiPress)
    private val dirs = mutableListOf<File>()

    @After
    fun tearDown() {
        ShadowStatFs.reset()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun repository(liveness: Liveness, fileSystem: FakeSafFileSystem = FakeSafFileSystem()) = AndroidGitRepository(
        context = context,
        sshKeyProvider = null,
        credentialAccess = FakeCredentialAccess(),
        pathResolver = { null },
        fileSystem = fileSystem,
        ownedOperations = OwnedOperationSupport(
            liveness = { _, _, _, _ -> liveness },
            identity = self,
            registry = ActiveRunRegistry(),
            nowMs = { now },
        ),
    )

    private fun writeRecord(gitDir: File, instance: String, startedAtMs: Long, mtimeMs: Long) {
        val record = LockOwnerRecord(InstanceId(instance), 99, 7, "pid:[1]", AndroidProcessLiveness.HOST, null, "sync", "UiPress", startedAtMs)
        File(gitDir, JavaIoLockFiles.RECORD_NAME).apply { writeText(record.encode()); setLastModified(mtimeMs) }
    }

    private fun plainRepo(): Pair<File, GitConfig> {
        val root = createTempDirectory("android-claim").toFile().also { dirs += it }
        Git.init().setDirectory(root).setInitialBranch("main").call().close()
        return root to GitConfig(graphId = "g", repoRoot = RepoRoot(root.absolutePath), wikiSubdir = null, authType = GitAuthType.NONE)
    }

    @Test
    fun removeStaleLockFile_should_NeverDeleteAnOldLock() = runTest {
        val (root, config) = plainRepo()
        val lock = File(root, ".git/index.lock").apply { writeText(""); setLastModified(now - 3_600_000) }

        val result = repository(Liveness.Dead).removeStaleLockFile(config)

        assertIs<DomainError.GitError.StaleLockFile>(assertIs<Either.Left<DomainError.GitError>>(result).value)
        assertTrue(lock.exists())
    }

    @Test
    fun beginOwnedOperation_should_ClearDeadOwnerAndEndRemovesOurRecord() = runTest {
        val (root, config) = plainRepo()
        val gitDir = File(root, ".git")
        writeRecord(gitDir, "dead", startedAtMs = now - 100_000, mtimeMs = now - 60_000)
        val lock = File(gitDir, "index.lock").apply { writeText(""); setLastModified(now - 60_000) }
        val repo = repository(Liveness.Dead)

        assertEquals(Either.Right(LockVerdict.ClearedDeadOwner), repo.beginOwnedOperation(config, info))
        assertFalse(lock.exists())
        repo.endOwnedOperation(config)
        assertFalse(File(gitDir, JavaIoLockFiles.RECORD_NAME).exists())
    }

    @Test
    fun beginOwnedOperation_should_LeaveLiveOwnersFiles() = runTest {
        val (root, config) = plainRepo()
        val gitDir = File(root, ".git")
        writeRecord(gitDir, "other", startedAtMs = now - 100_000, mtimeMs = now - 1_000)
        val lock = File(gitDir, "index.lock").apply { writeText(""); setLastModified(now - 10_000) }

        assertEquals(Either.Right(LockVerdict.OwnerAlive), repository(Liveness.Alive).beginOwnedOperation(config, info))
        assertTrue(lock.exists())
    }

    @Test
    fun beginOwnedOperation_should_ClaimInTheShadowGitDir_ForASafResolvedGraph() = runTest {
        val repoRoot = "saf://content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%3Awiki-claim"
        val repo = repository(Liveness.Dead)
        val shadowRoot = requireNotNull(repo.shadowWorktreeFor(repoRoot)).worktreeRootPath
        ShadowStatFs.registerStats(shadowRoot, 2_000_000, 1_000_000, 1_000_000)
        assertTrue(repo.init(RepoRoot(repoRoot)).isRight())
        val config = GitConfig(graphId = "wiki-claim", repoRoot = RepoRoot(repoRoot), wikiSubdir = null, authType = GitAuthType.NONE)

        assertEquals(Either.Right(LockVerdict.NoLock), repo.beginOwnedOperation(config, info))

        assertTrue(File(repo.resolveForJGit(repoRoot), ".git/${JavaIoLockFiles.RECORD_NAME}").exists(), "record lives in the shadow .git")
        repo.endOwnedOperation(config)
    }
}
