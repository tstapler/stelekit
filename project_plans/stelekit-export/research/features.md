# Research: Features
**Dimension**: Features | **Date**: 2026-04-13

---

## Logseq Export Format

### On-disk format (what SteleKit stores — identical to Logseq)

Every page is a flat `.md` file. All content is expressed as an outliner bullet tree using `- ` as the list marker. Child blocks are indented with one `\t` per level. Page-level and block-level properties are `key:: value` lines interspersed with content lines (not YAML frontmatter).

Concrete example from the demo graph (`Start Here.md`):

```
- type:: index
- tags:: #welcome #getting-started
- Welcome to SteleKit — your Logseq-compatible outliner for Desktop, Android, and iOS.
	- This graph is both a working demo and a built-in help reference.
	- Every feature has a dedicated page. Read it, edit it, link from it.
- This graph is portable. The same `.md` files open in Logseq unchanged.
- **Feature Pages** — explore SteleKit's capabilities:
	- [[Block Editing]] — create, indent, and reorder blocks
	- [[Page Linking]] — connect pages with `[[double bracket]]` wiki links
```

### Key formatting constructs

| Construct | On-disk syntax | Notes |
|---|---|---|
| Block | `- content` | Every line of content is a bullet |
| Child block | `\t- content` | One tab per nesting level |
| Page property | `- key:: value` (top-level bullets) | Written by `GraphWriter` before other blocks |
| Block property | `\t\tkey:: value` (child of annotated block) | Indented child, no `- ` prefix |
| Wiki link | `[[Page Name]]` | Internal page reference |
| Block reference | `((uuid))` e.g. `((6b5a2f3d-...))` | UUID of another block |
| Hashtag | `#TagName` | Treated as both tag and page reference |
| Task marker | `TODO ` / `DONE ` prefix in content | Part of inline content string |
| Scheduled | `SCHEDULED: <2026-04-13 ...>` | Logseq org-mode timestamp stripped by `TimestampParser` |
| Inline code | `` `code` `` | Standard Markdown |
| Fenced code | ` ```lang … ``` ` | Multi-line, stored verbatim in block content |
| Highlight | `==text==` | Logseq extension, not standard Markdown |

### What Logseq's "Export page" produces

Logseq (the original app) offers several export variants:
- **Export as Markdown** — rewrites the page removing Logseq-specific syntax: block refs `((uuid))` are replaced with quoted block content, `[[links]]` become plain text or are optionally retained, and the leading `- ` bullet prefix is dropped at the top level. Nesting is re-expressed with `  ` (2-space indent) bullet lists.
- **Export as OPML** — tree structure converted to OPML XML.
- **Export as JSON** — structured block tree with UUIDs, content, and properties.
- **Copy as Markdown** — clipboard variant of the Markdown export.

The key transformations Logseq applies for its "clean" Markdown export:
1. Top-level blocks lose their `- ` bullet prefix (become paragraphs or headings).
2. Nested blocks become indented Markdown bullet lists (`  - ` with 2-space indent, not tabs).
3. `((uuid))` block references are resolved: replaced with the target block's text (or a `> blockquote` embed).
4. `[[Page Name]]` is either kept as-is (portable) or stripped to bare text.
5. `key:: value` property lines are either omitted from body or promoted to YAML frontmatter.
6. `#Tag` becomes plain text or an inline link depending on export target.
7. `==highlight==` becomes `**highlight**` (bold) or is stripped (no universal standard).

---

## Comparable Tools

### Obsidian

Obsidian stores files as standard Markdown (not outliner bullets). Its on-disk format uses YAML frontmatter for page metadata:

```markdown
---
tags: [tagA, tagB]
date: 2026-04-13
---

# Page Title

Regular paragraph text.

- Standard Markdown list item
  - Nested with 2-space indent
```

Obsidian's "Copy as Markdown" / export behaviour:
- Content is already clean Markdown — no transformation needed.
- `[[wikilinks]]` are retained in the file; on export they optionally become `[Page Name](Page Name.md)` relative links.
- Properties (frontmatter) are YAML, not inline `key:: value`.
- No concept of block UUIDs or block references.

**Key lesson for SteleKit export**: Obsidian's format is the target "clean Markdown" that users expect when pasting into GitHub, Notion, or email. The export job is to transform Logseq-flavored bullet Markdown into Obsidian-style Markdown.

### Notion

Notion's Markdown export (via "Export as Markdown & CSV"):
- Top-level content becomes paragraphs, not bullets.
- Lists use standard `- ` with 4-space indent for nesting.
- Properties map to a metadata table at the top of the page, not YAML frontmatter.
- Internal page links become relative file paths; external links are preserved.
- No block-reference concept: inline database embeds become tables.

### Summary: what "clean" means

