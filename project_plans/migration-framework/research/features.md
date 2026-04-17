# Research: Features

**Date**: 2026-04-14
**Dimension**: Comparable tools and transferable concepts

## Summary

The four canonical SQL migration tools — Atlas, Sqitch, Liquibase, and Flyway — each solve a distinct sub-problem: desired-state diffing (Atlas), explicit dependency ordering (Sqitch), audited changeset history with checksums (Liquibase), and simple versioned file conventions (Flyway). No existing tool targets file-content (Markdown/outliner block) migrations, but SQL-over-document-store patterns (DuckDB, SQLDelight) demonstrate that SQL SELECT/WHERE/UPDATE semantics can be cleanly mapped onto structured non-relational data. The SteleKit migration framework should synthesize Flyway's versioned file convention, Liquibase's checksum-guarded changelog, Atlas's desired-state diff loop, and Sqitch's dependency DAG — applied to block/page content rather than database schema.

---

## 1. Atlas — Desired-State Diff Engine

### What Atlas Actually Does

Atlas is a declarative schema migration tool. The user writes the *desired* schema state (in HCL, SQL, or ORM schema), and Atlas:

1. **Inspect** — reads the *current* state from the live database (or migration directory) and represents it as an internal graph model.
2. **Diff** — computes the delta between current and desired state. This is not a text diff; it is a semantic diff over a normalized schema object graph (tables, columns, constraints, indexes).
3. **Plan** — emits ordered SQL DDL statements that move current → desired. The plan respects destructive-change safety policies (50+ lint analyzers).
4. **Apply** — executes the plan, optionally with `--dry-run` for preview or `--auto-approve` for CI.

The `atlas schema plan` command introduced a pre-approval step: the plan is stored, reviewed, and referenced by ID. On `atlas schema apply`, Atlas checks whether an approved plan exists for the exact current→desired transition; if yes, it applies without prompting.

### Drift Detection

Atlas continuously compares the live database schema against the repository-declared desired state. When they diverge, Atlas raises an alert with an ERD and HCL/SQL diff. This is the **schema drift** detection model.

### Hybrid Mode

Atlas supports a hybrid workflow: local development uses declarative apply directly; shared environments use a versioned migration directory (`atlas migrate diff` generates an explicit `.sql` file committed to VCS). The same diff engine powers both paths.

### What to Adopt for File-Content Migrations

- **Desired-state spec file**: The user writes `desired_state.sql` (or equivalent DSL) describing what block/page state *should* look like. The engine computes what transformations are needed.
- **Inspect → Diff → Apply loop**: Load current graph state into an in-memory representation, diff it against the desired spec, emit a change plan, apply only the delta.
- **Drift detection**: After applying, re-inspect and verify current state matches desired state. Flag if they diverge.
- **Dry-run mode**: Print the planned operations without executing them. Critical for user confidence.
- **Lint / safety checks**: Before applying, validate that destructive operations (deleting blocks, overwriting content) are explicitly acknowledged.

### What to Skip

- HCL as the spec language — too complex for a first iteration. SQL-like DSL is simpler.
- The versioned migration directory as a secondary concern; desired-state is the primary model.

---

## 2. Sqitch — Dependency DAG and Change Tracking

### What Sqitch Actually Does

Sqitch is a database change management tool whose central abstraction is the **change**, not the version number. Changes live in a `sqitch.plan` file (plain text). Each change has:

- A unique name
- A timestamp and author
- A bracketed `[dependency1 dependency2]` list of required changes
- Optionally, `!conflicting_change` for conflicts

Example plan file entry:
```
users [appschema] 2013-12-30T23:49:00Z Marge N. O'Vera <marge@example.com> # Creates users table
```

Each change has three scripts: `deploy/`, `revert/`, `verify/`. Deploy applies the change; revert undoes it; verify confirms it was applied correctly.

### Dependency Resolution

Sqitch builds a DAG from the `requires` declarations and performs a topological sort. Before deploying a change, it checks that all required changes are already deployed (querying the deployment metadata table in the target DB). Circular dependencies are rejected at plan-parse time.

