package dev.stapler.stelekit.sketch

import dev.stapler.stelekit.units.Inches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SketchPreviewFitTest {
    private fun pt(x: Long, y: Long) = SketchPoint(Inches(x * 16), Inches(y * 16))
    private fun rect(id: String, x1: Long, y1: Long, x2: Long, y2: Long) = SketchPrimitive.Rect(id, pt(x1, y1), pt(x2, y2))
    private fun sceneOf(vararg p: SketchPrimitive) = p.fold(SketchScene.EMPTY) { s, x -> s.add(x).getOrNull()!! }

    @Test fun emptySceneHasNoBounds() = assertNull(sceneBounds(SketchScene.EMPTY))

    @Test fun boundsUnionAllPrimitives() {
        val b = sceneBounds(sceneOf(rect("a", 0, 0, 10, 5), SketchPrimitive.Line("b", pt(-3, 2), pt(4, 20))))!!
        assertEquals(Aabb(-48, 0, 160, 320), b)
    }

    @Test fun fitCentresAndKeepsEverythingInsidePadding() {
        val bounds = sceneBounds(sceneOf(rect("a", 0, 0, 10, 5)))!!
        val fit = fitPreview(bounds, 400f, 300f, 12f)!!
        val left = fit.originX
        val right = fit.originX + 10 * fit.scale
        val top = fit.originY
        val bottom = fit.originY + 5 * fit.scale
        assertTrue(left >= 12f - 0.01f && right <= 388f + 0.01f && top >= 12f - 0.01f && bottom <= 288f + 0.01f)
        assertEquals(400f - right, left, 0.01f)
        assertEquals(300f - bottom, top, 0.01f)
    }

    @Test fun fitIsLimitedByTheTighterAxis() {
        val bounds = sceneBounds(sceneOf(rect("a", 0, 0, 10, 10)))!!
        assertEquals((200f - 20f) / 10f, fitPreview(bounds, 400f, 200f, 10f)!!.scale, 0.001f)
    }

    @Test fun degenerateLineStillFits() {
        val bounds = sceneBounds(sceneOf(SketchPrimitive.Line("l", pt(0, 0), pt(8, 0))))!!
        val fit = fitPreview(bounds, 300f, 100f, 10f)!!
        assertTrue(fit.scale.isFinite() && fit.scale > 0f)
    }

    @Test fun noRoomForPaddingYieldsNull() {
        val bounds = sceneBounds(sceneOf(rect("a", 0, 0, 1, 1)))!!
        assertNull(fitPreview(bounds, 20f, 100f, 10f))
        assertNotNull(fitPreview(bounds, 21f, 100f, 10f))
    }

    @Test fun aspectRatioFollowsBoundsAndIsClamped() {
        assertEquals(2f, previewAspectRatio(sceneBounds(sceneOf(rect("a", 0, 0, 10, 5)))), 0.001f)
        assertEquals(4f, previewAspectRatio(sceneBounds(sceneOf(SketchPrimitive.Line("l", pt(0, 0), pt(100, 0))))), 0.001f)
        assertEquals(0.4f, previewAspectRatio(sceneBounds(sceneOf(SketchPrimitive.Line("l", pt(0, 0), pt(0, 100))))), 0.001f)
        assertEquals(2f, previewAspectRatio(null), 0.001f)
    }
}
