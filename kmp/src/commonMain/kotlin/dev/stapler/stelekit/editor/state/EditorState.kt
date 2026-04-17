package dev.stapler.stelekit.editor.state

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.editor.text.DefaultTextOperations
import dev.stapler.stelekit.editor.text.ITextOperations
import dev.stapler.stelekit.editor.text.TextState
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.performance.PerformanceMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Instant

/**
 * Configuration for the rich text editor.
 * Defines behavior, appearance, and functionality settings.
 */
data class EditorConfig(
    // ===== BEHAVIOR CONFIGURATION =====
    
    /**
     * Whether the editor is editable or read-only.
     */
    val isEditable: Boolean = true,
    
    /**
     * Whether to auto-save content changes.
     */
    val autoSave: Boolean = true,
    
    /**
     * Auto-save interval in milliseconds.
     */
    val autoSaveInterval: Long = 3000L, // 3 seconds
    
    /**
     * Maximum undo history size.
     */
    val maxUndoHistory: Int = 100,
    
    /**
     * Whether to enable spell checking.
     */
    val spellCheckEnabled: Boolean = true,
    
    /**
     * Whether to enable auto-completion.
     */
    val autoCompletionEnabled: Boolean = true,
    
    /**
     * Whether to enable smart quotes.
     */
    val smartQuotesEnabled: Boolean = true,
    
    /**
     * Whether to enable auto-indentation.
     */
    val autoIndentEnabled: Boolean = true,
    
    /**
     * Whether to enable word wrap.
     */
    val wordWrapEnabled: Boolean = true,
    
    /**
     * Tab size in spaces.
     */
    val tabSize: Int = 4,
    
    /**
     * Whether to use spaces instead of tabs.
     */
    val insertSpacesForTabs: Boolean = true,
    
    // ===== VISUAL CONFIGURATION =====
    
    /**
     * Font family name.
     */
    val fontFamily: String = "Inter",
    
    /**
     * Font size in sp.
     */
    val fontSize: Float = 14f,
    
    /**
     * Line height multiplier.
     */
    val lineHeight: Float = 1.5f,
    
    /**
     * Letter spacing multiplier.
     */
    val letterSpacing: Float = 0f,
    
    /**
     * Padding around the editor content.
     */
    val contentPadding: EditorPadding = EditorPadding(16, 16, 16, 16),
    
    /**
     * Whether to show line numbers.
     */
    val showLineNumbers: Boolean = false,
    
    /**
     * Whether to show the current line highlight.
     */
    val highlightCurrentLine: Boolean = true,
    
    /**
     * Whether to show the selection margin.
     */
    val showSelectionMargin: Boolean = false,
    
    /**
     * Theme configuration.
     */
    val theme: EditorTheme = EditorTheme.Default,
    
    // ===== INTERACTION CONFIGURATION =====
    
    /**
     * Keyboard shortcuts configuration.
     */
    val keyboardShortcuts: KeyboardShortcuts = KeyboardShortcuts.default(),
    
    /**
     * Whether to enable drag and drop.
     */
    val dragAndDropEnabled: Boolean = true,
    
    /**
     * Whether to enable touch gestures on mobile.
     */
    val touchGesturesEnabled: Boolean = true,
    
    /**
     * Double-tap behavior on mobile.
     */
    val doubleTapBehavior: DoubleTapBehavior = DoubleTapBehavior.SELECT_WORD,
    
    /**
     * Long-press behavior on mobile.
     */
    val longPressBehavior: LongPressBehavior = LongPressBehavior.SHOW_CONTEXT_MENU,
    
    // ===== PERFORMANCE CONFIGURATION =====
    
    /**
     * Maximum content length before performance warnings.
     */
    val maxContentLength: Int = 1_000_000,
    
    /**
     * Whether to enable virtual scrolling for large documents.
     */
    val virtualScrollingEnabled: Boolean = false,
    
    /**
     * Virtual scroll buffer size (lines before/after viewport).
     */
    val virtualScrollBufferSize: Int = 50,
    
    /**
     * Whether to debounce text input events.
     */
    val debounceTextInput: Boolean = true,
    
    /**
     * Text input debounce delay in milliseconds.
     */
    val textInputDebounceDelay: Long = 100L,
    
    // ===== ACCESSIBILITY CONFIGURATION =====
    
    /**
     * Whether to enable screen reader support.
     */
    val screenReaderEnabled: Boolean = true,
    
    /**
     * Whether to provide high contrast mode.
     */
    val highContrastMode: Boolean = false,
    
    /**
     * Content description for accessibility.
     */
    val accessibilityDescription: String = "Text editor",
    
    // ===== LOGGING AND DEBUGGING =====
    
    /**
     * Whether to enable performance tracing.
     */
    val performanceTracingEnabled: Boolean = true,
    
    /**
     * Whether to log all text operations.
     */
    val logTextOperations: Boolean = false,
    
    /**
     * Minimum operation duration to log (in milliseconds).
     */
    val minOperationLogDuration: Long = 50L
) {
    init {
        require(autoSaveInterval > 0) { "Auto-save interval must be positive" }
        require(maxUndoHistory > 0) { "Max undo history must be positive" }
        require(tabSize > 0) { "Tab size must be positive" }
        require(fontSize > 0) { "Font size must be positive" }
        require(lineHeight > 0) { "Line height must be positive" }
        require(maxContentLength > 0) { "Max content length must be positive" }
        require(virtualScrollBufferSize >= 0) { "Virtual scroll buffer size must be non-negative" }
        require(textInputDebounceDelay >= 0) { "Text input debounce delay must be non-negative" }
        require(minOperationLogDuration >= 0) { "Min operation log duration must be non-negative" }
    }
}

