package dev.stapler.stelekit.editor.input

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stapler.stelekit.editor.state.EditorState
import dev.stapler.stelekit.editor.state.EditorConfig
import dev.stapler.stelekit.editor.text.ITextOperations
import dev.stapler.stelekit.editor.text.TextRange
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.performance.PerformanceMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Instant

// Helper function for i18n
private fun t(key: String, vararg args: Any): String {
    // Simple placeholder implementation
    return key.split('.').last().replaceFirstChar { it.uppercase() } + (if (args.isNotEmpty()) " " + args.joinToString(" ") else "")
}

/**
 * IME (Input Method Editor) composition state manager.
 * Handles international text input composition for languages like Chinese, Japanese, Korean.
 */
class ImeCompositionManager(
    private val scope: CoroutineScope
) {
    private val logger = Logger("ImeCompositionManager")
    
    private val _compositionState = MutableStateFlow(ImeCompositionState())
    val compositionState: StateFlow<ImeCompositionState> = _compositionState.asStateFlow()
    
    private val _compositionHistory = MutableStateFlow<List<ImeCompositionEvent>>(emptyList())
    val compositionHistory: StateFlow<List<ImeCompositionEvent>> = _compositionHistory.asStateFlow()
    
    /**
     * Start IME composition at the specified position.
     */
    fun startComposition(
        position: Int,
        preeditText: String = ""
    ) {
        val traceId = PerformanceMonitor.startTrace("ime-start-composition")
        
        try {
            val newState = ImeCompositionState(
                isComposing = true,
                position = position,
                preeditText = preeditText,
                originalText = "",
                cursorOffset = preeditText.length,
                targetStart = 0,
                targetEnd = preeditText.length,
                startTime = kotlin.time.Clock.System.now()
            )
            
            _compositionState.value = newState
            addToHistory(ImeCompositionEvent.Start(position, preeditText))
            
            logger.debug("Started IME composition at position $position with text: '$preeditText'")
        } finally {
            PerformanceMonitor.endTrace(traceId)
        }
    }
    
    /**
     * Update IME composition with new preedit text.
     */
    fun updateComposition(
        preeditText: String,
        cursorOffset: Int = preeditText.length,
        targetStart: Int = 0,
        targetEnd: Int = preeditText.length
    ) {
        val traceId = PerformanceMonitor.startTrace("ime-update-composition")
        
        try {
            val currentState = _compositionState.value
            if (currentState.isComposing) {
                val newState = currentState.copy(
                    preeditText = preeditText,
                    cursorOffset = cursorOffset,
                    targetStart = targetStart,
                    targetEnd = targetEnd
                )
                
                _compositionState.value = newState
                addToHistory(ImeCompositionEvent.Update(preeditText, cursorOffset, targetStart, targetEnd))
                
                logger.debug("Updated IME composition: '$preeditText' (cursor: $cursorOffset)")
            }
        } finally {
            PerformanceMonitor.endTrace(traceId)
        }
    }
    
    /**
     * Commit IME composition text.
     */
    fun commitComposition(committedText: String) {
        val traceId = PerformanceMonitor.startTrace("ime-commit-composition")
        
        try {
            val currentState = _compositionState.value
            if (currentState.isComposing) {
                val finalState = currentState.copy(
                    isComposing = false,
                    committedText = committedText,
                    endTime = kotlin.time.Clock.System.now()
                )
                
                _compositionState.value = finalState
                addToHistory(ImeCompositionEvent.Commit(committedText))
                
                logger.debug("Committed IME composition: '$committedText'")
                
                // Clear composition after a short delay to allow for final processing
                scope.launch {
                    delay(100)
                    _compositionState.value = ImeCompositionState()
                }
            }
        } finally {
            PerformanceMonitor.endTrace(traceId)
        }
    }
    
    /**
     * Cancel IME composition.
     */
    fun cancelComposition() {
        val traceId = PerformanceMonitor.startTrace("ime-cancel-composition")
        
        try {
            val currentState = _compositionState.value
            if (currentState.isComposing) {
                val cancelState = currentState.copy(
                    isComposing = false,
                    cancelled = true,
                    endTime = kotlin.time.Clock.System.now()
                )
                
                _compositionState.value = cancelState
                addToHistory(ImeCompositionEvent.Cancel)
                
                logger.debug("Cancelled IME composition")
                
                // Clear composition after a short delay
                scope.launch {
                    delay(100)
                    _compositionState.value = ImeCompositionState()
                }
            }
        } finally {
            PerformanceMonitor.endTrace(traceId)
        }
    }
    
    /**
     * Check if composition is currently active.
     */
    fun isComposing(): Boolean = _compositionState.value.isComposing
    
    /**
     * Get current composition text.
     */
    fun getCurrentCompositionText(): String = _compositionState.value.preeditText
    
    /**
     * Get composition range in the original text.
     */
    fun getCompositionRange(): TextRange? {
        val state = _compositionState.value
        return if (state.isComposing) {
            TextRange(state.position, state.position + state.preeditText.length - 1)
        } else null
    }
    
    private fun addToHistory(event: ImeCompositionEvent) {
        val currentHistory = _compositionHistory.value
        val newHistory = currentHistory + event
        _compositionHistory.value = newHistory.takeLast(50) // Keep last 50 events
    }
}

