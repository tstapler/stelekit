# ADR-013: Fractional String Position Ordering for Block Sibling Ordering

**Status**: Accepted
**Date**: 2026-06-19
**Feature**: Block Operations Performance — Phase 2 (R1)

## Context

The `blocks` table has a `position INTEGER` column that gives the sort order among siblings. SQLDelight queries use `ORDER BY position` with index `idx_blocks_parent_position(parent_uuid, position)` to retrieve children in order.

The current scheme assigns dense sequential integers (0, 1, 2, …) to siblings. Inserting a new block in the middle of a sibling list requires shifting every subsequent sibling's position value upward — even as a single batch `UPDATE`, this touches O(n) rows per insertion. On a 500-block page, every Enter keypress that inserts in the middle emits a batch UPDATE writing ~500 rows. This contributes measurably to write latency and WAL frame pressure on Android.

### Alternatives Evaluated

**Option A: Float midpoint (`position REAL`)**

Store `position REAL`. Insert between `L` and `R` using `(L + R) / 2.0`. IEEE 754 doubles have 52 mantissa bits; after ~52 consecutive bisections at the same gap the midpoint rounds to one of its endpoints. Two blocks silently acquire equal position values, making `ORDER BY position` return undefined relative order for those blocks — a silent correctness failure with no detectable error. Verified empirically by vlcn.io: 53 inserts between the same two positions causes collapse. Not safe.

**Option B: Large-gap integers**

Keep `position INTEGER`, initialize with gap 65536 between siblings. Insert midpoint = `(L + R) / 2` (integer division). `log2(65536) = 16` bisections before the gap reaches 1 and a rebalance is needed. Rebalance is detectable (`abs(R - L) == 1`) and is O(sibling count) per parent, not O(all blocks). No schema type change; migration is a single `UPDATE blocks SET position = position * 65536`. Simple, no dependencies, rarely triggers rebalance for realistic editing patterns.

**Option C: Lexicographic string fractional index (`rocicorp/fractional-indexing`)**

Store `position TEXT`. Use base-62 variable-length strings that sort correctly under SQLite's default BINARY (memcmp) collation. The midpoint algorithm appends characters rather than colliding — string length grows logarithmically (base 62), so 10 000 inserts at the same slot yields keys of only 3–4 characters. No rebalance ever needed. This is the scheme used by Logseq DB (`:block/order`), Figma, and Linear.

Logseq's evolution is instructive: they moved from a `left_uuid` linked list (Era 2, the architecture SteleKit inherited) to a fractional string order (Era 3) specifically because `ORDER BY order_key` on an indexed TEXT column is faster and simpler than a `WITH RECURSIVE` linked-list traversal, and because the fractional string never rebalances.

A KMP port is available on Maven Central as `com.davidarvelo:fractional-indexing` (`commonMain`-compatible, byte-for-byte compatible with the JS reference implementation). The alternative is a self-contained ~50-line Kotlin implementation ported from Logseq's Clojure source.

### Constraint: `left_uuid` is retained for file format fidelity

The Logseq `.md` file format uses `:block/left` UUID refs to encode sibling order. `left_uuid` is kept in the schema for serialization round-trip fidelity. On page load, the `left_uuid` chain is traversed once to assign `position TEXT` values; on block insert/move, both `left_uuid` and `position` are updated in the same transaction.

`left_uuid` alone cannot replace `position` for SQL ordering: a `WITH RECURSIVE` CTE is needed to reconstruct linked-list order, is ~2× slower than `ORDER BY position` on an indexed column per vlcn.io benchmarks, and would require rewriting every ordered block query.

## Decision

Replace `position INTEGER` with `position TEXT NOT NULL` using the `rocicorp/fractional-indexing` algorithm (base-62 lexicographic string midpoints) for block sibling ordering.

Concrete changes:

1. **Schema migration**: recreate the `blocks` table with `position TEXT NOT NULL` (was `INTEGER NOT NULL`). Because `blocks` has FTS5 triggers (`blocks_ai`, `blocks_ad`, `blocks_au`) that reference it, the migration must drop and recreate those triggers. The `idx_blocks_parent_position` and `idx_blocks_page_position` indexes must be recreated with the same definitions — `ORDER BY position` continues to work unchanged with TEXT lexicographic ordering under BINARY collation.

