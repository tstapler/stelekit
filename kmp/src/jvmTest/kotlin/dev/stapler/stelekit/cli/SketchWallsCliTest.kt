// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.cli

import arrow.core.Either
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningKind
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.WallGeometry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `build`, `describe` and `validate` for walls and hosted openings. */
class SketchWallsCliTest {
    private fun outcome(spec: String): SpecOutcome = SketchSpecBuilder(Json.parseToJsonElement(spec) as JsonObject).build()

    private fun built(spec: String): SketchDocumentV2 {
        val o = outcome(spec)
        return o.doc ?: error("spec failed: ${o.issues.errors}")
    }

    private fun bytes(doc: SketchDocumentV2): ByteArray = (SheetCodecV2.encode(doc) as Either.Right).value

    private val room = """{
      "spec": 1, "mode": "plan", "title": "Walls",
      "shapes": [ {"id": "living", "as": "walls", "x": "0", "y": "0", "w": "14'", "h": "12'", "label": "Living"} ],
      "openings": [
        {"id": "door1", "type": "door", "on": "living", "wall": "south", "offset": "10'", "width": "3'"},
        {"id": "win1", "type": "window", "on": "living", "wall": "north", "offset": "4'", "width": "5'"},
        {"id": "gap1", "type": "opening", "on": "living", "wall": "east", "offset": "2'", "width": "3'"}
      ]
    }"""

    @Test
    fun `a rect drawn as walls builds a closed wall and hosted openings at the right place`() {
        val doc = built(room)
        val wall = doc.scene[ElementId("living")] as WallElement
        assertTrue(wall.closed)
        assertEquals(4, wall.points.size)
        assertEquals(72.0, wall.thickness.value)
        val door = doc.scene[ElementId("door1")] as OpeningElement
        assertEquals(OpeningKind.Door, door.kind)
        assertEquals(ElementId("living"), door.hostId)
        assertEquals(2, door.segment, "the south wall is the third segment of the clockwise outline")
        assertTrue(door.flipSide, "swing: in goes to the inside, which is the right of a clockwise outline")
        val centreX = door.x.value + door.width.value / 2.0
        val centreY = door.y.value + door.height.value / 2.0
        assertEquals((10 + 1.5) * 192.0, centreX, 1e-6, "10' from the west end plus half the 3' door")
        assertEquals(12 * 192.0, centreY, 1e-6)
        val window = doc.scene[ElementId("win1")] as OpeningElement
        assertEquals(OpeningKind.Window, window.kind)
        assertEquals(0, window.segment)
        assertEquals((4 + 2.5) * 192.0, window.x.value + window.width.value / 2.0, 1e-6)
        val gap = doc.scene[ElementId("gap1")] as OpeningElement
        assertEquals(OpeningKind.Opening, gap.kind)
        assertEquals(1, gap.segment)
    }

    @Test
    fun `an open chain of walls takes openings by segment`() {
        val doc = built(
            """{"spec": 1, "mode": "plan", "title": "L",
              "walls": [ {"id": "w", "points": [["0","0"],["10'","0"],["10'","8'"]], "thickness": "6\""} ],
              "openings": [ {"id": "d", "type": "door", "on": "w", "segment": 1, "offset": "2'", "width": "30\"", "swing": "right", "hinge": "end"} ] }""",
        )
        val wall = doc.scene[ElementId("w")] as WallElement
        assertEquals(96.0, wall.thickness.value)
        assertTrue(!wall.closed)
        val door = doc.scene[ElementId("d")] as OpeningElement
        assertEquals(1, door.segment)
        assertTrue(door.flipSide && door.flipHinge)
        assertEquals(10 * 192.0, door.x.value + door.width.value / 2.0, 1e-6)
        assertEquals((2 * 12 + 15) * 16.0, door.y.value + door.height.value / 2.0, 1e-6)
    }

    @Test
    fun `build output is canonical, stable and valid`() {
        val a = bytes(built(room))
        val b = bytes(built(room))
        assertEquals(a.decodeToString(), b.decodeToString())
        val report = SketchValidate.validate(a)
        assertTrue(report.ok, report.render())
        assertTrue(report.info.any { "canonical: yes" in it }, report.render())
        val back = SheetCodecV2.decode(a).getOrNull() ?: error("decode")
        assertEquals(bytes(back).decodeToString(), a.decodeToString(), "a load and save changes nothing")
    }

