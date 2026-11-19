// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.codec

/**
 * Correctly rounded (half to even) decimal to double, with the same result on every target. The wasmJs
 * `String.toDouble` does not round-trip the shortest digits [ShortestDouble] prints (Spike 0.1.3a, confirmed by the
 * Story 1.1.2 wasm run), so the codec never calls it.
 *
 * Fast path: at most 15 significant digits and a power of ten up to 10^22 are both exact doubles, so one IEEE
 * multiply or divide is already correctly rounded (Clinger 1990). Everything else goes through exact integers.
 */
internal object DecimalParser {
    private const val EXACT_DIGITS = 15
    private const val EXACT_POW10 = 22
    private const val DIGIT_WINDOW = 770
    private const val MANTISSA_BITS = 52
    private const val MIN_NORMAL_EXP = -1022
    private const val LARGEST_EXP = 1023
    private const val EXP_BIAS = 1023
    private const val QUOTIENT_BITS = 57
    private const val TARGET_BITS = 53
    private const val OVERFLOW_DECADE = 310
    private const val UNDERFLOW_DECADE = -330
    private const val EXP_PARSE_CLAMP = 100_000_000
    private const val SHIFT_BITS_SAFE = 62
    private const val RADIX = 10
    private const val CHUNK = 9
    private val pow10 = DoubleArray(EXACT_POW10 + 1).also { t -> t[0] = 1.0; for (i in 1..EXACT_POW10) t[i] = t[i - 1] * 10.0 }

    private class Scan(val digits: String, val decExp: Int, val negative: Boolean)

    /** The finite double nearest to [text] (`-?digits[.digits][(e|E)[+-]digits]`), or null for bad syntax or an overflow. */
    fun parse(text: String): Double? {
        val scan = scan(text) ?: return null
        if (scan.digits.isEmpty()) return if (scan.negative) -0.0 else 0.0
        val magnitude = digitsToDouble(scan.digits, scan.decExp) ?: return null
        return if (scan.negative) -magnitude else magnitude
    }

    /** Splits [text] into significant digits (no leading zeros, at most [DIGIT_WINDOW] plus one sticky digit) and a decimal exponent. */
    private fun scan(text: String): Scan? {
        var i = 0
        val negative = text.startsWith('-')
        if (negative) i++
        val sig = StringBuilder()
        var droppedCount = 0
        var droppedNonZero = false
        var fracDigits = 0
        var seenDigit = false
        var seenPoint = false
        while (i < text.length) {
            val c = text[i]
            if (c in '0'..'9') {
                seenDigit = true
                if (seenPoint) fracDigits++
                if (sig.isNotEmpty() || c != '0') {
                    if (sig.length < DIGIT_WINDOW) sig.append(c) else { droppedCount++; droppedNonZero = droppedNonZero || c != '0' }
                }
            } else if (c == '.' && !seenPoint) {
                seenPoint = true
            } else {
                break
            }
            i++
        }
        if (!seenDigit) return null
        var exp10 = 0
        if (i < text.length) {
            if (text[i] != 'e' && text[i] != 'E') return null
            i++
            val expNegative = i < text.length && text[i] == '-'
            if (i < text.length && (text[i] == '-' || text[i] == '+')) i++
            if (i >= text.length) return null
            var e = 0
            while (i < text.length) {
                val c = text[i]
                if (c !in '0'..'9') return null
                if (e < EXP_PARSE_CLAMP) e = e * RADIX + (c - '0')
                i++
            }
            exp10 = if (expNegative) -e else e
        }
        var decExp = exp10 - fracDigits + droppedCount
        // A lost non-zero tail only matters as "more than the window": one sticky digit stands in for it.
        if (droppedNonZero) { sig.append('1'); decExp-- }
        return Scan(sig.toString(), decExp, negative)
    }

    private fun digitsToDouble(digits: String, decExp: Int): Double? {
        val decade = digits.length + decExp
        if (decade > OVERFLOW_DECADE) return null
        if (decade < UNDERFLOW_DECADE) return 0.0
        if (digits.length <= EXACT_DIGITS) {
            val d = digits.toLong().toDouble()
            if (decExp == 0) return d
            if (decExp in 1..EXACT_POW10) return d * pow10[decExp]
            if (decExp in -EXACT_POW10..-1) return d / pow10[-decExp]
        }
        return exact(digits, decExp)
    }

    private fun exact(digits: String, decExp: Int): Double? {
        val num = Nat.of(0L)
        for (chunk in digits.chunked(CHUNK)) num.mulPow10(chunk.length).addSmall(chunk.toInt())
        val den = Nat.of(1L)
        if (decExp >= 0) num.mulPow10(decExp) else den.mulPow10(-decExp)
        // Scale so the quotient has 55 or 56 significant bits; a non-zero remainder is the sticky bit.
        val s = TARGET_BITS + 2 - (num.bitLength() - den.bitLength())
        if (s >= 0) num.shl(s) else den.shl(-s)
        val q = num.divRemLong(den, QUOTIENT_BITS)
        val sticky = !num.isZero
        val len = Long.SIZE_BITS - q.countLeadingZeroBits()
        val exp2 = len - 1 - s
        if (exp2 > LARGEST_EXP) return null
        var drop = len - TARGET_BITS
        if (exp2 < MIN_NORMAL_EXP) drop += MIN_NORMAL_EXP - exp2
        val m = roundedMantissa(q, drop, sticky)
        if (exp2 < MIN_NORMAL_EXP) return Double.fromBits(m) // subnormal field; a carry to 2^52 is exactly the smallest normal
        val carried = m >= (1L shl TARGET_BITS)
        val mant = if (carried) m ushr 1 else m
        val e2 = if (carried) exp2 + 1 else exp2
        if (e2 > LARGEST_EXP) return null
        return Double.fromBits(((e2 + EXP_BIAS).toLong() shl MANTISSA_BITS) or (mant and ((1L shl MANTISSA_BITS) - 1)))
    }

    /** `q >> drop`, rounded to nearest, ties to even; [sticky] says bits below `q` were non-zero. */
    private fun roundedMantissa(q: Long, drop: Int, sticky: Boolean): Long {
        if (drop > SHIFT_BITS_SAFE) return 0L
        var m = q ushr drop
        val rem = q and ((1L shl drop) - 1)
        val half = 1L shl (drop - 1)
        if (roundsUp(rem, half, sticky, odd = (m and 1L) == 1L)) m++
        return m
    }

    private fun roundsUp(rem: Long, half: Long, sticky: Boolean, odd: Boolean): Boolean =
        rem > half || (rem == half && (sticky || odd))
}
