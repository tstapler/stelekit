package dev.stapler.stelekit.ui

import dev.stapler.stelekit.editor.commands.CommandContext
import dev.stapler.stelekit.editor.commands.CommandManager
import dev.stapler.stelekit.editor.commands.CommandResult
import dev.stapler.stelekit.editor.commands.EditorCommand
import dev.stapler.stelekit.ui.state.BlockStateManager
import kotlinx.coroutines.CoroutineScope

/**
 * Coordinator for command execution and available command management.
 * Encapsulates [CommandManager] operations and command routing.
 */
class CommandManagerCoordinator(
    private val scope: CoroutineScope,
    private val notificationManager: NotificationManager?,
    private val blockStateManagerProvider: () -> BlockStateManager?,
) {
    val commandManager: CommandManager = CommandManager.create(scope) { message, type, timeout ->
        notificationManager?.show(message, type, timeout)
    }

    /**
     * Execute a command by ID.
     */
    suspend fun executeCommand(
        commandId: String,
        context: CommandContext = CommandContext()
    ): CommandResult {
        if (commandId == "block.toggle-todo") {
            val manager = blockStateManagerProvider()
                ?: return CommandResult.Error(message = "No block is currently being edited")
            manager.requestTodoToggle()
            return CommandResult.Success(message = "Todo status toggled")
        }
        return commandManager.executeCommand(commandId, context)
    }

    /**
     * Get available commands for current context.
     */
    suspend fun getAvailableCommands(context: CommandContext): List<EditorCommand> {
        return commandManager.getAvailableCommands(context)
    }
}
