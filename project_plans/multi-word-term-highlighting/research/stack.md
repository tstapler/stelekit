# Research: Stack — Multi-Word Highlighting Bug

## Summary

The `AhoCorasickMatcher` and `getUnlinkedReferences` regex logic are both correct for multi-word terms. The primary bug is fragile `indexOf`-based TextNode position mapping in `MarkdownEngine.renderNode` (line 80): when a block contains duplicate or repeated text, `indexOf` finds the wrong occurrence, producing incorrect `contentStart`/`contentEnd` offsets. This means the `[[…]]` wrap is inserted at the wrong position when the user accepts a multi-word suggestion. Secondary issue: the per-page unlinked-references SQL fallback uses LIKE rather than regex, causing false-positive returns for multi-word names.

## AhoCorasickMatcher Analysis

**Status: Correct for multi-word patterns.**

- `findAll` (line 86–109): standard Aho-Corasick search; word-boundary check (`isWordBoundary`) is applied only at the outer edges of the full matched term — internal spaces within "Andrew Underwood" are not treated as boundaries.
- `resolveOverlaps` (line 123–139): keeps leftmost-longest match per start position, then skips overlapping candidates. Correct.
- `PageNameIndex` (line 50–52): `.associate { it.name.lowercase() to it.name }` passes multi-word names as single patterns — no splitting on spaces occurs.

**No bug in the matcher.**

## MarkdownEngine Span Application

**Bug identified: ambiguous `indexOf`-based position tracking.**

`MarkdownEngine.kt` line 80 (`renderNode`):
```kotlin
val origStart = ctx.original.indexOf(node.content, ctx.searchFrom)
    .takeIf { it >= 0 } ?: ctx.searchFrom
ctx.searchFrom = origStart + node.content.length
```

**Problem**: When the InlineParser splits a multi-word name across separate `TextNode`s (word + space + word), and the same text appears more than once in the block, `indexOf` finds the first occurrence after `searchFrom`, not necessarily the one matching the current node. This produces a wrong `origStart`, which propagates into the `contentStart`/`contentEnd` values stored in the `PAGE_SUGGESTION_TAG` annotation.

In `renderGapWithSuggestions` (line 309): span offsets are calculated as `gapStart + match.start` and `gapStart + match.end` — these are correct relative to the gap, but if `gapStart` itself is wrong (due to the above), the stored offsets are wrong.

**Impact**: For any block where the same word appears more than once (e.g. "New Year New Year"), the position mapping for multi-word page names appearing later in the block can be off, causing the `[[…]]` wrap to land at the wrong location.

## BlockItem Position Mapping

**Status: Correct — downstream of the bug.**

`BlockItem.kt` lines 411–416 and 432–436 do a safe positional wrap:
```kotlin
val newContent = content.substring(0, safeStart) +
    "[[${pending.canonicalName}]]" +
    content.substring(safeEnd)
```

The coercion (`coerceAtMost`, `coerceIn`) prevents crashes, but if `contentStart`/`contentEnd` are wrong due to the MarkdownEngine issue, the wrap still lands incorrectly. No additional bug here.

## getUnlinkedReferences SQL Analysis

**Status: Mostly correct; minor false-positive risk in fallback path.**

The in-memory regex filter in `SqlDelightBlockRepository` uses:
```kotlin
val plainTextPattern = "\\b${Regex.escape(pageName)}\\b".toRegex(RegexOption.IGNORE_CASE)
```

`\b` on a phrase like `\bAndrew Underwood\b` works correctly — the boundary check applies at the outer edges only, and spaces are non-word characters so they don't block matching.

The FTS5 schema (`SteleDatabase.sq`) uses `porter unicode61` tokenizer, which stems words. The codebase does **not** use FTS5 for unlinked references — it uses the in-memory regex path instead, which is correct.

**Minor issue**: The SQL fallback `selectBlocksWithContentLikePaginated("%$pageName%", ...)` is a substring match with no phrase-boundary enforcement. For multi-word names this can over-return results, but the in-memory regex filter removes false positives before returning. Net effect: no false positives escape, but the SQL fetch may be unnecessarily broad.

## Identified Bugs / Gaps

- **`MarkdownEngine.kt:80`** — `indexOf(node.content, ctx.searchFrom)` is fragile for blocks containing repeated text. Multi-word page names split across parser TextNodes will get wrong `origStart` values when duplicate text exists in the block. **Severity: High.**
- **Missing test: `ExtractSuggestionsTest.kt`** — no test for blocks with repeated words (e.g. "New Year New Year"). Existing multi-word test (line 123–129) covers the happy path only.
- **Missing test: no `AhoCorasickMatcherTest.kt`** — the matcher has no dedicated test file; multi-word patterns, word-boundary edge cases, and overlap resolution are untested.

## Recommended Fixes

- **Fix `renderNode` position tracking (High Priority)**: Replace `indexOf` with a forward-only cursor that advances by `node.content.length` unconditionally (or uses absolute positions from the AST if the parser exposes them). The `searchFrom` approach is only safe when text is unique; a monotonically-advancing cursor is always safe.
- **Add `ExtractSuggestionsTest` cases for repeated text (Medium Priority)**: "Meeting Notes Meeting Notes", "Andrew Underwood and Andrew Underwood", case variants.
- **Add `AhoCorasickMatcherTest.kt` (Medium Priority)**: Cover multi-word patterns, outer-edge word boundaries, prefix-overlap (pages "KMP" and "KMP SDK"), case-insensitive matching, and `resolveOverlaps` correctness.
- **Tighten SQL fallback (Low Priority)**: Change `selectBlocksWithContentLikePaginated` to use `% pageName %` with space guards, or accept the current over-fetch and rely on the in-memory filter (current behaviour is functionally correct, just slightly wasteful).
