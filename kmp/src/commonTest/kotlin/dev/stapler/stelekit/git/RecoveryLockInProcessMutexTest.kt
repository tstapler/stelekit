package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.DomainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryLockInProcessMutexTest {
    private val key = "/repo/.git/stelekit-sync.owner"
    private val dead = testRecord(instance = "dead", startedAtMs = 1_000)

    private fun files() = FakeLockFiles(
        locks = mutableListOf(LockFileEntry("index.lock", 2_000)),
        record = RecordRead(dead.encode(), 4_000),
    )

    @Test
    fun twoClaimersInOneJvm_should_BeExcludedByInProcessMutex_AndOverlappingFileLockExceptionHandled() {
        val shared = ActiveRunRegistry()
        val first = files()
        val second = files()
        var secondResult: Any? = null
        // The second claimer starts while the first is inside tryLockRecovery, i.e. already holding the mutex.
        first.onTryLock = {
            secondResult = LockRecovery.claim(key, second, NEVER_ALIVE, testRecord(instance = "b", pid = 2), shared) { 100_000L }
        }

        LockRecovery.claim(key, first, NEVER_ALIVE, testRecord(instance = "a", pid = 1), shared) { 100_000L }

        assertEquals(0, second.tryLockCalls, "the in-process mutex must stop the second claimer before the file lock")
        assertTrue(second.mutations.isEmpty())
        assertEquals(DomainError.GitError.SyncInProgress(null), (secondResult as arrow.core.Either.Left<*>).value)
    }

    @Test
    fun claim_should_ReturnSyncInProgress_When_AdapterReportsOverlappingLockAsContended() {
        // JavaIoLockFiles maps OverlappingFileLockException to null; the claim treats null as contended.
        val files = files().apply { recoveryMode = FakeLockFiles.RecoveryMode.Contended }

        val result = LockRecovery.claim(key, files, NEVER_ALIVE, testRecord(instance = "a", pid = 1), ActiveRunRegistry()) { 100_000L }

        assertEquals(DomainError.GitError.SyncInProgress(null), result.leftOrNull())
        assertTrue(files.deletions.isEmpty())
    }

    @Test
    fun mutex_should_BeReleasedAfterClaim_AndIndependentPerKey() {
        val registry = ActiveRunRegistry()
        assertTrue(registry.tryEnterRecovery("a"))
        assertTrue(registry.tryEnterRecovery("b"))
        assertFalse(registry.tryEnterRecovery("a"))
        registry.exitRecovery("a")
        assertTrue(registry.tryEnterRecovery("a"))

        val files = files()
        LockRecovery.claim(key, files, NEVER_ALIVE, testRecord(instance = "a", pid = 1), registry) { 100_000L }
        assertTrue(registry.tryEnterRecovery(key), "claim must exit the in-process mutex in finally")
    }
}
