package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.platform.sensor.CameraFrame
import dev.stapler.stelekit.platform.sensor.CameraFrameSource
import dev.stapler.stelekit.transfer.FrameTransportReceiver
import dev.stapler.stelekit.transfer.FrameTransportSender
import dev.stapler.stelekit.transfer.TransferId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/**
 * The v1 [dev.stapler.stelekit.transfer.FrameTransportSender]/[FrameTransportReceiver]
 * implementation: real QR send/receive, wired to the fountain + chunk-framing + QR raster
 * layers. This is the concrete proof (Story 2.1.4) that the `FrameTransport` seam is real, not
 * paper-only — both ViewModels above it (Stories 3.1.2/3.2.2) reach `FountainEncoder`/
 * `ChunkFrameCodec`/`QrCodec`/`QrScanner` only through this adapter.
 *
 * [encode]/[scan] default to the real [QrCodec]/[QrScanner] singletons but are constructor-
 * injectable so [QrFrameTransportTest] can substitute an in-process fixture that skips real QR
 * raster rendering — this keeps the adapter round-trip test in `commonTest` (no platform-specific
 * `QrCodec` actual required there).
 */
class QrFrameTransport(
    private val transferId: TransferId,
    private val cameraFrameSource: CameraFrameSource,
    private val maxFragmentBytes: Int = QrTransferSettings.DEFAULT_MAX_FRAGMENT_BYTES,
    private val encode: (ByteArray) -> QrMatrix = QrCodec::encode,
    private val scan: (CameraFrame) -> ScanResult = QrScanner::decode,
) : FrameTransportSender, FrameTransportReceiver {

    private val _displayFrames = MutableStateFlow<QrMatrix?>(null)

    /**
     * The QR matrix currently being displayed by [send] — QR-specific, so deliberately not part
     * of the medium-neutral [FrameTransportSender] seam. `QrEncodeViewModel` (Story 3.1.2)
     * collects this to render the screen.
     */
    val displayFrames: StateFlow<QrMatrix?> = _displayFrames.asStateFlow()

    /**
     * Composes [FountainCodec.encoder] -> [FountainEncoder.parts] -> [ChunkFrameCodec.encode] ->
     * [encode] for [payload], as a cold [Flow] — the actual send-side codec composition, exposed
     * directly (not through [displayFrames]'s `StateFlow` conflation) so callers/tests can collect
     * every produced frame, not just the latest. [FountainEncoder.parts] is an unbounded sequence
     * by design (ADR-001); callers bound consumption (e.g. `take(seqLen)`, or cancellation).
     */
    fun encodeFrames(payload: ByteArray): Flow<QrMatrix> = flow {
        val encoder = FountainCodec.encoder(transferId, payload, maxFragmentBytes).getOrNull() ?: return@flow
        for (chunk in encoder.parts()) {
            val frameBytes = ChunkFrameCodec.encode(chunk)
            emit(encode(frameBytes))
        }
    }

    /**
     * [FrameTransportSender] implementation: for each raw payload in [frames], drives
     * [encodeFrames] and publishes each resulting [QrMatrix] to [displayFrames]. Pacing is kept
     * simple/testable here; the real ~400 ms/frame UI pacing is owned by the ViewModel (Phase 3).
     * Runs until the collecting coroutine is cancelled.
     */
    override suspend fun send(frames: Flow<ByteArray>) {
        frames.collect { payload ->
            encodeFrames(payload).collect { matrix -> _displayFrames.value = matrix }
        }
    }

    /**
     * [FrameTransportReceiver] implementation: composes [CameraFrameSource.frameStream] ->
     * [scan] -> filter to [ScanResult.Decoded] -> re-encode via [ChunkFrameCodec.encode] so the
     * emitted bytes stay an opaque wire frame — the seam never carries a [FountainChunk] object
     * directly, only [ByteArray], per [FrameTransportReceiver].
     */
    override fun frames(): Flow<ByteArray> =
        cameraFrameSource.frameStream()
            .mapNotNull { it.getOrNull() }
            .map { frame -> scan(frame) }
            .filterIsInstance<ScanResult.Decoded>()
            .map { decoded -> ChunkFrameCodec.encode(decoded.chunk) }
}
