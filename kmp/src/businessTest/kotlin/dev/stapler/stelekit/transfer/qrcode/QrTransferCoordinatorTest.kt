package dev.stapler.stelekit.transfer.qrcode

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.GraphLoader
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.sensor.CameraFrame
import dev.stapler.stelekit.platform.sensor.CameraFrameSource
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.transfer.FrameTransportReceiver
import dev.stapler.stelekit.transfer.TransferId
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Story 3.2.2 acceptance criteria (Task 3.2.2d): [QrTransferCoordinator] is independently
 * unit-testable without a ViewModel or Compose, using fakes for its two constructor collaborators
 * — a fake [FrameTransportReceiver] for the transfer-data path and a fake diagnostics `scan`
 * function for [ScanHint] derivation.
 */
class QrTransferCoordinatorTest {

    private class NoOpFileSystem : FileSystem {
        override fun getDefaultGraphPath() = ""
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = false
        override fun listFiles(path: String): List<String> = emptyList()
        override fun listDirectories(path: String): List<String> = emptyList()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = false
        override fun deleteFile(path: String) = false
        override fun pickDirectory(): String? = null
        override fun getLastModifiedTime(path: String): Long? = null
    }

    private fun buildImportService(): Pair<QrImportService, InMemoryPageRepository> {
        val pageRepo = InMemoryPageRepository()
        val blockRepo = InMemoryBlockRepository()
        val actor = DatabaseWriteActor(blockRepo, pageRepo)
        val graphLoader = GraphLoader(
            fileSystem = NoOpFileSystem(),
            pageRepository = pageRepo,
            blockRepository = blockRepo,
            externalWriteActor = actor,
        )
        return QrImportService(graphLoader, pageRepo, actor) to pageRepo
    }

    /**
     * Emits [FountainChunk]s from [encoder] (the true `bc-ur` fountain sequence), wire-encoded.
     * Yields after each emit so the diagnostics coroutine and any test collector get a fair
     * scheduling chance between chunks on `Dispatchers.Default` — this loop is otherwise CPU-bound
     * and would starve other coroutines on a single-threaded test dispatcher.
     */
    private fun fakeReceiver(encoder: FountainEncoder): FrameTransportReceiver = object : FrameTransportReceiver {
        override fun frames(): Flow<ByteArray> = flow {
            for (chunk in encoder.parts()) {
                emit(ChunkFrameCodec.encode(chunk))
                kotlinx.coroutines.yield()
            }
        }
    }

    private fun noOpCameraFrameSource(available: Boolean = true): CameraFrameSource = object : CameraFrameSource {
        override val isAvailable = available
        override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = flow {
            // A single benign frame is enough to exercise the diagnostics path once, then idle.
            emit(CameraFrame(luminanceBytes = ByteArray(4) { 200.toByte() }, width = 2, height = 2, rotationDegrees = 0).right())
        }
    }

    /**
     * Collects every [CoordinatorEvent] emitted until a terminal (Success/Failed) event, or
     * timeout. [QrTransferCoordinator.events] is a buffered (non-conflating) `SharedFlow`, so this
     * reliably observes every transient milestone (e.g. `Reassembling`) even for a payload small
     * enough to reassemble in a single tick.
     */
    private suspend fun collectUntilTerminal(
        coordinator: QrTransferCoordinator,
        timeoutMs: Long = 5_000,
    ): List<CoordinatorEvent> = withTimeout(timeoutMs) {
        val seen = mutableListOf<CoordinatorEvent>()
        coordinator.events.first { event ->
            seen.add(event)
            event is CoordinatorEvent.Success || event is CoordinatorEvent.Failed
        }
        seen
    }

