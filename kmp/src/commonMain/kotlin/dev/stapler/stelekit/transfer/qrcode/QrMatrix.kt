package dev.stapler.stelekit.transfer.qrcode

/**
 * Platform-agnostic QR module grid: `true` = dark module, `false` = light module, row-major.
 *
 * Deliberately not a raster/bitmap type (no `ImageBitmap`/`Bitmap`) so [QrCodec.encode] stays
 * usable from `commonMain` — the Compose UI layer (Phase 3) renders this grid, it does not decode
 * platform image types out of it.
 */
data class QrMatrix(
    val bits: BooleanArray,
    val size: Int,
) {
    init {
        require(bits.size == size * size) {
            "bits.size (${bits.size}) must equal size*size ($size*$size = ${size * size})"
        }
    }

    /** Module value at ([x], [y]), both `0 until size`. */
    operator fun get(x: Int, y: Int): Boolean = bits[y * size + x]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QrMatrix) return false
        return size == other.size && bits.contentEquals(other.bits)
    }

    override fun hashCode(): Int {
        var result = bits.contentHashCode()
        result = 31 * result + size
        return result
    }
}
