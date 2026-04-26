@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.editor.viewmodel

import androidx.compose.runtime.*
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.editor.state.*
import dev.stapler.stelekit.editor.input.ImeCompositionManager
import dev.stapler.stelekit.editor.input.KeyboardEventHandler
import dev.stapler.stelekit.editor.text.TextOperations
import dev.stapler.stelekit.editor.text.ITextOperations
import dev.stapler.stelekit.editor.text.TextRange
import dev.stapler.stelekit.editor.text.TextState
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.performance.PerformanceMonitor
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.ui.i18n.Language
import dev.stapler.stelekit.ui.theme.StelekitThemeMode
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.random.Random

/**
 * ViewModel for rich text editor that integrates with existing Logseq infrastructure.
 * Provides reactive state management, auto-save functionality, and comprehensive text editing capabilities.
 * 
 * Updated to use UUID-native storage.
 */
@Stable
class EditorViewModel(
    private val blockRepository: BlockRepository,
    private val scope: CoroutineScope,
    initialState: EditorState = EditorState.empty(scope)
) {
    private val logger = Logger("EditorViewModel")
    private val performanceTraceId = PerformanceMonitor.startTrace("editor-viewmodel-init")
    
    // ===== STATE MANAGEMENT =====
    
    private val _editorState = MutableStateFlow(initialState)
    val editorState: StateFlow<EditorState> = _editorState.asStateFlow()

    private val currentTextOperations: ITextOperations get() = _editorState.value.textOperations
    
    // ===== MANAGERS =====
    
    private val imeManager = ImeCompositionManager(scope)
    private val keyboardEventHandler = KeyboardEventHandler(
        editorStateProvider = { _editorState.value },
        onEditorStateChange = { newState -> _editorState.value = newState },
        onExecuteCommand = { command -> executeCommand(command) },
        scope = scope
    )
    
    private fun executeCommand(command: String) {
        when (command) {
            "undo" -> undo()
            "redo" -> redo()
            "findNext" -> {
                val state = _editorState.value
                findNext(state.findText, state.findCaseSensitive, state.findWholeWord)
            }
            "save" -> saveToBlock()
            else -> logger.warn("Unknown command: $command")
        }
    }
    
    // ===== AUTO-SAVE FUNCTIONALITY =====
    
    private var autoSaveJob: Job? = null
    private val _autoSaveStatus = MutableStateFlow<AutoSaveStatus>(AutoSaveStatus.Idle)
    val autoSaveStatus: StateFlow<AutoSaveStatus> = _autoSaveStatus.asStateFlow()
    
    // ===== ERROR HANDLING =====
    
    private val _errorState = MutableStateFlow<EditorError?>(null)
    val errorState: StateFlow<EditorError?> = _errorState.asStateFlow()
    
    init {
        PerformanceMonitor.endTrace(performanceTraceId)
        
        // Initialize text operations
        initializeTextOperations()
        
        // Set up auto-save monitoring
        setupAutoSaveMonitoring()
        
        // Set up error monitoring
        setupErrorMonitoring()
        
        // Set up IME integration
        setupImeIntegration()
    }
    
    // ===== TEXT OPERATIONS =====
    
    private fun initializeTextOperations() {
        val textOperations = TextOperations(blockRepository)
        updateEditorState { it.withTextOperations(textOperations) }
    }
    
    /**
     * Get the current text operations interface.
     */
    fun getTextOperations(): ITextOperations {
        return _editorState.value.textOperations
    }
    
    /**
     * Insert text at the current cursor position.
     */
    fun insertText(text: String) {
        scope.launch {
            try {
                val currentState = _editorState.value
                val focusedBlockUuid = currentState.metadata["blockUuid"]
                if (focusedBlockUuid != null) {
                    currentTextOperations.insertText(focusedBlockUuid, text)
                }
                logger.debug("Inserted text: '$text'")
            } catch (e: Exception) {
                handleError(EditorError.TextOperationError("Failed to insert text", e))
            }
        }
    }
    
    /**
     * Replace the entire content.
     */
    fun setContent(content: String) {
        scope.launch {
            try {
                val currentState = _editorState.value
                val focusedBlockUuid = currentState.metadata["blockUuid"]
                if (focusedBlockUuid != null) {
                    // For setContent, we need to replace the entire content
                    val currentContent = currentTextOperations.getText(focusedBlockUuid).getOrNull() ?: ""
                    currentTextOperations.deleteText(focusedBlockUuid, TextRange(0, currentContent.length))
                    currentTextOperations.insertText(focusedBlockUuid, content)
                }
                logger.debug("Set content length: ${content.length}")
            } catch (e: Exception) {
                handleError(EditorError.TextOperationError("Failed to set content", e))
            }
        }
    }
    
    /**
     * Clear all content.
     */
    fun clearContent() {
        scope.launch {
            try {
                val currentState = _editorState.value
                val focusedBlockUuid = currentState.metadata["blockUuid"]
                if (focusedBlockUuid != null) {
                    val currentContent = currentTextOperations.getText(focusedBlockUuid).getOrNull() ?: ""
                    currentTextOperations.deleteText(focusedBlockUuid, TextRange(0, currentContent.length))
                }
                logger.debug("Cleared content")
            } catch (e: Exception) {
                handleError(EditorError.TextOperationError("Failed to clear content", e))
            }
        }
    }
    
    // ===== EDITOR CONFIGURATION =====
    
    /**
     * Update editor configuration.
     */
    fun updateConfig(config: EditorConfig) {
        updateEditorState { it.withConfig(config) }
        logger.debug("Updated editor configuration")
    }
    
    /**
     * Update editor theme.
     */
    fun updateTheme(themeMode: StelekitThemeMode) {
        val currentConfig = _editorState.value.config
        val theme = when (themeMode) {
            StelekitThemeMode.LIGHT -> EditorTheme.Light
            StelekitThemeMode.DARK -> EditorTheme.Dark
            StelekitThemeMode.STONE -> EditorTheme.Gruvbox
            StelekitThemeMode.SYSTEM -> EditorTheme.Default
            StelekitThemeMode.DYNAMIC -> EditorTheme.Default
        }
        val newConfig = currentConfig.copy(theme = theme)
        updateConfig(newConfig)
        logger.debug("Updated theme to: $themeMode")
    }
    
    /**
     * Update editor language.
     */
    fun updateLanguage(language: Language) {
        // Language affects UI but not editor state directly
        logger.debug("Updated language to: $language")
    }
    
    /**
     * Update font configuration.
     */
    fun updateFontConfiguration(
        fontFamily: String? = null,
        fontSize: Float? = null,
        lineHeight: Float? = null
    ) {
        val currentConfig = _editorState.value.config
        val newConfig = currentConfig.copy(
            fontFamily = fontFamily ?: currentConfig.fontFamily,
            fontSize = fontSize ?: currentConfig.fontSize,
            lineHeight = lineHeight ?: currentConfig.lineHeight
        )
        updateConfig(newConfig)
        logger.debug("Updated font configuration")
    }
    
    // ===== FOCUS AND UI STATE =====
    
    /**
     * Request focus for the editor.
     */
    fun requestFocus() {
        updateEditorState { it.withFocus(true) }
        logger.debug("Requested focus")
    }
    
    /**
     * Release focus from the editor.
     */
    fun releaseFocus() {
        updateEditorState { it.withFocus(false) }
        logger.debug("Released focus")
    }
    
    /**
     * Show loading state with optional message.
     */
    fun showLoading(message: String? = null) {
        updateEditorState { it.withLoading(true, message) }
        logger.debug("Showed loading state")
    }
    
    /**
     * Hide loading state.
     */
    fun hideLoading() {
        updateEditorState { it.withLoading(false) }
        logger.debug("Hid loading state")
    }
    
    // ===== FIND AND REPLACE =====
    
    /**
     * Show find/replace dialog.
     */
    fun showFindReplace() {
        updateEditorState { it.withFindReplace(true) }
        logger.debug("Showed find/replace dialog")
    }
    
    /**
     * Hide find/replace dialog.
     */
    fun hideFindReplace() {
        updateEditorState { it.withFindReplace(false) }
        logger.debug("Hid find/replace dialog")
    }
    
    /**
     * Find next occurrence.
     */
    fun findNext(text: String, caseSensitive: Boolean = false, wholeWord: Boolean = false) {
        scope.launch {
            try {
                val currentState = _editorState.value
                val focusedBlockUuid = currentState.metadata["blockUuid"] ?: return@launch
                
                // findNext implementation using regex search
                val currentContent = currentTextOperations.getText(focusedBlockUuid).getOrNull() ?: ""
                val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                val regex = if (wholeWord) {
                    "\\b${Regex.escape(text)}\\b".toRegex(options)
                } else {
                    Regex.escape(text).toRegex(options)
                }
                val match = regex.find(currentContent)
                match?.let { mr ->
                    logger.debug("Found text at range: ${mr.range}")
                }
            } catch (e: Exception) {
                handleError(EditorError.SearchError("Failed to find next", e))
            }
        }
    }
    
    /**
     * Replace all occurrences.
     */
    fun replaceAll(
        searchText: String,
        replacementText: String,
        caseSensitive: Boolean = false,
        wholeWord: Boolean = false
    ) {
        scope.launch {
            try {
                val currentState = _editorState.value
                val focusedBlockUuid = currentState.metadata["blockUuid"] ?: return@launch
                
                val currentContent = currentTextOperations.getText(focusedBlockUuid).getOrNull() ?: ""
                val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                val regex = if (wholeWord) {
                    "\\b${Regex.escape(searchText)}\\b".toRegex(options)
                } else {
                    Regex.escape(searchText).toRegex(options)
                }
                val replacement = currentContent.replace(regex, replacementText)
                
                // Apply the replacement
                currentTextOperations.deleteText(focusedBlockUuid, TextRange(0, currentContent.length))
                currentTextOperations.insertText(focusedBlockUuid, replacement)
                
                logger.debug("Replaced occurrences")
            } catch (e: Exception) {
                handleError(EditorError.SearchError("Failed to replace all", e))
            }
        }
    }
    
    // ===== UNDO/REDO =====
    
    /**
     * Undo the last operation.
     */
    fun undo() {
        scope.launch {
            try {
                val currentState = _editorState.value
                // undo operations would need to be implemented via command pattern or similar
                logger.info("Undo operation not implemented in EditorViewModel")
                logger.debug("Performed undo")
            } catch (e: Exception) {
                handleError(EditorError.UndoRedoError("Failed to undo", e))
            }
        }
    }
    
    /**
     * Redo the last undone operation.
     */
    fun redo() {
        scope.launch {
            try {
                val currentState = _editorState.value
                // redo operations would need to be implemented via command pattern or similar
                logger.info("Redo operation not implemented in EditorViewModel")
                logger.debug("Performed redo")
            } catch (e: Exception) {
                handleError(EditorError.UndoRedoError("Failed to redo", e))
            }
        }
    }
    
    // ===== BLOCK INTEGRATION =====
    
    /**
     * Load block content into the editor.
     */
    fun loadBlock(blockUuid: String) {
        scope.launch {
            try {
                showLoading(t("editor.loading.block"))
                
                val blockResult = blockRepository.getBlockByUuid(blockUuid).first()
                blockResult.getOrNull()?.let { block ->
                    // Set block content using text operations
                    val focusedBlockId = _editorState.value.metadata["blockUuid"]
                    if (focusedBlockId != null) {
                        val currentContent = currentTextOperations.getText(focusedBlockId).getOrNull() ?: ""
                        currentTextOperations.deleteText(focusedBlockId, TextRange(0, currentContent.length))
                        currentTextOperations.insertText(focusedBlockId, block.content)
                    } else {
                        // If no block was focused, we are loading a new one.
                    }
                    
                    updateEditorState { 
                        it
                            .withDocumentInfo(
                                filePath = null,
                                title = "Block: ${block.uuid.take(8)}",
                                lastModifiedAt = block.updatedAt
                            )
                            .withMetadata("blockUuid", block.uuid)
                            .withUnsavedChanges(false)
                    }
                    
                    // Now populate content
                    val currentContent = currentTextOperations.getText(block.uuid).getOrNull() ?: ""
                    if (currentContent != block.content) {
                         currentTextOperations.deleteText(block.uuid, TextRange(0, currentContent.length))
                         currentTextOperations.insertText(block.uuid, block.content)
                    }

                    logger.debug("Loaded block: $blockUuid")
                } ?: run {
                    handleError(EditorError.BlockNotFoundError("Block not found: $blockUuid"))
                }
                
                hideLoading()
            } catch (e: Exception) {
                hideLoading()
                handleError(EditorError.BlockLoadError("Failed to load block", e))
            }
        }
    }
    
    /**
     * Save current content to block.
     */
    fun saveToBlock() {
        scope.launch {
            try {
                val currentState = _editorState.value
                val blockUuid = currentState.metadata["blockUuid"]
                
                if (blockUuid != null) {
                    showLoading(t("editor.saving.block"))
                    
                    val content = currentTextOperations.getText(blockUuid).getOrNull() ?: ""
                    val block = blockRepository.getBlockByUuid(blockUuid).first().getOrNull()
                    
                    if (block != null) {
                        val updatedBlock = block.copy(
                            content = content,
                            updatedAt = Clock.System.now()
                        )
                        val result = blockRepository.saveBlock(updatedBlock)
                        
                        if (result.isRight()) {
                            updateEditorState { 
                                it
                                    .withUnsavedChanges(false)
                                    .withDocumentInfo(lastModifiedAt = updatedBlock.updatedAt)
                            }
                            logger.debug("Saved block: $blockUuid")
                        } else {
                            handleError(EditorError.BlockSaveError("Failed to save block", result.leftOrNull()?.let { RuntimeException(it.message) }))
                        }
                    } else {
                         handleError(EditorError.BlockNotFoundError("Block not found: $blockUuid"))
                    }
                    
                    hideLoading()
                } else {
                    handleError(EditorError.ValidationError("No block UUID associated with editor"))
                }
            } catch (e: Exception) {
                hideLoading()
                handleError(EditorError.BlockSaveError("Failed to save block", e))
            }
        }
    }
    
    /**
     * Create new block with current content.
     */
    fun createNewBlock(pageUuid: String) {
        scope.launch {
            try {
                val currentState = _editorState.value
                val currentBlockUuid = currentState.metadata["blockUuid"]
                val content = if (currentBlockUuid != null) {
                    currentTextOperations.getText(currentBlockUuid).getOrNull() ?: ""
                } else ""
                
                val newUuid = UuidGenerator.generateV7()
                val now = Clock.System.now()
                val newBlock = Block(
                    uuid = newUuid,
                    pageUuid = pageUuid,
                    content = content,
                    position = 0,
                    createdAt = now,
                    updatedAt = now
                )
                
                val result = blockRepository.saveBlock(newBlock)
                
                if (result.isRight()) {
                    updateEditorState { 
                        it
                            .withDocumentInfo(
                                filePath = null,
                                title = "Block: ${newBlock.uuid.take(8)}",
                                lastModifiedAt = newBlock.updatedAt
                            )
                            .withMetadata("blockUuid", newBlock.uuid)
                            .withUnsavedChanges(false)
                    }
                    logger.debug("Created new block: ${newBlock.uuid}")
                } else {
                    handleError(EditorError.BlockCreateError("Failed to create block", result.leftOrNull()?.let { RuntimeException(it.message) }))
                }
            } catch (e: Exception) {
                handleError(EditorError.BlockCreateError("Failed to create block", e))
            }
        }
    }
    
    // ===== ERROR HANDLING =====
    
    private fun handleError(error: EditorError) {
        _errorState.value = error
        logger.error("Editor error: ${error.message}", error.cause)
        
        // Auto-clear error after a delay
        scope.launch {
            kotlinx.coroutines.delay(5000)
            _errorState.value = null
        }
    }
    
    fun clearError() {
        _errorState.value = null
    }
    
    private fun setupErrorMonitoring() {
        scope.launch {
            _editorState
                .map { it.errorMessage }
                .distinctUntilChanged()
                .filter { it != null }
                .collect { errorMessage ->
                    if (errorMessage != null) {
                        handleError(EditorError.UiError(errorMessage))
                    }
                }
        }
    }
    
    // ===== AUTO-SAVE FUNCTIONALITY =====
    
    private fun setupAutoSaveMonitoring() {
        scope.launch {
            _editorState
                .map { it.shouldAutoSave }
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    triggerAutoSave()
                }
        }
    }
    
    private fun triggerAutoSave() {
        val currentState = _editorState.value
        
        if (!currentState.config.autoSave || !currentState.hasUnsavedChanges) {
            return
        }
        
        // Cancel any existing auto-save job
        autoSaveJob?.cancel()
        
        // Schedule new auto-save
        autoSaveJob = scope.launch {
            _autoSaveStatus.value = AutoSaveStatus.Pending
            
            try {
                kotlinx.coroutines.delay(currentState.config.autoSaveInterval)
                
                _autoSaveStatus.value = AutoSaveStatus.Saving
                
                val blockUuid = currentState.metadata["blockUuid"]
                if (blockUuid != null) {
                    val content = currentTextOperations.getText(blockUuid).getOrNull() ?: ""
                    val block = blockRepository.getBlockByUuid(blockUuid).first().getOrNull()
                    
                    if (block != null) {
                        val updatedBlock = block.copy(
                            content = content,
                            updatedAt = Clock.System.now()
                        )
                        val result = blockRepository.saveBlock(updatedBlock)
                        
                        if (result.isRight()) {
                            updateEditorState { 
                                it
                                    .withUnsavedChanges(false)
                                    .withDocumentInfo(lastModifiedAt = updatedBlock.updatedAt)
                            }
                            _autoSaveStatus.value = AutoSaveStatus.Success
                            logger.debug("Auto-saved block: $blockUuid")
                        } else {
                            _autoSaveStatus.value = AutoSaveStatus.Error(result.leftOrNull()?.message ?: "Unknown error")
                        }
                    } else {
                        _autoSaveStatus.value = AutoSaveStatus.Error("Block not found")
                    }
                } else {
                    _autoSaveStatus.value = AutoSaveStatus.Error("No block associated with editor")
                }
                
                // Clear status after a delay
                kotlinx.coroutines.delay(2000)
                _autoSaveStatus.value = AutoSaveStatus.Idle
                
            } catch (e: Exception) {
                _autoSaveStatus.value = AutoSaveStatus.Error(e.message ?: "Unknown error")
                logger.error("Auto-save failed: ${e.message}")
                
                // Clear status after a delay
                kotlinx.coroutines.delay(2000)
                _autoSaveStatus.value = AutoSaveStatus.Idle
            }
        }
    }
    
    // ===== IME INTEGRATION =====
    
    private fun setupImeIntegration() {
        scope.launch {
            imeManager.compositionState
                .collect { compositionState ->
                    updateEditorState { currentState ->
                        currentState.withImeComposition(
                            isComposing = compositionState.isComposing,
                            compositionText = compositionState.preeditText,
                            compositionRange = compositionState.let { state ->
                                if (state.isComposing) {
                                    dev.stapler.stelekit.editor.text.TextRange(
                                        state.position,
                                        state.position + state.preeditText.length - 1
                                    )
                                } else null
                            }
                        )
                    }
                }
        }
    }
    
    // ===== KEYBOARD HANDLING =====
    
    /**
     * Handle keyboard event.
     */
    fun handleKeyboardEvent(
        key: String,
        modifiers: Set<dev.stapler.stelekit.editor.input.KeyModifier> = emptySet()
    ): Boolean {
        return keyboardEventHandler.handleKeyPress(key, modifiers)
    }
    
    // ===== UTILITY METHODS =====
    
    private fun updateEditorState(transform: (EditorState) -> EditorState) {
        _editorState.value = transform(_editorState.value)
    }
    
    /**
     * Get current editor statistics.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun getEditorStatistics(): Flow<EditorStatistics> {
        return _editorState.flatMapLatest { state ->
            val blockUuid = state.metadata["blockUuid"]
            val textStateFlow = if (blockUuid != null) {
                currentTextOperations.getTextState(blockUuid)
            } else {
                flowOf(TextState())
            }
            
            textStateFlow.map { textState ->
                EditorStatistics(
                    characterCount = textState.content.length,
                    wordCount = textState.content.split(WHITESPACE_REGEX).filter { it.isNotBlank() }.size,
                    lineCount = textState.content.split('\n').size,
                    hasUnsavedChanges = state.hasUnsavedChanges,
                    isFocused = state.isFocused,
                    isComposing = state.isImeComposing,
                    canUndo = textState.canUndo,
                    canRedo = textState.canRedo,
                    lastModified = state.lastModifiedAt,
                    autoSaveEnabled = state.config.autoSave
                )
            }
        }
    }
    
    /**
     * Cleanup resources.
     */
    fun dispose() {
        autoSaveJob?.cancel()
        logger.debug("EditorViewModel disposed")
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}

