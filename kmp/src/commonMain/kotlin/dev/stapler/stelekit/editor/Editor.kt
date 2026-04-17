@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.editor

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
import kotlin.Result

/**
 * Main editor implementation that orchestrates all editing operations.
 * Updated to use UUID-native storage.
 */
class Editor(
    private val blockRepository: BlockRepository,
    private val graphWriter: GraphWriter,
    private val textOperations: ITextOperations,
    private val blockOperations: IBlockOperations,
    private val commandSystem: ICommandSystem,
    private val formatProcessor: IFormatProcessor
) : IEditor {

    private val scope = mutableStateOf<kotlinx.coroutines.CoroutineScope?>(null)
    private val _editorState = MutableStateFlow(EditorState(textOperations = textOperations))
    private val _currentPage = MutableStateFlow<Page?>(null)
    private val _cursorState = MutableStateFlow(CursorState())

    override val editorState: StateFlow<EditorState> = _editorState.asStateFlow()
    override val currentPage: StateFlow<Page?> = _currentPage.asStateFlow()
    override val cursorState: StateFlow<CursorState> = _cursorState.asStateFlow()

    override val config: EditorConfig = EditorConfig()

    override suspend fun initialize(page: Page): Result<Unit> {
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
            Result.success(Unit)
        } catch (e: Exception) {
            _editorState.update { it.copy(isLoading = false) }
            Result.failure(e)
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

    override suspend fun executeCommand(command: EditorCommand): Result<Unit> {
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
            if (result is CommandResult.Success) Result.success(Unit) else Result.failure(Exception((result as CommandResult.Error).message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun executeCommand(commandId: String, args: Map<String, Any>): Result<Any?> {
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
                is CommandResult.Success -> Result.success(result.data)
                is CommandResult.Error -> Result.failure(result.exception ?: Exception(result.message))
                is CommandResult.Partial -> Result.success(mapOf("completed" to result.completed, "total" to result.total))
                is CommandResult.Nothing -> Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Editor-specific helper methods

    suspend fun insertText(text: String): Result<Unit> {
        val currentBlockUuid = _cursorState.value.blockId
        return if (currentBlockUuid != null) {
            textOperations.insertText(currentBlockUuid, text)
        } else {
            Result.failure(IllegalStateException("No block focused"))
        }
    }

    suspend fun createNewBlock(content: String = ""): Result<Block> {
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
                if (result.isSuccess) {
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
            Result.failure(IllegalStateException("No page loaded"))
        }
    }

    suspend fun deleteCurrentBlock(): Result<Unit> {
        val currentBlockUuid = _cursorState.value.blockId
        return if (currentBlockUuid != null) {
            blockOperations.deleteBlock(currentBlockUuid, false).also { result ->
                if (result.isSuccess) {
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
            Result.failure(IllegalStateException("No block focused"))
        }
    }

    suspend fun indentCurrentBlock(): Result<Unit> {
        val currentBlockUuid = _cursorState.value.blockId
        return if (currentBlockUuid != null) {
            blockOperations.indentBlock(currentBlockUuid).also { result ->
                if (result.isSuccess) {
                    refreshBlocks()
                }
            }
        } else {
            Result.failure(IllegalStateException("No block focused"))
        }
    }

    suspend fun outdentCurrentBlock(): Result<Unit> {
        val currentBlockUuid = _cursorState.value.blockId
        return if (currentBlockUuid != null) {
            blockOperations.outdentBlock(currentBlockUuid).also { result ->
                if (result.isSuccess) {
                    refreshBlocks()
                }
            }
        } else {
            Result.failure(IllegalStateException("No block focused"))
        }
    }

    suspend fun moveBlockUp(): Result<Unit> {
        val currentBlockUuid = _cursorState.value.blockId
        return if (currentBlockUuid != null) {
            blockOperations.moveBlockUp(currentBlockUuid).also { result ->
                if (result.isSuccess) {
                    refreshBlocks()
                }
            }
        } else {
            Result.failure(IllegalStateException("No block focused"))
        }
    }

    suspend fun moveBlockDown(): Result<Unit> {
        val currentBlockUuid = _cursorState.value.blockId
        return if (currentBlockUuid != null) {
            blockOperations.moveBlockDown(currentBlockUuid).also { result ->
                if (result.isSuccess) {
                    refreshBlocks()
                }
            }
        } else {
            Result.failure(IllegalStateException("No block focused"))
        }
    }

    suspend fun splitCurrentBlock(): Result<Block> {
        val currentBlockUuid = _cursorState.value.blockId
        val position = _cursorState.value.position
        
        return if (currentBlockUuid != null) {
            blockOperations.splitBlock(currentBlockUuid, position).also { result ->
                if (result.isSuccess) {
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
            Result.failure(IllegalStateException("No block focused"))
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
