package dev.stapler.stelekit.transfer.qrcode

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.stapler.stelekit.platform.sensor.CameraFrame

/**
 * JVM (Desktop) [QrCodec] actual, backed by ZXing (`com.google.zxing:core`).
 *
 * Encodes/decodes arbitrary bytes (not text) by round-tripping through ISO-8859-1, a lossless
 * 1:1 byte<->char mapping — combined with the `CHARACTER_SET` hint, this forces ZXing's encoder
 * into QR byte mode instead of trying (and mangling binary data via) alphanumeric/Kanji modes.
 *
 * `TRY_HARDER` is intentionally NOT set on decode (plan.md: false-positive risk).
 */
actual object QrCodec {
    private val ENCODE_HINTS = mapOf(
        EncodeHintType.CHARACTER_SET to "ISO-8859-1",
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 4,
    )
    private val DECODE_HINTS = mapOf(
        DecodeHintType.CHARACTER_SET to "ISO-8859-1",
    )

    /**
     * Byte-mode data capacity of a QR symbol at [QrTransferSettings.MAX_QR_VERSION] (v20) and EC
     * level M — matches this file's hardcoded [ENCODE_HINTS] EC level. Defense-in-depth only: the
     * real upstream guard is [FountainEncoder]'s per-fragment payload-size check (Story 1.2.2),
     * which already bounds every fragment before it reaches this function. This just turns an
     * otherwise-unclear ZXing `WriterException` (or a matrix ZXing silently renders at a larger,
     * less-scannable version) into an explicit, actionable failure.
     */
    private const val MAX_ENCODABLE_BYTES = 666

    actual fun encode(bytes: ByteArray): QrMatrix {
        require(bytes.size <= MAX_ENCODABLE_BYTES) {
            "QrCodec.encode: payload is ${bytes.size} bytes, exceeds the $MAX_ENCODABLE_BYTES-byte " +
                "capacity ceiling for QR version ${QrTransferSettings.MAX_QR_VERSION} at EC level M"
        }
        val content = bytes.toString(Charsets.ISO_8859_1)
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, ENCODE_HINTS)
        val size = bitMatrix.width
        val bits = BooleanArray(size * size) { i -> bitMatrix.get(i % size, i / size) }
        return QrMatrix(bits, size)
    }

    actual fun decode(frame: CameraFrame): ByteArray? {
        val (rotatedBytes, width, height) =
            rotateLuminanceClockwise(frame.luminanceBytes, frame.width, frame.height, frame.rotationDegrees)
        val source = PlanarYUVLuminanceSource(rotatedBytes, width, height, 0, 0, width, height, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            val result = MultiFormatReader().decode(bitmap, DECODE_HINTS)
            result.text.toByteArray(Charsets.ISO_8859_1)
        } catch (e: NotFoundException) {
            null
        } catch (e: Exception) {
            null
        }
    }
}
