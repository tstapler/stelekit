# Plan: fix-inline-hyperlink-rendering

## Approach

Three focused edits, all in `commonMain`, each with a targeted test gate. No changes to `MarkdownEngine`, `WikiLinkText`, `TableBlock`, or `Lexer` token types — the fix is confined to `InlineParser.kt`.

---

## Task 1 — Fix `hasMatchingLabelClose` to handle `BLOCK_REF_CLOSE`

**File:** `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/InlineParser.kt`

The forward lookahead that guards the full label-parse must accept `BLOCK_REF_CLOSE` as a valid URL-closer (the first `)` of the pair closes the URL; the second is trailing prose).

```kotlin
// BEFORE
while (currentToken.type != TokenType.R_PAREN && currentToken.type != TokenType.EOF && currentToken.type != TokenType.NEWLINE) {
    advance()
}
found = currentToken.type == TokenType.R_PAREN

// AFTER
while (currentToken.type != TokenType.R_PAREN
    && currentToken.type != TokenType.BLOCK_REF_CLOSE
    && currentToken.type != TokenType.EOF
    && currentToken.type != TokenType.NEWLINE) {
    advance()
}
found = currentToken.type == TokenType.R_PAREN || currentToken.type == TokenType.BLOCK_REF_CLOSE
```

---

## Task 2 — Fix URL-collection loop in `parseLink` to handle `BLOCK_REF_CLOSE`

**File:** `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/InlineParser.kt`

When the URL-scanning loop inside `parseLink` encounters `BLOCK_REF_CLOSE`, the first `)` closes the URL and the second `)` must be "put back" for the caller to consume as trailing prose.

```kotlin
// BEFORE (inside parseLink, URL scanning section):
val urlSb = StringBuilder()
while (currentToken.type != TokenType.R_PAREN && currentToken.type != TokenType.EOF && currentToken.type != TokenType.NEWLINE) {
    urlSb.append(currentToken.text(source))
    advance()
}
if (currentToken.type == TokenType.R_PAREN) {
    advance() // consume )
    return UrlLinkNode(url = urlSb.toString(), text = labelNodes)
}

// AFTER:
val urlSb = StringBuilder()
while (currentToken.type != TokenType.R_PAREN
    && currentToken.type != TokenType.BLOCK_REF_CLOSE
    && currentToken.type != TokenType.EOF
    && currentToken.type != TokenType.NEWLINE) {
    urlSb.append(currentToken.text(source))
    advance()
}
if (currentToken.type == TokenType.R_PAREN) {
    advance() // consume )
    return UrlLinkNode(url = urlSb.toString(), text = labelNodes)
}
if (currentToken.type == TokenType.BLOCK_REF_CLOSE) {
    // )) — first ) closes the URL, push the second ) back as a pending R_PAREN
    // by restoring the lexer to the character after the first )
    val closeToken = currentToken
    lexer.restoreState(Lexer.State(closeToken.start + 1, false))
    currentToken = lexer.nextToken()
    return UrlLinkNode(url = urlSb.toString(), text = labelNodes)
}
```

> Note: This requires `Lexer.State` to be accessible from `InlineParser`. Verify that `Lexer.State` is `internal` or `public`; adjust visibility if needed.

---

## Task 3 — Add regression tests for the double-paren case

**File:** `kmp/src/commonTest/kotlin/dev/stapler/stelekit/parsing/InlineLinksSpec.kt`

Add a new section after the existing BUG P0 block:

