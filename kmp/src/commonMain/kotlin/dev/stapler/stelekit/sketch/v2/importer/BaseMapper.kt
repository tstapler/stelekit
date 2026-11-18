// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.importer

import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawElement
import dev.stapler.stelekit.sketch.v2.scene.ColorText
import dev.stapler.stelekit.sketch.v2.scene.ElementBase
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.ExcalidrawFill
import dev.stapler.stelekit.sketch.v2.scene.GroupId
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.LayerId
import dev.stapler.stelekit.sketch.v2.scene.Roundness
import dev.stapler.stelekit.sketch.v2.scene.RoundnessKind
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.StrokeStyle
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `customData.stelekit` object an Excalidraw file written by SteleKit carries (Story 5.1.2/5.1.3, OD-8). Excalidraw has
 * no point or arc, so those two kinds travel as a tiny ellipse and a sampled line tagged here and are restored from the tag.
 */
object StelekitCustomData {
    const val KEY = "stelekit"
    const val LAYER_ID = "layerId"
    const val KIND = "kind"
    const val KIND_POINT = "point"
    const val KIND_ARC = "arc"
    const val ARC_CX = "cx"
    const val ARC_CY = "cy"
    const val ARC_RADIUS = "radius"
    const val ARC_START = "startAngle"
    const val ARC_END = "endAngle"

    /** `extras` key under which an unsupported element (embeddable, iframe, magicframe) remembers its Excalidraw type. */
    const val EXTRA_UNSUPPORTED_TYPE = "unsupportedType"
}

/** Ceilings for style numbers a file can set to 1e300; a value above one is clamped, not dropped, so the element still imports. */
internal object StyleBounds {
    const val STROKE_WIDTH_CEILING = 256.0
    const val FONT_SIZE_CEILING = 4096.0
    const val LINE_HEIGHT_CEILING = 16.0
}

/** Members every Excalidraw element has; whatever else an element carries stays in `extras`. */
internal val COMMON_MEMBERS: Set<String> = setOf(
    "id", "type", "x", "y", "width", "height", "angle", "strokeColor", "backgroundColor", "fillStyle", "strokeWidth", "strokeStyle",
    "roughness", "opacity", "groupIds", "frameId", "index", "roundness", "seed", "version", "versionNonce", "isDeleted",
    "boundElements", "updated", "link", "locked", "customData",
)

/** Maps the members shared by every element: style, groups, frame, lock, layer hint, and the unknown remainder as `extras`. */
internal class BaseMapper(private val limits: SceneLimits, private val report: ReportBuilder) {
    private val hinted = LinkedHashSet<LayerId>()

    /** The layers the hints named, in first-seen order, ready for `ImportScene` to add. */
    val layers: List<Layer> get() = hinted.mapIndexed { i, id -> Layer(id, id.value, order = i + 1) }

    fun base(x: ExcalidrawElement, zKey: ZKey, known: Set<String>): ElementBase {
        val raw = x.raw
        val fill = ExcalidrawFill.import(raw.text("fillStyle"), leftovers(x, known))
        if (raw.hasValue("fillStyle") && fill.extras.containsKey(ExcalidrawFill.EXTRAS_KEY)) report.unknownFills++
        return ElementBase(
            id = ElementId(x.id),
            layerId = layerOf(raw),
            groupIds = x.groupIds.map { GroupId(it) },
            frameId = x.frameId?.let { ElementId(it) },
            zKey = zKey,
            locked = x.locked,
            opacity = (raw.number("opacity") ?: ElementBase.FULL_OPACITY.toDouble()).toInt().coerceIn(0, ElementBase.FULL_OPACITY),
            style = style(raw, fill.style),
            seed = raw.number("seed")?.toLong()?.toInt() ?: 0,
            extras = fill.extras,
        )
    }

