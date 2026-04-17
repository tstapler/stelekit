# ADR-001: Block Type Preservation Strategy

**Status**: Accepted
**Date**: 2026-04-13
**Feature**: Render All Markdown Blocks

---

## Context

`MarkdownParser.convertBlock()` in `parser/MarkdownParser.kt` receives a typed `BlockNode` (one of nine subtypes from `parsing/ast/BlockNodes.kt`) and converts it to a `ParsedBlock`. During this conversion, `reconstructContent()` serialises the block's AST back to a string. The `ParsedBlock` and downstream `Block` domain model only carry `content: String` and `level: Int` — the block subtype (heading, code fence, table, etc.) is not preserved.

At render time, `BlockItem` calls `BlockViewer` with `block.content`. Because the type was discarded, `BlockViewer` passes all content to the inline parser (`parseMarkdownWithStyling`) which handles only inline-level nodes. Block-level constructs silently degrade to raw text.

### Options considered

**Option A — Re-detect block type at render time from `block.content`**

Parse the raw content string inside `BlockViewer` to detect the block type on every render. A heading would be detected by a `^#{1,6} ` prefix, a code fence by ` ``` ` at start and end, etc.

Rejected because:
- Creates a second, divergent parser that will drift from `LogseqParser` over time
- Multi-line blocks (code fences, tables) require stateful parsing that is error-prone when operating on a flat stored string
- Performance: regex/string scanning runs on every recompose for every block
- Does not address the root cause: the information is available at parse time and should be preserved

**Option B — Preserve block type in model (chosen)**

Stop discarding the subtype at `convertBlock()`. Carry the type through `ParsedBlock` → `Block` → SQLDelight → UI. One DB migration required.

---

## Decision

Use **Option B**. Add a `sealed class BlockType` to `ParsedModels.kt` and a `blockType: BlockType` field to `ParsedBlock`. Add a `blockType: String` string discriminator (not sealed class) to the `Block` domain model and a `block_type TEXT NOT NULL DEFAULT 'bullet'` column to the `blocks` table.

### Why sealed class in `ParsedBlock` but string in `Block`?

`ParsedBlock` is an in-memory intermediate representation never persisted directly. Using a sealed class here gives the mapping code type safety and allows `Heading(level: Int)` and `CodeFence(language: String)` to carry parameters.

`Block` is the domain object that maps directly to a SQLDelight row. Using a plain string discriminator avoids the need for a custom `ColumnAdapter` and keeps the DB column human-readable. A `BlockTypeMapper` utility converts between the two representations.

### Discriminator string values

| Sealed class | Stored string |
|---|---|
| `BlockType.Bullet` | `"bullet"` |
| `BlockType.Paragraph` | `"paragraph"` |
| `BlockType.Heading(level)` | `"heading:1"` through `"heading:6"` |
| `BlockType.CodeFence(lang)` | `"code_fence:kotlin"` (lang may be empty → `"code_fence:"`) |
| `BlockType.Blockquote` | `"blockquote"` |
| `BlockType.OrderedListItem(n)` | `"ordered_list_item:1"` (number may change on edit) |
| `BlockType.ThematicBreak` | `"thematic_break"` |
| `BlockType.Table` | `"table"` |
| `BlockType.RawHtml` | `"raw_html"` |

The colon-separated format allows `split(":", limit = 2)` to extract both the type discriminator and the parameter in a single pass.

### SQLDelight migration

Create `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/migrations/2.sqm`:

```sql
ALTER TABLE blocks ADD COLUMN block_type TEXT NOT NULL DEFAULT 'bullet';
```

The `DEFAULT 'bullet'` ensures backward compatibility: all existing rows become `"bullet"` and render via the existing `BlockViewer` path until the page is reloaded from disk, at which point `GraphLoader` re-parses and writes correct `block_type` values.

Update `insertBlock` named query to add the 13th positional parameter `block_type`.

The FTS5 virtual table (`blocks_fts`) indexes only the `content` column and its triggers do not need modification.

---

## Consequences

**Positive**:
- Single source of truth: block type is determined once, at parse time, by `LogseqParser`
- No second parser; no drift risk
- Efficient rendering: composable dispatch is a sealed-class `when` — O(1)
- DB column is human-readable and debuggable

**Negative**:
- One SQLDelight migration required; all existing DB rows will have `block_type = "bullet"` until pages are reloaded
- `insertBlock` query gains a 13th parameter — all call sites must be updated
- `OrderedListItem` number stored in `block_type` string means a re-parse is needed if the list is reordered; acceptable because the outliner re-parses on every disk save anyway

**Neutral**:
- `Block.init` adds a `validateBlockType()` call; invalid strings will throw `IllegalArgumentException` at construction time, failing fast rather than silently rendering wrong
