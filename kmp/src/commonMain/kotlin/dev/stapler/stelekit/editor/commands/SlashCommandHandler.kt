package dev.stapler.stelekit.editor.commands

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Validation

/**
 * Data class representing a parsed slash command
 */
data class SlashCommand(
    val command: String,
    val arguments: List<String> = emptyList(),
    val namedArguments: Map<String, String> = emptyMap(),
    val rawInput: String,
    val cursorPosition: Int = 0
) {
    init {
        Validation.validateString(command, maxLength = 100)
        arguments.forEach { Validation.validateString(it, maxLength = 1000) }
        namedArguments.forEach { (key, value) ->
            Validation.validateName(key)
            Validation.validateString(value, maxLength = 1000)
        }
    }
    
    /**
     * Get argument by position (0-based)
     */
    fun getArgument(index: Int, default: String? = null): String? {
        return if (index < arguments.size) arguments[index] else default
    }
    
    /**
     * Get named argument
     */
    fun getNamedArgument(key: String, default: String? = null): String? {
        return namedArguments[key] ?: default
    }
    
    /**
     * Check if has any arguments
     */
    fun hasArguments(): Boolean = arguments.isNotEmpty() || namedArguments.isNotEmpty()
}

/**
 * Result of slash command parsing
 */
sealed class SlashCommandParseResult {
    data class Success(val command: SlashCommand) : SlashCommandParseResult()
    data class Error(val message: String, val position: Int? = null) : SlashCommandParseResult()
    object NotASlashCommand : SlashCommandParseResult()
}

/**
 * Slash command suggestion for auto-completion
 */
data class SlashCommandSuggestion(
    val command: String,
    val description: String,
    val arguments: List<String> = emptyList(),
    val insertText: String? = null,
    val priority: Int = 0
)

/**
 * Interface for handling slash commands
 */
interface ISlashCommandHandler {
    /**
     * Parse input text as a slash command
     */
    suspend fun parse(input: String, cursorPosition: Int = input.length): SlashCommandParseResult
    
    /**
     * Execute a parsed slash command
     */
    suspend fun execute(slashCommand: SlashCommand, context: CommandContext): CommandResult
    
    /**
     * Get suggestions for auto-completion
     */
    suspend fun getSuggestions(input: String, cursorPosition: Int = input.length): List<SlashCommandSuggestion>
    
    /**
     * Register a new slash command
     */
    suspend fun registerSlashCommand(command: String, handler: suspend (SlashCommand, CommandContext) -> CommandResult)
    
    /**
     * Check if input looks like a slash command
     */
    suspend fun isSlashCommand(input: String): Boolean
}

/**
 * Slash command handler implementation
 */
