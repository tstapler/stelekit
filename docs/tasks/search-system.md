# Task: Search & Query System Implementation

## Overview
Implement a comprehensive search and query system for Logseq KMP, combining **SQLite FTS5** for high-performance full-text search and **Datascript** for advanced structured queries (Datalog). This hybrid approach ensures both speed for common searches and flexibility for complex user queries.

## Architecture

The system uses a **Hybrid Search Architecture**:

1.  **Full Text Search (FTS)**:
    -   **Engine**: SQLite FTS5 (via SQLDelight).
    -   **Use Case**: "Omnibar" typing, simple text matching, finding blocks/pages by content.
    -   **Data Source**: `blocks_fts` virtual table (already defined in schema).
    -   **Performance**: Extremely fast, indexed, low memory footprint.

2.  **Structured Query (Datalog)**:
    -   **Engine**: `logseq-kmp-datascript` (In-Memory).
    -   **Use Case**: `{{query ...}}` blocks, complex filtering (tags, properties, ranges), graph analysis.
    -   **Data Source**: In-memory Datascript DB (synced from Repository/SQLite).
    -   **Performance**: Fast for complex joins, reactive.

## Requirements

### Functional
1.  **Simple Search (Omnibar)**:
    -   User types text -> System returns matching Pages and Blocks.
    -   **Ranking**: Exact Page Title > Page Title Partial > Block Content.
    -   **UI**: Integrated into Command Palette.
2.  **Advanced Query**:
    -   Support `{{query ...}}` syntax in blocks.
    -   Execute Datalog queries against the graph.
    -   Support standard Logseq query clauses: `and`, `or`, `not`, `between`, `property`, `page`, `tag`.
3.  **Search UI**:
    -   **Command Palette**: Enhanced to show search results.
    -   **Search Panel**: Dedicated view for deep search results (like References).

### Non-Functional
-   **Performance**: Search results < 100ms.
-   **Reactivity**: Queries update when underlying data changes.
-   **Platform**: 100% KMP compatible (JVM, Android, iOS).

## Implementation Plan

### Phase 1: Foundation & Dependencies
*Goal: Set up dependencies and verify FTS layer.*

- [ ] **Task 1.1: Add Datascript Dependency**
    -   Add `logseq-kmp-datascript` to `kmp/build.gradle.kts`.
    -   Verify compilation.
- [ ] **Task 1.2: Verify FTS5 Schema & Queries**
    -   Review `SteleDatabase.sq` (already contains `blocks_fts`).
    -   Ensure triggers (`blocks_ai`, `blocks_ad`, `blocks_au`) are working correctly via unit test.
    -   Add missing FTS queries if needed (e.g., snippet generation).

### Phase 2: Search Repository (FTS)
*Goal: Expose FTS functionality to the application.*

- [ ] **Task 2.1: Implement `SearchRepository`**
    -   Create `kmp/src/commonMain/kotlin/com/logseq/kmp/search/SearchRepository.kt`.
    -   Inject `SteleDatabase`.
    -   Implement `search(query: String): Flow<List<SearchResult>>`.
    -   Map SQL results to `SearchResult` domain model (polymorphic: Page | Block).
- [ ] **Task 2.2: Implement Result Ranking**
    -   In `SearchRepository`, implement ranking logic:
        1.  Exact Page Name Match
        2.  Partial Page Name Match
        3.  Block Content Match (using FTS rank if available, or simple heuristics).

### Phase 3: Advanced Query Engine (Datascript)
*Goal: Enable Datalog queries.*

- [ ] **Task 3.1: Datascript Integration**
    -   Update `DatalogEngine.kt` to use `logseq-kmp-datascript`.
    -   Implement `syncToDatascript()`: Mechanism to load/sync data from SQLite/Repository into Datascript DB. *Note: For MVP, load on demand or startup.*
- [ ] **Task 3.2: Query Parser & Executor**
    -   Implement `QueryParser` to parse `{{query ...}}` strings into Datalog EDN/structures.
    -   Expose `execute(query: String): Flow<List<Block>>` in `DatalogEngine`.

### Phase 4: UI Implementation
*Goal: User-facing search features.*

- [ ] **Task 4.1: Search ViewModel**
    -   Create `SearchViewModel`.
    -   Manage state: `query`, `results`, `isSearching`, `filter`.
    -   Debounce input (e.g., 300ms).
- [ ] **Task 4.2: Command Palette Integration**
    -   Update `CommandPalette.kt` to observe `SearchViewModel`.
    -   Render `SearchResultList` composable inside the palette.
    -   Handle navigation on selection (Go to Page / Go to Block).
- [ ] **Task 4.3: Search Panel (Deep Search)**
    -   Create `SearchPanel.kt` (Compose).
    -   Display full list of results with context (breadcrumbs).
    -   Support "Show more results".

### Phase 5: Testing
*Goal: Ensure correctness and performance.*

- [ ] **Task 5.1: Unit Tests**
    -   Test `SearchRepository` ranking logic.
    -   Test `DatalogEngine` with sample queries.
- [ ] **Task 5.2: Integration Tests**
    -   Test FTS triggers (insert block -> find in search).
    -   Test Datascript sync (modify block -> query updates).

## Known Issues / Risks
-   **Datascript Sync Overhead**: Keeping Datascript in sync with SQLite/Repository might be expensive. *Mitigation*: Use incremental updates or re-fetch for MVP.
-   **FTS Tokenizer**: Default SQLite tokenizer might not handle all languages perfectly. *Mitigation*: Stick to `unicode61` for now.
-   **Vector Search**: Explicitly deferred to Phase 2 (Out of Scope).

## References
-   `kmp/src/commonMain/sqldelight/com/logseq/kmp/db/SteleDatabase.sq` (FTS Schema)
-   `tasks/migration-tasks/03-SEARCH_QUERY_SYSTEM.md` (Original Requirements)
