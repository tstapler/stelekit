# ADR-003: Declared Execution Order with Kahn's Algorithm for Validation Only

**Status**: Accepted
**Date**: 2026-04-14
**Deciders**: Tyler Stapler

---

## Context

The migration framework must handle migrations that depend on each other. Migration B may restructure blocks that Migration A created, so B cannot run before A has completed. Two strategies exist for ordering migration execution:

**Option A — Auto-topological-sort (reorder automatically)**

Build a dependency DAG from `requires` declarations. Run Kahn's algorithm (BFS topological sort) to produce a canonical execution order. The declared registration order in `MigrationRegistry` is advisory; the framework silently reorders based on dependency graph.

- Execution order is reproducible from the DAG, not from the registration sequence.
- Developers do not need to think about registration order — the framework handles it.
- Risk: two valid topological orderings may exist for a given DAG (diamond dependencies). The framework picks one arbitrarily, and the choice may change if the DAG changes, producing non-deterministic behavior across runs.

**Option B — Declared order with Kahn validation (Sqitch model)**

Migrations are registered in a specific order (the registration sequence in `MigrationRegistry` is canonical). Kahn's algorithm runs at startup purely to *validate* the DAG — it detects cycles and verifies that every migration's dependencies appear before it in the declared order. If the declared order violates a dependency, the framework throws at startup with a clear error.

- Execution order is the registration sequence, which is deterministic across all runs.
- Developers must declare migrations in a valid topological order.
- The framework enforces correctness with a startup error rather than silently reordering.

**Research finding** (from `research/architecture.md`): Sqitch explicitly removed auto-topological-sort after users reported non-deterministic orderings across runs. The Sqitch documentation states: "The declared order is canonical. The DAG is validated, not used for ordering." This mirrors git's approach to merge commits: explicit, not automatic.

---

## Decision

**Option B: declared execution order with Kahn's algorithm for validation only.**

---

## Rationale

1. **Determinism**: The registration sequence is the execution sequence, always. There is no ambiguity when multiple valid topological orderings exist (common in diamond dependency graphs). Every developer on every machine runs migrations in the same order.

2. **Fail-fast correctness**: If a migration is registered before its declared dependency, the framework throws at startup with the exact migration ID that is out of order. This makes the error recoverable immediately — the developer reorders the registration — rather than discovering the issue at apply time after some blocks have already changed.

3. **Audit clarity**: The migration log in `migration_changelog` records migrations in execution order. When an operator reads the log, the `execution_order` column reflects the declared sequence, not a computed one. This makes post-mortem debugging straightforward.

4. **Precedent from Sqitch**: Sqitch's plan file is append-only and declares order explicitly. Changes must appear after their dependencies in the file. Auto-sort was removed precisely because it produced non-deterministic orderings. The SteleKit framework adopts the same lesson.

5. **Simplicity of implementation**: The validation pass runs Kahn's algorithm once at startup over the full registered migration set. If the result set size equals the input set size, the DAG is acyclic and the declared order is valid. If not, throw `MigrationCycleException` naming the cycle path. No ordering state needs to be maintained beyond the registration list.

---

## Consequences

- `MigrationRegistry` stores migrations as a `List<Migration>` (ordered). The list is the canonical execution order.
- `DagValidator.validate(migrations: List<Migration>)` runs Kahn's algorithm at `MigrationRunner` startup. It validates: (a) no cycles, (b) all `requires` references resolve to known migration IDs, (c) each migration appears after all migrations it requires.
- If validation fails, `MigrationRunner.runPending()` throws before touching any data.
- Migration authors are responsible for registering migrations in valid topological order. The error message on failure names the exact out-of-order pair.
- `conflicts` declarations (a migration that must NOT have been applied before this one) are validated in the same pass — `MigrationConflictException` is thrown if a conflict pair is detected in the applied changelog.
- The `execution_order` column in `migration_changelog` records the 0-based index in the registration list, providing a stable audit trail.
