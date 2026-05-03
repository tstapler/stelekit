package dev.stapler.stelekit.editor.commands

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import dev.stapler.stelekit.logging.Logger

/**
 * Main command system implementation
 */
class CommandSystem(
    private val registry: CommandRegistry,
) : ICommandSystem {
    
    private val logger = Logger("CommandSystem")
    private val _history = MutableStateFlow<List<CommandHistoryEntry>>(emptyList())
    private val maxHistorySize = 100
    
    override suspend fun executeCommand(commandId: String, context: CommandContext): CommandResult {
        val startTime = Clock.System.now()
        
        return try {
            val command = registry.getCommand(commandId)
                ?: return CommandResult.Error("Command not found: $commandId")
            
            logger.debug("Executing command: ${command.id}")
            
            val result = if (command.config.async) {
                command.executeWithContext(context)
            } else {
                withContext(Dispatchers.Default) {
                    command.executeWithContext(context)
                }
            }
            
            val executionTime = Clock.System.now().minus(startTime).inWholeMilliseconds
            val historyEntry = CommandHistoryEntry(
                command = command,
                context = context,
                result = result,
                timestamp = startTime,
                executionTimeMs = executionTime
            )
            
            addToHistory(historyEntry)
            
            when (result) {
                is CommandResult.Success -> {
                    logger.debug("Command executed successfully in ${executionTime}ms")
                    result
                }
                is CommandResult.Error -> {
                    logger.error("Command failed: ${result.message}", result.exception)
                    result
                }
                is CommandResult.Partial -> {
                    logger.debug("Command partially completed: ${result.completed}/${result.total}")
                    result
                }
                is CommandResult.Nothing -> {
                    logger.debug("Command resulted in no action")
                    result
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val executionTime = Clock.System.now().minus(startTime).inWholeMilliseconds
            logger.error("Unexpected error executing command $commandId", e)
            CommandResult.Error("Unexpected error: ${e.message}", e)
        }
    }
    
    override suspend fun getAvailableCommands(context: CommandContext): List<EditorCommand> {
        return registry.getAllCommands().filter { it.isAvailable(context) }
    }
    
    override suspend fun getCommand(commandId: String): EditorCommand? {
        return registry.getCommand(commandId)
    }
    
    override fun getHistory(): StateFlow<List<CommandHistoryEntry>> = _history.asStateFlow()
    
    override suspend fun clearHistory() {
        _history.value = emptyList()
        logger.debug("Command history cleared")
    }
    
    private fun addToHistory(entry: CommandHistoryEntry) {
        val currentHistory = _history.value.toMutableList()
        currentHistory.add(0, entry)

        // Limit history size
        if (currentHistory.size > maxHistorySize) {
            currentHistory.removeAt(currentHistory.lastIndex)
        }

        _history.value = currentHistory
    }
}
