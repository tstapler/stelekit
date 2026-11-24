// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:Suppress("MagicNumber", "ReturnCount", "TooManyFunctions", "LongMethod", "CyclomaticComplexMethod", "LargeClass", "NestedBlockDepth", "ComplexCondition")

package dev.stapler.stelekit.cli

import arrow.core.getOrElse
import dev.stapler.stelekit.sketch.v2.codec.CanonicalJson
import dev.stapler.stelekit.sketch.v2.commands.SheetKindExtras
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.ZKeys
import dev.stapler.stelekit.sketch.v2.constraints.SheetKind
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
import dev.stapler.stelekit.sketch.v2.scene.GroupId
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.LineElement
import dev.stapler.stelekit.sketch.v2.scene.NameCounters
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ScenePoint
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.StrokeStyle
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import dev.stapler.stelekit.sketch.v2.scene.reconcile
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** The result of expanding a spec: a document, or the errors that stopped it; warnings accompany either. */
internal class SpecOutcome(val doc: SketchDocumentV2?, val issues: SpecIssues, val inchesPerPixel: Double?)

/** Layer ids the builder writes; the describer and renderer recognise them by these names. */
internal object SketchLayers {
    const val OPENINGS = "openings"
    const val DIMENSIONS = "dimensions"
}

/** A shape the spec placed, kept so later entries (arrows, openings, dimensions) can refer to it by id. */
internal class Placed(val id: String, val kind: String, val x: Double, val y: Double, val w: Double, val h: Double, val thickness: Double) {
    val cx: Double get() = x + w / 2
    val cy: Double get() = y + h / 2
}

/**
 * Expands a spec (see `docs/reference/sketch-format-v2.md`, "Authoring spec") into a real [SketchDocumentV2]. The output
 * goes through the same `reconcile` pass and codec as an edited sheet, so a built sheet is valid by construction.
 * Entries are processed in the order of the keys below; every error names its JSON path.
 */
internal class SketchSpecBuilder(private val root: JsonObject) {
    private val issues = SpecIssues()
    private val elements = ArrayList<SceneElement>()
    private val names = HashSet<String>()
    private val ids = HashSet<String>()
    private val placed = LinkedHashMap<String, Placed>()
    private val layersUsed = LinkedHashSet<String>()
    private var autoId = 0
    private lateinit var reader: SpecReader
    private lateinit var real: SpecReader
    private var mode = SpecMode.Plan
    private val plan: Boolean get() = mode == SpecMode.Plan

    private val thin: Double get() = if (plan) 16.0 else 1.0
    private val defaultFont: Double get() = if (plan) 96.0 else 20.0
    private val dimFont: Double get() = if (plan) 64.0 else 14.0
    private var wall = 72.0

    fun build(): SpecOutcome {
        checkKeys(root, "spec", TOP_KEYS)
        if ((root["spec"] as? JsonPrimitive)?.content?.toIntOrNull() != 1) issues.error("spec", "must be the number 1 (this tool reads spec version 1)")
        val modeWire = (root["mode"] as? JsonPrimitive)?.content ?: "plan"
        mode = SpecMode.of(modeWire) ?: SpecMode.Plan.also { issues.error("mode", "must be \"plan\" or \"diagram\", got \"$modeWire\"") }
        val photo = (root["space"] as? JsonPrimitive)?.content == "photo"
        var inchesPerPx: Double? = null
        var snap: Double? = null
        if (photo) {
            val cal = root["calibration"] as? JsonObject
            SpecReader.calibrate(cal, issues)?.let { (scale, spread) ->
                inchesPerPx = scale
                issues.notes += "calibration: 1 px = ${fmtScale(scale)} in (${SketchLengths.feetInches(scale * 16 * 1000)} per 1000 px); references disagree by ${(spread * 100).roundToInt()}%"
                if (spread > CALIBRATION_WARN) issues.warn("calibration.refs", "the references disagree by ${(spread * 100).roundToInt()}% (perspective or a mis-read point); re-measure or use refs on the same wall plane")
            }
            val snapText = (cal?.get("snap") as? JsonPrimitive)?.content ?: "1\""
            snap = if (snapText == "none") null else SketchLengths.parseInches(snapText)?.times(SketchLengths.UNITS_PER_INCH)
        } else if (root["calibration"] != null) {
            issues.warn("calibration", "ignored: calibration only applies when \"space\" is \"photo\"")
        }
        reader = SpecReader(issues, mode, inchesPerPx, snap)
        // Thicknesses, font sizes, gaps and offsets are real lengths even in photo space: `reader` would read a bare number as pixels.
        real = SpecReader(issues, mode, null, null)
        root["wall"]?.let { w -> wall = real.length(w, "wall") ?: wall }
        if (plan && root["wall"] == null) issues.notes += "wall thickness defaults to 4 1/2\" (set \"wall\" to change)"

        (root["shapes"] as? JsonArray)?.forEachIndexed { i, s -> shape(s, "shapes[$i]") }
        (root["lines"] as? JsonArray)?.forEachIndexed { i, s -> line(s, "lines[$i]") }
        (root["openings"] as? JsonArray)?.forEachIndexed { i, s -> opening(s, "openings[$i]") }
        (root["arrows"] as? JsonArray)?.forEachIndexed { i, s -> arrow(s, "arrows[$i]") }
        (root["labels"] as? JsonArray)?.forEachIndexed { i, s -> label(s, "labels[$i]") }
        (root["dimensions"] as? JsonArray)?.forEachIndexed { i, s -> dimension(s, "dimensions[$i]") }
        if (elements.isEmpty() && issues.errors.isEmpty()) issues.error("spec", "nothing to draw: add at least one of shapes, lines, arrows, labels")
        if (issues.errors.isNotEmpty()) return SpecOutcome(null, issues, inchesPerPx)
        return SpecOutcome(assemble(), issues, inchesPerPx)
    }

