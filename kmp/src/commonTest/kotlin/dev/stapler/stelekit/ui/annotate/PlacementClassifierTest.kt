package dev.stapler.stelekit.ui.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlacementClassifierTest {
    private val coord = Arb.float(-2000f, 2000f)
    private val threshold = 100f

    @Test
    fun classifyPlacement_should_ReturnDragMeasure_When_ReleaseBeyond48dpAtFitOrCloser_ElseAimPlace() = runTest {
        checkAll(
            coord, coord, coord, coord, Arb.enum<AnnotationTool>(), Arb.int(0..3), Arb.float(0.05f, 4f),
        ) { px, py, rx, ry, tool, pending, scale ->
            val press = Offset(px, py)
            val release = Offset(rx, ry)
            val result = classifyPlacement(press, release, threshold, pending, tool, scale)
            val far = hypot(rx - px, ry - py) > threshold
            val expectDrag = tool == AnnotationTool.DISTANCE && pending == 0 && isFitOrGreater(scale) && far
            if (expectDrag) {
                assertEquals(PlacementGesture.DragMeasure(press, release), result)
            } else {
                assertEquals(PlacementGesture.AimPlace(release), result)
            }
            val swapped = classifyPlacement(release, press, threshold, pending, tool, scale)
            assertEquals(result is PlacementGesture.DragMeasure, swapped is PlacementGesture.DragMeasure)
        }
    }

    @Test
    fun thresholdBoundary_should_BeExact() {
        val press = Offset(0f, 0f)
        assertIs<PlacementGesture.AimPlace>(
            classifyPlacement(press, Offset(threshold, 0f), threshold, 0, AnnotationTool.DISTANCE, 1f),
        )
        assertIs<PlacementGesture.DragMeasure>(
            classifyPlacement(press, Offset(threshold + 0.01f, 0f), threshold, 0, AnnotationTool.DISTANCE, 1f),
        )
    }

    @Test
    fun classifyPlacement_should_ReturnAimPlace_When_ViewScaleBelowFit_AndUseIsFitOrGreaterHelperNotRawFloat() {
        val far = Offset(500f, 0f)
        fun kind(scale: Float, fit: Float) =
            classifyPlacement(Offset.Zero, far, threshold, 0, AnnotationTool.DISTANCE, scale, fit)
        assertIs<PlacementGesture.AimPlace>(kind(0.5f, 1f))
        assertIs<PlacementGesture.DragMeasure>(kind(1f, 1f))
        assertIs<PlacementGesture.DragMeasure>(kind(1f - 1e-6f, 1f))
        assertIs<PlacementGesture.AimPlace>(kind(0.99f, 1f))
        assertIs<PlacementGesture.DragMeasure>(kind(0.3f, 0.25f))
        assertTrue(isFitOrGreater(0.9999999f, 1f))
        assertFalse(isFitOrGreater(0.9f, 1f))
    }

    @Test
    fun isFitOrGreater_should_AgreeWithViewTransformIsFit_AtUnsnappedScales() {
        listOf(0.9995f, 1.0005f, 0.9991f, 0.999f, 1f).forEach { s ->
            val view = AnnotationViewTransform(scale = s)
            assertTrue(!view.isFit || isFitOrGreater(s), "isFit at $s must imply fit-or-greater")
        }
        assertTrue(isFitOrGreater(0.9995f))
        assertIs<PlacementGesture.DragMeasure>(
            classifyPlacement(Offset.Zero, Offset(500f, 0f), 100f, 0, AnnotationTool.DISTANCE, 0.9995f),
        )
    }

    @Test
    fun identicalPoints_should_BeBelowTwoImagePixels() {
        assertTrue(arePointsIdentical(Offset(10f, 10f), Offset(11f, 10f)))
        assertFalse(arePointsIdentical(Offset(10f, 10f), Offset(12f, 10f)))
    }

    @Test
    fun offCanvasRelease_should_RespectInset_AndZeroInset() {
        val canvas = Size(1000f, 800f)
        assertFalse(isOffCanvasRelease(Offset(500f, 400f), canvas))
        assertTrue(isOffCanvasRelease(Offset(-1f, 400f), canvas))
        assertTrue(isOffCanvasRelease(Offset(1001f, 400f), canvas))
        assertFalse(isOffCanvasRelease(Offset(0f, 0f), canvas, insetPx = 0f))
        assertTrue(isOffCanvasRelease(Offset(10f, 400f), canvas, insetPx = 24f))
        assertTrue(isOffCanvasRelease(Offset(990f, 400f), canvas, insetPx = 24f))
    }

    @Test
    fun constants_should_MatchAdr007() {
        assertEquals(48, DRAG_COMMIT_DP)
    }
}