/**
 * Editor modes
 */
enum class EditorMode {
    VIEW,       // Read-only mode
    EDIT,       // Standard editing
    COMMAND,    // Command palette active
    SEARCH,     // Search mode
    PREVIEW     // Markdown preview
}

/**
 * Padding configuration for the editor.
 */
data class EditorPadding(
    val start: Int,
    val top: Int,
    val end: Int,
    val bottom: Int
) {
    /**
     * Returns padding as a PaddingValues object for Compose.
     */
    fun toComposePaddingValues() = androidx.compose.foundation.layout.PaddingValues(
        start = start.dp,
        top = top.dp,
        end = end.dp,
        bottom = bottom.dp
    )
}

/**
 * Editor theme configuration.
 */
sealed class EditorTheme {
    object Default : EditorTheme()
    object Light : EditorTheme()
    object Dark : EditorTheme()
    object Gruvbox : EditorTheme()
    object Sepia : EditorTheme()
    data class Custom(
        val backgroundColor: String,
        val textColor: String,
        val selectionColor: String,
        val cursorColor: String,
        val lineNumberColor: String
    ) : EditorTheme()
}

/**
 * Keyboard shortcuts configuration.
 */
data class KeyboardShortcuts(
    val undo: String = "Ctrl+Z",
    val redo: String = "Ctrl+Y",
    val cut: String = "Ctrl+X",
    val copy: String = "Ctrl+C",
    val paste: String = "Ctrl+V",
    val selectAll: String = "Ctrl+A",
    val find: String = "Ctrl+F",
    val replace: String = "Ctrl+H",
    val save: String = "Ctrl+S"
) {
    companion object {
        fun default() = KeyboardShortcuts()
        
        fun emacs() = KeyboardShortcuts(
            undo = "Ctrl+/",
            redo = "Ctrl+/",
            cut = "Ctrl+W",
            copy = "Alt+W",
            paste = "Ctrl+Y",
            selectAll = "Ctrl+X Ctrl+A",
            find = "Ctrl+S",
            replace = "Ctrl+%",
            save = "Ctrl+X Ctrl+S"
        )
        
        fun vim() = KeyboardShortcuts(
            undo = "u",
            redo = "Ctrl+R",
            cut = "d",
            copy = "y",
            paste = "p",
            selectAll = "gg VG",
            find = "/",
            replace = ":%s//",
            save = ":w"
        )
    }
}

