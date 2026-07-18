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
    /**
     * The camera stream's first emission was a [DomainError.SensorError] rather than a frame —
     * e.g. [DomainError.SensorError.PermissionDenied] once the user actually denies the runtime
     * prompt. Bug 1 fix: this is the only place a genuine permission denial is observable —
     * [CameraFrameSource] implementations emit exactly one `Left` then complete, never a
     * mid-scan failure a live [TransferSession] would need to recover from. Distinct from a
     * synchronous `isAvailable == false` check, which never touches the camera at all.
     */
    data class PreflightFailed(val reason: DomainError.SensorError) : CoordinatorEvent

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

    /**
     * [ScanHint] changed (e.g. the stall timer crossed [TransferSession.STALL_THRESHOLD_SECONDS],
     * or a diagnostics frame started/stopped matching [ScanHint.WrongCode]/[ScanHint.LowLight])
     * WITHOUT a new fragment being admitted — Story 3.3.2. Kept distinct from [FragmentAdmitted]
     * so a hint-only update never inflates a `framesDecoded` counter derived from that event.
     */
    data class ScanHintUpdated(val uniqueFragments: Int, val stalledSeconds: Int, val hint: ScanHint?) : CoordinatorEvent

    /**
     * A frame carrying a different [dev.stapler.stelekit.transfer.TransferId] than the active
     * session was dropped (Story 3.3.4) — a binding, user-visible signal, not a silent drop. The
     * active session's [ChunkBuffer] is unaffected; this is diagnostics-only.
     */
    data object ConcurrentTransferDetected : CoordinatorEvent
}

