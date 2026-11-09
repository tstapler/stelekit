// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stapler.stelekit.model.Calibration
import dev.stapler.stelekit.model.CalibrationMethod
import dev.stapler.stelekit.model.ImageAnnotation
import dev.stapler.stelekit.model.MeasurementUnit
import dev.stapler.stelekit.platform.SteleKitContext
import dev.stapler.stelekit.repository.InMemoryMeasurementAnnotationRepository
import dev.stapler.stelekit.ui.annotate.AnnotationEditorScreen
import dev.stapler.stelekit.ui.annotate.AnnotationEditorViewModel
import dev.stapler.stelekit.ui.annotate.AnnotationTool
import dev.stapler.stelekit.ui.annotate.LOUPE_OVERLAY_TAG
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-device check of the loupe invariant: the loupe's centre shows the image point under the
 * finger, and that is the point that gets placed. The image is four colour quadrants meeting at
 * (0.5, 0.5); pressing a known offset from that corner means the quadrant boundaries must appear
 * at a known offset (touch delta x loupe zoom) from the loupe centre.
 *
 * Run: ./gradlew :kmp:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.stapler.stelekit.LoupeAlignmentE2ETest
 */
@RunWith(AndroidJUnit4::class)
class LoupeAlignmentE2ETest {
    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    // Landscape image in a different-aspect window, so any letterbox mismatch shows up.
    private val imgW = 1200
    private val imgH = 800
    private val zoom = 3

    private val topLeft = Triple(0, 200, 0)
    private val topRight = Triple(255, 0, 0)
    private val bottomLeft = Triple(0, 0, 255)

    @Before
    fun setUp() {
        SteleKitContext.init(context)
    }

    private fun writeImage(): File {
        val bmp = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        fun fill(l: Int, t: Int, r: Int, b: Int, rgb: Triple<Int, Int, Int>) =
            c.drawRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat(), Paint().apply { color = Color.rgb(rgb.first, rgb.second, rgb.third) })
        fill(0, 0, imgW / 2, imgH / 2, topLeft)
        fill(imgW / 2, 0, imgW, imgH / 2, topRight)
        fill(0, imgH / 2, imgW / 2, imgH, bottomLeft)
        fill(imgW / 2, imgH / 2, imgW, imgH, Triple(255, 255, 0))
        val f = File(context.cacheDir, "loupe-e2e-${System.nanoTime()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    private fun isNear(argb: Int, rgb: Triple<Int, Int, Int>, tol: Int = 60) =
        abs(((argb shr 16) and 0xFF) - rgb.first) < tol &&
            abs(((argb shr 8) and 0xFF) - rgb.second) < tol &&
            abs((argb and 0xFF) - rgb.third) < tol

    private class Loupe(val w: Int, val h: Int, val px: IntArray) {
        operator fun get(x: Int, y: Int) = px[y * w + x]
    }

    private fun captureLoupe(): Loupe {
        val img = rule.onNodeWithTag(LOUPE_OVERLAY_TAG).captureToImage()
        return Loupe(img.width, img.height, IntArray(img.width * img.height).also { img.readPixels(it) })
    }

    /** First offset from the loupe centre (scanning [range]) where [along] stops matching [before]. */
    private fun boundary(range: IntRange, before: Triple<Int, Int, Int>, sample: (Int) -> Int): Int? =
        range.firstOrNull { !isNear(sample(it), before) }

    @Test
    fun loupeBoundaries_matchTouchOffsetFromCorner() {
        val file = writeImage()
        val img = ImageAnnotation(
            uuid = "img-loupe-e2e", blockUuid = "blk-loupe-e2e", pageUuid = "page-loupe-e2e",
            graphPath = "/test", filePath = "file://${file.absolutePath}",
            calibration = Calibration(CalibrationMethod.MANUAL_REFERENCE, 100.0, 100),
            unit = MeasurementUnit.METERS,
        )
        val vm = AnnotationEditorViewModel(measurementRepository = InMemoryMeasurementAnnotationRepository())
            .also { it.initialize(img) }
        vm.selectTool(AnnotationTool.DISTANCE)
        rule.setContent { AnnotationEditorScreen(viewModel = vm, imageAnnotation = img) }

        val canvas = rule.onNodeWithContentDescription("Annotation canvas — tap to place points")
        rule.waitUntil(timeoutMillis = 20_000) {
            val s = canvas.fetchSemanticsNode().size
            s.height > 0 && abs(s.width.toFloat() / s.height - imgW.toFloat() / imgH) < 0.02f
        }
        val size = canvas.fetchSemanticsNode().size
        // Press 25px right of and 20px below the quadrant corner at the canvas centre.
        val dx = 25f
        val dy = 20f
        val pos = Offset(size.width * 0.5f + dx, size.height * 0.5f + dy)

        canvas.performTouchInput { down(Offset(size.width * 0.5f, size.height * 0.9f)); up() } // warm-up
        rule.waitForIdle()
        vm.cancelInProgress()

        canvas.performTouchInput { down(pos) }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithTag(LOUPE_OVERLAY_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        // The loupe image decodes asynchronously: wait until any quadrant colour appears.
        rule.waitUntil(timeoutMillis = 10_000) {
            val l = captureLoupe()
            l.px.any { isNear(it, topRight) } || l.px.any { isNear(it, topLeft) }
        }
        rule.waitForIdle()
        val l = captureLoupe()
        val cx = l.w / 2
        val cy = l.h / 2
        // Top half rows (above the boundary): horizontal green->red flip. Left columns: green->blue flip.
        val rowY = cy - 100
        val colX = cx - 100
        val xBoundary = boundary(-100..100, topLeft) { l[cx + it, rowY] }
        val yBoundary = boundary(-100..100, topLeft) { l[colX, cy + it] }
        val expX = -dx * zoom
        val expY = -dy * zoom

        canvas.performTouchInput { up() }
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 5_000) { vm.state.value.inProgressPoints.isNotEmpty() }
        val placed = vm.state.value.inProgressPoints.single()

        val glyph = fun(c: Int): Char = when {
            isNear(c, topLeft) -> 'G'; isNear(c, topRight) -> 'R'; isNear(c, bottomLeft) -> 'B'
            isNear(c, Triple(255, 255, 0)) -> 'Y'; isNear(c, Triple(255, 255, 255), 12) -> 'W'
            isNear(c, Triple(0, 0, 0), 40) -> 'd'; else -> '?'
        }
        val map = (0 until 13).joinToString("/") { gy ->
            (0 until 13).map { gx -> glyph(l[(gx * (l.w - 1)) / 12, (gy * (l.h - 1)) / 12]) }.joinToString("")
        }
        val report = "map(13x13 rows)=$map loupe=${l.w}x${l.h} canvas=$size press=$pos | x-boundary=$xBoundary expected=$expX | y-boundary=$yBoundary expected=$expY | " +
            "placed=(${placed.x},${placed.y}) expected=(${pos.x / size.width},${pos.y / size.height})"
        assertTrue(xBoundary != null && yBoundary != null, "no quadrant boundary found in loupe; $report")
        assertTrue(abs(xBoundary!! - expX) < 8 && abs(yBoundary!! - expY) < 8, "loupe misaligned; $report")
        assertEquals(pos.x.toDouble() / size.width, placed.x, 0.005, report)
        assertEquals(pos.y.toDouble() / size.height, placed.y, 0.005, report)
    }
}
