# Research Plan: Render All Markdown Blocks

**Date**: 2026-04-13
**Requirements source**: `project_plans/render-all-markdown/requirements.md`

## Codebase Context (pre-research audit)

### Parser block types (BlockNodes.kt)
All of these are produced by `LogseqParser` but **none have dedicated Compose renderers**:

| Block Node | Status |
|---|---|
| `BulletBlockNode` | Partial — level/indentation handled, content rendered as inline text |
| `ParagraphBlockNode` | Content rendered as inline text only |
| `HeadingBlockNode` | **NO renderer** — `level` field lost in `ParsedBlock` conversion |
| `CodeFenceBlockNode` | **NO renderer** — serialized back to ``` string |
| `BlockquoteBlockNode` | **NO renderer** |
| `OrderedListItemBlockNode` | **NO renderer** — number/ordering lost |
| `ThematicBreakBlockNode` | **NO renderer** |
| `TableBlockNode` | **NO renderer** — headers/rows/alignments lost |
| `RawHtmlBlockNode` | **NO renderer** |

### Inline nodes (InlineNodes.kt)
Most inline nodes are rendered via `MarkdownEngine.kt` `parseMarkdownWithStyling()`:

| Inline Node | Status |
|---|---|
| TextNode, Bold, Italic, Strike, Highlight, Code | ✓ Rendered |
| WikiLink, BlockRef, Tag, UrlLink, MdLink | ✓ Rendered (with click handlers) |
| TaskMarker, MacroNode, PriorityNode | ✓ Rendered (styled text) |
| HardBreak, SoftBreak | ✓ Rendered |
| `ImageNode` | ✗ Rendered as underlined text link only — no actual image |
| `LatexInlineNode` | ✗ Rendered as monospace text — no math rendering |
| SubscriptNode, SuperscriptNode | ✗ No sub/superscript styling |

### Root architectural problem
`MarkdownParser.convertBlock()` serializes `BlockNode` subtypes back to a content string via
`reconstructContent()`. The `ParsedBlock` and `Block` models only carry `content: String` and 
`level: Int` — **block type is completely lost**. At render time, `BlockItem` passes 
`block.content` directly to `BlockViewer` which runs `InlineParser` (inline-only). 
Block-level dispatch never happens.

**Two possible fix strategies:**
1. **Re-detect at render time**: Add block-type detection to `BlockItem` by parsing the content 
   string prefix/structure (e.g., `#` → heading, ```` ``` ```` → code fence, `|` rows → table).
2. **Preserve in model**: Add a `blockType` (sealed class or enum + extra fields) to 
   `ParsedBlock` and `Block` so the type survives the parser → DB → render pipeline.

## Research Subtopics

### 1. Stack
**Goal**: Identify KMP-compatible libraries for the hard rendering problems (math, syntax highlighting, images).
**Search strategy**:
- KMP + Compose math rendering (KaTeX alternative, multiplatform LaTeX)
- Compose Multiplatform syntax highlighting library
- KMP image loading library (Coil 3, Kamel)
- Search cap: 5 searches

### 2. Features
**Goal**: What block types does Logseq actually support? How do other Compose markdown renderers handle them?
**Search strategy**:
- Logseq markdown format spec / documentation
- `compose-richtext` or `compose-markdown` library capabilities
- Existing KMP markdown viewer implementations
- Search cap: 4 searches

### 3. Architecture
**Goal**: How should block-type dispatch be structured? Should type survive the model or be re-detected?
**Search strategy**:
- Compose sealed class dispatch patterns for renderers
- Whether to store block type in DB model vs. re-detect from content string
- How Compose handles heterogeneous list items (LazyColumn with different item types)
- Search cap: 4 searches

### 4. Pitfalls
**Goal**: Known failure modes for each block type in KMP/Compose.
**Search strategy**:
- Math rendering on iOS + Web in KMP
- Table overflow / horizontal scroll in Compose LazyColumn
- Fenced code blocks in Compose (monospace font + scroll)
- Image loading async lifecycle in Compose
- Search cap: 5 searches
