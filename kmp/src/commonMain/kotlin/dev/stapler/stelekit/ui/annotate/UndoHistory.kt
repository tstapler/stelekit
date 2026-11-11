// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.annotate

/** Bounded undo/redo stacks of whole states. Not thread-safe; callers confine it to one thread. */
class UndoHistory<S>(private val maxHistory: Int = 50) {
    private val undoStack = ArrayDeque<S>(maxHistory)
    private val redoStack = ArrayDeque<S>(maxHistory)

    val undoDepth: Int get() = undoStack.size
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Records [before] as an undo point and invalidates redo. */
    fun push(before: S) {
        if (undoStack.size >= maxHistory) undoStack.removeFirst()
        undoStack.addLast(before)
        redoStack.clear()
    }

    /** Pops the previous state, parking [current] for redo; null when nothing to undo. */
    fun undo(current: S): S? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        return prev
    }

    /** Pops the redo state, parking [current] for undo; null when nothing to redo. */
    fun redo(current: S): S? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        return next
    }
}

/**
 * State to show after undo/redo lands on [this] snapshot. Snapshots are taken at commit time and
 * so carry the in-progress points that were being drawn; restoring them resurrects phantom points
 * (the next tap would then commit a 3-point distance). Only the committed set and calibration come
 * from the snapshot; the live tool stays and in-progress points are cleared.
 */
fun AnnotationEditorState.restoredOver(current: AnnotationEditorState): AnnotationEditorState =
    copy(
        currentTool = current.currentTool,
        inProgressPoints = emptyList(),
        error = current.error,
    )
