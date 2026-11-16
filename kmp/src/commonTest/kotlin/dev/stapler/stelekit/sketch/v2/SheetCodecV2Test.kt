// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import arrow.core.Either
import dev.stapler.stelekit.sketch.v2.codec.CanonicalJson
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.codec.SheetError
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.BoundKind
import dev.stapler.stelekit.sketch.v2.scene.BoundRef
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
import dev.stapler.stelekit.sketch.v2.scene.GroupId
import dev.stapler.stelekit.sketch.v2.scene.ImageCrop
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
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.StrokeStyle
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sample documents shared by the codec tests. Doubles enter JSON through `CanonicalJson.number` so equality survives a round trip. */
internal object SheetFixtures {
    fun u(d: Double): SceneUnit = SceneUnit.of(d).fold({ error("bad fixture $d") }, { it })

    fun pt(x: Double, y: Double) = ScenePoint(u(x), u(y))

    private fun num(d: Double): JsonPrimitive = CanonicalJson.number(d).fold({ error("bad number") }, { it })

    fun base(id: String, index: Int = 0, layer: LayerId = LayerId.Default) =
        ElementBase(id = ElementId(id), layerId = layer, zKey = ZKey("a" + index.toString().padStart(3, '0')), seed = index, ordinal = index)

    /** Every element kind, a second layer, constraints and extras. */
    fun everyKind(): SketchDocumentV2 {
        val extras = JsonObject(mapOf("customData" to JsonObject(mapOf("scale" to num(0.1), "tag" to JsonPrimitive("a\"b\n")))))
        val styled = ElementStyle(strokeColor = "#ff0000", backgroundColor = "#00ff00", fillStyle = FillStyle.Hachure, strokeWidth = 0.1, strokeStyle = StrokeStyle.Dashed, roughness = 2, roundness = Roundness(RoundnessKind.ProportionalRadius, 0.25))
        val elements: List<SceneElement> = listOf(
            RectElement(base("r1", 0).copy(style = styled, groupIds = listOf(GroupId("g")), extras = extras, name = "Wall"), u(0.1), u(-3.5), u(100.0), u(1.0e-7), 0.30000000000000004, listOf(BoundRef(ElementId("a1"), BoundKind.Arrow))),
            DiamondElement(base("d1", 1).copy(groupIds = listOf(GroupId("g"))), u(5.0), u(6.0), u(7.0), u(8.0)),
            EllipseElement(base("e1", 2, LayerId("l2")), u(1.0), u(2.0), u(3.0), u(3.0)),
            LineElement(base("l1", 3), listOf(pt(0.0, 0.0), pt(1.5, 2.5), pt(1.0e7, -2.0)), polygon = true),
            ArrowElement(base("a1", 4), listOf(pt(0.0, 0.0), pt(10.0, 10.0)), endBinding = Binding(ElementId("r1"), 0.5, 0.25), startArrowhead = Arrowhead.Bar, elbowed = true),
            FreedrawElement(base("f1", 5), listOf(pt(0.0, 0.0), pt(1.0, 1.0)), listOf(0.5, 0.75), simulatePressure = false),
            TextElement(base("t1", 6), u(1.0), u(1.0), u(50.0), u(20.0), text = "hi\nthere é", fontSize = 16.5),
            ImageElement(base("i1", 7), u(0.0), u(0.0), u(10.0), u(10.0), fileId = "abc123", flipX = true, crop = ImageCrop(1.0, 2.0, 3.0, 4.0, 100.0, 200.0)),
            FrameElement(base("fr1", 8), u(0.0), u(0.0), u(500.0), u(500.0)),
            PointElement(base("p1", 9), u(2.0), u(3.0)),
            ArcElement(base("c1", 10), u(0.0), u(0.0), u(5.0), 0.0, 1.5707963267948966),
        )
        var scene = SketchSceneV2.empty().withLayers(listOf(Layer.DefaultLayer, Layer(LayerId("l2"), "Layer 2", visible = false, locked = true, opacity = 0.5, order = 1)))
        for (e in elements) scene = scene.add(e).fold({ error("fixture $it") }, { it })
        val constraints = listOf(
            Constraint(ConstraintId("k1"), ConstraintKind.Coincident, listOf(ConstraintTarget(ElementId("p1")), ConstraintTarget(ElementId("l1")))),
            Constraint(ConstraintId("k2"), ConstraintKind.Radius, listOf(ConstraintTarget(ElementId("e1"))), value = 0.1, reference = true),
        )
        return SketchDocumentV2(scene, "00000000-0000-0000-0000-000000000001", constraints, extras)
    }

