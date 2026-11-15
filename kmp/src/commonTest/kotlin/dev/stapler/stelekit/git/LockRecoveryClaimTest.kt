package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory [LockFiles] with an injectable hook run inside [tryLockRecovery], where a competing claimer would act. */
internal class FakeLockFiles(
    var locks: MutableList<LockFileEntry> = mutableListOf(),
    var record: RecordRead? = null,
) : LockFiles {
    enum class RecoveryMode { Free, Contended, Unsupported }

    var recoveryMode = RecoveryMode.Free
    var onTryLock: (() -> Unit)? = null
    var failCreate = false
    var nowMs = 100_000L
    val mutations = mutableListOf<String>()
    var tryLockCalls = 0
    var releaseCalls = 0
    private var held = false

    override fun listAllowListedLocks(): List<LockFileEntry> = locks.toList()
    override fun readRecord(): RecordRead? = record
    override fun createRecordExclusive(content: String): Boolean {
        if (failCreate) error("disk full")
        if (record != null) return false
        record = RecordRead(content, nowMs)
        mutations += "create"
        return true
    }
    override fun touchRecord(): Boolean {
        val current = record ?: return false
        record = current.copy(mtimeMs = nowMs)
        mutations += "touch"
        return true
    }
    override fun deleteRecord() { record = null; mutations += "deleteRecord" }
    override fun delete(path: String) { locks.removeAll { it.path == path }; mutations += "delete:$path" }
    override fun tryLockRecovery(): RecoveryLockHandle? {
        tryLockCalls++
        onTryLock?.invoke()
        when (recoveryMode) {
            RecoveryMode.Unsupported -> throw RecoveryLockUnsupportedException("fuse")
            RecoveryMode.Contended -> return null
            RecoveryMode.Free -> Unit
        }
        if (held) return null
        held = true
        return object : RecoveryLockHandle {
            override fun release() { held = false; releaseCalls++ }
        }
    }

    val deletions: List<String> get() = mutations.filter { it.startsWith("delete") }
}

internal val NEVER_ALIVE = ProcessLiveness { _, _, _, _ -> Liveness.Dead }
internal val ALWAYS_ALIVE = ProcessLiveness { _, _, _, _ -> Liveness.Alive }

class LockRecoveryClaimTest {
    private val key = "/repo/.git/stelekit-sync.owner"
    private val self = testRecord(instance = "self", pid = 1, startedAtMs = 50_000)
    private val deadOwner = testRecord(instance = "dead", startedAtMs = 1_000)

    private fun filesWithDeadOwner(vararg lockMtimes: Long) = FakeLockFiles(
        locks = lockMtimes.mapIndexed { i, m -> LockFileEntry(if (i == 0) "index.lock" else "refs/heads/b$i.lock", m) }.toMutableList(),
        record = RecordRead(deadOwner.encode(), 4_000),
    )

    private fun claim(files: LockFiles, registry: ActiveRunRegistry = ActiveRunRegistry(), liveness: ProcessLiveness = NEVER_ALIVE) =
        LockRecovery.claim(key, files, liveness, self, registry) { 100_000L }

    @Test
    fun claim_should_PublishOurRecordAndRegister_When_NoLock() {
        val files = FakeLockFiles()
        val registry = ActiveRunRegistry()

        val result = claim(files, registry)

        assertIs<Either.Right<ClaimedRun>>(result)
        assertEquals(self.encode(), files.record?.content)
        assertTrue(registry.isActive(key))
        assertEquals(0, files.tryLockCalls, "the no-record case needs no recovery lock")
    }

    @Test
    fun claim_should_ReturnSyncInProgressAndDeleteNothing_When_OwnerAlive() {
        val files = filesWithDeadOwner(2_000)

        val result = claim(files, liveness = ALWAYS_ALIVE)

        assertEquals(DomainError.GitError.SyncInProgress(null), result.leftOrNull())
        assertTrue(files.mutations.isEmpty())
    }