/**
 * Current IME composition state.
 */
data class ImeCompositionState(
    val isComposing: Boolean = false,
    val position: Int = 0,
    val preeditText: String = "",
    val originalText: String = "",
    val committedText: String = "",
    val cursorOffset: Int = 0,
    val targetStart: Int = 0,
    val targetEnd: Int = 0,
    val cancelled: Boolean = false,
    val startTime: Instant = kotlin.time.Instant.DISTANT_PAST,
    val endTime: Instant = kotlin.time.Instant.DISTANT_PAST
) {
    /**
     * The effective cursor position in the composed text.
     */
    val effectiveCursorPosition: Int get() = position + cursorOffset
    
    /**
     * The duration of the composition process.
     */
    val duration: Long get() = if (startTime != kotlin.time.Instant.DISTANT_PAST && endTime != kotlin.time.Instant.DISTANT_PAST) {
        (endTime - startTime).inWholeMilliseconds
    } else 0L
}

/**
 * IME composition events for history tracking.
 */
sealed class ImeCompositionEvent {
    data class Start(val position: Int, val preeditText: String) : ImeCompositionEvent()
    data class Update(
        val preeditText: String,
        val cursorOffset: Int,
        val targetStart: Int,
        val targetEnd: Int
    ) : ImeCompositionEvent()
    data class Commit(val committedText: String) : ImeCompositionEvent()
    object Cancel : ImeCompositionEvent()
}

/**
 * Keyboard event handler for rich text editor.
 * Provides comprehensive keyboard shortcut support and event management.
 */
