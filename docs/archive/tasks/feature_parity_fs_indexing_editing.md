# Feature Parity: File System Indexing, Navigation, and Editing

## Epic Overview

**User Value**: Users can point the application to a local directory of Markdown files, have them indexed into a local database, navigate through the resulting knowledge graph, edit content, and have those edits persisted back to the file system. This restores the core "local-first" capability of the original Logseq.

**Success Metrics**:
-   **Indexing Speed**: < 5 seconds for 1000 pages.
-   **Data Integrity**: 100% of edits persisted to disk without corruption.
-   **Navigation Latency**: < 100ms page transitions.
-   **Parity**: Supports basic Markdown syntax (headers, lists, properties) equivalent to the CLJS version's core.

**Scope**:
-   **Included**: Directory selection, recursive Markdown parsing, SQLDelight storage, Page/Block UI, Editing, File Persistence.
-   **Excluded**: Advanced queries, plugins, complex org-mode support (Markdown only for now), real-time collaboration.

**Constraints**:
-   Must use Kotlin Multiplatform (KMP).
-   Must use SQLDelight for storage.
-   Must run on JVM (Desktop) initially, with Android/iOS in mind.

## Architecture Decisions

### ADR 001: Kotlin-Native Markdown Parsing
-   **Context**: The legacy app uses a CLJS parser. KMP requires a native solution.
-   **Decision**: Enhance the existing Kotlin-based `GraphLoader` to parse Markdown structure (indentation, bullets, properties) directly into the SQLDelight relational model.
-   **Rationale**: Avoids complex JS interop; ensures high performance on all platforms; allows strict typing with the SQLDelight schema.
-   **Consequences**: We must reimplement parsing logic, potentially missing some edge cases initially compared to the mature CLJS parser.
-   **Patterns**: Adapter Pattern (File -> DB).

### ADR 002: Write-Through Persistence Strategy
-   **Context**: We need to keep the DB and File System in sync.
-   **Decision**: UI updates the DB immediately (Reactive). A background `GraphWriter` observer listens for DB changes and writes back to the File System asynchronously.
-   **Rationale**: Ensures UI responsiveness while guaranteeing eventual consistency on disk.
-   **Consequences**: Risk of data loss if app crashes before write; requires robust error handling for file I/O.
-   **Patterns**: Observer Pattern, Repository Pattern.

## Story Breakdown

### Story 1: Robust Graph Loading ### Story 1: Robust Graph Loading & Indexing [1 week] Indexing [1 week] ✅ COMPLETED
**User Value**: I can select my existing Logseq graph and see my data correctly structured in the app.
**Acceptance Criteria**:
-   User can pick a directory via system dialog.
-   App recursively finds all `.md` files.
-   Parser correctly identifies Page properties (title, id).
-   Parser correctly identifies Block hierarchy (parent/child) based on indentation.
-   Data is persisted to SQLite.

### Story 2: Page Navigation ### Story 2: Page Navigation & Display [1 week] Display [1 week] 🚧 IN PROGRESS
**User Value**: I can browse my knowledge graph.
**Acceptance Criteria**:
-   Home screen lists all pages (or recent ones).
-   Clicking a page opens the Page Detail view.
-   Page Detail view renders blocks in correct hierarchy (indentation).
-   Back navigation works.

### Story 3: Editing ### Story 3: Editing & Persistence [1-2 weeks] Persistence [1-2 weeks] 🚧 IN PROGRESS
**User Value**: I can modify my notes and keep them safe.
**Acceptance Criteria**:
-   Clicking a block allows text editing.
-   "Enter" creates a new sibling block.
-   "Tab" indents a block (changes parent).
-   Edits update the DB immediately.
-   Edits are written back to the `.md` file within 1 second (or on blur).

## Atomic Task Decomposition

### Story 1: Robust Graph Loading & Indexing

#### Task 1.1: Enhance GraphLoader for Properties #### Task 1.1: Enhance GraphLoader for Properties & IDs [3h] IDs [3h] ✅ COMPLETED
-   **Objective**: Update `GraphLoader.kt` to parse frontmatter properties and block properties (id, etc.).
-   **Context Boundary**:
    -   `kmp/src/commonMain/kotlin/com/logseq/kmp/db/GraphLoader.kt`
    -   `kmp/src/commonMain/kotlin/com/logseq/kmp/model/Models.kt`
-   **Prerequisites**: Understanding of Logseq Markdown format.
-   **Implementation**:
    -   Add Regex for `property:: value`.
    -   Add Regex for `id:: uuid`.
    -   Update `parseAndSavePage` to extract these before creating Block objects.
-   **Validation**: Unit test with a sample Markdown file containing properties.

#### Task 1.2: Implement GraphRepository [2h] ✅ COMPLETED
-   **Objective**: Create a Repository layer to abstract the `GraphLoader` and DB access.
-   **Context Boundary**:
    -   `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/GraphRepository.kt` (New)
    -   `kmp/src/commonMain/kotlin/com/logseq/kmp/db/GraphLoader.kt`
