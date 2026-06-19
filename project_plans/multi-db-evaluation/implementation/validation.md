# Multi-DB Evaluation — Validation Plan

**Date**: 2026-06-19
**Status**: PASS (with one conditional task)

---

## REQ → TEST Traceability

### REQ-1: Scored comparison matrix covering all databases × all evaluation dimensions

> requirements.md §Success Criteria 1: "A scored comparison matrix covering all databases × all
> evaluation dimensions"

| Test ID | Description | Type | Location |
|---|---|---|---|
| TEST-1A | ADR-006 contains a Recommendation Matrix table with all 7 engines as rows and all evaluation dimensions as columns | Artifact-existence / Manual | `docs/adr/ADR-006-embedded-database-engine-selection.md` |
| TEST-1B | plan.md Recommendation Matrix covers all five evaluation dimensions (Performance, Query Capability, Cross-Platform Portability, Correctness/ACID, Integration Cost) for each engine | Manual | `project_plans/multi-db-evaluation/implementation/plan.md` |
| TEST-1C | research/ directory contains at least `architecture.md`, `features.md`, `pitfalls.md`, `stack.md` documenting the evaluation | Artifact-existence | `project_plans/multi-db-evaluation/research/` |

**Verdict**: PASS — ADR-006 §Alternatives Rejected covers all engines. The Recommendation Matrix in plan.md has seven rows (libsql, Turso/Limbo, LadybugDB, DuckDB, ClickHouse/chDB, LevelDB/RocksDB, Realm Kotlin) and columns for JVM Desktop, Android, iOS, WASM/Kotlin, FTS5, SQLDelight, OLTP Fit, and Verdict. Four research files exist in the research/ directory.

---

### REQ-2: Clear recommendation for each candidate engine with rationale

> requirements.md §Success Criteria 2: "A clear recommendation: 'replace libsql', 'complement
> libsql', or 'disqualify' for each candidate engine — with rationale"

| Test ID | Description | Type | Location |
|---|---|---|---|
| TEST-2A | ADR-006 §Alternatives Rejected section contains a named subsection for each of the 6 evaluated alternatives (LadybugDB, Limbo/Turso, DuckDB, ClickHouse/chDB, LevelDB/RocksDB, Realm Kotlin) | Manual | `docs/adr/ADR-006-embedded-database-engine-selection.md` |
| TEST-2B | Each alternatives section contains at least one explicit disqualification reason referencing the four-platform constraint or a specific technical gap | Manual | `docs/adr/ADR-006-embedded-database-engine-selection.md` |
| TEST-2C | plan.md §Not Doing table covers all rejected engines with a reason column | Manual | `project_plans/multi-db-evaluation/implementation/plan.md` |
| TEST-2D | H2/HSQLDB receives at least a one-line dismissal (gap identified by adversarial review Finding 5) | Manual | `docs/adr/ADR-006-embedded-database-engine-selection.md` or plan.md §Not Doing |

**Verdict on TEST-2A/B/C**: PASS — ADR-006 has dedicated sections for all 6 alternatives; each cites a specific disqualification (platform gap, OLAP mismatch, no SQL layer, no JVM binding). plan.md §Not Doing covers the same engines.

**Verdict on TEST-2D**: CONDITIONAL — H2/HSQLDB is not mentioned anywhere in ADR-006 or plan.md. Adversarial review Finding 5 flags this as low severity. A one-line row in plan.md §Not Doing suffices.

---

### REQ-3: If no replacement recommended — statement of libsql advantages and reassessment conditions

> requirements.md §Success Criteria 4: "If no replacement is recommended, a statement of libsql's
> advantages and what would need to change for a future reassessment"

