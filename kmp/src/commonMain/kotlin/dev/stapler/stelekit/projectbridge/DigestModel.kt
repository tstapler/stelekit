// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionStatus
import dev.stapler.stelekit.fusion.FusionResult
import dev.stapler.stelekit.fusion.InvariantVerdict
import dev.stapler.stelekit.units.LengthFormat
import dev.stapler.stelekit.units.LengthFormatter
import dev.stapler.stelekit.units.Rational
import dev.stapler.stelekit.units.toInches

/** "total = parts" as a named check; its verdict is the solver's verdict for [constraintId], never recomputed. */
data class SumCheck(val label: String, val constraintId: Int, val total: String, val parts: List<String>)

enum class CheckVerdict { PASS, FAIL, UNKNOWN }

data class CheckOutcome(val label: String, val verdict: CheckVerdict, val summary: String)

/** Exact value text shared by the digest and the LLM payload, so the two cannot drift. */
internal object DimensionDisplay {
    fun value(v: Rational, kind: DimensionKind): String = when (kind) {
        DimensionKind.COUNT -> fraction(v)
        DimensionKind.LENGTH -> v.toInches().fold(
            { fraction(v) + "\"" },
            { LengthFormatter.format(it, LengthFormat.INCHES_MIXED) },
        )
    }

    private fun fraction(v: Rational) = if (v.den == 1L) "${v.num}" else "${v.num}/${v.den}"

    /** The single value a status commits to; a conflict or an unread dimension has none. */
    fun single(status: DimensionStatus?): Rational? = when (status) {
        is DimensionStatus.Confirmed -> status.value
        is DimensionStatus.SingleSource -> status.value
        is DimensionStatus.Derivable -> status.value
        is DimensionStatus.NotRepresentableValue -> status.exact
        is DimensionStatus.Conflict, DimensionStatus.UnderConstrained, null -> null
    }

    fun evaluate(check: SumCheck, result: FusionResult, kindOf: (String) -> DimensionKind): CheckOutcome {
        val verdict = when (result.invariants.firstOrNull { it.constraintId == check.constraintId }?.verdict) {
            InvariantVerdict.HOLDS -> CheckVerdict.PASS
            InvariantVerdict.FAILS -> CheckVerdict.FAIL
            InvariantVerdict.UNDETERMINED, null -> CheckVerdict.UNKNOWN
        }
        fun show(key: String): String {
            val status = result.dimensions[key]
            if (status is DimensionStatus.Conflict) {
                return status.pairs.flatMap { listOf(it.first.value, it.second.value) }.distinct()
                    .joinToString(" or ", prefix = "[", postfix = "]") { value(it, kindOf(key)) }
            }
            return single(status)?.let { value(it, kindOf(key)) } ?: "?"
        }
        val joiner = if (verdict == CheckVerdict.FAIL) " != " else " = "
        val sum = check.parts.joinToString(" + ") { show(it) }
        return CheckOutcome(check.label, verdict, "($sum)$joiner${show(check.total)}")
    }
}
