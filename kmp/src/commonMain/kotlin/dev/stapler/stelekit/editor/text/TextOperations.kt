@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.editor.text

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import androidx.compose.runtime.*
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.editor.TextFormat
import dev.stapler.stelekit.performance.PerformanceMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Implementation of text operations for Logseq KMP editor
 */
class TextOperations(
    private val blockRepository: BlockRepository
) : ITextOperations {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val operationMutex = Mutex()
    private val _textStates = MutableStateFlow<Map<String, TextState>>(emptyMap())
    private val _selections = MutableStateFlow<Map<String, TextSelection>>(emptyMap())

    override fun getTextState(blockId: String): StateFlow<TextState> {
        return _textStates.map { it[blockId] ?: TextState() }
            .stateIn(scope, SharingStarted.Eagerly, TextState())
    }

    override fun getSelection(blockId: String): StateFlow<TextSelection?> {
        return _selections.map { it[blockId] }
            .stateIn(scope, SharingStarted.Eagerly, null)
    }

    override suspend fun insertText(blockId: String, text: String): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val traceId = PerformanceMonitor.startTrace("insert-text")
            
            val currentState = _textStates.value[blockId] ?: TextState()
            val newText = currentState.content.substring(0, currentState.cursorPosition) + 
                        text + 
                        currentState.content.substring(currentState.cursorPosition)
            
            val newState = currentState.copy(
                content = newText,
                selection = TextSelection.cursor(currentState.cursorPosition + text.length),
                lastModified = kotlin.time.Clock.System.now()
            )
            
            updateTextState(blockId, newState)
            
            // Update block content in repository
            blockRepository.updateBlockContentOnly(blockId,newText)
            PerformanceMonitor.endTrace(traceId)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun replaceText(blockId: String, range: TextRange, newText: String): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val traceId = PerformanceMonitor.startTrace("replace-text")
            
            val currentState = _textStates.value[blockId] ?: TextState()
            
            // Validate range
            if (range.start < 0 || range.end > currentState.content.length || range.start > range.end) {
                return DomainError.ValidationError.ConstraintViolation("Invalid text range: $range").left()
            }
            
            val updatedText = currentState.content.substring(0, range.start) + 
                           newText + 
                           currentState.content.substring(range.end)
            
            val newCursorPosition = range.start + newText.length
            
            val newState = currentState.copy(
                content = updatedText,
                selection = TextSelection.cursor(newCursorPosition),
                lastModified = kotlin.time.Clock.System.now()
            )
            
            updateTextState(blockId, newState)
            
            // Update block content in repository
            blockRepository.updateBlockContentOnly(blockId,updatedText)
            PerformanceMonitor.endTrace(traceId)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun deleteText(blockId: String, range: TextRange): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val traceId = PerformanceMonitor.startTrace("delete-text")
            
            val currentState = _textStates.value[blockId] ?: TextState()
            
            // Validate range
            if (range.start < 0 || range.end > currentState.content.length || range.start > range.end) {
                return DomainError.ValidationError.ConstraintViolation("Invalid delete range: $range").left()
            }
            
            val updatedText = currentState.content.substring(0, range.start) + 
                           currentState.content.substring(range.end)
            
            val newCursorPosition = range.start
            
            val newState = currentState.copy(
                content = updatedText,
                selection = TextSelection.cursor(newCursorPosition),
                lastModified = kotlin.time.Clock.System.now()
            )
            
            updateTextState(blockId, newState)
            
            // Update block content in repository
            blockRepository.updateBlockContentOnly(blockId,updatedText)
            PerformanceMonitor.endTrace(traceId)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun getText(blockId: String): Either<DomainError, String> {
        val currentState = _textStates.value[blockId]
        return (currentState?.content ?: "").right()
    }

    override suspend fun setCursor(blockId: String, position: Int): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            
            // Validate position
            if (position < 0 || position > currentState.content.length) {
                return DomainError.ValidationError.ConstraintViolation("Invalid cursor position: $position").left()
            }
            
            val newState = currentState.copy(
                selection = TextSelection.cursor(position),
                lastModified = kotlin.time.Clock.System.now()
            )
            
            updateTextState(blockId, newState)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun setSelection(blockId: String, range: TextRange): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val content = currentState.content
            
            // Validate range
            if (range.start < 0 || range.end > content.length) {
                return DomainError.ValidationError.ConstraintViolation("Invalid selection range: $range").left()
            }
            
            val selection = TextSelection(TextRange(range.start, range.end))
            val newState = currentState.copy(
                selection = selection,
                lastModified = kotlin.time.Clock.System.now()
            )
            
            updateTextState(blockId, newState)
            
            // Update selections map
            val newSelections = _selections.value.toMutableMap()
            if (selection.isCursor) {
                newSelections.remove(blockId)
            } else {
                newSelections[blockId] = selection
            }
            _selections.value = newSelections
            
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun moveCursorToWordStart(blockId: String): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val newPos = findWordStart(currentState.content, currentState.cursorPosition)
            
            val newState = currentState.copy(
                selection = TextSelection.cursor(newPos),
                lastModified = kotlin.time.Clock.System.now()
            )
            updateTextState(blockId, newState)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun moveCursorToWordEnd(blockId: String): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val newPos = findWordEnd(currentState.content, currentState.cursorPosition)
            
            val newState = currentState.copy(
                selection = TextSelection.cursor(newPos),
                lastModified = kotlin.time.Clock.System.now()
            )
            updateTextState(blockId, newState)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun moveCursorToLineStart(blockId: String): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val newPos = findLineStart(currentState.content, currentState.cursorPosition)
            
            val newState = currentState.copy(
                selection = TextSelection.cursor(newPos),
                lastModified = kotlin.time.Clock.System.now()
            )
            updateTextState(blockId, newState)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun moveCursorToLineEnd(blockId: String): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val newPos = findLineEnd(currentState.content, currentState.cursorPosition)
            
            val newState = currentState.copy(
                selection = TextSelection.cursor(newPos),
                lastModified = kotlin.time.Clock.System.now()
            )
            updateTextState(blockId, newState)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun selectWord(blockId: String): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val text = currentState.content
            val cursorPos = currentState.cursorPosition
            
            if (text.isEmpty() || cursorPos >= text.length) {
                return Unit.right()
            }
            
            // Find word boundaries
            val wordStart = findWordStart(text, cursorPos)
            val wordEnd = findWordEnd(text, cursorPos)
            
            setSelection(blockId, TextRange(wordStart, wordEnd))
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun selectLine(blockId: String): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val text = currentState.content
            val cursorPos = currentState.cursorPosition
            
            if (text.isEmpty()) {
                return Unit.right()
            }
            
            // Find line boundaries
            val lineStart = findLineStart(text, cursorPos)
            val lineEnd = findLineEnd(text, cursorPos)
            
            setSelection(blockId, TextRange(lineStart, lineEnd))
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun selectAll(blockId: String): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            setSelection(blockId, TextRange(0, currentState.content.length))
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun cut(blockId: String): Either<DomainError, String> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val selection = currentState.selection
            
            return if (selection != null && !selection.isCursor) {
                val selectedText = currentState.content.substring(selection.range.start, selection.range.end)
                deleteText(blockId, TextRange(selection.range.start, selection.range.end))
                selectedText.right()
            } else {
                "".right()
            }
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun copy(blockId: String): Either<DomainError, String> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val selection = currentState.selection
            
            return if (selection != null && !selection.isCursor) {
                val selectedText = currentState.content.substring(selection.range.start, selection.range.end)
                selectedText.right()
            } else {
                "".right()
            }
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun paste(blockId: String): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            // For now, simulate clipboard paste with placeholder text
            // In real implementation, this would use platform-specific clipboard
            val clipboardText = "[pasted text]"
            
            // Logic from insertText
            val currentState = _textStates.value[blockId] ?: TextState()
            val newText = currentState.content.substring(0, currentState.cursorPosition) + 
                        clipboardText + 
                        currentState.content.substring(currentState.cursorPosition)
            
            val newState = currentState.copy(
                content = newText,
                selection = TextSelection.cursor(currentState.cursorPosition + clipboardText.length),
                lastModified = kotlin.time.Clock.System.now()
            )
            
            updateTextState(blockId, newState)
            
            // Update block content in repository
            blockRepository.updateBlockContentOnly(blockId,newText)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun duplicate(blockId: String): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val text = currentState.content
            val cursorPos = currentState.cursorPosition
            
            // Find line range
            val start = findLineStart(text, cursorPos)
            val end = findLineEnd(text, cursorPos)
            val lineContent = text.substring(start, end)
            
            // Insert newline + line content
            val textToInsert = "\n" + lineContent
            
            // Logic from insertText (appending at end of line)
            val insertPos = end
            val newText = text.substring(0, insertPos) + 
                        textToInsert + 
                        text.substring(insertPos)
            
            val newState = currentState.copy(
                content = newText,
                selection = TextSelection.cursor(insertPos + textToInsert.length),
                lastModified = kotlin.time.Clock.System.now()
            )
            
            updateTextState(blockId, newState)
            
            // Update block content in repository
            blockRepository.updateBlockContentOnly(blockId,newText)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun applyFormat(blockId: String, range: TextRange, format: TextFormat): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val content = currentState.content
            
            // Validate range
            if (range.start < 0 || range.end > content.length || range.start > range.end) {
                return DomainError.ValidationError.ConstraintViolation("Invalid range: $range").left()
            }
            
            val selectedText = content.substring(range.start, range.end)
            var newText = selectedText
            
            // Apply formatting (simple markdown wrapping)
            if (format.bold) newText = "**$newText**"
            if (format.italic) newText = "_${newText}_"
            if (format.code) newText = "`$newText`"
            // Quote usually applies to line start, but for simplicity wrapping here or prepending
            if (format.quote) newText = "> $newText" 
            
            // Replace text
            val updatedContent = content.substring(0, range.start) + 
                               newText + 
                               content.substring(range.end)
            
            val newState = currentState.copy(
                content = updatedContent,
                selection = TextSelection.cursor(range.start + newText.length),
                lastModified = kotlin.time.Clock.System.now()
            )
            
            updateTextState(blockId, newState)
            
            // Update block content in repository
            blockRepository.updateBlockContentOnly(blockId,updatedContent)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun initializeBlock(blockId: String, content: String): Either<DomainError, Unit> {
        return try {
            val existingState = _textStates.value[blockId]
            // Only initialize if not already present or content is different
            if (existingState == null || existingState.content.isEmpty()) {
                val newState = TextState(
                    content = content,
                    selection = TextSelection.cursor(0),
                    lastModified = kotlin.time.Clock.System.now()
                )
                updateTextState(blockId, newState)
            }
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    // Helper methods

    private fun updateTextState(blockId: String, newState: TextState) {
        val newStates = _textStates.value.toMutableMap()
        newStates[blockId] = newState
        _textStates.value = newStates
    }

    private fun findWordStart(text: String, position: Int): Int {
        var start = position
        while (start > 0 && !isWordBoundary(text[start - 1])) {
            start--
        }
        return start
    }

    private fun findWordEnd(text: String, position: Int): Int {
        var end = position
        while (end < text.length && !isWordBoundary(text[end])) {
            end++
        }
        return end
    }

    private fun findLineStart(text: String, position: Int): Int {
        var start = position
        while (start > 0 && text[start - 1] != '\n') {
            start--
        }
        return start
    }

    private fun findLineEnd(text: String, position: Int): Int {
        var end = position
        while (end < text.length && text[end] != '\n') {
            end++
        }
        return end
    }

    private fun isWordBoundary(char: Char): Boolean {
        return !char.isLetterOrDigit() && char != '_' && char != '-'
    }
}
