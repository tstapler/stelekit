// jvmTest-reason: n/a (commonTest; runs on JVM, Android unit and wasmJs)
package dev.stapler.stelekit.sketch.constraints

import dev.stapler.stelekit.sketch.constraints.constraints.AngleDimension
import dev.stapler.stelekit.sketch.constraints.constraints.AnglePolicy
import dev.stapler.stelekit.sketch.constraints.constraints.Coincident
import dev.stapler.stelekit.sketch.constraints.constraints.ConstraintEquation
import dev.stapler.stelekit.sketch.constraints.constraints.DiameterDimension
import dev.stapler.stelekit.sketch.constraints.constraints.DistanceDimension
import dev.stapler.stelekit.sketch.constraints.constraints.DistanceX
import dev.stapler.stelekit.sketch.constraints.constraints.DistanceY
import dev.stapler.stelekit.sketch.constraints.constraints.EqualLength
import dev.stapler.stelekit.sketch.constraints.constraints.EqualRadius
import dev.stapler.stelekit.sketch.constraints.constraints.FixedPoint
import dev.stapler.stelekit.sketch.constraints.constraints.FixedScalar
import dev.stapler.stelekit.sketch.constraints.constraints.Horizontal
import dev.stapler.stelekit.sketch.constraints.constraints.Parallel
import dev.stapler.stelekit.sketch.constraints.constraints.Perpendicular
import dev.stapler.stelekit.sketch.constraints.constraints.PointLineDistance
import dev.stapler.stelekit.sketch.constraints.constraints.PointOnCircle
import dev.stapler.stelekit.sketch.constraints.constraints.PointOnLine
import dev.stapler.stelekit.sketch.constraints.constraints.RadiusDimension
import dev.stapler.stelekit.sketch.constraints.constraints.RadiusRef
import dev.stapler.stelekit.sketch.constraints.constraints.Symmetric
import dev.stapler.stelekit.sketch.constraints.constraints.TangentArcArcEndpoint
import dev.stapler.stelekit.sketch.constraints.constraints.TangentCircleCircle
import dev.stapler.stelekit.sketch.constraints.constraints.TangentLineArcEndpoint
import dev.stapler.stelekit.sketch.constraints.constraints.TangentLineCircle
import dev.stapler.stelekit.sketch.constraints.constraints.Vertical
import dev.stapler.stelekit.sketch.constraints.linalg.DenseMatrix
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 4.1.2 acceptance: every analytic Jacobian equals central differences, and degenerate geometry
 * never yields NaN or Infinity or an exception. Layout: six points at variable indices 0, 2, .. 10
 * (P0..P5), then two scalar radii at 12 and 13.
 */
class JacobianFiniteDifferenceTest {
    private val p = IntArray(6) { it * 2 }
    private val r1 = 12
    private val r2 = 13
    private val variableCount = 14

    private val fdStep = 1e-6
    private val fdTolerance = 1e-6

    private class Case(val name: String, val build: (DoubleArray) -> ConstraintEquation)

    private val cases: List<Case> = listOf(
        Case("Coincident") { Coincident("c", p[0], p[1]) },
        Case("FixedPoint") { FixedPoint("c", p[0], 1.5, -2.0) },
        Case("FixedScalar") { FixedScalar("c", r1, 2.0) },
        Case("PointOnLine") { PointOnLine("c", p[2], p[0], p[1]) },
        Case("PointOnCircle") { PointOnCircle("c", p[2], p[0], RadiusRef.scalar(r1)) },
        Case("PointOnArc") { PointOnCircle("c", p[2], p[0], RadiusRef.arc(p[0], p[1])) },
        Case("Horizontal") { Horizontal("c", p[0], p[1]) },
        Case("Vertical") { Vertical("c", p[0], p[1]) },
        Case("Parallel") { Parallel("c", p[0], p[1], p[2], p[3]) },
        Case("ParallelSharedPoint") { Parallel("c", p[0], p[1], p[1], p[2]) },
        Case("Perpendicular") { Perpendicular("c", p[0], p[1], p[2], p[3]) },
        Case("EqualLength") { EqualLength("c", p[0], p[1], p[2], p[3]) },
        Case("EqualRadius") { EqualRadius("c", RadiusRef.scalar(r1), RadiusRef.scalar(r2)) },
        Case("EqualRadiusCircleArc") { EqualRadius("c", RadiusRef.scalar(r1), RadiusRef.arc(p[0], p[1])) },
        Case("Symmetric") { Symmetric("c", p[0], p[1], p[2], p[3]) },
        Case("TangentLineCircle") { x -> TangentLineCircle("c", p[0], p[1], p[2], RadiusRef.scalar(r1), TangentLineCircle.sideOf(x, p[0], p[1], p[2])) },
        Case("TangentLineArc") { x -> TangentLineCircle("c", p[0], p[1], p[2], RadiusRef.arc(p[2], p[3]), TangentLineCircle.sideOf(x, p[0], p[1], p[2])) },
        Case("TangentCircleCircle") { x -> TangentCircleCircle.infer("c", x, p[0], RadiusRef.scalar(r1), p[1], RadiusRef.scalar(r2)) },
        Case("TangentArcCircle") { x -> TangentCircleCircle.infer("c", x, p[0], RadiusRef.arc(p[0], p[2]), p[1], RadiusRef.scalar(r2)) },
        Case("TangentLineArcEndpoint") { TangentLineArcEndpoint("c", p[0], p[1], p[2], p[3]) },
        Case("TangentArcArcEndpoint") { TangentArcArcEndpoint("c", p[0], p[1], p[2]) },
        Case("Distance") { x -> DistanceDimension("c", p[0], p[1], x[0] + 3.0) },
        Case("PointLineDistance") { x -> PointLineDistance("c", p[2], p[0], p[1], PointLineDistance("probe", p[2], p[0], p[1], 0.0, reference = true).measure(x) + 0.5) },
        Case("DistanceX") { DistanceX("c", p[0], p[1], 2.5) },
        Case("DistanceY") { DistanceY("c", p[0], p[1], -1.5) },
        Case("Radius") { RadiusDimension("c", RadiusRef.scalar(r1), 2.0) },
        Case("RadiusArc") { RadiusDimension("c", RadiusRef.arc(p[0], p[1]), 2.0) },
        Case("Diameter") { DiameterDimension("c", RadiusRef.scalar(r1), 4.0) },
        Case("DiameterArc") { DiameterDimension("c", RadiusRef.arc(p[0], p[1]), 4.0) },
        Case("AngleDirected") { x -> angleNear(x, AnglePolicy.Directed) },
        Case("AngleLines") { x -> angleNear(x, AnglePolicy.Lines) },
    )

