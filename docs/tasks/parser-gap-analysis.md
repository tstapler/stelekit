# Parser Gap Analysis: SteleKit vs mldoc

## Executive Summary

SteleKit has two parallel parsing systems that are partially redundant and inconsistently capable:

1. **`parsing/` (LogseqParser / BlockParser / InlineParser)** — A hand-written Pratt-style recursive descent parser that produces an AST. Used by `MarkdownParser.kt` for data extraction (references, properties, children).
2. **`ui/components/MarkdownEngine.kt`** — A regex-based tokenizer used exclusively for rendering styled text. This is what users actually see.

Both are compared against **mldoc**, Logseq's production OCaml parser, which uses the Angstrom combinator library and handles the full Logseq feature surface.

**The critical architectural problem**: `MarkdownParser` parses content into an AST, then reconstructs raw strings from that AST, and those strings are stored in the database and later re-parsed by `MarkdownEngine` for rendering. This double-parse pipeline means bugs in either layer can corrupt display or data. The `parsing/` layer is a skeleton — `parseInfix` returns a placeholder, `getPrecedence()` always returns 0, and block content is assembled as a single `TextNode` string before inline parsing. The `MarkdownEngine` regex layer is more complete for rendering but has its own gaps.

---

## Feature Coverage Table

| Feature | mldoc | SteleKit `parsing/` AST | SteleKit `MarkdownEngine` (render) | Priority | Notes |
|---------|-------|------------------------|------------------------------------|----------|-------|
| Bullet blocks (`- text`) | Full | Partial — `- ` only, not `* ` or `+ ` | Not applicable | High | Lexer `isBulletStart` accepts `-`, `*`, `+` but only `- ` (dash+space) creates a BULLET token; `*` and `+` fall through |
| Nested bullets (indent) | Full | Partial — level detection has tab/space rounding bug | Not applicable | High | `calculateLevel` uses `(spaces+1)/2` — 3 spaces = level 2 instead of expected level 1 |
| Ordered lists (`1.`) | Full | **Missing** | **Missing** | High | No lexer token, no parser rule |
| ATX headings (`# text`) | Full | **Missing** | **Missing** | High | `#` is tokenized as HASH but parsed as tag; no heading block type |
| Fenced code blocks (` ``` `) | Full (with language + options) | **Missing** | **Missing** | High | No block-level code fence detection |
| Indented code blocks (4-space) | Full (`Example` type) | **Missing** | **Missing** | Medium | |
| Blockquotes (`> text`) | Full — multi-line continuation | **Missing** | **Missing** | High | |
| Tables (`\| col \| col \|`) | Full — org + markdown, headers, col groups | **Missing** | **Missing** | Medium | |
| Horizontal rules (`---`) | Full | **Missing** | **Missing** | Low | |
| HTML blocks | Full (`Raw_Html` type) | **Missing** | **Missing** | Low | |
| Hiccup blocks | Full (`Hiccup` type) | **Missing** | **Missing** | Low | |
| Directives (`#+KEY: value`) | Full | **Missing** | **Missing** | Medium | mldoc parses as `Property_Drawer` when used with `#+` |
| Front matter (YAML/TOML) | Full (`markdown_front_matter.ml`) | **Missing** | **Missing** | Medium | |
| Page properties (`key:: value`) | Full — inline `key:: value` and `:PROPERTIES:` drawer | Partial — `BlockParser.tryParseProperty()` requires TEXT then two separate COLON tokens but the lexer only emits single COLON; multi-word keys with hyphens/dots fail | Partial — `PropertiesParser` handles both styles | High | Regex in `PropertiesParser`: `[\w\-_]+` — misses dots (e.g. `a.b.c::`) which mldoc supports |
| Property drawer (`:PROPERTIES:`) | Full | Partial — `PropertiesParser` handles it but `parsing/BlockParser` doesn't | Partial | Medium | |
| SCHEDULED / DEADLINE timestamps | Full (structured `Timestamp.t`) | Via `TimestampParser` regex (strips from content) | Not rendered specially | Medium | `TimestampParser` strips the value and stores as string; no rich representation |
| Task markers (`TODO`/`DONE`/`NOW`/`LATER`/`WAITING`/`CANCELLED`) | Full — `marker` field on `Heading` | **Missing** | **Missing** | High | mldoc supports: `TODO DOING WAITING WAIT DONE CANCELED CANCELLED STARTED IN-PROGRESS NOW LATER` |
| Priority (`[#A]`) | Full — `priority : char option` on `Heading` | **Missing** | **Missing** | Medium | |
| Bold (`**text**` / `__text__`) | Full — left-flanking delimiter rules, underscore word-boundary rules | Partial — works for `**`, not `__` (UNDERSCORE token run, but `__` not fully distinguished from `_`) | Partial — `boldPattern = (\*\*\|__)(.+?)\1` works for simple cases | Medium | |
| Italic (`*text*` / `_text_`) | Full — with correct word-boundary rules for `_` | Partial — single `*` works, `_word_` hits word-boundary issues | Partial — `italicPattern` regex doesn't enforce word-boundary for `_` | Medium | mldoc rejects `a_b_c` (middle of word); SteleKit would match it |
| Bold+italic (`***text***`) | Full — nested `Italic(Bold(...))` | **Missing** — `len >= 2` check in `parseEmphasis` treats `***` as bold; no nesting | Partial — `boldPattern` greedily matches `**`, `*` left over | Medium | |
| Strikethrough (`~~text~~`) | Full (`Strike_through`) | Working — `len >= 2` correctly gates `~~` | Working — `strikethroughPattern` | — | |
| Highlight (`==text==`) | Full (`Highlight`) | **Missing** — `=` not a lexer token | **Missing** | Medium | |
| Underline (`_text_` in Org mode) | Full (Org only) | Not applicable | Not applicable | Low | |
| Verbatim / Code (`~text~` in Org) | Full (Org: `~text~`; Markdown: `` `text` ``) | Single `~` correctly returns plain text (fixed bug); `` ` `` works | `` ` `` works; `~` not treated as code | — | |
| Inline code (`` `text` ``) | Full — takes priority over emphasis | Working | Working | — | |
| Wiki-links (`[[page]]`) | Full — page references, file links, nested links `[[page [[nested]]]]` | Partial — basic `[[page]]` works; alias syntax `[[page\|alias]]` missing; nested links `[[a [[b]]]]` missing | Partial — `wikiLinkPattern = \[\[([^\]]+)\]\]` — works for simple; fails on `[[a]]b]]` or nested | High | |
| Wiki-link with alias (`[[page\|alias]]`) | Full — label parsed as inline content | **Missing** | **Missing** | High | Users frequently write `[[page\|display text]]` |
| Wiki-link with embedded refs (`[[page [[nested]]]]`) | Full (`Nested_link` type) | **Missing** | **Missing** | Medium | |
| Block references (`((uuid))`) | Full (`Block_ref` url type) | Working — `BLOCK_REF_OPEN`/`CLOSE` tokens | Working — `blockRefPattern` | — | |
| Markdown links (`[text](url)`) | Full — with title, `<url with spaces>`, escaped brackets | Partial — `parseLink` returns `TextNode("[")` for plain `[` (non-wiki), never reaches URL parsing | Working in MarkdownEngine — `linkPattern` | High | `parsing/InlineParser` cannot parse `[text](url)` at all; only wiki-links work |
| Images (`![alt](url)`) | Full | **Missing** | Working — `imagePattern` | Medium | |
| Auto-links (bare `http://...`) | Full (`Complex` url) | **Missing** | Working — `urlPattern` | Low | |
| Footnote references (`[^1]`) | Full (`Footnote_Reference`) | **Missing** | **Missing** | Low | |
| Footnote definitions (`[^abc]: text`) | Full (`Footnote_Definition` block) | **Missing** | **Missing** | Low | |
| Hashtags (`#tag`) | Full — stops at `.`, `,`, `!`, `?`, `"`, `:`; supports `[[page]]` in tag path | Partial — stops only at whitespace; `#tag.` includes the dot | Partial — regex `#([^\s#.,!\[\]()]+)` — closer but misses `:` terminator | Medium | |
| Hashtag with page-ref (`#a.[[b c]].e`) | Full | **Missing** | **Missing** | Low | |
| Macros (`{{name args}}`) | Full — name + argument list, nested links in args | **Missing** | **Missing** | High | `{{embed [[page]]}}`, `{{query ...}}`, `{{renderer ...}}` all unrecognized |
| LaTeX inline (`$...$` / `\(...\)`) | Full (`Latex_Fragment`) | **Missing** | **Missing** | Medium | |
| LaTeX display (`$$...$$` / `\[...\]`) | Full (`Displayed_Math` block) | **Missing** | **Missing** | Medium | |
| LaTeX environment (`\begin{eq}`) | Full (`Latex_Environment`) | **Missing** | **Missing** | Low | |
| Subscript (`_{text}`) | Full (Org and Markdown `_{...}`) | **Missing** | **Missing** | Low | |
| Superscript (`^{text}`) | Full (`^{...}`) | **Missing** | **Missing** | Low | |
| Entities (`\Delta`) | Full (`Entity` type, Unicode lookup) | **Missing** | **Missing** | Low | |
| Email addresses | Full (`Email` type) | **Missing** | **Missing** | Low | |
| Export snippets | Full (`Export_Snippet`) | **Missing** | **Missing** | Low | |
| Hard line breaks (`\\` at EOL) | Full (`Hard_Break_Line`) | **Missing** — NEWLINE consumed as TextNode | **Missing** | Medium | |
| Soft line breaks | Full (`Break_Line`) | **Missing** | **Missing** | Medium | |
| Drawers (`:LOGBOOK:` etc.) | Full (`Drawer` type) | **Missing** | **Missing** | Medium | |
| Custom blocks (`#+begin_foo`) | Full (`Custom` type) | **Missing** | **Missing** | Medium | |
| Code block language + options | Full — `language` + `options` fields | **Missing** | **Missing** | High | |
| Org headings (`* Heading`) | Full | **Missing** | **Missing** | Low | SteleKit targets Markdown format |
| `#+TBLFM:` table formulas | Full | **Missing** | **Missing** | Low | |
| Backslash escapes in links | Full — `\]`, `\)` etc. | **Missing** | **Missing** | Medium | |
| Inline HTML | Full (`Inline_Html`) | **Missing** | **Missing** | Low | |
| Radio targets (`<<<text>>>`) | Full | **Missing** | **Missing** | Low | |

---

## Critical Bugs (Data Loss Risk)

These issues either silently drop content or corrupt it during parse-reconstruct cycles.

### Bug 1: `parseInfix` is a No-Op — All Infix Parsing Broken

**File**: `parsing/InlineParser.kt` lines 47–56

`parseInfix` always returns `left` unchanged. `getPrecedence()` always returns 0. This means the Pratt parser never binds anything as an infix expression — the `parse()` loop runs but only ever calls `parsePrefix`. Combined with the fact that `while (precedence < getPrecedence())` is always false (0 < 0 is false), the `parseExpression` call degenerates into a pure prefix-only scan.

**What goes wrong**: For any content where an inline element appears after another (e.g., `**bold** and _italic_`), the second element is only parsed because `parse()` loops over `parseExpression(0)` calls. This actually works accidentally for sequences, but any future infix operators (e.g. for nested emphasis) will silently do nothing.

**Example**: `**bold** plain _italic_` — currently produces `[Bold([Text("bold")]), Text(" plain "), Italic([Text("italic")])]` only by luck of the outer loop. Any actual infix grammar rule added later will be silently ignored.

---

### Bug 2: Markdown Links `[text](url)` Not Parsed by AST Layer

**File**: `parsing/InlineParser.kt` line 83

`parseLink` handles `[[...]]` (wiki-link) but when it sees a single `[` with non-`[` next token, it immediately returns `TextNode("[")`. The URL content `text](url)` is then parsed as plain text.

**Example**:
```
Input:  [Visit Google](https://google.com)
Output: TextNode("["), TextNode("Visit Google"), TextNode("](https://google.com)")
```

**Impact**: The `MarkdownParser.reconstructContent()` serializes this as the raw broken string `[Visit Google](https://google.com)` only because `TextNode` happens to store the raw text segments — but if any processing modified those nodes, the link would be destroyed. The `MarkdownEngine` regex layer does parse links correctly, but any AST-level reference extraction (`extractReferences`) silently misses all markdown links.

---

### Bug 3: `peekToken` Advances the Lexer Without Proper Isolation

**File**: `parsing/BlockParser.kt` lines 183–201

`peekToken(offset)` calls `lexer.saveState()` then calls `lexer.nextToken()` `offset` times, but the saved state is the lexer's current cursor position — which is already PAST `currentToken`. The method returns the token at `offset` positions ahead of the current lexer position, not offset positions from `currentToken`. This means `peekToken(1)` returns the token 2 ahead of `currentToken` (i.e. the token after next).

**What goes wrong**: `tryParseProperty()` calls `peekToken(1)` and `peekToken(2)` expecting to see `COLON` twice for `key::` detection. Because of the off-by-one, it peeks at the wrong tokens and either misses valid properties or falsely matches non-properties.

**Example**:
```
key:: value
```
Expected: `TEXT("key")`, `COLON`, `COLON`, `WS`, `TEXT("value")`
What `tryParseProperty` sees at peek offsets 1 and 2: the tokens AFTER the two colons.

---

### Bug 4: Property Keys with Hyphens, Dots, or Multi-Word Names Silently Dropped

**File**: `parsing/BlockParser.kt` line 138; `parser/PropertiesParser.kt` line 17

`BlockParser.tryParseProperty()` only handles `TEXT` tokens as keys. The Lexer's `isSpecial` treats `_` and `.` and `-` as part of TEXT (since they aren't in the special list), but a key like `a.b.c::` would be tokenized as `TEXT("a.b.c")`, which is fine. However, `PropertiesParser.propertyRegex = ^\s*([\w\-_]+)::` doesn't allow dots in keys, so `a.b.c:: def` would fail to match.

