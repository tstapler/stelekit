package dev.stapler.stelekit.transfer.qrcode

import arrow.core.Either
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.sensor.CameraFrame
import dev.stapler.stelekit.platform.sensor.CameraFrameSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Result of scanning a single [CameraFrame] for a SteleKit QR transfer chunk.
 *
 * Differentiating "wrong/foreign QR" from "no QR at all" (rather than a single nullable) lets
 * `QrDecodeUiState`'s scan hint (Story 3.2.2) tell the user "that's not a SteleKit transfer code"
 * instead of a generic "keep scanning."
 */
sealed interface ScanResult {
    /** [QrCodec.decode] found a QR and [ChunkFrameCodec.decode] parsed a valid [FountainChunk]. */
    data class Decoded(val chunk: FountainChunk) : ScanResult

    /** A QR code was found, but it failed [ChunkFrameCodec.decode] (bad magic/version/CRC). */
    data object NotSteleKitCode : ScanResult

    /** No QR code was found in the frame at all. */
    data object NoCodeDetected : ScanResult
}

/**
 * `CameraFrame` -> [ScanResult] scanner: composes [QrCodec.decode] -> [ChunkFrameCodec.decode].
 *
 * Created here (Story 2.1.4) as a dependency of [QrFrameTransport]'s receive-side composition.
 * Story 3.2.2 adds a second, directly-injected consumer of this same class for `ScanHint`
 * diagnostics — [QrTransferCoordinator]'s constructor takes an actual injected [QrScanner]
 * instance (Bug 3 fix), not this file recreated.
 *
 * An interface (with the real decoder as [QrScanner.Companion], not a bare `object`) so
 * [QrTransferCoordinator] can hold a fakeable [QrScanner] collaborator (Task 3.2.2d testability)
 * while every existing call site — `QrScanner.decode(frame)`, `QrScanner::decode` — keeps working
 * unchanged, resolved through the companion.
 */
interface QrScanner {
    fun decode(frame: CameraFrame): ScanResult

    /**
     * Whether the bound camera hardware is available — mirrors [CameraFrameSource.isAvailable].
     * `true` by default (the companion/stateless decode-only usage, e.g. [QrFrameTransport], never
     * queries this); [bind] overrides it to delegate to the real [CameraFrameSource].
     */
    val isAvailable: Boolean get() = true

    /**
     * Diagnostics-only camera frame stream — mirrors [CameraFrameSource.frameStream]. Empty by
     * default; [bind] overrides it to delegate to the real [CameraFrameSource]. This is how
     * [QrTransferCoordinator] observes [ScanHint] diagnostics (and the Bug 1 pre-flight signal)
     * WITHOUT holding a separate `CameraFrameSource` constructor collaborator of its own.
     */
    fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = emptyFlow()

    companion object : QrScanner {
        override fun decode(frame: CameraFrame): ScanResult {
            val qrBytes = QrCodec.decode(frame) ?: return ScanResult.NoCodeDetected
            val chunk = ChunkFrameCodec.decode(qrBytes) ?: return ScanResult.NotSteleKitCode
            return ScanResult.Decoded(chunk)
        }

        /**
         * Binds the real decoder to [cameraFrameSource], producing a "fully-formed" [QrScanner]
         * that [QrTransferCoordinator] can use for both diagnostics and its pre-flight gate (Bug 3
         * fix — constructed outside the coordinator, e.g. in
         * [dev.stapler.stelekit.ui.transfer.QrDecodeViewModel]'s `coordinatorFactory`, and passed
         * in already-formed, per plan.md's literal collaborator wording).
         */
        fun bind(cameraFrameSource: CameraFrameSource): QrScanner = BoundQrScanner(cameraFrameSource)
    }
}

private class BoundQrScanner(private val cameraFrameSource: CameraFrameSource) : QrScanner {
    override fun decode(frame: CameraFrame): ScanResult = QrScanner.decode(frame)
    override val isAvailable: Boolean get() = cameraFrameSource.isAvailable
    override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = cameraFrameSource.frameStream()
}
