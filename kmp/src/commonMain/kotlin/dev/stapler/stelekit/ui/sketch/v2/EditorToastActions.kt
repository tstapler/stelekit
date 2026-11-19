// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchEditorStrings

/**
 * Runs the More item [item] of [notice] when it is one this editor owns: "Unlock all" of the `LockedSkipped` toast unlocks the
 * locked elements of the selection as one undo step and says so (Task 3.5.2d, UX-86). True when the item was ours.
 */
internal fun SketchEditorController.onToastMore(notice: SketchNotice, item: String): Boolean {
    if (notice.key != SketchEditorStrings.Toasts.KEY_LOCKED_SKIPPED) return false
    if (item != LayerNotices.unlockAll(emptyList()).label) return false
    val locked = ui.selection.map(::ElementId).filter { doc.scene[it]?.base?.locked == true }
    val action = LayerNotices.unlockAll(locked)
    action.command?.let { submit(it) }
    notifier.announce(action.announcement)
    return true
}
