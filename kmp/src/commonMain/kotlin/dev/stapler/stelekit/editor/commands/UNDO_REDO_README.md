# Logseq KMP Editor Undo/Redo System

## Overview

The Logseq KMP editor now includes a comprehensive undo/redo command system that provides complete history management for all editor operations. The system is built on the Command Pattern and provides robust, performant, and extensible history management.

## Architecture

### Core Components

1. **EditorCommand Interface** - Base interface for all commands with execute/undo/redo capabilities
2. **CommandRegistry** - Manages command registration, discovery, and lifecycle
3. **CommandHistory** - Stack-based history management with checkpoints
4. **UndoManager** - Coordinates command execution and history management
5. **EditorCommandSystem** - High-level integration with existing editor

### Key Features

- **Command Pattern Foundation** - All operations implement the same interface
- **Performance Optimization** - Command coalescing for consecutive typing (maintains 16ms performance)
- **Memory Management** - Efficient history storage with configurable limits
- **Checkpoint Support** - Save points for long operations
- **Batch Operations** - Atomic execution of multiple commands
- **Concurrent Safety** - Thread-safe command execution and history management
- **Serialization** - Persist commands for recovery and collaboration
- **Comprehensive Coverage** - All text and block operations support undo/redo

## Usage Examples

### Basic Text Operations

```kotlin
val commandSystem = EditorCommandSystem(editorState, activeModel)

// Insert text
commandSystem.insertText(
    blockId = "block123",
    position = 10,
    text = "Hello, World!"
)

// Delete text
commandSystem.deleteText(
    blockId = "block123",
    position = 0,
    length = 13
)

// Replace text
commandSystem.replaceText(
    blockId = "block123",
    position = 0,
    length = 0,
    newText = "New content"
)
```

### Block Operations

```kotlin
// Create a new block
commandSystem.createBlock(
    parentBlockId = "parent123",
    position = 0,
    content = "New block content"
)

// Move a block
commandSystem.moveBlock(
    blockId = "block123",
    newParentId = "newParent",
    newPosition = 2
)

// Delete a block
commandSystem.deleteBlock(
    blockId = "block123",
    preserveChildren = true
)
```

### Batch Operations

```kotlin
// Execute multiple commands as a batch
val commands = listOf(
    InsertTextCommand("block1", 0, "First"),
    InsertTextCommand("block2", 0, "Second"),
    MoveBlockCommand("block1", "block2", null)
)

commandSystem.executeBatch(commands, "Document restructuring")

// Smart batch with optimization
commandSystem.executeSmartBatch(commands, "Optimized operation")

// Transaction with all-or-nothing guarantee
commandSystem.executeTransaction(commands, "Critical update")
```

### Undo/Redo Operations

```kotlin
// Basic undo/redo
commandSystem.undo()
commandSystem.redo()

// Check if undo/redo is available
if (commandSystem.canUndo.value) {
    commandSystem.undo()
}

// Create checkpoints
commandSystem.createCheckpoint("before_major_edit", "Before restructuring")
commandSystem.restoreToCheckpoint("before_major_edit")

// Get undo/redo descriptions
println("Can undo: ${commandSystem.undoDescription.value}")
println("Can redo: ${commandSystem.redoDescription.value}")
```

## Performance Optimization

### Command Coalescing

The system automatically coalesces consecutive text operations to maintain 16ms typing performance:

- **Insertion Coalescing** - Consecutive character insertions are merged
- **Deletion Coalescing** - Adjacent deletions are combined
- **Time Window** - Commands within 1 second are eligible for coalescing
- **Smart Batching** - Optimizes command ordering for better performance

### Memory Management

- **History Size Limit** - Configurable maximum history entries (default: 1000)
- **Memory Usage Monitoring** - Tracks command memory usage and trims when needed
- **Lazy Loading** - Commands are loaded on-demand for large histories
- **Checkpoint Support** - Creates efficient save points for navigation

## Integration with Existing Editor

### Connecting to EditorActiveModel

```kotlin
class EditorIntegration {
    private val commandSystem = EditorCommandSystem(editorState, activeModel)
    
    // Handle text input with automatic coalescing
    suspend fun handleTextInput(blockId: String, text: String, position: Int) {
        commandSystem.insertText(blockId, position, text)
    }
    
    // Handle keyboard shortcuts
    suspend fun handleUndoShortcut() {
        if (commandSystem.canUndo.value) {
            commandSystem.undo()
        }
    }
    
    // Update UI based on undo/redo state
    fun setupUIObservers() {
        commandSystem.canUndo.collect { canUndo ->
            updateUndoButton(canUndo)
        }
        
        commandSystem.undoDescription.collect { description ->
            updateUndoTooltip(description)
        }
    }
}
```

## File Structure

```
commands/
├── EditorCommand.kt         # Base command interface and types
├── CommandRegistry.kt      # Command registration and discovery
├── CommandHistory.kt        # History management with checkpoints
├── UndoManager.kt          # Core undo/redo coordination
├── TextCommands.kt         # Text manipulation commands
├── BlockCommands.kt        # Block-level operation commands
├── BatchCommand.kt         # Batch and transaction commands
├── EditorCommandSystem.kt  # High-level integration
└── README.md              # This documentation
```

## Best Practices

### Performance

1. **Use Coalescing** - Enable coalescing for text operations
2. **Batch Operations** - Group related commands together
3. **Checkpoints** - Create checkpoints before expensive operations
4. **Memory Limits** - Configure appropriate history sizes
5. **Optimize Regularly** - Run history optimization periodically

### Code Organization

1. **Command Classes** - Keep commands focused and single-purpose
2. **Error Handling** - Provide meaningful error messages
3. **Documentation** - Document command purpose and side effects
4. **Testing** - Test undo/redo behavior thoroughly
5. **Validation** - Validate command parameters before execution

This comprehensive undo/redo system provides robust, performant, and extensible history management for Logseq KMP editor while maintaining 16ms performance target and supporting all editor operations.