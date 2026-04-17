# ADR-006: UUID Migration

**Status**: Proposed
**Date**: 2026-04-13
**Deciders**: Tyler Stapler

## Context

SteleKit's current block UUID scheme is `hash(filePath + parentUuid + siblingIndex + content)`. The new scheme (ADR-001) is `hash(filePath + parentUuid + siblingIndex)`. Every existing block in every existing SQLite database must be migrated from its old content-derived UUID to the new position-derived UUID.

This migration is destructive: the old UUIDs no longer exist after it runs. It is not gradual: there is no stable intermediate state where some blocks have old UUIDs and others have new ones, because blocks reference each other via `parent_uuid`, `left_uuid`, and `block_references`.

Migration constraints:
- Must run inside a single SQLite transaction (atomic: all-or-nothing)
- Must update all FK columns that reference `blocks.uuid`: `parent_uuid`, `left_uuid`, `block_references.from_block_uuid`, `block_references.to_block_uuid`, `properties.block_uuid`
- Must validate FK consistency before commit
- Must handle the edge case where the new UUID collides with an existing UUID (rare but possible if two blocks swap positions)
- Must not corrupt the graph if interrupted mid-run
- SQLDelight schema migrations are DDL-only (no procedural logic); the migration must be Kotlin-side

## Decision

The migration is implemented as a **Kotlin-side transaction** in `UuidMigration.kt`, invoked by `GraphManager` on first open of an existing DB that has not yet been migrated (detected via `metadata.schema_version < 2`).

### Migration Procedure

1. `GraphManager.addGraph()` checks `metadata.schema_version`. If version < 2 and blocks exist, invoke `UuidMigration.run(db, graphPath, fileSystem)`
2. `UuidMigration.run()`:
   a. Temporarily disable FK enforcement: `PRAGMA foreign_keys = OFF`
   b. Open a `db.transaction { ... }` block
   c. For each page in the graph (read from `pages` table):
      - Read the corresponding `.md` file from disk using `graphPath + page.file_path`
      - Re-parse the file using `MarkdownParser` to get a flat list of `ParsedBlock` items with positions
      - For each block, derive the new UUID using the updated `generateUuid` (position-only seed)
      - If `oldUuid != newUuid`: execute `UPDATE blocks SET uuid = newUuid WHERE uuid = oldUuid`
   d. After all blocks updated: run cascade updates in this order:
      - `UPDATE blocks SET parent_uuid = newUuid WHERE parent_uuid = oldUuid` (for all changed pairs)
      - `UPDATE blocks SET left_uuid = newUuid WHERE left_uuid = oldUuid`
      - `UPDATE block_references SET from_block_uuid = newUuid WHERE from_block_uuid = oldUuid`
      - `UPDATE block_references SET to_block_uuid = newUuid WHERE to_block_uuid = oldUuid`
      - `UPDATE properties SET block_uuid = newUuid WHERE block_uuid = oldUuid`
   e. Run validation query (must return 0):
      ```sql
      SELECT COUNT(*) FROM blocks b
      WHERE b.parent_uuid IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM blocks p WHERE p.uuid = b.parent_uuid)
      ```
   f. If validation passes: commit transaction, re-enable FK enforcement, update `metadata.schema_version` to `'2'`
   g. If validation fails or any step throws: rollback, log error, surface to user as a blocking error
3. The migration runs before `GraphLoader.loadGraph()` is called; the file watcher is not started until after migration succeeds

### Collision Handling

If the new UUID derived for block X equals the current UUID of block Y (which will itself be updated to a different UUID), the naive approach fails with a UNIQUE constraint violation. Resolution:

- Build the full `(oldUuid → newUuid)` mapping for the entire graph before executing any UPDATE
- Detect cycles in the mapping (positions that swap: block A's new UUID = block B's current UUID and vice versa)
- Resolve cycles by using a temporary UUID as an intermediate step: `A → temp → B's new UUID`, `B → A's new UUID`
- In practice, this collision scenario requires two blocks to occupy exactly swapped positions in the same file between the old and new parse, which is only possible if the migration re-parse encounters files that have changed since the UUID scheme did. For a one-shot migration on a consistent DB+file pair, this should not occur. If detected, log it and use the intermediate UUID approach.