    /** An angle target a little off the current angle, so the wrap is nowhere near its jump. */
    private fun angleNear(x: DoubleArray, policy: AnglePolicy): AngleDimension {
        val probe = AngleDimension("probe", p[0], p[1], p[2], p[3], 0.0, policy, reference = true)
        return AngleDimension("c", p[0], p[1], p[2], p[3], probe.measure(x) + 0.3, policy)
    }

    /** Six points pairwise at least 0.5 apart in a 10 x 10 box, radii in 0.5..3. */
    private fun randomConfig(rng: Random): DoubleArray {
        val x = DoubleArray(variableCount)
        var placed = 0
        while (placed < 6) {
            val cx = rng.nextDouble(-5.0, 5.0)
            val cy = rng.nextDouble(-5.0, 5.0)
            val clear = (0 until placed).all { k ->
                val dx = x[p[k]] - cx
                val dy = x[p[k] + 1] - cy
                dx * dx + dy * dy > 0.25
            }
            if (clear) { x[p[placed]] = cx; x[p[placed] + 1] = cy; placed++ }
        }
        x[r1] = rng.nextDouble(0.5, 3.0)
        x[r2] = rng.nextDouble(0.5, 3.0)
        return x
    }

    private fun residuals(c: ConstraintEquation, x: DoubleArray): DoubleArray =
        DoubleArray(c.rowCount).also { c.residual(x, it, 0) }

    private fun analytic(c: ConstraintEquation, x: DoubleArray): DenseMatrix =
        DenseMatrix(c.rowCount, variableCount).also { c.jacobian(x, it, 0) }

    private fun maxFdError(c: ConstraintEquation, x: DoubleArray): Double {
        val j = analytic(c, x)
        var worst = 0.0
        for (v in 0 until variableCount) {
            val keep = x[v]
            x[v] = keep + fdStep
            val plus = residuals(c, x)
            x[v] = keep - fdStep
            val minus = residuals(c, x)
            x[v] = keep
            for (row in 0 until c.rowCount) {
                val numeric = (plus[row] - minus[row]) / (2 * fdStep)
                worst = maxOf(worst, abs(numeric - j[row, v]))
            }
        }
        return worst
    }

    @Test
    fun analyticJacobian_should_MatchCentralDifferences_ForEveryConstraintType() = runTest {
        for (case in cases) {
            checkAll(25, Arb.int()) { seed ->
                val x = randomConfig(Random(seed))
                val err = maxFdError(case.build(x), x)
                assertTrue(err < fdTolerance, "${case.name} seed $seed: max Jacobian error $err")
            }
        }
    }

    @Test
    fun degenerateGeometry_should_GiveFiniteResidualsAndJacobians_WithoutThrowing() {
        val starts = listOf(
            DoubleArray(variableCount),
            DoubleArray(variableCount) { if (it < r1) 3.25 else 0.0 },
            DoubleArray(variableCount) { if (it < r1) (it / 2).toDouble() else 1.0 }.also { v -> v[2] = v[0]; v[3] = v[1] },
        )
        for (x in starts) for (case in cases) {
            val c = case.build(x)
            val out = residuals(c, x)
            val j = analytic(c, x)
            assertTrue(out.all { it.isFinite() }, "${case.name}: residual not finite")
            assertTrue(j.data.all { it.isFinite() }, "${case.name}: Jacobian not finite")
        }
    }

