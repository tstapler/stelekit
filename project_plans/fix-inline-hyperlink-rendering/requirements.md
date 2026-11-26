# Requirements: fix-inline-hyperlink-rendering

## Problem Statement

Markdown inline hyperlinks (`[text](url)`) are not rendered as clickable links inside pipe-table cells in SteleKit. Instead, the raw markdown syntax appears as literal text — the label, square brackets, URL, and any surrounding parentheses all render verbatim. The screenshot shows two failure modes:

1. `[Kichler](https://www.kichler.com/...)` inside a table cell renders as raw text with the link label and broken-up URL visible.
2. When a markdown link appears inside surrounding parentheses like `([Kichler](url))`, the double-closing `))` is tokenised by the Lexer as `BLOCK_REF_CLOSE` rather than two separate `R_PAREN` tokens, preventing the URL parser from finding the URL's closing paren at all.

This is a P0 regression documented in `InlineLinksSpec.kt` with 8 known-failing test cases marked `(BUG P0)`.

## Scope

**In scope:**
- Fix `InlineParser.parseLink` so `[text](url)` markdown links produce `UrlLinkNode` correctly
- Fix `hasMatchingLabelClose` to handle `BLOCK_REF_CLOSE` (`))`) when the second `)` is from surrounding prose rather than a Logseq block ref
- Ensure the existing 8 `(BUG P0)` tests in `InlineLinksSpec.kt` now pass
- Add regression tests for the `([link](url))` double-paren case
- Verify table cells render links correctly (integration path: `TableBlock` → `WikiLinkText` → `MarkdownEngine` → `InlineParser`)

**Out of scope (secondary, file separately):**
- `TableParser.splitRow` splitting cells on `|` inside a URL — only affects URLs containing literal `|`, not the reported case
- Sheet/inline-edit path (`InlineTableBlock`) — that path shares the same `InlineParser` and will benefit automatically

## Acceptance Criteria

1. All 8 `(BUG P0)` tests in `kmp/src/commonTest/kotlin/dev/stapler/stelekit/parsing/InlineLinksSpec.kt` pass without being marked `@Ignore`.
2. `InlineParser.parse("[Kichler](https://www.kichler.com/customer-care/newsroom/landscape-lighting-just-got-reinvented))")` produces a `UrlLinkNode` for the link portion (the surrounding text `)` renders as a `TextNode`, not a merged `BLOCK_REF_CLOSE`).
3. `InlineParser.parse("([Kichler](https://www.kichler.com/...))")` correctly yields a `TextNode("(")`, a `UrlLinkNode`, and a `TextNode(")")` — not raw text.
4. `TableCell` renders the Kichler link in the screenshot as an underlined, tappable hyperlink on Android (verified via Robolectric `TableBlockLayoutTest` or a new `TableCellHyperlinkTest`).
5. No regression in existing block-ref parsing: `((uuid))` still produces `BlockRefNode`, not a broken link.
6. No regression in wiki-link parsing: `[[Page Name]]` still produces `WikiLinkNode`.
7. `bazel test //kmp:business_tests` and `bazel test //kmp/modules:...` pass for affected modules.

## Related Files

| File | Role |
|------|------|
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/InlineParser.kt` | Bug: `parseLink` / `hasMatchingLabelClose` |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/lexer/Lexer.kt` | `))`→`BLOCK_REF_CLOSE` tokenisation |
| `kmp/src/commonTest/kotlin/dev/stapler/stelekit/parsing/InlineLinksSpec.kt` | 8 P0 failing tests (ready to pass) |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/TableBlock.kt` | Rendering path uses `WikiLinkText` per cell |
| `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/MarkdownEngine.kt` | Downstream renderer (correct; no change needed) |
