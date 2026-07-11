package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.platform.sensor.CameraFrame
import dev.stapler.stelekit.platform.sensor.CameraFrameSource
import dev.stapler.stelekit.transfer.FrameTransportReceiver
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Events emitted by [QrTransferCoordinator] — the receive-side, non-Compose pipeline owner (Story
 * 3.2.2, models [dev.stapler.stelekit.ui.annotate.DepthEstimationCoordinator]).
 * [dev.stapler.stelekit.ui.transfer.QrDecodeViewModel] collects [QrTransferCoordinator.events] and
 * maps it almost 1:1 onto [dev.stapler.stelekit.ui.transfer.QrDecodeUiState] — the ViewModel does
 * not otherwise touch [CameraFrameSource]/[QrScanner]/[ChunkBuffer].
 */
sealed interface CoordinatorEvent {
    /** A fragment was admitted into the active [TransferSession]. */
    data class FragmentAdmitted(val uniqueFragments: Int, val hint: ScanHint?) : CoordinatorEvent

    /** [ChunkBuffer.reassemble] proof-gate check in progress. */
    data object Reassembling : CoordinatorEvent

    /**
     * A page named [existingName] already exists; the caller must call
     * [QrTransferCoordinator.resolveCollision] (or [QrTransferCoordinator.cancel]) before the
     * import proceeds — the coordinator writes nothing while in this state (S11, UX criterion 10).
     */
    data class CollisionDetected(val existingName: PageName, val proposedName: PageName) : CoordinatorEvent

    /** [QrImportService.import] pipeline running. */
    data object Importing : CoordinatorEvent

    /** Import succeeded. */
    data class Success(val pageName: PageName) : CoordinatorEvent

    /** Terminal failure — see [dev.stapler.stelekit.ui.transfer.QrDecodeUiState.Failed] KDoc for why this is a general [DomainError]. */
    data class Failed(val error: DomainError) : CoordinatorEvent

    /** User cancelled (or declined a collision); no write ever occurred. */
    data object Cancelled : CoordinatorEvent
}

/**
 * Owns the frame -> scan -> buffer -> reassemble -> import pipeline for one QR receive (Story
 * 3.2.2). Independently unit-testable without a ViewModel or Compose (Task 3.2.2d).
 *
 * **Two collaborators, one data path** (Architecture concern, plan.md Story 3.2.2): the
 * transfer-DATA path goes only through [frameTransportReceiver] — frames -> [ChunkFrameCodec.decode]
 * -> [TransferSession]/[ChunkBuffer] -> `reassemble()`. This coordinator never calls [QrCodec] and
 * never collects [CameraFrameSource.frameStream] for the data path.
 *
 * The **one documented exception**: [ScanHint] diagnostics (`WrongCode`/`LowLight`) are derived
 * from a SEPARATE, parallel collection of [cameraFrameSource] run through the directly-injected
 * [scan] function (defaults to [QrScanner.decode]) — rich scan diagnostics are QR-specific UX (gap
 * G5 in `design/ux.md`) that the medium-neutral `FrameTransport` seam deliberately does not carry
 * (see ADR-002/ADR-006 and plan.md's Domain Glossary entry for `QrTransferCoordinator`). This
 * diagnostics stream's output NEVER feeds [ChunkBuffer] — a faked `NotSteleKitCode` changes only
 * [CoordinatorEvent.FragmentAdmitted.hint], never the reassembled payload (`QrTransferCoordinatorTest`).
 *
 * `Stalled` hint derivation is intentionally NOT implemented here — Story 3.3.2 adds that
 * stall-timer *behavior* on top of [TransferSession.lastNewFragmentAt] later; this class only
 * carries the base plumbing that behavior will attach to.
 *
 * Holds a [TransferSession] as its aggregate root from the first accepted frame — never a bare
 * [ChunkBuffer] retrofitted later.
 *
 * CRITICAL: owns its [CoroutineScope] internally (CLAUDE.md scope-ownership) — call [close] when
 * the decode screen is permanently dismissed.
 */
