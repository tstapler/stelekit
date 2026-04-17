# ADR-001: Replace indexOf with Forward-Only Cursor for TextNode Position Tracking

**Status**: Accepted  
**Date**: 2026-04-14  
**Feature**: Multi-Word Term Highlighting & Unlinked References

---

## Context

`MarkdownEngine.renderNode` (line 80) locates where each `TextNode`'s content sits within the original block string so that `PAGE_SUGGESTION_TAG` annotations receive correct `contentStart`/`contentEnd` offsets:

```kotlin
val origStart = ctx.original.indexOf(node.content, ctx.searchFrom)
    .takeIf { it >= 0 } ?: ctx.searchFrom
ctx.searchFrom = origStart + node.content.length
```

This uses `String.indexOf(substring, startIndex)`, which scans forward from `searchFrom` and returns the *first occurrence at or after that position*. For blocks where the same token appears more than once (e.g., `"New Year New Year"`, `"test test test"`), the Kotlin `InlineParser` emits multiple `TextNode` objects with identical `.content` strings. The first node is mapped correctly, but subsequent identical nodes will also resolve to the *same* `indexOf` result (the first occurrence) because `searchFrom` advances past it only on the first hit — each duplicate maps to whatever the next `indexOf` hit is from the current `searchFrom`, which may be behind the true position if the parser reuses identical content values in a non-sequential order.

The `extractSuggestions` function in the same file has the identical pattern at line 407:

```kotlin
val nodeOrigStart = content.indexOf(node.content, searchFrom).takeIf { it >= 0 } ?: continue
```

The downstream effect is that `SuggestionState.contentStart` / `contentEnd` are incorrect for multi-word page names in blocks with repeated text, causing the `[[…]]` wrap to land at the wrong character position.

---

## Decision

Replace the `indexOf`-based lookup with a **monotonically advancing cursor** that accumulates the running offset by adding each node's content length unconditionally, matching the order in which `InlineParser` emits nodes.

The cursor does not perform a string search at all: it trusts that the parser emits `TextNode` objects in document order and that their concatenated lengths equal the original string length. This is guaranteed by how `InlineParser` tokenises input — every character in the input is covered by exactly one `InlineNode`'s content or structural marker.

Concrete change in `renderNode` (depth == 0 branch):

```kotlin
// Before (fragile indexOf)
val origStart = ctx.original.indexOf(node.content, ctx.searchFrom)
    .takeIf { it >= 0 } ?: ctx.searchFrom
ctx.searchFrom = origStart + node.content.length

// After (forward cursor)
val origStart = ctx.searchFrom
ctx.searchFrom += node.content.length
```

The same change applies to `extractSuggestions`:

```kotlin
// Before
val nodeOrigStart = content.indexOf(node.content, searchFrom).takeIf { it >= 0 } ?: continue
searchFrom = nodeOrigStart + node.content.length

// After
val nodeOrigStart = searchFrom
searchFrom += node.content.length
```

---

## Consequences

**Positive**:
- Eliminates the duplicate-text position bug for both `renderNode` and `extractSuggestions`.
- Simpler code: removes a fallback branch (`?: ctx.searchFrom`) that masked failures silently.
- O(1) per node vs. O(content length) for `indexOf`: negligible in practice but strictly better.

**Negative / Risk**:
- The cursor now trusts that `InlineParser` emits nodes covering the full input in order. If `InlineParser` ever skips characters (e.g., strips leading/trailing whitespace from a TextNode), the cursor will drift from the true offset. This must be validated with a targeted test: parse a known multi-word block and assert that `origStart + node.content.length` equals the position of the next node's first character.
- The existing `indexOf` fallback (`?: ctx.searchFrom`) silently recovered from parser inconsistencies. The new cursor has no fallback — a parser bug will propagate as a wrong offset. This is acceptable because a wrong offset that is *silently swallowed* is harder to detect and debug than one that produces a visibly wrong link wrap.

**Validation**:
- New unit test in `ExtractSuggestionsTest.kt`: block with repeated tokens (e.g., `"New Year New Year"`) with page "New Year" — assert match offsets are `[0,8]` and `[9,17]`, not two matches both at `[0,8]`.
- Existing `ExtractSuggestionsTest` suite must continue to pass.
