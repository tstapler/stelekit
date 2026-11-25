// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.cli

import arrow.core.Either
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.AddConstraint
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintTarget
import dev.stapler.stelekit.sketch.v2.constraints.TargetPart
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.provenance
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Epic E8: `sketch-cli edit` and `diff`: edits by id or name, audit trail, dry run, refusals, provenance. */
class SketchEditCliTest {
    private class Run(val code: Int, val out: String, val err: String)

    private fun cli(vararg args: String): Run {
        val out = StringBuilder()
        val err = StringBuilder()
        val code = SketchCli({ out.appendLine(it) }, { err.append(it) }).run(args.toList())
        return Run(code, out.toString(), err.toString())
    }

    private val dir: File = Files.createTempDirectory("sketch-edit-cli").toFile()

    private fun file(name: String, text: String) = File(dir, name).also { it.writeText(text) }

    private val spec = """{
      "spec": 1, "mode": "plan", "title": "Edit target",
      "shapes": [
        {"id": "kitchen", "x": "0", "y": "0", "w": "12'", "h": "10'", "label": "Kitchen"},
        {"id": "living", "as": "walls", "x": "20'", "y": "0", "w": "14'", "h": "12'"}
      ],
      "dimensions": [ {"id": "kw", "shape": "kitchen", "side": "north"} ]
    }"""

    private fun bytes(doc: SketchDocumentV2) = (SheetCodecV2.encode(doc) as Either.Right).value

    private fun sheet(name: String = "sheet.v2.json"): File {
        val doc = SketchSpecBuilder(Json.parseToJsonElement(spec) as JsonObject).build().doc ?: error("spec failed")
        return File(dir, name).also { it.writeBytes(bytes(doc)) }
    }

    private fun load(f: File): SketchDocumentV2 = (SheetCodecV2.decode(f.readBytes()) as Either.Right).value

    private fun edits(vararg json: String) = file("edits-${json.hashCode()}.json", """{"agent": "photo-agent", "edits": [${json.joinToString(",")}]}""")

    @Test
    fun dryRun_should_PrintTheDiffAndWriteNothing_When_EditsAreValid() {
        val sheet = sheet()
        val before = sheet.readBytes()
        val e = edits("""{"op":"move","id":"kitchen","dx":"1'"}""", """{"op":"relabel","id":"kitchen","text":"Galley"}""")
        val r = cli("edit", sheet.path, "--edits", e.path, "--dry-run")
        assertEquals(0, r.code, r.out)
        assertTrue(r.out.contains("dry run: nothing was written"), r.out)
        assertTrue(r.out.lines().any { it.startsWith("-") && it.contains("Kitchen") }, "the old label shows as a removed line:\n${r.out}")
        assertTrue(r.out.lines().any { it.startsWith("+") && it.contains("Galley") }, "the new label shows as an added line:\n${r.out}")
        assertTrue(before.contentEquals(sheet.readBytes()), "a dry run never touches the sheet")
    }

