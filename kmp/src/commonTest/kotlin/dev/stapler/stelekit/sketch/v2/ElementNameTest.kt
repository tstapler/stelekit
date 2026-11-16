// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.W6Kit.circle
import dev.stapler.stelekit.sketch.v2.W6Kit.get
import dev.stapler.stelekit.sketch.v2.W6Kit.line
import dev.stapler.stelekit.sketch.v2.W6Kit.rect
import dev.stapler.stelekit.sketch.v2.W6Kit.run
import dev.stapler.stelekit.sketch.v2.W6Kit.step
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.DeleteElements
import dev.stapler.stelekit.sketch.v2.commands.DuplicateElements
import dev.stapler.stelekit.sketch.v2.commands.RenameElement
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.NameCounters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 3.5.7, Task 3.5.7a: stable, never-reused element names; counters live in the sheet. */
class ElementNameTest {
    private fun name(doc: SketchDocumentV2, id: String) = ElementNames.nameOf(doc.scene[ElementId(id)]!!)

    @Test
    fun names_should_NeverBeReused_When_TheFirstLineIsDeleted() {
        var doc = SketchDocumentV2()
        doc = run(doc, AddElement(line("a", 0, 0, 5, 5)), AddElement(rect("b", 0, 0)), AddElement(line("c", 0, 0, 9, 9)), AddElement(circle("d", 5, 5)))
        assertEquals(listOf("Line 1", "Rectangle 1", "Line 2", "Circle 1"), listOf("a", "b", "c", "d").map { name(doc, it) })
        doc = run(doc, DeleteElements(listOf(ElementId("a"))), AddElement(line("e", 1, 1, 2, 2)))
        assertEquals("Line 3", name(doc, "e"), "the deleted ordinal 1 must not come back")
        assertEquals("Line 2", name(doc, "c"))
    }

    @Test
    fun ellipse_should_ReadCircleOnlyWhenRadiiAreEqual() {
        val circle = circle("c", 0, 0, 5)
        val ellipse = dev.stapler.stelekit.sketch.v2.scene.EllipseElement(circle.base.copy(id = ElementId("e")), W6Kit.u(0), W6Kit.u(0), W6Kit.u(6), W6Kit.u(3))
        val doc = run(SketchDocumentV2(), AddElement(circle), AddElement(ellipse))
        assertEquals("Circle 1", name(doc, "c"))
        assertEquals("Ellipse 2", name(doc, "e"), "circle and ellipse share one ellipse counter")
    }

    @Test
    fun counters_should_SurviveTheSheetCodec_And_NeverSitBelowAHeldOrdinal() {
        val doc = run(SketchDocumentV2(), AddElement(line("a", 0, 0, 1, 1)), AddElement(line("b", 0, 0, 2, 2)), DeleteElements(listOf(ElementId("b"))))
        assertEquals(2, doc.nameCounters.issued["line"])
        val back = SheetCodecV2.decode(SheetCodecV2.encode(doc).get()).get()
        assertEquals(doc.nameCounters, back.nameCounters)
        assertEquals(3, back.nameCounters.peek("line"))
    }

    @Test
    fun assign_should_NumberAnImportedSceneInZOrder() {
        val (named, counters) = NameCounters.Empty.assign(listOf(rect("r1", 0, 0), line("l1", 0, 0, 1, 1), rect("r2", 5, 5)))
        assertEquals(listOf(1, 1, 2), named.map { it.base.ordinal })
        assertEquals(3, counters.peek("rectangle"))
        assertEquals(2, counters.peek("line"))
    }

    @Test
    fun paste_should_GiveCopiesNewOrdinalsAndDropTheUserName() {
        var doc = run(SketchDocumentV2(), AddElement(rect("r", 0, 0)), RenameElement(ElementId("r"), "Door"))
        doc = step(doc, DuplicateElements(listOf(ElementId("r")), mapOf(ElementId("r") to ElementId("r2")), 10.0, 10.0))
        assertEquals("Door", name(doc, "r"))
        assertEquals("Rectangle 2", name(doc, "r2"))
    }

    @Test
    fun rename_should_RefuseATakenNameAndATooLongOne_And_BlankRestoresTheDefault() {
        var doc = run(SketchDocumentV2(), AddElement(rect("r", 0, 0)), AddElement(rect("s", 3, 3)))
        val taken = RenameElement(ElementId("s"), "Rectangle 1").apply(doc).swap().get()
        assertIs<CommandError.NameTaken>(taken)
        val limit = doc.scene.limits.maxNameChars
        assertIs<CommandError.NameTooLong>(RenameElement(ElementId("s"), "x".repeat(limit + 1)).apply(doc).swap().get())
        doc = step(doc, RenameElement(ElementId("s"), "Window"))
        assertEquals("Window", name(doc, "s"))
        doc = step(doc, RenameElement(ElementId("s"), "  "))
        assertEquals("Rectangle 2", name(doc, "s"))
    }

    @Test
    fun export_should_CarryTheNameInCustomData() {
        val doc = run(SketchDocumentV2(), AddElement(line("a", 0, 0, 1, 1)))
        assertEquals("{\"stelekit\":{\"name\":\"Line 1\"}}", ElementNames.excalidrawCustomData(doc.scene[ElementId("a")]!!).toString())
        assertTrue(ElementNames.isTaken(doc.scene, "Line 1"))
    }
}
