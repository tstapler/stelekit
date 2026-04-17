# Validation Plan: SteleKit Export

**Phase**: 4 — Validation | **Date**: 2026-04-13
**Maps to**: `docs/tasks/stelekit-export.md`

---

## Test Strategy Summary

The export feature is a pure-function domain layer (no UI, no network) making it highly amenable to unit and integration testing. The strategy is:

1. **Unit tests in `commonTest`** for all four exporters and the block-ref expansion logic — these run on every platform and form the bulk of coverage.
2. **Integration tests in `jvmTest`** for the full pipeline (load graph → export → assert string) and for the JVM-specific clipboard `Transferable` implementation.
3. **Property-based tests in `commonTest`** for the round-trip invariant (Markdown) and the XSS-safety invariant (HTML).
4. **No UI tests for export** — the feature is wired into `StelekitViewModel` and `TopBar`, but the export logic itself has no Compose dependency and does not need Roborazzi screenshot tests.

All new export test classes live in either `commonTest` (platform-agnostic, no file I/O) or `jvmTest` (needs real file-loading or AWT clipboard). No export tests belong in `androidUnitTest` or `iosTest` for Phase 1.

---

## Unit Tests

### `ExportServiceTest`

**Source set**: `commonTest`
**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/export/ExportServiceTest.kt`

| # | Test name | Assertion |
|---|-----------|-----------|
| U-ES-01 | `collectBlockRefUuids returns UUIDs from block ref syntax` | A block with `content = "See ((abc-123)) for details"` yields `setOf("abc-123")` from `collectBlockRefUuids()`. |
| U-ES-02 | `collectBlockRefUuids returns empty set for plain text block` | A block with no `((...))` syntax yields `emptySet()`. |
| U-ES-03 | `collectBlockRefUuids aggregates across multiple blocks` | Three blocks each with one `BlockRefNode` yield a set of three distinct UUIDs. |
| U-ES-04 | `subtreeBlocks with single root returns root and all descendants` | Given a 3-level hierarchy, `subtreeBlocks(all, setOf(rootUuid))` returns all 3 levels. |
| U-ES-05 | `subtreeBlocks with leaf root returns only that block` | `subtreeBlocks(all, setOf(leafUuid))` returns exactly one block. |
| U-ES-06 | `subtreeBlocks with two non-adjacent roots returns both subtrees merged` | Two sibling roots each with one child → four blocks returned, depth-first ordered. |
| U-ES-07 | `subtreeBlocks with empty set returns empty list` | `subtreeBlocks(all, emptySet())` returns `emptyList()`. |
| U-ES-08 | `exportToString dispatches to registered exporter by formatId` | A stub exporter with `formatId = "stub"` returning `"stub-output"` is called; result string equals `"stub-output"`. |
| U-ES-09 | `exportToString returns failure for unknown formatId` | `exportToString(page, blocks, "nonexistent")` returns `Result.failure` (not a crash). |
| U-ES-10 | `resolveBlockRefs returns resolved text for known UUID` | Repository stub returns `"resolved text"` for a UUID; `resolveBlockRefs(setOf(uuid))` returns `mapOf(uuid to "resolved text")`. |
| U-ES-11 | `resolveBlockRefs returns empty map for dangling UUID` | Repository stub returns `null` for unknown UUID; result map is empty (not an exception). |

---

### `MarkdownExporterTest`

**Source set**: `commonTest`
**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/export/MarkdownExporterTest.kt`

