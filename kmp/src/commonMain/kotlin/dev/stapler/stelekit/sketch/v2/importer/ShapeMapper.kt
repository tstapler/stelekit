// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.importer

import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawElement
import dev.stapler.stelekit.sketch.excalidraw.ImportReason
import dev.stapler.stelekit.sketch.v2.scene.BoundKind
import dev.stapler.stelekit.sketch.v2.scene.BoundRef
import dev.stapler.stelekit.sketch.v2.scene.DiamondElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementStyle
import dev.stapler.stelekit.sketch.v2.scene.EllipseElement
import dev.stapler.stelekit.sketch.v2.scene.FillStyle
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.ImageCrop
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.PointElement
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SceneUnit
import dev.stapler.stelekit.sketch.v2.scene.TextAlign
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import dev.stapler.stelekit.sketch.v2.scene.VerticalAlign
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What every per-kind mapper needs: the limits, the shared base mapper, the report, and lookups by Excalidraw id. */
internal class MapContext(
    val limits: SceneLimits,
    val base: BaseMapper,
    val report: ReportBuilder,
    val byId: Map<String, ExcalidrawElement>,
    /** Excalidraw `files` key to the picture it holds. */
    val images: Map<String, ImportedImage>,
    /** Excalidraw file ids the parser refused, with its reason. */
    val refused: Map<String, ImportReason>,
) {
    fun unit(v: Double): SceneUnit? = SceneUnit.of(v, limits).getOrNull()
}

/** A box in scene units; a negative width or height is flipped so the box is always positive. */
internal class BoxUnits(val x: SceneUnit, val y: SceneUnit, val width: SceneUnit, val height: SceneUnit, val rawWidth: Double, val rawHeight: Double)

/** Rectangle, diamond, ellipse, text, frame, image and the grey placeholders of kinds the engine does not draw. */
internal class ShapeMapper(private val ctx: MapContext) {
    fun map(x: ExcalidrawElement, zKey: ZKey): SceneElement? {
        val box = box(x)
        if (box == null) {
            ctx.report.drop(x.id, ImportReason.CoordinateOutOfRange.name)
            return null
        }
        return when {
            x.unsupported -> placeholder(x, zKey, box)
            x.type == "rectangle" -> RectElement(ctx.base.base(x, zKey, NO_EXTRA), box.x, box.y, box.width, box.height, x.angle, boundRefs(x.raw))
            x.type == "diamond" -> DiamondElement(ctx.base.base(x, zKey, NO_EXTRA), box.x, box.y, box.width, box.height, x.angle, boundRefs(x.raw))
            x.type == "ellipse" -> ellipse(x, zKey, box)
            x.type == "frame" -> frame(x, zKey, box)
            x.type == "text" -> text(x, zKey, box)
            else -> image(x, zKey, box)
        }
    }

    private fun box(x: ExcalidrawElement): BoxUnits? {
        val left = if (x.width < 0.0) x.x + x.width else x.x
        val top = if (x.height < 0.0) x.y + x.height else x.y
        val w = kotlin.math.abs(x.width)
        val h = kotlin.math.abs(x.height)
        return BoxUnits(ctx.unit(left) ?: return null, ctx.unit(top) ?: return null, ctx.unit(w) ?: return null, ctx.unit(h) ?: return null, w, h)
    }

    private fun boundRefs(raw: JsonObject): List<BoundRef> = raw.array("boundElements").orEmpty().mapNotNull { entry ->
        val o = entry as? JsonObject ?: return@mapNotNull null
        val id = o.text("id") ?: return@mapNotNull null
        when (o.text("type")) {
            "arrow" -> BoundRef(ElementId(id), BoundKind.Arrow)
            "text" -> BoundRef(ElementId(id), BoundKind.Text)
            else -> null
        }
    }

    private fun ellipse(x: ExcalidrawElement, zKey: ZKey, box: BoxUnits): SceneElement? {
        val base = ctx.base.base(x, zKey, NO_EXTRA)
        val cx = ctx.unit(box.x.value + box.rawWidth / 2.0)
        val cy = ctx.unit(box.y.value + box.rawHeight / 2.0)
        if (cx == null || cy == null) {
            ctx.report.drop(x.id, ImportReason.CoordinateOutOfRange.name)
            return null
        }
        if (x.raw.obj("customData")?.obj(StelekitCustomData.KEY)?.text(StelekitCustomData.KIND) == StelekitCustomData.KIND_POINT) {
            return PointElement(base, cx, cy)
        }
        val rx = ctx.unit(box.rawWidth / 2.0)
        val ry = ctx.unit(box.rawHeight / 2.0)
        if (rx == null || ry == null || minOf(rx.value, ry.value) <= 0.0) {
            ctx.report.drop(x.id, DEGENERATE)
            return null
        }
        return EllipseElement(base, cx, cy, rx, ry, x.angle, boundRefs(x.raw))
    }