### Merkle Tree Integrity

Sqitch uses a Merkle tree pattern (similar to Git commits) over the plan file. Each change entry's hash incorporates the hashes of its dependencies, making the plan tamper-evident.

### `rework` Command

When a change needs modification after it has been deployed to production, `sqitch rework` copies the existing scripts to a tag-namespaced version, allowing both the original and new version to coexist. This is how Sqitch handles immutability of deployed changes while still allowing evolution.

### What to Adopt for File-Content Migrations

- **Named changes with explicit dependencies**: Each migration file declares `requires: [other-migration-id]`. The engine builds a DAG and topologically sorts the execution order. This is essential once migrations compose (e.g., migration B restructures blocks that migration A created).
- **Deploy + Revert + Verify triad**: Every migration ships with a revert script (or uses SteleKit's op log for undo) and a verify query (re-runs the desired-state check after apply).
- **Plan file as the source of truth**: A plain-text `migrations.plan` lists all known migration IDs, their dependencies, and metadata. New migrations append to the bottom; the file is append-only.
- **Rework pattern**: If a migration needs to change after deployment, create a new migration that supersedes it rather than mutating the old one. This preserves audit history.

### What to Skip

- Merkle tree over plan file — overkill for a first version. A simpler checksum per migration file (Liquibase-style) is sufficient.
- `sqitch bundle` / multi-project dependency syntax — not needed initially.

---

## 3. Liquibase — Changelog with Checksums

### What Liquibase Actually Does

Liquibase uses a **changelog** file (XML, YAML, JSON, or SQL) that lists ordered **changesets**. Each changeset has:

- A unique `id` + `author` composite key (namespace)
- The actual change operations (createTable, addColumn, custom SQL, etc.)
- Optional `rollback` block for explicit undo
- Optional `preconditions` that gate execution
- Optional `runOnChange: true` or `runAlways: true` flags

### The DATABASECHANGELOG Table

When a changeset runs, Liquibase records it in the `DATABASECHANGELOG` table with columns including: `ID`, `AUTHOR`, `FILENAME`, `DATEEXECUTED`, `ORDEREXECUTED`, `MD5SUM`, `DESCRIPTION`, `COMMENTS`, `EXECTYPE`, `CONTEXTS`, `LABELS`.

The `MD5SUM` column stores a checksum of the changeset content. On subsequent runs, Liquibase recomputes the checksum and compares it. If the changeset was modified after deployment, the checksums diverge and Liquibase raises a validation error, preventing silent corruption.

### Rollback Mechanics

- For modeled changelogs (XML/YAML/JSON), many change types (createTable, addColumn, renameColumn) generate automatic rollback SQL.
- For SQL changelogs, the user must supply explicit rollback SQL inside a `<rollback>` block.
- Rollback executes changesets in reverse order (most-recently-applied first).
- If a rollback step fails, Liquibase stops and does NOT continue — leaves the system in a known partial state rather than silently proceeding.

### `runOnChange` — Repeatable Changesets

If `runOnChange: true`, Liquibase re-applies the changeset whenever its checksum changes. This is the equivalent of Flyway's `R__` repeatable migrations — useful for idempotent operations like re-seeding lookup data, updating stored procedures, or re-computing derived properties.

### What to Adopt for File-Content Migrations

- **Checksum guard**: Compute a SHA-256 of each migration file's content. Store it in the migration log table (SQLDelight). On subsequent runs, recompute and compare — if modified, raise an error. Users must explicitly `--clear-checksum` to re-deploy a changed migration.
- **Migration log table**: A SQLDelight table with columns: `migration_id`, `filename`, `checksum`, `applied_at`, `applied_by`, `execution_order`, `status` (`applied` / `failed` / `reverted`).
- **`runOnChange` equivalent**: Desired-state migrations are inherently repeatable — re-running them rechecks current state and applies only the delta. This is cleaner than Liquibase's explicit flag.
- **Preconditions**: Before running a migration, check preconditions against the current graph state (e.g., "page X must exist", "block property Y must be set"). If preconditions fail, abort with a clear error.
- **Automatic rollback for known operations**: If the migration DSL declares operations like "rename property A to B", the engine can auto-generate the inverse ("rename property B back to A"). Complex SQL operations require explicit rollback declarations.

### What to Skip

- The author+id composite key — a UUID or content-hash-based migration ID is simpler.
- XML/YAML format — SQL-like DSL is closer to the project's intent.
- Multi-database support abstractions — only one "database" (the graph file set) exists.

---

## 4. Flyway — Versioned Migration Files

### What Flyway Actually Does

Flyway is the simplest of the four tools. Its core model:

1. **Versioned migrations** (`V{version}__{description}.sql`): Applied once, in version order, never re-run.
2. **Repeatable migrations** (`R__{description}.sql`): Applied whenever their checksum changes.
3. **Undo migrations** (`U{version}__{description}.sql`, paid feature): Explicit rollback scripts per versioned migration.

Flyway tracks applied migrations in `flyway_schema_history` with columns: `installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`.

### Versioning Model

Versions are comparable integers or dotted strings (1, 1.1, 2, 1.2 — note out-of-order is possible but discouraged). The `migrate` command applies all pending migrations in version order. `validate` recomputes checksums of applied scripts and fails if any have been tampered. `repair` removes failed migration records and realigns checksums (used after manual intervention).

### Key Edge Cases

- **Out-of-order**: Disabled by default. If enabled, Flyway will apply a migration with a lower version number than the current highest applied version. Useful for branch-based development where parallel branches create migrations.
- **Baseline**: For existing databases not yet managed by Flyway, `baseline` records all existing state as "already applied at version N" without actually running any scripts.
- **Checksum validation on startup**: By default Flyway validates all applied migrations against their files on every `migrate` run. This catches file drift early.

### What to Adopt for File-Content Migrations

- **`V{version}__{description}.migration` naming convention**: Immediately legible, sortable, and discoverable. Version can be a timestamp (`V20260414_001__rename_property.migration`) or a sequential integer.
- **Append-only history**: New migrations only added at the end (or with higher version numbers). Never mutate old migration files.
- **Repeatable migrations for desired-state specs**: Since desired-state queries are idempotent by nature, they naturally map to `R__` semantics — recheck and re-apply whenever the spec changes.
- **`validate` command**: Recompute checksums of all applied migrations and verify nothing has been tampered. Cheap to run on startup.
- **`repair` command**: Clear failed migration records and allow re-run after fixing the migration file. Essential for development-time iteration.
- **`baseline` command**: For existing graphs that pre-date the migration framework, record the current state as the baseline so subsequent migrations apply cleanly.

### What to Skip

- Paid undo migrations (`U__` prefix) — use SteleKit's op log instead.
- Java-based callbacks — not applicable.
- Multiple schema support — single graph per run.

---

## 5. File-Content Migration Precedents

### Existing Tools Survey

There is **no established tool** that manages versioned, idempotent, rollback-capable migrations of structured text/Markdown content the way Flyway/Liquibase manages SQL schemas. The closest existing work:

**Obsidian Importer (obsidianmd/obsidian-importer)**: One-time import from external formats (Roam, Notion, etc.) into Obsidian Markdown. No versioning, no rollback, no idempotency. Purpose: initial data migration, not ongoing evolution.

**Logseq migration scripts (community)**: Ad-hoc Python/JavaScript scripts that transform Markdown graph files (e.g., Roam JSON → Logseq Markdown). No tracking, no checksums, no undo. These are exactly the "one-off scripts" that SteleKit's framework aims to replace.

**Jekyll/Hugo content migrations**: Static site generators provide no migration tooling. Content changes are managed via Git commits, not a migration framework.

**Tana/Notion/Roam import tools**: Import converters, not migration frameworks. They handle one-time structural transformation (block reference format, property syntax) but provide no deployment tracking.

**MarkItDown (Microsoft)**: Python utility for converting file formats to Markdown. Not a migration tool; no state tracking.

### Key Gap This Framework Fills

The gap is significant: outliner/graph applications store structured content in Markdown files, but have no equivalent of Flyway/Liquibase for evolving that content over time. When Logseq's database version released, users faced a massive one-time migration with no rollback path. When property syntax changes, users write ad-hoc scripts. SteleKit's framework would be the first production-grade content migration system for Markdown-based outliner graphs.

---

## 6. SQL as a Query Language for Document Stores

### DuckDB Pattern

DuckDB queries JSON files, Parquet files, and CSV directly using standard SQL: `SELECT * FROM read_json('data.json')`. It also has a bidirectional SQLite extension (`duckdb-sqlite`) that allows `SELECT`, `INSERT`, `UPDATE`, `DELETE` on SQLite tables from DuckDB. The key insight: SQL semantics (SELECT/WHERE/UPDATE) map cleanly onto any data that can be represented as rows with columns, including structured documents.

### SQLDelight Pattern

SteleKit already uses SQLDelight 2.3.2, which generates type-safe Kotlin from `.sq` files. SQLDelight's `.sq` files are literal SQL SELECT/INSERT/UPDATE/DELETE statements — the exact syntax the requirements call for as the migration DSL. This is a direct precedent for "SQL queries against block/page content".

### Mapping SQL Semantics to Block/Page Content

| SQL Operation | File-Content Equivalent |
|---|---|
| `SELECT * FROM blocks WHERE property = 'tag' AND value = 'todo'` | Find all blocks with a specific property |
| `UPDATE blocks SET value = 'done' WHERE value = 'todo'` | Bulk-update block properties |
| `DELETE FROM blocks WHERE content LIKE '%deprecated%'` | Remove blocks matching a pattern |
| `INSERT INTO pages (title, content) VALUES (...)` | Create new pages |
| `SELECT p.title, b.content FROM pages p JOIN blocks b ON b.page_id = p.id` | Cross-page block queries |

### Steampipe Pattern

Steampipe uses SQL to query cloud APIs, treating API responses as virtual tables. The relevant precedent: a custom "foreign data wrapper" translates SQL queries into domain-specific operations. For SteleKit, the migration DSL can be SQL that is compiled/interpreted against the in-memory block/page model — no need for a full SQL parser if a constrained subset of SELECT/WHERE/UPDATE is sufficient.

### sqlite-utils Pattern

`sqlite-utils` (by Simon Willison) is a Python CLI and library for manipulating SQLite databases, including a `transform` command for restructuring tables and a `convert` command for transforming column values in place. Its `convert` command accepts a Python function applied to each matching row — a precise analogue to the "apply a transformation to each block matching a WHERE clause" model SteleKit needs.

---

## Transferability Matrix

| Concept | Source Tool | Applies to File Migrations? | Notes |
|---|---|---|---|
| Desired-state spec (declare what SHOULD exist) | Atlas | Yes — high value | Write SQL SELECT describing desired block/page state; engine diffs against current |
| Inspect → Diff → Apply loop | Atlas | Yes — core workflow | Load graph into memory, diff against spec, emit operations, apply |
| Schema drift detection | Atlas | Yes | Post-apply verify: re-inspect and confirm current = desired |
| Dry-run / plan preview | Atlas | Yes — essential for trust | Print operations before executing |
| Lint / destructive-change warnings | Atlas | Yes | Warn before deleting blocks, overwriting content |
| Named changes with explicit `requires` dependencies | Sqitch | Yes — medium priority | Needed once migrations compose; overkill for v1 |
| Deploy + Revert + Verify triad per change | Sqitch | Yes — partial | Revert = SteleKit op log; Verify = re-run desired-state check |
| Append-only plan file (DAG) | Sqitch | Yes | `migrations.plan` listing all migration IDs in dependency order |
| `rework` pattern (supersede, not mutate) | Sqitch | Yes | Create new migration to replace old one; preserve both in history |
| Checksum guard (detect post-deploy mutation) | Liquibase | Yes — essential | SHA-256 of migration file stored in SQLDelight log; error on mismatch |
| Migration log table | Liquibase | Yes — essential | SQLDelight table: id, filename, checksum, applied_at, status |
| `runOnChange` repeatable changesets | Liquibase | Yes — desired-state naturally fits | Desired-state specs re-run whenever spec changes (idempotent) |
| Preconditions | Liquibase | Yes | Pre-migration checks: page exists, property set, etc. |
| Automatic rollback generation for known operations | Liquibase | Yes — partial | Auto-invert simple renames; complex ops need explicit undo |
| `V{version}__{desc}` file naming convention | Flyway | Yes — adopt directly | `V20260414_001__rename_tag_property.migration` |
| `R__` repeatable migrations | Flyway | Yes — maps to desired-state | Desired-state files are repeatable by nature |
| `validate` command (checksum recompute) | Flyway | Yes — run on startup | Catches tampered migration files early |
| `repair` command | Flyway | Yes — dev workflow | Clear failed records, allow re-run |
| `baseline` command | Flyway | Yes — needed for existing graphs | Record current graph state as baseline before first migration run |
| Out-of-order migration support | Flyway | Maybe — v2 | Useful for branch-based development |
| SQL SELECT/WHERE/UPDATE against block model | DuckDB / SQLDelight | Yes — core DSL model | SQLDelight already in project; extend `.sq` query semantics |
| Row-transformation per matching record | sqlite-utils `convert` | Yes — core operation model | For each block WHERE clause matches, apply transformation |
| Virtual table over non-SQL data | Steampipe | Yes — architecture pattern | Expose block/page model as virtual SQL tables for query evaluation |

---

## Recommendations

Listed in priority order for implementation:

### P0 — Must Have for v1

1. **Migration log table (SQLDelight)** — `applied_migrations` table with `migration_id`, `filename`, `checksum`, `applied_at`, `status`, `execution_order`. This is the foundation everything else builds on. Model after Liquibase's DATABASECHANGELOG. Use existing SQLDelight infrastructure.

2. **`V{version}__{description}.migration` file convention** — versioned, append-only migration files. Simple to implement, immediately legible. Timestamp-based versions (YYYYMMDD_NNN) avoid merge conflicts better than sequential integers on parallel branches.

3. **Checksum guard** — SHA-256 of migration file content stored in log table. On re-run, recompute and fail-fast if mismatch. Users must explicitly clear to proceed. This prevents the most common failure mode (accidentally editing an already-applied migration).

4. **SQL-like DSL for desired-state spec** — leverage SQLDelight's `.sq` syntax as the query language. Expose blocks/pages as virtual tables. Support `SELECT` (inspect), `UPDATE` (transform), `DELETE` (remove), and `INSERT` (create) against the block/page model. Constrained subset only — no JOINs required for v1.

5. **Inspect → Diff → Apply loop** — for desired-state (repeatable) migrations: load current graph state, evaluate the desired-state spec, diff current vs desired, emit a list of operations, apply. This is the Atlas model applied to block content.

6. **Dry-run mode** — `--dry-run` flag prints planned operations without executing. Non-negotiable for user trust.

### P1 — Should Have for v1

7. **`validate` command** — recompute checksums of all applied migration files on disk, compare against log table, report mismatches. Run automatically on `migrate` startup.

8. **`baseline` command** — for existing graphs, record current state as the starting point without running any migrations.

9. **Preconditions** — declare required pre-conditions in the migration file header. The engine evaluates them before applying. Fail with a clear diagnostic if unmet.

10. **Automatic rollback for known operations** — for operations like "rename property A → B", auto-generate the inverse. Integrate with SteleKit's op log for block-level undo.

### P2 — Post-v1

11. **Dependency DAG (`requires` declarations)** — add `requires: [migration-id-1, migration-id-2]` headers to migration files. Build DAG, topologically sort, detect cycles. Required when migrations start composing on each other.

12. **`repair` command** — clear failed migration records to allow re-run after fixing the migration file. Critical for the development inner loop.

13. **`rework` pattern** — create a new migration that supersedes an old one, preserving both in the log.

14. **Out-of-order migration support** — for parallel branch development. Low priority for v1.

15. **Repeatable desired-state migrations (`R__` prefix)** — make desired-state specs explicitly flagged as repeatable and re-checked on every run. Distinguishes them from one-time versioned transformations.