```kotlin
// -------------------------------------------------------------------------
// Markdown links inside surrounding parentheses  ([text](url))
// -------------------------------------------------------------------------

@Test
fun `markdown-link - link immediately followed by surrounding close paren`() {
    // ([Kichler](https://www.kichler.com/...)) — the )) at the end should not
    // prevent URL parsing; the second ) is trailing prose, not part of the URL.
    val result = parse("([Kichler](https://www.kichler.com/customer-care/newsroom/landscape-lighting-just-got-reinvented))")
    val urlNodes = result.filterIsInstance<UrlLinkNode>()
    assertEquals(1, urlNodes.size, "Expected UrlLinkNode for link inside surrounding parens")
    assertEquals("https://www.kichler.com/customer-care/newsroom/landscape-lighting-just-got-reinvented", urlNodes[0].url)
    val labelText = urlNodes[0].text.filterIsInstance<TextNode>().joinToString("") { it.content }
    assertEquals("Kichler", labelText)
    // The trailing ) from the outer parens should be a TextNode, not consumed into the URL
    val textNodes = result.filterIsInstance<TextNode>()
    assertTrue(textNodes.any { it.content == ")" }, "Trailing ) from surrounding parens must survive as TextNode")
}

@Test
fun `markdown-link - link at end of sentence with single trailing paren`() {
    val result = parse("See (the [docs](https://example.com)) for details.")
    val urlNodes = result.filterIsInstance<UrlLinkNode>()
    assertEquals(1, urlNodes.size, "Expected UrlLinkNode inside parenthesised text")
}
```

---

## Task 4 — Remove BUG markers from now-passing tests

Once Tasks 1–3 are implemented and tests pass, remove the `(BUG P0)` suffix from the 8 test names in `InlineLinksSpec.kt` and remove the stale `// KNOWN BUG P0` section comment. Do NOT add `@Ignore` — the tests are now expected to pass.

---

## Task 5 — Verify no regression in block-ref parsing

Run the existing block-ref tests:
```bash
bazel test //kmp:business_tests --test_filter=InlineLinksSpec
```
Specifically confirm `((uuid))` and `((ref-1)) and ((ref-2))` still produce `BlockRefNode`. The `parseBlockRef` path is entered via `BLOCK_REF_OPEN` and is not affected by the changes above (which only affect the URL-scanning sub-path of `parseLink`).

---

## Task 6 — Table-cell integration smoke test

Add or extend `TableBlockLayoutTest` (Robolectric, `androidUnitTest`) with a cell that contains a markdown link, verifying the rendered semantics contain a "URL" annotation at the link position:

```kotlin
@Test
fun `table cell with markdown hyperlink renders link annotation`() {
    // Input: | [Kichler](https://www.kichler.com) |
    // The AnnotatedString produced by WikiLinkText must have a "link" annotation over "Kichler"
    ...
}
```

---

## Adversarial review

**Risk: `BLOCK_REF_CLOSE` push-back breaks in edge case `(url)))`**

If a URL legitimately ends in `)` and is followed by `))` prose (three closing parens total), the first `))` is consumed as `BLOCK_REF_CLOSE`. The push-back restores to position+1, re-lexing the second `)` as `R_PAREN` — but the first `)` from `BLOCK_REF_CLOSE` has already been treated as the URL-closer, leaving one `)` unaccounted for before the pushed-back `)`. Net effect: URL is captured up to and not including the first `)`; the remaining `))` becomes two `TextNode(")")`. This is acceptable behavior for a rare edge case — the alternative (keeping the URL's trailing `)`) would require heuristic count-matching which is out of scope.

**Risk: `Lexer.State` visibility**

If `Lexer.State` is `private`, the push-back cannot be performed from `InlineParser`. Mitigation: expose a package-private `fun Lexer.rewindOneByte(cursor: Int)` or change `State` to `internal`. This is a one-line visibility change.

**Risk: Performance of `hasMatchingLabelClose` with new BLOCK_REF_CLOSE check**

Adding two more token-type checks to tight loops has negligible cost — O(1) comparison per iteration. The guard was added specifically to keep the common case O(n) instead of exponential; this change preserves that property.

---

## Estimated effort

| Task | Estimate |
|------|---------|
| Fix `hasMatchingLabelClose` | 30 min |
| Fix URL-collection loop + push-back | 1h |
| Add double-paren regression tests | 30 min |
| Remove BUG markers from 8 tests | 15 min |
| Verify block-ref regression | 15 min |
| Table-cell integration smoke test | 1h |
| **Total** | **~3.5h** |
