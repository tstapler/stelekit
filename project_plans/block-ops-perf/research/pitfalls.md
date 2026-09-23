# Pitfalls Research: Block Operations Performance Phase 2

Research findings for the six areas identified in requirements.md. Covers known failure modes, edge cases, and risks for each planned change.

---

## 1. Fractional / Sparse Position Pitfalls (R1)

### Float positions: IEEE 754 precision exhaustion

SQLite REAL uses IEEE 754 64-bit double (53 bits of significand). When using float midpoints for fractional indexing, precision degrades with each split:

- Near 0.5, adjacent doubles are spaced 2^-53 apart (one ULP).
- Starting from `[0.0, 1.0]`: after ~52–53 binary midpoint splits, `(left + right) / 2.0` rounds to one of its endpoints — the new position becomes identical to an existing neighbor, silently breaking ordering.
- Starting from `[0, 2^31]`: the extra integer range gives roughly 21 additional safe bisections (2^31 needs 32 bits, leaving 21 fractional bits of precision) before the gap falls to 1 ULP, then ~31 more before denormal range. Practical safe limit: **~52 bisections from any starting gap** before collision.

**Consequence of collision:** Two blocks acquire the same `position` value. `ORDER BY position` produces undefined relative order between them. No crash, no error — the ordering silently breaks and may manifest as blocks jumping positions on the next page reload.

**Rebalance cost:** When gaps exhaust, all siblings must be assigned new evenly-spaced positions (e.g., gaps of 65536 again). This requires an UPDATE touching every sibling — exactly the O(n) cost that fractional indexing was meant to avoid, though it should be rare in practice.

**Mitigation if using integer large-gap approach:** Use 64-bit integers (SQLite `INTEGER` stores up to 8 bytes), initial gap of 2^32. Each bisection halves the gap; after 32 splits the gap reaches 1 and further splits produce a gap of 0. Safe for approximately 32 successive insertions between the same pair of neighbors before rebalancing — vastly better than dense integers but still requires a rebalance procedure.

### String (lexicographic) positions: never exhausts

Figma-style base-36 string indices (`generateKeyBetween` pattern) produce keys like `"a0"`, `"V00000000000000"`. When two adjacent keys differ by only 1 in the last character, the algorithm appends a character to extend the key. String length grows by one character per "collision" — **key length grows without bound rather than colliding**. No rebalancing is ever needed.