    private fun style(raw: JsonObject, fill: dev.stapler.stelekit.sketch.v2.scene.FillStyle): ElementStyle {
        val dash = raw.text("strokeStyle")
        val stroke = when (dash) {
            null, "solid" -> StrokeStyle.Solid
            "dashed" -> StrokeStyle.Dashed
            "dotted" -> StrokeStyle.Dotted
            else -> StrokeStyle.Solid.also { report.unknownStrokeStyles++ }
        }
        return ElementStyle(
            strokeColor = color(raw.text("strokeColor"), DEFAULT_STROKE),
            backgroundColor = color(raw.text("backgroundColor"), TRANSPARENT),
            fillStyle = fill,
            strokeWidth = raw.number("strokeWidth")?.takeIf { it >= 0.0 }?.coerceAtMost(StyleBounds.STROKE_WIDTH_CEILING) ?: DEFAULT_STROKE_WIDTH,
            strokeStyle = stroke,
            roughness = (raw.number("roughness") ?: DEFAULT_ROUGHNESS.toDouble()).toInt().coerceIn(0, MAX_ROUGHNESS),
            roundness = roundness(raw.obj("roundness")),
        )
    }

    private fun roundness(obj: JsonObject?): Roundness? {
        obj ?: return null
        val kind = when (obj.number("type")?.toInt()) {
            LEGACY_ROUNDNESS -> RoundnessKind.Legacy
            PROPORTIONAL_ROUNDNESS -> RoundnessKind.ProportionalRadius
            ADAPTIVE_ROUNDNESS -> RoundnessKind.AdaptiveRadius
            else -> return null
        }
        return Roundness(kind, obj.number("value")?.coerceIn(0.0, limits.maxCoord))
    }

    /** Keeps only characters a colour string can hold, so a hostile value never reaches an SVG attribute or a painter. */
    private fun color(css: String?, default: String): String = ColorText.clean(css, default)

    private fun leftovers(x: ExcalidrawElement, known: Set<String>): JsonObject {
        val out = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for ((k, v) in x.raw) if (k !in known && k !in COMMON_MEMBERS) out[k] = v
        x.link?.let { out["link"] = JsonPrimitive(it) }
        x.raw.obj("customData")?.let { data ->
            val rest = JsonObject(data - StelekitCustomData.KEY)
            if (rest.isNotEmpty()) out["customData"] = rest
        }
        return JsonObject(out)
    }

    private fun layerOf(raw: JsonObject): LayerId {
        val hint = raw.obj("customData")?.obj(StelekitCustomData.KEY)?.text(StelekitCustomData.LAYER_ID) ?: return LayerId.Default
        val id = LayerId(hint)
        val valid = hint.isNotBlank() && hint.length <= limits.maxLayerIdChars && hint != LayerId.Default.value
        return when {
            !valid -> LayerId.Default.also { if (hint != LayerId.Default.value) report.layerHintsIgnored++ }
            id in hinted -> id
            hinted.size < limits.maxLayers - 1 -> id.also { hinted += it }
            else -> LayerId.Default.also { report.layerHintsIgnored++ }
        }
    }

    private companion object {
        const val DEFAULT_STROKE = "#1e1e1e"
        const val TRANSPARENT = "transparent"
        const val DEFAULT_STROKE_WIDTH = 2.0
        const val DEFAULT_ROUGHNESS = 1
        const val MAX_ROUGHNESS = 2
        const val LEGACY_ROUNDNESS = 1
        const val PROPORTIONAL_ROUNDNESS = 2
        const val ADAPTIVE_ROUNDNESS = 3
    }
}

/** Mutable counters of one mapping run; [build] freezes them into the public [MapReport]. */
internal class ReportBuilder {
    var unsupportedArrowheads = 0
    var unknownFills = 0
    var unknownStrokeStyles = 0
    var legacyBindings = 0
    var layerHintsIgnored = 0
    val dropped = mutableListOf<DroppedElement>()
    val placeholders = LinkedHashMap<String, Int>()
    val missingImages = mutableListOf<MissingImage>()

    fun placeholder(type: String) {
        placeholders[type] = (placeholders[type] ?: 0) + 1
    }

    fun drop(id: String, code: String) {
        dropped += DroppedElement(id, code)
    }
}
