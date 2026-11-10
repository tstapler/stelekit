package dev.stapler.stelekit.units

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/** Why an exact arithmetic step failed. Never produced by wrapping or rounding. */
sealed interface ArithmeticError {
    /** The exact result does not fit in a `Long`. */
    data object Overflow : ArithmeticError

    data object DivideByZero : ArithmeticError
}

/**
 * Overflow-checked `Long` operations written in common code: `Math.*Exact` is JVM-only and the
 * units package must compile for wasmJs and iOS.
 */
object CheckedMath {
    fun addExact(a: Long, b: Long): Either<ArithmeticError.Overflow, Long> {
        val r = a + b
        // Overflow iff both operands have the sign opposite to the result.
        return if (((a xor r) and (b xor r)) < 0) ArithmeticError.Overflow.left() else r.right()
    }

    fun negateExact(a: Long): Either<ArithmeticError.Overflow, Long> =
        if (a == Long.MIN_VALUE) ArithmeticError.Overflow.left() else (-a).right()

    fun mulExact(a: Long, b: Long): Either<ArithmeticError.Overflow, Long> {
        if (a == 0L || b == 0L) return 0L.right()
        if ((a == Long.MIN_VALUE && b == -1L) || (b == Long.MIN_VALUE && a == -1L)) {
            return ArithmeticError.Overflow.left()
        }
        val r = a * b
        return if (r / b != a) ArithmeticError.Overflow.left() else r.right()
    }

    /** Greatest common divisor of the magnitudes; neither input may be `Long.MIN_VALUE`. */
    fun gcd(a: Long, b: Long): Long {
        var x = if (a < 0) -a else a
        var y = if (b < 0) -b else b
        while (y != 0L) {
            val t = x % y
            x = y
            y = t
        }
        return x
    }
}
