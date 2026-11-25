// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:Suppress("MagicNumber", "ReturnCount", "TooManyFunctions", "LongMethod", "CyclomaticComplexMethod")

package dev.stapler.stelekit.cli

import arrow.core.Either
import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.export.DrawingScale
import dev.stapler.stelekit.sketch.v2.export.Micrometers
import dev.stapler.stelekit.sketch.v2.export.Orientation
import dev.stapler.stelekit.sketch.v2.export.PaperSize
import dev.stapler.stelekit.sketch.v2.export.SheetPlanner
import dev.stapler.stelekit.sketch.v2.export.TitleBlock
import dev.stapler.stelekit.sketch.v2.importer.ExcalidrawExporter
import dev.stapler.stelekit.sketch.v2.image.ImageHeaderProbe
import dev.stapler.stelekit.sketch.v2.importer.ExcalidrawImporter
import dev.stapler.stelekit.sketch.v2.scene.underlay
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.system.exitProcess

/**
 * `sketch-cli`: headless tools for reading, rendering and authoring SteleKit v2 sketch sheets without the app. Everything
 * runs through the app's own codec, renderer and sidecar writer. Output goes to [out] (reports) and [err] (usage problems);
 * exit code 0 is success, 1 means the input has errors the caller should fix, 2 means a usage or file problem.
 */
internal class SketchCli(private val out: (String) -> Unit, private val err: (String) -> Unit) {
    fun run(args: List<String>): Int {
        val command = args.firstOrNull()
        if (command == null || command in setOf("help", "--help", "-h")) {
            out(USAGE)
            return if (command == null) EXIT_USAGE else 0
        }
        val parsed = Parsed.of(args.drop(1)) ?: return usage("could not read the arguments; flags are --name value pairs")
        return try {
            when (command) {
                "build" -> build(parsed)
                "render" -> render(parsed)
                "validate" -> validate(parsed)
                "canonicalize" -> canonicalize(parsed)
                "describe" -> describe(parsed)
                "from-excalidraw" -> fromExcalidraw(parsed)
                "to-excalidraw" -> toExcalidraw(parsed)
                "place" -> place(parsed)
                "edit" -> edit(parsed)
                "diff" -> diff(parsed)
                else -> usage("unknown command \"$command\"")
            }
        } catch (e: UsageProblem) {
            usage(e.message.orEmpty())
        } catch (e: java.io.IOException) {
            err("sketch-cli: file problem: ${e.message}\n")
            EXIT_USAGE
        }
    }

    private class UsageProblem(message: String) : RuntimeException(message)

    private class Parsed(val positional: List<String>, val flags: Map<String, String>) {
        fun flag(name: String): String? = flags[name]

        fun has(name: String): Boolean = name in flags

        fun input(): File = File(positional.firstOrNull() ?: throw UsageProblem("give the input file"))

        companion object {
            private val SWITCHES = setOf("dimensions", "no-dimensions", "in-place", "no-scale-bar", "tile", "dry-run", "partial")

            fun of(args: List<String>): Parsed? {
                val positional = ArrayList<String>()
                val flags = HashMap<String, String>()
                var i = 0
                while (i < args.size) {
                    val a = args[i]
                    if (a.startsWith("--")) {
                        val name = a.removePrefix("--")
                        if (name in SWITCHES) flags[name] = "true" else {
                            val v = args.getOrNull(i + 1) ?: return null
                            flags[name] = v
                            i++
                        }
                    } else positional += a
                    i++
                }
                return Parsed(positional, flags)
            }
        }
    }

    private fun usage(message: String): Int {
        err("sketch-cli: $message\n")
        err(USAGE)
        return EXIT_USAGE
    }

    private fun read(file: File): ByteArray {
        if (!file.isFile) throw UsageProblem("no such file: ${file.path}")
        return file.readBytes()
    }

    // Loading any supported input as a document

    private sealed interface Loaded {
        class Doc(val doc: SketchDocumentV2, val notes: List<String>) : Loaded

        class Failed(val report: String) : Loaded
    }

