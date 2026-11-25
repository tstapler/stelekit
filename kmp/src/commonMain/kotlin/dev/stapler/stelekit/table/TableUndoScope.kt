package dev.stapler.stelekit.table

/**
 * Table-scoped, named undo bookkeeping (UX-85, ADR-004). Counts only commits that changed content;
 * the actual undo is performed by the host. Not thread-safe: owned by one committer or strip.
 */
class TableUndoScope {
    private val undoLabels = ArrayDeque<String>()
    private val redoLabels = ArrayDeque<String>()

    val undoDepth: Int get() = undoLabels.size
    val redoDepth: Int get() = redoLabels.size
    val canUndo: Boolean get() = undoLabels.isNotEmpty()
    val canRedo: Boolean get() = redoLabels.isNotEmpty()
    val nextUndoLabel: String? get() = undoLabels.lastOrNull()
    val nextRedoLabel: String? get() = redoLabels.lastOrNull()

    fun record(label: String, changed: Boolean) {
        if (!changed) return
        undoLabels.addLast(label)
        if (undoLabels.size > CAP) undoLabels.removeFirst()
        redoLabels.clear()
    }

    /** Moves the newest step to the redo side; returns its label, or null when there is nothing to undo. */
    fun undo(): String? {
        val label = undoLabels.removeLastOrNull() ?: return null
        redoLabels.addLast(label)
        return label
    }

    fun redo(): String? {
        val label = redoLabels.removeLastOrNull() ?: return null
        undoLabels.addLast(label)
        return label
    }

    /** Strip hides or the sheet opens: a new scope starts. */
    fun reset() {
        undoLabels.clear()
        redoLabels.clear()
    }

    /** Another writer's state entered the table (inline): earlier steps no longer apply. */
    fun foreignEntry() = reset()

    companion object {
        const val CAP = 100
    }
}
