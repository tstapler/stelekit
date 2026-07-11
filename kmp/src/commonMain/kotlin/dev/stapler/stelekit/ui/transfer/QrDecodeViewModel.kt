package dev.stapler.stelekit.ui.transfer

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.platform.sensor.CameraFrameSource
import dev.stapler.stelekit.transfer.TransferId
import dev.stapler.stelekit.transfer.qrcode.CoordinatorEvent
import dev.stapler.stelekit.transfer.qrcode.QrFrameTransport
import dev.stapler.stelekit.transfer.qrcode.QrImportService
import dev.stapler.stelekit.transfer.qrcode.QrScanner
import dev.stapler.stelekit.transfer.qrcode.QrTransferCoordinator
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

/** S11 collision-resolution prompt, surfaced mid-[QrDecodeUiState.Importing] (Story 3.2.4). */
data class CollisionPrompt(val existingName: PageName, val proposedName: PageName)

/**
 * Owns scope + UI-state ONLY for the QR receiver (Story 3.2.2, SRP split). Delegates the entire
 * frame -> scan -> buffer -> reassemble -> import pipeline (INCLUDING the pre-flight
 * availability/permission gate, Bug 3 fix) to [QrTransferCoordinator], collecting its event
 * [kotlinx.coroutines.flow.Flow] to drive [QrDecodeUiState]. Does NOT touch [CameraFrameSource],
 * `QrScanner`, or `ChunkBuffer` directly — [cameraFrameSource] is held only to construct the
 * default [coordinatorFactory]'s collaborators, never called from this class's own logic — mirrors
 * [dev.stapler.stelekit.ui.annotate.AnnotationEditorViewModel] +
 * [dev.stapler.stelekit.ui.annotate.DepthEstimationCoordinator].
 *
 * CRITICAL: owns its [CoroutineScope] internally (CLAUDE.md scope-ownership) — never accepts an
 * externally supplied `rememberCoroutineScope()`. Call [close] when the decode screen is
 * permanently dismissed.
 */
