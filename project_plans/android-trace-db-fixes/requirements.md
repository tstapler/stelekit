# Requirements: Android DB Performance — Trace-Confirmed Fixes

**Status**: Draft | **Phase**: 1 — Ideation complete
**Created**: 2026-06-02

## Context

This project continues `android-save-parse-performance`, which was awaiting a session export from a real device before committing to specific fixes. The session export has been collected (610 spans, 19 sessions, Android, app v0.33.0, SAF-backed graph). The trace confirms and extends the hypotheses from `android-save-parse-performance/research/synthesis.md`.

**Trace file**: `uploads/1780383911191-4219198234-stelekit-perf-2026-06-02-0004.json`

## Problem Statement

On Android with a real-world SAF-backed graph, four distinct DB-layer bottlenecks produce severe user-visible latency. These are now confirmed by span evidence in the session export — not hypotheses.

### Issue 1 — db.saveBlocks UPDATE path: 300–2,000x regression vs INSERT

When a page is first inserted into an empty DB, `db.saveBlocks` completes at ~3ms/block. When the page already exists and blocks must be updated, the same operation takes **1,000–6,682ms/block** — a 300–2,000x regression.

Evidence from trace:
- Session `019e7f0f` (fresh DB, INSERT): 133 blocks in **401ms total** = 3ms/block
- Session `019e8601` (warm DB, UPDATE): 63 blocks in **111,025ms** = 1,762ms/block
- Session `019e85f2` "My Dog Mochi.md" (2 blocks, UPDATE): **13,363ms** = 6,682ms/block

Root cause candidates:
1. N individual UPDATE statements without wrapping `transaction { }` — each write triggers a WAL page-lock/unlock cycle
2. Missing index on `blocks.page_uuid` or `blocks.uuid` — full-table scan per block update
3. Possible DELETE + re-insert pattern when updating existing pages

### Issue 2 — db.lookupPage: 200x slower when page exists

`db.lookupPage` on `page.found=false` takes 2–10ms. On `page.found=true` it takes **500–2,200ms avg**.

Evidence:
- 30 lookups `page.found=true`, avg **1,204ms** (session `019e7fd2`)
- 20 lookups `page.found=true`, avg **1,107ms** (session `019e800d`)
- Corresponding INSERT-path sessions: avg **5ms**

This is a full-table scan on a `SELECT ... WHERE name = ?` that happens to find a row. Root cause: missing index on `pages.name` (or `normalized_name`) — the query must scan every row to determine a "not found" result terminates earlier than a "found" result.

### Issue 3 — Read operations routed through write actor (~1,000ms queue waits)

Inside every `parseAndSavePage` span, there are ~1,000ms inter-span gaps between each child operation:

```
gap=968ms  → db.getBlocks (READ)
gap=980ms  → db.savePage (WRITE)
gap=850ms  → diff (CPU)
gap=1,045ms → db.saveBlocks (WRITE)
```

Reads (`db.lookupPage`, `db.getBlocks`) are enqueued in the `DatabaseWriteActor` queue alongside writes, creating serial head-of-line blocking. A 43-byte file with 2 blocks takes **34.8 seconds** primarily from these 1-second waits.

### Issue 4 — editor_input median latency ≥ 5,000ms

Histogram from trace:
```json
{"operationName": "editor_input", "p50Ms": 5000, "p95Ms": 5000, "p99Ms": 5000, "sampleCount": 111}
```

5,000ms is a cap value — at least 50% of 111 keystrokes exceeded 5 seconds. This is the direct result of Issues 1–3: when the `DatabaseWriteActor` is blocked processing a bulk page load, debounced editor saves queue behind it, freezing the editor.

## Success Criteria

| Metric | Current (trace) | Target |
|--------|-----------------|--------|
| `db.saveBlocks` warm DB | 1,000–6,682ms/block | < 10ms/block |
| `db.lookupPage` found=true | 500–2,200ms | < 20ms |
| `parseAndSavePage` inter-span gap | ~1,000ms per op | < 50ms |
| `editor_input` p50 | 5,000ms (capped) | < 100ms |
| JVM `jvmTest` | all passing | no regression |
| Android unit tests | all passing | no regression |
| iOS (commonMain) | not measured | no regression |

## Scope

### Must Have (MoSCoW)

1. **Wrap block saves in a single transaction** — all `db.saveBlocks` for one page must execute inside a single SQLite `transaction { }`. This eliminates per-write WAL page-lock overhead.

2. **Add missing DB indexes** — add indexes that SQL query plans confirm are missing:
   - `CREATE INDEX IF NOT EXISTS idx_pages_name ON pages(name)` (or `normalized_name` — whichever column `lookupPage` queries)
   - `CREATE INDEX IF NOT EXISTS idx_blocks_page_uuid ON blocks(page_uuid)` (if missing)
   - `CREATE INDEX IF NOT EXISTS idx_blocks_uuid ON blocks(uuid)` (if missing)
   - Indexes must also be added to `MigrationRunner.all` (project rule: every schema change must appear in migrations)

3. **Route read operations off the write actor** — `db.lookupPage` and `db.getBlocks` must use `PlatformDispatcher.DB` directly, not queue through `DatabaseWriteActor`.

