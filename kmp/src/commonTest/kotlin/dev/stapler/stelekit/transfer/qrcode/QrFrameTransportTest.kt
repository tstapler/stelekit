package dev.stapler.stelekit.transfer.qrcode

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.platform.sensor.CameraFrame
import dev.stapler.stelekit.platform.sensor.CameraFrameSource
import dev.stapler.stelekit.transfer.TransferId
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Story 2.1.4 (Task 2.1.4c) acceptance criteria: [QrFrameTransport.send]'s output, fed into a
 * second instance's [QrFrameTransport.frames] via a scripted [CameraFrameSource], reconstructs
 * the original payload — the concrete proof the `FrameTransport` seam is real. Uses an in-process
 * fake QR codec fixture (bit-packed, not real ZXing raster) injected via [QrFrameTransport]'s
 * `encode`/`scan` constructor parameters, so this test needs no platform-specific `QrCodec`
 * actual and no direct codec calls above the adapter.
 */
class QrFrameTransportTest {

    // ---- in-process fake QR codec fixture ----
    // Bit-packs a length-prefixed byte array into a square QrMatrix and back. Stands in for real
    // QR raster encode/decode (that fidelity is covered separately by QrCodecJvmTest);  this
    // fixture only needs to be a faithful, invertible ByteArray<->QrMatrix<->CameraFrame bridge.

    private fun fakeEncode(bytes: ByteArray): QrMatrix {
        val lengthPrefixed = byteArrayOf((bytes.size ushr 8).toByte(), (bytes.size and 0xFF).toByte()) + bytes
        val totalBits = lengthPrefixed.size * 8
        val size = ceil(sqrt(totalBits.toDouble())).toInt().coerceAtLeast(1)
        val bits = BooleanArray(size * size)
        for (i in lengthPrefixed.indices) {
            val byte = lengthPrefixed[i].toInt() and 0xFF
            for (b in 0 until 8) {
                bits[i * 8 + b] = (byte shr (7 - b)) and 1 == 1
            }
        }
        return QrMatrix(bits, size)
    }

    private fun fakeScan(frame: CameraFrame): ScanResult {
        val bits = BooleanArray(frame.luminanceBytes.size) { i -> (frame.luminanceBytes[i].toInt() and 0xFF) < 128 }
        fun readByte(byteIndex: Int): Int {
            var v = 0
            for (b in 0 until 8) if (bits[byteIndex * 8 + b]) v = v or (1 shl (7 - b))
            return v
        }
        if (bits.size < 16) return ScanResult.NoCodeDetected
        val len = (readByte(0) shl 8) or readByte(1)
        if (len < 0 || (len + 2) * 8 > bits.size) return ScanResult.NoCodeDetected
        val wireBytes = ByteArray(len) { i -> readByte(i + 2).toByte() }
        val chunk = ChunkFrameCodec.decode(wireBytes) ?: return ScanResult.NotSteleKitCode
        return ScanResult.Decoded(chunk)
    }

    private fun QrMatrix.toCameraFrame(): CameraFrame {
        val luminance = ByteArray(bits.size) { i -> if (bits[i]) 0 else 255.toByte() }
        return CameraFrame(luminanceBytes = luminance, width = size, height = size, rotationDegrees = 0)
    }

    /** Scripted [CameraFrameSource] that replays a fixed list of frames — no real camera/hardware. */
    private class FakeCameraFrameSource(private val scriptedFrames: List<CameraFrame>) : CameraFrameSource {
        override val isAvailable: Boolean = true
        override fun frameStream(): Flow<Either<DomainError.SensorError, CameraFrame>> = flow {
            for (frame in scriptedFrames) emit(frame.right())
        }
    }

    @Test
    fun qrFrameTransport_should_ReconstructPayload_When_SendOutputFedIntoFramesViaFakeCameraFrameSource() = runTest {
        val payload = "Hello SteleKit QR transfer".encodeToByteArray()
        val transferId = TransferId(42)
        val maxFragmentBytes = 16
        val preflight = FountainEncoder.preflightEstimate(payload.size, maxFragmentBytes = maxFragmentBytes)

        // Send side (a first, "sender device" instance) — no CameraFrameSource activity needed to
        // exercise encodeFrames, which is the codec composition send() itself delegates to.
        val sender = QrFrameTransport(
            transferId = transferId,
            cameraFrameSource = FakeCameraFrameSource(emptyList()),
            maxFragmentBytes = maxFragmentBytes,
            encode = ::fakeEncode,
        )
        // The first seqLen parts are the pure (non-redundant) fragments — sufficient alone to
        // reconstruct the full payload (see FountainEncoder.parts KDoc).
        val matrices = sender.encodeFrames(payload).take(preflight.pureFragmentCount).toList()
        assertEquals(preflight.pureFragmentCount, matrices.size)

        // Receive side (a second, "receiver device" instance) — scripted from the sender's output.
        val receiver = QrFrameTransport(
            transferId = transferId,
            cameraFrameSource = FakeCameraFrameSource(matrices.map { it.toCameraFrame() }),
            maxFragmentBytes = maxFragmentBytes,
            scan = ::fakeScan,
        )

        val decoder = FountainCodec.decoder()
        receiver.frames().collect { wireBytes ->
            val chunk = ChunkFrameCodec.decode(wireBytes) ?: return@collect
            decoder.accept(chunk)
        }

        val reconstructed = assertIs<VerifiedTransferPayload>(decoder.reassemble().getOrNull())
        assertEquals(payload.decodeToString(), reconstructed.markdown)
    }
}
