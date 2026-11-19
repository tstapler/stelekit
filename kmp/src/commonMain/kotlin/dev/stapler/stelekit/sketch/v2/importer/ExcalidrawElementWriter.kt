// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.importer

import dev.stapler.stelekit.sketch.v2.codec.CanonicalJson
import dev.stapler.stelekit.sketch.v2.scene.ArcElement
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.Arrowhead
import dev.stapler.stelekit.sketch.v2.scene.Binding
import dev.stapler.stelekit.sketch.v2.scene.BindingMode
import dev.stapler.stelekit.sketch.v2.scene.BoundKind
import dev.stapler.stelekit.sketch.v2.scene.BoundRef
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.ExcalidrawFill
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.FreedrawElement
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
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/** The scene around one element being written: what is kept (hidden layers are not), the layers, and the labels of arrows. */
internal class ElementContext(val scene: SketchSceneV2, val kept: Set<ElementId>, val arrowLabels: Map<ElementId, List<ElementId>>) {
    fun layer(e: SceneElement): Layer = scene.layer(e.base.layerId) ?: Layer.DefaultLayer
}

/** One element being written: the element, its fresh `index` key and the scene context. */
internal class ElementDraw(val e: SceneElement, val index: String, val ctx: ElementContext)

/** The geometry members every element has; a linear element gives its bounding box. */
private class Box(val x: Double, val y: Double, val width: Double, val height: Double, val angle: Double = 0.0)

/** A canonical number primitive; the writer never lets a platform `Double.toString` into the file. */
internal fun jsonNumber(d: Double): JsonPrimitive = CanonicalJson.number(d).getOrNull() ?: JsonPrimitive(0)

/** Writes one scene element as an Excalidraw element object (Story 5.1.3); [ExcalidrawExporter] orders, filters and wraps them. */
internal class ExcalidrawElementWriter(private val limits: SceneLimits) {
    // ---- elements

    fun write(d: ElementDraw): JsonObject = when (val e = d.e) {
        is RectElement -> shape(d, "rectangle", Box(e.x.value, e.y.value, e.width.value, e.height.value, e.angle), e.boundElements)
        is DiamondElement -> shape(d, "diamond", Box(e.x.value, e.y.value, e.width.value, e.height.value, e.angle), e.boundElements)
        is EllipseElement -> shape(d, "ellipse", Box(e.cx.value - e.rx.value, e.cy.value - e.ry.value, e.rx.value * 2, e.ry.value * 2, e.angle), e.boundElements)
        is FrameElement -> frame(d, e)
        is TextElement -> text(d, e)
        is ImageElement -> image(d, e)
        is LineElement -> linear(d, "line", e.points, null) { put("polygon", JsonPrimitive(e.polygon)) }
        is ArrowElement -> arrow(d, e)
        is FreedrawElement -> freedraw(d, e)
        is PointElement -> point(d, e)
        is ArcElement -> arc(d, e)
    }

