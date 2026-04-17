# Logseq KMP Command System

A comprehensive command system for the Logseq Kotlin Multiplatform editor that provides extensible command execution, slash command parsing, and command palette functionality.

## Architecture Overview

The command system is built around several key components:

- **CommandTypes**: Core data classes and enums for commands, contexts, and results
- **CommandSystem**: Main command execution engine with history tracking
- **CommandRegistry**: Registration and discovery system for commands
- **SlashCommandHandler**: Parses and executes `/` style commands
- **CommandManager**: High-level integration layer
- **EssentialCommands**: Built-in command implementations

## Core Concepts

### Commands

Commands are defined by the `EditorCommand` data class:

```kotlin
data class EditorCommand(
    val id: String,                    // Unique identifier
    val type: CommandType,             // Category of command
    val label: String,                 // Display name
    val description: String,           // User-friendly description
    val shortcut: String?,             // Keyboard shortcut
    val icon: String?,                 // Icon identifier
    val config: CommandConfig,          // Configuration options
    val condition: (CommandContext) -> Boolean,  // Availability check
    val execute: suspend (CommandContext) -> CommandResult  // Action
)
```

### Command Types

- **TEXT**: Text formatting and manipulation
- **BLOCK**: Block-level operations
- **NAVIGATION**: Navigation and search
- **SYSTEM**: System commands (save, undo, redo)
- **EDITOR**: Editor state and settings
- **PLUGIN**: Plugin-related commands
- **CUSTOM**: User-defined commands

### Command Context

Context provides information about the current editor state:

```kotlin
data class CommandContext(
    val currentText: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val currentBlockId: String?,
    val currentBlockContent: String?,
    val currentPageId: String?,
    val cursorPosition: Int,
    val modifiers: Set<CommandModifier>,
    val additionalData: Map<String, Any>
)
```

## Usage Examples

### Basic Command Execution

```kotlin
// Get command manager from ViewModel
val commandManager = viewModel.getCommandManager()

// Execute command by ID
val result = commandManager.executeCommand("text.bold", context)

// Execute slash command
val slashResult = commandManager.executeSlashCommand("/bold", context)
```

### Creating Custom Commands

```kotlin
val customCommand = EditorCommand(
    id = "my.custom.command",
    type = CommandType.CUSTOM,
    label = "My Custom Command",
    description = "A custom command example",
    shortcut = "Ctrl+Shift+X",
    config = CommandConfig(
        requiresSelection = true,
        priority = CommandPriority.HIGH
    ),
    condition = { context -> context.selectionStart < context.selectionEnd },
    execute = { context ->
        val selectedText = context.currentText.substring(
            context.selectionStart, 
            context.selectionEnd
        )
        // Custom logic here
        CommandResult.Success(
            message = "Custom command executed",
            data = mapOf("processedText" to selectedText.uppercase())
        )
    }
)

// Register the command
commandManager.registerCommand(customCommand)
```

### Custom Slash Commands

```kotlin
commandManager.registerSlashCommand("hello") { slashCommand, context ->
    val name = slashCommand.getArgument(0, "World")
    val message = slashCommand.getNamedArgument("message", "Hello from Logseq!")
    
    CommandResult.Success(
        message = "Greeting created: $name",
        data = mapOf(
            "greeting" to "Hello, $name! $message",
            "name" to name
        )
    )
}
```

## Built-in Commands

### Text Formatting
- `text.bold` - Format text as bold (`**text**`)
- `text.italic` - Format text as italic (`*text*`)
- `text.code` - Format text as inline code (`` `text` ``)
- `text.strikethrough` - Format text as strikethrough (`~~text~~`)
- `text.highlight` - Highlight text (`==text==`)
- `text.link` - Create a link (`[text](url)`)
- `text.heading` - Toggle heading level

### Block Operations
- `block.new` - Create new block
- `block.delete` - Delete current block
- `block.move-up` - Move block up
- `block.move-down` - Move block down
- `block.indent` - Indent block
- `block.outdent` - Outdent block
- `block.toggle-todo` - Toggle todo status