    @Test
    fun `an opening that does not fit a wall says where and by how much`() {
        val errors = outcome(room.replace("\"offset\": \"10'\", \"width\": \"3'\"", "\"offset\": \"13'\", \"width\": \"3'\"")).issues.errors
        assertTrue(errors.any { "south wall of \"living\"" in it && "free" in it }, errors.toString())
        val plain = outcome(room.replace("\"as\": \"walls\", ", "")).issues.errors
        assertTrue(plain.any { "plain opening needs wall elements" in it }, plain.toString())
    }

    @Test
    fun `wall specs refuse a bad justification, repeated points and as on a diagram`() {
        fun errors(spec: String) = outcome(spec).issues.errors
        assertTrue(errors("""{"spec":1,"walls":[{"id":"w","points":[["0","0"],["5'","0"]],"justify":"left"}]}""").any { "inside, centre or outside" in it })
        assertTrue(errors("""{"spec":1,"walls":[{"id":"w","points":[["0","0"],["0","0"]]}]}""").any { "same place" in it })
        assertTrue(errors("""{"spec":1,"mode":"diagram","shapes":[{"id":"s","as":"walls","x":0,"y":0,"w":100,"h":50}]}""").any { "plan mode" in it })
    }

    @Test
    fun `describe reads walls and hosted openings with the clear area`() {
        val text = SketchDescribe.describe(built(room))
        assertTrue("wall (closed room)" in text && "clear area" in text, text)
        assertTrue("sq ft" in text, text)
        assertTrue("door" in text && "on living segment 3" in text, text)
    }

    @Test
    fun `render draws the walls, the openings, the wall lengths and the area`() {
        val svg = SketchRender.svg(built(room), RenderRequest(dimensions = true))
        assertTrue("<svg" in svg)
        assertTrue("158.4 sq ft" in svg, "the clear area of a 14' by 12' room on 4 1/2\" walls")
        assertTrue("14' 0&quot;" in svg || "14' 0\"" in svg, "the wall lengths")
        assertTrue(Regex("<path").findAll(svg).count() >= 4, "walls, door and window paths")
        val off = SketchRender.svg(built(room), RenderRequest(dimensions = false))
        assertTrue("sq ft" !in off, "no annotations when they are off")
    }

    @Test
    fun `validate checks host references and fit`() {
        val good = bytes(built(room)).decodeToString()
        val orphan = good.replace("\"host\":\"living\"", "\"host\":\"nowhere\"")
        val orphanReport = SketchValidate.validate(orphan.encodeToByteArray())
        assertTrue(orphanReport.warnings.any { "host \"nowhere\" does not exist" in it }, orphanReport.render())
        val notWall = good.replace("\"host\":\"living\"", "\"host\":\"door1\"")
        assertTrue(SketchValidate.validate(notWall.encodeToByteArray()).warnings.any { "host \"door1\" is not a (valid) wall" in it })
        val badSegment = good.replace("\"seg\":2", "\"seg\":9")
        assertTrue(SketchValidate.validate(badSegment.encodeToByteArray()).warnings.any { "is not a segment" in it })
        val tooWide = good.replace("\"w\":576", "\"w\":99999")
        assertTrue(SketchValidate.validate(tooWide.encodeToByteArray()).warnings.any { "does not fit inside segment" in it })
        val tooFew = good.replace("\"pts\":[[0,0],[2688,0],[2688,2304],[0,2304]]", "\"pts\":[[0,0]]")
        assertTrue(!SketchValidate.validate(tooFew.encodeToByteArray()).ok)
    }

    @Test
    fun `a hand written wall without derived footprint fields loads and is fitted`() {
        val text = """{"schema":2,"sheetUuid":"11111111-1111-4111-8111-111111111111","elements":[
          {"id":"w","type":"wall","pts":[[0,0],[300,0]],"t":8},
          {"id":"o","type":"opening","host":"w","off":260,"w":100}]}"""
        val report = SketchValidate.validate(text.encodeToByteArray())
        assertTrue(report.ok, report.render())
        assertTrue(report.warnings.any { "does not fit inside segment" in it }, report.render())
        val doc = SheetCodecV2.decode(text.encodeToByteArray()).getOrNull() ?: error("decode")
        val opening = assertNotNull(doc.scene[ElementId("o")] as? OpeningElement)
        val span = assertNotNull(WallGeometry.span(doc.scene[ElementId("w")] as WallElement, 0))
        assertEquals(span.hi, opening.offset.value + opening.width.value, 1e-9)
    }
}