    @Test
    fun edit_should_ApplyEveryOp_WriteAValidSheet_AndMarkAgentWork() {
        val sheet = sheet()
        val out = File(dir, "edited.v2.json")
        val audit = File(dir, "audit.json")
        val e = edits(
            """{"op":"move","id":"kitchen","dx":"1'","confidence":80,"note":"shifted to match the photo"}""",
            """{"op":"resize","name":"kitchen","w":"13'"}""",
            """{"op":"relabel","id":"kitchen","text":"Galley"}""",
            """{"op":"add","id":"island","kind":"rect","x":"2'","y":"2'","w":"4'","h":"2'","confidence":70,"note":"read off the photo"}""",
            """{"op":"set-layer","id":"island","layer":"Furniture","create":true}""",
            """{"op":"add-wall","id":"pantry","points":[["30'","20'"],["40'","20'"]]}""",
            """{"op":"add-opening","id":"pantry-door","type":"door","on":"pantry","segment":0,"offset":"2'","width":"3'"}""",
            """{"op":"set-dimension","id":"kw","value":"13'"}""",
        )
        val r = cli("edit", sheet.path, "--edits", e.path, "--out", out.path, "--audit", audit.path, "--at", "2026-11-24T00:00:00Z")
        assertEquals(0, r.code, r.out)
        assertEquals(0, cli("validate", out.path).code, "the edited sheet validates")
        val doc = load(out)
        assertTrue(doc.scene[ElementId("island")] is RectElement)
        assertEquals("Furniture", doc.scene.layers.first { it.id == doc.scene[ElementId("island")]!!.base.layerId }.name)
        assertTrue(doc.scene[ElementId("pantry")] is WallElement)
        assertTrue(doc.scene[ElementId("pantry-door")] is OpeningElement)
        assertEquals("Galley", (doc.scene[ElementId("kitchen.label")] ?: doc.scene.elementsInOrder.first { it is TextElement && it.text == "Galley" }).let { (it as TextElement).text })
        assertEquals(13 * 192.0, (doc.scene[ElementId("kitchen")] as RectElement).width.value, 1e-6)
        val island = doc.scene[ElementId("island")]!!.base.provenance
        assertEquals(70, island?.confidence)
        assertEquals("read off the photo", island?.note)
        assertEquals("agent", island?.source)
        assertEquals("13' 0\"", (doc.scene[ElementId("kw.text")] as TextElement).text, "an authored dimension is re-laid and its label follows")
        val line = doc.scene[ElementId("kw.line")] as dev.stapler.stelekit.sketch.v2.scene.ArrowElement
        assertEquals(13 * 192.0, line.points.last().x.value - line.points.first().x.value, 1e-6)
        assertTrue(cli("describe", out.path).out.contains("[agent, needs review (70%): read off the photo]"))

        val trail = Json.parseToJsonElement(audit.readText()).jsonObject
        assertEquals("agent", (trail["source"] as JsonPrimitive).content)
        assertEquals("photo-agent", (trail["agent"] as JsonPrimitive).content)
        assertEquals("2026-11-24T00:00:00Z", (trail["at"] as JsonPrimitive).content)
        assertEquals(ContentHasher.sha256(sheet.readBytes()), (trail["sheetSha256Before"] as JsonPrimitive).content)
        assertEquals(ContentHasher.sha256(out.readBytes()), (trail["sheetSha256After"] as JsonPrimitive).content)
        assertEquals(8, trail["applied"]!!.jsonArray.size)
        assertEquals(0, (trail["refused"] as JsonArray).size)
        val added = trail["applied"]!!.jsonArray.first { it.jsonObject["op"].toString().contains("add\"") && it.jsonObject["target"].toString().contains("island") }.jsonObject
        assertEquals("null", added["before"]!!.jsonObject["island"].toString(), "an added element has no before")
        assertNotNull(added["after"]!!.jsonObject["island"]!!.jsonObject["provenance"])
    }

    @Test
    fun edit_should_RefuseAndWriteNothing_When_AnEditDoesNotFit() {
        val sheet = sheet()
        val before = sheet.readBytes()
        val out = File(dir, "never.v2.json")
        val e = edits(
            """{"op":"move","id":"kitchen","dx":"1'"}""",
            """{"op":"add-opening","id":"huge","type":"door","on":"living","wall":"north","segment":0,"offset":"1'","width":"20'"}""",
            """{"op":"delete","id":"no-such-thing"}""",
        )
        val r = cli("edit", sheet.path, "--edits", e.path, "--out", out.path)
        assertEquals(1, r.code, r.out)
        assertTrue(r.out.contains("REFUSED  [1] add-opening"), r.out)
        assertTrue(r.out.contains("REFUSED  [2] delete: no element with id or name \"no-such-thing\""), r.out)
        assertFalse(out.exists(), "nothing is written when any edit is refused")
        assertTrue(before.contentEquals(sheet.readBytes()))
        val partial = cli("edit", sheet.path, "--edits", e.path, "--out", out.path, "--partial")
        assertEquals(1, partial.code, "refusals still exit 1")
        assertTrue(out.exists(), "--partial keeps the edits that worked")
        assertEquals((load(sheet).scene[ElementId("kitchen")] as RectElement).x.value + 192.0, (load(out).scene[ElementId("kitchen")] as RectElement).x.value, 1e-6)
    }