class QrTransferCoordinator(
    private val frameTransportReceiver: FrameTransportReceiver,
    private val cameraFrameSource: CameraFrameSource,
    private val qrImportService: QrImportService,
    private val targetName: PageName,
    private val maxPayloadBytes: Int = FountainEncoder.DEFAULT_MAX_PAYLOAD_BYTES,
    /** Mean luminance (0-255) below which a frame with no decodable QR is reported as [ScanHint.LowLight]. */
    private val lowLightThreshold: Int = 40,
    /** Diagnostics-only scan function — directly injectable so tests can fake [ScanResult] without a real [QrCodec]. */
    private val scan: (CameraFrame) -> ScanResult = QrScanner::decode,
) {
    private val logger = Logger("QrTransferCoordinator")

    // CRITICAL: internal scope — never injected from outside composition.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                // CLAUDE.md Throwable rule: an OutOfMemoryError (an Error, not Exception) must
                // surface as Failed, never crash the process.
                logger.error("QrTransferCoordinator uncaught: ${throwable.message}")
                emitEvent(CoordinatorEvent.Failed(DomainError.QrTransferError.ChunkDecodeFailed))
            },
    )

    // A buffered SharedFlow (NOT StateFlow) so transient milestone events (Reassembling,
    // Importing) are never silently conflated away before a collector observes them — StateFlow's
    // "keep only the latest value" semantics would drop a Reassembling that's overwritten by
    // Importing microseconds later on a small/fast payload. replay=1 still gives a late-attaching
    // collector the most recent event, matching the old StateFlow-based subscribe behavior.
    private val _events = MutableSharedFlow<CoordinatorEvent>(replay = 1, extraBufferCapacity = 64)
    val events: SharedFlow<CoordinatorEvent> = _events.asSharedFlow()

    private fun emitEvent(event: CoordinatorEvent) {
        val delivered = _events.tryEmit(event)
        check(delivered) { "CoordinatorEvent buffer overflowed — increase extraBufferCapacity" }
    }

    private var session: TransferSession? = null
    @Volatile private var currentHint: ScanHint? = null
    private var collisionChannel: Channel<QrImportService.CollisionChoice?>? = null
    private var dataJob: Job? = null
    private var diagnosticsJob: Job? = null

    /**
     * Begins collecting frames on both paths. No-op if already started.
     *
     * No transfer id is known upfront — [TransferSession] is created lazily from the FIRST
     * accepted chunk's [FountainChunk.transferId] (Story 3.2.2, aggregate root "from frame 0" —
     * frame 0 is the first one actually observed, not a caller-supplied guess). Once locked,
     * frames carrying a different transfer id are ignored (Story 3.3.4's binding rule; the
     * required user-visible warning for that case is Story 3.3.4's own follow-on work).
     */
    fun start() {
        if (dataJob != null) return
        diagnosticsJob = scope.launch { runDiagnostics() }
        dataJob = scope.launch { runDataPath() }
    }

    private suspend fun runDiagnostics() {
        try {
            cameraFrameSource.frameStream().conflate().collect { either ->
                either.fold(
                    ifLeft = { /* sensor error surfaces via the pre-flight gate upstream, not here */ },
                    ifRight = { frame -> currentHint = deriveHint(frame) },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Diagnostics are additive UX only (G5) — a failure here must never break the data path.
            logger.warn("diagnostics stream failed: ${e.message}")
        }
    }

    private fun deriveHint(frame: CameraFrame): ScanHint? = when (scan(frame)) {
        is ScanResult.NotSteleKitCode -> ScanHint.WrongCode
        is ScanResult.NoCodeDetected -> if (meanLuminance(frame) < lowLightThreshold) ScanHint.LowLight else null
        is ScanResult.Decoded -> null
    }

    private fun meanLuminance(frame: CameraFrame): Int {
        val bytes = frame.luminanceBytes
        if (bytes.isEmpty()) return 255
        var sum = 0L
        for (b in bytes) sum += (b.toInt() and 0xFF)
        return (sum / bytes.size).toInt()
    }

    private suspend fun runDataPath() {
        try {
            frameTransportReceiver.frames().conflate().collect { rawFrame ->
                val chunk = ChunkFrameCodec.decode(rawFrame) ?: return@collect

                var activeSession = session
                if (activeSession == null) {
                    activeSession = TransferSession(chunk.transferId, maxPayloadBytes)
                    session = activeSession
                } else if (chunk.transferId != activeSession.transferId) {
                    // Concurrent-sender rejection (Story 3.3.4, out of scope) — silently ignored
                    // here; a later story adds the required user-visible "another transfer
                    // started" signal. The active session's ChunkBuffer is untouched.
                    return@collect
                }

                val accepted = activeSession.accept(chunk)
                if (accepted) {
                    emitEvent(CoordinatorEvent.FragmentAdmitted(activeSession.uniqueFragments, currentHint))
                }
                if (activeSession.buffer.isComplete()) {
                    finishReassembly(activeSession)
                    throw CancellationException("transfer reassembly complete")
                }
            }
        } catch (e: CancellationException) {
            // Normal termination path: reassembly completed, or cancel()/close() was called.
        } catch (e: Throwable) {
            logger.error("QrTransferCoordinator data path failed: ${e.message}")
            emitEvent(CoordinatorEvent.Failed(DomainError.QrTransferError.ChunkDecodeFailed))
        } finally {
            diagnosticsJob?.cancel()
        }
    }

    private suspend fun finishReassembly(activeSession: TransferSession) {
        emitEvent(CoordinatorEvent.Reassembling)
        activeSession.buffer.reassemble().fold(
            ifLeft = { err -> emitEvent(CoordinatorEvent.Failed(err)) },
            ifRight = { payload -> proceedToImport(payload) },
        )
    }

    private suspend fun proceedToImport(payload: VerifiedTransferPayload) {
        val existing = qrImportService.findCollision(targetName)
        val choice = if (existing == null) {
            QrImportService.CollisionChoice.KEEP_BOTH
        } else {
            emitEvent(CoordinatorEvent.CollisionDetected(PageName(existing.name), targetName))
            val channel = Channel<QrImportService.CollisionChoice?>(Channel.RENDEZVOUS)
            collisionChannel = channel
            val resolved = channel.receive()
            collisionChannel = null
            if (resolved == null) {
                emitEvent(CoordinatorEvent.Cancelled)
                return
            }
            resolved
        }

        emitEvent(CoordinatorEvent.Importing)
        qrImportService.import(payload, targetName, choice).fold(
            ifLeft = { err -> emitEvent(CoordinatorEvent.Failed(err)) },
            ifRight = { name -> emitEvent(CoordinatorEvent.Success(name)) },
        )
    }

    /** Resolves a pending [CoordinatorEvent.CollisionDetected] — no-op if none is pending. */
    fun resolveCollision(choice: QrImportService.CollisionChoice) {
        collisionChannel?.trySend(choice)
    }

    /**
     * Cancels the in-progress transfer. If a [CoordinatorEvent.CollisionDetected] is pending, the
     * import is aborted (`null` sent on the collision channel) rather than defaulting to a write.
     * No write occurs after this call.
     */
    fun cancel() {
        collisionChannel?.trySend(null)
        dataJob?.cancel()
        diagnosticsJob?.cancel()
        emitEvent(CoordinatorEvent.Cancelled)
    }

    /** Cancels the internal [CoroutineScope]. Call when the decode screen is permanently dismissed. */
    fun close() {
        scope.cancel()
    }
}