    private fun fmtScale(v: Double): String = (round4(v * 1000) / 1000).toString()

    private fun round4(v: Double): Double = SketchLengths.tidy(v)

    // Shapes

    private fun shape(el: JsonElement, path: String) {
        val o = el as? JsonObject ?: return issues.error(path, "expected an object, got ${SpecReader.describeJson(el)}")
        checkKeys(o, path, SHAPE_KEYS)
        val id = idOf(o, path, "shape") ?: return
        val kind = (o["kind"] as? JsonPrimitive)?.content ?: "rect"
        if (kind !in setOf("rect", "ellipse", "diamond")) return issues.error("$path.kind", "must be rect, ellipse or diamond, got \"$kind\"")
        val w = reader.length(o["w"], "$path.w") ?: return
        val h = reader.length(o["h"], "$path.h") ?: return
        if (w <= 0.0 || h <= 0.0) return issues.error(path, "w and h must be greater than zero (got ${fmt(w)} x ${fmt(h)})")
        val xy = position(o, path, id, w, h) ?: return
        val thickness = if (o["thickness"] != null) real.length(o["thickness"], "$path.thickness") ?: wall else defaultThickness(o)
        val role = (o["role"] as? JsonPrimitive)?.content
        if (role != null && role != "room" && role != "furniture") return issues.error("$path.role", "must be room or furniture, got \"$role\"")
        val stroke = color(o["stroke"], "$path.stroke", if (furniture(o)) "#495057" else "#1e1e1e")
        val fill = color(o["fill"], "$path.fill", "transparent")
        val dashed = (o["dashed"] as? JsonPrimitive)?.content == "true"
        val base = base(id, LayerId.Default.value, ElementStyle(stroke, fill, FillStyle.Solid, thickness, if (dashed) StrokeStyle.Dashed else StrokeStyle.Solid, 0), named = true)
        val (x, y) = xy
        val e: SceneElement = when (kind) {
            "ellipse" -> EllipseElement(base, u(x + w / 2), u(y + h / 2), u(w / 2), u(h / 2))
            "diamond" -> DiamondElement(base, u(x), u(y), u(w), u(h))
            else -> RectElement(base, u(x), u(y), u(w), u(h))
        }
        add(e)
        placed[id] = Placed(id, kind, x, y, w, h, thickness)
        (o["label"] as? JsonPrimitive)?.content?.let { text ->
            val fs = o["fontSize"]?.let { real.length(it, "$path.fontSize") } ?: defaultFont
            val inner = w - thickness
            labelIn(id, text, x + w / 2, y + h / 2, fs, inner, h - thickness, path)
        }
    }

    private fun furniture(o: JsonObject) = (o["role"] as? JsonPrimitive)?.content == "furniture"

    private fun defaultThickness(o: JsonObject): Double = if (!plan) 2.0 else if (furniture(o)) thin else wall

    private fun position(o: JsonObject, path: String, id: String, w: Double, h: Double): Pair<Double, Double>? {
        val rightOf = (o["rightOf"] as? JsonPrimitive)?.content
        val below = (o["below"] as? JsonPrimitive)?.content
        val gap = o["gap"]?.let { real.length(it, "$path.gap") } ?: if (plan) 0.0 else 60.0
        var x = o["x"]?.let { reader.length(it, "$path.x") }
        var y = o["y"]?.let { reader.length(it, "$path.y") }
        if (o["x"] != null && x == null || o["y"] != null && y == null) return null
        if (rightOf != null) {
            val ref = placed[rightOf] ?: return issues.error("$path.rightOf", "unknown shape \"$rightOf\"; it must be listed earlier in shapes").let { null }
            if (x == null) x = ref.x + ref.w + gap
            if (y == null) y = ref.cy - h / 2
        }
        if (below != null) {
            val ref = placed[below] ?: return issues.error("$path.below", "unknown shape \"$below\"; it must be listed earlier in shapes").let { null }
            if (y == null) y = ref.y + ref.h + gap
            if (x == null) x = ref.cx - w / 2
        }
        if (x == null || y == null) {
            issues.error(path, "shape \"$id\" needs x and y (top-left corner), or rightOf / below another shape")
            return null
        }
        return x to y
    }