class KeyboardEventHandler(
    private val editorStateProvider: () -> EditorState,
    private val onEditorStateChange: (EditorState) -> Unit,
    private val onExecuteCommand: (String) -> Unit,
    private val scope: CoroutineScope
) {
    private val logger = Logger("KeyboardEventHandler")
    
    private val _keyEventHistory = MutableStateFlow<List<KeyboardEvent>>(emptyList())
    val keyEventHistory: StateFlow<List<KeyboardEvent>> = _keyEventHistory.asStateFlow()
    
    /**
     * Handle key press event.
     */
    fun handleKeyPress(
        key: String,
        modifiers: Set<KeyModifier> = emptySet(),
        timestamp: Instant = kotlin.time.Clock.System.now()
    ): Boolean {
        val traceId = PerformanceMonitor.startTrace("keyboard-handle-key")
        
        return try {
            val event = KeyboardEvent.KeyPress(key, modifiers, timestamp)
            addToHistory(event)
            
            val currentState = editorStateProvider()
            val textOperations = currentState.textOperations
            val blockId = currentState.selectedBlocks.firstOrNull() ?: currentState.metadata["blockUuid"] ?: ""
            
            // Check for registered shortcuts
            when {
                // Text navigation shortcuts
                key == "ArrowLeft" && modifiers.contains(KeyModifier.CONTROL) -> {
                    if (blockId.isNotEmpty()) {
                        scope.launch { textOperations.moveCursorToWordStart(blockId) }
                    }
                    true
                }
                
                key == "ArrowRight" && modifiers.contains(KeyModifier.CONTROL) -> {
                    if (blockId.isNotEmpty()) {
                        scope.launch { textOperations.moveCursorToWordEnd(blockId) }
                    }
                    true
                }
                
                key == "Home" && !modifiers.contains(KeyModifier.CONTROL) -> {
                    if (blockId.isNotEmpty()) {
                        scope.launch { textOperations.moveCursorToLineStart(blockId) }
                    }
                    true
                }
                
                key == "End" && !modifiers.contains(KeyModifier.CONTROL) -> {
                    if (blockId.isNotEmpty()) {
                        scope.launch { textOperations.moveCursorToLineEnd(blockId) }
                    }
                    true
                }
                
                key == "Home" && modifiers.contains(KeyModifier.CONTROL) -> {
                    if (blockId.isNotEmpty()) {
                        scope.launch { textOperations.setCursor(blockId, 0) }
                    }
                    true
                }
                
                key == "End" && modifiers.contains(KeyModifier.CONTROL) -> {
                    if (blockId.isNotEmpty()) {
                        // We need content length. Assuming textOperations can handle it or we get it from state
                        // But textOperations.setCursor takes position.
                        // We can get content from textOperations.getTextState(blockId).value.content.length
                        scope.launch { 
                            val length = textOperations.getTextState(blockId).value.content.length
                            textOperations.setCursor(blockId, length) 
                        }
                    }
                    true
                }
                
                // Selection shortcuts
                key == "a" && modifiers.contains(KeyModifier.CONTROL) -> {
                    if (blockId.isNotEmpty()) {
                        scope.launch { textOperations.selectAll(blockId) }
                    }
                    true
                }
                
                // Text manipulation shortcuts
                key == "z" && modifiers.contains(KeyModifier.CONTROL) && !modifiers.contains(KeyModifier.SHIFT) -> {
                    onExecuteCommand("undo")
                    true
                }
                
                key == "z" && modifiers.contains(KeyModifier.CONTROL) && modifiers.contains(KeyModifier.SHIFT) -> {
                    onExecuteCommand("redo")
                    true
                }
                
                key == "y" && modifiers.contains(KeyModifier.CONTROL) -> {
                    onExecuteCommand("redo")
                    true
                }
                
                key == "x" && modifiers.contains(KeyModifier.CONTROL) -> {
                    if (blockId.isNotEmpty()) {
                        scope.launch { textOperations.cut(blockId) }
                    }
                    true
                }
                
                key == "c" && modifiers.contains(KeyModifier.CONTROL) && !modifiers.contains(KeyModifier.ALT) -> {
                    if (blockId.isNotEmpty()) {
                        scope.launch { textOperations.copy(blockId) }
                    }
                    true
                }
                
                key == "v" && modifiers.contains(KeyModifier.CONTROL) -> {
                    if (blockId.isNotEmpty()) {
                        scope.launch { textOperations.paste(blockId) }
                    }
                    true
                }
                
                // Find and replace shortcuts
                key == "f" && modifiers.contains(KeyModifier.CONTROL) -> {
                    onEditorStateChange(currentState.withFindReplace(true))
                    true
                }
                
                key == "h" && modifiers.contains(KeyModifier.CONTROL) -> {
                    onEditorStateChange(currentState.withFindReplace(true))
                    true
                }
                
                key == "g" && modifiers.contains(KeyModifier.CONTROL) -> {
                    onExecuteCommand("findNext")
                    true
                }
                
                // Save shortcuts
                key == "s" && modifiers.contains(KeyModifier.CONTROL) -> {
                    onExecuteCommand("save")
                    logger.debug("Save shortcut triggered")
                    true
                }
                
                // Line manipulation shortcuts
                key == "d" && modifiers.contains(KeyModifier.CONTROL) -> {
                    if (blockId.isNotEmpty()) {
                        scope.launch { textOperations.duplicate(blockId) }
                    }
                    true
                }
                
                // Other standard shortcuts
                else -> false
            }
        } finally {
            PerformanceMonitor.endTrace(traceId)
        }
    }
    
    /**
     * Handle key release event.
     */
    fun handleKeyRelease(
        key: String,
        modifiers: Set<KeyModifier> = emptySet(),
        timestamp: Instant = kotlin.time.Clock.System.now()
    ) {
        val event = KeyboardEvent.KeyRelease(key, modifiers, timestamp)
        addToHistory(event)
        
        logger.debug("Key released: $key with modifiers: $modifiers")
    }
    
    /**
     * Handle special character input (IME or special keys).
     */
    fun handleSpecialInput(
        input: String,
        inputType: SpecialInputType
    ): Boolean {
        val traceId = PerformanceMonitor.startTrace("keyboard-special-input")
        
        return try {
            val event = KeyboardEvent.SpecialInput(input, inputType, kotlin.time.Clock.System.now())
            addToHistory(event)
            
            when (inputType) {
                SpecialInputType.IME_COMMIT -> {
                    // Text is already committed by the text field
                    false
                }
                
                SpecialInputType.ENTER -> {
                    val currentState = editorStateProvider()
                    // if (currentState.config.autoIndentEnabled) {
                        // TODO: Implement auto-indentation logic
                        logger.debug("Enter with auto-indent")
                    // }
                    false // Let the text field handle the newline
                }
                
                SpecialInputType.TAB -> {
                    // val currentState = editorStateProvider()
                    // if (config.insertSpacesForTabs) {
                    //     val spaces = " ".repeat(config.tabSize)
                    //     scope.launch { textOperations.insertAtCursor(spaces) }
                    //     true
                    // } else {
                        false // Let the text field handle the tab
                    // }
                }
                
                SpecialInputType.BACKSPACE -> {
                    false // Let the text field handle backspace
                }
                
                SpecialInputType.DELETE -> {
                    false // Let the text field handle delete
                }
                
                SpecialInputType.ESCAPE -> {
                    val currentState = editorStateProvider()
                    // if (currentState.isContextMenuVisible) {
                    //     onEditorStateChange(currentState.withContextMenu(false))
                    //     true
                    // } else if (currentState.isFindReplaceVisible) {
                    //     onEditorStateChange(currentState.withFindReplace(false))
                    //     true
                    // } else {
                        false
                    // }
                }
            }
        } finally {
            PerformanceMonitor.endTrace(traceId)
        }
    }
    
    private fun addToHistory(event: KeyboardEvent) {
        val currentHistory = _keyEventHistory.value
        val newHistory = currentHistory + event
        _keyEventHistory.value = newHistory.takeLast(100) // Keep last 100 events
    }
}