/**
 * Owns the frame -> scan -> buffer -> reassemble -> import pipeline for one QR receive (Story
 * 3.2.2). Independently unit-testable without a ViewModel or Compose (Task 3.2.2d).
 *
 * **Two collaborators, one data path** (Architecture concern, plan.md Story 3.2.2 — stated three
 * times in plan.md as a deliberate SRP/ISP decision): the transfer-DATA path goes only through
 * [frameTransportReceiver] — frames -> [ChunkFrameCodec.decode] -> [TransferSession]/[ChunkBuffer]
 * -> `reassemble()`. This coordinator never calls [QrCodec] directly and holds no separate
 * `CameraFrameSource` constructor collaborator of its own (Bug 3 fix — it previously took
 * `cameraFrameSource: CameraFrameSource` plus a `scan` function reference, a third raw camera
 * dependency the plan's Domain Glossary didn't authorize).
 *
 * The **one documented exception**: [ScanHint] diagnostics (`WrongCode`/`LowLight`) are derived
 * from a SEPARATE, parallel collection of the directly-injected [qrScanner]'s
 * [QrScanner.frameStream] — rich scan diagnostics are QR-specific UX (gap G5 in `design/ux.md`)
 * that the medium-neutral `FrameTransport` seam deliberately does not carry (see ADR-002/ADR-006
 * and plan.md's Domain Glossary entry for `QrTransferCoordinator`). This diagnostics stream's
 * output NEVER feeds [ChunkBuffer] — a faked `NotSteleKitCode` changes only
 * [CoordinatorEvent.FragmentAdmitted.hint], never the reassembled payload (`QrTransferCoordinatorTest`).
 * The SAME stream also carries this coordinator's pre-flight gate (Bug 1 fix, see
 * [CoordinatorEvent.PreflightFailed]) — [qrScanner] is constructed already bound to its
 * [CameraFrameSource] outside this class (see [QrScanner.bind], used by
 * [dev.stapler.stelekit.ui.transfer.QrDecodeViewModel]'s `coordinatorFactory`) and passed in
 * fully-formed, matching plan.md's literal "directly-injected `qrScanner: QrScanner`" wording.
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
    private val qrImportService: QrImportService,
    private val maxPayloadBytes: Int = FountainEncoder.DEFAULT_MAX_PAYLOAD_BYTES,
    /** Mean luminance (0-255) below which a frame with no decodable QR is reported as [ScanHint.LowLight]. */
    private val lowLightThreshold: Int = 40,
    /**
     * Diagnostics + pre-flight collaborator (Bug 3 fix) — an actual injected [QrScanner] instance,
     * not a bare function reference, so tests can fake both [QrScanner.decode] and
     * [QrScanner.frameStream]/[QrScanner.isAvailable] without a real [CameraFrameSource]. Defaults
     * to the real stateless decoder (via [QrScanner.Companion]), whose default `isAvailable=true`/
     * `frameStream()=emptyFlow()` make [start] proceed normally with no diagnostics activity —
     * production callers pass [QrScanner.bind] instead.
     */
    private val qrScanner: QrScanner = QrScanner,
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

    // @Volatile: read from the diagnostics coroutine, written from the data-path coroutine —
    // both run on Dispatchers.Default and may land on different threads (Story 3.3.2 stall hint).
    @Volatile private var session: TransferSession? = null
    @Volatile private var currentHint: ScanHint? = null

    // CRITICAL C4 fix: written inside proceedToImport (the data-path coroutine, Dispatchers.Default)
    // and read from resolveCollision()/cancel(), which QrDecodeViewModel calls SYNCHRONOUSLY from
    // the UI thread (not via scope.launch) — a stale/null read here silently drops the user's
    // collision choice and hangs the coordinator forever on channel.receive(). Same cross-thread
    // hazard as session/currentHint above.
    @Volatile private var collisionChannel: Channel<QrImportService.CollisionChoice?>? = null
    @Volatile private var dataJob: Job? = null
    @Volatile private var diagnosticsJob: Job? = null

    /**
     * Begins collecting frames on both paths. No-op if already started.
     *
     * Pre-flight gate (Bug 1 + Bug 3 fix): rejects immediately — never launches either
     * coroutine — when [QrScanner.isAvailable] is false, mirroring the synchronous
     * `CameraFrameSource.isAvailable` check this used to require callers (the ViewModel) to
     * perform themselves. A SECOND, asynchronous pre-flight check happens inside [runDiagnostics]
     * once started (see [CoordinatorEvent.PreflightFailed]).
     *
     * No transfer id is known upfront — [TransferSession] is created lazily from the FIRST
     * accepted chunk's [FountainChunk.transferId] (Story 3.2.2, aggregate root "from frame 0" —
     * frame 0 is the first one actually observed, not a caller-supplied guess). Once locked,
     * frames carrying a different transfer id are ignored (Story 3.3.4's binding rule; the
     * required user-visible warning for that case is Story 3.3.4's own follow-on work).
     */
    fun start() {
        if (dataJob != null) return
        if (!qrScanner.isAvailable) {
            emitEvent(CoordinatorEvent.PreflightFailed(DomainError.SensorError.HardwareUnavailable("camera")))
            return
        }
        diagnosticsJob = scope.launch { runDiagnostics() }
        dataJob = scope.launch { runDataPath() }
    }

    private suspend fun runDiagnostics() {
        try {
            var sawFirstFrame = false
            qrScanner.frameStream().conflate().collect { either ->
                // Bug 1 fix: only the FIRST emission is a meaningful pre-flight signal — a real
                // CameraFrameSource emits exactly one Left (PermissionDenied/HardwareUnavailable)
                // then completes, so this can never fire mid-scan and abort an in-progress session.
                if (!sawFirstFrame) {
                    sawFirstFrame = true
                    either.onLeft { reason ->
                        emitEvent(CoordinatorEvent.PreflightFailed(reason))
                        dataJob?.cancel()
                    }
                }
                either.onRight { frame -> updateHint(deriveHint(frame)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Diagnostics are additive UX only (G5) — a failure here must never break the data path.
            logger.warn("diagnostics stream failed: ${e.message}")
        }
    }

    /**
     * Stores [newHint] and, if it differs from the previously observed hint, emits
     * [CoordinatorEvent.ScanHintUpdated] (Story 3.3.2) so [dev.stapler.stelekit.ui.transfer.QrDecodeUiState.Scanning]
     * reflects a stall (or its resolution) even when no new fragment has arrived to otherwise
     * drive a state update.
     */
    private fun updateHint(newHint: ScanHint?) {
        if (newHint == currentHint) return
        currentHint = newHint
        val activeSession = session ?: return
        emitEvent(
            CoordinatorEvent.ScanHintUpdated(
                uniqueFragments = activeSession.uniqueFragments,
                stalledSeconds = activeSession.stalledSeconds(),
                hint = newHint,
            ),
        )
    }

    /** Scan-based hint takes priority; the session's own stall timer only fills in when scan is silent. */
    private fun deriveHint(frame: CameraFrame): ScanHint? {
        val scanHint = when (qrScanner.decode(frame)) {
            is ScanResult.NotSteleKitCode -> ScanHint.WrongCode
            is ScanResult.NoCodeDetected -> if (meanLuminance(frame) < lowLightThreshold) ScanHint.LowLight else null
            is ScanResult.Decoded -> null
        }
        return scanHint ?: session?.stallHint()
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
            // No `.conflate()` here (root-cause fix — was previously applied, copied from
            // runDiagnostics()'s legitimate use below without accounting for the different
            // semantics): each raw frame on this path decodes to a DISTINCT fountain-coded
            // fragment that FountainCodec's reassembly needs — conflate's entire contract is
            // "only the most recent value survives if the collector falls behind," which is
            // correct for the diagnostics path's camera-preview frames (stale ones are
            // meaningless) but silently discards real transfer data here. Under any scheduling
            // lag between the producer and this collector (GC pause, a busy shared thread pool,
            // a slow device) conflate can drop one or more fragments the encoder assumed would
            // arrive, so reassembly falls short of "enough" fragments and fails intermittently —
            // this was the root cause of QrTransferCoordinatorTest's CI-only flakiness (passes
            // locally on a lightly-loaded machine, fails under the shared/contended CI runner).
            // Removing conflate restores normal Flow backpressure: the producer suspends until
            // this collector is ready for the next fragment, so none can ever be silently lost.
            // A real camera-frame producer is already rate-limited at the source (QR decode is
            // throttled to a few fps, see `framesPerSecond`), so backpressure here never stalls
            // frame capture in practice.
            frameTransportReceiver.frames().collect { rawFrame ->
                val chunk = ChunkFrameCodec.decode(rawFrame) ?: return@collect

                var activeSession = session
                if (activeSession == null) {
                    activeSession = TransferSession(chunk.transferId, maxPayloadBytes)
                    session = activeSession
                } else if (chunk.transferId != activeSession.transferId) {
                    // Concurrent-sender rejection (Story 3.3.4): the active session's ChunkBuffer
                    // is untouched, but the drop is a REQUIRED user-visible signal, not a silent one.
                    logger.warn(
                        "ignoring frame for transferId=${chunk.transferId.value} — active session " +
                            "is bound to transferId=${activeSession.transferId.value}",
                    )
                    emitEvent(CoordinatorEvent.ConcurrentTransferDetected)
                    return@collect
                }

                activeSession.accept(chunk).fold(
                    ifLeft = { err ->
                        // Terminal, non-recoverable rejection (Story 1.2.3 AC): the claimed
                        // payloadLen alone already exceeds maxPayloadBytes, so no amount of
                        // further scanning can ever complete this transfer — end the session
                        // instead of leaving the user stuck in Scanning with uniqueFragments
                        // stuck at 0, indistinguishable from "no QR in view."
                        emitEvent(CoordinatorEvent.Failed(err))
                        throw CancellationException("payload too large")
                    },
                    ifRight = { accepted ->
                        if (accepted) {
                            emitEvent(CoordinatorEvent.FragmentAdmitted(activeSession.uniqueFragments, currentHint))
                        }
                    },
                )
                if (activeSession.buffer.isComplete()) {
                    finishReassembly(activeSession)
                    throw CancellationException("transfer reassembly complete")
                }
            }
        } catch (e: CancellationException) {
            // Normal termination path: reassembly completed, a PayloadTooLarge rejection ended
            // the session, or cancel()/close() was called.
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

    /**
     * Unwraps [TransferPayloadEnvelope] to recover the real `(pageName, markdown)` pair the
     * sender packed into the proof-gated payload — replacing the old "Received page &lt;epochMs&gt;"
     * placeholder entirely. [payload] already passed [ChunkBuffer.reassemble]'s CRC32 gate; this
     * is a downstream transformation of that existing [VerifiedTransferPayload], never a new way
     * to construct one — [unwrap] returns a plain `String`, not a second [VerifiedTransferPayload].
     * A malformed/corrupted envelope is a distinct terminal [DomainError.QrTransferError.EnvelopeMalformed]
     * failure, never a silent fallback to a fake name.
     */
    private suspend fun proceedToImport(payload: VerifiedTransferPayload) {
        val (decodedName, markdown) = TransferPayloadEnvelope.unwrap(payload.markdown.encodeToByteArray()).fold(
            ifLeft = { err ->
                emitEvent(CoordinatorEvent.Failed(err))
                return
            },
            ifRight = { it },
        )

        val existing = qrImportService.findCollision(decodedName)
        val choice = if (existing == null) {
            QrImportService.CollisionChoice.KEEP_BOTH
        } else {
            emitEvent(CoordinatorEvent.CollisionDetected(PageName(existing.name), decodedName))
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
        qrImportService.import(markdown, decodedName, choice).fold(
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
