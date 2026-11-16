// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.tools

import dev.stapler.stelekit.sketch.v2.scene.ElementId
import dev.stapler.stelekit.sketch.v2.scene.FrameElement

// What the selection, eraser and frame tools ask the document to do. The bridge (ToolCommandBridge.kt) maps each to
// one undoable command, so one gesture is one history step.

/** One element's part of a resize or rotate gesture, in absolute scene values (Story 3.2.2b). */
sealed interface ElementTransform {
    val id: ElementId

    /** Sets the unrotated box; [angle], when not null, is also set (radians about the new centre). */
    data class Fit(
        override val id: ElementId,
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
        val angle: Double? = null,
    ) : ElementTransform

    /** Translates an element that cannot be resized or rotated (a point or an arc) with the rest of the selection. */
    data class Shift(override val id: ElementId, val dx: Double, val dy: Double) : ElementTransform
}

/**
 * Translates [ids]; [frameChanges] maps an element to the frame it now belongs to (null: it left its frame), decided
 * when the drag ended so dropping an element into or out of a frame is part of the same undo step.
 */
data class MoveSelectionCommand(
    val ids: List<ElementId>,
    val dx: Double,
    val dy: Double,
    val frameChanges: Map<ElementId, ElementId?> = emptyMap(),
) : ToolCommand

/** Resize or rotate of one or more elements, one undo step. */
data class TransformSelectionCommand(val steps: List<ElementTransform>) : ToolCommand

data class FlipSelectionCommand(val ids: List<ElementId>, val horizontal: Boolean) : ToolCommand

data class DuplicateSelectionCommand(
    val ids: List<ElementId>,
    val idMap: Map<ElementId, ElementId>,
    val dx: Double = 0.0,
    val dy: Double = 0.0,
) : ToolCommand

/** Deletes [ids] and whatever the delete command takes with them (bound labels), one undo step. */
data class DeleteSelectionCommand(val ids: List<ElementId>) : ToolCommand

/** The eraser removed [ids] with one stroke (or one tap); one undo step for the whole stroke. */
data class EraseCommand(val ids: List<ElementId>) : ToolCommand

/** A new [frame] adopting [memberIds], the elements fully inside it, in one step. */
data class AddFrameCommand(val frame: FrameElement, val memberIds: List<ElementId>) : ToolCommand
