package dev.stapler.stelekit.platform.sensor

/**
 * A single luminance frame captured from a continuous camera stream, e.g. for QR decode.
 *
 * @property luminanceBytes Y-plane (grayscale) bytes, ready for a ZXing
 *   `PlanarYUVLuminanceSource`-style decoder.
 * @property width Frame width in pixels, before [rotationDegrees] is applied.
 * @property height Frame height in pixels, before [rotationDegrees] is applied.
 * @property rotationDegrees Clockwise rotation to apply before decode. MUST be applied —
 *   ignoring it causes silent decode failures on devices that report sensor rotation.
 */
data class CameraFrame(
    val luminanceBytes: ByteArray,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CameraFrame) return false
        return luminanceBytes.contentEquals(other.luminanceBytes) &&
            width == other.width &&
            height == other.height &&
            rotationDegrees == other.rotationDegrees
    }

    override fun hashCode(): Int {
        var result = luminanceBytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + rotationDegrees
        return result
    }
}
