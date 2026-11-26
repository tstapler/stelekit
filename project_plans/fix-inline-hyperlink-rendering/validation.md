# Validation: fix-inline-hyperlink-rendering

## Traceability matrix

| Acceptance Criterion | Validated By |
|---------------------|-------------|
| 8 P0 tests in `InlineLinksSpec` pass | `bazel test //kmp:business_tests --test_filter=InlineLinksSpec` |
| `([Kichler](url))` produces `UrlLinkNode` + trailing `TextNode(")")` | New test in `InlineLinksSpec` (Task 3) |
| `((uuid))` still produces `BlockRefNode` | Existing block-ref tests in `InlineLinksSpec` |
| `[[Page Name]]` still produces `WikiLinkNode` | Existing wiki-link tests in `InlineLinksSpec` |
| Table cell renders link as tappable | New `TableBlockLayoutTest` or `TableCellHyperlinkTest` (Task 6) |
| Full business test suite green | `bazel test //kmp:business_tests` |

## Risks and mitigations

| Risk | Likelihood | Mitigation |
|------|-----------|-----------|
| `Lexer.State` not accessible from `InlineParser` | Low — both in `parsing` package | Make `State` `internal` (one-line change) |
| Push-back breaks for `url)))` (3 close parens) | Low — rare in practice | Acceptable degradation; documented in plan |
| Label double-advance bug for nested `[[link]]` in label | Medium — not in reported case | File as separate issue; add failing test to prevent silent regression |
| Performance regression from extra token-type checks | Very low | O(1) per iteration; benchmarks not needed |

## Pre-merge checklist

- [ ] `bazel test //kmp:business_tests` green
- [ ] `bazel test //kmp/modules:...` green for `ui_found` and `sketch_core` modules (may contain table or inline tests)
- [ ] `./gradlew testDebugUnitTest` green (Android; covers `TableBlockLayoutTest`)
- [ ] Screenshots of a table cell with `[text](url)` on Android reviewed visually (phone render via Robolectric `captureRoboImage`)
- [ ] Block-ref `((uuid))` test cases verified manually in demo graph

## Open questions

1. Does `hasMatchingLabelClose` need to handle `BLOCK_REF_CLOSE` in the label-scanning phase too (before the `]`)? A label containing `))` would stop the scan early. Likely not a real use case, but worth confirming with a test.
2. Is there a property-based test opportunity here? `Arb.string()` restricted to URL-safe chars wrapped in various paren patterns (`()`, `(())`, etc.) could catch combinatorial edge cases the hand-written tests miss.
3. The `TableParser.splitRow` function splits on literal `|` without escaping inside URLs. This is a secondary defect tracked separately — confirm it is out of scope for this item.