class QrDecodeViewModel(
    private val cameraFrameSource: CameraFrameSource,
    private val qrImportService: QrImportService,
    private val settings: QrTransferSettings,
    /**
     * Produces the name the imported page is saved under. The wire protocol carries only block
     * content (no page title — [dev.stapler.stelekit.db.LogseqPageSerializer.serialize] never
     * embeds one), so a name cannot be recovered from the payload itself; this is a known,
     * flagged gap (see this task's final report) rather than a silently-invented wire-format
     * change. Defaults to a timestamp-derived placeholder.
     */
    private val targetNameProvider: () -> PageName = {
        PageName("Received page ${Clock.System.now().toEpochMilliseconds()}")
    },
    /** Builds the coordinator for one receive session — overridden in tests with fake collaborators. */
    private val coordinatorFactory: (targetName: PageName) -> QrTransferCoordinator = { name ->
        QrTransferCoordinator(
            frameTransportReceiver = QrFrameTransport(
                transferId = TransferId(0), // unused on the receive path — QrFrameTransport.frames() ignores it.
                cameraFrameSource = cameraFrameSource,
                maxFragmentBytes = settings.maxFragmentBytes,
            ),
            qrImportService = qrImportService,
            targetName = name,
            // Bug 3 fix: bound outside the coordinator and passed in fully-formed — the
            // coordinator itself never holds a raw CameraFrameSource collaborator.
            qrScanner = QrScanner.bind(cameraFrameSource),
        )
    },
    /** Injected so tests can assert on emitted lines via [dev.stapler.stelekit.logging.LogManager.logs] (Story 3.3.3). */
    private val logger: Logger = Logger("QrDecodeViewModel"),
    private val clock: () -> Instant = { Clock.System.now() },
) {

    // CRITICAL: internal scope — never injected from outside composition.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                // CLAUDE.md Throwable rule: an OutOfMemoryError (an Error, not Exception) must
                // surface as Failed, never crash the process.
                logger.error("QrDecodeViewModel uncaught: ${throwable.message}")
                _state.value = QrDecodeUiState.Failed(DomainError.QrTransferError.ChunkDecodeFailed)
            },
    )

    private val _state = MutableStateFlow<QrDecodeUiState>(QrDecodeUiState.Idle)
    val state: StateFlow<QrDecodeUiState> = _state.asStateFlow()

    private val _collisionPrompt = MutableStateFlow<CollisionPrompt?>(null)
    val collisionPrompt: StateFlow<CollisionPrompt?> = _collisionPrompt.asStateFlow()

    /** Non-null while a write for a just-resolved collision choice is in flight (drives the S11 spinner). */
    private val _pendingCollisionChoice = MutableStateFlow<QrImportService.CollisionChoice?>(null)
    val pendingCollisionChoice: StateFlow<QrImportService.CollisionChoice?> = _pendingCollisionChoice.asStateFlow()

    /**
     * Incremented each time [CoordinatorEvent.ConcurrentTransferDetected] fires (Story 3.3.4). A
     * counter, not a `Boolean`, so a second warning while the first is still visible re-triggers
     * [dev.stapler.stelekit.ui.transfer.QrDecodeScreen]'s `LaunchedEffect(nonce)` instead of being
     * swallowed as a no-op state change.
     */
    private val _concurrentTransferWarningNonce = MutableStateFlow(0)
    val concurrentTransferWarningNonce: StateFlow<Int> = _concurrentTransferWarningNonce.asStateFlow()

    private var activeCoordinator: QrTransferCoordinator? = null
    private var collectJob: Job? = null

    // Story 3.3.3 observability bookkeeping — read only at qr_transfer_started/qr_transfer_ended.
    private var transferStartedAt: Instant? = null
    private var framesDecoded = 0

    /**
     * Idle -> PreflightFailed | Scanning. Pre-flight rejects immediately — never enters
     * [QrDecodeUiState.Scanning] — via TWO paths, both now owned entirely by
     * [QrTransferCoordinator.start] (Bug 3 fix; this method no longer touches [CameraFrameSource]
     * itself, per this class's own SRP KDoc): a synchronous [DomainError.SensorError.HardwareUnavailable]
     * check (Story 3.2.2 AC), and an asynchronous [DomainError.SensorError.PermissionDenied] check
     * (Bug 1 fix, Story 3.2.4) once the coordinator's diagnostics stream's first emission is a
     * `Left`. Both surface as [CoordinatorEvent.PreflightFailed], mapped 1:1 to
     * [QrDecodeUiState.PreflightFailed] in [applyEvent] — never [QrDecodeUiState.Scanning].
     */
    fun start() {
        if (_state.value != QrDecodeUiState.Idle) return

        transferStartedAt = clock()
        framesDecoded = 0
        logger.info("qr_transfer_started role=receiver")

        val coordinator = coordinatorFactory(targetNameProvider())
        activeCoordinator = coordinator
        collectJob = scope.launch {
            coordinator.events.collect { event -> _state.value = applyEvent(event) }
        }
        coordinator.start()
    }

    private fun applyEvent(event: CoordinatorEvent): QrDecodeUiState {
        when (event) {
            is CoordinatorEvent.CollisionDetected -> {
                _collisionPrompt.value = CollisionPrompt(event.existingName, event.proposedName)
                _pendingCollisionChoice.value = null
            }
            // Success/Failed/Cancelled are the only events that terminate a pending collision
            // prompt — Importing right after resolveCollision() must NOT clear it, so the dialog
            // stays up showing the spinner (S11, UX criterion 10) until the write settles.
            is CoordinatorEvent.Success, is CoordinatorEvent.Failed, CoordinatorEvent.Cancelled -> {
                _collisionPrompt.value = null
                _pendingCollisionChoice.value = null
            }
            else -> { /* FragmentAdmitted, Reassembling, Importing, ScanHintUpdated, ConcurrentTransferDetected: no collision-prompt change */ }
        }

        when (event) {
            is CoordinatorEvent.FragmentAdmitted -> {
                framesDecoded += 1
                logger.debug("qr_frame_decoded chunkIndex=$framesDecoded admitted=true")
            }
            is CoordinatorEvent.ConcurrentTransferDetected -> {
                logger.debug("qr_chunk_rejected reason=concurrent_transfer_id")
                _concurrentTransferWarningNonce.value += 1
            }
            CoordinatorEvent.Reassembling -> logger.info("qr_reassembly uniqueFragments=$framesDecoded result=in_progress")
            is CoordinatorEvent.Success -> logEnded(outcome = "success")
            is CoordinatorEvent.Failed -> logEnded(outcome = "failed")
            CoordinatorEvent.Cancelled -> logEnded(outcome = "cancelled")
            else -> { /* FragmentAdmitted logged above; CollisionDetected/Importing: no dedicated log line */ }
        }

        return when (event) {
            is CoordinatorEvent.PreflightFailed -> QrDecodeUiState.PreflightFailed(event.reason)

            is CoordinatorEvent.FragmentAdmitted ->
                QrDecodeUiState.Scanning(event.uniqueFragments, stalledSeconds = 0, hint = event.hint)

            is CoordinatorEvent.ScanHintUpdated ->
                QrDecodeUiState.Scanning(event.uniqueFragments, event.stalledSeconds, event.hint)

            CoordinatorEvent.ConcurrentTransferDetected -> _state.value

            CoordinatorEvent.Reassembling -> QrDecodeUiState.Reassembling
            is CoordinatorEvent.CollisionDetected -> QrDecodeUiState.Importing
            CoordinatorEvent.Importing -> QrDecodeUiState.Importing
            is CoordinatorEvent.Success -> QrDecodeUiState.Success(event.pageName)
            is CoordinatorEvent.Failed -> QrDecodeUiState.Failed(event.error)
            CoordinatorEvent.Cancelled -> QrDecodeUiState.Cancelled
        }
    }

    /** `qr_transfer_ended{role=receiver, outcome, elapsedMs, framesDecoded}` (Story 3.3.3 Observability Plan). */
    private fun logEnded(outcome: String) {
        val elapsedMs = transferStartedAt?.let { (clock() - it).inWholeMilliseconds } ?: 0L
        logger.info(
            "qr_transfer_ended role=receiver outcome=$outcome elapsedMs=$elapsedMs framesDecoded=$framesDecoded",
        )
    }

    /** Resolves a pending [collisionPrompt] (S11: Keep both / Overwrite). No-op if none is pending. */
    fun resolveCollision(choice: QrImportService.CollisionChoice) {
        _pendingCollisionChoice.value = choice
        activeCoordinator?.resolveCollision(choice)
    }

    /** Cancels the in-progress scan/import. No write occurs after this call. */
    fun cancel() {
        activeCoordinator?.cancel()
        _collisionPrompt.value = null
        _pendingCollisionChoice.value = null
        _state.value = QrDecodeUiState.Cancelled
    }

    /**
     * Lifecycle background/foreground signal (Story 3.3.2, UQ-3) — intentionally NO-OP. Backgrounding
     * must NOT tear down [activeCoordinator] or its `TransferSession`/`ChunkBuffer`: accumulated
     * fragments persist across a background/foreground cycle within the same VM lifetime. Callers
     * (e.g. an Android lifecycle observer) may call this unconditionally; only [cancel] or [close]
     * ever tear the coordinator down.
     */
    fun pause() { /* intentionally no-op — see KDoc */ }

    /** Paired with [pause] — also intentionally a no-op; scanning simply continues where it left off. */
    fun resume() { /* intentionally no-op — see KDoc */ }

    /** Cancel the internal [CoroutineScope] and the active coordinator. Call on permanent dismiss. */
    fun close() {
        activeCoordinator?.close()
        scope.coroutineContext[Job]?.cancel()
    }
}
