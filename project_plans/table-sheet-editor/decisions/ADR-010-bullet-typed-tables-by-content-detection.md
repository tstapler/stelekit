# ADR-010: Bullet-typed tables are detected by content in the renderer, behind the flags

**Status**: Accepted (PR-B)
**Date**: 2026-11-24

## Context
`BlockType.Table` is produced only for unindented pipe rows. Logseq's own format (`- | a | b |` with indented rows) parses as `BlockType.Bullet` with identical de-indented content (repair-log P5-1, `TableBlockContentShapeTest`). `BlockItem` renders `TableBlock` only for `Table`, so most real Logseq tables would never get inline editing or the sheet.

## Decision
`BlockItem` treats a `Bullet` block as a table when `inlineEditEnabled || sheetEnabled` and `isGridTableContent(block.content)` (the content parses with a header and a delimiter row via `TableParser`). The check short-circuits on the flags, so with both off it is never evaluated and the dispatch is unchanged. The stored `blockType` is not changed.

## Consequences
- Write-back keeps the block's existing content shape: the engine serializes with the parsed table's own style, so an indented Logseq table stays indented. Raw editing is unchanged and still opens on tap fallback or "Edit as markdown".
- Detection is conservative (review fix): the table must be the block's whole content. A Bullet with prose before or after the table, or any fenced code (```` ``` ```` or `~~~`) anywhere in it, keeps its bullet rendering, so a table inside a code fence or an explanatory paragraph is never hijacked into the grid. Such a block is still editable as raw markdown; an explicit `BlockType.Table` block with surrounding prose keeps rendering its prefix and suffix lines.
- The block is not re-typed on write-back: `BlockStateManager.applyContentChange` copies the block with new content and version only, and the DB write is content-only, so a committed Bullet stays `Bullet` (verified by reading `applyContentChange`; the write-back shape is covered by `TableBlockFlagOffEquivalenceTest`). After a reload the parser types it the same way again, so detection keeps applying.
- The dispatch type is memoized per `(content, blockType, flags)`, so the parse runs once per content change, not per recomposition.
- Tap on the 1 dp border or between cells does nothing with the flags on (every cell fills the grid, so there is no padding area); with the flags off a tap anywhere opened the raw editor. Accepted: the cell under the finger always edits in place, and "Edit as markdown" is one tap away.
- Cost: one parse per Bullet render while a flag is on, bounded by the `contains('|')` pre-check.
- Rejected: changing the parser's block typing (touches every graph load and the DB) or persisting a new type.
