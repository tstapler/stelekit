# Research: Block Position Ordering Approaches

## 1. Logseq's Block Ordering

Logseq has gone through three distinct eras for block ordering, and SteleKit's current schema reflects the middle era that Logseq has since abandoned:

**Era 1 (early):** `:block/children` cardinality/many refs — no explicit ordering at all.

**Era 2 (middle — where SteleKit's `left_uuid` comes from):** `:block/left` UUID ref pointing to the previous sibling (linked list). This is the era from which SteleKit derived its `left_uuid` column.

**Era 3 (current, both file-based and DB version):** `:block/order` — a fractional index string. The linked list is completely gone from both `deps/db/src/logseq/db/frontend/schema.cljs` (DB version) and `deps/db/src/logseq/db/file_based/schema.cljs` (file-based). Children are queried by `[:block/parent this-block-uuid]` and sorted by `:block/order`. Their DB outliner core (`deps/outliner/src/logseq/outliner/core.cljs`) documents `:block/order` explicitly as "fractional index string for ordering siblings."

**Roam Research** uses a dense integer `:block/order` (0-based). Inserts renumber all subsequent siblings. No linked list.

**Obsidian** has no database at all. Block ordering = byte position in the `.md` file.

## 2. Fractional Indexing Approaches

### Pure float midpoint (`REAL`)

Starting range [0.0, 1.0]. Insert between a and b: `(a + b) / 2.0`. IEEE 754 double has 52 mantissa bits; after ~52 consecutive inserts in the same gap, `(a + b) / 2 == a` and positions collide silently. vlcn.io confirmed empirically: 53 inserts between the same two positions causes collapse. The failure is silent — two blocks get equal position values and sort order becomes undefined. SQL: `ORDER BY position ASC` on a `REAL` column works natively.

**Verdict: Not safe. Silent failure mode with no detectable error.**

### Large-gap integers

Space initial items at multiples of G (e.g. 65536). Insert midpoint = `(a + b) / 2` (integer division). After `log2(G)` inserts in the same gap, gap reaches 1 and the next insert is impossible. With G=65536: 16 inserts per slot before exhaustion. With G=2^20: 20. Exhaustion is detectable (`abs(b - a) == 1`). Rebalance is per-parent only — O(sibling count), not O(total blocks). For typical outliner editing (users rarely insert into the same sibling slot 16 times), rebalance essentially never triggers.

**Verdict: Solid pragmatic choice. Simple, no dependencies, detectable failure, rare rebalance.**

### String-based lexicographic fractional indexing (`rocicorp/fractional-indexing`)

Variable-length base-62 strings that sort correctly under SQLite's default `BINARY` collation. The reference implementation is `rocicorp/fractional-indexing` (CC0), based on Evan Wallace's Figma engineering work. **This is what Figma ships in production.**

Example values:
```
generateKeyBetween(null, null)   → "a0"    // first item
generateKeyBetween("a0", null)   → "a1"    // append
generateKeyBetween("a0", "a1")   → "a0V"   // midpoint
generateKeyBetween("a0V", "a1")  → "a0l"
```

String length grows logarithmically (base 62) — practically unbounded. 10,000 inserts in the same slot yields keys of only 3–4 characters. No rebalance ever. SQLite's default `BINARY` text collation sorts base-62 keys correctly with no configuration. **Do not use `COLLATE NOCASE`** — it breaks the sort invariant.

SQL schema:
```sql
-- Column rename: position TEXT NOT NULL (was INTEGER)
CREATE INDEX idx_blocks_parent_position ON blocks(parent_uuid, position);
SELECT * FROM blocks WHERE parent_uuid = ? ORDER BY position ASC;
-- Root blocks:
SELECT * FROM blocks WHERE parent_uuid IS NULL AND page_uuid = ? ORDER BY position ASC;
```

KMP library: `com.davidarvelo:fractional-indexing` on Maven Central — Kotlin Multiplatform, `commonMain`-compatible, byte-for-byte compatible with the JS reference.

**Verdict: Production-grade. Same choice as Logseq DB version. Never rebalances.**

### LexoRank (Jira)

Three-bucket prefix system (`0|…`, `1|…`, `2|…`) enabling non-blocking background rebalance by migrating between buckets. Massively more complex than fractional-indexing strings. Buys nothing for SteleKit — the background migration machinery exists to handle zero-downtime rebalance at Jira's millions-of-issues scale.

**Verdict: Overkill. Do not use.**

### LSEQ / RGA (CRDTs — Yjs, Automerge)

Character-level interleave prevention for concurrent text editing. Requires tombstones for deletions. Cannot use `ORDER BY` directly — logical order must be reconstructed from the CRDT DAG in application code on every read, or materialized on write. Poor JVM library ecosystem.

**Verdict: Wrong tool. Block-level ordering in an outliner does not need CRDT guarantees.**

## 3. Roam / Obsidian Block Ordering

**Roam:** Dense integer `:block/order`. Insert in the middle = renumber all subsequent siblings in Kotlin/JS before write. No linked list; no linked list traversal. Children are fetched via `:block/children` refs and sorted numerically.

**Obsidian:** No database. Block ordering = line order in `.md` file. No position column exists.

## 4. SQLite-Specific Patterns

**REAL column bisection depth:** Starting from `[0, 1]`, the hard limit is 52 bisections before gaps become indistinguishable. Extended range `[0, 2^32]` adds 32 more levels = ~84 total — but rounding errors compound with non-power-of-2 boundaries. Not recommended due to silent failure.

**TEXT column with `BINARY` collation:** SQLite's default `BINARY` collation is `memcmp` byte-by-byte. Base-62 characters (0-9 = `0x30–0x39`, A-Z = `0x41–0x5A`, a-z = `0x61–0x7A`) are monotonically increasing in ASCII, so `ORDER BY position` works correctly for base-62 fractional index strings with zero configuration. No custom collation required.

**Integer gap rebalance query (SQLite 3.25+, confirmed safe with bundled 3.45):**
```sql
WITH ordered AS (
    SELECT uuid, ROW_NUMBER() OVER (ORDER BY position) AS rn
    FROM blocks WHERE parent_uuid = ?
)
UPDATE blocks
SET position = (SELECT rn * 65536 FROM ordered WHERE ordered.uuid = blocks.uuid)
WHERE parent_uuid = ?;
```

**Recursive CTE for `left_uuid` traversal:**
```sql
WITH RECURSIVE ordered_blocks(uuid, content, depth) AS (
    SELECT uuid, content, 0
    FROM blocks WHERE parent_uuid = ? AND left_uuid IS NULL
    UNION ALL
    SELECT b.uuid, b.content, ob.depth + 1
    FROM blocks b
    JOIN ordered_blocks ob ON b.left_uuid = ob.uuid
    WHERE b.parent_uuid = ?
)
SELECT uuid, content FROM ordered_blocks ORDER BY depth;
```

This requires `ORDER BY depth` — SQLite does not guarantee CTE row order otherwise. vlcn.io benchmarks show linked-list CTE traversal is ~2x slower than `ORDER BY position` on an indexed column for the same N.

## 5. `left_uuid` Alone vs. `position` — SQLite Tradeoffs

**`left_uuid` alone (no `position` column):**

- Cannot use `ORDER BY` — SQLite has no native linked-list sort
- Every ordered read requires the recursive CTE above
- CTE binds `parent_uuid` twice (once for anchor, once for recursive step)
- Performance: ~2x slower than indexed `ORDER BY position` per vlcn.io benchmarks
- Current queries like `selectBlocksByParentUuidOrdered`, `selectRootBlocks`, `selectBlockChildren` all use `ORDER BY position` — all would need rewriting to use the CTE
- The `idx_blocks_parent_position` and `idx_blocks_page_position` indexes become useless

**`position` alone (no `left_uuid`):**

- Loses Logseq file format fidelity — `.md` files reference `:block/left` UUID for ordering
- Breaks round-trip compatibility with Logseq graph files

**Hybrid (keep `left_uuid` + migrate `position` to fractional index string):**

- `left_uuid` maintained for file serialization fidelity (Logseq `.md` format uses it)
- `position TEXT` (was `INTEGER`) used for all SQL ordering — `ORDER BY position` continues to work with zero query changes
- On file parse: traverse `left_uuid` chain once per parent, assign `position` values using `generateKeyBetween` — O(N) one-time per page load
- On block insert/move: update two `left_uuid` pointers AND compute new `position` as midpoint of neighbors — all in one transaction, O(1)
- On reads: `ORDER BY position ASC` via existing index, no changes to SQL queries

**`left_uuid` alone cannot replace `position`** without rewriting every ordered query to use the recursive CTE and accepting the ~2x read slowdown. The hybrid approach (keep both, migrate `position INTEGER` → `position TEXT` fractional) is the correct path and matches what Logseq DB version does (they dropped the linked list for SQL and use `ORDER BY :block/order`).

---

## Summary

**What comparable tools use:**

Logseq DB version (the reference for SteleKit) uses a fractional index string (`:block/order`) for all block ordering — no linked list. They explicitly dropped `:block/left` when building the DB version because `ORDER BY order_key` is simpler and faster than linked-list CTE traversal. Figma uses the same approach (`rocicorp/fractional-indexing`). Roam uses dense integers and accepts O(n) renumbering. Obsidian has no DB.

**Recommended scheme for SteleKit:**

Migrate `position INTEGER` → `position TEXT NOT NULL` using the `rocicorp/fractional-indexing` algorithm (KMP port: `com.davidarvelo:fractional-indexing`, Maven Central, `commonMain`-compatible). Existing SQL queries (`ORDER BY position`) and indexes (`idx_blocks_parent_position`, `idx_blocks_page_position`) require zero changes — the column rename is the only schema migration needed, plus a one-time data migration to convert integer positions to base-62 strings in the same relative order. Keep `left_uuid` for file format fidelity. No rebalance mechanism ever needed. This is the same decision Logseq made.

If the Maven Central dependency is undesirable, integer gaps with G=2^20 (20 levels before rebalance, detectable at insert time, O(sibling count) rebalance per parent) is a viable self-contained alternative with zero dependencies.

**`left_uuid` alone cannot replace `position`:**

No. `left_uuid` alone requires a `WITH RECURSIVE` CTE for every ordered read, is ~2x slower than `ORDER BY position` on indexed data per vlcn.io benchmarks, and would require rewriting all ordered queries (`selectBlockChildren`, `selectRootBlocks`, `selectBlocksByParentUuidOrdered`, etc.). The hybrid of `left_uuid` (file fidelity) + `position TEXT` (SQL ordering) is the correct architecture — which is exactly the structure SteleKit already has, minus the fractional indexing migration.
