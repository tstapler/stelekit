# Adversarial Review — Multi-DB Evaluation Plan

**Verdict: CONCERNS**

The plan is directionally correct and the "keep libsql" recommendation is well-supported.
However, three material issues require correction before any document in this plan is
treated as settled.

---

## Finding 1: FTS5 query count is wrong — 6 named queries, not 8

**Severity: Medium (ADR claim is factually incorrect)**

The plan states in Epic 2: "SteleDatabase.sq has 8 FTS-related queries and 6 FTS5
triggers." The trigger count (6) is correct. The query count is wrong.

Actual named FTS queries in `SteleDatabase.sq` (verified by reading the file):

1. `searchBlocksByContentFts`
2. `searchBlocksByContentFtsInPage`
3. `searchBlocksCountFts`
4. `searchPagesByNameFts`
5. `searchPagesByNameFtsInDateRange`
6. `searchBlocksByContentFtsInDateRange`

That is **6 queries**, not 8. The ADR section (Epic 1, Story 1.1) references "8 FTS-related
queries" in the Context description — this will be a factually wrong claim in a committed
ADR. Correct the count to 6 before writing the ADR.

Additionally, the plan's ADR Context section states the queries use `snippet()` as an
auxiliary function: *"bm25(), highlight(), and MATCH queries"* is correct. But the
watchlist in Epic 4 mentions: *"all three trigger forms used by SteleDatabase.sq"* and
then links to the COMPAT.md to confirm `bm25()`, `highlight()`, `snippet()` are supported.
`snippet()` is **not used anywhere in SteleDatabase.sq**. The watchlist criterion
referencing `snippet()` is unnecessary noise and slightly misleading. Remove it or replace
with the accurate set: `bm25()` and `highlight()` only.

---

## Finding 2: MVCC claim is partially misleading — the driver actually checks at runtime

**Severity: Medium (architectural accuracy)**

The plan states in multiple places: *"MVCC (`BEGIN CONCURRENT`) only activates in sqld
server mode, not local-file mode."* The pitfalls research confirms this for the libsql
embedded mode. However, the actual `LibsqlDriverCore.kt` implementation contradicts the
strong framing:

```kotlin
val isMvccActive: Boolean = LibsqlJni.isDatabaseMvccEnabled(dbHandle)
// ...
val beginSql = if (isMvccActive) "BEGIN CONCURRENT" else "BEGIN IMMEDIATE"
```

The driver does not *assume* MVCC is unavailable in local mode — it queries the native
library at runtime via `isDatabaseMvccEnabled()`. This means:

1. If a future libsql release enables MVCC in local-file mode, the driver will silently
   start using `BEGIN CONCURRENT` without any code change. This is probably intentional
   design — but the plan's ADR should reflect the *actual mechanism* (runtime detection)
   rather than claiming MVCC is permanently off in local mode.

2. The consequence row in the ADR as drafted reads: *"MVCC via `BEGIN CONCURRENT` on
   JVM/Android"* in the positives section, then *"MVCC (`BEGIN CONCURRENT`) only activates
   in sqld server mode"* in the negatives section. These two statements are in tension and
   will confuse a future reader. The correct framing: MVCC is off in local-file mode today
   (per pitfalls.md), but the driver is instrumented to enable it automatically if libsql
   ever ships local MVCC.

**Action required**: Rewrite the MVCC consequence bullet in the ADR to say "the driver
detects MVCC availability at runtime via `isDatabaseMvccEnabled()`; as of libsql 0.9.x
embedded local-file mode returns false (falls back to `BEGIN IMMEDIATE`), so MVCC is
inactive in practice."

---

## Finding 3: ADR is already written — Epic 1 is describing work that is done

**Severity: Low-Medium (scope tracking)**

`docs/adr/ADR-006-embedded-database-engine-selection.md` already exists (7.8K, committed
on this branch). Epic 1 Story 1.1 instructs the developer to "check next ADR number" and
"write ADR-NNN." The next number would be ADR-007, but ADR-006 is already the correct
document for this decision.

This creates two risks:
- A future implementer reading this plan might create a duplicate ADR-007 covering the
  same topic.
- The plan's success criterion #1 ("ADR-NNN-embedded-database-engine.md exists") is
  technically already satisfied — but by ADR-006, not by any new file. The criteria
  wording implies the file doesn't exist yet.

**Action required**: Update Epic 1 to reference the existing `ADR-006` by name. Change
the instruction from "write ADR-NNN" to "review and verify ADR-006-embedded-database-engine-selection.md
against the findings in this plan" (particularly re-check the MVCC and FTS5 counts noted
above). Update the success criterion accordingly.

