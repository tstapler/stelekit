// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:Suppress("MagicNumber", "ReturnCount", "TooManyFunctions", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "LargeClass", "ComplexCondition")

package dev.stapler.stelekit.cli

import arrow.core.Either
import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.codec.SheetError
import dev.stapler.stelekit.sketch.v2.importer.ExcalidrawImporter
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.StrokeStyle
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What `validate` found. [errors] stop a sheet from loading; [warnings] load but are probably not what was meant. */
internal class ValidationReport(val kind: String, val errors: List<String>, val warnings: List<String>, val info: List<String>) {
    val ok: Boolean get() = errors.isEmpty()

    fun render(): String = buildString {
        appendLine("FILE KIND: $kind")
        errors.forEach { appendLine("ERROR   $it") }
        warnings.forEach { appendLine("WARNING $it") }
        info.forEach { appendLine("INFO    $it") }
        appendLine(if (ok) "RESULT: OK" + if (warnings.isEmpty()) "" else " (with ${warnings.size} warning${if (warnings.size == 1) "" else "s"})" else "RESULT: FAILED (${errors.size} error${if (errors.size == 1) "" else "s"})")
    }
}

/**
 * Checks a file an agent wrote, in the order an agent can act on: JSON syntax with line and column, then raw shape checks
 * that name the element id, its line and the keys it should have, then the real codec (what the app would do on load), then the
 * canonical round trip. Spec files are checked by running the spec builder; `.excalidraw` files by the real importer.
 */
internal object SketchValidate {
    private val json = Json

