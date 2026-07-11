package dev.stapler.stelekit.ui.transfer

import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.PageName
import dev.stapler.stelekit.platform.sensor.CameraFrameSource
import dev.stapler.stelekit.transfer.TransferId
import dev.stapler.stelekit.transfer.qrcode.CoordinatorEvent
import dev.stapler.stelekit.transfer.qrcode.QrFrameTransport
import dev.stapler.stelekit.transfer.qrcode.QrImportService
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

/** S11 collision-resolution prompt, surfaced mid-[QrDecodeUiState.Importing] (Story 3.2.4). */
data class CollisionPrompt(val existingName: PageName, val proposedName: PageName)

/**
 * Owns scope + UI-state ONLY for the QR receiver (Story 3.2.2, SRP split). Delegates the entire
 * frame -> scan -> buffer -> reassemble -> import pipeline to [QrTransferCoordinator], collecting
 * its event [kotlinx.coroutines.flow.Flow] to drive [QrDecodeUiState]. Does NOT touch
 * [CameraFrameSource], `QrScanner`, or `ChunkBuffer` directly — mirrors
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
            cameraFrameSource = cameraFrameSource,
            qrImportService = qrImportService,
            targetName = name,
        )
    },
) {
    private val logger = Logger("QrDecodeViewModel")

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

    private var activeCoordinator: QrTransferCoordinator? = null
    private var collectJob: Job? = null

    /**
     * Idle -> PreflightFailed | Scanning. Pre-flight rejects immediately — never enters [QrDecodeUiState.Scanning]
     * — when [CameraFrameSource.isAvailable] is false (Story 3.2.2 AC).
     */
    fun start() {
        if (_state.value != QrDecodeUiState.Idle) return

        if (!cameraFrameSource.isAvailable) {
            _state.value = QrDecodeUiState.PreflightFailed(
                DomainError.SensorError.HardwareUnavailable("camera"),
            )
            return
        }

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
            else -> { /* FragmentAdmitted, Reassembling, Importing: no collision-prompt change */ }
        }
        return when (event) {
            is CoordinatorEvent.FragmentAdmitted ->
                QrDecodeUiState.Scanning(event.uniqueFragments, stalledSeconds = 0, hint = event.hint)

            CoordinatorEvent.Reassembling -> QrDecodeUiState.Reassembling
            is CoordinatorEvent.CollisionDetected -> QrDecodeUiState.Importing
            CoordinatorEvent.Importing -> QrDecodeUiState.Importing
            is CoordinatorEvent.Success -> QrDecodeUiState.Success(event.pageName)
            is CoordinatorEvent.Failed -> QrDecodeUiState.Failed(event.error)
            CoordinatorEvent.Cancelled -> QrDecodeUiState.Cancelled
        }
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

    /** Cancel the internal [CoroutineScope] and the active coordinator. Call on permanent dismiss. */
    fun close() {
        activeCoordinator?.close()
        scope.coroutineContext[Job]?.cancel()
    }
}
