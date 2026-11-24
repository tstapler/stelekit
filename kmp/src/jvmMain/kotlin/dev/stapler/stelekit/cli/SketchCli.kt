// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:Suppress("MagicNumber", "ReturnCount", "TooManyFunctions", "LongMethod", "CyclomaticComplexMethod")

package dev.stapler.stelekit.cli

import arrow.core.Either
import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.importer.ExcalidrawExporter
import dev.stapler.stelekit.sketch.v2.importer.ExcalidrawImporter
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
            private val SWITCHES = setOf("dimensions", "in-place")

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
                val outcome = SketchSpecBuilder(root).build()
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
        val outcome = SketchSpecBuilder(root).build()
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
        dimensions = p.has("dimensions"),
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
    )

    private fun renderTo(doc: SketchDocumentV2, target: File, request: RenderRequest): Int {
        target.absoluteFile.parentFile?.mkdirs()
        when (target.extension.lowercase()) {
            "svg" -> target.writeText(SketchRender.svg(doc, request))
            "png" -> {
                val bytes = SketchRender.png(doc, request)
                if (bytes == null) {
                    out("ERROR   the PNG could not be produced (empty sheet, or larger than the PNG size cap); use an .svg or a smaller --width")
                    return 1
                }
                target.writeBytes(bytes)
            }
            else -> throw UsageProblem("--out must end in .svg or .png")
        }
        out("rendered ${target.path} (${target.length()} bytes${if (request.dimensions) ", with measured dimensions overlaid" else ""})")
        return 0
    }

    private fun render(p: Parsed): Int {
        val request = requestOf(p)
        val target = File(p.flag("out") ?: throw UsageProblem("render needs --out <file.svg|file.png>"))
        val doc = docOrReport(p.input()) ?: return 1
        return renderTo(doc, target, request)
    }

    private fun validate(p: Parsed): Int {
        val report = SketchValidate.validate(read(p.input()))
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
        val placed = SketchBundle.place(read(file), graph, p.flag("slug"), p.flag("name"), System.currentTimeMillis())
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

    companion object {
        const val EXIT_USAGE = 2
        private val HEX_COLOR = Regex("#[0-9a-fA-F]{6}")

        val USAGE = """
            |Usage: sketch-cli <command> [args]
            |
            |  build <spec.json> --out <sheet.v2.json> [--render <x.svg|x.png>] [--dimensions] [--width N]
            |        expand an authoring spec (rooms, walls, doors, labels, dimensions; feet-inches; photo calibration) into a valid sheet
            |  render <sheet.v2.json|.excalidraw|spec.json> --out <x.svg|x.png> [--dimensions] [--width N] [--style clean|sketchy] [--background #rrggbb|transparent]
            |        draw the sheet; --dimensions overlays the measured width and height of every shape
            |  validate <file>          check a sheet, spec or .excalidraw and explain every problem (exit 1 on errors)
            |  canonicalize <sheet> [--out f | --in-place]   rewrite hand-written sheet JSON as the exact canonical bytes; prints the sha256
            |  describe <file>          text description: elements with sizes, labels, relationships, openings, dimensions, a character map
            |  from-excalidraw <in.excalidraw> --out <sheet.v2.json>
            |  to-excalidraw <sheet.v2.json> --out <x.excalidraw>
            |  place <sheet.v2.json> --graph <graph dir> [--slug proj-xxxxxx] [--name "Title"]
            |        add the sheet to the graph's sketch bundle and print the markdown embed line
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
