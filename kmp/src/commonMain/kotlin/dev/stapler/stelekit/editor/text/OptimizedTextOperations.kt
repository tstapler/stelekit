@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.editor.text

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import androidx.compose.runtime.*
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.performance.PerformanceMonitor
import dev.stapler.stelekit.editor.TextFormat
import kotlin.time.Duration.Companion.minutes

/**
 * Optimized text operations for Logseq KMP editor with performance improvements
 */
class OptimizedTextOperations(
    private val blockRepository: BlockRepository
) : ITextOperations {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val operationMutex = Mutex()
    private val _textStates = MutableStateFlow<Map<String, TextState>>(emptyMap())
    
    // Performance optimization: bounded state map
    private val maxStates = 1000
    private var lastCleanup = kotlin.time.Clock.System.now()

    override fun getTextState(blockId: String): StateFlow<TextState> {
        return _textStates.map { it[blockId] ?: TextState() }
            .stateIn(scope, SharingStarted.Eagerly, TextState())
    }

    override fun getSelection(blockId: String): StateFlow<TextSelection?> {
        return _textStates.map { it[blockId]?.selection }
            .stateIn(scope, SharingStarted.Eagerly, null)
    }

    override suspend fun insertText(blockId: String, text: String): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val traceId = PerformanceMonitor.startTrace("insert-text-optimized")
            
            // Security validation
            if (text.length > MAX_TEXT_LENGTH) {
                return DomainError.ValidationError.ConstraintViolation("Text too long: ${text.length} > $MAX_TEXT_LENGTH").left()
            }
            if (!isValidText(text)) {
                return DomainError.ValidationError.ConstraintViolation("Invalid characters in text").left()
            }
            
            val currentState = _textStates.value[blockId] ?: TextState()
            
            // Performance optimization: use StringBuilder
            val newText = buildString {
                append(currentState.content.substring(0, currentState.cursorPosition))
                append(text)
                append(currentState.content.substring(currentState.cursorPosition))
            }
            
            val newCursorPosition = currentState.cursorPosition + text.length
            
            val newState = currentState.copy(
                content = newText,
                selection = TextSelection.cursor(newCursorPosition),
                lastModified = kotlin.time.Clock.System.now()
            )
            
            updateTextState(blockId, newState)
            
            // Update block content in repository (batch operation)
            updateBlockContentOptimized(blockId, newText)
            
            PerformanceMonitor.endTrace(traceId)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun replaceText(blockId: String, range: TextRange, newText: String): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val content = currentState.content
            
            if (!isValidRange(range, content.length)) {
                return DomainError.ValidationError.ConstraintViolation("Invalid range: $range").left()
            }
            if (!isValidText(newText)) {
                return DomainError.ValidationError.ConstraintViolation("Invalid characters in text").left()
            }
            
            val prefix = content.substring(0, range.start)
            val suffixStart = range.end + 1
            val suffix = if (suffixStart < content.length) content.substring(suffixStart) else ""
            
            val newContent = prefix + newText + suffix
            
            if (newContent.length > MAX_CONTENT_LENGTH) {
                 return DomainError.ValidationError.ConstraintViolation("Content exceeds maximum length").left()
            }
            
            val newCursorPos = range.start + newText.length
            
            val newState = currentState.copy(
                content = newContent,
                selection = TextSelection.cursor(newCursorPos),
                lastModified = kotlin.time.Clock.System.now()
            )
            
            updateTextState(blockId, newState)
            updateBlockContentOptimized(blockId, newContent)
            
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun deleteText(blockId: String, range: TextRange): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val content = currentState.content
            
            if (!isValidRange(range, content.length)) {
                return DomainError.ValidationError.ConstraintViolation("Invalid range: $range").left()
            }
            
            val prefix = content.substring(0, range.start)
            val suffixStart = range.end + 1
            val suffix = if (suffixStart < content.length) content.substring(suffixStart) else ""
            
            val newContent = prefix + suffix
            
            val newState = currentState.copy(
                content = newContent,
                selection = TextSelection.cursor(range.start),
                lastModified = kotlin.time.Clock.System.now()
            )
            
            updateTextState(blockId, newState)
            updateBlockContentOptimized(blockId, newContent)
            
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun getText(blockId: String): Either<DomainError, String> {
        val state = _textStates.value[blockId] ?: TextState()
        return state.content.right()
    }

    override suspend fun setCursor(blockId: String, position: Int): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            if (position < 0 || position > currentState.content.length) {
                return DomainError.ValidationError.ConstraintViolation("Invalid cursor position").left()
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
            if (!isValidRange(range, currentState.content.length)) {
                return DomainError.ValidationError.ConstraintViolation("Invalid range").left()
            }
            
            val newState = currentState.copy(
                selection = TextSelection(range),
                lastModified = kotlin.time.Clock.System.now()
            )
            updateTextState(blockId, newState)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun moveCursorToWordStart(blockId: String): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val newPos = findWordStartOptimized(currentState.content, currentState.cursorPosition)
            
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
            val newPos = findWordEndOptimized(currentState.content, currentState.cursorPosition)
            
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
            val newPos = findLineStartOptimized(currentState.content, currentState.cursorPosition)
            
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
            val newPos = findLineEndOptimized(currentState.content, currentState.cursorPosition)
            
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
            val start = findWordStartOptimized(currentState.content, currentState.cursorPosition)
            val end = findWordEndOptimized(currentState.content, currentState.cursorPosition)
            val rangeEnd = maxOf(start, end - 1)
            
            val newState = currentState.copy(
                selection = TextSelection(TextRange(start, rangeEnd)),
                lastModified = kotlin.time.Clock.System.now()
            )
            updateTextState(blockId, newState)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun selectLine(blockId: String): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val start = findLineStartOptimized(currentState.content, currentState.cursorPosition)
            val end = findLineEndOptimized(currentState.content, currentState.cursorPosition)
            val rangeEnd = maxOf(start, end - 1)
            
            val newState = currentState.copy(
                selection = TextSelection(TextRange(start, rangeEnd)),
                lastModified = kotlin.time.Clock.System.now()
            )
            updateTextState(blockId, newState)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun selectAll(blockId: String): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            val length = currentState.content.length
            val rangeEnd = maxOf(0, length - 1)
            
            val newState = currentState.copy(
                selection = TextSelection(TextRange(0, rangeEnd)),
                lastModified = kotlin.time.Clock.System.now()
            )
            updateTextState(blockId, newState)
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun cut(blockId: String): Either<DomainError, String> = operationMutex.withLock {
        try {
            val currentState = _textStates.value[blockId] ?: TextState()
            if (!currentState.hasSelection) return "".right()
            
            val text = currentState.selectedText
            val range = currentState.selection.range
            val content = currentState.content
            
            val prefix = content.substring(0, range.start)
            val suffixStart = range.end + 1
            val suffix = if (suffixStart < content.length) content.substring(suffixStart) else ""
            val newContent = prefix + suffix
            
            val newState = currentState.copy(
                content = newContent,
                selection = TextSelection.cursor(range.start),
                lastModified = kotlin.time.Clock.System.now()
            )
            updateTextState(blockId, newState)
            updateBlockContentOptimized(blockId, newContent)
            
            text.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun copy(blockId: String): Either<DomainError, String> {
        val currentState = _textStates.value[blockId] ?: TextState()
        return currentState.selectedText.right()
    }

    override suspend fun paste(blockId: String): Either<DomainError, Unit> = operationMutex.withLock {
        try {
            val text = clipboardText
            val currentState = _textStates.value[blockId] ?: TextState()
            val content = currentState.content
            
            val start = if (currentState.hasSelection) currentState.selection.range.start else currentState.cursorPosition
            val end = if (currentState.hasSelection) currentState.selection.range.end else currentState.cursorPosition - 1
            
            val prefix = content.substring(0, start)
            val suffixStart = end + 1
            val suffix = if (suffixStart < content.length) content.substring(suffixStart) else ""
            
            val newContent = prefix + text + suffix
            val newCursorPos = start + text.length
            
            val newState = currentState.copy(
                content = newContent,
                selection = TextSelection.cursor(newCursorPos),
                lastModified = kotlin.time.Clock.System.now()
            )
            updateTextState(blockId, newState)
            updateBlockContentOptimized(blockId, newContent)
            
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
            val start = findLineStartOptimized(text, cursorPos)
            val end = findLineEndOptimized(text, cursorPos)
            val lineContent = text.substring(start, end)
            
            // Insert newline + line content
            val textToInsert = "\n" + lineContent
            
            // Logic from insertText (appending at end of line)
            val insertPos = end
            val newText = buildString {
                append(text.substring(0, insertPos))
                append(textToInsert)
                append(text.substring(insertPos))
            }
            
            val newState = currentState.copy(
                content = newText,
                selection = TextSelection.cursor(insertPos + textToInsert.length),
                lastModified = kotlin.time.Clock.System.now()
            )
            
            updateTextState(blockId, newState)
            updateBlockContentOptimized(blockId, newText)
            
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
            if (!isValidRange(range, content.length)) {
                return DomainError.ValidationError.ConstraintViolation("Invalid range: $range").left()
            }
            
            // Assuming range.end is inclusive, so substring needs +1
            val suffixStart = range.end + 1
            val selectedText = content.substring(range.start, suffixStart)
            var newText = selectedText
            
            // Apply formatting (simple markdown wrapping)
            if (format.bold) newText = "**$newText**"
            if (format.italic) newText = "_${newText}_"
            if (format.code) newText = "`$newText`"
            if (format.quote) newText = "> $newText"
            
            // Replace text
            val updatedContent = buildString {
                append(content.substring(0, range.start))
                append(newText)
                if (suffixStart < content.length) {
                    append(content.substring(suffixStart))
                }
            }
            
            val newState = currentState.copy(
                content = updatedContent,
                selection = TextSelection.cursor(range.start + newText.length),
                lastModified = kotlin.time.Clock.System.now()
            )
            
            updateTextState(blockId, newState)
            updateBlockContentOptimized(blockId, updatedContent)
            
            Unit.right()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    // Optimized helper methods

    private fun updateTextState(blockId: String, newState: TextState) {
        val currentStates = _textStates.value
        val newStates = if (currentStates.size >= maxStates) {
            // Performance: cleanup old states
            cleanupOldStates()
            currentStates + (blockId to newState)
        } else {
            currentStates + (blockId to newState)
        }
        _textStates.value = newStates
    }

    private fun cleanupOldStates() {
        val now = kotlin.time.Clock.System.now()
        if (now > lastCleanup + CLEANUP_INTERVAL) {
            val cutoff = now.minus(STALE_STATE_DURATION)
            val filtered = _textStates.value.filter { (_, state) ->
                state.lastModified > cutoff
            }
            _textStates.value = filtered
            lastCleanup = now
        }
    }

    private suspend fun updateBlockContentOptimized(blockId: String, content: String) {
        // Security: validate content length
        require(content.length <= MAX_CONTENT_LENGTH) { "Content exceeds maximum length" }
        
        val block = blockRepository.getBlockByUuid(blockId).first().getOrNull()
        if (block != null) {
            val updatedBlock = block.copy(content = content)
            blockRepository.saveBlock(updatedBlock)
        }
    }

    private fun isValidRange(range: TextRange, contentLength: Int): Boolean {
        if (range.start < 0 || range.start > range.end) return false
        return if (range.isCollapsed) range.end <= contentLength else range.end < contentLength
    }

    private fun isValidText(text: String): Boolean {
        // Security: validate for dangerous characters
        return !text.any { char ->
            char.code in DANGEROUS_CHARACTERS
        }
    }

    private fun findWordStartOptimized(text: String, position: Int): Int {
        var start = position
        while (start > 0 && !isWordBoundaryOptimized(text[start - 1])) {
            start--
        }
        return start
    }

    private fun findWordEndOptimized(text: String, position: Int): Int {
        var end = position
        while (end < text.length && !isWordBoundaryOptimized(text[end])) {
            end++
        }
        return end
    }

    private fun findLineStartOptimized(text: String, position: Int): Int {
        return text.lastIndexOf('\n', position - 1) + 1
    }

    private fun findLineEndOptimized(text: String, position: Int): Int {
        val nextNewline = text.indexOf('\n', position)
        return if (nextNewline != -1) nextNewline else text.length
    }

    private fun isWordBoundaryOptimized(char: Char): Boolean {
        return !char.isLetterOrDigit() && char != '_' && char != '-'
    }

    // Platform-specific clipboard implementation would go here; returns placeholder for now.
    private val clipboardText: String = "[pasted text]"

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

    companion object {
        // Security constants
        private const val MAX_TEXT_LENGTH = 10000
        private const val MAX_CONTENT_LENGTH = 100000

        // Security: dangerous character ranges
        private val DANGEROUS_CHARACTERS = setOf(
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, // Control chars
            127, 129, 141, 143, 144, 157 // Extended control
        )
        
        // Performance constants
        private val CLEANUP_INTERVAL = 5.minutes
        private val STALE_STATE_DURATION = 30.minutes
    }
}
