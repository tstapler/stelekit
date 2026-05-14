package dev.stapler.stelekit.ui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages undo/redo history for block editing operations.
 *
 * Capped at [maxHistory] entries (oldest discarded on overflow). Redo stack is
 * cleared whenever a new operation is recorded, matching standard editor behaviour.
 */
class BlockUndoManager(private val scope: CoroutineScope, private val maxHistory: Int = 100) {

    private data class UndoEntry(
        val undo: suspend () -> Unit,
        val redo: (suspend () -> Unit)?
    )

    private val undoStack = ArrayDeque<UndoEntry>()
    private val redoStack = ArrayDeque<UndoEntry>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    fun record(undo: suspend () -> Unit, redo: (suspend () -> Unit)? = null) {
        undoStack.addLast(UndoEntry(undo, redo))
        if (undoStack.size > maxHistory) undoStack.removeFirst()
        redoStack.clear()
        _canUndo.value = true
        _canRedo.value = false
    }

    fun undo(): Job = scope.launch {
        val entry = undoStack.removeLastOrNull() ?: return@launch
        entry.undo()
        if (entry.redo != null) {
            redoStack.addLast(entry)
            _canRedo.value = true
        } else {
            redoStack.clear()
            _canRedo.value = false
        }
        _canUndo.value = undoStack.isNotEmpty()
    }

    fun redo(): Job = scope.launch {
        val entry = redoStack.removeLastOrNull() ?: return@launch
        entry.redo?.invoke() ?: return@launch
        undoStack.addLast(entry)
        _canUndo.value = true
        _canRedo.value = redoStack.isNotEmpty()
    }
}