    // Free lines and polygons

    private fun line(el: JsonElement, path: String) {
        val o = el as? JsonObject ?: return issues.error(path, "expected an object, got ${SpecReader.describeJson(el)}")
        checkKeys(o, path, LINE_KEYS)
        val id = idOf(o, path, "line") ?: return
        val closed = (o["closed"] as? JsonPrimitive)?.content == "true"
        val pts = reader.points(o["points"], "$path.points", if (closed) 3 else 2) ?: return
        val thickness = if (o["thickness"] != null) real.length(o["thickness"], "$path.thickness") ?: wall else if (plan) wall else 2.0
        val dashed = (o["dashed"] as? JsonPrimitive)?.content == "true"
        val style = ElementStyle(
            color(o["stroke"], "$path.stroke", "#1e1e1e"), color(o["fill"], "$path.fill", "transparent"), FillStyle.Solid,
            thickness, if (dashed) StrokeStyle.Dashed else StrokeStyle.Solid, 0,
        )
        add(LineElement(base(id, LayerId.Default.value, style, named = true), pts.map(::pt), closed))
        (o["label"] as? JsonPrimitive)?.content?.let { text ->
            val fs = o["fontSize"]?.let { real.length(it, "$path.fontSize") } ?: defaultFont
            labelFree("$id.label", text, pts.map { it.first }.average(), pts.map { it.second }.average(), fs, "center", "#1e1e1e", LayerId.Default.value, null)
        }
    }

    // Doors and windows

    private fun opening(el: JsonElement, path: String) {
        val o = el as? JsonObject ?: return issues.error(path, "expected an object, got ${SpecReader.describeJson(el)}")
        checkKeys(o, path, OPENING_KEYS)
        val id = idOf(o, path, "opening") ?: return
        val type = (o["type"] as? JsonPrimitive)?.content
        if (type != "door" && type != "window") return issues.error("$path.type", "must be \"door\" or \"window\", got ${SpecReader.describeJson(o["type"])}")
        val onId = (o["on"] as? JsonPrimitive)?.content ?: return issues.error("$path.on", "name the shape (room) whose wall holds the opening")
        val room = placed[onId] ?: return issues.error("$path.on", "unknown shape \"$onId\"; list the room in shapes first")
        if (room.kind != "rect") return issues.error("$path.on", "\"$onId\" is a ${room.kind}; openings go on rect shapes")
        val side = (o["wall"] as? JsonPrimitive)?.content
        if (side !in setOf("north", "south", "east", "west")) return issues.error("$path.wall", "must be north, south, east or west, got ${SpecReader.describeJson(o["wall"])}")
        val offset = reader.length(o["offset"], "$path.offset") ?: return
        val width = if (o["width"] == null) defaultOpeningWidth(type).also { issues.notes += "$path: width defaults to ${SketchLengths.feetInches(it)}" } else reader.length(o["width"], "$path.width") ?: return
        if (width <= 0.0) return issues.error("$path.width", "must be greater than zero")
        val wallLength = if (side == "north" || side == "south") room.w else room.h
        if (offset < 0.0 || offset + width > wallLength + 0.5) {
            issues.error(path, "the opening spans ${SketchLengths.feetInches(offset)} to ${SketchLengths.feetInches(offset + width)} but the $side wall of \"$onId\" is only ${SketchLengths.feetInches(wallLength)} long")
            return
        }
        val geometry = OpeningGeometry(room, side!!, offset, width)
        val th = room.thickness
        val onLayer = SketchLayers.OPENINGS
        val gapStyle = ElementStyle("transparent", "#ffffff", FillStyle.Solid, 0.0, StrokeStyle.Solid, 0)
        val frameStyle = ElementStyle("#1e1e1e", "#ffffff", FillStyle.Solid, thin, StrokeStyle.Solid, 0)
        val group = listOf(GroupId(id))
        if (type == "window") {
            val g = geometry.box(th + 2.0)
            add(RectElement(base("$id.frame", onLayer, frameStyle, groups = group), u(g.x), u(g.y), u(g.w), u(g.h)))
            val c = geometry.centerLine()
            add(LineElement(base("$id.glass", onLayer, ElementStyle("#1e1e1e", "transparent", FillStyle.Solid, thin, StrokeStyle.Solid, 0), groups = group), listOf(pt(c.ax to c.ay), pt(c.bx to c.by))))
            return
        }
        val inward = (o["swing"] as? JsonPrimitive)?.content != "out"
        val hingeStart = (o["hinge"] as? JsonPrimitive)?.content != "end"
        val gap = geometry.box(th + 2.0)
        add(RectElement(base("$id.gap", onLayer, gapStyle, groups = group), u(gap.x), u(gap.y), u(gap.w), u(gap.h)))
        val leaf = geometry.leaf(inward, hingeStart)
        add(LineElement(base("$id.leaf", onLayer, ElementStyle("#1e1e1e", "transparent", FillStyle.Solid, thin * 1.5, StrokeStyle.Solid, 0), groups = group), listOf(pt(leaf.hx to leaf.hy), pt(leaf.tipX to leaf.tipY))))
        val a0 = atan2(leaf.closedY - leaf.hy, leaf.closedX - leaf.hx)
        val a1 = atan2(leaf.tipY - leaf.hy, leaf.tipX - leaf.hx)
        val cross = (leaf.closedX - leaf.hx) * (leaf.tipY - leaf.hy) - (leaf.closedY - leaf.hy) * (leaf.tipX - leaf.hx)
        val sweep = if (cross > 0) a0 to a1 else a1 to a0
        add(ArcElement(base("$id.swing", onLayer, ElementStyle("#868e96", "transparent", FillStyle.Solid, thin, StrokeStyle.Dashed, 0), groups = group), u(leaf.hx), u(leaf.hy), u(width), sweep.first, sweep.second))
    }

