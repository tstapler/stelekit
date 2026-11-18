package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.DomainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForceRemoveRacesLiveRunTest {
    private val key = "/repo/.git/stelekit-sync.owner"
    private val self = testRecord(instance = "self", pid = 1)
    private val live = testRecord(instance = "cli", pid = 7777, startedAtMs = 60_000)
    private val liveness = ProcessLiveness { pid, _, _, _ -> if (pid == 7777L) Liveness.Alive else Liveness.Dead }

    private fun force(files: FakeLockFiles, registry: ActiveRunRegistry = ActiveRunRegistry()) =
        LockRecovery.forceRemove(key, files, liveness, self, registry)

    @Test
    fun forceRemoveLock_should_RecheckVerdictUnderRecoveryLock_AndRefuse_When_OwnerAlive() {
        // The dialog was opened over a stale lock; a CLI run then starts and publishes a live record and lock.
        val files = FakeLockFiles(locks = mutableListOf(LockFileEntry("index.lock", 1_000)))
        files.onTryLock = {
            files.record = RecordRead(live.encode(), 99_000)
            files.locks = mutableListOf(LockFileEntry("index.lock", 99_000))
        }

        val result = force(files)

        assertEquals(DomainError.GitError.SyncInProgress(null), result.leftOrNull())
        assertTrue(files.deletions.isEmpty(), "the live run's lock and record must survive: ${files.mutations}")
        assertEquals(1, files.releaseCalls)
    }

    @Test
    fun forceRemoveLock_should_Refuse_When_RunRegisteredInThisProcess() {
        val files = FakeLockFiles(locks = mutableListOf(LockFileEntry("index.lock", 1_000)))
        val registry = ActiveRunRegistry().also { it.register(key, SyncOrigin.Scheduled) }

        assertEquals(DomainError.GitError.SyncInProgress(SyncOrigin.Scheduled), force(files, registry).leftOrNull())
        assertEquals(0, files.tryLockCalls)
        assertTrue(files.mutations.isEmpty())
    }

    @Test
    fun forceRemoveLock_should_DeleteOnlyAllowListedLocksAndDeadRecord_When_OwnerDead() {
        val dead = testRecord(instance = "dead", pid = 4242)
        val files = FakeLockFiles(
            locks = mutableListOf(LockFileEntry("index.lock", 1_000), LockFileEntry("objects/pack/tmp_pack_x.lock", 1_000)),
            record = RecordRead(dead.encode(), 1_000),
        )

        val result = force(files)

        assertEquals(listOf("index.lock"), (result as arrow.core.Either.Right).value)
        assertEquals(listOf("delete:index.lock", "deleteRecord"), files.mutations)
    }

    @Test
    fun forceRemoveLock_should_KeepRecordButRemoveLock_When_NoRecordAndStaleLock() {
        val files = FakeLockFiles(locks = mutableListOf(LockFileEntry("index.lock", 1_000)))

        force(files)

        assertEquals(listOf("delete:index.lock"), files.mutations)
    }

    @Test
    fun forceRemoveLock_should_Refuse_When_RecoveryLockContendedOrUnsupported() {
        val contended = FakeLockFiles(locks = mutableListOf(LockFileEntry("index.lock", 1_000)))
        contended.recoveryMode = FakeLockFiles.RecoveryMode.Contended
        assertEquals(DomainError.GitError.SyncInProgress(null), force(contended).leftOrNull())
        assertTrue(contended.mutations.isEmpty())

        val unsupported = FakeLockFiles(locks = mutableListOf(LockFileEntry("index.lock", 1_000)))
        unsupported.recoveryMode = FakeLockFiles.RecoveryMode.Unsupported
        assertEquals(
            DomainError.GitError.StaleLockFile(key, LockVerdict.OwnerUnknown(UnknownReason.RecoveryLockUnsupported)),
            force(unsupported).leftOrNull(),
        )
        assertTrue(unsupported.mutations.isEmpty())
    }
}
