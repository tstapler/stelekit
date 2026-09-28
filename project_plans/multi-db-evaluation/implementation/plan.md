# Multi-DB Evaluation — Implementation Plan

## Decision Summary

libsql is the correct and only viable embedded database engine for SteleKit's four-platform KMP
requirement today. All 4 research streams independently confirmed that no evaluated alternative
(LadybugDB, Limbo/Turso, DuckDB, ClickHouse/chDB, LevelDB/RocksDB) supports JVM, Android, iOS,
and Kotlin/WASM simultaneously, and all fail on at least one of: platform coverage, SQLite dialect
compatibility, FTS5 support, or OLTP fitness for interactive block editing. The `stelekit-libsql`
branch (PR #171) should be merged as-is; no new engine is needed.

This plan delivers three things: (1) an Architecture Decision Record documenting the evaluation
outcome so future contributors do not re-litigate it, (2) explicit verification that the existing
libsql JNI driver correctly handles the FTS5 virtual tables that are already in `SteleDatabase.sq`
(both `blocks_fts` and `pages_fts` with `bm25`, `highlight`, and `MATCH` queries), and (3) a
tracked watchlist item for Limbo/Turso so the solo developer knows exactly when to reassess.

---

## Recommendation Matrix

| Engine | JVM Desktop | Android | iOS | WASM/Kotlin | FTS5 | SQLDelight | OLTP Fit | Verdict |
|---|---|---|---|---|---|---|---|---|
| **libsql** (current) | Yes — JNI | Yes — JNI | Yes — SQLiter | Yes — OPFS | Yes (built-in) | Yes | Excellent | **Keep / Merge** |
| Turso (Limbo) | Beta JDBC | No | No | No | Experimental | In theory | Good | Disqualified now — **reassess at 1.0** |
| LadybugDB | Yes (Maven) | No (NDK only) | No (no KMP) | JS-only | Extension only | No (Cypher) | Poor (columnar) | **Disqualified** |
| DuckDB | Yes — JDBC | Experimental | No | JS-only | Extension only | No | Poor (OLAP) | **Disqualified** |
| ClickHouse/chDB | No JVM binding | No | No | No | No (LIKE only) | No | Hostile | **Disqualified** |
| LevelDB / RocksDB | Yes (JNI) | Yes | No (C++ only) | No | None | No (KV store) | No SQL at all | **Disqualified** |
| Realm Kotlin | Yes | Yes | Yes (K/N) | No | None | No | Object model | **Disqualified** |

---

## Epics

---

### Epic 1: Document the evaluation decision (ADR)

**Goal**: Write and merge an ADR so future contributors understand why libsql was chosen over
all evaluated alternatives, what the platform constraints are, and when to reconsider.

**Why this is first**: Without this artifact, the evaluation work lives only in `project_plans/`
and can be missed by any future contributor or by the developer revisiting the decision in 12 months.

#### Story 1.1 — Review and patch ADR-006: Embedded Database Engine Selection

`docs/adr/ADR-006-embedded-database-engine-selection.md` was written during this planning
phase. Review it for factual accuracy and patch any issues before committing.

**Tasks:**

1. **Verify FTS5 counts**: Confirm the ADR's FTS5 claims match `SteleDatabase.sq` exactly.
   Verified counts: 2 virtual tables (`blocks_fts`, `pages_fts`), 6 triggers, **6** named
   FTS queries (not 8). Auxiliary functions used: `bm25()` and `highlight()` only — `snippet()`
   is NOT used in the schema.

2. **Verify MVCC framing**: The ADR should describe runtime detection via
   `LibsqlJni.isDatabaseMvccEnabled(dbHandle)` rather than stating "server-mode only" as a
   static fact — the driver branches at runtime so future local-mode MVCC support would be
   picked up automatically.

3. **Review remaining sections** for accuracy:
   - All sections listed in ADR-006 are present and accurate
   - FTS query count reads "6 named FTS queries" (not 8)
   - MVCC description references runtime detection via `isDatabaseMvccEnabled()`

3. **Wire the ADR into the ADR index** (if one exists at `docs/adr/README.md` or similar).

**Acceptance criteria**: ADR is committed on the branch, references PR #171, and contains all
sections listed above.

---

### Epic 2: Verify and complete libsql FTS5 support

**Goal**: Confirm that the existing libsql JNI driver correctly handles FTS5 virtual table DDL,
`MATCH`-based queries, and the `bm25()` / `highlight()` auxiliary functions that
`SteleDatabase.sq` uses. `SteleDatabase.sq` has 6 FTS-related named queries and 6 FTS5 triggers
that all run against the libsql JNI bridge on JVM and Android. (`snippet()` is NOT used in the
schema — only `bm25()` and `highlight()`.)

**Why this is needed**: The architecture research confirmed that libsql bundles SQLite with FTS5
compiled in. However, FTS5 virtual tables are created via `CREATE VIRTUAL TABLE ... USING fts5()`
which is DDL that flows through the same `SqlDriver.execute()` path as all other SQL. The JNI
driver must pass these through to libsql's embedded FTS5 module without modification. An explicit
test verifying this end-to-end is missing; currently the business tests exercise the search
repository but may not confirm the full FTS5 pipeline through the JNI layer.

#### Story 2.1 — Audit FTS5 DDL and query coverage in existing tests

**Tasks:**

1. **Grep existing tests for FTS assertions**: Search `kmp/src/jvmTest/` and `kmp/src/businessTest/`
   for tests that exercise `searchBlocks`, `searchPages`, or `fullTextSearch`. Determine whether
   any test actually exercises `bm25()` score ordering, `highlight()` output, or `MATCH` against
   a non-trivial corpus (not just an empty database).

2. **Identify gaps**: If the search tests use in-memory repositories (which bypass the JNI driver
   and use SQLDelight's `JdbcSqliteDriver`), they do not validate the JNI FTS5 path. Note which
   tests exercise the real driver vs. the in-memory stub.

3. **Record audit result**: Annotate this plan section with the finding: either "FTS5 tested via
   JNI — no additional tests needed" or "FTS5 only tested via in-memory driver — Story 2.2 required."

#### Story 2.2 — Add integration test for FTS5 via libsql JNI (if gap found in 2.1)

Only execute this story if the audit in 2.1 finds that FTS5 is not tested through the JNI bridge.

**Tasks:**

1. **Add a `jvmTest` integration test class** (e.g., `LibsqlFts5IntegrationTest`) that:
   - Creates a real `JvmLibsqlDriver` against a temp-file database (not in-memory)
   - Runs schema creation via `SteleDatabase.Schema.create(driver)` followed by
     `MigrationRunner.applyAll(queries)` to ensure `blocks_fts` and `pages_fts` are present
   - Inserts 5–10 blocks with known content containing search terms
   - Executes `searchBlocksByText` and `searchBlocksByTextInPage` queries
   - Asserts: (a) results are returned in BM25 order (most-relevant first), (b) `highlight`
     column contains `<em>` tags around matched terms, (c) queries with no match return empty list
   - Inserts a page and asserts `searchPagesByName` returns it with BM25 score

2. **Verify FTS5 triggers work through the JNI driver**: In the same test class, confirm that
   after updating a block's content via `upsertBlock`, the FTS index is updated so the new
   content is findable and the old content is no longer returned.

3. **Run test locally**: `./gradlew jvmTest --tests "dev.stapler.stelekit.db.libsql.LibsqlFts5IntegrationTest"`
   to confirm green.

**Acceptance criteria**: At least one test exercises the full FTS5 pipeline through the libsql JNI
bridge (create virtual table DDL → trigger execution → `MATCH` query with `bm25()`/`highlight()`).

---

### Epic 3: DuckDB JVM analytics sidecar — DEFERRED

**Status: DEFERRED indefinitely.** This epic is documented here to record the decision, not to
schedule work.

**Rationale for deferral**:
- SteleKit's current analytics queries (`COUNT(*)`, `ORDER BY updated_at DESC`, date-range
  histograms) all execute in under 5ms on a 10,000-page libsql database in WAL mode. There is
  no measured performance problem that DuckDB would solve.
- DuckDB's Android support is explicitly labeled "experimental" by the DuckDB team with no stable
  AAR on Maven Central. Adding it would create a JVM-only code path that diverges from Android,
  iOS, and WASM — violating the principle of consistent behavior across platforms.
- The dual-engine integration pattern (shadow writes or DuckDB's SQLite extension ATTACH) adds
  ~300 LOC of sync complexity to `DatabaseWriteActor` with no user-visible benefit today.

**Reassessment trigger**: If SteleKit adds cross-graph analytical queries over 50,000+ pages
(e.g., "show all blocks mentioning X across all my graphs, faceted by month"), and if DuckDB
publishes a stable Maven Central artifact for Android, then re-open this epic with a 2-day spike:
- Wrap `duckdb_jdbc` as a JVM-only `SqlDriver` (~50 LOC via `asJdbcDriver()`)
- Use DuckDB's SQLite extension to `ATTACH` the libsql `.db` file directly (no sync code)
- Measure query latency improvement vs. libsql for the specific slow query
- Only proceed if latency improvement is ≥10× and the Android gap has been closed

---

### Epic 4: Limbo/Turso future-roadmap tracking

**Goal**: Ensure the developer re-evaluates Limbo (now called Turso in the upstream repo) when
it matures, rather than re-running the full research from scratch.

#### Story 4.1 — Create a tracking Markdown note in project plans

**Tasks:**

1. **Write `project_plans/multi-db-evaluation/research/limbo-watchlist.md`** containing:
   - Current status as of evaluation date (2026-06-19): beta, FTS5 experimental, no Android/iOS
     published artifacts, JDBC available at `bindings/java` but not on Maven Central as stable
   - **Blockers to clear before adoption is possible** (all must be true simultaneously):
     - Stable 1.0 release with explicit "FTS5 stable" in release notes (not just planned)
     - Android ABI artifacts (`arm64-v8a`, `x86_64`) published to Maven Central or Turso's artifact
       registry as a stable release
     - iOS Kotlin/Native cinterop path documented and tested (or a published `.xcframework`)
     - `COMPAT.md` (https://github.com/tursodatabase/turso/blob/main/COMPAT.md) confirms FTS5
       virtual table syntax is fully compatible with SQLite FTS5 (including `bm25()`, `highlight()`,
       and all three trigger forms used by `SteleDatabase.sq` — `snippet()` is not required)
   - **Migration path when all blockers clear** (estimated 1 week of work):
     - Replace `LibsqlJni.kt` Rust JNI bridge with a Turso JDBC wrapper (`asJdbcDriver()`, ~50 LOC)
     - Replace `JvmLibsqlDriver` and `AndroidLibsqlDriver` factory calls
     - The schema, migrations, and queries in `SteleDatabase.sq` require no changes (same SQLite
       dialect and file format)
     - Run full test suite, benchmark write throughput vs. libsql baseline

2. **Add a calendar reminder or issue** (whichever the developer uses for tracking) to re-check
   the Turso GitHub releases page in approximately 6 months (target: 2026-12-19).

**Acceptance criteria**: The watchlist file is committed, searchable in the repo, and contains
the four specific blockers listed above so a future reassessment can be done with a checklist
rather than a full research cycle.

---

## Not Doing (and Why)

| Engine | Reason |
|---|---|
| LadybugDB | No Android support (NDK only, no prebuilt artifacts), no Kotlin/Native iOS path, Cypher query language incompatible with SQLDelight's SQL code generation. Disqualified on platform support alone; even if platforms existed, migrating 1,223 lines of `.sq` to Cypher is a multi-month rewrite. |
| DuckDB (primary) | No iOS support (no official arm64-apple-ios build), Android explicitly "experimental" with no stable Maven artifact, OLAP columnar write path is 4–8× slower than SQLite for single-row UPDATE workloads. SteleKit's debounced 500ms block writes are exactly the workload DuckDB handles worst. |
| ClickHouse / chDB | No JVM binding, no Android binding, no iOS binding, no WASM path. Append-only columnar model is fundamentally incompatible with interactive OLTP editing. Disqualified before any feature comparison. |
| LevelDB / RocksDB | No SQL layer at all. Adopting either would require implementing FTS, joins, recursive block hierarchy traversal, and BM25 ranking in application code — equivalent to writing a new database engine on top of a storage engine. No iOS or WASM support. |
| Realm Kotlin | No WASM support (deal-breaker for the web target). Project is in reduced-maintenance community state after MongoDB deprecated Atlas Device Sync. API is object-model, incompatible with SQLDelight. |
| sqlite-jdbc (xerial) | Already replaced by libsql on this branch. No MVCC, lower write throughput. Regressing to this would be strictly worse. |

---

## Success Criteria

The evaluation is complete and PR #171 is ready to merge when all of the following are true:

1. **ADR reviewed and committed** (Epic 1): `docs/adr/ADR-006-embedded-database-engine-selection.md`
   exists, status is "Accepted", FTS query count is accurate (6), MVCC framing describes runtime
   detection, and it covers all evaluated engines with rationale for each verdict.

2. **FTS5 coverage confirmed** (Epic 2, Story 2.1): The audit either confirms that existing tests
   exercise FTS5 via the libsql JNI bridge, or (if a gap is found) Story 2.2's integration test
   has been written, passes, and is committed.

3. **Limbo watchlist documented** (Epic 4, Story 4.1): `limbo-watchlist.md` is committed with
   the four concrete blockers and estimated migration effort.

4. **No new engine is added**: The PR contains the libsql JNI driver and its associated CI
   workflow; no additional database engine code is introduced.

5. **CI passes**: `./gradlew ciCheck` green on the branch including any new jvmTest tests from
   Epic 2 Story 2.2 (if written).

---

## Appendix: Platform Driver Matrix (Final)

| Platform | Driver | Engine | FTS5 | MVCC |
|---|---|---|---|---|
| JVM Desktop | `JvmLibsqlDriver` → `LibsqlDriverCore` → JNI | libsql embedded | Yes | BEGIN CONCURRENT (server-mode only; local-file uses single-writer WAL — sufficient because `DatabaseWriteActor` already serializes writes) |
| Android | `AndroidLibsqlDriver` → `LibsqlDriverCore` → JNI | libsql embedded | Yes | Same as JVM |
| iOS | `NativeSqliteDriver` (SQLDelight) + SQLiter | System SQLite | Yes | SQLite WAL — single writer |
| WASM/Web | `WasmOpfsSqlDriver` (sqlite-wasm + OPFS) | sqlite-wasm | Yes | Single-threaded, no MVCC needed |