// ===== DATA CLASSES =====

/**
 * Auto-save status information.
 */
sealed class AutoSaveStatus {
    object Idle : AutoSaveStatus()
    object Pending : AutoSaveStatus()
    object Saving : AutoSaveStatus()
    object Success : AutoSaveStatus()
    data class Error(val message: String) : AutoSaveStatus()
}

/**
 * Editor error information.
 */
sealed class EditorError(
    open val message: String,
    open val cause: Throwable? = null
) {
    data class TextOperationError(
        override val message: String,
        override val cause: Throwable? = null
    ) : EditorError(message, cause)
    
    data class SearchError(
        override val message: String,
        override val cause: Throwable? = null
    ) : EditorError(message, cause)
    
    data class UndoRedoError(
        override val message: String,
        override val cause: Throwable? = null
    ) : EditorError(message, cause)
    
    data class BlockNotFoundError(
        override val message: String,
        override val cause: Throwable? = null
    ) : EditorError(message, cause)
    
    data class BlockLoadError(
        override val message: String,
        override val cause: Throwable? = null
    ) : EditorError(message, cause)
    
    data class BlockSaveError(
        override val message: String,
        override val cause: Throwable? = null
    ) : EditorError(message, cause)
    
    data class BlockCreateError(
        override val message: String,
        override val cause: Throwable? = null
    ) : EditorError(message, cause)
    
    data class ValidationError(
        override val message: String,
        override val cause: Throwable? = null
    ) : EditorError(message, cause)
    
    data class UiError(
        override val message: String,
        override val cause: Throwable? = null
    ) : EditorError(message, cause)
}

/**
 * Editor statistics information.
 */
data class EditorStatistics(
    val characterCount: Int,
    val wordCount: Int,
    val lineCount: Int,
    val hasUnsavedChanges: Boolean,
    val isFocused: Boolean,
    val isComposing: Boolean,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val lastModified: Instant?,
    val autoSaveEnabled: Boolean
)

// Extension function for i18n
private fun t(key: String, vararg _args: Any): String {
    return when (key) {
        "editor.loading.block" -> "Loading block..."
        "editor.saving.block" -> "Saving block..."
        else -> key
    }
}
