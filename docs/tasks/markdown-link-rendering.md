# Markdown Link Rendering — Backlog Item

**Status**: Backlog (not started). Needs a reproduction before design.

## Problem

Standard Markdown links, `[label](https://example.com)` and `[label](url "title")`, do not render correctly in view mode. This is a bug in the existing parser/renderer path, not a new feature. The parser already has an `MdLinkNode` (`parser/MarkdownParser.kt`) and reconstructs it to text, so the gap is most likely in how the node is turned into rendered, clickable content.

## Scope

- Render the label as link text (styled and tappable); do not show the raw `[..](..)` syntax.
- Open the URL on tap/click on every platform (Desktop, Android, iOS, Web), through the existing URL-open path.
- Support the optional title (`"title"`) as a tooltip or long-press hint where the platform has one.
- Handle edge cases: URLs containing parentheses, escaped brackets in the label, empty label, relative paths and `file:` links, labels that contain inline formatting (bold/italic/code), and links inside list items and tables.
- Editor: entering edit mode shows the raw Markdown unchanged; saving must not rewrite or re-escape the link (no round-trip drift).
- Do not change double-bracket page links, `#tags` or bare-URL autolinks.

## Acceptance criteria

1. First step: reproduce, then record in this doc which inputs render wrongly and where it fails (parse, AST-to-annotated-string, or click handling).
2. A property/round-trip test: parse then reconstruct returns the original text for generated links (see `kotest-property` guidance in CLAUDE.md).
3. A rendering test (Robolectric under `androidUnitTest` unless it needs real pixels) that asserts the label is shown, the raw syntax is not, and a click opens the expected URL.
4. Round-trip test through `GraphWriter`: a page containing links is saved and re-loaded byte-identical.

## Pointers

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parser/MarkdownParser.kt` (`MdLinkNode`)
- `docs/tasks/parser-feature-spec.md`, `docs/tasks/parser-gap-analysis.md`
