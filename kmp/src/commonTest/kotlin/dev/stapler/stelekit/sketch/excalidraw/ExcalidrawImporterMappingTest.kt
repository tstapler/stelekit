// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import arrow.core.Either
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.element
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawTestData.scene
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.importer.ExcalidrawImporter
import dev.stapler.stelekit.sketch.v2.importer.ImportedScene
import dev.stapler.stelekit.sketch.v2.importer.MissingImageReason
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.StrokeStyle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 5.1.2 on inline files (runs on every target, unlike the fixture test which reads resource files on the JVM). */
class ExcalidrawImporterMappingTest {
    private suspend fun import(text: String, limits: SceneLimits = SceneLimits.Default): ImportedScene {
        val result = ExcalidrawImporter(limits).import(ImportSource.ofBytes(text.encodeToByteArray()))
        assertIs<Either.Right<ImportedScene>>(result, "$result")
        return result.value
    }

    private fun linear(type: String, id: String, extra: String) = element(type, id, 0, 0, 10, 0, extra)

    @Test
    fun unknownType_should_BeSkippedAndNamedInTheSummary() = runTest {
        val imported = import(scene(listOf(element("rectangle", "r1"), element("stickynote", "s1"), element("stickynote", "s2"))))
        assertEquals(1, imported.elements.size)
        assertEquals("Imported 1 of 3. Skipped: 2 stickynotes.", imported.report.summary())
    }

