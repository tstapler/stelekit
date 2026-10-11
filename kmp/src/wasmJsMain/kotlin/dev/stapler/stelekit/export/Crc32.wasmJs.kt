// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.export

/**
 * Table-driven CRC-32 (IEEE 802.3 / ISO 3309 polynomial, `0xEDB88320` reflected form) — the same
 * checksum `java.util.zip.CRC32` and the PKZIP format use, hand-rolled here because
 * `java.util.zip` is JVM-only and unavailable on Kotlin/Wasm (ADR-003 Amendment). Every ZIP entry
 * needs one regardless of compression method, including [StoredZipWriter]'s uncompressed entries.
 */
internal object Crc32 {
    private const val POLYNOMIAL = -0x12477ce0 // 0xEDB88320 as a signed Int literal

    private val table = IntArray(256).also { table ->
        for (i in 0 until 256) {
            var c = i
            repeat(8) {
                c = if (c and 1 != 0) (POLYNOMIAL xor (c ushr 1)) else (c ushr 1)
            }
            table[i] = c
        }
    }

    /** Returns the CRC-32 of [data] as an unsigned 32-bit value held in a [Long]. */
    fun compute(data: ByteArray): Long {
        var crc = -1 // 0xFFFFFFFF
        for (byte in data) {
            val index = (crc xor byte.toInt()) and 0xFF
            crc = table[index] xor (crc ushr 8)
        }
        return (crc.toLong() xor 0xFFFFFFFFL) and 0xFFFFFFFFL
    }
}
