# Features Research: ViewModel Actions Benchmarkable Without Compose

## Key files read
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightSearchRepository.kt`

---

## 1. Full action inventory

All public methods on `StelekitViewModel` that mutate state or do IO work:

| Action | Method signature | Compose dependency? |
|--------|-----------------|---------------------|
| Navigate to page by UUID | `fun navigateToPageByUuid(pageUuid: String)` | None |
| Navigate to page by name | `fun navigateToPageByName(pageName: String)` | None |
| Navigate to screen | `fun navigateTo(screen: Screen, addToHistory: Boolean = true)` | None |
| Go back | `fun goBack(): Boolean` | None |
| Go forward | `fun goForward(): Boolean` | None |
| Search pages | `fun searchPages(query: String): Flow<List<SearchResultItem>>` | None |
| Rename page | `fun renamePage(page: Page, newName: String)` | None |
| Indent block | `fun indentBlock(blockUuid: String)` | None |
| Outdent block | `fun outdentBlock(blockUuid: String)` | None |
| Move block up | `fun moveBlockUp(blockUuid: String)` | None |
| Move block down | `fun moveBlockDown(blockUuid: String)` | None |
| Move block (full) | `fun moveBlock(blockUuid: String, newParentUuid: String?, newPosition: Int)` | None |
| Add new block | `fun addNewBlock(currentBlockUuid: String)` | None |
| Add block to page | `fun addBlockToPage(pageUuid: String)` | None |
| Split block | `fun splitBlock(blockUuid: String, cursorPosition: Int)` | None |
| Merge block | `fun mergeBlock(blockUuid: String)` | None |
| Undo | `fun undo()` | None |
| Redo | `fun redo()` | None |
| Load graph | `fun loadGraph(path: String)` | None |
| Bulk delete pages | `fun bulkDeletePages(uuids: List<String>)` | None |
| Toggle favorite | `fun toggleFavorite(page: Page)` | None |
| Execute command | `suspend fun executeCommand(commandId: String, context: CommandContext)` | None |
| Get command suggestions | `suspend fun getCommandSuggestions(query: String, ...)` | None |

## 2. Parameters for FR-2 action categories

### navigate 20 pages — `navigateToPageByUuid(pageUuid: String)`
- Launches a coroutine that calls `pageRepository.getPageByUuid(pageUuid).first()`
- Calls `navigateTo(Screen.PageView(page))` internally
- Calls `searchRepository.recordPageVisit(page.uuid)` fire-and-forget
- **No Compose dependency** — pure state mutation + DB read

### type 30 blocks — `addNewBlock(currentBlockUuid: String)` + `addBlockToPage(pageUuid: String)`
- `addNewBlock` reads siblings, shifts positions, inserts a new block via `blockRepository.saveBlocks`
- `addBlockToPage` gets top-level blocks, creates a new block at end
- Both call `requestEditBlock(newBlock.uuid)` — this only updates `_uiState.editingBlockId`, no Compose dependency

### search 15 queries — `searchPages(query: String): Flow<List<SearchResultItem>>`
- Returns a Flow from `searchRepository.searchWithFilters(SearchRequest(query, limit=10))`
- Must be collected: `searchPages("query").first()` to get results
- `searchWithFilters` is defined in `SqlDelightSearchRepository` — FTS5 with BM25 ranking, runs on `PlatformDispatcher.DB`
- **No Compose dependency**

### rename 5 pages — `renamePage(page: Page, newName: String)`
- Requires `writeActor` non-null (asserted lazily in `backlinkRenamer`)
- Calls `backlinkRenamer.execute(page, newName, graphPath)` which updates DB + rewrites disk files
- Requires `_uiState.value.currentGraphPath` to be set to the graph dir
- **No Compose dependency**

### indent/outdent blocks — `indentBlock(blockUuid: String)`, `outdentBlock(blockUuid: String)`
- Each launches a coroutine calling `blockRepository.indentBlock(blockUuid)` / `blockRepository.outdentBlock(blockUuid)`
- Both are `@OptIn(DirectRepositoryWrite::class)` — bypass write actor, direct DB writes
- **No Compose dependency**

### move block up/down — `moveBlockUp(blockUuid: String)`, `moveBlockDown(blockUuid: String)`
- Same pattern as indent/outdent: direct `blockRepository` calls
- **No Compose dependency**

### undo — `fun undo()`
- Calls `undoManager?.undo()` — no-op if `undoManager` is null in deps
- For the benchmark to exercise real undo, `UndoManager` must be wired into deps; otherwise this is a no-op
- **No Compose dependency**

### navigate back — `fun goBack(): Boolean`
- Pure `_uiState` mutation — reads `navigationHistory` and decrements `historyIndex`
- **No Compose dependency**

## 3. `searchWithFilters` — key parameters

```kotlin
data class SearchRequest(
    val query: String?,
    val limit: Int = 20,
    val offset: Int = 0,
    val scope: SearchScope = SearchScope.ALL,
    val dataTypes: Set<DataType> = setOf(DataType.TITLES, DataType.CONTENT),
    val pageUuid: String? = null,
    val dateRange: DateRange? = null,
)
```

- `query.length >= 2` is required for block search (single-char queries skip block FTS to avoid full BM25 scan)
- `SearchScope.ALL` searches both pages and blocks
- `SearchScope.PAGES_ONLY` / `SearchScope.BLOCKS_ONLY` for targeted benchmarks

## 4. `renamePage` implementation notes (from GraphWriter)

`GraphWriter.renamePage(page, newName, graphPath)` is the disk-side operation:
- Reads old file, writes new file with new path, deletes old file
- AAD-aware for encrypted graphs (but test graphs will be plaintext, so `cryptoLayer` is null)
- Sets `graphPath` field on the writer to compute correct file paths
- The `graphWriter.graphPath` must be set to the temp graph directory before rename benchmarks run

For the benchmark, the `GraphWriter` must be instantiated with `graphPath = tempDir.absolutePath`:
```kotlin
val graphWriter = GraphWriter(
    fileSystem  = PlatformFileSystem(),
    writeActor  = repoSet.writeActor,
    graphPath   = tempDir.absolutePath,
)
```

## 5. Actions that CANNOT be benchmarked without stubs

| Action | Reason | Workaround |
|--------|--------|------------|
| `exportPage(formatId)` | Requires `blockStateManager?.blocksForPage(pageUuid)` — null without BlockStateManager | Skip or stub BSM |
| `undo()` with actual undo | Requires `UndoManager` which has its own coroutine scope | Wire `UndoManager` into deps |
| `startAutoSave()` | Calls `graphWriter.startAutoSave()` + `observeExternalFileChanges()` | Don't call; use direct actor writes |
| File-watching conflict dialog | Requires external file change events from GraphLoader | Not needed for session benchmark |

---

## Summary

- All 10 FR-2 action categories (`navigate`, `type`, `search`, `rename`, `indent`, `outdent`, `moveUp/Down`, `undo`, `back`) can be called directly on `StelekitViewModel` from a `runBlocking` test with no Compose runtime dependency.
- Methods fire coroutines on the ViewModel's own `scope`; the test must `runBlocking` and `yield()` or `delay(50)` after each call to let the coroutine complete before collecting results.
- `searchPages(query).first()` is the correct collection pattern — it returns a `Flow` that completes after one emission on `PlatformDispatcher.DB`.
- `renamePage` requires `writeActor` non-null and `graphWriter.graphPath` set to the test graph dir.
