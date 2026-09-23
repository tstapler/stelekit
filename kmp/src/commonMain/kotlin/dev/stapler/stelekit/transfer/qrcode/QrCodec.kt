package dev.stapler.stelekit.transfer.qrcode

import dev.stapler.stelekit.platform.sensor.CameraFrame

/**
 * Platform QR raster boundary: turns raw bytes into a renderable [QrMatrix], and a scanned
 * [CameraFrame] back into bytes. Layer 3 (`QrFrameTransport`/UI) renders/scans through this
 * without knowing which QR library backs it.
 *
 * Each platform provides its own `actual`:
 * - JVM (Desktop): `jvmMain/.../transfer/qrcode/QrCodec.jvm.kt` (ZXing)
 * - Android: `androidMain/.../transfer/qrcode/QrCodec.android.kt` (ZXing)
 * - iOS (encode-only, Epic 4.2): CoreImage `CIQRCodeGenerator`; `decode` unimplemented (Epic 4.4
 *   deferral, per ADR-003/ADR-005).
 */
expect object QrCodec {
    /** Encodes [bytes] into a [QrMatrix] ready for display. */
    fun encode(bytes: ByteArray): QrMatrix

    /**
     * Attempts to decode a QR code out of [frame]. Applies [CameraFrame.rotationDegrees] before
     * scanning — skipping this causes silent decode failures on devices reporting sensor rotation
     * (see `CameraFrame` KDoc). Returns `null` if no QR code was found or it failed to decode;
     * never throws.
     */
    fun decode(frame: CameraFrame): ByteArray?
}

/**
 * Rotates a grayscale (single-channel) luminance buffer clockwise by [degrees] (must be one of
 * `0`/`90`/`180`/`270`), returning the rotated bytes plus the resulting (width, height).
 *
 * Pure byte-array math shared by every platform's [QrCodec.decode] actual so
 * `CameraFrame.rotationDegrees` is applied consistently (ADR-002's documented rotation-drift
 * failure) without duplicating the geometry per platform.
 */
internal fun rotateLuminanceClockwise(bytes: ByteArray, width: Int, height: Int, degrees: Int): Triple<ByteArray, Int, Int> {
    return when (((degrees % 360) + 360) % 360) {
        90 -> {
            val out = ByteArray(bytes.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[x * height + (height - 1 - y)] = bytes[y * width + x]
                }
            }
            Triple(out, height, width)
        }
        180 -> Triple(bytes.reversedArray(), width, height)
        270 -> {
            val out = ByteArray(bytes.size)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[(width - 1 - x) * height + y] = bytes[y * width + x]
                }
            }
            Triple(out, height, width)
        }
        else -> Triple(bytes, width, height)
    }
}
