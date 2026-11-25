// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:Suppress("MagicNumber", "ReturnCount", "TooManyFunctions", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")

package dev.stapler.stelekit.cli

import arrow.core.Either
import arrow.core.getOrElse
import dev.stapler.stelekit.sketch.constraints.SolveResult
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.AddLayer
import dev.stapler.stelekit.sketch.v2.commands.Applied
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.DeleteElements
import dev.stapler.stelekit.sketch.v2.commands.MoveElements
import dev.stapler.stelekit.sketch.v2.commands.MoveElementsToLayer
import dev.stapler.stelekit.sketch.v2.commands.RenameElement
import dev.stapler.stelekit.sketch.v2.commands.ResizeElement
import dev.stapler.stelekit.sketch.v2.commands.SequenceCommand
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintId
import dev.stapler.stelekit.sketch.v2.constraints.ConstraintKind
import dev.stapler.stelekit.sketch.v2.constraints.SceneProblem
import dev.stapler.stelekit.sketch.v2.constraints.SceneToProblem
import dev.stapler.stelekit.sketch.v2.constraints.SetConstraintReference
import dev.stapler.stelekit.sketch.v2.constraints.SetDimensionValue
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.BoxElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningElement
import dev.stapler.stelekit.sketch.v2.scene.OpeningKind
import dev.stapler.stelekit.sketch.v2.scene.Provenance
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.WallElement
import dev.stapler.stelekit.sketch.v2.scene.WallGeometry
import dev.stapler.stelekit.sketch.v2.scene.WallJustification
import dev.stapler.stelekit.sketch.v2.scene.provenance
import dev.stapler.stelekit.sketch.v2.scene.withProvenance
import dev.stapler.stelekit.sketch.v2.view.SceneBounds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.abs
import kotlin.math.hypot

/** A parsed edit list: [agent] names who is editing (written into the audit trail), [edits] run in order. */
internal class EditRequest(val agent: String?, val edits: List<JsonObject>)

/** One edit that ran: what it was, the elements it touched, and a before/after summary of each. */
internal class AppliedEdit(val index: Int, val op: String, val target: String?, val ids: List<String>, val before: Map<String, JsonObject?>, val after: Map<String, JsonObject?>, val note: String?)

/** One edit that was refused, with the reason and the constraint ids (or element ids) it conflicted with. */
internal class RefusedEdit(val index: Int, val op: String, val reason: String, val conflicts: List<String>)

internal class EditOutcome(val doc: SketchDocumentV2, val applied: List<AppliedEdit>, val refused: List<RefusedEdit>)

/**
 * `sketch-cli edit`: applies a JSON list of command-level edits to an existing sheet, by element id or name. Each edit is one of
 * the app's own commands (so the result is what the editor would have produced), runs against the sheet the earlier edits left,
 * and is refused, not forced, when it would break a constraint or does not fit (a door wider than its wall). Everything the agent
 * adds or changes is stamped `extras.provenance` (source agent, unreviewed), so the owner can review it in the editor.
 * Lengths are real-world: a number is inches, text is `3' 6"` or `25.4 mm`.
 */
internal object SketchEdit {
    private const val UNITS_PER_INCH = 16.0
    private const val GLYPH_WIDTH = 0.6
    private const val DEFAULT_FONT = 20.0
    private const val DEFAULT_WALL_INCHES = 4.5
    private const val DEFAULT_OPENING_INCHES = 36.0
    private const val FIT_SLACK = 0.5
    private const val MAX_CONFIDENCE = 100
    val OPS = setOf("add", "move", "resize", "relabel", "delete", "set-dimension", "set-reference", "add-wall", "add-opening", "set-layer")