/**
 * Double-tap behavior configuration.
 */
enum class DoubleTapBehavior {
    SELECT_WORD,
    SELECT_LINE,
    SELECT_PARAGRAPH,
    NO_ACTION
}

/**
 * Long-press behavior configuration.
 */
enum class LongPressBehavior {
    SHOW_CONTEXT_MENU,
    SELECT_WORD,
    START_SELECTION,
    NO_ACTION
}

/**
 * Comprehensive state for the rich text editor.
 * Combines text operations, configuration, and UI state.
 */
data class EditorState(
    // ===== TEXT AND SELECTION STATE =====
    
    /**
     * Text operations interface for content manipulation.
     */
    val textOperations: ITextOperations,

    val blocks: List<Block> = emptyList(),
    val isEditing: Boolean = false,
    val selectedBlocks: Set<String> = emptySet(),
    
    /**
     * Current editor configuration.
     */
    val config: EditorConfig = EditorConfig(),

    /**
     * Current editor mode.
     */
    val mode: EditorMode = EditorMode.EDIT,
    
    // ===== UI STATE =====
    
    /**
     * Whether the editor currently has focus.
     */
    val isFocused: Boolean = false,
    
    /**
     * Whether the editor is currently loading.
     */
    val isLoading: Boolean = false,
    
    /**
     * Whether there are unsaved changes.
     */
    val hasUnsavedChanges: Boolean = false,
    
    /**
     * Current loading status message.
     */
    val loadingMessage: String? = null,
    
    /**
     * Current error message, if any.
     */
    val errorMessage: String? = null,
    
    /**
     * Whether the context menu is currently visible.
     */
    val isContextMenuVisible: Boolean = false,
    
    /**
     * Position for the context menu, if visible.
     */
    val contextMenuPosition: EditorPosition? = null,
    
    /**
     * Whether the find/replace dialog is visible.
     */
    val isFindReplaceVisible: Boolean = false,
    
    /**
     * Current find text.
     */
    val findText: String = "",
    
    /**
     * Current replace text.
     */
    val replaceText: String = "",
    
    /**
     * Whether search is case sensitive.
     */
    val findCaseSensitive: Boolean = false,
    
    /**
     * Whether search matches whole words only.
     */
    val findWholeWord: Boolean = false,
    
    // ===== IME STATE =====
    
    /**
     * Whether IME composition is currently active.
     */
    val isImeComposing: Boolean = false,
    
    /**
     * Current IME composition text.
     */
    val imeCompositionText: String = "",
    
    /**
     * Current IME composition range.
     */
    val imeCompositionRange: dev.stapler.stelekit.editor.text.TextRange? = null,
    
    // ===== VIEWPORT STATE =====
    
    /**
     * Current viewport information.
     */
    val viewport: ViewportInfo = ViewportInfo(),
    
    /**
     * Current scroll position.
     */
    val scrollPosition: EditorPosition = EditorPosition(0f, 0f),
    
    /**
     * Current zoom level (1.0 = 100%).
     */
    val zoomLevel: Float = 1f,
    
    // ===== METADATA =====
    
    /**
     * File path for the current document (if applicable).
     */
    val filePath: String? = null,
    
    /**
     * Document title.
     */
    val documentTitle: String = "Untitled",
    
    /**
     * Last save timestamp.
     */
    val lastSavedAt: Instant? = null,
    
    /**
     * Last modified timestamp.
     */
    val lastModifiedAt: Instant? = null,
    
    /**
     * Document metadata.
     */
    val metadata: Map<String, String> = emptyMap()
) {
    /**
     * Whether the editor is ready for interaction.
     */
    val isReady: Boolean get() = !isLoading && errorMessage == null
    
    /**
     * Whether auto-save should be triggered.
     */
    val shouldAutoSave: Boolean get() = config.autoSave && hasUnsavedChanges && isReady
    
    // ===== STATE TRANSFORMATION METHODS =====
    
    /**
     * Returns a new EditorState with the specified text operations.
     */
    fun withTextOperations(textOperations: ITextOperations): EditorState {
        return copy(textOperations = textOperations)
    }
    
    /**
     * Returns a new EditorState with the specified configuration.
     */
    fun withConfig(config: EditorConfig): EditorState {
        return copy(config = config)
    }

    /**
     * Returns a new EditorState with the specified mode.
     */
    fun withMode(mode: EditorMode): EditorState {
        return copy(mode = mode)
    }
    
    /**
     * Returns a new EditorState with the specified focus state.
     */
    fun withFocus(isFocused: Boolean): EditorState {
        return copy(isFocused = isFocused)
    }
    
    /**
     * Returns a new EditorState with the specified loading state.
     */
    fun withLoading(isLoading: Boolean, message: String? = null): EditorState {
        return copy(isLoading = isLoading, loadingMessage = if (isLoading) message else null)
    }
    
    /**
     * Returns a new EditorState with the specified error message.
     */
    fun withError(errorMessage: String?): EditorState {
        return copy(errorMessage = errorMessage, isLoading = false)
    }
    
    /**
     * Returns a new EditorState with the specified unsaved changes state.
     */
    fun withUnsavedChanges(hasUnsavedChanges: Boolean): EditorState {
        return copy(hasUnsavedChanges = hasUnsavedChanges)
    }
    
    /**
     * Returns a new EditorState with the specified context menu state.
     */
    fun withContextMenu(isVisible: Boolean, position: EditorPosition? = null): EditorState {
        return copy(isContextMenuVisible = isVisible, contextMenuPosition = if (isVisible) position else null)
    }
    
    /**
     * Returns a new EditorState with the specified find/replace state.
     */
    fun withFindReplace(
        isVisible: Boolean,
        findText: String = this.findText,
        replaceText: String = this.replaceText,
        caseSensitive: Boolean = this.findCaseSensitive,
        wholeWord: Boolean = this.findWholeWord
    ): EditorState {
        return copy(
            isFindReplaceVisible = isVisible,
            findText = findText,
            replaceText = replaceText,
            findCaseSensitive = caseSensitive,
            findWholeWord = wholeWord
        )
    }
    
    /**
     * Returns a new EditorState with the specified IME composition state.
     */
    fun withImeComposition(
        isComposing: Boolean,
        compositionText: String = "",
        compositionRange: dev.stapler.stelekit.editor.text.TextRange? = null
    ): EditorState {
        return copy(
            isImeComposing = isComposing,
            imeCompositionText = if (isComposing) compositionText else "",
            imeCompositionRange = if (isComposing) compositionRange else null
        )
    }
    
    /**
     * Returns a new EditorState with the specified viewport information.
     */
    fun withViewport(viewport: ViewportInfo): EditorState {
        return copy(viewport = viewport)
    }
    
    /**
     * Returns a new EditorState with the specified scroll position.
     */
    fun withScrollPosition(position: EditorPosition): EditorState {
        return copy(scrollPosition = position)
    }
    
    /**
     * Returns a new EditorState with the specified zoom level.
     */
    fun withZoomLevel(zoomLevel: Float): EditorState {
        return copy(zoomLevel = zoomLevel.coerceIn(0.5f, 3.0f))
    }
    
    /**
     * Returns a new EditorState with the specified document information.
     */
    fun withDocumentInfo(
        filePath: String? = this.filePath,
        title: String = this.documentTitle,
        lastSavedAt: Instant? = this.lastSavedAt,
        lastModifiedAt: Instant? = this.lastModifiedAt
    ): EditorState {
        return copy(
            filePath = filePath,
            documentTitle = title,
            lastSavedAt = lastSavedAt,
            lastModifiedAt = lastModifiedAt
        )
    }
    
    /**
     * Returns a new EditorState with updated metadata.
     */
    fun withMetadata(key: String, value: String): EditorState {
        val newMetadata = metadata.toMutableMap().apply { put(key, value) }
        return copy(metadata = newMetadata)
    }
    
    /**
     * Returns a new EditorState with metadata removed.
     */
    fun withoutMetadata(key: String): EditorState {
        val newMetadata = metadata.toMutableMap().apply { remove(key) }
        return copy(metadata = newMetadata)
    }
    
    companion object {
        /**
         * Creates an empty EditorState with default configuration.
         */
        fun empty(scope: CoroutineScope): EditorState {
            val textOperations = DefaultTextOperations(scope)
            return EditorState(
                textOperations = textOperations,
                config = EditorConfig()
            )
        }
        
        /**
         * Creates an EditorState with initial content.
         */
        fun withContent(content: String, scope: CoroutineScope): EditorState {
            val textOperations = DefaultTextOperations(scope)
            return EditorState(
                textOperations = textOperations,
                config = EditorConfig(),
                hasUnsavedChanges = content.isNotEmpty()
            )
        }
    }
}

