# SteleKit Parser Feature Specification

**Version**: 1.0  
**Date**: 2026-04-12  
**Status**: Authoritative — this document is the single source of truth for what the SteleKit parser must handle.

---

## Source Specifications

1. **CommonMark 0.31.2** — https://spec.commonmark.org/0.31.2/  
   The baseline Markdown standard. All rule numbers referenced below are from this spec.

2. **GitHub Flavored Markdown (GFM)** — https://github.github.com/gfm/  
   Strict superset of CommonMark adding tables, task list items, strikethrough, extended autolinks, and disallowed raw HTML.

3. **mldoc (Logseq OCaml parser)** — https://github.com/logseq/mldoc  
   Logseq's production parser. Defines all Logseq-specific extensions: wiki-links, block references, task markers, properties, macros, timestamps, and more.

---

## Parsing Architecture Context

SteleKit currently has two parallel parsing systems:

- **`parsing/` (LogseqParser / BlockParser / InlineParser)** — hand-written recursive descent + Pratt parser producing an AST (`DocumentNode → BlockNode → InlineNode`). Used for data extraction (references, properties, tree structure).
- **`ui/components/MarkdownEngine.kt`** — regex-based tokenizer used exclusively for rendering styled Compose `AnnotatedString`.

The spec below covers all constructs that both layers collectively must handle. Priority ratings account for the current implementation gaps documented in `docs/tasks/parser-gap-analysis.md`.

**Priority Scale**:
- **P0** — Data correctness: missing or wrong parsing causes data loss or graph corruption.
- **P1** — Core Logseq parity: feature is essential for basic Logseq file compatibility.
- **P2** — Feature completeness: visible to users but not critical path.
- **P3** — Extended / edge-case features.

---

## Block Constructs

### ATX Headings

**CommonMark Rule**: Section 4.2  
**GFM Extension**: No  
**Logseq behavior**: Headings appear both as top-level page structure and embedded within bullet blocks (e.g., `- ## TODO My heading`). In Logseq's outliner model, headings are decoration on bullets, not standalone blocks.

**Syntax**:
```
# Heading level 1
## Heading level 2
### Heading level 3
#### Heading level 4
##### Heading level 5
###### Heading level 6
```

**Rules**:
- 1–6 `#` characters followed by a space/tab or end-of-line.
- Up to 3 spaces of indentation before the `#` sequence.
- Optional closing `#` sequence (must be preceded by space/tab).
- Content parsed as inline elements.
- `#5 bolt` (no space) is NOT a heading — it is plain text.

**Current SteleKit status**: Missing in both layers. `#` is tokenized as `HASH` and misidentified as a hashtag (Bug 5 in gap analysis).

**AST node needed**: `HeadingBlockNode(level: Int, content: List<InlineNode>)`

**Priority**: P1

---

### Setext Headings

**CommonMark Rule**: Section 4.3  
**GFM Extension**: No  
**Logseq behavior**: Not commonly used in Logseq files; mldoc parses them but they are rare in practice.

**Syntax**:
```
My Heading Level One
====================

My Heading Level Two
--------------------
```

**Rules**:
- One or more lines of text followed by a line of `=` (level 1) or `-` (level 2).
- Underline cannot have internal spaces.
- Cannot interrupt paragraphs without a preceding blank line.
- Up to 3 spaces of indentation before the underline.

**Current SteleKit status**: Missing in both layers.

**Normalization policy**: Parse on read; normalize to ATX (`##`) on write so stored files use the canonical form.

**Priority**: P2

---

### Thematic Breaks (Horizontal Rules)

**CommonMark Rule**: Section 4.1  
**GFM Extension**: No  
**Logseq behavior**: Standard `---` renders as a divider line. Used in page separators.

**Syntax**:
```
---
***
- - -
_ _ _
```

**Rules**:
- 3 or more matching `-`, `_`, or `*` characters.
- May have spaces between characters.
- No other characters on the line.
- Up to 3 spaces of indentation.
- Can interrupt paragraphs.
- Note: `---` must be distinguished from setext heading underlines and YAML front-matter fences.

**Current SteleKit status**: Missing in both layers.

**Priority**: P2

---

### Fenced Code Blocks

