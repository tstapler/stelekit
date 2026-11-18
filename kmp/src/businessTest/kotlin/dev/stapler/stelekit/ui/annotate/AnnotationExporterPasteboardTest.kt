// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.NormalizedPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I-E2-05: the pasteboard and the zoom are view-only. The exporter takes only the source image and the
 * measurements, so its output cannot depend on the view transform, and it adds no border around the photo.
 */
class AnnotationExporterPasteboardTest {
    private val measurement = MeasurementAnnotation(
        uuid = "m1", imageUuid = "i1", annotationType = AnnotationType.DISTANCE,
        normalizedPoints = listOf(NormalizedPoint(0.0, 0.5), NormalizedPoint(1.0, 0.5)),
        valueDisplay = "3.20 m",
    )

    private fun pixels(bitmap: ImageBitmap) = bitmap.toPixelMap().let { map ->
        (0 until bitmap.height).flatMap { y -> (0 until bitmap.width).map { x -> map[x, y] } }
    }

    @Test
    fun export_should_BeIdenticalWithAndWithoutPasteboard() {
        val source = ImageBitmap(200, 150)
        val atFit = AnnotationExporter.bakeAnnotations(source, listOf(measurement))
        // The view layer at 25 percent (image floating on a pasteboard) is a different AnnotationViewTransform, never an exporter input.
        val view = AnnotationViewTransform.Fit.stepped(0.25f, ViewGeometry(1000f, 1000f, 0f, 0f, 1000f, 1000f))
        assertTrue(view.scale < FIT_SCALE)
        val whileZoomedOut = AnnotationExporter.bakeAnnotations(source, listOf(measurement))

        assertEquals(source.width, atFit.width)
        assertEquals(source.height, atFit.height)
        assertEquals(pixels(atFit), pixels(whileZoomedOut))
    }

    @Test
    fun export_should_DrawEndpointsAtTheImageEdges_WhenPointsAreClampedTo0And1() {
        val out = AnnotationExporter.bakeAnnotations(ImageBitmap(200, 150), listOf(measurement)).toPixelMap()
        val leftEdgeOpaque = (0 until 150).any { y -> out[0, y].alpha > 0f }
        val rightEdgeOpaque = (0 until 150).any { y -> out[199, y].alpha > 0f }
        assertTrue(leftEdgeOpaque && rightEdgeOpaque, "a 0/1 point is drawn on the image's outermost pixel column")
    }
}
