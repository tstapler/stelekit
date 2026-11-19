// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.diagnostics.A1Gate
import dev.stapler.stelekit.sketch.v2.diagnostics.A1GateOutcome
import dev.stapler.stelekit.sketch.v2.diagnostics.A1GateRun
import kotlin.test.Test
import kotlin.test.assertEquals

/** Task 6.2.4e: the precedence UNMEASURED, PIVOT, ADJUST, PROCEED and the 2.0 ratio bar. The inputs here are fixtures, not owner data. */
class A1GateTest {
    private fun run(finished: Boolean = true, cont: Boolean = true, app: Double = 100.0, web: Double = 100.0, blockers: Int = 0) =
        A1GateRun(finished, cont, app, web, blockers)

    @Test
    fun evaluate_should_BeUnmeasuredUnderCap_When_NoItemIsReachable_EvenWithARun() {
        assertEquals(A1GateOutcome.UnmeasuredUnderCap, A1Gate.evaluate(false, null))
        assertEquals(A1GateOutcome.UnmeasuredUnderCap, A1Gate.evaluate(false, run()))
    }

    @Test
    fun evaluate_should_ClaimNoVerdict_When_NoRunIsRecorded() {
        assertEquals(A1GateOutcome.NotRun, A1Gate.evaluate(true, null))
        assertEquals(A1GateOutcome.NotRun, A1Gate.evaluate(true, run(app = 0.0)))
        assertEquals(A1GateOutcome.NotRun, A1Gate.evaluate(true, run(web = Double.NaN)))
    }

    @Test
    fun evaluate_should_Pivot_When_NotFinishedOrWouldNotContinue_BeforeAnyAdjust() {
        assertEquals(A1GateOutcome.Pivot, A1Gate.evaluate(true, run(finished = false, app = 500.0, blockers = 3)))
        assertEquals(A1GateOutcome.Pivot, A1Gate.evaluate(true, run(cont = false)))
    }

    @Test
    fun evaluate_should_Adjust_When_RatioOverTheBarOrABlockerIsNamed() {
        assertEquals(A1GateOutcome.Adjust(2.5, 0), A1Gate.evaluate(true, run(app = 250.0)))
        assertEquals(A1GateOutcome.Adjust(1.0, 1), A1Gate.evaluate(true, run(blockers = 1)))
    }

    @Test
    fun evaluate_should_Proceed_When_RatioIsExactlyAtTheBarAndNothingBlocks() {
        assertEquals(A1GateOutcome.Proceed(2.0), A1Gate.evaluate(true, run(app = 200.0)))
        assertEquals(A1GateOutcome.Proceed(0.5), A1Gate.evaluate(true, run(app = 50.0)))
    }
}
