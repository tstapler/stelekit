# ADR-011: Wikilink Reference Index for O(1) Backlink Counting

**Status**: Accepted
**Date**: 2026-06-18
**Feature**: Performance — Backlink Counting
**Supersedes**: ADR-002-all-pages-backlink-count-strategy.md

## Context

ADR-002 chose a schema-free approach to backlink counting: a LIKE scan (`SELECT COUNT(*) FROM blocks WHERE content LIKE '%[[pageName]]%'`) issued on demand. At the time, no schema change was the correct trade-off — the graph was small and the lazily-computed approach avoided a migration.

At graph scale (8 000+ pages, 40 000+ blocks) this decision became a performance liability in three places:

1. **Write path — `recomputeBacklinkCountForPage`**: called on every block content edit to keep `pages.backlink_count` current. An O(total_blocks) full-table scan on every keystroke (after the 300 ms debounce) added ~684 ms of write latency on Android where a single SQLite connection serialises reads and writes.

2. **Backlinks panel — `countLinkedReferences`**: two full-table LIKE scans (`%[[pageName%` and `%#pageName%`) executed each time the backlinks panel opened. On a 40 000-block graph this consistently exceeded 300 ms.

3. **Backlinks list — `getLinkedReferences`**: same two LIKE scans to retrieve the full list of referencing blocks, loading every candidate block into memory before regex filtering.

A secondary problem surfaced during analysis: `countLinkedReferences` counted both `[[pageName]]` wikilinks and `#pageName` hashtag references, but `pages.backlink_count` only counted wikilinks (its LIKE pattern was `%[[pageName]]%`). This meant the two backlink signals reported different numbers for the same page — a silent semantic inconsistency.

## Decision

Add a `wikilink_references` table as a maintained index of all `[[pageName]]` wikilinks in block content:

```sql
CREATE TABLE IF NOT EXISTS wikilink_references (
    block_uuid TEXT NOT NULL,
    page_name  TEXT NOT NULL COLLATE NOCASE,
    PRIMARY KEY (block_uuid, page_name),
    FOREIGN KEY (block_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_wikilink_refs_page_name
    ON wikilink_references(page_name);
```

Design choices:

- **PRIMARY KEY `(block_uuid, page_name)`** with `COLLATE NOCASE` — prevents duplicate entries; matches the case-insensitive uniqueness of `pages.name`; a block referencing `[[Page]]` and `[[page]]` correctly produces one row.
- **`ON DELETE CASCADE`** — when a block is deleted, its wikilink rows are removed automatically. Removes the class of orphan-ref bugs entirely; no manual cleanup needed in delete paths.
- **Index on `page_name`** — enables the `recomputeBacklinkCountFromIndex` query to run in O(1) via index rather than O(blocks) via scan.

### Write path changes

All block mutation paths maintain the index incrementally:

| Path | Change |
|------|--------|
| `saveBlocks` (bulk import) | Inserts refs inside chunk transaction; backlink counts are NOT updated (counts start at 0, correct on first user edit — matches prior behaviour) |
| `saveBlocksDiff` toInsert | Same as saveBlocks |
| `saveBlocksDiff` toUpdate | Calls `replaceWikilinkRefs` after chunk loop; recomputes counts for changed page names |
| `saveBlock` (single insert) | Calls `addWikilinkRefs`; recomputes counts |
| `updateBlockContentOnly` | Reads old page names from index (O(1)), calls `replaceWikilinkRefs`, recomputes only changed pages |
| `splitBlock` | Calls `replaceWikilinkRefs` for both the trimmed original and the new block |
| `mergeBlocks` | Calls `replaceWikilinkRefs` for block A with merged content; block B's rows removed by CASCADE |
| `deleteBlock` / `deleteBulk` | Collects page names from index before deletion; CASCADE removes rows; recomputes counts after |
| `deleteBlocksForPage` / `deleteBlocksForPages` | Same pattern |
| `updateBlockContentsForRename` | `UPDATE OR IGNORE … SET page_name = newName WHERE page_name = oldName`; then `DELETE WHERE page_name = oldName` to clean up rows that were skipped due to a block already referencing both the old and new page name |

### Query replacements

**Backlink count maintenance** (hot write path):
```sql
-- Replaces: SELECT COUNT(*) FROM blocks WHERE content LIKE '%[[' || pages.name || ']]%'
recomputeBacklinkCountFromIndex:
UPDATE pages
SET backlink_count = (
    SELECT COUNT(*) FROM wikilink_references WHERE page_name = pages.name
)
WHERE pages.name = ?;
```

**Backlinks panel count** (`countLinkedReferences`):
Replaced with `COUNT(DISTINCT block_uuid) FROM wikilink_references WHERE page_name = ?` — O(1) index lookup, scoped to wikilinks only.

**Backlinks list** (`getLinkedReferences`):
Replaced with a `wikilink_references`-backed UUID lookup followed by batch block fetch — O(backlinks) rather than O(blocks).

### Migration and backfill

A new migration `wikilink_references_table` creates the DDL. A follow-on migration `wikilink_references_backfill` approximates the initial population via a SQL cross-join:

```sql
INSERT OR IGNORE INTO wikilink_references (block_uuid, page_name)
SELECT b.uuid, p.name
FROM blocks b
JOIN pages p ON b.content LIKE '%[[' || p.name || ']]%'
WHERE b.content LIKE '%[[%';
```

This handles the common case. Edge cases (aliased links `[[Page|alias]]`, complex nesting) are corrected progressively as blocks are re-saved through the Kotlin parser.

## Rationale

- **O(1) counts** via index lookup instead of O(total_blocks) LIKE scan removes write latency from the critical edit path — the dominant contributor to the 684 ms Android write latency.
- **ON DELETE CASCADE** eliminates the orphan-row failure mode. Prior code had to manually track wikilinks in block content at deletion time; a missed extraction left stale counts permanently.
- **Semantic consistency**: scoping both `backlink_count` and `countLinkedReferences` to wikilinks-only makes the two signals agree. Hashtag references (`#pageName`) are a separate feature with separate UI treatment; conflating them into the backlink count was the source of the inconsistency, not the correct behaviour.
- **Enforcement**: the `@DirectSqlWrite` annotation gate on `RestrictedDatabaseQueries` ensures all index writes go through reviewed stubs. `MigrationRunnerSchemaSyncTest` enforces the table appears in `MigrationRunner.all`. `SqlDelightBacklinkRepositoryTest` verifies index correctness for COLLATE NOCASE matching and aliased wikilinks against a real SQLite connection.

## Consequences

**Positive**

- Block edit write latency reduced from O(blocks) to O(1) for backlink count maintenance.
- Backlinks panel opens instantly at any graph size.
- `getLinkedReferences` memory footprint reduced from O(blocks) to O(backlinks).
- Orphan wikilink rows are structurally impossible (CASCADE).
- `backlink_count` and `countLinkedReferences` are now semantically consistent.

**Negative / Trade-offs**

- All block write paths must maintain the index — a new invariant. Enforced by tests, not by the type system. Any new block mutation path added in future must call `replaceWikilinkRefs` or `addWikilinkRefs`.
- `#pageName` hashtag references no longer appear in the backlinks panel count. This is an intentional semantic change; hashtag backlinks require a separate `hashtag_references` index if the product adds that feature.
- The SQL cross-join backfill migration is approximate. On large graphs it may take several seconds on first startup after upgrade. Exact consistency is reached progressively as pages are re-loaded.
- The index adds ~1 row per wikilink per block to the database. On a graph where the average block contains 1.5 wikilinks and there are 40 000 blocks, this is ~60 000 additional rows — acceptable storage overhead.
