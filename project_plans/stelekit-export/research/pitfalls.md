# Research: Pitfalls
**Dimension**: Pitfalls | **Date**: 2026-04-13

## Block References

Logseq uses `((block-uuid))` syntax. The AST represents these as `BlockRefNode(blockUuid: String)`.
`MarkdownEngine.kt` resolves them at render time via a `resolvedRefs: Map<String, String>` passed into `parseMarkdownWithStyling()`. That map is populated by the ViewModel from the block repository.

**Failure modes:**

1. **Dangling references** — If the referenced block is on a different page that has not yet been loaded into the repository, the UUID lookup fails silently. `MarkdownEngine` already handles this gracefully: it falls back to the display text `"((…))"`. An exporter must apply the same defensive fallback rather than crash or leave raw UUIDs in the output.

2. **Cross-page expansion** — Expanding a `((uuid))` to its full content requires fetching the referenced block from `BlockRepository.getBlockByUuid()`. This is a suspend function that hits SQLDelight. If the export serializer runs synchronously on the main thread, every cross-page reference becomes a blocking DB call. Pages with many block references (e.g. a "map of content" page with 50+ references) will cause noticeable UI jank or ANRs.

3. **Circular references** — A block can embed another block that embeds the original. Logseq renders these with a depth cap. An exporter that naively recurses will stack-overflow. A depth limit (e.g. 3) or a visited-UUID set is required.

4. **Format-specific treatment:**
   - **Markdown**: `((uuid))` has no standard equivalent. Options: expand to the block text inline, render as a footnote, or strip to a placeholder `[block ref]`. Expanded form is most readable but introduces duplication.
   - **Plain text**: Strip to the resolved text or `[block ref]` placeholder.
   - **HTML**: Can expand into a `<blockquote>` or `<span>` with a `data-block-ref` attribute for traceability.
   - **JSON**: Emit an object `{"type":"block_ref","uuid":"…","resolved_text":"…"}` — structured and lossless.

