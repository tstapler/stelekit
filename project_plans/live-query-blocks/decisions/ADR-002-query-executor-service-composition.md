# ADR-002: `QueryExecutor` as a Composing Service, Not a Repository

**Status**: Accepted
**Date**: 2026-09-15
**Feature**: Live Query Blocks

---

## Context

Live query blocks need to execute four filter forms against the graph: task-marker (`task`/`todo`), page-property, journal date-range (`between`), and page-ref/tag. Per `research/architecture.md` §2, only the page-ref/tag form is a natural single-repository extension (`BlockSearchRepository`, same shape as `searchBlocksByContent`). The other three either need a same-table scan not currently exposed anywhere (`task`) or a join across `PageRepository` and `BlockReadRepository` (`page-property`, `between`) — no single existing repository interface cleanly owns all four.

### Options considered

**Option A — Widen `BlockSearchRepository` to own all four query methods**

Rejected: would force `BlockSearchRepository` (currently a narrow, single-table role interface, same tier as `BlockReadRepository`/`PropertyRepository`/`PageRepository` per the composite-interface doc comment on `BlockRepository.kt:4-12`, which explicitly recommends narrow role interfaces for consumers) to depend on `PageRepository` for the page-property and between forms — inverting the existing layering for a capability only the query feature needs.

**Option B — New `QueryRepository` interface with a SQLDelight-backed and `DatalogBlockRepository`-fake implementation, following the standard prod/fake parity rule**

Rejected: a `QueryRepository`'s "read" contract would just be a thin wrapper delegating to three other repositories it would have to be constructed with — it has no direct database access of its own for 3 of 4 forms. Giving it prod/fake parity would mean re-implementing composition logic twice for no benefit (the composition itself has no backend-specific behavior to fake).

**Option C — `QueryExecutor` plain class composing existing repository interfaces via `Flow.combine`/`flatMapLatest` (chosen)**

A `QueryExecutor(blockSearchRepository, propertyRepository, pageRepository, blockReadRepository)` with `fun executeQuery(query: SimpleQuery): Flow<Either<DomainError, List<Block>>>` is additive: it depends on already-`RepositoryFactory`-provided narrow interfaces without widening any of them. It needs no direct DB access and therefore no prod/fake parity pair of its own — it's testable by composing in-memory fakes (`DatalogBlockRepository`, etc.), exactly as `businessTest` already does for ViewModel-level tests. This is a Service Layer (Fowler, PoEAA) sitting above Repository, not a repository itself.

## Decision

Introduce `dev.stapler.stelekit.query.QueryExecutor` as a plain class, instantiated per-graph (same lifetime pattern as `DatabaseWriteActor` — constructed inside `RepositoryFactoryImpl.createRepositorySet()` and stored on `RepositorySet.queryExecutor`) so a query block on Graph A can never observe Graph B's repositories after `GraphManager.switchGraph()`. The one form needing genuinely new repository-level read access — `task`/`todo` marker matching — gets a new method on `BlockSearchRepository` (`findBlocksWithTaskMarker`), with the standard SQLDelight + `DatalogBlockRepository` fake-parity pair, because that predicate has no existing SQL surface anywhere.

## Consequences

- `BlockSearchRepository`, `PropertyRepository`, `PageRepository`, `BlockReadRepository` stay narrow and unwidened.
- `QueryExecutor` has no direct database access itself; correctness of its composition logic is covered by `businessTest`-tier tests against in-memory fakes, not a resilience test against a closed DB (that guard lives in the repositories it composes, which already carry `catchDbError()`).
- Must be re-derived from `GraphManager.activeRepositorySet` per collection (not captured once) to avoid the stale-graph-subscription hazard documented in `research/pitfalls.md` §5.
