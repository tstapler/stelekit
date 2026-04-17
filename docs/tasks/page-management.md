# Feature Plan: Page Management (Rename & Delete)

## 1. Epic Overview

### User Value
Users need to organize their knowledge graph by renaming pages as concepts evolve and deleting obsolete pages. This feature provides the fundamental ability to manage the lifecycle of pages, ensuring that when a page is renamed, all links to it are automatically updated, maintaining the integrity of the knowledge graph.

### Success Metrics
- **Data Integrity**: 100% of wiki-links `[[Old Name]]` are updated to `[[New Name]]` across the entire graph upon rename.
- **Consistency**: File system, SQLite, and Datascript states remain consistent after operations.
- **Performance**: Rename operation on a page with 100 backlinks completes in < 2 seconds.
- **Reliability**: Zero data loss if the operation is interrupted (atomic-like behavior).

### Scope
- **In Scope**:
    - Renaming pages (updating file name, DB title, and all incoming references).
    - Deleting pages (removing file, DB entity, and updating incoming references to plain text).
    - Handling case-insensitivity and name collisions.
    - Supporting both File System and Database (Hybrid Architecture).
- **Out of Scope**:
    - UI implementation (Dialogs, Toasts) - this plan focuses on the Domain/Data layer.
    - Undo/Redo support (will be handled by a separate UndoService later).
    - Advanced refactoring (e.g., merging pages).

### Constraints
- **Hybrid Architecture**: Must update both Datascript (Memory) and SQLite (Disk).
- **File System**: Must handle file naming conventions (sanitization) and OS limitations.
- **Performance**: Cannot load all blocks into memory for reference scanning; must use optimized queries.

## 2. Architecture Decisions

### ADR-001: PageService as Orchestrator
- **Context**: Page operations involve multiple layers: File System (`GraphWriter`), Database (`PageRepository`), and Cross-cutting concerns (`BlockRepository` for references).
- **Decision**: Introduce a `PageService` domain service to orchestrate these operations.
- **Rationale**: Encapsulates the complex business logic (collision checks, reference updates) away from the Repositories (which should be dumb data access) and the UI.
- **Consequences**: Centralizes logic, making it easier to test and maintain.

### ADR-002: SQL-Based Reference Search
- **Context**: Finding all blocks that reference a page is required for renaming. The current `SqlDelightBlockRepository` implementation loads all blocks into memory to filter with Regex, which is O(N) and memory-intensive.
- **Decision**: Implement `SELECT * FROM blocks WHERE content LIKE '%[[Page Name]]%'` in SQLDelight.
- **Rationale**: Offloads filtering to the database engine, significantly reducing memory usage and data transfer.
- **Consequences**: Requires schema/query updates but enables scalability to large graphs.

### ADR-003: Atomic-ish File & DB Operations
- **Context**: We need to update both File and DB. If one fails, we have an inconsistent state.
- **Decision**: Perform File operations *first*, then DB operations. If File fails, abort. If DB fails, we are in a tricky state, but File is the source of truth in Logseq.
- **Rationale**: The Markdown file is the ultimate source of truth. If the file is renamed but DB fails, a re-index can restore consistency.
- **Consequences**: We accept a small risk of temporary inconsistency (fixable by re-index) to avoid complex 2PC (Two-Phase Commit) implementation.

## 3. Story Breakdown

### Story 1: Core Data Layer Enhancements [1 week]
**User Value**: Foundation for page management; enables the backend to handle delete/rename operations efficiently.
**Acceptance Criteria**:
- `GraphWriter` can rename and delete markdown files.
- `PageRepository` can rename and delete page entities in both Datascript and SQLite.
- `BlockRepository` can efficiently find blocks referencing a page using SQL.

### Story 2: PageService & Reference Logic [1 week]
**User Value**: The actual business logic that ensures the graph stays connected when pages change.
**Acceptance Criteria**:
- `PageService.renamePage` updates the page and all 100+ backlinks correctly.
- `PageService.deletePage` removes the page and unlinks references.
- Unit tests cover collision handling and edge cases (case sensitivity).

## 4. Atomic Task Decomposition

### Story 1: Core Data Layer Enhancements

#### Task 1.1: Enhance GraphWriter for File Operations [2h]
- **Objective**: Enable `GraphWriter` to perform file system rename and delete operations.
- **Context Boundary**:
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/db/GraphWriter.kt`
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/platform/PlatformFileSystem.kt` (Interface check)
- **Implementation Approach**:
    1.  Add `suspend fun renamePage(page: Page, newName: String): Boolean` to `GraphWriter`.
        -   Calculate new file path.
        -   Use `fileSystem.moveFile(oldPath, newPath)` (ensure this exists in PlatformFileSystem, or implement copy+delete).
    2.  Add `suspend fun deletePage(page: Page): Boolean` to `GraphWriter`.
        -   Use `fileSystem.deleteFile(page.filePath)`.
- **Validation**: Unit test mocking `PlatformFileSystem` to verify correct paths are generated and methods called.

#### Task 1.2: Enhance PageRepository Interface & Implementations [3h]
- **Objective**: Add rename and delete capabilities to the Page Repository layer.
- **Context Boundary**:
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/PageRepository.kt`
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/DatascriptPageRepository.kt`
    - `kmp/src/jvmMain/kotlin/com/logseq/kmp/repository/SqlDelightPageRepository.kt`
    - `kmp/src/commonMain/sqldelight/com/logseq/kmp/db/SteleDatabase.sq`
