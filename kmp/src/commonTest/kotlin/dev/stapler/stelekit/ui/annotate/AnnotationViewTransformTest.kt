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
        checkAll(Arb.int(250..8000), Arb.int(-2000..2000), Arb.int(-2000..2000), Arb.int(0..1000), Arb.int(0..1000)) { scaleMilli, ox, oy, x, y ->
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
        assertEquals(MIN_VIEW_SCALE, tooNear.scale)
        assertFalse(tooNear.isFit)
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
        // The far edge stops slack (25% of the viewport) short of the viewport's right edge.
        close(750f, flungAway.toView(Offset(1000f, 0f)).x)
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
        assertEquals(MIN_VIEW_SCALE, view.scale)
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
    fun defaultTransform_should_BeFit_And_isFitSymmetric_And_Floor025Stops() = runTest {
        assertEquals(FIT_SCALE, AnnotationViewTransform().scale)
        assertEquals(0.25f, MIN_VIEW_SCALE)
        assertTrue(AnnotationViewTransform().isFit)
        assertEquals(AnnotationViewTransform.Fit, AnnotationViewTransform())
        assertTrue(AnnotationViewTransform(1.0005f).isFit)
        assertTrue(AnnotationViewTransform(0.9995f).isFit)
        for (s in listOf(0.5f, 0.25f)) {
            val v = AnnotationViewTransform(s, 7f, 7f)
            assertFalse(v.isFit)
            val c = v.clamped(roomy)
            assertEquals(s, c.scale)
            // centred: 1000 * s content in a 1000 viewport
            close((1000f - 1000f * s) / 2f, c.offsetX, 0.01f)
        }
        var view = AnnotationViewTransform.Fit
        repeat(60) { view = view.transformed(Offset(300f, 300f), Offset.Zero, 0.8f, roomy) }
        assertEquals(MIN_VIEW_SCALE, view.scale)
        checkAll(Arb.int(250..1000), Arb.int(0..1000), Arb.int(0..1000)) { sMilli, nx, ny ->
            val v = AnnotationViewTransform.Fit.zoomedTo(sMilli / 1000f, Offset(nx.toFloat(), ny.toFloat()), roomy)
            assertEquals(sMilli / 1000f, v.scale)
        }
    }

    @Test
    fun slowPinchOut_should_LeaveFit_When_200EventsOfFactor09995() {
        var view = AnnotationViewTransform.Fit
        repeat(200) { view = view.transformed(Offset(500f, 500f), Offset.Zero, 0.9995f, roomy) }
        close(0.905f, view.scale, 0.005f)
        assertFalse(view.isFit)
    }

    @Test
    fun clamp_should_CentreAxisWhenContentFits_AndKeepFarEdgeSlackShortOfViewportEdge_When_ZoomedIn() = runTest {
        val g = letterboxed
        checkAll(Arb.int(250..990), Arb.int(-100000..100000), Arb.int(-100000..100000)) { sMilli, dx, dy ->
            val below = AnnotationViewTransform(sMilli / 1000f).panned(Offset(dx.toFloat(), dy.toFloat()), g)
            val centred = AnnotationViewTransform(sMilli / 1000f).clamped(g)
            assertEquals(centred, below, "pan below Fit must be a no-op")
        }
        checkAll(Arb.int(1100..8000), Arb.int(-100000..100000), Arb.int(-100000..100000)) { sMilli, dx, dy ->
            val s = sMilli / 1000f
            val v = AnnotationViewTransform(s).panned(Offset(dx.toFloat(), dy.toFloat()), g)
            val left = v.offsetX + g.canvasLeft
            val slackX = 0.25f * g.viewportWidth
            assertTrue(left <= slackX + 0.01f && left + g.canvasWidth * s >= g.viewportWidth - slackX - 0.01f)
            if (dx < -50000) close(g.viewportWidth - g.canvasWidth * s - slackX, left, 0.5f)
        }
    }

    @Test
    fun pinchCentroidIsFixedWhenUnclamped_AndZoomIsAboutViewportCentreBelowFit() = runTest {
        checkAll(Arb.int(1000..7000), Arb.int(-50..50), Arb.int(1..1000), Arb.int(1..1000)) { sMilli, zPermille, ax, ay ->
            val start = AnnotationViewTransform(sMilli / 1000f, -300f, -300f)
            val zoom = 1f + zPermille / 1000f
            val c = Offset(ax.toFloat(), ay.toFloat())
            val pinned = start.toCanvas(c)
            val end = start.transformed(c, Offset.Zero, zoom, roomy)
            if (end.scale >= FIT_SCALE && end.scale != MAX_VIEW_SCALE) {
                close(c.x, end.toView(pinned).x, 0.2f)
                close(c.y, end.toView(pinned).y, 0.2f)
            }
        }
        val out = AnnotationViewTransform.Fit.transformed(Offset(10f, 10f), Offset.Zero, 0.5f, roomy)
        val centre = roomy.viewportCenter
        close(centre.x, out.toView(out.toCanvas(centre)).x)
        close(500f - 250f, out.toView(Offset.Zero).x, 0.1f)
    }

    @Test
    fun everyNormalizedPointIsReachableInsideTheViewportAtFloorScale() = runTest {
        val floor = AnnotationViewTransform.Fit.zoomedTo(MIN_VIEW_SCALE, Offset(10f, 10f), letterboxed)
        assertEquals(MIN_VIEW_SCALE, floor.scale)
        checkAll(Arb.int(0..1000), Arb.int(0..1000)) { nx, ny ->
            val p = floor.toView(Offset(nx / 1000f * letterboxed.canvasWidth, ny / 1000f * letterboxed.canvasHeight))
            assertTrue(p.x + letterboxed.canvasLeft in 0f..letterboxed.viewportWidth)
            assertTrue(p.y + letterboxed.canvasTop in 0f..letterboxed.viewportHeight)
        }
    }

    @Test
    fun toggledAtReturnsToFitFromAnyNonFitScale() {
        for (s in listOf(0.25f, 0.5f, 2f, 8f)) {
            assertEquals(AnnotationViewTransform.Fit, AnnotationViewTransform(s).toggledAt(Offset(1f, 1f), letterboxed))
        }
    }

    @Test
    fun approximateEqualityToleratesRoundingOnly() {
        val a = AnnotationViewTransform(2f, 10f, 10f)
        assertTrue(a.approximatelyEquals(a.copy(offsetX = 10.0004f)))
        assertFalse(a.approximatelyEquals(a.copy(scale = 2.1f)))
    }
}
