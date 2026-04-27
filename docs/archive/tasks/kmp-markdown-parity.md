# Feature Plan: KMP Markdown Parser Parity

## Epic Overview
**User Value**: Enables full cross-platform support (Mobile, Desktop, Web) with a single codebase by replacing the legacy C++/WASM `mldoc` parser with a pure Kotlin Multiplatform implementation. This ensures consistent behavior across all platforms and simplifies the build process.

**Success Metrics**:
- **Parity**: 100% functional parity with legacy parser for Properties, Timestamps, and Structure.
- **Performance**: Parsing speed within acceptable range of legacy parser (target < 50ms for average pages).
- **Stability**: Zero regressions in block structure parsing.

**Scope**:
- **Included**: Properties drawers (`:PROPERTIES:`), `id::`/`collapsed::`, `SCHEDULED`/`DEADLINE`, loose indentation repair.
- **Excluded**: Full Org-mode support (focus is Markdown), advanced query parsing (handled elsewhere).

**Constraints**:
- Must use `org.jetbrains:markdown` as the base parser.
- Must be pure Kotlin (CommonMain).
- Must not rely on `mldoc` bindings.

## Architecture Decisions

### ADR-001: Pre-processing for Indentation Repair
- **Context**: Legacy `mldoc` is lenient with indentation (e.g., "loose" lists), while `org.jetbrains:markdown` (CommonMark) is strict.
- **Decision**: Implement a `MarkdownPreprocessor` step to normalize indentation before passing text to the AST parser.
- **Rationale**: Modifying the JetBrains parser's Lexer/Parser to be lenient is high-risk and complex. Normalizing the input text (e.g., fixing off-by-one spaces) is safer, easier to test, and keeps the core parser standard.
- **Consequences**: Slight performance overhead for pre-processing string manipulation.

### ADR-002: Explicit Metadata Fields in Data Model
- **Context**: `SCHEDULED`, `DEADLINE`, `id`, and `collapsed` are first-class concepts in Logseq, not just generic properties.
- **Decision**: Update `ParsedBlock` to include explicit fields for these metadata, or ensure they are reliably accessible in the `properties` map with standardized keys.
- **Rationale**: Explicit fields provide type safety and clarity for downstream consumers (DB sync, UI).
- **Patterns**: Domain Model.

## Story Breakdown

### Story 1: Advanced Metadata Parsing [1 week]
**User Value**: Users can see and edit block properties, scheduling, and deadlines correctly on all platforms.
**Acceptance Criteria**:
- `:PROPERTIES:` ... `:END:` drawers are parsed and hidden from content.
- `id::` and `collapsed::` are extracted correctly.
- `SCHEDULED:` and `DEADLINE:` timestamps are parsed.
- Metadata is removed from the visible block `content`.

### Story 2: Indentation & Structure Robustness [1 week]
**User Value**: Users' existing notes with "loose" formatting are displayed correctly without breaking hierarchy.
**Acceptance Criteria**:
- Lists with non-standard indentation (e.g., 1 space instead of 2) are nested correctly.
- Mixed tab/space indentation is handled gracefully.

## Atomic Task Decomposition

### Task 1.1: Model Update & Timestamp Parsing [Micro - 2h] ✅
**Objective**: Update `ParsedBlock` to support timestamps and implement extraction logic.
**Context Boundary**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/model/ParsedModels.kt`
- `kmp/src/commonMain/kotlin/com/logseq/kmp/parser/TimestampParser.kt` (New)
- `kmp/src/commonMain/kotlin/com/logseq/kmp/parser/MarkdownParser.kt`
**Prerequisites**: None.
**Implementation Approach**:
1. Add `scheduled: String?` and `deadline: String?` to `ParsedBlock`.
2. Create `TimestampParser` object/class with regex logic.
3. Integrate into `MarkdownParser` loop.
**Validation**: Unit tests for `TimestampParser` with various date formats.

### Task 1.2: Properties Drawer Parsing [Small - 3h] ✅
**Objective**: Implement robust property parsing including `:PROPERTIES:` drawers.
**Context Boundary**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/parser/PropertiesParser.kt` (New)
- `kmp/src/commonMain/kotlin/com/logseq/kmp/parser/MarkdownParser.kt`
**Prerequisites**: Task 1.1 (for integration flow).
**Implementation Approach**:
1. Create `PropertiesParser` to handle both inline `key:: val` and drawer syntax.
2. Logic to detect `:PROPERTIES:` start and `:END:` end.
3. Strip drawer lines from final block content.
**Validation**: Test cases with drawers, inline props, and mixed content.

### Task 1.3: Indentation Preprocessor [Medium - 3h] ✅
**Objective**: Normalize markdown input to ensure strict CommonMark compliance for lists.
**Context Boundary**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/parser/MarkdownPreprocessor.kt` (New)
- `kmp/src/commonMain/kotlin/com/logseq/kmp/parser/MarkdownParser.kt`
**Prerequisites**: None.
**Implementation Approach**:
1. Create `MarkdownPreprocessor` class.
2. Implement `normalize(text: String): String`.
3. Logic: Scan lines, detect list markers, enforce 2-space/4-space hierarchy.
**Validation**: Test cases with "loose" markdown vs expected "strict" markdown.

### Task 1.4: Integration & Parity Testing [Small - 2h] ✅
**Objective**: Verify full parser pipeline against legacy scenarios.
**Context Boundary**:
- `kmp/src/commonTest/kotlin/com/logseq/kmp/parser/MarkdownParserTest.kt`
**Prerequisites**: Tasks 1.1, 1.2, 1.3.
**Implementation Approach**:
1. Create comprehensive test suite.
2. Add test cases covering all new features (timestamps, drawers, indentation).
3. Verify `ParsedPage` output structure.
**Validation**: All tests pass.

## Known Issues

### 🐛 Performance Risk: Regex Overhead [SEVERITY: Medium]
**Description**: Heavy use of Regex for properties and timestamps on every block might slow down parsing for large pages.
**Mitigation**:
- Compile Regex patterns (static/singleton).
- Use simple string checks (`startsWith`, `contains`) before running Regex.
- **Files**: `PropertiesParser.kt`, `TimestampParser.kt`.

### 🐛 Edge Case: Nested Drawers [SEVERITY: Low]
**Description**: Malformed markdown might have nested `:PROPERTIES:` or missing `:END:`.
**Mitigation**:
- Implement strict state machine or simple stack for drawer parsing.
- Fail gracefully (treat as text) if structure is invalid.

## Dependency Visualization
```
[Task 1.1: Model/Timestamps] --> [Task 1.2: Properties]
       |
       v
[Task 1.3: Preprocessor] ------> [Task 1.4: Integration Test]
```

## Context Preparation Guide
- **Load**: `MarkdownParser.kt`, `ParsedModels.kt`.
- **Understand**: Logseq's block structure (properties, scheduled/deadline), CommonMark list parsing rules.
