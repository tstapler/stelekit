package dev.stapler.stelekit.editor.text

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.Result
import dev.stapler.stelekit.editor.TextFormat

/**
 * Default implementation of text operations for initial state.
 * Mostly no-ops or in-memory only.
 */
class DefaultTextOperations(
    private val scope: CoroutineScope
) : ITextOperations {
    
    private val _textState = MutableStateFlow(TextState())
    
    override fun getTextState(blockId: String): StateFlow<TextState> {
        return _textState.asStateFlow()
    }

    override fun getSelection(blockId: String): StateFlow<TextSelection?> {
        return MutableStateFlow(null)
    }

    override suspend fun insertText(blockId: String, text: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun replaceText(blockId: String, range: TextRange, newText: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun deleteText(blockId: String, range: TextRange): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun getText(blockId: String): Result<String> {
        return Result.success("")
    }

    override suspend fun setCursor(blockId: String, position: Int): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun setSelection(blockId: String, range: TextRange): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun selectWord(blockId: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun selectLine(blockId: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun selectAll(blockId: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun cut(blockId: String): Result<String> {
        return Result.success("")
    }

    override suspend fun copy(blockId: String): Result<String> {
        return Result.success("")
    }

    override suspend fun paste(blockId: String): Result<Unit> {
        return Result.success(Unit)
    }
    
    override suspend fun moveCursorToWordStart(blockId: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun moveCursorToWordEnd(blockId: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun moveCursorToLineStart(blockId: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun moveCursorToLineEnd(blockId: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun duplicate(blockId: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun applyFormat(blockId: String, range: TextRange, format: TextFormat): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun initializeBlock(blockId: String, content: String): Result<Unit> {
        _textState.value = TextState(content = content)
        return Result.success(Unit)
    }
}
