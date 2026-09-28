package dev.stapler.stelekit.editor.commands


/**
 * Types of commands available in the editor
 */
enum class CommandType {
    TEXT,           // Text formatting and manipulation
    BLOCK,          // Block-level operations
    NAVIGATION,     // Navigation and search
    SYSTEM,         // System commands (save, undo, redo)
    EDITOR,         // Editor state and settings
    PLUGIN,         // Plugin-related commands
    MEDIA,          // Media attachment commands (image, file)
    CUSTOM          // User-defined commands
}

/**
 * Priority levels for command execution
 */
enum class CommandPriority {
    LOWEST, LOW, NORMAL, HIGH, HIGHEST
}

/**
 * Result of command execution
 */
sealed class CommandResult {
    data class Success(
        val message: String? = null,
        val data: Map<String, Any> = emptyMap()
    ) : CommandResult()
    
    data class Error(
        val message: String,
        val exception: Throwable? = null,
        val code: String? = null
    ) : CommandResult()
    
    data class Partial(
        val message: String? = null,
        val completed: Int,
        val total: Int
    ) : CommandResult()
    
    object Nothing : CommandResult()
}

/**
 * Context information available during command execution
 */
data class CommandContext(
    val currentText: String = "",
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val currentBlockId: String? = null,
    val currentBlockContent: String? = null,
    val currentPageId: String? = null,
    val cursorPosition: Int = 0,
    val modifiers: Set<CommandModifier> = emptySet(),
    val additionalData: Map<String, Any> = emptyMap()
)

/**
 * Keyboard modifiers that can affect command behavior
 */
enum class CommandModifier {
    CTRL, SHIFT, ALT, META, CMD
}

/**
 * Configuration for command behavior and availability.
 *
 * Phase H (rich-editing-experience, ADR-001/Epic H.2.2): [hidden] is the mechanism
 * `EssentialCommands.kt` now uses to keep every command that lacks a real, working mutation
 * route out of `StelekitViewModel`'s command palette (`updateCommands()` sources its list from
 * `getAvailableCommands()`, which filters on [EditorCommand.isAvailable] below) — see
 * `EssentialCommands.kt`'s per-command audit comments for which commands are hidden and why.
 */
data class CommandConfig(
    val enabled: Boolean = true,
    /**
     * Do not flip to false without first verifying the command has a real, non-discarded
     * mutation route — see the per-command audit comment at each `hidden = true` site in
     * EssentialCommands.kt.
     */
    val hidden: Boolean = false,
    val requiresSelection: Boolean = false,
    val requiresBlock: Boolean = false,
    val requiresPage: Boolean = false,
    val priority: CommandPriority = CommandPriority.NORMAL,
    val debounceMs: Long = 0,
    val async: Boolean = false,
    val undoable: Boolean = true,
    val batchable: Boolean = true
)

/**
 * A command that can be executed in the editor
 */
data class EditorCommand(
    val id: String,
    val type: CommandType,
    val label: String,
    val description: String = "",
    val shortcut: String? = null,
    val icon: String? = null,
    val config: CommandConfig = CommandConfig(),
    val condition: (CommandContext) -> Boolean = { true },
    val execute: suspend (CommandContext) -> CommandResult
) {
    /**
     * Check if this command is available in the given context
     */
    fun isAvailable(context: CommandContext): Boolean {
        return config.enabled && 
               !config.hidden && 
               condition(context) &&
               (!config.requiresSelection || context.selectionStart < context.selectionEnd) &&
               (!config.requiresBlock || context.currentBlockId != null) &&
               (!config.requiresPage || context.currentPageId != null)
    }
    
    /**
     * Execute the command with the given context
     */
    suspend fun executeWithContext(context: CommandContext): CommandResult {
        return if (isAvailable(context)) {
            execute(context)
        } else {
            CommandResult.Error("Command not available in current context")
        }
    }
}

/**
 * Command execution history entry
 */
data class CommandHistoryEntry(
    val command: EditorCommand,
    val context: CommandContext,
    val result: CommandResult,
    val timestamp: kotlin.time.Instant,
    val executionTimeMs: Long
)

// Phase H (rich-editing-experience, ADR-001/Epic H.2.2): CommandSuggestion/MatchType (formerly
// defined here) and CommandRegistry.searchCommands (their sole producer) were removed — zero
// callers anywhere in the codebase. CommandPalette.kt does its own fuzzy filtering directly over
// List<Command>, not EditorCommand/CommandSuggestion.