-   **Prerequisites**: Task 1.1.
-   **Implementation**:
    -   Expose `loadGraph(path: String)` flow.
    -   Expose `observeLoadState()` (Loading/Success/Error).
-   **Validation**: Integration test mocking FileSystem.

### Story 2: Page Navigation & Display

#### Task 2.1: Page List UI [3h] ✅ COMPLETED
-   **Objective**: Create a Compose screen to list pages from DB.
-   **Context Boundary**:
    -   `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/pages/PageListScreen.kt` (New)
    -   `kmp/src/commonMain/kotlin/com/logseq/kmp/viewmodel/PageListViewModel.kt` (New)
-   **Prerequisites**: DB populated.
-   **Implementation**:
    -   ViewModel observes `SimplePageRepository.getAllPages()`.
    -   Compose LazyColumn to render page titles.
-   **Validation**: Manual verification (run app).

#### Task 2.2: Page Detail UI with Block Tree [4h] ✅ COMPLETED
-   **Objective**: Render the hierarchical block tree for a page.
-   **Context Boundary**:
    -   `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/page/PageDetailScreen.kt` (New)
    -   `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/blocks/BlockTree.kt` (New)
-   **Prerequisites**: Task 2.1.
-   **Implementation**:
    -   Recursive LazyColumn or indented Column for blocks.
    -   Fetch blocks by `page_id` ordered by `position`.
-   **Validation**: Visual check of indentation.

### Story 3: Editing & Persistence

#### Task 3.1: GraphWriter Implementation [4h] ✅ COMPLETED
-   **Objective**: Logic to serialize a Page and its Blocks back to Markdown.
-   **Context Boundary**:
    -   `kmp/src/commonMain/kotlin/com/logseq/kmp/db/GraphWriter.kt` (New)
    -   `kmp/src/commonMain/kotlin/com/logseq/kmp/platform/PlatformFileSystem.kt`
-   **Prerequisites**: Story 1.
-   **Implementation**:
    -   `writePage(pageId: Long)`: Fetch all blocks, sort by position/hierarchy.
    -   Convert to Markdown string (tabs for indentation).
    -   Call `fileSystem.writeFile`.
-   **Validation**: Unit test: Parse -> Write -> Parse -> Compare.

#### Task 3.2: Editor Integration [4h] 🚧 IN PROGRESS
-   **Objective**: Connect UI text fields to DB updates and trigger `GraphWriter`.
-   **Context Boundary**:
    -   `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/blocks/BlockEditor.kt` (New)
    -   `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/BlockRepository.kt`
-   **Prerequisites**: Task 3.1.
-   **Implementation**:
    -   `onValueChange` -> Update DB.
    -   Launch coroutine to trigger `GraphWriter.writePage`.
-   **Validation**: Edit text, check file on disk.

## Known Issues

### 🐛 Concurrency Risk: File Overwrites [SEVERITY: High]
-   **Description**: Rapid edits might trigger multiple file writes, potentially interleaving or locking the file.
-   **Mitigation**: Use a `Mutex` or a `Channel` (Actor pattern) in `GraphWriter` to serialize writes for a specific file.
-   **Files**: `GraphWriter.kt`
-   **Prevention**: Implement a `WriteQueue` per file.

### 🐛 Data Integrity: Parsing Edge Cases [SEVERITY: Medium]
-   **Description**: The simple parser might mangle complex Markdown (e.g., code blocks with indentation).
-   **Mitigation**: Add specific tests for code blocks and blockquotes.
-   **Files**: `GraphLoader.kt`
-   **Prevention**: Use a robust state-machine parser or existing library if possible (though KMP options are limited).

## Dependency Visualization

```
[Story 1: Indexing]
    |
    +---> [Task 1.1: GraphLoader] --> [Task 1.2: GraphRepo]
                                            |
                                            v
[Story 2: Navigation] <---------------------+
    |
    +---> [Task 2.1: Page List] --> [Task 2.2: Page Detail]
                                            |
                                            v
[Story 3: Editing] <------------------------+
    |
    +---> [Task 3.1: GraphWriter] --> [Task 3.2: Editor Integration]
```

## Integration Checkpoints
-   **Checkpoint 1 (After Story 1)**: App can open a folder and the SQLite DB is populated with correct data.
-   **Checkpoint 2 (After Story 2)**: User can navigate the graph read-only.
-   **Checkpoint 3 (Final)**: User can edit a block and see the file change on disk.

## Context Preparation Guide
-   **Files to Load**:
    -   `kmp/src/commonMain/kotlin/com/logseq/kmp/db/GraphLoader.kt`
    -   `kmp/src/commonMain/sqldelight/com/logseq/kmp/db/SteleDatabase.sq`
    -   `kmp/src/commonMain/kotlin/com/logseq/kmp/platform/PlatformFileSystem.kt`
-   **Concepts**:
    -   Logseq Markdown format (indentation = hierarchy).
    -   SQLDelight queries.
    -   Compose State Management.
