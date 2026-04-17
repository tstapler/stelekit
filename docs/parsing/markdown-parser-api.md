# Markdown Parser API

## Overview
The `MarkdownParser` interface abstracts the logic for converting raw text content into structured domain objects (`ParsedPage`, `ParsedBlock`). This allows for:
- Testing parsing logic in isolation.
- Supporting multiple parser implementations (e.g., regex-based, AST-based).
- Handling Logseq specific syntax (properties, tasks, advanced queries) consistently.

## Data Models

### ParsedPage
Intermediate representation of a page.
- `title`: Derived from filename or `title::` property.
- `properties`: Page-level properties (key-value pairs at the start of the file).
- `blocks`: Root level blocks.

### ParsedBlock
Intermediate representation of a block.
- `content`: The text content (stripped of bullet marker).
- `properties`: Block-level properties.
- `level`: Indentation level (0-based).
- `children`: Nested blocks (if the parser builds a tree).

## Interface

```kotlin
interface MarkdownParser {
    fun parse(content: String): ParsedPage
    fun parseBlock(rawBlock: String): ParsedBlock
}
```

## Implementation Notes
- The parser should handle both indentation-based hierarchy (tabs or spaces) and explicit parent/child relationships if defined in properties (though standard Logseq relies on indentation).
- Property parsing should support `key:: value` syntax.
- The parser should be resilient to malformed markdown where possible.
