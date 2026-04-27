# Findings: Features — Comparable App Strategies

**Dimension**: How comparable note-taking apps handle block/page caching and mobile DB performance
**Date**: 2026-04-24
**Status**: Training knowledge + pending web searches

---

## Summary

Five note-taking apps were surveyed across five trade-off axes: caching strategy (in-memory vs. persistent), DB/storage choice, mobile performance approach, offline correctness, and block graph traversal strategy. The dominant pattern across all apps is a **two-tier model**: a hot in-memory structure for active navigation and search, backed by a persistent store (SQLite or file system) for durability.

Key take-aways relevant to SteleKit:

- Logseq's original all-in-memory DataScript approach is the canonical cautionary tale: fast on desktop, disastrously slow on mobile for large graphs (8+ second cold starts on iPhone 14; crashes above 300 MB graph size).
- Logseq's ongoing DB migration to SQLite + DataScript overlay directly mirrors SteleKit's current SQLDelight architecture and validates the approach.
- Obsidian's `MetadataCache` — a per-file, event-driven in-memory index of links/tags/headings — is the most directly transferable pattern to SteleKit's block reference graph.
- Bear's SQLite + FTS5 combination on iOS shows that a pure-SQLite stack with full-text search can deliver near-instant search on mobile without a separate in-memory index layer.
- AppFlowy's Rust + CRDT (yrs/Yjs) demonstrates how a local-first architecture can defer writes while keeping an in-memory "transactional" view consistent.
- SQLite WAL mode is table-stakes: read concurrency improves ~12x over rollback-journal mode. [TRAINING_ONLY — verify exact multiplier]

---

## Options Surveyed

### 1. Logseq — DataScript in-memory graph with SQLite persistence overlay

**Caching strategy**: The entire graph lives in RAM. No lazy loading. Derived query results are memoized using DataScript's `max-tx` (transaction counter) as a cache key. When any transaction occurs, the key changes and the derived cache is automatically invalidated. A Web Worker isolation model keeps the UI thread free while the DataScript worker processes heavy queries.

**New DB version (2024–2025)**: Logseq is migrating to a hybrid model — DataScript stays as the query engine but now speaks to a SQLite backing store via a KVS table. On write, DataScript transactions are flushed to SQLite. On startup, data is loaded from SQLite into DataScript rather than re-parsing markdown files. [TRAINING_ONLY — verify exact flush/load sequence]

**Mobile performance**: The legacy file-based model is severely problematic on mobile: iOS cold start 8+ seconds on iPhone 14 for graphs with several thousand pages; Android nearly every open requires a full re-index; graphs exceeding 300 MB cause startup failures (OOM) on mobile; the graph view becomes completely unresponsive with 18,500+ interconnected pages.

---

### 2. Obsidian — per-file MetadataCache with lazy file content

**Caching strategy**: `MetadataCache` is populated at vault open, storing per-file: links, backlinks, headings, tags, frontmatter, embeds, and section positions. File content itself is NOT kept in memory. Cache invalidation is event-driven — when a file is modified on disk, Obsidian re-parses only that file and updates its entry. A persistent binary cache on disk (`.obsidian/cache`) stores the index across restarts; subsequent startups reconcile against actual file modification times.

**Mobile performance**: Substantially faster than Logseq for the same vault size because it avoids full markdown-to-graph parsing. Memory is a concern on iOS: the in-memory cache can be evicted, forcing a re-index on next open. For very large vaults (50,000+ files) with plugins like Dataview, RAM usage can reach 6–10 GB [TRAINING_ONLY — verify; includes plugin overhead].

**Key lesson**: Structural metadata (links, tags, headings) is sufficient for 90% of UI interactions. You do not need to load content to build a useful navigation graph.

---

### 3. Bear — SQLite as primary store, FTS5 for search

**Caching strategy**: No known separate in-memory index layer. Bear relies on SQLite's own page cache (`PRAGMA cache_size`) plus the OS buffer cache. FTS5 (Full-Text Search, SQLite extension) is used for instant search, maintaining its own inverted index within SQLite, updated transactionally on every write. No separate indexing step and no cache invalidation logic needed.

**Mobile performance**: Bear is widely cited as the benchmark for fast, responsive note-taking on iOS. Key reasons: SQLite with FTS5 provides sub-100ms search across thousands of notes; no startup indexing phase; background operations use WAL mode, allowing the UI thread to read while writes occur on background threads; the database is the source of truth, so there is no cold-start rebuild step.

