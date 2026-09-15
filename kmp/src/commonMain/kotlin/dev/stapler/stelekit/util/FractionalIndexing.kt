package dev.stapler.stelekit.util

/**
 * Port of https://github.com/rocicorp/fractional-indexing (CC0 license).
 *
 * Generates lexicographically ordered string keys for arbitrary insertion ordering.
 * Uses the base-62 alphabet "0-9A-Za-z" which matches ASCII order, so
 * default Kotlin String comparison gives the correct ordering.
 *
 * Migration-produced keys use zero-padded 11-digit decimals ("00000000000").
 * New keys interleave correctly since '0'-'9' are the first 10 alphabet chars.
 */
object FractionalIndexing {
    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val BASE = 62

    /**
     * Returns a key that sorts strictly between [a] and [b].
     * Pass null for [a] = "before everything"; null for [b] = "after everything".
     * Both null returns "a0" (the canonical first key).
     *
     * Append (b=null): increments the last character of [a] so sequential inserts
     * produce "a0", "a1", "a2", ... rather than jumping to the alphabet midpoint.
     */
    fun generateKeyBetween(a: String?, b: String?): String {
        if (a != null && b != null) {
            require(a < b) { "a must be less than b: a='$a', b='$b'" }
        }
        return when {
            a == null && b == null -> "a0"
            b == null -> increment(a!!)
            a == null -> midpoint("", b)
            else -> midpoint(a, b)
        }
    }

    /**
     * Returns the lexicographically next key after [key] by incrementing the last
     * character that is not at max (DIGITS.last()). If all characters are at max,
     * appends DIGITS[0] to grow the key length.
     */
    private fun increment(key: String): String {
        val sb = StringBuilder(key)
        for (i in sb.indices.reversed()) {
            val idx = DIGITS.indexOf(sb[i])
            check(idx >= 0) { "Invalid char '${sb[i]}' in key '$key'" }
            if (idx < DIGITS.length - 1) {
                sb[i] = DIGITS[idx + 1]
                return sb.toString()
            }
            sb[i] = DIGITS[0]
        }
        // All digits at max — append one more digit to grow the key
        return key + DIGITS[0]
    }

    private fun midpoint(a: String, b: String?): String {
        val zero = DIGITS[0]

        if (b != null) {
            var n = 0
            while (n < b.length) {
                val ac = if (n < a.length) a[n] else zero
                val bc = b[n]
                if (ac == bc) n++ else break
            }
            if (n > 0) {
                return b.substring(0, n) + midpoint(a.drop(n), b.drop(n))
            }
        }

        val digitA = if (a.isNotEmpty()) {
            val idx = DIGITS.indexOf(a[0])
            check(idx >= 0) { "Invalid char '${a[0]}' in key '$a'" }
            idx
        } else 0

        val digitB = if (b != null) {
            val idx = DIGITS.indexOf(b[0])
            check(idx >= 0) { "Invalid char '${b[0]}' in key '$b'" }
            idx
        } else BASE

        return if (digitB - digitA > 1) {
            val mid = (digitA + digitB) / 2
            DIGITS[mid].toString()
        } else if (b != null && b.length > 1) {
            b[0].toString() + midpoint(a.drop(1), b.drop(1))
        } else {
            DIGITS[digitA].toString() + midpoint(a.drop(1), null)
        }
    }
}
