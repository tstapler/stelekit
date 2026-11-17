// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.export

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.sketch.v2.export.SvgExportOptions
import dev.stapler.stelekit.sketch.v2.export.SvgExporter
import dev.stapler.stelekit.sketch.v2.export.SvgImageSource
import dev.stapler.stelekit.sketch.v2.image.ReferencedAssets
import dev.stapler.stelekit.sketch.v2.importer.ExcalidrawExportOptions
import dev.stapler.stelekit.sketch.v2.importer.ExcalidrawExporter
import dev.stapler.stelekit.sketch.v2.index.Bounds
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.library.LibraryCodec
import dev.stapler.stelekit.sketch.v2.library.LibraryItem
import dev.stapler.stelekit.sketch.v2.render.DrawListBuilder
import dev.stapler.stelekit.sketch.v2.render.RenderStyle
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.FrameElement
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import kotlinx.coroutines.CancellationException

enum class ExportFormat(val extension: String, val mimeType: String) {
    Png("png", "image/png"),
    Svg("svg", "image/svg+xml"),
    Excalidraw("excalidraw", "application/json"),
    Library("excalidrawlib", "application/json"),
    Csv("csv", "text/csv"),
}

/** What part of the sketch is exported (Story 5.2.3): everything, the selected elements, or one frame cropped to its bounds. */
enum class ExportScope { Whole, Selection, Frame }

/** "As drawn" keeps each element's own roughness (the sketchy look); "Clean" draws every element with exact geometry. */
enum class ExportStyle(val render: RenderStyle) { AsDrawn(RenderStyle.Sketchy), Clean(RenderStyle.Clean) }

data class ExportRequest(
    val format: ExportFormat = ExportFormat.Png,
    val scope: ExportScope = ExportScope.Whole,
    val frameId: ElementId? = null,
    val scale: Int = 2,
    val background: Boolean = true,
    val style: ExportStyle = ExportStyle.AsDrawn,
)

/** The sheet as the dialog sees it. [csv] is null when the sheet has no dimensions. */
class ExportSource(
    val scene: SketchSceneV2,
    val selection: Set<ElementId>,
    val images: SvgImageSource,
    val library: List<LibraryItem> = emptyList(),
    val csv: (() -> ByteArray)? = null,
    val baseName: String = DEFAULT_BASE_NAME,
) {
    companion object {
        const val DEFAULT_BASE_NAME = "sketch"
    }
}

/** A finished export: the bytes, the file name and how many images had no data and were written as placeholders. */
class ExportFile(val bytes: ByteArray, val fileName: String, val mimeType: String, val missingImages: Int)

sealed interface ExportFailure {
    /** The PNG would be over `SceneLimits.maxPngSidePx` on a side; the dialog offers 1x. */
    data class TooLarge(val widthPx: Long, val heightPx: Long, val maxSidePx: Int) : ExportFailure

    /** The image could not be encoded, or memory ran out; the scene is untouched. */
    data object EncodeFailed : ExportFailure

    data object NothingToExport : ExportFailure
}

/**
 * Story 5.2.3: turns an [ExportRequest] over an [ExportSource] into one file. Scope is applied first, so every format sees
 * the same subject: a selection is the selected elements (a selected frame brings its members), a frame is the frame and its
 * members cropped to the frame's bounds.
 */