A universally pasteable output file should:
1. Use no `- ` bullet on top-level prose content.
2. Use standard `  - ` or `  * ` with 2-space indent for list nesting.
3. Contain no `((uuid))` references.
4. Contain no `key:: value` lines in the body (move to frontmatter or omit).
5. Keep `[[links]]` as-is OR convert to plain text (user preference).
6. Keep all standard Markdown intact: bold, italic, code, fenced code, headings, images, URLs.
7. Optionally keep `#tags` as-is (readable) or strip them.

---

## SteleKit Data Model (relevant fields)

### `Page` (defined in `Models.kt`)

| Field | Type | Export relevance |
|---|---|---|
| `uuid` | String | JSON export identifier |
| `name` | String | Page title / `# H1` heading |
| `namespace` | String? | Prefix for namespaced pages (`Foo/Bar`) |
| `filePath` | String? | Source path — useful for file export |
| `createdAt` / `updatedAt` | Instant | JSON metadata |
| `properties` | Map<String,String> | Page-level `key:: value` → YAML frontmatter or JSON |
| `isJournal` | Boolean | Journal pages may need date heading |
| `journalDate` | LocalDate? | Journal date — use as H1 or title |

### `Block` (defined in `Models.kt`)

| Field | Type | Export relevance |
|---|---|---|
| `uuid` | String | JSON export identifier; used in `((uuid))` refs |
| `pageUuid` | String | Parent page link |
| `parentUuid` | String? | Null = top-level block; non-null = child |
| `leftUuid` | String? | Sibling ordering (linked-list) |
| `content` | String | Raw block text (Logseq-flavored Markdown inline) |
| `level` | Int | Nesting depth (0 = root) |
| `position` | Int | Sort order among siblings |
| `properties` | Map<String,String> | Block-level `key:: value` metadata |

### `BlockNode` (outliner in-memory tree, `outliner/BlockNode.kt`)

The `BlockNode` tree is the natural input for export: it holds `content`, `children: MutableList<BlockNode>`, and back-references to `originalBlock` for properties. Export should walk this tree recursively.

### Inline AST (`parser/MarkdownParser.kt`)

The `LogseqParser` + `InlineParser` parse block `content` strings into structured inline nodes:
- `WikiLinkNode(target)` — `[[Page Name]]`
- `BlockRefNode(blockUuid)` — `((uuid))`
- `TagNode(tag)` — `#tag`
- `BoldNode`, `ItalicNode`, `StrikeNode`, `CodeNode`, `HighlightNode`, `TaskMarkerNode`, `LatexInlineNode`
- `UrlLinkNode(url, text)`, `MdLinkNode(label, url, title)`, `ImageNode(alt, url)`
- `MacroNode(name, arguments)` — `{{embed ...}}`

The parser also recognises `HeadingBlockNode`, `CodeFenceBlockNode`, `OrderedListItemBlockNode`, `TableBlockNode`, `BlockquoteBlockNode`.

**Key insight**: export does NOT need to re-parse block `content` strings from scratch. The parser infrastructure already exists and can produce an inline AST per block. The serializer only needs to walk that AST and emit the desired format.

### Property handling

`PropertiesParser` strips `key:: value` lines from the content string and returns them as a `Map`. `OutlinerPipeline.processBlock` does the same and stores them in `Block.properties`. By the time blocks reach the export layer, `content` has already been stripped of property lines; properties are in the `properties` map.

---

## Recommended Output Spec per Format

### Formatted Markdown

**Goal**: pasteable into GitHub, Notion, email — universally readable.

**Algorithm**:
1. Emit YAML frontmatter block if the page has properties:
   ```
   ---
   title: Page Name
   key: value
   ...
   ---
   ```
2. Emit `# Page Name` as H1 (or the journal date for journal pages).
3. Walk block tree depth-first. For each block:
   - If `level == 0` and block is a single line of prose: emit as paragraph (no bullet).
   - If `level == 0` and block has children: emit as paragraph + nested list.
   - If `level > 0`: emit as `${"  ".repeat(level - 1)}- content` (2-space indent, 0-indexed children).
   - Emit a blank line between top-level blocks.
4. Inline transformations on `content`:
   - `[[Page Name]]` → keep as `[[Page Name]]` (default, compatible with Obsidian) OR strip to plain `Page Name`.
   - `((uuid))` → resolve to target block's first line of text; wrap in `> ` blockquote (or inline em-dash reference) if resolution is available; otherwise strip entirely.
   - `#Tag` → keep as `#Tag` (readable; not Markdown-standard but harmless in most renderers).
   - `key:: value` lines → already stripped by parser; do not re-emit in body.
   - `==highlight==` → convert to `**highlight**` (bold) for broad compatibility.
   - `{{embed [[Page]]}}` → emit a note `_[embed: Page Name]_` or skip.
   - `SCHEDULED:` / `DEADLINE:` → omit from body (TimestampParser already strips these from `content`).

