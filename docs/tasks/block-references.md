# Block References Implementation Plan

## Objective
Enable users to reference other blocks using the `((uuid))` syntax. The referenced block's content should be rendered in place of the reference.

## Context
Logseq allows blocks to be reused via references. Currently, the KMP implementation supports `[[Wiki Links]]` but not `((Block References))`. The UUID generation logic is already in place.

## Scope (Atomic)
- **Parser**: Detect `((uuid))` syntax.
- **Repository**: Add capability to fetch a block by its UUID.
- **UI**: Render the referenced block's content (text only for this iteration).

## Prerequisites
- [x] Deterministic UUID generation
- [x] BlockRepository infrastructure

## Atomic Steps

### 1. Parser Support (1h) ✅
- **File**: `kmp/src/commonMain/kotlin/com/logseq/kmp/parsing/LogseqParser.kt`
- **Task**: Update the AST/Parser to recognize `((...))` pattern.
- **Output**: A new `BlockReference` token or node type in the parsed content.

### 2. Repository Lookup (1h) ✅
- **File**: `shared/src/commonMain/kotlin/logseq/repository/BlockRepository.kt`
- **Task**: Add `getBlockByUuid(uuid: String): Flow<Block?>` to the interface.
- **Validation**: Unit test fetching a known block by UUID.

### 3. UI Rendering (2h) ✅
- **File**: `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/BlockRenderer.kt`
- **Task**: Update `BlockRenderer` to handle block reference nodes.
- **Logic**: Use a `LaunchedEffect` or injected provider to fetch the referenced block's content using the UUID.
- **Visual**: Display the referenced text, distinct from normal text (e.g., usually underlined or in a container).

## Validation Strategy
1. Create a block "Source".
2. Create a block "Ref: ((source-uuid))".
3. Verify "Ref: Source" is displayed.