    private fun load(file: File): Loaded {
        val bytes = read(file)
        val root = try {
            Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (e: Exception) {
            null
        }
        return when {
            root == null || (root["schema"] == null && root["spec"] == null && (root["type"] as? JsonPrimitive)?.content != "excalidraw") ->
                Loaded.Failed(SketchValidate.validate(bytes).render())
            root["spec"] != null && root["schema"] == null -> {
                val outcome = SketchSpecBuilder(root, file.absoluteFile.parentFile).build()
                outcome.doc?.let { Loaded.Doc(it, outcome.issues.warnings.map { w -> "warning: $w" } + outcome.issues.notes) }
                    ?: Loaded.Failed(specReport(outcome))
            }
            (root["type"] as? JsonPrimitive)?.content == "excalidraw" -> fromExcalidrawBytes(bytes)
            else -> when (val decoded = SheetCodecV2.decode(bytes)) {
                is Either.Right -> Loaded.Doc(decoded.value, emptyList())
                is Either.Left -> Loaded.Failed(SketchValidate.validate(bytes).render())
            }
        }
    }

    private fun specReport(outcome: SpecOutcome): String = buildString {
        outcome.issues.errors.forEach { appendLine("ERROR   $it") }
        outcome.issues.warnings.forEach { appendLine("WARNING $it") }
        appendLine("RESULT: FAILED (${outcome.issues.errors.size} errors)")
    }

    private fun fromExcalidrawBytes(bytes: ByteArray): Loaded {
        val imported = runBlocking { ExcalidrawImporter().import(ImportSource.ofBytes(bytes)) }
        val scene = (imported as? Either.Right)?.value ?: return Loaded.Failed("ERROR   the importer refused the file: ${(imported as Either.Left).value}\n")
        val uuid = uuidFrom(ContentHasher.sha256(bytes))
        val applied = scene.command().apply(SketchDocumentV2(sheetUuid = uuid))
        val doc = (applied as? Either.Right)?.value?.doc ?: return Loaded.Failed("ERROR   the imported elements could not be added to a sheet\n")
        return Loaded.Doc(doc, listOf(scene.report.summary()))
    }

    private fun uuidFrom(hex: String) = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-4${hex.substring(13, 16)}-8${hex.substring(17, 20)}-${hex.substring(20, 32)}"

    private fun docOrReport(file: File): SketchDocumentV2? = when (val l = load(file)) {
        is Loaded.Doc -> {
            l.notes.forEach { out("note: $it") }
            l.doc
        }
        is Loaded.Failed -> {
            out(l.report)
            null
        }
    }

    // Commands

    private fun build(p: Parsed): Int {
        val file = p.input()
        val root = try { Json.parseToJsonElement(read(file).decodeToString()) as? JsonObject } catch (e: Exception) { null }
        if (root == null) {
            out(SketchValidate.validate(read(file)).render())
            return 1
        }
        val outPath = p.flag("out") ?: throw UsageProblem("build needs --out <sheet.v2.json>")
        val outcome = SketchSpecBuilder(root, file.absoluteFile.parentFile).build()
        outcome.issues.warnings.forEach { out("WARNING $it") }
        outcome.issues.notes.forEach { out("INFO    $it") }
        val doc = outcome.doc
        if (doc == null) {
            out(specReport(outcome).lines().filter { it.startsWith("ERROR") || it.startsWith("RESULT") }.joinToString("\n"))
            return 1
        }
        val bytes = (SheetCodecV2.encode(doc) as? Either.Right)?.value ?: run { out("ERROR   the built sheet could not be encoded"); return 1 }
        File(outPath).also { it.absoluteFile.parentFile?.mkdirs() }.writeBytes(bytes)
        out("wrote $outPath (${bytes.size} bytes, ${doc.scene.size} elements, sha256 ${ContentHasher.sha256(bytes)})")
        p.flag("render")?.let { renderTo(doc, File(it), requestOf(p)) }
        return 0
    }

    private fun requestOf(p: Parsed) = RenderRequest(
        dimensions = when {
            p.has("dimensions") && p.has("no-dimensions") -> throw UsageProblem("--dimensions and --no-dimensions cannot be combined")
            p.has("dimensions") -> true
            p.has("no-dimensions") -> false
            else -> null
        },
        width = p.flag("width")?.let { it.toIntOrNull()?.takeIf { w -> w in 16..16_000 } ?: throw UsageProblem("--width must be a whole number of pixels from 16 to 16000") },
        style = when (p.flag("style") ?: "clean") {
            "clean" -> RenderStyle.Clean
            "sketchy" -> RenderStyle.Sketchy
            else -> throw UsageProblem("--style must be clean or sketchy")
        },
        background = when (val b = p.flag("background") ?: "#ffffff") {
            "transparent" -> null
            else -> b.takeIf { HEX_COLOR.matches(it) } ?: throw UsageProblem("--background must be #rrggbb or transparent")
        },
        underlay = p.flag("underlay")?.let { photoOf(File(it)) },
        sheet = flagsOf(p),
    )

    private val pageFlags = listOf("paper", "scale", "orientation", "margin", "north", "title", "author", "date", "sheet-number", "notes", "dpi", "no-scale-bar", "tile")

    /** The page flags, or null when none was given (a PDF supplies defaults itself). */
    private fun flagsOf(p: Parsed): SheetFlags? {
        if (pageFlags.none { p.has(it) } && p.flag("format")?.lowercase() != "pdf") return null
        val title = listOf("title", "author", "date", "sheet-number", "notes").takeIf { names -> names.any { p.has(it) } }?.let {
            TitleBlock(p.flag("title").orEmpty(), p.flag("author").orEmpty(), p.flag("date").orEmpty(), p.flag("sheet-number").orEmpty(), p.flag("notes").orEmpty())
        }
        return SheetFlags(
            paper = p.flag("paper")?.let { PaperSize.parse(it) ?: throw UsageProblem("--paper must be Letter, A4, A3, Tabloid or WxHmm / WxHin (for example 300x200mm)") },
            orientation = p.flag("orientation")?.let {
                when (it.lowercase()) {
                    "portrait" -> Orientation.Portrait
                    "landscape" -> Orientation.Landscape
                    else -> throw UsageProblem("--orientation must be portrait or landscape")
                }
            },
            scale = p.flag("scale")?.let { DrawingScale.parse(it) ?: throw UsageProblem("--scale must be a ratio like 1:50 or 2:1, or 1/4\" = 1'") },
            margin = p.flag("margin")?.let { Micrometers.parseWithUnit(it) ?: throw UsageProblem("--margin must be a length like 0.5in or 10mm") },
            scaleBar = if (p.has("no-scale-bar")) false else null,
            north = p.flag("north")?.let { it.toIntOrNull() ?: throw UsageProblem("--north must be a whole number of degrees clockwise from up") },
            title = title,
            dpi = p.flag("dpi")?.let { it.toIntOrNull()?.takeIf { d -> d in 36..1200 } ?: throw UsageProblem("--dpi must be a whole number from 36 to 1200") } ?: SheetFlags.DEFAULT_DPI,
            tiled = p.has("tile"),
        )
    }

    private fun photoOf(file: File): UnderlayImage {
        val bytes = read(file)
        val header = (ImageHeaderProbe.probe(bytes) as? Either.Right)?.value ?: throw UsageProblem("--underlay ${file.path} is not a PNG, JPEG, GIF or WebP image")
        return UnderlayImage(ContentHasher.sha256(bytes), header.format.mime, bytes)
    }

    /** True when the photo given to `--underlay` is the one the sheet's underlay names; says why not otherwise. */
    private fun photoMatches(doc: SketchDocumentV2, request: RenderRequest): Boolean {
        val photo = request.underlay ?: return true
        val state = doc.scene.underlay
        if (state == null) {
            out("WARNING the sheet has no underlay; --underlay is ignored")
            return true
        }
        if (state.element.fileId == photo.fileId) return true
        out("ERROR   --underlay is not the sheet's photo: its sha256 is ${photo.fileId}, the underlay names ${state.element.fileId}")
        return false
    }

    private fun renderTo(doc: SketchDocumentV2, target: File, request: RenderRequest, format: String = target.extension.lowercase()): Int {
        if (!photoMatches(doc, request)) return 1
        target.absoluteFile.parentFile?.mkdirs()
        val flags = request.sheet ?: SheetFlags().takeIf { format == "pdf" }
        if (flags != null) {
            val print = SketchRender.print(doc, request, flags)
            if (print == null) {
                out("ERROR   the sheet is empty; nothing to put on a page")
                return 1
            }
            val tiling = flags.tiled && format == "pdf"
            if (tiling && print.plan.tooManyPages) {
                out("ERROR   tiling would take ${print.plan.pageCount} pages (the most is ${SheetPlanner.PAGE_BUDGET}); use a smaller --scale or bigger --paper")
                return 1
            }
            val setup = print.plan.setup
            out("page ${setup.paper.label} ${setup.orientation.name.lowercase()}, scale ${setup.scale.ratio}, ${if (tiling) "${print.plan.pageCount} page(s)" else "1 page"}")
            if (!print.plan.fits && !tiling) out("WARNING the drawing does not fit one page at ${setup.scale.ratio} and is cut off at the margins; use a smaller --scale${if (format == "pdf") " or --tile" else ""}")
        }
        when (format) {
            "pdf" -> target.writeBytes(SketchRender.pdf(doc, request, flags ?: SheetFlags(), target.nameWithoutExtension) ?: return 1)
            "svg" -> target.writeText(
                (if (flags != null) SketchRender.pageSvg(doc, request, flags) else SketchRender.svg(doc, request)) ?: run {
                    out("ERROR   the sheet is empty; nothing to put on a page")
                    return 1
                },
            )
            "png" -> {
                val bytes = if (flags != null) SketchRender.pagePng(doc, request, flags) else SketchRender.png(doc, request)
                if (bytes == null) {
                    out("ERROR   the PNG could not be produced (empty sheet, or larger than the PNG size cap); use an .svg or a smaller --width")
                    return 1
                }
                target.writeBytes(bytes)
            }
            else -> throw UsageProblem("--out must end in .svg, .png or .pdf (or give --format)")
        }
        out("rendered ${target.path} (${target.length()} bytes${if (SketchRender.dimensionOptions(doc, request) != null) ", with dimension annotations" else ""})")
        return 0
    }

    private fun render(p: Parsed): Int {
        val request = requestOf(p)
        val target = File(p.flag("out") ?: throw UsageProblem("render needs --out <file.svg|file.png|file.pdf>"))
        val doc = docOrReport(p.input()) ?: return 1
        val format = (p.flag("format") ?: target.extension).lowercase()
        if (format !in setOf("svg", "png", "pdf")) throw UsageProblem("--format must be svg, png or pdf")
        return renderTo(doc, target, request, format)
    }

    private fun validate(p: Parsed): Int {
        val report = SketchValidate.validate(read(p.input()), p.input().absoluteFile.parentFile)
        out(report.render())
        return if (report.ok) 0 else 1
    }

    private fun canonicalize(p: Parsed): Int {
        val file = p.input()
        val bytes = read(file)
        val decoded = SheetCodecV2.decode(bytes)
        val doc = (decoded as? Either.Right)?.value ?: run {
            out(SketchValidate.validate(bytes).render())
            return 1
        }
        val canonical = (SheetCodecV2.encode(doc) as? Either.Right)?.value ?: run { out("ERROR   the sheet could not be encoded"); return 1 }
        val target = when {
            p.has("in-place") -> file
            p.has("out") -> File(p.flag("out")!!)
            else -> null
        }
        if (target == null) {
            out(canonical.decodeToString())
        } else {
            target.absoluteFile.parentFile?.mkdirs()
            target.writeBytes(canonical)
        }
        val same = canonical.contentEquals(bytes)
        err("canonical sha256 ${ContentHasher.sha256(canonical)}; ${if (same) "the input was already canonical" else "the input differed from canonical bytes"}${target?.let { "; wrote ${it.path}" } ?: ""}\n")
        return 0
    }

    private fun describe(p: Parsed): Int {
        val doc = docOrReport(p.input()) ?: return 1
        out(SketchDescribe.describe(doc).trimEnd())
        return 0
    }

    private fun fromExcalidraw(p: Parsed): Int {
        val loaded = fromExcalidrawBytes(read(p.input()))
        val doc = (loaded as? Loaded.Doc)?.doc ?: run { out((loaded as Loaded.Failed).report); return 1 }
        (loaded as Loaded.Doc).notes.forEach { out("INFO    $it") }
        val bytes = (SheetCodecV2.encode(doc) as? Either.Right)?.value ?: run { out("ERROR   the imported sheet could not be encoded"); return 1 }
        val target = File(p.flag("out") ?: throw UsageProblem("from-excalidraw needs --out <sheet.v2.json>"))
        target.absoluteFile.parentFile?.mkdirs()
        target.writeBytes(bytes)
        out("wrote ${target.path} (${doc.scene.size} elements, sha256 ${ContentHasher.sha256(bytes)}); images are not carried over")
        return 0
    }

    private fun toExcalidraw(p: Parsed): Int {
        val doc = docOrReport(p.input()) ?: return 1
        val exported = ExcalidrawExporter().export(doc.scene)
        val drawing = (exported as? Either.Right)?.value ?: run { out("ERROR   the sheet could not be exported"); return 1 }
        val target = File(p.flag("out") ?: throw UsageProblem("to-excalidraw needs --out <file.excalidraw>"))
        target.absoluteFile.parentFile?.mkdirs()
        target.writeText(drawing.text)
        out("wrote ${target.path} (${drawing.elementCount} elements; layers are flattened, constraints are not carried)")
        return 0
    }

    private fun place(p: Parsed): Int {
        val file = p.input()
        val graph = File(p.flag("graph") ?: throw UsageProblem("place needs --graph <graph folder>"))
        val photo = p.flag("underlay-image")?.let { read(File(it)) }
        val placed = SketchBundle.place(read(file), graph, p.flag("slug"), p.flag("name"), System.currentTimeMillis(), photo)
        return when (placed) {
            is Either.Left -> { out("ERROR   ${placed.value}"); 1 }
            is Either.Right -> {
                val r = placed.value
                out("${if (r.replaced) "updated" else "created"} sketch bundle ${r.slug}:")
                r.files.forEach { out("  $it") }
                out("embed this line in the page (pages/ and journals/ both use ../assets):")
                out(r.embed)
                0
            }
        }
    }

    private fun edit(p: Parsed): Int {
        val file = p.input()
        val dryRun = p.has("dry-run")
        if (!dryRun && !p.has("out") && !p.has("in-place")) throw UsageProblem("edit needs --out <sheet.v2.json>, --in-place or --dry-run")
        if (p.has("out") && p.has("in-place")) throw UsageProblem("--out and --in-place cannot be combined")
        val request = SketchEdit.parse(read(File(p.flag("edits") ?: throw UsageProblem("edit needs --edits <edits.json>"))).decodeToString())
            .fold({ throw UsageProblem(it) }, { it })
        val before = docOrReport(file) ?: return 1
        val shaBefore = ContentHasher.sha256(read(file))
        val outcome = SketchEdit.run(before, request)
        outcome.applied.forEach { a -> out("ok       [${a.index}] ${a.op}${a.target?.let { " $it" }.orEmpty()} -> ${a.ids.joinToString()}${a.note?.let { " ($it)" }.orEmpty()}") }
        outcome.refused.forEach { r -> out("REFUSED  [${r.index}] ${r.op}: ${r.reason}${if (r.conflicts.isEmpty()) "" else " (conflicts: ${r.conflicts.joinToString()})"}") }
        val encoded = (SheetCodecV2.encode(outcome.doc) as? Either.Right)?.value
        val valid = encoded != null && SketchValidate.validate(encoded).ok
        if (!valid) out("ERROR   the edited sheet does not validate; nothing was written")
        val writes = !dryRun && valid && (outcome.refused.isEmpty() || p.has("partial"))
        val target = if (p.has("in-place")) file else p.flag("out")?.let(::File)
        val shaAfter = encoded?.let { ContentHasher.sha256(it) }
        p.flag("audit")?.let { path ->
            val at = p.flag("at") ?: java.time.Instant.now().toString()
            val audit = SketchEdit.audit(request, outcome, file.path, shaBefore, shaAfter.takeIf { writes }, dryRun, at)
            File(path).also { it.absoluteFile.parentFile?.mkdirs() }.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), audit) + "\n")
        }
        val diff = SketchDiff.sheets(before, outcome.doc, file.path, if (writes) target?.path ?: file.path else "${file.path} (edited)")
        out(if (diff.isEmpty()) "no differences" else diff.trimEnd())
        if (writes && target != null && encoded != null) {
            target.absoluteFile.parentFile?.mkdirs()
            target.writeBytes(encoded)
            out("wrote ${target.path} (${encoded.size} bytes, sha256 $shaAfter)")
        } else if (dryRun) {
            out("dry run: nothing was written")
        } else if (outcome.refused.isNotEmpty() && !p.has("partial")) {
            out("nothing was written because ${outcome.refused.size} edit(s) were refused; fix them, or pass --partial to keep the others")
        }
        return if (valid && outcome.refused.isEmpty()) 0 else 1
    }

    private fun diff(p: Parsed): Int {
        if (p.positional.size != 2) throw UsageProblem("diff needs two files: diff <a.json> <b.json>")
        val a = docOrReport(File(p.positional[0])) ?: return 1
        val b = docOrReport(File(p.positional[1])) ?: return 1
        val text = SketchDiff.sheets(a, b, p.positional[0], p.positional[1])
        out(if (text.isEmpty()) "no differences" else text.trimEnd())
        return 0
    }

    companion object {
        const val EXIT_USAGE = 2
        private val HEX_COLOR = Regex("#[0-9a-fA-F]{6}")

        val USAGE = """
            |Usage: sketch-cli <command> [args]
            |
            |  build <spec.json> --out <sheet.v2.json> [--render <x.svg|x.png>] [--dimensions|--no-dimensions] [--width N]
            |        expand an authoring spec (rooms, walls, doors, labels, dimensions; feet-inches; photo calibration) into a valid sheet
            |  render <sheet.v2.json|.excalidraw|spec.json> --out <x.svg|x.png|x.pdf> [--format svg|png|pdf] [--dimensions|--no-dimensions] [--underlay <photo>] [--width N] [--style clean|sketchy] [--background #rrggbb|transparent]
            |        draw the sheet; --dimensions draws dimension annotations (--no-dimensions hides them; else the sheet flag decides);
            |        the photo underlay is left out unless --underlay <photo> gives its file (the sha256 must match the sheet's)
            |        on paper: --format pdf (or an .pdf --out) or any of --paper Letter|A4|A3|Tabloid|WxHmm  --scale 1:50|"1/4in=1ft"  --orientation portrait|landscape
            |        --margin 10mm  --no-scale-bar  --north DEG  --title T --author A --date D --sheet-number N --notes X  --dpi N (png)  --tile (pdf: several pages)
            |        lays the drawing out on the page at that scale (SVG: real-world width/height + data-sketch-scale; PNG at --dpi); flags left out fall back to the sheet's stored setup,
            |        then Letter, the drawing's orientation and the largest standard scale that fits
            |  validate <file>          check a sheet, spec or .excalidraw and explain every problem (exit 1 on errors)
            |  canonicalize <sheet> [--out f | --in-place]   rewrite hand-written sheet JSON as the exact canonical bytes; prints the sha256
            |  describe <file>          text description: elements with sizes, labels, relationships, openings, dimensions, a character map
            |  from-excalidraw <in.excalidraw> --out <sheet.v2.json>
            |  to-excalidraw <sheet.v2.json> --out <x.excalidraw>
            |  place <sheet.v2.json> --graph <graph dir> [--slug proj-xxxxxx] [--name "Title"] [--underlay-image <photo>]
            |        add the sheet to the graph's sketch bundle and print the markdown embed line; --underlay-image stores the photo in assets/
            |  edit <sheet.v2.json> --edits <edits.json> (--out <sheet.v2.json> | --in-place | --dry-run) [--audit <audit.json>] [--partial] [--at <timestamp>]
            |        apply a list of edits (add, move, resize, relabel, delete, set-dimension, add-wall, add-opening, set-layer) by element id or name;
            |        everything written is marked as agent-written for review; refused edits (constraint conflicts, no fit) write nothing unless --partial;
            |        prints the textual diff, and --audit writes who/what/before/after as JSON
            |  diff <a.json> <b.json>   unified textual diff of two sheets' descriptions (no differences when they match)
            |
            |Exit codes: 0 ok, 1 the input has errors (read the report), 2 usage or missing file.
        """.trimMargin() + "\n"
    }
}

/** Entry point: `dev.stapler.stelekit.cli.SketchCliKt`. */
fun main(args: Array<String>) {
    val code = SketchCli({ println(it) }, { System.err.print(it) }).run(args.toList())
    System.out.flush()
    exitProcess(code)
}