    fun rects(count: Int, movedX: Double? = null): SketchDocumentV2 {
        var scene = SketchSceneV2.empty()
        for (i in 0 until count) {
            val x = if (i == 0 && movedX != null) movedX else i * 10.0
            scene = scene.add(RectElement(base("r$i", i), u(x), u(0.0), u(8.0), u(8.0))).fold({ error("fixture") }, { it })
        }
        return SketchDocumentV2(scene, "sheet-100")
    }

    fun bytes(doc: SketchDocumentV2): ByteArray = SheetCodecV2.encode(doc).fold({ error("encode $it") }, { it })
}

class SheetCodecV2Test {
    private fun <L, R> Either<L, R>.right(): R = fold({ error("unexpected Left: $it") }, { it })

    private fun <L, R> Either<L, R>.left(): L = fold({ it }, { error("unexpected Right: $it") })

    @Test
    fun roundTrip_should_ReproduceDocumentAndBytes_When_EveryKindLayersConstraintsAndExtras() {
        val doc = SheetFixtures.everyKind()
        val bytes = SheetFixtures.bytes(doc)
        val decoded = SheetCodecV2.decode(bytes).right()
        assertEquals(doc, decoded)
        assertTrue(bytes.contentEquals(SheetFixtures.bytes(decoded)), "re-encoding must give identical bytes")
    }

