// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/** Affine matrix `[a c e; b d f; 0 0 1]` as in SVG `matrix(a b c d e f)`. */
data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    /** `this` applied after [inner]: the composed matrix maps a point through [inner] first. */
    fun after(inner: Affine) = Affine(
        a * inner.a + c * inner.b, b * inner.a + d * inner.b,
        a * inner.c + c * inner.d, b * inner.c + d * inner.d,
        a * inner.e + c * inner.f + e, b * inner.e + d * inner.f + f,
    )

    fun apply(x: Double, y: Double): Pair<Double, Double> = Pair(a * x + c * y + e, b * x + d * y + f)

    companion object {
        val IDENTITY = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    }
}

sealed interface TransformError {
    /** Skew cannot be calibrated to a scale, so it is refused rather than approximated. */
    data class Skew(val text: String) : TransformError
    data class Unsupported(val function: String) : TransformError
    data class Malformed(val text: String) : TransformError
}

private val TRANSFORM_FN = Regex("^\\s*([A-Za-z]+)\\s*\\(([^)]*)\\)[\\s,]*")

/** One `transform` attribute: functions apply right to left, so the leftmost is outermost. */
fun parseTransform(text: String): Either<TransformError, Affine> {
    var rest = text
    val fns = mutableListOf<Affine>()
    while (rest.isNotBlank()) {
        val m = TRANSFORM_FN.find(rest) ?: return TransformError.Malformed(text).left()
        val args = m.groupValues[2].split(',', ' ', '\t', '\n').filter { it.isNotBlank() }.map {
            it.toDoubleOrNull() ?: return TransformError.Malformed(text).left()
        }
        val fn = when (val name = m.groupValues[1]) {
            "translate" -> if (args.size in 1..2) Affine(1.0, 0.0, 0.0, 1.0, args[0], args.getOrElse(1) { 0.0 }) else null
            "scale" -> if (args.size in 1..2) Affine(args[0], 0.0, 0.0, args.getOrElse(1) { args[0] }, 0.0, 0.0) else null
            "matrix" -> if (args.size == 6) Affine(args[0], args[1], args[2], args[3], args[4], args[5]) else null
            "skewX", "skewY" -> return TransformError.Skew(text).left()
            else -> return TransformError.Unsupported(name).left()
        } ?: return TransformError.Malformed(text).left()
        fns += fn
        rest = rest.substring(m.value.length)
    }
    return fns.fold(Affine.IDENTITY) { acc, f -> acc.after(f) }.right()
}

/** Ancestor chain, outermost first, composed into one matrix. */
fun composeTransforms(chain: List<String>): Either<TransformError, Affine> {
    var acc = Affine.IDENTITY
    for (t in chain) {
        val next = parseTransform(t).fold({ return it.left() }, { it })
        acc = acc.after(next)
    }
    return acc.right()
}
