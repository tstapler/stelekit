package dev.stapler.stelekit.ui.transfer

import dev.stapler.stelekit.db.LogseqPageSerializer
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.platform.sensor.CameraFrameSource
import dev.stapler.stelekit.platform.sensor.NoOpCameraFrameSource
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.transfer.TransferId
import dev.stapler.stelekit.transfer.qrcode.FountainEncoder
import dev.stapler.stelekit.transfer.qrcode.QrFrameTransport
import dev.stapler.stelekit.transfer.qrcode.QrMatrix
import dev.stapler.stelekit.transfer.qrcode.QrTransferSettings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Owns serialize -> pre-flight -> paced-display for the QR sender (Story 3.1.2). Drives the send
 * half of the [QrFrameTransport] seam only — never constructs [FountainEncoder] to do real
 * chunking, never touches `ChunkFrameCodec`/`QrCodec` directly. The one exception is reading
 * [FountainEncoder.DEFAULT_MAX_PAYLOAD_BYTES] and calling [FountainEncoder.preflightEstimate] —
 * both are pure, side-effect-free helpers explicitly designed for UI pre-flight sizing (Task
 * 2.1.3b), not the chunking data path.
 *
 * CRITICAL: owns its [CoroutineScope] internally (CLAUDE.md scope-ownership) — never accepts an
 * externally supplied `rememberCoroutineScope()`. Call [close] when the encoder screen is
 * permanently dismissed so in-flight frame loops are cancelled cleanly.
 *
 * @param tick Injectable frame-advance delay. Real callers get ~1000/fps ms via [kotlinx.coroutines.delay];
 *   [QrEncodeViewModelTest] substitutes a virtual-time dispatcher's `delay` (via `runTest`) so
 *   pacing assertions never sleep in real time.
 */
