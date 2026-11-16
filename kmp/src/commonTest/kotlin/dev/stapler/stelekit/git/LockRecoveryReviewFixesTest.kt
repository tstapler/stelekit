package dev.stapler.stelekit.git

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regression tests for the PR #682 review findings C2, M3, M4, M5. */
class LockRecoveryReviewFixesTest {
    private val key = "/repo/.git/stelekit-sync.owner"
    private val self = testRecord(instance = "self", pid = 1, startedAtMs = 50_000)

    private fun claim(files: LockFiles, registry: ActiveRunRegistry = ActiveRunRegistry(), liveness: ProcessLiveness = NEVER_ALIVE) =
        LockRecovery.claim(key, files, liveness, self, registry) { 100_000L }

    // C2: a user-confirmed remove must clear a record the automatic path will never delete.
    @Test
    fun forceRemove_should_ClearTornRecord_SoNextClaimSucceeds() {
        val files = FakeLockFiles(
            locks = mutableListOf(LockFileEntry("index.lock", 1_000)),
            record = RecordRead("{not a record", 1_000),
        )
        val registry = ActiveRunRegistry()
        assertIs<Either.Left<*>>(claim(files, registry))

        LockRecovery.forceRemove(key, files, NEVER_ALIVE, self, registry)

        assertNull(files.record)
        assertIs<Either.Right<ClaimedRun>>(claim(files, registry))
    }

    @Test
    fun forceRemove_should_ClearDifferentHostAndLivenessUnknownRecords() {
        val otherHost = testRecord(instance = "x", host = "elsewhere", startedAtMs = 1_000)
        val unknown = testRecord(instance = "y", pid = 4242, startedAtMs = 1_000)
        listOf(otherHost to NEVER_ALIVE, unknown to ProcessLiveness { _, _, _, _ -> Liveness.Unknown }).forEach { (rec, liveness) ->
            val files = FakeLockFiles(locks = mutableListOf(LockFileEntry("index.lock", 1_000)), record = RecordRead(rec.encode(), 1_000))
            val registry = ActiveRunRegistry()

            LockRecovery.forceRemove(key, files, liveness, self, registry)

            assertNull(files.record, "record of ${rec.instanceId} must be cleared")
            assertIs<Either.Right<ClaimedRun>>(claim(files, registry, liveness))
        }
    }

    @Test
    fun forceRemove_should_KeepLiveOwnersRecord() {
        val live = testRecord(instance = "live", pid = 7777, startedAtMs = 1_000)
        val files = FakeLockFiles(record = RecordRead(live.encode(), 1_000))

        val result = LockRecovery.forceRemove(key, files, ALWAYS_ALIVE, self, ActiveRunRegistry())

        assertEquals(DomainError.GitError.SyncInProgress(null), result.leftOrNull())
        assertNotNull(files.record)
    }

    // M3: a sibling claimer's live own-instance record must never be treated as a corpse.
    @Test
    fun claim_should_NotDeleteLiveOwnRecord_When_SiblingRegistersDuringRecovery() {
        val registry = ActiveRunRegistry()
        val sibling = testRecord(instance = "self", pid = 1, startedAtMs = 60_000)
        val files = FakeLockFiles(record = RecordRead(sibling.encode(), 99_000))
        // A's early registry check passed (not yet registered); B registers just before A takes the recovery lock.
        files.onTryLock = { registry.register(key, SyncOrigin.Scheduled) }

        val result = claim(files, registry)

        assertEquals(DomainError.GitError.SyncInProgress(SyncOrigin.Scheduled), result.leftOrNull())
        assertTrue(files.deletions.isEmpty(), "live record must survive: ${files.mutations}")
        assertTrue(registry.isActive(key))
    }

    @Test
    fun publish_should_RegisterBeforeRecordExists() {
        val registry = ActiveRunRegistry()
        val files = object : LockFiles by FakeLockFiles() {
            override fun createRecordExclusive(content: String): Boolean {
                assertTrue(registry.isActive(key), "record must never exist while the run is unregistered")
                return true
            }
            override fun readRecord(): RecordRead? = null
            override fun listAllowListedLocks(): List<LockFileEntry> = emptyList()
        }
        // readRecord returns null after create, so the claim backs off; the assertion inside create is the point.
        claim(files, registry)
    }

    // M4: failing to write the record must not run the sync unprotected.
    @Test
    fun claim_should_FailClosedAndUnregister_When_RecordWriteFails() {
        val files = FakeLockFiles().apply { failCreate = true }
        val registry = ActiveRunRegistry()

        val result = claim(files, registry)

        assertIs<DomainError.GitError.StaleLockFile>(result.leftOrNull())
        assertFalse(registry.isActive(key))
        assertNull(files.record)
    }

