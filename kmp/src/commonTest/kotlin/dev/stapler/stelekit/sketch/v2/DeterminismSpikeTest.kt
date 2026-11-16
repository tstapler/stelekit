package dev.stapler.stelekit.sketch.v2

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spike 0.1.3a: canonical (ECMAScript Number::toString) double formatting built on the platform's
 * `Double.toString()` shortest digits. Prototype only; Story 1.1.2 re-implements it in `V2/codec`.
 * Runs on every target (JVM, wasmJs, Android unit tests via commonTest); equal expectations on all
 * of them is the cross-target evidence.
 */
object CanonicalDoubleSpike {
    /** Rounds the digit string (value = 0.DIGITS * 10^n) half-up to [len] digits; returns digits and the new n. */
    private fun roundDigits(digits: String, n: Int, len: Int): Pair<String, Int> {
        val kept = digits.substring(0, len).toCharArray()
        if (digits[len] < '5') return kept.concatToString().trimEnd('0').ifEmpty { "0" } to n
        var i = len - 1
        while (i >= 0) {
            if (kept[i] == '9') { kept[i] = '0'; i-- } else { kept[i] = kept[i] + 1; break }
        }
        return if (i < 0) "1" to n + 1 else kept.concatToString().trimEnd('0') to n
    }

    fun format(d: Double): String {
        require(d.isFinite()) { "non-finite" }
        if (d == 0.0) return "0"
        val neg = d < 0
        val raw = abs(d).toString()
        val eIdx = raw.indexOfFirst { it == 'e' || it == 'E' }
        val mant = if (eIdx >= 0) raw.substring(0, eIdx) else raw
        val exp10 = if (eIdx >= 0) raw.substring(eIdx + 1).removePrefix("+").toInt() else 0
        val dot = mant.indexOf('.')
        val intPart = if (dot >= 0) mant.substring(0, dot) else mant
        val fracPart = if (dot >= 0) mant.substring(dot + 1) else ""
        var digits = intPart + fracPart
        var n = intPart.length + exp10 // value = 0.DIGITS * 10^n
        val lead = digits.indexOfFirst { it != '0' }
        digits = digits.substring(lead)
        n -= lead
        digits = digits.trimEnd('0')
        // Some platforms' toString() is not always the shortest digit string (JVM keeps "4.9E-324" for
        // Double.MIN_VALUE where ECMAScript says "5e-324"), so shorten while the value still round-trips.
        for (len in 1 until digits.length) {
            val (cand, candN) = roundDigits(digits, n, len)
            if (("0." + cand + "e" + candN).toDouble() == abs(d)) { digits = cand; n = candN; break }
        }
        val k = digits.length
        val body = when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val e = n - 1
                val m = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                m + "e" + (if (e < 0) "-" else "+") + abs(e)
            }
        }
        return if (neg) "-$body" else body
    }
}

class DeterminismSpikeTest {

    @Test
    fun planFixtureValues() {
        val got = listOf(0.1, 1e-5, 123456789.125, -0.0, 4.0).map(CanonicalDoubleSpike::format)
        assertEquals(listOf("0.1", "0.00001", "123456789.125", "0", "4"), got)
    }

    @Test
    fun edgeCasesMatchEcmaScript() {
        val expected = mapOf(
            1e21 to "1e+21", 1e20 to "100000000000000000000", 1e-6 to "0.000001", 1e-7 to "1e-7",
            1.5e300 to "1.5e+300", 5e-324 to "5e-324", Double.MAX_VALUE to "1.7976931348623157e+308",
            0.1 + 0.2 to "0.30000000000000004", 9007199254740992.0 to "9007199254740992",
            -123.456 to "-123.456", 100.0 to "100", 1.0E7 to "10000000", 0.001 to "0.001",
            4.35 to "4.35", 2.0.pow53() to "9007199254740992",
        )
        expected.forEach { (d, s) -> assertEquals(s, CanonicalDoubleSpike.format(d), "for $d") }
    }

    private fun Double.pow53(): Double = this * 4503599627370496.0

    private fun roundTripReport(name: String, n: Int, gen: (Random) -> Double): Pair<Int, Int> {
        val rnd = Random(20261116)
        var rawFailures = 0
        var canonFailures = 0
        repeat(n) {
            val d = gen(rnd)
            if (d.toString().toDouble() != d) rawFailures++
            if (CanonicalDoubleSpike.format(d).toDouble() != d) canonFailures++
        }
        println("SPIKE_DOUBLE_ROUNDTRIP class=$name n=$n platformToStringNotRoundTripping=$rawFailures canonicalNotRoundTripping=$canonFailures")
        return rawFailures to canonFailures
    }

    @Test
    fun sceneCoordinateClassesRoundTrip() {
        assertEquals(0, roundTripReport("sixteenths", 20_000) { it.nextInt(-2_000_000, 2_000_000) / 16.0 }.second)
    }

    /** Recorded, not asserted: full-precision values differ on wasmJs (6 of 20,000 here), see ADR-002 "Spike 0.1.3a". */
    @Test
    fun uniformRoundTripIsReported() {
        roundTripReport("uniform0to1000", 20_000) { it.nextDouble() * 1000.0 }
    }

    /** Recorded, not asserted: the full-range result is a platform fact that ADR-002 reports per target. */
    @Test
    fun randomBitPatternRoundTripIsReported() {
        roundTripReport("fullBits", 20_000) { r -> generateSequence { Double.fromBits(r.nextLong()) }.first { it.isFinite() } }
    }

    /**
     * Hash of the canonical strings of fixed pseudo-random corpora, one per input class, must be identical on
     * every target for the class scene coordinates come from (multiples of 1/16). Wasm differs on full-precision
     * classes (Double.toString there is not shortest-round-trip); JVM 21 recorded uniform=1461392967,
     * fullBits=1799138217, wasm gave uniform=-1618638240, fullBits=-1299924261.
     */
    @Test
    fun corpusChecksumsAreIdenticalAcrossTargets() {
        val rnd = Random(1337)
        fun hashOf(n: Int, gen: () -> Double): Int {
            var h = -0x7ee3623b // FNV-1a offset basis (as Int)
            repeat(n) {
                for (c in CanonicalDoubleSpike.format(gen())) { h = (h xor c.code) * 16777619 }
                h = (h xor '\n'.code) * 16777619
            }
            return h
        }
        val rounded = hashOf(5_000) { rnd.nextDouble() * 1000.0 }
        val fullBits = hashOf(5_000) { Double.fromBits(rnd.nextLong()).takeIf { it.isFinite() } ?: 1.0 }
        val sixteenths = hashOf(5_000) { (rnd.nextInt(-2_000_000, 2_000_000)) / 16.0 }
        println("DETERMINISM_CHECKSUM uniform0to1000=$rounded fullBits=$fullBits sixteenths=$sixteenths")
        assertEquals(EXPECTED_SIXTEENTHS, sixteenths, "scene-unit (k/16) corpus must match JVM on every target")
        // fullBits is recorded in ADR-002 "Spike 0.1.3a", not asserted: wasm Double.toString is not shortest-round-trip.
    }

    private companion object {
        const val EXPECTED_SIXTEENTHS = 343850831 // recorded on JVM 21
    }
}