**CommonMark Rule**: Section 4.5  
**GFM Extension**: No (CommonMark standard)  
**Logseq behavior**: mldoc supports a `language` field and an `options` list (e.g., ` ```clojure :results output `). Options are space-separated strings after the language name.

**Syntax**:
````
```language :option1 :option2
code here
```

~~~python
code here
~~~
````

**Rules**:
- Opening fence: 3 or more `` ` `` or `~` characters. Backtick fences more common in Logseq.
- Info string: first word is the language identifier; remaining words are options (Logseq extension).
- Closing fence: same character type, same or greater length.
- Up to 3 spaces of indentation before the opening fence.
- Content is literal (no Markdown parsing inside).
- Unclosed fences close at document end.
- Backtick info strings cannot contain backtick characters.

**Examples**:
````
```clojure
(defn hello [] "world")
```

```python :results output
print("hello")
```
````

**Current SteleKit status**: Missing in both layers.

**AST node needed**: `CodeFenceBlockNode(language: String?, options: List<String>, lines: List<String>)`

**Priority**: P1

---

### Indented Code Blocks

**CommonMark Rule**: Section 4.4  
**GFM Extension**: No  
**Logseq behavior**: mldoc's `markdown_code_block.ml` implements the 4-space indented code block parser and produces `Example of string list` AST nodes. However, mldoc operates in two modes: full-parse mode (`parse_outline_only = false`) and outline mode (`parse_outline_only = true`). In outline mode — which is what Logseq uses for its outliner — the post-parse filter in `mldoc_parser.ml` explicitly removes `Example _` nodes from the AST (along with `Src _`, `Raw_Html _`, `Hiccup _`, and other rich block types). This means **indented code blocks are silently discarded in Logseq's outline parse mode**. In non-outline Markdown files (flat documents), they do appear as `Example` nodes.

**Syntax**:
```
    four spaces of indentation
    means code block
```

**Rules**:
- Each line must start with 4+ spaces (or a tab).
- Cannot interrupt a paragraph (blank line required before).
- Up to 4 spaces of indentation removed from each line; extras preserved.
- Content is literal (no Markdown parsing).

**mldoc AST node**: `Example of string list` — distinct from `Src of code_block` (fenced code). No language or options fields.

**Current SteleKit status**: Missing in both layers.

**SteleKit policy**: Disable in outline parsing mode (matching mldoc behavior). In non-outline/flat document mode, parse as `CodeFenceBlockNode(language=null)`. The 4-space indent would conflict with Logseq's 2-space or tab-based outliner nesting if naively enabled.

**Priority**: P2 — Implement with outline-mode disabled guard; needed for flat Markdown file compatibility.

---

### Blockquotes

**CommonMark Rule**: Section 5.1  
**GFM Extension**: No  
**Logseq behavior**: Standard blockquote. Multi-line blockquotes use `>` on each continuation line; lazy continuation (omitting `>`) is supported by CommonMark but not widely used in Logseq.

**Syntax**:
```
> This is a blockquote.
> It can span multiple lines.
>
> A blank `>` line continues the same blockquote.
```

**Rules**:
- `>` marker at start of line, optionally followed by a single space.
- Up to 3 spaces of indentation before `>`.
- Can contain any block constructs (headings, lists, nested blockquotes).
- Lazy continuation: on a continuation paragraph, the `>` can be omitted.
- Two consecutive block quotes require a blank line between them.

**Current SteleKit status**: Missing in both layers.

**AST node needed**: `BlockquoteBlockNode(children: List<BlockNode>)`

**Priority**: P2

---

### Bullet Lists (Unordered Lists)

**CommonMark Rule**: Section 5.3  
**GFM Extension**: No  
**Logseq behavior**: Bullet lists (`-`, `*`, `+`) are the PRIMARY structural unit in Logseq — every content entry is a bullet. Logseq uses indentation (tabs or 2 spaces) to express nesting levels, not the CommonMark 4-space convention.

**Syntax**:
```
- First item
- Second item
  - Nested item (2-space indent = 1 level in Logseq)
    - Double-nested (4-space = 2 levels)
* Also a bullet
+ Also a bullet
```

**Rules**:
- Marker: `-`, `*`, or `+` followed by space/tab.
- **Logseq indentation**: 2 spaces or 1 tab per nesting level (NOT CommonMark's 4-space rule).
- Items can contain multi-line content (continuation lines indented further than the bullet).
- Tight lists: items not separated by blank lines.
- Loose lists: items separated by blank lines (wrap content in `<p>` in HTML output).

**Current SteleKit status**: Partially working. Only `-` creates a BULLET token; `*` and `+` fall through. Indentation level calculation (`calculateLevel`) has a rounding bug for odd space counts.

**Priority**: P0 — All content is in bullets; this must work correctly.

---

### Ordered Lists

**CommonMark Rule**: Section 5.2  
**GFM Extension**: No  
**Logseq behavior**: Supported but less common than unordered lists.

**Syntax**:
```
1. First item
2. Second item
   1. Nested ordered item
3. Third item

1) Alternative syntax with parenthesis
```

**Rules**:
- Marker: 1–9 digits followed by `.` or `)`, then 1+ spaces.
- Start number taken from the first item; subsequent numbers are ignored in rendering.
- When interrupting a paragraph, start number must be 1.
- Max 9 digits (e.g., `1234567890.` is NOT a list item — too long).
- Continuation lines indented to align with content after marker.

**Current SteleKit status**: Missing in both layers.

**AST node needed**: `OrderedListItemBlockNode(number: Int, content: List<InlineNode>, children: List<BlockNode>)`

**Priority**: P2

---

### Link Reference Definitions

**CommonMark Rule**: Section 4.7  
**GFM Extension**: No  
**Logseq behavior**: Rarely used in Logseq files. Properties (`key:: value`) are the idiomatic metadata format.

**Syntax**:
```
[foo]: /url "Optional title"
[foo]: /url 'Single-quote title'
[foo]: /url (Paren title)
[foo]: <>

[foo]
```

**Rules**:
- Label + `:` + destination + optional title.
- Up to 3 spaces of indentation before label.
- Label matching is case-insensitive.
- First definition wins if duplicates exist.
- Cannot interrupt paragraphs.
- Title can span multiple lines (no blank lines allowed).

**Current SteleKit status**: Missing.

**Priority**: P3

---

### HTML Blocks

**CommonMark Rule**: Section 4.6  
**GFM Extension**: Disallows certain tags (see GFM section below)  
**Logseq behavior**: mldoc supports `Raw_Html` type. In SteleKit's rendering context (Compose), raw HTML cannot be rendered directly and must be either stripped or displayed as a code block.

**Syntax**:
```html
<div class="custom">
  content here
</div>
```

**7 HTML block types**:
- Type 1: `<pre>`, `<script>`, `<style>`, `<textarea>` — ends at matching close tag
- Type 2: `<!--` comment — ends at `-->`
- Type 3: `<?` processing instruction — ends at `?>`
- Type 4: `<!LETTER` declaration — ends at `>`
- Type 5: `<![CDATA[` — ends at `]]>`
- Type 6: Block-level tags (address, article, aside, blockquote, details, dialog, dd, div, dl, dt, fieldset, figcaption, figure, footer, form, h1–h6, header, hgroup, hr, li, main, nav, ol, p, pre, section, summary, table, ul) — ends at blank line
- Type 7: Any open/close tag not in above lists — ends at blank line; cannot interrupt paragraph

**Current SteleKit status**: Missing. In Compose rendering context, this likely maps to "display as raw text/code block."

**AST node needed**: `RawHtmlBlockNode(rawContent: String)`

**Priority**: P2 — Parse and preserve as `RawHtmlBlockNode`; render as a code block displaying the raw HTML.

---

### Hiccup Blocks (Logseq Extension)

**CommonMark Rule**: N/A  
**GFM Extension**: No  
**Logseq behavior**: Logseq supports Hiccup (ClojureScript HTML DSL) as an alternative to raw HTML for custom rendering. Recognized by mldoc as a `Hiccup` block type.

**Syntax**:
```
[:div {:class "custom"} "Hello world"]
[:span {:style {:color "red"}} "Red text"]
```

**Rules**:
- Line starts with `[` followed by `:` (Clojure keyword syntax for tag names).
- Treated as a literal rendering instruction.
- In SteleKit (Compose context), likely displayed as raw text since Hiccup rendering is ClojureScript-specific.

**Current SteleKit status**: Missing.

**AST node needed**: `HiccupBlockNode(rawContent: String)`

**Priority**: P2 — Parse as `HiccupBlockNode`; render as grayed-out code block since Hiccup execution is ClojureScript-specific.

---

### Tables (GFM Extension)

**CommonMark Rule**: N/A (GFM extension)  
**GFM Extension**: Yes  
**Logseq behavior**: mldoc supports both Markdown-style (`| col |`) and Org-mode-style tables, plus column groups. Standard GFM table format is the Markdown variant.

**Syntax**:
```
| Header 1 | Header 2 | Header 3 |
| -------- | :------: | -------: |
| left     | center   | right    |
| cell     | cell     | cell     |
```

**Alignment in delimiter row**:
- `---` or `-` — no alignment (default left)
- `:---` — left-aligned
- `---:` — right-aligned
- `:---:` — center-aligned

**Rules**:
- Header row required; delimiter row required; zero or more data rows.
- Leading/trailing pipes optional but recommended.
- Spaces between pipes and content are trimmed.
- Header cell count must match delimiter row exactly.
- Data rows can have fewer cells (empty fill) or more cells (excess ignored).
- Block-level elements cannot be inserted in table cells.
- Tables end at first blank line or block-level structure.
- Escaped pipes `\|` inside cell content.

**Current SteleKit status**: Missing in both layers.

**AST node needed**: `TableBlockNode(headers: List<List<InlineNode>>, alignment: List<Alignment?>, rows: List<List<List<InlineNode>>>)`

**Priority**: P2

---

### Custom Blocks / Directives (Logseq Extension)

**CommonMark Rule**: N/A  
**GFM Extension**: No  
**Logseq behavior**: mldoc supports `#+begin_name` / `#+end_name` syntax for named custom blocks. Also `#+KEY: value` page-level directives.

**Syntax**:
```
#+begin_quote
This is a quote block.
#+end_quote

#+begin_warning
Warning text here.
#+end_warning

#+TITLE: My Page Title
#+TAGS: programming, clojure
```

**Rules**:
- `#+begin_NAME` on its own line opens a custom block.
- `#+end_NAME` on its own line closes it (case-insensitive).
- `#+KEY: value` at the top of a file is treated as a page-level directive (similar to front matter).
- Block content is literal text.

**Current SteleKit status**: Missing.

**AST node needed**: `CustomBlockNode(name: String, content: String)` and `DirectiveNode(key: String, value: String)`

**Priority**: P2

---

### Front Matter (YAML / TOML)

**CommonMark Rule**: N/A  
**GFM Extension**: No  
**Logseq behavior**: mldoc supports YAML front matter between `---` delimiters at the very start of a file. Used for page-level metadata.

**Syntax**:
```yaml
---
title: My Page
tags: [programming, clojure]
aliases: [My-Page, mypage]
---
```

**Rules**:
- Must appear at the very start of the file.
- Opening `---` on line 1.
- Closing `---` on its own line.
- Content is YAML (or TOML with `+++` delimiters).
- Keys extracted as page-level properties.

**Current SteleKit status**: Missing.

**Priority**: P3 — Logseq primarily uses `key:: value` inline properties; YAML front matter is uncommon in Logseq exports.

---

### Property Drawers

**CommonMark Rule**: N/A  
**GFM Extension**: No  
**Logseq behavior**: Org-mode style property drawers. Also supported in Logseq's Markdown mode. Properties are attached to the block they appear in.

**Syntax**:
```
:PROPERTIES:
:id: 64d8e5a3-b77c-4c9b-a6c1-3a8e6d5f8b1a
:created-at: 1690000000000
:END:
```

**Rules**:
- `:PROPERTIES:` must be the first non-blank line after the block heading/content.
- Each property line: `:KEY: value` (colon-wrapped key, single colon separator).
- `:END:` closes the drawer.
- Properties attached to the current block.
- Case-insensitive drawer names.

**Note**: This is DISTINCT from Logseq inline properties (`key:: value`) — note the single colon separator vs. double colon.

**Current SteleKit status**: `PropertiesParser` handles `:PROPERTIES:` drawers, but `BlockParser` does not integrate it.

**Priority**: P1 — Block UUIDs are stored here; losing these breaks block reference resolution.

---

### Drawers (General)

**CommonMark Rule**: N/A  
**GFM Extension**: No  
**Logseq behavior**: Beyond `:PROPERTIES:`, Logseq uses `:LOGBOOK:` for clock entries and custom drawers. mldoc supports `Drawer` type for any `:NAME:` ... `:END:` block.

**Syntax**:
```
:LOGBOOK:
CLOCK: [2023-07-22 Sat 14:00]--[2023-07-22 Sat 15:30] =>  1:30
:END:
```

**Rules**:
- Any `:UPPERCASE_NAME:` on its own line opens a drawer.
- `:END:` closes it.
- Content is literal text.

**Current SteleKit status**: Missing.

**AST node needed**: `DrawerBlockNode(name: String, content: String)`

**Priority**: P2

---

### LaTeX Environments (Display Math)

**CommonMark Rule**: N/A  
**GFM Extension**: No  
**Logseq behavior**: mldoc supports `\begin{env}...\end{env}` LaTeX environments as `Latex_Environment` blocks.

**Syntax**:
```
\begin{equation}
  E = mc^2
\end{equation}

\begin{align}
  x &= a + b \\
  y &= c + d
\end{align}
```

**Rules**:
- `\begin{name}` opens; `\end{name}` closes.
- Content is literal LaTeX, not parsed as Markdown.

**Current SteleKit status**: Missing.

**Priority**: P2 — Parse as `CodeFenceBlockNode(language="latex")`; render as a code block. Enables future MathJax/KaTeX integration without an AST change.

---

### Paragraphs

**CommonMark Rule**: Section 4.8  
**GFM Extension**: No  
**Logseq behavior**: In Logseq's outliner, top-level paragraph blocks (not preceded by `-`) can appear but are uncommon. The `ParagraphBlockNode` covers this.

**Syntax**: Any sequence of non-blank lines not matching another block construct.

**Rules**:
- Content parsed as inline elements.
- Leading/trailing spaces stripped.
- Up to 3 spaces of indentation.

**Current SteleKit status**: `ParagraphBlockNode` exists but is a fallback; needs correct interaction with heading/list detection.

**Priority**: P1 — Needed for page header content (title properties).

---

## Inline Constructs

### Bold (Strong Emphasis)

**CommonMark Rule**: Section 6.2 (strong emphasis)  
**GFM Extension**: No  
**Logseq behavior**: `**text**` is the primary bold syntax. `__text__` is also supported by CommonMark but must follow word-boundary rules (underscore bold only works when not in the middle of a word).

**Syntax**:
```
**bold text**
__bold text__
```

**Rules** (CommonMark left-flanking delimiter):
- Opening `**` must be followed by a non-whitespace character (left-flanking).
- Closing `**` must be preceded by a non-whitespace character (right-flanking).
- For `__`: additionally, must not be preceded/followed by a Unicode alphanumeric character (word boundary rule). `hello__world__` does NOT produce bold.
- Can span across soft line breaks within the same block.
- Cannot span across block boundaries.

**Current SteleKit status**: `**` works. `__` works in MarkdownEngine regex but fails word-boundary rules. InlineParser `parseEmphasis` doesn't implement left-flanking delimiter check.

**Priority**: P1

---

### Italic (Emphasis)

**CommonMark Rule**: Section 6.2 (emphasis)  
**GFM Extension**: No  
**Logseq behavior**: `*text*` is primary. `_text_` requires word-boundary checking — `hello_world_` must NOT italicize.

**Syntax**:
```
*italic text*
_italic text_
```

**Rules**:
- Same left-flanking / right-flanking rules as bold.
- For `_`: must not be preceded/followed by Unicode alphanumeric (word-boundary).
- `a_b_c` → plain text (mldoc rejects mid-word underscores).

**Current SteleKit status**: `*italic*` works. `_italic_` has word-boundary issues in both layers.

**Priority**: P1

---

### Bold+Italic (Combined Emphasis)

**CommonMark Rule**: Section 6.2  
**GFM Extension**: No  
**Logseq behavior**: `***text***` produces bold+italic.

**Syntax**:
```
***bold and italic***
___bold and italic___
**_bold and italic_**
*__bold and italic__*
```

**Rules**:
- `***` (3 asterisks) opens bold+italic simultaneously.
- Can also nest: `**_..._**` or `*__...__*`.
- Closing must match opening length.

**Current SteleKit status**: Missing. `parseEmphasis` with `len >= 2` treats `***` as bold, leaving the remaining `*` stranded.

**Priority**: P2

---

### Strikethrough

**CommonMark Rule**: N/A  
**GFM Extension**: Yes  
**Logseq behavior**: `~~text~~` is the standard strikethrough. Single `~` is NOT strikethrough (mldoc `~text~` is verbatim/code in Org mode only).

**Syntax**:
```
~~strikethrough text~~
```

**Rules**:
- Requires exactly 2 tildes on each side.
- Single `~` is plain text in Markdown mode.
- Renders as `<del>` in HTML / `TextDecoration.LineThrough` in Compose.
- Can contain other inline elements.

**Current SteleKit status**: Working in both layers. Single `~` correctly falls back to plain text (fixed bug).

**Priority**: Complete.

---

### Highlight

**CommonMark Rule**: N/A  
**GFM Extension**: No  
**Logseq behavior**: `==text==` produces highlighted text. This is a Logseq extension (from mldoc's `Highlight` type), not in CommonMark or GFM.

**Syntax**:
```
==highlighted text==
```

**Rules**:
- Requires exactly 2 `=` signs on each side.
- `=` is not currently a special character in the lexer.
- Renders as highlighted background (yellow in most Logseq themes).

**Current SteleKit status**: Missing in both layers. `=` is not a lexer token.

**Priority**: P2

---

### Inline Code (Code Span)

**CommonMark Rule**: Section 6.1  
**GFM Extension**: No  
**Logseq behavior**: Standard backtick code span. Inline code takes priority over emphasis — content inside backticks is never parsed for other markdown.

**Syntax**:
```
`code here`
`` code with `backtick` inside ``
```

**Rules**:
- Opening and closing backtick sequences must match in length.
- Content treated literally (no markdown, no backslash escapes, no entity references).
- Leading/trailing space stripped if both present: `` ` foo ` `` → `foo`.
- If no matching closing backtick found, the opening backtick is plain text (backtrack).
- Takes priority over `*`, `_`, etc. — emphasis cannot straddle a code span.

**Current SteleKit status**: Single backtick works. Multi-backtick spans missing. No backtracking on unclosed backtick (Bug 6 — consumes to EOF).

**Priority**: P0 — Unclosed backtick bug causes data loss.

---

### Links (Inline and Reference)

**CommonMark Rule**: Section 6.3  
**GFM Extension**: No  
**Logseq behavior**: Standard markdown links. mldoc supports optional title, `<url with spaces>` syntax, and backslash-escaped brackets in the label.

**Syntax**:
```
[link text](https://example.com)
[link with title](https://example.com "The Title")
[link with angle URL](<https://example.com/path with spaces>)
[reference link][ref-id]
[ref-id][]

[ref-id]: https://example.com "title"
```

**Rules**:
- Inline link: `[` text `](` url optional-title `)`.
- Text content is parsed as inline (can contain emphasis, code, etc.).
- URL: raw URL or `<url>` (angle brackets allow spaces).
- Title: optional, enclosed in `"..."`, `'...'`, or `(...)`.
- Reference link: `[text][label]` where label matches a link reference definition.
- Collapsed reference: `[text][]` where text itself is the label.
- Shortcut reference: `[text]` where text matches a label.
- Links cannot be nested inside other links.
- Backslash escapes work inside link text and URL.

**Current SteleKit status**: MarkdownEngine regex handles `[text](url)` but not titles or angle-bracket URLs. InlineParser `parseLink` returns `TextNode("[")` for non-wiki-links — completely broken at AST level (Bug 2).

**Priority**: P0 — Markdown links not parsed by AST layer causes broken reference extraction.

---

### Images

**CommonMark Rule**: Section 6.4  
**GFM Extension**: No  
**Logseq behavior**: Standard image syntax. Logseq renders images inline. Alt text can contain inline markup per CommonMark (though rarely used).

**Syntax**:
```
![Alt text](https://example.com/image.png)
![Alt with title](./local/image.jpg "Image title")
![Reference image][img-ref]
```

**Rules**:
- Identical to link syntax prefixed with `!`.
- Alt text: content in `[]` treated as plain text for screen readers.
- Inline images in Logseq are rendered directly in the block.

**Current SteleKit status**: MarkdownEngine `imagePattern` regex works. InlineParser AST layer missing.

**Priority**: P1

---

### Wiki-Links (Logseq Extension)

**CommonMark Rule**: N/A  
**GFM Extension**: No  
**Logseq behavior**: The primary linking mechanism in Logseq. Creates page references and builds the knowledge graph. Alias syntax separates display text from target page name.

**Syntax**:
```
[[Page Name]]
[[Page Name|Display Alias]]
[[page/namespace/subpage]]
[[page [[nested page ref]]]]
```

**Rules**:
- `[[` opens; `]]` closes.
- Target is the page name (case-sensitive for lookup, case-insensitive for display in Logseq).
- Alias syntax: first `|` inside separates target from display text — `[[Target|Alias]]`.
  - Display text on left of `|`, target page name on right? No — **Logseq convention**: `[[Page Name|Alias Text]]` where target is left, alias is right.
- Namespace/hierarchy: `/` in the page name creates namespace hierarchy. `[[programming/clojure]]` refers to the "clojure" page under the "programming" namespace.
- Nested links: `[[a [[b]]]]` — `b` is a page ref embedded in the label for `a`. This is a Logseq-specific construct (`Nested_link` in mldoc).
- Tags using wiki-link style: `#[[multi word tag]]` is a valid tag.
- Wiki-links inside macros: `{{embed [[Page Name]]}}` — must extract the inner wiki-link as a reference.

**Current SteleKit status**: Basic `[[Page Name]]` works. Alias (`[[p|alias]]`) stores `p|alias` as target (Bug 7). Nested links missing. `#[[tag]]` form missing.

**Priority**: P0 — Alias links produce broken page lookups.

---

### Block References (Logseq Extension)

**CommonMark Rule**: N/A  
**GFM Extension**: No  
**Logseq behavior**: References to a specific block by its UUID. Renders the referenced block's content inline (transclusion). A core Logseq feature.

**Syntax**:
```
((64d8e5a3-b77c-4c9b-a6c1-3a8e6d5f8b1a))
```

**Rules**:
- `((` opens; `))` closes.
- Content must be a valid UUID (format: `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`).
- In rendering: resolved to the referenced block's content at display time.
- Reference must be recorded in the block's `references` list for graph integrity.
- Unresolved references display as `((...))` placeholder.

**Current SteleKit status**: Working in both layers. `BLOCK_REF_OPEN`/`BLOCK_REF_CLOSE` tokens exist.

**Priority**: Complete.

---

### Tags / Hashtags (Logseq Extension)

**CommonMark Rule**: N/A (not in CommonMark)  
**GFM Extension**: No  
**Logseq behavior**: Tags create page references, identical to `[[Page Name]]` in graph terms. Tags can use dotted paths and can embed wiki-links for multi-word tags.

**Syntax**:
```
#tag
#multi-word-tag-with-hyphens
#a.b.c
#[[Multi Word Tag]]
#programming/clojure
```

**Rules**:
- `#` followed immediately by text (no space).
- Valid tag characters: alphanumeric, `-`, `_`, `.`, `/`.
- Tag terminates at whitespace or these characters: `,`, `;`, `.` (when followed by space), `!`, `?`, `"`, `:`.
  - Exception: `.` within a dotted path (`a.b.c`) does NOT terminate.
- `#[[page name]]` — wiki-link tag (allows spaces in tag name).
- Tag with dotted namespace: `#a.b.c` refers to the nested page `a/b/c`.
- Tags are page references — they appear in the page's linked references.

**Current SteleKit status**: Basic `#tag` works but terminates only at whitespace. Dot-in-tag logic missing. `#[[...]]` form missing. InlineParser stops at non-TEXT tokens; MarkdownEngine regex `#([^\s#.,!\[\]()]+)` is closer but misses `:` terminator.

**Priority**: P1

---

### Inline Properties (Logseq Extension)

**CommonMark Rule**: N/A  
**GFM Extension**: No  
**Logseq behavior**: `key:: value` on its own line within a block sets a property on that block. Properties are metadata, not displayed as content. The first block of a page with only properties sets page-level properties.

**Syntax**:
```
- Some block content
  key:: value
  another-key:: another value
  date.format:: YYYY-MM-DD
  tags:: [[page1]], [[page2]]
```

**Rules**:
- Line format: `key:: value` — key followed by `::` (double colon), then a space, then value.
- Key characters: alphanumeric, `-`, `_`, `.` (dotted keys like `date.format` are valid).
- Value is a string; can contain wiki-links, tags, comma-separated lists.
- Property lines are removed from block content and stored separately.
- Multi-word values allowed: `full name:: John Doe`.
- The `:PROPERTIES:` / `:END:` drawer is an alternative (Org-mode style, same semantics).
- Empty value: `key::` with nothing after `::` → `""`.

**Current SteleKit status**: `PropertiesParser` handles both styles. `BlockParser.tryParseProperty()` has an off-by-one bug in `peekToken` (Bug 3) and the regex `[\w\-_]+` misses dots in keys (Bug 4).

**Priority**: P0 — Property keys with dots silently dropped; `peekToken` bug causes property detection failures.

---

### Task Markers (Logseq Extension)

**CommonMark Rule**: N/A  
**GFM Extension**: Partially (GFM task list items use `[ ]`/`[x]` syntax)  
**Logseq behavior**: Task markers appear at the START of a bullet's content. Full marker set: `TODO`, `DOING`, `DONE`, `LATER`, `NOW`, `WAITING`, `WAIT`, `CANCELED`, `CANCELLED`, `STARTED`, `IN-PROGRESS`.

**Syntax**:
```
- TODO Write the parser spec
- DOING Implement wiki-link alias
- DONE Fix the backtick bug
- LATER Read about Pratt parsers
- NOW Fix critical bug
- WAITING Waiting on external feedback
- CANCELLED Old task no longer needed
```

**Rules**:
- Marker is a keyword at the very start of block content (after `- ` bullet marker and indentation).
- Case-sensitive (`TODO` not `todo`).
- Full marker set (mldoc): `TODO`, `DOING`, `WAITING`, `WAIT`, `DONE`, `CANCELED`, `CANCELLED`, `STARTED`, `IN-PROGRESS`, `NOW`, `LATER`.
- After the marker, optional priority `[#A]`, then content.
- Task markers make the block a "task" block.
- GFM `[ ]`/`[x]` checkbox syntax is distinct from Logseq task markers (both should be supported).

**Current SteleKit status**: Missing in both layers. Currently rendered as literal text (e.g., `TODO Fix bug` appears with "TODO" as plain text).

**AST node needed**: `TaskMarkerNode(marker: TaskMarker)` where `TaskMarker` is an enum.

**Priority**: P1

---

### Task Priority (Logseq Extension)

**CommonMark Rule**: N/A  
**GFM Extension**: No  
**Logseq behavior**: Priority marker appears after the task marker, before block content. Three levels: A (highest), B, C.

**Syntax**:
```
- TODO [#A] Critical task
- TODO [#B] Important task
- LATER [#C] Low priority
```

**Rules**:
- Format: `[#A]`, `[#B]`, or `[#C]` — uppercase letter only.
- Must appear after a task marker (cannot appear without a task marker).
- Stored as `priority: Char` (the letter A, B, or C).

**Current SteleKit status**: Missing.

**AST node needed**: `priority: Char?` field on `BulletBlockNode` or a dedicated `PriorityNode`.

**Priority**: P2

---

### GFM Task List Items

**CommonMark Rule**: N/A  
**GFM Extension**: Yes  
**Logseq behavior**: mldoc treats `- [ ] task` and `- [x] task` as a special list item form, distinct from Logseq's keyword-based tasks. Both forms coexist.

**Syntax**:
```
- [ ] Unchecked task
- [x] Checked task
- [X] Also checked (uppercase X)
```

**Rules**:
- Must be a list item (bullet) with `[ ]` or `[x]`/`[X]` as the first content after the bullet marker.
- `[ ]` = unchecked; `[x]` or `[X]` = checked.
- Renders as `<input type="checkbox">` in HTML / a checkbox UI in Compose.
- Content after the checkbox can contain any inline elements.

**Current SteleKit status**: Missing. Currently parsed as regular bullet with literal `[ ]` or `[x]` text.

**Priority**: P2

---

### SCHEDULED / DEADLINE Timestamps (Logseq Extension)

**CommonMark Rule**: N/A  
**GFM Extension**: No  
**Logseq behavior**: Org-mode style date/time scheduling. Appears as a property-like line in a block. `SCHEDULED` sets a start/due date. `DEADLINE` sets a hard deadline. Logseq renders these with a calendar icon.

**Syntax**:
```
- TODO Fix the bug
  SCHEDULED: <2023-07-22 Sat>
  DEADLINE: <2023-07-25 Tue>

- Meeting with team
  SCHEDULED: <2023-07-22 Sat 14:00>

- Recurring task
  SCHEDULED: <2023-07-22 Sat .+1w>
```

**Timestamp format**: `<YYYY-MM-DD DayAbbrev HH:MM repeater>` where:
- Day abbreviation: `Mon`, `Tue`, `Wed`, `Thu`, `Fri`, `Sat`, `Sun`
- Time: optional `HH:MM`
- Repeater: optional `.+Nunit` where unit is `d`/`w`/`m`/`y`

**Rules**:
- `SCHEDULED:` or `DEADLINE:` followed by whitespace and a timestamp in `<>`.
- Stripped from block content and stored as structured metadata.
- Both can appear in the same block.

**Current SteleKit status**: `TimestampParser` handles via regex and stores as strings. Strips from content correctly. No rich representation (just raw string).

**Priority**: P2 — Working minimally; enhancement needed for rich date representation.

---

### Macros (Logseq Extension)

**CommonMark Rule**: N/A  
**GFM Extension**: No  
**Logseq behavior**: Logseq macros are the extension mechanism for transclusion, queries, and plugin renderers. Critical for graph integrity — embedded wiki-links in macro arguments must be extracted as references.

**Syntax**:
```
{{embed [[Page Name]]}}
{{embed ((block-uuid))}}
{{query (and [[tag1]] [[tag2]])}}
{{renderer :logseq/src, src}}
{{youtube https://youtu.be/xxx}}
{{tweet https://twitter.com/...}}
```

**Macro format**:
- `{{name arguments}}`
- Name: alphanumeric + `-` / `_`
- Arguments: space-separated after name; can contain wiki-links, block refs, Datalog queries
- Triple-brace form `{{{name}}}` also exists in mldoc tests

**Rules**:
- `{{` opens; `}}` closes.
- Content between braces is the macro call.
- First word is the macro name; remainder is arguments.
- Must extract any `[[wiki-links]]` or `((block-refs))` within arguments for graph reference tracking.
- Unknown macro names → render as literal text with the macro syntax visible.
- Well-known macros: `embed`, `query`, `renderer`, `youtube`, `tweet`, `vimeo`, `bilibili`.

**Current SteleKit status**: Missing. `{` is not a lexer special character. All macros render as plain text (Bug 8).

**AST node needed**: `MacroNode(name: String, arguments: List<String>)`

**Priority**: P1 — `{{embed [[Page]]}}` is extremely common in Logseq graphs; embedded references are missed.

---

### Autolinks (Standard)

**CommonMark Rule**: Section 6.5  
**GFM Extension**: Extended (see below)  
**Logseq behavior**: Angle-bracket autolinks are rare in Logseq files; bare URL autolinks (GFM extended) are common.

**Syntax**:
```
<https://example.com>
<mailto:user@example.com>
<user@example.com>
```

**Rules**:
- URL scheme + colon + valid URI characters, enclosed in `<>`.
- Email: ASCII alphanumeric + `-`, `_`, `.`, `+` + `@` + domain, enclosed in `<>`.
- Content is URL-encoded in href.

**Current SteleKit status**: Missing at AST layer. MarkdownEngine `urlPattern` handles bare URLs (not angle-bracket form).

**Note**: The `<email@example.com>` angle-bracket form should be supported (P2). Bare email autolinks (without angle brackets) remain out of scope due to ambiguity in prose.

**Priority**: P2

---

### Extended Autolinks (GFM)

**CommonMark Rule**: N/A  
**GFM Extension**: Yes  
**Logseq behavior**: Bare URLs (without `[]()` syntax) should be detected and made clickable. This is the most common way links appear in Logseq content.

**Syntax**:
```
Visit https://example.com for more info.
Check out www.github.com too.
Email user@example.com directly.
```

**Rules**:
- URL beginning with `http://` or `https://` followed by non-whitespace characters.
- `www.` prefix also triggers autolink detection.
- Email addresses matching standard format.
- Terminates at whitespace, `<`, `>`, `"`, `)` or trailing punctuation.
- Does NOT require angle brackets.

**Current SteleKit status**: MarkdownEngine `urlPattern = Regex("""https?://[^\s<>"]+""")` handles `http`/`https` URLs. `www.` and bare email forms missing.

**Priority**: P2

---

### Inline HTML

**CommonMark Rule**: Section 6.6  
**GFM Extension**: Disallowed raw HTML filters certain tags  
**Logseq behavior**: mldoc supports `Inline_Html`. In Compose rendering, inline HTML cannot be rendered natively and must be either stripped or shown as-is.

**Syntax**:
```
<span style="color:red">red text</span>
<b>bold via HTML</b>
<!-- comment -->
```

**Rules** (CommonMark):
- Open tags, close tags, comments, processing instructions, CDATA, and declarations treated as raw HTML.
- Must form valid HTML syntax (not just any `<...>` sequence).
- Not processed for Markdown inside HTML tags.

**GFM disallowed tags**: `<title>`, `<textarea>`, `<style>`, `<xmp>`, `<iframe>`, `<noembed>`, `<noframes>`, `<script>`, `<plaintext>` — replaced with escaped equivalents.

**Current SteleKit status**: Missing. In Compose context, pass-through is not feasible; treat as plain text or code span.

**Priority**: P2 — Parse and preserve; render as inline code span showing the raw HTML.

---

### Hard Line Breaks

**CommonMark Rule**: Section 6.7  
**GFM Extension**: No  
**Logseq behavior**: mldoc supports `Hard_Break_Line`. In Logseq's outliner model, most content is single-line per bullet, but multi-line blocks exist (continuation lines).

**Syntax**:
```
Line one  
Line two (preceded by 2+ trailing spaces)

Line one\
Line two (backslash at end)
```

**Rules**:
- Two or more trailing spaces at end of line → `<br />`.
- Backslash immediately before newline → `<br />`.
- Applies within paragraphs and list items; NOT in headings or code blocks.

**Current SteleKit status**: Missing. `NEWLINE` tokens are consumed without special treatment.

**Priority**: P2

---

### Soft Line Breaks

**CommonMark Rule**: Section 6.8  
**GFM Extension**: No  
**Logseq behavior**: mldoc emits `Break_Line` nodes. In Logseq's rendering, soft breaks are typically rendered as spaces (or ignored) rather than `<br>`.

**Syntax**: A single newline within a paragraph (no trailing spaces, no backslash).

**Rules**:
- Rendered as a single space in HTML output.
- Can be treated as literal line continuation in tight block context.

**Current SteleKit status**: Missing distinct representation. Newlines within block content are passed through.

**Priority**: P3

---

### LaTeX Inline Fragments

**CommonMark Rule**: N/A  
**GFM Extension**: No  
**Logseq behavior**: mldoc supports `Latex_Fragment` for inline math. Common in academic/science notes.

**Syntax**:
```
Inline math: $E = mc^2$
Alt form: \(E = mc^2\)
```

**Rules**:
- `$...$` for inline math.
- `\(...\)` is an alternative form (mldoc: `Latex_Fragment (Inline "...")`)
- Content is literal LaTeX, not parsed for other Markdown.
- `$$...$$` and `\[...\]` are DISPLAY math (block-level, see below).

**Current SteleKit status**: Missing.

**Priority**: P2

---

### LaTeX Display Math (Block-Level)

**CommonMark Rule**: N/A  
**GFM Extension**: No  
**Logseq behavior**: mldoc supports `Displayed_Math` block type.

**Syntax**:
```
$$
E = mc^2
$$

\[
\int_0^\infty x^2 dx
\]
```

**Rules**:
- `$$` on its own line opens; `$$` on its own line closes.
- `\[` / `\]` alternative delimiters.
- Content is literal LaTeX.

**Current SteleKit status**: Missing.

**Priority**: P2

---

### Subscript / Superscript (Logseq Extension)

**CommonMark Rule**: N/A  
**GFM Extension**: No  
**Logseq behavior**: mldoc supports subscript and superscript with brace syntax.

**Syntax**:
```
H_{2}O         → H₂O (subscript)
x^{2}          → x² (superscript)
x^2            → x² (unbraced form also supported)
```

**Rules**:
- `_{text}` for subscript; `^{text}` for superscript.
- Unbraced form `^text` also recognized for single-word superscripts.
- Content is literal (not parsed for further markdown in braced form).

**Current SteleKit status**: Missing.

**Priority**: P2 — Compose supports `BaselineShift` for super/subscript rendering.

---

### Backslash Escapes

**CommonMark Rule**: Implicit in all inline rules  
**GFM Extension**: No  
**Logseq behavior**: mldoc supports `\*`, `\[`, `\]`, `\(`, `\)`, `\_`, `\\` etc. to escape special characters.

**Syntax**:
```
\*literal asterisk\*
\[[not a link\]]
\\ → literal backslash
```

**Rules**:
- `\` before any ASCII punctuation character treats it as literal.
- Recognized escapable characters: `!`, `"`, `#`, `$`, `%`, `&`, `'`, `(`, `)`, `*`, `+`, `,`, `-`, `.`, `/`, `:`, `;`, `<`, `=`, `>`, `?`, `@`, `[`, `\`, `]`, `^`, `_`, `` ` ``, `{`, `|`, `}`, `~`.
- `\n` (backslash before newline) = hard line break.
- Does not work inside code spans or fenced code blocks.

**Current SteleKit status**: Missing in both layers. Causes issues with `[[page|alias]]` and link labels.

**Priority**: P2

---

### Entity and Numeric Character References

**CommonMark Rule**: Section 6.2 (character references)  
**GFM Extension**: No  
**Logseq behavior**: mldoc supports `Entity` type with Unicode lookup. Relevant for mathematical and special symbols.

**Syntax**:
```
&amp;       → &
&lt;        → <
&gt;        → >
&nbsp;      → non-breaking space
&#35;       → # (decimal)
&#x22;      → " (hex)
\Delta      → Δ (mldoc entity syntax — backslash + name)
```

**Rules**:
- HTML5 named entities: `&name;` (case-sensitive).
- Decimal numeric: `&#digits;`.
- Hex numeric: `&#xhex;` or `&#Xhex;`.
- mldoc-specific: `\Name` as entity reference (LaTeX-style names mapped to Unicode).
- Not recognized inside code spans/blocks.
- Invalid references are passed through as literal text.

**Current SteleKit status**: Missing.

**Priority**:
- **P1** for HTML entities (`&amp;`, `&lt;`, `&gt;`, `&nbsp;`, numeric `&#N;`, `&#xN;`) — CommonMark requires these to be decoded; they appear in real pasted content.
- **P3** for LaTeX-style entities (`\Delta`, `\alpha`, etc.) — niche STEM usage only.

---

### Footnote References (CommonMark Extension)

**CommonMark Rule**: N/A (not in base CommonMark; extended by many processors)  
**GFM Extension**: No (not in GFM spec)  
**Logseq behavior**: mldoc supports `Footnote_Reference` inline nodes and `Footnote_Definition` blocks.

**Syntax**:
```
This sentence has a footnote.[^1]

[^1]: This is the footnote definition.
[^long]: Footnote with a longer label.
```

**Rules**:
- `[^label]` creates a reference; `[^label]: text` creates the definition.
- Labels: alphanumeric and `-`, `_`.
- Definitions can be multi-paragraph (indented continuation).

**Current SteleKit status**: Missing.

**Priority**: P2 — Useful for research/academic notes; mldoc has first-class support.

---

### Plain Text

**CommonMark Rule**: Section 6.9  
**GFM Extension**: No  

Any sequence of characters not consumed by the above constructs. Treated as literal text. Unicode content passed through unchanged.

**Priority**: Always implemented (baseline).

---

## Logseq Extensions (Not in CommonMark/GFM)

This section collects Logseq-specific constructs not covered above.

### Page Embed

**Syntax**: `{{embed [[Page Name]]}}`  
**Semantics**: Transclude the entire contents of the named page inline.  
**Implementation**: This is a `MacroNode` with `name = "embed"` and a single wiki-link argument. The embedded content is resolved at render time.  
**Priority**: P1 (handled via Macro support).

---

### Block Embed

**Syntax**: `{{embed ((block-uuid))}}`  
**Semantics**: Transclude the specific block (and its children) at this location.  
**Implementation**: `MacroNode` with `name = "embed"` and a single block-ref argument.  
**Priority**: P1 (handled via Macro support).

---

### Query Blocks

**Syntax**: `{{query (and [[tag1]] [[tag2]])}}`  
**Semantics**: Executes a Datalog query against the graph and renders results inline.  
**Implementation**: `MacroNode` with `name = "query"` and a Datalog expression argument. Query arguments contain wiki-links that must be extracted as references.  
**Priority**: P2 (parsed as macro; query execution is outside parser scope).

---

### Namespace / Hierarchical Pages

**Syntax**: `[[parent/child/grandchild]]`  
**Semantics**: Pages organized in a hierarchy using `/` as separator. `[[programming/clojure]]` is a child of `[[programming]]`.  
**Implementation**: Handled within wiki-link parsing. The `/` is part of the page name. The graph layer is responsible for namespace hierarchy resolution.  
**Priority**: P1 (must not break wiki-link parsing for `/`-containing names).

---

### Page Aliases

**Syntax** (property): `alias:: My Old Name, Another Name`  
**Semantics**: Register alternative names for a page. Lookups for alias names resolve to this page.  
**Implementation**: Handled via property parsing (`alias` is a well-known property key). The value is a comma-separated list of aliases.  
**Priority**: P2.

---

### Radio Targets

**Syntax**: `<<<target text>>>`  
**Semantics**: Defines a link target that can be referenced from `[[target text]]` anywhere in the graph without creating a dedicated page.  
**Implementation**: mldoc supports this as `Radio_Target`. Parse-and-preserve; extract as a reference node so the graph layer can register the target.  
**Priority**: P2 — Parse and extract as reference; full bidirectional resolution is graph layer responsibility.

---

### Export Snippets

**Syntax**: `@@format:content@@`  
**Semantics**: Format-specific inline export content (e.g., `@@html:<b>bold</b>@@`).  
**Implementation**: mldoc supports `Export_Snippet`.  
**Priority**: P3 — Niche feature.

---

## Out of Scope

Features present in CommonMark, GFM, or mldoc that SteleKit explicitly does NOT need to implement, with rationale:

| Feature | Rationale |
|---------|-----------|
| **Org-mode headings** (`* Heading`) | SteleKit targets Markdown format exclusively. Org-mode is a separate format that Logseq supports but SteleKit does not. |
| **Org TBLFM table formulas** | Org-mode only. Markdown format does not use table formulas. |
| **`#+TBLFM:`** | Org-mode table formula directive. Out of scope. |
| **GFM disallowed raw HTML filtering** | Relevant for web publishing security; SteleKit does not output raw HTML to the web, so XSS filtering is not applicable. Relevant for a future exporter feature, not the parser itself. |
| **Export snippets** (`@@format:...@@`) | Logseq export feature; not relevant for a viewer/editor app. |
| **LaTeX entities** (`\Delta`, `\alpha`, etc.) | Low frequency in typical note-taking; only relevant for STEM users. HTML entities (`&amp;`, etc.) are P1 and handled separately. Defer LaTeX-style names to P3. |
| **Bare email autolinks** (`user@example.com`) | Ambiguous in prose; high false-positive rate. The `<user@example.com>` angle-bracket form is supported (P2) and unambiguous. |

---

## Summary Priority Table

### P0 — Must fix (data loss / correctness)

| Construct | Issue |
|-----------|-------|
| Bullet lists (`-`, `*`, `+`) | `*` and `+` bullets not recognized; indent level rounding bug |
| Inline code (`` ` ``) | Unclosed backtick consumes to EOF instead of backtracking |
| Markdown links `[text](url)` | AST layer returns `TextNode("[")` — broken reference extraction |
| Wiki-link alias `[[page\|alias]]` | Alias stored as part of target — page lookups fail |
| Inline properties (`key:: value`) | Dotted key names dropped; `peekToken` off-by-one in BlockParser |

### P1 — Core Logseq parity

| Construct |
|-----------|
| ATX headings (`# text`) |
| Fenced code blocks (` ``` `) |
| Task markers (`TODO`, `DONE`, `NOW`, etc.) |
| Macros (`{{embed [[page]]}}`) |
| Images (`![alt](url)`) |
| Tags (complete termination rules, `#[[...]]` form) |
| Property drawers (`:PROPERTIES:` / `:END:`) integration in BlockParser |
| Namespace wiki-links (`[[parent/child]]`) |
| HTML entities (`&amp;`, `&lt;`, `&gt;`, `&nbsp;`, `&#N;`, `&#xN;`) |

### P2 — Feature completeness

| Construct |
|-----------|
| Bold+Italic (`***text***`) |
| Highlight (`==text==`) |
| Blockquotes (`> text`) |
| Ordered lists (`1.` / `1)`) |
| Tables (GFM pipe tables) |
| GFM task list items (`[ ]` / `[x]`) |
| Task priority (`[#A]`, `[#B]`, `[#C]`) |
| SCHEDULED / DEADLINE (rich representation) |
| Hard line breaks (trailing `  ` or `\`) |
| Thematic breaks (`---`) |
| Autolinks (standard `<url>` form; extended bare URLs; `<email@>` angle-bracket form) |
| LaTeX inline (`$...$`) and display (`$$...$$`) |
| Backslash escapes |
| Custom blocks (`#+begin_name`) |
| Drawers (`:LOGBOOK:`, custom drawers) |
| Setext headings (parse on read; normalize to ATX on write) |
| HTML blocks / inline HTML (`RawHtmlBlockNode` — render as code block) |
| Hiccup blocks (`HiccupBlockNode` — render as grayed-out code) |
| LaTeX environments (`\begin{...}` → `CodeFenceBlockNode(language="latex")`) |
| Subscript / Superscript (`_{x}` / `^{x}`) |
| Footnotes (`[^id]`, `[^id]: text`) |
| Radio targets (`<<<text>>>` — parse-and-preserve, extract as reference) |
| Indented code blocks (4-space) — disabled in outline mode; enabled in flat-document mode |

### P3 — Extended features

| Construct |
|-----------|
| LaTeX-style entity names (`\Delta`, `\alpha`) |
| Front matter (YAML `---`) |
| Link reference definitions (`[ref]: url`) |
| Nested wiki-links (`[[a [[b]]]]`) |
| Soft line breaks (distinct node type) |

---

## AST Extension Checklist

New AST nodes required (beyond current `AST.kt`):

```kotlin
// Block nodes
HeadingBlockNode(level: Int, marker: TaskMarker?, priority: Char?, content: List<InlineNode>, children: List<BlockNode>)
CodeFenceBlockNode(language: String?, options: List<String>, rawContent: String)
BlockquoteBlockNode(children: List<BlockNode>)
OrderedListItemBlockNode(number: Int, content: List<InlineNode>, children: List<BlockNode>, properties: Map<String, String>)
TableBlockNode(headers: List<TableCell>, alignment: List<Alignment?>, rows: List<List<TableCell>>)
DrawerBlockNode(name: String, rawContent: String)
CustomBlockNode(name: String, rawContent: String)
HorizontalRuleBlockNode()
RawHtmlBlockNode(rawContent: String)       // HTML blocks + inline HTML — render as code block
HiccupBlockNode(rawContent: String)        // Hiccup blocks — render as grayed-out code
RadioTargetNode(text: String)              // <<<text>>> — extract as reference

// Inline nodes
MacroNode(name: String, arguments: List<String>)
TaskMarkerNode(marker: TaskMarker)  // or field on BulletBlockNode
HighlightNode(children: List<InlineNode>)
LatexInlineNode(content: String)
SubscriptNode(content: String)
SuperscriptNode(content: String)
FootnoteRefNode(label: String)
HardBreakNode()
```

**Lexer token additions required**:
- `DOUBLE_HASH` — `##` … `######` for ATX heading detection
- `MACRO_OPEN` — `{{`
- `MACRO_CLOSE` — `}}`
- `PIPE` — `|` (for table cells and wiki-link alias separator)
- `EQUALS` — `=` (for `==highlight==`)
- `GT` — `>` (for blockquotes)
- `BACKTICK_FENCE` — ` ``` ` (3+ backticks at line start)
- `ORDERED_MARKER` — `\d+[.)]` (digits followed by `.` or `)`)