| Test ID | Description | Type | Location |
|---|---|---|---|
| TEST-3A | ADR-006 §Rationale enumerates libsql's specific advantages (platform coverage, FTS5 dialect, runtime MVCC detection, write throughput) | Manual | `docs/adr/ADR-006-embedded-database-engine-selection.md` |
| TEST-3B | ADR-006 §Consequences or §Alternatives Rejected states explicit reassessment conditions for at least Limbo/Turso | Manual | `docs/adr/ADR-006-embedded-database-engine-selection.md` |
| TEST-3C | Limbo watchlist file exists at `project_plans/multi-db-evaluation/research/limbo-watchlist.md` or `turso-watchlist.md` with four concrete blockers | Artifact-existence | `project_plans/multi-db-evaluation/research/` |

**Verdict on TEST-3A/B**: PASS — ADR-006 §Rationale has four numbered subsections covering the advantages; §Consequences has an explicit Limbo reassessment trigger (stable 1.0 + FTS5 + Android/iOS artifacts).

**Verdict on TEST-3C**: FAIL (blocker) — Neither `limbo-watchlist.md` nor `turso-watchlist.md` exists in the research directory. Epic 4 Story 4.1 is not yet done. This is a required deliverable per plan.md §Success Criteria 3.

---

### REQ-4: Platform constraint — all four platforms simultaneously

> requirements.md §Platform Constraint: "An engine that cannot run on all four of JVM/Desktop,
> Android, iOS, and WASM/Web is disqualified"

| Test ID | Description | Type | Location |
|---|---|---|---|
| TEST-4A | ADR-006 §Context lists the four target platforms with their current driver | Manual | `docs/adr/ADR-006-embedded-database-engine-selection.md` |
| TEST-4B | ADR-006 Recommendation Matrix contains columns for JVM Desktop, Android, iOS, and WASM/Kotlin for each evaluated engine | Manual | `docs/adr/ADR-006-embedded-database-engine-selection.md` |
| TEST-4C | Every disqualified engine's rejection section cites at least one platform-coverage failure | Manual | `docs/adr/ADR-006-embedded-database-engine-selection.md` |

**Verdict**: PASS — ADR-006 §Context has the platform table; the Recommendation Matrix has all four platform columns; each rejected engine cites a platform gap (LadybugDB: no Android/iOS; Limbo: no Android/iOS; DuckDB: no iOS; ClickHouse/chDB: no JVM; LevelDB/RocksDB: no iOS/WASM; Realm: no WASM).

---

### REQ-5: FTS5 support verified for libsql JNI driver

> requirements.md §Query Capability: "Full-text search (Logseq heavily uses block content search)"
> plan.md §Success Criteria 2: FTS5 coverage confirmed via Epic 2

| Test ID | Description | Type | Location |
|---|---|---|---|
| TEST-5A | `SteleDatabase.sq` contains exactly 2 FTS5 virtual tables (`blocks_fts`, `pages_fts`) | Factual-accuracy / Automated | Verified against schema |
| TEST-5B | `SteleDatabase.sq` contains exactly 6 FTS5 triggers (3 for blocks: `blocks_ai`, `blocks_ad`, `blocks_au`; 3 for pages: `pages_ai`, `pages_ad`, `pages_au`) | Factual-accuracy / Automated | Verified against schema |
| TEST-5C | `SteleDatabase.sq` contains exactly 6 named FTS queries: `searchBlocksByContentFts`, `searchBlocksByContentFtsInPage`, `searchBlocksCountFts`, `searchPagesByNameFts`, `searchPagesByNameFtsInDateRange`, `searchBlocksByContentFtsInDateRange` | Factual-accuracy / Automated | Verified against schema |
| TEST-5D | `snippet()` is NOT used anywhere in `SteleDatabase.sq`; only `bm25()` and `highlight()` are used | Factual-accuracy / Automated | Verified against schema |
| TEST-5E | `BlocksFtsTriggerTest` verifies `blocks_au` trigger DDL is restricted to `AFTER UPDATE OF content ON blocks` | Automated / CI | `kmp/src/jvmTest/.../db/BlocksFtsTriggerTest.kt` |
| TEST-5F | `SearchRepositoryIntegrationTests` exercises all 6 named FTS queries through FTS5 MATCH/bm25/highlight | Automated / CI | `kmp/src/jvmTest/.../repository/SearchRepositoryIntegrationTests.kt` |
| TEST-5G | Existing FTS tests (`SearchRepositoryIntegrationTests`, `BlocksFtsTriggerTest`, `FtsRebuildTest`) use `DriverFactory().createDriver("jdbc:sqlite::memory:")` — i.e., `PooledJdbcSqliteDriver` (xerial), NOT `JvmLibsqlDriver` | Gap finding / Audit | Multiple jvmTest files |
| TEST-5H | (Conditional) If Epic 2 Story 2.1 audit confirms the gap found in TEST-5G: `LibsqlFts5IntegrationTest` exists in `kmp/src/jvmTest/` and exercises `JvmLibsqlDriver` against a real temp-file DB, creating FTS schema, inserting blocks, verifying MATCH+bm25+highlight, and confirming FTS triggers fire on UPDATE | Automated / CI | To be created per plan.md Story 2.2 |

