package dev.stapler.stelekit.command

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the undo/redo stacks and command execution.
 */
class UndoManager {
    private val _undoStack = ArrayDeque<Command<*>>()
    private val _redoStack = ArrayDeque<Command<*>>()
    
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /**
     * Executes a command and pushes it to the undo stack if successful.
     * Clears the redo stack.
     */
    suspend fun <T> execute(command: Command<T>): Either<DomainError, T> {
        val result = command.execute()
        if (result.isRight()) {
            _undoStack.addLast(command)
            _redoStack.clear()
            updateState()
        }
        return result
    }

    /**
     * Undoes the last executed command.
     */
    suspend fun undo(): Either<DomainError, Unit> {
        val command = _undoStack.removeLastOrNull() ?: return DomainError.DatabaseError.WriteFailed("Nothing to undo").left()
        
        val result = command.undo()
        if (result.isRight()) {
            _redoStack.addLast(command)
            updateState()
        } else {
            // If undo fails, we put it back to maintain state consistency as best as possible
            _undoStack.addLast(command) 
        }
        return result
    }

    /**
     * Redoes the last undone command.
     */
    suspend fun redo(): Either<DomainError, Unit> {
        val command = _redoStack.removeLastOrNull() ?: return DomainError.DatabaseError.WriteFailed("Nothing to redo").left()

        val result = command.execute()
        if (result.isRight()) {
            _undoStack.addLast(command)
            updateState()
            return Unit.right()
        } else {
            _redoStack.addLast(command)
            return (result.leftOrNull() ?: DomainError.DatabaseError.WriteFailed("Redo failed")).left()
        }
    }
    
    /**
     * Clears the undo and redo stacks.
     */
    fun clear() {
        _undoStack.clear()
        _redoStack.clear()
        updateState()
    }

    private fun updateState() {
        _canUndo.value = _undoStack.isNotEmpty()
        _canRedo.value = _redoStack.isNotEmpty()
    }
}
