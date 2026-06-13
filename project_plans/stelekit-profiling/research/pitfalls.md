# Pitfalls Research: Failure Modes for Headless StelekitViewModel in jvmTest

## Key files read
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` (full file, focus on lines 240-260, 762-914)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModelDependencies.kt`

---

## 1. `init` block fires immediately — can trigger graph load

The ViewModel `init` runs synchronously at construction time:

```kotlin
init {
    blockStateManager?.let { graphLoader.setActivePageUuids(it.activePageUuids) }
    updateCommands()        // launches a coroutine on `scope`
    observeSyncState()      // launches a coroutine on `scope`

    val path = _uiState.value.currentGraphPath
    val onboarded = _uiState.value.onboardingCompleted
    if (path.isNotEmpty() && onboarded) {
        loadGraph(path)     // TRIGGERS A FULL GRAPH LOAD if settings say so
    }
    observeSpecialPages()   // launches pageRepository.getAllPages().collect { ... }
}
```

**Pitfall:** If `platformSettings.getString("lastGraphPath", "")` returns a non-empty path AND `platformSettings.getBoolean("onboardingCompleted", false)` is `true`, the ViewModel will call `loadGraph(path)` at construction. For a benchmark that controls the graph, use a `NoOpSettings` / `InMemorySettings` stub that returns empty string and false, then call `loadGraph(tempDir.absolutePath)` manually.

**Fix:** Provide a `platformSettings` stub that returns:
- `"lastGraphPath"` → `""` (empty)
- `"onboardingCompleted"` → `false`
- All other keys → empty/false/null

## 2. `observeSpecialPages()` launches a long-running collector immediately

```kotlin
private fun observeSpecialPages() {
    scope.launch {
        ...
        pageRepository.getAllPages().collect { result ->   // long-running collect
            cachedAllPages = allPages
            _uiState.update { ... }
        }
    }
    loadMoreRegularPages(reset = true)   // DB query on init
    loadMoreJournalPages(reset = true)   // DB query on init
}
```

This collector runs for the lifetime of the ViewModel scope. It is safe — it just means `cachedAllPages` updates asynchronously. The benchmark must `delay(100)` or `yield()` after construction to let the initial page load complete before navigating.

## 3. `updateCommands()` on `init` — safe but fires coroutine

```kotlin
private fun updateCommands() {
    scope.launch {
        try {
            val availableCommands = getAvailableCommands()
            // ... updates _uiState.commands
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { logger.error("Failed to update commands") }
    }
}
```

This launches a coroutine immediately at construction. It calls `getAvailableCommands()` which uses `commandManager`. No Compose dependency — but the launch may race with the first navigation call. Use `delay(50)` after construction to let it settle.

## 4. `startMidnightBoundaryWatcher()` — launched inside `loadGraph` → `onPhase1Complete`

```kotlin
onPhase1Complete = {
    ...
    scope.launch { journalService.ensureTodayJournal() }
    startMidnightBoundaryWatcher()   // starts an infinite delay loop
}
```

`startMidnightBoundaryWatcher()` launches a coroutine that `delay(millisUntilNextMidnight())` — roughly 12 hours of delay. This is harmless in tests (coroutine never wakes up during the benchmark run) but will be a dangling coroutine if the ViewModel scope is not cancelled at the end. Always call `viewModel.close()` in `finally`.

## 5. `navigateToPageByUuid` — analysis of lines 905-914

```kotlin
fun navigateToPageByUuid(pageUuid: String) {
    scope.launch {
        val page = pageRepository.getPageByUuid(pageUuid).first().getOrNull()
        if (page != null) {
            navigateTo(Screen.PageView(page))
        } else {
            _uiState.update { it.copy(statusMessage = "Page not found: $pageUuid") }
        }
    }
}
```

**This is entirely safe for headless tests:**
- No Compose runtime required
- No UI collector required
- `navigateTo(Screen.PageView(page))` only mutates `_uiState` (a `MutableStateFlow`) and fires two additional fire-and-forget launches: `refreshCurrentPage()` and `scope.launch { searchRepository.recordPageVisit(page.uuid) }`
- The test must `delay(50)` after calling `navigateToPageByUuid` to let the coroutine complete before timing the next action

**Timing caveat:** `navigateToPageByUuid` launches on `scope` but returns immediately. To measure true wall-clock latency including the DB read, the benchmark must wait for `_uiState` to update:
```kotlin
val startMs = epochMs()
viewModel.navigateToPageByUuid(uuid)
// Wait for state to reflect the navigation
viewModel.uiState.first { it.currentScreen is Screen.PageView && (it.currentScreen as Screen.PageView).page.uuid == uuid }
val endMs = epochMs()
```

