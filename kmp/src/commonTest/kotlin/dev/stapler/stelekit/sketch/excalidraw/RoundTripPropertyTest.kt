// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import arrow.core.Either
import dev.stapler.stelekit.sketch.v2.export.SvgImage
import dev.stapler.stelekit.sketch.v2.export.SvgImageSource
import dev.stapler.stelekit.sketch.v2.importer.ExcalidrawExporter
import dev.stapler.stelekit.sketch.v2.importer.ExcalidrawImporter
import dev.stapler.stelekit.sketch.v2.importer.ImportedScene
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.BindingMode
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.GroupId
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.Roundness
import dev.stapler.stelekit.sketch.v2.scene.RoundnessKind
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.StrokeStyle
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Story 5.1.3 (Task 5.1.3b): any generated scene of the supported subset survives export then import with kinds, geometry,
 * text, bindings and z-order intact; point and arc come back from their export fallback (OD-8); layers flatten per ADR-006;
 * and saving twice gives the same bytes. Generated geometry sits on the sixteenth-inch grid, where every sum in the writer
 * is exact, so equality is exact (the plan asks for 1e-9). The fixtures are hand-authored, not real exports (owner Task
 * 0.1.5d is open): see `FixtureRoundTripTest`.
 */
class RoundTripPropertyTest {
    private fun u(v: Int) = SceneUnit.ofInt(v)

    private fun pt(r: Random) = ScenePoint(u(r.nextInt(-400, 400) * GRID / 16), u(r.nextInt(-400, 400) * GRID / 16))

    private fun style(r: Random) = ElementStyle(
        strokeColor = listOf("#1e1e1e", "#e03131", "#2f9e44")[r.nextInt(3)],
        backgroundColor = listOf("transparent", "#ffec99")[r.nextInt(2)],
        fillStyle = FillStyle.entries[r.nextInt(FillStyle.entries.size)],
        strokeWidth = (r.nextInt(1, 5)).toDouble(),
        strokeStyle = StrokeStyle.entries[r.nextInt(StrokeStyle.entries.size)],
        roughness = r.nextInt(3),
        roundness = if (r.nextBoolean()) null else Roundness(RoundnessKind.entries[r.nextInt(RoundnessKind.entries.size)], if (r.nextBoolean()) 0.25 else null),
    )

    private fun base(r: Random, id: String, frames: List<String>) = ElementBase(
        id = ElementId(id),
        groupIds = List(r.nextInt(3)) { GroupId("g${r.nextInt(3)}") },
        frameId = if (frames.isNotEmpty() && r.nextInt(4) == 0) ElementId(frames[r.nextInt(frames.size)]) else null,
        zKey = ZKey("a" + id.padStart(4, '0')),
        locked = r.nextInt(5) == 0,
        opacity = listOf(100, 80, 25)[r.nextInt(3)],
        style = style(r),
        seed = r.nextInt(1_000_000),
    )

    private val angles = listOf(0.0, 0.5, 1.0 / 3.0, -2.25)

    private fun scene(seed: Long): SketchSceneV2 {
        val r = Random(seed)
        val count = r.nextInt(4, 14)
        val ids = List(count) { "e$it" }
        val frames = ids.filterIndexed { i, _ -> i % 5 == 0 }
        val shapes = ids.filterIndexed { i, _ -> i % 5 == 1 }
        var scene = SketchSceneV2.empty()
        for ((i, id) in ids.withIndex()) {
            val b = base(r, id, frames.filter { it != id })
            val x = u(r.nextInt(-300, 300))
            val y = u(r.nextInt(-300, 300))
            val w = u(r.nextInt(1, 200))
            val h = u(r.nextInt(1, 200))
            val angle = angles[r.nextInt(angles.size)]
            val element: SceneElement = when (i % 5) {
                0 -> FrameElement(b.copy(frameId = null, name = "Frame $i"), x, y, w, h, angle)
                1 -> shapeOf(r, b, x, y, w, h, angle)
                2 -> linearOf(r, b, shapes)
                3 -> TextElement(
                    b, x, y, w, h, angle, text = "t$i\nline é", originalText = "t$i", fontSize = r.nextInt(8, 40).toDouble(), fontFamily = r.nextInt(1, 6),
                    textAlign = TextAlign.entries[r.nextInt(TextAlign.entries.size)], containerId = shapes.firstOrNull()?.let { ElementId(it) }.takeIf { r.nextBoolean() },
                    autoResize = r.nextBoolean(),
                )
                else -> technical(r, b, x, y, w)
            }
            scene = scene.add(element).fold({ error("generator: $it") }, { it })
        }
        return scene
    }

