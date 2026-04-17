# ADR-002: Recent Pages Persistence Strategy

**Status**: Accepted  
**Date**: 2026-04-16  
**Feature**: Recent Pages  

---

## Context

The current `StelekitViewModel` already tracks recently visited pages using a comma-delimited UUID string stored in `PlatformSettings` under the key `"recent_pages"`. This works for a single graph, but SteleKit supports multiple graphs via `GraphManager`. If a user switches graphs, the same `recent_pages` key will contain UUIDs from the previous graph's pages, which will silently resolve to `null` after the `cachedAllPages` lookup, producing an empty recents list.

Two persistence options exist:

1. **`PlatformSettings` with per-graph keys** — store the list as `"recent_pages_<graphPath>"` (or `"recent_pages_<graphId>"`). Zero schema changes, survives DB clears.
2. **SQLDelight table `recent_pages`** — add a new table to `SteleDatabase.sq` with `(page_uuid, visited_at)` columns. Survives graph migrations, supports richer queries (e.g. visit count, last-seen).

## Decision

Use **`PlatformSettings` with per-graph key namespacing** (`"recent_pages_<graphPath>"`).

## Rationale

- The existing `addToRecent()` / `recentPageUuids` mechanism already works correctly within a single graph; the only defect is the shared key.
- Adding a SQLDelight table requires a new `migration_changelog` entry, FTS triggers, and `@Schema` version bump — significant overhead for what is essentially a small ordered list (≤ 20 items).
- `PlatformSettings` is already used for other per-graph preferences (e.g. `cached_graph_path`). Namespacing by path is consistent with the existing pattern `platformSettings.getString("cached_graph_path", "")`.
- The list is bounded (max 20 UUIDs, ≈ 720 bytes). Storing it as a settings key is not a storage concern.
- Recovery is trivial: if the graph path changes the list auto-clears to an empty string, which is the correct fallback behaviour.

## Consequences

- **Positive**: No SQLDelight migration needed. No new table, no FTS changes.
- **Positive**: Consistent with the existing per-graph key pattern.
- **Negative**: If the graph path is renamed/moved by the OS, the recents list is silently lost (same consequence as the existing `lastGraphPath` setting).
- **Negative**: Graph IDs would be more stable keys (paths can change), but `GraphInfo.id` is not available in `StelekitViewModel` at the point `addToRecent()` is called in the single-graph startup path — using path avoids a plumbing refactor.

## Patterns Applied

- Key-value settings namespacing (per-tenant settings pattern)
- Fail-safe defaults (empty list on key miss)
