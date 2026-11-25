package dev.stapler.stelekit.table

data class TextSelection(val start: Int, val end: Int = start)

/**
 * In-progress text of one cell, hoisted so it survives scrolling and recycling. Immutable: every
 * operation returns a new draft. Field-level undo (UX-86) lives here and never touches
 * [committedContent], the cell text when the edit began.
 */
class CellDraft private constructor(
    val address: CellAddress,
    val committedContent: String,
    val text: String,
    val selection: TextSelection,
    val composing: Boolean,
    private val settledText: String,
    private val settledSelection: TextSelection,
    private val lastEditAtMs: Long?,
    private val lastEditEndedWord: Boolean,
    private val undoStack: List<Snapshot>,
    private val redoStack: List<Snapshot>,
) {
    private data class Snapshot(val text: String, val selection: TextSelection)

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size
    val isDirty: Boolean get() = text != committedContent

    /**
     * Applies typing. One undo step per word or per pause of [PAUSE_MS]; while [composing] the text
     * is kept but no step is recorded, and the composed result becomes one step when composition ends.
     */
    fun edit(newText: String, newSelection: TextSelection, composing: Boolean, atMs: Long): CellDraft {
        if (composing) {
            return copy(text = newText, selection = newSelection, composing = true)
        }
        if (newText == settledText) {
            return copy(text = newText, selection = newSelection, composing = false, settledSelection = newSelection)
        }
        val newStep = lastEditAtMs == null || atMs - lastEditAtMs >= PAUSE_MS || lastEditEndedWord
        val undo = if (newStep) (undoStack + Snapshot(settledText, settledSelection)).takeLast(HISTORY_CAP) else undoStack
        return copy(
            text = newText, selection = newSelection, composing = false,
            settledText = newText, settledSelection = newSelection,
            lastEditAtMs = atMs, lastEditEndedWord = newText.lastOrNull()?.isWhitespace() == true,
            undoStack = undo, redoStack = emptyList(),
        )
    }

    fun undo(): CellDraft {
        val prev = undoStack.lastOrNull() ?: return this
        return copy(
            text = prev.text, selection = prev.selection, composing = false,
            settledText = prev.text, settledSelection = prev.selection,
            lastEditAtMs = null, lastEditEndedWord = false,
            undoStack = undoStack.dropLast(1),
            redoStack = (redoStack + Snapshot(settledText, settledSelection)).takeLast(HISTORY_CAP),
        )
    }

    fun redo(): CellDraft {
        val next = redoStack.lastOrNull() ?: return this
        return copy(
            text = next.text, selection = next.selection, composing = false,
            settledText = next.text, settledSelection = next.selection,
            lastEditAtMs = null, lastEditEndedWord = false,
            undoStack = (undoStack + Snapshot(settledText, settledSelection)).takeLast(HISTORY_CAP),
            redoStack = redoStack.dropLast(1),
        )
    }

    /** After a commit or cancel: history is gone, the text stays. */
    fun clearHistory(): CellDraft = copy(undoStack = emptyList(), redoStack = emptyList(), lastEditAtMs = null)

    /** The text just committed: the draft stays open but is no longer dirty. */
    fun markCommitted(): CellDraft = copy(committedContent = text, undoStack = emptyList(), redoStack = emptyList(), lastEditAtMs = null)

    private fun copy(
        committedContent: String = this.committedContent,
        text: String = this.text,
        selection: TextSelection = this.selection,
        composing: Boolean = this.composing,
        settledText: String = this.settledText,
        settledSelection: TextSelection = this.settledSelection,
        lastEditAtMs: Long? = this.lastEditAtMs,
        lastEditEndedWord: Boolean = this.lastEditEndedWord,
        undoStack: List<Snapshot> = this.undoStack,
        redoStack: List<Snapshot> = this.redoStack,
    ) = CellDraft(
        address, committedContent, text, selection, composing, settledText, settledSelection,
        lastEditAtMs, lastEditEndedWord, undoStack, redoStack,
    )

    companion object {
        const val HISTORY_CAP = 100
        const val PAUSE_MS = 1_000L

        fun start(address: CellAddress, committedContent: String, initialText: String = committedContent): CellDraft {
            val sel = TextSelection(initialText.length)
            return CellDraft(
                address, committedContent, initialText, sel, false, initialText, sel,
                null, false, emptyList(), emptyList(),
            )
        }
    }
}