    private fun shapeOf(r: Random, b: ElementBase, x: SceneUnit, y: SceneUnit, w: SceneUnit, h: SceneUnit, angle: Double): SceneElement = when (r.nextInt(3)) {
        0 -> RectElement(b, x, y, w, h, angle)
        1 -> DiamondElement(b, x, y, w, h, angle)
        else -> EllipseElement(b, x, y, w, if (r.nextBoolean()) w else h, angle)
    }

    private fun linearOf(r: Random, b: ElementBase, shapes: List<String>): SceneElement {
        val points = List(r.nextInt(2, 6)) { pt(r) }
        return when (r.nextInt(3)) {
            0 -> LineElement(b, points, polygon = r.nextBoolean())
            1 -> FreedrawElement(b, points, if (r.nextBoolean()) points.map { 0.5 } else emptyList(), simulatePressure = r.nextBoolean())
            else -> {
                fun binding() = shapes.takeIf { it.isNotEmpty() && r.nextBoolean() }?.let { Binding(ElementId(it[r.nextInt(it.size)]), r.nextInt(5) / 4.0, r.nextInt(5) / 4.0, BindingMode.entries[r.nextInt(BindingMode.entries.size)]) }
                ArrowElement(b, points, binding(), binding(), Arrowhead.entries[r.nextInt(Arrowhead.entries.size)], Arrowhead.entries[r.nextInt(Arrowhead.entries.size)], elbowed = r.nextBoolean())
            }
        }
    }

    private fun technical(r: Random, b: ElementBase, x: SceneUnit, y: SceneUnit, radius: SceneUnit): SceneElement =
        if (r.nextBoolean()) PointElement(b, x, y) else ArcElement(b, x, y, radius, angles[r.nextInt(angles.size)], 0.5 + r.nextInt(4))

    private suspend fun roundTrip(scene: SketchSceneV2, images: SvgImageSource = SvgImageSource.None): ImportedScene {
        val exported = ExcalidrawExporter().export(scene, images).fold({ error("export: $it") }, { it })
        val result = ExcalidrawImporter().import(ImportSource.ofBytes(exported.text.encodeToByteArray()))
        assertIs<Either.Right<ImportedScene>>(result, "$result")
        return result.value
    }

    /** What an export may legitimately change: re-keyed order, the edit counter, ordinals, a non-frame's user name and extras. */
    private fun normalised(e: SceneElement): SceneElement =
        e.withBase(e.base.copy(zKey = ZKey("a"), version = 1, ordinal = 0, name = e.base.name.takeIf { e is FrameElement }, extras = JsonObject(emptyMap())))

    @Test
    fun roundTrip_should_PreserveKindsGeometryTextBindingsAndZOrder_ForAnyGeneratedScene() = runTest {
        checkAll(ITERATIONS, Arb.long()) { seed ->
            val original = scene(seed)
            val back = roundTrip(original)
            assertEquals(original.elementsInOrder.map { it.id }, back.elements.map { it.id }, "z-order, seed $seed")
            assertEquals(original.elementsInOrder.map(::normalised), back.elements.map(::normalised), "elements, seed $seed")
            assertEquals(original.size, back.report.imported)
        }
    }

