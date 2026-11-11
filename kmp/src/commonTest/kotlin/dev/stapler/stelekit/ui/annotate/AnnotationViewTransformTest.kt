package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.geometry.Offset
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnnotationViewTransformTest {
    /** A wide photo letterboxed in a square viewport. */
    private val letterboxed = ViewGeometry(
        canvasWidth = 1000f, canvasHeight = 500f, canvasLeft = 0f, canvasTop = 250f,
        viewportWidth = 1000f, viewportHeight = 1000f,
    )

    /** Canvas fills the viewport and there is lots of pan room, so clamping never moves a zoom anchor. */
    private val roomy = ViewGeometry(
        canvasWidth = 1000f, canvasHeight = 1000f, canvasLeft = 0f, canvasTop = 0f,
        viewportWidth = 1000f, viewportHeight = 1000f, slack = 1_000_000f,
    )

    private fun close(expected: Float, actual: Float, tolerance: Float = 0.05f) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")

    @Test
    fun fitIsTheIdentity() {
        val p = Offset(123f, 45f)
        assertEquals(p, AnnotationViewTransform.Fit.toView(p))
        assertTrue(AnnotationViewTransform.Fit.isFit)
    }

    @Test
    fun screenToCanvasToScreenRoundTrips() = runTest {
        checkAll(Arb.int(1000..8000), Arb.int(-2000..2000), Arb.int(-2000..2000), Arb.int(0..1000), Arb.int(0..1000)) { scaleMilli, ox, oy, x, y ->
            val view = AnnotationViewTransform(scaleMilli / 1000f, ox.toFloat(), oy.toFloat())
            val local = Offset(x.toFloat(), y.toFloat())
            val back = view.toView(view.toCanvas(local))
            close(local.x, back.x, 0.01f)
            close(local.y, back.y, 0.01f)
        }
    }

    @Test
    fun zoomAboutAPointKeepsThatCanvasPointFixed() = runTest {
        checkAll(Arb.int(1100..7000), Arb.int(0..1000), Arb.int(0..1000), Arb.int(0..1000), Arb.int(0..1000)) { targetMilli, ax, ay, ox, oy ->
            val target = targetMilli / 1000f
            val start = AnnotationViewTransform(2f, -ox.toFloat(), -oy.toFloat())
            val anchor = Offset(ax.toFloat(), ay.toFloat())
            val pinned = start.toCanvas(anchor)
            val zoomed = start.zoomedTo(target, anchor, roomy)
            val after = zoomed.toView(pinned)
            close(anchor.x, after.x, 0.2f)
            close(anchor.y, after.y, 0.2f)
        }
    }

    @Test
    fun scaleIsClampedBetweenFitAndMax() {
        val tooFar = AnnotationViewTransform.Fit.zoomedTo(100f, Offset(500f, 500f), roomy)
        assertEquals(MAX_VIEW_SCALE, tooFar.scale)
        val tooNear = tooFar.zoomedTo(0.01f, Offset(500f, 500f), roomy)
        assertTrue(tooNear.isFit)
        assertEquals(AnnotationViewTransform.Fit, tooNear)
    }

    @Test
    fun anyGestureSequenceKeepsTheImageOverlappingTheViewport() = runTest {
        val step = Arb.list(Arb.int(-3000..3000), 4..4)
        checkAll(Arb.list(step, 1..12)) { steps ->
            var view = AnnotationViewTransform.Fit
            for ((dx, dy, zoomPermille, ax) in steps) {
                val zoom = 0.5f + (zoomPermille + 3000) / 3000f
                view = view.transformed(Offset(ax.toFloat() % 1000f, 400f), Offset(dx.toFloat(), dy.toFloat()), zoom, letterboxed)
                assertImageOverlapsViewport(view, letterboxed)
            }
        }
    }

    private fun assertImageOverlapsViewport(view: AnnotationViewTransform, g: ViewGeometry) {
        val tl = view.toView(Offset(0f, 0f)) + Offset(g.canvasLeft, g.canvasTop)
        val br = view.toView(Offset(g.canvasWidth, g.canvasHeight)) + Offset(g.canvasLeft, g.canvasTop)
        assertTrue(br.x > 0f && tl.x < g.viewportWidth, "x range $tl..$br left the ${g.viewportWidth} viewport")
        assertTrue(br.y > 0f && tl.y < g.viewportHeight, "y range $tl..$br left the ${g.viewportHeight} viewport")
    }

    @Test
    fun panIsClampedSoTheImageCannotBeLost() {
        val zoomed = AnnotationViewTransform.Fit.zoomedTo(4f, Offset(500f, 500f), letterboxed)
        val flungAway = zoomed.panned(Offset(-1_000_000f, 1_000_000f), letterboxed)
        assertImageOverlapsViewport(flungAway, letterboxed)
        // The right edge of the image stops at the viewport's right edge (no slack in this geometry).
        close(1000f, flungAway.toView(Offset(1000f, 0f)).x)
    }

    @Test
    fun zoomedLetterboxAxisStaysCentred() {
        val zoomed = AnnotationViewTransform.Fit.zoomedTo(1.5f, Offset(100f, 100f), letterboxed)
        // 500 * 1.5 = 750 < 1000 viewport height, so the image stays vertically centred.
        val top = zoomed.toView(Offset(0f, 0f)).y + letterboxed.canvasTop
        close(125f, top)
    }

    @Test
    fun fitResetsOffsetEvenIfAskedToStayPanned() {
        val skewed = AnnotationViewTransform(1f, 40f, 40f).clamped(letterboxed)
        assertEquals(AnnotationViewTransform.Fit, skewed)
    }

    @Test
    fun doubleTapTogglesBetweenFitAndFourX() {
        val zoomed = AnnotationViewTransform.Fit.toggledAt(Offset(500f, 500f), letterboxed)
        assertEquals(DOUBLE_TAP_SCALE, zoomed.scale)
        assertEquals(AnnotationViewTransform.Fit, zoomed.toggledAt(Offset(10f, 10f), letterboxed))
    }

    @Test
    fun zoomButtonsStepAboutTheViewportCentreAndSaturate() {
        var view = AnnotationViewTransform.Fit
        repeat(20) { view = view.stepped(ZOOM_STEP_FACTOR, roomy) }
        assertEquals(MAX_VIEW_SCALE, view.scale)
        val centreBefore = view.toCanvas(roomy.viewportCenter)
        val out = view.stepped(1f / ZOOM_STEP_FACTOR, roomy)
        val centreAfter = out.toCanvas(roomy.viewportCenter)
        close(centreBefore.x, centreAfter.x, 0.5f)
        repeat(30) { view = view.stepped(1f / ZOOM_STEP_FACTOR, roomy) }
        assertTrue(view.isFit)
    }

    @Test
    fun revealingPansANearbyPointIntoView() {
        val zoomed = AnnotationViewTransform.Fit.zoomedTo(4f, Offset(0f, 250f), letterboxed)
        // Canvas point at the far right is off screen; revealing brings it inside the margin.
        val target = Offset(1000f, 250f)
        assertFalse(zoomed.toView(target).x in 0f..1000f)
        val revealed = zoomed.revealing(target, 24f, letterboxed)
        assertTrue(revealed.toView(target).x in 0f..1000f)
        assertEquals(zoomed, zoomed.revealing(zoomed.toCanvas(Offset(500f, 500f)), 24f, letterboxed))
    }

    @Test
    fun wheelZoomsInOnScrollUpAndOutOnScrollDown() {
        assertTrue(wheelZoomFactor(-1f) > 1f)
        assertTrue(wheelZoomFactor(1f) < 1f)
        close(1f, wheelZoomFactor(1f) * wheelZoomFactor(-1f), 1e-5f)
        assertEquals(1f, wheelZoomFactor(0f))
    }

    @Test
    fun loupeMagnificationGrowsWithViewZoomButIsBounded() {
        assertEquals(3f, loupeZoomFactor(1f))
        assertEquals(8f, loupeZoomFactor(4f))
        assertEquals(16f, loupeZoomFactor(8f))
    }

    @Test
    fun approximateEqualityToleratesRoundingOnly() {
        val a = AnnotationViewTransform(2f, 10f, 10f)
        assertTrue(a.approximatelyEquals(a.copy(offsetX = 10.0004f)))
        assertFalse(a.approximatelyEquals(a.copy(scale = 2.1f)))
    }
}