/**
 * Position information within the editor.
 */
data class EditorPosition(
    val x: Float,
    val y: Float
)

/**
 * Viewport information for the editor.
 */
data class ViewportInfo(
    val width: Float = 0f,
    val height: Float = 0f,
    val contentHeight: Float = 0f,
    val visibleLineStart: Int = 0,
    val visibleLineEnd: Int = 0,
    val firstVisibleLine: Int = 0,
    val lastVisibleLine: Int = 0
) {
    /**
     * Whether the viewport is valid (has dimensions).
     */
    val isValid: Boolean get() = width > 0 && height > 0
    
    /**
     * Number of visible lines.
     */
    val visibleLineCount: Int get() = visibleLineEnd - visibleLineStart + 1
}

/**
 * Manager for EditorState with reactive updates and lifecycle management.
 */
class EditorStateManager(
    private val scope: CoroutineScope,
    initialState: EditorState = EditorState.empty(scope)
) {
    private val logger = Logger("EditorStateManager")
    private val traceId = PerformanceMonitor.startTrace("editor-state-manager-init")
    
    private val _editorState = MutableStateFlow(initialState)
    val editorState: StateFlow<EditorState> = _editorState.asStateFlow()
    
    // Exposed convenience flows
    // Note: Text state is block-specific in ITextOperations. 
    // This convenience flow would need a current block context or be removed.
    // Removing for now to avoid compilation errors, callers should use textOperations directly with block ID.
    // val textState: StateFlow<dev.stapler.stelekit.editor.text.TextState> = 
    //    editorState.map { it.textOperations.textState.value }
    
    val config: StateFlow<EditorConfig> = editorState.map { it.config }.stateIn(scope, SharingStarted.Eagerly, initialState.config)
    val isFocused: StateFlow<Boolean> = editorState.map { it.isFocused }.stateIn(scope, SharingStarted.Eagerly, initialState.isFocused)
    val isLoading: StateFlow<Boolean> = editorState.map { it.isLoading }.stateIn(scope, SharingStarted.Eagerly, initialState.isLoading)
    val hasUnsavedChanges: StateFlow<Boolean> = editorState.map { it.hasUnsavedChanges }.stateIn(scope, SharingStarted.Eagerly, initialState.hasUnsavedChanges)
    val isReady: StateFlow<Boolean> = editorState.map { it.isReady }.stateIn(scope, SharingStarted.Eagerly, initialState.isReady)
    
    init {
        PerformanceMonitor.endTrace(traceId)
        
        // Watch for text changes to update unsaved state
        // Commenting out until we have a way to observe global text state or specific block
        /*
        scope.launch {
            combine(
                textState.map { it.version },
                textState.map { it.lastModified }
            ) { version, lastModified -> 
                version to lastModified
            }.collect { (version, lastModified) ->
                updateState { it.withUnsavedChanges(true) }
            }
        }
        */
    }
    
    /**
     * Update the editor state with the provided transformation.
     */
    fun updateState(transform: (EditorState) -> EditorState) {
        val currentState = _editorState.value
        val newState = transform(currentState)
        _editorState.value = newState
        
        if (currentState.config.logTextOperations) {
            logger.debug("State updated: ${currentState.config}")
        }
    }
    
    /**
     * Set the text operations for the editor.
     */
    fun setTextOperations(textOperations: ITextOperations) {
        updateState { it.withTextOperations(textOperations) }
    }
    
    /**
     * Update the editor configuration.
     */
    fun updateConfig(config: EditorConfig) {
        updateState { it.withConfig(config) }
    }

    /**
     * Update the editor mode.
     */
    fun setMode(mode: EditorMode) {
        updateState { it.withMode(mode) }
    }
    
    /**
     * Set focus state.
     */
    fun setFocus(isFocused: Boolean) {
        updateState { it.withFocus(isFocused) }
    }
    
    /**
     * Set loading state with optional message.
     */
    fun setLoading(isLoading: Boolean, message: String? = null) {
        updateState { it.withLoading(isLoading, message) }
    }
    
    /**
     * Set error message.
     */
    fun setError(errorMessage: String?) {
        updateState { it.withError(errorMessage) }
    }
    
    /**
     * Clear any current error.
     */
    fun clearError() {
        updateState { it.withError(null) }
    }
    
    /**
     * Mark changes as saved (clear unsaved flag).
     */
    fun markAsSaved() {
        val now = kotlin.time.Clock.System.now()
        updateState { 
            it
                .withUnsavedChanges(false)
                .withDocumentInfo(lastSavedAt = now, lastModifiedAt = now)
        }
    }
    
    /**
     * Show/hide context menu.
     */
    fun setContextMenu(isVisible: Boolean, position: EditorPosition? = null) {
        updateState { it.withContextMenu(isVisible, position) }
    }
    
    /**
     * Show/hide find/replace dialog.
     */
    fun setFindReplace(
        isVisible: Boolean,
        findText: String = "",
        replaceText: String = "",
        caseSensitive: Boolean = false,
        wholeWord: Boolean = false
    ) {
        updateState { it.withFindReplace(isVisible, findText, replaceText, caseSensitive, wholeWord) }
    }
    
    /**
     * Update IME composition state.
     */
    fun setImeComposition(
        isComposing: Boolean,
        compositionText: String = "",
        compositionRange: dev.stapler.stelekit.editor.text.TextRange? = null
    ) {
        updateState { it.withImeComposition(isComposing, compositionText, compositionRange) }
    }
    
    /**
     * Update viewport information.
     */
    fun setViewport(viewport: ViewportInfo) {
        updateState { it.withViewport(viewport) }
    }
    
    /**
     * Update scroll position.
     */
    fun setScrollPosition(position: EditorPosition) {
        updateState { it.withScrollPosition(position) }
    }
    
    /**
     * Update zoom level.
     */
    fun setZoomLevel(zoomLevel: Float) {
        updateState { it.withZoomLevel(zoomLevel) }
    }
    
    /**
     * Update document information.
     */
    fun setDocumentInfo(
        filePath: String? = null,
        title: String = "Untitled",
        lastSavedAt: Instant? = null,
        lastModifiedAt: Instant? = null
    ) {
        updateState { it.withDocumentInfo(filePath, title, lastSavedAt, lastModifiedAt) }
    }
    
    /**
     * Update metadata.
     */
    fun setMetadata(key: String, value: String) {
        updateState { it.withMetadata(key, value) }
    }
    
    /**
     * Remove metadata.
     */
    fun removeMetadata(key: String) {
        updateState { it.withoutMetadata(key) }
    }
    
    /**
     * Reset state to initial values.
     */
    fun reset() {
        _editorState.value = EditorState.empty(scope)
    }
    
    /**
     * Get current text operations for direct manipulation.
     */
    fun getTextOperations(): ITextOperations {
        return _editorState.value.textOperations
    }
}