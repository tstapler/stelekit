# ADR-003: GlobalUnlinkedReferencesViewModel with Streaming Aggregation

**Status**: Accepted  
**Date**: 2026-04-14  
**Feature**: Multi-Word Term Highlighting & Unlinked References

---

## Context

No global unlinked-references view exists in SteleKit. The per-page `ReferencesPanel` manages its state locally with `remember { mutableStateOf }` inside the composable. That pattern works for per-page because state is discarded on navigation away, which is acceptable (the next page load re-fetches from the repository).

A global view — one that aggregates unlinked references across all pages in the active graph — has different lifecycle requirements:
1. Loading is expensive (potentially hundreds of `getUnlinkedReferences` calls).
2. State must survive navigation (user goes to check a page, then returns to the global list).
3. Accept/reject operations must dispatch through the existing `DatabaseWriteActor` path to stay serialised with other block mutations.
4. The view needs to be reachable from the command palette and from sidebar navigation.

Three architectural options were evaluated:

**Option A — Composable-local state with `rememberSaveable`**: Simpler, but `rememberSaveable` does not survive process death on Android, and storing a large list of `Block` objects in a `Bundle` is impractical. Loses scroll position and results on every navigation.

**Option B — Extend `StelekitViewModel` with global unlinked refs fields**: Avoids a new class, but `StelekitViewModel` is already large and the global refs lifecycle (scoped to an open screen) does not match the ViewModel's graph-wide lifecycle. Results would persist unnecessarily after the screen is closed.

**Option C — Dedicated `GlobalUnlinkedReferencesViewModel` (selected)**: A lightweight ViewModel instantiated inside the composable screen via `remember`. Its `CoroutineScope` is tied to the screen's `rememberCoroutineScope()`. When the user navigates away, the scope is cancelled; when they return, loading restarts. This keeps the screen self-contained without adding memory pressure between sessions.

---

## Decision

Create `GlobalUnlinkedReferencesViewModel` at:
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/GlobalUnlinkedReferencesViewModel.kt`

It receives `PageRepository`, `BlockRepository`, and `DatabaseWriteActor` (nullable, with direct `BlockRepository.saveBlock` fallback) as constructor parameters injected from the `RepositorySet` already available in the screen call site.

**Aggregation strategy**: Sequential page iteration with a target batch size of 50 results. For each page, fetch up to 20 blocks via `blockRepository.getUnlinkedReferences(page.name, limit=20, offset=0)`. Accumulate until 50 results are reached, then stop and record the page index as the resume point. "Load More" continues from that index.

For graphs with >200 pages, switch to parallel fetching via `async { }` / `awaitAll()` in batches of 5 pages, to keep total wall-clock time under 500ms.

**Accept dispatch**: `acceptSuggestion(block, pageName, matchStart, matchEnd, graphPath)` performs an in-place substring wrap (`content[0..matchStart] + "[[pageName]]" + content[matchEnd..]`) then dispatches via `writeActor.send(SaveBlockCommand(...))`. The offsets come from the UI layer (same `PAGE_SUGGESTION_TAG` annotation system used in `BlockItem`). The same version-stamp guard from ADR-002 is applied: `capturedContent` is stored alongside the offsets and validated before the write.

**Reject**: Remove from in-memory list only (no persistence in v1). Persisted dismissals are deferred to a future enhancement using a sidecar file.

**Navigation**: Add `Screen.GlobalUnlinkedReferences` to `AppState.kt`'s `sealed class Screen`. Add a route handler in `App.kt`'s `ScreenRouter`. Register a command in `StelekitViewModel.updateCommands()`. Add a sidebar item in `LeftSidebar.kt`.

---

## Consequences

**Positive**:
- Clean separation of concerns: the global screen owns its own loading and state lifecycle.
- Uses the existing `DatabaseWriteActor` path for writes — no new write serialisation infrastructure.
- Easy to test: constructor-inject mock repositories, call `loadInitial()`, assert state transitions.
- Survives `StelekitViewModel` refactors because it has no dependency on it (only on `RepositorySet` and `DatabaseWriteActor`).

**Negative / Risk**:
- State is lost on navigation (acceptable per requirements — re-loading is the correct behaviour for a freshness-sensitive view).
- For very large graphs (1000+ pages, all with many unlinked references), the initial load can take several seconds. Mitigation: show a `CircularProgressIndicator` immediately and stream results progressively so the first 50 appear quickly.
- The `remember { GlobalUnlinkedReferencesViewModel(...) }` pattern in the composable means the ViewModel is recreated on every full recomposition of the screen. The `rememberCoroutineScope()` scope handles cancellation, but care is required to avoid double-initialisation. Guard with a `loadInitialCalled` flag inside the ViewModel or use `LaunchedEffect(Unit)` in the composable.

**Validation**:
- Test: create a `GlobalUnlinkedReferencesViewModel` with an in-memory repository containing 3 pages each with 2 unlinked reference blocks. Call `loadInitial()`, assert `state.results.size == 6`, `state.hasMore == false`.
- Test: call `acceptSuggestion(...)`, assert `writeActor` received a `SaveBlockCommand` with the correct wrapped content.
- Test: call `rejectSuggestion(blockId)`, assert the block is removed from `state.results`.
