package dev.stapler.stelekit.editor.commands

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for command system implementation
 */
interface ICommandSystem {
    /**
     * Execute a command by ID
     */
    suspend fun executeCommand(commandId: String, context: CommandContext): CommandResult
    
    /**
     * Get all available commands for a given context
     */
    suspend fun getAvailableCommands(context: CommandContext): List<EditorCommand>
    
    /**
     * Get command by ID
     */
    suspend fun getCommand(commandId: String): EditorCommand?
    
    /**
     * Get command history
     */
    fun getHistory(): StateFlow<List<CommandHistoryEntry>>
    
    /**
     * Clear command history
     */
    suspend fun clearHistory()
}