class SlashCommandHandler(
    private val commandSystem: ICommandSystem,
    private val scope: CoroutineScope
) : ISlashCommandHandler {
    
    private val logger = Logger("SlashCommandHandler")
    private val _registeredCommands = MutableStateFlow<Map<String, suspend (SlashCommand, CommandContext) -> CommandResult>>(emptyMap())
    private val registeredCommands: StateFlow<Map<String, suspend (SlashCommand, CommandContext) -> CommandResult>> = _registeredCommands.asStateFlow()
    
    override suspend fun parse(input: String, cursorPosition: Int): SlashCommandParseResult {
        val trimmed = input.trim()
        
        if (!trimmed.startsWith("/")) {
            return SlashCommandParseResult.NotASlashCommand
        }
        
        try {
            // Remove the leading slash and split
            val withoutSlash = trimmed.substring(1)
            val parts = parseCommandParts(withoutSlash)
            
            if (parts.isEmpty()) {
                return SlashCommandParseResult.Error("No command specified", 1)
            }
            
            val command = parts[0]
            val (args, namedArgs) = parseArguments(parts.drop(1))
            
            return SlashCommandParseResult.Success(
                SlashCommand(
                    command = command,
                    arguments = args,
                    namedArguments = namedArgs,
                    rawInput = input,
                    cursorPosition = cursorPosition
                )
            )
        } catch (e: Exception) {
            logger.error("Error parsing slash command: $input", e)
            return SlashCommandParseResult.Error("Invalid command format: ${e.message}")
        }
    }
    
    override suspend fun execute(slashCommand: SlashCommand, context: CommandContext): CommandResult {
        logger.debug("Executing slash command: ${slashCommand.command}")
        
        // Check for registered slash command handlers first
        val registeredHandler = _registeredCommands.value[slashCommand.command]
        if (registeredHandler != null) {
            return try {
                registeredHandler(slashCommand, context)
            } catch (e: Exception) {
                logger.error("Error in registered slash command handler", e)
                CommandResult.Error("Error executing slash command: ${e.message}", e)
            }
        }
        
        // Try to map to regular editor command
        val commandId = mapSlashToCommandId(slashCommand.command)
        if (commandId != null) {
            val updatedContext = context.copy(
                additionalData = context.additionalData + ("slashCommand" to slashCommand)
            )
            return commandSystem.executeCommand(commandId, updatedContext)
        }
        
        return CommandResult.Error("Unknown slash command: ${slashCommand.command}")
    }
    
    override suspend fun getSuggestions(input: String, cursorPosition: Int): List<SlashCommandSuggestion> {
        if (!input.startsWith("/")) {
            return emptyList()
        }
        
        val parseResult = parse(input, cursorPosition)
        when (parseResult) {
            is SlashCommandParseResult.Success -> {
                return getCommandSuggestions(parseResult.command.command, parseResult.command.arguments)
            }
            is SlashCommandParseResult.Error -> {
                return getCommandSuggestions(input.substring(1).trim(), emptyList())
            }
            is SlashCommandParseResult.NotASlashCommand -> {
                return emptyList()
            }
        }
    }
    
    override suspend fun registerSlashCommand(
        command: String,
        handler: suspend (SlashCommand, CommandContext) -> CommandResult
    ) {
        val current = _registeredCommands.value
        _registeredCommands.value = current + (command to handler)
        logger.debug("Registered slash command: $command")
    }
    
    override suspend fun isSlashCommand(input: String): Boolean {
        return input.trim().startsWith("/")
    }
    
    /**
     * Parse command parts handling quotes and escaping
     */
    private fun parseCommandParts(input: String): List<String> {
        val parts = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var escapeNext = false
        
        for (char in input) {
            when {
                escapeNext -> {
                    current.append(char)
                    escapeNext = false
                }
                char == '\\' -> {
                    escapeNext = true
                }
                char == '"' -> {
                    inQuotes = !inQuotes
                }
                char.isWhitespace() && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        parts.add(current.toString())
                        current.clear()
                    }
                }
                else -> {
                    current.append(char)
                }
            }
        }
        
        if (current.isNotEmpty()) {
            parts.add(current.toString())
        }
        
        return parts
    }
    
    /**
     * Parse arguments into positional and named arguments
     */
    private fun parseArguments(parts: List<String>): Pair<List<String>, Map<String, String>> {
        val positional = mutableListOf<String>()
        val named = mutableMapOf<String, String>()
        
        for (part in parts) {
            if (part.startsWith("--") && part.contains("=")) {
                val (key, value) = part.substring(2).split("=", limit = 2)
                named[key] = value
            } else if (part.startsWith("-") && part.length > 1) {
                // Short form: -k value or -k=value
                val key = part.substring(1)
                if (key.contains("=")) {
                    val (k, value) = key.split("=", limit = 2)
                    named[k] = value
                } else {
                    named[key] = ""
                }
            } else {
                positional.add(part)
            }
        }
        
        return Pair(positional, named)
    }
    
    /**
     * Map slash command names to editor command IDs
     */
    private fun mapSlashToCommandId(slashCommand: String): String? {
        return when (slashCommand) {
            "bold" -> "text.bold"
            "italic" -> "text.italic"
            "code" -> "text.code"
            "strikethrough" -> "text.strikethrough"
            "highlight" -> "text.highlight"
            "link" -> "text.link"
            "heading" -> "text.heading"
            "bullet" -> "block.bullet"
            "numbered" -> "block.numbered"
            "quote" -> "block.quote"
            "todo" -> "block.todo"
            "doing" -> "block.doing"
            "done" -> "block.done"
            "move-up" -> "block.move-up"
            "move-down" -> "block.move-down"
            "indent" -> "block.indent"
            "outdent" -> "block.outdent"
            "delete" -> "block.delete"
            "copy" -> "block.copy"
            "cut" -> "block.cut"
            "paste" -> "block.paste"
            "new-page" -> "page.new"
            "new-block" -> "block.new"
            "save" -> "system.save"
            "undo" -> "system.undo"
            "redo" -> "system.redo"
            "search" -> "navigation.search"
            "goto" -> "navigation.goto"
            "help" -> "system.help"
            "settings" -> "system.settings"
            else -> null
        }
    }
    
    /**
     * Get command suggestions based on partial input
     */
    private suspend fun getCommandSuggestions(partialCommand: String, args: List<String>): List<SlashCommandSuggestion> {
        val availableCommands = commandSystem.getAvailableCommands(CommandContext())
        
        return availableCommands.mapNotNull { command ->
            if (command.id.contains(partialCommand) || command.label.contains(partialCommand, ignoreCase = true)) {
                val slashName = mapCommandIdToSlash(command.id)
                if (slashName != null) {
                    SlashCommandSuggestion(
                        command = slashName,
                        description = command.description,
                        arguments = emptyList(), // Could be enhanced with argument specs
                        priority = when (command.type) {
                            CommandType.TEXT -> 3
                            CommandType.BLOCK -> 2
                            CommandType.NAVIGATION -> 1
                            else -> 0
                        }
                    )
                } else null
            } else null
        }.sortedByDescending { it.priority }
    }
    
    /**
     * Map editor command ID to slash command name
     */
    private fun mapCommandIdToSlash(commandId: String): String? {
        return when (commandId) {
            "text.bold" -> "bold"
            "text.italic" -> "italic"
            "text.code" -> "code"
            "text.strikethrough" -> "strikethrough"
            "text.highlight" -> "highlight"
            "text.link" -> "link"
            "text.heading" -> "heading"
            "block.bullet" -> "bullet"
            "block.numbered" -> "numbered"
            "block.quote" -> "quote"
            "block.todo" -> "todo"
            "block.doing" -> "doing"
            "block.done" -> "done"
            "block.move-up" -> "move-up"
            "block.move-down" -> "move-down"
            "block.indent" -> "indent"
            "block.outdent" -> "outdent"
            "block.delete" -> "delete"
            "block.copy" -> "copy"
            "block.cut" -> "cut"
            "block.paste" -> "paste"
            "page.new" -> "new-page"
            "block.new" -> "new-block"
            "system.save" -> "save"
            "system.undo" -> "undo"
            "system.redo" -> "redo"
            "navigation.search" -> "search"
            "navigation.goto" -> "goto"
            "system.help" -> "help"
            "system.settings" -> "settings"
            else -> null
        }
    }
}