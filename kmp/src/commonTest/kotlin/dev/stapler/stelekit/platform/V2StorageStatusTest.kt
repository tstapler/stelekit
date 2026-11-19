package dev.stapler.stelekit.platform

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class V2StorageStatusTest {
    @Test
    fun privateWindowHint_should_BeSuspectedBelowTheQuotaThreshold() {
        assertTrue(PrivateWindowHint.from(0L))
        assertTrue(PrivateWindowHint.from(PrivateWindowHint.QUOTA_THRESHOLD_BYTES - 1))
        assertFalse(PrivateWindowHint.from(PrivateWindowHint.QUOTA_THRESHOLD_BYTES))
        assertFalse(PrivateWindowHint.from(10L * 1024 * 1024 * 1024))
    }

    @Test
    fun privateWindowHint_should_NotSuspectAnUnknownEstimate() {
        assertFalse(PrivateWindowHint.from(null))
    }

    @Test
    fun unsupportedPersistence_should_AnswerUnsupported() = runTest {
        assertEquals(PersistenceState.Unsupported, UnsupportedBrowserPersistence.request())
    }

    @Test
    fun onlyReady_should_BeReady() {
        assertTrue(V2StorageStatus.Ready(sessionOnly = false, privateSuspected = false).isReady)
        assertFalse(V2StorageStatus.Checking.isReady)
        assertFalse(V2StorageStatus.Unavailable(StorageReason.NoOpfs).isReady)
    }

    @Test
    fun storageReason_should_HaveTheSevenTableIds() {
        assertEquals(
            listOf("NoOpfs", "NoCreateWritable", "NoAtomicReplace", "NoSliceRead", "PreloadFailed", "PreloadTooLarge", "CheckTimedOut"),
            StorageReason.entries.map { it.name },
        )
    }
}