    @Test
    fun export_should_GiveTheSameBytesTwice_SoRepeatedSavesDoNotChurnDiffs() {
        val original = scene(SEED_FOR_BYTES)
        val a = ExcalidrawExporter().export(original).fold({ error("$it") }, { it }).text
        val b = ExcalidrawExporter().export(original).fold({ error("$it") }, { it }).text
        assertEquals(a, b)
        val elements = Json.parseToJsonElement(a).jsonObject["elements"]!!.jsonArray.map { it.jsonObject }
        assertTrue(elements.all { it["updated"]!!.jsonPrimitive.content == "1" && it["isDeleted"]!!.jsonPrimitive.content == "false" })
    }

    @Test
    fun pointAndArc_should_ExportAsATinyEllipseAndASampledLine_AndComeBackAsTheSameKinds() = runTest {
        val point = PointElement(ElementBase(ElementId("p"), zKey = ZKey("a1")), u(100), u(-50))
        val arc = ArcElement(ElementBase(ElementId("c"), zKey = ZKey("a2")), u(10), u(20), u(30), 0.5, 2.0)
        val scene = SketchSceneV2.empty().add(point).fold({ error("$it") }, { it }).add(arc).fold({ error("$it") }, { it })
        val text = ExcalidrawExporter().export(scene).fold({ error("$it") }, { it }).text
        val elements = Json.parseToJsonElement(text).jsonObject["elements"]!!.jsonArray.map { it.jsonObject }
        val tiny = elements.single { it["id"]!!.jsonPrimitive.content == "p" }
        assertEquals("ellipse", tiny["type"]!!.jsonPrimitive.content)
        assertEquals("4", tiny["width"]!!.jsonPrimitive.content)
        val sampled = elements.single { it["id"]!!.jsonPrimitive.content == "c" }
        assertEquals("line", sampled["type"]!!.jsonPrimitive.content)
        assertTrue(sampled["points"]!!.jsonArray.size > 2)
        assertEquals("arc", sampled["customData"]!!.jsonObject["stelekit"]!!.jsonObject["kind"]!!.jsonPrimitive.content)

        val back = roundTrip(scene)
        assertEquals(listOf<SceneElement>(point, arc).map(::normalised), back.elements.map(::normalised))
    }

    @Test
    fun layers_should_FlattenPerAdr006_HiddenOmittedLockedAndOpacityFoldedAndHintWritten() = runTest {
        val l2 = Layer(LayerId("l2"), "Hidden", visible = false, order = 1)
        val l3 = Layer(LayerId("l3"), "Locked", locked = true, opacity = 0.5, order = 2)
        var scene = SketchSceneV2.empty().withLayers(listOf(Layer.DefaultLayer, l2, l3))
        fun rect(id: String, layer: LayerId, key: String) = RectElement(ElementBase(ElementId(id), layerId = layer, zKey = ZKey(key)), u(0), u(0), u(10), u(10))
        for (e in listOf(rect("top", LayerId("l3"), "a1"), rect("hidden", LayerId("l2"), "a1"), rect("bottom", LayerId.Default, "a9"))) scene = scene.add(e).fold({ error("$it") }, { it })
        val exported = ExcalidrawExporter().export(scene).fold({ error("$it") }, { it })
        assertEquals(1, exported.hiddenOmitted)
        val elements = Json.parseToJsonElement(exported.text).jsonObject["elements"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("bottom", "top"), elements.map { it["id"]!!.jsonPrimitive.content }, "layer order folded into index")
        val indexes = elements.map { it["index"]!!.jsonPrimitive.content }
        assertTrue(indexes[0] < indexes[1])
        val top = elements[1]
        assertEquals("true", top["locked"]!!.jsonPrimitive.content)
        assertEquals("50", top["opacity"]!!.jsonPrimitive.content)
        assertEquals("l3", top["customData"]!!.jsonObject["stelekit"]!!.jsonObject["layerId"]!!.jsonPrimitive.content)
        assertEquals("100", elements[0]["opacity"]!!.jsonPrimitive.content)

        val back = roundTrip(scene)
        assertEquals(listOf("l3"), back.layers.map { it.id.value })
    }

