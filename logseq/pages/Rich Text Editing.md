# Rich Text Editing

Implementation patterns and techniques for WYSIWYG text editing with formatting, inline content, and cross-platform consistency.

## Current Limitations (2025)

### Compose Multiplatform Constraints
- **No Inline Content**: `TextField` and `BasicTextField` don't support images, code blocks, or other inline elements
- **Single Field Limitation**: Cannot create monolithic rich text editor with mixed content types
- **Selection Issues**: Cross-block selection requires custom implementation
- **Platform Differences**: Text editing behavior varies between Android and iOS

### Workaround Patterns
- **Block-Based Architecture**: Document as list of separate blocks (text, images, etc.)
- **Hybrid Approach**: Text fields for simple editing, WebView for complex content
- **Future Planning**: Architecture designed to migrate to single field when supported

## Available Libraries

### Compose Rich Editor
- **Platform Support**: Android, iOS, Desktop, Web
- **Features**: Bold, Italic, Underline, Links, Code blocks, Lists
- **State Management**: `RichTextState` with `rememberRichTextState()`
- **Import/Export**: HTML and Markdown support
- **Limitations**: Still constrained by underlying `TextField` limitations

### Custom Implementation
- **Text Styling**: Span-based styling using `AnnotatedString`
- **Paragraph Styles**: Alignment, indentation, spacing
- **Link Handling**: URL detection and interaction
- **List Support**: Ordered and unordered lists with nesting

## Implementation Patterns

### State Management
```kotlin
// Rich text state with formatting
data class RichTextValue(
    val text: String,
    val spans: List<TextSpan>,
    val selection: TextRange
)

// State management with undo/redo
class RichTextState {
    private val undoStack = ArrayDeque<RichTextOp>()
    private val redoStack = ArrayDeque<RichTextOp>()
    
    fun applyStyle(range: TextRange, style: TextStyle)
    fun toggleBold()
    fun toggleItalic()
    // etc.
}
```

### Block-Based Architecture
```kotlin
sealed interface Block {
    val id: String
}

data class TextBlock(
    override val id: String,
    val content: TextFieldValue,
    val format: TextFormat
) : Block

data class ImageBlock(
    override val id: String,
    val uri: String,
    val alt: String?,
    val width: Int?
) : Block
```

### Selection and Editing
- **Local Selection**: Within single text blocks
- **Cross-Block Selection**: Document-level selection state
- **Caret Navigation**: Proper handling of block boundaries
- **Keyboard Shortcuts**: Platform-specific normalization

## Performance Optimization

### Virtual Scrolling
- **LazyColumn**: Efficient rendering of large documents
- **Item Recycling**: Reuse composables for off-screen blocks
- **Keyed Updates**: Prevent unnecessary recomposition

### Text Measurement
- **Pre-computation**: Cache text measurements for common styles
- **Incremental Updates**: Only remeasure changed portions
- **Background Processing**: Offload text layout to background threads

### State Updates
- **Minimal Recomposition**: Only update changed blocks
- **Debounced Input**: Delay rapid-fire text changes
- **Batch Operations**: Group multiple style changes

## Cross-Platform Considerations

### Platform-Specific Behavior
- **IME Integration**: Different input method behavior across platforms
- **Selection Handles**: Native vs custom selection affordances
- **Keyboard Navigation**: Arrow keys and shortcuts vary by platform
- **Text Rendering**: Font rendering and spacing differences

### Abstraction Strategies
- **Common Interface**: Platform-agnostic editor API
- **Expect/Actual**: Platform-specific implementations
- **Adapter Pattern**: Bridge platform differences to common API

## Advanced Features

### Collaboration
- **CRDT Integration**: Conflict-free concurrent editing
- **Cursors**: Shared cursor positions
- **Presence**: Real-time user presence indicators
- **Conflict Resolution**: Automatic merge of concurrent changes

### Import/Export
- **Markdown**: Bidirectional Markdown conversion
- **HTML**: Rich HTML import and export
- **PDF**: Export to PDF with proper formatting
- **Custom Formats**: Plugin-defined formats

### Accessibility
- **Screen Readers**: Proper content description
- **High Contrast**: Support for high contrast modes
- **Dynamic Type**: Respect user font size preferences
- **Keyboard Navigation**: Full keyboard accessibility

## Testing Strategies

### Unit Tests
- **State Management**: Undo/redo behavior
- **Text Operations**: Insert, delete, style operations
- **Edge Cases**: Empty documents, large documents
- **Platform Differences**: Test on all target platforms

### Integration Tests
- **User Workflows**: Complete editing scenarios
- **Performance**: Large document handling
- **Memory Usage**: Memory leak detection
- **Cross-Platform**: Consistent behavior verification

## Related Concepts
[[Editor Architecture]], [[Compose Multiplatform]], [[Text Rendering]], [[Virtual Scrolling]], [[CRDT]]

## References
- [[Knowledge Synthesis - 2026-01-25]] - Rich text editing patterns and library options

## Tags
#[[Editor]] #[[User Interface]] #[[Text Processing]] #[[Cross-Platform]] #[[Performance]]