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
import dev.stapler.stelekit.repository.InMemoryBlockRepository
import dev.stapler.stelekit.repository.InMemoryPageRepository
import dev.stapler.stelekit.transfer.ChunkIndex
import dev.stapler.stelekit.transfer.FrameTransportReceiver
import dev.stapler.stelekit.transfer.PayloadChecksum
import dev.stapler.stelekit.transfer.TransferId
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * — a fake [FrameTransportReceiver] for the transfer-data path and a fake [QrScanner] for
 * diagnostics + pre-flight (Bug 3 fix — an actual injected instance, not a function reference).
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

    /**
     * A fake [QrScanner] (Bug 3 fix — an actual injected instance, not a `scan` function
     * reference): [decodeResult] drives [QrScanner.decode]; [frames] drives [QrScanner.frameStream]
     * — a single benign frame by default, enough to exercise the diagnostics path once, then idle.
     */
    private fun fakeQrScanner(
        decodeResult: ScanResult = ScanResult.NoCodeDetected,
        frames: Flow<Either<DomainError.SensorError, CameraFrame>> = flow {
            emit(CameraFrame(luminanceBytes = ByteArray(4) { 200.toByte() }, width = 2, height = 2, rotationDegrees = 0).right())
        },
    ): QrScanner = object : QrScanner {
        override fun decode(frame: CameraFrame): ScanResult = decodeResult
        override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = frames
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
        val envelopeBytes = TransferPayloadEnvelope.wrap(PageName("Page Body Page"), markdown)
        val encoder = FountainCodec.encoder(TransferId(42), envelopeBytes, maxFragmentBytes = 12).getOrNull()!!
        val (importService, pageRepo) = buildImportService()

        val coordinator = QrTransferCoordinator(
            frameTransportReceiver = fakeReceiver(encoder),
            qrImportService = importService,
        )

        // Nothing written before Success — QrImportService is invoked only after reassemble()
        // yields Right(VerifiedTransferPayload) AND TransferPayloadEnvelope.unwrap() yields Right.
        coordinator.start()

        val events = collectUntilTerminal(coordinator)
        val successEvent = assertIs<CoordinatorEvent.Success>(events.last())
        assertEquals("Page Body Page", successEvent.pageName.value, "the real decoded page name must be used, not a synthesized placeholder")
        assertTrue(events.any { it is CoordinatorEvent.Reassembling }, "expected a Reassembling event, got $events")
        assertTrue(events.any { it is CoordinatorEvent.Importing }, "expected an Importing event, got $events")

        val saved = pageRepo.getPageByName(successEvent.pageName.value).first().getOrNull()
        assertTrue(saved != null, "page must be written only after reassembly succeeded")

        coordinator.close()
    }

    @Test
    fun coordinator_should_ReconstructPayloadAndDeriveWrongCodeHint_When_UsingFakeFrameTransportReceiverAndFakeQrScanner() = runBlocking {
        val markdown = "- another page body with enough content to need several fountain chunks\n"
        val envelopeBytes = TransferPayloadEnvelope.wrap(PageName("Wrong Code Page"), markdown)
        val encoder = FountainCodec.encoder(TransferId(7), envelopeBytes, maxFragmentBytes = 12).getOrNull()!!
        val (importService, _) = buildImportService()

        val coordinator = QrTransferCoordinator(
            frameTransportReceiver = fakeReceiver(encoder),
            qrImportService = importService,
            // Fake diagnostics scanner: ALWAYS reports a foreign QR, regardless of the real frame
            // content — its output must never feed ChunkBuffer, only the hint.
            qrScanner = fakeQrScanner(ScanResult.NotSteleKitCode),
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
            qrImportService = importService,
        )

        coordinator.start()
        coordinator.cancel()

        val state = withTimeout(5_000) { coordinator.events.first { it is CoordinatorEvent.Cancelled } }
        assertIs<CoordinatorEvent.Cancelled>(state)
        val saved = pageRepo.getPageByName("Cancelled Page").first().getOrNull()
        assertNull(saved, "no write must occur after cancel() (nothing was ever written under this name)")

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
        val activeEnvelopeBytes = TransferPayloadEnvelope.wrap(PageName("Concurrent Sender Page"), activeMarkdown)
        val activeEncoder = FountainCodec.encoder(TransferId(7), activeEnvelopeBytes, maxFragmentBytes = 12).getOrNull()!!
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
            qrImportService = importService,
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
    fun start_should_EmitFailedWithPayloadTooLarge_When_FirstFrameClaimsPayloadLenExceedingMaxPayloadBytes() = runBlocking {
        // Diagnosability gap this test guards against: without this wiring, a receiver pointed at
        // an oversized sender's QR stream just sits in Scanning forever with uniqueFragments stuck
        // at 0 — indistinguishable from "no QR in view." Story 1.2.3 AC requires a terminal,
        // distinct PayloadTooLarge failure instead.
        val (importService, pageRepo) = buildImportService()
        val maxPayloadBytes = 65536
        val oversizedChunk = FountainChunk(
            transferId = TransferId(1),
            chunkIndex = ChunkIndex(0),
            payloadLen = 5_000_000,
            payloadCrc = PayloadChecksum(0),
            fragment = byteArrayOf(1, 2, 3),
        )
        val oversizedReceiver = object : FrameTransportReceiver {
            override fun frames(): Flow<ByteArray> = flow { emit(ChunkFrameCodec.encode(oversizedChunk)) }
        }

        val coordinator = QrTransferCoordinator(
            frameTransportReceiver = oversizedReceiver,
            qrImportService = importService,
            maxPayloadBytes = maxPayloadBytes,
        )

        coordinator.start()

        val event = withTimeout(5_000) { coordinator.events.first { it is CoordinatorEvent.Failed } }
        val failed = assertIs<CoordinatorEvent.Failed>(event)
        val payloadTooLarge = assertIs<DomainError.QrTransferError.PayloadTooLarge>(failed.error)
        assertEquals(5_000_000, payloadTooLarge.sizeBytes)
        assertEquals(maxPayloadBytes, payloadTooLarge.maxBytes)

        val saved = pageRepo.getPageByName("Oversized Sender Page").first().getOrNull()
        assertNull(saved, "no write must occur after a PayloadTooLarge rejection")

        coordinator.close()
    }

    @Test
    fun start_should_EmitPreflightFailedWithPermissionDenied_When_CameraStreamFirstEmissionIsLeft() = runBlocking {
        // Bug 1 fix: a real CameraFrameSource (e.g. AndroidCameraFrameSource) emits exactly one
        // Left(PermissionDenied) then completes when the user denies the runtime prompt — this
        // must surface as CoordinatorEvent.PreflightFailed, never leave the coordinator silently
        // idling in Scanning.
        val (importService, pageRepo) = buildImportService()
        val deniedQrScanner = fakeQrScanner(
            frames = flow { emit(DomainError.SensorError.PermissionDenied("camera").left()) },
        )
        val neverEmittingReceiver = object : FrameTransportReceiver {
            override fun frames(): Flow<ByteArray> = flow { kotlinx.coroutines.awaitCancellation() }
        }

        val coordinator = QrTransferCoordinator(
            frameTransportReceiver = neverEmittingReceiver,
            qrImportService = importService,
            qrScanner = deniedQrScanner,
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
