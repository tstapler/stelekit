package dev.stapler.stelekit.sketch

import arrow.core.Either
import dev.stapler.stelekit.units.Inches
import dev.stapler.stelekit.units.Rational
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewTransformTest {
    private fun <A> Either<*, A>.ok(): A = fold({ error("unexpected $it") }, { it })

    private val zoomArb: Arb<Rational> = arbitrary { rs ->
        // zoom = n/100 with n in 10..2000, i.e. 0.1..20
        Rational(Arb.long(10L..2_000L).sample(rs).value, 100L)
    }
    private val screenArb: Arb<Rational> = arbitrary { rs ->
        Rational(Arb.long(-50_000L..50_000L).sample(rs).value, Arb.long(1L..64L).sample(rs).value)
    }
    private val pointArb: Arb<SketchPoint> = arbitrary { rs ->
        SketchPoint(
            Inches(Arb.long(-1_000_000L..1_000_000L).sample(rs).value),
            Inches(Arb.long(-1_000_000L..1_000_000L).sample(rs).value),
        )
    }

    @Test
    fun roundTripIsExactAtEveryZoom() = runTest {
        checkAll(zoomArb, screenArb, screenArb, pointArb) { zoom, ox, oy, p ->
            val vt = ViewTransform.of(zoom, ScreenPoint(ox, oy))
            assertEquals(p, vt.toSheet(vt.toScreen(p).ok()).ok())
        }
    }

    @Test
    fun zoomIsClampedToLegalRange() {
        assertEquals(ViewTransform.MIN_ZOOM, ViewTransform.of(Rational(1, 1000)).zoom)
        assertEquals(ViewTransform.MAX_ZOOM, ViewTransform.of(Rational(500)).zoom)
        assertEquals(Rational.ONE, ViewTransform.of(Rational.ONE).zoom)
    }

    @Test
    fun zoomAtKeepsAnchorFixed() = runTest {
        checkAll(zoomArb, zoomArb, screenArb, screenArb, screenArb, screenArb) { z1, z2, ox, oy, ax, ay ->
            val vt = ViewTransform.of(z1, ScreenPoint(ox, oy))
            val anchor = ScreenPoint(ax, ay)
            val zoomed = vt.zoomAt(anchor, z2).ok()
            assertEquals(vt.toSheet(anchor).ok(), zoomed.toSheet(anchor).ok())
        }
    }

    @Test
    fun hitToleranceTimesScaleIsTwelveDp() = runTest {
        checkAll(zoomArb) { zoom ->
            val vt = ViewTransform.of(zoom)
            assertEquals(Rational(12), vt.hitToleranceInches().ok().multiply(vt.dpPerInch).ok())
        }
    }

    @Test
    fun hitToleranceExamples() {
        // 96 dp/in at zoom 1: 12 dp is 1/8 inch.
        assertEquals(Rational(1, 8), ViewTransform.of(Rational.ONE).hitToleranceInches().ok())
        assertEquals(Rational(1, 80), ViewTransform.of(Rational(10)).hitToleranceInches().ok())
    }

    @Test
    fun panMovesOriginOnly() {
        val vt = ViewTransform.of(Rational(2)).panBy(Rational(5), Rational(-3)).ok()
        assertEquals(ScreenPoint(Rational(5), Rational(-3)), vt.origin)
        assertEquals(Rational(2), vt.zoom)
    }
}
