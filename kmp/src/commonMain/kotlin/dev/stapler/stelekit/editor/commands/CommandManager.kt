package dev.stapler.stelekit.editor.commands

import kotlinx.coroutines.CoroutineScope
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.NotificationType
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * Command manager that integrates all command system components
 */
class CommandManager(
    private val scope: CoroutineScope,
    private val notificationManager: ((String, NotificationType, Long?) -> Unit)? = null
) {
    private val logger = Logger("CommandManager")
    
    private val registry = CommandRegistry()
    private val commandSystem = CommandSystem(registry)
    private val slashCommandHandler = SlashCommandHandler(commandSystem)
    
    init {
        // Register essential commands
        scope.launch {
            registry.registerAll(EssentialCommands.getAll())
            logger.info("Command manager initialized with ${EssentialCommands.getAll().size} essential commands")
        }
    }
    
    /**
     * Get the command system
     */
    fun getCommandSystem(): ICommandSystem = commandSystem
    
    /**
     * Get the command registry
     */
    fun getRegistry(): ICommandRegistry = registry
    
    /**
     * Get the slash command handler
     */
    fun getSlashCommandHandler(): ISlashCommandHandler = slashCommandHandler
    
    /**
     * Execute a command by ID with context
     */
    suspend fun executeCommand(commandId: String, context: CommandContext = CommandContext()): CommandResult {
        return try {
            val result = commandSystem.executeCommand(commandId, context)
            handleResult(result)
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to execute command $commandId", e)
            val errorResult = CommandResult.Error("Failed to execute command: ${e.message}", e)
            handleResult(errorResult)
            errorResult
        }
    }
    
    /**
     * Execute a slash command
     */
    suspend fun executeSlashCommand(input: String, context: CommandContext = CommandContext()): CommandResult {
        return try {
            when (val parseResult = slashCommandHandler.parse(input)) {
                is SlashCommandParseResult.Success -> {
                    val result = slashCommandHandler.execute(parseResult.command, context)
                    handleResult(result)
                    result
                }
                is SlashCommandParseResult.Error -> {
                    val errorResult = CommandResult.Error(parseResult.message)
                    handleResult(errorResult)
                    errorResult
                }
                is SlashCommandParseResult.NotASlashCommand -> {
                    CommandResult.Error("Not a slash command")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to execute slash command: $input", e)
            val errorResult = CommandResult.Error("Failed to execute slash command: ${e.message}", e)
            handleResult(errorResult)
            errorResult
        }
    }
    
    /**
     * Get command suggestions for the palette
     */
    suspend fun getCommandSuggestions(query: String, context: CommandContext = CommandContext()): List<CommandSuggestion> {
        return try {
            registry.searchCommands(query, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to get command suggestions", e)
            emptyList()
        }
    }
    
    /**
     * Get slash command suggestions for auto-completion
     */
    suspend fun getSlashCommandSuggestions(input: String): List<SlashCommandSuggestion> {
        return try {
            slashCommandHandler.getSuggestions(input)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to get slash command suggestions", e)
            emptyList()
        }
    }
    
    /**
     * Check if input is a slash command
     */
    suspend fun isSlashCommand(input: String): Boolean {
        return try {
            slashCommandHandler.isSlashCommand(input)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to check if input is slash command", e)
            false
        }
    }
    
    /**
     * Get all available commands for a context
     */
    suspend fun getAvailableCommands(context: CommandContext = CommandContext()): List<EditorCommand> {
        return try {
            commandSystem.getAvailableCommands(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to get available commands", e)
            emptyList()
        }
    }
    
    /**
     * Register a custom command
     */
    suspend fun registerCommand(command: EditorCommand) {
        try {
            registry.register(command)
            logger.info("Registered custom command: ${command.id}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to register command: ${command.id}", e)
        }
    }
    
    /**
     * Register a custom slash command handler
     */
    suspend fun registerSlashCommand(
        command: String,
        handler: suspend (SlashCommand, CommandContext) -> CommandResult
    ) {
        try {
            slashCommandHandler.registerSlashCommand(command, handler)
            logger.info("Registered custom slash command: $command")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to register slash command: $command", e)
        }
    }
    
    /**
     * Get command by ID
     */
    suspend fun getCommand(commandId: String): EditorCommand? {
        return try {
            registry.getCommand(commandId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to get command: $commandId", e)
            null
        }
    }
    
    /**
     * Get command execution history
     */
    fun getHistory() = commandSystem.getHistory()
    
    /**
     * Clear command history
     */
    suspend fun clearHistory() {
        try {
            commandSystem.clearHistory()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to clear history", e)
        }
    }
    
    /**
     * Handle command results (show notifications, etc.)
     */
    private fun handleResult(result: CommandResult) {
        notificationManager?.let { notify ->
            when (result) {
                is CommandResult.Success -> {
                    result.message?.let { message ->
                        notify(message, NotificationType.SUCCESS, 3000)
                    }
                }
                is CommandResult.Error -> {
                    notify(result.message, NotificationType.ERROR, 5000)
                }
                is CommandResult.Partial -> {
                    result.message?.let { message ->
                        notify("$message (${result.completed}/${result.total})", NotificationType.INFO, 2000)
                    }
                }
                is CommandResult.Nothing -> {
                    // No notification needed
                }
            }
        }
    }
    
    companion object {
        /**
         * Create a command manager with default configuration
         */
        fun create(
            scope: CoroutineScope,
            notificationManager: ((String, NotificationType, Long?) -> Unit)? = null
        ): CommandManager {
            return CommandManager(scope, notificationManager)
        }
    }
}