| # | Test name | Assertion |
|---|-----------|-----------|
| U-MD-01 | `empty page produces H1 heading only` | `export(page, emptyList())` returns `"# Test Page\n\n"` (no frontmatter for empty properties). |
| U-MD-02 | `top-level block renders as paragraph not bullet` | Block with `level=0` and content `"Hello world"` renders as `"Hello world\n\n"` (no leading `- `). |
| U-MD-03 | `nested block renders as indented bullet` | Block with `level=1` and content `"child"` renders as `"- child\n"`. |
| U-MD-04 | `level-2 block renders with one-level indent` | Block with `level=2` renders as `"  - grandchild\n"` (2-space indent). |
| U-MD-05 | `tab-indented input normalizes to 2-space bullet indent` | The exporter emits 2-space-per-level regardless of original tab indentation in the source file. |
| U-MD-06 | `HighlightNode renders as bold` | `==highlighted==` in block content → `**highlighted**` in output. |
| U-MD-07 | `WikiLinkNode with no alias renders as [[target]]` | `[[Page Name]]` → `[[Page Name]]` kept verbatim. |
| U-MD-08 | `WikiLinkNode with alias renders as [[target\|alias]] or as alias text` | `[[Page\|display]]` → display text used, not raw `Page\|display`. |
| U-MD-09 | `BlockRefNode resolved renders inline text` | `((uuid))` with `resolvedRefs = mapOf("uuid" to "resolved text")` → `"resolved text"` in output. |
| U-MD-10 | `BlockRefNode dangling renders as [block ref]` | `((uuid))` with empty `resolvedRefs` → `"[block ref]"` in output. |
| U-MD-11 | `TODO marker renders as GFM checkbox unchecked` | Block content `"TODO Buy milk"` → `"- [ ] Buy milk"` in output. |
| U-MD-12 | `DONE marker renders as GFM checkbox checked` | Block content `"DONE Buy milk"` → `"- [x] Buy milk"` in output. |
| U-MD-13 | `NOW marker renders as bold text prefix` | Block content `"NOW Buy milk"` → `"**NOW** Buy milk"` (no GFM checkbox). |
| U-MD-14 | `YAML frontmatter emitted when page has properties` | Page with `properties = mapOf("type" to "index")` → output starts with `---\ntitle: Test Page\ntype: index\n---`. |
| U-MD-15 | `id key is excluded from YAML frontmatter` | Page with `properties = mapOf("id" to "some-uuid", "author" to "Jane")` → frontmatter contains `author:` but not `id:`. |
| U-MD-16 | `YAML value containing colon is double-quoted` | Property value `"My: value"` → YAML line `key: "My: value"` (quoted). |
| U-MD-17 | `YAML value containing [ is double-quoted` | Property value `"[[Page]]"` → YAML line `tags: "[[Page]]"` (quoted). |
| U-MD-18 | `YAML value containing & is double-quoted` | Property value `"A & B"` → YAML line `title: "A & B"` (quoted). |
| U-MD-19 | `property-only blocks are skipped in output` | Block with blank `content` and non-empty `properties` does not produce a line in the output. |
| U-MD-20 | `code fence block preserved verbatim` | Block content containing a fenced code block emits the fence and language identifier unchanged. |
| U-MD-21 | `journal page uses date as H1 instead of page name` | Page with `isJournal=true` and `journalDate=2026-04-13` → H1 is `# 2026-04-13`. |
| U-MD-22 | `namespace page name with / in [[link]] kept verbatim` | `[[Namespace/SubPage]]` → `[[Namespace/SubPage]]` unchanged (default wiki-link retention mode). |

---

### `PlainTextExporterTest`

**Source set**: `commonTest`
**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/export/PlainTextExporterTest.kt`

| # | Test name | Assertion |
|---|-----------|-----------|
| U-PT-01 | `empty page renders title line only` | `export(page, emptyList())` returns `"Test Page\n=========\n\n"` (underline matches name length). |
| U-PT-02 | `bold markup stripped` | Block content `"**bold text**"` → `"bold text"` (no asterisks). |
| U-PT-03 | `italic markup stripped` | Block content `"*italic text*"` → `"italic text"`. |
| U-PT-04 | `strikethrough markup stripped` | Block content `"~~struck~~"` → `"struck"`. |
| U-PT-05 | `WikiLinkNode renders as target text` | `[[Page Name]]` → `"Page Name"` (no brackets). |
| U-PT-06 | `WikiLinkNode with alias renders as alias` | `[[Page\|Display]]` → `"Display"`. |
| U-PT-07 | `hashtag renders without hash character` | `#tagName` → `"tagName"`. |
| U-PT-08 | `highlight markup stripped` | `==highlighted==` → `"highlighted"`. |
| U-PT-09 | `image node renders as [Image: alt text]` | `![alt text](url.png)` → `"[Image: alt text]"`. |
| U-PT-10 | `macro node renders as [name: args]` | `{{embed [[Page]]}}` → `"[embed: [[Page]]]"`. |
| U-PT-11 | `top-level blocks separated by blank lines` | Two top-level blocks produce a blank line between them in output. |
| U-PT-12 | `nested block indented by 2 spaces per level` | `level=1` block content is prefixed with `"  "`; `level=2` with `"    "`. |
| U-PT-13 | `nested blocks not separated by blank lines` | Two `level=1` sibling blocks appear with no blank line between them. |
| U-PT-14 | `BlockRefNode resolved renders inline text` | `((uuid))` with matching `resolvedRefs` → resolved text, no UUID. |
| U-PT-15 | `BlockRefNode dangling renders as [block ref]` | `((uuid))` with empty refs → `"[block ref]"`. |
| U-PT-16 | `task marker text prefix preserved` | Block content `"TODO task text"` → `"TODO task text"` (marker kept as plain text). |

