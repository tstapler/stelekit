// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTier
import dev.stapler.stelekit.sketch.v2.runtime.constraints.SolveRejection
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchErrorState
import dev.stapler.stelekit.sketch.v2.tools.ToolNotice
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEditorStrings
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchErrorStrings

/**
 * What the editor tells the user: toasts through the shared [queue], the status line and the banner rows. Every refusal of an edit,
 * a tool notice and a solve rejection reaches the user through here, in the words of the string table. [revision] moves whenever
 * the queue changed, so the toast lane re-reads it.
 */
class EditorNotifier(val queue: SketchNoticeQueue, private val clockMillis: () -> Long) {
    var revision: Int by mutableIntStateOf(0)
        private set

    fun now(): Long = clockMillis()

    fun toast(key: String, text: String) = post(SketchNotice(key, BannerTier.Info, text))

    fun post(notice: SketchNotice) {
        queue.post(notice, now())
        revision += 1
    }

    fun announce(text: String) = queue.announceStatus(text, ANNOUNCE_KEY)

    fun toolNotice(notice: ToolNotice) {
        val (key, text) = when (notice) {
            is ToolNotice.LockedSkipped -> SketchEditorStrings.Toasts.KEY_LOCKED_SKIPPED to SketchEditorStrings.Toasts.lockedSkipped(notice.skipped)
            is ToolNotice.LockedNotErased -> SketchEditorStrings.Toasts.KEY_LOCKED_NOT_ERASED to SketchEditorStrings.Toasts.lockedNotErased(notice.skipped)
            ToolNotice.ZoomInForHandles -> SketchEditorStrings.Toasts.KEY_ZOOM_FOR_HANDLES to SketchEditorStrings.Toasts.ZOOM_FOR_HANDLES
            ToolNotice.NothingDrawn -> SketchEditorStrings.Toasts.KEY_NOTHING_DRAWN to SketchEditorStrings.Toasts.NOTHING_DRAWN
        }
        toast(key, text)
    }

    /** Says why [error] stopped an edit. */
    fun refuse(error: CommandError) {
        val text = when (error) {
            is CommandError.CapacityExceeded -> SketchErrorStrings.message(SketchErrorState.ElementCapReached)
            is CommandError.ConstraintCapExceeded -> SketchErrorStrings.message(SketchErrorState.ConstraintCapReached)
            else -> SketchEditorStrings.Toasts.EDIT_FAILED
        }
        toast(SketchEditorStrings.Toasts.KEY_REFUSED, text)
    }

    /** Says why the solve refused an edit. */
    fun rejected(reason: SolveRejection) {
        val state = when (reason) {
            is SolveRejection.Unsatisfiable -> SketchErrorState.ConstraintConflict
            is SolveRejection.EditFailed -> return refuse(reason.error)
            is SolveRejection.Crashed -> SketchErrorState.LowMemory
        }
        toast(SketchEditorStrings.Toasts.KEY_REFUSED, SketchErrorStrings.message(state))
    }

    fun lockedSkipped(count: Int) {
        if (count > 0) toast(SketchEditorStrings.Toasts.KEY_LOCKED_SKIPPED, SketchEditorStrings.Toasts.lockedSkipped(count))
    }

    /** True while a toast with a deadline is up, so the lane keeps re-reading the queue until it expires. */
    fun hasTimedToast(): Boolean = queue.visible(now()).any { NoticeTiming.durationOf(it.notice) != null }

    /** The primary button of a toast: the Undo of an undoable confirmation is the ordinary Undo command. */
    fun act(notice: SketchNotice, undo: () -> Unit) {
        if (notice.confirmsUndoableEdit) undo()
        queue.act(notice.key, now())
        revision += 1
    }

    /** The saver or another owner changed a banner row: the lane and the slot re-read the queue. */
    fun refresh() {
        revision += 1
    }

    fun resolve(row: BannerRowId) {
        queue.resolveBanner(row)
        revision += 1
    }

    private companion object {
        const val ANNOUNCE_KEY = "editor.status"
    }
}