class SketchExportRunner(
    private val png: PngExporter,
    private val builder: DrawListBuilder,
    private val excalidraw: ExcalidrawExporter = ExcalidrawExporter(),
) {
    /** The PNG size for [request], for the preview and the impossible-export check; null for any other format. */
    fun measure(source: ExportSource, request: ExportRequest): Either<ExportFailure, PixelSize>? {
        if (request.format != ExportFormat.Png) return null
        val subject = subject(source, request) ?: return ExportFailure.NothingToExport.left()
        return png.measure(subject.scene, pngOptions(request, subject)).mapLeft(::failure)
    }

    fun run(source: ExportSource, request: ExportRequest): Either<ExportFailure, ExportFile> = try {
        when (request.format) {
            ExportFormat.Library -> text(LibraryCodec.encode(source.library), source, request, 0)
            ExportFormat.Csv -> source.csv?.let { file(it(), source, request, 0).right() } ?: ExportFailure.NothingToExport.left()
            else -> subject(source, request)?.let { inScope(it, source, request) } ?: ExportFailure.NothingToExport.left()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Throwable: an out-of-memory condition while allocating the bitmap must be the retryable EncodeFailed, not a crash.
        ExportFailure.EncodeFailed.left()
    }

    private fun inScope(subject: Subject, source: ExportSource, request: ExportRequest): Either<ExportFailure, ExportFile> {
        val missing = ReferencedAssets.placeholders(subject.scene) { source.images.load(it) != null }.size
        return when (request.format) {
            ExportFormat.Png -> png.export(subject.scene, builder, request.style.render, pngOptions(request, subject)).mapLeft(::failure)
                .map { file(it, source, request, missing) }
            ExportFormat.Svg -> {
                val options = SvgExportOptions(background = background(request), bounds = subject.bounds)
                text(SvgExporter.export(subject.scene, builder, request.style.render, source.images, options), source, request, missing)
            }
            else -> excalidraw.export(subject.scene, source.images, ExcalidrawExportOptions()).fold(
                { ExportFailure.EncodeFailed.left() },
                { drawing -> text(drawing.text, source, request, drawing.missingFileIds.size) },
            )
        }
    }

    private fun text(content: String, source: ExportSource, request: ExportRequest, missing: Int): Either<ExportFailure, ExportFile> =
        file(content.encodeToByteArray(), source, request, missing).right()

    private fun file(bytes: ByteArray, source: ExportSource, request: ExportRequest, missing: Int): ExportFile =
        ExportFile(bytes, "${source.baseName}.${request.format.extension}", request.format.mimeType, missing)

    private fun failure(error: PngExportError): ExportFailure = when (error) {
        is PngExportError.TooLarge -> ExportFailure.TooLarge(error.widthPx, error.heightPx, error.maxSidePx)
        PngExportError.NothingToExport -> ExportFailure.NothingToExport
        PngExportError.InvalidScale, PngExportError.EncodeFailed -> ExportFailure.EncodeFailed
    }

    private fun background(request: ExportRequest): String? = if (request.background) WHITE else null

    private fun pngOptions(request: ExportRequest, subject: Subject) =
        PngExportOptions(scale = request.scale, background = background(request), bounds = subject.bounds)

    /** The scene a scope exports and, for a frame, the crop. Null when the scope selects nothing. */
    private class Subject(val scene: SketchSceneV2, val bounds: Bounds?)

    private fun subject(source: ExportSource, request: ExportRequest): Subject? {
        val scene = source.scene
        return when (request.scope) {
            ExportScope.Whole -> Subject(scene, null).takeIf { scene.size > 0 }
            ExportScope.Selection -> keeping(scene, withMembers(scene, source.selection))?.let { Subject(it, null) }
            ExportScope.Frame -> {
                val frame = request.frameId?.let { scene[it] } as? FrameElement ?: return null
                keeping(scene, withMembers(scene, setOf(frame.id)))?.let { Subject(it, ElementBounds.of(frame)) }
            }
        }
    }

    private fun withMembers(scene: SketchSceneV2, ids: Set<ElementId>): Set<ElementId> {
        val frames = ids.mapNotNull { scene[it] }.filterIsInstance<FrameElement>().mapTo(HashSet()) { it.id }
        return ids + scene.elementsInOrder.filter { it.base.frameId in frames }.map { it.id }
    }

    private fun keeping(scene: SketchSceneV2, keep: Set<ElementId>): SketchSceneV2? {
        val kept = keep.filter { it in scene }
        if (kept.isEmpty()) return null
        return scene.order.filter { it !in keep }.fold(scene) { s, id -> s.remove(id).getOrNull() ?: s }
    }

    private companion object {
        const val WHITE = "#ffffff"
    }
}
