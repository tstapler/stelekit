// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.git

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.error.SyncOrigin
import dev.stapler.stelekit.git.model.GitConfig
import dev.stapler.stelekit.git.testsupport.StubConfigRepository
import dev.stapler.stelekit.git.testsupport.sampleConfig
import dev.stapler.stelekit.model.RepoRoot
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** PR #682 round-2 review: claim atomicity (C1) and the confirmed "Remove lock file" path end to end (M1). */
class StaleLockRecoveryFollowUpTest {
    private val dirs = mutableListOf<File>()
    private val services = mutableListOf<GitSyncService>()
    private val self = ProcessIdentity(InstanceId("self"), pid = 11, startTicks = 5, pidNs = null, host = "h", bootId = null, machineId = "m")
    private val info = OwnedOperationInfo("sync", SyncOrigin.UiPress)

    @AfterTest
    fun cleanup() {
        services.forEach { it.shutdown() }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File = Files.createTempDirectory("lock-followup").toFile().also { dirs += it }

    private fun support(registry: ActiveRunRegistry = ActiveRunRegistry(), liveness: Liveness = Liveness.Dead) =
        OwnedOperationSupport(liveness = { _, _, _, _ -> liveness }, identity = self, registry = registry)

    private fun writeDeadOwner(gitDir: File) {
        val record = LockOwnerRecord(InstanceId("dead"), 99, 7, null, "h", null, "sync", "UiPress", System.currentTimeMillis() - 100_000, "m")
        File(gitDir, JavaIoLockFiles.RECORD_NAME).apply { writeText(record.encode()); setLastModified(System.currentTimeMillis() - 60_000) }
        File(gitDir, "index.lock").apply { writeText(""); setLastModified(System.currentTimeMillis() - 60_000) }
    }

    @Test
    fun beginChecked_should_EndTheClaim_When_TheHealthCheckRefuses() = runBlocking<Unit> {
        val gitDir = tempDir()
        writeDeadOwner(gitDir)
        val registry = ActiveRunRegistry()
        val support = support(registry)

        val refused = support.beginChecked(gitDir, info) { DomainError.GitError.RepairNeeded("corrupt").left() }

        assertIs<DomainError.GitError.RepairNeeded>(refused.leftOrNull())
        assertFalse(registry.isActive(OwnedOperationSupport.keyFor(gitDir)), "a refused claim must not stay registered")
        assertFalse(File(gitDir, JavaIoLockFiles.RECORD_NAME).exists(), "our record must not outlive the refusal")
        val next = support.begin(gitDir, info)
        assertEquals(Either.Right(LockVerdict.NoLock), next, "the next claim must not see SyncInProgress")
    }

    @Test
    fun beginChecked_should_EndTheClaimAndRethrow_When_TheHealthCheckThrows() = runBlocking<Unit> {
        val gitDir = tempDir()
        writeDeadOwner(gitDir)
        val registry = ActiveRunRegistry()
        val support = support(registry)

        val thrown = runCatching { support.beginChecked(gitDir, info) { error("status failed") } }.exceptionOrNull()

        assertIs<IllegalStateException>(thrown)
        assertFalse(registry.isActive(OwnedOperationSupport.keyFor(gitDir)))
        assertFalse(File(gitDir, JavaIoLockFiles.RECORD_NAME).exists())
    }

    // The original bug's victims: a leftover index.lock with no owner record.
    @Test
    fun removeLockFile_should_UnwedgeSync_When_StaleLockHasNoRecord() = runBlocking<Unit> {
        val root = tempDir()
        Git.init().setDirectory(root).setInitialBranch("main").call().close()
        val lock = File(root, ".git/index.lock").apply { writeText(""); setLastModified(System.currentTimeMillis() - 3_600_000) }
        val service = serviceFor(root)

        val refused = service.sync("g", SyncTrigger.Manual)
        assertIs<DomainError.GitError.StaleLockFile>(refused.leftOrNull(), "no record: the claim must never delete on its own")
        assertTrue(lock.exists())

        assertEquals(Either.Right(Unit), service.removeLockFile("g"))
        assertFalse(lock.exists())

        val synced = service.sync("g", SyncTrigger.Manual)
        assertTrue(synced.isRight(), "sync must proceed after the confirmed removal, got $synced")
    }

    @Test
    fun removeLockFile_should_Refuse_When_TheLockIsOnlyMinutesOld() = runBlocking<Unit> {
        val root = tempDir()
        Git.init().setDirectory(root).setInitialBranch("main").call().close()
        val lock = File(root, ".git/index.lock").apply { writeText(""); setLastModified(System.currentTimeMillis() - 120_000) }

        assertTrue(serviceFor(root).removeLockFile("g").isLeft(), "an editor-held index.lock can sit unchanged for minutes")
        assertTrue(lock.exists())
    }

    @Test
    fun removeLockFileAction_should_SurfaceTheRefusal_When_TheLockIsKept() = runBlocking<Unit> {
        val root = tempDir()
        Git.init().setDirectory(root).setInitialBranch("main").call().close()
        File(root, ".git/index.lock").writeText("")
        val service = serviceFor(root)
        val events = kotlinx.coroutines.CompletableDeferred<dev.stapler.stelekit.ui.SyncUiEvent>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        try {
            val actions = dev.stapler.stelekit.ui.SyncSafetyActions(
                kotlinx.coroutines.flow.MutableStateFlow(service), { "g" }, scope,
                kotlinx.coroutines.flow.MutableStateFlow(dev.stapler.stelekit.ui.AppState()),
                dev.stapler.stelekit.jobs.InProcessJobs(),
                emit = { events.complete(it) },
            ) { dev.stapler.stelekit.git.model.SyncState.Idle }

            actions.removeLockFile()

            val event = kotlinx.coroutines.withTimeout(20_000) { events.await() }
            assertIs<dev.stapler.stelekit.ui.SyncUiEvent.LockRemovalRefused>(event)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun removeLockFile_should_Refuse_When_TheLockWasJustTouched() = runBlocking<Unit> {
        val root = tempDir()
        Git.init().setDirectory(root).setInitialBranch("main").call().close()
        val lock = File(root, ".git/index.lock").apply { writeText("") }
        val service = serviceFor(root)

        assertTrue(service.removeLockFile("g").isLeft(), "a lock written moments ago may be a live git process")
        assertTrue(lock.exists())
    }

    private fun serviceFor(root: File): GitSyncService {
        val config: GitConfig = sampleConfig.copy(repoRoot = RepoRoot(root.absolutePath))
        val real = JvmGitRepository(ownedOperations = support())
        val fake = GateAwareFakeGitRepository(File(root, ".git"))
        val repo = object : GitRepository by fake {
            override fun gateKeyFor(config: GitConfig) = real.gateKeyFor(config)
            override suspend fun beginOwnedOperation(config: GitConfig, info: OwnedOperationInfo) = real.beginOwnedOperation(config, info)
            override suspend fun endOwnedOperation(config: GitConfig) = real.endOwnedOperation(config)
            override suspend fun touchOwnedOperation(config: GitConfig) = real.touchOwnedOperation(config)
            override suspend fun forceRemoveLock(config: GitConfig) = real.forceRemoveLock(config)
        }
        return buildGitSyncTestService(
            gitRepository = repo,
            configRepository = StubConfigRepository(Either.Right(config)),
            isOnline = { true },
        ).also { services += it }
    }
}
