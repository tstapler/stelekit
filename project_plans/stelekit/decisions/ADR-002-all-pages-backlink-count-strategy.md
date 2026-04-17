# ADR-002: Backlink Count via Dedicated SQL Query

**Status**: Accepted
**Date**: 2026-04-16
**Feature**: All Pages View

## Context

The All Pages table needs a "Backlinks" column showing how many blocks link to each page (`[[Page Name]]` wikilinks). `BlockRepository.getLinkedReferences(pageName)` returns the full list of matching blocks — calling it once per page in a 5000-page graph would issue 5000 sequential queries and spend ~250 ms just on IO round-trips before any sort/filter.

## Decision

Add a new SQLDelight query `countLinkedReferences(pageName: String): Flow<Long>` and a batch variant `countLinkedReferencesForPages(pageNames: List<String>): Flow<Map<String, Long>>` to `SteleDatabase.sq`.

The batch query uses a LIKE pattern union approach rather than a full-text scan:

```sql
-- countBacklinksForPage
SELECT COUNT(*) FROM blocks WHERE content LIKE '%[[' || :pageName || ']]%';
```

For the initial load of the All Pages screen, compute backlink counts lazily: load the first visible page of rows with `backlinkCount = 0`, then compute counts for visible rows in background coroutines using `Dispatchers.IO`, updating a `MutableStateFlow<Map<String, Int>>` that the UI observes.

## Rationale

- **No schema change required**: the `blocks` table with its LIKE-searchable `content` column already supports the pattern.
- **Incremental delivery**: the table renders immediately with 0 counts; counts fill in as background jobs complete, matching the progressive-loading pattern already in use.
- **FTS5 alternative considered**: `blocks_fts` MATCH `[[PageName]]` would be faster for large graphs but requires escaping page names and has tokenizer quirks with brackets. Deferred to a follow-up optimization.

## Consequences

- Positive: no schema migration; no new table; UI shows data immediately.
- Negative: LIKE scan on large block tables is O(n); for graphs with 50 000+ blocks, counts may take 1-2 s. Acceptable given lazy/background loading.
- Follow-up: if performance degrades, add an indexed `backlink_count` denormalized column to `pages` refreshed by trigger, or switch to FTS5 MATCH.
