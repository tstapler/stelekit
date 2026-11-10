package dev.stapler.stelekit.fusion

import dev.stapler.stelekit.units.Rational

/**
 * A linear equality over named dimensions with integer coefficients (ADR-003). [id] is the
 * caller's non-negative, unique handle and is what [Provenance] names.
 */
sealed interface Constraint {
    val id: Int

    /** Every dimension the constraint mentions, with repeats. */
    val dimensions: List<String>
}

/** `dimension = value`. */
data class Fixed(override val id: Int, val dimension: String, val value: Rational) : Constraint {
    override val dimensions: List<String> get() = listOf(dimension)
}

/** `total = parts[0] + parts[1] + ...`; a repeated part counts once per repeat. */
data class Sum(override val id: Int, val total: String, val parts: List<String>) : Constraint {
    override val dimensions: List<String> get() = listOf(total) + parts
}

/** `a = b`. */
data class Equal(override val id: Int, val a: String, val b: String) : Constraint {
    override val dimensions: List<String> get() = listOf(a, b)
}

/** `a - b = k`. */
data class Offset(override val id: Int, val a: String, val b: String, val k: Rational) : Constraint {
    override val dimensions: List<String> get() = listOf(a, b)
}