    fun validate(bytes: ByteArray): ValidationReport {
        val text = bytes.decodeToString()
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return ValidationReport("unreadable", listOf(syntaxMessage(text, e)), emptyList(), emptyList())
        }
        val obj = root as? JsonObject ?: return ValidationReport("unknown", listOf("the top level must be a JSON object, got ${SpecReader.describeJson(root)}"), emptyList(), emptyList())
        return when {
            obj["schema"] != null -> sheet(bytes, text, obj)
            obj["spec"] != null -> spec(obj)
            (obj["type"] as? JsonPrimitive)?.content == "excalidraw" -> excalidraw(bytes)
            else -> ValidationReport(
                "unknown",
                listOf("not a SteleKit sketch file: expected \"schema\": 2 (a v2 sheet), \"spec\": 1 (an authoring spec) or \"type\": \"excalidraw\""),
                emptyList(), emptyList(),
            )
        }
    }

    private fun syntaxMessage(text: String, e: Exception): String {
        val offset = Regex("offset (\\d+)").find(e.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        val where = offset?.let { " at line ${lineOf(text, it)}, column ${columnOf(text, it)}" } ?: ""
        return "invalid JSON$where: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}"
    }

    private fun lineOf(text: String, offset: Int) = 1 + text.take(offset.coerceIn(0, text.length)).count { it == '\n' }

    private fun columnOf(text: String, offset: Int) = 1 + offset.coerceIn(0, text.length) - (text.lastIndexOf('\n', offset.coerceIn(0, text.length) - 1) + 1)

    private fun spec(obj: JsonObject): ValidationReport {
        val outcome = SketchSpecBuilder(obj).build()
        val info = ArrayList(outcome.issues.notes)
        outcome.doc?.let { info += "builds to ${it.scene.size} elements; run `build` to write the sheet" }
        return ValidationReport("authoring spec", outcome.issues.errors, outcome.issues.warnings, info)
    }

    private fun excalidraw(bytes: ByteArray): ValidationReport {
        val result = runBlocking { ExcalidrawImporter().import(ImportSource.ofBytes(bytes)) }
        return when (result) {
            is Either.Left -> ValidationReport("excalidraw", listOf("the importer refused the file: ${result.value}"), emptyList(), emptyList())
            is Either.Right -> ValidationReport("excalidraw", emptyList(), emptyList(), listOf(result.value.report.summary()))
        }
    }

    // v2 sheets

    private fun sheet(bytes: ByteArray, text: String, root: JsonObject): ValidationReport {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        val info = ArrayList<String>()
        val lines = elementLines(text)
        rootChecks(root, errors, warnings)
        val layerIds = (root["layers"] as? JsonArray)?.mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.content }.orEmpty().toSet() + "default"
        val elements = root["elements"] as? JsonArray ?: JsonArray(emptyList())
        val ids = HashMap<String, Int>()
        elements.forEachIndexed { i, el -> elementChecks(i, el, lines.getOrNull(i), layerIds, ids, errors, warnings) }
        referenceChecks(elements, ids.keys, lines, warnings)
        constraintChecks(root, ids.keys, warnings)
        when (val decoded = SheetCodecV2.decode(bytes)) {
            is Either.Left -> {
                if (errors.isEmpty()) errors += "codec: ${sheetErrorText(decoded.value, elements, lines)}"
            }
            is Either.Right -> if (errors.isEmpty()) canonicalCheck(bytes, decoded.value, warnings, info)
        }
        return ValidationReport("SteleKit sketch sheet (schema 2)", errors, warnings, info)
    }

    private fun canonicalCheck(bytes: ByteArray, doc: dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2, warnings: MutableList<String>, info: MutableList<String>) {
        val canonical = (SheetCodecV2.encode(doc) as? Either.Right)?.value ?: return
        info += "loads as ${doc.scene.size} elements, ${doc.constraints.size} constraints, ${doc.scene.layers.size} layers"
        if (canonical.contentEquals(bytes)) {
            info += "canonical: yes, sha256 ${ContentHasher.sha256(canonical)}"
        } else {
            warnings += "the file is valid but not canonical (the app would rewrite it on first save, so a diff and a hash would change); run `canonicalize` to get the exact bytes (sha256 ${ContentHasher.sha256(canonical)})"
        }
    }

    private fun rootChecks(root: JsonObject, errors: MutableList<String>, warnings: MutableList<String>) {
        if ((root["schema"] as? JsonPrimitive)?.content?.toDoubleOrNull() != 2.0) errors += "\"schema\" must be the number 2, got ${SpecReader.describeJson(root["schema"])}"
        val uuid = root["sheetUuid"]
        if (uuid !is JsonPrimitive || !uuid.isString || !SketchLengths.UUID_SHAPE.matches(uuid.content)) errors += "\"sheetUuid\" must be a UUID-like string (letters, digits, '-', 8 to 64 characters; it becomes a file name); `canonicalize` does not invent one, copy it from `build` output or generate one"
        if (root["elements"] != null && root["elements"] !is JsonArray) errors += "\"elements\" must be a list"
        for (k in root.keys) if (k !in ROOT_KEYS) warnings += "top-level key \"$k\" is not part of schema 2 and will be dropped (allowed: ${ROOT_KEYS.joinToString()})"
        (root["layers"] as? JsonArray)?.forEachIndexed { i, l ->
            val o = l as? JsonObject
            if (o == null || (o["id"] as? JsonPrimitive)?.isString != true) errors += "layers[$i]: needs a string \"id\"; optional name, visible, locked, opacity (0 to 1), order"
        }
    }

    private fun elementChecks(
        i: Int, el: JsonElement, line: Int?, layerIds: Set<String>, ids: MutableMap<String, Int>,
        errors: MutableList<String>, warnings: MutableList<String>,
    ) {
        val o = el as? JsonObject
        if (o == null) {
            errors += "elements[$i]${at(line)}: must be an object, got ${SpecReader.describeJson(el)}"
            return
        }
        val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val who = "elements[$i]${if (id != null) " (id \"$id\")" else ""}${at(line)}"
        if (id.isNullOrBlank()) errors += "$who: needs a non-empty string \"id\""
        else if (ids.put(id, i) != null) errors += "$who: duplicate id; ids must be unique across the sheet"
        val type = (o["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val spec = TYPES[type]
        if (spec == null) {
            errors += "$who: \"type\" must be one of ${TYPES.keys.joinToString()}, got ${SpecReader.describeJson(o["type"])}"
            return
        }
        for ((key, kind) in spec.required) checkField(o, key, kind, true, who, errors)
        for ((key, kind) in spec.optional) checkField(o, key, kind, false, who, errors)
        for (k in o.keys) if (k !in COMMON_KEYS && k !in spec.allKeys) warnings += "$who: unknown key \"$k\" is dropped on load${HINTS[k]?.let { " (did you mean $it?)" } ?: ""}; a $type has: ${spec.allKeys.joinToString()}"
        (o["layer"] as? JsonPrimitive)?.content?.let { if (it !in layerIds) warnings += "$who: layer \"$it\" is not in \"layers\"; the element moves to the default layer on load" }
        (o["style"] as? JsonObject)?.let { styleChecks(it, who, errors, warnings) }
        enumCheck(o, "sah", Arrowhead.entries.map { it.name }, who, errors)
        enumCheck(o, "eah", Arrowhead.entries.map { it.name }, who, errors)
        enumCheck(o, "ta", TextAlign.entries.map { it.name }, who, errors)
        enumCheck(o, "va", VerticalAlign.entries.map { it.name }, who, errors)
        sizeChecks(o, type!!, who, warnings, errors)
    }

    private fun styleChecks(style: JsonObject, who: String, errors: MutableList<String>, warnings: MutableList<String>) {
        enumCheck(style, "fill", FillStyle.entries.map { it.name }, "$who.style", errors)
        enumCheck(style, "ss", StrokeStyle.entries.map { it.name }, "$who.style", errors)
        for (k in style.keys) if (k !in STYLE_KEYS) warnings += "$who.style: unknown key \"$k\" is dropped on load${HINTS[k]?.let { " (did you mean $it?)" } ?: ""}; style has: ${STYLE_KEYS.joinToString()}"
    }

    private fun sizeChecks(o: JsonObject, type: String, who: String, warnings: MutableList<String>, errors: MutableList<String>) {
        fun num(k: String) = (o[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
        if (type in setOf("rect", "diamond", "frame", "text", "image")) {
            val w = num("w")
            val h = num("h")
            if (w != null && h != null && (w <= 0.0 || h <= 0.0)) warnings += "$who: w and h are $w x $h; a zero or negative size draws nothing useful"
        }
        if (type == "ellipse") for (k in listOf("rx", "ry")) if ((num(k) ?: 1.0) <= 0.0) errors += "$who: \"$k\" must be greater than zero"
        if (type == "arc" && (num("r") ?: 1.0) <= 0.0) errors += "$who: \"r\" must be greater than zero"
        if (type == "text" && (o["text"] as? JsonPrimitive)?.content.isNullOrEmpty()) warnings += "$who: text is empty"
    }

    private fun checkField(o: JsonObject, key: String, kind: Kind, required: Boolean, who: String, errors: MutableList<String>) {
        val v = o[key]
        if (v == null) {
            if (required) errors += "$who: \"$key\" is missing; ${kind.help}"
            return
        }
        val ok = when (kind) {
            Kind.Number -> v is JsonPrimitive && !v.isString && v !is JsonNull && v.content.toDoubleOrNull()?.isFinite() == true
            Kind.Text -> v is JsonPrimitive && v.isString
            Kind.Bool -> v is JsonPrimitive && !v.isString && (v.content == "true" || v.content == "false")
            Kind.Points -> v is JsonArray && v.all { p -> p is JsonArray && p.size == 2 && p.all { n -> n is JsonPrimitive && !n.isString && n.content.toDoubleOrNull() != null } }
            Kind.List -> v is JsonArray
            Kind.Object -> v is JsonObject
        }
        if (!ok) errors += "$who: \"$key\" must be ${kind.help}, got ${SpecReader.describeJson(v)}"
    }

    private fun enumCheck(o: JsonObject, key: String, allowed: List<String>, who: String, errors: MutableList<String>) {
        val v = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        if (v !in allowed) errors += "$who: \"$key\" is \"$v\"; it must be one of ${allowed.joinToString()} (capitalised exactly)"
    }

    private fun referenceChecks(elements: JsonArray, ids: Set<String>, lines: List<Int>, warnings: MutableList<String>) {
        elements.forEachIndexed { i, el ->
            val o = el as? JsonObject ?: return@forEachIndexed
            val who = "elements[$i] (id \"${(o["id"] as? JsonPrimitive)?.content}\")${at(lines.getOrNull(i))}"
            fun ref(label: String, target: String?) {
                if (target != null && target !in ids) warnings += "$who: $label \"$target\" does not exist; the reference is cleared on load"
            }
            ref("container", (o["container"] as? JsonPrimitive)?.content)
            ref("frame", (o["frame"] as? JsonPrimitive)?.content)
            ref("start binding", ((o["sb"] as? JsonObject)?.get("id") as? JsonPrimitive)?.content)
            ref("end binding", ((o["eb"] as? JsonObject)?.get("id") as? JsonPrimitive)?.content)
            (o["bound"] as? JsonArray)?.forEach { ref("bound element", ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.content) }
        }
    }

    private fun constraintChecks(root: JsonObject, ids: Set<String>, warnings: MutableList<String>) {
        (root["constraints"] as? JsonArray)?.forEachIndexed { i, c ->
            ((c as? JsonObject)?.get("targets") as? JsonArray)?.forEach { t ->
                val e = ((t as? JsonObject)?.get("e") as? JsonPrimitive)?.content
                if (e != null && e !in ids) warnings += "constraints[$i]: target \"$e\" does not exist; the constraint is dropped on load"
            }
        }
    }

    private fun sheetErrorText(e: SheetError, elements: JsonArray, lines: List<Int>): String {
        fun idAt(i: Int) = ((elements.getOrNull(i) as? JsonObject)?.get("id") as? JsonPrimitive)?.content?.let { " (id \"$it\")" } ?: ""
        return when (e) {
            is SheetError.Malformed -> "${e.where}: missing or wrong-typed value${Regex("elements\\[(\\d+)]").find(e.where)?.groupValues?.get(1)?.toIntOrNull()?.let { idAt(it) + at(lines.getOrNull(it)) } ?: ""}"
            is SheetError.UnsupportedSchema -> "unsupported schema ${e.schema}; this reader needs 2"
            is SheetError.NonFinite -> "${e.where}: not a finite number"
            is SheetError.OutOfRange -> "${e.where}: value out of range (coordinates must stay within +-268435456 units)"
            is SheetError.TooLarge -> "the file is ${e.bytes} bytes; the limit is ${e.limit}"
            is SheetError.TooDeep -> "JSON nests ${e.depth} levels deep; the limit is ${SceneLimits.Default.maxJsonDepth}"
            is SheetError.TooManyElements -> "${e.count} elements; the limit is ${e.limit}"
            is SheetError.TooManyConstraints -> "${e.count} constraints; the limit is ${e.limit}"
            is SheetError.TooManyLayers -> "${e.count} layers; the limit is ${e.limit}"
            is SheetError.TooManyPoints -> "elements[${e.index}]${idAt(e.index)}: ${e.count} points; the limit is ${e.limit}"
            is SheetError.TextTooLong -> "elements[${e.index}]${idAt(e.index)}: text is longer than ${e.limit} characters"
            is SheetError.NameTooLong -> "${e.where}: name is longer than ${e.limit} characters"
            is SheetError.InvalidElement -> "elements[${e.index}]${idAt(e.index)}${at(lines.getOrNull(e.index))}: invalid \"${e.field}\""
            is SheetError.DuplicateElement -> "elements[${e.index}]${idAt(e.index)}: duplicate id"
        }
    }

    private fun at(line: Int?) = line?.let { ", line $it" } ?: ""

    /** Every key each element type may carry, as the validator knows it; a test holds the JSON Schema to the same table. */
    internal fun elementKeys(): Map<String, Set<String>> = TYPES.mapValues { COMMON_KEYS + it.value.allKeys }

    /** The 1-based line each entry of the top-level `elements` array starts on. */
    fun elementLines(text: String): List<Int> {
        val starts = ArrayList<Int>()
        var depth = 0
        var inString = false
        var escaped = false
        var stringStart = 0
        var armed = false
        var inElements = false
        var line = 1
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') line++
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') {
                    inString = false
                    if (depth == 1 && text.substring(stringStart, i) == "elements") armed = true
                }
            } else when (c) {
                '"' -> { inString = true; stringStart = i + 1 }
                '{', '[' -> {
                    if (inElements && depth == 2) starts += line
                    depth++
                    if (armed && c == '[' && depth == 2) { inElements = true; armed = false }
                }
                '}', ']' -> {
                    depth--
                    if (inElements && depth == 1) return starts
                }
                else -> Unit
            }
            i++
        }
        return starts
    }

    private enum class Kind(val help: String) {
        Number("a number"),
        Text("a string"),
        Bool("true or false"),
        Points("a list of [x, y] number pairs, e.g. [[0, 0], [100, 50]]"),
        List("a list"),
        Object("an object"),
    }

    private class TypeSpec(val required: List<Pair<String, Kind>>, val optional: List<Pair<String, Kind>>) {
        val allKeys: List<String> = (required + optional).map { it.first }
    }

    private fun box(extra: List<Pair<String, Kind>> = emptyList()) = TypeSpec(
        listOf("x" to Kind.Number, "y" to Kind.Number, "w" to Kind.Number, "h" to Kind.Number),
        listOf("angle" to Kind.Number) + extra,
    )

    private val ROOT_KEYS = listOf("schema", "sheetUuid", "constraints", "elements", "extras", "layers", "nameCounters")
    private val COMMON_KEYS = setOf("id", "type", "layer", "groups", "frame", "z", "locked", "opacity", "style", "seed", "ver", "ord", "name", "extras")
    private val STYLE_KEYS = listOf("stroke", "bg", "fill", "sw", "ss", "rough", "round")
    private val HINTS = mapOf(
        "width" to "\"w\"", "height" to "\"h\"", "points" to "\"pts\"", "strokeColor" to "style.\"stroke\"", "backgroundColor" to "style.\"bg\"",
        "strokeWidth" to "style.\"sw\"", "fontSize" to "\"fs\"", "fontFamily" to "\"ff\"", "textAlign" to "\"ta\"", "verticalAlign" to "\"va\"",
        "containerId" to "\"container\"", "boundElements" to "\"bound\"", "startArrowhead" to "\"sah\"", "endArrowhead" to "\"eah\"", "radius" to "\"r\"",
        "fillStyle" to "style.\"fill\"", "strokeStyle" to "style.\"ss\"", "roughness" to "style.\"rough\"", "version" to "\"ver\"", "layerId" to "\"layer\"",
        "text_align" to "\"ta\"", "closed" to "\"poly\"", "polygon" to "\"poly\"", "originalText" to "\"orig\"", "lineHeight" to "\"lh\"",
    )
    private val TYPES: Map<String, TypeSpec> = mapOf(
        "rect" to box(listOf("bound" to Kind.List)),
        "diamond" to box(listOf("bound" to Kind.List)),
        "frame" to box(),
        "ellipse" to TypeSpec(
            listOf("cx" to Kind.Number, "cy" to Kind.Number, "rx" to Kind.Number, "ry" to Kind.Number),
            listOf("angle" to Kind.Number, "bound" to Kind.List),
        ),
        "line" to TypeSpec(listOf("pts" to Kind.Points), listOf("poly" to Kind.Bool)),
        "arrow" to TypeSpec(
            listOf("pts" to Kind.Points),
            listOf("sb" to Kind.Object, "eb" to Kind.Object, "sah" to Kind.Text, "eah" to Kind.Text, "elbow" to Kind.Bool),
        ),
        "freedraw" to TypeSpec(listOf("pts" to Kind.Points), listOf("pressures" to Kind.List, "sim" to Kind.Bool)),
        "text" to TypeSpec(
            listOf("x" to Kind.Number, "y" to Kind.Number, "w" to Kind.Number, "h" to Kind.Number),
            listOf(
                "angle" to Kind.Number, "text" to Kind.Text, "orig" to Kind.Text, "fs" to Kind.Number, "ff" to Kind.Number, "ta" to Kind.Text,
                "va" to Kind.Text, "container" to Kind.Text, "lh" to Kind.Number, "auto" to Kind.Bool,
            ),
        ),
        "image" to box(listOf("fileId" to Kind.Text, "fx" to Kind.Bool, "fy" to Kind.Bool, "crop" to Kind.Object)),
        "point" to TypeSpec(listOf("x" to Kind.Number, "y" to Kind.Number), emptyList()),
        "arc" to TypeSpec(
            listOf("cx" to Kind.Number, "cy" to Kind.Number, "r" to Kind.Number, "a0" to Kind.Number, "a1" to Kind.Number),
            emptyList(),
        ),
    )
}
