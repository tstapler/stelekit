package dev.stapler.stelekit.command

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
    suspend fun <T> execute(command: Command<T>): Result<T> {
        val result = command.execute()
        if (result.isSuccess) {
            _undoStack.addLast(command)
            _redoStack.clear()
            updateState()
        }
        return result
    }

    /**
     * Undoes the last executed command.
     */
    suspend fun undo(): Result<Unit> {
        val command = _undoStack.removeLastOrNull() ?: return Result.failure(Exception("Nothing to undo"))
        
        val result = command.undo()
        if (result.isSuccess) {
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
    suspend fun redo(): Result<Unit> {
        val command = _redoStack.removeLastOrNull() ?: return Result.failure(Exception("Nothing to redo"))
        
        // We ignore the result type T here because redo just restores state
        val result = command.execute()
        if (result.isSuccess) {
            _undoStack.addLast(command)
            updateState()
            return Result.success(Unit)
        } else {
            _redoStack.addLast(command)
            return Result.failure(result.exceptionOrNull() ?: Exception("Redo failed"))
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
