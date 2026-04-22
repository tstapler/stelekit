package dev.stapler.stelekit.editor.commands

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.stapler.stelekit.logging.Logger
import kotlin.math.max

/**
 * Interface for command registry
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
     * Search commands by query
     */
    suspend fun searchCommands(query: String, context: CommandContext): List<CommandSuggestion>
    
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
    
    override suspend fun searchCommands(query: String, context: CommandContext): List<CommandSuggestion> {
        if (query.isEmpty()) {
            return getAvailableCommands(context).map { 
                CommandSuggestion(it, 1.0, MatchType.EXACT, it.label) 
            }
        }
        
        val availableCommands = getAvailableCommands(context)
        val normalizedQuery = query.lowercase()
        
        return availableCommands.mapNotNull { command ->
            val normalizedLabel = command.label.lowercase()
            val score = calculateMatchScore(normalizedQuery, normalizedLabel, command.id)
            
            if (score > 0) {
                val matchType = determineMatchType(normalizedQuery, normalizedLabel)
                val highlightedLabel = highlightMatch(command.label, normalizedQuery)
                CommandSuggestion(command, score, matchType, highlightedLabel)
            } else null
        }.sortedByDescending { it.matchScore }
    }
    
    override suspend fun getAvailableCommands(context: CommandContext): List<EditorCommand> {
        return _commands.value.values.filter { it.isAvailable(context) }
    }
    
    /**
     * Calculate fuzzy matching score for command search
     */
    private fun calculateMatchScore(query: String, label: String, commandId: String): Double {
        // Exact match gets highest score
        if (query == label || query == commandId.lowercase()) {
            return 1.0
        }
        
        // Prefix match gets high score
        if (label.startsWith(query) || commandId.lowercase().startsWith(query)) {
            return 0.8
        }
        
        // Contains match gets medium score
        if (label.contains(query) || commandId.lowercase().contains(query)) {
            return 0.6
        }
        
        // Fuzzy match using simple character sequence matching
        val fuzzyScore = calculateFuzzyScore(query, label)
        return if (fuzzyScore > 0.3) fuzzyScore else 0.0
    }
    
    /**
     * Simple fuzzy string matching
     */
    private fun calculateFuzzyScore(query: String, text: String): Double {
        if (query.isEmpty()) return 0.0
        if (text.isEmpty()) return 0.0
        
        var queryIndex = 0
        var textIndex = 0
        var matches = 0
        
        while (queryIndex < query.length && textIndex < text.length) {
            if (query[queryIndex] == text[textIndex]) {
                matches++
                queryIndex++
            }
            textIndex++
        }
        
        return if (queryIndex == query.length) {
            // All query characters found, score based on concentration
            val concentration = matches.toDouble() / text.length
            concentration * 0.4 // Base fuzzy score
        } else {
            0.0
        }
    }
    
    /**
     * Determine the type of match
     */
    private fun determineMatchType(query: String, label: String): MatchType {
        return when {
            query == label -> MatchType.EXACT
            label.startsWith(query) -> MatchType.PREFIX
            label.contains(query) -> MatchType.CONTAINS
            else -> MatchType.FUZZY
        }
    }
    
    /**
     * Highlight matching parts in the label
     */
    private fun highlightMatch(label: String, query: String): String {
        if (query.isEmpty()) return label
        
        // Simple implementation - in a real app, you'd use markdown or styled spans
        val normalizedLabel = label.lowercase()
        val normalizedQuery = query.lowercase()
        
        val startIndex = normalizedLabel.indexOf(normalizedQuery)
        return if (startIndex != -1) {
            val endIndex = startIndex + query.length
            val before = label.substring(0, startIndex)
            val match = label.substring(startIndex, endIndex)
            val after = label.substring(endIndex)
            "$before**$match**$after"
        } else {
            label
        }
    }
}
