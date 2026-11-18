package dev.stapler.stelekit.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TornRecordIsUnknownTest {
    private val self = testRecord(instance = "self", pid = 1)

    @Test
    fun decide_should_ReturnOwnerUnknownAndDeleteNothing_When_RecordIsEmptyTornOrUnparseable() {
        val half = testRecord().encode().take(20)
        for (content in listOf("", "   \n", half, "garbage", "{}", "[]", "null")) {
            val files = RecordingLockFiles(listOf(LockFileEntry("index.lock", 5_000)))
            // A liveness answer of Dead must be irrelevant: a torn record never reaches the probe.
            val verdict = LockRecovery.decide(RecordRead(content, 1), files, { _, _, _, _ -> Liveness.Dead }, self)

            assertEquals(LockVerdict.OwnerUnknown(UnknownReason.TornRecord), verdict, "content=<$content>")
            assertTrue(LockRecovery.deletableLocks(verdict, files).isEmpty())
            assertTrue(files.mutations.isEmpty())
        }
    }
}
