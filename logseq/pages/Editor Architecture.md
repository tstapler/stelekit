# Editor Architecture

Structural patterns and design principles for implementing rich text editors in modern cross-platform applications, particularly with Kotlin Multiplatform and Compose.

## Core Architectural Patterns

### Block-Based Editor
- **Rationale**: Compose TextField doesn't support inline content (images, etc.) in 2025
- **Structure**: Document modeled as ordered list of blocks (`TextBlock`, `ImageBlock`, etc.)
- **State Management**: Uses `SnapshotStateList<Block>` for efficient updates and recomposition
- **Rendering**: `LazyColumn` with keyed items for performance with large documents

### Single Field Editor
- **Current Limitation**: Not fully viable in Compose Multiplatform yet
- **Future**: Expected with `BasicTextField2` and text engine improvements
- **Advantages**: Simpler selection model, easier text flow
- **Challenges**: Complex inline content handling, performance with large documents

## State Management Patterns

### Document State
- **Immutable Blocks**: Each block has immutable identity but mutable content
- **Command Pattern**: Operations represented as command objects for undo/redo
- **Single Source of Truth**: Central state management with clear update patterns

### Selection State
- **Local Selection**: Within individual text blocks using `TextFieldValue.selection`
- **Cross-Block Selection**: Document-level state with `Cursor` and `DocSelection`
- **Platform Handling**: Normalize keyboard and mouse events across platforms

## Performance Optimization

### Virtual Scrolling
- **LazyColumn**: Efficient rendering of large documents
- **Keyed Items**: Prevent unnecessary recomposition of unchanged blocks
- **Bring Into View**: Ensure cursor/selection remains visible during navigation

### State Efficiency
- **State Hoisting**: Move state up to appropriate level to minimize recomposition
- **Derived State**: Use `derivedStateOf` for computed values to avoid recalculation
- **Snapshot State**: Leverage Compose's snapshot state for efficient updates

## Cross-Platform Considerations

### Platform Differences
- **Text Input**: IME behavior and keyboard shortcuts vary by platform
- **Selection Affordances**: Native selection handles within blocks, custom chrome for cross-block
- **Performance Characteristics**: Different rendering performance on mobile vs desktop

### Abstraction Strategies
- **Common Interfaces**: Platform-agnostic interfaces for editor functionality
- **Expect/Actual**: Platform-specific implementations for text handling
- **Adapters**: Bridge between platform capabilities and common editor API

## Collaboration Support

### CRDT Integration
- **Mergeable Types**: Data structures that support conflict-free merging
- **Real-time Sync**: State synchronization across multiple clients
- **Conflict Resolution**: Automatic resolution of concurrent edits

### Offline-First Design
- **Local Storage**: Immediate persistence to local database
- **Sync Strategy**: Background synchronization with conflict resolution
- **Network Handling**: Graceful degradation when network unavailable

## Related Concepts
[[Kotlin Multiplatform]], [[Rich Text Editing]], [[CRDT]], [[Virtual Scrolling]], [[Command Pattern]]

## References
- [[Knowledge Synthesis - 2026-01-25]] - Comprehensive editor migration patterns and architectural considerations

## Tags
#[[Architecture]] #[[Editor]] #[[Cross-Platform]] #[[Performance]] #[[State Management]]