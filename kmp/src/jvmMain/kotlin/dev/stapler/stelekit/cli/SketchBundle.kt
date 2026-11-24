// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

@file:Suppress("MagicNumber", "ReturnCount")

package dev.stapler.stelekit.cli

import arrow.core.Either
import dev.stapler.stelekit.model.ProjectBundle
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetPayload
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchProject
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.projectbridge.ProjectSlugs
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarCodec
import dev.stapler.stelekit.sketch.v2.bridge.V2ToV1
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.commands.SketchDocumentV2
import dev.stapler.stelekit.util.ContentHasher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where `place` put a sheet and the markdown that embeds it. */
internal class PlacedSheet(val slug: String, val files: List<String>, val embed: String, val replaced: Boolean)

/**
 * Writes a sheet into a graph's sketch bundle `<graph>/assets/stelekit-sketch/<slug>/` the way the app's own sidecar writer
 * does: the sheet at `sheets/<uuid>.v2.json`, `project-model.json` naming it with its sha256, and a `preview.svg` for the
 * page embed. The model comes from `SidecarCodec`, so the hash, key order and file layout are the app's, not a copy of them.
 */
internal object SketchBundle {
    private const val ROOT = "assets/stelekit-sketch"
    private const val PREVIEW = "preview.svg"
    private const val STANDALONE = "standalone"

    fun place(sheetBytes: ByteArray, graph: File, slugArg: String?, nameArg: String?, createdAt: Long): Either<String, PlacedSheet> {
        val doc = (SheetCodecV2.decode(sheetBytes) as? Either.Right)?.value
            ?: return Either.Left("the sheet does not load; run `validate` and fix its errors first")
        val canonical = (SheetCodecV2.encode(doc) as? Either.Right)?.value ?: return Either.Left("the sheet does not encode")
        if (!graph.isDirectory) return Either.Left("${graph.path} is not a directory; pass the graph folder (the one holding pages/ and journals/)")
        val slug = slugArg ?: ProjectSlugs.fromBits(doc.sheetUuid.hashCode())
        if (!ProjectSlugs.isValid(slug)) return Either.Left("the slug \"$slug\" must look like proj-1a2b3c (proj- and six hex digits)")
        val dir = File(graph, "$ROOT/$slug")
        val existing = readBundle(dir)
        if (slugArg == null && existing != null && existing.sheets.none { it.uuid.value == doc.sheetUuid }) {
            return Either.Left("the derived slug $slug already holds a different project; pass --slug proj-xxxxxx (a new one to create, or this one to add the sheet to it)")
        }
        val sheet = sheetRow(doc, existing?.sheets?.size?.toLong() ?: 0L)
        val uuid = SheetUuid(doc.sheetUuid)
        val bundle = existing?.let { replaceSheet(it, sheet, canonical) } ?: newBundle(doc, sheet, canonical, slug, nameArg, createdAt)
        val files = SidecarCodec.encode(bundle)
        for ((path, bytes) in files) writeAtomic(File(dir, path), bytes)
        val preview = SketchRender.svg(doc, RenderRequest())
        writeAtomic(File(dir, PREVIEW), preview.encodeToByteArray())
        val replaced = existing?.sheets?.any { it.uuid == uuid } == true
        return Either.Right(PlacedSheet(slug, files.keys.sorted().map { "$ROOT/$slug/$it" } + "$ROOT/$slug/$PREVIEW", "![sketch](../$ROOT/$slug/$PREVIEW)", replaced))
    }

    private fun readBundle(dir: File): ProjectBundle? {
        val model = File(dir, SidecarCodec.MODEL_PATH)
        if (!model.isFile) return null
        val files = HashMap<String, ByteArray>()
        files[SidecarCodec.MODEL_PATH] = model.readBytes()
        File(dir, SidecarCodec.SHEETS_DIR).listFiles()?.filter { it.isFile }?.forEach { files["${SidecarCodec.SHEETS_DIR}/${it.name}"] = it.readBytes() }
        return (SidecarCodec.decode(files) as? Either.Right)?.value
    }

    private fun sheetRow(doc: SketchDocumentV2, position: Long): SketchSheet = SketchSheet(
        uuid = SheetUuid(doc.sheetUuid),
        projectUuid = ProjectUuid(""),
        kind = "plan",
        viewDirection = "top",
        unit = "in",
        scaleSixteenthsPerUnit = 16,
        layers = V2ToV1.projectScene(doc.scene).layers.map { it.encode() },
        position = position,
        schemaVersion = SheetPaths.SCHEMA_V2,
    )

    private fun newBundle(doc: SketchDocumentV2, sheet: SketchSheet, bytes: ByteArray, slug: String, name: String?, createdAt: Long): ProjectBundle {
        val title = ((doc.extras["agent"] as? JsonObject)?.get("title") as? JsonPrimitive)?.content
        val hex = ContentHasher.sha256("project:${doc.sheetUuid}")
        val uuid = ProjectUuid("${hex.substring(0, 8)}-${hex.substring(8, 12)}-7${hex.substring(13, 16)}-8${hex.substring(17, 20)}-${hex.substring(20, 32)}")
        val project = SketchProject(
            uuid = uuid, slug = slug, name = name ?: title ?: "Sketch", createdAt = createdAt,
            settings = JsonObject(mapOf(STANDALONE to JsonPrimitive(true))),
        )
        return ProjectBundle(project, listOf(sheet.copy(projectUuid = uuid)), emptyList(), emptyList(), mapOf(sheet.uuid to SheetPayload.V2Opaque.of(bytes)))
    }

    private fun replaceSheet(existing: ProjectBundle, sheet: SketchSheet, bytes: ByteArray): ProjectBundle {
        val project = existing.project.uuid
        val kept = existing.sheets.filter { it.uuid != sheet.uuid }
        val position = existing.sheets.firstOrNull { it.uuid == sheet.uuid }?.position ?: kept.size.toLong()
        val replacement = sheet.copy(projectUuid = project, position = position)
        val payloads = existing.sheetPayloads.filterKeys { it != sheet.uuid } + (sheet.uuid to SheetPayload.V2Opaque.of(bytes))
        return existing.copy(sheets = kept + replacement, sheetPayloads = payloads)
    }

    /** Temp file in the same folder, then an atomic rename, so a reader never sees half a file. */
    private fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeBytes(bytes)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
