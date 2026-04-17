# Features Research: Logseq Block Types & Comparable Renderers

## Logseq Supported Block Types

Logseq is an outliner where every item is a "block" (a bullet node). Within block content, the following block-level markdown constructs are supported:

| Block Type | Supported | Notes |
|---|---|---|
| Headings (H1–H6) | Yes | Standard ATX `#` syntax |
| Paragraphs | Yes | Require hyphen/bullet prefix at top level; line breaks use CR |
| Unordered lists | Yes | Standard `-` / `*` |
| Ordered lists | Yes | Standard `1.` numbering |
| Task lists (`- [ ]` / `- [x]`) | No | Logseq uses native TODO/DOING/DONE or LATER/NOW keyword state instead |
| Fenced code blocks (` ``` `) | Yes | Syntax highlighting included; always uses markdown fence even in org-mode |
| Blockquotes (`>`) | Yes | Standard `>` prefix |
| Tables | Yes | GFM pipe table syntax |
| Horizontal rules | Yes | `---` / `***` / `___` |
| Inline HTML | Yes | Must be valid XHTML (self-closing tags required) |
| HTML blocks | Partial | Limited to valid XHTML; full block HTML not guaranteed |
| Images | Yes | Inline `![alt](url)` syntax |
| Links | Yes | Including auto-linking of bare URLs |
| Math / LaTeX | Yes | KaTeX is included as a dependency; `$$...$$` block math supported |
| Footnotes | No | Listed as a roadmap item |
| Definition lists | No | Not supported |
| Emoji shortcodes | No | Copy-paste emoji works; `:shortcode:` syntax does not |

### Logseq-Specific Block Extensions

- **Properties / frontmatter**: `key:: value` pairs at the start of a block
- **Block references**: `((block-uuid))` embeds another block inline
- **Page embeds / block embeds**: `{{embed [[page]]}}` / `{{embed ((uuid))}}`
- **Queries**: `{{query ...}}` DSL for dynamic block queries
- **Macros**: `{{macro-name args}}`
- **Namespace pages**: `parent/child` page naming
- **Draw / Excalidraw blocks**: fenced code with `excalidraw` language tag

---

## Callouts / Admonitions in Logseq

Logseq does **not** support GitHub-style callouts (`> [!NOTE]`, `> [!WARNING]`, etc.). Those are a GitHub-specific extension; Logseq's parser does not recognise them.

Logseq supports admonition blocks via **org-mode `#+BEGIN_` / `#+END_` syntax**. These render with a styled callout box in the UI. Access via the `<` slash-command picker.

Confirmed admonition types (from community discussion and the `<` picker):

| Block | Org syntax |
|---|---|
| Warning | `#+BEGIN_WARNING` … `#+END_WARNING` |
| Note | `#+BEGIN_NOTE` … `#+END_NOTE` |
| Tip | `#+BEGIN_TIP` … `#+END_TIP` |
| Important | `#+BEGIN_IMPORTANT` … `#+END_IMPORTANT` |
| Caution | `#+BEGIN_CAUTION` … `#+END_CAUTION` |
| Pinned | `#+BEGIN_PINNED` … `#+END_PINNED` |
| Quote | `#+BEGIN_QUOTE` … `#+END_QUOTE` |
| Example | `#+BEGIN_EXAMPLE` … `#+END_EXAMPLE` |
| Export (HTML) | `#+BEGIN_EXPORT html` … `#+END_EXPORT` |
| Center | `#+BEGIN_CENTER` … `#+END_CENTER` |
| Verse | `#+BEGIN_VERSE` … `#+END_VERSE` |

These are Logseq-specific rendered blocks. Other apps that open the same `.md` files will see them as plain text or fenced code blocks — they are **not** portable markdown.

---

## Org-mode Block Syntax (Logseq)

Even in Markdown mode (not Org-mode mode), Logseq accepts the `#+BEGIN_` / `#+END_` syntax for admonitions. In Org-mode mode, `#+BEGIN_SRC lang` is the canonical code block form (analogous to a fenced code block in markdown), and Logseq maps this to its code editor with syntax highlighting. The shortcut `/Code Block` always inserts the markdown fence (` ``` `) regardless of graph format.

Key org-mode blocks and their Logseq behaviour:

| Org block | Logseq behaviour |
|---|---|
| `#+BEGIN_SRC lang` … `#+END_SRC` | Rendered as syntax-highlighted code block (org-mode graphs) |
| `#+BEGIN_EXAMPLE` … `#+END_EXAMPLE` | Rendered as a styled "Example" callout box |
| `#+BEGIN_QUOTE` … `#+END_QUOTE` | Rendered as a styled "Quote" callout box (distinct from `>` blockquote) |
| `#+BEGIN_NOTE` … `#+END_NOTE` | Rendered as a styled info callout |
| `#+BEGIN_WARNING` … `#+END_WARNING` | Rendered as a styled warning callout |
| Other `#+BEGIN_X` | Rendered with the corresponding label if recognised; otherwise falls back to plain text |

---

## Comparable KMP Renderer Survey

### multiplatform-markdown-renderer (Mikepenz)

**Repo**: https://github.com/mikepenz/multiplatform-markdown-renderer  
**Parser**: JetBrains Markdown with GFM (GitHub Flavored Markdown) descriptor  
**Targets**: Android, iOS, Desktop (JVM), Wasm (Web)

| Block Type | Supported | Notes |
|---|---|---|
| Headings (H1–H6, ATX + Setext) | Yes | Full support |
| Paragraphs | Yes | |
| Unordered lists | Yes | Nesting supported |
| Ordered lists | Yes | Nesting supported |
| Task lists (`- [ ]`) | Yes | Checkbox rendering |
| Fenced code blocks | Yes | Syntax highlighting via optional `multiplatform-markdown-renderer-code` (Highlights) add-on |
| Blockquotes | Yes | Including nested blockquotes |
| Tables | Yes | Cell alignment supported |
| Images | Yes | Async loading supported |
| Horizontal rules | Yes | |
| Inline HTML | No | Not rendered |
| HTML blocks | No | Not rendered |
| Math / LaTeX | No | No built-in support; would require a custom `MarkdownComponents` handler |
| GitHub-style callouts (`> [!NOTE]`) | No | Not built-in; parser sees them as blockquotes |
| Org-mode `#+BEGIN_` blocks | No | Parser does not understand org syntax |
| Logseq-specific extensions | No | Block refs, embeds, queries not parsed |

**Extensibility**: `MarkdownComponents` interface allows custom element renderers for any AST node type.

---

### compose-richtext (halilozercan)

**Repo**: https://github.com/halilozercan/compose-richtext  
**Modules**: `richtext-commonmark` (CommonMark parser), `richtext-markdown` (legacy)  
**Targets**: Android, Desktop (JVM); iOS support listed as limited/absent  
**Parser**: CommonMark spec

| Block Type | Supported | Notes |
|---|---|---|
| Headings (H1–H6) | Yes | |
| Paragraphs | Yes | |
| Unordered lists | Yes | `*`, `-`, `+` bullets |
| Ordered lists | Yes | |
| Task lists (`- [ ]`) | Unknown | Not explicitly documented; CommonMark itself does not define task lists (GFM extension) |
| Fenced code blocks | Yes | Monospace font rendering |
| Blockquotes | Yes | `>` syntax |
| Tables | Yes | Pipe-delimited GFM tables |
| Images | Yes | Including base64-encoded inline images |
| Horizontal rules | Yes | |
| Inline HTML | Unknown | Not explicitly documented |
| HTML blocks | No | Not mentioned |
| Math / LaTeX | No | Not supported |
| GitHub-style callouts (`> [!NOTE]`) | No | Not built-in |
| Org-mode `#+BEGIN_` blocks | No | |
| Logseq-specific extensions | No | |

**Extensibility**: Custom block rendering via `AstBlockNode` interception; richtext-ui provides `FormattedList`, `Table`, `CodeBlock` primitives that can be composed directly.

**iOS caveat**: Modules are tagged Compose Multiplatform but iOS support is incomplete/absent as of 2024.

---

## Gap Analysis

The following Logseq block constructs have **no equivalent in either KMP renderer**:

| Logseq Feature | Gap in KMP Renderers | Mitigation |
|---|---|---|
| Math / LaTeX (`$$...$$`) | Neither library supports it | Must integrate KaTeX (JS-side) or a KMP math renderer; or render in a WebView |
| Org-mode `#+BEGIN_NOTE/WARNING/TIP/...` admonitions | Neither library parses `#+BEGIN_` syntax | Requires a custom pre-processing step to convert to a supported representation (e.g. a fenced block with a metadata attribute), then a custom renderer |
| `#+BEGIN_QUOTE` / `#+BEGIN_EXAMPLE` | Same as above | Same mitigation |
| GitHub-style callouts `> [!NOTE]` | Neither renders them as styled callouts (both treat as plain blockquote) | Custom `MarkdownComponents` handler in Mikepenz, or custom AST post-processing |
| Block references `((uuid))` | Not markdown; no renderer supports it | Must be resolved to content by SteleKit's own pipeline before rendering |
| Block/page embeds `{{embed ...}}` | Not markdown; no renderer supports it | Same — resolve in pipeline |
| Query blocks `{{query ...}}` | Not markdown | Must be evaluated and replaced with result before rendering |
| Task state keywords (TODO/DOING/DONE) | Neither renderer knows Logseq TODO states | Must be transformed to `- [ ]` / `- [x]` before passing to renderer |
| Inline HTML (Logseq requires XHTML) | Mikepenz drops HTML; compose-richtext unclear | Strip or sanitize HTML upstream |
| Footnotes | Not supported by Logseq either; moot | — |

**Summary**: The two biggest gaps requiring custom work are (1) **math/LaTeX** rendering and (2) **org-mode admonition blocks**. Both Mikepenz and compose-richtext cover the standard GFM block set well (headings, lists, tables, code, blockquotes, task lists). SteleKit will need a preprocessing pipeline that resolves Logseq-specific constructs (block refs, embeds, queries, TODO keywords, `#+BEGIN_` blocks) before handing content to a KMP renderer.

---

## Sources

- [Logseq Markdown Reference — Markdown Guide](https://www.markdownguide.org/tools/logseq/)
- [Logseq Admonitions Forum Thread](https://discuss.logseq.com/t/how-to-format-as-admonitions/3000)
- [Block Parsing and Transformation — DeepWiki (logseq/logseq)](https://deepwiki.com/logseq/logseq/6.1-format-and-block-processing)
- [Markdown Element Rendering — DeepWiki (mikepenz/multiplatform-markdown-renderer)](https://deepwiki.com/mikepenz/multiplatform-markdown-renderer/3-markdown-element-rendering)
- [multiplatform-markdown-renderer GitHub](https://github.com/mikepenz/multiplatform-markdown-renderer)
- [compose-richtext GitHub](https://github.com/halilozercan/compose-richtext)
- [Commonmark Markdown — compose-richtext docs](https://halilibo.com/compose-richtext/richtext-commonmark/)
- [Logseq Markdown Cheat Sheet — Face Dragons](https://facedragons.com/foss/logseq-markdown-cheat-sheet/)