### Navigation
- `navigation.search` - Open search dialog
- `navigation.goto-page` - Open page selector
- `navigation.back` - Navigate back
- `navigation.forward` - Navigate forward

### System
- `system.save` - Save document
- `system.undo` - Undo last action
- `system.redo` - Redo last undone action
- `system.settings` - Open settings
- `system.help` - Open help documentation

## Slash Command Mappings

Many commands can be executed via slash commands:

```
/bold          → text.bold
/italic        → text.italic
/code          → text.code
/strikethrough → text.strikethrough
/highlight     → text.highlight
/link          → text.link
/heading       → text.heading
/new-block     → block.new
/delete        → block.delete
/move-up       → block.move-up
/move-down     → block.move-down
/indent        → block.indent
/outdent       → block.outdent
/todo          → block.toggle-todo
/search        → navigation.search
/goto          → navigation.goto-page
/save          → system.save
/undo          → system.undo
/redo          → system.redo
/settings      → system.settings
/help          → system.help
```

## Command Palette Integration

The enhanced command palette provides:

- **Fuzzy search**: Find commands by typing partial names
- **Keyboard navigation**: Arrow keys to navigate, Enter to execute
- **Slash command support**: Type `/` followed by command name
- **Context awareness**: Only shows available commands for current context
- **Auto-completion**: Suggests commands as you type

### Usage in UI

```kotlin
EnhancedCommandPalette(
    visible = commandPaletteVisible,
    commandManager = viewModel.getCommandManager(),
    onDismiss = { viewModel.setCommandPaletteVisible(false) },
    onCommandExecuted = { /* Handle post-execution logic */ }
)
```

## Performance Considerations

### Async Commands
Commands can be marked as async in their configuration:

```kotlin
config = CommandConfig(
    async = true  // Execute in background thread
)
```

### Debouncing
Prevent rapid execution:

```kotlin
config = CommandConfig(
    debounceMs = 300  // Wait 300ms before executing
)
```

### Command History
The system tracks command execution history:

```kotlin
// Get execution history
val history = commandManager.getHistory()

// Clear history
commandManager.clearHistory()
```

## Error Handling

Commands return `CommandResult` which can be:

- `Success(message, data)` - Command executed successfully
- `Error(message, exception, code)` - Command failed
- `Partial(message, completed, total)` - Partial completion for long operations
- `Nothing` - No action performed

The command manager automatically shows notifications for results when a `NotificationManager` is provided.

## Internationalization

All command labels and descriptions are internationalized using the existing I18n system. Translation keys follow the pattern:

```
command.{type}.{id}.label        // Display name
command.{type}.{id}.description  // Help text
```

## Plugin Integration

The command system is designed for plugin extensibility:

```kotlin
// Plugin can register commands
plugin.registerCommand("plugin.action") { command, context ->
    // Plugin-specific logic
    CommandResult.Success("Plugin action executed")
}

// Plugin can add slash commands
plugin.registerSlashCommand("plugin-cmd") { slashCommand, context ->
    // Handle plugin-specific slash command
    CommandResult.Success("Plugin command executed")
}
```

## Best Practices

1. **Use descriptive IDs**: Use hierarchical IDs like `text.format.bold`
2. **Provide good descriptions**: Help users understand what commands do
3. **Check context**: Use conditions to ensure commands are only available when appropriate
4. **Handle errors gracefully**: Always return appropriate CommandResult types
5. **Use keyboard shortcuts**: Provide intuitive shortcuts for common operations
6. **Internationalize**: Always use translation keys for user-facing text
7. **Test conditions**: Ensure availability checks work correctly in different contexts

## Future Enhancements

Potential areas for expansion:

1. **Command chaining**: Execute multiple commands in sequence
2. **Command macros**: Record and playback command sequences
3. **Conditional commands**: Commands with if/then logic
4. **Parameter validation**: Rich parameter parsing and validation
5. **Command suggestions**: AI-powered command suggestions
6. **Voice commands**: Voice-activated command execution
7. **Gesture commands**: Gesture-based command triggering