---

## Finding 4: `pages_fts` uses implicit `rowid` — the plan/ADR must not conflate with `id`

**Severity: Low (subtle correctness issue for future FTS5 test author)**

The schema has an asymmetry the plan does not call out:

- `blocks_fts`: `content_rowid=id` — uses `blocks.id` (INTEGER PRIMARY KEY AUTOINCREMENT)
- `pages_fts`: `content_rowid=rowid` — uses the implicit SQLite rowid, because `pages.uuid`
  (TEXT PRIMARY KEY) does not create an integer rowid alias

The join in `searchPagesByNameFts` is `JOIN pages p ON p.rowid = pf.rowid`, not `p.id`.
If Story 2.2's integration test author is not aware of this asymmetry and writes
`ON p.id = pf.rowid`, the test will fail with a confusing error (no `id` column on
`pages`). Epic 2 Story 2.2's task list should call out this rowid asymmetry explicitly
when describing what to verify.

---

## Finding 5: Scope alignment — plan recommends no expansion, but requirement item says evaluate "any additional strong candidates"

**Severity: Low (acknowledged gap, but not fully justified)**

Requirements item: *"Any other strong candidates surfaced by research."* The plan
recommends no expansion and dismisses all evaluated alternatives. This is probably the
right call, but the plan does not explicitly address two candidates that a diligent reader
might raise:

1. **sqlite-jdbc (xerial)**: Listed in the "Not Doing" table with the reason "already
   replaced by libsql on this branch." This is accurate and sufficient.

2. **H2 / HSQLDB**: Pure-JVM embedded databases. The plan does not mention them. They
   are correctly ignorable because they have no iOS or WASM path and no FTS5. But a
   future reader might ask "why not H2?" — a one-line dismissal in the "Not Doing" table
   would close this.

3. **Realm Kotlin SDK**: Already in the "Not Doing" table — correctly dismissed for no
   WASM support.

The gap is low severity because the overall recommendation is sound. However, adding H2
and HSQLDB (one combined row) to the "Not Doing" table would make the evaluation visibly
complete and prevent the question from recurring.

---

## Finding 6: Epic 4 Limbo/Turso watchlist — naming inconsistency will cause confusion

**Severity: Low (documentation hygiene)**

Epic 4 uses "Limbo" and "Turso" interchangeably. The pitfalls.md says: *"Limbo (now
internally called 'Turso' in the repository)."* The watchlist file is named
`limbo-watchlist.md` but points to the GitHub URL `tursodatabase/turso`. The ADR
"Alternatives Rejected" section refers to it as "Turso/Limbo."

This naming inconsistency will confuse a developer checking the watchlist in 6 months.
Pick one canonical name for the file and all references. The GitHub repo is now named
`turso` under `tursodatabase`; suggest using "Turso (formerly Limbo)" as the canonical
label, and renaming the watchlist file to `turso-watchlist.md`.

---

## Finding 7: Missing risk — libsql embedded-mode active maintenance status

**Severity: Low-Medium (risk not captured)**

The pitfalls research correctly notes the JNI maintenance burden. What it does not assess
is whether Turso (the company) is actively maintaining the embedded local-file mode of
libsql vs. pivoting exclusively to cloud/server (`sqld`). Turso's commercial product is
the cloud database; the embedded mode is a community concern. If Turso deprioritizes
local-file libsql in favor of `sqld`, the JNI bridge will track a slowly-maintained
upstream with potentially increasing maintenance cost.

The plan does not capture this organizational risk. The watchlist should include a check:
"Is `libsql` embedded local mode still receiving bug fixes and releases at parity with
the server mode?" as part of the 6-month reassessment.

---

## Summary

**Verdict: CONCERNS**

Three bullets that matter most:

1. **FTS5 query count is wrong**: The plan claims 8 named FTS queries; the actual count
   in `SteleDatabase.sq` is 6. Additionally, `snippet()` is cited but not used anywhere
   in the schema. Correct before committing the ADR.

2. **MVCC framing is misleading**: The driver uses runtime detection (`isDatabaseMvccEnabled`)
   rather than hardcoded "local = no MVCC." The ADR must reflect the actual mechanism,
   not overstate the limitation as permanent.

3. **ADR-006 already exists**: Epic 1 instructs writing a new ADR, but ADR-006-embedded-database-engine-selection.md
   is already committed. Epic 1 should be reframed as "review and patch ADR-006" rather
   than "write ADR-NNN," or the success criterion will be satisfied by a file the plan
   never mentions.
