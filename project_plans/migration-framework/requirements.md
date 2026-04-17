# Requirements: Migration Framework

**Status**: Draft | **Phase**: 1 — Ideation complete
**Created**: 2026-04-14

## Problem Statement

SteleKit graph files (Markdown-based) drift from a desired structure over time. Developers write
one-off scripts to restructure files, producing inconsistency and errors. Transformations happen
without history, making it impossible to undo or audit changes. The full migration lifecycle is
unmanaged: schema drift, manual scripts, and no rollback.

Primary users: SteleKit contributors and power users who manage large graphs and need to evolve
their file structure reliably.

## Success Criteria

- Migration framework is integrated into SteleKit and handling real graph migrations in production
- All file migrations are expressed as desired-state SQL specs; no ad-hoc scripts
- Running the framework repeatedly produces identical results (idempotent)
- Every transformation is logged with before/after state

## Scope

### Must Have (MoSCoW)

- **SQL desired-state DSL** — write SQL (or SQL-like) queries to declare what block/page state should look like
- **Diff + apply engine** — compute delta between current state and desired state, apply only what changed
- **Migration versioning** — track which migrations have run (like Flyway/Liquibase) so re-runs are safe and idempotent

### Should Have

- **Rollback / undo** — reverse migrations using SteleKit's existing op log infrastructure

### Out of Scope

- UI for writing migrations (CLI or config file is sufficient)
- Non-Markdown files (binary assets, images, etc.)
- Remote/cloud sync or remote execution
- Full SQL parser/runtime (a constrained DSL is sufficient)

## Constraints

- **Tech stack**: Kotlin Multiplatform — must compile to all SteleKit targets (JVM, Android, iOS, Web via commonMain)
- **Timeline**: Not fixed; production usage in SteleKit is the 3-month horizon
- **Dependencies**: SQLDelight 2.3.2 (existing), SteleKit op log / undo infrastructure (existing)
- **First target**: JVM/Desktop correctness first; other platforms follow

## Context

### Inspiration

The framework is explicitly inspired by:

| Tool | Concept to adopt |
|---|---|
| **Atlas** | Desired-state model — declare what the schema SHOULD look like; tool computes the diff automatically |
| **Sqitch** | Dependency graph — migrations are a DAG with explicit requires/conflicts; deploy/revert/verify per step |
| **Liquibase** | Changelog with checksums — ordered changeset log with re-run detection and rollback support |
| **Flyway** | Versioned migration files — `V1__description` naming convention; simple, append-only history |

### Existing Work

- SteleKit has an op log and undo infrastructure (from `feat(storage): stable block identity, diff-merge, op log, undo, sidecar`)
- SQLDelight schema exists in `kmp/src/commonMain/sqldelight/`
- Block identity is stable (required for diff-merge)

### Stakeholders

- SteleKit contributors (primary authors of migrations)
- SteleKit end users (benefit from reliable graph evolution without data loss)

## Research Dimensions Needed

- [ ] Stack — evaluate Kotlin DSL options, SQL parsing libraries, KMP-compatible approaches
- [ ] Features — survey Sqitch, Atlas, Liquibase, Flyway in depth; identify transferable concepts
- [ ] Architecture — design patterns for desired-state diff engines, migration DAGs, changelog schemas
- [ ] Pitfalls — known failure modes in file-based migration systems, KMP serialization gotchas, idempotency edge cases