    private fun frame(x: ExcalidrawElement, zKey: ZKey, box: BoxUnits): SceneElement {
        val title = x.raw.text("name")?.take(ctx.limits.maxNameChars)?.takeIf { it.isNotBlank() }
        val base = ctx.base.base(x, zKey, FRAME_MEMBERS).let { it.copy(name = title, frameId = null) }
        return FrameElement(base, box.x, box.y, box.width, box.height, x.angle)
    }

    /** OD-6: an embeddable, iframe or magicframe is a grey rectangle that remembers its type, so export writes it back. */
    private fun placeholder(x: ExcalidrawElement, zKey: ZKey, box: BoxUnits): SceneElement {
        ctx.report.placeholder(x.type)
        val base = ctx.base.base(x, zKey, NO_EXTRA)
        val marked = base.copy(
            style = ElementStyle(strokeColor = PLACEHOLDER_STROKE, backgroundColor = PLACEHOLDER_FILL, fillStyle = FillStyle.Solid, roughness = 0),
            extras = JsonObject(base.extras + (StelekitCustomData.EXTRA_UNSUPPORTED_TYPE to JsonPrimitive(x.type))),
        )
        return RectElement(marked, box.x, box.y, box.width, box.height, x.angle)
    }

    private fun text(x: ExcalidrawElement, zKey: ZKey, box: BoxUnits): SceneElement {
        val raw = x.raw
        val content = (raw.text("text") ?: "").take(ctx.limits.maxTextChars)
        return TextElement(
            base = ctx.base.base(x, zKey, TEXT_MEMBERS),
            x = box.x,
            y = box.y,
            width = box.width,
            height = box.height,
            angle = x.angle,
            text = content,
            originalText = (raw.text("originalText") ?: content).take(ctx.limits.maxTextChars),
            fontSize = raw.number("fontSize")?.takeIf { it > 0.0 } ?: TextElement.DEFAULT_FONT_SIZE,
            fontFamily = raw.number("fontFamily")?.toInt() ?: 1,
            textAlign = when (raw.text("textAlign")) {
                "center" -> TextAlign.Center
                "right" -> TextAlign.Right
                else -> TextAlign.Left
            },
            verticalAlign = when (raw.text("verticalAlign")) {
                "middle" -> VerticalAlign.Middle
                "bottom" -> VerticalAlign.Bottom
                else -> VerticalAlign.Top
            },
            containerId = raw.text("containerId")?.let { ElementId(it) },
            lineHeight = raw.number("lineHeight")?.takeIf { it > 0.0 } ?: TextElement.DEFAULT_LINE_HEIGHT,
            autoResize = raw.flag("autoResize") ?: true,
        )
    }

    private fun image(x: ExcalidrawElement, zKey: ZKey, box: BoxUnits): SceneElement {
        val raw = x.raw
        val key = raw.text("fileId")
        val picture = key?.let { ctx.images[it] }
        if (picture == null) ctx.report.missingImages += MissingImage(ElementId(x.id), missingReason(x, key))
        val scale = raw.array("scale")?.let { pairOf(it) }
        return ImageElement(
            base = ctx.base.base(x, zKey, IMAGE_MEMBERS),
            x = box.x,
            y = box.y,
            width = box.width,
            height = box.height,
            angle = x.angle,
            fileId = picture?.fileId,
            flipX = (scale?.first ?: 1.0) < 0.0,
            flipY = (scale?.second ?: 1.0) < 0.0,
            crop = crop(raw.obj("crop")),
        )
    }

    private fun missingReason(x: ExcalidrawElement, key: String?): MissingImageReason {
        val refusal = key?.let { ctx.refused[it] }
        return when {
            refusal != null -> MissingImageReason.Refused(refusal)
            key == null && x.link != null -> MissingImageReason.LinkNotDownloaded
            else -> MissingImageReason.NoFileData
        }
    }

    private fun crop(obj: JsonObject?): ImageCrop? {
        obj ?: return null
        val v = CROP_FIELDS.map { obj.number(it) ?: return null }
        return ImageCrop(v[0], v[1], v[2], v[3], v[4], v[5])
    }

    private companion object {
        const val DEGENERATE = "Degenerate"
        const val PLACEHOLDER_STROKE = "#868e96"
        const val PLACEHOLDER_FILL = "#e9ecef"
        val NO_EXTRA: Set<String> = emptySet()
        val FRAME_MEMBERS = setOf("name")
        val TEXT_MEMBERS = setOf("text", "originalText", "fontSize", "fontFamily", "textAlign", "verticalAlign", "containerId", "lineHeight", "autoResize")
        val IMAGE_MEMBERS = setOf("fileId", "status", "scale", "crop")
        val CROP_FIELDS = listOf("x", "y", "width", "height", "naturalWidth", "naturalHeight")
    }
}
