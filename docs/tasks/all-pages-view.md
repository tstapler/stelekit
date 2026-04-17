# All Pages View — Implementation Plan

**Feature ID**: APV-001
**Priority**: High (Logseq Parity — Critical)
**Estimated Total**: ~12–16 hours across 3 stories
**Last Updated**: 2026-04-16

---

## Epic Overview

### User Value
Users need a dedicated screen to audit, navigate, and manage every page in their knowledge graph. The journals view shows recent work; the search dialog finds specific pages. Neither provides a comprehensive, sortable inventory. Without an All Pages view, users cannot identify orphan pages (zero backlinks), find old pages by creation date, or bulk-remove pages they no longer need.

### Success Metrics
- All pages in the graph are visible and navigable from a single screen
- Backlink count column allows users to identify orphans (0 backlinks) at a glance
- Sort by name, backlinks, last modified, or created date works correctly asc/desc
- Filter by name reduces the visible list reactively as the user types
- Journal/non-journal toggle shows only the relevant page type
- Multi-select + bulk delete removes pages from DB and disk with confirmation
- Screen renders < 100 ms for graphs up to 10 000 pages (virtual list)

### Scope

**In Scope:**
- Dedicated `AllPagesScreen` composable replacing the current stub in `App.kt`
- `AllPagesViewModel` owning all screen-local state
- Sortable columns: Name, Backlinks, Last Modified, Created
- Name filter with 300 ms debounce
- Page type toggle: All / Journals / Pages
- Click row to navigate to page
- Multi-select (long-press on mobile, checkbox on desktop)
- Bulk delete with confirmation dialog
- Lazy backlink count loading (counts fill in after initial render)
- Entry point: left sidebar "All Pages" navigation item (already wired to `Screen.AllPages`)

**Out of Scope:**
- Namespace tree grouping (deferred to Graph View epic)
- Inline page rename from the table (use existing rename dialog)
- Export selected pages to CSV/JSON
- Undo for bulk delete (no Trash bin in this iteration)
- Backlink count via FTS5 optimization (deferred; LIKE scan is acceptable for v1)

### Constraints
- KMP: must compile and run on JVM, Android, and Web (JS). No JVM-only APIs.
- No new SQL table columns or schema migrations (this avoids migration framework overhead).
- `AppState` and `StelekitViewModel.regularPages` must not be modified — the sidebar depends on them.
- Virtual/lazy list is mandatory: `LazyColumn` with `key` already used across the app.

---

## Architecture Decisions

| ADR | File | Decision |
|-----|------|----------|
| ADR-001 | `project_plans/stelekit/decisions/ADR-001-all-pages-view-viewmodel-placement.md` | Standalone `AllPagesViewModel` — keeps `AppState` lean, follows `JournalsViewModel` / `SearchViewModel` precedent |
| ADR-002 | `project_plans/stelekit/decisions/ADR-002-all-pages-backlink-count-strategy.md` | Lazy LIKE-scan backlink counts — no schema change, counts fill in after initial render |
| ADR-003 | `project_plans/stelekit/decisions/ADR-003-all-pages-bulk-delete-safety.md` | Bulk delete via `StelekitViewModel.bulkDeletePages()` with mandatory confirmation dialog |

---

## Story Breakdown

### Story 1 — Sortable Page Table with Navigation [~1 week]

**User Value**: Users can see all pages in a sortable table and click any row to open it.

**Acceptance Criteria:**
- [ ] Screen shows all non-journal pages by default (matches Logseq default)
- [ ] Columns: Name, Backlinks (lazy 0→real), Last Modified, Created
- [ ] Clicking any column header toggles sort asc/desc
- [ ] Clicking a row navigates to that page
- [ ] Filter text field reduces visible rows as user types (≥ 300 ms debounce)
- [ ] Page type toggle (All / Journals / Pages) changes which pages are shown
- [ ] `AllPagesViewModel` unit tests cover sort, filter, and toggle logic

---

#### Task 1.1 — Add `countLinkedReferencesForPage` SQL query [2h]

**Objective**: Add a SQLDelight query to count backlinks for a single page by name, used by `AllPagesViewModel` to populate the Backlinks column lazily.

