// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.constraints.Constraint
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTier
import dev.stapler.stelekit.sketch.v2.runtime.constraints.AutoConstrainResult
import dev.stapler.stelekit.sketch.v2.runtime.constraints.undoAutoConstraints
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConstraintStrings

/**
 * The "Constraint added." toast of auto-constrain (Task 4.2.2b, UX-39): one `AddedConstraint` notice with Undo, whose Details
 * name the kinds ("Added Coincident"). It goes through [SketchNoticeQueue], so identical notices coalesce into a count, the live
 * region announces it politely and the lane shows it like any other toast. Undo removes the inferred constraints and leaves the
 * drawn shape; it is its own undoable step.
 */
object ConstraintToasts {
    fun added(constraints: List<Constraint>): SketchNotice = SketchNotice(
        key = SketchConstraintStrings.Toast.KEY,
        tier = BannerTier.Info,
        text = SketchConstraintStrings.Toast.SUMMARY,
        action = SketchConstraintStrings.Toast.UNDO,
        confirmsUndoableEdit = true,
        shortTitle = SketchConstraintStrings.Toast.SHORT,
        details = SketchConstraintStrings.Toast.details(constraints.map { SketchConstraintStrings.Panel.kindLabel(it.kind) }),
    )

    /** The toast of a two-pick creation (Task 4.2.6b): the same notice, with [details] from `PickStep.Complete`, "Added Coincident: Line 1 end, Line 2 start". */
    fun addedByPick(details: String): SketchNotice = added(emptyList()).copy(details = details)

    /** Posts the toast for a drawn shape's inferred constraints; nothing is posted when none were added (Ctrl held, toggle off, nothing snapped). */
    fun post(queue: SketchNoticeQueue, result: AutoConstrainResult, now: Long): SketchNotice? {
        val with = result as? AutoConstrainResult.WithConstraints ?: return null
        return added(with.added).also { queue.post(it, now) }
    }

    /** What the toast's Undo commits: the inferred constraints go, the shape stays. */
    fun undo(result: AutoConstrainResult.WithConstraints): Command = undoAutoConstraints(result.added)
}
