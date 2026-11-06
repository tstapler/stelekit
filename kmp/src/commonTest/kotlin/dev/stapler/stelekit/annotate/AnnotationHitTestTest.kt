package dev.stapler.stelekit.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.ui.annotate.AnnotationHit
import dev.stapler.stelekit.ui.annotate.hitTestAnnotations
import dev.stapler.stelekit.ui.annotate.translateClamped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnnotationHitTestTest {
    private val size = IntSize(1000, 1000)
    private val uuid = "00000000-0000-0000-0000-000000000001"
    private val line = MeasurementAnnotation(
        uuid = uuid,
        imageUuid = "00000000-0000-0000-0000-000000000002",
        annotationType = AnnotationType.DISTANCE,
        normalizedPoints = listOf(NormalizedPoint(0.2, 0.5), NormalizedPoint(0.8, 0.5)),
    )

    private fun hit(x: Float, y: Float) = hitTestAnnotations(listOf(line), Offset(x, y), size, 40f, 30f)

    @Test fun nearEndpoint_hitsVertex() = assertEquals(AnnotationHit.Vertex(uuid, 0), hit(210f, 520f))

    @Test fun nearMiddleOfLine_hitsBody() = assertEquals(AnnotationHit.Body(uuid), hit(500f, 520f))

    @Test fun farFromLine_hitsNothing() = assertNull(hit(500f, 700f))

    @Test fun translate_clampsToUnitSquare() {
        val moved = translateClamped(line.normalizedPoints, 0.5, -0.9)
        assertEquals(1.0, moved.maxOf { it.x }, 1e-9)
        assertEquals(0.0, moved.minOf { it.y }, 1e-9)
    }
}
