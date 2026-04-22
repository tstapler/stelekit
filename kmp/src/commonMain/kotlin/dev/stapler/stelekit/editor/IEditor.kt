package dev.stapler.stelekit.editor

import androidx.compose.ui.input.key.KeyEvent
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.CursorState
import dev.stapler.stelekit.editor.commands.EditorCommand
import dev.stapler.stelekit.editor.state.EditorState
import dev.stapler.stelekit.editor.state.EditorConfig
import kotlinx.coroutines.flow.StateFlow
import kotlin.Result

/**
 * Core interface for the Logseq editor.
 * Manages editor state, user input, and command execution.
 */
interface IEditor {
    
    // ===== STATE ACCESS =====
    
    /**
     * Current editor state including blocks, mode, and status.
     */
    val editorState: StateFlow<EditorState>
    
    /**
     * Currently loaded page.
     */
    val currentPage: StateFlow<Page?>
    
    /**
     * Current cursor state including block focus and position.
     */
    val cursorState: StateFlow<CursorState>
    
    /**
     * Editor configuration.
     */
    val config: EditorConfig
    
    // ===== LIFECYCLE OPERATIONS =====
    
    /**
     * Initialize editor with a specific page.
     * 
     * @param page The page to load
     * @return Result indicating success or error
     */
    suspend fun initialize(page: Page): Result<Unit>
    
    /**
     * Dispose editor resources and clean up state.
     */
    suspend fun dispose()
    
    // ===== INPUT HANDLING =====
    
    /**
     * Handle keyboard events for shortcuts and navigation.
     * 
     * @param keyEvent The keyboard event
     * @return true if event was handled, false if it should propagate
     */
    fun handleKeyEvent(keyEvent: KeyEvent): Boolean
    
    // ===== COMMAND EXECUTION =====
    
    /**
     * Execute a structured editor command.
     * 
     * @param command The command to execute
     * @return Result indicating success or error
     */
    suspend fun executeCommand(command: EditorCommand): Result<Unit>
    
    /**
     * Execute a command by ID with arguments.
     * 
     * @param commandId The ID of the command
     * @param args Command arguments
     * @return Result containing command result or error
     */
    suspend fun executeCommand(commandId: String, args: Map<String, Any>): Result<Any?>
}

/**
 * Interface for processing text formatting
 */
interface IFormatProcessor {
    /**
     * Get the active format at a specific position in the text
     */
    fun getFormatAt(content: String, position: Int): Result<TextFormat>

    /**
     * Parse markdown content into formatted segments (for rendering)
     * This might return a list of spans or similar, but for now we just need the interface definition.
     */
}