    @Test
    fun encode_should_PutSchemaWithinTheFirst64Bytes_OfARealSheet() {
        val bytes = SheetFixtures.bytes(SheetFixtures.rects(100))
        val text = bytes.decodeToString()
        assertTrue(text.startsWith("{\"schema\":2,\"sheetUuid\":\""), text.take(40))
        assertTrue(text.indexOf("\"schema\"") < SchemaWindow)

        val sorted = CanonicalJson.encode(CanonicalJson.decode(bytes).right()).right().decodeToString()
        assertTrue(sorted.indexOf("\"schema\"") >= SchemaWindow, "a fully sorted emission puts schema late: ${sorted.indexOf("\"schema\"")}")
    }

    @Test
    fun encode_should_ChangeExactlyOneLine_When_OneElementMoves() {
        val before = SheetFixtures.bytes(SheetFixtures.rects(100)).decodeToString().lines()
        val after = SheetFixtures.bytes(SheetFixtures.rects(100, movedX = 123.5)).decodeToString().lines()
        assertEquals(before.size, after.size)
        assertEquals(1, before.indices.count { before[it] != after[it] })
        assertTrue(CanonicalJson.decode(after.joinToString("\n").encodeToByteArray()).isRight())
    }

    @Test
    fun decode_should_BeLeft_When_SchemaIsNotTwo() {
        val v1 = """{"schema":1,"sheetUuid":"s","primitives":[]}""".encodeToByteArray()
        assertEquals(SheetError.UnsupportedSchema(1L), SheetCodecV2.decode(v1).left())
        assertEquals(SheetError.UnsupportedSchema(null), SheetCodecV2.decode("{}".encodeToByteArray()).left())
    }

    @Test
    fun decode_should_ClearDanglingBinding_When_SheetIsReconciled() {
        val json = """{"schema":2,"sheetUuid":"s","elements":[
            {"type":"arrow","id":"a1","pts":[[0,0],[5,5]],"eb":{"id":"gone","fx":0.5,"fy":0.5}}
        ]}"""
        val doc = SheetCodecV2.decode(json.encodeToByteArray()).right()
        assertNull((doc.scene[ElementId("a1")] as ArrowElement).endBinding)
    }

    @Test
    fun decode_should_RejectOverCountSheets_When_LimitsExceeded() {
        val manyElements = "{\"schema\":2,\"sheetUuid\":\"s\",\"elements\":[" + (0..5000).joinToString(",") { """{"type":"point","id":"p$it","x":0,"y":0}""" } + "]}"
        assertIs<SheetError.TooManyElements>(SheetCodecV2.decode(manyElements.encodeToByteArray()).left())

        val manyConstraints = "{\"schema\":2,\"sheetUuid\":\"s\",\"constraints\":[" + (0..200).joinToString(",") { "{}" } + "]}"
        assertIs<SheetError.TooManyConstraints>(SheetCodecV2.decode(manyConstraints.encodeToByteArray()).left())

        val manyPoints = "{\"schema\":2,\"sheetUuid\":\"s\",\"elements\":[{\"type\":\"line\",\"id\":\"l\",\"pts\":[" + (0..2000).joinToString(",") { "[$it,0]" } + "]}]}"
        assertIs<SheetError.TooManyPoints>(SheetCodecV2.decode(manyPoints.encodeToByteArray()).left())
    }

    @Test
    fun decode_should_RejectOversizedAndTooDeepSheets() {
        val small = SceneLimits(maxSheetBytes = 200)
        assertIs<SheetError.TooLarge>(SheetCodecV2.decode(SheetFixtures.bytes(SheetFixtures.rects(10)), small).left())
        assertIs<SheetError.TooLarge>(SheetCodecV2.encode(SheetFixtures.rects(10), small).left())

        val depth = SceneLimits.Default.maxJsonDepth + 6
        val nested = "[".repeat(depth) + "]".repeat(depth)
        val deep = "{\"schema\":2,\"sheetUuid\":\"s\",\"extras\":{\"x\":$nested}}"
        assertIs<SheetError.TooDeep>(SheetCodecV2.decode(deep.encodeToByteArray()).left())
    }

    @Test
    fun decode_should_BeLeft_When_NumbersAreNotFinite() {
        for (bad in listOf("NaN", "Infinity", "1e999", "-1e999")) {
            val json = """{"schema":2,"sheetUuid":"s","elements":[{"type":"point","id":"p","x":$bad,"y":0}]}"""
            assertTrue(SheetCodecV2.decode(json.encodeToByteArray()).isLeft(), bad)
        }
        val outOfRange = """{"schema":2,"sheetUuid":"s","elements":[{"type":"point","id":"p","x":1e9,"y":0}]}"""
        assertIs<SheetError.OutOfRange>(SheetCodecV2.decode(outOfRange.encodeToByteArray()).left())
    }

    @Test
    fun decode_should_BeLeft_When_TruncatedOrNotUtf8() {
        val bytes = SheetFixtures.bytes(SheetFixtures.everyKind())
        assertTrue(SheetCodecV2.decode(bytes.copyOf(bytes.size / 2)).isLeft())
        assertTrue(SheetCodecV2.decode(byteArrayOf(0x7b, 0xC3.toByte(), 0x28, 0x7d)).isLeft())
        assertTrue(SheetCodecV2.decode(ByteArray(0)).isLeft())
    }

    @Test
    fun decode_should_RefuseDuplicateIdsAndUnknownTypes() {
        val dup = """{"schema":2,"sheetUuid":"s","elements":[{"type":"point","id":"p","x":0,"y":0},{"type":"point","id":"p","x":1,"y":1}]}"""
        assertEquals(SheetError.DuplicateElement(1), SheetCodecV2.decode(dup.encodeToByteArray()).left())
        val unknown = """{"schema":2,"sheetUuid":"s","elements":[{"type":"hexagon","id":"p"}]}"""
        assertEquals(SheetError.InvalidElement(0, "type"), SheetCodecV2.decode(unknown.encodeToByteArray()).left())
    }
}

private const val SchemaWindow = 64