    @Test
    fun edit_should_ReportTheConflict_When_AValueBreaksConstraints() {
        fun u(d: Double) = SceneUnit.of(d).getOrNull()!!
        val line = LineElement(ElementBase(ElementId("l")), listOf(ScenePoint(u(0.0), u(0.0)), ScenePoint(u(5.0), u(0.0))))
        fun on(part: TargetPart = TargetPart.Whole) = ConstraintTarget(ElementId("l"), part)
        fun c(id: String, kind: ConstraintKind, targets: List<ConstraintTarget>, value: Double? = null) = Constraint(ConstraintId(id), kind, targets, value)
        var doc = AddElements(listOf(line)).apply(SketchDocumentV2(sheetUuid = "constrained-1")).let { (it as Either.Right).value.doc }
        for (command: Command in listOf(
            AddConstraint(c("pin", ConstraintKind.Fixed, listOf(on(TargetPart.Start)))),
            AddConstraint(c("h", ConstraintKind.Horizontal, listOf(on()))),
            AddConstraint(c("d", ConstraintKind.Distance, listOf(on()), 5.0)),
            AddConstraint(c("d2", ConstraintKind.Distance, listOf(on(TargetPart.Start), on(TargetPart.End)), 5.0)),
        )) doc = (command.apply(doc) as Either.Right).value.doc
        val sheet = file("constrained.v2.json", "").also { it.writeBytes(bytes(doc)) }
        val r = cli("edit", sheet.path, "--edits", edits("""{"op":"set-dimension","id":"d","value":9}""").path, "--dry-run")
        assertEquals(1, r.code, r.out)
        assertTrue(r.out.contains("REFUSED  [0] set-dimension"), r.out)
        assertTrue(r.out.contains("conflicts: "), "the conflicting constraints are named:\n${r.out}")
        assertTrue(r.out.contains("d2") || r.out.contains("d,"), r.out)
        val ref = cli("edit", sheet.path, "--edits", edits("""{"op":"set-reference","id":"d2","reference":true}""").path, "--dry-run")
        assertEquals(0, ref.code, ref.out)
        assertTrue(ref.out.contains("ok       [0] set-reference d2"), ref.out)
        assertEquals(1, cli("edit", sheet.path, "--edits", edits("""{"op":"set-reference","id":"nope","reference":true}""").path, "--dry-run").code)
    }

    @Test
    fun edit_should_ResolveByNameAndRefuseAnAmbiguousOne() {
        val sheet = sheet()
        val named = cli(
            "edit", sheet.path, "--dry-run",
            "--edits", edits("""{"op":"add","id":"r9","name":"Pantry shelf","kind":"rect","x":"0","y":"0","w":"1'","h":"1'"}""", """{"op":"move","id":"Pantry shelf","dx":"1'"}""").path,
        )
        assertEquals(0, named.code, named.out)
        assertTrue(named.out.contains("ok       [1] move Pantry shelf -> r9"), "a name finds the element:\n${named.out}")
        val both = cli("edit", sheet.path, "--edits", edits("""{"op":"add","id":"kitchen2","kind":"rect","name":"kitchen","x":"0","y":"0","w":"1'","h":"1'"}""").path, "--dry-run")
        assertEquals(1, both.code, both.out)
        assertTrue(both.out.contains("the name \"kitchen\" is taken"), both.out)
    }

    @Test
    fun diff_should_ShowChangedLines_AndSayWhenSheetsMatch() {
        val a = sheet("a.v2.json")
        val b = File(dir, "b.v2.json")
        assertEquals(0, cli("edit", a.path, "--edits", edits("""{"op":"relabel","id":"kitchen","text":"Galley"}""").path, "--out", b.path).code)
        val same = cli("diff", a.path, a.path)
        assertEquals(0, same.code)
        assertEquals("no differences\n", same.out)
        val d = cli("diff", a.path, b.path)
        assertEquals(0, d.code, d.out)
        assertTrue(d.out.startsWith("--- ${a.path}\n+++ ${b.path}\n@@ "), d.out)
        assertTrue(d.out.contains("Kitchen") && d.out.contains("Galley"), d.out)
    }

    @Test
    fun unifiedDiff_should_MatchTheGolden_When_OneLineChanges() {
        val golden = "--- a\n+++ b\n@@ -1,6 +1,6 @@\n one\n two\n-three\n+THREE\n four\n five\n six\n"
        assertEquals(golden, SketchDiff.unified("one\ntwo\nthree\nfour\nfive\nsix\nseven", "one\ntwo\nTHREE\nfour\nfive\nsix\nseven", "a", "b"))
        assertEquals("", SketchDiff.unified("x\ny", "x\ny", "a", "b"))
    }

    @Test
    fun usage_should_ExplainWhatIsMissing() {
        val sheet = sheet()
        assertEquals(2, cli("edit", sheet.path, "--edits", edits("""{"op":"delete","id":"kitchen"}""").path).code, "no --out, --in-place or --dry-run")
        assertEquals(2, cli("edit", sheet.path, "--dry-run").code, "no --edits")
        assertEquals(2, cli("diff", sheet.path).code)
    }
}