    private fun defaultOpeningWidth(type: String): Double = (if (type == "door") DOOR_INCHES else WINDOW_INCHES) * SketchLengths.UNITS_PER_INCH

    // Arrows

    private fun arrow(el: JsonElement, path: String) {
        val o = el as? JsonObject ?: return issues.error(path, "expected an object, got ${SpecReader.describeJson(el)}")
        checkKeys(o, path, ARROW_KEYS)
        val id = idOf(o, path, "arrow") ?: return
        val fromId = (o["from"] as? JsonPrimitive)?.content
        val toId = (o["to"] as? JsonPrimitive)?.content
        val from = fromId?.let { placed[it] ?: return issues.error("$path.from", "unknown shape \"$it\"; list it in shapes first") }
        val to = toId?.let { placed[it] ?: return issues.error("$path.to", "unknown shape \"$it\"; list it in shapes first") }
        if (from != null && from === to) return issues.error(path, "from and to are the same shape \"$fromId\"")
        val via = o["via"]?.let { reader.points(it, "$path.via", 1) ?: return } ?: emptyList()
        val free = o["points"]?.let { reader.points(it, "$path.points", 2) ?: return }
        val pts: List<Pair<Double, Double>> = when {
            free != null -> free
            from != null && to != null -> {
                val firstTarget = via.firstOrNull() ?: (to.cx to to.cy)
                val lastSource = via.lastOrNull() ?: (from.cx to from.cy)
                listOf(exit(from, firstTarget.first, firstTarget.second)) + via + listOf(exit(to, lastSource.first, lastSource.second))
            }
            else -> return issues.error(path, "give from and to (shape ids) or points [[x, y], [x, y]]")
        }
        val head = (o["head"] as? JsonPrimitive)?.content ?: "end"
        if (head !in setOf("end", "start", "both", "none")) return issues.error("$path.head", "must be end, start, both or none, got \"$head\"")
        val dashed = (o["dashed"] as? JsonPrimitive)?.content == "true"
        val style = ElementStyle(color(o["stroke"], "$path.stroke", "#1e1e1e"), "transparent", FillStyle.Solid, if (plan) thin * 2 else 2.0, if (dashed) StrokeStyle.Dashed else StrokeStyle.Solid, 0)
        val arrow = ArrowElement(
            base(id, LayerId.Default.value, style, named = true), pts.map(::pt),
            if (free == null) from?.let { binding(it, pts.first()) } else null,
            if (free == null) to?.let { binding(it, pts.last()) } else null,
            if (head == "start" || head == "both") Arrowhead.Arrow else Arrowhead.None,
            if (head == "end" || head == "both") Arrowhead.Arrow else Arrowhead.None,
        )
        add(arrow)
        (o["label"] as? JsonPrimitive)?.content?.let { text ->
            val seg = if (pts.size == 2) 0 else pts.size / 2 - 1
            val (ax, ay) = pts[seg]
            val (bx, by) = pts[seg + 1]
            val fs = o["fontSize"]?.let { real.length(it, "$path.fontSize") } ?: (if (plan) dimFont else 14.0)
            val horizontal = abs(bx - ax) >= abs(by - ay)
            val dx = if (horizontal) 0.0 else fs * 0.6 * text.length / 2 + fs * 0.5
            val dy = if (horizontal) -(fs * 0.9) else 0.0
            labelFree("$id.label", text, (ax + bx) / 2 + dx, (ay + by) / 2 + dy, fs, "center", "#1e1e1e", LayerId.Default.value, id)
        }
    }

