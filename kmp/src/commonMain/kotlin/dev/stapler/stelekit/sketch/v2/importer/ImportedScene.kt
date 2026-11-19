// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.importer

import dev.stapler.stelekit.sketch.excalidraw.ExcalidrawScene
import dev.stapler.stelekit.sketch.excalidraw.ImportReason
import dev.stapler.stelekit.sketch.excalidraw.ImportReport
import dev.stapler.stelekit.sketch.v2.commands.ImportScene
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.Layer
import dev.stapler.stelekit.sketch.v2.scene.SceneElement

/** Why an image element has no picture (UX-55): it is drawn as a labelled placeholder and nothing is fetched. */
sealed interface MissingImageReason {
    /** The element names a file the `.excalidraw` does not contain. */
    data object NoFileData : MissingImageReason

    /** The element carries a link and no file data: the picture lives at a URL the importer never downloads. */
    data object LinkNotDownloaded : MissingImageReason

    /** The file entry was refused by the parser (size, type, header); [reason] is the parser's code. */
    data class Refused(val reason: ImportReason) : MissingImageReason
}

data class MissingImage(val elementId: ElementId, val reason: MissingImageReason)

/** An element the mapper could not turn into a scene element, with the mapper reason code. */
data class DroppedElement(val id: String, val code: String)

/** An embedded picture, keyed by the lowercase SHA-256 of its bytes (what `ImageAssetStore.store` will return). */
class ImportedImage(val fileId: String, val mimeType: String, val bytes: ByteArray, val widthPx: Int, val heightPx: Int) {
    override fun equals(other: Any?): Boolean = other is ImportedImage && fileId == other.fileId && mimeType == other.mimeType

    override fun hashCode(): Int = fileId.hashCode()
}

/**
 * What the mapper did beyond a straight copy. [total] counts the elements the file held except deleted ones;
 * [imported] counts those that became a scene element (a placeholder counts). Counts are by kind so the summary
 * names them: "Imported 41 of 44. Skipped: 2 stickynotes. Kept as grey placeholders: 2 embeddables."
 */
data class MapReport(
    val total: Int,
    val imported: Int,
    val skippedTypes: Map<String, Int>,
    val dropped: List<DroppedElement>,
    val placeholders: Map<String, Int>,
    val unsupportedArrowheads: Int,
    val unknownFills: Int,
    val unknownStrokeStyles: Int,
    val legacyBindings: Int,
    val missingImages: List<MissingImage>,
    val layerHintsIgnored: Int,
    /** True when the file's `index` values were absent, repeated or out of array order, so paint order follows the array. */
    val zOrderFromArray: Boolean,
) {
    val skipped: Int get() = total - imported

    /** One line for the import summary; empty parts are left out. */
    fun summary(): String {
        val parts = mutableListOf("Imported $imported of $total.")
        val skips = skippedTypes.map { (type, n) -> countOf(n, type) } +
            listOfNotNull(if (dropped.isNotEmpty()) countOf(dropped.size, "invalid element") else null)
        if (skips.isNotEmpty()) parts += "Skipped: ${skips.joinToString(", ")}."
        if (placeholders.isNotEmpty()) parts += "Kept as grey placeholders: ${placeholders.map { (t, n) -> countOf(n, t) }.joinToString(", ")}."
        val changed = listOfNotNull(
            if (unsupportedArrowheads > 0) countOf(unsupportedArrowheads, "unsupported arrowhead") else null,
            if (unknownFills > 0) countOf(unknownFills, "unknown fill") else null,
            if (unknownStrokeStyles > 0) countOf(unknownStrokeStyles, "unknown stroke style") else null,
        )
        if (changed.isNotEmpty()) parts += "Changed: ${changed.joinToString(", ")}."
        if (missingImages.isNotEmpty()) parts += "${countOf(missingImages.size, "image")} could not be shown."
        return parts.joinToString(" ")
    }

    private fun countOf(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"
}

/** The result of mapping one parsed file: elements in paint order, the layers they name, and the pictures to store. */
data class ImportedScene(
    val elements: List<SceneElement>,
    val layers: List<Layer>,
    val images: List<ImportedImage>,
    val report: MapReport,
    val parse: ImportReport,
    val backgroundColor: String?,
    val gridSize: Double?,
    /** The file's `version` member, or null when absent; see `ExcalidrawScene.fileVersion`. */
    val fileVersion: Int? = null,
) {
    /** True when a newer Excalidraw wrote the file: the import proceeds with a notice and a Cancel before commit (Story 5.1.5b). */
    val newerSchema: Boolean get() = fileVersion != null && fileVersion > ExcalidrawScene.KNOWN_FILE_VERSION

    /** The single undoable command that adds everything to a sheet (Story 5.1.2c). */
    fun command(): ImportScene = ImportScene(elements, layers)
}