    fun parse(text: String): Either<String, EditRequest> {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            return Either.Left("the edit file is not JSON: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        val list = when (root) {
            is JsonArray -> root
            is JsonObject -> root["edits"] as? JsonArray ?: return Either.Left("the edit file needs an \"edits\" array (or be an array itself)")
            else -> return Either.Left("the edit file must be an object with \"edits\" or an array of edits")
        }
        val edits = list.mapIndexed { i, e -> e as? JsonObject ?: return Either.Left("edit $i is not an object") }
        val agent = (root as? JsonObject)?.get("agent")?.let { (it as? JsonPrimitive)?.contentOrNull }
        return Either.Right(EditRequest(agent, edits))
    }

    fun run(start: SketchDocumentV2, request: EditRequest): EditOutcome {
        var doc = start
        val applied = ArrayList<AppliedEdit>()
        val refused = ArrayList<RefusedEdit>()
        for ((i, edit) in request.edits.withIndex()) {
            val op = str(edit, "op").orEmpty()
            when (val step = step(doc, edit)) {
                is Step.Refused -> refused += RefusedEdit(i, op, step.reason, step.conflicts)
                is Step.Done -> {
                    val stamped = if (step.stamp) stamp(step.doc, step.changed, edit) else step.doc
                    applied += AppliedEdit(i, op, str(edit, "id") ?: str(edit, "name"), step.changed.map { it.value }, summaries(doc, step.changed), summaries(stamped, step.changed), step.note)
                    doc = stamped
                }
            }
        }
        return EditOutcome(doc, applied, refused)
    }

    private sealed interface Step {
        class Done(val doc: SketchDocumentV2, val changed: Set<ElementId>, val stamp: Boolean = true, val note: String? = null) : Step

        class Refused(val reason: String, val conflicts: List<String> = emptyList()) : Step
    }

    private fun step(doc: SketchDocumentV2, e: JsonObject): Step {
        val op = str(e, "op") ?: return Step.Refused("every edit needs an \"op\" (${OPS.joinToString()})")
        return when (op) {
            "add" -> add(doc, e)
            "move" -> move(doc, e)
            "resize" -> resize(doc, e)
            "relabel" -> relabel(doc, e)
            "delete" -> delete(doc, e)
            "set-dimension" -> setDimension(doc, e)
            "set-reference" -> setReference(doc, e)
            "add-wall" -> addWall(doc, e)
            "add-opening" -> addOpening(doc, e)
            "set-layer" -> setLayer(doc, e)
            else -> Step.Refused("unknown op \"$op\"; use one of ${OPS.joinToString()}")
        }
    }

    // Applying commands

    private fun run(doc: SketchDocumentV2, command: Command, stamp: Boolean = true, solve: Boolean = true, note: String? = null): Step =
        when (val r = command.apply(doc)) {
            is Either.Left -> Step.Refused(describe(r.value), conflictsOf(r.value))
            is Either.Right -> settle(r.value, stamp, solve, note)
        }

    /** Re-solves the constraints around the edit; an edit the solver cannot satisfy is refused with the constraints it names. */
    private fun settle(applied: Applied, stamp: Boolean, solve: Boolean, note: String?): Step {
        val doc = applied.doc
        if (!solve || doc.constraints.isEmpty()) return Step.Done(doc, applied.changedIds, stamp, note)
        val problem = SceneToProblem.build(doc.scene, doc.constraints, skipInvalid = true).getOrElse { return Step.Refused(describe(it)) }
        val solved = problem.solve(doc.scene).getOrElse { return Step.Refused(describe(it)) }
        val ok = solved.result is SolveResult.Solved || solved.result is SolveResult.Redundant
        if (!ok) return Step.Refused("the edit breaks the sheet's constraints (${solved.result::class.simpleName})", conflictIds(solved.result))
        val moved = doc.scene.order.filter { solved.scene[it] != doc.scene[it] }.toSet()
        val tail = if (moved.isEmpty()) note else listOfNotNull(note, "the solver moved ${moved.joinToString { it.value }}").joinToString("; ")
        return Step.Done(doc.copy(scene = solved.scene), applied.changedIds + moved, stamp, tail)
    }

