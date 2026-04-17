# ADR-003: Search Context Model — SearchRequest Extension vs. New Type

**Status**: Proposed  
**Date**: 2026-04-12  
**Deciders**: Tyler Stapler

---

## Context

The existing `SearchRequest` data class has placeholder fields (`propertyFilters`, `dateRange`, `pageUuid`) that are defined but entirely unused in the current implementation. Story 5 requires adding scope and data-type filtering, and there are two structural options: extend the existing `SearchRequest`, or introduce a parallel type.

The `SearchRepository` interface is implemented by `SqlDelightSearchRepository` (production) and stub implementations in tests. All callers currently pass `SearchRequest(query = query, limit = 20)` without setting any other fields.

## Decision

**Extend `SearchRequest`** with two new fields using default values that preserve current behaviour:

```kotlin
data class SearchRequest(
    val query: String? = null,
    val pageUuid: String? = null,
    val scope: SearchScope = SearchScope.ALL,
    val dataTypes: Set<DataType> = setOf(DataType.TITLES, DataType.CONTENT),
    val propertyFilters: Map<String, String> = emptyMap(),
    val dateRange: DateRange? = null,
    val limit: Int = 50,
    val offset: Int = 0
)
```

All existing call sites (`SearchViewModel`, `StelekitViewModel.searchPages`) continue to compile without modification because they use named parameters and the new fields have defaults.

The `SearchScope` and `DataType` enums are defined in `GraphRepository.kt` alongside `SearchRequest` and `SearchResult`, maintaining cohesion in the search contract file.

Filter state for the UI is held in `SearchViewModel` as separate `StateFlow` properties, not embedded in `SearchRequest`. The ViewModel constructs a `SearchRequest` from its state fields on each query. This separates UI interaction state (which changes per user action) from the repository query contract.

```kotlin
// SearchViewModel
private val _scope = MutableStateFlow(SearchScope.ALL)
private val _dataTypes = MutableStateFlow(setOf(DataType.TITLES, DataType.CONTENT))

fun onScopeChange(scope: SearchScope) { _scope.value = scope; triggerSearch() }
```

## Alternatives Considered

### Option A: New `SearchFilter` type, leaving `SearchRequest` unchanged

Introduce a `SearchFilter` data class carrying scope and data types, passed separately to an overloaded `searchWithFilters(request: SearchRequest, filter: SearchFilter)`.

**Rejected because**:
- Two parallel types for the same concept creates confusion about which one to use.
- The existing unused `propertyFilters` and `dateRange` fields on `SearchRequest` already establish the pattern that filtering lives in `SearchRequest`.
- Overloading `searchWithFilters` would require updating the `SearchRepository` interface, breaking implementations.

### Option B: Replace `SearchRequest` with a sealed class hierarchy

Model different search modes as subtypes: `AllSearch`, `ScopedSearch`, `FilteredSearch`.

**Rejected because**:
- Overkill for the current complexity. The data class with defaults handles all combinations.
- Sealed hierarchies prevent `copy()` usage across subtypes, which the ViewModel relies on.

### Option C: Hold filter state in `SearchRequest` directly (no ViewModel-level state)

Pass `SearchRequest` objects around as the single source of truth for all search configuration.

**Rejected because**:
- `SearchRequest` is a repository contract type; it should not carry transient UI interaction state (e.g., "user is hovering over the scope chip but hasn't committed").
- ViewModel-level `StateFlow` for scope/filters allows the UI to react to filter changes independently of search results loading state.

## Consequences

**Positive**:
- Zero breaking changes to existing callers — all compile without modification.
- `SearchRequest` remains the single search contract type; no proliferation of query types.
- Default values make the API additive: new filtering capabilities are opt-in.
- Scope + data-type state in `SearchViewModel` is easily testable with `StateFlow` test utilities.

**Negative / Risks**:
- `SearchRequest` grows over time as more filter dimensions are added. If it exceeds 8–10 fields, consider a builder pattern.
- The unused `propertyFilters` and `dateRange` fields now have company but remain unimplemented. Document them with `// TODO: implement in Story 5` comments so they are not overlooked.
- `pageUuid` semantics change: previously unused, now meaningful when `scope == SearchScope.CURRENT_PAGE`. Add a validation check in `SqlDelightSearchRepository.searchWithFilters`: if `scope == CURRENT_PAGE && pageUuid == null`, emit `Result.failure(IllegalArgumentException("pageUuid required for CURRENT_PAGE scope"))`.
