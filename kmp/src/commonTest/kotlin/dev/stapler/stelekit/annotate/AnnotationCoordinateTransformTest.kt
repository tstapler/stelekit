package dev.stapler.stelekit.annotate

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.stapler.stelekit.model.NormalizedPoint
import dev.stapler.stelekit.ui.annotate.toNormalized
import dev.stapler.stelekit.ui.annotate.toScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AnnotationCoordinateTransformTest {

    // ── toNormalized ──────────────────────────────────────────────────────────

    @Test
    fun toNormalized_returnsNull_whenCanvasWidthIsZero() {
        assertNull(Offset(100f, 100f).toNormalized(IntSize(0, 600)))
    }

    @Test
    fun toNormalized_returnsNull_whenCanvasHeightIsZero() {
        assertNull(Offset(100f, 100f).toNormalized(IntSize(800, 0)))
    }

    @Test
    fun toNormalized_returnsNull_whenCanvasSizeIsZero() {
        assertNull(Offset(100f, 100f).toNormalized(IntSize.Zero))
    }

    @Test
    fun toNormalized_mapsCenter() {
        val size = IntSize(800, 600)
        val result = Offset(400f, 300f).toNormalized(size)
        assertNotNull(result)
        assertEquals(0.5, result.x, 0.001)
        assertEquals(0.5, result.y, 0.001)
    }

    @Test
    fun toNormalized_mapsTopLeftCorner() {
        val size = IntSize(800, 600)
        val result = Offset(0f, 0f).toNormalized(size)
        assertNotNull(result)
        assertEquals(0.0, result.x, 0.001)
        assertEquals(0.0, result.y, 0.001)
    }

    @Test
    fun toNormalized_mapsBottomRightCorner() {
        val size = IntSize(800, 600)
        val result = Offset(800f, 600f).toNormalized(size)
        assertNotNull(result)
        assertEquals(1.0, result.x, 0.001)
        assertEquals(1.0, result.y, 0.001)
    }

    @Test
    fun toNormalized_clampsNegativeOffset() {
        val size = IntSize(800, 600)
        val result = Offset(-50f, -100f).toNormalized(size)
        assertNotNull(result)
        assertEquals(0.0, result.x, 0.001)
        assertEquals(0.0, result.y, 0.001)
    }

    @Test
    fun toNormalized_clampsOffsetBeyondCanvasBounds() {
        val size = IntSize(800, 600)
        val result = Offset(900f, 700f).toNormalized(size)
        assertNotNull(result)
        assertEquals(1.0, result.x, 0.001)
        assertEquals(1.0, result.y, 0.001)
    }

    // ── toScreen ──────────────────────────────────────────────────────────────

    @Test
    fun toScreen_mapsZeroToOrigin() {
        val size = IntSize(800, 600)
        val result = NormalizedPoint(0.0, 0.0).toScreen(size)
        assertEquals(0f, result.x, 0.5f)
        assertEquals(0f, result.y, 0.5f)
    }

    @Test
    fun toScreen_mapsOneToBottomRight() {
        val size = IntSize(800, 600)
        val result = NormalizedPoint(1.0, 1.0).toScreen(size)
        assertEquals(800f, result.x, 0.5f)
        assertEquals(600f, result.y, 0.5f)
    }

    @Test
    fun toScreen_mapsCenter() {
        val size = IntSize(1000, 800)
        val result = NormalizedPoint(0.5, 0.5).toScreen(size)
        assertEquals(500f, result.x, 0.5f)
        assertEquals(400f, result.y, 0.5f)
    }

    // ── round-trip ────────────────────────────────────────────────────────────

    @Test
    fun toScreen_isInverseOfToNormalized() {
        val size = IntSize(1000, 800)
        val original = Offset(320f, 240f)
        val recovered = original.toNormalized(size)!!.toScreen(size)
        assertEquals(original.x, recovered.x, 0.5f)
        assertEquals(original.y, recovered.y, 0.5f)
    }

    @Test
    fun toScreen_isInverseOfToNormalized_nearEdge() {
        val size = IntSize(500, 400)
        val original = Offset(499f, 399f)
        val recovered = original.toNormalized(size)!!.toScreen(size)
        assertEquals(original.x, recovered.x, 1f)
        assertEquals(original.y, recovered.y, 1f)
    }
}
