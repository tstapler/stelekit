package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.DomainError
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OwnerHeartbeatTest {
    @Test
    fun heartbeat_should_AdvanceRecordMtimeEvery5s_AndStarvedHeartbeat_should_DegradeToOwnerUnknownNeverDelete() = runTest {
        val self = testRecord(instance = "self", pid = 1, startedAtMs = 0)
        val files = FakeLockFiles(record = RecordRead(self.encode(), 0))
        val heartbeat = OwnerHeartbeat(backgroundScope, files)

        heartbeat.start()
        advanceTimeBy(12_000)
        heartbeat.stop()

        assertTrue(files.mutations.count { it == "touch" } >= 2, "12 s of lease must touch at least twice: ${files.mutations}")

        // Starved: the owner died long ago, its heartbeat is stale, and a lock appeared well after it.
        val key = "/repo/.git/stelekit-sync.owner"
        val dead = testRecord(instance = "dead", startedAtMs = 1_000)
        val starved = FakeLockFiles(
            locks = mutableListOf(LockFileEntry("index.lock", 500_000)),
            record = RecordRead(dead.encode(), 20_000),
        )
        val result = LockRecovery.claim(key, starved, NEVER_ALIVE, testRecord(instance = "x", pid = 2), ActiveRunRegistry()) { 1_000_000L }
        assertEquals(
            DomainError.GitError.StaleLockFile(key, LockVerdict.OwnerUnknown(UnknownReason.ForeignLockSuspected)),
            result.leftOrNull(),
        )
        assertTrue(starved.deletions.isEmpty())
    }

    @Test
    fun heartbeat_should_StopTouching_When_Stopped() = runTest {
        val files = FakeLockFiles(record = RecordRead(testRecord().encode(), 0))
        val heartbeat = OwnerHeartbeat(backgroundScope, files, intervalMs = 1_000)

        heartbeat.start()
        advanceTimeBy(3_500)
        heartbeat.stop()
        val seen = files.mutations.size
        advanceTimeBy(10_000)

        assertEquals(seen, files.mutations.size)
    }
}