**SQLite TEXT ordering:** SQLite compares TEXT columns byte-by-byte in UTF-8 (COLLATE BINARY by default). This is locale-independent. The fractional index libraries (e.g., `fractional-indexing` npm, Logseq's own implementation) generate keys that maintain correct lexicographic order under COLLATE BINARY. `COLLATE NOCASE` would break them (uppercase and lowercase sort interleaved). **The position column must use the default BINARY collation (no COLLATE clause).**

**Risk if NOCASE is accidentally applied:** A migration that creates the column with `COLLATE NOCASE` would silently corrupt ordering for keys mixing upper/lowercase characters.

### Schema migration risk (R1)

Changing `position INTEGER` to `position REAL` or `position TEXT` requires:
1. A migration that alters or recreates the `blocks` table.
2. Rewriting all existing integer positions to the new scheme for existing user databases.
3. Updating `idx_blocks_page_position` and `idx_blocks_parent_position` — these are critical for query plan quality (QueryPlanAuditTest covers them). ANALYZE must run after migration.

**Test gap:** No existing test verifies that two float/string positions that collide produce a deterministic ordering failure. A unit test for the gap-exhaustion case and the rebalance procedure is required.

---

## 2. WITHOUT ROWID and Foreign Keys (R2)

### FK cascade direction: safe

A `WITHOUT ROWID` table as the **child (referencing) table** — where `wikilink_references.block_uuid` references `blocks.uuid` — is fully supported. When a row in `blocks` is deleted, SQLite's FK engine deletes matching rows in `wikilink_references` by doing a B-tree range delete on `(block_uuid, *)` in the WITHOUT ROWID PK B-tree. This is actually **faster** than a rowid-table FK cascade because the PK is the B-tree root — no secondary index lookup required.

This behavior has been stable since SQLite 3.8.2 (2013). No version-specific caveats apply to SQLite 3.45.

### The one restriction that does NOT apply here

The documented restriction on WITHOUT ROWID + FK only applies when the WITHOUT ROWID table is the **parent (referenced) table**: the child must reference the WITHOUT ROWID table's explicit PK columns, not a rowid (since there is none). `wikilink_references` is the child, not the parent, so this restriction is irrelevant.

### idx_wikilink_refs_page_name must survive the recreation

WITHOUT ROWID cannot be added to an existing table via `ALTER TABLE`. The migration must:
1. `CREATE TABLE wikilink_references_new (...) WITHOUT ROWID`
2. `INSERT INTO wikilink_references_new SELECT * FROM wikilink_references`
3. `DROP TABLE wikilink_references`
4. `ALTER TABLE wikilink_references_new RENAME TO wikilink_references`
5. `CREATE INDEX IF NOT EXISTS idx_wikilink_refs_page_name ON wikilink_references(page_name COLLATE NOCASE)`

If step 5 is omitted, `selectWikilinkPageNamesForPage` degrades to a full scan. `QueryPlanAuditTest` covers `selectWikilinkPageNamesForBlock` and `selectWikilinkPageNamesForBlocks` — both must still pass after migration.

### FTS5 triggers: no interaction

The WITHOUT ROWID change only affects `wikilink_references`. The `blocks_fts` triggers fire on the `blocks` table and are unaffected.

---

## 3. WITH RECURSIVE CTE Depth Limits (R5)

### Default recursion limit: 1000 steps

SQLite limits `WITH RECURSIVE` CTEs to **at most 1000 recursive iterations** by default. This is a compile-time constant (`SQLITE_MAX_EXPR_DEPTH` governs expression tree depth; the CTE recursion limit is tracked separately but defaults to 1000 in the same header). On overflow, SQLite throws an error — it does **not** silently truncate.

`PRAGMA recursive_triggers` is unrelated — it controls whether DML triggers fire recursively, not CTE depth.

### Risk for deep block hierarchies

The recursive CTE for `getBlockHierarchy` accumulates one recursive step per depth level of the tree. A 500-level deep outline (theoretically possible if a user indents aggressively) reaches 500 recursive steps — safely under 1000. A 1000-level outline would hit the limit and throw.

In practice, Logseq and SteleKit UI renders indentation up to a practical visual limit, but nothing prevents programmatic creation of deeply nested structures (import from another app, or bulk indent operations). The current BFS Kotlin loop handles arbitrarily deep trees (bounded only by memory); the CTE query would fail above 1000 levels.

**Recommendation:** Add a depth guard in the CTE: `WHERE level < 1000` in the recursive term, or `LIMIT N` on the final SELECT. If depth is exceeded, fall back to the existing BFS path. Alternatively, document the 1000-level limit as acceptable for the use case.

**Test gap:** No existing test exercises a hierarchy deeper than a few levels. A test with a 50-level or 200-level hierarchy is needed to validate the CTE implementation and confirm it handles the depth limit gracefully.

### SQLite 3.45 (Requery bundled) — confirmed safe

The requirements document states Requery bundles SQLite 3.45. `WITH RECURSIVE` has been stable since SQLite 3.8.3 (2013). The 1000-step default applies.

---

## 4. Two-Connection WAL Pitfalls (R6)

### MVCC snapshot isolation: correct as designed

In WAL mode, a reader sees a consistent snapshot of the database as it existed when the reader's transaction began, regardless of concurrent writes. A write connection using `BEGIN IMMEDIATE` does not block the read connection. This is SQLite's documented MVCC guarantee and is the primary motivation for R6.

### WAL file growth from persistent read connections — serious risk

SQLite can only checkpoint (compact) the WAL file up to the earliest active reader's end mark. If a read connection holds a transaction open continuously (e.g., a SQLDelight `Flow` that never closes), and writes are frequent, the WAL file **grows without bound**. The default auto-checkpoint triggers at 1000 WAL pages (~4MB), but a stuck reader prevents the reset step.

SteleKit uses SQLDelight `asFlow()` collectors that hold query subscriptions open for the lifetime of the graph. These are exactly the long-lived readers that can block checkpointing.

**Mitigation:** Configure `PRAGMA wal_autocheckpoint = 0` (disable auto-checkpoint on the write connection) and run `PRAGMA wal_checkpoint(RESTART)` explicitly during idle (e.g., on app background). Or accept the WAL growth risk and monitor database file sizes.

### Two SQLDelight SqlDriver instances: unsupported by the library

SQLDelight's `SteleDatabase.Schema.create(driver)` runs `CREATE TABLE pages` (without `IF NOT EXISTS`) as its first statement. Called twice on the same database file (once per driver), the second call will throw `SQLITE_ERROR: table already exists`. Whether this error propagates or is swallowed depends on the driver implementation — in the current code it is swallowed by `applyAll`'s error handler, but the SteleKit `MigrationRunner` (not SQLDelight's built-in schema versioning) is used anyway.

**Safe pattern for R6:** Initialize the schema using only the write driver. Open the read driver **after** `MigrationRunner.applyAll()` completes. Wrap both drivers in a single `SqlDriver` facade that routes reads to `readDriver` and writes to `writeDriver`. This façade is passed to `SteleDatabase(facade)` — there is only one `SteleDatabase` instance, not two.

SQLDelight has no documented support for the two-driver pattern. The façade approach is a community-established workaround.

### BEGIN IMMEDIATE on the write connection

In WAL mode, `BEGIN IMMEDIATE` acquires the write lock immediately, preventing concurrent writers. Readers are unaffected. The existing codebase uses `BEGIN IMMEDIATE` for all JVM transactions (commit e3411409d0). In the two-connection model, the write connection should continue using `BEGIN IMMEDIATE`; the read connection should use the default `BEGIN DEFERRED` (read-only transactions).

### SQLITE_BUSY_SNAPSHOT risk

`SQLITE_BUSY_SNAPSHOT` occurs when a read connection that started before a write commits tries to upgrade to a writer. In the strict two-connection split (read driver never writes, write driver never reads), this error cannot occur.

---

## 5. BlockType Sealed Class Migration Risks (R3)

### Existing `validBlockTypes` allowlist in `Block.init`

`Models.kt` validates `blockType` against a fixed set:

```kotlin
private val validBlockTypes = setOf(
    "bullet", "paragraph", "heading", "code_fence", "blockquote",
    "ordered_list_item", "thematic_break", "table", "raw_html",
    "image_annotation"
)
require(blockType in validBlockTypes) { "Invalid blockType: $blockType" }
```

Any unknown `block_type` string from the database will throw `IllegalArgumentException` in `Block.init` during `toModel()`. This is the existing behavior and is already safe (unknown types crash before they reach the UI). The R3 migration to a `BlockType` sealed class must preserve this: `fromString()` for unknown values should return a `BlockType.Unknown(raw: String)` variant that renders as a bullet, not throw.

**Critical gap:** `ParsedModels.BlockType` (the parser's sealed class) does NOT include `ImageAnnotation`. `BlockTypes.IMAGE_ANNOTATION = "image_annotation"` exists as a string constant, and `image_annotation` is in `validBlockTypes`, but the sealed class in `ParsedModels.kt` has no corresponding variant. When R3 unifies the domain model's `blockType: String` with the sealed class, `image_annotation` blocks must be represented — either as a new `BlockType.ImageAnnotation` variant or as `BlockType.Unknown("image_annotation")`. The former is strongly preferred since `ImageAnnotation` is a first-class entity.

### Sealed class exhaustiveness: breaking changes on new variants

If `BlockType` becomes a sealed class in the domain model, all `when (block.blockType)` expressions that are currently string comparisons will become exhaustive `when` expressions. Adding a new `BlockType` variant in the future will require adding a branch to every exhaustive `when` — this is a feature (compile-time exhaustiveness), but it is a source-breaking API change.

**Recommendation:** Add `BlockType.Unknown(raw: String)` as a catch-all variant in the sealed class. `fromString()` maps any unrecognized string to `Unknown`. New variants can be added incrementally without breaking the `Unknown` fallback branch in existing `when` expressions.

### Column mapping at repository boundary

The DB column `block_type TEXT` remains a string. The mapping happens in `toModel()` (DB row → `Block`). The `fromString()` function must be called there. The reverse mapping (for writes) in `insertBlock` / `updateBlockForSave` must call `blockType.toDiscriminatorString()`. `BlockTypeMapper.kt` already exists with `toDiscriminatorString()` — it must be extended with a `fromString()` counterpart.

### No migration needed (column stays TEXT)

R3 requires no schema migration — the `block_type TEXT` column stays as-is. The change is purely at the Kotlin model boundary.

---

## 6. Multi-Row INSERT Pitfalls (R4)

### SQLDelight 2.x does not generate multi-row INSERT

The SQLDelight code generator emits one Kotlin function per named query. There is no `.sq` syntax for parameterized multi-row VALUES. The workaround is either:

1. **Transaction loop (current approach):** `for (name in pageNames) restricted.insertWikilinkReference(blockUuid, name)` — N separate INSERT statements inside a single `transaction { }`. SQLite defers B-tree page splits and lock acquisition until commit, making this substantially faster than N autocommit inserts, but still N function calls and N SQLite statement preparations.

2. **Dynamic SQL via `driver.execute()`:** Build the SQL string with `(?, ?), (?, ?), ...` tuples, call `driver.execute(null, sql, paramCount)` with manually bound parameters. Bypasses the `RestrictedDatabaseQueries` gating layer — the call site must carry `@OptIn(DirectSqlWrite::class)` and route through `DatabaseWriteActor`.

### SQLITE_MAX_VARIABLE_NUMBER on Android with Requery

Requery's `sqlite-android` library bundles its own SQLite (3.45 as noted in requirements). **Critical: Requery compiles its bundled SQLite with `SQLITE_MAX_VARIABLE_NUMBER = 999`**, bypassing the system SQLite entirely. This means the 999 limit applies on all Android versions when using Requery, not just API < 30.

For the multi-row INSERT workaround:
- 2 parameters per row (block_uuid, page_name)
- Maximum safe rows per INSERT: `floor(999 / 2) = 499 rows`
- A block referencing more than 499 pages in its wikilinks would exceed the limit — pathological but possible in bulk-imported notes with many links

The existing `IN ?` chunk size of ≤500 UUIDs is correct for the 1-parameter-per-UUID case (500 < 999). However, any CTE anchor clause that combines multiple bind parameters with `IN ?` reduces the available slots.

### INSERT OR IGNORE requirement

R4 requires `INSERT OR IGNORE` (not `INSERT OR REPLACE`). `INSERT OR REPLACE` silently deletes then re-inserts, firing the `blocks_ad` and `blocks_ai` FTS5 triggers on the `blocks` table for the referenced rows — O(n) FTS updates for n existing wikilinks even when nothing changed. `INSERT OR IGNORE` on a row that already exists is a true no-op at the SQLite level.

### Test gap

The existing test suite does not verify the count of INSERT statements emitted by `addWikilinkRefs`. A test that installs a statement-counting SQLite trace listener and asserts `INSERT count == 1` for a 5-wikilink block save would enforce the R4 acceptance criterion.

---

## 7. Cross-Cutting: Android SQLITE_MAX_VARIABLE_NUMBER Summary

| Context | Limit | Chunk size required |
|---|---|---|
| `IN ?` with UUIDs (1 param each) | 999 (Requery) | ≤ 500 ✓ (existing) |
| Multi-row INSERT wikilinks (2 params each) | 999 (Requery) | ≤ 499 rows |
| WITH RECURSIVE CTE anchor `WHERE uuid = ?` | 1 param | No chunking needed |
| `selectWikilinkPageNamesForBlocks IN ?` | 999 (Requery) | ≤ 500 ✓ (existing) |

The Requery SQLITE_MAX_VARIABLE_NUMBER issue (Requery sqlite-android issue #124) is open with no fix. Treat 999 as the permanent effective limit on Android regardless of API level.

---

## Existing Test Coverage Map

| Risk | Covered by existing test? | Gap |
|---|---|---|
| Float position collision | No | Need: midpoint-exhaustion unit test + rebalance test |
| WITHOUT ROWID FK cascade | No | Need: migration integration test asserting cascade still fires |
| CTE depth > 1000 levels | No | Need: deep hierarchy test (50+ levels minimum) |
| WAL file growth (persistent reader) | No | Acceptable risk — operational concern, not a correctness regression |
| blockType Unknown fallback | No | Need: `fromString("unknown_plugin_type")` → `Unknown` (not crash) |
| Multi-row INSERT count | No | Need: SQL trace test asserting 1 INSERT for N wikilinks |
| Wikilink chunk ≤ 499 rows | Partially (≤500 for IN ?) | Need: explicit test for multi-row INSERT chunking |
| WITHOUT ROWID migration correctness | Yes (MigrationRunnerSchemaSyncTest) | Will catch missing index; add integration assertion |
| QueryPlanAuditTest post-migration | Yes (existing test) | Will catch new full scans if index is dropped |
