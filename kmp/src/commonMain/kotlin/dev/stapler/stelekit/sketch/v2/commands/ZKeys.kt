// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.commands

import dev.stapler.stelekit.sketch.v2.scene.ZKey

/**
 * Fractional z-order keys (Story 1.3.2b). A key is a string over `0-9A-Za-z`, ordered by plain string comparison, so
 * a key can usually be generated strictly between two others and moving one element rewrites one key, not the layer.
 * [between] returns null when no key fits (equal or inverted neighbours, a foreign character); callers then renumber
 * the layer with [sequence].
 */
object ZKeys {
    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val radix = DIGITS.length
    private const val LEAD = "a"
    private const val ZERO = '0'

    /** Fresh keys are `a` plus this many digits, so appends carry inside the digits for 62^3 elements before the key grows. */
    private const val MIN_WIDTH = 3

    /** A key strictly after [lo] and strictly before [hi]; a null bound is open-ended. */
    fun between(lo: ZKey?, hi: ZKey?): ZKey? {
        val a = lo?.value.orEmpty()
        val b = hi?.value
        if (!valid(a) || (b != null && !valid(b))) return null
        // Trailing zeros are order-significant but carry no digit; the midpoint search needs them gone.
        val aTrim = a.trimEnd(ZERO)
        val bTrim = b?.trimEnd(ZERO)
        if (bTrim != null && (bTrim.isEmpty() || aTrim >= bTrim)) return null
        val mid = midpoint(aTrim, bTrim)
        val ok = mid > a && (b == null || mid < b)
        return if (ok) ZKey(mid) else null
    }

    /** A key after [lo]: the same length with the last digit stepped up (carrying), so appending 5,000 elements adds no length. */
    fun after(lo: ZKey?): ZKey {
        val a = lo?.value ?: return sequence(1).first()
        if (!valid(a)) return ZKey(a + "1")
        val chars = a.toCharArray()
        var i = chars.lastIndex
        while (i >= 0) {
            val d = DIGITS.indexOf(chars[i])
            if (d < radix - 1) {
                chars[i] = DIGITS[d + 1]
                return ZKey(chars.concatToString())
            }
            chars[i] = ZERO
            i--
        }
        return ZKey(a + "1")
    }

    /** [count] fresh, strictly increasing keys (the renumber fallback and the seed of an empty layer). */
    fun sequence(count: Int): List<ZKey> {
        var width = MIN_WIDTH
        var span = radix * radix * radix
        while (span <= count + 1) { width++; span *= radix }
        return List(count) { i -> ZKey(LEAD + encode(i + 1, width)) }
    }

    private fun encode(value: Int, width: Int): String {
        val sb = StringBuilder()
        var v = value
        repeat(width) { sb.append(DIGITS[v % radix]); v /= radix }
        return sb.reverse().toString()
    }

    private fun valid(s: String): Boolean = s.all { it in DIGITS }

    private fun digit(s: String, i: Int): Int = if (i < s.length) DIGITS.indexOf(s[i]) else 0

    private fun midpoint(a: String, b: String?): String {
        if (b != null) {
            var n = 0
            while (n < b.length && digit(a, n) == DIGITS.indexOf(b[n])) n++
            if (n > 0) return b.substring(0, n) + midpoint(a.substring(minOf(n, a.length)), b.substring(n))
        }
        val da = digit(a, 0)
        val db = if (b == null) radix else DIGITS.indexOf(b[0])
        if (db - da > 1) return DIGITS[(da + db + 1) / 2].toString()
        if (b != null && b.length > 1) return b.substring(0, 1)
        return DIGITS[da].toString() + midpoint(a.drop(1), null)
    }
}
