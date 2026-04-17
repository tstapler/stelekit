# ADR-001: TopicExtractor Placement Inside ImportService.scan()

**Date**: 2026-04-17
**Status**: Accepted

## Context

Topic extraction must run as part of the import pipeline to produce `List<TopicSuggestion>` alongside the existing `ScanResult`. Three candidate placements were evaluated:

1. Inside `ImportService.scan()` as a second internal pass, returning an extended result.
2. As a parallel `async {}` coroutine launched from `ImportViewModel`, with results combined via `awaitAll()`.
3. Entirely inside `ImportViewModel` as a private function with no separate domain object.

The existing `ImportService` is a pure `object` with no I/O. The `TopicExtractor` candidate is also a pure function. `ImportViewModel` already has one call site for `ImportService.scan()` and manages scan lifecycle via `scanJob`.

## Decision

Place `TopicExtractor` as a pure-function `object` in the `domain/` package. Call it from within `ImportService.scan()`, extending `ScanResult` to carry `topicSuggestions: List<TopicSuggestion>`. The `scan()` signature gains one new parameter: `existingNames: Set<String> = emptySet()`.

## Rationale

Option 1 wins on three grounds:

**Single call site.** `ImportViewModel.runScan()` has one call to `ImportService.scan()`. Keeping both Aho-Corasick matching and heuristic extraction inside that single call means `runScan()` requires no structural changes — it reads one additional field from the existing return type.

**Testability of `TopicExtractor` in isolation.** Placing `TopicExtractor` in `domain/` as a standalone object makes it directly testable in `businessTest` without touching `ImportViewModel` or any ViewModel test infrastructure. This mirrors the pattern established by `ImportService` itself.

**No async complexity increase.** Option 2 introduces parallel coroutines in the ViewModel. Both `ImportService.scan()` and `TopicExtractor.extract()` are synchronous CPU-bound calls; there is no latency benefit to parallelizing them since they share the same `Dispatchers.Default` context and the total combined time is well under the 500 ms budget.

Option 3 was rejected because placing business logic inside the ViewModel makes it untestable without composable test infrastructure and prevents reuse outside the import screen.

The Claude API enrichment layer is explicitly **not** placed inside `ImportService` — it is async, has I/O, and belongs in the ViewModel tier. See ADR-002 and ADR-003.

## Consequences

- `ScanResult` gains `topicSuggestions: List<TopicSuggestion> = emptyList()` — backward-compatible data class extension.
- `ImportService.scan()` gains `existingNames: Set<String> = emptySet()` — backward-compatible default parameter.
- `ImportViewModel.runScan()` must pass `matcherFlow`'s current canonical-names snapshot as `existingNames`. The snapshot is already available as the key set of `_canonicalNames` inside `PageNameIndex`.
- `TopicExtractor` is testable in `businessTest` as a pure function with no mocking required.
- Existing `ImportServiceTest` tests require no changes because the default `existingNames = emptySet()` keeps their call signatures valid.

## Patterns Applied

- Pure function / value object (domain layer has no I/O)
- Single Responsibility Principle (extraction is a separate object, not embedded in scan logic)
- Backward-compatible API extension via default parameters
