// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.migration

import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.DiagnosticsPort
import dev.stapler.stelekit.sketch.v2.runtime.session.ProjectAckStore
import dev.stapler.stelekit.ui.sketch.v2.SketchNotice
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConsentCopy

/**
 * The `PostConversion` banner candidate (UX-65): "Upgraded. Backup saved." with the backup sentence and the whole-project
 * warning as its Details. Raised once per sheet; a dismissal or a reopen never raises it again.
 */
class PostConversionNotice(private val diagnostics: DiagnosticsPort, private val acks: ProjectAckStore) {
    /** Returns true when the banner was raised (the first time for [sheetUuid]). */
    fun onConverted(sheetUuid: String, backupPath: String): Boolean {
        if (acks.postConversionShown(sheetUuid)) return false
        acks.markPostConversionShown(sheetUuid)
        return diagnostics.raise(BannerRowId.PostConversion, SketchConsentCopy.postConversionDetails(backupPath)).isRight()
    }

    fun dismiss() = diagnostics.resolve(BannerRowId.PostConversion)
}

/**
 * The mid-session mode change (UX-69): the open editor is neither swapped nor closed. [onModeChanged] returns the
 * `EngineChange` toast while an editor is open (and null otherwise); only an explicit [reopen] saves the pending edits and
 * reopens the sketch in the editor the router now chooses.
 */
class EngineChangeFlow(private val savePending: suspend () -> Unit, private val reopenSketch: () -> Unit) {
    fun onModeChanged(editorOpen: EditorOpen): SketchNotice? = if (editorOpen == EditorOpen.Yes) EngineChangeNotice.notice else null

    suspend fun reopen() {
        savePending()
        reopenSketch()
    }
}

/** Whether a sketch editor is open while the mode changes. */
enum class EditorOpen { Yes, No }
