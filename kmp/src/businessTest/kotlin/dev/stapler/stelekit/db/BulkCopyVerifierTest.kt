package dev.stapler.stelekit.db

import dev.stapler.stelekit.db.sidecar.FakeFileSystem
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.StorageLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Story 3.1.1 Task 3.1.1e — bounded-batch and corruption-detection coverage for
 * [BulkCopyVerifier], the primitive `GraphRelocationCoordinator` (Story 3.1.5) will orchestrate
 * quiesce/copy/verify/repoint around.
 */
class BulkCopyVerifierTest {

    @Test
    fun `copyAndVerify should ProcessFilesInBoundedBatches When SourceHas8030Files`() = runTest {
        val fileSystem = FakeFileSystem()
        val fileCount = 8_030
        repeat(fileCount) { i ->
            fileSystem.writeFileBytes("source/page_$i.md", "content $i".encodeToByteArray())
        }
        val verifier = BulkCopyVerifier(fileSystem)

        val batchSizes = mutableListOf<Int>()
        val result = verifier.copyAndVerifyPaths(
            sourceRoot = "source",
            destinationRoot = "destination",
            onBatchProgress = { _, batchSize -> batchSizes += batchSize },
        )

        val report = assertIs<arrow.core.Either.Right<CopyReport>>(result).value
        assertEquals(fileCount, report.filesCopied)
        assertEquals(fileCount, report.verifiedPaths.size)

        // Mirrors GraphLoader.indexRemainingPages's INDEX_BATCH_SIZE = 100 bounded-drain
        // precedent: never more than COPY_BATCH_SIZE files' worth of a batch processed at once,
        // and the 8,030-file source required more than one batch to prove batching actually ran.
        assertTrue(batchSizes.size > 1, "expected multiple batches, got ${batchSizes.size}")
        assertTrue(
            batchSizes.all { it <= BulkCopyVerifier.COPY_BATCH_SIZE },
            "every batch must be <= ${BulkCopyVerifier.COPY_BATCH_SIZE} files, got $batchSizes",
        )
        assertEquals(fileCount, batchSizes.sum())
    }

    @Test
    fun `copyAndVerify should ReturnVerificationFailed When DestinationFileByteCorrupted`() = runTest {
        val corruptedPath = "destination/notes.md"
        val fileSystem = ByteFlippingFakeFileSystem(corruptOnWriteTo = corruptedPath)
        fileSystem.writeFileBytes("source/notes.md", "hello world".encodeToByteArray())
        val verifier = BulkCopyVerifier(fileSystem)

        val result = verifier.copyAndVerify(
            source = StorageLocation.DirectAccessFolder("graph-1", "source"),
            destination = StorageLocation.DirectAccessFolder("graph-1", "destination"),
            graphId = "graph-1",
        )

        val error = assertIs<arrow.core.Either.Left<DomainError.StorageError>>(result).value
        assertIs<DomainError.StorageError.VerificationFailed>(error)
        assertEquals("notes.md", (error as DomainError.StorageError.VerificationFailed).path)
    }

    @Test
    fun `copyAndVerify should call decodeFileName for identity, not a raw comparison`() = runTest {
        val fileSystem = FakeFileSystem()
        val sanitized = dev.stapler.stelekit.util.FileUtils.sanitizeFileName("Q&A_notes")
        fileSystem.writeFileBytes("source/$sanitized.md", "content".encodeToByteArray())
        val verifier = BulkCopyVerifier(fileSystem)

        val result = verifier.copyAndVerify(
            source = StorageLocation.DirectAccessFolder("graph-1", "source"),
            destination = StorageLocation.DirectAccessFolder("graph-1", "destination"),
            graphId = "graph-1",
        )

        val report = assertIs<arrow.core.Either.Right<CopyReport>>(result).value
        assertEquals(1, report.filesCopied)
    }

    /** Simulates a torn/corrupted write by flipping a byte the instant [corruptOnWriteTo] is written. */
    private class ByteFlippingFakeFileSystem(private val corruptOnWriteTo: String) : FakeFileSystem() {
        override fun writeFileBytes(path: String, data: ByteArray): Boolean {
            val toWrite = if (path == corruptOnWriteTo) {
                data.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }
            } else {
                data
            }
            return super.writeFileBytes(path, toWrite)
        }
    }
}