class QrEncodeViewModel(
    private val pageRepository: PageRepository,
    private val blockRepository: BlockRepository,
    private val settings: QrTransferSettings,
    private val cameraFrameSource: CameraFrameSource = NoOpCameraFrameSource(),
    private val tick: suspend (delayMs: Long) -> Unit = { delayMs -> delay(delayMs) },
    /** Injected so tests can assert on emitted lines via [dev.stapler.stelekit.logging.LogManager.logs] (Story 3.3.3). */
    private val logger: Logger = Logger("QrEncodeViewModel"),
    private val clock: () -> Instant = { Clock.System.now() },
) {

    // CRITICAL: internal scope — never injected from outside composition.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                logger.error("QrEncodeViewModel uncaught: ${throwable.message}")
                _state.value = QrEncodeUiState.Failed(DomainError.QrTransferError.ChunkDecodeFailed)
                logEnded(outcome = "failed")
            },
    )

    private val _state = MutableStateFlow<QrEncodeUiState>(QrEncodeUiState.Idle)
    val state: StateFlow<QrEncodeUiState> = _state.asStateFlow()

    /** The QR matrix currently on screen. Sampled from [QrFrameTransport.displayFrames] at each paced tick. */
    private val _currentFrame = MutableStateFlow<QrMatrix?>(null)
    val currentFrame: StateFlow<QrMatrix?> = _currentFrame.asStateFlow()

    private var sendJob: Job? = null
    private var pacingJob: Job? = null
    private var activeTransport: QrFrameTransport? = null

    // Frame-position bookkeeping, mirrored into Displaying/Paused payloads so pause()/resume()
    // and reduce-motion advanceFrame() can reconstruct state without parsing the current state.
    private var frameIndex = 0
    private var totalCycled = 0
    private var chunkCount = 0
    private var estBytes = 0

    // Story 3.3.3 observability bookkeeping — read only at qr_transfer_started/qr_frame_sent/qr_transfer_ended.
    private var transferId: TransferId? = null
    private var transferStartedAt: Instant? = null
    private var endedLogged = false

    /** Idle -> Serializing -> Displaying/Failed. No-op unless currently [QrEncodeUiState.Idle]. */
    fun start(pageUuid: PageUuid) {
        if (_state.value != QrEncodeUiState.Idle) return
        _state.value = QrEncodeUiState.Serializing
        transferStartedAt = clock()
        endedLogged = false
        scope.launch {
            val page = pageRepository.getPageByUuid(pageUuid).first().getOrNull()
            if (page == null) {
                logger.error("start: page not found for $pageUuid")
                _state.value = QrEncodeUiState.Idle
                return@launch
            }
            val blocks = blockRepository.getBlocksForPage(pageUuid).first().getOrNull().orEmpty()
            val payload = LogseqPageSerializer.serialize(page, blocks).encodeToByteArray()

            // Pre-flight PayloadTooLarge gate (UX gap G1): fails before any frame is ever
            // displayed — mirrors FountainEncoder's own guard without driving real chunking.
            val maxPayloadBytes = FountainEncoder.DEFAULT_MAX_PAYLOAD_BYTES
            if (payload.size > maxPayloadBytes) {
                _state.value = QrEncodeUiState.Failed(
                    DomainError.QrTransferError.PayloadTooLarge(payload.size, maxPayloadBytes),
                )
                logEnded(outcome = "failed")
                return@launch
            }

            val maxFragmentBytes = settings.maxFragmentBytes
            val preflight = FountainEncoder.preflightEstimate(
                payloadLen = payload.size.coerceAtLeast(1),
                maxFragmentBytes = maxFragmentBytes,
            )
            chunkCount = preflight.estimatedFrameCount
            estBytes = payload.size
            frameIndex = 0
            totalCycled = 0

            val id = TransferId(Random.nextInt())
            transferId = id
            val transport = QrFrameTransport(
                transferId = id,
                cameraFrameSource = cameraFrameSource,
                maxFragmentBytes = maxFragmentBytes,
            )
            activeTransport = transport
            sendJob = scope.launch { transport.send(flowOf(payload)) }

            logger.info("qr_transfer_started transferId=${id.value} role=sender estBytes=$estBytes chunkCount=$chunkCount")

            _state.value = QrEncodeUiState.Displaying(frameIndex, totalCycled, chunkCount, estBytes)
            if (!settings.reduceMotion) startPacing(transport)
            // reduceMotion=true: no auto-advance loop — the screen drives advanceFrame() per tap.
        }
    }

    private fun startPacing(transport: QrFrameTransport) {
        pacingJob?.cancel()
        pacingJob = scope.launch {
            while (true) {
                tick((1000.0 / settings.framesPerSecond).toLong())
                advanceFrame(transport)
            }
        }
    }

    private fun advanceFrame(transport: QrFrameTransport) {
        // NOTE: displayFrames.value can still be null here — QrFrameTransport.send()'s first QR
        // matrix is produced by a separate async job (sendJob) and isn't guaranteed ready by the
        // time the very first paced tick fires. Progress (totalCycled/frameIndex/state) must
        // still advance on schedule regardless; only the rendered matrix is best-effort. Silently
        // no-op'ing the whole tick here would stall the pacing loop waiting on a tick that never
        // comes (observed as a hang under real thread-scheduling contention).
        transport.displayFrames.value?.let { _currentFrame.value = it }
        totalCycled += 1
        frameIndex = if (chunkCount > 0) totalCycled % chunkCount else 0
        logger.debug("qr_frame_sent transferId=${transferId?.value} frameIndex=$frameIndex")
        _state.value = QrEncodeUiState.Displaying(frameIndex, totalCycled, chunkCount, estBytes)
    }

    /**
     * Reduce-motion tap-to-advance (S5): steps exactly one frame per call, never auto-paced.
     * No-op outside [QrEncodeUiState.Displaying] (e.g. while [Paused] or before a transport exists).
     */
    fun advanceFrame() {
        if (_state.value !is QrEncodeUiState.Displaying) return
        val transport = activeTransport ?: return
        advanceFrame(transport)
    }

    /** Displaying -> Paused, freezing [frameIndex] exactly. Lifecycle-driven (backgrounding/screen-off). */
    fun pause() {
        if (_state.value !is QrEncodeUiState.Displaying) return
        pacingJob?.cancel()
        pacingJob = null
        _state.value = QrEncodeUiState.Paused(frameIndex, totalCycled, chunkCount, estBytes)
    }

    /** Paused -> Displaying, resuming at the same [frameIndex] — position preserved, not reset. */
    fun resume() {
        if (_state.value !is QrEncodeUiState.Paused) return
        _state.value = QrEncodeUiState.Displaying(frameIndex, totalCycled, chunkCount, estBytes)
        val transport = activeTransport ?: return
        if (!settings.reduceMotion) startPacing(transport)
    }

    /**
     * Explicit user "Done sending" action (distinct from [cancel]) — Displaying -> Complete.
     * Copy shown by the screen must read "Sent — ask the other device to confirm it imported,"
     * never implying confirmed delivery (QR has no back-channel).
     */
    fun complete() {
        if (_state.value !is QrEncodeUiState.Displaying) return
        stopLoops()
        _state.value = QrEncodeUiState.Complete
        logEnded(outcome = "success")
    }

    /** Cancels an in-progress send from any non-terminal state. Stops the loop within one tick. */
    fun cancel() {
        stopLoops()
        _state.value = QrEncodeUiState.Cancelled
        logEnded(outcome = "cancelled")
    }

    /** `qr_transfer_ended{role=sender, outcome, elapsedMs, framesSent}` (Story 3.3.3 Observability Plan). */
    private fun logEnded(outcome: String) {
        if (endedLogged) return
        endedLogged = true
        val elapsedMs = transferStartedAt?.let { (clock() - it).inWholeMilliseconds } ?: 0L
        logger.info(
            "qr_transfer_ended transferId=${transferId?.value} role=sender outcome=$outcome " +
                "elapsedMs=$elapsedMs framesSent=$totalCycled",
        )
    }

    private fun stopLoops() {
        pacingJob?.cancel()
        sendJob?.cancel()
        pacingJob = null
        sendJob = null
        activeTransport = null
    }

    /**
     * Cancel the internal [CoroutineScope]. Call when the encoder screen is permanently
     * dismissed to cancel in-flight frame loops cleanly.
     */
    fun close() {
        scope.coroutineContext[Job]?.cancel()
    }
}
