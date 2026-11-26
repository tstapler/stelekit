# Research: fix-inline-hyperlink-rendering

## Rendering pipeline for table cell text

```
TableBlock (UI) → FlagOffTableBlock → TableCell → WikiLinkText
                                                     ↓
                                               MarkdownEngine.parseMarkdownWithStyling
                                                     ↓
                                               InlineParser(text).parseWithRanges()
                                                     ↓
                                               Lexer (tokenises input)
```

The rendering machinery in `MarkdownEngine.kt` and `WikiLinkText` correctly handles `UrlLinkNode` (underlined, `addStringAnnotation("link", node.url, ...)`). The bug is entirely upstream in `InlineParser` / `Lexer`.

## Root cause 1 — `InlineParser.parseLink` does not produce `UrlLinkNode`

`InlineParser.parseLink` has a **performance guard** (`hasMatchingLabelClose`) that does a cheap forward scan to verify `](url)` exists before the expensive recursive label parse:

```kotlin
if (!hasMatchingLabelClose()) {
    return TextNode("[")   // ← bug falls here
}
```

`hasMatchingLabelClose` scans forward looking for `TokenType.R_PAREN` as the URL closer:

```kotlin
while (currentToken.type != TokenType.R_PAREN && ...) { advance() }
found = currentToken.type == TokenType.R_PAREN
```

This fails for the common case in the screenshot because of Root Cause 2 below.

## Root cause 2 — Lexer greedily emits `BLOCK_REF_CLOSE` for `))` at URL boundary

`Lexer.kt` emits `BLOCK_REF_CLOSE` whenever it sees two consecutive `)`:

```kotlin
')' -> {
    if (cursor < length && input[cursor] == ')') {
        cursor++
        Token(TokenType.BLOCK_REF_CLOSE, start, cursor)   // consumes both )
    } else {
        Token(TokenType.R_PAREN, start, cursor)
    }
}
```

In the input `([Kichler](https://www.kichler.com/...))`  the URL's closing `)` and the surrounding prose's `)` form `))` — tokenised as one `BLOCK_REF_CLOSE`, consuming both. Neither `hasMatchingLabelClose` nor the URL-collection loop in `parseLink` checks for `BLOCK_REF_CLOSE`, so they never see `R_PAREN`, fail, and backtrack to `TextNode("[")`.

## Root cause 3 — Label-collection loop double-advance (secondary)

In `parseLink`, the label-collection loop:
```kotlin
while (currentToken.type != TokenType.R_BRACKET && ...) {
    labelSb.append(currentToken.text(source))
    val t = currentToken
    advance()                    // ← explicit advance
    labelNodes.add(parsePrefix(t))   // parsePrefix uses already-consumed `t`
}
```
`parsePrefix(t)` for simple TEXT/WS tokens is safe (just wraps the passed token). But if `t` is `L_BRACKET` (nested link), `parsePrefix` calls `parseLink`, which reads `currentToken` — which has already been advanced past `t`. This causes desynchronisation for nested brackets. This is a secondary issue (nested links in labels are rare), but the double-advance pattern is fragile.

## Existing test coverage

`kmp/src/commonTest/kotlin/dev/stapler/stelekit/parsing/InlineLinksSpec.kt` lines 220–312 contain **8 tests explicitly marked `(BUG P0)`**:

| Test name | What it asserts |
|-----------|----------------|
| `basic link does not reduce to bare bracket TextNode` | `[Visit Google](https://google.com)` → not `TextNode("[")` |
| `label and url are preserved in link node` | Produces `UrlLinkNode` with correct url and label |
| `https url scheme` | `[Docs](https://docs.example.com/page)` → `UrlLinkNode` |
| `http url scheme` | `[Legacy](http://old.example.com)` → `UrlLinkNode` |
| `unicode label` | `[日本語ページ](https://ja.example.com)` → `UrlLinkNode` |
| `inline code in label` | ``[`fn()`](https://api.example.com)`` → `UrlLinkNode` with `CodeNode` child |
| `embedded in surrounding text` | `Read [the guide](url) for more.` → 1 `UrlLinkNode` |
| `two links on one line` | `[A](url) and [B](url)` → 2 `UrlLinkNode` |

No test yet covers the double-paren case `([link](url))`.

## Impact surface

- All platforms (commonMain)
- Block-level text rendering via `WikiLinkText`
- Table cell text rendering (reported case)
- Sheet/inline-edit path shares the same `InlineParser` and benefits automatically
- Export path (`HtmlExporterUnitTest`) may also benefit if it passes through `InlineParser`

## Fix options considered

| Option | Assessment |
|--------|-----------|
| Fix `hasMatchingLabelClose` to treat `BLOCK_REF_CLOSE` as URL-close | Partial — still needs URL-collection loop fix |
| Fix URL-collection loop to stop on `BLOCK_REF_CLOSE` and push back one `)` | Needs lexer cursor access; feasible via `Lexer.State` |
| Modify Lexer to not combine `))` to `BLOCK_REF_CLOSE` inside URL context | Lexer is stateless — requires context-threading, high complexity |
| Re-lex just the boundary: when URL-scan hits `BLOCK_REF_CLOSE`, restore state to `token.start + 1` | Clean; uses existing `Lexer.saveState/restoreState`; keeps block-ref behavior intact elsewhere |

**Recommended**: Fix both `hasMatchingLabelClose` and the URL-collection loop in `parseLink` to handle `BLOCK_REF_CLOSE` by pushing back the second `)` using `Lexer.State(token.start + 1, false)`. This is surgical and does not affect `parseBlockRef`.