---

### `HtmlExporterTest`

**Source set**: `commonTest` (for pure logic); `jvmTest` for integration cases requiring file I/O
**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/export/HtmlExporterTest.kt`

| # | Test name | Assertion |
|---|-----------|-----------|
| U-HT-01 | `empty page renders article with h1 only` | `export(page, emptyList())` contains `<h1>Test Page</h1>` and no `<ul>` or `<li>`. |
| U-HT-02 | `output is an HTML fragment, not a full document` | Output does not contain `<html>`, `<head>`, or `<body>` tags. |
| U-HT-03 | `top-level block with no children renders as paragraph` | Single `level=0` block with no children → `<p>block content</p>`. |
| U-HT-04 | `nested block renders inside ul li` | `level=0` parent with `level=1` child → `<ul><li>child content</li></ul>` within the parent's content. |
| U-HT-05 | `deep nesting produces balanced ul tags` | 3-level hierarchy (root → child → grandchild → back to root) → `<ul>` count equals `</ul>` count in output. |
| U-HT-06 | `code fence content is HTML-escaped` | Block with code fence containing `<script>alert(1)</script>` → output contains `&lt;script&gt;` not `<script>`. |
| U-HT-07 | `code fence emits pre code with language class` | Code fence with language `"kotlin"` → `<pre><code class="language-kotlin">`. |
| U-HT-08 | `WikiLinkNode renders as anchor tag` | `[[Page Name]]` → `<a href="#Page Name">Page Name</a>`. |
| U-HT-09 | `WikiLinkNode with alias uses alias as link text` | `[[Page\|Display]]` → `<a href="#Page">Display</a>`. |
| U-HT-10 | `XSS: page name in href is HTML-attribute-escaped` | Page named `"Hello <World>"` → `href` value contains `&lt;World&gt;` not `<World>`. |
| U-HT-11 | `bold renders as strong` | `**bold**` → `<strong>bold</strong>`. |
| U-HT-12 | `italic renders as em` | `*italic*` → `<em>italic</em>`. |
| U-HT-13 | `strikethrough renders as s` | `~~struck~~` → `<s>struck</s>`. |
| U-HT-14 | `highlight renders as mark` | `==highlight==` → `<mark>highlight</mark>`. |
| U-HT-15 | `inline code renders as code tag with escaped content` | `` `code` `` → `<code>code</code>`. |
| U-HT-16 | `TODO block adds todo class to li` | Block with `TaskMarkerNode("TODO")` → `<li class="todo">`. |
| U-HT-17 | `DONE block adds done class to li and checked checkbox` | Block with `TaskMarkerNode("DONE")` → `<li class="done">` with `<input type="checkbox" checked disabled>`. |
| U-HT-18 | `NOW/LATER/WAITING render as styled span` | `TaskMarkerNode("NOW")` → `<span class="task-marker now">NOW</span>`. |
| U-HT-19 | `BlockRefNode resolved renders in blockquote` | `((uuid))` resolved → `<blockquote data-block-ref="uuid">resolved text</blockquote>`. |
| U-HT-20 | `BlockRefNode dangling renders as unresolved-ref span` | `((uuid))` with no resolved text → `<span class="unresolved-ref">[block ref]</span>`. |
| U-HT-21 | `image node renders as img tag with escaped alt and src` | `![alt](url.png)` → `<img alt="alt" src="url.png">`. |
| U-HT-22 | `markdown link renders as anchor` | `[text](https://example.com)` → `<a href="https://example.com">text</a>`. |
| U-HT-23 | `ul open/close count matches for 6-level nesting` | 6-level deep hierarchy → string occurrences of `<ul>` equals occurrences of `</ul>`. |

---

### `JsonExporterTest`

**Source set**: `commonTest`
**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/export/JsonExporterTest.kt`

| # | Test name | Assertion |
|---|-----------|-----------|
| U-JS-01 | `output is valid JSON parseable by kotlinx.serialization` | `Json.decodeFromString<ExportRoot>(output)` does not throw. |
| U-JS-02 | `version field is 1` | Decoded `ExportRoot.version == 1`. |
| U-JS-03 | `exportedAt is present and non-blank` | Decoded `ExportRoot.exportedAt` is not blank. |
| U-JS-04 | `page metadata included in output` | `ExportRoot.page.name == "Test Page"` and `page.uuid` matches the input page UUID. |
| U-JS-05 | `nested blocks appear as children not flat list` | Child block at `level=1` appears inside `blocks[0].children`, not at top level of `blocks`. |
| U-JS-06 | `block content preserves raw Logseq syntax` | Block content containing `[[Page]]` and `((uuid))` is preserved verbatim in `BlockDto.content`. |
| U-JS-07 | `block properties included in output` | Block with `properties = mapOf("author" to "Jane")` → `BlockDto.properties["author"] == "Jane"`. |
| U-JS-08 | `id key is excluded from page properties in output` | Page with `properties = mapOf("id" to "some-uuid", "type" to "index")` → `PageDto.properties` lacks `"id"` key. |
| U-JS-09 | `id key is excluded from block properties in output` | Block with `properties = mapOf("id" to "some-uuid")` → `BlockDto.properties` is empty. |
| U-JS-10 | `output is pretty-printed` | Output string contains newlines and indentation (not single-line minified JSON). |
| U-JS-11 | `subtree export includes page metadata but only selected blocks` | Export of a subtree rooted at a mid-level block → `ExportRoot.page` contains full page; `ExportRoot.blocks` contains only the subtree. |
| U-JS-12 | `isJournal and journalDate fields present for journal pages` | Journal page → `PageDto.isJournal == true` and `PageDto.journalDate` is non-null. |
| U-JS-13 | `block position field matches input order` | Blocks with explicit `position` values → `BlockDto.position` preserves those values. |

---

### `HtmlUtilsTest`

**Source set**: `commonTest`
**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/export/HtmlUtilsTest.kt`

| # | Test name | Assertion |
|---|-----------|-----------|
| U-HU-01 | `ampersand escaped as &amp;` | `HtmlUtils.escape("a & b") == "a &amp; b"`. |
| U-HU-02 | `less-than escaped as &lt;` | `HtmlUtils.escape("<tag>") == "&lt;tag>"` (opening bracket only in this test; both in U-HU-03). |
| U-HU-03 | `script tag fully escaped` | `HtmlUtils.escape("<script>alert('xss')</script>") == "&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;"`. |
| U-HU-04 | `double quote escaped as &quot;` | `HtmlUtils.escape("say \"hello\"") == "say &quot;hello&quot;"`. |
| U-HU-05 | `single quote escaped as &#39;` | `HtmlUtils.escape("it's") == "it&#39;s"`. |
| U-HU-06 | `empty string returns empty string` | `HtmlUtils.escape("") == ""`. |
| U-HU-07 | `plain text with no special chars passes through unchanged` | `HtmlUtils.escape("Hello World") == "Hello World"`. |
| U-HU-08 | `emoji passes through unescaped` | `HtmlUtils.escape("Hello 👋") == "Hello 👋"`. |
| U-HU-09 | `Arabic text passes through unescaped` | `HtmlUtils.escape("مرحبا") == "مرحبا"`. |
| U-HU-10 | `escapeAttr delegates to escape` | `HtmlUtils.escapeAttr("<tag>") == "&lt;tag&gt;"` (same as `escape`). |

---

### Block Ref Expansion Tests

**Source set**: `commonTest`
**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/export/BlockRefExpansionTest.kt`

| # | Test name | Assertion |
|---|-----------|-----------|
| U-BR-01 | `resolved ref is inlined in Markdown output` | Block `"See ((uuid-a))"` with `resolvedRefs = mapOf("uuid-a" to "the target text")` → Markdown output contains `"the target text"`, not `"((uuid-a))"`. |
| U-BR-02 | `dangling ref renders as [block ref]` | Block with `((uuid-missing))` and empty `resolvedRefs` → output contains `"[block ref]"`. |
| U-BR-03 | `circular reference produces [circular ref] not stack overflow` | `resolvedRefs` map contains `"uuid-a"` pointing to content that itself contains `((uuid-a))` → expansion stops and emits `"[circular ref]"` after depth-3. |
| U-BR-04 | `depth limit of 3 prevents infinite expansion` | A chain A → B → C → A is resolved at most 3 levels deep; no StackOverflowError. |
| U-BR-05 | `non-UUID content between (( )) is treated as unresolvable` | Block content `"((not-a-uuid))"` where the value does not match UUID format → fallback to `"[block ref]"`. |
| U-BR-06 | `multiple refs in same block all resolved` | Block with two `BlockRefNode`s in the same `content` string → both are resolved/replaced independently. |

---

## Integration Tests

### End-to-End Export Pipeline

**Source set**: `jvmTest`
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/export/ExportIntegrationTest.kt`

These tests use `TestFixtures` and `InMemoryBlockRepository` — no disk I/O needed.

| # | Test name | Assertion |
|---|-----------|-----------|
| I-E2E-01 | `Markdown export of page with wiki links contains no raw Logseq block syntax` | Page with blocks containing `[[Page Name]]`, `((uuid))`, `key:: value` → exported Markdown contains no `((`, no `key::` in body, and correct `# ` heading. |
| I-E2E-02 | `plain text export strips all markup characters` | Page with bold, italic, highlight, and wiki link blocks → output contains none of `*`, `**`, `[[`, `]]`, `==`, `~~`. |
| I-E2E-03 | `HTML export escapes script tag in code block` | Block with code fence containing `<script>alert(1)</script>` → exported HTML contains `&lt;script&gt;` not `<script>`. |
| I-E2E-04 | `JSON export round-trips through kotlinx.serialization decoder` | `Json.decodeFromString<ExportRoot>(export(page, blocks))` succeeds without exception; decoded block count matches input. |
| I-E2E-05 | `subtree export excludes blocks above selected root` | Select a `level=1` block; export → output does not contain content from the `level=0` parent block. |
| I-E2E-06 | `export of page with properties includes YAML frontmatter in Markdown` | Page with `properties = mapOf("type" to "reference")` → Markdown output starts with `---\ntitle: ...`. |
| I-E2E-07 | `export of page with no blocks returns heading only in Markdown` | Page with `emptyList()` of blocks → Markdown output is exactly `# ${page.name}\n\n`. |
| I-E2E-08 | `export of journal page uses journal date as H1` | Journal page with `journalDate = LocalDate(2026, 4, 13)` → Markdown H1 is `# 2026-04-13`. |

### HTML Exporter Edge Cases (Integration)

**Source set**: `jvmTest`
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/export/HtmlExporterIntegrationTest.kt`

| # | Test name | Assertion |
|---|-----------|-----------|
| I-HT-01 | `6-level nesting produces valid ul structure` | Six-level block hierarchy → `<ul>` and `</ul>` counts are equal; no empty `<li>` tags. |
| I-HT-02 | `mixed TODO DONE NOW task markers all render` | Page with TODO, DONE, and NOW blocks → output contains both `class="todo"`, `class="done"`, and `class="task-marker now"`. |
| I-HT-03 | `page with no blocks produces minimal article fragment` | Empty page → output is `<article><h1>...</h1></article>` with no crash or empty `<ul>`. |

### Clipboard Write (JVM Smoke Test)

**Source set**: `jvmTest`
**File**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/export/ClipboardIntegrationTest.kt`

| # | Test name | Assertion |
|---|-----------|-----------|
| I-CB-01 | `HtmlStringSelection writeText round-trip` | `HtmlStringSelection("html", "plain")` written to AWT clipboard; reading back `DataFlavor.stringFlavor` returns `"plain"`. |
| I-CB-02 | `HtmlStringSelection HTML flavor round-trip` | Reading back `HTML_FLAVOR` returns `"html"`. |
| I-CB-03 | `getTransferData for unsupported flavor throws UnsupportedFlavorException` | Calling `getTransferData` with an unknown `DataFlavor` throws `UnsupportedFlavorException` (not NPE). |
| I-CB-04 | `getTransferDataFlavors returns both HTML and plain` | `HtmlStringSelection("h","p").transferDataFlavors` has length 2 and includes `HTML_FLAVOR` and `DataFlavor.stringFlavor`. |

Note: I-CB-01 and I-CB-02 require a display context (non-headless JVM). They should be annotated with a `@AssumeHeadful` guard or skipped via `GraphicsEnvironment.isHeadless()`.

---

## Property-Based Tests

### Round-Trip Property (Markdown Format)

**Source set**: `commonTest`
**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/export/MarkdownRoundTripPropertyTest.kt`

| # | Test name | Assertion |
|---|-----------|-----------|
| P-RT-01 | `exported Markdown contains no raw uuid refs for any resolved block` | For any page/block combination where all `BlockRefNode` UUIDs are in `resolvedRefs`, the exported Markdown string contains no `((` sequences. |
| P-RT-02 | `exported Markdown contains no key:: value lines in body` | For any page with blocks containing property nodes, the exported body (below the `---` frontmatter) contains no ` :: ` sequence. |
| P-RT-03 | `top-level block content appears verbatim without bullet prefix` | For any `level=0` block whose content is plain text (no special nodes), the content string appears in the Markdown output without a leading `- `. |

### No-XSS Property (HTML Format)

**Source set**: `commonTest`
**File**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/export/HtmlXssPropertyTest.kt`

| # | Test name | Assertion |
|---|-----------|-----------|
| P-XSS-01 | `arbitrary block content produces no unescaped < in HTML output` | For any non-empty block `content` string (generated), the exported HTML does not contain a bare `<` character outside of the intentional HTML structure tags (`<article>`, `<h1>`, `<p>`, `<ul>`, `<li>`, `<pre>`, `<code>`, `<strong>`, `<em>`, `<s>`, `<mark>`, `<a>`, `<img>`, `<blockquote>`, `<span>`, `<input>`, `<dl>`, `<dt>`, `<dd>`). |
| P-XSS-02 | `arbitrary block content produces no unescaped > outside HTML tags` | Same as P-XSS-01 for the `>` character. |

Implementation note: The property test can use a simple fuzzer that generates `content` strings containing `<`, `>`, `&`, `"`, `'` characters in random positions and asserts the above invariants.

---

## Edge Cases (from Pitfalls Research)

Each pitfall from `project_plans/stelekit-export/research/pitfalls.md` maps to at least one test case:

| Pitfall | Covered by |
|---------|------------|
| Circular block references | U-BR-03, U-BR-04 |
| `id::` property must not appear in output | U-MD-15, U-JS-08, U-JS-09 |
| Code fence with `<script>` content | U-HT-06, I-E2E-03 |
| Code fence with triple-backtick in rawContent | U-MD-20 (Markdown: 4-backtick fence), I-HT-01 |
| 8+ level deep nesting | U-HT-23 (6-level), I-HT-01 (6-level) — plan caps display at 6 levels |
| Unicode / emoji in block content | U-HU-08, U-HU-09 (passthrough in HtmlUtils) |
| Namespace page names with `/` in `[[links]]` | U-MD-22, U-HT-10 |
| YAML frontmatter value with `:` character | U-MD-16 |
| YAML frontmatter value with `[` character | U-MD-17 |
| Dangling block references | U-BR-02, U-MD-10, U-PT-15 |
| Properties already stripped from `Block.content` | U-MD-14 (reads `page.properties` map, not raw content) |
| `id::` proliferation on block properties | U-JS-09 |
| `[[Page\|alias]]` — alias used not raw target | U-MD-08, U-HT-09, U-PT-06 |
| `HtmlUtils.escape` missing | U-HU-01 through U-HU-10, P-XSS-01, P-XSS-02 |
| BlockRef DB amplification (bulk-fetch strategy) | U-ES-11 (resolveBlockRefs handles nulls), I-E2E-01 |
| Synchronous serialization on main thread | Covered by architecture (ExportService is `suspend`; tested via coroutine test harness in U-ES-08) |

---

## Coverage Requirements

| Component | Target coverage | Rationale |
|-----------|----------------|-----------|
| `HtmlUtils` | 100% branch | Security-critical; every escape path must be exercised |
| `MarkdownExporter` | 90%+ branch | Core output format; all inline node types covered |
| `PlainTextExporter` | 90%+ branch | All strip/render paths covered |
| `HtmlExporter` | 90%+ branch | All inline node types + list nesting logic |
| `JsonExporter` | 85%+ branch | Structure building + filter logic |
| `ExportService` | 85%+ branch | Dispatch, subtree filtering, ref collection |
| Block ref expansion (within exporters) | 85%+ branch | Circular guard, dangling fallback, depth limit |

---

## Test File Locations

| Test class | Source set | Path |
|------------|-----------|------|
| `ExportServiceTest` | `commonTest` | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/export/ExportServiceTest.kt` |
| `MarkdownExporterTest` | `commonTest` | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/export/MarkdownExporterTest.kt` |
| `PlainTextExporterTest` | `commonTest` | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/export/PlainTextExporterTest.kt` |
| `HtmlExporterTest` | `commonTest` | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/export/HtmlExporterTest.kt` |
| `JsonExporterTest` | `commonTest` | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/export/JsonExporterTest.kt` |
| `HtmlUtilsTest` | `commonTest` | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/export/HtmlUtilsTest.kt` |
| `BlockRefExpansionTest` | `commonTest` | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/export/BlockRefExpansionTest.kt` |
| `MarkdownRoundTripPropertyTest` | `commonTest` | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/export/MarkdownRoundTripPropertyTest.kt` |
| `HtmlXssPropertyTest` | `commonTest` | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/export/HtmlXssPropertyTest.kt` |
| `ExportIntegrationTest` | `jvmTest` | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/export/ExportIntegrationTest.kt` |
| `HtmlExporterIntegrationTest` | `jvmTest` | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/export/HtmlExporterIntegrationTest.kt` |
| `ClipboardIntegrationTest` | `jvmTest` | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/export/ClipboardIntegrationTest.kt` |

All `commonTest` files run via `./gradlew jvmTest` (and also on other KMP targets). `jvmTest` files run via `./gradlew jvmTest` only.

---

## Task → Test Mapping

| Task | Description | Validating test cases |
|------|-------------|----------------------|
| **1.1** `PageExporter` interface + `ExportService` skeleton | Interface, dispatch, subtree filter, ref collection | U-ES-01 through U-ES-11, U-BR-04 through U-BR-06 |
| **1.2** `MarkdownExporter` | Full Markdown serialization | U-MD-01 through U-MD-22, U-BR-01, U-BR-02, U-BR-03, P-RT-01, P-RT-02, P-RT-03, I-E2E-01, I-E2E-06, I-E2E-07, I-E2E-08 |
| **1.3** `PlainTextExporter` | Markup-free serialization | U-PT-01 through U-PT-16, I-E2E-02 |
| **1.4** `JsonExporter` | Structured JSON tree output | U-JS-01 through U-JS-13, I-E2E-04 |
| **2.1** `HtmlUtils` HTML escaping utility | `escape()` and `escapeAttr()` | U-HU-01 through U-HU-10, P-XSS-01, P-XSS-02 |
| **2.2** `HtmlExporter` | Full HTML fragment serialization | U-HT-01 through U-HT-23, I-HT-01, I-HT-02, I-HT-03, I-E2E-03, P-XSS-01, P-XSS-02 |
| **2.3** JVM `ClipboardProvider` + `HtmlStringSelection` | AWT clipboard multi-flavor write | I-CB-01 through I-CB-04 |
| **3.1** `StelekitViewModel` export methods | ViewModel dispatch, null guard, notification | I-E2E-01 through I-E2E-08 (end-to-end wiring verified via ExportService unit tests; ViewModel tested via existing `jvmTest` ViewModel patterns) |
| **4.1** Subtree filter + keyboard shortcut | `subtreeBlocks()` correctness | U-ES-04 through U-ES-07, I-E2E-05 |
| **4.2** Export integration coverage | Full pipeline smoke tests | I-E2E-01 through I-E2E-08, I-HT-01 through I-HT-03 |
