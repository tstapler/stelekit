# EditorActiveModel Documentation

## Overview

The `EditorActiveModel` is a unified state management solution for the Logseq KMP editor that consolidates fragmented state from multiple ViewModels into a single, cohesive MVI (Model-View-Intent) architecture. It serves as the single source of truth for all editor-related state and operations.

## Architecture

### MVI Pattern

The EditorActiveModel follows the MVI pattern with unidirectional data flow:

```
User Input → Intent → Action → Reducer → State → UI
```

1. **Intent**: User interactions or system events
2. **Action**: Sealed interface representing all possible state changes
3. **Reducer**: Pure functions that transform state based on actions
4. **State**: Immutable state representation
5. **UI**: Reactive updates based on state changes

### Key Components

- **EditorActiveModel**: Main class coordinating state management
- **ActiveEditorState**: Immutable state data class
- **EditorAction**: Sealed interface of all possible actions
- **EditorActiveModelExtensions**: Migration and utility functions

## Features

### 1. Unified State Management

Consolidates state from:
- **EditorViewModel**: Rich text editor state
- **JournalsViewModel**: Block tree state and operations
- **EditorState**: Configuration and UI state

### 2. MVI Pattern Implementation

- **Unidirectional Data Flow**: Predictable state updates
- **Immutable State**: Thread-safe and predictable
- **Pure Reducers**: Testable state transformations
- **Side Effect Handling**: Clean separation of concerns

### 3. Batch Operations

Performance optimization for bulk operations:

```kotlin
activeModel.batch {
    processAction(EditorAction.InsertText("block1", "Hello"))
    processAction(EditorAction.InsertText("block1", " World"))
    processAction(EditorAction.FormatText("block1", range, TextFormat.BOLD))
}
```

### 4. Unified Undo/Redo

Integrates command-based undo/redo with text operations:

```kotlin
processAction(EditorAction.Undo)
processAction(EditorAction.Redo)
processAction(EditorAction.ExecuteCommand(customCommand))
```

### 5. Auto-Save Coordination

Automatic saving with configuration support:

```kotlin
val config = EditorConfig(autoSave = true, autoSaveInterval = 3000L)
processAction(EditorAction.UpdateConfig(config))
```

## Usage Examples

### Basic Setup

```kotlin
// Create dependencies
val textOperations: ITextOperations = DefaultTextOperations()
val blockOperations: IBlockOperations = BlockOperationsImpl()
val undoManager = UndoManager()
val scope = CoroutineScope(Dispatchers.Main)

// Create EditorActiveModel
val editorModel = EditorActiveModel(
    textOperations = textOperations,
    blockOperations = blockOperations,
    undoManager = undoManager,
    scope = scope
)
```

### Loading Content

```kotlin
// Load blocks for a page
processAction(EditorAction.LoadBlocks(pageId = 123))

// Observe loading state
editorModel.isLoading.collect { isLoading ->
    if (isLoading) {
        showLoadingIndicator()
    } else {
        hideLoadingIndicator()
    }
}

// Observe loaded blocks
editorModel.blocks.collect { blocks ->
    updateUIWithBlocks(blocks)
}
```

### Text Operations

```kotlin
// Insert text at cursor
processAction(EditorAction.InsertText(blockId = "block-123", text = "Hello World"))

// Replace text range
val range = TextRange(start = 0, end = 5)
processAction(EditorAction.ReplaceText("block-123", range, "Hi"))

// Set cursor position
processAction(EditorAction.SetCursor("block-123", position = 10))

// Delete text
processAction(EditorAction.DeleteText("block-123", range))
```

### Block Operations

```kotlin
// Create new block
processAction(EditorAction.CreateBlock(
    pageId = 123,
    content = "New block content",
    parentId = "parent-123"
))

// Update block content
processAction(EditorAction.UpdateBlock(
    blockUuid = "block-123",
    content = "Updated content",
    properties = mapOf("priority" to "high")
))

// Delete block
processAction(EditorAction.DeleteBlock(
    blockUuid = "block-123",
    deleteStrategy = DeleteStrategy.MOVE_CHILDREN_TO_PARENT
))

// Move block in hierarchy
processAction(EditorAction.MoveBlock(
    blockUuid = "block-123",
    newParentId = "new-parent-456",
    newLeftId = "sibling-789"
))
```

### Focus and Selection

```kotlin
// Focus a block
processAction(EditorAction.FocusBlock(blockId = "block-123"))

// Observe focus changes
editorModel.focusedBlockId.collect { blockId ->
    updateFocusUI(blockId)
}

// Select multiple blocks
processAction(EditorAction.SelectAll)
processAction(EditorAction.SelectRange("start-123", "end-456"))
processAction(EditorAction.ClearSelection)
```

### Configuration

```kotlin
// Update editor configuration
val newConfig = EditorConfig(
    isEditable = true,
    autoSave = true,
    autoSaveInterval = 5000L,
    fontSize = 16f,
    fontFamily = "Inter"
)
processAction(EditorAction.UpdateConfig(newConfig))

// Observe configuration changes
editorModel.config.collect { config ->
    applyEditorSettings(config)
}
```

### Error Handling

```kotlin
// Observe error state
editorModel.error.collect { errorMessage ->
    errorMessage?.let { showError(it) }
}

// Clear error
processAction(EditorAction.ClearError)
```

## Migration Guide

### From Simple EditorViewModel

