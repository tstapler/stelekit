// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.runtime.telemetry

/** What the editor looks like once the user has acted on an error state (UX-75: no state is a dead end). */
enum class EditorAfter {
    /** No modal is left and the tools are enabled. */
    UsableEditor,

    /** The sheet stays open read-only and the read-only banner explains why. */
    ReadOnlyBanner,

    /** The user leaves the editor; nothing of it is left open. */
    LeavesEditor,
}

/** One thing the user can do about an error state. Labels live in `SketchErrorStrings`. */
enum class ErrorAction(val after: EditorAfter) {
    Retry(EditorAfter.UsableEditor),
    SaveNow(EditorAfter.UsableEditor),
    ExportCopy(EditorAfter.UsableEditor),
    Discard(EditorAfter.LeavesEditor),
    Dismiss(EditorAfter.UsableEditor),
    Undo(EditorAfter.UsableEditor),
    ShowConflicts(EditorAfter.UsableEditor),
    RemoveConstraint(EditorAfter.UsableEditor),
    DeleteSome(EditorAfter.UsableEditor),
    SplitSheet(EditorAfter.UsableEditor),
    UseSmallerScale(EditorAfter.UsableEditor),
    ChooseAnotherFile(EditorAfter.UsableEditor),
    OpenBackup(EditorAfter.ReadOnlyBanner),
    OpenLegacyEditor(EditorAfter.LeavesEditor),
    OpenInOtherEditor(EditorAfter.LeavesEditor),
    CopyDetails(EditorAfter.UsableEditor),
    Close(EditorAfter.LeavesEditor),
}

/** The story group that owns a state; a parked group's states are skipped, and listed, by the coverage tests. */
enum class ErrorOwner { Core, Job2Ui, Interop }

/**
 * Every user-visible error state of the v2 editor (plan Story 6.2.7, UX-71, UX-75). One enum drives both the actions the
 * UI offers ([actions]) and the one `sketch.*` line that is logged ([logName], through `SketchLog.errorState`). Both
 * tables are exhaustive `when`s with no `else`, so a state added without a log line and an action row does not compile.
 * A story that adds an error state later adds its value here in its own change; `ErrorLogCoverageTest` fails otherwise.
 */
enum class SketchErrorState(val owner: ErrorOwner = ErrorOwner.Core) {
    LoadCorrupt, SheetBusy, NewerSchema, SaveFailed, SaveConflict, LowMemory,
    ImportInvalid(ErrorOwner.Interop), ImportTooLarge(ErrorOwner.Interop), ImportOverCap(ErrorOwner.Interop),
    ImportCancelled(ErrorOwner.Interop), ImportCrashed(ErrorOwner.Interop),
    ExportTooLarge(ErrorOwner.Interop), ExportEncodeFailed(ErrorOwner.Interop), ExportWriteFailed(ErrorOwner.Interop),
    ConversionFailed, HasTechnicalData,
    ConstraintConflict(ErrorOwner.Job2Ui), ConstraintNoConvergence(ErrorOwner.Job2Ui), ConstraintCapReached(ErrorOwner.Job2Ui),
    ElementCapReached, LayerLocked, CopyRefused,
    ;

    /** The one `sketch.*` line name this state logs. */
    val logName: String
        get() = when (this) {
            LoadCorrupt, NewerSchema -> "sketch.sheet.rejected"
            SheetBusy -> "sketch.sheet.busy"
            SaveFailed -> "sketch.persist.failed"
            SaveConflict -> "sketch.save.conflict"
            LowMemory -> "sketch.memory.low"
            ImportInvalid, ImportTooLarge, ImportOverCap, ImportCancelled, ImportCrashed -> "sketch.import.failed"
            ExportTooLarge, ExportEncodeFailed, ExportWriteFailed -> "sketch.export.failed"
            ConversionFailed, HasTechnicalData -> "sketch.migrate.outcome"
            ConstraintConflict, ConstraintNoConvergence -> "sketch.solve.failed"
            ConstraintCapReached, ElementCapReached -> "sketch.cap.reached"
            LayerLocked -> "sketch.edit.refused"
            CopyRefused -> "sketch.copy.refused"
        }

