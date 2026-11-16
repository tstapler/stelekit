// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.AddElements
import dev.stapler.stelekit.sketch.v2.commands.AlignElements
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.DeleteElements
import dev.stapler.stelekit.sketch.v2.commands.DistributeElements
import dev.stapler.stelekit.sketch.v2.commands.DuplicateElements
import dev.stapler.stelekit.sketch.v2.commands.GroupElements
import dev.stapler.stelekit.sketch.v2.commands.LockElements
import dev.stapler.stelekit.sketch.v2.commands.MoveElements
import dev.stapler.stelekit.sketch.v2.commands.PasteElements
import dev.stapler.stelekit.sketch.v2.commands.ReorderElements
import dev.stapler.stelekit.sketch.v2.commands.ResizeElement
import dev.stapler.stelekit.sketch.v2.commands.RotateElement
import dev.stapler.stelekit.sketch.v2.commands.StyleElements
import dev.stapler.stelekit.sketch.v2.commands.UngroupElements
import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.ElementNames
import dev.stapler.stelekit.sketch.v2.scene.GroupId
import dev.stapler.stelekit.sketch.v2.scene.SketchSceneV2
import dev.stapler.stelekit.sketch.v2.tools.ElementIdSource

/** Words for undo and redo announcements ("move rectangle"), made from a command and the scene it ran on (UX-43). */
object CommandLabels {
    fun of(command: Command, sceneBefore: SketchSceneV2): String {
        fun first(ids: List<ElementId>): String = ids.firstNotNullOfOrNull { sceneBefore[it] }?.let { ElementNames.kindWord(it).lowercase() } ?: "element"
        return when (command) {
            is AddElement -> "add ${ElementNames.kindWord(command.element).lowercase()}"
            is AddElements -> command.elements.singleOrNull()?.let { "add ${ElementNames.kindWord(it).lowercase()}" } ?: "add ${command.elements.size} elements"
            is MoveElements -> "move ${first(command.ids)}"
            is ResizeElement -> "resize ${first(listOf(command.id))}"
            is RotateElement -> "rotate ${first(listOf(command.id))}"
            is DeleteElements -> "delete ${first(command.ids)}"
            is StyleElements -> "change style"
            is ReorderElements -> "reorder ${first(command.ids)}"
            is LockElements -> if (command.locked) "lock ${first(command.ids)}" else "unlock ${first(command.ids)}"
            is GroupElements -> "group elements"
            is UngroupElements -> "ungroup elements"
            is DuplicateElements -> "duplicate ${first(command.ids)}"
            is PasteElements -> "paste elements"
            is AlignElements -> "align elements"
            is DistributeElements -> "distribute elements"
            else -> "edit"
        }
    }
}

/**
 * Keeps the label of every undoable step so Ctrl+Z can say what it undid, and what an empty history says (UX-43). Mirrors
 * the history: a new step drops the redo labels. Immutable.
 */
class UndoAnnouncer private constructor(private val past: List<String>, private val future: List<String>) {
    fun recorded(label: String) = UndoAnnouncer(past + label, emptyList())

    /** The announcement and the announcer after an undo. */
    fun undone(): Pair<String, UndoAnnouncer> =
        if (past.isEmpty()) NOTHING_TO_UNDO to this else "Undid: ${past.last()}" to UndoAnnouncer(past.dropLast(1), listOf(past.last()) + future)

    fun redone(): Pair<String, UndoAnnouncer> =
        if (future.isEmpty()) NOTHING_TO_REDO to this else "Redid: ${future.first()}" to UndoAnnouncer(past + future.first(), future.drop(1))

    companion object {
        const val NOTHING_TO_UNDO = "Nothing to undo"
        const val NOTHING_TO_REDO = "Nothing to redo"
        val Empty = UndoAnnouncer(emptyList(), emptyList())
    }
}

/** A planned edit: the command to run and the locked elements it left out (for the `LockedSkipped` toast, Task 3.5.2d). */
data class EditPlan(val command: Command?, val skippedLocked: List<ElementId>)

/** Turns the selection-editing keys into commands. Locked elements and elements on locked layers are skipped, never moved. */
object KeyActionPlanner {
    /** [ids] supplies fresh ids for a duplicate and fresh group ids. */
    fun plan(action: KeyAction, scene: SketchSceneV2, selection: List<ElementId>, ids: ElementIdSource): EditPlan {
        val editable = selection.filter { LayerSelectionRules.isSelectable(scene, scene[it] ?: return@filter false, LayerSelectionRules.Source.Canvas) }
        val skipped = selection.filter { it in scene && it !in editable }
        fun planned(command: Command?) = EditPlan(if (editable.isEmpty()) null else command, skipped)
        return when (action) {
            is KeyAction.Delete -> planned(DeleteElements(editable))
            is KeyAction.Nudge -> {
                val step = if (action.far) NUDGE_FAR_UNITS else NUDGE_UNITS
                planned(MoveElements(editable, action.dx * step, action.dy * step))
            }
            is KeyAction.Arrange -> planned(ReorderElements(editable, action.move))
            is KeyAction.Group -> planned(if (editable.size >= MIN_GROUP) GroupElements(editable, GroupId(ids.next().value)) else null)
            is KeyAction.Ungroup -> planned(UngroupElements(editable))
            is KeyAction.Duplicate -> planned(DuplicateElements(editable, editable.associateWith { ids.next() }, NUDGE_FAR_UNITS, NUDGE_FAR_UNITS))
            else -> EditPlan(null, emptyList())
        }
    }

    /** One arrow press moves a selection by one scene unit; Shift moves it by ten (Story 3.6.1). */
    const val NUDGE_UNITS = 1.0
    const val NUDGE_FAR_UNITS = 10.0
    private const val MIN_GROUP = 2
}
