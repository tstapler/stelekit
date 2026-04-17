# Knowledge Graph Maintenance — Implementation Plan

**Epic**: Knowledge Graph Maintenance  
**Status**: Planning  
**Target**: JVM + Android (KMP commonMain)  
**ADRs**: ADR-007, ADR-008, ADR-009

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Requirements Analysis](#requirements-analysis)
3. [Architecture Overview](#architecture-overview)
4. [Dependency Graph](#dependency-graph)
5. [Story Breakdown](#story-breakdown)
   - [Story 1: Page Rename with Backlink Update](#story-1-page-rename-with-backlink-update)
   - [Story 2: Unlinked Mentions Navigator](#story-2-unlinked-mentions-navigator)
   - [Story 3: Duplicate Page Detector and Merge Workflow](#story-3-duplicate-page-detector-and-merge-workflow)
6. [Known Issues](#known-issues)
7. [ADR Index](#adr-index)
8. [Testing Strategy](#testing-strategy)

---

## Executive Summary

Three workflows form this epic, each addressing a different kind of knowledge-graph drift:

**Story 1 — Page Rename with Backlink Update** is the most critical. When a user renames a page, every `[[OldName]]` wikilink across all pages and journal entries must be atomically updated to `[[NewName]]`. The existing `GraphWriter.renamePage` moves the file on disk, but the backlink rewrite is absent. This story adds the missing transactional layer: a preview dialog enumerates affected blocks, then a single DB transaction + parallel file-rewrite batch executes the change. Failure in any file write rolls back the DB row changes and surfaces a detailed error.

**Story 2 — Unlinked Mentions Navigator** surfaces blocks where page-name text appears as plain text rather than a wikilink. It reuses the `PageNameIndex` + `AhoCorasickMatcher` infrastructure from the page-term-highlighting epic (PTH-001) and provides a focused side-panel workflow distinct from the inline suggestion popups of that epic. Users can step through candidates and link them one by one.

**Story 3 — Duplicate Page Detector and Merge Workflow** uses edit-distance and common-prefix heuristics to identify pages whose names are likely the same concept. A merge operation: appends the source page's blocks to the target page, rewrites all backlinks from source to target, then deletes the source page file and DB row.

---

## Requirements Analysis

### Functional Requirements — MoSCoW

| ID | Requirement | Priority | Story |
|----|-------------|----------|-------|
| KGM-01 | When a page is renamed, all `[[OldName]]` occurrences in block content are updated to `[[NewName]]` | Must | 1 |
| KGM-02 | The rename + backlink update is atomic: either all DB rows and all files are updated, or none are | Must | 1 |
| KGM-03 | A preview dialog shows the count and list of affected blocks before the rename commits | Must | 1 |
| KGM-04 | Rename confirms or cancels from a single UI gesture; no multi-step wizard | Must | 1 |
| KGM-05 | The renamed page file is moved/renamed on disk; the old path no longer exists after success | Must | 1 |
| KGM-06 | If any file write fails, the DB changes are rolled back and an error is surfaced to the user | Must | 1 |
| KGM-07 | The command palette and page list offer a "Rename page" action | Must | 1 |
| KGM-08 | Backlinks that use page aliases (e.g., `[[OldAlias]]`) are also updated | Should | 1 |
| KGM-09 | An "Unlinked Mentions" panel on each page lists blocks containing the page name as plain text | Should | 2 |
| KGM-10 | Each unlinked-mention row shows the source block with the matched text highlighted | Should | 2 |
| KGM-11 | A "Link" action on each row converts the plain text to `[[PageName]]` and saves atomically | Should | 2 |
| KGM-12 | "Link All" converts every unlinked mention in a single batch with confirmation | Should | 2 |
| KGM-13 | The unlinked-mentions panel is paginated; default page size 20 | Should | 2 |
| KGM-14 | The panel updates live as mentions are linked (already-linked rows are removed) | Should | 2 |
| KGM-15 | A "Duplicate Pages" tool accessible from the graph toolbar or Settings lists candidate pairs | Could | 3 |
| KGM-16 | Candidates are ranked by edit distance (Levenshtein) and common-prefix length | Could | 3 |
| KGM-17 | The user selects a "keep" page and a "remove" page for each pair and initiates merge | Could | 3 |
| KGM-18 | Merge appends all blocks from the removed page to the kept page | Could | 3 |
| KGM-19 | Merge rewrites all `[[RemovedName]]` backlinks to `[[KeptName]]` across the graph | Could | 3 |
| KGM-20 | The removed page's file is deleted and its DB row is removed after a successful merge | Could | 3 |
| KGM-21 | Merge is non-destructive: block content from the removed page is never silently dropped | Must (if merge is done) | 3 |
| KGM-22 | A "Skip" button dismisses a candidate pair without action | Could | 3 |
| KGM-23 | Page names shorter than 4 characters are never reported as duplicate candidates | Should | 3 |

### Non-Functional Requirements

| Attribute | Target |
|-----------|--------|
| Rename preview latency | < 500 ms for a 5 000-page graph (SQL LIKE scan over block content) |
| Rename commit latency | < 2 s for 100 affected files on JVM (parallel IO with 4 workers) |
| Backlink rewrite correctness | 100% — regex-based replacement must not corrupt surrounding markdown |
| Unlinked-mentions query latency | < 300 ms for first page of 20 results (reuses AhoCorasick / FTS5) |
| Merge atomicity | Single SQLDelight transaction for all DB mutations; file deletes happen after DB success |
| Edit-distance computation | < 100 ms for 5 000 page pairs on JVM |
| Undo safety | Rename and merge emit undo-stack entries usable by the existing `CommandManager` |
| KMP compliance | No platform-specific code in `commonMain` |

---

## Architecture Overview

### Current Rename Gap

`GraphWriter.renamePage` moves the file on disk (read + write + delete). `PageRepository.renamePage` updates the `name` column in the DB. Neither touches block content containing the old wikilink. This story adds the missing `BacklinkRenamer` service that sits between these two operations.

### Components Added by this Epic

```
┌──────────────────────────────────────────────────────────────────────┐
│ UI Layer                                                              │
│  RenamePageDialog.kt          (new — preview + confirm modal)        │
│  UnlinkedMentionsPanel.kt     (new — paginated mention list panel)   │
│  DuplicatePagesTool.kt        (new — candidate list + merge action)  │
│  PageView.kt                  (add UnlinkedMentionsPanel tab)        │
│  Sidebar.kt / Settings.kt     (add DuplicatePagesTool entry point)   │
└──────────────────────────┬───────────────────────────────────────────┘
                           │ StateFlow / suspend functions
┌──────────────────────────▼───────────────────────────────────────────┐
│ ViewModel / Service Layer                                             │
│  StelekitViewModel.kt  (renamePage, initiateRename, confirmRename)   │
│  BacklinkRenamer.kt    (new — transactional rename + file rewrite)   │
│  PageMergeService.kt   (new — merge + backlink redirect + cleanup)   │
│  DuplicateDetector.kt  (new — edit-distance candidate generator)     │
└──────────────────────────┬───────────────────────────────────────────┘
                           │ suspend / Flow
┌──────────────────────────▼───────────────────────────────────────────┐
│ Repository / DB Layer                                                 │
│  BlockRepository.getUnlinkedReferences()  (already exists)           │
│  BlockRepository.getLinkedReferences()    (already exists)           │
│  BlockRepository.saveBlock()              (already exists)           │
│  PageRepository.renamePage()              (already exists)           │
│  PageRepository.deletePage()              (already exists)           │
│  GraphWriter.renamePage()                 (already exists)           │
│  GraphWriter.savePage()                   (already exists)           │
└──────────────────────────────────────────────────────────────────────┘
```

### Rename Transaction Protocol

```
User confirms rename (OldName -> NewName)
  1. BEGIN TRANSACTION (SQLDelight)
  2. UPDATE pages SET name = NewName WHERE uuid = pageUuid
  3. SELECT all blocks WHERE content LIKE '%[[OldName]]%'
  4. For each block: UPDATE blocks SET content = replace(content, '[[OldName]]', '[[NewName]]')
  5. COMMIT TRANSACTION
  6. If COMMIT fails -> surface error, no file changes
  7. If COMMIT succeeds -> parallel file rewrites (4 workers):
       for each affected page:
         read file -> replace all [[OldName]] -> write file
  8. GraphWriter.renamePage() moves the renamed page's own file
  9. If any file write fails:
       -> ROLLBACK DB (re-run steps 1-4 with names swapped)
       -> collect partial-write paths for error report
       -> surface "Partial rename" error with affected file list
```

### Merge Transaction Protocol

```
User confirms merge (SourcePage -> TargetPage)
  1. BEGIN TRANSACTION
  2. Re-parent all root blocks of SourcePage to TargetPage (update page_uuid, reset position)
  3. UPDATE all blocks WHERE content contains [[SourceName]]: replace with [[TargetName]]
  4. DELETE FROM pages WHERE uuid = sourcePageUuid  (CASCADE deletes SourcePage's blocks — but
     they were already re-parented in step 2, so they survive)
  5. COMMIT
  6. GraphWriter.deletePage() removes the source file from disk
  7. GraphWriter.savePage() rewrites the target page file with merged blocks
```

---

## Dependency Graph

```
External Dependency (must be complete before this epic):
  [PTH-001] Page Term Highlighting — Story 1 (PageNameIndex + AhoCorasickMatcher)
      ^
      |  Story 2 reuses PageNameIndex and AhoCorasickMatcher directly.
      |  Story 2 also reuses BlockRepository.getUnlinkedReferences() which exists today.

This Epic:
  Story 1 — Page Rename with Backlink Update   [no internal dependencies]
      |
      v
  Story 2 — Unlinked Mentions Navigator        [depends on PTH-001 Story 1]
      |
      v
  Story 3 — Duplicate Page Detector & Merge    [depends on Story 1 for BacklinkRenamer reuse]
```

Critical path: **PTH-001 Story 1 + KGM Story 1 (parallel) -> KGM Story 2 -> KGM Story 3**

Story 1 and Story 2 can begin development in parallel. Story 3 should start after Story 1 is merged (it reuses `BacklinkRenamer` for the backlink-redirect step of a merge).

---

## Story Breakdown

---

### Story 1: Page Rename with Backlink Update

**Goal**: Renaming a page atomically updates the DB row, all `[[OldName]]` occurrences in block content, and moves the page file on disk. A preview dialog shows the user what will change before committing.

**Acceptance Criteria**:
- Renaming "Project Alpha" to "Project Alpha 2026" updates every `[[Project Alpha]]` across all blocks in the DB in a single transaction
- The renamed page's markdown file is moved from `Project Alpha.md` to `Project Alpha 2026.md`
- A preview modal shows "N blocks in M pages will be updated" before the user confirms
- If any file-level rewrite fails, the DB is restored to the pre-rename state and an error lists the unwriteable files
- The rename action is available from the page header context menu and from the command palette
- Journal pages cannot be renamed (the rename UI is disabled for `isJournal = true` pages)
- Cancelling the preview leaves no changes in the DB or on disk

---

#### Task 1.1 — Implement `BacklinkRenamer` service
**Files**: new `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/BacklinkRenamer.kt`  
**Effort**: 3 h

```kotlin
class BacklinkRenamer(
    private val database: SteleDatabase,        // for raw transaction control
    private val blockRepository: BlockRepository,
    private val pageRepository: PageRepository,
    private val graphWriter: GraphWriter,
    private val graphLoader: GraphLoader,
    private val graphPath: String
) {
    /**
     * Preview: returns a RenamePreview without mutating any state.
     * Uses LIKE query to count affected blocks efficiently.
     */
    suspend fun preview(pageUuid: String, oldName: String, newName: String): RenamePreview

    /**
     * Execute: transactional rename.
     * Returns Result.success(Unit) on full success, Result.failure on any error.
     * On partial file-write failure, DB is rolled back and PartialRenameException is thrown.
     */
    suspend fun execute(pageUuid: String, oldName: String, newName: String): Result<RenameResult>
}

data class RenamePreview(
    val affectedBlockCount: Int,
    val affectedPageCount: Int,
    val affectedBlocks: List<Block>   // capped at 50 for display; full count always accurate
)

data class RenameResult(
    val updatedBlockCount: Int,
    val updatedFileCount: Int
)
```

Implementation notes:
- The SQL LIKE pattern for preview is `'%[[' || oldName || ']]%'` (case-sensitive; wikilinks are stored with canonical casing).
- Use `database.transaction { ... }` (SQLDelight's built-in transaction API) to wrap the UPDATE statements.
- After the DB transaction commits, spawn a `coroutineScope { }` with a `Semaphore(4)` for parallel file rewrites.
- Regex for content replacement: `Regex("\\[\\[" + Regex.escape(oldName) + "]]")` — must handle alias forms `[[OldName|display text]]` by replacing only the page-name segment.
- `graphLoader.markFileWrittenByUs(filePath)` must be called for each rewritten file to suppress spurious external-change events.

---

#### Task 1.2 — Add SQL query for bulk backlink content update
**Files**: `kmp/src/commonMain/sqldelight/dev/stapler/stelekit/db/SteleDatabase.sq`  
**Effort**: 1 h

Add two named queries:

```sql
-- Count blocks containing a specific wikilink pattern (for preview)
countBlocksWithWikilink:
SELECT COUNT(*) FROM blocks WHERE content LIKE '%[[' || :pageName || ']]%';

-- Select blocks containing a specific wikilink pattern (for preview list + rewrite loop)
selectBlocksWithWikilink:
SELECT * FROM blocks WHERE content LIKE '%[[' || :pageName || ']]%' ORDER BY page_uuid, position;
```

Note: `LIKE` on `content` without an FTS query is acceptable here because:
1. Rename is an infrequent user action (not per-keystroke).
2. The `blocks_fts` table only indexes `content` for FTS5 `MATCH` queries, not LIKE patterns.
3. An additional index on `content` for substring search would not help — SQLite cannot use a B-tree index for a leading-wildcard LIKE.

For graphs with >50 000 blocks, consider a FTS5 phrase query (`blocks_fts MATCH '"[[OldName]]"'`) as a faster alternative — document this as a PERF-003 follow-up.

---

#### Task 1.3 — Implement `RenamePageDialog` composable
**Files**: new `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/RenamePageDialog.kt`  
**Effort**: 2 h

```kotlin
@Composable
fun RenamePageDialog(
    page: Page,
    onConfirm: (newName: String) -> Unit,
    onDismiss: () -> Unit
)
```

States:
1. **Input** — text field pre-populated with current name, "Preview" button
2. **Previewing** — spinner while `BacklinkRenamer.preview()` runs; then shows "N blocks in M pages will be updated; continue?" with "Rename" and "Cancel" buttons
3. **Renaming** — indeterminate progress indicator, no dismiss
4. **Error** — shows `PartialRenameException` detail with "Copy error" and "Close" buttons

Name validation: disallow empty names, names containing `[[`, `]]`, or `/` at start/end (reuse existing `Validation.validateName` if present, or add a simple check inline).

---

#### Task 1.4 — Wire rename action into `StelekitViewModel` and UI entry points
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/LogseqViewModel.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/PageView.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt`  
**Effort**: 2 h

Add to `StelekitViewModel`:

```kotlin
suspend fun initiateRename(page: Page): RenamePreview
suspend fun confirmRename(page: Page, newName: String): Result<RenameResult>
```

In `PageView`, add a "Rename" option to the page title's context menu (long-press on mobile, right-click or kebab menu on desktop) that opens `RenamePageDialog`.

Add a command in `updateCommands()`: `"rename-page"` that opens the dialog for `appState.currentPage`.

---

#### Task 1.5 — Unit and integration tests for `BacklinkRenamer`
**Files**: new `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/BacklinkRenamerTest.kt`  
**Effort**: 2 h

Test cases:
- Rename "Alpha" to "Beta": all blocks with `[[Alpha]]` contain `[[Beta]]` after execute; blocks without `[[Alpha]]` are unchanged
- Alias form `[[Alpha|my alias]]` is updated to `[[Beta|my alias]]`
- Preview returns correct `affectedBlockCount` before any mutation
- Rename with an existing page named "Beta" is rejected with a `DuplicatePageNameException`
- File-write failure for one file: DB is rolled back, error lists the failing file path
- Journal page rename is blocked (returns `Result.failure` with `JournalRenameNotAllowedException`)
- Empty graph (no blocks): rename succeeds with `updatedBlockCount = 0`

---

### Story 2: Unlinked Mentions Navigator

**Goal**: A panel on every page view lists blocks across the graph that mention the page's name as plain text (not as a wikilink). Each row lets the user link the mention in one click. This story reuses `PageNameIndex` and `AhoCorasickMatcher` from PTH-001.

**Precondition**: PTH-001 Story 1 (`PageNameIndex`, `AhoCorasickMatcher`) must be merged.

**Acceptance Criteria**:
- The "Unlinked Mentions" tab in `ReferencesPanel` shows blocks from `BlockRepository.getUnlinkedReferences()`, paginated at 20 per page
- Each row displays the block content with the matching page-name text highlighted (using the same `PAGE_SUGGESTION_TAG` annotation from PTH-001)
- "Link" action on a row replaces the plain-text occurrence with `[[PageName]]`, saves via `GraphWriter`, and removes the row from the list
- "Link All" shows a count confirmation and links every unlinked mention in sequence
- The panel displays a "0 unlinked mentions" empty state when none exist
- The feature is controlled by the same `showPageNameSuggestions` AppState flag as PTH-011

---

#### Task 2.1 — Extend `ReferencesPanel` with Unlinked Mentions tab
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ReferencesPanel.kt`  
**Effort**: 2 h

`ReferencesPanel` currently renders linked and unlinked reference sections sequentially. Convert the unlinked-mentions section into a proper tab or collapsible section with a "Link All" button header. The existing `blockRepository.getUnlinkedReferences(pageName, limit, offset)` provides the data.

Add state:
```kotlin
var linkAllInProgress by remember { mutableStateOf(false) }
var linkAllCount by remember { mutableIntStateOf(0) }
```

The "Link" button per row calls a new `onLinkMention(block: Block, pageName: String)` callback, which the parent (`PageView`) implements by delegating to the ViewModel.

---

#### Task 2.2 — Add `linkMention` to `StelekitViewModel`
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/LogseqViewModel.kt`  
**Effort**: 2 h

```kotlin
suspend fun linkMention(block: Block, pageName: String, matchStart: Int, matchEnd: Int): Result<Unit>
suspend fun linkAllMentions(pageName: String): Result<Int>  // returns count linked
```

`linkMention` implementation:
1. Compute `newContent = block.content.substring(0, matchStart) + "[[$pageName]]" + block.content.substring(matchEnd)`
2. Call `blockRepository.saveBlock(block.copy(content = newContent))`
3. Collect the block's page, call `graphWriter.savePage(page, allBlocks, graphPath)` to write to disk
4. Notify the `externalFileChanges` suppression so the watcher does not reload the file

`linkAllMentions` iterates `getUnlinkedReferences(pageName)` (full, unpaginated for the batch) and calls `linkMention` for the first plain-text occurrence in each block, batching DB writes.

The match position (`matchStart`, `matchEnd`) is provided by the UI layer by running `AhoCorasickMatcher.findAll(block.content)` at display time and storing offsets in a `MentionState` similar to PTH-001's `SuggestionState`.

---

#### Task 2.3 — Highlight matched text in unlinked-mention rows
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/ReferencesPanel.kt`  
**Effort**: 1 h

Each unlinked-mention row renders the block's `content` using `parseMarkdownWithStyling` with a pre-built `AhoCorasickMatcher` seeded with `setOf(pageName.lowercase())`. The `PAGE_SUGGESTION_TAG` annotation provides the highlight span. No popup — clicking "Link" directly converts it.

The `AhoCorasickMatcher` is built once per panel via `remember(pageName)` to avoid per-row reconstruction.

---

#### Task 2.4 — Integration tests for link-mention flow
**Files**: new `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/integration/UnlinkedMentionsTest.kt`  
**Effort**: 2 h

Test cases:
- Create page "Kotlin", create block with content "I love Kotlin programming": `getUnlinkedReferences("Kotlin")` returns 1 block
- Call `linkMention` with correct offsets: block content becomes "I love [[Kotlin]] programming"
- After linking: `getUnlinkedReferences("Kotlin")` returns 0 blocks; `getLinkedReferences("Kotlin")` returns the same block
- `linkAllMentions("Kotlin")` with 5 mention blocks: all are linked, return count = 5
- Block that already contains `[[Kotlin]]` somewhere does not appear in unlinked results even if plain-text "Kotlin" also appears — note: current `getUnlinkedReferences` uses `LIKE` without exclusion; document the edge case and add a test verifying current behaviour

---

### Story 3: Duplicate Page Detector and Merge Workflow

**Goal**: Identify page pairs with similar names and provide a safe merge workflow: content from the removed page is appended to the kept page, all backlinks are redirected, and the duplicate is deleted.

**Precondition**: Story 1 (`BacklinkRenamer`) must be merged — `PageMergeService` reuses it for the backlink-redirect step.

**Acceptance Criteria**:
- A "Find Duplicate Pages" action in Settings > Advanced (or graph toolbar) runs the detector
- Candidate pairs are displayed sorted by similarity score (lowest edit distance first)
- Pages shorter than 4 characters are excluded
- Journal pages are excluded from all candidate results
- Each candidate pair shows both page names and a similarity score badge
- The user designates which page to keep and which to remove before merging
- Merge appends all root blocks of the removed page below the last block of the kept page, separated by a horizontal-rule block (or a header block `## (Merged from [[RemovedName]])`)
- All `[[RemovedName]]` wikilinks across the graph become `[[KeptName]]` after merge
- The removed page's file is deleted; the kept page's file is rewritten
- A "Skip" action dismisses a pair from the candidate list without merging
- Merge is undoable — the `CommandManager` receives a `MergePageCommand` entry

---

#### Task 3.1 — Implement `DuplicateDetector`
**Files**: new `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DuplicateDetector.kt`  
**Effort**: 3 h

```kotlin
class DuplicateDetector {
    /**
     * Returns candidate pairs ordered by ascending similarity distance.
     * Filters out journal pages and names shorter than MIN_NAME_LENGTH characters.
     */
    fun detectCandidates(pages: List<Page>): List<DuplicateCandidate>

    companion object {
        const val MIN_NAME_LENGTH = 4
        const val MAX_EDIT_DISTANCE = 3   // configurable via AppState setting
        const val COMMON_PREFIX_MIN = 5   // minimum prefix length for prefix-based detection
    }
}

data class DuplicateCandidate(
    val pageA: Page,
    val pageB: Page,
    val editDistance: Int,
    val similarityScore: Float   // 1.0 = identical, 0.0 = completely different
)
```

Algorithm:
1. Load all non-journal pages with `name.length >= MIN_NAME_LENGTH`.
2. Normalize each name: lowercase, collapse whitespace, strip leading `#`.
3. Use a two-pass strategy:
   - **Pass 1 (common prefix)**: Sort names lexicographically; adjacent pairs in sorted order that share a prefix of >= `COMMON_PREFIX_MIN` characters are candidates without computing edit distance.
   - **Pass 2 (edit distance)**: For pairs within a 2x name-length heuristic that were not caught by pass 1, compute Levenshtein distance. Include pairs where `editDistance <= MAX_EDIT_DISTANCE`.
4. Deduplicate and sort by `editDistance ASC, pageA.name ASC`.

Note: O(n^2) in the worst case, but `MAX_EDIT_DISTANCE = 3` limits the comparison space — early termination when the running edit cost exceeds the threshold.

Pure Kotlin implementation — no external dependency.

---

#### Task 3.2 — Implement `PageMergeService`
**Files**: new `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/PageMergeService.kt`  
**Effort**: 3 h

```kotlin
class PageMergeService(
    private val database: SteleDatabase,
    private val blockRepository: BlockRepository,
    private val pageRepository: PageRepository,
    private val graphWriter: GraphWriter,
    private val backlinkRenamer: BacklinkRenamer,
    private val graphPath: String
) {
    /**
     * Preview: returns blocks that would be appended and backlink count.
     */
    suspend fun preview(sourcePageUuid: String, targetPageUuid: String): MergePreview

    /**
     * Execute: atomic merge.
     */
    suspend fun execute(sourcePageUuid: String, targetPageUuid: String): Result<MergeResult>
}

data class MergePreview(
    val sourceBlockCount: Int,
    val backlinkCount: Int,
    val sourcePageName: String,
    val targetPageName: String
)

data class MergeResult(
    val appendedBlockCount: Int,
    val updatedBacklinkCount: Int
)
```

`execute` protocol:
1. Load source page blocks (`getBlocksForPage(sourceUuid)`) and target page blocks.
2. BEGIN TRANSACTION.
3. Insert a separator block into target page: content = `"## (Merged from [[${sourcePage.name}]])"`, `position` = last target position + 1.
4. Re-parent all source root blocks to target page: update `page_uuid`, recalculate `position` and `level`.
5. Call `backlinkRenamer.execute(sourcePageUuid, sourceName, targetName)` — this rewrites `[[SourceName]]` to `[[TargetName]]` in all blocks including the just-moved ones.
6. DELETE FROM pages WHERE uuid = sourcePageUuid — CASCADE removes any remaining source blocks (there should be none after step 4, but this is a safety net).
7. COMMIT.
8. `graphWriter.deletePage(sourcePage)` — remove source file.
9. `graphWriter.savePage(targetPage, mergedBlocks, graphPath)` — rewrite target file.

Non-destructive guarantee: step 3 creates the separator block before step 6. If the transaction fails at step 6, the separator and re-parented blocks are rolled back cleanly — no data loss.

---

#### Task 3.3 — Implement `DuplicatePagesTool` composable
**Files**: new `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/DuplicatePagesTool.kt`  
**Effort**: 2 h

```kotlin
@Composable
fun DuplicatePagesTool(
    candidates: List<DuplicateCandidate>,
    isScanning: Boolean,
    onScan: () -> Unit,
    onMerge: (sourcePageUuid: String, targetPageUuid: String) -> Unit,
    onSkip: (DuplicateCandidate) -> Unit,
    modifier: Modifier = Modifier
)
```

Layout:
- Header row: "Find Duplicate Pages" button + progress indicator when scanning
- Candidate list (`LazyColumn`) — each row:
  - Left column: page name A
  - Center: similarity badge ("Edit distance: N")
  - Right column: page name B
  - Below: radio buttons "Keep A / Keep B" (default: keep the one with more blocks)
  - "Merge" button and "Skip" button

Empty state: "No duplicate pages found." with a "Rescan" button.

Entry point: add "Find Duplicate Pages" item in `Settings.kt` under the Advanced section.

---

#### Task 3.4 — Wire `DuplicateDetector` and `PageMergeService` into ViewModel
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/LogseqViewModel.kt`  
**Effort**: 2 h

```kotlin
fun scanForDuplicates(): Flow<List<DuplicateCandidate>>
suspend fun mergeDuplicates(sourcePageUuid: String, targetPageUuid: String): Result<MergeResult>
fun dismissDuplicateCandidate(candidate: DuplicateCandidate)
```

`scanForDuplicates` loads all pages via `pageRepository.getAllPages()`, runs `DuplicateDetector.detectCandidates`, and emits results incrementally via a `flow { emit(...) }`. This allows the UI to render partial results while scanning.

`dismissDuplicateCandidate` removes the candidate from a local `MutableStateFlow<List<DuplicateCandidate>>` without touching the DB.

---

#### Task 3.5 — Unit and integration tests for merge
**Files**: new `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/PageMergeServiceTest.kt`, new `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/db/DuplicateDetectorTest.kt`  
**Effort**: 2 h

`DuplicateDetectorTest` cases:
- "Meeting Notes" and "Meeting Note" (edit distance 1) — detected
- "Alpha" and "Beta" (edit distance 4) — not detected
- "Go" and "Go2" (name < 4 chars) — excluded
- Journal page included in input — excluded from results
- "Project/Alpha" and "Project/Beta" (namespace pages) — detected if edit distance qualifies
- Two identical names (edit distance 0) — detected (should not normally exist due to UNIQUE constraint, but defensive test)

`PageMergeServiceTest` cases:
- Merge B into A: A's blocks count increases by B's block count + 1 separator
- Merge B into A: all `[[B]]` backlinks become `[[A]]` after merge
- B's DB row does not exist after merge
- B's file is deleted after merge (verified via `InMemoryFileSystem`)
- Preview returns correct `sourceBlockCount` and `backlinkCount`
- Merge with zero blocks in source (empty page): only separator added, no block re-parenting

---

## Known Issues

### Bug 1: Partial rename leaves DB and disk out of sync [SEVERITY: Critical]

**Description**: If the DB transaction commits (step 5 in the rename protocol) but one or more file rewrites fail (step 7), the DB has the new name but some markdown files still contain `[[OldName]]`. On next load, `GraphLoader` will re-import those files, creating `[[OldName]]` wikilinks that point to a non-existent page (since the page was renamed in the DB).

**Mitigation**:
- Track which files were successfully rewritten. If any fail, immediately execute a compensating DB transaction that reverts the `pages.name` update and all block `content` updates.
- Expose the list of unwritable files in a `PartialRenameException` so the user can manually resolve them (e.g., file permissions issue on a network drive).
- Add a `rename_log` table or an in-memory journal to track in-progress renames, allowing recovery on restart.
- Write an integration test that simulates a file-write failure mid-batch and verifies DB rollback.

**Files Likely Affected**:
- `BacklinkRenamer.kt`
- `GraphWriter.kt`
- `SteleDatabase.sq` (potential `rename_journal` table)

---

### Bug 2: LIKE query misses alias forms `[[OldName|display text]]` [SEVERITY: High]

**Description**: The SQL LIKE pattern `'%[[OldName]]%'` does not match alias wikilinks like `[[OldName|See this]]`. These would be missed by both the preview count and the content rewrite, leaving stale alias links pointing to the renamed page.

**Mitigation**:
- Add a second LIKE pattern: `'%[[' || oldName || '|%'` — blocks matching either pattern are included in the affected set.
- The regex for replacement must also handle the alias form: `Regex("\\[\\[" + Regex.escape(oldName) + "(]|\\|)")` — capture the terminator and preserve it.
- Add a test case: block with `[[Alpha|see Alpha]]` after rename to `[[Beta|see Alpha]]` (page name updated, display text preserved).

**Files Likely Affected**:
- `BacklinkRenamer.kt`
- `SteleDatabase.sq` (new `selectBlocksWithWikilinkOrAlias` query)

---

### Bug 3: Edit-distance false positives for short page names [SEVERITY: Medium]

**Description**: With `MIN_NAME_LENGTH = 4` and `MAX_EDIT_DISTANCE = 3`, a 4-character page name ("Tags") could match almost any other 4-7 character page name. For example, "Tags" (edit distance 3 from "Bags", "Taps", "Lags", "Task", "Tabs").

**Mitigation**:
- Apply a proportional threshold: `maxAllowedDistance = floor(name.length * 0.25)` with a minimum of 1 and maximum of 3. This means 4-char names require exact distance 1, 8-char names allow distance 2, 12-char names allow distance 3.
- The `DuplicateDetector.MAX_EDIT_DISTANCE` constant becomes a max cap, not a fixed value.
- Add a user-accessible sensitivity slider in the DuplicatePagesTool header (Low / Medium / High) that maps to multipliers 0.15 / 0.25 / 0.35.
- Unit test: verify that "Tags" does not match "Task" at Medium sensitivity.

**Files Likely Affected**:
- `DuplicateDetector.kt`
- `DuplicatePagesTool.kt` (sensitivity control)

---

### Bug 4: Undo/redo implications of rename and merge [SEVERITY: High]

**Description**: The existing `CommandManager` (from ED-001) holds an undo stack of `EditorCommand` entries. A rename or merge affects multiple pages and files — these are not representable as simple text-edit undos. If the user presses Ctrl+Z after a rename, the CommandManager may attempt to undo the last in-editor text change, ignoring the rename entirely.

**Mitigation**:
- `BacklinkRenamer` and `PageMergeService` should push a `CompoundUndoEntry` onto a separate graph-operation undo stack (distinct from the block-editor stack). This stack is drained separately (e.g., via a "Undo Graph Operation" command or a dedicated undo button in the rename confirmation success toast).
- For v1, rename undo can be implemented as "rename back" (re-execute `BacklinkRenamer.execute` with names swapped). Merge undo is harder (requires reconstructing the source page from the appended blocks) — accept that merge is not undoable in v1, and display a prominent "This cannot be undone" warning in the merge confirmation dialog.
- Document this limitation clearly in `PageMergeService` and the merge dialog.

**Files Likely Affected**:
- `BacklinkRenamer.kt`
- `PageMergeService.kt`
- `RenamePageDialog.kt`
- `DuplicatePagesTool.kt`
- `LogseqViewModel.kt` (CommandManager integration)

---

### Bug 5: Concurrent rename collides with in-progress block edit [SEVERITY: Medium]

**Description**: If the user is actively editing a block containing `[[OldName]]` when a rename is confirmed from a different screen (e.g., from the sidebar page list), the `BlockStateManager` holds a local copy of the block content. When the user saves their edit (via the 500 ms debounce), the old content (with `[[OldName]]`) overwrites the renamed content in the DB and on disk.

**Mitigation**:
- Before executing a rename, check `blockStateManager.editingBlockUuid`. If any block on an affected page is currently being edited, surface a warning: "Save or discard your current edit before renaming."
- Alternatively, apply the wikilink substitution to the in-memory `BlockStateManager` content at the same time as the DB update, so the debounced save writes the updated content.
- Preferred approach: block the rename from completing while any affected page has an active edit — simpler to reason about. Add this guard to `BacklinkRenamer.execute`.

**Files Likely Affected**:
- `BacklinkRenamer.kt`
- `LogseqViewModel.kt`
- `ui/state/BlockStateManager.kt`

---

### Bug 6: Merge re-parents blocks with position collisions [SEVERITY: Medium]

**Description**: The merge protocol re-parents source root blocks with recalculated `position` values starting from `lastTargetPosition + 1`. If the merge transaction is interrupted after the separator block is inserted but before all blocks are re-parented, position values in the target page may have gaps, and the `left_uuid` linked-list chain will be broken.

**Mitigation**:
- All position recalculations for the merged blocks must happen inside the single DB transaction. Do not split the position-assignment step across multiple transactions.
- After the transaction commits, call `blockRepository.getBlocksForPage(targetUuid)` and verify the `left_uuid` chain is intact before writing to disk. If the chain is broken, surface an error (do not write a corrupt file).
- Add a `left_uuid` chain validator utility (useful for other operations too) in `BlockRepository` or a companion object.

**Files Likely Affected**:
- `PageMergeService.kt`
- `SqlDelightBlockRepository.kt` (chain validator)

---

### Bug 7: `getUnlinkedReferences` LIKE query matches text inside existing wikilinks [SEVERITY: Low]

**Description**: `BlockRepository.getUnlinkedReferences(pageName)` likely uses a LIKE query (e.g., `content LIKE '%pageName%'`). If a block contains `[[pageName - extended]]` (a different page with the same prefix), it may surface as an unlinked mention even though "pageName" does not appear as plain text.

**Mitigation**:
- The `UnlinkedMentionsPanel` re-runs `AhoCorasickMatcher.findAll` with exclusion zones (wikilinks, tags, code spans) before displaying each row. Any block returned by the LIKE query that has no valid match after exclusion-zone filtering is silently skipped in the UI.
- This is a display-layer filter, not a query-layer fix — acceptable because the LIKE query already scans the full block table; adding exclusion logic in SQL would require complex regex.
- Log the count of false-positive blocks to aid future optimization.

**Files Likely Affected**:
- `ReferencesPanel.kt`
- `SqlDelightBlockRepository.kt`

---

## ADR Index

| ADR | Title | File |
|-----|-------|------|
| ADR-007 | Transactional rename strategy: DB-first with file-write compensation | `project_plans/stelekit/decisions/ADR-007-transactional-rename-strategy.md` |
| ADR-008 | Merge content strategy: append with separator vs. interleave | `project_plans/stelekit/decisions/ADR-008-merge-content-strategy.md` |
| ADR-009 | Duplicate detection algorithm: edit distance vs. embedding similarity | `project_plans/stelekit/decisions/ADR-009-duplicate-detection-algorithm.md` |

---

## Testing Strategy

### Unit Tests (no Compose, no database)

| Test Class | Coverage |
|------------|----------|
| `BacklinkRenamerTest` | Preview count accuracy; full rename correctness; alias form handling; rollback on file failure; journal page rejection |
| `DuplicateDetectorTest` | Edit-distance thresholds; proportional sensitivity; journal/short-name exclusions; prefix detection; empty input |
| `PageMergeServicePreviewTest` | Block count and backlink count accuracy without mutation |

### Integration Tests (in-memory repositories + in-memory filesystem)

| Test Class | Coverage |
|------------|----------|
| `BacklinkRenamerIntegrationTest` | Full rename flow across multiple pages; DB state post-commit; file state post-rewrite; partial-failure rollback |
| `UnlinkedMentionsTest` | `getUnlinkedReferences` count; `linkMention` mutation; `linkAllMentions` batch; exclusion zone false-positive filtering |
| `PageMergeServiceTest` | Full merge: block re-parenting; backlink redirect via `BacklinkRenamer`; source page deletion; chain integrity validation |

### UI Tests (Compose test harness, JVM)

| Test Class | Coverage |
|------------|----------|
| `RenamePageDialogTest` | Input state; preview state; error state; confirm calls ViewModel; cancel leaves state unchanged |
| `DuplicatePagesToolTest` | Candidate list renders; "Keep A/B" radio changes selection; "Skip" removes candidate; "Merge" calls ViewModel |

### Manual Acceptance Checklist

**Story 1 — Rename**
- [ ] Rename "Project Alpha" to "Project Alpha 2026" — all `[[Project Alpha]]` in all pages become `[[Project Alpha 2026]]`
- [ ] Rename preview shows correct block and page counts before confirming
- [ ] Rename of a page with no backlinks completes instantly with "0 blocks updated"
- [ ] Cancel on preview dialog leaves all state unchanged
- [ ] Alias form `[[Project Alpha|alpha]]` becomes `[[Project Alpha 2026|alpha]]`
- [ ] Rename with an existing page name shows an inline validation error
- [ ] Journal page rename option is disabled (greyed out)
- [ ] After rename, navigating to the renamed page still works (DB row is consistent)

**Story 2 — Unlinked Mentions**
- [ ] "Unlinked Mentions" tab shows blocks containing the page name as plain text
- [ ] Matched text is highlighted in each row
- [ ] "Link" converts the plain text to wikilink; row disappears from list
- [ ] "Link All" (with 3+ mentions) shows confirmation count; all are linked after confirm
- [ ] Tab shows "0 unlinked mentions" when none exist
- [ ] Disabling "Show page-name suggestions" hides the tab content (or disables "Link" actions)

**Story 3 — Duplicate Detection and Merge**
- [ ] "Find Duplicate Pages" scan completes and shows candidate pairs
- [ ] Pairs sorted by edit distance (closest first)
- [ ] "Skip" dismisses a pair without any DB change
- [ ] Merge: source page's blocks appear at the bottom of the kept page after merge
- [ ] Merge: separator `## (Merged from [[SourceName]])` appears above appended blocks
- [ ] Merge: `[[SourceName]]` links across the graph become `[[TargetName]]`
- [ ] Merge: source page no longer appears in "All Pages" list
- [ ] Merge: "This cannot be undone" warning is displayed before confirming
