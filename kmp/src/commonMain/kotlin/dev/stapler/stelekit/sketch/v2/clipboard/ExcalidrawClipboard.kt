// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.clipboard

import arrow.core.Either
import dev.stapler.stelekit.sketch.excalidraw.ImportError
import dev.stapler.stelekit.sketch.excalidraw.ImportReason
import dev.stapler.stelekit.sketch.excalidraw.ImportSource
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.PasteElements
import dev.stapler.stelekit.sketch.v2.importer.ExcalidrawImporter
import dev.stapler.stelekit.sketch.v2.importer.ImportedImage
import dev.stapler.stelekit.sketch.v2.importer.ImportedScene
import dev.stapler.stelekit.sketch.v2.importer.MapReport
import dev.stapler.stelekit.sketch.v2.index.ElementBounds
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource

/** What pasting Excalidraw clipboard text came to (Story 5.1.4). */
sealed interface ExcalidrawPaste {
    /**
     * The elements are ready: [command] pastes them as ONE undo step with fresh ids, centred on the cursor. [images] are the
     * embedded pictures the caller stores (`ImageAssetStore`) before running the command; [report] feeds the summary.
     */
    class Ready(val command: Command, val images: List<ImportedImage>, val report: MapReport, val pastedIds: List<ElementId>) : ExcalidrawPaste

    /** The text is not an Excalidraw scene (ordinary text, other JSON, broken JSON, nothing to paste): do nothing. */
    data object NotExcalidraw : ExcalidrawPaste

    /** It is Excalidraw JSON but over a limit (size, element count, time): tell the user, paste nothing. */
    data class Refused(val error: ImportError) : ExcalidrawPaste
}

/**
 * Pastes Excalidraw clipboard JSON (`{"type":"excalidraw/clipboard","elements":[...]}`) at the cursor (Story 5.1.4). The text
 * enters through [ImportSource.ofText] and the same bounded parser and mapper as a file, so every `SceneLimits` cap
 * applies; ids are remapped and the whole paste is one command. As Excalidraw does, the pasted group is centred on the cursor.
 */
object ExcalidrawClipboard {
    /** Reads [text]; [cursorX] and [cursorY] are the paste point in scene units. */
    suspend fun read(
        text: String,
        cursorX: Double,
        cursorY: Double,
        ids: ElementIdSource,
        limits: SceneLimits = SceneLimits.Default,
    ): ExcalidrawPaste {
        val source = ImportSource.ofText(text, limits).fold({ return refusal(it) }, { it })
        val imported = when (val result = ExcalidrawImporter(limits).import(source)) {
            is Either.Left -> return refusal(result.value)
            is Either.Right -> result.value
        }
        return if (imported.elements.isEmpty()) ExcalidrawPaste.NotExcalidraw else ready(imported, cursorX, cursorY, ids)
    }

    private fun refusal(error: ImportError): ExcalidrawPaste =
        if (error.reason == ImportReason.NotExcalidraw || error.reason == ImportReason.MalformedJson) ExcalidrawPaste.NotExcalidraw else ExcalidrawPaste.Refused(error)

    private fun ready(imported: ImportedScene, cursorX: Double, cursorY: Double, ids: ElementIdSource): ExcalidrawPaste {
        val bounds = imported.elements.map { ElementBounds.geometry(it) }
        val centreX = (bounds.minOf { it.minX } + bounds.maxOf { it.maxX }) / 2.0
        val centreY = (bounds.minOf { it.minY } + bounds.maxOf { it.maxY }) / 2.0
        val map = imported.elements.associate { it.id to ids.next() }
        val command = PasteElements(imported.elements, map, cursorX - centreX, cursorY - centreY)
        return ExcalidrawPaste.Ready(command, imported.images, imported.report, imported.elements.map { map.getValue(it.id) })
    }
}