mldoc explicitly handles dotted keys (test case: `"a.b.c:: def"` → `Property_Drawer [("a.b.c", "def", [])]`).

**Example**:
```
date.format:: YYYY-MM-DD
```
SteleKit `PropertiesParser`: no match (dot not in `[\w\-_]`), line kept as content.
mldoc: parsed as property `("date.format", "YYYY-MM-DD")`.

---

### Bug 5: Heading (`#`) Parsed as Hashtag

**File**: `parsing/InlineParser.kt` line 154; `parsing/lexer/Lexer.kt` lines 52–57; `parsing/BlockParser.kt`

The `#` character is tokenized as a HASH run. In `BlockParser`, there is no detection of ATX headings (`# Title`, `## Title`). The `BlockParser` sees `# ` as `HASH + WS` and falls through to paragraph parsing. In `InlineParser`, `parseTag` will try to treat `#` followed by TEXT as a hashtag.

**Example**:
```
# My Journal Page
```
SteleKit: Parsed as a `ParagraphBlockNode` with content `TextNode("# My Journal Page")`, which renders as literal hash-prefixed text rather than a heading. The `MarkdownEngine` has no heading rendering either — it just shows `# My Journal Page` as a plain line.

---

### Bug 6: Unclosed Inline Code Consumes Until EOF

