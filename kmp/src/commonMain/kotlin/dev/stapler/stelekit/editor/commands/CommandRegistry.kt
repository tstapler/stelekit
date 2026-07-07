package dev.stapler.stelekit.editor.commands

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.stapler.stelekit.logging.Logger

/**
 * Interface for command registry.
 *
 * Phase H (rich-editing-experience, ADR-001/Epic H.2.2): `searchCommands`/fuzzy-matching
 * (`CommandSuggestion`, `MatchType`) was trimmed — it had zero callers anywhere in the codebase
 * (the real command palette, `CommandPalette.kt`, does its own fuzzy filtering directly over
 * `List<Command>`, not `EditorCommand`/`CommandSuggestion`). Only the live bridge's needs
 * remain: register/getCommand/getAllCommands/getAvailableCommands.
 */
interface ICommandRegistry {
    /**
     * Register a new command
     */
    suspend fun register(command: EditorCommand)
    
    /**
     * Register multiple commands
     */
    suspend fun registerAll(commands: List<EditorCommand>)
    
    /**
     * Unregister a command by ID
     */
    suspend fun unregister(commandId: String)
    
    /**
     * Get command by ID
     */
    suspend fun getCommand(commandId: String): EditorCommand?
    
    /**
     * Get all commands
     */
    suspend fun getAllCommands(): List<EditorCommand>
    
    /**
     * Get commands by type
     */
    suspend fun getCommandsByType(type: CommandType): List<EditorCommand>
    
    /**
     * Get available commands for context
     */
    suspend fun getAvailableCommands(context: CommandContext): List<EditorCommand>
}

/**
 * Command registry implementation
 */
class CommandRegistry : ICommandRegistry {
    
    private val logger = Logger("CommandRegistry")
    private val _commands = MutableStateFlow<Map<String, EditorCommand>>(emptyMap())
    private val commands: StateFlow<Map<String, EditorCommand>> = _commands.asStateFlow()
    
    override suspend fun register(command: EditorCommand) {
        val currentCommands = _commands.value
        if (currentCommands.containsKey(command.id)) {
            logger.warn("Command ${command.id} already exists, overwriting")
        }
        
        _commands.value = currentCommands + (command.id to command)
        logger.debug("Registered command: ${command.id}")
    }
    
    override suspend fun registerAll(commands: List<EditorCommand>) {
        commands.forEach { command ->
            register(command)
        }
    }
    
    override suspend fun unregister(commandId: String) {
        val currentCommands = _commands.value
        if (currentCommands.containsKey(commandId)) {
            _commands.value = currentCommands - commandId
            logger.debug("Unregistered command: $commandId")
        } else {
            logger.warn("Attempted to unregister non-existent command: $commandId")
        }
    }
    
    override suspend fun getCommand(commandId: String): EditorCommand? {
        return _commands.value[commandId]
    }
    
    override suspend fun getAllCommands(): List<EditorCommand> {
        return _commands.value.values.toList()
    }
    
    override suspend fun getCommandsByType(type: CommandType): List<EditorCommand> {
        return _commands.value.values.filter { it.type == type }
    }
    
    override suspend fun getAvailableCommands(context: CommandContext): List<EditorCommand> {
        return _commands.value.values.filter { it.isAvailable(context) }
    }
}