2. **Data migration**: convert existing integer positions to their fractional string equivalents in the correct relative order per parent group. Use a `WITH RECURSIVE`-free approach: for each `(page_uuid, parent_uuid)` group, assign `generateKeyBetween` values in order of existing integer position. A `WITH` CTE using `ROW_NUMBER() OVER (PARTITION BY page_uuid, parent_uuid ORDER BY position)` extracts the rank; the Kotlin migration helper maps rank to a pre-generated sequence of fractional keys. Runs in `MigrationRunner` before the UI is shown.

3. **Block insert/move logic**: `SqlDelightBlockRepository.splitBlock`, `moveBlockUp`, `moveBlockDown`, `insertBlock` — wherever new positions are computed — replace `(left.position + right.position) / 2` integer arithmetic with `FractionalIndexing.generateKeyBetween(leftKey, rightKey)`. Null arguments are supported by the library for first/last position.

4. **`left_uuid` unchanged**: file serialization and all `left_uuid` maintenance code is unaffected.

5. **Query changes**: none. All `ORDER BY position` queries, `idx_blocks_parent_position`, and `idx_blocks_page_position` work identically with TEXT values under BINARY collation. `COLLATE NOCASE` must NOT be added to the column or index — it would break ordering for mixed-case base-62 keys.

6. **`shiftRootBlockPositionsFrom` / `shiftChildBlockPositionsFrom`**: these batch UPDATE queries are deleted from `SteleDatabase.sq` and `RestrictedDatabaseQueries` once fractional ordering is live, since no shifts are ever needed.

## Rationale

- **Eliminates O(n) position shifts**: inserting anywhere in a 500-block page now writes exactly 1 row (the new block), not 501 rows (1 new + 500 shifted). The batch shift UPDATEs (`shiftRootBlockPositionsFrom`, `shiftChildBlockPositionsFrom`) are dead code once fractional ordering is adopted.
- **Never rebalances**: string fractional indices grow longer rather than colliding. The rebalance procedure needed for integer-gap or float approaches is never needed.
- **ORDER BY unchanged**: existing SQL queries and indexes require no modification. The migration is contained entirely to the Kotlin insert/move logic and the one-time schema + data migration.
- **Matches Logseq DB**: the reference implementation for SteleKit already made this choice. The algorithm is proven at Logseq's production scale.
- **Acceptance criterion met**: after migration, inserting a block at any position in a 500-block page produces zero `UPDATE blocks SET position = ...` statements, verified by a SQL trace test.

## Consequences

**Positive**

- Zero position-shift writes on insert or move. On a 500-block page, every Enter keypress that inserts in the middle drops from ~501 writes to 1 write.
- `shiftRootBlockPositionsFrom` and `shiftChildBlockPositionsFrom` are removed from the schema and codebase.
- `ORDER BY position` query performance is unchanged (TEXT comparison over bounded sibling lists is imperceptible vs. INTEGER).
- No rebalance code path to maintain or test.

**Negative / Trade-offs**

- `blocks` table requires full recreation during migration (not an `ALTER COLUMN`). The migration must drop and recreate FTS5 triggers. Migration holds the write lock for the duration of the table copy — estimated 3–6 seconds for an 8 000-block graph on Android. Runs at startup before UI is shown.
- New KMP dependency: `com.davidarvelo:fractional-indexing` (CC0 license, ~50 lines Kotlin). Alternatively, inline a self-contained port to avoid the dependency.
- `position TEXT` values are less human-readable in DB inspection than integers.
- BINARY collation on the `position` column must not be altered. Any future migration that recreates the `blocks` table must preserve the absence of a COLLATE clause on `position`.
- `MigrationRunnerSchemaSyncTest` must be updated: the test reads `SteleDatabase.sq` for `CREATE TABLE IF NOT EXISTS blocks` and verifies the migration entry exists; the schema file update is required for fresh-install correctness.

## Related

- Requirements: `project_plans/block-ops-perf/requirements.md` § R1
- Research: `project_plans/block-ops-perf/research/features.md` § 2, `research/stack.md` § R1, `research/pitfalls.md` § 1
- Complements ADR-011 (wikilink reference index) and ADR-012 (reactive invalidation): those ADRs addressed write-path and read-path O(N) costs; this ADR eliminates the O(n) position-shift writes that remain after Phase 1
- Implementation plan: `project_plans/block-ops-perf/`