    // M5: an IOException after registration must not leave the run registered or our record behind.
    @Test
    fun claim_should_UnregisterAndDeleteOurRecord_When_ReadBackThrows() {
        val registry = ActiveRunRegistry()
        val base = FakeLockFiles()
        var reads = 0
        val files = object : LockFiles by base {
            override fun readRecord(): RecordRead? {
                if (reads++ >= 1) error("boom")
                return base.readRecord()
            }
        }

        val thrown = runCatching { claim(files, registry) }.exceptionOrNull()

        assertIs<IllegalStateException>(thrown)
        assertFalse(registry.isActive(key))
        assertNull(base.record, "our freshly published record must not be orphaned")
    }

    // PR #683 review C1/C2: "inside the owner's window and quiet" means a LIVE owner's lock, never a removable one.
    private val otherHost = testRecord(instance = "x", host = "elsewhere", startedAtMs = 1_000)
    private fun force(files: FakeLockFiles, nowMs: Long) =
        LockRecovery.forceRemove(key, files, NEVER_ALIVE, self, ActiveRunRegistry()) { nowMs }

    @Test
    fun forceRemove_should_RefuseOtherHostOwner_When_HeartbeatFreshOrLockYoung_EvenInsideItsWindow() {
        val freshHeartbeat = FakeLockFiles(locks = mutableListOf(LockFileEntry("index.lock", 2_000)), record = RecordRead(otherHost.encode(), 99_000))
        assertEquals(DomainError.GitError.StaleLockFile(key, LockVerdict.OwnerUnknown(UnknownReason.ForeignLockSuspected)), force(freshHeartbeat, 100_000).leftOrNull())
        assertTrue(freshHeartbeat.deletions.isEmpty() && freshHeartbeat.record != null, "${freshHeartbeat.mutations}")

        val youngLock = FakeLockFiles(locks = mutableListOf(LockFileEntry("index.lock", 60_000)), record = RecordRead(otherHost.encode(), 2_000))
        assertIs<Either.Left<*>>(force(youngLock, 100_000))
        assertTrue(youngLock.deletions.isEmpty())
    }

    @Test
    fun forceRemove_should_RemoveOtherHostOwner_When_RecordAndLockBothIdleForMinutes() {
        val idle = FakeLockFiles(locks = mutableListOf(LockFileEntry("index.lock", 2_000)), record = RecordRead(otherHost.encode(), 3_000))

        assertIs<Either.Right<*>>(force(idle, 1_000_000))
        assertEquals(listOf("delete:index.lock", "deleteRecord"), idle.mutations)
    }

    @Test
    fun forceRemove_should_RequireMinutesOfQuiet_When_NoRecord_AndRefuseWhileGitRunning() {
        val young = FakeLockFiles(locks = mutableListOf(LockFileEntry("index.lock", 90_000)))
        assertIs<Either.Left<*>>(force(young, 100_000))
        assertTrue(young.deletions.isEmpty(), "a lock held for a long commit/rebase is not stale after 10 s")

        val running = FakeLockFiles(locks = mutableListOf(LockFileEntry("index.lock", 90_000))).apply { gitRunning = true }
        assertIs<Either.Left<*>>(force(running, 1_000_000))
        assertTrue(running.deletions.isEmpty(), "a visible git process for the repo blocks removal")

        val idle = FakeLockFiles(locks = mutableListOf(LockFileEntry("index.lock", 90_000)))
        assertIs<Either.Right<*>>(force(idle, 1_000_000))
        assertEquals(listOf("delete:index.lock"), idle.mutations)
    }

    @Test
    fun forceRemove_should_NotDeleteALockThatChangedSinceItWasExamined() {
        val files = FakeLockFiles(locks = mutableListOf(LockFileEntry("index.lock", 90_000))).apply { restatMtimeMs = 999_000 }

        assertIs<Either.Left<*>>(force(files, 1_000_000))
        assertTrue(files.deletions.isEmpty(), "a replaced lock (new git run) must survive: ${files.mutations}")
    }

    // PR #683 review M1: hostnames collide and change, so namespace-less "pid is gone" needs a matching machine id.
    @Test
    fun decide_should_NotCallOwnerDead_When_HostnameMatchesButMachineIdIsMissingOrDifferent() {
        val files = FakeLockFiles()
        fun verdict(record: LockOwnerRecord, me: LockOwnerRecord) = LockRecovery.decide(RecordRead(record.encode(), 1), files, NEVER_ALIVE, me)
        val noNs = testRecord(instance = "other", pidNs = null, bootId = null)
        val me = testRecord(instance = "self", pid = 1, pidNs = null, bootId = null)

        assertEquals(LockVerdict.OwnerUnknown(UnknownReason.LivenessUnknown), verdict(noNs, me), "no machine id on either side")
        assertEquals(LockVerdict.OwnerUnknown(UnknownReason.LivenessUnknown), verdict(noNs.copy(machineId = "a"), me), "record only")
        assertEquals(LockVerdict.OwnerUnknown(UnknownReason.DifferentHost), verdict(noNs.copy(machineId = "a"), me.copy(machineId = "b")))
        assertEquals(LockVerdict.ClearedDeadOwner, verdict(noNs.copy(machineId = "a"), me.copy(machineId = "a")))
    }
}
