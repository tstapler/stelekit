// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.sketch.v2.W6Kit.rect
import dev.stapler.stelekit.sketch.v2.W6Kit.run
import dev.stapler.stelekit.sketch.v2.W6Kit.step
import dev.stapler.stelekit.sketch.v2.clipboard.ClipboardPayload
import dev.stapler.stelekit.sketch.v2.clipboard.PasteRoute
import dev.stapler.stelekit.sketch.v2.clipboard.SceneClipboard
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.AlignBaseline
import dev.stapler.stelekit.sketch.v2.commands.AlignEdge
import dev.stapler.stelekit.sketch.v2.commands.AlignElements
import dev.stapler.stelekit.sketch.v2.commands.DistributeAxis
import dev.stapler.stelekit.sketch.v2.commands.DistributeElements
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.StyleElements
import dev.stapler.stelekit.sketch.v2.commands.StylePatch
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.5.3: align, distribute, the scene clipboard, paste offsets and style copy. */
class AlignCommandsTest {
    private fun x(doc: SketchDocumentV2, id: String) = (doc.scene[ElementId(id)] as RectElement).x.value
    private fun y(doc: SketchDocumentV2, id: String) = (doc.scene[ElementId(id)] as RectElement).y.value

    private fun three(): SketchDocumentV2 =
        run(SketchDocumentV2(), AddElements(listOf(rect("a", 0, 0), rect("b", 30, 7), rect("c", 100, 20))))

    private val all = listOf(ElementId("a"), ElementId("b"), ElementId("c"))

    @Test
    fun distributeHorizontally_should_EqualiseTheGaps_When_ThreeRectsAt0_30_100() {
        val doc = step(three(), DistributeElements(all, DistributeAxis.Horizontal))
        assertEquals(listOf(0.0, 50.0, 100.0), listOf("a", "b", "c").map { x(doc, it) })
        assertEquals(7.0, y(doc, "b"), "the other axis is untouched")
    }

    @Test
    fun distributeVertically_should_KeepTheOutermostWhereTheyAre() {
        val doc = step(three(), DistributeElements(all, DistributeAxis.Vertical))
        assertEquals(listOf(0.0, 10.0, 20.0), listOf("a", "b", "c").map { y(doc, it) })
    }

    @Test
    fun distribute_should_DoNothingForFewerThanThree() {
        val doc = three()
        val applied = DistributeElements(all.take(2), DistributeAxis.Horizontal).apply(doc).getOrNull()!!
        assertEquals(doc, applied.doc)
        assertTrue(applied.changedIds.isEmpty())
    }

    @Test
    fun align_should_LineUpEveryEdgeAndCentre() {
        fun after(edge: AlignEdge) = step(three(), AlignElements(all, edge))
        assertEquals(listOf(0.0, 0.0, 0.0), listOf("a", "b", "c").map { x(after(AlignEdge.Left), it) })
        assertEquals(listOf(100.0, 100.0, 100.0), listOf("a", "b", "c").map { x(after(AlignEdge.Right), it) })
        assertEquals(listOf(50.0, 50.0, 50.0), listOf("a", "b", "c").map { x(after(AlignEdge.CenterHorizontal), it) })
        assertEquals(listOf(0.0, 0.0, 0.0), listOf("a", "b", "c").map { y(after(AlignEdge.Top), it) })
        assertEquals(listOf(20.0, 20.0, 20.0), listOf("a", "b", "c").map { y(after(AlignEdge.Bottom), it) })
        assertEquals(listOf(10.0, 10.0, 10.0), listOf("a", "b", "c").map { y(after(AlignEdge.CenterVertical), it) })
    }

    @Test
    fun baselineIds_should_CoverAllEightAlignAndDistributeEntries() {
        val ids = AlignBaseline.alignIds.values + AlignBaseline.distributeIds.values
        assertEquals(8, ids.toSet().size)
        assertEquals(AlignEdge.entries.toSet(), AlignBaseline.alignIds.keys)
        assertEquals(DistributeAxis.entries.toSet(), AlignBaseline.distributeIds.keys)
    }

    @Test
    fun repeatedPastes_should_StepByTenUnitsAndRemapIds() {
        val doc = three()
        val payload = SceneClipboard.copy(doc.scene, listOf(ElementId("a")))
        val ids = W6Kit.SeqIds("p")
        val once = step(doc, SceneClipboard.paste(payload, 0, ids))
        val twice = step(once, SceneClipboard.paste(payload, 1, ids))
        assertEquals(10.0, x(once, "p1"))
        assertEquals(20.0, x(twice, "p2"))
        assertEquals(5, twice.scene.size)
        assertEquals(setOf("a", "b", "c", "p1", "p2"), twice.scene.order.map { it.value }.toSet())
    }

    @Test
    fun clipboardText_should_RoundTripAndRoute() {
        val doc = three()
        val text = SceneClipboard.copy(doc.scene, listOf(ElementId("b"))).toText().fold({ error("$it") }, { it })
        assertEquals(PasteRoute.SceneInternal, ClipboardPayload.route(text))
        val back = assertNotNull(ClipboardPayload.fromText(text))
        assertEquals(listOf(ElementId("b")), back.elements.map { it.id })
        assertEquals(doc.scene[ElementId("b")], back.elements.single())
        assertEquals(PasteRoute.ExcalidrawJson, ClipboardPayload.route("{\"type\":\"excalidraw/clipboard\"}"))
        assertEquals(PasteRoute.ExcalidrawJson, ClipboardPayload.route("﻿{}"))
        assertEquals(PasteRoute.Unknown, ClipboardPayload.route("hello"))
        assertNull(ClipboardPayload.fromText("hello"))
    }

    @Test
    fun copy_should_BringBoundLabelsAndGroupMates() {
        val grouped = run(
            SketchDocumentV2(),
            AddElements(listOf(rect("a", 0, 0), rect("b", 20, 0), rect("c", 40, 0))),
            dev.stapler.stelekit.sketch.v2.commands.GroupElements(listOf(ElementId("a"), ElementId("b")), dev.stapler.stelekit.sketch.v2.scene.GroupId("g")),
        )
        assertEquals(setOf(ElementId("a"), ElementId("b")), SceneClipboard.copy(grouped.scene, listOf(ElementId("a"))).ids)
        assertTrue(SceneClipboard.copy(SketchSceneV2.empty(), listOf(ElementId("zz"))).elements.isEmpty())
    }

    @Test
    fun copyStyle_should_PasteTheLookOntoOtherElements() {
        val styled = step(three(), StyleElements(listOf(ElementId("a")), StylePatch(strokeColor = "#e03131", fillStyle = FillStyle.CrossHatch, strokeWidth = 4.0)))
        val patch = SceneClipboard.copyStyle(styled.scene[ElementId("a")]!!)
        val done = step(styled, SceneClipboard.pasteStyle(patch, listOf(ElementId("b"), ElementId("c"))))
        for (id in listOf("b", "c")) {
            val s = done.scene[ElementId(id)]!!.base.style
            assertEquals("#e03131", s.strokeColor)
            assertEquals(FillStyle.CrossHatch, s.fillStyle)
            assertEquals(4.0, s.strokeWidth)
        }
    }
}
