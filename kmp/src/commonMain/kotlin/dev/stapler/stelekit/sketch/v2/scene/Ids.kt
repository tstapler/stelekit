// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.scene

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlin.jvm.JvmInline
import kotlin.math.abs

/** Why a scene value cannot be constructed (parse, don't validate). */
sealed interface ValueError {
    /** NaN or an infinity (ADR-002: non-finite values are unrepresentable). */
    data class NonFinite(val raw: Double) : ValueError

    /** Finite but beyond `SceneLimits.maxCoord`. */
    data class OutOfRange(val raw: Double, val bound: Double) : ValueError

    /** A radius or size that must be strictly positive. */
    data class NotPositive(val raw: Double) : ValueError
}

/**
 * A finite scene coordinate or length, 1 unit = 1/16 inch (ADR-002). The only way to obtain one is [of], so a
 * `NaN`, an infinity or a value beyond `SceneLimits.maxCoord` can never reach an element. Negative zero is
 * normalised to zero so structural equality and canonical JSON agree.
 */
@JvmInline
value class SceneUnit private constructor(val value: Double) {
    override fun toString(): String = value.toString()

    companion object {
        val ZERO = SceneUnit(0.0)

        fun of(d: Double, limits: SceneLimits = SceneLimits.Default): Either<ValueError, SceneUnit> = when {
            !d.isFinite() -> ValueError.NonFinite(d).left()
            abs(d) > limits.maxCoord -> ValueError.OutOfRange(d, limits.maxCoord).left()
            else -> SceneUnit(d + 0.0).right()
        }

        /** For literals already known to be in range (tests, generated geometry); an out-of-range input is a programming error. */
        fun ofInt(i: Int): SceneUnit {
            val d = i.toDouble()
            require(abs(d) <= MAX_LITERAL) { "literal outside the coordinate bound: $i" }
            return SceneUnit(d + 0.0)
        }

        private const val MAX_LITERAL = SceneLimits.MAX_COORD
    }
}

/** Stable string id of a scene element; remapped on paste and import. */
@JvmInline
value class ElementId(val value: String) : Comparable<ElementId> {
    override fun compareTo(other: ElementId): Int = value.compareTo(other.value)
    override fun toString(): String = value
}

/** Fractional order string within a layer; collisions fall back to array order on import. */
@JvmInline
value class ZKey(val value: String) : Comparable<ZKey> {
    override fun compareTo(other: ZKey): Int = value.compareTo(other.value)
    override fun toString(): String = value
}

@JvmInline
value class GroupId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class LayerId(val value: String) {
    override fun toString(): String = value

    companion object {
        /** The default layer exists on every sheet (ADR-006). */
        val Default = LayerId("default")
    }
}
