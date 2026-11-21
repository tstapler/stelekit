// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.W6Kit.circle
import dev.stapler.stelekit.sketch.v2.W6Kit.rect
import dev.stapler.stelekit.sketch.v2.W6Kit.run
import dev.stapler.stelekit.sketch.v2.W6Kit.step
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.ui.sketch.v2.ElementStats
import dev.stapler.stelekit.ui.sketch.v2.ElementStatsEdits
import dev.stapler.stelekit.ui.sketch.v2.StatField
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The size numbers are the element's own box, so editing a rotated element's height cannot widen or move it. */
class ElementStatsGeometryTest {
    private fun rotatedDoc(): SketchDocumentV2 {
        val tilted = (rect("r1", 10, 20) as RectElement).copy(angle = PI / 4)
        return run(SketchDocumentV2(), AddElements(listOf(tilted)))
    }

    @Test
    fun aRotatedRectangle_should_ReportItsOwnBoxNotItsRotatedBounds() {
        val r = rotatedDoc().scene[ElementId("r1")] as RectElement
        val stats = checkNotNull(ElementStats.of(r))

        assertEquals(r.width.value, stats.width)
        assertEquals(r.height.value, stats.height)
        assertEquals(r.x.value, stats.x)
        assertEquals(45.0, stats.angleDegrees, 1e-9)
    }

    @Test
    fun editingTheHeightOfARotatedRectangle_should_LeaveItsWidthAndCornerAlone() {
        val d = rotatedDoc()
        val before = d.scene[ElementId("r1")] as RectElement

        val edit = assertIs<ElementStatsEdits.CommitResult.Edit>(ElementStatsEdits.commit(before, StatField.Height, "60"))
        val after = step(d, edit.command).scene[ElementId("r1")] as RectElement

        assertEquals(60.0, after.height.value)
        assertEquals(before.width.value, after.width.value)
        assertEquals(before.x.value, after.x.value)
        assertEquals(before.y.value, after.y.value)
    }

    @Test
    fun anEllipse_should_ReportItsDiameters() {
        val d = run(SketchDocumentV2(), AddElements(listOf(circle("c", 50, 50))))
        val e = d.scene[ElementId("c")] as EllipseElement
        val stats = checkNotNull(ElementStats.of(e))

        assertEquals(2 * e.rx.value, stats.width)
        assertEquals(e.cx.value - e.rx.value, stats.x)
    }
}