    @Test
    fun unknownArrowheadFillAndStrokeStyle_should_BeCountedAndFallBack() = runTest {
        val arrow = linear("arrow", "a1", ""","points":[[0,0],[10,0]],"startArrowhead":"crowfoot","endArrowhead":"bar"""")
        val rect = element("rectangle", "r1").replace(""""fillStyle":"solid"""", """"fillStyle":"dots"""").replace(""""strokeStyle":"solid"""", """"strokeStyle":"wavy"""")
        val imported = import(scene(listOf(arrow, rect)))
        val a = assertIs<ArrowElement>(imported.elements[0])
        assertEquals(Arrowhead.None, a.startArrowhead)
        assertEquals(Arrowhead.Bar, a.endArrowhead)
        val r = assertIs<RectElement>(imported.elements[1])
        assertEquals(FillStyle.Solid, r.base.style.fillStyle)
        assertEquals(JsonPrimitive("dots"), r.base.extras["fillStyle"], "the unknown name is kept for the export")
        assertEquals(StrokeStyle.Solid, r.base.style.strokeStyle)
        assertEquals(listOf(1, 1, 1), listOf(imported.report.unsupportedArrowheads, imported.report.unknownFills, imported.report.unknownStrokeStyles))
        assertEquals("Imported 2 of 2. Changed: 1 unsupported arrowhead, 1 unknown fill, 1 unknown stroke style.", imported.report.summary())
    }

    @Test
    fun arrowWithoutArrowheadMembers_should_GetTheExcalidrawDefaults() = runTest {
        val imported = import(scene(listOf(linear("arrow", "a1", ""","points":[[0,0],[10,0]]"""))))
        val a = assertIs<ArrowElement>(imported.elements.single())
        assertEquals(Arrowhead.None, a.startArrowhead)
        assertEquals(Arrowhead.Arrow, a.endArrowhead)
    }

    @Test
    fun missingImages_should_BeLabelledPlaceholdersWithAReason() = runTest {
        val noFile = element("image", "i1", 0, 0, 40, 40, ""","fileId":"absent","scale":[1,1]""")
        val linked = element("image", "i2", 50, 0, 40, 40, ""","fileId":null,"scale":[1,1]""").replace(""""link":null""", "").replace(""""locked":false""", """"locked":false,"link":"https://example.com/p.png"""")
        val huge = ExcalidrawTestData.pngBytes(HUGE_SIDE, HUGE_SIDE)
        val refused = element("image", "i3", 100, 0, 40, 40, ""","fileId":"big","scale":[1,1]""")
        val files = ExcalidrawTestData.files(ExcalidrawTestData.fileEntry("big", "image/png", huge))
        val imported = import(scene(listOf(noFile, linked, refused), files))
        assertEquals(3, imported.elements.size)
        assertTrue(imported.elements.all { it is ImageElement && it.fileId == null })
        val reasons = imported.report.missingImages.associate { it.elementId.value to it.reason }
        assertEquals(MissingImageReason.NoFileData, reasons["i1"])
        assertEquals(MissingImageReason.LinkNotDownloaded, reasons["i2"])
        assertIs<MissingImageReason.Refused>(reasons["i3"])
        assertEquals("Imported 3 of 3. 3 images could not be shown.", imported.report.summary())
    }

    @Test
    fun layerHints_should_BeCappedAndAnOverlongIdIgnored() = runTest {
        fun hinted(id: String, layer: String) = element("rectangle", id, extra = ""","customData":{"stelekit":{"layerId":"$layer"}}""")
        val limits = SceneLimits(maxLayers = 3, maxLayerIdChars = 8)
        val imported = import(scene(listOf(hinted("a", "one"), hinted("b", "two"), hinted("c", "three"), hinted("d", "one"), hinted("e", "far-too-long-id"))), limits)
        assertEquals(listOf("one", "two"), imported.layers.map { it.id.value })
        assertEquals(listOf("one", "two", "default", "one", "default"), imported.elements.map { it.base.layerId.value })
        assertEquals(2, imported.report.layerHintsIgnored)
        assertEquals(LayerId("one"), imported.elements[3].base.layerId)
    }

    @Test
    fun pointAndArc_should_BeRestoredFromTheirExportFallbackTags() = runTest {
        val point = element("ellipse", "p1", 95, 95, 10, 10, ""","customData":{"stelekit":{"kind":"point"}}""")
        val arc = linear(
            "line", "arc1",
            ""","points":[[0,0],[5,5],[10,0]],"customData":{"stelekit":{"kind":"arc","cx":5,"cy":0,"radius":5,"startAngle":0,"endAngle":3.14159}}""",
        )
        val untagged = linear("line", "l1", ""","points":[[0,0],[5,5]]""")
        val imported = import(scene(listOf(point, arc, untagged)))
        val p = assertIs<PointElement>(imported.elements[0])
        assertEquals(100.0 to 100.0, p.x.value to p.y.value)
        val a = assertIs<ArcElement>(imported.elements[1])
        assertEquals(listOf(5.0, 0.0, 5.0), listOf(a.cx.value, a.cy.value, a.radius.value))
        assertIs<LineElement>(imported.elements[2])
    }

    @Test
    fun validIncreasingIndexes_should_BecomeTheZKeys() = runTest {
        fun at(id: String, index: String) = element("rectangle", id).replace(""""index":"a0"""", """"index":"$index"""")
        val ordered = import(scene(listOf(at("a", "a1"), at("b", "a2"), at("c", "a3"))))
        assertEquals(listOf("a1", "a2", "a3"), ordered.elements.map { it.base.zKey.value })
        assertEquals(false, ordered.report.zOrderFromArray)
        val clash = import(scene(listOf(at("a", "a2"), at("b", "a2"), at("c", "a3"))))
        assertTrue(clash.report.zOrderFromArray, "repeated indexes fall back to array order")
        assertEquals(clash.elements.map { it.base.zKey }.sorted(), clash.elements.map { it.base.zKey })
    }

    @Test
    fun hostileColourAndBadNumbers_should_FallBackToDefaults() = runTest {
        val rect = element("rectangle", "r1").replace(""""strokeColor":"#1e1e1e"""", """"strokeColor":"red;} <script>"""").replace(""""opacity":100""", """"opacity":250""")
        val r = assertIs<RectElement>(import(scene(listOf(rect))).elements.single())
        assertEquals("#1e1e1e", r.base.style.strokeColor)
        assertEquals(100, r.base.opacity)
    }

    @Test
    fun importScene_should_RefuseWholeAndChangeNothing_When_TheSheetCannotHoldEveryElement() = runTest {
        val imported = import(scene(listOf(element("rectangle", "a"), element("rectangle", "b"), element("rectangle", "c"))))
        val doc = SketchDocumentV2(scene = SketchSceneV2.empty(SceneLimits(maxElements = 2)))
        val result = Transaction.begin(doc).apply(imported.command())
        assertEquals(CommandError.CapacityExceeded(2), result.leftOrNull())
        assertEquals(0, doc.scene.size)
    }

    @Test
    fun importScene_should_AddTheNamedLayersOnceAndUndoThemWithTheElements() = runTest {
        val text = scene(listOf(element("rectangle", "a", extra = ""","customData":{"stelekit":{"layerId":"walls"}}""")))
        val imported = import(text)
        val committed = Transaction.begin(SketchDocumentV2()).apply(imported.command()).getOrNull()!!.commit().getOrNull()!!
        assertEquals(listOf("default", "walls"), committed.after.scene.layers.map { it.id.value })
        assertEquals(LayerId("walls"), committed.after.scene[ElementId("a")]?.base?.layerId)
        val undone = committed.patch.backward(committed.after).getOrNull()!!
        assertEquals(listOf("default"), undone.scene.layers.map { it.id.value })
        assertEquals(0, undone.scene.size)
    }

    private companion object {
        const val HUGE_SIDE = 60_000
    }
}
