# ADR-003: Use `dirtyPageUuids` (Dirty Blocks Only) as the Watcher Guard, Not `activePageUuids` (All Open Pages)

## Status
Accepted

## Context

`GraphFileWatcher` uses a guard set of file paths to suppress `onReloadFile` for pages that are currently open. The intent is to avoid overwriting a user's in-progress edits with an externally-detected change. The original implementation used `activePageUuids` — the set of all page UUIDs currently observed by the UI — as the guard.

This caused a correctness defect: the journals screen observes the current journal page's UUID, so the journals page UUID was always in `activePageUuids` while the journals screen was open. Since the guard matched, `onReloadFile` was never called for journal pages, and external changes never appeared on screen — even when the user had made no edits at all.

The correct guard predicate is: "does this page have unsaved block edits?" A page that is open but not being edited should be reloaded normally when an external change is detected.

`BlockStateManager` already tracks `_dirtyBlocks: MutableStateFlow<Map<String, Long>>` (block UUID → version) and `_blocks: MutableStateFlow<Map<String, List<Block>>>` (page UUID → block list). The set of dirty page UUIDs can be derived as the set of page UUIDs in `_blocks` whose block lists contain at least one block UUID present in `_dirtyBlocks`.

## Decision

`BlockStateManager` exposes a new derived property:

```kotlin
val dirtyPageUuids: StateFlow<Set<String>> = combine(_dirtyBlocks, _blocks) { dirtyBlocks, blocks ->
    if (dirtyBlocks.isEmpty()) return@combine emptySet()
    blocks.entries
        .filter { (_, blockList) -> blockList.any { it.uuid in dirtyBlocks } }
        .map { it.key }
        .toSet()
}.distinctUntilChanged()
 .stateIn(scope, SharingStarted.Eagerly, emptySet())
```

`GraphLoaderPort` gains a method `setUnsavedPageUuids(uuids: StateFlow<Set<String>>?)`. `GraphLoader` implements it by launching a background Job on its owned `parallelScope` that collects from the provided `StateFlow`, resolves each UUID to a file path via `pageRepository.getPageByUuid`, and stores the result in a `@Volatile` field `unsavedPageFilePaths: Set<FilePath>`. `GraphFileWatcher` uses `unsavedPageFilePaths` as the reload guard instead of `activePageFilePaths`.

`StelekitViewModel.init` calls `graphLoader.setUnsavedPageUuids(blockStateManager.dirtyPageUuids)` alongside the existing `setActivePageUuids` call.

`SharingStarted.Eagerly` is used (not `WhileSubscribed`) because `GraphLoader` reads `dirtyPageUuids` at file-change time, which may occur with zero UI subscribers. The value must be current at the moment a poll fires.

## Alternatives Considered

**Separate "locked" flag per page**: A `Map<PageUuid, Boolean>` field tracking whether each open page is locked against reload. Requires an explicit lock/unlock call at every edit-start and edit-save site. Error-prone — any missing unlock leaves the page permanently locked until navigation away. Rejected in favour of the derived `StateFlow` which is always consistent with the actual dirty state.

**Debounce-based guard**: Suppress reloads for a fixed window (e.g., 5 seconds) after the last keystroke on a page. Does not correctly handle slow typists (reload could fire during a pause) or fast saves (lock lifted too soon). Does not compose well with the existing debounce in `BlockStateManager.diskWriteDebounce`. Rejected.

**Keep `activePageUuids` but add an "is editing" flag**: Add a boolean per open page and AND it with the active set. Functionally equivalent to `dirtyPageUuids` but with more explicit state. More surface area to keep consistent. Rejected.

## Consequences

- `_dirtyBlocks` must be declared before `dirtyPageUuids` in `BlockStateManager`'s class body. The Kotlin compiler enforces declaration-order initialisation for property initialisers; referencing `_dirtyBlocks` in the `combine` call requires `_dirtyBlocks` to already be initialised. `_blocks` must similarly precede `dirtyPageUuids`. This is a compile-time constraint, not a runtime concern.
- `GraphLoader.setUnsavedPageUuids` cancels any previous collection Job before starting a new one, so graph switches do not leak collectors.
- UUID → file path resolution via `getPageByUuid(...).first()` is one DB read per dirty page UUID per `dirtyPageUuids` emission. For typical single-page editing this is negligible. A future improvement could debounce the collection lambda to avoid N+1 reads during bulk edit sessions.
- Pages whose DB write is still in-flight (optimistic creation) will resolve to `null` from `getPageByUuid` and be absent from `unsavedPageFilePaths`. This creates a narrow TOCTOU window where a freshly-created page is not guarded. This edge case is acceptable for the current release; a retry mechanism can close the gap in a follow-up.
- An open-but-unedited journal page (or any open page with no dirty blocks) correctly returns to empty in `dirtyPageUuids`, and `onReloadFile` fires normally when an external change is detected.