    @Test
    fun claim_should_ReturnStaleLockFileAndDeleteNothing_When_LockWithNoRecord() {
        val files = FakeLockFiles(locks = mutableListOf(LockFileEntry("index.lock", 2_000)))

        val left = claim(files).leftOrNull()

        assertEquals(DomainError.GitError.StaleLockFile(key, LockVerdict.NoRecord), left)
        assertTrue(files.mutations.isEmpty())
    }

    @Test
    fun claim_should_NotDeleteLockOrRecord_When_RecoveryLockContendedOrUnsupported() {
        val contended = filesWithDeadOwner(2_000).apply { recoveryMode = FakeLockFiles.RecoveryMode.Contended }
        assertEquals(DomainError.GitError.SyncInProgress(null), claim(contended).leftOrNull())
        assertTrue(contended.mutations.isEmpty())

        val unsupported = filesWithDeadOwner(2_000).apply { recoveryMode = FakeLockFiles.RecoveryMode.Unsupported }
        val left = claim(unsupported).leftOrNull()
        assertEquals(
            DomainError.GitError.StaleLockFile(key, LockVerdict.OwnerUnknown(UnknownReason.RecoveryLockUnsupported)),
            left,
        )
        assertTrue(unsupported.mutations.isEmpty())
    }

    @Test
    fun claim_should_RedecideUnderLockAndBackOff_When_RecordReplacedByLiveClaimer() {
        val files = filesWithDeadOwner(2_000)
        val live = testRecord(instance = "winner", pid = 7777, startedAtMs = 60_000)
        files.onTryLock = { files.record = RecordRead(live.encode(), 99_000) }
        val registry = ActiveRunRegistry()
        // The dead owner (pid 4242) probes Dead; the winner that appears under the lock probes Alive.
        val liveness = ProcessLiveness { pid, _, _, _ -> if (pid == 7777L) Liveness.Alive else Liveness.Dead }

        val result = LockRecovery.claim(key, files, liveness, self, registry) { 100_000L }

        assertEquals(DomainError.GitError.SyncInProgress(null), result.leftOrNull())
        assertTrue(files.deletions.isEmpty(), "the winner's record and locks must survive: ${files.mutations}")
        assertFalse(registry.isActive(key))
        assertEquals(1, files.releaseCalls)
    }

    @Test
    fun claim_should_DeleteLocksThenRecordThenPublish_When_DeadOwnerLocksWithinWindow() {
        val files = filesWithDeadOwner(2_000, 3_000)

        val result = claim(files)

        assertIs<Either.Right<ClaimedRun>>(result)
        assertEquals(listOf("delete:index.lock", "delete:refs/heads/b1.lock", "deleteRecord", "create"), files.mutations)
        assertEquals(self.encode(), files.record?.content)
        assertEquals(1, files.releaseCalls, "recovery lock must be released in finally")
    }

    @Test
    fun claim_should_ContinueWithoutRecord_When_RecordWriteFails() {
        val files = FakeLockFiles().apply { failCreate = true }
        val registry = ActiveRunRegistry()

        assertIs<Either.Right<ClaimedRun>>(claim(files, registry))
        assertTrue(registry.isActive(key))
        assertNull(files.record)
    }

    @Test
    fun end_should_DeleteOnlyOurOwnRecordAndUnregister() {
        val files = FakeLockFiles()
        val registry = ActiveRunRegistry()
        val run = claim(files, registry).getOrNullForTest()!!

        // A different instance replaced the record behind our back: it is not ours to delete.
        val other = testRecord(instance = "other", startedAtMs = 70_000)
        files.record = RecordRead(other.encode(), 1)
        LockRecovery.end(run, files, registry)
        assertEquals(other.encode(), files.record?.content)
        assertFalse(registry.isActive(key))

        val files2 = FakeLockFiles()
        val registry2 = ActiveRunRegistry()
        val run2 = claim(files2, registry2).getOrNullForTest()!!
        LockRecovery.end(run2, files2, registry2)
        assertNull(files2.record)
        assertFalse(registry2.isActive(key))
    }
}

internal fun <A, B> Either<A, B>.getOrNullForTest(): B? = (this as? Either.Right)?.value
internal fun <A, B> Either<A, B>.leftOrNull(): A? = (this as? Either.Left)?.value
