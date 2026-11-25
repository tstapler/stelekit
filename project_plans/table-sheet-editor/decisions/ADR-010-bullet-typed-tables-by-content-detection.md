# ADR-010: Bullet-typed tables are detected by content in the renderer, behind the flags

**Status**: Accepted (PR-B)
**Date**: 2026-11-24

## Context
`BlockType.Table` is produced only for unindented pipe rows. Logseq's own format (`- | a | b |` with indented rows) parses as `BlockType.Bullet` with identical de-indented content (repair-log P5-1, `TableBlockContentShapeTest`). `BlockItem` renders `TableBlock` only for `Table`, so most real Logseq tables would never get inline editing or the sheet.

## Decision
`BlockItem` treats a `Bullet` block as a table when `inlineEditEnabled || sheetEnabled` and `isGridTableContent(block.content)` (the content parses with a header and a delimiter row via `TableParser`). The check short-circuits on the flags, so with both off it is never evaluated and the dispatch is unchanged. The stored `blockType` is not changed.

## Consequences
- Write-back keeps the block's existing content shape: the engine serializes with the parsed table's own style, so an indented Logseq table stays indented. Raw editing is unchanged and still opens on tap fallback or "Edit as markdown".
- A Bullet whose content merely contains a table (text before or after it) also qualifies; prefix and suffix lines are kept verbatim by the parser.
- Cost: one parse per Bullet render while a flag is on, bounded by the `contains('|')` pre-check.
- Rejected: changing the parser's block typing (touches every graph load and the DB) or persisting a new type.
