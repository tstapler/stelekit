// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketchimport

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.asset.AssetIndexService
import dev.stapler.stelekit.asset.AssetIntent
import dev.stapler.stelekit.asset.DrawingLocationRule
import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.fusion.DimensionKind
import dev.stapler.stelekit.fusion.DimensionRegistry
import dev.stapler.stelekit.fusion.RefTable
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.RevisionToken
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.model.SketchDimension
import dev.stapler.stelekit.model.SketchSheet
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchRepositories
import dev.stapler.stelekit.util.ContentHasher
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.flow.first

sealed interface ImportError {
    data class UnsupportedType(val extension: String) : ImportError

    data object Empty : ImportError

    data object ProjectMissing : ImportError

    data class Failed(val message: String) : ImportError
}

/** What a picked drawing holds, before anything is copied or written. [bytes] are the picked file's content. */
class DrawingPreview(
    val fileName: String,
    val extension: String,
    val bytes: ByteArray,
    val contentHash: String,
    val labels: LabelBindingResult,
    val viewDirection: ViewDirection?,
    val unsupported: List<String>,
) {
    val taggingItems: List<TaggingItem> get() = Tagging.items(labels)
}

data class ImportOutcome(val sheetUuid: SheetUuid, val assetPath: String, val dimensionCount: Int)

/**
 * Drawing import (Story 4.4). [prepare] only reads; [commit] is the one place anything is written
 * (the file under `drawings/`, its `DRAWING` asset row, the sheet and the observations), so a
 * cancelled import leaves no rows. The picked bytes are held by the caller (Android SAF stream,
 * desktop file or wasm File API, all already read to bytes), so this class touches no picker.
 */
class ImportFlow(
    private val assets: AssetIndexService,
    private val repos: SketchRepositories,
    private val fileSystem: FileSystem,
    private val notesPath: EffectiveNotesPath,
    private val writeActor: DatabaseWriteActor?,
    private val refTable: RefTable = RefTable(),
) {
    fun prepare(registry: DimensionRegistry, fileName: String, bytes: ByteArray): Either<ImportError, DrawingPreview> {
        val extension = extensionOf(fileName)
        if (extension != SVG) return ImportError.UnsupportedType(extension).left()
        if (bytes.isEmpty()) return ImportError.Empty.left()
        val hash = ContentHasher.sha256(bytes)
        val doc = readSvg(bytes.decodeToString())
        val labels = LabelBinder(registry, refTable).bind(doc, "drawing:$hash")
        return DrawingPreview(
            fileName = fileName,
            extension = extension,
            bytes = bytes,
            contentHash = hash,
            labels = labels,
            viewDirection = calibrateSheet(doc).getOrNull()?.viewDirection,
            unsupported = doc.unsupported.map { it.kind }.distinct(),
        ).right()
    }

    /**
     * Copies the drawing to `assets/stelekit-sketch/<slug>/drawings/<hash8>.<ext>`, registers it as
     * `DRAWING`, then writes one sheet row and an observation (source kind `svg`) for every bound
     * label and every tagged one. [assignments] maps an unbound label's ref to its dimension key.
     */
    @OptIn(DirectRepositoryWrite::class)
    suspend fun commit(
        projectUuid: ProjectUuid,
        preview: DrawingPreview,
        registry: DimensionRegistry,
        assignments: Map<String, String>,
    ): Either<ImportError, ImportOutcome> {
        val loaded = repos.projects.loadProject(projectUuid).fold({ return fail(it) }, { it })
            ?: return ImportError.ProjectMissing.left()
        val token = loaded.token
        val dimensions = dimensionsFor(projectUuid, preview, registry, assignments)

        val dir = "${notesPath.child("assets")}/${DrawingLocationRule.SKETCH_DIR}/${loaded.project.slug}/drawings"
        val path = "$dir/${preview.contentHash.take(HASH_PREFIX)}.${preview.extension}"
        fileSystem.createDirectory(dir)
        if (!fileSystem.writeFileBytes(path, preview.bytes)) return ImportError.Failed("could not write the drawing").left()

        val asset = assets.registerAsset(path, notesPath, writeActor = writeActor, fileSystem = fileSystem, intent = AssetIntent.DRAWING)
            .fold({ return fail(it) }, { it })

        val position = repos.sheets.getSheets(projectUuid).first().getOrNull()?.items?.size?.toLong() ?: 0L
        val sheet = SketchSheet(
            uuid = SheetUuid(UuidGenerator.generateV7()),
            projectUuid = projectUuid,
            kind = SHEET_KIND,
            viewDirection = if (preview.viewDirection == ViewDirection.RIGHT_TO_LEFT) "from-back" else "from-front",
            unit = "in",
            scaleSixteenthsPerUnit = 16,
            sourceAssetUuid = asset.uuid.value,
            position = position,
        )
        writeAll(token, sheet, dimensions).fold({ return fail(it) }, { })
        return ImportOutcome(sheet.uuid, path, dimensions.size).right()
    }

    @OptIn(DirectRepositoryWrite::class)
    private suspend fun writeAll(token: RevisionToken, sheet: SketchSheet, dimensions: List<SketchDimension>): Either<DomainError, Unit> {
        val body: suspend () -> Either<DomainError, Unit> = {
            repos.sheets.saveSheet(sheet, token).fold({ it.left() }, {
                var result: Either<DomainError, Unit> = Unit.right()
                for (d in dimensions) {
                    result = repos.dimensions.saveDimension(d, token)
                    if (result.isLeft()) break
                }
                result
            })
        }
        return writeActor?.execute { body() } ?: body()
    }

    private fun fail(error: DomainError): Either<ImportError, Nothing> = ImportError.Failed(error.message).left()

    private companion object {
        const val SVG = "svg"
        const val SHEET_KIND = "drawing"
        const val HASH_PREFIX = 8
    }
}

internal fun extensionOf(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

/** Bound labels at or above the accept threshold, plus the user-tagged ones; each becomes an `svg` observation. */
internal fun dimensionsFor(
    projectUuid: ProjectUuid,
    preview: DrawingPreview,
    registry: DimensionRegistry,
    assignments: Map<String, String>,
): List<SketchDimension> {
    val autoBound = preview.labels.bound.filter { it.dimensionKey != null && it.confidence >= BindingConfidence.ACCEPT }
        .map { it to it.dimensionKey!! }
    val tagged = preview.labels.unbound.mapNotNull { label -> assignments[label.ref]?.let { label to it } }
    return (autoBound + tagged).mapNotNull { (label, key) ->
        val entry = registry.entry(key) ?: return@mapNotNull null
        SketchDimension(
            uuid = UuidGenerator.generateV7(),
            projectUuid = projectUuid,
            key = key,
            kind = "length",
            value = label.value,
            precision = label.precision,
            sourceKind = "svg",
            sourceRef = label.ref,
        ).takeIf { entry.kind == DimensionKind.LENGTH }
    }
}
