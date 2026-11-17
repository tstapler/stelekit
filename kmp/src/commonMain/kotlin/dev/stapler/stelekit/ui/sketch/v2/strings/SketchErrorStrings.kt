// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.strings

import dev.stapler.stelekit.sketch.v2.runtime.telemetry.ErrorAction
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchErrorState

/**
 * The text of every [SketchErrorState] and [ErrorAction] (Story 6.2.7; ux.md error-state tables of sections 1, 9, 11, 12 and 14).
 * Part of the string table (`ui/sketch/v2/strings/`). Both lookups are exhaustive `when`s with no `else`: a state or an
 * action without text does not compile.
 */
object SketchErrorStrings {
    fun message(state: SketchErrorState): String = when (state) {
        SketchErrorState.LoadCorrupt -> "This sketch couldn't be read."
        SketchErrorState.SheetBusy -> "This sketch is open in the other editor."
        SketchErrorState.NewerSchema -> SketchMigrationStrings.NoEditor.BODY_NEWER
        SketchErrorState.SaveFailed -> SketchStrings.TopBar.FAILED_FULL
        SketchErrorState.SaveConflict -> "This sketch changed on disk while you were editing."
        SketchErrorState.LowMemory -> SketchStrings.Capacity.LOW_MEMORY_SUMMARY
        SketchErrorState.ImportInvalid -> SketchImportExportStrings.Import.NOT_EXCALIDRAW
        SketchErrorState.ImportTooLarge -> "Files over 20 MB aren't imported."
        SketchErrorState.ImportOverCap -> SketchImportExportStrings.Import.CAP_REACHED
        SketchErrorState.ImportCancelled -> SketchImportExportStrings.Import.CANCELLED
        SketchErrorState.ImportCrashed -> SketchImportExportStrings.Import.FAILED
        SketchErrorState.ExportTooLarge -> "This image would be too large. Choose 1x or export a frame."
        SketchErrorState.ExportEncodeFailed -> "Couldn't create the image. Try a smaller scale."
        SketchErrorState.ExportWriteFailed -> "Couldn't save the file."
        SketchErrorState.ConversionFailed -> "Couldn't upgrade this sketch. Your original sketch wasn't changed."
        SketchErrorState.HasTechnicalData -> "This sketch has zones or dimensions that can't be upgraded yet."
        SketchErrorState.ConstraintConflict -> SketchConstraintStrings.WarningNotice.SUMMARY
        SketchErrorState.ConstraintNoConvergence -> SketchConstraintStrings.Panel.NOT_SOLVED
        SketchErrorState.ConstraintCapReached -> SketchStrings.Capacity.CONSTRAINT_LIMIT_SUMMARY
        SketchErrorState.ElementCapReached -> "This sketch is full."
        SketchErrorState.LayerLocked -> SketchStrings.NonDrag.LOCKED
        SketchErrorState.CopyRefused -> "This can't be copied here."
    }

    fun label(action: ErrorAction): String = when (action) {
        ErrorAction.Retry -> "Retry"
        ErrorAction.SaveNow -> SketchStrings.Capacity.LOW_MEMORY_ACTION
        ErrorAction.ExportCopy -> "Export copy"
        ErrorAction.Discard -> "Discard"
        ErrorAction.Dismiss -> "Dismiss"
        ErrorAction.Undo -> "Undo"
        ErrorAction.ShowConflicts -> SketchConstraintStrings.Panel.SHOW_CONFLICTS
        ErrorAction.RemoveConstraint -> SketchConstraintStrings.Panel.REMOVE_CONFLICTING
        ErrorAction.DeleteSome -> SketchStrings.Capacity.DELETE_SOME
        ErrorAction.SplitSheet -> SketchStrings.Capacity.SPLIT
        ErrorAction.UseSmallerScale -> "Use 1x"
        ErrorAction.ChooseAnotherFile -> SketchImportExportStrings.Import.CHOOSE_ANOTHER
        ErrorAction.OpenBackup -> "Open backup"
        ErrorAction.OpenLegacyEditor -> "Open in legacy editor"
        ErrorAction.OpenInOtherEditor -> "Open there"
        ErrorAction.CopyDetails -> SketchImportExportStrings.Import.COPY_DETAILS
        ErrorAction.Close -> "Close"
    }
}
