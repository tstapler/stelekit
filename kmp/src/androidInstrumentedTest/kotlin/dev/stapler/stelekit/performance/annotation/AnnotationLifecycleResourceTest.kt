// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.performance.annotation

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import arrow.core.getOrElse
import dev.stapler.stelekit.model.AnnotationType
import dev.stapler.stelekit.model.MeasurementAnnotation
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.performance.probe.PeakSampler
import dev.stapler.stelekit.performance.probe.ProbeReporter
import dev.stapler.stelekit.performance.probe.ProbeRun
import dev.stapler.stelekit.performance.probe.ResourceProbe
import dev.stapler.stelekit.ui.annotate.AnnotationExporter
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/**
 * Scenario (e): export bake of a 4096x3072 source. Measures the baked bitmap's real allocation and
 * the heap peak during bake and encode; the ~110-130 MB figure in architecture.md A7 is INFERRED,
 * this records the measured number (plan B.3d).
 */
@RunWith(AndroidJUnit4::class)
class AnnotationLifecycleResourceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun exportBake_4096x3072_recordsAllocationAndPeak() {
        val run = ProbeRun(context, "Export").begin()
        val probe = ResourceProbe(context)
        val baseline = probe.snapshot()
        val source = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val measurements = listOf(
            MeasurementAnnotation(
                uuid = "synthetic-m-0", imageUuid = "synthetic-img-0", annotationType = AnnotationType.DISTANCE,
                normalizedPoints = listOf(NormalizedPoint(0.1, 0.1), NormalizedPoint(0.9, 0.9)),
            ),
        )
        var peakBytes = 0L
        val baked: Bitmap
        PeakSampler().use { sampler ->
            baked = AnnotationExporter.bakeAnnotations(source.asImageBitmap(), measurements).asAndroidBitmap()
            val jpeg = AnnotationExporter.bakeAndEncode(source.asImageBitmap(), measurements, quality = JPEG_QUALITY)
            assertTrue(jpeg.getOrElse { ByteArray(0) }.isNotEmpty(), "encode failed: $jpeg")
            peakBytes = (sampler.peakJavaBytes - baseline.javaUsedBytes) + (sampler.peakNativeBytes - baseline.nativeAllocatedBytes)
        }
        val allocation = baked.allocationByteCount.toDouble()
        ProbeReporter.report(
            context,
            run.finish(mapOf("exportedBitmapBytes" to allocation, "bakePeakMb" to peakBytes / BYTES_PER_MB)),
        )
        ProbeReporter.report(context, run.processRecord())
        source.recycle()
        assertTrue(allocation >= WIDTH * HEIGHT, "baked bitmap smaller than the source: $allocation")
    }

    private companion object {
        const val WIDTH = 4096
        const val HEIGHT = 3072
        const val JPEG_QUALITY = 85
        const val BYTES_PER_MB = 1024.0 * 1024.0
    }
}
