# ADR-004: Extract CaptureEnrichmentCoordinator as a Testable commonMain Collaborator

**Date**: 2026-08-10
**Status**: Accepted

## Context

`research/architecture.md` §3-4 sketches the scan/timeout/`EnrichmentState` logic as private
methods and fields directly on `CaptureViewModel` (an `androidApp`-module `AndroidViewModel`).
Planning-phase verification (`grep`/`find` across `androidApp/src/test` and
`androidApp/src/androidTest`) confirms **zero existing tests reference `CaptureViewModel`** —
only `CaptureShareTextTest.kt`, which tests the static `buildShareText` companion function, not
the ViewModel itself. `requirements.md`'s own Research Dimensions section flags this explicitly:
"existing `CaptureViewModel`/`CaptureActivity` test coverage patterns to extend" — there are
none to extend.

`CaptureViewModel` depends on a concrete `GraphManager`/`Application` and is not currently
structured for dependency injection; standing it up in a Robolectric test to exercise a new,
non-trivial async pipeline (debounced scan, timeout fallback, LLM enrichment, stub-page dedup)
is materially harder than testing a plain Kotlin class with fakes in `businessTest`.

## Decision

Extract the scan/timeout/suggestion-lifecycle/stub-creation logic into a new commonMain class,
`CaptureEnrichmentCoordinator`
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/capture/CaptureEnrichmentCoordinator.kt`),
constructed with `pageRepository: PageRepository`, `topicEnricher: TopicEnricher =
NoOpTopicEnricher()`, and an optional `coroutineScope: CoroutineScope? = null` (owning its own
`CoroutineScope(SupervisorJob() + Dispatchers.Default)` when not supplied — the same
test-injectable-scope pattern `ImportViewModel` already uses,
`ImportViewModel.kt:125-133`).

`CaptureViewModel` becomes a thin Android wrapper: it constructs exactly one
`CaptureEnrichmentCoordinator` per active graph (passing its own `viewModelScope`, never a
`rememberCoroutineScope()`), forwards `updateText()`/`save()` events into it, and forwards its
`StateFlow<EnrichmentState>` out to `CaptureScreen`.

## Rationale

Considered against two alternatives (full analysis in `implementation/plan.md`'s Step 0.5
creative pass):

1. **Inline everything in `CaptureViewModel`** (architecture.md's own sketch) — lowest
   short-term file count, but ships an entirely new async pipeline with zero unit-test coverage,
   testable only via slow/flaky instrumented tests, directly at odds with this repo's evident
   testing culture (`businessTest` exists specifically to test business logic without Android).
2. **Hoist `PageNameIndex` to `GraphManager`/graph scope** so both `StelekitViewModel` and
   `CaptureViewModel` share one warm instance — the architecturally "more correct" long-term
   fix per `research/pitfalls.md`'s P0 recommendation, but a materially bigger, cross-cutting
   change (touches every existing `PageNameIndex` consumer) that both `architecture.md` and
   `pitfalls.md` explicitly flag as belonging to a separate item, not folded silently into this
   one.

Extracting a `commonMain` collaborator (this ADR) is bounded to a single new file plus a thin
wrapper, closes the test-coverage gap directly, and does not touch any other feature's code —
unlike option 2. It is a superset, not a rejection, of architecture.md's design: the exact same
`EnrichmentState` shape, `resolveForSave`/timeout logic, and fold-before-save write ordering
architecture.md specified are implemented — only their *location* changes, from
`CaptureViewModel` to `CaptureEnrichmentCoordinator`.

## Consequences

- New file: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/capture/CaptureEnrichmentCoordinator.kt`.
- New test file: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/capture/CaptureEnrichmentCoordinatorTest.kt`,
  giving this feature its first-ever unit-tested capture-path logic.
- `CaptureViewModel` gains a `coordinatorFor(repoSet)` lazy-build-once accessor and a small
  internal test seam (see plan Story 2.1.1/4.2.1) rather than embedding the logic itself.
- `ImportViewModel` is **not** refactored to use this new coordinator — that would be a second,
  separate migration (architecture.md's own "worth flagging as a shared win in the planning
  phase" note about fixing `ImportViewModel`'s orphaned `topicEnricher`) and is out of scope
  here.