    private fun binding(shape: Placed, p: Pair<Double, Double>): Binding =
        Binding(ElementId(shape.id), ((p.first - shape.x) / shape.w).coerceIn(0.0, 1.0), ((p.second - shape.y) / shape.h).coerceIn(0.0, 1.0), BindingMode.Orbit)

    /** Where the ray from [s]'s centre toward (tx, ty) leaves the shape's outline. */
    private fun exit(s: Placed, tx: Double, ty: Double): Pair<Double, Double> {
        val dx = tx - s.cx
        val dy = ty - s.cy
        if (dx == 0.0 && dy == 0.0) return s.cx to s.cy
        val hw = s.w / 2
        val hh = s.h / 2
        val t = when (s.kind) {
            "ellipse" -> 1.0 / hypot(dx / hw, dy / hh)
            "diamond" -> 1.0 / (abs(dx) / hw + abs(dy) / hh)
            else -> 1.0 / max(abs(dx) / hw, abs(dy) / hh)
        }
        return (s.cx + dx * t) to (s.cy + dy * t)
    }

    // Labels and dimensions

    private fun label(el: JsonElement, path: String) {
        val o = el as? JsonObject ?: return issues.error(path, "expected an object, got ${SpecReader.describeJson(el)}")
        checkKeys(o, path, LABEL_KEYS)
        val id = (o["id"] as? JsonPrimitive)?.content?.also { if (!validId(it)) return issues.error("$path.id", ID_RULE) } ?: "label${++autoId}"
        val text = (o["text"] as? JsonPrimitive)?.content ?: return issues.error("$path.text", "expected the label text")
        val at = reader.point(o["at"], "$path.at") ?: return
        val fs = o["size"]?.let { real.length(it, "$path.size") } ?: defaultFont
        val align = (o["align"] as? JsonPrimitive)?.content ?: "center"
        if (align !in setOf("left", "center", "right")) return issues.error("$path.align", "must be left, center or right, got \"$align\"")
        labelFree(id, text, at.first, at.second, fs, align, color(o["color"], "$path.color", "#1e1e1e"), LayerId.Default.value, null, named = o["id"] != null)
    }

    private fun dimension(el: JsonElement, path: String) {
        val o = el as? JsonObject ?: return issues.error(path, "expected an object, got ${SpecReader.describeJson(el)}")
        checkKeys(o, path, DIM_KEYS)
        val id = (o["id"] as? JsonPrimitive)?.content?.also { if (!validId(it)) return issues.error("$path.id", ID_RULE) } ?: "dim${++autoId}"
        val offset = o["offset"]?.let { real.length(it, "$path.offset") } ?: (if (plan) 24.0 * 16 else 40.0)
        val a: Pair<Double, Double>
        val b: Pair<Double, Double>
        var sign = 1.0
        val shapeId = (o["shape"] as? JsonPrimitive)?.content
        if (shapeId != null) {
            val s = placed[shapeId] ?: return issues.error("$path.shape", "unknown shape \"$shapeId\"; list it in shapes first")
            val side = (o["side"] as? JsonPrimitive)?.content ?: "north"
            when (side) {
                "north" -> { a = s.x to s.y; b = (s.x + s.w) to s.y; sign = -1.0 }
                "south" -> { a = s.x to (s.y + s.h); b = (s.x + s.w) to (s.y + s.h) }
                "west" -> { a = s.x to s.y; b = s.x to (s.y + s.h); sign = -1.0 }
                "east" -> { a = (s.x + s.w) to s.y; b = (s.x + s.w) to (s.y + s.h) }
                else -> return issues.error("$path.side", "must be north, south, east or west, got \"$side\"")
            }
        } else {
            a = reader.point(o["from"], "$path.from") ?: return
            b = reader.point(o["to"], "$path.to") ?: return
        }
        if (hypot(b.first - a.first, b.second - a.second) < 1.0) return issues.error(path, "the two ends are the same point")
        val text = (o["text"] as? JsonPrimitive)?.content
        DimensionMarks(markSink, plan, thin, dimFont).emit(id, a, b, sign * offset, text)
    }

    private val markSink = object : MarkSink {
        override fun add(e: SceneElement) = this@SketchSpecBuilder.add(e)

        override fun text(id: String, text: String, cx: Double, cy: Double, fs: Double, color: String, groups: List<GroupId>) =
            labelFree(id, text, cx, cy, fs, "center", color, SketchLayers.DIMENSIONS, null, groups = groups)
    }

