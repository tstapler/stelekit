// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

// jvmTest-reason: desktop-only (the CLI lives in jvmMain; the PNG case needs the desktop Skia raster)
package dev.stapler.stelekit.cli

import arrow.core.Either
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Goldens and behaviour of the agent-facing sketch CLI (`docs/reference/sketch-format-v2.md`); the property test is [SketchSpecRoundTripPropertyTest]. */
class SketchAgentCliTest {
    private fun outcome(spec: String): SpecOutcome = SketchSpecBuilder(Json.parseToJsonElement(spec) as JsonObject).build()

    private fun built(spec: String): SketchDocumentV2 {
        val o = outcome(spec)
        assertTrue(o.issues.errors.isEmpty(), "spec errors: ${o.issues.errors}")
        return assertNotNull(o.doc)
    }

    private fun encode(doc: SketchDocumentV2): ByteArray = (SheetCodecV2.encode(doc) as Either.Right).value

    private class Run(val code: Int, val out: String, val err: String)

    private fun cli(vararg args: String): Run {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = SketchCli({ out.appendLine(it) }, { err.append(it) }).run(args.toList())
        return Run(code, out.toString(), err.toString())
    }

    private fun tempDir(): File = Files.createTempDirectory("sketch-agent-cli").toFile()

    @Test
    fun `room spec builds the golden sheet`() {
        val doc = built(ROOM_SPEC)
        val bytes = encode(doc)
        assertEquals(17, doc.scene.size)
        assertEquals(ROOM_SHA256, ContentHasher.sha256(bytes))
        assertTrue(bytes.decodeToString().startsWith("{\"schema\":2,\"sheetUuid\":\""), "the header probe needs schema first")
    }

    @Test
    fun `room describe matches the golden text`() {
        assertEquals(ROOM_DESCRIBE, SketchDescribe.describe(built(ROOM_SPEC)))
    }

    @Test
    fun `diagram renders the golden svg`() {
        assertEquals(DIAGRAM_SVG, SketchRender.svg(built(DIAGRAM_SPEC), RenderRequest()))
    }

    @Test
    fun `build then encode then decode gives the same scene and canonical bytes`() {
        for (spec in listOf(ROOM_SPEC, DIAGRAM_SPEC, PHOTO_SPEC)) {
            val doc = built(spec)
            val bytes = encode(doc)
            val decoded = (SheetCodecV2.decode(bytes) as Either.Right).value
            assertEquals(doc.scene, decoded.scene)
            assertEquals(doc.extras, decoded.extras)
            assertEquals(doc.nameCounters, decoded.nameCounters)
            assertTrue(bytes.contentEquals(encode(decoded)), "re-encoding must reproduce the same bytes")
        }
    }

    @Test
    fun `rebuilding the same spec is byte identical`() {
        assertTrue(encode(built(ROOM_SPEC)).contentEquals(encode(built(ROOM_SPEC))))
    }

