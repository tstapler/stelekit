package dev.stapler.stelekit.error

import kotlin.test.Test
import kotlin.test.assertTrue

class DomainErrorStorageErrorTest {

    @Test
    fun verificationFailed_message_includesPathAndReason() {
        val error = DomainError.StorageError.VerificationFailed(path = "/graphs/g1/notes.md", reason = "hash mismatch")
        assertTrue(error.message.isNotBlank())
        assertTrue(error.message.contains("/graphs/g1/notes.md"))
        assertTrue(error.message.contains("hash mismatch"))
    }

    @Test
    fun sourceInFlight_message_includesReason() {
        val error = DomainError.StorageError.SourceInFlight(reason = "git sync in progress")
        assertTrue(error.message.isNotBlank())
        assertTrue(error.message.contains("git sync in progress"))
    }

    @Test
    fun destinationNotWritable_message_includesPath() {
        val error = DomainError.StorageError.DestinationNotWritable(path = "content://com.android.externalstorage/tree/primary")
        assertTrue(error.message.isNotBlank())
        assertTrue(error.message.contains("content://com.android.externalstorage/tree/primary"))
    }

    @Test
    fun partialCopyDetected_message_includesPath() {
        val error = DomainError.StorageError.PartialCopyDetected(path = "/staging/g1/pages/foo.md")
        assertTrue(error.message.isNotBlank())
        assertTrue(error.message.contains("/staging/g1/pages/foo.md"))
    }

    @Test
    fun insufficientSpace_message_includesBothByteCounts() {
        val error = DomainError.StorageError.InsufficientSpace(requiredBytes = 5_000_000L, availableBytes = 1_000L)
        assertTrue(error.message.isNotBlank())
        assertTrue(error.message.contains("5000000"))
        assertTrue(error.message.contains("1000"))
    }

    @Test
    fun quiesceTimedOut_message_includesWaitedMs() {
        val error = DomainError.StorageError.QuiesceTimedOut(waitedMs = 30_000L)
        assertTrue(error.message.isNotBlank())
        assertTrue(error.message.contains("30000"))
    }

    @Test
    fun reopenFailed_message_includesGraphId() {
        val error = DomainError.StorageError.ReopenFailed(graphId = "g1")
        assertTrue(error.message.isNotBlank())
        assertTrue(error.message.contains("g1"))
    }
}