    @Test
    fun start_should_TransitionScanningReassemblingImportingSuccess_When_StreamDeliversEnoughPartsForPageBody() = runBlocking {
        val markdown = "- page body\n"
        val encoder = FountainCodec.encoder(TransferId(42), markdown.encodeToByteArray(), maxFragmentBytes = 12).getOrNull()!!
        val (importService, pageRepo) = buildImportService()

        val coordinator = QrTransferCoordinator(
            frameTransportReceiver = fakeReceiver(encoder),
            cameraFrameSource = noOpCameraFrameSource(),
            qrImportService = importService,
            targetName = PageName("Page Body Page"),
        )

        // Nothing written before Success — QrImportService is invoked only after reassemble()
        // yields Right(VerifiedTransferPayload).
        coordinator.start()

        val events = collectUntilTerminal(coordinator)
        val successEvent = assertIs<CoordinatorEvent.Success>(events.last())
        assertTrue(events.any { it is CoordinatorEvent.Reassembling }, "expected a Reassembling event, got $events")
        assertTrue(events.any { it is CoordinatorEvent.Importing }, "expected an Importing event, got $events")

        val saved = pageRepo.getPageByName(successEvent.pageName.value).first().getOrNull()
        assertTrue(saved != null, "page must be written only after reassembly succeeded")

        coordinator.close()
    }

    @Test
    fun coordinator_should_ReconstructPayloadAndDeriveWrongCodeHint_When_UsingFakeFrameTransportReceiverAndFakeQrScanner() = runBlocking {
        val markdown = "- another page body with enough content to need several fountain chunks\n"
        val encoder = FountainCodec.encoder(TransferId(7), markdown.encodeToByteArray(), maxFragmentBytes = 12).getOrNull()!!
        val (importService, _) = buildImportService()

        val coordinator = QrTransferCoordinator(
            frameTransportReceiver = fakeReceiver(encoder),
            cameraFrameSource = noOpCameraFrameSource(),
            qrImportService = importService,
            targetName = PageName("Wrong Code Page"),
            // Fake diagnostics scanner: ALWAYS reports a foreign QR, regardless of the real frame
            // content — its output must never feed ChunkBuffer, only the hint.
            scan = { ScanResult.NotSteleKitCode },
        )

        coordinator.start()
        val events = collectUntilTerminal(coordinator)

        // Reassembly must still succeed despite the WrongCode diagnostics hint — the fake scan
        // function's output never reached ChunkBuffer (it only ever influences `hint`).
        assertIs<CoordinatorEvent.Success>(events.last())

        val fragmentEvents = events.filterIsInstance<CoordinatorEvent.FragmentAdmitted>()
        assertTrue(fragmentEvents.isNotEmpty(), "expected at least one FragmentAdmitted event")
        assertTrue(
            fragmentEvents.any { it.hint == ScanHint.WrongCode },
            "expected at least one FragmentAdmitted event carrying hint=WrongCode among $fragmentEvents",
        )

        coordinator.close()
    }

    @Test
    fun cancel_should_TransitionCancelledWithNoWrite_When_CalledDuringScanning() = runBlocking {
        // Never-completing receiver: emits nothing, so the session never reaches isComplete()
        // before cancel() is called.
        val (importService, pageRepo) = buildImportService()
        val neverEmittingReceiver = object : FrameTransportReceiver {
            override fun frames(): Flow<ByteArray> = flow { kotlinx.coroutines.awaitCancellation() }
        }

        val coordinator = QrTransferCoordinator(
            frameTransportReceiver = neverEmittingReceiver,
            cameraFrameSource = noOpCameraFrameSource(),
            qrImportService = importService,
            targetName = PageName("Cancelled Page"),
        )

        coordinator.start()
        coordinator.cancel()

        val state = withTimeout(5_000) { coordinator.events.first { it is CoordinatorEvent.Cancelled } }
        assertIs<CoordinatorEvent.Cancelled>(state)
        val saved = pageRepo.getPageByName("Cancelled Page").first().getOrNull()
        assertNull(saved, "no write must occur after cancel()")

        coordinator.close()
    }
}
