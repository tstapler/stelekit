package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.ui.placement.PxOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnnotationHitTestUnderTransformTest {
    private val size = IntSize(1000, 1000)
    private val uuid = "00000000-0000-0000-0000-000000000001"
    private val line = MeasurementAnnotation(
        uuid = uuid,
        imageUuid = "00000000-0000-0000-0000-000000000002",
        annotationType = AnnotationType.DISTANCE,
        normalizedPoints = listOf(NormalizedPoint(0.5, 0.5), NormalizedPoint(0.9, 0.5)),
    )

    /** 4x zoom about the canvas centre: canvas (500,500) stays at local (500,500). */
    private val zoomed = AnnotationViewTransform(4f, -1500f, -1500f)

    private fun hit(position: Offset, view: AnnotationViewTransform) =
        hitTestAnnotations(listOf(line), position, size, 28f, 20f, view)

    @Test
    fun grabRadiusIsInScreenPixelsAtAnyZoom() {
        // 20 screen px from the vertex: inside the 28 px radius at 1x and at 4x.
        assertEquals(AnnotationHit.Vertex(uuid, 0), hit(Offset(520f, 500f), AnnotationViewTransform.Fit))
        assertEquals(AnnotationHit.Vertex(uuid, 0), hit(Offset(520f, 500f), zoomed))
        // 80 screen px along the line is only 20 canvas px at 4x. The radius is screen px, so the vertex is out
        // of reach and the touch grabs the line body instead.
        assertEquals(AnnotationHit.Body(uuid), hit(Offset(580f, 500f), zoomed))
        assertNull(hit(Offset(540f, 560f), zoomed))
    }

    @Test
    fun anAnnotationOffScreenWhenZoomedIsNotHit() {
        // The second vertex is canvas (900,500), which lives at local x = 900*4-1500 = 2100 when zoomed.
        assertEquals(AnnotationHit.Vertex(uuid, 1), hit(Offset(2100f, 500f), zoomed))
        // The same local spot at 1x is empty canvas.
        assertNull(hit(Offset(2100f, 500f), AnnotationViewTransform.Fit))
    }

    @Test
    fun lineBodyIsHitAlongItsScreenPosition() {
        // Midpoint of the segment (canvas x=700) is local x = 1300 when zoomed.
        assertEquals(AnnotationHit.Body(uuid), hit(Offset(1300f, 510f), zoomed))
    }

    @Test
    fun aTapAtFourXMapsToTheRightNormalizedPosition() {
        val tap = Offset(1300f, 700f)
        val canvas = zoomed.toCanvas(tap)
        assertEquals(NormalizedPoint(0.7, 0.55).x, (canvas.x / size.width).toDouble(), 1e-6)
        assertEquals(0.55, (canvas.y / size.height).toDouble(), 1e-6)
    }

    @Test
    fun nudgeStepsAreScreenPixelsSoTheyGetFinerWhenZoomed() {
        val fit = AnnotationPlacementSpace(1000, 1000)
        val deep = AnnotationPlacementSpace(1000, 1000, zoom = 8f)
        val start = NormalizedPoint(0.5, 0.5)
        val oneAtFit = fit.shift(start, PxOffset(1, 0)).x - start.x
        val oneAtDeep = deep.shift(start, PxOffset(1, 0)).x - start.x
        assertEquals(1.0 / 1000, oneAtFit, 1e-9)
        assertEquals(1.0 / 8000, oneAtDeep, 1e-9)
    }

    @Test
    fun percentStepIsAPercentOfTheVisibleWidth() {
        val deep = AnnotationPlacementSpace(1000, 1000, zoom = 8f, visibleWidthPx = 1080)
        // Visible width is the viewport (1080), not the 8000 px zoomed canvas.
        assertEquals(1080, deep.canvasWidthPx)
        val fit = AnnotationPlacementSpace(1000, 1000, zoom = 1f, visibleWidthPx = 1080)
        assertEquals(1000, fit.canvasWidthPx)
    }
}
