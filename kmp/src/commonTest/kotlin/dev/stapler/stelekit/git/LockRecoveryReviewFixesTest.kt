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

    // Review round 2 (M2): a confirmed removal over an unsure owner still respects the foreign-lock window.
    @Test
    fun forceRemove_should_RefuseUnsureOwner_When_LockIsFreshOrOutsideTheRecordsWindow() {
        val other = testRecord(instance = "x", host = "elsewhere", startedAtMs = 1_000)
        val fresh = FakeLockFiles(locks = mutableListOf(LockFileEntry("index.lock", 99_000)), record = RecordRead(other.encode(), 1_000))
        val refused = LockRecovery.forceRemove(key, fresh, NEVER_ALIVE, self, ActiveRunRegistry()) { 100_000L }
        assertEquals(DomainError.GitError.StaleLockFile(key, LockVerdict.OwnerUnknown(UnknownReason.ForeignLockSuspected)), refused.leftOrNull())
        assertTrue(fresh.deletions.isEmpty(), "${fresh.mutations}")

        val outside = FakeLockFiles(locks = mutableListOf(LockFileEntry("index.lock", 60_000)), record = RecordRead(other.encode(), 1_000))
        assertIs<Either.Left<*>>(LockRecovery.forceRemove(key, outside, NEVER_ALIVE, self, ActiveRunRegistry()) { 100_000L })
        assertTrue(outside.deletions.isEmpty())
    }

    @Test
    fun forceRemove_should_RefuseRecentLockWithNoRecord_ButRemoveAQuietOne() {
        val recent = FakeLockFiles(locks = mutableListOf(LockFileEntry("index.lock", 98_000)))
        assertIs<Either.Left<*>>(LockRecovery.forceRemove(key, recent, NEVER_ALIVE, self, ActiveRunRegistry()) { 100_000L })
        assertTrue(recent.deletions.isEmpty())

        val quiet = FakeLockFiles(locks = mutableListOf(LockFileEntry("index.lock", 90_000)))
        assertIs<Either.Right<*>>(LockRecovery.forceRemove(key, quiet, NEVER_ALIVE, self, ActiveRunRegistry()) { 100_000L })
        assertEquals(listOf("delete:index.lock"), quiet.deletions)
    }
}