    private fun conflictIds(result: SolveResult): List<String> {
        val raw = when (result) {
            is SolveResult.Conflict -> result.ids
            is SolveResult.Degenerate -> result.ids
            else -> emptyList()
        }
        return raw.mapNotNull { SceneProblem.constraintIdOf(it)?.value }.distinct()
    }

    private fun conflictsOf(error: CommandError): List<String> = (error as? CommandError.Unsolvable)?.conflictIds.orEmpty()

    private fun describe(error: CommandError): String = when (error) {
        is CommandError.Unsolvable -> "the solver cannot satisfy the sheet's constraints with the new value (conflicts: ${error.conflictIds.joinToString().ifEmpty { "none named" }})"
        is CommandError.UnknownElement -> "no element \"${error.id}\""
        is CommandError.DuplicateElement -> "an element \"${error.id}\" already exists"
        is CommandError.NameTaken -> "the name \"${error.name}\" is taken"
        CommandError.NoChange -> "the edit changes nothing"
        else -> error.toString()
    }

    private fun stamp(doc: SketchDocumentV2, changed: Set<ElementId>, edit: JsonObject): SketchDocumentV2 {
        val confidence = (edit["confidence"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, MAX_CONFIDENCE)
        val provenance = Provenance(Provenance.AGENT, confidence, str(edit, "note"))
        var scene = doc.scene
        for (id in changed) {
            val el = scene[id] ?: continue
            scene = scene.replace(el.withBase(el.base.withProvenance(provenance))).getOrElse { scene }
        }
        return doc.copy(scene = scene)
    }

    // Resolving and reading values

    private fun str(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    /** Inches in, scene units out: a number is inches, text is a length (`3' 6"`, `25.4 mm`). Null when it is not a length. */
    private fun units(o: JsonObject, key: String): Double? {
        val p = o[key] as? JsonPrimitive ?: return null
        return SketchLengths.parseInches(p.content)?.let { SketchLengths.tidy(it * UNITS_PER_INCH) }
    }

    private fun need(o: JsonObject, key: String): Double? = units(o, key)

    /** Keys present in [o] whose value is not a length: such an edit is refused, never applied with the bad part dropped. */
    private fun badLengths(o: JsonObject, vararg keys: String): List<String> = keys.filter { o[it] != null && units(o, it) == null }

    private sealed interface Found {
        class One(val element: SceneElement) : Found

        class None(val reason: String) : Found
    }

    /** The element named by [key] in [o]: an exact id, else a user name, else the shown name ("Wall 1"); an ambiguous name is refused. */
    private fun find(doc: SketchDocumentV2, o: JsonObject, key: String = "id"): Found {
        val ref = str(o, key) ?: str(o, "name") ?: return Found.None("name the element with \"id\" (its id or its name)")
        doc.scene[ElementId(ref)]?.let { return Found.One(it) }
        val byName = doc.scene.elementsInOrder.filter { it.base.name == ref }.ifEmpty { doc.scene.elementsInOrder.filter { ElementNames.nameOf(it) == ref } }
        return when (byName.size) {
            0 -> Found.None("no element with id or name \"$ref\"")
            1 -> Found.One(byName.single())
            else -> Found.None("\"$ref\" names ${byName.size} elements (${byName.joinToString { it.id.value }}); use an id")
        }
    }

    private inline fun withElement(doc: SketchDocumentV2, o: JsonObject, block: (SceneElement) -> Step): Step =
        when (val f = find(doc, o)) {
            is Found.None -> Step.Refused(f.reason)
            is Found.One -> block(f.element)
        }

    private fun layerId(doc: SketchDocumentV2, ref: String): LayerId? =
        doc.scene.layers.firstOrNull { it.id.value == ref }?.id ?: doc.scene.layers.firstOrNull { it.name == ref }?.id

    // The edits

    private fun baseFor(doc: SketchDocumentV2, o: JsonObject, id: String): Either<String, ElementBase> {
        if (doc.scene[ElementId(id)] != null) return Either.Left("an element \"$id\" already exists")
        val layer = str(o, "layer")?.let { layerId(doc, it) ?: return Either.Left("no layer \"${str(o, "layer")}\"") } ?: LayerId.Default
        val name = str(o, "name") ?: id
        if (ElementNames.isTaken(doc.scene, name, except = null)) return Either.Left("the name \"$name\" is taken; give \"name\" a different value")
        return Either.Right(ElementBase(ElementId(id), layer, style = ElementStyle(), seed = id.hashCode() and Int.MAX_VALUE, name = name))
    }

    private fun unit(v: Double): SceneUnit? = SceneUnit.of(v).getOrNull()

    private fun add(doc: SketchDocumentV2, o: JsonObject): Step {
        val id = str(o, "id") ?: return Step.Refused("add needs an \"id\"")
        val kind = str(o, "kind") ?: return Step.Refused("add needs a \"kind\": rect, ellipse, text or line")
        val base = baseFor(doc, o, id).getOrElse { return Step.Refused(it) }
        val x = need(o, "x") ?: return Step.Refused("add $kind needs \"x\"")
        val y = need(o, "y") ?: return Step.Refused("add $kind needs \"y\"")
        val element: SceneElement = when (kind) {
            "rect", "ellipse" -> {
                val w = need(o, "w") ?: return Step.Refused("add $kind needs \"w\"")
                val h = need(o, "h") ?: return Step.Refused("add $kind needs \"h\"")
                if (w <= 0.0 || h <= 0.0) return Step.Refused("\"w\" and \"h\" must be greater than zero")
                if (kind == "rect") {
                    RectElement(base, unit(x) ?: return outOfRange(), unit(y) ?: return outOfRange(), unit(w) ?: return outOfRange(), unit(h) ?: return outOfRange())
                } else {
                    EllipseElement(base, unit(x + w / 2) ?: return outOfRange(), unit(y + h / 2) ?: return outOfRange(), unit(w / 2) ?: return outOfRange(), unit(h / 2) ?: return outOfRange())
                }
            }
            "text" -> {
                val text = str(o, "text") ?: return Step.Refused("add text needs \"text\"")
                badLengths(o, "fontSize").takeIf { it.isNotEmpty() }?.let { return Step.Refused("\"fontSize\" is not a length") }
                val font = need(o, "fontSize") ?: DEFAULT_FONT
                val (w, h) = textBox(text, font)
                TextElement(base, unit(x) ?: return outOfRange(), unit(y) ?: return outOfRange(), unit(w) ?: return outOfRange(), unit(h) ?: return outOfRange(), text = text, fontSize = font)
            }
            "line" -> {
                val x2 = need(o, "x2") ?: return Step.Refused("add line needs \"x2\" and \"y2\"")
                val y2 = need(o, "y2") ?: return Step.Refused("add line needs \"x2\" and \"y2\"")
                LineElement(base, listOf(ScenePoint(unit(x) ?: return outOfRange(), unit(y) ?: return outOfRange()), ScenePoint(unit(x2) ?: return outOfRange(), unit(y2) ?: return outOfRange())))
            }
            else -> return Step.Refused("unknown kind \"$kind\"; use rect, ellipse, text or line")
        }
        return run(doc, AddElement(element))
    }

    private fun outOfRange() = Step.Refused("a coordinate is outside the sheet's range")

    private fun textBox(text: String, font: Double): Pair<Double, Double> {
        val lines = text.split('\n')
        return SketchLengths.tidy(lines.maxOf { it.length } * font * GLYPH_WIDTH) to SketchLengths.tidy(lines.size * font * TextElement.DEFAULT_LINE_HEIGHT)
    }

    private fun move(doc: SketchDocumentV2, o: JsonObject): Step = withElement(doc, o) { el ->
        badLengths(o, "dx", "dy").takeIf { it.isNotEmpty() }?.let { return@withElement Step.Refused("${it.joinToString()} is not a length (use 3' 6\", 42 or 25.4 mm)") }
        val dx = need(o, "dx") ?: 0.0
        val dy = need(o, "dy") ?: 0.0
        if (dx == 0.0 && dy == 0.0) Step.Refused("move needs \"dx\" and/or \"dy\"") else run(doc, MoveElements(listOf(el.id), dx, dy))
    }

    private fun resize(doc: SketchDocumentV2, o: JsonObject): Step = withElement(doc, o) { el ->
        val b = SceneBounds.of(listOf(el)) ?: return@withElement Step.Refused("\"${el.id.value}\" has no size")
        badLengths(o, "w", "h").takeIf { it.isNotEmpty() }?.let { return@withElement Step.Refused("${it.joinToString()} is not a length (use 3' 6\", 42 or 25.4 mm)") }
        val w = need(o, "w") ?: b.width
        val h = need(o, "h") ?: b.height
        run(doc, ResizeElement(el.id, b.minX, b.minY, w, h))
    }

    private fun relabel(doc: SketchDocumentV2, o: JsonObject): Step = withElement(doc, o) { el ->
        val text = str(o, "text")
        val rename = str(o, "to")
        if (text == null && rename == null) return@withElement Step.Refused("relabel needs \"text\" (the words shown) or \"to\" (the element's name)")
        if (text == null) return@withElement run(doc, RenameElement(el.id, rename))
        val label = if (el is TextElement) el else doc.scene.elementsInOrder.filterIsInstance<TextElement>().firstOrNull { it.containerId == el.id }
            ?: return@withElement Step.Refused("\"${el.id.value}\" has no label to change; add a text element instead")
        val (w, h) = textBox(text, label.fontSize)
        val cx = label.x.value + label.width.value / 2
        val cy = label.y.value + label.height.value / 2
        val moved = label.copy(
            text = text, originalText = text,
            x = unit(SketchLengths.tidy(cx - w / 2)) ?: return@withElement outOfRange(), y = unit(SketchLengths.tidy(cy - h / 2)) ?: return@withElement outOfRange(),
            width = unit(w) ?: return@withElement outOfRange(), height = unit(h) ?: return@withElement outOfRange(),
            base = label.base.copy(version = label.base.version + 1),
        )
        val scene = doc.scene.replace(moved).getOrElse { return@withElement Step.Refused(describe(it)) }
        Step.Done(doc.copy(scene = scene), setOf(label.id))
    }

    private fun delete(doc: SketchDocumentV2, o: JsonObject): Step = withElement(doc, o) { el ->
        run(doc, DeleteElements(listOf(el.id)), stamp = false)
    }

    private fun setLayer(doc: SketchDocumentV2, o: JsonObject): Step {
        val ref = str(o, "layer") ?: return Step.Refused("set-layer needs \"layer\" (an id or a name)")
        val ids = (o["ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val elements = if (ids != null) {
            ids.map { id -> doc.scene[ElementId(id)] ?: return Step.Refused("no element \"$id\"") }
        } else {
            listOf((find(doc, o) as? Found.One)?.element ?: return Step.Refused((find(doc, o) as Found.None).reason))
        }
        var current = doc
        var target = layerId(current, ref)
        if (target == null) {
            if ((o["create"] as? JsonPrimitive)?.contentOrNull != "true") return Step.Refused("no layer \"$ref\"; add \"create\": true to make it")
            val id = LayerId(ref.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "layer" })
            current = (AddLayer(id, ref).apply(current) as? Either.Right)?.value?.doc ?: return Step.Refused("the layer \"$ref\" could not be created")
            target = id
        }
        return run(current, MoveElementsToLayer(elements.map { it.id }, target), stamp = false, solve = false)
    }

    private fun addWall(doc: SketchDocumentV2, o: JsonObject): Step {
        val id = str(o, "id") ?: return Step.Refused("add-wall needs an \"id\"")
        val base = baseFor(doc, o, id).getOrElse { return Step.Refused(it) }
        val raw = o["points"] as? JsonArray ?: return Step.Refused("add-wall needs \"points\": [[x, y], [x, y], ...]")
        val points = raw.map { p ->
            val pair = p as? JsonArray
            val x = pair?.getOrNull(0)?.let { lengthOf(it) }
            val y = pair?.getOrNull(1)?.let { lengthOf(it) }
            if (x == null || y == null) return Step.Refused("each point is [x, y] in lengths")
            ScenePoint(unit(x) ?: return outOfRange(), unit(y) ?: return outOfRange())
        }
        val closed = str(o, "closed") == "true"
        if (points.size < (if (closed) 3 else 2)) return Step.Refused("a wall needs ${if (closed) 3 else 2} points or more")
        if (!WallGeometry.hasDistinctVertices(points, closed)) return Step.Refused("two consecutive points are the same place")
        val thickness = need(o, "thickness") ?: (DEFAULT_WALL_INCHES * UNITS_PER_INCH)
        if (thickness <= 0.0) return Step.Refused("\"thickness\" must be greater than zero")
        val justify = str(o, "justify") ?: "centre"
        val just = WallJustification.entries.firstOrNull { it.name.lowercase() == justify } ?: return Step.Refused("\"justify\" must be inside, centre or outside")
        val style = ElementStyle("#1e1e1e", "#ced4da")
        return run(doc, AddElement(WallElement(base.copy(style = style), points, unit(thickness) ?: return outOfRange(), just, closed)))
    }

    private fun lengthOf(e: JsonElement): Double? = (e as? JsonPrimitive)?.let { SketchLengths.parseInches(it.content) }?.let { SketchLengths.tidy(it * UNITS_PER_INCH) }

    private fun addOpening(doc: SketchDocumentV2, o: JsonObject): Step {
        val id = str(o, "id") ?: return Step.Refused("add-opening needs an \"id\"")
        val base = baseFor(doc, o, id).getOrElse { return Step.Refused(it) }
        val type = str(o, "type") ?: return Step.Refused("add-opening needs \"type\": door, window or opening")
        val kind = when (type) {
            "door" -> OpeningKind.Door
            "window" -> OpeningKind.Window
            "opening" -> OpeningKind.Opening
            else -> return Step.Refused("\"type\" must be door, window or opening")
        }
        val host = (find(doc, o, "on") as? Found.One)?.element as? WallElement ?: return Step.Refused("\"on\" must name a wall element")
        val segment = (o["segment"] as? JsonPrimitive)?.intOrNull ?: 0
        if (segment < 0 || segment >= host.segmentCount) return Step.Refused("wall \"${host.id.value}\" has ${host.segmentCount} segments, numbered from 0")
        val offset = need(o, "offset") ?: return Step.Refused("add-opening needs \"offset\" (from the start of the segment)")
        val width = need(o, "width") ?: (DEFAULT_OPENING_INCHES * UNITS_PER_INCH)
        if (width <= 0.0) return Step.Refused("\"width\" must be greater than zero")
        val fit = WallGeometry.fit(host, segment, offset, width)
        if (fit == null || abs(fit.first - offset) > FIT_SLACK || abs(fit.second - width) > FIT_SLACK) {
            val free = WallGeometry.span(host, segment)?.length ?: WallGeometry.segments(host)[segment].length
            return Step.Refused("the opening spans ${SketchLengths.feetInches(offset)} to ${SketchLengths.feetInches(offset + width)} but segment $segment of \"${host.id.value}\" has only ${SketchLengths.feetInches(free)} free")
        }
        val swing = str(o, "swing") ?: "left"
        if (swing !in setOf("in", "out", "left", "right")) return Step.Refused("\"swing\" must be in, out, left or right")
        val hinge = str(o, "hinge") ?: "start"
        if (hinge != "start" && hinge != "end") return Step.Refused("\"hinge\" must be start or end")
        val element = OpeningElement(base.copy(style = ElementStyle("#1e1e1e", "transparent")), host.id, segment, unit(offset) ?: return outOfRange(), unit(width) ?: return outOfRange(), kind, swing == "in" || swing == "right", hinge == "end")
        return run(doc, AddElement(element))
    }

    /** A driving dimension (by constraint id) is solved; an authored dimension group (`<id>.line` / `.text`) is re-laid to the new length. */
    private fun setDimension(doc: SketchDocumentV2, o: JsonObject): Step {
        val ref = str(o, "id") ?: return Step.Refused("set-dimension needs \"id\" (a constraint id or an authored dimension's id)")
        val value = need(o, "value") ?: return Step.Refused("set-dimension needs \"value\" (a length)")
        if (value <= 0.0) return Step.Refused("\"value\" must be greater than zero")
        if (doc.constraints.any { it.id.value == ref && it.kind == ConstraintKind.Angle }) return Step.Refused("\"$ref\" is an angle; set-dimension sets lengths only")
        if (doc.constraints.any { it.id.value == ref }) return run(doc, SetDimensionValue(ConstraintId(ref), value), solve = false, stamp = false, note = "driving dimension; the solver moved the geometry").let { markDimension(doc, it, ref, o) }
        val line = doc.scene[ElementId("$ref.line")] as? ArrowElement ?: return Step.Refused("no constraint or authored dimension \"$ref\"")
        val text = doc.scene[ElementId("$ref.text")] as? TextElement
        val a = line.points.first()
        val b = line.points.last()
        val length = hypot(b.x.value - a.x.value, b.y.value - a.y.value)
        if (length == 0.0) return Step.Refused("the dimension line \"$ref.line\" has no length")
        val k = value / length
        val nb = ScenePoint(unit(SketchLengths.tidy(a.x.value + (b.x.value - a.x.value) * k)) ?: return outOfRange(), unit(SketchLengths.tidy(a.y.value + (b.y.value - a.y.value) * k)) ?: return outOfRange())
        val dx = nb.x.value - b.x.value
        val dy = nb.y.value - b.y.value
        var scene = doc.scene.replace(line.copy(points = line.points.dropLast(1) + nb, base = line.base.copy(version = line.base.version + 1))).getOrElse { return Step.Refused(describe(it)) }
        val changed = linkedSetOf(line.id)
        (scene[ElementId("$ref.ext2")] as? LineElement)?.let { ext ->
            val moved = ext.copy(points = ext.points.map { ScenePoint(unit(SketchLengths.tidy(it.x.value + dx)) ?: it.x, unit(SketchLengths.tidy(it.y.value + dy)) ?: it.y) }, base = ext.base.copy(version = ext.base.version + 1))
            scene = scene.replace(moved).getOrElse { scene }
            changed += ext.id
        }
        if (text != null) {
            val words = SketchLengths.feetInches(value)
            val (w, h) = textBox(words, text.fontSize)
            val cx = text.x.value + text.width.value / 2 + dx / 2
            val cy = text.y.value + text.height.value / 2 + dy / 2
            val label = text.copy(text = words, originalText = words, x = unit(SketchLengths.tidy(cx - w / 2)) ?: text.x, y = unit(SketchLengths.tidy(cy - h / 2)) ?: text.y, width = unit(w) ?: text.width, height = unit(h) ?: text.height, base = text.base.copy(version = text.base.version + 1))
            scene = scene.replace(label).getOrElse { scene }
            changed += label.id
        }
        return settle(Applied(doc.copy(scene = scene), changed), stamp = true, solve = true, note = "authored dimension re-laid to ${SketchLengths.feetInches(value)}")
    }

    /** Flips a dimension between driving (it moves geometry) and reference (it only reports): the "reference vs driving" hook. */
    private fun setReference(doc: SketchDocumentV2, o: JsonObject): Step {
        val ref = str(o, "id") ?: return Step.Refused("set-reference needs \"id\" (a constraint id)")
        val flag = str(o, "reference") ?: return Step.Refused("set-reference needs \"reference\": true (reference) or false (driving)")
        if (flag != "true" && flag != "false") return Step.Refused("\"reference\" must be true or false")
        if (doc.constraints.none { it.id.value == ref }) return Step.Refused("no constraint \"$ref\"")
        return run(doc, SetConstraintReference(ConstraintId(ref), flag == "true"), stamp = false)
    }

    /** A driving dimension changes constraint values and solver-moved elements: stamp the moved ones so the review shows them. */
    private fun markDimension(before: SketchDocumentV2, step: Step, ref: String, o: JsonObject): Step {
        if (step !is Step.Done) return step
        val moved = step.changed.filter { before.scene[it] != step.doc.scene[it] }.toSet()
        return Step.Done(stamp(step.doc, moved, o), step.changed, stamp = false, note = "${step.note}; constraint $ref set")
    }

    // Audit

    private fun summaries(doc: SketchDocumentV2, ids: Set<ElementId>): Map<String, JsonObject?> =
        ids.associate { it.value to doc.scene[it]?.let(::summary) }

    private fun summary(e: SceneElement): JsonObject = buildJsonObject {
        put("kind", ElementNames.kindWord(e))
        put("name", ElementNames.nameOf(e))
        put("layer", e.base.layerId.value)
        SceneBounds.of(listOf(e))?.let { b ->
            putJsonArray("box") { listOf(b.minX, b.minY, b.width, b.height).forEach { add(JsonPrimitive(SketchLengths.tidy(it))) } }
        }
        if (e is TextElement) put("text", e.text)
        e.base.provenance?.let { p ->
            putJsonObject("provenance") {
                put("source", p.source)
                p.confidence?.let { put("confidence", it) }
                p.note?.let { put("note", it) }
                if (p.accepted) put("accepted", true)
            }
        }
    }

    /** The audit trail: who edited, what each edit did to which elements (before and after), and what was refused. Units are scene units (1/16 inch). */
    fun audit(request: EditRequest, outcome: EditOutcome, input: String, shaBefore: String, shaAfter: String?, dryRun: Boolean, at: String): JsonObject = buildJsonObject {
        put("tool", "sketch-cli edit")
        put("source", Provenance.AGENT)
        request.agent?.let { put("agent", it) }
        put("at", at)
        put("input", input)
        put("dryRun", dryRun)
        put("written", shaAfter != null && !dryRun)
        put("sheetSha256Before", shaBefore)
        if (shaAfter != null) put("sheetSha256After", shaAfter) else put("sheetSha256After", JsonNull)
        putJsonArray("applied") {
            for (a in outcome.applied) add(
                buildJsonObject {
                    put("index", a.index)
                    put("op", a.op)
                    a.target?.let { put("target", it) }
                    put("source", Provenance.AGENT)
                    putJsonArray("elements") { a.ids.forEach { add(JsonPrimitive(it)) } }
                    putJsonObject("before") { a.before.forEach { (k, v) -> put(k, v ?: JsonNull) } }
                    putJsonObject("after") { a.after.forEach { (k, v) -> put(k, v ?: JsonNull) } }
                    a.note?.let { put("note", it) }
                },
            )
        }
        putJsonArray("refused") {
            for (r in outcome.refused) add(
                buildJsonObject {
                    put("index", r.index)
                    put("op", r.op)
                    put("reason", r.reason)
                    putJsonArray("conflicts") { r.conflicts.forEach { add(JsonPrimitive(it)) } }
                },
            )
        }
    }
}
