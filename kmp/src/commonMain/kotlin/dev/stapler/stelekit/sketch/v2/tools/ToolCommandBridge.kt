// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.sketch.v2.binding.BindArrowEnd
import dev.stapler.stelekit.sketch.v2.commands.AddElement
import dev.stapler.stelekit.sketch.v2.commands.AssignToFrame
import dev.stapler.stelekit.sketch.v2.commands.Command
import dev.stapler.stelekit.sketch.v2.commands.CommandError
import dev.stapler.stelekit.sketch.v2.commands.DeleteElements
import dev.stapler.stelekit.sketch.v2.commands.DuplicateElements
import dev.stapler.stelekit.sketch.v2.commands.FlipElements
import dev.stapler.stelekit.sketch.v2.commands.MoveElements
import dev.stapler.stelekit.sketch.v2.commands.RemoveFromFrame
import dev.stapler.stelekit.sketch.v2.commands.ResizeElement
import dev.stapler.stelekit.sketch.v2.commands.RotateElement
import dev.stapler.stelekit.sketch.v2.commands.SequenceCommand
import dev.stapler.stelekit.sketch.v2.commands.SetLinePoints
import dev.stapler.stelekit.sketch.v2.commands.SetTextBox
import dev.stapler.stelekit.sketch.v2.commands.SetUnderlayPlacement

/**
 * Maps what a finished gesture asked for to the undoable [Command] that does it (Story 1.3.2). A tool command with no
 * mapping is refused with [CommandError.Unsupported] rather than dropped, so a tool added without its mapping fails loudly.
 */
fun ToolCommand.toCommand(): Either<CommandError, Command> = when (this) {
    is AddElementCommand -> AddElement(element).right()
    is MoveSelectionCommand -> moveCommand().right()
    is TransformSelectionCommand -> SequenceCommand(steps.flatMap { it.toCommands() }).right()
    is FlipSelectionCommand -> FlipElements(ids, horizontal).right()
    is DuplicateSelectionCommand -> DuplicateElements(ids, idMap, dx, dy).right()
    is DeleteSelectionCommand -> DeleteElements(ids).right()
    is EraseCommand -> DeleteElements(ids).right()
    is AddFrameCommand -> SequenceCommand(listOfNotNull(AddElement(frame), memberIds.takeIf { it.isNotEmpty() }?.let { AssignToFrame(frame.id, it) })).right()
    is SetLinePointsCommand -> SetLinePoints(id, points).right()
    is BindArrowEndCommand -> BindArrowEnd(arrow, end, target).right()
    is AdjustUnderlayCommand -> SetUnderlayPlacement(placement).right()
    else -> CommandError.Unsupported(this::class.simpleName.orEmpty(), "toCommand").left()
}

private fun MoveSelectionCommand.moveCommand(): Command {
    val joins = frameChanges.entries.filter { it.value != null }.groupBy({ it.value!! }, { it.key })
    val leaves = frameChanges.entries.filter { it.value == null }.map { it.key }
    val steps = buildList<Command> {
        add(MoveElements(ids, dx, dy))
        if (leaves.isNotEmpty()) add(RemoveFromFrame(leaves))
        joins.forEach { (frame, members) -> add(AssignToFrame(frame, members)) }
    }
    return if (steps.size == 1) steps.single() else SequenceCommand(steps)
}

private fun ElementTransform.toCommands(): List<Command> = when (this) {
    is ElementTransform.Fit -> listOfNotNull(ResizeElement(id, x, y, width, height), angle?.let { RotateElement(id, it) })
    is ElementTransform.TextBox -> listOf(SetTextBox(id, x, y, width, height, fontSize, text, autoResize))
    is ElementTransform.Shift -> listOf(MoveElements(listOf(id), dx, dy))
}
