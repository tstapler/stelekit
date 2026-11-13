// jvmTest-reason: pixels
package dev.stapler.stelekit.ui.sketch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.stapler.stelekit.sketch.SketchPoint
import dev.stapler.stelekit.sketch.SketchPrimitive
import dev.stapler.stelekit.sketch.SnapKind
import dev.stapler.stelekit.units.Inches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Draws into an off-screen bitmap (no display needed); android unit tests have no real Canvas, hence jvmTest. */
class SketchCanvasDrawTest {
    private fun pt(x: Long, y: Long) = SketchPoint(Inches(x), Inches(y))

    private fun drawInto(block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
        val bitmap = ImageBitmap(200, 200)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(200f, 200f), block)
    }

    @Test
    fun snapFeedbackLabelsTheSnapAndTheReadout() {
        val frame = DrawFrame(10f, 10f, 96f)
        val labels = mutableListOf<String>()
        val locked = DragPreview(SketchPrimitive.Line("p", pt(0, 0), pt(32, 0)), pt(32, 0), SnapKind.AXIS_LOCK, pt(0, 0))
        val none = DragPreview(SketchPrimitive.Rect("p", pt(0, 0), pt(16, 16)), pt(16, 16), SnapKind.NONE, null)
        drawInto {
            drawSnapFeedback(locked, frame, Color.Blue, Color.Black, 1f) { t, _: Offset, _, _ -> labels += t }
            drawSnapFeedback(none, frame, Color.Blue, Color.Black, 1f) { t, _: Offset, _, _ -> labels += t }
            drawPreviewShape(locked.shape, Color.Blue, 0.05f)
            drawPreviewShape(none.shape, Color.Blue, 0.05f)
            drawHandle(pt(16, 16), frame, Color.Blue, 1f)
        }
        assertEquals(3, labels.size)
        assertEquals("Straight", labels[0])
        assertTrue(labels[2].contains(" x "), labels[2])
    }
}