---

### 4. Roam Research — full graph in-memory, server-authoritative

**Caching strategy**: The full graph (all blocks, pages, references) is loaded on sign-in and kept in memory in the browser as a JavaScript object graph. Block references are resolved entirely in-memory using JavaScript Map/Set lookups, making reference rendering fast once loaded.

**Mobile performance**: No native mobile app. The web app degrades significantly for large databases (10,000+ notes). Users report slowdowns and browser tab crashes — the predictable consequence of loading a full graph into a resource-constrained browser tab.

**Offline correctness**: Fundamentally weak — no server connection means no edits. This is Roam's most cited weakness.

---

### 5. AppFlowy — Rust + CRDT (yrs) with dual SQLite/RocksDB storage

**Caching strategy**: CRDT documents are loaded into memory as `yrs` `Doc` objects when a page is opened. Changes are recorded as yrs transactions (CRDT updates), flushed to CollabKVDB via plugin hook. For read-only access (navigation, search), AppFlowy queries SQLite metadata rather than deserializing full CRDT documents.

**Mobile performance**: The Rust backend compiles to native code for iOS/Android, avoiding JVM/JS overhead. CRDT merge on open is O(document size), not O(total vault size) — scales much better than whole-graph-in-memory approaches on mobile.

---

## Trade-off Matrix

| App | In-memory scope | Persistent store | Mobile startup | Offline correctness | Block graph traversal |
|-----|-----------------|------------------|----------------|--------------------|-----------------------|
| Logseq (file) | Entire graph (DataScript) | Markdown files | Very slow — 8s+ iPhone 14 | Good (file is truth) | Datalog on full in-memory graph |
| Logseq (DB) | Entire graph (DataScript) | SQLite + KVS | Improved (not benchmarked) | Good | Same Datalog, faster load |
| Obsidian | Structural index per file | Files + `.obsidian/cache` | Fast (incremental cache) | Good (file is truth) | O(1) hash map via MetadataCache |
| Bear | SQLite page cache only | SQLite (FTS5 included) | Instant (no rebuild) | Excellent (SQLite is truth) | SQL query on FTS5 index |
| Roam | Entire graph (JS objects) | Server only | Slow (full graph download) | None (requires server) | O(1) hash lookup in JS memory |
| AppFlowy | Active page CRDT Doc | SQLite + RocksDB | Fast (page-granular load) | Excellent (CRDT) | SQL metadata query |

---

## Risk and Failure Modes

**Whole-graph-in-memory (Logseq file mode, Roam)**: Memory exhaustion on mobile; cold start time scales linearly with graph size; garbage collection of stale derived state must be explicit and correct.

**Structural index only (Obsidian MetadataCache)**: Index size still grows with vault size; index corruption or desync requires full re-index; plugins that bypass the cache (like Dataview) re-introduce the whole-graph parsing problem.

**SQLite as sole store (Bear)**: WAL checkpoint stalls can cause write latency spikes; WAL file growth if readers hold long transactions; FTS5 index maintenance adds write amplification (~2–5x for indexed columns).

**CRDT (AppFlowy)**: CRDT state vectors grow monotonically; old tombstones are never truly deleted without snapshot/compaction; RocksDB compaction pauses can cause latency spikes on mobile.

---

## Migration and Adoption Cost

For SteleKit, which already uses SQLDelight (SQLite) with an in-memory repository layer:

- **Obsidian-style backlink MetadataCache**: medium cost. SteleKit's `PageRepository` and `BlockRepository` are already the conceptual equivalent; the gap is adding a lightweight in-memory reference/backlink index populated from DB queries at startup, invalidated via DB change notifications.
- **Bear-style FTS5**: low cost. SQLDelight supports FTS5 virtual tables via raw `CREATE VIRTUAL TABLE` in `.sq` files. Main work is schema design and query routing.
- **AppFlowy-style CRDT**: very high cost. Requires replacing the write model entirely. Not recommended unless collaborative editing is a first-class requirement.
- **Logseq-style full-graph-in-memory**: counter-productive given existing architecture and known mobile failure modes.

---

## Operational Concerns