- **Implementation Approach**:
    1.  Update `PageRepository` interface: `renamePage(uuid, newName)`, `deletePage(uuid)`.
    2.  Implement in `DatascriptPageRepository`:
        -   Update `pages` map.
        -   Update `byName` and `byNamespace` indexes.
    3.  Implement in `SqlDelightPageRepository`:
        -   Add SQL queries: `updatePageTitle`, `deletePageById`.
        -   Call these queries in the implementation.
- **Validation**: Integration tests verifying DB state changes after rename/delete.

#### Task 1.3: Optimize BlockRepository Reference Search [3h]
- **Objective**: Replace memory-heavy scan with efficient SQL query for finding references.
- **Context Boundary**:
    - `kmp/src/commonMain/sqldelight/com/logseq/kmp/db/SteleDatabase.sq`
    - `kmp/src/jvmMain/kotlin/com/logseq/kmp/repository/SqlDelightBlockRepository.kt`
- **Implementation Approach**:
    1.  Add SQL query: `selectBlocksWithContentLike: SELECT * FROM blocks WHERE content LIKE ?`.
    2.  Update `SqlDelightBlockRepository.getLinkedReferences`:
        -   Use the new SQL query with parameter `"%[[${pageName}]]%"`.
        -   *Note*: Still perform a regex check on the results in memory to handle edge cases (false positives from LIKE), but the dataset will be drastically smaller.
- **Validation**: Performance test comparing execution time on a large dataset (mocked).

### Story 2: PageService & Reference Logic

#### Task 2.1: Implement PageService Skeleton & Delete Logic [3h]
- **Objective**: Create the service and implement the simpler "Delete" flow.
- **Context Boundary**:
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/service/PageService.kt` (New)
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/repository/BlockRepository.kt`
- **Implementation Approach**:
    1.  Define `PageService` class taking Repositories and GraphWriter as dependencies.
    2.  Implement `deletePage(uuid)`:
        -   Get page by UUID.
        -   `graphWriter.deletePage(page)`.
        -   `pageRepository.deletePage(uuid)`.
        -   `blockRepository.getLinkedReferences(page.name)`.
        -   For each block: `content.replace("[[${page.name}]]", page.name)`.
        -   `blockRepository.saveBlocks(updatedBlocks)`.
- **Validation**: Unit test verifying the orchestration sequence.

#### Task 2.2: Implement PageService Rename Logic [4h]
- **Objective**: Implement the complex "Rename" flow with reference updates.
- **Context Boundary**:
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/service/PageService.kt`
- **Implementation Approach**:
    1.  Implement `renamePage(uuid, newName)`:
        -   Check `pageRepository.getPageByName(newName)` for collisions.
        -   `graphWriter.renamePage(page, newName)`.
        -   `pageRepository.renamePage(uuid, newName)`.
        -   `blockRepository.getLinkedReferences(oldName)`.
        -   For each block: `content.replace("[[${oldName}]]", "[[${newName}]]")`.
        -   `blockRepository.saveBlocks(updatedBlocks)`.
- **Validation**: Unit tests covering collision scenarios and reference updates.

### Story 3: Quick Capture & Creation

#### Task 3.1: Explicit Page Creation UI [2h]
- **Objective**: Allow creating a new page immediately (Quick Capture).
- **Context Boundary**:
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/TopBar.kt`
    - `kmp/src/commonMain/kotlin/com/logseq/kmp/ui/components/CommandPalette.kt`
- **Implementation**:
    - Add "New Page" button to TopBar.
    - Show input dialog for page name.
    - Call `PageService.createPage(name)` (needs implementation).
    - Navigate to new page.
    - *See [docs/tasks/android-readiness.md](docs/tasks/android-readiness.md) for more details.*

## 5. Known Issues & Risks

### 🐛 Concurrency Risk: User Editing During Rename [SEVERITY: Medium]
**Description**: If a user edits a block that is being updated by the rename process, one of the writes might be lost (Last Write Wins).
**Mitigation**:
- Short term: The operation is fast enough that this is rare.
- Long term: Implement Optimistic Locking (versioning) on Blocks.
**Prevention Strategy**:
- Ensure `PageService` runs on a single thread/coroutine context if possible, or use a Mutex for the critical section of reading-modifying-writing blocks.

### 🐛 Data Integrity: Partial Failure [SEVERITY: High]
**Description**: File renamed, but DB update fails.
**Mitigation**:
- Log the error heavily.
- Trigger a "Re-index" prompt to the user if an inconsistency is detected.
**Prevention Strategy**:
- Perform File operations first (Source of Truth).
- Wrap DB operations in a transaction where possible (SQLite supports this, Datascript is in-memory).

## 6. Dependency Visualization

```
[Task 1.1: GraphWriter] --> [Task 2.1: PageService Delete]
[Task 1.2: PageRepo]    --> [Task 2.1: PageService Delete]
[Task 1.3: BlockRepo]   --> [Task 2.1: PageService Delete]
                            |
                            v
                        [Task 2.2: PageService Rename]
```

## 7. Context Preparation Guide

### For Task 1.1 (GraphWriter)
- **Files**: `GraphWriter.kt`, `PlatformFileSystem.kt`
- **Concept**: Understand how `savePageInternal` constructs paths to replicate logic for `renamePage`.

### For Task 1.3 (BlockRepo Optimization)
- **Files**: `SteleDatabase.sq`, `SqlDelightBlockRepository.kt`
- **Concept**: SQL `LIKE` operator and SQLDelight query generation.

### For Task 2.2 (PageService Rename)
- **Files**: `PageService.kt`, `BlockRepository.kt`
- **Concept**: Regex replacement strategies for `[[Wiki Links]]`.
