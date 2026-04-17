# ADR-002: Inline Math in Compose — InlineContent Slot vs Separate Box Overlay

**Status**: Accepted
**Date**: 2026-04-16
**Feature**: LaTeX / Math Rendering

---

## Context

Inline LaTeX (`$E=mc^2$`) appears mid-sentence inside `BasicText`. Compose's `AnnotatedString` is a flat string with `SpanStyle` / `StringAnnotation` markers — it cannot host arbitrary `@Composable` children. Two approaches exist for inserting non-text content inline:

- **Option A — `inlineContent` map on `BasicText`**: Compose Multiplatform 1.5+ provides `BasicText(text, inlineContent = mapOf("math_0" to InlineTextContent(...) { MathRenderer(...) }))`. A placeholder character `\uFFFD` (Unicode replacement character) is inserted at the formula position in the `AnnotatedString`; Compose measures and places the composable at that position during layout.
- **Option B — `Box` overlay**: Render math formulas as absolutely-positioned `Box` overlays above the `BasicText`. Requires measuring text offsets from `TextLayoutResult` and positioning a `Popup`/`Box` manually.

## Decision

**Option A — `inlineContent` map on `BasicText`, with placeholder characters injected at `LatexInlineNode` positions.**

`MarkdownEngine.kt` changes:
- `renderNode` for `LatexInlineNode` appends a placeholder string `"\uFFFD"` to the `AnnotatedString` and adds a `StringAnnotation` tag `"MATH_INLINE"` with the formula as the item value.
- `WikiLinkText` (in `BlockViewer.kt`) builds the `inlineContent: Map<String, InlineTextContent>` by scanning `AnnotatedString` annotations for `"MATH_INLINE"` tags and creating one `InlineTextContent` entry per unique formula.
- `BasicText` in `WikiLinkText` gains the `inlineContent` parameter.

For edit mode (`BlockEditor.kt`) inline math is not rendered — the raw `$formula$` text is shown in the `BasicTextField` as-is (the `applyMarkdownStylingForEditor` path already handles this via monospace styling).

## Rationale

| Criterion | Option A (inlineContent) | Option B (overlay) |
|---|---|---|
| Vertical alignment | Compose handles baseline alignment automatically | Must manually calculate baseline from `TextLayoutResult` |
| Reflow on text change | Handled by Compose layout pass | Must re-measure on every recomposition |
| Accessibility | Semantics can be applied inside `InlineTextContent` | Hidden overlay is invisible to semantics tree |
| Compose API support | First-class (CMP 1.5+) | No API support; uses `Box + offset` hacks |
| Complexity | Moderate — placeholder injection in AnnotatedString builder | High — `TextLayoutResult` offset arithmetic |

Option A is the idiomatic Compose approach and handles multi-line reflow, baseline alignment, and accessibility without bespoke layout code.

## Consequences

**Positive:**
- Math renders inline at the correct text baseline.
- Recomposition is handled by Compose's normal diffing — no manual invalidation.
- Semantics tree includes math content for accessibility.

**Negative / Watch-outs:**
- The `\uFFFD` placeholder is a real Unicode character. If `InlineTextContent` is not provided (e.g. in edit mode or on a platform with a no-op `MathRenderer`), the replacement character is visible. Mitigation: in `applyMarkdownStylingForEditor` and on iOS, render the raw formula string in monospace instead of inserting a placeholder.
- `InlineTextContent` requires an explicit `Placeholder` with a fixed `width` and `height`. The width/height must be provided before the composable is measured — for JVM `jlatexmath` this requires a two-pass render (first measure, then layout). Mitigation: Use a `MathRenderer` that accepts a `size: Dp` output parameter and defaults to `lineHeight × 1.2` for the initial layout pass.
- Each unique formula produces one `InlineTextContent` entry. A block with `n` distinct formulas allocates `n` entries in the map. This is bounded by the number of inline nodes per block, which is typically small.

## Patterns Applied

- **Decorator**: `WikiLinkText` is decorated with math content awareness without modifying `MarkdownEngine.kt`'s output contract.
- **Template Method**: `renderNode` in `MarkdownEngine.kt` is extended with a new branch — existing branches are unchanged.
