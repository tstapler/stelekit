// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.constraints

import kotlin.test.Test
import kotlin.test.assertEquals

/** ADR-003 item 7 / D-114: the per-target tier rule. */
class SolverTierTest {
    private val ok = TargetMeasurement(fullSetTimeMet = true, geometricOnlySetTimeMet = true, geometricOnlySetResidualMet = true)

    @Test
    fun everythingMet_should_BeFullOnEveryTarget() {
        val tiers = SolverTierPolicy.decide(true, mapOf(SolverTarget.Jvm to ok, SolverTarget.WasmJs to ok))
        assertEquals(mapOf(SolverTarget.Jvm to SolverTier.Full, SolverTarget.WasmJs to SolverTier.Full), tiers)
    }

    @Test
    fun aWasmOnlyTimeMiss_should_LeaveTheJvmOnFull() {
        val wasmSlow = ok.copy(fullSetTimeMet = false)
        val tiers = SolverTierPolicy.decide(true, mapOf(SolverTarget.Jvm to ok, SolverTarget.WasmJs to wasmSlow))
        assertEquals(SolverTier.Full, tiers[SolverTarget.Jvm])
        assertEquals(SolverTier.GeometricOnly, tiers[SolverTarget.WasmJs])
    }

    @Test
    fun aTargetIndependentMiss_should_SelectGeometricOnlyEverywhere() {
        val tiers = SolverTierPolicy.decide(false, mapOf(SolverTarget.Jvm to ok, SolverTarget.WasmJs to ok))
        assertEquals(setOf(SolverTier.GeometricOnly), tiers.values.toSet())
    }

    @Test
    fun aGeometricOnlySetThatAlsoMisses_should_BeHiddenOnThatTargetOnly() {
        val wasmBad = ok.copy(fullSetTimeMet = false, geometricOnlySetTimeMet = false)
        val tiers = SolverTierPolicy.decide(true, mapOf(SolverTarget.Jvm to ok, SolverTarget.WasmJs to wasmBad))
        assertEquals(SolverTier.Full, tiers[SolverTarget.Jvm])
        assertEquals(SolverTier.Hidden, tiers[SolverTarget.WasmJs])
        val residualBad = ok.copy(geometricOnlySetResidualMet = false)
        val everywhere = SolverTierPolicy.decide(false, mapOf(SolverTarget.Jvm to residualBad, SolverTarget.WasmJs to residualBad))
        assertEquals(setOf(SolverTier.Hidden), everywhere.values.toSet())
    }
}
