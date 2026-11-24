// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.annotation.AnnotationCanvas
import dev.stapler.stelekit.sketch.v2.annotation.AnnotationRect
import dev.stapler.stelekit.sketch.v2.annotation.DimensionAnnotationEngine
import dev.stapler.stelekit.sketch.v2.annotation.DimensionExport
import dev.stapler.stelekit.sketch.v2.annotation.DimensionExportOptions
import dev.stapler.stelekit.sketch.v2.annotation.RoomArea
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.WallGeometry
import dev.stapler.stelekit.sketch.v2.scene.WallJustification
import dev.stapler.stelekit.units.LengthFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A wall's lengths and a room's area go through the same annotation engine as every other dimension. */
class WallAnnotationTest {
    private fun u(d: Double) = SheetFixtures.u(d)

    private fun pt(x: Double, y: Double) = SheetFixtures.pt(x, y)

    // A 12 ft by 10 ft room (192 units per foot), drawn on the centre line of 4 1/2 inch walls.
    private val width = 2304.0
    private val height = 1920.0
    private val room = WallElement(
        ElementBase(ElementId("w1"), ordinal = 1),
        listOf(pt(0.0, 0.0), pt(width, 0.0), pt(width, height), pt(0.0, height)), u(72.0), WallJustification.Centre, closed = true,
    )

    private val dpPerUnit = 0.1

    private val canvas = AnnotationCanvas({ x, y -> (x * dpPerUnit + 140.0) to (y * dpPerUnit + 100.0) }, dpPerUnit, 520.0, 420.0)

    private fun layout(vararg elements: SceneElement, format: LengthFormat = LengthFormat.FEET_INCHES) =
        DimensionAnnotationEngine.layout(elements.toList(), elements.toList(), canvas, emptyList(), 1.0, 0.0, format)

    @Test
    fun wall_should_GetOneLengthPerSegment_OutsideTheRoom() {
        val placed = layout(room).filter { it.tag != DimensionAnnotationEngine.AREA_TAG }
        assertEquals(setOf("s0", "s1", "s2", "s3"), placed.map { it.tag }.toSet())
        val roomBox = AnnotationRect(140.0, 100.0, 140.0 + width * dpPerUnit, 100.0 + height * dpPerUnit)
        for (p in placed) assertTrue(!roomBox.overlaps(p.textRect), "${p.tag} sits inside the room: ${p.textRect}")
    }

    @Test
    fun wallLengths_should_BeTheDrawnLineLengthsInTheActiveUnit() {
        val feet = layout(room).filter { it.tag.startsWith("s") }.associate { it.tag to it.text }
        assertEquals("12' 0\"", feet["s0"])
        assertEquals("10' 0\"", feet["s1"])
        val metric = layout(room, format = LengthFormat.METRIC_MM).filter { it.tag.startsWith("s") }.associate { it.tag to it.text }
        assertEquals("3657.6 mm", metric["s0"])
    }

    @Test
    fun closedRoom_should_GetAnAreaLabel_InSquareFeetOrSquareMetres() {
        val feet = assertNotNull(layout(room).singleOrNull { it.tag == DimensionAnnotationEngine.AREA_TAG })
        assertEquals("111.9 sq ft", feet.text)
        val metric = assertNotNull(layout(room, format = LengthFormat.METRIC_CM_M).singleOrNull { it.tag == DimensionAnnotationEngine.AREA_TAG })
        assertEquals("10.4 m²", metric.text)
        val centre = AnnotationRect(140.0, 100.0, 140.0 + width * dpPerUnit, 100.0 + height * dpPerUnit)
        assertTrue(centre.contains(feet.textRect), "the label is inside the room")
        assertEquals(feet.textRect.centreX, centre.centreX, 1e-6)
        assertEquals(feet.line.a, feet.line.b, "an area label has no dimension line")
    }

    @Test
    fun areaLabel_should_BeDropped_When_TheRoomIsTooSmallOnScreenForItsText() {
        val tiny = AnnotationCanvas({ x, y -> (x * 0.005 + 140.0) to (y * 0.005 + 100.0) }, 0.005, 520.0, 420.0)
        val placed = DimensionAnnotationEngine.layout(listOf(room), listOf(room), tiny, emptyList(), 1.0, 0.0)
        assertNull(placed.firstOrNull { it.tag == DimensionAnnotationEngine.AREA_TAG })
    }

    @Test
    fun openWall_should_GetLengthsButNoArea() {
        val open = room.copy(closed = false)
        val placed = layout(open)
        assertTrue(placed.none { it.tag == DimensionAnnotationEngine.AREA_TAG })
        assertEquals(setOf("s0", "s1", "s2"), placed.map { it.tag }.toSet())
    }

    @Test
    fun roomArea_should_ConvertSquareUnitsExactly() {
        assertEquals("1.0 sq ft", RoomArea.text(192.0 * 192.0, LengthFormat.FEET_INCHES))
        assertEquals("1.0 m²", RoomArea.text((1000.0 / 1.5875) * (1000.0 / 1.5875), LengthFormat.METRIC_MM))
        assertEquals("0.0 sq ft", RoomArea.text(0.0, LengthFormat.INCHES_MIXED))
    }

    @Test
    fun export_should_CarryWallLengthsAndTheRoomArea_WithUniqueElementIds() {
        val scene = SketchSceneV2.empty().add(room).getOrNull() ?: error("add")
        val dims = DimensionExport.layout(scene, DimensionExportOptions())
        assertTrue(dims.any { it.tag == DimensionAnnotationEngine.AREA_TAG }, "area label exported")
        assertTrue(dims.count { it.tag.startsWith("s") } >= 2)
        val withElements = DimensionExport.withElements(scene, dims, "#1e1e1e")
        val ids = withElements.elementsInOrder.map { it.id.value }
        assertEquals(ids.size, ids.toSet().size, "every exported annotation element has its own id")
        assertTrue(ids.any { it.endsWith(".area.t") }, ids.toString())
        val ops = DimensionExport.drawOps(dims, "#1e1e1e")
        assertTrue(ops.isNotEmpty())
        assertNotNull(WallGeometry.interiorArea(room))
    }
}