4. **Composite actor execute for full page save** — the four sequential actor enqueues in `parseAndSavePage` (lookupPage → getBlocks → savePage → saveBlocks) must collapse into a single `actor.execute { }` lambda for the write portion, with reads executing via `withContext(PlatformDispatcher.DB)` before the actor.

### Should Have

- Performance regression test: an instrumented Android test (or businessTest equivalent) that would have caught Issues 1 and 2 (verifies that warm-DB block saves stay < 10ms/block and lookupPage stays < 20ms)
- Verify and apply `EXPLAIN QUERY PLAN` on `lookupPage` and `getBlocks` queries to confirm index usage

### Out of Scope

- SAF shadow-copy / `filesDir` migration (addressed in `saf-cache-redesign` project)
- Phase 3 / BlockStateManager race condition (addressed in `android-save-parse-performance`)
- PRAGMA tuning (covered by `android-save-parse-performance`, Fix 3)
- Read-before-write removal in `savePageInternal` (covered by `android-save-parse-performance`, Step 3)
- iOS-specific profiling
- UI frame rendering (trace confirms p99 frames = 33ms, no systemic jank)

## Constraints

- **Must not break iOS or Desktop**: all changes to `commonMain` or `commonTest` code must pass `jvmTest` and Android unit tests on CI before merging. iOS uses the same `commonMain` SQL; index additions are additive and safe.
- **No new dependencies**: use existing SQLDelight, Arrow, coroutines only.
- **API contracts preserved**: `DatabaseWriteActor` public API, repository interfaces, and `GraphWriter`/`GraphLoader` public APIs must not change signatures (internal restructuring is fine).
- **Migration rule**: every `CREATE INDEX` added to `SteleDatabase.sq` must also appear in `MigrationRunner.all` (existing project constraint, enforced by `MigrationRunnerSchemaSyncTest`).

## Architecture — Key Fix Points

### Fix 1: Transaction batching in block saves

In `SqlDelightBlockRepository` (or wherever `db.saveBlocks` is implemented), the block save loop must be wrapped:

```kotlin
// Before (each block is its own implicit transaction):
blocks.forEach { block -> queries.upsertBlock(block.uuid, block.content, ...) }

// After (single transaction for all blocks on a page):
withContext(PlatformDispatcher.DB) {
    queries.transaction {
        blocks.forEach { block -> queries.upsertBlock(block.uuid, block.content, ...) }
    }
}
```

### Fix 2: Index additions in SteleDatabase.sq + MigrationRunner

Add to `SteleDatabase.sq`:
```sql
CREATE INDEX IF NOT EXISTS idx_pages_name ON pages(name);
CREATE INDEX IF NOT EXISTS idx_blocks_page_uuid ON blocks(page_uuid);
```

Add corresponding migration in `MigrationRunner.all`:
```kotlin
Migration(version = X, sql = """
    CREATE INDEX IF NOT EXISTS idx_pages_name ON pages(name);
    CREATE INDEX IF NOT EXISTS idx_blocks_page_uuid ON blocks(page_uuid);
""")
```

### Fix 3: Read operations off the write actor

In `GraphLoader.parseAndSavePage` (and related callers), read calls must switch from the actor queue to direct `withContext(PlatformDispatcher.DB)`:

```kotlin
// Before: reads go through actor queue
val existingPage = actor.execute { queries.lookupPage(uuid) }

// After: reads bypass actor, use DB dispatcher directly
val existingPage = withContext(PlatformDispatcher.DB) { queries.selectPageByUuid(uuid) }
```

### Fix 4: Composite actor execute

For the write portion of `parseAndSavePage`, collapse the sequential actor calls into one:

```kotlin
// Before: 4 separate actor.execute { } calls with ~1,000ms gaps
val page = actor.execute { queries.lookupPage(...) }
val blocks = actor.execute { queries.getBlocks(...) }
actor.execute { queries.savePage(...) }
actor.execute { queries.saveBlocks(...) }

// After: reads first (off actor), then single write transaction
val page = withContext(PlatformDispatcher.DB) { queries.selectPageByName(...) }
val blocks = withContext(PlatformDispatcher.DB) { queries.selectBlocksByPage(page.uuid) }
actor.execute {
    queries.transaction {
        queries.upsertPage(...)
        blocks.forEach { queries.upsertBlock(...) }
    }
}
```

## Prior Research

This project does NOT need to redo research already completed in `android-save-parse-performance/research/`. Relevant findings already established:
- `findings-stack.md`: Microbenchmark (not Macrobenchmark) is the right CI tool
- `findings-architecture.md`: `DatabaseWriteActor` confirmed as sole write serializer; actor contention identified as secondary bottleneck
- `synthesis.md`: "Only after Step 5 [session export] should actor chunk decomposition be committed to" — Step 5 is now complete via the trace file

Research needed for THIS project:
- **SQLite transaction batching**: confirm optimal batch sizes and transaction isolation for WAL mode
- **SQLite index strategy**: verify `EXPLAIN QUERY PLAN` outputs for specific queries in `SteleDatabase.sq`
- **Actor routing patterns**: research patterns for separating read/write actor responsibilities in Kotlin coroutines (without breaking existing `RestrictedDatabaseQueries` write enforcement)
- **Pitfalls**: partial-write visibility during transaction, index rebuild cost on migration, WAL checkpoint behavior after transaction batching

## Stakeholders

- Tyler Stapler (developer + user)
