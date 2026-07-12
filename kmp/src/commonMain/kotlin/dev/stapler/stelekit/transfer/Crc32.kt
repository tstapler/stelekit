package dev.stapler.stelekit.transfer

/**
 * Pure-Kotlin, table-driven CRC32 (ISO 3309 / ITU-T V.42 polynomial 0xEDB88320).
 *
 * Deliberately avoids `java.util.zip.CRC32` so integrity checks work identically on every
 * KMP target (Android, iOS, JVM desktop) from a single `commonMain` implementation.
 */
object Crc32 {

    private val table: IntArray = IntArray(256).also { t ->
        for (n in 0 until 256) {
            var c = n
            repeat(8) {
                c = if (c and 1 != 0) {
                    (0xEDB88320.toInt()) xor (c ushr 1)
                } else {
                    c ushr 1
                }
            }
            t[n] = c
        }
    }

    /** Returns the standard CRC32 check value for [bytes]. */
    fun of(bytes: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (b in bytes) {
            val index = (crc xor b.toInt()) and 0xFF
            crc = (crc ushr 8) xor table[index]
        }
        return crc.inv()
    }
}
