package dev.stapler.stelekit.editor.text

import dev.stapler.stelekit.model.Validation
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Represents a range of text with start and end positions.
 * Both positions are inclusive and zero-based.
 */
@Serializable
data class TextRange(
    val start: Int,
    val end: Int
) {
    init {
        require(start >= 0) { "Start position must be non-negative" }
        require(end >= start) { "End position must be greater than or equal to start" }
    }

    /**
     * The length of the range (number of characters it spans).
     */
    val length: Int get() = end - start + 1

    /**
     * Whether this range represents a single cursor position (length = 0).
     */
    val isCollapsed: Boolean get() = start == end

    /**
     * Whether this range is empty (start = end = 0).
     */
    val isEmpty: Boolean get() = start == 0 && end == 0

    /**
     * Returns a new range with the same start but adjusted end.
     */
    fun withEnd(newEnd: Int): TextRange {
        require(newEnd >= start) { "New end must be greater than or equal to start" }
        return copy(end = newEnd)
    }

    /**
     * Returns a new range with the same end but adjusted start.
     */
    fun withStart(newStart: Int): TextRange {
        require(newStart >= 0) { "New start must be non-negative" }
        require(end >= newStart) { "End must be greater than or equal to new start" }
        return copy(start = newStart)
    }

    /**
     * Returns a new range that covers both this range and the other.
     */
    fun union(other: TextRange): TextRange {
        return TextRange(
            start = minOf(this.start, other.start),
            end = maxOf(this.end, other.end)
        )
    }

    /**
     * Returns the intersection of this range and the other, or null if they don't overlap.
     */
    fun intersection(other: TextRange): TextRange? {
        val newStart = maxOf(this.start, other.start)
        val newEnd = minOf(this.end, other.end)
        return if (newStart <= newEnd) TextRange(newStart, newEnd) else null
    }

    /**
     * Checks if this range contains the specified position.
     */
    fun contains(position: Int): Boolean {
        return position in start..end
    }

    /**
     * Checks if this range contains the entire other range.
     */
    fun contains(other: TextRange): Boolean {
        return contains(other.start) && contains(other.end)
    }

    companion object {
        /**
         * Creates a collapsed range at the specified position.
         */
        fun collapsed(at: Int): TextRange = TextRange(at, at)

        /**
         * Creates a range from 0 to the specified position.
         */
        fun fromStart(to: Int): TextRange = TextRange(0, to)

        /**
         * Creates an empty range at position 0.
         */
        val Empty = TextRange(0, 0)

        /**
         * Creates a range covering the entire text.
         */
        fun all(textLength: Int): TextRange {
            require(textLength >= 0) { "Text length must be non-negative" }
            return TextRange(0, maxOf(0, textLength - 1))
        }
    }
}

/**
 * Represents the current selection state in the editor.
 * Can be a cursor (collapsed) or a selection range (expanded).
 */
@Serializable
data class TextSelection(
    val range: TextRange,
    val direction: SelectionDirection = SelectionDirection.FORWARD
) {
    /**
     * Whether this is a cursor (collapsed selection).
     */
    val isCursor: Boolean get() = range.isCollapsed

    /**
     * Whether this is an expanded selection.
     */
    val isSelection: Boolean get() = !range.isCollapsed

    /**
     * The current cursor position (for both collapsed and expanded selections).
     */
    val cursorPosition: Int get() = if (direction == SelectionDirection.FORWARD) range.end else range.start

    /**
     * The anchor point (where the selection started).
     */
    val anchor: Int get() = if (direction == SelectionDirection.FORWARD) range.start else range.end

    /**
     * Returns a new selection with the specified range.
     */
    fun withRange(newRange: TextRange): TextSelection {
        return copy(range = newRange)
    }

    /**
     * Returns a new selection with the specified direction.
     */
    fun withDirection(newDirection: SelectionDirection): TextSelection {
        return copy(direction = newDirection)
    }

    /**
     * Returns a cursor selection at the specified position.
     */
    fun asCursor(at: Int): TextSelection {
        return TextSelection(TextRange.collapsed(at))
    }

    /**
     * Returns a selection covering the specified range.
     */
    fun asSelection(start: Int, end: Int, direction: SelectionDirection = SelectionDirection.FORWARD): TextSelection {
        return TextSelection(TextRange(start, end), direction)
    }

    companion object {
        /**
         * Creates a cursor at the specified position.
         */
        fun cursor(at: Int): TextSelection = TextSelection(TextRange.collapsed(at))

        /**
         * Creates a selection covering the specified range.
         */
        fun selection(start: Int, end: Int, direction: SelectionDirection = SelectionDirection.FORWARD): TextSelection {
            return TextSelection(TextRange(start, end), direction)
        }

        /**
         * Creates an empty selection at position 0.
         */
        val Empty = TextSelection(TextRange.Empty)
    }
}

/**
 * Direction of text selection.
 */
enum class SelectionDirection {
    /** Selection was made from start to end (forward direction) */
    FORWARD,
    
    /** Selection was made from end to start (backward direction) */
    BACKWARD
}

/**
 * Represents the current state of text content and metadata.
 */