    @Test
    fun layerHints_should_BeCappedOnImport_AndTheOverflowIgnored() = runTest {
        val limits = SceneLimits.Default
        val distinct = limits.maxLayers + 6
        val elements = (0 until distinct).map { i ->
            ExcalidrawTestData.element("rectangle", "r$i", i * 20, 0, 10, 10, ""","customData":{"stelekit":{"layerId":"layer-$i"}}""")
        } + ExcalidrawTestData.element("rectangle", "long", 0, 50, 10, 10, ""","customData":{"stelekit":{"layerId":"${"x".repeat(limits.maxLayerIdChars + 1)}"}}""")
        val result = ExcalidrawImporter().import(ImportSource.ofBytes(ExcalidrawTestData.scene(elements).encodeToByteArray()))
        assertIs<Either.Right<ImportedScene>>(result)
        val imported = result.value
        assertTrue(imported.layers.size < limits.maxLayers, "the default layer takes one of the slots")
        assertEquals(distinct - imported.layers.size + 1, imported.report.layerHintsIgnored)
    }

    @Test
    fun images_should_ExportTheirFilesEntryAndAMissingAssetAsAnErrorElementWithoutFailing() = runTest {
        val bytes = ExcalidrawTestData.pngBytes(8, 8)
        val fileId = "a".repeat(64)
        val have = ImageElement(ElementBase(ElementId("i1"), zKey = ZKey("a1")), u(0), u(0), u(20), u(20), fileId = fileId)
        val lost = ImageElement(ElementBase(ElementId("i2"), zKey = ZKey("a2")), u(30), u(0), u(20), u(20), fileId = "b".repeat(64))
        val scene = SketchSceneV2.empty().add(have).fold({ error("$it") }, { it }).add(lost).fold({ error("$it") }, { it })
        val source = SvgImageSource { id -> if (id == fileId) SvgImage("image/png", bytes) else null }
        val exported = ExcalidrawExporter().export(scene, source).fold({ error("$it") }, { it })
        assertEquals(listOf(fileId), exported.fileIds)
        assertEquals(listOf("b".repeat(64)), exported.missingFileIds)
        val root = Json.parseToJsonElement(exported.text).jsonObject
        val entry = root["files"]!!.jsonObject[fileId]!!.jsonObject
        assertEquals("image/png", entry["mimeType"]!!.jsonPrimitive.content)
        assertTrue(entry["dataURL"]!!.jsonPrimitive.content.startsWith("data:image/png;base64,"))
        val statuses = (root["elements"] as JsonArray).associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["status"]!!.jsonPrimitive.content }
        assertEquals(mapOf("i1" to "saved", "i2" to "error"), statuses)
    }

    @Test
    fun images_should_NeverBeWrittenAsSvgOrAnUnknownType() {
        val fileId = "c".repeat(64)
        val img = ImageElement(ElementBase(ElementId("i1")), u(0), u(0), u(20), u(20), fileId = fileId)
        val scene = SketchSceneV2.empty().add(img).fold({ error("$it") }, { it })
        val svg = SvgImageSource { SvgImage("image/svg+xml", "<svg onload=alert(1)/>".encodeToByteArray()) }
        val exported = ExcalidrawExporter().export(scene, svg).fold({ error("$it") }, { it })
        assertEquals(emptyList(), exported.fileIds)
        assertEquals(listOf(fileId), exported.missingFileIds)
        assertTrue("svg" !in exported.text.substringAfter("\"files\""))
    }

    private companion object {
        const val ITERATIONS = 150
        const val GRID = 16
        const val SEED_FOR_BYTES = 7L
    }
}
