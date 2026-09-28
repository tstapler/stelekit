# ADR-010: Make Illegal States Unrepresentable

**Date**: 2026-06-09
**Status**: Accepted
**Deciders**: Tyler Stapler

## Context

Three separate production crashes in the same release cycle traced back to the same root cause: invariants that were documented in comments or CLAUDE.md but not enforced by the type system.

1. **DB-closed crash (v0.38.0–0.38.1)**: `catchDbError()` was a private extension copied into 5 of 8 repository files, missing in 3. The rule "every Flow chain must end with `catchDbError()`" lived only in documentation. Any new repository method silently skipped it with 50% probability.

2. **F-Droid index 404 (CI, 2026-06)**: `fdroid-content` artifact structure changed when the upload `path:` was switched from two entries (common prefix stripped → `repo/` preserved) to one (full prefix stripped → flat files). The structural contract of the artifact was implicit; two callers (fdroid.yml and pages.yml) drifted apart without a compile-time signal.

3. **Android startup crash (v0.38.4)**: `GraphManager._activeRepositorySet` (nullable) and `_pendingMigration` (bare `Deferred<Unit>`) together encoded a state machine — Empty → Loading → Ready — but neither field carried the graph ID or the current phase. `switchGraph()` could be called twice with the same ID: once from `GraphManager.init{}` and once from `StelekitApp`'s `LaunchedEffect`. The second call silently cancelled the first initialization scope, crashed the graph load in progress, and triggered a second load that hit an unguarded `Throwable` path.

In each case, the fix was a runtime guard added after the crash. The guards work, but they are invisible to new contributors and require the same discipline in every future call site.

## Decision

**Encode invariants in types, not in documentation or runtime checks.**

Specifically, for SteleKit code:

### 1. State machines → sealed classes

Whenever a class uses multiple nullable or mutable fields that collectively represent a state machine, replace them with a single `sealed interface` or `sealed class`. Intermediate impossible states become structurally unexpressible.

```kotlin
// ✗ Before: two fields, one state machine — impossible window between mutations
private val _activeRepositorySet = MutableStateFlow<RepositorySet?>(null)
private var _pendingMigration: Deferred<Unit> = ...

// ✓ After: one field, all states visible
sealed interface GraphState {
    data object Empty : GraphState
    data class Loading(val graphId: String, val migration: Deferred<RepositorySet>) : GraphState
    data class Ready(val graphId: String, val repos: RepositorySet) : GraphState
}
private val _state = MutableStateFlow<GraphState>(GraphState.Empty)
```

### 2. Convention-only rules → structural enforcement

When a rule applies to every call site (e.g. "every Flow must end with `catchDbError()`"), create a typed wrapper that applies the rule automatically. Make the safe path shorter than the unsafe path.

```kotlin
// ✗ Before: convention, easy to forget
queries.selectAllPages().asFlow().mapToList(DB).map { ... }

// ✓ After: structural — guard cannot be omitted
queries.selectAllPages().asDbFlowList(DB) { it.toModel() }
// asDbFlowList() is defined once in DbFlowExtensions.kt; it always applies catchDbError()
```

### 3. Void returns that carry outcomes → Either / sealed result types

When a function has meaningful failure modes that callers should handle, return a typed result rather than `Unit` or `void`. The compiler rejects an exhaustive `when` with missing branches.

```kotlin
// ✗ Before: void return — double-call is silent
fun switchGraph(id: String)

// ✓ After: callers must handle AlreadyActive at compile time
sealed interface SwitchResult {
    data class Started(val migration: Deferred<RepositorySet>) : SwitchResult
    data class AlreadyActive(val repos: RepositorySet) : SwitchResult
}
fun switchGraph(id: String): SwitchResult
```

### 4. Opt-in annotations for escape hatches

When a rule must allow occasional exceptions (e.g. migration writers need raw DB access), use `@RequiresOptIn` / `@OptIn` so callers explicitly declare the exception. Unannotated code cannot access the restricted API.

```kotlin
@RequiresOptIn(message = "Route writes through DatabaseWriteActor instead")
annotation class DirectSqlWrite
```

### 5. `catch (e: Throwable)` at lifecycle boundaries

Any coroutine that loads application-critical data (graph, schema, migration) must catch `Throwable`, not just `Exception`. `OutOfMemoryError`, `NoClassDefFoundError`, and other JVM `Error` subclasses are not `Exception`s and propagate uncaught without this guard, crashing the process silently.

## Rationale

Documentation and code review catch some violations, but not:
- First contributions from developers unfamiliar with the rule
- Copy-paste of old code that didn't follow the rule
- Refactors that split or inline a guarded path
- Timing races where the invariant is violated between two correct individual calls

Types catch all of these at compile time, before CI, before review, before production.

The codebase already demonstrates the pattern works:
- `Either<DomainError, T>` replaced nullable returns and thrown exceptions at repository boundaries — zero regressions since
- `@DirectSqlWrite` + `RestrictedDatabaseQueries` made raw write bypass a compile error — zero bypass violations since
- `asDbFlowList()` / `asDbFlowOrNull()` made the DB-closed guard structural — eliminated the entire class of missing-catchDbError bugs

## Alternatives Considered

| Option | Rejected because |
|--------|-----------------|
| Documentation + CLAUDE.md rules | Already tried; produced the three crashes above. Rules in markdown are invisible to the compiler and drift from the code over time. |
| Runtime guards at each call site | These are the patches we've been writing after each crash. They work locally but require the same discipline at every future call site and are invisible to new contributors. |
| Linting / static analysis rules | Detekt can catch some patterns but cannot enforce cross-file invariants (e.g. "this Flow must end with this specific extension") without a custom rule plugin. Type-level enforcement requires no tooling configuration. |
| Test coverage for every call site | Tests validate behavior but not structure. A test for `catchDbError` being present in method A doesn't prevent method B from being written without it. The `UpgradeResilienceTest` catches violations after the fact; the sealed-class approach prevents them before commit. |

## Consequences

**Accepted trade-offs:**
- More types to define upfront. A `sealed interface` for a two-state machine feels heavy. This overhead is real but one-time; the type pays for itself on the first prevented bug.
- Refactoring existing code is incremental, not atomic. The current `GraphManager` still uses nullable fields. ADR-010 establishes the direction; migration happens as touch points arise.

**Follow-up actions (not blocking acceptance of this ADR):**
1. Migrate `GraphManager._activeRepositorySet` + `_pendingMigration` to `sealed GraphState` — eliminates the double-`switchGraph` race structurally rather than via the runtime guard added in `6c6a4ebc9b`.
2. Change `switchGraph()` to return `SwitchResult` so `LaunchedEffect` cannot silently re-trigger initialization.
3. Update CLAUDE.md to reference this ADR as the canonical rule for new state machine code.

**Standard deviation:** None. This ADR extends the patterns already established by `Either<DomainError, T>`, `@DirectSqlWrite`, and `DbFlowExtensions.kt`. It names and generalises what the codebase has been moving toward.