    // Element plumbing

    private fun labelIn(containerId: String, text: String, cx: Double, cy: Double, fs: Double, maxW: Double, maxH: Double, path: String) {
        val wrapped = wrap(text, maxW, fs)
        val (w, h) = textSize(wrapped, fs)
        if (w > maxW + 1.0 || h > maxH + 1.0) issues.warn(path, "label \"$text\" (${SketchLengths.feetInches(w)} x ${SketchLengths.feetInches(h)}) is bigger than its shape; shorten it or set a smaller fontSize")
        add(
            TextElement(
                base("$containerId.label", LayerId.Default.value, ElementStyle(strokeColor = "#1e1e1e", roughness = 0)),
                u(cx - w / 2), u(cy - h / 2), u(w), u(h), 0.0, wrapped, text, fs, FONT_FAMILY, TextAlign.Center, VerticalAlign.Middle,
                ElementId(containerId), TextElement.DEFAULT_LINE_HEIGHT, true,
            ),
        )
    }

    internal fun labelFree(id: String, text: String, cx: Double, cy: Double, fs: Double, align: String, color: String, layer: String, container: String?, named: Boolean = false, groups: List<GroupId> = emptyList()) {
        val (w, h) = textSize(text, fs)
        val x = when (align) { "left" -> cx; "right" -> cx - w; else -> cx - w / 2 }
        val ta = when (align) { "left" -> TextAlign.Left; "right" -> TextAlign.Right; else -> TextAlign.Center }
        add(
            TextElement(
                base(id, layer, ElementStyle(strokeColor = color, roughness = 0), named = named, groups = groups),
                u(x), u(cy - h / 2), u(w), u(h), 0.0, text, text, fs, FONT_FAMILY, ta, VerticalAlign.Middle,
                container?.let(::ElementId), TextElement.DEFAULT_LINE_HEIGHT, true,
            ),
        )
    }

    private fun wrap(text: String, maxW: Double, fs: Double): String {
        val perLine = max(1, floor(maxW / (fs * CHAR_WIDTH)).toInt())
        return text.split('\n').joinToString("\n") { para ->
            val lines = ArrayList<String>()
            var cur = ""
            for (word in para.split(' ')) {
                val candidate = if (cur.isEmpty()) word else "$cur $word"
                if (candidate.length > perLine && cur.isNotEmpty()) { lines += cur; cur = word } else cur = candidate
            }
            lines += cur
            lines.joinToString("\n")
        }
    }

    internal fun textSize(text: String, fs: Double): Pair<Double, Double> {
        val lines = text.split('\n')
        return round4(lines.maxOf { it.length } * fs * CHAR_WIDTH) to round4(lines.size * fs * TextElement.DEFAULT_LINE_HEIGHT)
    }

    private fun base(id: String, layer: String, style: ElementStyle, named: Boolean = false, groups: List<GroupId> = emptyList()) =
        ElementBase(ElementId(id), LayerId(layer), groupIds = groups, style = style, seed = id.hashCode() and Int.MAX_VALUE, name = id.takeIf { named })

    private fun add(e: SceneElement) {
        if (!ids.add(e.id.value)) {
            issues.error("id", "the id \"${e.id.value}\" is used twice (derived ids like <id>.label count); make every id unique")
            return
        }
        layersUsed += e.base.layerId.value
        elements += e
    }

    private fun idOf(o: JsonObject, path: String, kind: String): String? {
        val raw = (o["id"] as? JsonPrimitive)?.content
        if (raw == null) {
            issues.error("$path.id", "every $kind needs an id (letters, digits, - _ .)")
            return null
        }
        if (!validId(raw)) {
            issues.error("$path.id", ID_RULE)
            return null
        }
        if (!names.add(raw)) {
            issues.error("$path.id", "the id \"$raw\" is used twice")
            return null
        }
        return raw
    }

    private fun validId(id: String) = ID_PATTERN.matches(id)

    private fun color(el: JsonElement?, path: String, default: String): String {
        val text = (el as? JsonPrimitive)?.content ?: return default
        val named = COLORS[text.lowercase()]
        if (named != null) return named
        if (text == "transparent" || HEX.matches(text)) return text
        issues.error(path, "\"$text\" is not a colour; use #rrggbb, transparent, or one of ${COLORS.keys.joinToString()}")
        return default
    }

    private fun pt(p: Pair<Double, Double>) = ScenePoint(u(p.first), u(p.second))

    private fun u(d: Double): SceneUnit = SceneUnit.of(round4(d)).getOrElse {
        issues.error("coordinates", "the value ${fmt(d)} is outside the sheet's range (about +-16.7 million units); check the units and the calibration")
        SceneUnit.ZERO
    }