    @Test
    fun zeroLengthLine_should_AddNoGradientAndNoNaN_WhenParallel() {
        val x = DoubleArray(variableCount) { 0.0 }
        x[4] = 1.0; x[5] = 2.0; x[6] = 3.0; x[7] = 5.0
        val c = Parallel("c", p[0], p[1], p[2], p[3])
        assertEquals(0.0, residuals(c, x)[0])
        assertTrue(analytic(c, x).data.all { it.isFinite() })
    }

    @Test
    fun residuals_should_BeZero_WhenGeometrySatisfiesTheConstraint() {
        val x = DoubleArray(variableCount)
        fun put(i: Int, px: Double, py: Double) { x[p[i]] = px; x[p[i] + 1] = py }
        put(0, 0.0, 0.0); put(1, 4.0, 0.0); put(2, 0.0, 3.0); put(3, 4.0, 3.0); put(4, 2.0, 1.0); put(5, 0.0, 0.0)
        x[r1] = 1.0
        assertEquals(0.0, residuals(Horizontal("h", p[0], p[1]), x)[0])
        assertEquals(0.0, residuals(Vertical("v", p[0], p[2]), x)[0])
        assertEquals(0.0, residuals(Parallel("p", p[0], p[1], p[2], p[3]), x)[0])
        assertEquals(0.0, residuals(Perpendicular("q", p[0], p[1], p[0], p[2]), x)[0])
        assertEquals(0.0, residuals(EqualLength("e", p[0], p[1], p[2], p[3]), x)[0])
        // Mirror of (0,3) about the x axis (P0-P1) is (0,-3); here P0 and P2 mirror across the line y = 1.5.
        put(4, 0.0, 6.0)
        put(5, -1.0, 3.0)
        // Axis P5=(-1,3) to P3=(4,3) is y = 3; (0,0) and (0,6) are mirror images.
        assertEquals(0.0, residuals(Symmetric("s", p[0], p[4], p[5], p[3]), x).maxOf { abs(it) })
        // Line y = 0 is tangent to a unit circle centered at (0, 1) (side +1) and to one at (0, -1) (side -1).
        put(4, 0.0, 1.0)
        val up = TangentLineCircle("t", p[0], p[1], p[4], RadiusRef.scalar(r1), TangentLineCircle.sideOf(x, p[0], p[1], p[4]))
        assertEquals(0.0, residuals(up, x)[0])
        put(4, 0.0, -1.0)
        val down = TangentLineCircle("t", p[0], p[1], p[4], RadiusRef.scalar(r1), TangentLineCircle.sideOf(x, p[0], p[1], p[4]))
        assertEquals(0.0, residuals(down, x)[0])
    }

    @Test
    fun tangentCircleCircle_should_PickExternalOrInternalBranchFromTheStart() {
        val x = DoubleArray(variableCount)
        x[p[1]] = 5.0
        x[r1] = 2.0; x[r2] = 3.0
        val external = TangentCircleCircle.infer("e", x, p[0], RadiusRef.scalar(r1), p[1], RadiusRef.scalar(r2))
        assertEquals(0.0, residuals(external, x)[0])
        x[p[1]] = 1.0
        val internal = TangentCircleCircle.infer("i", x, p[0], RadiusRef.scalar(r1), p[1], RadiusRef.scalar(r2))
        assertEquals(0.0, residuals(internal, x)[0])
        x[p[1]] = 2.0
        assertTrue(abs(residuals(internal, x)[0]) > 0.5, "moving off the internal tangent must show a residual")
    }

    @Test
    fun angleDimension_should_DisambiguateByPolicy_AndReferenceModeAddsNoRows() {
        val x = DoubleArray(variableCount)
        // u = P0->P1 along +x, v = P2->P3 along +y: counter-clockwise 90 degrees.
        x[p[1]] = 1.0
        x[p[3] + 1] = 1.0
        val quarter = PI / 2
        assertEquals(0.0, residuals(AngleDimension("a", p[0], p[1], p[2], p[3], quarter, AnglePolicy.Directed), x)[0], 1e-12)
        // Reversing v makes the directed angle -90 degrees (a residual of pi), but the same line pair.
        x[p[2] + 1] = 1.0; x[p[3] + 1] = 0.0
        assertTrue(abs(residuals(AngleDimension("a", p[0], p[1], p[2], p[3], quarter, AnglePolicy.Directed), x)[0]) > 3.0)
        assertEquals(0.0, residuals(AngleDimension("a", p[0], p[1], p[2], p[3], quarter, AnglePolicy.Lines), x)[0], 1e-12)
        val ref = AngleDimension("r", p[0], p[1], p[2], p[3], 0.0, AnglePolicy.Lines, reference = true)
        assertEquals(0, ref.rowCount)
        assertEquals(-quarter, ref.measure(x), 1e-12)
        val j = DenseMatrix(0, variableCount)
        ref.jacobian(x, j, 0)
        assertEquals(0, residuals(ref, x).size)
    }
}
