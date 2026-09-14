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

        // (processed, total) as reported by each onBatchProgress call.
        val progressCalls = mutableListOf<Pair<Int, Int>>()
        val result = verifier.copyAndVerifyPaths(
            sourceRoot = "source",
            destinationRoot = "destination",
            onBatchProgress = { processed, total -> progressCalls += processed to total },
        )

        val report = assertIs<arrow.core.Either.Right<CopyReport>>(result).value
        assertEquals(fileCount, report.filesCopied)
        assertEquals(fileCount, report.verifiedPaths.size)

        // Mirrors GraphLoader.indexRemainingPages's INDEX_BATCH_SIZE = 100 bounded-drain
        // precedent: infer batching from the number of progress calls — the 8,030-file source
        // required more than one batch to prove batching actually ran.
        assertTrue(progressCalls.size > 1, "expected multiple batches, got ${progressCalls.size}")

        // `total` is the WHOLE operation's file count, fixed on every single call — never the
        // current batch's size. Regression guard for the progress-total/batch-size mismatch:
        // total used to reset to <= COPY_BATCH_SIZE every batch instead of staying at fileCount.
        assertTrue(
            progressCalls.all { (_, total) -> total == fileCount },
            "total must stay fixed at $fileCount on every call, got ${progressCalls.map { it.second }}",
        )

        // `processed` climbs monotonically in steps no larger than COPY_BATCH_SIZE and ends
        // exactly at fileCount.
        var previousProcessed = 0
        for ((processed, _) in progressCalls) {
            assertTrue(
                processed - previousProcessed <= BulkCopyVerifier.COPY_BATCH_SIZE,
                "batch step must be <= ${BulkCopyVerifier.COPY_BATCH_SIZE}, got ${processed - previousProcessed}",
            )
            previousProcessed = processed
        }
        assertEquals(fileCount, previousProcessed)
    }

    @Test
    fun `copyAndVerify should ReportProcessedGreaterThanOrEqualToTotalOnlyOnFinalBatch When SourceHas250Files`() = runTest {
        val fileSystem = FakeFileSystem()
        val fileCount = 250 // > COPY_BATCH_SIZE (100) -> 3 batches: 100, 100, 50
        repeat(fileCount) { i ->
            fileSystem.writeFileBytes("source/page_$i.md", "content $i".encodeToByteArray())
        }
        val verifier = BulkCopyVerifier(fileSystem)

        val progressCalls = mutableListOf<Pair<Int, Int>>()
        val result = verifier.copyAndVerifyPaths(
            sourceRoot = "source",
            destinationRoot = "destination",
            onBatchProgress = { processed, total -> progressCalls += processed to total },
        )

        assertIs<arrow.core.Either.Right<CopyReport>>(result)
        assertEquals(listOf(100 to 250, 200 to 250, 250 to 250), progressCalls)

        // GraphRelocationCoordinator.copyIntoStagingThenRepoint fires Verifying exactly when
        // `processed >= total` — reproducing that same predicate here must be true ONLY on the
        // last of the three batches. Before the progress-total/batch-size fix this fired after
        // the FIRST 100-file batch (100 >= batchSize-as-total(100)), i.e. long before the whole
        // graph was actually copied and verified.
        val verifyingFiredAt = progressCalls.indexOfFirst { (processed, total) -> total > 0 && processed >= total }
        assertEquals(progressCalls.lastIndex, verifyingFiredAt, "Verifying must fire only after the LAST batch")
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