    private fun fmt(d: Double) = round4(d).toString()

    private fun checkKeys(o: JsonObject, path: String, allowed: Set<String>) {
        for (k in o.keys) if (k !in allowed) issues.warn("$path.$k", "unknown key ignored (allowed here: ${allowed.sorted().joinToString()})")
    }

    // Assembly

    private fun assemble(): SketchDocumentV2 {
        val keys = ZKeys.sequence(elements.size)
        val keyed = elements.mapIndexed { i, e -> e.withBase(e.base.copy(zKey = keys[i])) }
        val (named, counters) = NameCounters.Empty.assign(keyed)
        val layers = listOf(Layer(LayerId.Default, if (plan) "Plan" else "Diagram", order = 0)) +
            (if (SketchLayers.OPENINGS in layersUsed) listOf(Layer(LayerId(SketchLayers.OPENINGS), "Openings", order = 1)) else emptyList()) +
            (if (SketchLayers.DIMENSIONS in layersUsed) listOf(Layer(LayerId(SketchLayers.DIMENSIONS), "Dimensions", order = 2)) else emptyList())
        var scene = SketchSceneV2.empty().withLayers(layers)
        for (e in named) scene = scene.add(e).getOrElse { err ->
            issues.error("elements", "could not add \"${e.id.value}\": $err")
            scene
        }
        val title = (root["title"] as? JsonPrimitive)?.content
        val agent = buildJsonObject {
            put("mode", mode.wire)
            put("spec", 1)
            title?.let { put("title", it) }
        }
        var extras = JsonObject(mapOf("agent" to agent))
        if (plan) extras = SheetKindExtras.write(extras, SheetKind.Technical)
        val uuid = (root["sheetUuid"] as? JsonPrimitive)?.content ?: uuidOf(root)
        return SketchDocumentV2(reconcile(scene).scene, uuid, emptyList(), extras, counters)
    }

    private fun uuidOf(spec: JsonObject): String {
        val hex = ContentHasher.sha256(CanonicalJson.encode(spec).getOrElse { spec.toString().encodeToByteArray() }.decodeToString())
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-4${hex.substring(13, 16)}-8${hex.substring(17, 20)}-${hex.substring(20, 32)}"
    }

    /** Opening geometry on one wall of a rect room; wall coordinates run west to east on north/south walls and north to south on east/west walls. */
    private class OpeningGeometry(val room: Placed, val side: String, val offset: Double, val width: Double) {
        private val horizontal = side == "north" || side == "south"
        private val wallX: Double = if (side == "east") room.x + room.w else room.x
        private val wallY: Double = if (side == "south") room.y + room.h else room.y
        private val inwardX: Double = when (side) { "west" -> 1.0; "east" -> -1.0; else -> 0.0 }
        private val inwardY: Double = when (side) { "north" -> 1.0; "south" -> -1.0; else -> 0.0 }
        private val alongX: Double = if (horizontal) 1.0 else 0.0
        private val alongY: Double = if (horizontal) 0.0 else 1.0

        fun box(thickness: Double): Rect4 {
            val sx = wallX + alongX * offset
            val sy = wallY + alongY * offset
            return if (horizontal) Rect4(sx, sy - thickness / 2, width, thickness) else Rect4(sx - thickness / 2, sy, thickness, width)
        }

        fun centerLine(): Seg = Seg(wallX + alongX * offset, wallY + alongY * offset, wallX + alongX * (offset + width), wallY + alongY * (offset + width))

        fun leaf(inward: Boolean, hingeStart: Boolean): Leaf {
            val dir = if (inward) 1.0 else -1.0
            val hingeAt = if (hingeStart) offset else offset + width
            val hx = wallX + alongX * hingeAt
            val hy = wallY + alongY * hingeAt
            val closedSign = if (hingeStart) 1.0 else -1.0
            return Leaf(
                hx, hy, hx + inwardX * dir * width, hy + inwardY * dir * width,
                hx + alongX * closedSign * width, hy + alongY * closedSign * width,
            )
        }
    }

    internal class Rect4(val x: Double, val y: Double, val w: Double, val h: Double)

    internal class Seg(val ax: Double, val ay: Double, val bx: Double, val by: Double)

    /** A door leaf: hinge, the tip of the open leaf, and the tip of the closed leaf. */
    internal class Leaf(val hx: Double, val hy: Double, val tipX: Double, val tipY: Double, val closedX: Double, val closedY: Double)

