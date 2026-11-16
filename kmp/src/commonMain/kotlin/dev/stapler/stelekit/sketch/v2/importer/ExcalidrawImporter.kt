// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.importer

import arrow.core.Either
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawElement
import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawParser
import dev.stapler.stelekit.sketch.excalidraw.ImportError
import dev.stapler.stelekit.sketch.excalidraw.ImportReason
import dev.stapler.stelekit.sketch.excalidraw.ImportResult
import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.v2.commands.ZKeys
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.scene.ZKey
import dev.stapler.stelekit.util.ContentHasher

/**
 * Story 5.1.2: maps what [ExcalidrawParser] kept (sanitised, pinned to Excalidraw v0.18.1) onto scene elements. Lives beside
 * the engine, not in `sketch/excalidraw`, because that package may not import scene types (`SketchImportImportDirectionTest`).
 *
 * Paint order is the file's array order. The file's `index` values become z-keys only when every element has one and they
 * are strictly increasing in array order; otherwise (absent, repeated, or in conflict with the array, which Excalidraw itself
 * repairs by array order) fresh keys follow the array and [MapReport.zOrderFromArray] says so.
 */
class ExcalidrawImporter(
    private val limits: SceneLimits = SceneLimits.Default,
    private val parser: ExcalidrawParser = ExcalidrawParser(limits),
) {
    suspend fun import(source: ImportSource): Either<ImportError, ImportedScene> = parser.parse(source).map { map(it) }

    fun map(result: ImportResult): ImportedScene {
        val scene = result.scene
        val report = ReportBuilder()
        val pictures = scene.files.associate { file ->
            file.id to ImportedImage(ContentHasher.sha256(file.bytes), file.mimeType, file.bytes, file.widthPx, file.heightPx)
        }
        val refused = result.report.issues.filter { it.reason in IMAGE_REASONS && it.target != null }.associate { it.target!! to it.reason }
        val base = BaseMapper(limits, report)
        val ctx = MapContext(limits, base, report, scene.elements.associateBy { it.id }, pictures, refused)
        val (keys, fromArray) = zKeys(scene.elements)
        val shapes = ShapeMapper(ctx)
        val linear = LinearMapper(ctx)
        val mapped = scene.elements.mapIndexedNotNull { i, x -> if (x.type in LINEAR_TYPES) linear.map(x, keys[i]) else shapes.map(x, keys[i]) }

        val parserDropped = result.report.issues.filter { it.reason in DROP_REASONS }
        val unknown = result.report.unknownTypes
        val total = scene.elements.size + unknown.values.sum() + parserDropped.size
        val dropped = parserDropped.map { DroppedElement(it.target.orEmpty(), it.reason.name) } + report.dropped
        return ImportedScene(
            elements = mapped,
            layers = base.layers,
            images = pictures.values.distinctBy { it.fileId },
            report = MapReport(
                total = total,
                imported = mapped.size,
                skippedTypes = unknown,
                dropped = dropped,
                placeholders = report.placeholders,
                unsupportedArrowheads = report.unsupportedArrowheads,
                unknownFills = report.unknownFills,
                unknownStrokeStyles = report.unknownStrokeStyles,
                legacyBindings = report.legacyBindings,
                missingImages = report.missingImages,
                layerHintsIgnored = report.layerHintsIgnored,
                zOrderFromArray = fromArray,
            ),
            parse = result.report,
            backgroundColor = scene.appState?.viewBackgroundColor,
            gridSize = scene.appState?.gridSize,
        )
    }

    private fun zKeys(elements: List<ExcalidrawElement>): Pair<List<ZKey>, Boolean> {
        val indexes = elements.map { it.index }
        val usable = indexes.all { it != null && it.isNotEmpty() && it.all(::isKeyChar) } &&
            indexes.zipWithNext().all { (a, b) -> a!! < b!! }
        return if (usable) indexes.map { ZKey(it!!) } to false else ZKeys.sequence(elements.size) to true
    }

    private fun isKeyChar(c: Char): Boolean = c in '0'..'9' || c in 'A'..'Z' || c in 'a'..'z'

    private companion object {
        val LINEAR_TYPES = setOf("line", "arrow", "freedraw")
        val IMAGE_REASONS = setOf(
            ImportReason.ImageTooLarge, ImportReason.ImageMimeMismatch, ImportReason.ImageMimeNotAllowed, ImportReason.ImageInvalid,
            ImportReason.ImageBudgetExceeded, ImportReason.DataUrlEscaped, ImportReason.LibraryImage,
        )
        val DROP_REASONS = setOf(
            ImportReason.ElementNotAnObject, ImportReason.InvalidNumber, ImportReason.NonFiniteCoordinate,
            ImportReason.CoordinateOutOfRange, ImportReason.TooManyPoints,
        )
    }
}