    /** The token logged beside [logName] so two states sharing a line stay distinguishable; ids and tokens only. */
    val reasonCode: String
        get() = when (this) {
            LoadCorrupt -> "load-corrupt"
            SheetBusy -> "lease"
            NewerSchema -> "newer-schema"
            SaveFailed -> "save-failed"
            SaveConflict -> "conflict"
            LowMemory -> "low-memory"
            ImportInvalid -> "invalid"
            ImportTooLarge -> "file-too-large"
            ImportOverCap -> "over-cap"
            ImportCancelled -> "cancelled"
            ImportCrashed -> "crashed"
            ExportTooLarge -> "too-large"
            ExportEncodeFailed -> "encode-failed"
            ExportWriteFailed -> "write-failed"
            ConversionFailed -> "conversion-failed"
            HasTechnicalData -> "has-technical-data"
            ConstraintConflict -> "conflict"
            ConstraintNoConvergence -> "no-convergence"
            ConstraintCapReached -> "constraint-cap"
            ElementCapReached -> "element-cap"
            LayerLocked -> "layer-locked"
            CopyRefused -> "copy-refused"
        }

    /** The actions the UI offers, default first. Never empty: every state has at least an action or a dismissal. */
    val actions: List<ErrorAction>
        get() = when (this) {
            LoadCorrupt -> listOf(ErrorAction.OpenBackup, ErrorAction.OpenLegacyEditor, ErrorAction.Close)
            SheetBusy -> listOf(ErrorAction.OpenInOtherEditor, ErrorAction.Close)
            NewerSchema -> listOf(ErrorAction.CopyDetails, ErrorAction.OpenBackup, ErrorAction.Close)
            SaveFailed -> listOf(ErrorAction.Retry, ErrorAction.ExportCopy, ErrorAction.Discard)
            SaveConflict -> listOf(ErrorAction.OpenBackup, ErrorAction.ExportCopy, ErrorAction.Dismiss)
            LowMemory -> listOf(ErrorAction.SaveNow, ErrorAction.Dismiss)
            ImportInvalid, ImportTooLarge, ImportOverCap -> listOf(ErrorAction.ChooseAnotherFile, ErrorAction.Close)
            ImportCancelled -> listOf(ErrorAction.Dismiss)
            ImportCrashed -> listOf(ErrorAction.CopyDetails, ErrorAction.Close)
            ExportTooLarge -> listOf(ErrorAction.UseSmallerScale, ErrorAction.Dismiss)
            ExportEncodeFailed, ExportWriteFailed -> listOf(ErrorAction.Retry, ErrorAction.Dismiss)
            ConversionFailed -> listOf(ErrorAction.OpenLegacyEditor, ErrorAction.Retry, ErrorAction.Close)
            HasTechnicalData -> listOf(ErrorAction.OpenLegacyEditor, ErrorAction.Close)
            ConstraintConflict -> listOf(ErrorAction.RemoveConstraint, ErrorAction.ShowConflicts, ErrorAction.Dismiss)
            ConstraintNoConvergence -> listOf(ErrorAction.Undo, ErrorAction.ShowConflicts, ErrorAction.Dismiss)
            ConstraintCapReached -> listOf(ErrorAction.DeleteSome, ErrorAction.Dismiss)
            ElementCapReached -> listOf(ErrorAction.DeleteSome, ErrorAction.SplitSheet, ErrorAction.Dismiss)
            LayerLocked, CopyRefused -> listOf(ErrorAction.Dismiss)
        }

    companion object {
        /** Owners whose stories are parked (plan REDUCED rows); empty while every group is built. */
        val parkedOwners: Set<ErrorOwner> = emptySet()
    }
}
