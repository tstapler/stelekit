# ADR-002: Block Renderer Dispatch Pattern

**Status**: Accepted
**Date**: 2026-04-13
**Feature**: Render All Markdown Blocks

---

## Context

`BlockItem.kt` currently has a single view-mode code path that calls `BlockViewer(content = block.content)` regardless of block type. After ADR-001 introduces `Block.blockType`, the view-mode branch needs a dispatch mechanism to route each block type to an appropriate composable.

There are two natural dispatch sites: `BlockItem` itself (orchestrator composable) or inside `BlockViewer` (current rendering composable).

### Options considered

**Option A — Dispatch inside `BlockViewer`**

`BlockViewer` already holds the rendering contract. Extend it with a `blockType` parameter and add the dispatch internally.

Rejected because:
- `BlockViewer` is currently a focused `WikiLinkText`-based text renderer. Adding non-text composables (images, tables, code blocks with `SelectionContainer`) turns it into a mega-composable with mixed concerns.
- `BlockViewer` is called with `content: String` only; it would need additional parameters (e.g., `imageLoader`, `onLinkClick`) that some block types require but others do not.

**Option B — Dispatch in `BlockItem` (chosen)**

`BlockItem` is already the orchestrator that assembles gutter + editor/viewer and manages all callbacks. Adding a `when (block.blockType)` branch in the view-mode `else` clause keeps the dispatch at the layer that already owns all necessary callbacks.

**Option C — Separate `BlockRenderer` composable**

Extract a `BlockRenderer` composable between `BlockItem` and the individual block composables, containing only the dispatch `when`.

Not chosen for now because the single `when` expression in `BlockItem` is not complex enough to justify an extra abstraction layer. Revisit if the dispatch grows beyond ~15 branches.

---

## Decision

Dispatch block rendering in `BlockItem` via a `when` expression on `block.blockType` in the view-mode branch. Each arm calls a dedicated composable (`HeadingBlock`, `CodeFenceBlock`, `BlockquoteBlock`, `ThematicBreakBlock`, `OrderedListItemBlock`, `TableBlock`, `ImageBlock`). Unknown or unsupported `blockType` values fall through to `BlockViewer` as a safe default.

```kotlin
} else {
    when {
        block.blockType == "thematic_break" -> ThematicBreakBlock(onStartEditing = onStartEditing)
        block.blockType.startsWith("heading:") -> HeadingBlock(
            content = block.content,
            level = block.blockType.substringAfter("heading:").toIntOrNull() ?: 1,
            onStartEditing = onStartEditing,
            modifier = Modifier.weight(1f),
        )
        block.blockType.startsWith("code_fence:") -> CodeFenceBlock(
            content = block.content,
            language = block.blockType.substringAfter("code_fence:"),
            onStartEditing = onStartEditing,
            modifier = Modifier.weight(1f),
        )
        block.blockType == "blockquote" -> BlockquoteBlock(
            content = block.content,
            onStartEditing = onStartEditing,
            onLinkClick = onLinkClick,
            modifier = Modifier.weight(1f),
        )
        block.blockType.startsWith("ordered_list_item:") -> OrderedListItemBlock(
            content = block.content,
            number = block.blockType.substringAfter("ordered_list_item:").toIntOrNull() ?: 1,
            onStartEditing = onStartEditing,
            onLinkClick = onLinkClick,
            modifier = Modifier.weight(1f),
        )
        block.blockType == "table" -> TableBlock(
            content = block.content,
            onStartEditing = onStartEditing,
            modifier = Modifier.weight(1f),
        )
        else -> BlockViewer(
            content = block.content,
            textColor = textColor,
            linkColor = linkColor,
            resolvedRefs = resolvedRefs,
            onLinkClick = onLinkClick,
            onStartEditing = onStartEditing,
            modifier = Modifier.weight(1f),
            isShiftDown = isShiftDown,
            onShiftClick = onShiftClick,
            suggestionMatcher = suggestionMatcher,
            onSuggestionClick = { ... },
            onSuggestionRightClick = { ... },
        )
    }
}
```

### `contentType` in `LazyColumn`

Wherever `BlockItem` is rendered inside a `LazyColumn` `items` block, add:

```kotlin
items(
    items = blocks,
    key = { it.uuid },
    contentType = { it.blockType }
) { block ->
    BlockItem(block = block, ...)
}
```

Compose uses `contentType` to match composable slots in the pool. Blocks of the same type reuse the same slot, reducing composition work on scroll. Without `contentType`, the pool matches slots positionally — as long as the block list is stable this is fine, but with mixed types the first scroll past a type boundary will always recompose.

---

## Consequences

**Positive**:
- Dispatch is colocated with the existing edit-mode dispatch in `BlockItem` — easy to reason about
- Safe default (`else` → `BlockViewer`) means new block types do not crash before a composable is implemented
- `contentType` in `LazyColumn` reduces recomposition on pages with mixed block types

**Negative**:
- `BlockItem` grows in length; may need extraction to `BlockRenderer` in a follow-up if the `when` grows beyond ~15 arms
- The string-matching `when` (using `startsWith`) is less ergonomic than matching on a sealed class; however `Block.blockType` is a String (per ADR-001) so this is intentional

**Neutral**:
- Each arm passes `onStartEditing` so tapping any block type enters edit mode — consistent UX
- The `else` branch preserves all existing `BlockViewer` parameters, so bullet/paragraph blocks have zero regression risk
