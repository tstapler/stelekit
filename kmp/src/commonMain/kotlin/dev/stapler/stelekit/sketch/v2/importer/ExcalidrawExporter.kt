// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.importer

import arrow.core.Either
import dev.stapler.stelekit.sketch.excalidraw.HeaderProbe
import dev.stapler.stelekit.sketch.excalidraw.ImageHeader
import dev.stapler.stelekit.sketch.v2.codec.CanonicalJson
import dev.stapler.stelekit.sketch.v2.codec.CanonicalJsonError
import dev.stapler.stelekit.sketch.v2.commands.ZKeys
import dev.stapler.stelekit.sketch.v2.export.SvgImageSource
import dev.stapler.stelekit.sketch.v2.image.ReferencedAssets
import dev.stapler.stelekit.sketch.v2.scene.ArrowElement
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.scene.TextElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** What the writer puts around the elements: the canvas colour and grid step of `appState`. */
data class ExcalidrawExportOptions(val backgroundColor: String = "#ffffff", val gridSize: Double? = null)

/**
 * The `.excalidraw` text plus what the writer did to get there. [missingFileIds] are images whose bytes the store could not
 * supply (or whose type is not on the allow-list): their elements are written with `status: "error"` and no `files` entry,
 * and the export still succeeds. [hiddenOmitted] counts elements left out because their layer is hidden (ADR-006).
 */
class ExportedDrawing(val text: String, val elementCount: Int, val fileIds: List<String>, val missingFileIds: List<String>, val hiddenOmitted: Int)

/**
 * Story 5.1.3: writes a scene as an Excalidraw v0.18.1 file, the inverse of [ExcalidrawImporter]. Layers are flattened
 * (ADR-006): paint order becomes the fresh `index` keys, hidden layers are omitted, `locked` is the element's or its layer's,
 * layer opacity multiplies into the element's, and `customData.stelekit.layerId` records the layer for the round trip.
 * Excalidraw has no point or arc, so those two kinds are written as a tiny ellipse and a sampled line tagged
 * `customData.stelekit.kind` (OD-8); the importer restores them from the tag. Members the importer kept in `extras` (an
 * unsupported element's type among them) are written back as they were.
 *
 * `version`, `versionNonce` and `updated` are derived from the element, not the clock, so saving twice gives the same bytes.
 * Numbers go through [CanonicalJson], never `Double.toString` (wasmJs does not round-trip it).
 */
@OptIn(ExperimentalEncodingApi::class)
class ExcalidrawExporter(private val limits: SceneLimits = SceneLimits.Default) {
    private val writer = ExcalidrawElementWriter(limits)

    fun export(
        scene: SketchSceneV2,
        images: SvgImageSource = SvgImageSource.None,
        options: ExcalidrawExportOptions = ExcalidrawExportOptions(),
    ): Either<CanonicalJsonError, ExportedDrawing> {
        val visible = visibleInPaintOrder(scene)
        val ctx = ElementContext(scene, visible.mapTo(HashSet()) { it.id }, labelsOfArrows(visible))
        val keys = ZKeys.sequence(visible.size)
        val elements = visible.mapIndexed { i, e -> writer.write(ElementDraw(e, keys[i].value, ctx)) }

        val usedFiles = visible.filterIsInstance<ImageElement>().mapNotNullTo(HashSet()) { it.fileId }
        val files = LinkedHashMap<String, JsonElement>()
        val missing = ArrayList<String>()
        for (id in ReferencedAssets.referencedFileIds(scene).filter { it in usedFiles }) {
            val entry = fileEntry(id, images)
            if (entry == null) missing += id else files[id] = entry
        }
        val broken = visible.filterIsInstance<ImageElement>().filter { it.fileId in missing }.mapTo(HashSet()) { it.id.value }
        val written = elements.map { if (idOf(it) in broken) JsonObject(it + ("status" to JsonPrimitive(STATUS_ERROR))) else it }

        val root = JsonObject(
            mapOf(
                "type" to JsonPrimitive(TYPE),
                "version" to JsonPrimitive(FILE_VERSION),
                "source" to JsonPrimitive(SOURCE),
                "elements" to JsonArray(written),
                "appState" to appState(options),
                "files" to JsonObject(files),
            ),
        )
        return CanonicalJson.encode(root, listOf("type", "version", "source"), limits).map {
            ExportedDrawing(it.decodeToString(), visible.size, files.keys.toList(), missing, scene.size - visible.size)
        }
    }

    private fun visibleInPaintOrder(scene: SketchSceneV2): List<SceneElement> {
        val rank = scene.layers.associate { it.id to it.order }
        return scene.elementsInOrder.withIndex()
            .sortedWith(compareBy({ rank[it.value.base.layerId] ?: Layer.DefaultLayer.order }, { it.value.base.zKey }, { it.index }))
            .map { it.value }
            .filter { (scene.layer(it.base.layerId) ?: Layer.DefaultLayer).visible }
    }

    private fun labelsOfArrows(visible: List<SceneElement>): Map<ElementId, List<ElementId>> {
        val arrows = visible.filterIsInstance<ArrowElement>().mapTo(HashSet()) { it.id }
        return visible.filterIsInstance<TextElement>().filter { it.containerId in arrows }.groupBy({ it.containerId!! }, { it.id })
    }

    private fun idOf(element: JsonObject): String = (element["id"] as? JsonPrimitive)?.content.orEmpty()

    // ---- files and appState

    private fun fileEntry(fileId: String, images: SvgImageSource): JsonElement? {
        val image = images.load(fileId) ?: return null
        val probe = ImageHeader.probe(image.bytes) as? HeaderProbe.Found ?: return null
        val mime = probe.kind.mime
        val dataUrl = "data:$mime;base64," + Base64.Default.encode(image.bytes)
        return JsonObject(
            mapOf(
                "mimeType" to JsonPrimitive(mime),
                "id" to JsonPrimitive(fileId),
                "dataURL" to JsonPrimitive(dataUrl),
                "created" to JsonPrimitive(UPDATED),
            ),
        )
    }

    private fun appState(options: ExcalidrawExportOptions): JsonObject {
        val members = LinkedHashMap<String, JsonElement>()
        members["viewBackgroundColor"] = JsonPrimitive(options.backgroundColor)
        options.gridSize?.let { members["gridSize"] = jsonNumber(it) }
        return JsonObject(members)
    }

    private companion object {
        const val TYPE = "excalidraw"
        const val SOURCE = "stelekit"
        const val FILE_VERSION = 2
        const val UPDATED = 1
        const val STATUS_ERROR = "error"
    }
}
