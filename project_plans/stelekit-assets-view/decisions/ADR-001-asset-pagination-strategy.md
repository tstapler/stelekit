# ADR-001: Asset Pagination Strategy — Keyset over OFFSET

**Status**: Accepted

---

## Context

The `AssetBrowserScreen` (REQ-5) requires infinite scroll over the `asset_index` table.
The existing `selectAssets`, `selectAssetsByMediaType`, and `searchAssets` queries all use
`ORDER BY imported_at_ms DESC LIMIT :limit OFFSET :offset`.

Two categories of drift make OFFSET unsafe here:

**Insert drift.** `AssetIndexService.registerAsset()` runs in the background during graph
load. A newly registered asset lands at `imported_at_ms = now`, which is the head of the
`DESC`-ordered result set. When the user scrolls to page 2, OFFSET 50 now points one row
further than the last item they saw — the row that was at position 50 on page 1 now appears
at position 51, and page 2 silently skips it.

**Invalidation churn.** SQLDelight invalidates every active collector of `asset_index` on
each write to that table. Background ML pipeline calls (`markAssetMlProcessed`,
`updateAssetAutoLabels`, `updatePageUuids`) each trigger a re-emission of the standing
`getAssets()` Flow in `AssetBrowserViewModel`. With OFFSET pagination, each re-emission
forces the ViewModel to re-issue all loaded pages from offset 0 or accept stale offsets —
either causes visible list reloads or duplicate/missing items during active browsing.

Client-side full-load (fetch all assets at once) is prohibited by the CLAUDE.md
bounded-reads rule: no unbounded repository reads are permitted.

---

## Decision

Use **keyset pagination** for the asset browser's infinite scroll:

- Replace `OFFSET :offset` with `WHERE imported_at_ms < :lastSeenMs` in the paginated
  `SELECT` queries.
- The first page passes `lastSeenMs = null` (no WHERE clause on the cursor column), loading
  the newest 50 items.
- Each subsequent page passes the `imported_at_ms` value of the last item received.
- Sort-by-name and sort-by-size queries require a **tie-break secondary sort on `uuid`** to
  produce a stable cursor when multiple assets share the same `file_path` or `size_bytes`:
  `ORDER BY file_path COLLATE NOCASE ASC, uuid ASC LIMIT :limit WHERE (file_path, uuid) > (:lastSeenName, :lastSeenUuid)`.
  The date-default sort does not need a tie-break because `imported_at_ms` is effectively
  unique in practice (millisecond-resolution ingestion timestamp).

Repository interface change: all paginated `AssetRepository` methods replace `offset: Int`
with `lastSeenMs: Long?` (null signals the first page). The `countAssets()` query is
unaffected and continues to drive `hasMore` calculation.

---

## Alternatives Considered

**OFFSET pagination (simpler, fragile).**
Already present in the codebase. Requires no query changes and is the path of least
resistance. Rejected because insert drift and invalidation churn both cause incorrect list
behavior (duplicated or skipped items) during the background ML pipeline run that follows
graph load — a common user scenario immediately after opening the Assets screen.

**Client-side full-load (load all assets once, sort/filter in memory).**
Eliminates all pagination drift. Prohibited by the CLAUDE.md bounded-reads rule: no
unbounded repository reads. On an 8 000+-page graph with many attached assets this would
also cause GC thrash and potential OOM on Android, matching the failure mode documented for
`getAllPages()` in the main README.

**Snapshot + tombstone (snapshot on screen entry, merge deletions client-side).**
Takes a bounded snapshot on entry and avoids re-querying on ML writes. More complex to
implement: requires a separate tombstone set, merging logic in the ViewModel, and a second
`countAssets()` subscription to detect when `hasMore` changes. Provides no advantage over
keyset when inserts at the head are the dominant drift source. Deferred.

---

## Consequences

- `AssetRepository` interface and `SqlDelightAssetRepository` replace `offset: Int` with
  `lastSeenMs: Long?` on all paginated read methods. Callers (`AssetBrowserViewModel`) are
  updated accordingly.
- Sort-by-name and sort-by-size queries need a two-column cursor `(sortKey, uuid)` instead
  of a single `imported_at_ms` cursor. `AssetBrowserUiState` tracks the last-seen cursor
  pair for these sort modes.
- Switching back to OFFSET pagination requires a query change and ViewModel update; this is
  intentional — the fragility is now architectural rather than accidental.
- The `countAssets()` query and `hasMore = fetchedCount < totalCount` logic remain unchanged;
  keyset and OFFSET use the same count-based `hasMore` sentinel.
- SQLDelight still invalidates collectors on every ML write. With keyset pagination the
  ViewModel may choose to suppress re-fetching previously loaded pages on invalidation
  (compare new first-page result against current head; only re-fetch if the head changed).
  This optimization is not required for correctness but reduces UI churn.