    private companion object {
        const val CALIBRATION_WARN = 0.05
        const val DOOR_INCHES = 36.0
        const val WINDOW_INCHES = 48.0
        const val CHAR_WIDTH = 0.55
        const val FONT_FAMILY = 1
        const val ID_RULE = "an id may use letters, digits, '-', '_' and '.' (1 to 64 characters)"
        val ID_PATTERN = Regex("[A-Za-z0-9_.-]{1,64}")
        val HEX = Regex("#[0-9a-fA-F]{6}")
        val COLORS = mapOf(
            "black" to "#1e1e1e", "white" to "#ffffff", "gray" to "#868e96", "red" to "#e03131", "blue" to "#1971c2",
            "green" to "#2f9e44", "yellow" to "#f08c00", "orange" to "#e8590c", "lightblue" to "#a5d8ff", "lightgreen" to "#b2f2bb", "lightyellow" to "#ffec99",
        )
        val TOP_KEYS = setOf("spec", "mode", "title", "space", "calibration", "wall", "sheetUuid", "shapes", "lines", "openings", "arrows", "labels", "dimensions")
        val SHAPE_KEYS = setOf("id", "kind", "x", "y", "w", "h", "rightOf", "below", "gap", "label", "fontSize", "fill", "stroke", "thickness", "dashed", "role")
        val LINE_KEYS = setOf("id", "points", "closed", "thickness", "stroke", "fill", "dashed", "label", "fontSize")
        val OPENING_KEYS = setOf("id", "type", "on", "wall", "offset", "width", "swing", "hinge")
        val ARROW_KEYS = setOf("id", "from", "to", "via", "points", "label", "head", "dashed", "stroke", "fontSize")
        val LABEL_KEYS = setOf("id", "text", "at", "size", "align", "color")
        val DIM_KEYS = setOf("id", "shape", "side", "from", "to", "offset", "text")
    }
}

/** Where [DimensionMarks] puts what it makes: the spec builder, or the render overlay. */
internal interface MarkSink {
    fun add(e: SceneElement)

    fun text(id: String, text: String, cx: Double, cy: Double, fs: Double, color: String, groups: List<GroupId>)
}

/** Builds one dimension (two extension lines, a double-headed arrow, a value) as elements on the `dimensions` layer. */
internal class DimensionMarks(private val sink: MarkSink, private val plan: Boolean, private val thin: Double, private val font: Double) {
    /** [offset] moves the line perpendicular to a to b: toward +y for a mostly horizontal span, toward +x for a mostly vertical one. */
    fun emit(id: String, a: Pair<Double, Double>, b: Pair<Double, Double>, offset: Double, text: String?) {
        val horizontal = abs(b.first - a.first) >= abs(b.second - a.second)
        val nx = if (horizontal) 0.0 else 1.0
        val ny = if (horizontal) 1.0 else 0.0
        val sign = if (offset < 0) -1.0 else 1.0
        val gap = if (plan) 48.0 else 4.0
        val over = if (plan) 48.0 else 6.0
        val p1 = (a.first + nx * offset) to (a.second + ny * offset)
        val p2 = (b.first + nx * offset) to (b.second + ny * offset)
        val group = listOf(GroupId(id))
        val style = ElementStyle("#495057", "transparent", FillStyle.Solid, thin, StrokeStyle.Solid, 0)
        val layer = SketchLayers.DIMENSIONS
        fun pt(x: Double, y: Double) = ScenePoint(SceneUnit.of(SketchLengths.tidy(x)).getOrElse { SceneUnit.ZERO }, SceneUnit.of(SketchLengths.tidy(y)).getOrElse { SceneUnit.ZERO })
        fun baseOf(suffix: String) = ElementBase(ElementId("$id.$suffix"), LayerId(layer), groupIds = group, style = style, seed = "$id.$suffix".hashCode() and Int.MAX_VALUE)
        sink.add(LineElement(baseOf("ext1"), listOf(pt(a.first + nx * sign * gap, a.second + ny * sign * gap), pt(p1.first + nx * sign * over, p1.second + ny * sign * over))))
        sink.add(LineElement(baseOf("ext2"), listOf(pt(b.first + nx * sign * gap, b.second + ny * sign * gap), pt(p2.first + nx * sign * over, p2.second + ny * sign * over))))
        sink.add(ArrowElement(baseOf("line"), listOf(pt(p1.first, p1.second), pt(p2.first, p2.second)), null, null, Arrowhead.Arrow, Arrowhead.Arrow))
        val length = hypot(b.first - a.first, b.second - a.second)
        val shown = text ?: if (plan) SketchLengths.feetInches(length) else SketchLengths.tidy(length).toString().removeSuffix(".0")
        val mx = (p1.first + p2.first) / 2
        val my = (p1.second + p2.second) / 2
        val lift = font * (if (horizontal) 0.9 else 0.0)
        val shift = if (horizontal) 0.0 else sign * (font * 0.55 * shown.length / 2 + font * 0.6)
        sink.text("$id.text", shown, mx + shift, my + sign * lift, font, "#495057", group)
    }
}
