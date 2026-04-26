package dev.stapler.stelekit.editor.text

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

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
class DefaultTextOperations : ITextOperations {
    
    private val _textState = MutableStateFlow(TextState())
    
    override fun getTextState(blockId: String): StateFlow<TextState> {
        return _textState.asStateFlow()
    }

    override fun getSelection(blockId: String): StateFlow<TextSelection?> {
        return MutableStateFlow(null)
    }

    override suspend fun insertText(blockId: String, text: String): Either<DomainError, Unit> {
        return Unit.right()
    }

    override suspend fun replaceText(blockId: String, range: TextRange, newText: String): Either<DomainError, Unit> {
        return Unit.right()
    }

    override suspend fun deleteText(blockId: String, range: TextRange): Either<DomainError, Unit> {
        return Unit.right()
    }

    override suspend fun getText(blockId: String): Either<DomainError, String> {
        return "".right()
    }

    override suspend fun setCursor(blockId: String, position: Int): Either<DomainError, Unit> {
        return Unit.right()
    }

    override suspend fun setSelection(blockId: String, range: TextRange): Either<DomainError, Unit> {
        return Unit.right()
    }

    override suspend fun selectWord(blockId: String): Either<DomainError, Unit> {
        return Unit.right()
    }

    override suspend fun selectLine(blockId: String): Either<DomainError, Unit> {
        return Unit.right()
    }

    override suspend fun selectAll(blockId: String): Either<DomainError, Unit> {
        return Unit.right()
    }

    override suspend fun cut(blockId: String): Either<DomainError, String> {
        return "".right()
    }

    override suspend fun copy(blockId: String): Either<DomainError, String> {
        return "".right()
    }

    override suspend fun paste(blockId: String): Either<DomainError, Unit> {
        return Unit.right()
    }
    
    override suspend fun moveCursorToWordStart(blockId: String): Either<DomainError, Unit> {
        return Unit.right()
    }

    override suspend fun moveCursorToWordEnd(blockId: String): Either<DomainError, Unit> {
        return Unit.right()
    }

    override suspend fun moveCursorToLineStart(blockId: String): Either<DomainError, Unit> {
        return Unit.right()
    }

    override suspend fun moveCursorToLineEnd(blockId: String): Either<DomainError, Unit> {
        return Unit.right()
    }

    override suspend fun duplicate(blockId: String): Either<DomainError, Unit> {
        return Unit.right()
    }

    override suspend fun applyFormat(blockId: String, range: TextRange, format: TextFormat): Either<DomainError, Unit> {
        return Unit.right()
    }

    override suspend fun initializeBlock(blockId: String, content: String): Either<DomainError, Unit> {
        _textState.value = TextState(content = content)
        return Unit.right()
    }
}
