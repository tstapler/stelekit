package dev.stapler.stelekit.editor.text

import kotlinx.coroutines.flow.StateFlow
import kotlin.Result
import dev.stapler.stelekit.editor.TextFormat

/**
 * Simplified interface for text operations in rich editor.
 * Matches the OptimizedTextOperations implementation.
 */
interface ITextOperations {
    
    // ===== STATE ACCESS =====
    
    /**
     * Get reactive text state for a specific block.
     */
    fun getTextState(blockId: String): StateFlow<TextState>
    
    /**
     * Get reactive selection state for a specific block.
     */
    fun getSelection(blockId: String): StateFlow<TextSelection?>
    
    // ===== TEXT OPERATIONS =====
    
    /**
     * Insert text at current cursor position in block.
     */
    suspend fun insertText(blockId: String, text: String): Result<Unit>
    
    /**
     * Replace text in specified range within block.
     */
    suspend fun replaceText(blockId: String, range: TextRange, newText: String): Result<Unit>
    
    /**
     * Delete text in specified range within block.
     */
    suspend fun deleteText(blockId: String, range: TextRange): Result<Unit>
    
    /**
     * Get current text content for block.
     */
    suspend fun getText(blockId: String): Result<String>
    
    /**
     * Set cursor position within block.
     */
    suspend fun setCursor(blockId: String, position: Int): Result<Unit>
    
    /**
     * Set text selection within block.
     */
    suspend fun setSelection(blockId: String, range: TextRange): Result<Unit>
    
    // ===== CURSOR MOVEMENT =====

    /**
     * Move cursor to the start of the current word.
     */
    suspend fun moveCursorToWordStart(blockId: String): Result<Unit>

    /**
     * Move cursor to the end of the current word.
     */
    suspend fun moveCursorToWordEnd(blockId: String): Result<Unit>

    /**
     * Move cursor to the start of the current line.
     */
    suspend fun moveCursorToLineStart(blockId: String): Result<Unit>

    /**
     * Move cursor to the end of the current line.
     */
    suspend fun moveCursorToLineEnd(blockId: String): Result<Unit>
    
    // ===== SELECTION OPERATIONS =====
    
    /**
     * Select word at current cursor position.
     */
    suspend fun selectWord(blockId: String): Result<Unit>
    
    /**
     * Select line at current cursor position.
     */
    suspend fun selectLine(blockId: String): Result<Unit>
    
    /**
     * Select all text in block.
     */
    suspend fun selectAll(blockId: String): Result<Unit>
    
    // ===== CLIPBOARD OPERATIONS =====
    
    /**
     * Cut selected text to clipboard.
     */
    suspend fun cut(blockId: String): Result<String>
    
    /**
     * Copy selected text to clipboard.
     */
    suspend fun copy(blockId: String): Result<String>
    
    /**
     * Paste text from clipboard at current position.
     */
    suspend fun paste(blockId: String): Result<Unit>

    /**
     * Duplicate the current block or selection.
     */
    suspend fun duplicate(blockId: String): Result<Unit>

    /**
     * Apply formatting to a range.
     */
    suspend fun applyFormat(blockId: String, range: TextRange, format: TextFormat): Result<Unit>

    /**
     * Initialize text state for a block with its content.
     * Should be called when loading blocks to populate initial content.
     */
    suspend fun initializeBlock(blockId: String, content: String): Result<Unit>
}