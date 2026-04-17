# Native KMP Graph Parser (Markdown + OrgMode)

## Epic Overview
**Goal**: Replace the current `org.intellij.markdown` parser and legacy `mldoc` dependency with a high-performance, native Kotlin Multiplatform (KMP) parser.
**Value**:
- **Performance**: Optimized for mobile devices and large graphs (zero-copy, minimal allocation).
- **Maintainability**: Pure Kotlin codebase, easier to debug and extend than C++/WASM.
- **Feature Parity**: Full support for Logseq's specific Markdown flavor (indentation, properties, advanced queries) and future Org-mode support.
- **KMP Native**: Removes platform-specific dependencies (WASM/JNI).

## Architecture Decisions

### ADR 001: Parsing Strategy
- **Context**: We need a parser that is fast, memory-efficient, and capable of handling Logseq's indentation-based structure and complex inline syntax.
- **Decision**: **Hand-written Recursive Descent (Block) + Pratt Parser (Inline)**.
- **Rationale**:
    - **Performance**: Parser combinators in Kotlin often incur overhead due to object allocation and lambda capturing. A hand-written parser allows for tight loops and primitive handling.
    - **Control**: Recursive descent is natural for the block hierarchy. Pratt parsing is ideal for handling operator precedence in inline elements (e.g., bold inside italic, links).
    - **Error Recovery**: Easier to implement robust error recovery in a hand-written parser.

### ADR 002: Zero-Copy Lexing
- **Context**: Creating strings for every token generates significant garbage collector pressure.
- **Decision**: **Index-based Tokenization**.
- **Rationale**: The lexer and parser will operate on the original `CharSequence` using start/end indices. Substrings will only be created when absolutely necessary (e.g., for the final AST node content).

### ADR 003: Unified AST
- **Context**: We need to support both Markdown and Org-mode.
- **Decision**: **Sealed Class Hierarchy** representing semantic Logseq concepts (Block, Page, Property) rather than format-specific nodes.
- **Rationale**: Allows the rest of the application to be agnostic to the source format.

## Story Breakdown

### Story 1: Core Infrastructure & Lexer
Establish the project structure, AST definitions, and the base lexer.
- **Goal**: Can tokenize a string into a stream of tokens without allocations.

### Story 2: Block Parser (Structure)
Implement the recursive descent parser to handle the indentation hierarchy (outlines).
- **Goal**: Correctly parse nested lists, headings, and block properties.

### Story 3: Inline Parser (Logseq Syntax)
Implement the Pratt parser for inline elements.
- **Goal**: Parse `[[links]]`, `((block-refs))`, `**bold**`, `*italic*`, `#tags`.

### Story 4: Integration & Parity
Replace the existing parser and verify parity with `mldoc`.
- **Goal**: Pass all existing tests and new parity tests.

## Atomic Task Decomposition

### Phase 1: Infrastructure & Lexer

- [ ] **Task 1.1**: Define AST Sealed Classes
    - Create `dev.stapler.stelekit.parsing.ast` package.
    - Define `ASTNode`, `BlockNode`, `InlineNode`.
    - **Context**: `kmp/src/commonMain/kotlin/com/logseq/kmp/parsing/ast/AST.kt`
    - **Output**: Sealed class hierarchy.

- [ ] **Task 1.2**: Implement Tokenizer Interface & Token Class
    - Define `Token` (type, start, end).
    - Define `TokenType` enum (TEXT, L_BRACKET, R_BRACKET, HASH, etc.).
    - **Context**: `kmp/src/commonMain/kotlin/com/logseq/kmp/parsing/lexer/Token.kt`

- [ ] **Task 1.3**: Implement Zero-Copy Lexer
    - Implement `Lexer` class taking `CharSequence`.
    - Implement `nextToken()` logic.
    - **Context**: `kmp/src/commonMain/kotlin/com/logseq/kmp/parsing/lexer/Lexer.kt`

### Phase 2: Block Parsing

- [ ] **Task 2.1**: Implement Block Parser Skeleton
    - Create `BlockParser` class.
    - Implement basic loop for line-by-line processing.
    - **Context**: `kmp/src/commonMain/kotlin/com/logseq/kmp/parsing/BlockParser.kt`

- [ ] **Task 2.2**: Handle Indentation & Hierarchy
    - Implement logic to track indentation levels.
    - Build the tree structure based on indentation.
    - **Context**: `BlockParser.kt`

- [ ] **Task 2.3**: Parse Block Properties
    - Detect `property:: value` lines at the start of a block.
    - Attach properties to the `BlockNode`.
    - **Context**: `BlockParser.kt`

### Phase 3: Inline Parsing

- [ ] **Task 3.1**: Implement Pratt Parser Infrastructure
    - Create `InlineParser` class.
    - Define `Parselet` interface (prefix, infix).
    - **Context**: `kmp/src/commonMain/kotlin/com/logseq/kmp/parsing/InlineParser.kt`

- [ ] **Task 3.2**: Implement Basic Formatting Parselets
    - Bold (`**`), Italic (`*`), Strike (`~~`).
    - **Context**: `InlineParser.kt`

- [ ] **Task 3.3**: Implement Link & Reference Parselets
    - WikiLinks (`[[...]]`).
    - Block Refs (`((...))`).
    - Tags (`#tag`).
    - **Context**: `InlineParser.kt`

### Phase 4: Integration

- [ ] **Task 4.1**: Create Parser Facade
    - Create `LogseqParser` class that orchestrates Block and Inline parsing.
    - **Context**: `kmp/src/commonMain/kotlin/com/logseq/kmp/parsing/LogseqParser.kt`

- [ ] **Task 4.2**: Add Unit Tests
    - Port relevant tests from `MarkdownParserTest`.
    - Add specific edge case tests for Logseq syntax.
    - **Context**: `kmp/src/commonTest/kotlin/com/logseq/kmp/parsing/ParserTest.kt`

- [ ] **Task 4.3**: Benchmark & Optimize
    - Measure performance against `org.intellij.markdown`.
    - Optimize hot paths.

