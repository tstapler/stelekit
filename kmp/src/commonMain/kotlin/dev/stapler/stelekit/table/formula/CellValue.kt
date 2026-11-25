package dev.stapler.stelekit.table.formula

import dev.stapler.stelekit.table.NumberGrammar
import kotlin.math.abs

enum class CellError(val display: String) {
    Parse("#PARSE!"),
    Ref("#REF!"),
    Cycle("#CYCLE!"),
    DivZero("#DIV/0!"),
    Value("#VALUE!"),
    Num("#NUM!"),
    Name("#NAME?"),
    Limit("#LIMIT!"),
}

fun FormulaParseError.toCellError(): CellError = when (this) {
    FormulaParseError.Parse -> CellError.Parse
    FormulaParseError.Name -> CellError.Name
    FormulaParseError.Limit -> CellError.Limit
}

/** Result of evaluating one cell. [Blank] only appears for empty operands, never as a formula result. */
sealed interface CellValue {
    data class Num(val value: Double) : CellValue
    data class Text(val value: String) : CellValue
    data object Blank : CellValue
    data class Err(val error: CellError) : CellValue

    companion object {
        /** Value of a non-formula cell's raw text. */
        fun fromRaw(raw: String): CellValue {
            if (raw.isBlank()) return Blank
            val n = NumberGrammar.parse(raw)
            return if (n != null) Num(n) else Text(raw)
        }
    }
}

/** 12-significant-digit display (ADR-002) built without `String.format`, whose output differs per target. */
object NumberDisplay {
    private const val SIGNIFICANT = 12
    private const val PLAIN_MIN_EXP = -7
    private const val PLAIN_MAX_EXP = 14

    fun format(value: Double): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        if (value == 0.0) return "0"
        val sign = if (value < 0) "-" else ""
        var (digits, pos) = shortestDigits(abs(value))
        // value = 0.<digits> * 10^pos; round half-up on the shortest round-trip digits.
        if (digits.length > SIGNIFICANT) {
            val roundUp = digits[SIGNIFICANT] >= '5'
            val kept = digits.substring(0, SIGNIFICANT).toCharArray()
            if (roundUp) {
                var k = SIGNIFICANT - 1
                while (k >= 0) {
                    if (kept[k] == '9') {
                        kept[k] = '0'
                        k--
                    } else {
                        kept[k] = kept[k] + 1
                        break
                    }
                }
                digits = if (k < 0) {
                    pos++
                    "1" + kept.concatToString().substring(1)
                } else {
                    kept.concatToString()
                }
            } else {
                digits = kept.concatToString()
            }
        }
        digits = digits.trimEnd('0')
        if (digits.isEmpty()) return "0"
        val sci = pos - 1
        if (sci < PLAIN_MIN_EXP || sci > PLAIN_MAX_EXP) {
            val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
            return sign + mantissa + "E" + (if (sci < 0) "-" else "+") + abs(sci)
        }
        val body = when {
            pos <= 0 -> "0." + "0".repeat(-pos) + digits
            pos >= digits.length -> digits + "0".repeat(pos - digits.length)
            else -> digits.substring(0, pos) + "." + digits.substring(pos)
        }
        return sign + body
    }

    /**
     * Rounds `0.<digits> * 10^pos` half up (digits are of a magnitude) to its first [keep] digits; an empty result
     * means zero. Carry out of the first digit raises the position.
     */
    internal fun roundDigits(digits: String, pos: Int, keep: Int): Pair<String, Int> {
        if (keep >= digits.length) return digits to pos
        if (keep < 0) return "" to pos
        val up = digits[keep] >= '5'
        if (keep == 0) return if (up) "1" to pos + 1 else "" to pos
        if (!up) return digits.substring(0, keep) to pos
        val kept = digits.substring(0, keep).toCharArray()
        var k = keep - 1
        while (k >= 0) {
            if (kept[k] == '9') {
                kept[k] = '0'
                k--
            } else {
                kept[k] = kept[k] + 1
                break
            }
        }
        return if (k < 0) ("1" + kept.concatToString()) to pos + 1 else kept.concatToString() to pos
    }

    /** Digits (no leading or trailing zeros) and decimal point position of `Double.toString`, parsed from any target's format. */
    internal fun shortestDigits(positive: Double): Pair<String, Int> {
        val s = positive.toString()
        val e = s.indexOfFirst { it == 'e' || it == 'E' }
        val mantissa = if (e >= 0) s.substring(0, e) else s
        val exp = if (e >= 0) s.substring(e + 1).removePrefix("+").toInt() else 0
        val dot = mantissa.indexOf('.')
        val intPart = if (dot >= 0) mantissa.substring(0, dot) else mantissa
        val frac = if (dot >= 0) mantissa.substring(dot + 1) else ""
        var all = intPart + frac
        var pos = intPart.length + exp
        val lead = all.indexOfFirst { it != '0' }
        if (lead < 0) return "0" to 0
        all = all.substring(lead)
        pos -= lead
        return all.trimEnd('0') to pos
    }
}
