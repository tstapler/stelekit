package dev.stapler.stelekit.editor.commands

import kotlinx.coroutines.CoroutineScope
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.NotificationType
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/**
 * Command manager that integrates all command system components.
 *
 * Phase H (rich-editing-experience, ADR-001): this is the one live, reachable slice of the
 * former "enterprise" `editor/commands/` framework — `StelekitViewModel` uses it purely to
 * source id/label/shortcut metadata for the command palette (`updateCommands()`'s
 * `getAvailableCommands()` call). The `/`-slash-command bridge (`SlashCommandHandler`) that
 * used to live here has been deleted (zero UI call sites, confirmed by repo-wide grep) — no
 * `/` keystroke trigger exists anywhere in the app.
 */
class CommandManager(
    private val scope: CoroutineScope,
    private val notificationManager: ((String, NotificationType, Long?) -> Unit)? = null
) {
    private val logger = Logger("CommandManager")

    private val registry = CommandRegistry()
    private val commandSystem = CommandSystem(registry)

    init {
        // Register essential commands
        scope.launch {
            registry.registerAll(EssentialCommands.getAll())
            logger.info("Command manager initialized with ${EssentialCommands.getAll().size} essential commands")
        }
    }

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
