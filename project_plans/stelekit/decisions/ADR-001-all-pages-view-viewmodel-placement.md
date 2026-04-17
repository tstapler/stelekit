# ADR-001: AllPagesViewModel as Standalone Screen ViewModel

**Status**: Accepted
**Date**: 2026-04-16
**Feature**: All Pages View

## Context

The current `AllPagesScreen` composable (in `App.kt`) receives `pages: List<Page>` and `hasMore: Boolean` directly from `AppState`, where `StelekitViewModel` drives pagination via `loadMoreRegularPages()`. This loads all non-journal pages into `AppState.regularPages` — a list also used by the sidebar. The feature needs sortable columns, per-column sort direction, a name filter, a page-type toggle (journal/non-journal/all), backlink count per page, and multi-select with bulk delete.

Adding all that state to `AppState` would bloat the central state object with screen-local concerns and re-render the entire layout tree on every keystroke in the filter field.

## Decision

Introduce a dedicated `AllPagesViewModel` in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/` that owns all All Pages screen state:
- `pages: StateFlow<List<PageRow>>` (sorted, filtered view)
- `sortColumn: StateFlow<SortColumn>` + `sortAscending: StateFlow<Boolean>`
- `filterQuery: StateFlow<String>`
- `pageTypeFilter: StateFlow<PageTypeFilter>` (ALL / JOURNAL / NON_JOURNAL)
- `selectedUuids: StateFlow<Set<String>>`
- `isLoading: StateFlow<Boolean>`

`PageRow` is a display-layer value class: `data class PageRow(val page: Page, val backlinkCount: Int)`.

`StelekitViewModel.loadMoreRegularPages()` and `AppState.regularPages` are left untouched for the sidebar. `AllPagesViewModel` calls `pageRepository.getPages(limit, offset)` + `blockRepository.getLinkedReferences(pageName)` independently.

## Rationale

- **Single Responsibility**: central `AppState` stays lean; screen-local sort/filter/selection state lives in screen scope.
- **Performance**: filter TextField recompositions are isolated to `AllPagesScreen` subtree.
- **Testability**: `AllPagesViewModel` can be tested without a full `AppState` or `StelekitViewModel`.
- **Precedent**: `JournalsViewModel` and `SearchViewModel` already follow this pattern.

## Consequences

- Positive: `AppState` does not grow; filter debounce is encapsulated; bulk-delete confirmation dialog is local.
- Negative: `AllPagesViewModel` must be created and wired in `GraphContent` alongside the other per-screen ViewModels.
- Risk: backlink counts require one `getLinkedReferences` call per page on first load. Mitigated by computing counts in a batch via a new SQL query (see Task 1.2).
