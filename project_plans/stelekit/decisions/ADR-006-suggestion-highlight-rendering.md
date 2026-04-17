# ADR-006: Suggestion Highlight Rendering Approach

**Status**: Proposed  
**Date**: 2026-04-12  
**Deciders**: Tyler Stapler  
**Feature**: Page Term Highlighting

---

## Context

The page-term-highlighting feature must render suggestion spans — plain-text substrings that match an existing page name — with a visual distinction that is:

1. Clearly different from existing wikilinks (which use `linkColor` + `FontWeight.Medium`)
2. Non-obtrusive — the hint should not distract from reading
3. Actionable — users should understand it is tappable
4. Implementable without platform-specific code (Compose Multiplatform, `commonMain`)

Three rendering approaches were considered:

| Approach | Implementation | Drawbacks |
|---|---|---|
| Extend `MarkdownEngine` tokenizer with new `InlineToken.PageSuggestion` | Add one sealed subclass; add one case in `parseMarkdownWithStyling` | Minimal — all existing call sites unchanged |
| Separate `AnnotatedString` composition pass after `parseMarkdownWithStyling` | Post-process the returned `AnnotatedString`; insert new spans | `AnnotatedString` is immutable; post-processing requires full reconstruction |
| Dedicated `PageSuggestionLayer` composable drawn on top of `BasicText` | Canvas `drawBehind` overlay matching character offsets | Requires `TextLayoutResult` introspection; complex to keep in sync |

The tokenizer extension is the cleanest option. The `MarkdownEngine.tokenizeMarkdown` function already operates on a priority-ordered list of `RawMatch` objects derived from regex scans. A new `PageSuggestion` token type fits naturally into this model: it is injected into `Text` gaps after wikilinks and other structural tokens have claimed their ranges, so exclusion zone handling is straightforward.

### Dashed Underline Limitation

Logseq's original Clojure/ClojureScript implementation uses a CSS `text-decoration: underline dashed` style for suggestion spans. Compose `SpanStyle.textDecoration` supports only `None`, `Underline`, and `LineThrough` — no dashed variant. Three options:

| Option | Fidelity | Effort |
|---|---|---|
| Solid underline with reduced opacity | Low | Trivial |
| `drawBehind` custom dashed underline on `BasicText` wrapper | High | ~1 day; complex layout math |
| Light background tint (`color.copy(alpha = 0.08f)`) without underline | Medium | Trivial |

For v1, use a **solid underline + light background tint** combination. This is visually distinct from wikilinks (which use no underline and `FontWeight.Medium`) and from URLs (which use `TextDecoration.Underline` without background). The dashed-underline enhancement is logged as a future improvement.

---

## Decision

Render suggestion spans by extending the existing `tokenizeMarkdown` + `parseMarkdownWithStyling` pipeline in `MarkdownEngine.kt`:

1. Add `InlineToken.PageSuggestion(displayText: String, canonicalPageName: String)` to the sealed class.
2. Extend `tokenizeMarkdown` to accept an optional `AhoCorasickMatcher?` and inject `PageSuggestion` tokens into `Text` gaps.
3. In `parseMarkdownWithStyling`, render `PageSuggestion` as `SpanStyle(textDecoration = TextDecoration.Underline, background = suggestionColor.copy(alpha = 0.08f))` with annotation tag `PAGE_SUGGESTION_TAG = "PAGE_SUGGESTION"`.
4. `WikiLinkText` handles `PAGE_SUGGESTION_TAG` taps and calls `onSuggestionClick(canonicalPageName, tapOffset)`.
5. All new parameters are optional with default no-op / null values — zero impact on existing call sites.

### Annotation Encoding

The `PAGE_SUGGESTION_TAG` annotation value encodes three fields to avoid maintaining separate state for popup positioning:

```
"${canonicalPageName}|${matchStart}|${matchEnd}"
```

`WikiLinkText` splits on `|` and parses the integers. This is compact and avoids introducing a mutable state map keyed by character offset.

---

## Consequences

**Positive**:
- The `MarkdownEngine` extension is additive — no existing token types or annotations are changed.
- `parseMarkdownWithStyling` is a pure function; the suggestion extension keeps it pure (takes matcher as parameter, returns `AnnotatedString`).
- The `remember` key on `parseMarkdownWithStyling` already includes all styling parameters; adding `suggestionMatcher` to the key is one line.
- No platform-specific code; no Canvas introspection.

**Negative / Trade-offs**:
- Dashed underline is not achievable in Compose `SpanStyle` without custom drawing. The solid underline + tint is a visual compromise. Logged as `FUTURE: dashed suggestion underline via drawBehind`.
- The annotation encoding (`"name|start|end"`) is a minor hack. If page names contain `|`, parsing breaks. Mitigation: percent-encode `|` in the page name component, or use a dedicated two-annotation approach (`PAGE_SUGGESTION_NAME` + `PAGE_SUGGESTION_RANGE`). The simpler single-annotation approach is acceptable for v1 given that `|` in page names is rare and `Validation.validateName` does not explicitly allow it.

**Rejected alternatives**:
- Post-processing `AnnotatedString`: the type is immutable after construction; rebuilding it from scratch in a second pass doubles the allocation cost and loses the clean separation of concerns.
- `PageSuggestionLayer` composable overlay: ties rendering to `TextLayoutResult` coordinates, which change on every recomposition when text changes. Keeping the overlay in sync is fragile and expensive.

---

## Implementation Notes

- File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MarkdownEngine.kt`
- File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/BlockViewer.kt`
- File: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/LinkSuggestionPopup.kt` (new)
- Constant: `PAGE_SUGGESTION_TAG = "PAGE_SUGGESTION"` added alongside `WIKI_LINK_TAG` in `MarkdownEngine.kt`
- Future: replace solid underline with dashed via `Paragraph.paintTo` or a custom `InlineTextContent` when Compose adds native support
