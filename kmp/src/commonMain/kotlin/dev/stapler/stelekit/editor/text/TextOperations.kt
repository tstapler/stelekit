@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.editor.text

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
import kotlin.Result

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

    override suspend fun insertText(blockId: String, text: String): Result<Unit> = operationMutex.withLock {
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
            val block = blockRepository.getBlockByUuid(blockId).first().getOrNull()
            if (block != null) {
                val updatedBlock = block.copy(content = newText)
                blockRepository.saveBlock(updatedBlock)
            }
            
            PerformanceMonitor.endTrace(traceId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun replaceText(blockId: String, range: TextRange, newText: String): Result<Unit> = operationMutex.withLock {
        try {
            val traceId = PerformanceMonitor.startTrace("replace-text")
            
            val currentState = _textStates.value[blockId] ?: TextState()
            
            // Validate range
            if (range.start < 0 || range.end > currentState.content.length || range.start > range.end) {
                return Result.failure(IllegalArgumentException("Invalid text range: $range"))
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
            val block = blockRepository.getBlockByUuid(blockId).first().getOrNull()
            if (block != null) {
                val updatedBlock = block.copy(content = updatedText)
                blockRepository.saveBlock(updatedBlock)
            }
            
            PerformanceMonitor.endTrace(traceId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteText(blockId: String, range: TextRange): Result<Unit> = operationMutex.withLock {
        try {
            val traceId = PerformanceMonitor.startTrace("delete-text")
            
            val currentState = _textStates.value[blockId] ?: TextState()
            
            // Validate range
            if (range.start < 0 || range.end > currentState.content.length || range.start > range.end) {
                return Result.failure(IllegalArgumentException("Invalid delete range: $range"))
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
            val block = blockRepository.getBlockByUuid(blockId).first().getOrNull()
            if (block != null) {
                val updatedBlock = block.copy(content = updatedText)
                blockRepository.saveBlock(updatedBlock)
            }
            
            PerformanceMonitor.endTrace(traceId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getText(blockId: String): Result<String> {
        val currentState = _textStates.value[blockId]
        return Result.success(currentState?.content ?: "")
    }

    override suspend fun setCursor(blockId: String, position: Int): Result<Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            
            // Validate position
            if (position < 0 || position > currentState.content.length) {
                return Result.failure(IllegalArgumentException("Invalid cursor position: $position"))
            }
            
            val newState = currentState.copy(
                selection = TextSelection.cursor(position),
                lastModified = kotlin.time.Clock.System.now()
            )
            
            updateTextState(blockId, newState)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setSelection(blockId: String, range: TextRange): Result<Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val content = currentState.content
            
            // Validate range
            if (range.start < 0 || range.end > content.length) {
                return Result.failure(IllegalArgumentException("Invalid selection range: $range"))
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
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun moveCursorToWordStart(blockId: String): Result<Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val newPos = findWordStart(currentState.content, currentState.cursorPosition)
            
            val newState = currentState.copy(
                selection = TextSelection.cursor(newPos),
                lastModified = kotlin.time.Clock.System.now()
            )
            updateTextState(blockId, newState)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun moveCursorToWordEnd(blockId: String): Result<Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val newPos = findWordEnd(currentState.content, currentState.cursorPosition)
            
            val newState = currentState.copy(
                selection = TextSelection.cursor(newPos),
                lastModified = kotlin.time.Clock.System.now()
            )
            updateTextState(blockId, newState)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun moveCursorToLineStart(blockId: String): Result<Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val newPos = findLineStart(currentState.content, currentState.cursorPosition)
            
            val newState = currentState.copy(
                selection = TextSelection.cursor(newPos),
                lastModified = kotlin.time.Clock.System.now()
            )
            updateTextState(blockId, newState)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun moveCursorToLineEnd(blockId: String): Result<Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val newPos = findLineEnd(currentState.content, currentState.cursorPosition)
            
            val newState = currentState.copy(
                selection = TextSelection.cursor(newPos),
                lastModified = kotlin.time.Clock.System.now()
            )
            updateTextState(blockId, newState)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun selectWord(blockId: String): Result<Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val text = currentState.content
            val cursorPos = currentState.cursorPosition
            
            if (text.isEmpty() || cursorPos >= text.length) {
                return Result.success(Unit)
            }
            
            // Find word boundaries
            val wordStart = findWordStart(text, cursorPos)
            val wordEnd = findWordEnd(text, cursorPos)
            
            setSelection(blockId, TextRange(wordStart, wordEnd))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun selectLine(blockId: String): Result<Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val text = currentState.content
            val cursorPos = currentState.cursorPosition
            
            if (text.isEmpty()) {
                return Result.success(Unit)
            }
            
            // Find line boundaries
            val lineStart = findLineStart(text, cursorPos)
            val lineEnd = findLineEnd(text, cursorPos)
            
            setSelection(blockId, TextRange(lineStart, lineEnd))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun selectAll(blockId: String): Result<Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            setSelection(blockId, TextRange(0, currentState.content.length))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun cut(blockId: String): Result<String> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val selection = currentState.selection
            
            return if (selection != null && !selection.isCursor) {
                val selectedText = currentState.content.substring(selection.range.start, selection.range.end)
                deleteText(blockId, TextRange(selection.range.start, selection.range.end))
                Result.success(selectedText)
            } else {
                Result.success("")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun copy(blockId: String): Result<String> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val selection = currentState.selection
            
            return if (selection != null && !selection.isCursor) {
                val selectedText = currentState.content.substring(selection.range.start, selection.range.end)
                Result.success(selectedText)
            } else {
                Result.success("")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun paste(blockId: String): Result<Unit> = operationMutex.withLock {
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
            val block = blockRepository.getBlockByUuid(blockId).first().getOrNull()
            if (block != null) {
                val updatedBlock = block.copy(content = newText)
                blockRepository.saveBlock(updatedBlock)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun duplicate(blockId: String): Result<Unit> = operationMutex.withLock {
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
            val block = blockRepository.getBlockByUuid(blockId).first().getOrNull()
            if (block != null) {
                val updatedBlock = block.copy(content = newText)
                blockRepository.saveBlock(updatedBlock)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun applyFormat(blockId: String, range: TextRange, format: TextFormat): Result<Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val content = currentState.content
            
            // Validate range
            if (range.start < 0 || range.end > content.length || range.start > range.end) {
                return Result.failure(IllegalArgumentException("Invalid range: $range"))
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
            val block = blockRepository.getBlockByUuid(blockId).first().getOrNull()
            if (block != null) {
                val updatedBlock = block.copy(content = updatedContent)
                blockRepository.saveBlock(updatedBlock)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun initializeBlock(blockId: String, content: String): Result<Unit> {
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
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
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
