# ADR-001: Store Task Status as Content Prefix Keyword

## Status
Accepted

## Context
SteleKit blocks store their content as raw Markdown strings in `Block.content`. Logseq encodes task status as a prefix keyword on the block's first line: `TODO Buy groceries`, `DONE Submit report`. We need to decide where task status lives in the data model.

**Options considered:**
1. **Content prefix keyword** (Logseq-compatible): `TODO content…` stored verbatim in `Block.content`
2. **Block property**: `status:: todo` stored in `Block.properties`
3. **Dedicated DB column**: add `task_status TEXT` to the `blocks` table

## Decision
Store task status as a content prefix keyword (`TODO`, `DOING`, `DONE`, `CANCELED`, `LATER`, `NOW`) verbatim in `Block.content`, consistent with Logseq's format.

## Rationale
- **File-format compatibility**: existing Logseq graphs import/export correctly with zero transformation.
- **Parser simplicity**: `TimestampParser` already parses structured prefixes from content; the same pattern applies.
- **No schema migration required**: `Block.content` already holds the full text; no DDL change is needed.
- **Simpler GraphWriter**: saving a block with a changed status is identical to saving any content change — no special-case persistence path.

## Consequences
- A `TaskStatusParser` (new, thin) must extract the keyword prefix on every render.
- The UI derives task status from content at read time rather than from a column.
- Querying all TODO blocks across a graph requires a content-prefix scan or FTS, which is acceptable for the agenda view's single-date query.
- Status cycling mutates `Block.content` (prefix swap), which flows through the existing `UpdateBlockContentCommand` → `BlockStateManager` → `GraphWriter` pipeline with no new infrastructure.

## Patterns Applied
- **Value Object**: `TaskStatus` enum is a pure value object derived from the content string.
- **Existing Command pattern reuse**: `UpdateBlockContentCommand` handles the write without modification.