## 6. `navigateTo(Screen.PageView(page))` — inline timing already in place

The ViewModel already records span timing for navigation:
```kotlin
val navStart = kotlin.time.Clock.System.now().toEpochMilliseconds()
// ... state mutation
val navEnd = kotlin.time.Clock.System.now().toEpochMilliseconds()
histogramWriter?.record("navigation", navDurationMs)
spanEmitter.emit("navigation", ...)
```

This captures only the synchronous state mutation, not the async DB read from `navigateToPageByUuid`. The benchmark should measure wall-clock time from the calling side (await `uiState` change) for accurate p50/p95/p99.

## 7. `renamePage` — coroutine scope + actor requirement

```kotlin
fun renamePage(page: Page, newName: String) {
    ...
    scope.launch {
        ...
        when (val result = backlinkRenamer.execute(page, trimmed, graphPath)) { ... }
    }
}
```

`backlinkRenamer` is a lazy delegate:
```kotlin
private val backlinkRenamer by lazy {
    BacklinkRenamer(
        pageRepository, blockRepository,
        graphWriter,
        requireNotNull(writeActor) { "writeActor is required for rename operations" }
    )
}
```

**Pitfall:** If `writeActor` is null in `StelekitViewModelDependencies`, accessing `backlinkRenamer` throws `IllegalArgumentException`. Always pass `writeActor = repoSet.writeActor` in deps when benchmarking rename.

## 8. `indentBlock` / `outdentBlock` / `moveBlockUp` / `moveBlockDown` — safety

```kotlin
@OptIn(DirectRepositoryWrite::class)
fun indentBlock(blockUuid: String) {
    scope.launch {
        blockRepository.indentBlock(blockUuid)
    }
}
```

These bypass the write actor and write directly to the repository. In a test environment with `SqlDelightBlockRepository`, these calls are safe — `PlatformDispatcher.DB` maps to `Dispatchers.IO` on JVM. No Compose dependency.

**Timing:** same coroutine-launch-and-forget pattern — must await state change or use a `delay` to capture true latency.

## 9. Platform-specific code that requires stubs in jvmTest

| Code | Platform concern | JVM test behavior |
|------|-----------------|-------------------|
| `PlatformFileSystem()` | JVM: `java.nio.file` — works without any stub | OK |
| `PlatformDispatcher.DB` | JVM: `Dispatchers.IO` — works | OK |
| `PlatformDispatcher.IO` | JVM: `Dispatchers.IO` — works | OK |
| `OtelRepositoryWrappers` | JVM actual uses OTel SDK — works | OK (SDK on jvmMain classpath) |
| `Settings` / `platformSettings` | Requires `InMemorySettings` stub — no platform storage in tests | **Must stub** |
| `NotificationManager` | Defaults to null — safe | OK |
| `BlockStateManager` | Defaults to null — safe | OK |
| Compose `rememberCoroutineScope()` | Not used in ViewModel itself | OK |

## 10. Scope lifecycle — must explicitly cancel

The ViewModel's `scope` is `CoroutineScope(SupervisorJob() + Dispatchers.Default)` when `deps.scope` is injected. Tests must call `viewModel.close()` (which calls `scope.cancel()`) in `finally` to terminate the midnight watcher, the `getAllPages().collect`, and any in-flight coroutines. Without this, dangling coroutines will leak across tests and potentially cause false assertion failures.

## 11. `Settings` stub needed

`StelekitViewModelDependencies.platformSettings` is `Settings` (a platform abstraction). The `init` block reads from it. The test needs a minimal in-memory stub:

```kotlin
class InMemorySettings : Settings {
    private val map = mutableMapOf<String, Any?>()
    override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
    override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
    override fun putString(key: String, value: String) { map[key] = value }
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    // ... other methods as no-ops
}
```

---

## Summary of failure modes and mitigations

| Failure | Cause | Mitigation |
|---------|-------|------------|
| Graph auto-loads at construction | `platformSettings` returns real path | Use `InMemorySettings` stub returning empty/false |
| `backlinkRenamer` NPE | `writeActor` is null | Always pass `writeActor = repoSet.writeActor` |
| Dangling coroutines after test | `scope` never cancelled | `finally { viewModel.close(); scope.cancel() }` |
| Timing misses DB round-trip | `navigateToPageByUuid` returns before coroutine finishes | Await `uiState.first { ... }` after each action |
| Initial command load races | `updateCommands()` fires at construction | `delay(50)` after `StelekitViewModel(deps)` before first benchmark action |
| `getAllPages().collect` never settles | `observeSpecialPages()` runs long-term | OK — just `delay(100)` after construction; collector runs in background |
