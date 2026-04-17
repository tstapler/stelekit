package dev.stapler.stelekit.util

/**
 * Pure Kotlin SHA-256 implementation (no platform dependencies).
 * Works across all KMP targets: JVM, Android, iOS, JS.
 *
 * Used for content-based block deduplication. The [sha256ForContent] function
 * normalizes whitespace before hashing so that blocks with identical logical content
 * but different trailing spaces or line endings are detected as duplicates.
 *
 * Reference: FIPS 180-4 Secure Hash Standard
 */
object ContentHasher {

    // SHA-256 round constants: first 32 bits of the fractional parts of the
    // cube roots of the first 64 primes.
    private val K: IntArray = longArrayOf(
        0x428a2f98L, 0x71374491L, 0xb5c0fbcfL, 0xe9b5dba5L,
        0x3956c25bL, 0x59f111f1L, 0x923f82a4L, 0xab1c5ed5L,
        0xd807aa98L, 0x12835b01L, 0x243185beL, 0x550c7dc3L,
        0x72be5d74L, 0x80deb1feL, 0x9bdc06a7L, 0xc19bf174L,
        0xe49b69c1L, 0xefbe4786L, 0x0fc19dc6L, 0x240ca1ccL,
        0x2de92c6fL, 0x4a7484aaL, 0x5cb0a9dcL, 0x76f988daL,
        0x983e5152L, 0xa831c66dL, 0xb00327c8L, 0xbf597fc7L,
        0xc6e00bf3L, 0xd5a79147L, 0x06ca6351L, 0x14292967L,
        0x27b70a85L, 0x2e1b2138L, 0x4d2c6dfcL, 0x53380d13L,
        0x650a7354L, 0x766a0abbL, 0x81c2c92eL, 0x92722c85L,
        0xa2bfe8a1L, 0xa81a664bL, 0xc24b8b70L, 0xc76c51a3L,
        0xd192e819L, 0xd6990624L, 0xf40e3585L, 0x106aa070L,
        0x19a4c116L, 0x1e376c08L, 0x2748774cL, 0x34b0bcb5L,
        0x391c0cb3L, 0x4ed8aa4aL, 0x5b9cca4fL, 0x682e6ff3L,
        0x748f82eeL, 0x78a5636fL, 0x84c87814L, 0x8cc70208L,
        0x90befffaL, 0xa4506cebL, 0xbef9a3f7L, 0xc67178f2L
    ).let { longs -> IntArray(64) { longs[it].toInt() } }

    // Initial hash values: first 32 bits of the fractional parts of the
    // square roots of the first 8 primes.
    private val H0: IntArray = longArrayOf(
        0x6a09e667L, 0xbb67ae85L, 0x3c6ef372L, 0xa54ff53aL,
        0x510e527fL, 0x9b05688cL, 0x1f83d9abL, 0x5be0cd19L
    ).let { longs -> IntArray(8) { longs[it].toInt() } }

    /**
     * Normalizes content for hashing: trims surrounding whitespace and
     * converts Windows line endings (CRLF) to Unix (LF).
     */
    fun normalizeForHash(content: String): String =
        content.trim().replace("\r\n", "\n")

    /**
     * Returns the SHA-256 hex digest of [content] after normalization.
     * Use this for content deduplication to ignore trivial whitespace differences.
     *
     * @return 64-character lowercase hex string
     */
    fun sha256ForContent(content: String): String =
        sha256(normalizeForHash(content))

    /**
     * Returns the SHA-256 hex digest of [data] (raw UTF-8 bytes of the string).
     *
     * @return 64-character lowercase hex string
     */
    fun sha256(data: String): String = sha256(data.encodeToByteArray())

    /**
     * Returns the SHA-256 hex digest of [data].
     *
     * @return 64-character lowercase hex string
     */
    fun sha256(data: ByteArray): String {
        val h = H0.copyOf()
        val padded = padMessage(data)

        for (blockStart in padded.indices step 64) {
            // Prepare message schedule W[0..63]
            val w = IntArray(64)
            for (i in 0..15) {
                w[i] = ((padded[blockStart + i * 4].toInt() and 0xFF) shl 24) or
                        ((padded[blockStart + i * 4 + 1].toInt() and 0xFF) shl 16) or
                        ((padded[blockStart + i * 4 + 2].toInt() and 0xFF) shl 8) or
                        (padded[blockStart + i * 4 + 3].toInt() and 0xFF)
            }
            for (i in 16..63) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            // Initialize working variables
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]

            // 64 rounds
            for (i in 0..63) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = hh + s1 + ch + K[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                hh = g; g = f; f = e; e = d + temp1
                d = c; c = b; b = a; a = temp1 + temp2
            }

            // Add compressed chunk to current hash value
            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }

        // Produce final hash as 64-char lowercase hex string
        return h.joinToString("") { word ->
            word.toUInt().toString(16).padStart(8, '0')
        }
    }

    /**
     * Pads the message per FIPS 180-4:
     * 1. Append 0x80 byte
     * 2. Append zero bytes so that length ≡ 56 (mod 64)
     * 3. Append original bit length as 64-bit big-endian integer
     */
    private fun padMessage(data: ByteArray): ByteArray {
        val msgLen = data.size
        val zeroPad = (55 - msgLen % 64 + 64) % 64
        val paddedLen = msgLen + 1 + zeroPad + 8

        val result = ByteArray(paddedLen)
        data.copyInto(result)
        result[msgLen] = 0x80.toByte()
        // Zero bytes are already initialised to 0

        // Append bit-length as 64-bit big-endian
        val bitLen = msgLen.toLong() * 8
        for (i in 0..7) {
            result[paddedLen - 8 + i] = ((bitLen ushr (56 - i * 8)) and 0xFF).toByte()
        }
        return result
    }
}