    private fun shape(d: ElementDraw, type: String, box: Box, bound: List<BoundRef>): JsonObject {
        val unsupported = (d.e.base.extras[StelekitCustomData.EXTRA_UNSUPPORTED_TYPE] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return common(d, unsupported ?: type, box, boundRefs(bound, d.ctx), null)
    }

    private fun frame(d: ElementDraw, e: FrameElement): JsonObject {
        val members = common(d, "frame", Box(e.x.value, e.y.value, e.width.value, e.height.value, e.angle), emptyList(), null)
        return JsonObject(members + ("name" to (e.base.name?.let { JsonPrimitive(it) } ?: JsonNull)))
    }

    private fun text(d: ElementDraw, e: TextElement): JsonObject {
        val align = when (e.textAlign) {
            TextAlign.Left -> "left"
            TextAlign.Center -> "center"
            TextAlign.Right -> "right"
        }
        val vertical = when (e.verticalAlign) {
            VerticalAlign.Top -> "top"
            VerticalAlign.Middle -> "middle"
            VerticalAlign.Bottom -> "bottom"
        }
        val container = e.containerId?.takeIf { it in d.ctx.kept }?.let { JsonPrimitive(it.value) } ?: JsonNull
        val own = mapOf(
            "text" to JsonPrimitive(e.text),
            "originalText" to JsonPrimitive(e.originalText),
            "fontSize" to jsonNumber(e.fontSize),
            "fontFamily" to JsonPrimitive(e.fontFamily),
            "textAlign" to JsonPrimitive(align),
            "verticalAlign" to JsonPrimitive(vertical),
            "containerId" to container,
            "lineHeight" to jsonNumber(e.lineHeight),
            "autoResize" to JsonPrimitive(e.autoResize),
        )
        return JsonObject(common(d, "text", Box(e.x.value, e.y.value, e.width.value, e.height.value, e.angle), emptyList(), null) + own)
    }

    private fun image(d: ElementDraw, e: ImageElement): JsonObject {
        val crop = e.crop?.let {
            JsonObject(
                mapOf(
                    "x" to jsonNumber(it.x), "y" to jsonNumber(it.y), "width" to jsonNumber(it.width), "height" to jsonNumber(it.height),
                    "naturalWidth" to jsonNumber(it.naturalWidth), "naturalHeight" to jsonNumber(it.naturalHeight),
                ),
            )
        }
        val own = mapOf(
            "fileId" to (e.fileId?.let { JsonPrimitive(it) } ?: JsonNull),
            "status" to JsonPrimitive(if (e.fileId == null) STATUS_PENDING else STATUS_SAVED),
            "scale" to JsonArray(listOf(JsonPrimitive(if (e.flipX) -1 else 1), JsonPrimitive(if (e.flipY) -1 else 1))),
            "crop" to (crop ?: JsonNull),
        )
        return JsonObject(common(d, "image", Box(e.x.value, e.y.value, e.width.value, e.height.value, e.angle), emptyList(), null) + own)
    }

    private fun linear(d: ElementDraw, type: String, points: List<ScenePoint>, tag: JsonObject?, more: MutableMap<String, JsonElement>.() -> Unit): JsonObject {
        val ox = points.firstOrNull()?.x?.value ?: 0.0
        val oy = points.firstOrNull()?.y?.value ?: 0.0
        val xs = points.map { it.x.value }
        val ys = points.map { it.y.value }
        val box = Box(ox, oy, (xs.maxOrNull() ?: 0.0) - (xs.minOrNull() ?: 0.0), (ys.maxOrNull() ?: 0.0) - (ys.minOrNull() ?: 0.0))
        val extra = LinkedHashMap<String, JsonElement>()
        extra["points"] = JsonArray(points.map { JsonArray(listOf(jsonNumber(it.x.value - ox), jsonNumber(it.y.value - oy))) })
        extra["lastCommittedPoint"] = JsonNull
        extra.more()
        return JsonObject(common(d, type, box, emptyList(), tag) + extra)
    }

    private fun arrow(d: ElementDraw, e: ArrowElement): JsonObject {
        val labels = d.ctx.arrowLabels[e.id].orEmpty().map { JsonObject(mapOf("id" to JsonPrimitive(it.value), "type" to JsonPrimitive("text"))) }
        val written = linear(d, "arrow", e.points, null) {
            put("startBinding", binding(e.startBinding, d.ctx))
            put("endBinding", binding(e.endBinding, d.ctx))
            put("startArrowhead", arrowhead(e.startArrowhead))
            put("endArrowhead", arrowhead(e.endArrowhead))
            put("elbowed", JsonPrimitive(e.elbowed))
        }
        return if (labels.isEmpty()) written else JsonObject(written + ("boundElements" to JsonArray(labels)))
    }

    private fun freedraw(d: ElementDraw, e: FreedrawElement): JsonObject = linear(d, "freedraw", e.points, null) {
        put("pressures", JsonArray(e.pressures.map { jsonNumber(it) }))
        put("simulatePressure", JsonPrimitive(e.simulatePressure))
    }

    private fun point(d: ElementDraw, e: PointElement): JsonObject {
        val half = POINT_DIAMETER / 2.0
        val tag = JsonObject(mapOf(StelekitCustomData.KIND to JsonPrimitive(StelekitCustomData.KIND_POINT)))
        return common(d, "ellipse", Box(e.x.value - half, e.y.value - half, POINT_DIAMETER, POINT_DIAMETER), emptyList(), tag)
    }

    /** A counter-clockwise arc as a line sampled every [ARC_SAMPLE_STEP] radians, with the exact arc in the tag. */
    private fun arc(d: ElementDraw, e: ArcElement): JsonObject {
        var sweep = (e.endAngle - e.startAngle) % FULL_TURN
        if (sweep < 0.0) sweep += FULL_TURN
        val segments = maxOf(1, ceil(sweep / ARC_SAMPLE_STEP).toInt())
        val points = (0..segments).map { i ->
            val a = e.startAngle + sweep * i / segments
            ScenePoint(unit(e.cx.value + e.radius.value * cos(a)), unit(e.cy.value + e.radius.value * sin(a)))
        }
        val tag = JsonObject(
            mapOf(
                StelekitCustomData.KIND to JsonPrimitive(StelekitCustomData.KIND_ARC),
                StelekitCustomData.ARC_CX to jsonNumber(e.cx.value),
                StelekitCustomData.ARC_CY to jsonNumber(e.cy.value),
                StelekitCustomData.ARC_RADIUS to jsonNumber(e.radius.value),
                StelekitCustomData.ARC_START to jsonNumber(e.startAngle),
                StelekitCustomData.ARC_END to jsonNumber(e.endAngle),
            ),
        )
        return linear(d, "line", points, tag) { put("polygon", JsonPrimitive(false)) }
    }

    private fun unit(v: Double): SceneUnit = SceneUnit.of(v, limits).getOrNull() ?: SceneUnit.ZERO

    // ---- members every element has

    private fun common(d: ElementDraw, type: String, box: Box, bound: List<JsonElement>, tag: JsonObject?): JsonObject {
        val b = d.e.base
        val layer = d.ctx.layer(d.e)
        val own = LinkedHashMap<String, JsonElement>()
        own["id"] = JsonPrimitive(b.id.value)
        own["type"] = JsonPrimitive(type)
        own["x"] = jsonNumber(box.x)
        own["y"] = jsonNumber(box.y)
        own["width"] = jsonNumber(box.width)
        own["height"] = jsonNumber(box.height)
        own["angle"] = jsonNumber(box.angle)
        own.putAll(styleMembers(b))
        own["opacity"] = JsonPrimitive(round(b.opacity * layer.opacity).toInt().coerceIn(0, ElementBase.FULL_OPACITY))
        own["groupIds"] = JsonArray(b.groupIds.map { JsonPrimitive(it.value) })
        own["frameId"] = b.frameId?.takeIf { it in d.ctx.kept }?.let { JsonPrimitive(it.value) } ?: JsonNull
        own["index"] = JsonPrimitive(d.index)
        own["seed"] = JsonPrimitive(b.seed)
        own["version"] = JsonPrimitive(b.version)
        own["versionNonce"] = JsonPrimitive(nonceOf(b))
        own["isDeleted"] = JsonPrimitive(false)
        own["boundElements"] = if (bound.isEmpty()) JsonNull else JsonArray(bound)
        own["updated"] = JsonPrimitive(UPDATED)
        own["link"] = (b.extras["link"] as? JsonPrimitive)?.takeIf { it.isString } ?: JsonNull
        own["locked"] = JsonPrimitive(b.locked || layer.locked)
        own["customData"] = customData(b, tag) ?: JsonNull
        return JsonObject(b.extras.filterKeys { it !in RESERVED_EXTRAS } + own)
    }

    private fun styleMembers(b: ElementBase): Map<String, JsonElement> {
        val dash = when (b.style.strokeStyle) {
            StrokeStyle.Solid -> "solid"
            StrokeStyle.Dashed -> "dashed"
            StrokeStyle.Dotted -> "dotted"
        }
        return mapOf(
            "strokeColor" to JsonPrimitive(b.style.strokeColor),
            "backgroundColor" to JsonPrimitive(b.style.backgroundColor),
            "fillStyle" to JsonPrimitive(ExcalidrawFill.exportName(b.style.fillStyle, b.extras)),
            "strokeWidth" to jsonNumber(b.style.strokeWidth),
            "strokeStyle" to JsonPrimitive(dash),
            "roughness" to JsonPrimitive(b.style.roughness),
            "roundness" to roundness(b.style.roundness),
        )
    }

    private fun customData(b: ElementBase, tag: JsonObject?): JsonObject? {
        val stelekit = LinkedHashMap<String, JsonElement>()
        if (b.layerId != LayerId.Default && b.layerId.value.length <= limits.maxLayerIdChars) stelekit[StelekitCustomData.LAYER_ID] = JsonPrimitive(b.layerId.value)
        tag?.let { stelekit.putAll(it) }
        val merged = LinkedHashMap<String, JsonElement>((b.extras["customData"] as? JsonObject)?.filterKeys { it != StelekitCustomData.KEY }.orEmpty())
        if (stelekit.isNotEmpty()) merged[StelekitCustomData.KEY] = JsonObject(stelekit)
        return if (merged.isEmpty()) null else JsonObject(merged)
    }

    private fun boundRefs(refs: List<BoundRef>, ctx: ElementContext): List<JsonElement> = refs.filter { it.id in ctx.kept }.map {
        val kind = when (it.kind) {
            BoundKind.Arrow -> "arrow"
            BoundKind.Text -> "text"
        }
        JsonObject(mapOf("id" to JsonPrimitive(it.id.value), "type" to JsonPrimitive(kind)))
    }

    private fun binding(b: Binding?, ctx: ElementContext): JsonElement {
        if (b == null || b.elementId !in ctx.kept) return JsonNull
        val mode = when (b.mode) {
            BindingMode.Inside -> "inside"
            BindingMode.Orbit -> "orbit"
            BindingMode.Skip -> "skip"
        }
        return JsonObject(
            mapOf(
                "elementId" to JsonPrimitive(b.elementId.value),
                "focus" to JsonPrimitive(0),
                "gap" to JsonPrimitive(0),
                "fixedPoint" to JsonArray(listOf(jsonNumber(b.fixedX), jsonNumber(b.fixedY))),
                "mode" to JsonPrimitive(mode),
            ),
        )
    }

    private fun arrowhead(a: Arrowhead): JsonElement = when (a) {
        Arrowhead.None -> JsonNull
        Arrowhead.Arrow -> JsonPrimitive("arrow")
        Arrowhead.Bar -> JsonPrimitive("bar")
        Arrowhead.Circle -> JsonPrimitive("circle")
        Arrowhead.CircleOutline -> JsonPrimitive("circle_outline")
        Arrowhead.Triangle -> JsonPrimitive("triangle")
        Arrowhead.TriangleOutline -> JsonPrimitive("triangle_outline")
        Arrowhead.Diamond -> JsonPrimitive("diamond")
        Arrowhead.DiamondOutline -> JsonPrimitive("diamond_outline")
    }

    private fun roundness(r: Roundness?): JsonElement {
        r ?: return JsonNull
        val type = when (r.kind) {
            RoundnessKind.Legacy -> LEGACY_ROUNDNESS
            RoundnessKind.ProportionalRadius -> PROPORTIONAL_ROUNDNESS
            RoundnessKind.AdaptiveRadius -> ADAPTIVE_ROUNDNESS
        }
        val members = LinkedHashMap<String, JsonElement>()
        members["type"] = JsonPrimitive(type)
        r.value?.let { members["value"] = jsonNumber(it) }
        return JsonObject(members)
    }

    /** A repeatable stand-in for Excalidraw's random per-edit nonce: a 31-bit FNV-1a of the id and the version. */
    private fun nonceOf(b: ElementBase): Int {
        var h = FNV_OFFSET
        for (c in b.id.value) h = (h xor c.code) * FNV_PRIME
        h = (h xor b.version) * FNV_PRIME
        return h and Int.MAX_VALUE
    }

    private companion object {
        const val UPDATED = 1
        const val STATUS_SAVED = "saved"
        const val STATUS_PENDING = "pending"
        const val POINT_DIAMETER = 4.0
        const val ARC_SAMPLE_STEP = PI / 32.0
        const val FULL_TURN = 2.0 * PI
        const val LEGACY_ROUNDNESS = 1
        const val PROPORTIONAL_ROUNDNESS = 2
        const val ADAPTIVE_ROUNDNESS = 3
        const val FNV_OFFSET = -2128831035
        const val FNV_PRIME = 16777619
        val RESERVED_EXTRAS = setOf(ExcalidrawFill.EXTRAS_KEY, "link", "customData", StelekitCustomData.EXTRA_UNSUPPORTED_TYPE)
    }
}