**File**: `parsing/InlineParser.kt` lines 143–151

`parseCode` loops until it finds `BACKTICK` or `EOF`. Unlike emphasis (which now backtracks via `saveState()`), an unclosed backtick silently consumes the rest of the line as "code content."

**Example**:
```
- Some text `with unclosed backtick and more words
- Next bullet should be independent
```
The second bullet's text is consumed into the code node of the first bullet, producing `CodeNode("with unclosed backtick and more words\n- Next bullet should be independent")`.

---

### Bug 7: Wiki-Link Alias `[[page|alias]]` Drops the Alias

**File**: `parsing/InlineParser.kt` lines 69–83

`parseLink` reads all tokens between `[[` and `]]` into a single StringBuilder. The `|` character is treated as text and is included in `WikiLinkNode.target`. The alias part is not split off.

**Example**:
```
See [[Logseq|the app]] for details.
```
SteleKit: `WikiLinkNode(target="Logseq|the app")` — page lookup will fail.
mldoc: `Link { url = Page_ref "Logseq"; label = [Plain "the app"] }` — renders "the app", links to "Logseq".

---

### Bug 8: Macro Syntax `{{...}}` Passed Through as Plain Text

**File**: `parsing/InlineParser.kt` — `{` is not a special character in the lexer.

`{{embed [[page]]}}`, `{{query ...}}`, `{{renderer ...}}` are all parsed as plain text. In Logseq, macros are executable — they trigger transclusion (embed), queries (query), and plugin renderers. Storing them as plain text means:
1. They render as literal `{{embed [[page]]}}` strings.
2. Any embedded page reference inside `{{embed [[page]]}}` is not extracted as a reference, so the graph's link structure is incomplete.

---

## Missing Features (Grouped by Priority)

### P0: Data Correctness (required to not lose user data)

- **Unclosed backtick backtracking** — add `saveState()`/`restoreState()` pattern to `parseCode` (same fix already applied to emphasis)
- **Markdown link parsing** — `[text](url)` must be handled in `InlineParser.parseLink`; currently always falls back to `TextNode("[")`
- **Property key regex** — extend `[\w\-_]` to `[\w\-_.]+` in `PropertiesParser` to match dotted keys

### P1: Core Logseq Constructs (required for basic Logseq compatibility)

- **Task markers** — `TODO`, `DONE`, `NOW`, `LATER`, `WAITING`, `CANCELLED` at the start of a heading/bullet content
- **ATX headings** — `#` through `######` must be recognized as block-level heading elements, not inline tags
- **Fenced code blocks** — ` ``` ` with optional language identifier and option list
- **Wiki-link alias** — `[[page|alias]]` must split on `|` inside `[[...]]`
- **Macros** — `{{name args}}` basic recognition; at minimum extract `[[page]]` references from within macro arguments

### P2: Feature Completeness

- **Blockquotes** — `>` at line start, multi-line continuation
- **Ordered lists** — `1.`, `2.` etc. with nesting
- **Task priority** — `[#A]`, `[#B]`, `[#C]` after task marker
- **Hashtag terminator precision** — stop at `,`, `;`, `.`, `!`, `?`, `"`, `:` (not just whitespace)
- **LaTeX** — `$inline$`, `$$display$$`, `\(...\)`, `\[...\]`
- **Hard line breaks** — `\\` at EOL or two trailing spaces
- **Drawers** — `:LOGBOOK:` and custom drawers
- **Nested wiki-links** — `[[page [[nested]]]]`

### P3: Extended Features

- **Custom blocks** — `#+begin_name` / `#+end_name`
- **Tables** — pipe-delimited tables
- **Front matter** — YAML between `---` delimiters
- **Footnotes** — `[^id]` references and `[^id]: definition`
- **Subscript / Superscript** — `_{text}` / `^{text}`
- **HTML blocks and inline HTML** — pass-through
- **Backslash escapes** — `\*`, `\[`, `\]`, `\(`, `\)` in inline contexts
- **Highlight** — `==text==`

---

## Architecture Recommendations

### ADR-001: Unify the Two Parser Layers

**Problem**: `parsing/` (AST) and `MarkdownEngine` (regex) are duplicate, inconsistent parsing systems. Every new feature must be added twice. The regex layer is more capable for rendering; the AST layer is more capable for structure.

**Options**:
- **A. Render from AST** — Extend `InlineParser` to produce all needed nodes, then have `MarkdownEngine` consume `List<InlineNode>` from the AST rather than re-parsing raw text.
- **B. Retire the AST layer for inline content** — Use only the regex tokenizer in `MarkdownEngine` for both rendering and reference extraction. The `parsing/` layer handles only block structure.
- **C. Status quo** — Keep both, add features twice.

**Recommendation**: Option A. The AST is the right representation; the regex tokenizer was built to fill a gap. Extending `InlineNode` to cover all cases (Macro, Heading, CodeFence, etc.) and feeding the AST into `MarkdownEngine`'s renderer eliminates the duplication. `MarkdownParser.reconstructContent()` (the lossy round-trip serialization) can then be removed.

---

### ADR-002: Fix Block-Level Structure First

**Problem**: `BlockParser` has no concept of headings, fenced code blocks, blockquotes, or ordered lists. The `peekToken` off-by-one makes property parsing unreliable.

**Recommendation**: Add a `BlockType` enum to the AST — `Heading(level)`, `CodeFence(language, options)`, `Blockquote`, `OrderedListItem(number)` — and add detection rules at the top of `parseBlock()`. The `peekToken` bug should be fixed by changing the implementation to save state, advance, record token, then restore (peek without side effects).

---

### ADR-003: Add Macro as a First-Class Node

**Problem**: Macros (`{{embed}}`, `{{query}}`, `{{renderer}}`) are Logseq's extension mechanism and appear in real user content. Treating them as plain text breaks transclusion, queries, and graph integrity.

**Recommendation**: Add `MacroNode(name: String, arguments: List<String>)` to `InlineNode`. The lexer should treat `{` as a special character to trigger `{{` detection. Reference extraction in `extractReferences` must recurse into macro arguments to find `[[page]]` links embedded in macros like `{{embed [[page]]}}`.

---

### ADR-004: Adopt Left-Flanking Delimiter Rules for Emphasis

**Problem**: The current `parseEmphasis` detects `*`/`_` by run-length only (len >= 2 for bold). mldoc implements CommonMark-compliant left-flanking delimiter rules: a `_` only starts italic if preceded by whitespace or punctuation (not a letter), preventing `hello_world_` from becoming italic.

**Recommendation**: Before constructing an emphasis node, check the character before the opening delimiter. If opening `_` is preceded by a word character (`[a-zA-Z0-9]`), treat it as plain text. This is a one-character lookahead into the source at `token.start - 1` before calling `parseEmphasis`.

---

### ADR-005: Backtracking Strategy for Code Blocks

**Problem**: `parseCode` (inline backtick) does not backtrack on unclosed delimiters, unlike `parseEmphasis` (which was fixed). Multi-line block-level code fences also need robust detection with a matching close fence.

**Recommendation**: Apply the same `saveState()`/`restoreState()` pattern from `parseEmphasis` to `parseCode`. For block-level fences, scan for the closing ` ``` ` line at `BlockParser` level before entering content parsing, so a missing close fence degrades to treating the opening ` ``` ` as plain text rather than consuming subsequent blocks.

---

## Implementation Roadmap

### Phase 1: Data-Loss Fixes (no regressions, just correctness)

1. Apply `saveState()`/`restoreState()` backtracking to `parseCode` (mirrors the emphasis fix)
2. Fix `peekToken` off-by-one in `BlockParser` — save state, call `nextToken` N times starting from scratch after `currentToken`
3. Extend `PropertiesParser.propertyRegex` to allow dots in property keys: `[\w\-_.]+`
4. Fix `parseLink` in `InlineParser` to handle `[text](url)` — detect `TEXT` + `R_BRACKET` + `L_PAREN` sequence after initial `L_BRACKET`

### Phase 2: Feature Completeness (Logseq parity)

5. Add task marker recognition (`TODO`/`DONE`/`NOW`/`LATER`/`WAITING`/`CANCELLED`) as `TaskMarkerNode` in `InlineNode` and handle in `InlineParser.parsePrefix` when `TEXT` token matches
6. Add `HeadingBlockNode(level: Int, ...)` to AST; detect `#`+WS at line start in `BlockParser`
7. Add `CodeFenceBlockNode(language, options, lines)` to AST; detect ` ``` ` at line start
8. Add wiki-link alias support — split `[[target|alias]]` on first `|` inside wiki-link parsing
9. Add `MacroNode` — detect `{{` in lexer as `MACRO_OPEN`, parse name and arguments
10. Add `BlockquoteBlockNode` — detect `>` at line start
11. Fix hashtag terminator to stop at `,;.!?":`

### Phase 3: Roundtrip Fidelity (AST drives rendering)

12. Extend `MarkdownEngine.tokenizeMarkdown` to consume `List<InlineNode>` from AST (or keep regex tokenizer but route it through the AST)
13. Implement `reconstructContent` for all new node types (Macro, Heading, CodeFence, Blockquote) in `MarkdownParser`
14. Add table parsing
15. Add LaTeX fragment parsing
16. Add front matter extraction
17. Add footnote support
18. Implement `Hard_Break_Line` / `Break_Line` distinction