**Context Boundary**:
- Primary: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq` (~580 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt` (~188 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/GraphRepository.kt` (BlockRepository interface)
- Total: ~770 lines

**Prerequisites:**
- Familiarity with SQLDelight query syntax and the `blocks` table schema
- Understand that `getLinkedReferences(pageName)` already uses LIKE `%[[pageName]]%`

**Implementation Approach:**
1. In `SteleDatabase.sq`, add after the existing `getLinkedReferences` queries:
   ```sql
   countLinkedReferencesForPage:
   SELECT COUNT(*) FROM blocks WHERE content LIKE '%[[' || :pageName || ']]%';
   ```
2. Add `fun countLinkedReferences(pageName: String): Flow<Result<Long>>` to the `BlockRepository` interface in `GraphRepository.kt`.
3. Implement in `SqlDelightBlockRepository.kt` using `queries.countLinkedReferencesForPage(pageName).asFlow().mapToOne(PlatformDispatcher.IO).map { success(it) }`.
4. Add a no-op stub in `InMemoryRepositories.kt` returning `flowOf(success(0L))`.

**Validation Strategy:**
- Unit test in `businessTest`: create 3 blocks with `[[TestPage]]`, 1 with plain text, 1 with `[[OtherPage]]`. Assert `countLinkedReferences("TestPage")` emits 3.
- Compile check: `./gradlew :kmp:compileKotlinJvm`

**Success Criteria**: Query compiles, test passes, no change to existing `getLinkedReferences` behavior.

**INVEST Check:**
- Independent: no dependency on other APV tasks
- Negotiable: could use FTS5 MATCH in future; LIKE is fine for v1
- Valuable: enables backlink count column without full block list fetch
- Estimable: 2 hours
- Small: 1 SQL line + 3 Kotlin additions across 3 files
- Testable: pure data query, deterministic

---

#### Task 1.2 — Create `AllPagesViewModel` with sort/filter/type-toggle state [3h]

**Objective**: Implement the screen ViewModel with reactive `StateFlow` for filtered+sorted page rows, filter query, sort column, sort direction, and page type filter.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/AllPagesViewModel.kt` (new file, ~180 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/GraphRepository.kt` (PageRepository, BlockRepository interfaces)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsViewModel.kt` (pattern reference)
- Total: ~430 lines

**Prerequisites:**
- Task 1.1 complete (`countLinkedReferences` available)
- Understanding of `PageRepository.getPages(limit, offset)` and `getAllPages()`
- `StateFlow` + `combine` operator pattern (see `JournalsViewModel`)

**Implementation Approach:**
1. Define `data class PageRow(val page: Page, val backlinkCount: Int)` in the same file.
2. Define `enum class SortColumn { NAME, BACKLINKS, LAST_MODIFIED, CREATED }`.
3. Define `enum class PageTypeFilter { ALL, JOURNALS, PAGES }`.
4. In `AllPagesViewModel`:
   - `_allRows: MutableStateFlow<List<PageRow>>` — raw loaded rows, backlink counts start at 0
   - `_filterQuery: MutableStateFlow<String>` with `fun onFilterChange(q: String)`
   - `_sortColumn: MutableStateFlow<SortColumn>` + `_sortAscending: MutableStateFlow<Boolean>` with `fun toggleSort(col: SortColumn)`
   - `_pageTypeFilter: MutableStateFlow<PageTypeFilter>` with `fun setPageTypeFilter(f: PageTypeFilter)`
   - `pages: StateFlow<List<PageRow>>` derived via `combine(_allRows, _filterQuery, _sortColumn, _sortAscending, _pageTypeFilter)` + debounce 300 ms on filter
5. `init` block: call `loadAllPages()` which fetches via `pageRepository.getAllPages()` and emits rows with `backlinkCount = 0`, then launches background coroutine to fill in real counts via `blockRepository.countLinkedReferences(page.name)` for each row, updating `_allRows` incrementally.
6. Add `fun refresh()` for pull-to-refresh.

**Validation Strategy:**
- Unit test: seed 5 pages (2 journal, 3 regular). Assert `PageTypeFilter.PAGES` returns 3. Assert `PageTypeFilter.JOURNALS` returns 2. Assert `SortColumn.NAME` ascending returns alphabetical order.
- Unit test: filter "foo" with 1 match — `pages` emits list of size 1.
- Unit test: `toggleSort(NAME)` twice results in descending order.

**Success Criteria**: `./gradlew :kmp:jvmTest --tests "*AllPagesViewModelTest*"` passes.

**INVEST Check:**
- Independent: depends only on Task 1.1 (new SQL method)
- Negotiable: debounce duration, initial sort column
- Valuable: drives the entire screen's reactive behavior
- Estimable: 3 hours
- Small: single new file, ~180 lines
- Testable: pure ViewModel logic, no Compose needed

---

#### Task 1.3 — Build `AllPagesScreen` composable (table + header + filter bar) [3h]

**Objective**: Replace the stub `AllPagesScreen` in `App.kt` with a full Compose screen backed by `AllPagesViewModel`. Render sortable column headers, name filter field, page type toggle chips, and a `LazyColumn` of page rows.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/AllPagesScreen.kt` (new file, ~280 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (wire-up, remove stub, ~840 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/AllPagesViewModel.kt` (Task 1.2)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SearchDialog.kt` (filter field pattern)
- Total: ~1300 lines read / ~280 lines written

**Prerequisites:**
- Tasks 1.1 and 1.2 complete
- Understanding of `LazyColumn` with `key`, `ListItem`, `FilterChip` from Material3

**Implementation Approach:**
1. Create `AllPagesScreen.kt` in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/`.
2. `@Composable fun AllPagesScreen(viewModel: AllPagesViewModel, onPageClick: (Page) -> Unit)`:
   - Collect `pages`, `filterQuery`, `sortColumn`, `sortAscending`, `pageTypeFilter` via `collectAsState()`
   - Top section: `OutlinedTextField` for filter (value = filterQuery, onChange = viewModel::onFilterChange), three `FilterChip`s for page type (All / Journals / Pages)
   - Column header row: four `TextButton`s (Name, Backlinks, Modified, Created), each showing a sort arrow icon when active, calling `viewModel.toggleSort(col)`
   - `LazyColumn` body: each item is a `ListItem` with `headlineContent = page.name`, `supportingContent = "Backlinks: ${row.backlinkCount}"`, trailing icons for last modified date
   - `Modifier.clickable { onPageClick(row.page) }` on each item
3. In `App.kt` `ScreenRouter`, replace the existing `AllPagesScreen(...)` call with:
   ```kotlin
   is Screen.AllPages -> AllPagesScreen(
       viewModel = allPagesViewModel,
       onPageClick = { viewModel.navigateTo(Screen.PageView(it)) }
   )
   ```
4. In `GraphContent`, add `val allPagesViewModel = remember { AllPagesViewModel(repos.pageRepository, repos.blockRepository, scope) }` alongside `journalsViewModel`.
5. Thread `allPagesViewModel` through `ScreenRouter`.

**Validation Strategy:**
- Manual smoke test: open All Pages, verify list appears, click a page, verify navigation.
- Manual: type in filter field, verify list reduces.
- Manual: click column header twice, verify sort direction toggles.
- Screenshot test (jvmTest Roborazzi): `AllPagesScreen_empty` and `AllPagesScreen_with_pages`.

**Success Criteria**: `./gradlew :kmp:jvmTest` passes; All Pages screen renders with real data on desktop run.

**INVEST Check:**
- Independent: tasks 1.1 and 1.2 required; no other dependencies
- Negotiable: exact column widths, chip vs. tab bar for page type
- Valuable: first visible user-facing increment of the feature
- Estimable: 3 hours
- Small: one new composable file + small changes to App.kt
- Testable: screenshot test + manual smoke

---

### Story 2 — Multi-Select and Bulk Delete [~3–4 days]

**User Value**: Users can select multiple pages and delete them in one action, cleaning up their graph without navigating to each page individually.

**Acceptance Criteria:**
- [ ] Long-press (mobile) or checkbox (desktop) enters selection mode
- [ ] Selected rows are visually highlighted
- [ ] A bottom action bar appears in selection mode with "Delete N pages" button
- [ ] Confirming delete removes pages from DB and their `.md` files from disk
- [ ] An error notification shows if any file could not be deleted from disk
- [ ] After delete, the page list refreshes automatically
- [ ] "Select All" and "Deselect All" buttons exist in the action bar

---

#### Task 2.1 — Add selection state and `bulkDeletePages` to ViewModel and StelekitViewModel [2h]

**Objective**: Extend `AllPagesViewModel` with `selectedUuids`, `isInSelectionMode`, `toggleSelection()`, `selectAll()`, `clearSelection()`; add `bulkDeletePages(uuids: List<String>)` to `StelekitViewModel` which handles DB deletion and disk file removal.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/AllPagesViewModel.kt` (Task 1.2, +60 lines)
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (~1342 lines, +40 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` (confirm `deleteFile` exists)
- Total: ~1600 lines

**Prerequisites:**
- Task 1.2 complete
- Confirm `PlatformFileSystem` has `deleteFile(path: String): Boolean`

**Implementation Approach:**
1. In `AllPagesViewModel`, add:
   ```kotlin
   private val _selectedUuids = MutableStateFlow<Set<String>>(emptySet())
   val selectedUuids: StateFlow<Set<String>> = _selectedUuids.asStateFlow()
   val isInSelectionMode: StateFlow<Boolean> = _selectedUuids.map { it.isNotEmpty() }.stateIn(scope, SharingStarted.Eagerly, false)
   
   fun toggleSelection(uuid: String) { _selectedUuids.update { if (uuid in it) it - uuid else it + uuid } }
   fun selectAll() { _selectedUuids.update { _allRows.value.map { it.page.uuid }.toSet() } }
   fun clearSelection() { _selectedUuids.update { emptySet() } }
   ```
2. In `StelekitViewModel`, add:
   ```kotlin
   fun bulkDeletePages(uuids: List<String>) {
       scope.launch {
           val pages = uuids.mapNotNull { pageRepository.getPageByUuid(it).first().getOrNull() }
           val fileErrors = mutableListOf<String>()
           pages.forEach { page ->
               pageRepository.deletePage(page.uuid)
               page.filePath?.let { path ->
                   if (!fileSystem.deleteFile(path)) fileErrors += page.name
               }
           }
           loadMoreRegularPages(reset = true)
           if (fileErrors.isNotEmpty()) {
               notificationManager?.show("Deleted ${uuids.size} pages; ${fileErrors.size} files could not be removed from disk", NotificationType.WARNING)
           }
       }
   }
   ```

**Validation Strategy:**
- Unit test `AllPagesViewModel`: toggle, selectAll, clearSelection produce correct `selectedUuids` emissions.
- Unit test `StelekitViewModel`: `bulkDeletePages` calls `deletePage` for each UUID; calls `loadMoreRegularPages(reset=true)`.

**Success Criteria**: Tests pass; `./gradlew :kmp:jvmTest` green.

**INVEST Check:**
- Independent: relies on Task 1.2 ViewModel; no UI dependency
- Valuable: enables bulk delete user story without UI yet
- Estimable: 2 hours
- Small: ~100 lines across 2 files
- Testable: pure logic, fully unit-testable

---

#### Task 2.2 — Selection UI: checkboxes, action bar, confirmation dialog [3h]

**Objective**: Add visual selection mode to `AllPagesScreen` — checkboxes on rows, animated bottom action bar with "Delete N pages" + "Select All" + "Deselect All", and an `AlertDialog` confirmation before calling `bulkDeletePages`.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/AllPagesScreen.kt` (Task 1.3, +100 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (Task 2.1, `bulkDeletePages`)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/DiskConflictDialog.kt` (dialog pattern reference)
- Total: ~600 lines

**Prerequisites:**
- Tasks 2.1 and 1.3 complete

**Implementation Approach:**
1. Each `ListItem` in `AllPagesScreen`: add leading `Checkbox(checked = uuid in selectedUuids, onCheckedChange = { viewModel.toggleSelection(uuid) })`.
2. Add long-press gesture on mobile (`pointerInput` with `detectTapGestures(onLongPress = { viewModel.toggleSelection(uuid) })`).
3. `AnimatedVisibility(isInSelectionMode)` wrapping a `BottomAppBar` (or `Row` at bottom of Column) containing:
   - Text: "N selected"
   - `TextButton("Select All") { viewModel.selectAll() }`
   - `TextButton("Deselect All") { viewModel.clearSelection() }`
   - `Button("Delete") { showDeleteConfirm = true }`
4. `if (showDeleteConfirm) AlertDialog(...)` showing count, with confirm calling `stellekitViewModel.bulkDeletePages(selectedUuids.toList())` then `viewModel.clearSelection()`.

**Validation Strategy:**
- Manual: select 2 rows on desktop, action bar appears, cancel clears selection.
- Manual: confirm delete, rows disappear, notification shows success.
- Manual (mobile): long-press enters selection mode.
- Roborazzi screenshot: `AllPagesScreen_selectionMode`.

**Success Criteria**: `./gradlew :kmp:jvmTest` passes; manual smoke test on desktop.

**INVEST Check:**
- Independent: depends on 1.3 and 2.1
- Valuable: completes the multi-select user story end-to-end
- Estimable: 3 hours
- Small: UI additions to one existing composable
- Testable: screenshot test captures selection state

---

### Story 3 — Polish: Empty States, A11y, and Performance Hardening [~1–2 days]

**User Value**: The All Pages view is robust for edge cases (empty graph, very large graph), accessible to screen readers, and performs acceptably with 10 000+ pages.

**Acceptance Criteria:**
- [ ] Empty state shown when graph has no pages (with CTA to create first page or open search)
- [ ] Loading skeleton/spinner while initial page list loads
- [ ] Screen reader content descriptions on all interactive elements (sort headers, checkboxes, chips)
- [ ] Virtual list does not materialize off-screen rows (LazyColumn key already enforces this; confirm with profiling)
- [ ] Backlink count loading does not freeze the UI (verified by running on JVM with a 5000-page test graph)

---

#### Task 3.1 — Empty state, loading state, and accessibility annotations [2h]

**Objective**: Add `EmptyAllPages` composable for zero-page graphs, a `CircularProgressIndicator` overlay for initial load, and `semantics { }` annotations on all interactive elements.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/AllPagesScreen.kt` (Task 1.3, +60 lines)
- Supporting: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/AllPagesViewModel.kt` (add `isLoading: StateFlow<Boolean>`)
- Total: ~420 lines

**Prerequisites:**
- Tasks 1.2 and 1.3 complete

**Implementation Approach:**
1. In `AllPagesViewModel`, add `_isLoading: MutableStateFlow<Boolean>`. Set to `true` before `pageRepository.getAllPages()` emits, `false` after.
2. In `AllPagesScreen`, wrap `LazyColumn` in `when`:
   - `isLoading` → `Box { CircularProgressIndicator() }`
   - `pages.isEmpty() && !isLoading` → `EmptyAllPages(onNewPage = { /* navigate to search */ })` 
   - else → `LazyColumn { ... }`
3. Add `semantics { contentDescription = "Sort by Name ${if ascending "ascending" else "descending"}" }` to each column header button.
4. Add `semantics { role = Role.Checkbox }` to each row's checkbox.
5. Add `semantics { contentDescription = "Page: ${page.name}, ${backlinkCount} backlinks" }` to each row.

**Validation Strategy:**
- Unit test: `AllPagesViewModel` emits `isLoading = true` then `false` around `getAllPages()`.
- TalkBack/VoiceOver manual check: navigate to All Pages and confirm column headers are announced.
- Screenshot test: `AllPagesScreen_empty`.

**Success Criteria**: `./gradlew :kmp:jvmTest` passes; empty state renders correctly.

---

#### Task 3.2 — Performance verification and background count throttling [1h]

**Objective**: Verify the backlink count background loading in `AllPagesViewModel` does not overload IO threads for large graphs (5000+ pages). Add a `Semaphore`-based throttle to cap concurrent LIKE queries at 8.

**Context Boundary**:
- Primary: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/AllPagesViewModel.kt` (Task 1.2, +20 lines)
- Total: ~200 lines

**Prerequisites:**
- Task 1.2 complete

**Implementation Approach:**
1. In `loadAllPages()`, replace the raw loop launching one coroutine per page with:
   ```kotlin
   val semaphore = Semaphore(8)
   _allRows.value.chunked(50).forEach { chunk ->
       chunk.forEach { row ->
           scope.launch(Dispatchers.IO) {
               semaphore.acquire()
               try {
                   val count = blockRepository.countLinkedReferences(row.page.name).first().getOrNull() ?: 0L
                   _allRows.update { rows -> rows.map { if (it.page.uuid == row.page.uuid) it.copy(backlinkCount = count.toInt()) else it } }
               } finally {
                   semaphore.release()
               }
           }
       }
   }
   ```
2. Verify with a `runBlocking` test that 200 pages do not launch 200 simultaneous LIKE queries (mock repository records max concurrent calls).

**Validation Strategy:**
- Unit test: assert max concurrent calls ≤ 8 via a counting mock.
- Manual: run desktop app on 5000-page test graph; confirm UI remains responsive during count loading.

**Success Criteria**: No ANR or UI jank during backlink count loading; test passes.

---

## Known Issues (Identified During Planning)

### BUG-001: `isFavorite` not returned in `PageRow` [SEVERITY: Low]
**Description**: `PageRow` wraps `Page`, which includes `isFavorite`. If a user favorites a page while on the All Pages screen, the row will not show the star until `refresh()` is called — the `_allRows` StateFlow is not connected to `pageRepository.getAllPages()` reactive emissions (unlike `AppState.favoritePages`).
**Mitigation**: Call `viewModel.refresh()` in `Screen.AllPages` `LaunchedEffect(Unit)`. Long-term, subscribe `_allRows` to `pageRepository.getAllPages()` reactive flow.
**Files Affected**: `AllPagesViewModel.kt`
**Related Tasks**: Task 1.2

### BUG-002: LIKE scan performance degrades on graphs > 50 000 blocks [SEVERITY: Medium]
**Description**: `countLinkedReferencesForPage` uses `LIKE '%[[name]]%'` which is an O(n) full table scan on the `blocks` table.
**Mitigation**: The 8-coroutine throttle (Task 3.2) limits throughput. For v1, this is acceptable. A follow-up should use `blocks_fts` MATCH query with bracket-escaped page names.
**Files Affected**: `SteleDatabase.sq`, `SqlDelightBlockRepository.kt`
**Prevention**: Add a comment in `SteleDatabase.sq` above the query: `-- TODO APV-PERF: replace with FTS5 MATCH for graphs > 50k blocks`.
**Related Tasks**: Task 1.1, Task 3.2

### BUG-003: Bulk delete does not update `AllPagesViewModel._allRows` immediately [SEVERITY: Low]
**Description**: `bulkDeletePages` in `StelekitViewModel` calls `loadMoreRegularPages(reset=true)` which updates `AppState.regularPages`, but `AllPagesViewModel._allRows` is populated independently from `pageRepository.getAllPages()`. After a bulk delete, the All Pages list will not refresh until `AllPagesViewModel.refresh()` is called.
**Mitigation**: In `AllPagesScreen`, after `bulkDeletePages` confirm, also call `allPagesViewModel.refresh()`. Wire via a callback parameter `onDeleteComplete: () -> Unit`.
**Files Affected**: `AllPagesScreen.kt`, `AllPagesViewModel.kt`
**Related Tasks**: Tasks 2.1, 2.2

### BUG-004: `selectAll()` selects filtered-out rows [SEVERITY: Low]
**Description**: `selectAll()` operates on `_allRows` (all loaded rows), not the current filtered `pages` StateFlow. If a user filters to "foo" and selects all, they will delete pages not currently visible.
**Mitigation**: Change `selectAll()` to use the current `pages.value` snapshot: `_selectedUuids.update { pages.value.map { it.page.uuid }.toSet() }`.
**Files Affected**: `AllPagesViewModel.kt`
**Related Tasks**: Task 2.1

---

## Dependency Visualization

```
Task 1.1 (SQL query)
    └─→ Task 1.2 (ViewModel)
            └─→ Task 1.3 (Screen UI)  ←─ can start in parallel with 2.1
                    └─→ Task 2.2 (Selection UI)
        └─→ Task 2.1 (Selection state + bulkDelete)
                └─→ Task 2.2 (Selection UI)
                        └─→ Task 3.1 (Polish)
                                └─→ Task 3.2 (Perf throttle) [can run in parallel with 3.1]
```

**Parallel opportunities:**
- Task 3.1 and Task 3.2 can be developed simultaneously once Task 1.3 and 2.1 are complete.
- Task 1.3 can be partially built (scaffold without backlink counts) while Task 1.2 is being finished.

---

## Integration Checkpoints

**After Story 1**: The All Pages screen shows all pages in a navigable, sortable, filterable table with lazy backlink counts. No selection or delete yet. This is a functional shippable increment.

**After Story 2**: Full Logseq-parity bulk management: multi-select, bulk delete with disk cleanup, and confirmation dialog. This completes the minimum feature set.

**After Story 3**: Production quality: accessible, handles empty graphs, throttled count loading, no UI jank at scale. Ready for release.

**Final Validation Criteria**:
- [ ] All unit tests pass: `./gradlew :kmp:jvmTest`
- [ ] Manual smoke test: navigate to All Pages, sort each column, filter, page type toggle, select 2 pages, delete
- [ ] Screenshot regression: no unexpected visual changes
- [ ] Performance: 5000-page graph loads All Pages in < 500 ms; backlink counts fill within 10 s without UI freeze
- [ ] Accessibility: TalkBack/VoiceOver announces column headers and page rows correctly

---

## Context Preparation Guide

### Before Task 1.1 (SQL Query)
**Files to load:**
- `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq` — understand `blocks` schema and existing LIKE patterns
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt` — understand Flow + SQLDelight pattern
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/GraphRepository.kt` — BlockRepository interface to extend

**Concepts to understand:**
- SQLDelight named query syntax (`:paramName`)
- `asFlow().mapToOne()` pattern for scalar queries
- `PlatformDispatcher.IO` is the correct dispatcher for DB queries

### Before Task 1.2 (AllPagesViewModel)
**Files to load:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/JournalsViewModel.kt` — ViewModel pattern to follow
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/GraphRepository.kt` — PageRepository.getAllPages()
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/Models.kt` — Page data class fields

**Concepts to understand:**
- `combine()` for deriving state from multiple flows
- `stateIn(scope, SharingStarted.Eagerly, initial)` for hot StateFlows from cold flows
- `debounce()` for filter query (import `kotlinx.coroutines.flow.debounce`)

### Before Task 1.3 (AllPagesScreen composable)
**Files to load:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (lines 841–893 — existing AllPagesScreen stub + ScreenRouter)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/AllPagesViewModel.kt` (Task 1.2 output)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SearchDialog.kt` (filter field pattern)

**Concepts to understand:**
- `LazyColumn` with `key = { it.uuid }` for stable item identity
- `FilterChip` from Material3 for page type toggle
- `AnimatedContent` is already used in `ScreenRouter` — no new pattern needed for transitions

### Before Task 2.1 (Selection state + bulkDeletePages)
**Files to load:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/AllPagesViewModel.kt` (Task 1.2)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (lines 400–410 — `clear()` and page manipulation patterns)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` — confirm `deleteFile` exists

**Concepts to understand:**
- `Set<String>` selection state follows `BlockStateManager.selectedBlockUuids` precedent
- `bulkDeletePages` must call `loadMoreRegularPages(reset=true)` to refresh sidebar

### Before Task 2.2 (Selection UI)
**Files to load:**
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/AllPagesScreen.kt` (Task 1.3)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/DiskConflictDialog.kt` — AlertDialog pattern
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (bulkDeletePages, Task 2.1)

---

## File Creation / Modification Summary

| Action | File Path |
|--------|-----------|
| CREATE | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/AllPagesViewModel.kt` |
| CREATE | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/AllPagesScreen.kt` |
| MODIFY | `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq` (add 2 queries) |
| MODIFY | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/GraphRepository.kt` (add interface method) |
| MODIFY | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt` (implement new method) |
| MODIFY | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/InMemoryRepositories.kt` (stub) |
| MODIFY | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (wire ViewModel + replace stub composable call) |
| MODIFY | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (add bulkDeletePages) |

---

## Quality Gates

- [ ] Test coverage on `AllPagesViewModel`: sort, filter, page type toggle, selection, selectAll BUG-004 fix
- [ ] Test coverage on `StelekitViewModel.bulkDeletePages`: DB deletion, file deletion, error notification
- [ ] No new compiler warnings introduced
- [ ] No changes to `AppState` fields (regression guard for sidebar)
- [ ] `./gradlew :kmp:jvmTest` green
- [ ] `./gradlew :kmp:compileKotlinJvm` clean (no unused imports, no `@Suppress` added without comment)
