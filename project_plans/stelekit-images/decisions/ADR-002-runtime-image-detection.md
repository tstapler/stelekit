# ADR-002: Runtime Image-Only Block Detection (Not a New BlockType)

**Status**: Accepted
**Date**: 2026-05-15

---

## Context

`BlockItem.kt` dispatches rendering based on block type. Currently image-only blocks (blocks whose sole content is `![alt](path)`) fall into the `PARAGRAPH` / `else` branch and render as raw link text because `ImageBlock` is never invoked.

Two strategies exist for fixing the dispatch:

1. **Add `BlockTypes.IMAGE`** — introduce a new enum variant, detect image-only blocks at parse or load time, persist the classification to the database, and update `BlockItem` dispatch.
2. **Runtime AST inspection** — in `BlockItem`, after the type switch, parse the block content's inline AST and check whether the result is exactly one `ImageNode` with no other inline nodes. If so, dispatch `ImageBlock`; otherwise continue to `PARAGRAPH` rendering.

The key consideration is the cost of each approach:

**`BlockTypes.IMAGE` (rejected)**
- Requires a new column or enum value in `SteleDatabase.sq`.
- Requires a database migration (`MigrationRunner` + `UuidMigration` pattern) with a one-time backfill pass over all existing blocks.
- Any block created by an external editor (Logseq, Obsidian, a text editor) and then loaded into SteleKit must be re-classified. The existing `GraphLoader` parse pipeline would need to emit `IMAGE` type for image-only blocks — adding logic there too.
- Schema migration risk: a bug in the migration corrupts the `block_type` column for non-image blocks.

**Runtime AST inspection (chosen)**
- `InlineParser` is already invoked during block rendering to produce the inline node list used by `RichTextBlock`. Reusing that result or re-running the parse to check for a single `ImageNode` adds no new infrastructure.
- `InlineParser` is fast (simple regex/state-machine over a short string). Even for a page with 200 blocks, the overhead is in microseconds per frame.
- No database schema change. No migration. No risk to existing data.
- Compatible with external edits: a block typed as `PARAGRAPH` in the DB but containing `![](path)` renders correctly because the check is at render time.

---

## Decision

Detect image-only blocks at render time inside `BlockItem`. After the existing type switch, if the block type is `PARAGRAPH` (or the generic `else` branch), parse the block's inline content and check:

```kotlin
val inlineNodes = InlineParser.parse(block.content)
if (inlineNodes.size == 1 && inlineNodes[0] is ImageNode) {
    ImageBlock(block = block, ...)
    return
}
// fall through to RichTextBlock / other rendering
```

Do **not** add `BlockTypes.IMAGE` to the enum. Do **not** add a database column or migration for this classification.

---

## Consequences

**Positive**
- Zero database schema changes — no migration needed, no risk to existing block data.
- Correct for all blocks regardless of how they were created (SteleKit editor, Logseq, manual text editor). The classification is always derived from the current content, never from a stale persisted type.
- Simpler code path: the detection logic is local to `BlockItem` and easy to read and test.
- Future-proof: if Logseq or another tool changes how it writes image blocks, the detection adapts automatically.

**Negative / Trade-offs**
- Slight CPU cost at render time: one `InlineParser.parse()` call per block per recomposition for blocks in the `PARAGRAPH` branch. In practice, recomposition is gated by `StateFlow` diffs, and `InlineParser` is fast enough that this is not measurable in profiling.
- The image-vs-paragraph distinction is not queryable from the database (e.g., cannot `SELECT` all image blocks). This is acceptable — no current feature requires that query.
- If `InlineParser` behavior diverges from `GraphLoader`'s inline parser (they must stay in sync), an image block might be misidentified. Mitigation: both must use the same `InlineParser` implementation (already the case).

**Risks mitigated**
- No migration bug can corrupt block type data for non-image blocks.
- No divergence between runtime rendering and persisted schema state.