- **WAL checkpoint management**: If SteleKit enables WAL mode (implied by the JVM `PooledJdbcSqliteDriver`), the WAL file must be checkpointed periodically. Logseq's 3-day GC/VACUUM cycle is a reference implementation.
- **Cache warming on startup**: Obsidian's pattern of reading a persisted cache and reconciling against mtimes is directly applicable to SteleKit's block reference index. Cost moves from O(total blocks) to O(changed blocks) on subsequent opens.
- **Memory pressure events**: iOS sends `didReceiveMemoryWarning`; Android has `onTrimMemory`. Any in-memory cache in SteleKit must respond to these by evicting non-essential entries.
- **Background vs. foreground indexing**: Heavy indexing should stay off the UI thread — maps to using `PlatformDispatcher.DB` for all repository work and never touching the DB on the main thread.

---

## Prior Art and Lessons Learned

1. **Logseq's DB migration (2024–2025)** validates that file-based → SQLite-backed transitions are feasible without breaking user data, even for complex graph structures.

2. **Obsidian's MetadataCache design** demonstrates that you do not need to load content to build a useful navigation graph. Structural metadata is sufficient for 90% of UI interactions.

3. **Bear's FTS5 success** shows that a pure SQLite stack with proper indexing is sufficient for fast search on mobile without an additional search engine.

4. **Dataview plugin's mobile slowness** (Obsidian ecosystem): a structural cache is only useful if all consumers respect it. Re-parsing files in a plugin layer defeats the entire architecture. SteleKit should ensure all read paths go through the repository layer and never bypass it to read `.md` files directly.

5. **AppFlowy's page-granular CRDT load** is the right unit of granularity for mobile: pay load cost per page opened, not per vault opened. SteleKit's current `GraphLoader` path (load page on navigate) already follows this pattern.

---

## Open Questions

- [ ] Does SteleKit's current startup path (`GraphManager.addGraph()`) load all pages eagerly or only metadata? If eager, this is a Logseq-file-mode antipattern. — blocks decision on: startup perf optimization priority
- [ ] Does SteleKit's SQLDelight driver use WAL mode on iOS/Android? — blocks decision on: which platforms need WAL verification
- [ ] Is there a structural in-memory index for block references (backlinks) in SteleKit, or does every backlink query hit SQLite? — blocks decision on: whether FTS5 or a dedicated backlink table is needed
- [ ] What is SteleKit's cold-start latency on Android with a 5,000-block graph? — blocks decision on: baseline vs. which approach to adopt

---

## Recommendation

SteleKit's SQLDelight architecture is already better positioned than Logseq's original file-based model. The three highest-leverage improvements ordered by cost-to-impact:

**1. Verify and enable SQLite WAL mode on all platforms** (low cost, high impact): WAL read/write concurrency eliminates the main source of UI jank. Target: verify WAL is active on iOS and Android native SQLite drivers, not just the JVM pool.

**2. Build a lightweight in-memory backlink index** (medium cost, high impact): Model after Obsidian's `MetadataCache`. At startup, run a single query to load `(block_uuid, referenced_uuid)` pairs into a Kotlin `HashMap`. Invalidate on `DatabaseWriteActor` write events. Makes block reference resolution O(1) instead of requiring a DB round-trip per block.

**3. Add FTS5 virtual table for full-text search** (medium cost, medium impact): Eliminates full-table LIKE scans. Bear demonstrates this works well on iOS without additional infrastructure.

Do not adopt whole-graph-in-memory loading (Logseq file-mode antipattern) or CRDT infrastructure (AppFlowy) unless the product requires real-time collaboration.

---

## Pending Web Searches

1. `Logseq DataScript in-memory graph database block caching architecture 2024 2025` — verify exact DataScript query memoization mechanism
2. `Logseq DB version SQLite migration DataScript performance mobile startup 2025` — quantitative benchmarks for new DB mode
3. `Obsidian MetadataCache CachedMetadata links tags headings cache invalidation` — verify cache update mechanism
4. `Bear notes FTS5 full text search SQLite iOS performance sub-100ms` — verify Bear's FTS5 search performance claims
5. `SQLite WAL read write concurrency improvement benchmark` — verify ~12x read improvement claim
6. `AppFlowy yrs CRDT collab RocksDB block cache mobile iOS Android` — verify AppFlowy's per-page loading approach
7. `SQLDelight iOS NativeSqliteDriver WAL journal_mode` — confirm WAL support on iOS native SQLite driver
