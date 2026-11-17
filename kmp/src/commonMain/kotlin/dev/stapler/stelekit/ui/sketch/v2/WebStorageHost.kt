// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.stapler.stelekit.platform.V2StorageStatus
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveState
import dev.stapler.stelekit.ui.sketch.CueChip
import dev.stapler.stelekit.ui.sketch.CueForms
import dev.stapler.stelekit.ui.sketch.CuePlacement
import dev.stapler.stelekit.ui.sketch.DurabilityCue
import dev.stapler.stelekit.ui.sketch.StillSavingTiming
import dev.stapler.stelekit.ui.sketch.WebStorageUx
import kotlinx.coroutines.delay

/** The persistent durability cue of a sheet (UX-153): its four forms, the accessible name of its chip and what activating the chip does. */
class DurabilityCueView(val forms: CueForms, val accessibleName: String, val onActivate: () -> Unit)

/** The cue as this window shows it: on the hint row, or as [chip] on the chip row. */
class ShownCue(val view: DurabilityCueView, val placement: CuePlacement, val chip: CueChip?)

object DurabilityCueHost {
    /** The cue of the storage [status], or null when the storage is neither a temporary graph nor a suspected private window. */
    fun viewOf(status: V2StorageStatus, onActivate: (String) -> Unit): DurabilityCueView? {
        val ready = status as? V2StorageStatus.Ready ?: return null
        val wording = WebStorageUx.durabilityWording(ready.sessionOnly, ready.privateSuspected)
        val forms = wording.cue ?: return null
        return DurabilityCueView(forms, wording.savedName) { onActivate(forms.full) }
    }

    /** Where and in which form [view] is shown in a window of [widthDp] by [heightDp] at [fontScale]. */
    fun shown(view: DurabilityCueView, widthDp: Int, heightDp: Int, fontScale: Double): ShownCue {
        val placement = DurabilityCue.placementFor(widthDp, heightDp, fontScale)
        val chip = if (placement == CuePlacement.ChipRow) {
            DurabilityCue.chipFor(view.forms, DurabilityCue.chipRowDp(widthDp, heightDp), fontScale)
        } else {
            null
        }
        return ShownCue(view, placement, chip)
    }

    /** The hint row's one line: the cue first, the tool hint after it where it still fits [charsPerLine]. */
    fun hintText(shown: ShownCue?, hint: String, charsPerLine: Int): String =
        if (shown == null || shown.placement != CuePlacement.HintRow) hint else DurabilityCue.hintLine(shown.view.forms, null, hint, charsPerLine).text
}

/** What the `StillSaving` toast needs of the editor's notice queue. */
interface StillSavingToast {
    /** True when no other toast holds the lane: a `StillSaving` toast never displaces another one. */
    fun laneFree(now: Long): Boolean

    fun show(now: Long)

    fun hide(now: Long)
}

/**
 * Drives the `StillSaving` toast of one sheet (UX-145, UX-150) from the saver's state, following [StillSavingTiming]: it shows
 * after [StillSavingTiming.SHOWN_AFTER_MS] of saving when the lane is free and stays at least [StillSavingTiming.MIN_VISIBLE_MS].
 * Clock-free: [update] takes `now` and returns how long to wait before calling it again, or null to wait for the next state.
 */
class StillSavingWatcher(private val toast: StillSavingToast) {
    private var startedAt: Long? = null
    private var shownAt: Long? = null

    fun update(saving: Boolean, now: Long): Long? {
        if (saving) return whileSaving(now)
        startedAt = null
        val shown = shownAt ?: return null
        val remaining = shown + StillSavingTiming.MIN_VISIBLE_MS - now
        if (remaining > 0) return remaining
        toast.hide(now)
        shownAt = null
        return null
    }

    private fun whileSaving(now: Long): Long? {
        val start = startedAt ?: now.also { startedAt = it }
        if (shownAt != null) return null
        val dueIn = start + StillSavingTiming.SHOWN_AFTER_MS - now
        if (dueIn > 0) return dueIn
        if (!toast.laneFree(now)) return null
        toast.show(now)
        shownAt = now
        return null
    }
}

/** The web build's `StillSaving` toast on the editor's notifier; nothing on a platform that persists at once. */
@Composable
internal fun StillSavingEffect(controller: SketchEditorController) {
    val state by controller.saves.state.collectAsState()
    val watcher = remember(controller) {
        StillSavingWatcher(NotifierStillSavingToast(controller.notifier))
    }
    LaunchedEffect(state) {
        val saving = state is SaveState.Saving || state is SaveState.Pending
        var wait = watcher.update(saving, controller.notifier.now())
        while (wait != null) {
            delay(wait)
            wait = watcher.update(saving, controller.notifier.now())
        }
    }
}

private class NotifierStillSavingToast(private val notifier: EditorNotifier) : StillSavingToast {
    override fun laneFree(now: Long): Boolean = notifier.queue.visible(now).isEmpty()

    override fun show(now: Long) = notifier.post(StillSavingTiming.notice())

    override fun hide(now: Long) {
        notifier.queue.dismiss(StillSavingTiming.notice().key, now)
        notifier.refresh()
    }
}
