// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.tools.filledRectEl
import dev.stapler.stelekit.units.LengthFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** E12: the outline's reading order, and the gesture-free "Add shape at..." form (pure, no UI). */
class SketchOutlineAndShapeAtTest {
    private fun sceneOf(vararg rects: RectElement): SketchSceneV2 = rects.fold(SketchSceneV2.empty()) { s, r -> s.add(r).getOrNull()!! }

    @Test
    fun outline_should_ReadTopToBottomThenLeftToRight_Not_InPaintOrder() {
        val scene = sceneOf(
            filledRectEl("c", 200, 100, 10, 10),
            filledRectEl("b", 0, 100, 10, 10),
            filledRectEl("a", 300, 0, 10, 10),
        )
        val out = SketchOutlineModel.build(scene, emptySet())
        assertEquals(listOf("a", "b", "c"), out.map { it.id.value })
        assertEquals(listOf(1, 2, 3), out.map { it.position })
        assertTrue(out.all { it.total == 3 })
    }

    @Test
    fun outline_should_KeepPaintOrder_When_BoundsTie_And_MarkSelection() {
        val scene = sceneOf(filledRectEl("first", 10, 10, 5, 5), filledRectEl("second", 10, 10, 5, 5))
        val out = SketchOutlineModel.build(scene, setOf(ElementId("second")))
        assertEquals(listOf("first", "second"), out.map { it.id.value })
        assertTrue(out[1].selected && !out[0].selected)
        assertTrue(out[1].spoken.startsWith("Item 2 of 2: ") && out[1].spoken.endsWith(", selected"))
    }

    @Test
    fun outline_should_BeEmpty_When_TheSheetIsEmpty() {
        assertTrue(SketchOutlineModel.build(SketchSceneV2.empty(), emptySet()).isEmpty())
    }

    private fun plan(form: ShapeAtForm, technical: Boolean = false) =
        ShapeAtModel.plan(form, technical, LengthFormat.FEET_INCHES, ElementId("new"), ElementStyle())

    @Test
    fun shapeAt_should_PlaceARectangle_At_TypedUnits_On_ADiagramSheet() {
        val result = plan(ShapeAtForm(ShapeAtKind.Rectangle, "-20", "0", "100", "40")).getOrNull()!!
        val rect = assertIs<RectElement>(result.command.element)
        assertEquals(listOf(-20.0, 0.0, 100.0, 40.0), listOf(rect.x.value, rect.y.value, rect.width.value, rect.height.value))
        assertEquals("Added Rectangle at -20, 0, 100 by 40", result.announcement)
    }

    @Test
    fun shapeAt_should_PutAnEllipseCentreAtTheBoxMiddle() {
        val e = assertIs<EllipseElement>(plan(ShapeAtForm(ShapeAtKind.Ellipse, "10", "20", "100", "40")).getOrNull()!!.command.element)
        assertEquals(listOf(60.0, 40.0, 50.0, 20.0), listOf(e.cx.value, e.cy.value, e.rx.value, e.ry.value))
    }

    @Test
    fun shapeAt_should_ReadFeetAndInches_On_ATechnicalSheet() {
        val rect = assertIs<RectElement>(plan(ShapeAtForm(ShapeAtKind.Rectangle, "1'", "0", "3' 7\"", "2'"), technical = true).getOrNull()!!.command.element)
        assertEquals(listOf(192.0, 0.0, 3 * 192.0 + 7 * 16.0, 2 * 192.0), listOf(rect.x.value, rect.y.value, rect.width.value, rect.height.value))
    }

    @Test
    fun shapeAt_should_NameTheFirstBadField_And_RefuseNonPositiveSizes() {
        assertEquals(ShapeAtField.Width, plan(ShapeAtForm(x = "0", y = "0", width = "0", height = "5")).leftOrNull()!!.field)
        assertEquals(ShapeAtField.Y, plan(ShapeAtForm(x = "0", y = "abc", width = "5", height = "5")).leftOrNull()!!.field)
        assertEquals(ShapeAtField.X, plan(ShapeAtForm(x = "", y = "0", width = "5", height = "5")).leftOrNull()!!.field)
        assertEquals(ShapeAtField.Height, plan(ShapeAtForm(x = "0", y = "0", width = "5", height = "-5")).leftOrNull()!!.field)
        assertTrue(plan(ShapeAtForm(x = "1e12", y = "0", width = "5", height = "5")).isLeft())
    }
}
