// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license
//
// The recurrence below is the one rough.js (MIT, (c) 2019 Preet Shihn, see NOTICE) uses, so a seed stored by
// Excalidraw reproduces the same jitter here.

package dev.stapler.stelekit.sketch.v2.render

/**
 * Deterministic generator behind Sketchy (ADR-004): `state = state * 48271` in 32-bit wrapping arithmetic, value =
 * low 31 bits / 2^31. Pure integer maths, so every target (JVM, Android, wasmJs) draws the same sequence;
 * `kotlin.random.Random` is deliberately not used. A seed of 0 is mapped to 1 (rough.js would fall back to
 * `Math.random()` for 0, which cannot be reproduced).
 */
class SeededRandom(seed: Int) {
    private var state: Int = if (seed == 0) 1 else seed

    /** Next value in [0, 1). */
    fun next(): Double {
        state *= MULTIPLIER
        return (state and LOW_31_BITS).toDouble() / MODULUS
    }

    private companion object {
        const val MULTIPLIER = 48271
        const val LOW_31_BITS = 0x7fffffff
        const val MODULUS = 2147483648.0
    }
}
