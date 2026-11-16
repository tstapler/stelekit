package dev.stapler.stelekit.git

import dev.stapler.stelekit.error.DomainError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActiveRunNotClearedOwnTest {
    private val key = "/repo/.git/stelekit-sync.owner"
    private val self = testRecord(instance = "self", pid = 1, startedAtMs = 50_000)

    @Test
    fun claim_should_ReturnSyncInProgressAndKeepLiveLock_When_OwnInstanceIdHasActiveRunOnCanonicalRecordPath() {
        val files = FakeLockFiles(
            locks = mutableListOf(LockFileEntry("index.lock", 60_000)),
            record = RecordRead(self.encode(), 99_000),
        )
        val registry = ActiveRunRegistry().also { it.register(key, SyncOrigin.UiPress) }

        val result = LockRecovery.claim(key, files, NEVER_ALIVE, self, registry) { 100_000L }

        assertEquals(DomainError.GitError.SyncInProgress(SyncOrigin.UiPress), result.leftOrNull())
        assertTrue(files.mutations.isEmpty(), "a live run's lock and record must survive: ${files.mutations}")
        assertEquals(0, files.tryLockCalls, "the registry check precedes any recovery work")
    }

    @Test
    fun claim_should_TreatOwnRecordAsCorpse_When_NoRunRegistered() {
        val files = FakeLockFiles(
            locks = mutableListOf(LockFileEntry("index.lock", 60_000)),
            record = RecordRead(self.encode(), 99_000),
        )

        LockRecovery.claim(key, files, NEVER_ALIVE, self, ActiveRunRegistry()) { 100_000L }

        assertEquals(1, files.tryLockCalls, "ClearedOwn is a deleting verdict, so it takes the recovery lock")
    }
}
