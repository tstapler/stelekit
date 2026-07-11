package dev.stapler.stelekit.transfer.qrcode

import arrow.core.Either
import arrow.core.left
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
import kotlinx.coroutines.channels.Channel
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

    @Test
    fun coordinator_should_EmitConcurrentTransferDetected_When_FrameFromDifferentTransferIdArrivesDuringActiveSession() = runBlocking {
        // Story 3.3.4 binding AC: a frame for a second TransferId dropped mid-session MUST emit a
        // user-visible signal — never a silent drop — while the active session (TransferId 7)
        // keeps making progress toward Success undisrupted.
        //
        // The data path applies .conflate() (by design — see QrTransferCoordinator KDoc), so a
        // producer that free-runs ahead of the collector can race-drop an individual injected
        // frame. This test avoids that race entirely: it drives frames through a manually-pumped
        // Channel and waits for the coordinator's own event confirming each frame was actually
        // processed before sending the next one — deterministic by construction, not by luck.
        val activeMarkdown = "- active session page body with enough content for several fountain chunks\n"
        val activeEncoder = FountainCodec.encoder(TransferId(7), activeMarkdown.encodeToByteArray(), maxFragmentBytes = 12).getOrNull()!!
        val foreignEncoder = FountainCodec.encoder(TransferId(9), "- foreign".encodeToByteArray(), maxFragmentBytes = 12).getOrNull()!!
        val (importService, pageRepo) = buildImportService()

        // Deterministic two-item prefix (the active session's first fragment, then the foreign
        // one) pumped one at a time through a Channel; once both are confirmed processed, the
        // flow transitions into the SAME abundant-redundant-parts generator this file's other
        // tests already rely on (see fakeReceiver above) — occasional .conflate() drops there are
        // harmless because the fountain stream vastly over-provisions coverage.
        val prefixChannel = Channel<ByteArray>(Channel.RENDEZVOUS)
        val pumpedReceiver = object : FrameTransportReceiver {
            override fun frames(): Flow<ByteArray> = flow {
                emit(prefixChannel.receive())
                emit(prefixChannel.receive())
                for (chunk in activeEncoder.parts().drop(1)) {
                    emit(ChunkFrameCodec.encode(chunk))
                    kotlinx.coroutines.yield()
                }
            }
        }

        val coordinator = QrTransferCoordinator(
            frameTransportReceiver = pumpedReceiver,
            cameraFrameSource = noOpCameraFrameSource(),
            qrImportService = importService,
            targetName = PageName("Concurrent Sender Page"),
        )

        coordinator.start()

        // Send + confirm the first active-session fragment is admitted (binds the session to
        // TransferId(7)) before introducing the foreign one.
        prefixChannel.send(ChunkFrameCodec.encode(activeEncoder.parts().first()))
        withTimeout(5_000) { coordinator.events.first { it is CoordinatorEvent.FragmentAdmitted } }

        // Inject a frame for a different TransferId — must be dropped with a visible signal, and
        // must NOT disturb the active session.
        prefixChannel.send(ChunkFrameCodec.encode(foreignEncoder.parts().first()))
        val concurrentEvent = withTimeout(5_000) {
            coordinator.events.first { it is CoordinatorEvent.ConcurrentTransferDetected }
        }
        assertIs<CoordinatorEvent.ConcurrentTransferDetected>(concurrentEvent)

        // The active TransferId(7) session must still be able to complete normally afterward —
        // the generator above keeps emitting its own redundant fountain stream automatically.
        val terminal = collectUntilTerminal(coordinator)
        assertIs<CoordinatorEvent.Success>(terminal.last(), "the active TransferId(7) session must still complete undisrupted")

        val saved = pageRepo.getPageByName("Concurrent Sender Page").first().getOrNull()
        assertTrue(saved != null, "the active session's own page must still be written")

        coordinator.close()
    }

    @Test
    fun start_should_EmitPreflightFailedWithPermissionDenied_When_CameraStreamFirstEmissionIsLeft() = runBlocking {
        // Bug 1 fix: a real CameraFrameSource (e.g. AndroidCameraFrameSource) emits exactly one
        // Left(PermissionDenied) then completes when the user denies the runtime prompt — this
        // must surface as CoordinatorEvent.PreflightFailed, never leave the coordinator silently
        // idling in Scanning.
        val (importService, pageRepo) = buildImportService()
        val deniedCameraFrameSource: CameraFrameSource = object : CameraFrameSource {
            override val isAvailable = true
            override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = flow {
                emit(DomainError.SensorError.PermissionDenied("camera").left())
            }
        }
        val neverEmittingReceiver = object : FrameTransportReceiver {
            override fun frames(): Flow<ByteArray> = flow { kotlinx.coroutines.awaitCancellation() }
        }

        val coordinator = QrTransferCoordinator(
            frameTransportReceiver = neverEmittingReceiver,
            cameraFrameSource = deniedCameraFrameSource,
            qrImportService = importService,
            targetName = PageName("Permission Denied Page"),
        )

        coordinator.start()

        val event = withTimeout(5_000) {
            coordinator.events.first { it is CoordinatorEvent.PreflightFailed }
        }
        val preflightFailed = assertIs<CoordinatorEvent.PreflightFailed>(event)
        assertIs<DomainError.SensorError.PermissionDenied>(preflightFailed.reason)

        val saved = pageRepo.getPageByName("Permission Denied Page").first().getOrNull()
        assertNull(saved, "no write must occur when the pre-flight camera stream reports PermissionDenied")

        coordinator.close()
    }
}
