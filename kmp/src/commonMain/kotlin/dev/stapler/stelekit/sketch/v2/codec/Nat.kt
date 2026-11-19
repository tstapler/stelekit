// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.codec

private const val LIMB_BITS = 32
private const val LIMB_MASK = 0xFFFFFFFFL
private const val BIG_POW10_CHUNK = 1_000_000_000
private const val BIG_POW10_DIGITS = 9
private const val RADIX = 10

/**
 * Non-negative arbitrary-precision integer (little-endian 32-bit limbs, mutable in place) with just the operations
 * the double printer and parser need. Exact arithmetic is what keeps their output identical on every target.
 */
internal class Nat private constructor(private var limbs: IntArray, private var size: Int) {
    val isZero: Boolean get() = size == 0

    fun copy() = Nat(limbs.copyOf(), size)

    fun bitLength(): Int = if (size == 0) 0 else size * LIMB_BITS - limbs[size - 1].countLeadingZeroBits()

    fun mulSmall(m: Int): Nat {
        var carry = 0L
        for (i in 0 until size) {
            val p = (limbs[i].toLong() and LIMB_MASK) * m + carry
            limbs[i] = p.toInt()
            carry = p ushr LIMB_BITS
        }
        if (carry != 0L) append(carry.toInt())
        return this
    }

    fun addSmall(a: Int): Nat = add(of(a.toLong()))

    fun mulPow10(n: Int): Nat {
        var left = n
        while (left >= BIG_POW10_DIGITS) { mulSmall(BIG_POW10_CHUNK); left -= BIG_POW10_DIGITS }
        var rest = 1
        repeat(left) { rest *= RADIX }
        return if (rest > 1) mulSmall(rest) else this
    }

    fun shl(bits: Int): Nat {
        if (bits == 0 || size == 0) return this
        val whole = bits / LIMB_BITS
        val part = bits % LIMB_BITS
        val out = IntArray(size + whole + 1)
        for (i in 0 until size) {
            val v = (limbs[i].toLong() and LIMB_MASK) shl part
            out[i + whole] = out[i + whole] or v.toInt()
            out[i + whole + 1] = out[i + whole + 1] or (v ushr LIMB_BITS).toInt()
        }
        limbs = out
        size = out.size
        trim()
        return this
    }

    fun add(o: Nat): Nat {
        val n = maxOf(size, o.size)
        if (limbs.size < n + 1) limbs = limbs.copyOf(n + 1)
        var carry = 0L
        for (i in 0 until n) {
            val a = if (i < size) limbs[i].toLong() and LIMB_MASK else 0L
            val b = if (i < o.size) o.limbs[i].toLong() and LIMB_MASK else 0L
            val sum = a + b + carry
            limbs[i] = sum.toInt()
            carry = sum ushr LIMB_BITS
        }
        limbs[n] = carry.toInt()
        size = n + 1
        trim()
        return this
    }

    /** Requires `this >= o`. */
    fun sub(o: Nat) {
        var borrow = 0L
        for (i in 0 until size) {
            val a = limbs[i].toLong() and LIMB_MASK
            val b = if (i < o.size) o.limbs[i].toLong() and LIMB_MASK else 0L
            var diff = a - b - borrow
            if (diff < 0) { diff += 1L shl LIMB_BITS; borrow = 1L } else borrow = 0L
            limbs[i] = diff.toInt()
        }
        trim()
    }

    fun compareTo(o: Nat): Int {
        if (size != o.size) return if (size < o.size) -1 else 1
        for (i in size - 1 downTo 0) {
            val a = limbs[i].toLong() and LIMB_MASK
            val b = o.limbs[i].toLong() and LIMB_MASK
            if (a != b) return if (a < b) -1 else 1
        }
        return 0
    }

    /** Quotient digit (below the radix by the printer's invariant) of `this / s`; `this` becomes the remainder. */
    fun divRem(s: Nat): Int {
        var q = 0
        while (compareTo(s) >= 0) { sub(s); q++ }
        return q
    }

    /** Quotient of `this / den` as a Long (the caller guarantees it is below 2^[quotientBits]); `this` becomes the remainder. */
    fun divRemLong(den: Nat, quotientBits: Int): Long {
        var q = 0L
        for (bit in quotientBits - 1 downTo 0) {
            val t = den.copy().shl(bit)
            if (compareTo(t) >= 0) { sub(t); q = q or (1L shl bit) }
        }
        return q
    }

    private fun append(limb: Int) {
        if (size == limbs.size) limbs = limbs.copyOf(size * 2 + 1)
        limbs[size++] = limb
    }

    private fun trim() { while (size > 0 && limbs[size - 1] == 0) size-- }

    companion object {
        fun of(v: Long): Nat {
            val n = Nat(IntArray(2), 0)
            n.limbs[0] = v.toInt()
            n.limbs[1] = (v ushr LIMB_BITS).toInt()
            n.size = 2
            n.trim()
            return n
        }
    }
}
