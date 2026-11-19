// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.importing

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.sketch.excalidraw.ImportError
import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.v2.commands.ImportScene
import dev.stapler.stelekit.sketch.v2.importer.ExcalidrawImporter
import dev.stapler.stelekit.sketch.v2.importer.ImportedImage
import dev.stapler.stelekit.sketch.v2.importer.ImportedScene
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.scene.ImageElement
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.SceneElement
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits

/** The three choices of the over-cap dialog (UX-53). */
enum class OverCapChoice { ImportFirst, ImportIntoNewSketch, Cancel }

/**
 * A file that holds more elements than the sheet can take. [fileElements] is the exact count, or null when the file is over the
 * bound the reader counts to; [room] is how many more elements the current sheet can hold.
 */
data class OverCap(val fileElements: Int?, val limit: Int, val room: Int) {
    val canImportFirst: Boolean get() = room > 0

    /** The file would fit a sheet of its own: the sheet is short of room, not the file over the cap. */
    val fitsNewSketch: Boolean get() = fileElements != null && fileElements <= limit
}

/** What the user is told before commit: [overCap] when the file does not fit, [newerSchema] when a newer Excalidraw wrote it. */
class PreparedImport(
    val scene: ImportedScene,
    val currentCount: Int,
    val overCap: OverCap?,
    private val limits: SceneLimits,
) {
    val newerSchema: Boolean get() = scene.newerSchema

    /** The commit for [choice]; [OverCapChoice.Cancel] is not a plan and returns null. [centreX] and [centreY] are the viewport centre. */
    fun plan(choice: OverCapChoice?, centreX: Double, centreY: Double): ImportPlan? {
        val all = scene.elements
        val kept = when {
            overCap == null -> all
            choice == OverCapChoice.ImportFirst -> all.take(overCap.room.coerceAtLeast(0))
            choice == OverCapChoice.ImportIntoNewSketch -> all.take(limits.maxElements)
            else -> return null
        }
        val newSketch = overCap != null && choice == OverCapChoice.ImportIntoNewSketch
        val (dx, dy) = if (newSketch) 0.0 to 0.0 else offsetToCentre(kept, centreX, centreY)
        val fileIds = kept.filterIsInstance<ImageElement>().mapNotNullTo(HashSet()) { it.fileId }
        return ImportPlan(
            command = ImportScene(kept, scene.layers, dx, dy),
            elements = kept,
            layers = scene.layers,
            images = scene.images.filter { it.fileId in fileIds },
            overLimit = all.size - kept.size,
            intoNewSketch = newSketch,
        )
    }

    private fun offsetToCentre(elements: List<SceneElement>, centreX: Double, centreY: Double): Pair<Double, Double> {
        if (elements.isEmpty()) return 0.0 to 0.0
        val bounds = elements.map { ElementBounds.geometry(it) }
        val x = (bounds.minOf { it.minX } + bounds.maxOf { it.maxX }) / 2.0
        val y = (bounds.minOf { it.minY } + bounds.maxOf { it.maxY }) / 2.0
        return (centreX - x) to (centreY - y)
    }
}

/**
 * Everything one commit does. [command] adds [elements] to the current sheet; for [intoNewSketch] the elements are the content
 * of a new sheet instead and the current sheet is never touched. [images] are stored once each before either.
 */
class ImportPlan(
    val command: ImportScene,
    val elements: List<SceneElement>,
    val layers: List<Layer>,
    val images: List<ImportedImage>,
    val overLimit: Int,
    val intoNewSketch: Boolean,
)

/**
 * Story 5.1.5: reads a source through the bounded importer and decides what committing it would do, without changing
 * anything. A file over the sheet's element cap is read to a wider bound ([OVER_CAP_READ_FACTOR] times the cap) so the dialog can
 * state its real count; beyond that bound the count is "more than" and only Cancel is offered.
 */
class ExcalidrawImportRunner(private val limits: SceneLimits = SceneLimits.Default) {
    private val importer = ExcalidrawImporter(limits.copy(maxElements = limits.maxElements * OVER_CAP_READ_FACTOR))

    suspend fun prepare(source: ImportSource, currentCount: Int): Either<ImportError, PreparedImport> {
        if (source.size > limits.maxImportBytes) return ImportSource.tooLarge(limits).left()
        val scene = importer.import(source).fold({ return it.left() }, { it })
        val room = limits.maxElements - currentCount
        val over = if (scene.elements.size > room) OverCap(scene.elements.size.takeIf { it <= limits.maxElements * OVER_CAP_READ_FACTOR }, limits.maxElements, room) else null
        return PreparedImport(scene, currentCount, over, limits).right()
    }

    companion object {
        /** How far past `SceneLimits.maxElements` the reader counts before it answers "more than". */
        const val OVER_CAP_READ_FACTOR: Int = 10
    }
}
