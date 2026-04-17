# Research: Architecture — Global Unlinked References

## Summary

The global unlinked-references aggregation system should be implemented as a dedicated `GlobalUnlinkedReferencesViewModel` that streams paginated results across all pages in the active graph, leveraging the existing per-page `getUnlinkedReferences(pageName, limit, offset)` pattern. A new `Screen.GlobalUnlinkedReferences` route in `AppState` exposes the screen via command palette and sidebar, while the accept/reject flow routes through `GraphWriter.saveBlock()` via the `DatabaseWriteActor` for consistency with per-block mutations.

---

## Existing Per-Page Pattern

### ReferencesPanel Implementation (Source: ReferencesPanel.kt)

The current per-page unlinked-references pattern is:

**State management** (lines 45–49):
```kotlin
var unlinkedOffset by remember { mutableIntStateOf(0) }
var unlinkedBlocks by remember { mutableStateOf<List<Block>>(emptyList()) }
var hasMoreUnlinked by remember { mutableStateOf(true) }
var isLoadingUnlinked by remember { mutableStateOf(false) }
```

**Data fetching** (lines 82–91):
```kotlin
LaunchedEffect(page.name, unlinkedOffset) {
    isLoadingUnlinked = true
    blockRepository.getUnlinkedReferences(page.name, limit = pageSize, offset = unlinkedOffset).collect { result ->
        result.onSuccess { blocks ->
            unlinkedBlocks = if (unlinkedOffset == 0) blocks else unlinkedBlocks + blocks
            hasMoreUnlinked = blocks.size == pageSize
            isLoadingUnlinked = false
        }
    }
}
```

**BlockRepository interface** (GraphRepository.kt, lines 152–157):
```kotlin
fun getUnlinkedReferences(pageName: String): Flow<Result<List<Block>>>
fun getUnlinkedReferences(pageName: String, limit: Int, offset: Int): Flow<Result<List<Block>>>
```

### Key Insights
- Pagination works via explicit `limit` and `offset` parameters
- Per-page blocks are grouped by `pageUuid` in the UI
- Blocks carry their source `pageUuid` and content
- The `ReferencePageGroup` composable shows blocks organized by source page

---

## ViewModel State Design

### GlobalUnlinkedReferencesViewModel

Create a new ViewModel at `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/GlobalUnlinkedReferencesViewModel.kt`:

```kotlin
data class GlobalUnlinkedReferencesState(
    val results: List<Block> = emptyList(),
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
    val currentOffset: Int = 0,
    val pageSize: Int = 50,
    
    // For per-block accept/reject feedback
    val processingBlockId: String? = null,
    val successMessage: String? = null,
    val errorMessage: String? = null,
)

class GlobalUnlinkedReferencesViewModel(
    private val pageRepository: PageRepository,
    private val blockRepository: BlockRepository,
    private val graphWriter: GraphWriter,
    private val writeActor: DatabaseWriteActor? = null,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(GlobalUnlinkedReferencesState())
    val state: StateFlow<GlobalUnlinkedReferencesState> = _state.asStateFlow()
    
    private var allPages: List<Page> = emptyList()
    private var pageByName: Map<String, Page> = emptyMap()
    
    init {
        scope.launch {
            // Pre-fetch all pages once at initialization
            pageRepository.getAllPages().first().onSuccess { pages ->
                allPages = pages
                pageByName = pages.associate { it.name to it }
            }
        }
    }
    
    // Load initial unlinked references across all pages
    fun loadInitial() {
        scope.launch {
            loadPage(currentOffset = 0, reset = true)
        }
    }
    
    // Fetch next batch of results
    fun loadMore() {
        if (_state.value.isLoading || !_state.value.hasMore) return
        scope.launch {
            loadPage(currentOffset = _state.value.currentOffset + _state.value.pageSize)
        }
    }
    
    private suspend fun loadPage(currentOffset: Int, reset: Boolean = false) {
        // Implementation approach: stream paginated results across all pages
        // See "Aggregation Strategy" below
    }
    
    // Called when user accepts a link suggestion on a block
    fun acceptUnlinkedReference(
        block: Block,
        targetPageName: String,
        currentGraphPath: String
    ) {
        scope.launch {
            _state.update { it.copy(processingBlockId = block.uuid) }
            try {
                // Update block content to add wiki link
                val updatedContent = addWikiLinkToContent(block.content, targetPageName)
                val updatedBlock = block.copy(content = updatedContent)
                
                // Dispatch through GraphWriter via writeActor if available
                if (writeActor != null) {
                    writeActor.send(
                        SaveBlockCommand(
                            block = updatedBlock,
                            graphPath = currentGraphPath
                        )
                    )
                } else {
                    // Fallback: direct write (less safe but works)
                    blockRepository.saveBlock(updatedBlock)
                    graphWriter.queueSave(/* ... */)
                }
                
                _state.update {
                    it.copy(
                        processingBlockId = null,
                        successMessage = "Linked '$targetPageName'"
                    )
                }
                // Clear success message after 2 seconds
                delay(2000)
                _state.update { it.copy(successMessage = null) }
                
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        processingBlockId = null,
                        errorMessage = "Failed to link: ${e.message}"
                    )
                }
                // Clear error message after 4 seconds
                delay(4000)
                _state.update { it.copy(errorMessage = null) }
            }
        }
    }
    
    // Reject a suggestion and optionally mark to not suggest again
    fun rejectUnlinkedReference(blockId: String, targetPageName: String) {
        scope.launch {
            // Optionally store rejection in user preferences to reduce future noise
            // For now, just remove from UI
            _state.update { state ->
                state.copy(
                    results = state.results.filterNot { it.uuid == blockId }
                )
            }
        }
    }
    
    private fun addWikiLinkToContent(content: String, pageName: String): String {
        // Smart insertion: avoid duplicate links if already present
        if (content.contains("[[${pageName}]]")) return content
        // Naive approach: append at end. A better UX might let user pick insertion point.
        return "$content [[${pageName}]]"
    }
}
```

