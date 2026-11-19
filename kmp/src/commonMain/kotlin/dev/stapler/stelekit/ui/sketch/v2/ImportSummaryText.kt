// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.excalidraw.ImportReason
import dev.stapler.stelekit.sketch.v2.importer.ImportedScene
import dev.stapler.stelekit.sketch.v2.importer.MissingImageReason
import dev.stapler.stelekit.sketch.v2.runtime.importing.OverCap
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchImportExportStrings.Codes
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchImportExportStrings.Import as Strings

/**
 * The words of the import summary dialog (ux.md section 11) and its copyable details. Pure: the lines come from what the
 * importer reported (OD-6: unsupported elements are kept as grey placeholders and counted), so a test reads them without a screen.
 */
object ImportSummaryText {
    /**
     * The summary lines of a finished import. [imported] is what was added (after any cap trim); [overLimit] counts elements
     * left out because they did not fit. A file from a newer Excalidraw adds its line.
     */
    fun lines(scene: ImportedScene, imported: Int, overLimit: Int): List<String> {
        val report = scene.report
        val skips = report.skippedTypes.map { (type, n) -> Strings.countOf(n, type) } +
            listOfNotNull(
                report.dropped.size.takeIf { it > 0 }?.let { Strings.countOf(it, Strings.INVALID_ELEMENT) },
                overLimit.takeIf { it > 0 }?.let { Strings.overLimit(it) },
            )
        val kept = report.placeholders.map { (type, n) -> Strings.countOf(n, type) }
        val changed = listOfNotNull(
            report.unsupportedArrowheads.takeIf { it > 0 }?.let { Strings.countOf(it, Strings.UNSUPPORTED_ARROWHEAD) },
            report.unknownFills.takeIf { it > 0 }?.let { Strings.countOf(it, Strings.UNKNOWN_FILL) },
            report.unknownStrokeStyles.takeIf { it > 0 }?.let { Strings.countOf(it, Strings.UNKNOWN_STROKE_STYLE) },
        )
        return buildList {
            add(Strings.imported(imported, report.total))
            if (skips.isNotEmpty()) add(Strings.skipped(skips))
            if (kept.isNotEmpty()) add(Strings.placeholders(kept))
            if (changed.isNotEmpty()) add(Strings.changed(changed))
            if (report.missingImages.isNotEmpty()) add(Strings.imagesMissing(report.missingImages.size))
            if (report.missingImages.any { it.reason == MissingImageReason.LinkNotDownloaded }) add(Strings.LINKED_IMAGES)
            if (scene.newerSchema) add(Strings.NEWER_SCHEMA)
        }
    }

    /**
     * The reason codes behind the summary, one `CODE count` per line in a fixed order, for "Copy details". Codes only: no element
     * text, file name or id ever enters it, so it is safe to paste into a bug report.
     */
    fun details(scene: ImportedScene): String {
        val counts = mutableMapOf<String, Int>()
        fun bump(code: String, by: Int = 1) {
            counts[code] = (counts[code] ?: 0) + by
        }
        scene.parse.issues.forEach { bump(it.reason.name) }
        if (scene.parse.deletedCount > 0) bump(Codes.DELETED, scene.parse.deletedCount)
        scene.parse.unknownTypes.forEach { (type, n) -> bump(Codes.tagged(Codes.UNKNOWN_TYPE, type), n) }
        scene.parse.unsupported.forEach { (type, n) -> bump(Codes.tagged(Codes.UNSUPPORTED, type), n) }
        scene.report.dropped.forEach { bump(it.code) }
        scene.report.missingImages.forEach { bump(Codes.tagged(Codes.MISSING_IMAGE, missingCode(it.reason))) }
        if (scene.report.zOrderFromArray) bump(Codes.Z_ORDER)
        if (scene.newerSchema) bump(Codes.NEWER_SCHEMA)
        return (listOf(Codes.head(scene.report.imported, scene.report.total)) + counts.toList().sortedBy { it.first }.map { (code, n) -> Codes.line(code, n) }).joinToString("\n")
    }

    private fun missingCode(reason: MissingImageReason): String = when (reason) {
        MissingImageReason.NoFileData -> Codes.NO_FILE_DATA
        MissingImageReason.LinkNotDownloaded -> Codes.LINK_NOT_DOWNLOADED
        is MissingImageReason.Refused -> reason.reason.name
    }
}

/** The refusal and over-cap sentences of the import dialogs, chosen from the importer's reason code. */
object ImportMessages {
    fun refusal(reason: ImportReason, limits: SceneLimits): String = when (reason) {
        ImportReason.FileTooLarge -> Strings.tooLarge(limits.maxImportBytes / BYTES_PER_MB)
        ImportReason.NotExcalidraw, ImportReason.MalformedJson -> Strings.NOT_EXCALIDRAW
        ImportReason.TooManyElements -> Strings.overCap(null, limits.maxElements)
        ImportReason.ImportTimeout -> Strings.TIMEOUT
        else -> Strings.NOT_EXCALIDRAW
    }

    /** "This drawing has 7,200 elements; the limit is 5,000.", or the room-left wording when the file would fit a sheet of its own. */
    fun overCap(over: OverCap): String = when {
        over.fileElements == null -> Strings.overCap(null, over.limit)
        over.fitsNewSketch -> Strings.overRoom(over.fileElements, over.room)
        else -> Strings.overCap(over.fileElements, over.limit)
    }

    private const val BYTES_PER_MB = 1_048_576L
}
