// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.codec

import kotlin.math.ceil

/**
 * Shortest round-trip decimal digits of a positive finite double, computed with exact integer arithmetic so every
 * target (JVM, Android, wasmJs, iOS) prints the same digits (Spike 0.1.3a: wasmJs `Double.toString` is not shortest
 * round-trip, so no platform formatter is trusted). Free-format algorithm of Burger and Dybvig, "Printing
 * Floating-Point Numbers Quickly and Accurately" (PLDI 1996), with round-half-even boundaries and, on an exact tie
 * between two shortest candidates, the even digit (what ECMAScript and the JDK print).
 */
internal object ShortestDouble {
    /** The value is `0.DIGITS x 10^pointPos`; [digits] has no leading or trailing zero. */
    class Digits(val digits: String, val pointPos: Int)

    private const val MANTISSA_BITS = 52
    private const val EXP_BIAS = 1075
    private const val SUBNORMAL_EXP = -1074
    private const val LOG10_OF_2 = 0.30102999566398114
    private const val ESTIMATE_SLACK = 1e-10
    private const val RADIX = 10
    private const val EXP_FIELD_MASK = 0x7FF

    /** [v] must be finite and greater than zero. */
    fun digitsOf(v: Double): Digits {
        val bits = v.toRawBits()
        val expBits = ((bits ushr MANTISSA_BITS) and EXP_FIELD_MASK.toLong()).toInt()
        val frac = bits and ((1L shl MANTISSA_BITS) - 1)
        val f = if (expBits == 0) frac else frac or (1L shl MANTISSA_BITS)
        val e = if (expBits == 0) SUBNORMAL_EXP else expBits - EXP_BIAS
        val even = (f and 1L) == 0L
        val unequalGap = f == (1L shl MANTISSA_BITS) && expBits > 1
        val gapShift = if (unequalGap) 1 else 0

        val r: Nat
        val s: Nat
        val mPlus: Nat
        val mMinus: Nat
        if (e >= 0) {
            r = Nat.of(f).shl(e + 1 + gapShift)
            s = Nat.of(2L).shl(gapShift)
            mPlus = Nat.of(1L).shl(e + gapShift)
            mMinus = Nat.of(1L).shl(e)
        } else {
            r = Nat.of(f).shl(1 + gapShift)
            s = Nat.of(1L).shl(-e + 1 + gapShift)
            mPlus = Nat.of(1L + gapShift)
            mMinus = Nat.of(1L)
        }

        val bitLen = Long.SIZE_BITS - f.countLeadingZeroBits()
        var k = ceil((e + bitLen - 1) * LOG10_OF_2 - ESTIMATE_SLACK).toInt()
        if (k >= 0) s.mulPow10(k) else { r.mulPow10(-k); mPlus.mulPow10(-k); mMinus.mulPow10(-k) }
        if (reachesUpperBound(r, mPlus, s, even)) k++ else { r.mulSmall(RADIX); mPlus.mulSmall(RADIX); mMinus.mulSmall(RADIX) }
        return Digits(generate(r, s, mPlus, mMinus, even), k)
    }

    private fun reachesUpperBound(r: Nat, mPlus: Nat, s: Nat, inclusive: Boolean): Boolean {
        val c = r.copy().add(mPlus).compareTo(s)
        return if (inclusive) c >= 0 else c > 0
    }

    private fun generate(r: Nat, s: Nat, mPlus: Nat, mMinus: Nat, even: Boolean): String {
        val out = StringBuilder()
        while (true) {
            val d = r.divRem(s)
            val cLow = r.compareTo(mMinus)
            val low = if (even) cLow <= 0 else cLow < 0
            val high = reachesUpperBound(r, mPlus, s, even)
            if (!low && !high) {
                out.append('0' + d)
                r.mulSmall(RADIX); mPlus.mulSmall(RADIX); mMinus.mulSmall(RADIX)
                continue
            }
            val last = when {
                low && !high -> d
                !low && high -> d + 1
                else -> {
                    // Both neighbours are valid shortest digits: take the closer, and on an exact tie the even digit.
                    val cmp = r.copy().shl(1).compareTo(s)
                    if (cmp < 0 || (cmp == 0 && d % 2 == 0)) d else d + 1
                }
            }
            out.append('0' + last)
            return out.toString().trimEnd('0').ifEmpty { "0" }
        }
    }
}