5. **UUID collision with display text** — `BlockRefNode.blockUuid` stores whatever is between `(( ))`, which could be any string (the regex in `OutlinerPipeline` validates strict UUID format but `InlineParser.parseBlockRef` accepts any content until `))`. If the UUID does not match the strict UUID regex, the lookup will return nothing; the exporter must treat it as unresolvable.

**Recommended default:** Render block references as their resolved text with a trailing `[↗]` annotation in Markdown/plain-text, or as a structured object in JSON. Never emit raw UUIDs in human-readable formats.

---

## Page References

`[[PageName]]` is parsed as `WikiLinkNode(target: String, alias: String?)`. The alias syntax `[[Page|display text]]` is fully supported by `InlineParser.parseLink`.

**Failure modes:**

1. **Markdown output** — `[[PageName]]` is not standard Markdown. If emitted verbatim, most renderers (GitHub, Notion) will not make it a link. Options:
   - Convert to `[PageName](PageName)` — works but the URL is meaningless outside SteleKit.
   - Convert to plain text `PageName` — clean but loses navigational intent.
   - Keep `[[PageName]]` — only useful if the target also understands wiki syntax (Obsidian, Foam).
   - **Risk**: choosing the wrong default will produce either broken links or ugly raw syntax in every export.

2. **Alias handling** — `[[Page|display]]` must not emit `Page|display` as the link text. The exporter must split on `|` and use `alias` for display and `target` for the link target. `WikiLinkNode` carries both fields explicitly, so this is straightforward — but only if the exporter uses the AST. If it serializes from the raw `content` string, the `|` will appear literally.

3. **Namespace pages** — Logseq allows `[[Namespace/SubPage]]` syntax. `NamespaceUtils.kt` exists in the codebase. When converting to Markdown links, the slash must be URL-encoded (`%2F`) or the link will be misinterpreted as a directory path. `Validation.validateName` explicitly notes that `[[Cordless/Corded]]` is a valid page name encoded as `%2F` in filenames.

4. **HTML output** — `<` and `>` in page names (rare but possible) must be HTML-escaped to avoid injection. A page named `Hello <World>` must become `Hello &lt;World&gt;` in the link label.

5. **JSON output** — Page names with special characters (`"`, `\`, newlines) must be JSON-escaped. Since page names pass through `Validation.validateName` which rejects control characters, the main risk is double-quotes and backslashes in page names.

6. **Plain text** — Simply emit the alias if present, otherwise the target name. Strip the `[[` / `]]` brackets entirely.

---

## Properties / Frontmatter

Properties are stored in two places:

- **Page-level properties**: `Page.properties: Map<String, String>` (parsed by `GraphLoader` from the first block).
- **Block-level properties**: `Block.properties: Map<String, String>` (parsed by `OutlinerPipeline.parseProperties()` / `PropertiesParser`).

`PropertiesParser` recognizes both `key:: value` inline syntax and `:PROPERTIES:` / `:END:` drawers (Org-mode style).

**Failure modes:**

1. **Properties already stripped from `Block.content`** — `OutlinerPipeline.processBlock()` calls `stripProperties()` before saving. The `content` field stored in SQLDelight contains no property lines. An exporter that reads `Block.content` will not see the raw `key:: value` lines; it must read `Block.properties` map instead. If the exporter reconstructs Markdown by appending properties back, it must re-emit them in the correct format.

2. **Property value may itself contain Logseq syntax** — Values like `[[LinkedPage]]` or comma-separated `[[A]], [[B]]` are common (e.g. `tags:: [[ProjectX]], [[ProjectY]]`). An exporter emitting YAML frontmatter must handle multi-value properties. `OutlinerPipeline.extractReferences` already handles the `tags` and `alias` keys specially; the export layer needs similar awareness.

3. **YAML frontmatter escaping** — If exporting page properties as YAML frontmatter (for Markdown):
   - Values containing `:` must be quoted.
   - Values starting with `[` (Logseq reference lists) will be interpreted as YAML arrays unless quoted.
   - Multi-line values (theoretically allowed in Logseq via continuation) need block scalar syntax.
   - The `id` property (Logseq's block UUID) is typically noise for external consumers; consider filtering it.

4. **Block properties in exports** — For block-level export (subtree export), properties on individual blocks are metadata that has no standard external representation:
   - Markdown: emit as a nested bullet `key:: value` (preserves Logseq syntax, but looks odd externally) or suppress.
   - HTML: could emit as `<dl>` / `<dt>` / `<dd>` pairs or `data-*` attributes.
   - JSON: emit as a nested `"properties": {}` object — clean and lossless.
   - Plain text: suppress unless the key is meaningful (e.g. `scheduled`, `deadline`).

5. **`id` property proliferation** — Every block in Logseq accumulates an `id:: <uuid>` property. These are internal identifiers meaningless to external readers. Emitting them in Markdown export would clutter every bullet. These should be excluded from human-readable formats by default.

---

## Nested Indentation

`BlockNode` (outliner tree) represents hierarchy via parent/child pointers, not indentation. `BulletBlockNode.level` tracks the original nesting depth. `MarkdownPreprocessor.normalize()` normalizes indentation to 4-space multiples during parse, using a heuristic `(spaces + 1) / 2` formula to handle 1- and 2-space indented content.

**Failure modes:**

1. **Deep nesting and CommonMark limits** — CommonMark does not define a maximum nesting depth, but practical parsers (GitHub, Pandoc) behave poorly past 10–15 levels. HTML renderers cap `<ul>` nesting visually. A page with 8+ levels of outline nesting will produce ugly or broken output in most Markdown-to-HTML pipelines.

2. **Indentation normalization asymmetry** — `MarkdownPreprocessor` normalizes 2-space indentation to 4-space. If an exporter emits 2-space-indented Markdown for compact output, some downstream parsers will not recognise the nesting. A safe default is 4-space indentation or use of `  - ` (2-space bullet continuation as per CommonMark spec). The safest choice is 2-space with a `  ` continuation paragraph indent.

3. **Mixed bullet and heading structure** — Logseq allows blocks like `- ## My Heading` (a heading inline on a bullet). Exporting this block at level 2 with a heading inside produces `## My Heading` at the top level of the output, which disrupts the heading hierarchy of the exported document. The exporter must decide: strip the heading marker and emit the text as a plain bullet, or flatten the block tree to use headings for top-level items only.

4. **Ordered list items mixed with unordered** — `OrderedListItemBlockNode` may appear as a sibling of `BulletBlockNode`. In Markdown, mixing ordered and unordered in the same list level requires them to be separate lists. Most renderers handle this, but GitHub's Markdown treats them as distinct lists, potentially inserting unwanted blank space.

5. **HTML nested `<ul>`** — When rendering as HTML, each nesting level must open a new `<ul>` or `<ol>` and close it at de-indent. An off-by-one in open/close tags will corrupt the entire DOM structure downstream. A depth-tracked recursive walker is safer than a flat loop with push/pop state.

6. **Plain text indentation** — Two-space or four-space indentation per level is standard. At 8+ levels the content is unreadable. Consider capping at a maximum depth (e.g. 6) and truncating with a `…` indicator, or flattening deeply nested content.

---

## Special Content Types

**LaTeX / Math**

- `LatexInlineNode` is parsed from `$formula$` syntax. `MarkdownEngine` renders it as monospace plain text (no actual rendering). Block-level LaTeX (`$$…$$`) may appear as raw content in `CodeFenceBlockNode` with language `"latex"` or `"math"`.
- **Markdown export risk**: `$` is a valid formula delimiter in Markdown only for renderers that support MathJax/KaTeX (GitHub does not by default). The `$` character is also used in shell scripts and will cause escaping issues if the Markdown is further processed programmatically.
- **HTML export risk**: Emitting raw LaTeX inside `<pre>` or `<code>` is safe but unrendered. Emitting it in a `<div class="math">` for MathJax injection requires the target page to load MathJax — not a safe assumption.
- **Plain text**: Formula should be emitted as-is (the raw LaTeX string), since it is the closest human-readable approximation.
- **JSON**: Emit as `{"type":"latex_inline","formula":"…"}`.

**Code Blocks**

- `CodeFenceBlockNode` preserves `rawContent` verbatim. This is the only block type that already holds an unmodified string, avoiding double-serialization.
- **HTML export risk**: `rawContent` must be HTML-escaped (`<`, `>`, `&`, `"`) before emitting inside `<pre><code>`. Failure to escape will cause the browser to interpret any HTML-like content in the code as markup — a potential XSS vector if the HTML is later rendered in a web context.
- **Markdown**: Emit as fenced code with the same language identifier. The only risk is if `rawContent` contains a line that starts with ` ``` ` — this will prematurely close the fence. The fix is to use a 4-backtick fence if the content contains triple-backtick sequences.
- **JSON**: `rawContent` may contain characters needing JSON escaping (especially `\`, `"`, and control characters). Use `kotlinx.serialization` or manual escaping; do not concatenate raw strings.

**Image Embeds**

- `ImageNode(alt, url)` may contain local relative paths (e.g. `../assets/image.png`).
- **HTML export risk**: Relative paths will be broken when the HTML is opened outside the graph directory. Options: embed as base64 data URIs, convert to absolute paths, or emit with a `[broken image]` fallback.
- **Markdown export**: Preserve the `![alt](url)` syntax, but the path will be broken if the file is moved. Consider documenting this limitation rather than silently breaking it.
- **Plain text**: Emit `[Image: alt]` or the URL.

**Video / PDF Embeds (`{{embed …}}` macros)**

- These appear as `MacroNode(name="embed", arguments=["[[path]]"])`.
- `PDFViewer.kt` exists in the UI, suggesting PDF embeds are a first-class feature.
- **HTML**: A `<video>` or `<object>` tag with a local path will not work outside the graph.
- **All formats**: Safest default is to emit a placeholder with the path: `[Embedded: path]`.
- `{{query …}}` macros (Datalog queries) have no meaningful static representation — emit `[Query: …]` in all text formats.

**Blockquotes**

- `BlockquoteBlockNode` contains `children: List<BlockNode>`. These are recursive and may contain other block types.
- **Risk**: A blockquote containing a code fence containing a blockquote is valid AST but requires careful depth tracking during serialization.

**Tables**

- `TableBlockNode` carries `headers`, `alignments`, and `rows` as pre-parsed string lists.
- **Plain text**: Emit as pipe-delimited rows or as a simple list of column values.
- **HTML**: Straightforward `<table>` generation, but cell contents may contain Logseq inline syntax (wiki links, etc.) and need recursive inline rendering.
- **JSON**: Natural — emit as array of objects keyed by header.
- **Markdown**: The cell strings in `rows` are already raw Markdown (from the original file). Re-emitting them verbatim is correct for Markdown output but incorrect for HTML/plain text without further parsing.

**Task Markers**

- `TaskMarkerNode(marker)` — valid markers include `TODO`, `DONE`, `NOW`, `LATER`, `WAITING`, `CANCELLED`, `DOING`, `WAIT`, `STARTED`.
- **Markdown**: GitHub-Flavored Markdown checkbox syntax (`- [ ]` / `- [x]`) maps naturally for `TODO`/`DONE`. Other markers (`NOW`, `LATER`, `WAITING`, `DOING`, `STARTED`) have no GFM equivalent and should be emitted as bold prefix text to preserve meaning.
- **HTML**: Can use `<input type="checkbox">` for TODO/DONE; render other states as styled `<span>` elements.

---

## Performance

**Risk level: Medium-High for large pages.**

**Observed architecture:**

- `GraphLoader` uses a `parallelScope` with `Dispatchers.Default` (4 IO threads, 2 compute threads) for file loading. Block serialization for export does not yet exist, so there is no established dispatcher pattern.
- `SqlDelightBlockRepository` has a `maxCacheSize = 1000` block cache and a `hierarchyTtlMs = 120_000L` TTL for hierarchy queries. A page with 500+ blocks may exhaust or evict this cache mid-export.
- `Validation.MAX_CONTENT_LENGTH = 10_000_000` (10 MB) — a single block's content can be enormous. Serializing such a block to HTML with entity-escaping is potentially slow if done naively character-by-character.

**Specific risks:**

1. **Synchronous serialization on the main thread** — If the export action is triggered from a Compose UI handler and the serializer is not `suspend`/coroutine-aware, a 500-block page will freeze the UI. The serializer must run on `Dispatchers.Default` and report progress or complete asynchronously.

2. **Block reference expansion amplification** — Expanding `((uuid))` references requires a repository lookup per reference. A page with 100 block references, each pointing to a block with its own references, creates a tree walk that multiplies DB calls. A bulk-fetch + in-memory map approach is needed: fetch all referenced block UUIDs in one query before starting serialization.

3. **StringBuilder vs. String concatenation** — Naive string concatenation in a loop for 500+ blocks is O(n²). The existing `reconstructContent` in `MarkdownParser` uses `StringBuilder` correctly. The export serializer must follow the same pattern.

4. **Memory spikes for HTML output** — A large page (500 blocks, each with ~100 chars) generates ~50 KB of raw content. HTML adds approximately 3–5× overhead for tags, producing ~200 KB strings in memory before clipboard write. This is manageable on JVM but should not be held in multiple String copies.

5. **Clipboard size limits** — macOS clipboard accepts strings of arbitrary length, but Windows has a 4 GB limit (not a practical concern). Some clipboard managers (Alfred, Raycast) have their own limits (~1 MB). Clipboard export of very large pages may be silently truncated. A "save to file" fallback for large exports (> 100 KB output) is advisable.

6. **`Validation.validateContent`** — Called in `Block.init`. If the export pipeline reconstructs `Block` objects (e.g. for testing), the validator will reject content with C0/C1 control characters. Any export that synthesizes blocks must not trigger this path.

---

## Unicode / Escaping

**The `Validation.validateString` function** rejects:
- Null bytes (`\u0000`)
- C0 control characters (`0x00–0x1F`) except `\n`, `\r`, `\t` when `allowWhitespace=true`
- C1 control characters (`0x80–0x9F`)

This means block content in the repository is already sanitized. However, escaping issues can still occur during serialization.

**Format-specific risks:**

1. **HTML escaping** — Characters `<`, `>`, `&`, `"` in block content must be escaped as `&lt;`, `&gt;`, `&amp;`, `"`. The existing `MarkdownEngine` does not do this (it outputs `AnnotatedString` for Compose, not HTML). The export HTML serializer must apply escaping independently — there is no existing utility for this in the codebase.

2. **Emoji in page names** — `Validation.validateName` does not explicitly reject emoji or non-ASCII Unicode. Page names like `📅 Daily Notes` are valid. In HTML, emoji in `<title>` or `href` attributes require no special escaping but may cause issues with older MIME type parsers. In JSON, emoji are valid UTF-8 and serialize cleanly with `kotlinx.serialization`. In Markdown, emoji in link labels are safe.

3. **Bidirectional text (RTL)** — Arabic, Hebrew, or mixed-direction content in block text will not render correctly in plain-text export without Unicode direction markers. HTML with proper `dir="auto"` on the container handles this gracefully. Plain text and Markdown exports may display RTL content in reversed visual order in some terminals.

4. **Combining characters and diacritics** — These are valid in page names and block content. String-length-based operations (e.g. truncating at a fixed character count) may split combining character sequences, producing malformed Unicode. Use `codePointCount` or character boundary checks rather than `String.length` for any truncation logic.

5. **JSON string escaping** — `kotlinx.serialization` handles standard JSON escaping automatically. However, if the exporter constructs JSON strings manually (e.g. via `StringBuilder`), it must escape `\`, `"`, and control characters. The `Validation` layer guarantees no C0/C1 control characters in content, which eliminates the most dangerous cases, but `\` and `"` in content are permitted and common.

6. **YAML frontmatter escaping** — Colons, hashes, brackets, and ampersands have meaning in YAML. A property value like `tags:: [[A & B]]` contains `[`, `]`, and `&` characters that require quoting in YAML. Without a proper YAML serializer, manually constructed frontmatter will silently produce invalid YAML.

7. **Markdown special characters in block content** — If converting block content from its raw Logseq form to "clean" Markdown (stripping Logseq-specific syntax), characters like `*`, `_`, `[`, `\`, `` ` `` in plain text segments must be escaped to prevent them from being interpreted as markup by downstream Markdown renderers. The `InlineParser` already identifies which characters are inside markup nodes versus plain `TextNode`s, so the exporter can escape only the `TextNode` content, leaving emphasis/code nodes as-is.

---

## Mitigations

| Risk | Mitigation |
|---|---|
| Dangling block references | Resolve via repository with a fallback display text `[block ref]`; never crash |
| Cross-page reference expansion | Bulk-fetch all referenced block UUIDs before serialization; apply depth limit of 3 |
| Circular block references | Track visited UUIDs in a `Set<String>` during recursive expansion |
| `[[PageName]]` in Markdown | Default to `[PageName](PageName)` with a note that links are app-internal; configurable |
| Namespace page names in links | URL-encode `/` as `%2F` when used in link targets |
| HTML injection in code blocks | Always HTML-escape `rawContent` before `<pre><code>` emission |
| `id::` property noise | Exclude the `id` key from all human-readable format exports by default |
| Property values containing Logseq syntax | Parse property values with `InlineParser` before emitting in non-Markdown formats |
| YAML frontmatter injection | Use a minimal YAML serializer that quotes all values containing `:`, `[`, `{`, `#`, `&` |
| Deep nesting (8+ levels) | Cap HTML/plain-text output at 6 levels; document the cap in the UI |
| Task markers without GFM equivalent | Emit `NOW` / `LATER` / `WAITING` as bold text prefixes in Markdown/HTML |
| UI freeze on large pages | Run serializer on `Dispatchers.Default`; expose as `suspend fun` |
| Block reference DB amplification | Pre-fetch all needed UUIDs in a single batch query before starting serialization |
| Clipboard size overflow | Add a size check (e.g. > 500 KB) and fall back to "save to file" dialog |
| Unicode combining character truncation | Avoid `String.length` for truncation; use code-point-aware iteration |
| Markdown special-char escaping | Escape only `TextNode` content; leave AST markup nodes as-is |
| HTML escaping missing | Implement a dedicated `htmlEscape(String): String` utility before any HTML serializer |