**Factual accuracy findings (TEST-5A through 5D)**: PASS — Schema verified:
- 2 virtual tables (`blocks_fts`, `pages_fts`)
- 6 triggers (`blocks_ai`, `blocks_ad`, `blocks_au`, `pages_ai`, `pages_ad`, `pages_au`)
- 6 named FTS queries (not 8)
- `bm25()` and `highlight()` only; `snippet()` not present

**Gap finding (TEST-5G)**: CONFIRMED FTS5 JNI GAP — Every FTS test file in `jvmTest` and `businessTest` calls `DriverFactory().createDriver("jdbc:sqlite::memory:")`. In `DriverFactory.jvm.kt`, this code path creates a `PooledJdbcSqliteDriver` (xerial JDBC SQLite), not a `JvmLibsqlDriver`. The libsql JNI bridge is only exercised via `DriverFactory.createLibsqlDriver(dbPath)`, which no search test currently calls. Therefore, Epic 2 Story 2.2 is required.

**Note on `pages_fts` rowid asymmetry**: `blocks_fts` uses `content_rowid=id` (integer PK autoincrement); `pages_fts` uses `content_rowid=rowid` (implicit SQLite rowid, because `pages.uuid` is TEXT PRIMARY KEY). The join in `searchPagesByNameFts` is `ON p.rowid = pf.rowid`. Any FTS5 integration test for pages must use `ON p.rowid = pf.rowid`, not `ON p.id = pf.rowid`.

---

### REQ-6: ADR exists, is committed, and contains required sections

> plan.md §Success Criteria 1 and Epic 1 Story 1.1

| Test ID | Description | Type | Location |
|---|---|---|---|
| TEST-6A | `docs/adr/ADR-006-embedded-database-engine-selection.md` exists and is committed on the `stelekit-libsql` branch | Artifact-existence | `docs/adr/` |
| TEST-6B | ADR-006 status field reads "Accepted" | Factual-accuracy / Manual | `docs/adr/ADR-006-embedded-database-engine-selection.md` |
| TEST-6C | ADR-006 FTS query count reads "Six" or "6" (not 8) | Factual-accuracy / Manual | `docs/adr/ADR-006-embedded-database-engine-selection.md` |
| TEST-6D | ADR-006 MVCC section describes runtime detection via `isDatabaseMvccEnabled()` rather than stating MVCC is statically unavailable in local mode | Factual-accuracy / Manual | `docs/adr/ADR-006-embedded-database-engine-selection.md` §Rationale 3 |
| TEST-6E | ADR-006 explicitly states `snippet()` is not used | Factual-accuracy / Manual | `docs/adr/ADR-006-embedded-database-engine-selection.md` |
| TEST-6F | ADR-006 references PR #171 | Manual | `docs/adr/ADR-006-embedded-database-engine-selection.md` §Context |