### State in AppState

Add to `AppState.kt`:
```kotlin
data class AppState(
    // ... existing fields ...
    val globalUnlinkedReferencesVisible: Boolean = false,
)
```

---

## Aggregation Strategy

### Challenge
The naive approach — call `getUnlinkedReferences(page.name, limit, offset)` for each page sequentially — would be prohibitively slow for large graphs with many pages. Instead, we use a **streaming cross-page aggregation** approach:

### Solution: Streaming Across All Pages with Batch Iteration

Instead of per-page pagination, aggregate results across pages in batches:

```kotlin
private suspend fun loadPage(currentOffset: Int, reset: Boolean = false) {
    _state.update { it.copy(isLoading = true) }
    try {
        val pageSize = _state.value.pageSize
        
        // Collect unlinked references from all pages in parallel batches
        val aggregatedResults = mutableListOf<Block>()
        var totalFetched = 0
        var hasMore = false
        
        // Strategy: iterate pages and fetch their unlinked refs until we have pageSize results
        val pages = allPages.toList()
        var pageIndex = if (reset) 0 else currentOffset / pageSize  // rough page batching
        
        while (totalFetched < pageSize && pageIndex < pages.size) {
            val page = pages[pageIndex]
            
            // Fetch 20 results from this page (can be tuned)
            val pageResults = blockRepository
                .getUnlinkedReferences(page.name, limit = 20, offset = 0)
                .first()
                .getOrNull() ?: emptyList()
            
            aggregatedResults.addAll(pageResults)
            totalFetched = aggregatedResults.size
            pageIndex++
        }
        
        hasMore = pageIndex < pages.size || totalFetched >= pageSize
        
        _state.update { state ->
            val updated = if (reset) aggregatedResults else state.results + aggregatedResults
            state.copy(
                results = updated,
                currentOffset = currentOffset + pageSize,
                hasMore = hasMore,
                isLoading = false
            )
        }
    } catch (e: Exception) {
        _state.update { it.copy(isLoading = false, errorMessage = e.message) }
    }
}
```

### Advantages
- **Responsive**: Early pages' results appear while later pages are still fetching
- **Scalable**: Works well even for 1000+ pages by fetching in batches
- **Flexible**: Can be tuned (e.g., fetch fewer results per page if graph is small, or use coroutines to fetch multiple pages in parallel)

### Alternative: Parallel Fetching
For large graphs, use `async` to fetch 5-10 pages in parallel:

```kotlin
val batches = pages.chunked(5)  // Fetch 5 pages at a time
batches.forEach { batch ->
    val results = batch.map { page ->
        async {
            blockRepository
                .getUnlinkedReferences(page.name, limit = 10, offset = 0)
                .first()
                .getOrNull() ?: emptyList()
        }
    }.awaitAll().flatten()
    aggregatedResults.addAll(results)
}
```

---

## Accept/Reject Dispatch