@Serializable
data class TextState(
    val content: String = "",
    val selection: TextSelection = TextSelection.Empty,
    val version: Int = 1,
    val lastModified: Instant = kotlin.time.Clock.System.now(),
    val isDirty: Boolean = false,
    val undoStack: List<TextOperation> = emptyList(),
    val redoStack: List<TextOperation> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        Validation.validateContent(content)
        require(version > 0) { "Version must be positive" }
        require(lastModified != kotlin.time.Instant.DISTANT_PAST) { "Invalid last modified timestamp" }
        metadata.forEach { (key, value) ->
            Validation.validateName(key)
            Validation.validateContent(value)
        }
    }

    /**
     * The current cursor position.
     */
    val cursorPosition: Int get() = selection.cursorPosition

    /**
     * Whether there is a selection (non-collapsed).
     */
    val hasSelection: Boolean get() = selection.isSelection

    /**
     * The currently selected text, or empty string if no selection.
     */
    val selectedText: String get() = if (hasSelection) {
        content.substring(selection.range.start, selection.range.end + 1)
    } else ""

    /**
     * Whether this state can be undone.
     */
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    /**
     * Whether this state can be redone.
     */
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * Returns a new TextState with the specified content.
     */
    fun withContent(newContent: String): TextState {
        return copy(
            content = newContent,
            version = version + 1,
            lastModified = kotlin.time.Clock.System.now(),
            isDirty = true
        )
    }

    /**
     * Returns a new TextState with the specified selection.
     */
    fun withSelection(newSelection: TextSelection): TextState {
        return copy(selection = newSelection)
    }

    /**
     * Returns a new TextState with the specified cursor position.
     */
    fun withCursor(position: Int): TextState {
        return withSelection(TextSelection.cursor(position.coerceIn(0, content.length)))
    }

    /**
     * Returns a new TextState with the specified undo stack.
     */
    fun withUndoStack(newUndoStack: List<TextOperation>): TextState {
        return copy(undoStack = newUndoStack, redoStack = emptyList())
    }

    /**
     * Returns a new TextState with the specified redo stack.
     */
    fun withRedoStack(newRedoStack: List<TextOperation>): TextState {
        return copy(redoStack = newRedoStack)
    }

    /**
     * Returns a new TextState with the dirty flag cleared.
     */
    fun asClean(): TextState {
        return copy(isDirty = false)
    }

    /**
     * Returns a new TextState with updated metadata.
     */
    fun withMetadata(key: String, value: String): TextState {
        val newMetadata = metadata.toMutableMap().apply { put(key, value) }
        return copy(metadata = newMetadata)
    }

    /**
     * Returns a new TextState with metadata removed.
     */
    fun withoutMetadata(key: String): TextState {
        val newMetadata = metadata.toMutableMap().apply { remove(key) }
        return copy(metadata = newMetadata)
    }

    companion object {
        /**
         * Creates an empty TextState.
         */
        val Empty = TextState()

        /**
         * Creates a TextState with the specified content.
         */
        fun of(content: String): TextState = Empty.withContent(content)
    }
}

/**
 * Represents a text operation that can be applied to TextState.
 * Used for undo/redo functionality.
 */
@Serializable
sealed class TextOperation {
    abstract val timestamp: Instant
    abstract val description: String
    abstract fun apply(textState: TextState): TextState
    abstract fun invert(textState: TextState): TextOperation

    /**
     * Insert text operation.
     */
    data class Insert(
        val position: Int,
        val text: String,
        override val timestamp: Instant = kotlin.time.Clock.System.now(),
        override val description: String = "Insert '${text.take(20)}${if (text.length > 20) "..." else ""}' at $position"
    ) : TextOperation() {
        init {
            require(position >= 0) { "Position must be non-negative" }
            Validation.validateContent(text)
        }

        override fun apply(textState: TextState): TextState {
            val newContent = textState.content.substring(0, position) + 
                           text + 
                           textState.content.substring(position)
            return textState.withContent(newContent)
        }

        override fun invert(textState: TextState): TextOperation {
            return Delete(position, text, timestamp, "Inverse of: $description")
        }
    }

    /**
     * Delete text operation.
     */
    data class Delete(
        val position: Int,
        val text: String,
        override val timestamp: Instant = kotlin.time.Clock.System.now(),
        override val description: String = "Delete '${text.take(20)}${if (text.length > 20) "..." else ""}' at $position"
    ) : TextOperation() {
        init {
            require(position >= 0) { "Position must be non-negative" }
            Validation.validateContent(text)
        }

        override fun apply(textState: TextState): TextState {
            val newContent = textState.content.substring(0, position) + 
                           textState.content.substring(position + text.length)
            return textState.withContent(newContent)
        }

        override fun invert(textState: TextState): TextOperation {
            return Insert(position, text, timestamp, "Inverse of: $description")
        }
    }

    /**
     * Replace text operation.
     */
    data class Replace(
        val position: Int,
        val oldText: String,
        val newText: String,
        override val timestamp: Instant = kotlin.time.Clock.System.now(),
        override val description: String = "Replace '${oldText.take(20)}${if (oldText.length > 20) "..." else ""}' with '${newText.take(20)}${if (newText.length > 20) "..." else ""}' at $position"
    ) : TextOperation() {
        init {
            require(position >= 0) { "Position must be non-negative" }
            Validation.validateContent(oldText)
            Validation.validateContent(newText)
        }

        override fun apply(textState: TextState): TextState {
            val newContent = textState.content.substring(0, position) + 
                           newText + 
                           textState.content.substring(position + oldText.length)
            return textState.withContent(newContent)
        }

        override fun invert(textState: TextState): TextOperation {
            return Replace(position, newText, oldText, timestamp, "Inverse of: $description")
        }
    }
}