**Block properties** (stored in `Block.properties`):
- Option A: Emit as indented sub-list `  - key: value` beneath their parent block.
- Option B: Omit entirely (cleaner for sharing).
- Recommendation: omit by default; include if user selects "include metadata".

### Plain Text

**Goal**: no markup at all — safe for email body, SMS, speech synthesis.

1. Emit page title as a plain text line followed by a blank line.
2. Walk block tree. For each block:
   - Emit `${"  ".repeat(level)}content_text` where `content_text` has ALL markup stripped.
   - Strip: `**`, `*`, `~~`, `` ` ``, `[[`, `]]`, `((`, `))`, `#`, `==`, `[`, `]`, `(`, `)` markers.
   - Keep plain text content of links: `[text](url)` → `text`.
   - Keep `\n` for code fences (multi-line content).
3. Separate top-level blocks with a blank line.
4. Omit all properties.

### HTML

**Goal**: copy-pasteable into rich text editors (email clients, Confluence, Google Docs).

1. Wrap in a `<article>` or `<div>` (no full `<html>` document — suitable for clipboard).
2. Page title as `<h1>`.
3. Page properties as a `<dl>` definition list (optional, default off).
4. Walk block tree:
   - A top-level block with no children: `<p>content</p>`.
   - A block that has children: `<p>content</p><ul><li>…</li></ul>` pattern.
   - Deeply nested blocks: `<ul><li>content<ul><li>…</li></ul></li></ul>`.
5. Inline transformations:
   - `[[Page Name]]` → `<a href="#Page Name">Page Name</a>` (local anchor link) or bare text.
   - `((uuid))` → resolved block text or omit.
   - `**bold**` → `<strong>bold</strong>`.
   - `*italic*` → `<em>italic</em>`.
   - `~~strike~~` → `<s>strike</s>`.
   - `` `code` `` → `<code>code</code>`.
   - ` ```lang … ``` ` → `<pre><code class="language-lang">…</code></pre>`.
   - `==highlight==` → `<mark>highlight</mark>`.
   - `[text](url)` → `<a href="url">text</a>`.
   - `![alt](url)` → `<img alt="alt" src="url">`.
   - `#Tag` → `<span class="tag">#Tag</span>` or bare text.
   - Task markers (`TODO`, `DONE`) → `<span class="task-marker todo">TODO</span>`.

### JSON

**Goal**: machine-readable export for scripting, migration, or archival.

Top-level schema:
```json
{
  "version": 1,
  "exportedAt": "ISO-8601",
  "page": {
    "uuid": "...",
    "name": "Page Name",
    "isJournal": false,
    "journalDate": null,
    "createdAt": "ISO-8601",
    "updatedAt": "ISO-8601",
    "properties": { "type": "reference", "tags": "#tagA #tagB" }
  },
  "blocks": [
    {
      "uuid": "...",
      "parentUuid": null,
      "position": 0,
      "level": 0,
      "content": "raw block content string",
      "properties": {},
      "children": [
        {
          "uuid": "...",
          "parentUuid": "parent-uuid",
          "position": 0,
          "level": 1,
          "content": "child block content",
          "properties": { "author": "Jane Doe" },
          "children": []
        }
      ]
    }
  ]
}
```

Notes:
- Use the `BlockNode` tree structure (not the flat DB list) so hierarchy is self-describing.
- `content` is the raw stored string (Logseq inline syntax preserved) so the output is round-trippable.
- Include `properties` at both page and block level.
- `version: 1` for forward-compatibility.
- For block-selection export (subtree), `page` metadata is still included; `blocks` contains only the selected subtree rooted at the selected `BlockNode`.

---

## Minimal Transformation Reference

| Input (Logseq) | Formatted Markdown | Plain Text | HTML |
|---|---|---|---|
| `- top-level text` | `text` (paragraph) | `text` | `<p>text</p>` |
| `\t- child text` | `- child text` (indented list) | `  child text` | `<li>child text</li>` |
| `[[Page Name]]` | `[[Page Name]]` | `Page Name` | `<a href="#Page Name">Page Name</a>` |
| `((uuid))` | resolved text or _(omitted)_ | resolved text or _(omitted)_ | resolved text or _(omitted)_ |
| `#Tag` | `#Tag` | `Tag` | `#Tag` or `<span class="tag">#Tag</span>` |
| `key:: value` in body | _(moved to frontmatter)_ | _(omitted)_ | _(omitted or dl)_ |
| `==highlight==` | `**highlight**` | `highlight` | `<mark>highlight</mark>` |
| `TODO text` | `- [ ] text` | `TODO text` | `<li class="todo">text</li>` |
| `DONE text` | `- [x] text` | `DONE text` | `<li class="done">text</li>` |