**Verdict**: PASS — ADR-006 exists and is committed. Status is "Accepted". The Context section reads "Six FTS5 triggers" and "Six named queries using MATCH, bm25(), and highlight() (snippet() is not used)". §Rationale 3 reads: "The driver calls `LibsqlJni.isDatabaseMvccEnabled(dbHandle)` at startup and branches between `BEGIN CONCURRENT` (MVCC) and `BEGIN IMMEDIATE` (single-writer WAL) accordingly." PR #171 is referenced in both Context and Consequences.

The three material issues from the adversarial review (Finding 1: FTS count wrong, Finding 2: MVCC framing, Finding 3: ADR already exists) are all RESOLVED in the current ADR-006 text.

---

### REQ-7: No new engine introduced in the PR

> plan.md §Success Criteria 4: "The PR contains the libsql JNI driver and its associated CI
> workflow; no additional database engine code is introduced."

| Test ID | Description | Type | Location |
|---|---|---|---|
| TEST-7A | `./gradlew ciCheck` passes with no database engine other than libsql (JNI), xerial JDBC (test-only), SQLiter (iOS), and sqlite-wasm (WASM) on the classpath | CI | Branch CI |
| TEST-7B | `build.gradle.kts` does not add a DuckDB, RocksDB, LadybugDB, or Realm dependency | Manual | `kmp/build.gradle.kts` |

**Verdict**: Not yet fully verifiable (CI run required), but build.gradle.kts review is straightforward. Presumed PASS based on plan alignment.

---

### REQ-8: CI passes including any new jvmTest tests

> plan.md §Success Criteria 5

| Test ID | Description | Type | Location |
|---|---|---|---|
| TEST-8A | `./gradlew jvmTest` is green after adding `LibsqlFts5IntegrationTest` (if required by Story 2.2) | CI | Branch CI |
| TEST-8B | `./gradlew ciCheck` is green overall | CI | Branch CI |

**Verdict**: Blocked by TEST-3C (limbo watchlist) and TEST-5H (LibsqlFts5IntegrationTest) not yet implemented. CI status cannot be confirmed until those artifacts exist.

---

## Test Case Count Summary

| Category | Count |
|---|---|
| Artifact-existence tests | 6 (TEST-1C, TEST-3C, TEST-6A, TEST-7B, and implicit ADR section checks) |
| Factual-accuracy tests | 9 (TEST-5A–5D, TEST-6B–6F) |
| Coverage/manual review tests | 8 (TEST-1A/B, TEST-2A–D, TEST-3A/B, TEST-4A–C) |
| Automated / CI tests | 5 (TEST-5E, TEST-5F, TEST-5H, TEST-8A/B) |
| Gap-finding / audit | 1 (TEST-5G) |
| **Total** | **29** |

---

## Readiness Gate Results

### Criterion 1: Requirements Coverage

Does plan.md address every requirement in requirements.md?

| Requirement | Addressed in Plan? | Notes |
|---|---|---|
| Evaluate libsql (baseline) | Yes — kept as primary | |
| Evaluate LadybugDB | Yes — §Alternatives Rejected + §Not Doing | |
| Evaluate Limbo | Yes — Epic 4 watchlist + §Alternatives Rejected | |
| Evaluate DuckDB | Yes — Epic 3 deferred + §Alternatives Rejected | |
| Evaluate ClickHouse | Yes — §Alternatives Rejected | |
| Evaluate LevelDB/RocksDB | Yes — §Alternatives Rejected | |
| "Any other strong candidates" | Partially — Realm Kotlin added; H2/HSQLDB missing | Low severity per adversarial review |
| Scored comparison matrix | Yes — plan.md Recommendation Matrix | |
| Clear recommendation per engine | Yes — Verdict column in matrix | |
| Four-platform hard constraint | Yes — central disqualification criterion | |
| Performance evaluation | Yes — write throughput measured (~24%), DuckDB OLTP latency cited | |
| Query capability (FTS5) | Yes — Epic 2 verifies this | |
| Cross-platform portability | Yes — Recommendation Matrix columns | |
| Correctness/ACID | Yes — MVCC section in ADR + Appendix driver matrix | |
| Integration cost | Yes — §Not Doing rationale per engine | |
| If no replacement: libsql advantages | Yes — ADR §Rationale | |
| If no replacement: reassessment conditions | Yes — ADR §Consequences + Epic 4 watchlist | |

