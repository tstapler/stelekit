# Progressive Data Loading Plan

## Analysis
The current KMP implementation suffers from potential performance bottlenecks due to eager data loading:
1.  **All Pages Loading**: `SqlDelightPageRepository.getAllPages` loads *all* pages into memory at startup. For graphs with 10k+ pages, this causes significant memory pressure and startup delay.
2.  **Linked References**: `getLinkedReferences` loads all candidate blocks into memory before filtering, which is inefficient for popular pages.
3.  **Graph Loading**: `GraphLoader` parses full page content (blocks) even if only metadata is needed for the initial index.

## Objectives
1.  **Paginate "All Pages"**: Implement `limit/offset` pagination for page lists.
2.  **Lazy References**: Load linked references in chunks.
3.  **Metadata-First Loading**: Optimize initial graph loading to prioritize page metadata.

## Implementation Plan

### Phase 1: Repository Pagination
Update repositories to support paginated queries. The SQL queries already exist (`selectAllPagesPaginated`, `selectBlocksByPageId`), but need to be exposed via the Repository interfaces.

#### Tasks
- [ ] **Update PageRepository Interface**:
    - Add `getPages(limit: Int, offset: Int): Flow<Result<List<Page>>>`
    - Add `searchPages(query: String, limit: Int, offset: Int): Flow<Result<List<Page>>>`
- [ ] **Update SqlDelightPageRepository**:
    - Implement `getPages` using `queries.selectAllPagesPaginated`.
    - Implement `searchPages` using `queries.searchPagesByTitle`.
- [ ] **Update BlockRepository Interface**:
    - Add `getLinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Result<List<Block>>>`
- [ ] **Update SqlDelightBlockRepository**:
    - Implement `getLinkedReferences` using a new paginated SQL query (requires adding `selectBlocksWithContentLikePaginated` to `.sq` file).

### Phase 2: ViewModel & UI Infinite Scroll
Refactor `LogseqViewModel` to avoid loading all pages into memory.

#### Tasks
- [ ] **Refactor LogseqViewModel**:
    - Remove `cachedAllPages` (or change it to a cache of *recent* pages only).
    - Create a `PagedList<T>` wrapper or use a simple state management for infinite scroll.
    - Expose `pages: StateFlow<List<Page>>` that appends data as the user scrolls.
    - Add `loadMorePages()` function.
- [ ] **Update UI State**:
    - `AppState` should hold the current *window* of pages or a growing list, not the entire database.

### Phase 3: GraphLoader Optimization
Optimize the initial graph scan to be faster and lighter.

#### Tasks
- [ ] **Add Metadata-Only Mode to GraphLoader**:
    - Create `scanPageMetadata(file: File): PageMetadata` that reads only the first few lines/blocks to extract title and properties.
    - Update `loadGraphProgressive` to use this mode for the initial pass.
- [ ] **Defer Block Parsing**:
    - Only parse full blocks when a page is actually opened or indexed for search.
    - *Note*: This requires a strategy to mark pages as "dirty" or "metadata-only" in the DB.

## Known Issues & Risks
- **Search Indexing**: If we defer block parsing, full-text search (FTS) won't work until the page is fully parsed.
    - *Mitigation*: Run a background job to fully parse pages for FTS after the UI is responsive.
- **Sorting**: "All Pages" view often supports sorting (by name, updated at). Pagination requires the SQL query to support these sort orders dynamically.
    - *Mitigation*: Ensure SQL queries cover common sort orders (`ORDER BY name`, `ORDER BY updated_at`).

## Success Criteria
- "All Pages" view loads instantly regardless of graph size.
- Memory usage remains stable even with 10k+ pages.
- Linked References panel loads quickly for pages with thousands of backlinks.
