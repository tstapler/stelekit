package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.platform.sensor.CameraFrame

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
 * diagnostics — it does not recreate this file.
 */
object QrScanner {
    fun decode(frame: CameraFrame): ScanResult {
        val qrBytes = QrCodec.decode(frame) ?: return ScanResult.NoCodeDetected
        val chunk = ChunkFrameCodec.decode(qrBytes) ?: return ScanResult.NotSteleKitCode
        return ScanResult.Decoded(chunk)
    }
}
