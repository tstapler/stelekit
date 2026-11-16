// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.excalidraw

import arrow.core.Either
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.sketch.v2.commands.Transaction
import dev.stapler.stelekit.sketch.v2.export.SvgImage
import dev.stapler.stelekit.sketch.v2.export.SvgImageSource
import dev.stapler.stelekit.sketch.v2.importer.ExcalidrawExporter
import dev.stapler.stelekit.sketch.v2.importer.ExcalidrawImporter
import dev.stapler.stelekit.sketch.v2.importer.ExportedDrawing
import dev.stapler.stelekit.sketch.v2.importer.ImportedScene
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import dev.stapler.stelekit.wiring.WiringInputs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Story 5.1.3, success metric 7: `import(export(import(fixture)))` equals `import(fixture)` for each of the six Story 0.1.5
 * fixtures (kinds, geometry, text, bindings, z-order and the `extras` of unsupported elements), and an embedded image comes
 * back with the same bytes.
 *
 * The fixtures are HAND-AUTHORED from Excalidraw v0.18.1 `types.ts`, NOT real exports (owner Task 0.1.5d is open):
 * hand-authored fixtures only, UNMEASURED against real exports. The fidelity pivot of Task 6.2.9d cannot be read from this
 * test until real exports are added.
 */
@OptIn(ExperimentalEncodingApi::class)
class FixtureRoundTripTest {
    private val names = listOf("shapes-text", "bound-arrows", "frame-group", "image", "freedraw", "legacy-binding")

    private fun fixtureText(name: String): String =
        WiringInputs.dataFile("excalidraw/$name.excalidraw", "kmp/src/commonTest/resources/excalidraw/$name.excalidraw")

    private fun import(bytes: ByteArray): ImportedScene = runBlocking {
        val result = ExcalidrawImporter().import(ImportSource.ofBytes(bytes))
        assertIs<Either.Right<ImportedScene>>(result, "$result")
        result.value
    }

    private fun documentOf(scene: ImportedScene): SketchDocumentV2 =
        Transaction.begin(SketchDocumentV2()).apply(scene.command()).getOrNull()!!.commit().getOrNull()!!.after

    private fun export(first: ImportedScene, doc: SketchDocumentV2): ExportedDrawing {
        val pictures = first.images.associateBy { it.fileId }
        val source = SvgImageSource { id -> pictures[id]?.let { SvgImage(it.mimeType, it.bytes) } }
        return ExcalidrawExporter().export(doc.scene, source).getOrNull()!!
    }

    /** Re-keyed order, the edit counter and ordinals are not part of what an export must keep. */
    private fun comparable(e: SceneElement): SceneElement = e.withBase(e.base.copy(zKey = ZKey("a"), version = 1, ordinal = 0))

    private fun roundTrip(name: String): Triple<ImportedScene, ImportedScene, ExportedDrawing> {
        val first = import(fixtureText(name).encodeToByteArray())
        val doc = documentOf(first)
        val exported = export(first, doc)
        val second = import(exported.text.encodeToByteArray())
        assertEquals(doc.scene.elementsInOrder.map { it.id }, second.elements.map { it.id }, "$name: z-order")
        assertEquals(doc.scene.elementsInOrder.map(::comparable), second.elements.map(::comparable), "$name: elements")
        return Triple(first, second, exported)
    }

    @Test
    fun fixtures_should_SurviveImportExportImport_WithTheSecondSceneEqualToTheFirst() {
        println("FixtureRoundTripTest: hand-authored fixtures only, UNMEASURED against real exports (Task 0.1.5d open)")
        for (name in names) {
            val (first, second, _) = roundTrip(name)
            assertEquals(first.report.imported, second.report.imported, "$name: imported count")
            assertEquals(first.backgroundColor, second.backgroundColor, "$name: background")
        }
    }

    @Test
    fun unsupportedElements_should_BeWrittenBackWithTheirTypeAndMembers() {
        val (first, second, exported) = roundTrip("frame-group")
        assertEquals(first.report.placeholders, second.report.placeholders)
        assertEquals(mapOf("magicframe" to 1, "embeddable" to 1, "iframe" to 1), second.report.placeholders)
        val written = Json.parseToJsonElement(exported.text).jsonObject["elements"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("magicframe", "embeddable", "iframe"), written.map { it["type"]!!.jsonPrimitive.content }.toSet().intersect(setOf("magicframe", "embeddable", "iframe")))
        val original = Json.parseToJsonElement(fixtureText("frame-group")).jsonObject["elements"]!!.jsonArray.map { it.jsonObject }
        for (id in listOf("emb1", "ifr1", "mf1")) {
            val before = original.single { it["id"]!!.jsonPrimitive.content == id }
            val after = written.single { it["id"]!!.jsonPrimitive.content == id }
            assertEquals(before["link"], after["link"], "$id link")
            for (extra in before.keys - COMMON) assertEquals(before[extra], after[extra], "$id $extra")
        }
        assertTrue(second.elements.filterIsInstance<FrameElement>().isNotEmpty())
    }

    @Test
    fun image_should_ExportItsFilesEntryWithTheSamePayloadAndKeepEveryImageElement() {
        val (first, second, exported) = roundTrip("image")
        assertEquals(first.images.map { it.fileId }, second.images.map { it.fileId })
        assertEquals(first.images.map { it.fileId }, exported.fileIds)
        val original = Json.parseToJsonElement(fixtureText("image")).jsonObject["files"]!!.jsonObject.values.map { it.jsonObject }
        val files = Json.parseToJsonElement(exported.text).jsonObject["files"]!!.jsonObject
        for (picture in first.images) {
            val entry = files[picture.fileId]!!.jsonObject
            assertTrue(entry["mimeType"]!!.jsonPrimitive.content in setOf("image/png", "image/jpeg", "image/gif", "image/webp"))
            val payload = entry["dataURL"]!!.jsonPrimitive.content.substringAfter("base64,")
            assertContentEquals(picture.bytes, Base64.Default.decode(payload))
            val source = original.map { Base64.Default.decode(it["dataURL"]!!.jsonPrimitive.content.substringAfter("base64,")) }
            assertTrue(source.any { it.contentEquals(picture.bytes) }, "the exported payload is one of the fixture's own")
        }
        assertEquals(first.elements.count { it is ImageElement }, second.elements.count { it is ImageElement })
    }

    private companion object {
        val COMMON = setOf(
            "id", "type", "x", "y", "width", "height", "angle", "strokeColor", "backgroundColor", "fillStyle", "strokeWidth", "strokeStyle",
            "roughness", "opacity", "groupIds", "frameId", "index", "roundness", "seed", "version", "versionNonce", "isDeleted",
            "boundElements", "updated", "link", "locked", "customData",
        )
    }
}
