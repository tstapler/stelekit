# Search Improvements — Implementation Plan

**Feature**: World-class local-first search experience  
**Status**: Implemented (commit 9feaff181, April 2026)  
**Target**: JVM + Android (KMP)

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Functional Requirements](#functional-requirements)
3. [Non-Functional Requirements](#non-functional-requirements)
4. [Architecture Overview](#architecture-overview)
5. [Dependency Graph](#dependency-graph)
6. [Story Breakdown](#story-breakdown)
   - [Story 1: FTS5 Page Search + BM25 Ranking](#story-1-fts5-page-search--bm25-ranking)
   - [Story 2: Multi-Token Search + Ranked Result Merging](#story-2-multi-token-search--ranked-result-merging)
   - [Story 3: Snippet Rendering](#story-3-snippet-rendering)
   - [Story 4: Phrase Search + Sanitizer Redesign](#story-4-phrase-search--sanitizer-redesign)
   - [Story 5: Search Context + Data Type Filtering](#story-5-search-context--data-type-filtering)
   - [Story 6: Recent Searches](#story-6-recent-searches)
   - [Story 7: Keyboard-First UX Polish](#story-7-keyboard-first-ux-polish)
7. [Known Issues](#known-issues)
8. [ADR Index](#adr-index)
9. [Test Strategy](#test-strategy)

---

## Executive Summary

SteleKit's current search has solid infrastructure (FTS5, triggers, `highlight()`) but most of it is wired incorrectly or not wired at all. The highest-leverage fixes are:

1. The `highlight()` result is fetched from SQLite but discarded before the UI sees it — fix this first because it is zero schema change and immediately visible.
2. BM25 ordering replaces the `ORDER BY b.id` sort — one line of SQL, dramatic relevance improvement.
3. `pages_fts` mirrors the existing `blocks_fts` pattern — small schema migration, unlocks ranked page title search.
4. The sanitizer strips `"` which breaks phrase queries — targeted fix with careful escaping.
5. Context/type filters and recent searches are progressive enhancements on top of the above.

---

## Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| SR-01 | Page titles searched via FTS5 with BM25 ranking | Must |
| SR-02 | Block content searched via FTS5 with BM25 ranking | Must |
| SR-03 | Multi-word query: each token searched independently, results ranked by combined score | Must |
| SR-04 | Snippet with highlighted match context rendered in search results UI | Must |
| SR-05 | Phrase search: `"exact phrase"` syntax passes through to SQLite FTS5 | Must |
| SR-06 | Page title matches rank above block content matches in merged results | Must |
| SR-07 | Search scope filter: All / Pages only / Blocks only / Current page / Journal / Favorites | Should |
| SR-08 | Data type filter: Titles / Content / Properties / Backlinks | Should |
| SR-09 | Recent searches: last 10 queries persisted per session, shown when input is empty | Should |
| SR-10 | Keyboard navigation in `[[` autocomplete: arrows select, Enter inserts, Escape dismisses | Must |
| SR-11 | Keyboard navigation in full SearchDialog: all existing nav maintained after filter additions | Must |

---

## Non-Functional Requirements

| Attribute | Target |
|-----------|--------|
| Search latency (p95) | < 150 ms from query change to results rendered, graph <= 50k blocks |
| Debounce | 300 ms (existing, keep) |
| Snippet length | 1-2 sentences / ~160 chars per result |
| Backward compatibility | Existing `SearchRequest` callers must not break |
| Schema migrations | SQLDelight migration file; no manual SQL at runtime |
| Testability | Repository layer fully testable with in-memory SQLite (existing pattern) |

---

## Architecture Overview

### Current Data Flow

```
User types
  -> SearchViewModel.onQueryChange (300ms debounce)
  -> SearchRepository.searchWithFilters(SearchRequest)
  -> SqlDelightSearchRepository
      -> queries.searchBlocksByContentFts(sanitized, limit, offset)   [FTS5, no BM25]
      -> queries.selectPagesByNameLike("%query%")                      [LIKE, no FTS]
  -> SearchResult(blocks, pages)
  -> SearchViewModel builds List<SearchResultItem>  [highlight discarded here]
  -> SearchDialog renders titles only
```

### Target Data Flow

```
User types
  -> SearchViewModel.onQueryChange (300ms debounce)
  -> SearchRepository.searchWithFilters(SearchRequest + SearchScope + DataTypeFilter)
  -> SqlDelightSearchRepository
      -> buildFtsQuery(rawQuery): FtsQuery  [safe passthrough of "phrase", multi-token]
      -> queries.searchBlocksByContentFts(ftsQuery, limit, offset)    [BM25 ORDER BY]
      -> queries.searchPagesByNameFts(ftsQuery, limit)                [NEW, BM25]
      -> mergeAndRank(pageResults, blockResults): List<RankedResult>   [title bonus]
  -> RankedSearchResult(rankedItems: List<RankedResult>)
  -> SearchViewModel maps to SearchResultItem, preserves snippet field
  -> SearchDialog renders snippet below title
  -> FilterBar renders scope chips above search field
```

### Component Diagram

```
┌─────────────────────────────────────────────────────────┐
│ UI Layer                                                  │
│  SearchDialog.kt          AutocompleteMenu.kt             │
│  FilterBar (new)          SnippetText (new composable)    │
└────────────────────────┬────────────────────────────────┘
                         │ StateFlow<SearchUiState>
┌────────────────────────▼────────────────────────────────┐
│ ViewModel Layer                                           │
│  SearchViewModel.kt  (extended: scope, filters, recents) │
└────────────────────────┬────────────────────────────────┘
                         │ SearchRequest (extended)
┌────────────────────────▼────────────────────────────────┐
│ Repository Layer                                          │
│  SearchRepository (interface — extended)                  │
│  SqlDelightSearchRepository (implementation)              │
│  FtsQueryBuilder (new: safe multi-token / phrase builder) │
│  SearchRanker (new: BM25 score merge, title bonus)        │
└────────────────────────┬────────────────────────────────┘
                         │ SQLDelight generated queries
┌────────────────────────▼────────────────────────────────┐
│ Database Layer                                            │
│  SteleDatabase.sq  (new queries, new pages_fts table)    │
│  Migration 2.sq    (CREATE VIRTUAL TABLE pages_fts)      │
└─────────────────────────────────────────────────────────┘
```

---

## Dependency Graph

Stories must be delivered in the following order. Stories within the same level are independent and can be parallelised.

```
Level 0 (no blockers):
  Story 1 — FTS5 Page Search + BM25 Ranking        [schema + repository + SQL]
  Story 3 — Snippet Rendering                       [no schema change needed]
  Story 4 — Phrase Search + Sanitizer Redesign      [no schema change needed]

Level 1 (requires Story 1):
  Story 2 — Multi-Token Search + Ranked Merging     [needs pages_fts + BM25 from Story 1]

Level 2 (requires Story 2):
  Story 5 — Context + Data Type Filtering           [needs unified ranked result model]

Level 3 (independent of search quality, but UX requires Story 2+):
  Story 6 — Recent Searches                         [session-only state, no schema]
  Story 7 — Keyboard-First UX Polish                [UI layer only]
```

Critical path: **Story 1 → Story 2 → Story 5**

---

## Story Breakdown

---

### Story 1: FTS5 Page Search + BM25 Ranking

**Goal**: Replace `LIKE` page search with FTS5 and apply BM25 ordering to both page and block queries.

**Acceptance Criteria**:
- `pages_fts` virtual table exists and is kept in sync via triggers
- `searchPagesByNameFts` query uses `rank` for `ORDER BY`
- `searchBlocksByContentFts` query uses `ORDER BY rank` (BM25, lower is better)
- Existing `selectPagesByNameLike` query remains (used by autocomplete callers that don't need ranking)
- Schema migration applied correctly in both fresh-install and upgrade paths
- All existing tests pass; new repository tests confirm BM25 order

**Tasks**:

#### Task 1.1 — Add `pages_fts` schema and migration
**Files**: `SteleDatabase.sq`, new `Migration_2.sq` (or equivalent SQLDelight migration)  
**Effort**: 1-2 h

Add virtual table and triggers mirroring `blocks_fts`:

```sql
CREATE VIRTUAL TABLE pages_fts USING fts5(
    name,
    content=pages,
    content_rowid=rowid,       -- pages uses uuid PK not rowid; use a rowid alias
    tokenize='porter unicode61'
);
CREATE TRIGGER pages_ai AFTER INSERT ON pages BEGIN
    INSERT INTO pages_fts(rowid, name) VALUES (last_insert_rowid(), new.name);
END;
CREATE TRIGGER pages_ad AFTER DELETE ON pages BEGIN
    INSERT INTO pages_fts(pages_fts, rowid, name) VALUES('delete', old.rowid, old.name);
END;
CREATE TRIGGER pages_au AFTER UPDATE OF name ON pages BEGIN
    INSERT INTO pages_fts(pages_fts, rowid, name) VALUES('delete', old.rowid, old.name);
    INSERT INTO pages_fts(rowid, name) VALUES (new.rowid, new.name);
END;
```

Note: `pages` uses a TEXT `uuid` primary key without an explicit `rowid`. SQLite assigns an implicit `rowid` to every table unless `WITHOUT ROWID` is used — `pages` does not use `WITHOUT ROWID`, so `last_insert_rowid()` and trigger `old.rowid` / `new.rowid` work correctly.

The migration must also populate `pages_fts` for existing rows:
```sql
INSERT INTO pages_fts(rowid, name) SELECT rowid, name FROM pages;
```

#### Task 1.2 — Add `searchPagesByNameFts` SQL query
**Files**: `SteleDatabase.sq`  
**Effort**: 30 min

```sql
searchPagesByNameFts:
SELECT p.*,
       rank AS bm25_score
FROM pages_fts pf
JOIN pages p ON p.rowid = pf.rowid
WHERE pages_fts MATCH :query || '*'
ORDER BY rank
LIMIT :limit;
```

#### Task 1.3 — Update `searchBlocksByContentFts` to use BM25
**Files**: `SteleDatabase.sq`  
**Effort**: 30 min

Change `ORDER BY b.id` to `ORDER BY rank`. The `rank` column is synthesised by FTS5 for the matched virtual table row — it must be referenced as the alias on the FTS table side, not the joined `blocks` table. Rewrite as:

```sql
searchBlocksByContentFts:
SELECT
    b.uuid, b.page_uuid, b.parent_uuid, b.left_uuid, b.content,
    b.level, b.position, b.created_at, b.updated_at, b.properties, b.version,
    highlight(blocks_fts, 0, '<em>', '</em>') AS highlight,
    rank AS bm25_score
FROM blocks_fts bm
JOIN blocks b ON b.id = bm.rowid
WHERE blocks_fts MATCH :query || '*'
ORDER BY rank
LIMIT :limit OFFSET :offset;
```

Note: `highlight()` column index argument (`0`) refers to the index in the FTS virtual table's column list, not the joined table. `blocks_fts` has one column — `content` — so the index is `0`, not `2` as in the current query. The existing `highlight(blocks_fts, 2, ...)` is wrong and returns empty strings.

#### Task 1.4 — Update `SqlDelightSearchRepository` to call new page query
**Files**: `SqlDelightSearchRepository.kt`  
**Effort**: 1 h

- Add `searchPagesByTitle(query, limit)` implementation using `searchPagesByNameFts`
- Update `searchWithFilters` to use the new FTS page query
- Carry `bm25_score` through the result model (see Story 2 for full merging)

---

### Story 2: Multi-Token Search + Ranked Result Merging

**Goal**: "2025 Taxes" surfaces both exact and partial matches ranked by relevance; page title matches rank above block body matches.

**Acceptance Criteria**:
- Query "2025 Taxes" matches pages/blocks containing either or both tokens
- Results containing both tokens rank above single-token matches (BM25 handles this naturally)
- A page whose title contains the query tokens ranks above a block whose body contains them
- `SearchResult` carries a unified ranked list, not separate `pages`/`blocks` lists
- `SearchViewModel` consumes the ranked list and produces ordered `SearchResultItem`s

**Tasks**:

#### Task 2.1 — Introduce `RankedResult` model and extend `SearchResult`
**Files**: `GraphRepository.kt`  
**Effort**: 1 h

Add to `GraphRepository.kt`:

```kotlin
data class RankedResult(
    val item: SearchResultItem.Type,  // page or block
    val score: Double,                // normalised, higher is better
    val snippet: String?              // from highlight()
)

// Extend SearchResult
data class SearchResult(
    val blocks: List<Block>,          // kept for backward compat
    val pages: List<Page>,            // kept for backward compat
    val rankedItems: List<RankedResult> = emptyList(),
    val totalCount: Int,
    val hasMore: Boolean
)
```

Use a sealed type or a union model — do not introduce a new `SearchResultItem` variant here; the UI mapping happens in the ViewModel.

#### Task 2.2 — Implement `SearchRanker` utility
**Files**: new `kmp/src/commonMain/kotlin/dev/stapler/stelekit/search/SearchRanker.kt`  
**Effort**: 2 h

Responsible for:
1. Converting FTS5 `rank` (negative float, lower = more relevant) to a normalised 0–1 score
2. Applying a configurable title-match bonus (e.g., `PAGE_TITLE_BONUS = 0.3`)
3. Interleaving page and block results in a single sorted list

```kotlin
object SearchRanker {
    const val PAGE_TITLE_BONUS = 0.3

    fun merge(
        pageResults: List<PageFtsResult>,
        blockResults: List<BlockFtsResult>
    ): List<RankedResult>
}
```

BM25 `rank` values from SQLite FTS5 are negative (more negative = better match). Normalise per-type before combining:
- `normalised = 1.0 / (1.0 - rawRank)` keeps the value in (0, 1]
- Apply `PAGE_TITLE_BONUS` to page results after normalisation

#### Task 2.3 — Wire `SearchRanker` into `SqlDelightSearchRepository`
**Files**: `SqlDelightSearchRepository.kt`  
**Effort**: 1 h

`searchWithFilters` calls both FTS queries, passes results to `SearchRanker.merge`, sets `rankedItems` on the returned `SearchResult`.

#### Task 2.4 — Update `SearchViewModel` to consume `rankedItems`
**Files**: `SearchViewModel.kt`  
**Effort**: 1 h

When `rankedItems` is non-empty, build the result list from it in rank order instead of the current pages-then-blocks grouping. Maintain the `CreatePageItem` logic.

---

### Story 3: Snippet Rendering

**Goal**: Show the highlighted match context beneath the result title in both `SearchDialog` and `AutocompleteMenu`.

**Acceptance Criteria**:
- `SearchResultItem.BlockItem` carries a `snippet: String?` field
- `SearchResultItem.PageItem` carries a `snippet: String?` field (may be null for title-only matches)
- `SearchDialog` renders snippet as secondary text below the title (max 2 lines, ellipsized)
- `AutocompleteMenu` renders snippet for block items (page items show namespace instead)
- `<em>` tags in snippet text are rendered as bold/highlighted spans, not as raw HTML
- No snippet shown when `highlight()` returns an empty string

**Tasks**:

#### Task 3.1 — Thread `highlight` field through the model stack
**Files**: `GraphRepository.kt`, `SearchViewModel.kt`  
**Effort**: 1 h

Add `snippet: String?` to `SearchResultItem.BlockItem` and `SearchResultItem.PageItem`. Update `SearchViewModel` to populate `snippet` from `RankedResult.snippet`. This is the primary data-threading task — no SQL changes needed; `highlight()` is already fetched.

#### Task 3.2 — Fix `highlight()` column index in SQL (part of Task 1.3)
This is already called out in Task 1.3 — the column index must be `0`, not `2`. Snippet rendering will produce empty strings until this is fixed.

#### Task 3.3 — Add `SnippetText` composable
**Files**: new `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SnippetText.kt`  
**Effort**: 2 h

Parses `<em>...</em>` tags in the snippet string and renders them as an `AnnotatedString` with bold weight. Falls back to plain text rendering if parsing produces an error.

```kotlin
@Composable
fun SnippetText(snippet: String, modifier: Modifier = Modifier)
```

#### Task 3.4 — Render snippet in `SearchDialog`
**Files**: `SearchDialog.kt`  
**Effort**: 1 h

Update `SearchResultRow` to accept an optional `snippet` parameter. Render `SnippetText` below the title when non-null and non-empty, with `maxLines = 2` and `overflow = TextOverflow.Ellipsis`.

#### Task 3.5 — Render snippet in `AutocompleteMenu`
**Files**: `AutocompleteMenu.kt`  
**Effort**: 1 h

Block autocomplete items show the snippet. Page items show namespace path (or nothing for root pages).

---

### Story 4: Phrase Search + Sanitizer Redesign

**Goal**: Allow `"exact phrase"` queries to pass through to SQLite FTS5 while still protecting against malformed operator sequences.

**Acceptance Criteria**:
- Query `"meeting notes"` performs an FTS5 phrase search
- Query `hello world` continues to work (multi-token implicit AND, prefix on last token)
- Malformed queries (`"unclosed`, `OR AND`, bare `:`) do not throw SQLiteException
- The sanitizer is unit-tested with a table of (input, expected-FTS5-string) pairs
- No change to the `MATCH ... || '*'` suffix — prefix matching still applies to the last unquoted token

**Tasks**:

#### Task 4.1 — Redesign `FtsQueryBuilder`
**Files**: new `kmp/src/commonMain/kotlin/dev/stapler/stelekit/search/FtsQueryBuilder.kt`  
**Effort**: 3 h

Replace the single `sanitizeFtsQuery` method in `SqlDelightSearchRepository` with a dedicated class. The new builder must:

1. Detect balanced double-quote pairs and preserve them as phrase segments
2. Strip any lone/unbalanced `"` characters
3. Preserve tokens outside of phrase segments
4. Append `*` only to the last unquoted token (for prefix matching of in-progress words)
5. Reject dangerous bare operators: lone `OR`, `AND`, `NOT` at start of query → strip
6. Collapse multiple spaces

Safe passthrough rules (as a finite-state parser, not regex replacement):

```
"meeting notes" taxes
  → "meeting notes" taxes*

"unclosed phrase taxes
  → unclosed phrase taxes*     (unbalanced quote stripped)

OR AND taxes
  → taxes*                     (leading operators stripped)

taxes::property
  → taxes                      (colon stripped, no prefix on property syntax)
```

#### Task 4.2 — Wire `FtsQueryBuilder` into repository
**Files**: `SqlDelightSearchRepository.kt`  
**Effort**: 30 min

Replace the inline `sanitizeFtsQuery` call with `FtsQueryBuilder.build(rawQuery)`. Both `searchBlocksByContent` and `searchWithFilters` use the builder.

#### Task 4.3 — Unit tests for `FtsQueryBuilder`
**Files**: new `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/search/FtsQueryBuilderTest.kt`  
**Effort**: 1 h

Parametrised test table covering: balanced phrases, unbalanced quotes, leading operators, mixed phrase+token, empty string, whitespace-only, injection attempt (`;`, `--`).

---

### Story 5: Search Context + Data Type Filtering

**Goal**: Scope search to All / Pages / Blocks / Current page / Journal / Favorites, and toggle searched fields (titles, content, properties, backlinks).

**Acceptance Criteria**:
- `SearchRequest` is extended with `scope: SearchScope` and `dataTypes: Set<DataType>` fields
- Default values maintain current behaviour (scope=All, dataTypes=setOf(TITLES, CONTENT))
- `SearchScope.CURRENT_PAGE` requires `pageUuid` to be non-null; repository validates this
- `FilterBar` composable renders scope chips horizontally above the search field in `SearchDialog`
- Active filter is visually distinct (filled chip vs outlined chip)
- `[[` autocomplete is unaffected by scope filters (always searches pages, scope=All)
- Filter state is held in `SearchViewModel` as `StateFlow`, not in `SearchRequest` itself

**Tasks**:

#### Task 5.1 — Define `SearchScope` and `DataType` enums
**Files**: `GraphRepository.kt`  
**Effort**: 30 min

```kotlin
enum class SearchScope { ALL, PAGES_ONLY, BLOCKS_ONLY, CURRENT_PAGE, JOURNAL, FAVORITES }
enum class DataType { TITLES, CONTENT, PROPERTIES, BACKLINKS }
```

Extend `SearchRequest`:
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

#### Task 5.2 — Implement scope filtering in `SqlDelightSearchRepository`
**Files**: `SqlDelightSearchRepository.kt`, `SteleDatabase.sq`  
**Effort**: 3 h

Scope → SQL filter mapping:

| Scope | Additional WHERE clause |
|-------|------------------------|
| ALL | (none) |
| PAGES_ONLY | skip block query entirely |
| BLOCKS_ONLY | skip page query entirely |
| CURRENT_PAGE | `AND b.page_uuid = :pageUuid` |
| JOURNAL | pages: `AND p.is_journal = 1`; blocks: JOIN pages, filter `is_journal = 1` |
| FAVORITES | pages: `AND p.is_favorite = 1`; blocks: JOIN pages, filter `is_favorite = 1` |

Add scoped variants of the FTS queries to `SteleDatabase.sq` rather than building WHERE clauses in Kotlin to keep type safety. Add:
- `searchBlocksByContentFtsScoped` (adds `AND b.page_uuid = ?` parameter)
- `searchBlocksByContentFtsJournal`
- `searchBlocksByContentFtsFavorites`
- `searchPagesByNameFtsJournal`
- `searchPagesByNameFtsFavorites`

#### Task 5.3 — Implement data-type filtering
**Files**: `SqlDelightSearchRepository.kt`, `SteleDatabase.sq`  
**Effort**: 2 h

| DataType | Action |
|----------|--------|
| TITLES | run page FTS query |
| CONTENT | run block FTS query |
| PROPERTIES | query `properties` table via FTS or LIKE on value column |
| BACKLINKS | query `block_references` joined with blocks for content |

For PROPERTIES: add `searchPropertiesByValue` query using `LIKE '%:query%'` on the `properties.value` column (FTS5 on properties is a future enhancement — LIKE is sufficient for v1).

#### Task 5.4 — `FilterBar` composable
**Files**: new `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/FilterBar.kt`  
**Effort**: 2 h

Horizontal scrolling `LazyRow` of `FilterChip` for each `SearchScope`. Tapping a chip calls `viewModel.onScopeChange(scope)`. Keyboard shortcut: `Cmd/Ctrl+1..6` cycles through scopes (optional, document but don't block story).

#### Task 5.5 — Wire `FilterBar` into `SearchDialog` and `SearchViewModel`
**Files**: `SearchDialog.kt`, `SearchViewModel.kt`  
**Effort**: 1 h

Add `scope: StateFlow<SearchScope>` and `onScopeChange(SearchScope)` to `SearchViewModel`. `SearchDialog` renders `FilterBar` between the text field and the results list. The autocomplete path in `BlockEditor` / `AutocompleteMenu` bypasses the filter.

---

### Story 6: Recent Searches

**Goal**: Remember the last 10 queries in the current session and surface them when the search field is empty.

**Acceptance Criteria**:
- Empty search field shows "Recent Searches" header followed by up to 10 recent query strings
- Tapping a recent query re-populates the search field and runs the search
- Recent queries are stored in `SearchViewModel` (session memory — not persisted to disk)
- Clearing the search field while recent queries are present shows them
- Arrow keys navigate recent queries when input is empty

**Tasks**:

#### Task 6.1 — Add `recentQueries` to `SearchViewModel`
**Files**: `SearchViewModel.kt`  
**Effort**: 1 h

```kotlin
private val _recentQueries = MutableStateFlow<List<String>>(emptyList())
```

After a successful search (results non-empty), if the query is distinct from the last entry, prepend it and cap at 10. Expose via `SearchUiState.recentQueries: List<String>`.

#### Task 6.2 — Render recent queries in `SearchDialog`
**Files**: `SearchDialog.kt`  
**Effort**: 1 h

When `uiState.query.isBlank()` and `uiState.recentQueries.isNotEmpty()`, render the recent list instead of the empty state. Use the same `SearchResultRow` composable with `subtitle = "Recent"` and a clock icon prefix. Keyboard navigation extends naturally since `selectedIndex` already handles the list.

---

### Story 7: Keyboard-First UX Polish

**Goal**: Full keyboard navigation in `[[` autocomplete matching the quality of the full `SearchDialog`.

**Acceptance Criteria**:
- Arrow Up/Down in `AutocompleteMenu` moves selection (currently `selectedIndex` is passed in but the source of arrow events is unclear)
- Enter commits selected item and closes menu
- Escape closes menu without insertion
- Tab moves to next item (optional, document)
- Selected item is visually scrolled into view in `LazyColumn`
- Focus does not escape to parent editor when autocomplete is open

**Tasks**:

#### Task 7.1 — Audit `BlockEditor` → `AutocompleteMenu` keyboard event flow
**Files**: `BlockEditor.kt`, `AutocompleteMenu.kt`  
**Effort**: 1 h (investigation)

Map where `onKeyEvent` is currently handled and whether arrow events are consumed before reaching the menu. Document findings before implementing.

#### Task 7.2 — Implement keyboard event handler in `AutocompleteMenu`
**Files**: `AutocompleteMenu.kt`  
**Effort**: 2 h

The `Popup` composable in `AutocompleteMenu` does not receive keyboard events by default in Compose Multiplatform — events go to the focused text field in `BlockEditor`. The fix is to handle arrow keys in the `BlockEditor`'s `onKeyEvent` modifier and drive `selectedIndex` state that is passed down to `AutocompleteMenu`.

Key contract:
- `BlockEditor` owns `selectedIndex` state when autocomplete is open
- Arrow Up/Down increments/decrements `selectedIndex`, consumes the event (returns `true`)
- Enter triggers `onItemSelected(items[selectedIndex])`, consumes event
- Escape triggers `onDismiss()`, consumes event

#### Task 7.3 — Scroll selected item into view in `AutocompleteMenu`
**Files**: `AutocompleteMenu.kt`  
**Effort**: 1 h

Add `val listState = rememberLazyListState()` and a `LaunchedEffect(selectedIndex)` that calls `listState.animateScrollToItem(selectedIndex)`. This mirrors the existing pattern in `SearchDialog`.

#### Task 7.4 — Fix header-skip logic for both dialogs
**Files**: `SearchDialog.kt`  
**Effort**: 30 min

The current header-skip logic in `SearchDialog` has an edge-case bug: if two `Header` items are consecutive (empty section), it skips only one. Replace with a `while` loop:

```kotlin
Key.DirectionDown -> {
    var next = (selectedIndex + 1) % uiState.results.size
    while (uiState.results[next] is SearchResultItem.Header) {
        next = (next + 1) % uiState.results.size
    }
    selectedIndex = next
    true
}
```

---

## Known Issues

### Bug 1: `highlight()` column index is wrong [SEVERITY: High, Fixed in Story 1 / Task 1.3]

**Description**: The current query uses `highlight(blocks_fts, 2, '<em>', '</em>')`. The `highlight()` function's second argument is a zero-based index into the FTS virtual table's own column list. `blocks_fts` has one column (`content`), so the only valid index is `0`. Index `2` silently returns an empty string. This is why snippets have never appeared in the UI despite the SQL fetching them.

**Fix**: Change argument to `0`. Confirmed by reading FTS5 documentation: the `iCol` argument selects from the virtual table's defined columns in declaration order.

**Files Affected**: `SteleDatabase.sq`

---

### Bug 2: Race condition in search job cancellation [SEVERITY: Medium]

**Description**: `SearchViewModel.searchJob?.cancel()` followed immediately by `scope.launch { delay(300); ... }` is correct, but if `searchWithFilters` emits multiple values (it is a `Flow`), later emissions may update state after the job was logically superseded by a newer query.

**Current exposure**: `SqlDelightSearchRepository.searchWithFilters` emits exactly once and does not use `collectLatest`. Risk is low today but will increase if the repository is changed to emit incremental updates.

**Mitigation**:
- In `SearchViewModel.onQueryChange`, use `searchJob?.cancel()` before launching (already done).
- When collecting inside the job, add a `isActive` check before calling `_uiState.update`.
- Longer term: switch to `collectLatest` when the repository emits a stream.

**Files Affected**: `SearchViewModel.kt`

---

### Bug 3: FTS5 index desync on bulk import [SEVERITY: Medium]

**Description**: The `blocks_ai` / `blocks_au` / `blocks_ad` triggers maintain `blocks_fts` for single-row DML. However, `GraphWriter` (the bulk importer) uses `INSERT OR REPLACE` which fires a DELETE trigger followed by an INSERT trigger. If the importer deletes all blocks for a page and re-inserts them in a single transaction, the triggers fire correctly. But if the import uses a mechanism that bypasses triggers (e.g., direct SQLite `executeSql` with `PRAGMA foreign_keys = OFF`), the FTS index becomes stale.

**Mitigation**:
- Add an `integrity-check` query variant for `blocks_fts` and `pages_fts` that can be called post-import.
- Document in `GraphWriter` that FTS triggers must remain enabled during import.
- Add a `rebuildFtsIndex()` function to `SqlDelightSearchRepository` for recovery.

**Files Affected**: `GraphWriter.kt`, `SqlDelightSearchRepository.kt`

---

### Bug 4: `pages_fts` rowid mapping relies on implicit rowid [SEVERITY: Medium]

**Description**: The `pages` table uses `uuid TEXT NOT NULL PRIMARY KEY`. SQLite assigns an implicit `rowid` to this table, but the relationship between `rowid` and `uuid` is not stable across `DELETE` + re-`INSERT` of the same logical page (rowid is re-assigned). The `pages_fts` `content_rowid` mapping will break if a page is deleted and a new row with the same `uuid` is inserted — SQLite will assign a new `rowid` that differs from the one stored in `pages_fts`.

**Mitigation**: Add a `rowid INTEGER` alias column to `pages` via an explicit `CREATE TABLE` modification, or use `WITHOUT ROWID` with a different FTS mapping approach. The cleanest fix is to add an `id INTEGER PRIMARY KEY` auto-increment column to `pages` to provide a stable integer key for FTS mapping — matching the pattern used by `blocks` (which has `id INTEGER PRIMARY KEY AUTOINCREMENT`).

This is a schema change that requires a migration and updates to all `pages`-touching mappers. Defer to a targeted task within Story 1 (Task 1.1 extended scope).

**Files Affected**: `SteleDatabase.sq`, migration file, `SqlDelightSearchRepository.kt`, `DataMappers.kt`

---

### Bug 5: Multi-word BM25 — token OR vs AND semantics [SEVERITY: Low]

**Description**: FTS5 `MATCH 'token1 token2*'` performs an implicit AND (both tokens must appear). "2025 Taxes" will not match a document containing only "Taxes 2024". To surface partial-match documents at lower rank (as the feature request specifies), the query must use explicit OR: `MATCH 'token1 OR token2*'`.

**Mitigation**: `FtsQueryBuilder` (Story 4 / Task 4.1) should emit `token1 OR token2 OR ... OR lastToken*` for multi-token queries without explicit phrase syntax. Documents matching all tokens will still rank higher than single-token matches because BM25 sums term frequencies. Add a test case for this in `FtsQueryBuilderTest`.

**Files Affected**: `FtsQueryBuilder.kt` (new), `FtsQueryBuilderTest.kt` (new)

---

### Bug 6: Consecutive `Header` items causing infinite skip loop [SEVERITY: Low, Fixed in Story 7 / Task 7.4]

**Description**: The header-skip logic in `SearchDialog` uses a single conditional check, not a loop. If two consecutive `SearchResultItem.Header` entries appear (possible if a section is empty and the "Create page" item is also a header), the `while` condition is never revisited and the selected item lands on the second `Header`.

**Fix**: Replace `if` with `while` as shown in Task 7.4.

**Files Affected**: `SearchDialog.kt`

---

### Bug 7: `sanitizeFtsQuery` strips `*` which prevents user-intended prefix queries [SEVERITY: Low]

**Description**: The current regex `["()*:^~{}\[\]!]` strips `*`. A user typing `kub*` expecting prefix matching gets `kub` — which still works because the query appends `*`. But a user typing `"exact*"` intending a phrase-with-prefix gets `exact` — the phrase markers are stripped first, then the trailing `*` is stripped, resulting in a plain token search. The redesigned `FtsQueryBuilder` handles this correctly.

**Files Affected**: `SqlDelightSearchRepository.kt` (replaced by `FtsQueryBuilder`)

---

## ADR Index

| ADR | Title | File |
|-----|-------|------|
| ADR-001 | FTS5 virtual table for page name search | `project_plans/stelekit/decisions/ADR-001-pages-fts-virtual-table.md` |
| ADR-002 | Ranking strategy: BM25 + title bonus | `project_plans/stelekit/decisions/ADR-002-ranking-strategy.md` |
| ADR-003 | Search context model: SearchRequest extension | `project_plans/stelekit/decisions/ADR-003-search-context-model.md` |
| ADR-004 | FTS5 sanitizer redesign | `project_plans/stelekit/decisions/ADR-004-fts-sanitizer-redesign.md` |

---

## Test Strategy

### Unit Tests (no database)

| Test Class | Coverage |
|------------|----------|
| `FtsQueryBuilderTest` | All sanitizer cases: balanced phrases, unbalanced quotes, leading operators, injection, empty/blank |
| `SearchRankerTest` | BM25 normalisation, title bonus application, merge order invariants |
| `SearchViewModelTest` | Recent queries: prepend, dedup, cap at 10; scope state transitions |

### Integration Tests (in-memory SQLite)

| Test Class | Coverage |
|------------|----------|
| `SqlDelightSearchRepositoryTest` | FTS page search returns ranked results; block FTS returns BM25 order; `highlight()` returns non-empty string; scope filters restrict results correctly |
| `FtsIndexSyncTest` | INSERT/UPDATE/DELETE on `blocks` and `pages` keep FTS index in sync; verify via direct FTS query after each operation |

### Regression Tests

- `JournalParseReproTest` already exists at `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/integration/JournalParseReproTest.kt` — ensure it continues to pass after schema migration.
- Add a `SearchMigrationTest` that opens a pre-migration database fixture, applies migration 2, and verifies `pages_fts` contains all pre-existing page names.

### Manual Acceptance Checklist

- [ ] Type `"meeting notes"` — results show only blocks/pages containing the exact phrase
- [ ] Type `2025 taxes` — results include pages matching either token, ranked by relevance
- [ ] Block result shows a two-line snippet with match terms bolded
- [ ] Scope chip "Journal" — only journal pages and blocks within journal pages appear
- [ ] Empty search field — recent queries appear
- [ ] Arrow keys navigate both `SearchDialog` and `[[` autocomplete without focus escaping to editor