## Rationale

**Single-transaction atomicity is non-negotiable.** A partial migration (some blocks updated, some not) would leave FK references in a broken state. The only safe states are: pre-migration (all old UUIDs) and post-migration (all new UUIDs). A transaction enforces this.

**Kotlin-side migration over SQLDelight migration system.** SQLDelight migrations support DDL (`CREATE TABLE`, `ALTER TABLE`) but not procedural logic that reads markdown files and re-derives values. The migration requires reading the file system and executing the `MarkdownParser`. This is only possible in Kotlin code.

**Disable FK enforcement during migration.** Re-enabling FK enforcement mid-migration (after each row update) would cause FK violations because `parent_uuid` references have not yet been updated when `uuid` is updated. Disabling FKs for the duration of the transaction and running a post-migration validation query provides the same correctness guarantee without the ordering constraint.

**Validate before commit.** The validation query is the final safety check. If any FK reference is broken (missing parent), the transaction is rolled back. Running on a copy of the production graph before the real migration is strongly recommended (advised in `UuidMigration.kt` kdoc).

**File watcher stopped during migration.** If the file watcher fires during migration, `parseAndSavePage` would attempt to save blocks with new-scheme UUIDs into a DB that still has old-scheme UUIDs (the transaction is not yet committed). This would cause insert failures or duplicate-key errors. Stopping the watcher before migration and restarting after commit avoids this race entirely.

## Consequences

### Positive
- Migration is a one-time, atomic operation. Once complete, all subsequent operations use the new scheme.
- Rollback on any failure means the DB is never left in a partially-migrated state
- Validation query provides a correctness guarantee before commit
- `schema_version` check ensures the migration runs exactly once per DB

### Negative (accepted costs)
- Migration duration is proportional to graph size. A graph with 100,000 blocks and 500 pages may take several seconds. A progress indicator in the loading UI is recommended.
- During migration, the app is unresponsive (migration runs on the write actor's coroutine). For large graphs, this may be noticeable.
- FTS5 triggers fire for every `UPDATE blocks SET uuid = ...` row, adding FTS5 reindex overhead. On large graphs, consider disabling triggers during migration and rebuilding the FTS5 index afterward (see Known Issues in plan.md).

### Risks
- **File out of sync with DB**: If a markdown file has been edited after the last DB load (before the migration), the re-parse produces UUIDs based on the current file state, which may not match the DB state. The mapping `oldUuid → newUuid` is built from the re-parse, not from the DB. This means the migration re-derives UUIDs from the ground truth (files) and rebuilds the DB accordingly — effectively treating the migration as a re-import. This is correct behavior but means any DB-only changes (properties, favorites) not reflected in the file may be reassigned to blocks at their current file positions.
- **Backup recommendation**: Log a prominent warning before migration recommending the user back up their `.db` file. The migration is not reversible without a backup.

## Alternatives Rejected

- **Gradual migration (per-page on demand)**: Rejected because it produces an intermediate state where some pages have new UUIDs and others have old UUIDs. Cross-page backlinks (block references) span pages; a backlink from a migrated page to an unmigrated page would have a broken reference until the target page migrates. There is no safe intermediate state.
- **SQLDelight migration system (DDL-only)**: SQLDelight migrations can add or rename columns and tables. They cannot read external files, run Kotlin code, or derive values. The UUID migration requires all three.
- **Store both old and new UUIDs temporarily**: Adding an `old_uuid` column during migration would allow a gradual approach. Rejected because it adds schema complexity and requires a second migration step to remove the temporary column. The single-transaction approach is simpler and safer.
- **Rebuild from scratch (delete all blocks, re-import all files)**: Equivalent to the current architecture (DELETE all + INSERT all), which is what this migration is designed to replace. It would also lose all per-block metadata (favorites, properties not in files, backlink references that are not in the file content).
