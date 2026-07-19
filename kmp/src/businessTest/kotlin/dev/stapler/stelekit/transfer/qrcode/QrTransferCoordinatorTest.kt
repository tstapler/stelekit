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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
     * Records every [CoordinatorEvent] from a coordinator's `events` via exactly ONE subscription,
     * forwarding into an unbounded [Channel].
     *
     * [QrTransferCoordinator.events] is a `replay = 1` `SharedFlow`. Calling `.first { ... }`
     * directly on it — repeatedly, once per awaited milestone, as this file used to — creates a
     * fresh subscription each time. Between one collector's cancellation (once its predicate
     * matches) and the next one's subscription, the coordinator can keep emitting further events
     * (e.g. more `FragmentAdmitted` for a still-active session); with `replay = 1` only the single
     * most recent event survives, so an event a later call is waiting for can be overwritten and
     * permanently lost before the new subscriber attaches. That race is invisible on a fast local
     * run but reliably manifests as an intermittent `TimeoutCancellationException` under CI
     * scheduling pressure (see stelekit CI history for this file).
     *
     * A [Channel] has real queueing semantics — every emitted event is buffered regardless of
     * whether a consumer is currently reading — so subscribing exactly once, before
     * [QrTransferCoordinator.start] is even called, and draining sequentially via
     * [awaitEvent]/[awaitTerminal] afterward avoids the resubscription race above.
     *
     * **This alone is not sufficient** — a SECOND, distinct race remains, and was the actual
     * cause of a later CI-only flake here (`AssertionError: expected a Reassembling event, got
     * [Success(...)]`): constructing this class only *schedules* its collector coroutine
     * (`scope.launch { ... }`, not run synchronously); if the coordinator's `start()` — called by
     * the test immediately after construction — runs to completion on `Dispatchers.Default`
     * before this collector's launch actually gets dispatched, the collector's first subscription
     * to the `replay = 1` `events` flow attaches AFTER the early milestone events already fired,
     * so it only ever sees the single most recent replayed event. See the `CoroutineStart.UNDISPATCHED`
     * comment on [job] for the fix — this class's constructor must fully register its subscription
     * before returning, not just enqueue a coroutine that will eventually do so.
     */
    private class EventRecorder(coordinator: QrTransferCoordinator, scope: CoroutineScope) {
        private val channel = Channel<CoordinatorEvent>(Channel.UNLIMITED)

        // CoroutineStart.UNDISPATCHED (root-cause fix): a plain `scope.launch { ... }` only
        // SCHEDULES the collector coroutine — it does not guarantee `coordinator.events.collect`
        // has actually subscribed to the SharedFlow before this constructor returns. `events` is
        // `replay = 1`: if the coordinator's `start()` (called by the test immediately after
        // constructing this recorder) runs its whole pipeline — FragmentAdmitted, Reassembling,
        // Importing, Success — before this launched coroutine gets its first turn on the
        // dispatcher, the late-attaching subscriber only receives the single most recent replayed
        // event (Success) via the replay cache; every earlier event emitted before it subscribed
        // is invisible to it, even though the coordinator emitted them correctly. This is
        // invisible on a slow/idle scheduler (the collector reliably wins the race to subscribe
        // before the pipeline finishes) but reliably manifests as "expected a Reassembling event,
        // got [Success(...)]" once producer and collector are close enough in speed that ordering
        // isn't guaranteed (e.g. CI's shared/contended runners, or a synthetic frame source with
        // no artificial delay). `UNDISPATCHED` runs the coroutine body synchronously up to its
        // first real suspension point, so the SharedFlow subscription (registered before
        // `collect` ever suspends waiting for a value) is guaranteed live by the time this
        // constructor returns — the collector can never lose this race, regardless of scheduler
        // pressure. See stelekit CI history for this file's prior lost-event race (a different
        // race than this one — that one was about repeated `.first {}` resubscription; this is
        // about the FIRST subscription's timing relative to `start()`).
        private val job: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.events.collect { channel.send(it) }
        }

        suspend fun awaitEvent(timeoutMs: Long = 5_000, predicate: (CoordinatorEvent) -> Boolean): CoordinatorEvent =
            withTimeout(timeoutMs) { channel.receiveAsFlow().first(predicate) }

        /** Drains events until a terminal (Success/Failed) event, returning everything seen. */
        suspend fun awaitTerminal(timeoutMs: Long = 5_000): List<CoordinatorEvent> = withTimeout(timeoutMs) {
            val seen = mutableListOf<CoordinatorEvent>()
            channel.receiveAsFlow().first { event ->
                seen.add(event)
                event is CoordinatorEvent.Success || event is CoordinatorEvent.Failed
            }
            seen
        }

        /** Must be called once the test is done with the coordinator — the background collector
         * job never completes on its own (a `SharedFlow` never signals completion), so leaving
         * this uncancelled would hang the enclosing `runBlocking` forever on the success path. */
        fun close() {
            job.cancel()
        }
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
        val recorder = EventRecorder(coordinator, this)

        // Nothing written before Success — QrImportService is invoked only after reassemble()
        // yields Right(VerifiedTransferPayload) AND TransferPayloadEnvelope.unwrap() yields Right.
        coordinator.start()

        val events = recorder.awaitTerminal()
        val successEvent = assertIs<CoordinatorEvent.Success>(events.last())
        assertEquals("Page Body Page", successEvent.pageName.value, "the real decoded page name must be used, not a synthesized placeholder")
        assertTrue(events.any { it is CoordinatorEvent.Reassembling }, "expected a Reassembling event, got $events")
        assertTrue(events.any { it is CoordinatorEvent.Importing }, "expected an Importing event, got $events")

        val saved = pageRepo.getPageByName(successEvent.pageName.value).first().getOrNull()
        assertTrue(saved != null, "page must be written only after reassembly succeeded")

        coordinator.close()
        recorder.close()
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
        val recorder = EventRecorder(coordinator, this)

        coordinator.start()
        val events = recorder.awaitTerminal()

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
        recorder.close()
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
        val recorder = EventRecorder(coordinator, this)

        coordinator.start()
        coordinator.cancel()

        val state = recorder.awaitEvent { it is CoordinatorEvent.Cancelled }
        assertIs<CoordinatorEvent.Cancelled>(state)
        val saved = pageRepo.getPageByName("Cancelled Page").first().getOrNull()
        assertNull(saved, "no write must occur after cancel() (nothing was ever written under this name)")

        coordinator.close()
        recorder.close()
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
        // Waiting on those confirming events goes through EventRecorder (see its KDoc) rather than
        // repeated `coordinator.events.first { ... }` calls, so a burst of unrelated events
        // between two awaited milestones can never race the replay=1 buffer and drop the one this
        // test is actually waiting for.
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
        val recorder = EventRecorder(coordinator, this)

        coordinator.start()

        // Send + confirm the first active-session fragment is admitted (binds the session to
        // TransferId(7)) before introducing the foreign one.
        prefixChannel.send(ChunkFrameCodec.encode(activeEncoder.parts().first()))
        recorder.awaitEvent { it is CoordinatorEvent.FragmentAdmitted }

        // Inject a frame for a different TransferId — must be dropped with a visible signal, and
        // must NOT disturb the active session.
        prefixChannel.send(ChunkFrameCodec.encode(foreignEncoder.parts().first()))
        val concurrentEvent = recorder.awaitEvent { it is CoordinatorEvent.ConcurrentTransferDetected }
        assertIs<CoordinatorEvent.ConcurrentTransferDetected>(concurrentEvent)

        // The active TransferId(7) session must still be able to complete normally afterward —
        // the generator above keeps emitting its own redundant fountain stream automatically.
        val terminal = recorder.awaitTerminal()
        assertIs<CoordinatorEvent.Success>(terminal.last(), "the active TransferId(7) session must still complete undisrupted")

        val saved = pageRepo.getPageByName("Concurrent Sender Page").first().getOrNull()
        assertTrue(saved != null, "the active session's own page must still be written")

        coordinator.close()
        recorder.close()
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
        val recorder = EventRecorder(coordinator, this)

        coordinator.start()

        val event = recorder.awaitEvent { it is CoordinatorEvent.Failed }
        val failed = assertIs<CoordinatorEvent.Failed>(event)
        val payloadTooLarge = assertIs<DomainError.QrTransferError.PayloadTooLarge>(failed.error)
        assertEquals(5_000_000, payloadTooLarge.sizeBytes)
        assertEquals(maxPayloadBytes, payloadTooLarge.maxBytes)

        val saved = pageRepo.getPageByName("Oversized Sender Page").first().getOrNull()
        assertNull(saved, "no write must occur after a PayloadTooLarge rejection")

        coordinator.close()
        recorder.close()
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
        val recorder = EventRecorder(coordinator, this)

        coordinator.start()

        val event = recorder.awaitEvent { it is CoordinatorEvent.PreflightFailed }
        val preflightFailed = assertIs<CoordinatorEvent.PreflightFailed>(event)
        assertIs<DomainError.SensorError.PermissionDenied>(preflightFailed.reason)

        val saved = pageRepo.getPageByName("Permission Denied Page").first().getOrNull()
        assertNull(saved, "no write must occur when the pre-flight camera stream reports PermissionDenied")

        coordinator.close()
        recorder.close()
    }

    /**
     * CRITICAL C4 + MAJOR M5 coverage: drives a REAL name collision through the coordinator (not
     * just the UI-level dialog, which `QrImportConfirmDialogTest` already covers) and resolves it
     * via [QrTransferCoordinator.resolveCollision] called from a dispatcher OTHER than the
     * coordinator's own internal `Dispatchers.Default` scope — the actual cross-thread rendezvous
     * [QrTransferCoordinator]'s `collisionChannel`/`dataJob`/`diagnosticsJob` `@Volatile` fields
     * guard against a stale/null read hanging the coordinator forever (mirrors how
     * `QrDecodeViewModel.resolveCollision` calls this SYNCHRONOUSLY from the UI thread, not via
     * `scope.launch`).
     */
    @Test
    fun resolveCollision_should_WriteDisambiguatedNameAndReachSuccess_When_CollisionDetectedAndKeepBothChosenFromDifferentDispatcher() = runBlocking {
        val (importService, pageRepo) = buildImportService()
        val seeded = importService.import("- pre-existing content\n", PageName("Collision Page"))
        assertTrue(seeded.isRight(), "fixture setup: seeding the pre-existing page must succeed")

        val markdown = "- incoming collision page body with enough content for several fountain chunks\n"
        val envelopeBytes = TransferPayloadEnvelope.wrap(PageName("Collision Page"), markdown)
        val encoder = FountainCodec.encoder(TransferId(501), envelopeBytes, maxFragmentBytes = 12).getOrNull()!!

        val coordinator = QrTransferCoordinator(
            frameTransportReceiver = fakeReceiver(encoder),
            qrImportService = importService,
        )
        val recorder = EventRecorder(coordinator, this)

        coordinator.start()

        val collision = recorder.awaitEvent { it is CoordinatorEvent.CollisionDetected } as CoordinatorEvent.CollisionDetected
        assertEquals("Collision Page", collision.existingName.value)
        assertEquals("Collision Page", collision.proposedName.value)

        // No write yet — the coordinator must not import while a collision is pending (S11).
        val beforeResolve = pageRepo.getPageByName("Collision Page (2)").first().getOrNull()
        assertNull(beforeResolve, "no disambiguated write must exist before resolveCollision() is called")

        // Resolve from a dispatcher distinct from the coordinator's own Dispatchers.Default scope
        // — exercises the actual cross-thread read this test's KDoc describes.
        withContext(Dispatchers.IO) {
            coordinator.resolveCollision(QrImportService.CollisionChoice.KEEP_BOTH)
        }

        val terminal = recorder.awaitTerminal()
        val success = assertIs<CoordinatorEvent.Success>(terminal.last())
        assertEquals("Collision Page (2)", success.pageName.value, "KEEP_BOTH must disambiguate rather than overwrite")

        val disambiguated = pageRepo.getPageByName("Collision Page (2)").first().getOrNull()
        assertTrue(disambiguated != null, "the disambiguated page must be written after resolveCollision()")
        val original = pageRepo.getPageByName("Collision Page").first().getOrNull()
        assertTrue(original != null, "the original page must be untouched by a KEEP_BOTH resolution")

        coordinator.close()
        recorder.close()
    }

    /**
     * Coordinator's own [QrTransferCoordinator.cancel] KDoc: "If a [CoordinatorEvent.CollisionDetected]
     * is pending, the import is aborted (`null` sent on the collision channel) rather than
     * defaulting to a write. No write occurs after this call." — this test exercises exactly that
     * documented contract, which had zero coverage before this fix (MAJOR M5).
     */
    @Test
    fun cancel_should_AbortWithNoWrite_When_CalledWhileCollisionDetectedIsPending() = runBlocking {
        val (importService, pageRepo) = buildImportService()
        val seeded = importService.import("- pre-existing content\n", PageName("Collision Cancel Page"))
        assertTrue(seeded.isRight(), "fixture setup: seeding the pre-existing page must succeed")

        val markdown = "- incoming collision-cancel page body with enough content for several fountain chunks\n"
        val envelopeBytes = TransferPayloadEnvelope.wrap(PageName("Collision Cancel Page"), markdown)
        val encoder = FountainCodec.encoder(TransferId(502), envelopeBytes, maxFragmentBytes = 12).getOrNull()!!

        val coordinator = QrTransferCoordinator(
            frameTransportReceiver = fakeReceiver(encoder),
            qrImportService = importService,
        )
        val recorder = EventRecorder(coordinator, this)

        coordinator.start()

        recorder.awaitEvent { it is CoordinatorEvent.CollisionDetected }

        coordinator.cancel()

        val terminal = recorder.awaitEvent { it is CoordinatorEvent.Cancelled }
        assertIs<CoordinatorEvent.Cancelled>(terminal)

        val disambiguated = pageRepo.getPageByName("Collision Cancel Page (2)").first().getOrNull()
        assertNull(disambiguated, "cancel() during a pending collision must never write a disambiguated page")
        val original = pageRepo.getPageByName("Collision Cancel Page").first().getOrNull()
        assertTrue(original != null, "the pre-existing page must remain untouched by an aborted collision")

        coordinator.close()
        recorder.close()
    }
}
