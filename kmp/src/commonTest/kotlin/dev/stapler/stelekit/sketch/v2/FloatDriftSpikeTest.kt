package dev.stapler.stelekit.sketch.v2

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Spike 0.1.3d: how much does a Double scene coordinate drift when it passes through a Float (Compose
 * `Offset`/`Canvas` are Float)? The solver fixtures of Spike 0.1.2c are NOT read here (that spike runs
 * separately): this measures the drift the fixtures would suffer for coordinates of the stated range.
 */
class FloatDriftSpikeTest {
    private fun maxDrift(range: Double, n: Int = 100_000): Double {
        val rnd = Random(99)
        var worst = 0.0
        repeat(n) { worst = max(worst, abs(rnd.nextDouble(-range, range).let { it - it.toFloat().toDouble() })) }
        return worst
    }

    @Test
    fun floatDriftExceedsSolverResidualBarForRealSheetCoordinates() {
        val report = listOf(1_000.0, 100_000.0, 16_777_216.0, 268_435_456.0).associateWith(::maxDrift)
        println("SPIKE_FLOAT_DRIFT " + report.entries.joinToString { "range=${it.key} maxAbsDrift=${it.value}" })
        // Solver residual bar is 1e-6 scene units (ADR-003). A Float round trip at a 100,000-unit sheet
        // coordinate already drifts well above it, so the solver and the model must stay Double.
        assertTrue(report.getValue(100_000.0) > 1e-6)
        assertTrue(report.getValue(1_000.0) < 1e-4)
    }
}
