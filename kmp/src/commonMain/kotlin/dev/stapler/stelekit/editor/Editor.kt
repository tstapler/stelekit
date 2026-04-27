@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.editor

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError

import androidx.compose.runtime.*
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.CursorState
import dev.stapler.stelekit.editor.commands.*
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.db.GraphWriter
import dev.stapler.stelekit.editor.text.ITextOperations
import dev.stapler.stelekit.editor.state.EditorState
import dev.stapler.stelekit.editor.state.EditorConfig
import dev.stapler.stelekit.editor.state.EditorMode
import dev.stapler.stelekit.editor.blocks.IBlockOperations
import dev.stapler.stelekit.editor.blocks.DeleteStrategy
import dev.stapler.stelekit.editor.format.IFormatProcessor
import dev.stapler.stelekit.performance.PerformanceMonitor
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Main editor implementation that orchestrates all editing operations.
 * Updated to use UUID-native storage.
 */
class Editor(
    private val blockRepository: BlockRepository,
    private val textOperations: ITextOperations,
    private val blockOperations: IBlockOperations,
    private val commandSystem: ICommandSystem,
) : IEditor {

    private val scope = mutableStateOf<kotlinx.coroutines.CoroutineScope?>(null)
    private val _editorState = MutableStateFlow(EditorState(textOperations = textOperations))
    private val _currentPage = MutableStateFlow<Page?>(null)
    private val _cursorState = MutableStateFlow(CursorState())

    override val editorState: StateFlow<EditorState> = _editorState.asStateFlow()
    override val currentPage: StateFlow<Page?> = _currentPage.asStateFlow()
    override val cursorState: StateFlow<CursorState> = _cursorState.asStateFlow()

    override val config: EditorConfig = EditorConfig()

    override suspend fun initialize(page: Page): Either<DomainError, Unit> {
        return try {
            val traceId = dev.stapler.stelekit.performance.PerformanceMonitor.startTrace("editor-initialize")
            
            // Set current page
            _currentPage.value = page
            
            // Load blocks for this page
            val blocks = blockRepository.getBlocksForPage(page.uuid).first().getOrNull().orEmpty()
            
            // Update editor state
            _editorState.update { it.copy(
                isLoading = false,
                blocks = blocks,
                isEditing = true
            ) }
            
            // Initialize text states for all blocks with their content
            blocks.forEach { block: Block ->
                textOperations.initializeBlock(block.uuid, block.content)
            }
            
            dev.stapler.stelekit.performance.PerformanceMonitor.endTrace(traceId)
            Unit.right()
        } catch (e: Exception) {
            _editorState.update { it.copy(isLoading = false) }
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun dispose() {
        _editorState.update { EditorState(textOperations = textOperations) }
        _currentPage.value = null
        _cursorState.value = CursorState()
    }

    override fun handleKeyEvent(keyEvent: KeyEvent): Boolean {
        val key = keyEvent.key
        when {
            keyEvent.isCtrlPressed && key == Key.S -> {
                // Save
                scope.value?.launch {
                    executeCommand("system.save", emptyMap())
                }
                return true
            }
            keyEvent.isCtrlPressed && key == Key.Z -> {
                // Undo
                scope.value?.launch {
                    executeCommand("system.undo", emptyMap())
                }
                return true
            }
            keyEvent.isCtrlPressed && key == Key.Y -> {
                // Redo
                scope.value?.launch {
                    executeCommand("system.redo", emptyMap())
                }
                return true
            }
            keyEvent.isCtrlPressed && key == Key.F -> {
                // Search
                scope.value?.launch {
                    executeCommand("navigation.search", emptyMap())
                }
                return true
            }
            key == Key.Escape -> {
                // Command palette
                scope.value?.launch {
                    _editorState.update { it.copy(mode = EditorMode.VIEW) }
                }
                return true
            }
        }
        
        return false
    }

    override suspend fun executeCommand(command: EditorCommand): Either<DomainError, Unit> {
        return try {
            val traceId = dev.stapler.stelekit.performance.PerformanceMonitor.startTrace("execute-command")
            
            val currentBlockUuid = _cursorState.value.blockId
            val textState = currentBlockUuid?.let { blockId: String -> textOperations.getTextState(blockId).value }
            val content = textState?.content ?: ""
            val selection = textState?.selection
            
            val context = CommandContext(
                currentText = content,
                selectionStart = selection?.range?.start ?: _cursorState.value.position,
                selectionEnd = selection?.range?.end ?: _cursorState.value.position,
                currentBlockId = currentBlockUuid,
                currentBlockContent = content,
                currentPageId = _currentPage.value?.uuid,
                cursorPosition = _cursorState.value.position
            )
            
            val result = command.execute(context)
            
            dev.stapler.stelekit.performance.PerformanceMonitor.endTrace(traceId)
            if (result is CommandResult.Success) Unit.right() else DomainError.DatabaseError.WriteFailed((result as CommandResult.Error).message).left()
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    override suspend fun executeCommand(commandId: String, args: Map<String, Any>): Either<DomainError, Any?> {
        return try {
            val currentBlockUuid = _cursorState.value.blockId
            val textState = currentBlockUuid?.let { textOperations.getTextState(it).value }
            val content = textState?.content ?: ""
            val selection = textState?.selection
            
            val context = CommandContext(
                currentText = content,
                selectionStart = selection?.range?.start ?: _cursorState.value.position,
                selectionEnd = selection?.range?.end ?: _cursorState.value.position,
                currentBlockId = currentBlockUuid,
                currentBlockContent = content,
                currentPageId = _currentPage.value?.uuid,
                cursorPosition = _cursorState.value.position,
                additionalData = args
            )

            val result = commandSystem.executeCommand(commandId, context)
            
            when (result) {
                is CommandResult.Success -> result.data.right()
                is CommandResult.Error -> DomainError.DatabaseError.WriteFailed(result.message).left()
                is CommandResult.Partial -> mapOf("completed" to result.completed, "total" to result.total).right()
                is CommandResult.Nothing -> null.right()
            }
        } catch (e: Exception) {
            DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left()
        }
    }

    // Editor-specific helper methods

    suspend fun insertText(text: String): Either<DomainError, Unit> {
        val currentBlockUuid = _cursorState.value.blockId
        return if (currentBlockUuid != null) {
            textOperations.insertText(currentBlockUuid, text)
        } else {
            DomainError.DatabaseError.WriteFailed("No block focused").left()
        }
    }

    suspend fun createNewBlock(content: String = ""): Either<DomainError, Block> {
        val currentPage = _currentPage.value
        val focusedBlockUuid = _cursorState.value.blockId
        
        return if (currentPage != null) {
            // Create new block after focused block or at root
            var parentUuid: String? = null
            
            if (focusedBlockUuid != null) {
                val focusedBlock = blockRepository.getBlockByUuid(focusedBlockUuid).first().getOrNull()
                // If we have a focused block, we likely want to create a sibling (same parent)
                parentUuid = focusedBlock?.parentUuid
            }
            
            blockOperations.createBlock(
                pageId = currentPage.uuid,
                content = content,
                parentId = parentUuid
            ).also { result ->
                if (result.isRight()) {
                    // Update editor state
                    val newBlock = result.getOrNull()
                    if (newBlock != null) {
                        _editorState.update { current ->
                        current.copy(
                            blocks = current.blocks + listOf(newBlock)
                        )
                        }
                        // Focus new block
                        _cursorState.value = CursorState(
                            blockId = newBlock.uuid,
                            position = content.length
                        )
                    }
                }
            }
        } else {
            DomainError.DatabaseError.WriteFailed("No page loaded").left()
        }
    }

    suspend fun deleteCurrentBlock(): Either<DomainError, Unit> {
        val currentBlockUuid = _cursorState.value.blockId
        return if (currentBlockUuid != null) {
            blockOperations.deleteBlock(currentBlockUuid, false).also { result ->
                if (result.isRight()) {
                    // Update editor state
                    _editorState.update { current ->
                        current.copy(
                            blocks = current.blocks.filter { block -> block.uuid != currentBlockUuid }
                        )
                    }
                    // Move focus to next sibling or parent
                    moveToNextBlockOrParent()
                }
            }
        } else {
            DomainError.DatabaseError.WriteFailed("No block focused").left()
        }
    }

    suspend fun indentCurrentBlock(): Either<DomainError, Unit> {
        val currentBlockUuid = _cursorState.value.blockId
        return if (currentBlockUuid != null) {
            blockOperations.indentBlock(currentBlockUuid).also { result ->
                if (result.isRight()) {
                    refreshBlocks()
                }
            }
        } else {
            DomainError.DatabaseError.WriteFailed("No block focused").left()
        }
    }

    suspend fun outdentCurrentBlock(): Either<DomainError, Unit> {
        val currentBlockUuid = _cursorState.value.blockId
        return if (currentBlockUuid != null) {
            blockOperations.outdentBlock(currentBlockUuid).also { result ->
                if (result.isRight()) {
                    refreshBlocks()
                }
            }
        } else {
            DomainError.DatabaseError.WriteFailed("No block focused").left()
        }
    }

    suspend fun moveBlockUp(): Either<DomainError, Unit> {
        val currentBlockUuid = _cursorState.value.blockId
        return if (currentBlockUuid != null) {
            blockOperations.moveBlockUp(currentBlockUuid).also { result ->
                if (result.isRight()) {
                    refreshBlocks()
                }
            }
        } else {
            DomainError.DatabaseError.WriteFailed("No block focused").left()
        }
    }

    suspend fun moveBlockDown(): Either<DomainError, Unit> {
        val currentBlockUuid = _cursorState.value.blockId
        return if (currentBlockUuid != null) {
            blockOperations.moveBlockDown(currentBlockUuid).also { result ->
                if (result.isRight()) {
                    refreshBlocks()
                }
            }
        } else {
            DomainError.DatabaseError.WriteFailed("No block focused").left()
        }
    }

    suspend fun splitCurrentBlock(): Either<DomainError, Block> {
        val currentBlockUuid = _cursorState.value.blockId
        val position = _cursorState.value.position
        
        return if (currentBlockUuid != null) {
            blockOperations.splitBlock(currentBlockUuid, position).also { result ->
                if (result.isRight()) {
                    refreshBlocks()
                    // Focus the new block
                    val newBlock = result.getOrNull()
                    if (newBlock != null) {
                        _cursorState.value = CursorState(
                            blockId = newBlock.uuid,
                            position = 0
                        )
                    }
                }
            }
        } else {
            DomainError.DatabaseError.WriteFailed("No block focused").left()
        }
    }

    // Private helper methods

    private fun getSelectedText(): String? {
        val currentBlockUuid = _cursorState.value.blockId
        return if (currentBlockUuid != null) {
            textOperations.getTextState(currentBlockUuid).value.selection.let { textSelection ->
                val textState = textOperations.getTextState(currentBlockUuid).value
                if (textSelection.range.start >= 0 && textSelection.range.end <= textState.content.length) {
                    textState.content.substring(textSelection.range.start, textSelection.range.end)
                } else null
            }
        } else {
            null
        }
    }

    private suspend fun refreshBlocks() {
        val currentPage = _currentPage.value
        if (currentPage != null) {
            val blocks = blockRepository.getBlocksForPage(currentPage.uuid).first().getOrNull().orEmpty()
            _editorState.update { it.copy(blocks = blocks) }
        }
    }

    private suspend fun moveToNextBlockOrParent() {
        val currentBlockUuid = _cursorState.value.blockId ?: return
        
        // Try to get next sibling
        val nextSibling = blockOperations.getBlockSiblings(currentBlockUuid).first().getOrNull()
            ?.filter { sibling -> sibling.uuid != currentBlockUuid }
            ?.sortedBy { sibling -> sibling.position }
            ?.firstOrNull()
        
        if (nextSibling != null) {
            _cursorState.value = CursorState(
                blockId = nextSibling.uuid,
                position = 0
            )
        } else {
            // Move to parent
            val parent = blockOperations.getBlockParent(currentBlockUuid).first().getOrNull()
            if (parent != null) {
                _cursorState.value = CursorState(
                    blockId = parent.uuid,
                    position = 0
                )
            }
        }
    }
}
