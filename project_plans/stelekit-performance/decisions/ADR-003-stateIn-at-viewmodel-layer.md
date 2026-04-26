# ADR-003: stateIn(WhileSubscribed) at ViewModel Layer vs. Repository Layer

**Status**: Accepted
**Date**: 2026-04-24
**Deciders**: Tyler Stapler
**Feature**: SteleKit Performance — Story 2 (Cache Invalidation Wiring)

---

## Context

`SqlDelightBlockRepository.getBlocksForPage()` returns a `Flow<Result<List<Block>>>` backed by
`queries.selectBlocksByPageUuidUnpaginated(pageUuid).asFlow().mapToList(PlatformDispatcher.DB)`.
Each collector of this flow creates an independent SQLDelight `Query.Listener` registration,
which causes an independent SQL re-execution every time `notifyListeners` fires.

During a normal page view, at least two composables collect blocks for the same page: the
outliner tree and the block editor. If additional composables (breadcrumbs, backlink count,
sidebar) also collect, the fan-out multiplies. The research synthesis confirmed this is the
hottest read path in the app.

The question is where to apply `stateIn(SharingStarted.WhileSubscribed(5_000))` to multicast
the flow.

Three placement options were considered:

| Option | Placement | Summary | Key trade-off |
|--------|-----------|---------|---------------|
| A: ViewModel layer | `StelekitViewModel` or a `PageViewModel` | `stateIn` applied to the flow returned by `blockRepository.getBlocksForPage()` | Correct; matches community and JetBrains guidance; ViewModel lifetime equals the graph-navigation scope |
| B: Repository layer | Inside `SqlDelightBlockRepository.getBlocksForPage()` | `stateIn` applied inside the repository; all callers share the cached flow | Repository must own a `CoroutineScope`; scope management is non-trivial; violates layering |
| C: Compose layer | `collectAsStateWithLifecycle()` in each composable | No `stateIn`; rely on Compose's lifecycle-aware collection | Each composable is an independent collector; N composables = N DB listeners; does not solve the fan-out |

---

## Decision

**Choose Option A: apply `stateIn(SharingStarted.WhileSubscribed(5_000))` at the ViewModel
layer.**

The ViewModel already owns the coroutine scope tied to the navigation backstack entry
(or graph lifetime). Applying `stateIn` here is the approach recommended in:
- JetBrains' "Making your Kotlin coroutines flow in Android" (Google I/O 2022)
- Manuel Vivo's "A safer way to collect flows from Android UIs" (2021)
- The SQLDelight community discussion on Kotlin Slack that the research synthesis cites

`SharingStarted.WhileSubscribed(5_000)` with a 5-second stop timeout means:
- While any composable is collecting, the upstream SQL query runs exactly once.
- When the user navigates away (all composables leave composition), the timeout prevents
  the query from being cancelled immediately. If the user navigates back within 5 seconds,
  the `StateFlow` is still live — no re-query required.
- After 5 seconds with no subscriber, the upstream is cancelled and the next collection
  triggers a fresh SQL query.

This approach requires zero changes to `SqlDelightBlockRepository`. The reactive flow already
re-emits after every write via SQLDelight's `notifyListeners`. The ViewModel's `StateFlow`
multicasts that single re-emission to all composable collectors simultaneously.

Option B (repository layer) is architecturally wrong: repositories in this codebase do not own
coroutine scopes. The `CLAUDE.md` rule states that classes stored in `remember { }` must own
their scope internally. By extension, repository classes — which live longer than any composable
but shorter than the process — must have their scope managed by the graph's `RepositorySet`
scope, not tied to a Compose scope. Embedding `stateIn` inside the repository would require the
repository to hold a `CoroutineScope` with the correct lifetime, which is exactly the pattern
that `RepositoryFactoryImpl.createRepositorySet()` manages at a higher level.

Option C (Compose layer only) does not solve the fan-out — it is the status quo.

---

## Consequences

**Positive**:
- N Compose subscribers sharing one `StateFlow` → one SQLDelight query instead of N.
- SQLDelight's `notifyListeners` fires once per write; all subscribers receive the update via
  the shared `StateFlow` without an additional DB round-trip.
- 5-second keep-alive eliminates redundant re-queries during short navigation transitions
  (e.g., open command palette, close, return to page).
- No changes to `SqlDelightBlockRepository` — the fix is entirely in the ViewModel layer.
- The pattern is consistent with `PageNameIndex` (already uses `stateIn` on `getAllPages()`).

**Negative / Trade-offs**:
- The `stateIn` `initialValue` must be chosen: `Result.success(emptyList())` is safe and
  causes composables to render an empty state on first collection before the first emission.
  If the ViewModel navigates to a page, composables will briefly see `emptyList()` before the
  first SQL query completes. This is acceptable — the composables already handle empty-list
  states for loading.
- The 5-second keep-alive means the SQL query stays registered with SQLDelight for 5 seconds
  after the last subscriber departs. During that window, `notifyListeners` (triggered by writes
  to other pages) still re-executes this query. This is a minor resource use — one extra SQL
  query per write event during the 5-second window. For a single-user notes app, this is
  negligible.
- The `stateIn` is page-scoped: when the user navigates to a different page, the ViewModel
  must update which `pageUuid` feeds the `stateIn`. This requires re-creating the flow (or
  using a `flatMapLatest` on a `pageUuid: StateFlow<String>`). The exact integration depends
  on whether `StelekitViewModel` uses a single `pageUuid` `StateFlow` or recreates the ViewModel
  on page change.

**Rejected alternatives**:
- Repository-layer `stateIn`: violates the no-scope-in-repository rule; scope lifecycle is
  ambiguous.
- Compose-layer-only collection: status quo; does not reduce DB query fan-out.
- `shareIn(SharingStarted.Eagerly)` at repository layer: keeps the query alive forever, even
  when no UI is showing the page. Wastes resources; unnecessary for a notes app.

---

## Implementation Notes

- Task: Story 2, Task 2.5
- Primary file: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`
  (or a new `PageViewModel` if page state is extracted per ADR-001 of the all-pages feature)
- Pattern:
  ```kotlin
  private val _blocksForPage: StateFlow<Result<List<Block>>> =
      blockRepository.getBlocksForPage(currentPageUuid)
          .stateIn(
              scope = viewModelScope,          // ViewModel coroutine scope
              started = SharingStarted.WhileSubscribed(5_000),
              initialValue = Result.success(emptyList())
          )

  val blocksForPage: StateFlow<Result<List<Block>>> = _blocksForPage
  ```
- If `currentPageUuid` changes during navigation, use `flatMapLatest`:
  ```kotlin
  private val _blocksForPage: StateFlow<Result<List<Block>>> =
      currentPageUuidFlow
          .flatMapLatest { uuid -> blockRepository.getBlocksForPage(uuid) }
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(5_000),
              initialValue = Result.success(emptyList())
          )
  ```
- The benchmark test (Story 3, Task 3.5) validates that the second navigation to the same
  page within 5 seconds executes zero SQL statements.
- Reference: `PageNameIndex.kt` uses `SharingStarted.WhileSubscribed(5_000)` on `getAllPages()`
  — this is the existing precedent in the codebase.
