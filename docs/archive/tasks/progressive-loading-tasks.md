# Atomic Tasks: Progressive Data Loading (Phase 1)

## Objective
Implement repository-level pagination for pages and blocks to support infinite scrolling and reduce memory pressure.

## Prerequisites
- [x] UUID-Native Block Storage ([DB-001](docs/tasks/uuid-native-block-storage.md))
- [x] Multi-Graph Support ([MG-001](docs/tasks/multi-graph-support.md))

## Atomic Tasks

### Task 1.1: Update PageRepository Interface (1h)
**Scope**: Add paginated query methods to the `PageRepository` interface.

**Files**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/GraphRepository.kt`

**Implementation**:
```kotlin
interface PageRepository {
    // ...
    fun getPages(limit: Int, offset: Int): Flow<Result<List<Page>>>
    fun searchPages(query: String, limit: Int, offset: Int): Flow<Result<List<Page>>>
}
```

**Validation**:
- Interface compiles.

**Status**: ✅ Completed

---

### Task 1.2: Implement Pagination in SqlDelightPageRepository (2h)
**Scope**: Implement the new interface methods using `SteleDatabase.sq` queries.

**Files**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/SqlDelightPageRepository.kt`

**Implementation**:
- Use `queries.selectAllPagesPaginated(limit.toLong(), offset.toLong())`.
- Use `queries.selectPagesByNameLike("%$query%")` (existing) but apply `drop(offset).take(limit)` or add a new paginated query to `.sq`.

**Validation**:
- Unit test for `getPages` with different offsets and limits.

**Status**: ✅ Completed

---

### Task 1.3: Update BlockRepository Interface for Paginated References (1h)
**Scope**: Add paginated linked references method to `BlockRepository`.

**Files**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/GraphRepository.kt`

**Implementation**:
```kotlin
interface BlockRepository {
    // ...
    fun getLinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Result<List<Block>>>
}
```

**Validation**:
- Interface compiles.

**Status**: ✅ Completed

---

### Task 1.4: Implement Paginated References in SqlDelightBlockRepository (2h)
**Scope**: Add SQL query for paginated references and implement in repository.

**Files**:
- `kmp/src/commonMain/sqldelight/com/logseq/kmp/db/SteleDatabase.sq` (add query)
- `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/SqlDelightBlockRepository.kt`

**Implementation**:
- Add `selectBlocksWithContentLikePaginated` to `.sq`.
- Implement `getLinkedReferences` in `SqlDelightBlockRepository`.

**Validation**:
- Unit test with large number of references.

**Status**: ✅ Completed

---

## Phase 2: ViewModel & UI Infinite Scroll

### Task 2.1: Refactor LogseqViewModel for Paginated Pages (2h)
**Scope**: Replace the full `getAllPages()` observer with a paginated approach.

**Files**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/LogseqViewModel.kt`
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/AppState.kt`

**Implementation**:
- Update `AppState` to include `regularPagesOffset: Int` and `hasMoreRegularPages: Boolean`.
- Add `loadMoreRegularPages()` to `LogseqViewModel`.
- Modify `init` to load the first batch (e.g., 50 pages).

**Validation**:
- Logs show SQL queries with LIMIT/OFFSET.

**Status**: ✅ Completed

---

### Task 2.2: Implement Infinite Scroll in "All Pages" View (1h)
**Scope**: Update the UI to detect scroll position and load more pages.

**Files**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/App.kt`

**Implementation**:
- Use `LazyListState` to detect when the user is near the bottom of the list.
- Call `viewModel.loadMoreRegularPages()`.

**Validation**:
- UI successfully appends more pages as the user scrolls.

**Status**: ✅ Completed

---

### Task 2.3: Paginate Linked References in PageView (2h)
**Scope**: Apply pagination to the linked references section of a page.

**Files**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/ReferencesPanel.kt`

**Implementation**:
- Add `loadMoreReferences()` functionality.
- Update UI to show a "Load More" button or use infinite scroll for references.

**Status**: ✅ Completed

---

## Phase 3: GraphLoader Optimization

### Task 3.1: Skip Block Parsing in METADATA_ONLY Mode (2h)
**Scope**: Optimize `GraphLoader` to avoid processing blocks during initial scan.

**Files**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/db/GraphLoader.kt`

**Implementation**:
- In `parsePageWithoutSaving`, if `mode == METADATA_ONLY`, do not call `processParsedBlocks`.
- Ensure `page.isContentLoaded` is set to `false`.
- Ensure `Page` properties are still extracted from the first block if present.

**Validation**:
- Initial graph scan is significantly faster (measure duration).
- "All Pages" view still shows all pages.
- Clicking a page triggers a full load correctly.

**Status**: ✅ Completed

---

### Task 3.2: Background Full Indexing (3h)
**Scope**: Implement a background worker to fully load pages for search.

**Files**:
- `kmp/src/commonMain/kotlin/com/logseq/kmp/db/GraphLoader.kt`
- `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/LogseqViewModel.kt`

**Implementation**:
- Add `indexRemainingPages()` to `GraphLoader`.
- Trigger this after Phase 1 completion in `LogseqViewModel`.

**Status**: ✅ Completed

---

## Dependency Visualization
```
Task 1.1 ──→ Task 1.2 ──→ Task 2.1 ──→ Task 2.2
Task 1.3 ──→ Task 1.4 ──→ Task 2.3
Task 2.1 ──→ Task 3.1 ──→ Task 3.2
```

## Next Step Recommendation
Start with **Task 1.1: Update PageRepository Interface** as it is the foundation for paginating the "All Pages" view.
