# ADR-001: `GraphManager`-Owned Capture Enrichment Coordinator

**Date**: 2026-08-27
**Status**: Accepted

## Context

`CaptureActivity` needs a `PageNameIndex` (and a resolved `TopicEnricher`) to auto-link
and suggest topics for shared text, but unlike every existing consumer of
`PageNameIndex` (`StelekitViewModel`, and transitively `ImportViewModel`/
`TagSuggestionEngine`), `CaptureActivity` can be cold-started by the Android share
sheet without `MainActivity`/`StelekitViewModel` ever running in this process
(requirements.md, "Existing machinery to reuse"). There is therefore no guarantee a
`PageNameIndex` already exists for the active graph when a capture session needs one,
and building one is not free — trie construction over the full page-name set is the
documented `OutOfMemoryError` risk on large graphs (`PageNameIndex.kt`'s own doc
comment: "hundreds of thousands of nodes" on 8,000+ page graphs).

Rapid concurrent text-change events (fast typing or a paste) must not race two
`PageNameIndex` constructions for the same capture session (AC #8) — the anti-pattern
this rule exists to rule out (a bare nullable field checked outside a lock) is already
present elsewhere in this codebase (`AndroidPhotoPickerLauncher.pendingResult`), safe
there only because of a single-callback guarantee that does not hold for a text field.

Two things needed to be decided: (1) what class encapsulates "one graph's
`PageNameIndex` + resolved `TopicEnricher`," and (2) what owns the race-safe,
memoized construction of that class per graph.

## Decision

1. A new, small `CaptureEnrichmentCoordinator` class (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/CaptureEnrichmentCoordinator.kt`)
   owns exactly: one `PageNameIndex`, one resolved `TopicEnricher`, and budget/timeout-bounded
   wrapper methods (`scan()`, `enhance()`) around `ImportService.scan()` and
   `TopicEnricher.enhance()`. It owns no write path — accepting a suggestion is
   `CaptureViewModel`'s concern (see the plan's Epic 4.1/4.2), not the coordinator's.

2. `GraphManager` — not `CaptureViewModel`, not a new standalone singleton — owns the
   race-safe, memoized construction: `suspend fun getOrCreateEnrichmentCoordinator(): CaptureEnrichmentCoordinator?`,
   guarded by a `Mutex` around a `Pair<GraphId, Deferred<CaptureEnrichmentCoordinator>>?`
   field, scoped to `activeGraphJobs[graphId]` — the exact `CoroutineScope` `GraphManager`
   already cancels on the next `switchGraph()`/`shutdown()`.

## Rationale

**Why `GraphManager`, structurally, not just by convention**: `GraphManager` already
owns every primitive this construction needs and nothing else in the codebase does.
`_activeRepositorySet` (`GraphManager.kt:79`) is the only source of a `PageRepository`
for the active graph; `activeGraphJobs: MutableMap<GraphId, CoroutineScope>`
(`GraphManager.kt:94`) is the only per-graph `CoroutineScope` whose lifetime already
matches "as long as this graph is active" (not a ViewModel's lifetime, not an
Activity's). Building the coordinator anywhere else — inside `CaptureViewModel`, say —
would require either duplicating this per-graph scope-and-invalidation bookkeeping
`GraphManager` already implements for `switchGraph()`'s own idempotency guard
(`GraphManager.kt:532-540` — "rapid concurrent triggers for the same graph id must not
race two initializations," the identical shape this feature needs one layer up), or
coupling the `PageNameIndex`'s lifetime to `CaptureActivity`'s instead of the graph's
— torn down every time the sheet closes even though the next capture 30 seconds later
targets the identical graph.

**Why the type is `commonMain`, not `androidApp`, even though only `CaptureActivity`
(Android-only, per requirements' explicit scope) consumes it today**: this is not a
style preference, it is a compile-time constraint. `androidApp` depends on `kmp`
(`androidApp/build.gradle.kts:104`, `implementation(project(":kmp"))`) — never the
reverse. `GraphManager` lives in `kmp/src/commonMain` and must return a
`CaptureEnrichmentCoordinator` from its own method; an `androidApp`-declared type
cannot appear in a `kmp`-module method signature at all. Placing
`CaptureEnrichmentCoordinator` in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/domain/`
— next to `PageNameIndex`, `ImportService`, `TopicEnricher`, all of which it composes —
is therefore the only placement that compiles, and it happens to also match every
sibling type it depends on.

**Why not fold this into `StelekitViewModel`'s existing `pageNameIndex`**: doing so
would change `StelekitViewModel`'s scope ownership for that field from its own
ViewModel-owned scope to `GraphManager`'s `graphScope` — a lifecycle change interacting
with `App.kt`'s `key(activeGraphId) { ... }` teardown/rebuild timing
(`App.kt:400`), which is outside this feature's "additive wiring, not a refactor of
shared domain code" constraint (requirements.md). `research/architecture.md` Q1
recommends leaving `StelekitViewModel.pageNameIndex` untouched for v1 and flags the
resulting duplicate-trie-build cost (when the main app and `CaptureActivity` are both
alive in the same process) as a bounded, rare, and explicitly deferred follow-up
— not a v1 requirement.

## Consequences

- `GraphManager` gains one new public suspend method and two new private fields
  (`coordinatorMutex`, `coordinatorFor`), plus a lazily-built `LlmProviderRegistry`
  constructed from its own existing `platformSettings` field (no new constructor
  parameter — see the plan's Pattern Decisions table).
- `CaptureActivity`/`CaptureViewModel` reach the coordinator via
  `steleApp.graphManager?.getOrCreateEnrichmentCoordinator()` with no
  `StelekitViewModel` dependency, satisfying the cold-start requirement.
- `StelekitViewModel.pageNameIndex` (`StelekitViewModel.kt:518`) is unchanged; a second,
  independent `PageNameIndex` may exist transiently when both `MainActivity` and
  `CaptureActivity` are alive concurrently for the same graph. This is a known,
  accepted, bounded cost (PF-7 in `research/pitfalls.md`), not a defect.
- A future story could consolidate the two `PageNameIndex` owners behind
  `GraphManager.getOrCreateEnrichmentCoordinator()` without another architectural
  rewrite, since `GraphManager` already has the right shape to absorb that later.
