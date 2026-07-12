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
 * Android [QrCodec] actual, backed by ZXing (`com.google.zxing:core`).
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

    actual fun encode(bytes: ByteArray): QrMatrix {
        val content = bytes.toString(Charsets.ISO_8859_1)
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, ENCODE_HINTS)
        val size = bitMatrix.width
        val bits = BooleanArray(size * size) { i -> bitMatrix.get(i % size, i / size) }
        return QrMatrix(bits, size)
    }

    actual fun decode(frame: CameraFrame): ByteArray? {
        return try {
            // PlanarYUVLuminanceSource's constructor throws IllegalArgumentException when the
            // luminance data length doesn't match width*height (camera row-stride padding is not
            // guaranteed to line up) — must be inside the try to honor decode()'s "never throws" contract.
            val (rotatedBytes, width, height) =
                rotateLuminanceClockwise(frame.luminanceBytes, frame.width, frame.height, frame.rotationDegrees)
            val source = PlanarYUVLuminanceSource(rotatedBytes, width, height, 0, 0, width, height, false)
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = MultiFormatReader().decode(bitmap, DECODE_HINTS)
            result.text.toByteArray(Charsets.ISO_8859_1)
        } catch (e: NotFoundException) {
            null
        } catch (e: Exception) {
            null
        }
    }
}
