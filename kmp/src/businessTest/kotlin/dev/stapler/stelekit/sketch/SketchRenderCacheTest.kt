package dev.stapler.stelekit.sketch

import dev.stapler.stelekit.ui.sketch.SketchRenderCache
import dev.stapler.stelekit.units.Inches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SketchRenderCacheTest {
    private fun pt(x: Long, y: Long) = SketchPoint(Inches(x), Inches(y))

    private fun scene(vararg p: SketchPrimitive) =
        p.fold(SketchScene.EMPTY) { s, prim -> s.add(prim).fold({ error("$it") }, { it }) }

    @Test
    fun horizontalLineLabelIsItsLengthAndAnchoredAtTheMidpoint() {
        val shape = SketchRenderCache().shapesFor(scene(SketchPrimitive.Line("h", pt(16, 32), pt(16 + 12 * 16 + 3, 32)))).single()
        assertEquals("1' 3/16\"", shape.label)
        assertEquals((1f + (1f + 12f + 3f / 16f)) / 2, shape.labelX)
        assertEquals(2f, shape.labelY)
    }

    @Test
    fun verticalLineLabelUsesTheAbsoluteLengthRegardlessOfDirection() {
        val shape = SketchRenderCache().shapesFor(scene(SketchPrimitive.Line("v", pt(0, 48), pt(0, 0)))).single()
        assertEquals("3\"", shape.label)
    }

    @Test
    fun slantedLinesAndRectanglesCarryNoLabel() {
        val shapes = SketchRenderCache().shapesFor(
            scene(SketchPrimitive.Line("s", pt(0, 0), pt(16, 16)), SketchPrimitive.Rect("r", pt(0, 0), pt(32, 32))),
        )
        assertNull(shapes[0].label)
        assertNull(shapes[1].label)
        assertEquals(listOf("s", "r"), shapes.map { it.id })
    }

    @Test
    fun shapesAreRebuiltOnlyWhenTheSceneInstanceChanges() {
        val cache = SketchRenderCache()
        val s1 = scene(SketchPrimitive.Line("a", pt(0, 0), pt(16, 0)))
        assertSame(cache.shapesFor(s1), cache.shapesFor(s1))
        val s2 = s1.add(SketchPrimitive.Line("b", pt(0, 0), pt(0, 16))).fold({ error("$it") }, { it })
        assertEquals(2, cache.shapesFor(s2).size)
    }
}