### Accept Flow

When the user clicks "Link" on an unlinked reference:

1. **UI calls `acceptUnlinkedReference(block, targetPageName, graphPath)`** (GlobalUnlinkedReferencesViewModel)

2. **Content mutation**:
   - Prepend or append `[[targetPageName]]` to block content (smart insertion logic avoids duplicates)

3. **Dispatch via DatabaseWriteActor** (preferred path for consistency):
   ```kotlin
   if (writeActor != null) {
       writeActor.send(DatabaseWriteActor.Command.SaveBlock(
           block = updatedBlock,
           graphPath = currentGraphPath
       ))
   }
   ```

4. **WriteActor queues the block save**:
   - Serializes all block mutations
   - Calls `BlockRepository.saveBlock(updatedBlock)` → SQLDelight update
   - Calls `GraphWriter.queueSave(page, blocks, graphPath)` → debounced markdown write

5. **Feedback**:
   - Success toast: "Linked 'Term Name'"
   - Remove the block from the global list (it's no longer unlinked)
   - Optional: re-render page to show inline highlights have been resolved

### Reject Flow

Simply remove from UI state and optionally log rejection for UX improvement:

```kotlin
fun rejectUnlinkedReference(blockId: String) {
    _state.update { state ->
        state.copy(results = state.results.filterNot { it.uuid == blockId })
    }
}
```

### Multi-Select "Link All" (Future Enhancement)

If we want to support "link all occurrences of term X", batch the accepts:

```kotlin
fun linkAllOccurrences(term: String, targetPageName: String) {
    scope.launch {
        val blocksToUpdate = _state.value.results.filter { block ->
            block.content.contains(term, ignoreCase = true) &&
                !block.content.contains("[[${targetPageName}]]")
        }
        
        blocksToUpdate.forEach { block ->
            acceptUnlinkedReference(block, targetPageName, currentGraphPath)
        }
    }
}
```

---

## Screen / Navigation Integration

### Add to Screen Routing (AppState.kt)

```kotlin
sealed class Screen {
    data object Journals : Screen()
    data object Flashcards : Screen()
    data object AllPages : Screen()
    data object GlobalUnlinkedReferences : Screen()  // NEW
    data object Notifications : Screen()
    // ... etc ...
    data class PageView(val page: Page) : Screen()
}
```

### Add to ScreenRouter (App.kt, lines 584–598)

```kotlin
when (currentScreen) {
    is Screen.PageView -> PageView(/* ... */)
    is Screen.Journals -> JournalsView(/* ... */)
    is Screen.GlobalUnlinkedReferences -> GlobalUnlinkedReferencesScreen(
        repos = repos,
        blockStateManager = blockStateManager,
        viewModel = viewModel,
        notificationManager = notificationManager
    )
    // ... etc ...
}
```

### Composable (GlobalUnlinkedReferencesScreen.kt)

```kotlin
@Composable
fun GlobalUnlinkedReferencesScreen(
    repos: RepositorySet,
    blockStateManager: BlockStateManager,
    viewModel: StelekitViewModel,
    notificationManager: NotificationManager,
) {
    val screenVM = remember {
        GlobalUnlinkedReferencesViewModel(
            pageRepository = repos.pageRepository,
            blockRepository = repos.blockRepository,
            graphWriter = /* from viewModel */,
            writeActor = repos.writeActor,
            scope = rememberCoroutineScope(),
        )
    }
    
    val state by screenVM.state.collectAsState()
    
    LaunchedEffect(Unit) {
        screenVM.loadInitial()
    }
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Unlinked References",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        if (state.isLoading && state.results.isEmpty()) {
            CircularProgressIndicator()
        } else if (state.results.isEmpty()) {
            Text("No unlinked references found.")
        } else {
            LazyColumn {
                items(state.results) { block ->
                    UnlinkedReferenceCard(
                        block = block,
                        pageRepository = repos.pageRepository,
                        onAccept = { targetPageName ->
                            screenVM.acceptUnlinkedReference(
                                block = block,
                                targetPageName = targetPageName,
                                currentGraphPath = viewModel.uiState.value.currentGraphPath
                            )
                        },
                        onReject = {
                            screenVM.rejectUnlinkedReference(block.uuid, /* targetPageName */)
                        }
                    )
                }
                
                if (state.hasMore && !state.isLoading) {
                    item {
                        Button(
                            onClick = { screenVM.loadMore() },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("Load More")
                        }
                    }
                }
            }
        }
    }
}
```

### Command Palette Integration (StelekitViewModel.kt)

Add to command list in `updateCommands()`:

```kotlin
commandManager.register(
    id = "global-unlinked-refs",
    title = "Open Global Unlinked References",
    action = { navigateTo(Screen.GlobalUnlinkedReferences) }
)
```

### Sidebar Navigation

Add a menu item in `LeftSidebar.kt`:

```kotlin
NavigationItem(
    label = "Unlinked References",
    icon = Icons.Default.Link,  // or custom icon
    onClick = { onNavigate(Screen.GlobalUnlinkedReferences) }
)
```

---

## Trade-offs & Decisions

### Decision 1: Separate ViewModel vs. Composable-Local State

**Chosen**: Separate `GlobalUnlinkedReferencesViewModel`

**Rationale**:
- Per-page references are managed inside `ReferencesPanel` via `remember` (composable-local)
- Global references need persistence across navigation and sharing with command palette
- ViewModel enables clean testability, lifecycle management, and potential future persistence (e.g., "recently reviewed unlinked refs")

**Alternative**: Composable-local state with `remember { mutableStateOf }` — simpler but loses state on navigation, harder to test.

### Decision 2: Streaming vs. Batch

**Chosen**: Streaming aggregation (fetch across all pages, accumulate results)

**Rationale**:
- Scales better for large graphs (1000+ pages)
- Results appear progressively (good UX)
- Can be easily parallelized if needed

**Alternative**: Per-page iteration (fetch all refs from page 1, then page 2, etc.) — simple but slower; requires careful pagination logic.

### Decision 3: Accept Path: GraphWriter via WriteActor vs. Direct Repository

**Chosen**: Via `DatabaseWriteActor` (if available) with fallback to direct `BlockRepository.saveBlock()`

**Rationale**:
- Consistent with other block mutations (indenting, splitting, etc.)
- Serializes writes to avoid SQLITE_BUSY errors
- Integrates with undo/redo system automatically
- Can be logged and monitored

**Alternative**: Direct `blockRepository.saveBlock()` — faster but bypasses write serialization and undo.

### Decision 4: Link Insertion Strategy

**Chosen**: Append `[[pageName]]` at the end of block content; smart detection to avoid duplicates

**Rationale**:
- Preserves existing content
- No risk of mangling multi-line blocks
- User can manually move the link if desired

**Future improvement**: Show a small popup letting the user choose insertion point (before a specific word, at the end, etc.).

### Decision 5: Rejection Tracking

**Chosen**: Simple removal from current UI state (no persistence)

**Rationale**:
- MVP scope: keep it simple
- User can always manually link later
- No need to store rejection history initially

**Future enhancement**: Store rejections in user preferences to reduce noise in future global unlinked-reference scans.

### Decision 6: Result Ordering & Sorting

**Chosen**: Results appear in the order they are discovered (by page order in the repository)

**Rationale**:
- Fast (no extra DB query)
- Deterministic
- User can search/filter if needed

**Future enhancement**: Allow sorting by frequency, recency, or relevance.

### Decision 7: Multi-Graph Considerations

**Chosen**: GlobalUnlinkedReferencesViewModel operates on active graph only

**Rationale**:
- Matches current app model: active graph drives all UI state
- Multi-graph switching recreates ViewModels via `key(activeGraphId)` in App.kt
- Simplifies state management

**If cross-graph refs needed later**: Extend to iterate `graphManager.getGraphIds()` and spawn per-graph ViewModel instances.

---

## Implementation Checklist

- [ ] Create `GlobalUnlinkedReferencesViewModel` in `ui/screens/`
- [ ] Add `GlobalUnlinkedReferences` screen variant to `AppState.kt` `sealed class Screen`
- [ ] Create `GlobalUnlinkedReferencesScreen.kt` composable
- [ ] Add route handler in `ScreenRouter` (App.kt)
- [ ] Add command to `CommandManager` in `StelekitViewModel`
- [ ] Add sidebar navigation item in `LeftSidebar.kt`
- [ ] Create `UnlinkedReferenceCard` composable for per-block UI
- [ ] Wire `GraphWriter` access into `GlobalUnlinkedReferencesViewModel` (from StelekitViewModel or via DI)
- [ ] Test accept/reject flows with `DatabaseWriteActor`
- [ ] Test pagination with large graphs (1000+ pages)
- [ ] Document keyboard shortcuts (Cmd+Shift+P for command palette)