    @Test
    fun `hand written sheet validates with a canonical warning and canonicalizes clean`() {
        val dir = tempDir()
        try {
            val file = File(dir, "hand.json").apply { writeText(HAND_WRITTEN) }
            val before = SketchValidate.validate(file.readBytes())
            assertTrue(before.ok, before.render())
            assertTrue(before.warnings.any { "not canonical" in it }, before.render())
            val run = cli("canonicalize", file.path, "--in-place")
            assertEquals(0, run.code, run.out + run.err)
            assertTrue("canonical sha256" in run.err)
            val after = SketchValidate.validate(file.readBytes())
            assertTrue(after.ok && after.warnings.isEmpty(), after.render())
            assertTrue(after.info.any { "canonical: yes, sha256 ${ContentHasher.sha256(file.readBytes())}" in it })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `validate names the element id line and fix for a bad sheet`() {
        val report = SketchValidate.validate(BAD_SHEET.encodeToByteArray())
        val text = report.render()
        assertFalse(report.ok)
        assertTrue("elements[1] (id \"b\"), line 3: \"w\" is missing" in text, text)
        assertTrue("unknown key \"width\" is dropped on load (did you mean \"w\"?)" in text, text)
        assertTrue("\"ta\" is \"center\"; it must be one of Left, Center, Right" in text, text)
        assertFalse("elements[0]" in text, "a valid element must not be flagged: $text")
        assertTrue("container \"ghost\" does not exist" in text, text)
        assertTrue(text.trimEnd().endsWith("RESULT: FAILED (3 errors)"), text)
    }

    @Test
    fun `validate reports line and column of a syntax error`() {
        val report = SketchValidate.validate("{\"schema\": 2,\n \"sheetUuid\": \"x\",\n \"elements\": [ {,} ]}".encodeToByteArray())
        assertFalse(report.ok)
        assertTrue(report.errors.single().startsWith("invalid JSON at line "), report.errors.toString())
    }

    @Test
    fun `spec errors name the json path`() {
        val o = outcome(
            """{"spec":1,"shapes":[{"id":"a","x":"nope","y":0,"w":"10'","h":"10'"},{"id":"a","x":0,"y":0,"w":-5,"h":10}],
              "arrows":[{"id":"r1","from":"a","to":"zzz"}]}""",
        )
        val errors = o.issues.errors
        assertTrue(errors.any { it.startsWith("shapes[0].x: \"nope\" is not a length") }, errors.toString())
        assertTrue(errors.any { it.startsWith("shapes[1].w:") || it.startsWith("shapes[1].id: the id \"a\" is used twice") }, errors.toString())
        assertTrue(errors.any { it.startsWith("arrows[0].from: unknown shape \"a\"") }, errors.toString())
        assertEquals(null, o.doc)
    }

    @Test
    fun `an opening that does not fit its wall says by how much`() {
        val o = outcome(
            """{"spec":1,"shapes":[{"id":"r","x":0,"y":0,"w":"10'","h":"8'"}],
              "openings":[{"id":"d","type":"door","on":"r","wall":"north","offset":"9'","width":"3'"}]}""",
        )
        assertTrue(o.issues.errors.single().contains("the north wall of \"r\" is only 10' 0\" long"), o.issues.errors.toString())
    }

    @Test
    fun `photo calibration turns pixels into real lengths and flags disagreeing references`() {
        val doc = built(PHOTO_SPEC)
        val kitchen = doc.scene.elementsInOrder.first { it.id.value == "kitchen" } as dev.stapler.stelekit.sketch.v2.scene.RectElement
        assertEquals(12 * 192.0, kitchen.width.value, 0.001)
        assertEquals(8 * 192.0, kitchen.height.value, 0.001)
        val skewed = outcome(
            """{"spec":1,"space":"photo","calibration":{"refs":[{"a":[0,0],"b":[100,0],"length":"3'"},{"a":[0,0],"b":[0,100],"length":"4'"}]},
              "shapes":[{"id":"a","x":0,"y":0,"w":100,"h":100}]}""",
        )
        assertTrue(skewed.issues.warnings.any { "references disagree by" in it }, skewed.issues.warnings.toString())
        val missing = outcome("""{"spec":1,"space":"photo","shapes":[{"id":"a","x":0,"y":0,"w":100,"h":100}]}""")
        assertTrue(missing.issues.errors.any { it.startsWith("calibration.refs: photo space needs at least one reference") })
    }

    @Test
    fun `excalidraw export and import round trip the element count`() {
        val dir = tempDir()
        try {
            val sheet = File(dir, "d.v2.json").apply { writeBytes(encode(built(DIAGRAM_SPEC))) }
            val ex = File(dir, "d.excalidraw")
            assertEquals(0, cli("to-excalidraw", sheet.path, "--out", ex.path).code)
            val back = File(dir, "back.v2.json")
            val run = cli("from-excalidraw", ex.path, "--out", back.path)
            assertEquals(0, run.code, run.out)
            val original = (SheetCodecV2.decode(sheet.readBytes()) as Either.Right).value
            val restored = (SheetCodecV2.decode(back.readBytes()) as Either.Right).value
            assertEquals(original.scene.size, restored.scene.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `place writes a bundle the app's sidecar reader accepts and replaces on a second run`() {
        val dir = tempDir()
        try {
            val sheetBytes = encode(built(ROOM_SPEC))
            val first = (SketchBundle.place(sheetBytes, dir, null, null, 1L) as Either.Right).value
            assertFalse(first.replaced)
            assertTrue(Regex("!\\[sketch]\\(\\.\\./assets/stelekit-sketch/proj-[0-9a-f]{6}/preview\\.svg\\)").matches(first.embed), first.embed)
            val bundleDir = File(dir, "assets/stelekit-sketch/${first.slug}")
            val files = HashMap<String, ByteArray>()
            files[SidecarCodec.MODEL_PATH] = File(bundleDir, SidecarCodec.MODEL_PATH).readBytes()
            File(bundleDir, "sheets").listFiles()!!.forEach { files["sheets/${it.name}"] = it.readBytes() }
            val decoded = (SidecarCodec.decode(files) as Either.Right).value
            assertEquals(1, decoded.sheets.size)
            assertEquals(2L, decoded.sheets.single().schemaVersion)
            assertTrue(File(bundleDir, "preview.svg").readText().startsWith("<svg"))
            val second = (SketchBundle.place(sheetBytes, dir, first.slug, null, 2L) as Either.Right).value
            assertTrue(second.replaced)
            assertEquals(1, File(bundleDir, "sheets").listFiles()!!.size)
            val other = (SketchBundle.place(encode(built(DIAGRAM_SPEC)), dir, first.slug, null, 3L) as Either.Right).value
            assertFalse(other.replaced)
            assertEquals(2, File(bundleDir, "sheets").listFiles()!!.size, "a second sheet joins the bundle named by --slug")
            val refused = SketchBundle.place(sheetBytes, File(dir, "missing"), null, null, 4L)
            assertTrue(refused is Either.Left && "not a directory" in refused.value, refused.toString())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `png render draws ink and honours the requested width`() {
        val doc = built(ROOM_SPEC)
        val bytes = assertNotNull(SketchRender.png(doc, RenderRequest(width = 800)))
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(800, image.width)
        var dark = 0
        for (y in 0 until image.height step 4) for (x in 0 until image.width step 4) if ((image.getRGB(x, y) and 0xFF) < 100) dark++
        assertTrue(dark > 200, "expected walls and text to leave ink, found $dark sampled dark pixels")
        val withDims = assertNotNull(SketchRender.png(doc, RenderRequest(width = 800, dimensions = true)))
        assertFalse(bytes.contentEquals(withDims), "--dimensions must change the picture")
    }

    @Test
    fun `svg width option keeps the aspect`() {
        val svg = SketchRender.svg(built(DIAGRAM_SPEC), RenderRequest(width = 400))
        assertTrue(Regex("width=\"400\" height=\"[0-9.]+\"").containsMatchIn(svg.lineSequence().first()), svg.lineSequence().first())
    }

    @Test
    fun `cli reports usage problems and success through exit codes`() {
        val dir = tempDir()
        try {
            val spec = File(dir, "room.spec.json").apply { writeText(ROOM_SPEC) }
            assertEquals(2, cli().code)
            assertEquals(2, cli("nope").code)
            assertEquals(2, cli("render", File(dir, "missing.json").path, "--out", File(dir, "x.svg").path).code)
            val built = cli("build", spec.path, "--out", File(dir, "room.v2.json").path, "--render", File(dir, "room.svg").path)
            assertEquals(0, built.code, built.out)
            assertTrue(File(dir, "room.svg").readText().contains("<svg"))
            assertEquals(0, cli("validate", File(dir, "room.v2.json").path).code)
            assertEquals(1, cli("validate", File(dir, "room.spec.json").also { it.writeText("""{"spec":2}""") }.path).code)
            val described = cli("describe", File(dir, "room.v2.json").path)
            assertEquals(ROOM_DESCRIBE, described.out)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `json schema and validator agree on the element types and keys`() {
        val schemaFile = listOf("docs/reference/sketch-scene-v2.schema.json", "../docs/reference/sketch-scene-v2.schema.json").map(::File).firstOrNull { it.isFile }
            ?: return // Bazel's sandbox does not carry docs/; the Gradle run is the check.
        val defs = ((Json.parseToJsonElement(schemaFile.readText()) as JsonObject)["\$defs"] as JsonObject)
        val oneOf = ((defs["element"] as JsonObject)["oneOf"] as JsonArray).map { ((it as JsonObject)["\$ref"] as JsonPrimitive).content.substringAfterLast('/') }
        val keys = SketchValidate.elementKeys()
        assertEquals(keys.keys, oneOf.toSet(), "schema element types must equal the validator's")
        for ((type, allowed) in keys) {
            val def = defs[type] as JsonObject
            val refs = (def["allOf"] as JsonArray).map { ((it as JsonObject)["\$ref"] as JsonPrimitive).content.substringAfterLast('/') }
            val expected = propertyKeys(def) + propertyKeys(defs["base"] as JsonObject) + (if ("box" in refs) propertyKeys(defs["box"] as JsonObject) else emptySet())
            assertEquals(allowed, expected, "keys of $type differ between the schema and the validator")
        }
    }

    private fun propertyKeys(o: JsonObject): Set<String> = (o["properties"] as? JsonObject)?.keys ?: emptySet()

    private companion object {
        const val ROOM_SHA256 = "6ea8a2a133347425ae8231700dee0414d81cf8ce3767ce317484fc40f9c4f1cc"

        val ROOM_SPEC = """{
  "spec": 1,
  "mode": "plan",
  "title": "Living room",
  "shapes": [
    {"id": "living", "x": "0", "y": "0", "w": "14'", "h": "12'", "label": "Living room"},
    {"id": "sofa", "role": "furniture", "x": "1'", "y": "8'", "w": "7'", "h": "3'", "label": "Sofa"}
  ],
  "openings": [
    {"id": "door1", "type": "door", "on": "living", "wall": "south", "offset": "10'", "width": "3'"},
    {"id": "win1", "type": "window", "on": "living", "wall": "north", "offset": "4'", "width": "5'"}
  ],
  "dimensions": [
    {"shape": "living", "side": "north", "offset": "30\""},
    {"shape": "living", "side": "east", "offset": "30\""}
  ]
}
"""

        val DIAGRAM_SPEC = """{
  "spec": 1,
  "mode": "diagram",
  "title": "Request path",
  "shapes": [
    {"id": "web", "x": 0, "y": 0, "w": 160, "h": 70, "label": "Web app", "fill": "lightblue"},
    {"id": "api", "rightOf": "web", "gap": 120, "w": 160, "h": 70, "label": "API", "fill": "lightblue"},
    {"id": "db", "kind": "ellipse", "rightOf": "api", "gap": 120, "w": 160, "h": 90, "label": "Database", "fill": "lightyellow"},
    {"id": "cache", "below": "api", "gap": 80, "w": 160, "h": 70, "label": "Cache"}
  ],
  "arrows": [
    {"id": "a1", "from": "web", "to": "api", "label": "HTTPS"},
    {"id": "a2", "from": "api", "to": "db", "label": "SQL"},
    {"id": "a3", "from": "api", "to": "cache", "head": "both", "dashed": true}
  ]
}
"""

        val PHOTO_SPEC = """{
  "spec": 1,
  "mode": "plan",
  "title": "Kitchen from photo",
  "space": "photo",
  "calibration": {
    "refs": [
      {"a": [112, 640], "b": [412, 640], "length": "3'"},
      {"a": [900, 120], "b": [900, 420], "length": "36\""}
    ],
    "snap": "1\""
  },
  "shapes": [
    {"id": "kitchen", "x": 100, "y": 100, "w": 1200, "h": 800, "label": "Kitchen"},
    {"id": "island", "role": "furniture", "x": 500, "y": 400, "w": 400, "h": 150, "label": "Island"}
  ],
  "openings": [
    {"id": "door1", "type": "door", "on": "kitchen", "wall": "south", "offset": 150, "width": 300}
  ],
  "dimensions": [
    {"shape": "kitchen", "side": "north"},
    {"shape": "kitchen", "side": "east"}
  ]
}
"""

        val ROOM_DESCRIBE = """SKETCH SHEET 96bd0e9a-1920-45e1-8bc5-f55ca59274b2 (schema 2), title "Living room"
Units: 1 scene unit = 1/16 inch (192 units per foot). x grows east (right), y grows south (down).
Extent (shapes, lines and text; not openings or dimension marks): 14' 4 1/2" wide x 12' 4 1/2" tall, from (-2 1/4", -2 1/4") to (14' 2 1/4", 12' 2 1/4").
Layers: default "Plan" (visible, 4 elements); openings "Openings" (visible, 5 elements); dimensions "Dimensions" (visible, 8 elements)

ELEMENTS (2, in paint order)
- living: rectangle 14' 0" x 12' 0", top-left (0", 0"), centre (7' 0", 6' 0"), label "Living room", line 4 1/2"
- sofa: rectangle 7' 0" x 3' 0", top-left (1' 0", 8' 0"), centre (4' 6", 9' 6"), label "Sofa", line 1"

OPENINGS (doors and windows; drawn as gaps in walls)
- door1: door 3' 0" wide, on the south wall of living, starting 10' 0" from the west end
- win1: window 5' 0" wide, on the north wall of living, starting 4' 0" from the west end

DIMENSIONS (authored annotations)
- dim1: "14' 0"" between (0", -2' 6") and (14' 0", -2' 6"), measures 14' 0"
- dim2: "12' 0"" between (16' 6", 0") and (16' 6", 12' 0"), measures 12' 0"

RELATIONSHIPS
- living contains sofa

MAP (coarse; north is up, each cell is about 2 5/8" wide; labels are truncated to fit)
+--------------------------------------------------------------+
|                                                              |
|                                                              |
|                                                              |
|                                                              |
|                                                              |
|                                                              |
|                                                              |
|                                                              |
|                                                              |
|                                                              |
|                                                              |
|                                                              |
|                         Living room                          |
|                                                              |
|                                                              |
|                                                              |
|    +------------------------------+                          |
|    |                              |                          |
|    |                              |                          |
|    |             Sofa             |                          |
|    |                              |                          |
|    |                              |                          |
|    |                              |                          |
|    +------------------------------+                          |
|                                                              |
+--------------------------------------------------------------+
"""

        val DIAGRAM_SVG = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" version="1.1" width="779.76" height="289.76" viewBox="-29.88 -39.88 779.76 289.76">
<rect x="-29.88" y="-39.88" width="779.76" height="289.76" fill="#ffffff"/>
<path d="M0 0 L160 0 L160 70 L0 70 Z" fill="#a5d8ff" stroke="#1e1e1e" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
<text font-family="Virgil, Segoe UI Emoji" font-size="20" fill="#1e1e1e" text-anchor="middle" xml:space="preserve"><tspan x="80" y="38.5">Web app</tspan></text>
<path d="M280 0 L440 0 L440 70 L280 70 Z" fill="#a5d8ff" stroke="#1e1e1e" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
<text font-family="Virgil, Segoe UI Emoji" font-size="20" fill="#1e1e1e" text-anchor="middle" xml:space="preserve"><tspan x="360" y="38.5">API</tspan></text>
<path d="M720 35 C720 59.8528 684.1828 80 640 80 C595.8172 80 560 59.8528 560 35 C560 10.1472 595.8172 -10 640 -10 C684.1828 -10 720 10.1472 720 35 Z" fill="#ffec99" stroke="#1e1e1e" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
<text font-family="Virgil, Segoe UI Emoji" font-size="20" fill="#1e1e1e" text-anchor="middle" xml:space="preserve"><tspan x="640" y="38.5">Database</tspan></text>
<path d="M280 150 L440 150 L440 220 L280 220 Z" fill="none" stroke="#1e1e1e" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
<text font-family="Virgil, Segoe UI Emoji" font-size="20" fill="#1e1e1e" text-anchor="middle" xml:space="preserve"><tspan x="360" y="188.5">Cache</tspan></text>
<path d="M160 35 L280 35" fill="none" stroke="#1e1e1e" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
<path d="M256.5077 43.5505 L280 35 L256.5077 26.4495" fill="none" stroke="#1e1e1e" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
<text font-family="Virgil, Segoe UI Emoji" font-size="14" fill="#1e1e1e" text-anchor="middle" xml:space="preserve"><tspan x="220" y="24.85">HTTPS</tspan></text>
<path d="M440 35 L560 35" fill="none" stroke="#1e1e1e" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
<path d="M536.5077 43.5505 L560 35 L536.5077 26.4495" fill="none" stroke="#1e1e1e" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
<text font-family="Virgil, Segoe UI Emoji" font-size="14" fill="#1e1e1e" text-anchor="middle" xml:space="preserve"><tspan x="500" y="24.85">SQL</tspan></text>
<path d="M360 70 L360 150" fill="none" stroke="#1e1e1e" stroke-width="2" stroke-dasharray="8 10" stroke-linecap="round" stroke-linejoin="round"/>
<path d="M351.4495 126.5077 L360 150 L368.5505 126.5077" fill="none" stroke="#1e1e1e" stroke-width="2" stroke-dasharray="8 10" stroke-linecap="round" stroke-linejoin="round"/>
<path d="M368.5505 93.4923 L360 70 L351.4495 93.4923" fill="none" stroke="#1e1e1e" stroke-width="2" stroke-dasharray="8 10" stroke-linecap="round" stroke-linejoin="round"/>
</svg>
"""

        val HAND_WRITTEN = """{"elements": [
 {"type": "rect", "id": "a", "x": 0, "y": 0, "w": 160, "h": 96, "style": {"stroke": "#1e1e1e"}},
 {"type": "text", "id": "t", "x": 10, "y": 10, "w": 60, "h": 25, "text": "Hi", "container": "a"}
], "sheetUuid": "11111111-1111-4111-8111-111111111111", "schema": 2}
"""

        val BAD_SHEET = """{"schema":2,"sheetUuid":"11111111-1111-4111-8111-111111111111","elements":[
{"id":"a","type":"rect","x":0,"y":0,"w":160,"h":96},
{"id":"b","type":"rect","x":0,"y":0,"width":160,"height":96},
{"id":"t","type":"text","x":0,"y":0,"w":50,"h":25,"text":"Hi","ta":"center","container":"ghost"}
]}"""
    }
}