/**
 * Keyboard event types.
 */
sealed class KeyboardEvent {
    abstract val timestamp: Instant
    
    data class KeyPress(
        val key: String,
        val modifiers: Set<KeyModifier>,
        override val timestamp: Instant
    ) : KeyboardEvent()
    
    data class KeyRelease(
        val key: String,
        val modifiers: Set<KeyModifier>,
        override val timestamp: Instant
    ) : KeyboardEvent()
    
    data class SpecialInput(
        val input: String,
        val type: SpecialInputType,
        override val timestamp: Instant
    ) : KeyboardEvent()
}

/**
 * Keyboard modifiers.
 */
enum class KeyModifier {
    CONTROL, SHIFT, ALT, META, FUNCTION
}

/**
 * Special input types for keyboard handling.
 */
enum class SpecialInputType {
    IME_COMMIT,
    ENTER,
    TAB,
    BACKSPACE,
    DELETE,
    ESCAPE
}

/**
 * IME composition visual indicator component.
 */
@Composable
fun ImeCompositionIndicator(
    editorState: EditorState,
    modifier: Modifier = Modifier
) {
    if (!editorState.isImeComposing) return
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = t("editor.ime.composing"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = editorState.imeCompositionText,
                style = MaterialTheme.typography.bodyMedium
            )
            
            editorState.imeCompositionRange?.let { range ->
                Text(
                    text = t("editor.ime.range", range.start, range.end),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Keyboard shortcuts reference component.
 */
@Composable
fun KeyboardShortcutsReference(
    // shortcuts: dev.stapler.stelekit.editor.state.KeyboardShortcuts, // Not in model
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = t("editor.shortcuts.title"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            ShortcutItem("Undo", "Ctrl+Z")
            ShortcutItem("Redo", "Ctrl+Y")
            ShortcutItem("Cut", "Ctrl+X")
            ShortcutItem("Copy", "Ctrl+C")
            ShortcutItem("Paste", "Ctrl+V")
            ShortcutItem("Select All", "Ctrl+A")
            ShortcutItem("Find", "Ctrl+F")
            ShortcutItem("Save", "Ctrl+S")
        }
    }
}

@Composable
private fun ShortcutItem(
    label: String,
    shortcut: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        
        Text(
            text = shortcut,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