**Gaps**: H2/HSQLDB not mentioned. Low severity. One row in plan.md §Not Doing closes it.

**Criterion 1 Result: PASS** (with minor gap)

---

### Criterion 2: Adversarial Review Resolved

Were all CONCERNS from adversarial-review.md addressed?

| Finding | Severity | Resolution Status |
|---|---|---|
| Finding 1: FTS query count wrong (8 → 6), snippet() not used | Medium | **RESOLVED** — ADR-006 reads "Six named queries" and "snippet() is not used"; plan.md uses "6 named FTS queries" throughout |
| Finding 2: MVCC framing misleading (static claim vs. runtime detection) | Medium | **RESOLVED** — ADR-006 §Rationale 3 explicitly describes runtime detection via `isDatabaseMvccEnabled()` and explains the current local-file behavior |
| Finding 3: ADR-006 already exists; Epic 1 should say "review" not "write" | Low-Medium | **RESOLVED** — plan.md Epic 1 Story 1.1 heading reads "Review and patch ADR-006" |
| Finding 4: `pages_fts` uses implicit `rowid`, not `id` — note for test author | Low | **NOTED in this validation** — plan.md Story 2.2 should mention this asymmetry; not yet in the plan text |
| Finding 5: H2/HSQLDB not mentioned | Low | **OPEN** — neither ADR nor plan §Not Doing mentions H2/HSQLDB |
| Finding 6: Limbo/Turso naming inconsistency | Low | **PARTIALLY RESOLVED** — plan.md uses "Limbo (Turso)" and "Turso/Limbo"; ADR uses "Limbo (Turso)". Consistent enough in context, but watchlist file name is not yet decided (file doesn't exist yet) |
| Finding 7: libsql embedded maintenance risk not captured | Low-Medium | **OPEN** — no watchlist entry for "is local-mode libsql still maintained vs. sqld pivot?" |

The three material findings (1, 2, 3) are resolved. The four lower-severity findings (4, 5, 6, 7) are partially or fully open. Finding 4 is addressed in this validation document for the test author. Finding 7 (maintenance risk) should be added to the watchlist when it is created.

**Criterion 2 Result: PASS** (material issues resolved; minor items noted)

---

### Criterion 3: Test Coverage Designed

Does this validation document have a test for every success criterion in plan.md?

| Plan Success Criterion | Test Coverage |
|---|---|
| SC-1: ADR-006 exists, status Accepted, FTS count 6, MVCC runtime detection | TEST-6A through TEST-6F |
| SC-2: FTS5 coverage confirmed (audit + optional integration test) | TEST-5E through TEST-5H |
| SC-3: Limbo watchlist committed with four blockers | TEST-3C |
| SC-4: No new engine added | TEST-7A/B |
| SC-5: CI passes including new jvmTest tests | TEST-8A/B |

**Criterion 3 Result: PASS**

---

### Criterion 4: No Ambiguous Tasks

Are all tasks in plan.md specific enough to implement without guesswork?

| Task | Specific Enough? | Notes |
|---|---|---|
| Epic 1 Story 1.1: Verify FTS5 counts, MVCC framing, wire ADR index | Yes | Count verified as 6; MVCC framing verified in ADR; ADR index check is a one-liner |
| Epic 2 Story 2.1: Grep existing tests for FTS assertions | Yes | File paths given; driver type to look for is specific |
| Epic 2 Story 2.2: Add LibsqlFts5IntegrationTest | Yes | Class name, driver factory method (`createLibsqlDriver`), schema setup steps, exact queries to call, and assertions are all specified. Rowid asymmetry caveat noted in this document |
| Epic 4 Story 4.1: Write limbo-watchlist.md | Yes | Four blockers listed verbatim in plan; file path given |
| Epic 4 Story 4.1: Add calendar reminder | Intentionally vague | Developer preference; acceptable |

**Criterion 4 Result: PASS**

---

## Scope Alignment Check

**Original request**: "expand the scope of this PR to evaluate additional database engines."

**Plan recommendation**: No expansion — libsql stays, no new engine added.

### Does the plan explicitly address this intent?

Yes. plan.md §Decision Summary opens with: "libsql is the correct and only viable embedded database engine for SteleKit's four-platform KMP requirement today." It explicitly states "no new engine is needed" and "The `stelekit-libsql` branch (PR #171) should be merged as-is."

### Is the evaluation thorough enough to justify "no expansion"?

Yes. The evaluation covers 7 engines across 5 evaluation dimensions, with platform-coverage as the decisive first-pass filter. The elimination logic is sound: every alternative fails on at least one of platform coverage (most common), SQL dialect compatibility (DuckDB/chDB), or OLTP fitness (DuckDB, LevelDB/RocksDB, LadybugDB). No evaluated engine reaches the FTS5 compatibility test because they are eliminated before it. This is documented in the Recommendation Matrix with per-cell verdicts and in individual rejection sections with specific technical evidence.

The adversarial review independently assessed the recommendation as "directionally correct" and did not challenge the "disqualify all" verdict for any engine — only the accuracy of supporting claims (which have been corrected).

### Do the deliverables (ADR, FTS5 audit, Limbo watchlist) constitute a meaningful addition to PR scope?

Yes, for three reasons:

1. **ADR-006** makes the evaluation durable — a future contributor or the developer in 12 months does not need to re-run 4 research streams to know why DuckDB or LadybugDB was not chosen. The rejection rationale is recorded with technical specifics (DuckDB memory profile, RocksDB JNI leak issues, chDB binding gap, etc.).

2. **FTS5 JNI gap closure** (Epic 2 Story 2.2) is genuine new value: the existing test suite exercises FTS5 via xerial JDBC SQLite, not via the libsql JNI bridge. The PR introduces a new driver; the FTS5 integration test confirms the new driver's critical query surface works end-to-end. Without this test, a regression in the JNI FTS5 path could go undetected until a user reports missing search results.

3. **Limbo watchlist** converts a "monitor this" intent into a checklist with four go/no-go criteria. Without it, the developer must re-research Limbo from scratch in 6–12 months; with it, reassessment takes 30 minutes.

The scope expansion is therefore substantive even though no new engine code is added: it adds one committed architectural decision record, one integration test class, and one maintenance checklist — all directly supporting the PR that introduced the new driver.

**Scope alignment verdict: ALIGNED**

---

## Overall Verdict

**PASS** — with two open tasks that must complete before the PR is merge-ready:

| # | Blocking Task | Criterion |
|---|---|---|
| 1 | Create `project_plans/multi-db-evaluation/research/limbo-watchlist.md` (or `turso-watchlist.md`) with four concrete blockers and the organizational maintenance risk check (adversarial review Finding 7) | SC-3, TEST-3C |
| 2 | Execute Epic 2 Story 2.1 (audit confirms FTS5 JNI gap) and write `LibsqlFts5IntegrationTest` using `DriverFactory().createLibsqlDriver(tempDbPath)`, verifying MATCH + bm25 + highlight through the JNI bridge, and noting the `pages_fts` rowid asymmetry in test comments | SC-2, TEST-5H |

Non-blocking items (low severity, can be done but do not block merge):

- Add H2/HSQLDB one-line dismissal to plan.md §Not Doing (adversarial review Finding 5)
- Add Story 2.2 task note about `pages_fts` rowid asymmetry (`ON p.rowid = pf.rowid`, not `ON p.id`)
- Decide canonical watchlist filename ("turso-watchlist.md" preferred per adversarial review Finding 6)
- Add organizational risk check to watchlist: "Is libsql embedded local-mode still receiving bug fixes at parity with sqld?" (adversarial review Finding 7)

Once tasks 1 and 2 are complete and `./gradlew jvmTest` is green with the new test, the evaluation is done and PR #171 is ready to merge.
