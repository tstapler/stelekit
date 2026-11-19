// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.platform.SketchSaveOutcome
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTier
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchImportExportStrings.Export
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchImportExportStrings.Import

/** The toasts of the import and export flows. Each is a fixed summary; names and places live in Details (UX-114, ux.md toast table). */
object ImportExportNotices {
    /** `ExportSaved` (desktop, Android): [Open] only where the app can open the file ([SketchSaveOutcome.Saved.path] is set). */
    fun exportSaved(saved: SketchSaveOutcome.Saved): SketchNotice {
        val canOpen = saved.path != null
        return SketchNotice(
            key = Export.EXPORT_SAVED_KEY,
            tier = BannerTier.Info,
            text = Export.EXPORT_SAVED_SUMMARY,
            action = if (canOpen) Export.EXPORT_SAVED_OPEN else null,
            shortTitle = Export.EXPORT_SAVED_SHORT,
            details = Export.savedDetails(saved.name, saved.folder ?: Export.PLACE_CHOSEN),
            secondaryActions = if (canOpen) listOf(Export.EXPORT_SAVED_COPY_PATH) else emptyList(),
        )
    }

    /** `ExportSavedWeb`: a download cannot be opened by the app, so no [Open] (UX-151). */
    fun exportDownloaded(saved: SketchSaveOutcome.Saved): SketchNotice = SketchNotice(
        key = Export.EXPORT_SAVED_WEB_KEY,
        tier = BannerTier.Info,
        text = Export.EXPORT_SAVED_WEB_SUMMARY,
        shortTitle = Export.EXPORT_SAVED_WEB_SHORT,
        details = Export.savedWebDetails(saved.name),
    )

    /** The notice that keeps the summary dialog one tap away after it closes; its action reopens it. */
    fun importFinished(summaryLine: String): SketchNotice = SketchNotice(
        key = Import.FINISHED_KEY,
        tier = BannerTier.Info,
        text = Import.FINISHED_SUMMARY,
        action = Import.FINISHED_ACTION,
        shortTitle = Import.FINISHED_SHORT,
        details = summaryLine,
    )

    fun importUndone(): SketchNotice = SketchNotice(key = Import.UNDONE_KEY, tier = BannerTier.Info, text = Import.UNDONE_SUMMARY)
}
