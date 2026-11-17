// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.constraints

import kotlin.test.Test
import kotlin.test.assertEquals

/** The tier a target gets from the recorded measurements (ADR-003 item 7), which `HostServices.tier` reads. */
class SolverTierFactsTest {
    @Test
    fun recorded_should_BeFullOnEveryMeasuredTarget() {
        assertEquals(SolverTier.Full, SolverTierFacts.Recorded.tierOf(SolverTarget.Jvm))
        assertEquals(SolverTier.Full, SolverTierFacts.Recorded.tierOf(SolverTarget.WasmJs))
    }

    @Test
    fun aTargetWithNoMeasurement_should_BeHidden() {
        assertEquals(SolverTier.Hidden, SolverTierFacts.Recorded.tierOf(null))
    }

    @Test
    fun aWasmOnlyTimeMiss_should_ChangeOnlyTheWasmTier() {
        val ok = TargetMeasurement(fullSetTimeMet = true, geometricOnlySetTimeMet = true, geometricOnlySetResidualMet = true)
        val facts = SolverTierFacts(true, mapOf(SolverTarget.Jvm to ok, SolverTarget.WasmJs to ok.copy(fullSetTimeMet = false)))
        assertEquals(SolverTier.Full, facts.tierOf(SolverTarget.Jvm))
        assertEquals(SolverTier.GeometricOnly, facts.tierOf(SolverTarget.WasmJs))
    }
}
