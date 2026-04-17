# Logseq-KMP Feature Map

## Functional Domain Mapping
Mapping of source files in `kmp/src/commonMain` to their functional domains.

### Core Domain Models
*   `kmp/src/commonMain/kotlin/com/logseq/kmp/model/Models.kt`
    *   **Feature**: Defines core data structures.
    *   **Key Symbols**: `Block`, `Page`, `Graph`.

### Data Persistence & Graph Management
*   `kmp/src/commonMain/kotlin/com/logseq/kmp/db/GraphLoader.kt`
    *   **Feature**: Loading and initializing the graph database.
    *   **Key Symbols**: `GraphLoader`.
*   `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/`
    *   **Feature**: Data access layer.

### Editor & Outliner
*   `kmp/src/commonMain/kotlin/com/logseq/kmp/editor/`
    *   **Feature**: Text editing, cursor management, and input handling.
*   `kmp/src/commonMain/kotlin/com/logseq/kmp/outliner/`
    *   **Feature**: Outliner logic (indentation, folding, block movement).

### Parsing & Syntax
*   `kmp/src/commonMain/kotlin/com/logseq/kmp/parser/`
    *   **Feature**: Markdown and Org-mode syntax parsing.
*   `kmp/src/commonMain/kotlin/com/logseq/kmp/parsing/`
    *   **Feature**: AST generation and processing.

### User Interface (Compose Multiplatform)
*   `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/`
    *   **Feature**: Visual components and layout.
