// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.platform.image

/** Rotation (clockwise, applied first) then optional horizontal mirror — how EXIF orientations 1..8 map. */
data class OrientationTransform(val rotateDegrees: Int, val flipHorizontal: Boolean) {
    val isIdentity: Boolean get() = rotateDegrees == 0 && !flipHorizontal

    companion object {
        val IDENTITY = OrientationTransform(0, false)
    }
}

/** Minimal JPEG APP1/EXIF reader for the Orientation tag (0x0112); decoders that ignore EXIF need it. */
object ExifOrientationReader {
    /** EXIF orientation 1..8, or 1 when absent, not a JPEG, or malformed. */
    fun read(bytes: ByteArray): Int = try {
        readOrThrow(bytes)
    } catch (_: IndexOutOfBoundsException) {
        1
    }

    fun transformFor(orientation: Int): OrientationTransform = when (orientation) {
        2 -> OrientationTransform(0, true)
        3 -> OrientationTransform(180, false)
        4 -> OrientationTransform(180, true)
        5 -> OrientationTransform(90, true)
        6 -> OrientationTransform(90, false)
        7 -> OrientationTransform(270, true)
        8 -> OrientationTransform(270, false)
        else -> OrientationTransform.IDENTITY
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff

    private fun readOrThrow(b: ByteArray): Int {
        if (b.size < 4 || u8(b, 0) != 0xff || u8(b, 1) != 0xd8) return 1
        var i = 2
        while (i + 4 <= b.size) {
            if (u8(b, i) != 0xff) return 1
            val marker = u8(b, i + 1)
            if (marker == 0xff) {
                i++
                continue
            }
            if (marker == 0xda || marker == 0xd9) return 1
            val len = (u8(b, i + 2) shl 8) or u8(b, i + 3)
            if (marker == 0xe1 && len >= 8 && b.decodeToString(i + 4, i + 8) == "Exif") {
                return readTiff(b, i + 10)
            }
            i += 2 + len
        }
        return 1
    }

    private fun readTiff(b: ByteArray, tiff: Int): Int {
        val little = when (b.decodeToString(tiff, tiff + 2)) {
            "II" -> true
            "MM" -> false
            else -> return 1
        }
        fun u16(o: Int) = if (little) u8(b, o) or (u8(b, o + 1) shl 8) else (u8(b, o) shl 8) or u8(b, o + 1)
        fun u32(o: Int) = if (little) u16(o) or (u16(o + 2) shl 16) else (u16(o) shl 16) or u16(o + 2)
        val ifd = tiff + u32(tiff + 4)
        val count = u16(ifd)
        for (n in 0 until count) {
            val e = ifd + 2 + n * 12
            if (u16(e) == 0x0112) return u16(e + 8).takeIf { it in 1..8 } ?: 1
        }
        return 1
    }
}
