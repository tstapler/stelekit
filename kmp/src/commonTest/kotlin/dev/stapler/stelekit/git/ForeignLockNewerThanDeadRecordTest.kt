package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.DomainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForeignLockNewerThanDeadRecordTest {
    private val key = "/repo/.git/stelekit-sync.owner"
    private val now = 1_000_000_000L
    private val self = testRecord(instance = "self", pid = 1, startedAtMs = now - 100)
    private val suspected = DomainError.GitError.StaleLockFile(key, LockVerdict.OwnerUnknown(UnknownReason.ForeignLockSuspected))

    // Dead owner started at 100_000_000 with its last heartbeat (record mtime) at 100_060_000.
    private val deadOwner = testRecord(instance = "dead", startedAtMs = 100_000_000)
    private val heartbeat = 100_060_000L

    private fun files(vararg locks: Pair<String, Long>, recordMtime: Long = heartbeat) = FakeLockFiles(
        locks = locks.map { LockFileEntry(it.first, it.second) }.toMutableList(),
        record = RecordRead(deadOwner.encode(), recordMtime),
    )

    private fun claim(files: FakeLockFiles) = LockRecovery.claim(key, files, NEVER_ALIVE, self, ActiveRunRegistry()) { now }

    private fun assertRefusedUntouched(files: FakeLockFiles) {
        assertEquals(suspected, claim(files).leftOrNull())
        assertTrue(files.deletions.isEmpty(), "nothing may be deleted, not even the record: ${files.mutations}")
        assertEquals(deadOwner.encode(), files.record?.content)
    }

    @Test
    fun claim_should_NotDeleteForeignLock_When_LockPostdatesDeadRecordHeartbeat() {
        // A week-old dead record, and an index.lock a terminal git created today.
        val weekOldHeartbeat = now - 7 * 86_400_000L
        val weekOld = testRecord(instance = "dead", startedAtMs = weekOldHeartbeat - 60_000)
        val files = FakeLockFiles(
            locks = mutableListOf(LockFileEntry("index.lock", now - 60_000)),
            record = RecordRead(weekOld.encode(), weekOldHeartbeat),
        )

        val left = LockRecovery.claim(key, files, NEVER_ALIVE, self, ActiveRunRegistry()) { now }.leftOrNull()

        assertEquals(suspected, left)
        assertTrue(files.deletions.isEmpty())
        assertEquals(weekOld.encode(), files.record?.content)
    }

    @Test
    fun claim_should_NotDeleteLock_When_LockOlderThanOwnerStartMinusTwoSeconds() {
        assertRefusedUntouched(files("index.lock" to deadOwner.startedAtMs - 2_001))
    }

    @Test
    fun claim_should_NotDeleteLock_When_LockModifiedWithinLastFiveSeconds() {
        // Inside the heartbeat window, but touched 4 s before the verdict: someone is still writing it.
        assertRefusedUntouched(files("index.lock" to now - 4_000, recordMtime = now - 3_000))
    }

    @Test
    fun claim_should_DeleteAndPublish_When_LockInsideWindowAndQuiet() {
        // Exactly on both inclusive bounds: start - 2 s and heartbeat + 10 s.
        val files = files("index.lock" to deadOwner.startedAtMs - 2_000, "refs/heads/main.lock" to heartbeat + 10_000)

        val result = claim(files)

        assertNull(result.leftOrNull())
        assertEquals(listOf("delete:index.lock", "delete:refs/heads/main.lock", "deleteRecord", "create"), files.mutations)
    }

    @Test
    fun claim_should_DeleteNothing_When_AnyOneAllowListedLockFailsABound() {
        val files = files("index.lock" to heartbeat, "refs/heads/main.lock" to heartbeat + 10_001)

        assertRefusedUntouched(files)
    }
}