```kotlin
// Create from existing simple ViewModel
val editorModel = createEditorActiveModelFromSimple(
    simpleViewModel = existingSimpleViewModel,
    textOperations = textOps,
    blockOperations = blockOps,
    scope = scope,
    pageId = currentPageId
)
```

### From Rich EditorViewModel

```kotlin
// Create from existing rich ViewModel
val editorModel = createEditorActiveModelFromRich(
    richViewModel = existingRichViewModel,
    blockOperations = blockOps,
    scope = scope
)
```

### From JournalsViewModel

```kotlin
// Create from existing JournalsViewModel
val editorModel = createEditorActiveModelFromJournals(
    journalsViewModel = existingJournalsViewModel,
    textOperations = textOps,
    blockOperations = blockOps,
    scope = scope
)
```

### Legacy Compatibility Wrapper

For gradual migration, use the compatibility wrapper:

```kotlin
val wrapper = EditorActiveModelWrapper(editorModel)

// Use old ViewModel methods
wrapper.insertText("block-123", "text")
wrapper.updateBlockContent("block-123", "new content")
wrapper.focusBlock("block-123")

// Or access original ActiveModel
wrapper.activeModel.processAction(EditorAction.Save)
```

## Performance Considerations

### Batch Operations

Use batch operations for multiple related actions:

```kotlin
// Good: Batch multiple related operations
activeModel.batch {
    processAction(EditorAction.InsertText("block1", "Title"))
    processAction(EditorAction.FormatText("block1", titleRange, TextFormat.HEADING_1))
    processAction(EditorAction.CreateBlock(pageId, "Content", parentId = "block1"))
}

// Avoid: Multiple individual operations
processAction(EditorAction.InsertText("block1", "Title"))
processAction(EditorAction.FormatText("block1", titleRange, TextFormat.HEADING_1))
processAction(EditorAction.CreateBlock(pageId, "Content", parentId = "block1"))
```

### State Observation

Use derived StateFlows for specific data needs:

```kotlin
// Efficient: Specific state observation
val focusedBlock = editorModel.state.map { it.focusedBlock }.distinctUntilChanged()

// Less efficient: Full state observation when only part is needed
val fullState = editorModel.state
```

### Memory Management

Monitor performance and memory usage:

```kotlin
// Get performance metrics
val metrics = editorModel.getPerformanceMetrics()
println("State size: ${metrics["stateSize"]}")
println("Batch depth: ${metrics["batchDepth"]}")

// Create performance report
val report = editorModel.createPerformanceReport()
if (report.memoryUsage > 10_000_000) { // 10MB
    // Consider state cleanup
}
```

## Testing

### State Testing

```kotlin
@Test
fun `should update block content correctly`() {
    // Arrange
    val initialState = ActiveEditorState.empty()
    val action = EditorAction.UpdateBlock("block-123", "new content")
    
    // Act
    val result = runBlocking {
        editorModel.reduce(initialState, action)
    }
    
    // Assert
    assertTrue(result.isSuccess)
    assertEquals("new content", result.getOrNull()?.focusedBlock?.content)
}
```

### Batch Testing

```kotlin
@Test
fun `should execute batch operations efficiently`() {
    var batchStartCount = 0
    var batchEndCount = 0
    
    editorModel.batch {
        batchStartCount++
        processAction(EditorAction.InsertText("block1", "Hello"))
        processAction(EditorAction.InsertText("block1", " World"))
        batchEndCount++
    }
    
    assertEquals(1, batchStartCount)
    assertEquals(1, batchEndCount)
    assertEquals("Hello World", editorModel.state.value.focusedBlock?.content)
}
```

## Debugging

### State Validation

```kotlin
// Validate migrated state
val report = editorModel.validateMigration()
if (!report.isValid) {
    println("Migration errors: ${report.errors}")
}
if (report.warnings.isNotEmpty()) {
    println("Migration warnings: ${report.warnings}")
}
```

### Debug State Flow

```kotlin
// Monitor debug state
editorModel.createDebugStateFlow().collect { debugState ->
    println("Block count: ${debugState.blockCount}")
    println("Focused: ${debugState.focusedBlockId}")
    println("Has changes: ${debugState.hasUnsavedChanges}")
    if (debugState.validationErrors.isNotEmpty()) {
        println("Errors: ${debugState.validationErrors}")
    }
}
```

## Best Practices

1. **Use Batch Operations**: Group related actions for better performance
2. **Observe Specific State**: Use derived StateFlows instead of full state when possible
3. **Handle Errors**: Always observe error state and provide user feedback
4. **Validate State**: Use built-in validation to catch state inconsistencies
5. **Monitor Performance**: Use performance metrics to identify optimization opportunities
6. **Test Reducers**: Test state transformations in isolation
7. **Migrate Gradually**: Use compatibility wrapper for incremental migration

## Troubleshooting

### Common Issues

1. **State Not Updating**: Ensure actions are processed in correct scope
2. **Memory Leaks**: Monitor StateFlow subscriptions and cancel when appropriate
3. **Performance Issues**: Use batch operations and optimize state observation
4. **Migration Problems**: Use validation reports to identify issues
5. **Undo/Redo Issues**: Ensure commands properly implement undo/redo methods

### Debug Tools

- **State Validation**: `editorModel.validateMigration()`
- **Performance Monitoring**: `editorModel.getPerformanceMetrics()`
- **Debug State Flow**: `editorModel.createDebugStateFlow()`
- **Error Observation**: `editorModel.error` StateFlow