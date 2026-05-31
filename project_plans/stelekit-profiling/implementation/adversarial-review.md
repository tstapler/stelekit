# Adversarial Review: stelekit-profiling

**Date**: 2026-05-29
**Verdict**: CONCERNS

---

## Blockers

*(none)*

---

## Concerns

- [ ] **Navigation timing await has a hidden infinite-hang risk** — `viewModel.uiState.first { it.currentScreen is Screen.PageView && ... }` will block forever if the page UUID does not exist in the graph (e.g., the random page was deleted during Action D renaming, or the page was a journal with a UUID that doesn't survive the copy). Recommendation: wrap every `uiState.first { }` call in `withTimeoutOrNull(5_000)` and treat null as a timing sample of `Long.MAX_VALUE` (flagged outlier) rather than hanging the whole test. Add a guard: `if (page.uuid !in loadedPageUuids) continue`.

- [ ] **Rename await condition is fragile — `pages` list in uiState may not update synchronously** — The plan awaits `state.pages.any { it.uuid == page.uuid && it.name.startsWith("bench-${i}") }`. However, `uiState.pages` in `StelekitViewModel` is populated by `observeSpecialPages()` which collects from `pageRepository.getAllPages()` — a `Flow` that only emits when the DB write completes AND the Flow collector re-executes. There is a race: `renamePage` writes to disk and the DB, but the `getAllPages()` flow may not re-emit before the test times out (2s). Recommendation: drain `delay(1000)` as primary await (not fallback) OR await the `pageRepository.getAllPages().first { ... }` flow directly, bypassing the uiState level.

- [ ] **`RingBufferSpanExporter` thread safety — noted as not thread-safe in architecture.md** — The session benchmark calls `viewModel.navigateToPageByUuid` (which spawns coroutines on the ViewModel scope / Dispatchers.Default) that in turn call `ringBuffer.record()` concurrently from multiple threads. If `RingBufferSpanExporter` is not thread-safe and uses a plain `ArrayList`, concurrent `record()` calls can produce `ConcurrentModificationException` or dropped spans. Recommendation: before implementing, confirm whether `RingBufferSpanExporter` uses `AtomicInteger` + array or a plain list. If plain list, either (a) accept some dropped spans with a comment, or (b) wrap in `Collections.synchronizedList` at construction time. The architecture.md notes "all calls must come from a single thread" which is violated here.

- [ ] **Action B typing measure includes artificial 200ms delay** — The plan measures `measureTime { viewModel.addBlockToPage(pageUuid); delay(200) }` which means every sample will be ~200ms regardless of actual write latency. The meaningful measurement is the block-add latency only; the 200ms pacing is a think-time simulation that should be excluded from the sample. Recommendation: split timing: `val writeElapsed = measureTime { viewModel.addBlockToPage(pageUuid); delay(50) }` for latency; then `delay(150)` for pacing. Record `writeElapsed` not the total.

- [ ] **Action E-G: `indentBlock` may silently no-op on blocks without a valid sibling** — `blockRepository.indentBlock(uuid)` requires the block to have a preceding sibling to become a child of. After 10 indents and 10 outdents on the same block list (task 2.2.5b), many blocks will have been moved and the UUIDs from the initial `pageBlocks` snapshot may no longer be at valid positions. Result: silent no-ops or wrong latency measurement. Recommendation: re-fetch `pageBlocks` from `uiState` before each indent/outdent loop iteration, or operate on only the middle-range blocks that are guaranteed to have siblings.

- [ ] **`searchRepository` is not passed to `StelekitViewModelDependencies` in the stack research snippet, but is required** — The stack.md shows the deps constructor with `searchRepository = repoSet.searchRepository`. If `repoSet` (of type `RepositorySet`) does not expose a `searchRepository` field, the implementation will fail to compile. Recommendation: verify `RepositorySet` exposes `searchRepository` (or the equivalent `SqlDelightSearchRepository` instance) before writing the code. The plan assumes it is available but does not explicitly confirm this.

- [ ] **`uiState.first { it.currentGraphPath.isNotEmpty() }` is not a reliable "graph fully loaded" signal** — `currentGraphPath` is set at the start of `loadGraph()` (phase 1), but the full graph load (phases 2 and 3 — DB write actor drain, FTS index build) happens asynchronously after phase 1. Navigating to pages immediately after `currentGraphPath` is set may hit an empty DB. Recommendation: await a more reliable signal such as `uiState.first { it.pages.isNotEmpty() }` or check that `pageRepository.getAllPages().first { it.isRight() && (it.getOrNull()?.size ?: 0) > 0 }` returns before proceeding.

---

## Minors

- Action H undo samples will always be ~50ms (pure `delay` cost) since `undoManager` is null in the benchmark deps. This is expected baseline behavior but should be noted in a comment in the test code so future readers understand the samples are not measuring real undo.

- The plan uses `Random(seed = 42)` for both Action A (navigate 20 pages) and Action C (search queries), drawing from `allPages.shuffled(Random(42))`. If Action C reuses the same `allPages.shuffled(Random(42))` call, it will produce the same ordering as Action A, meaning the first 10 "search queries" are derived from the same 10 pages that were navigated. This is fine for correctness but worth a comment.

- `writeJson` helper from `GraphLoadTimingTest` only handles flat `Map<String, Any>` — it will not correctly serialize the `actions` array in `benchmark-session.json` if the map values are nested objects. The plan mentions using it for query stats but recommends `kotlinx.serialization` for the spans and session JSON. Confirm that `benchmark-session-query-stats.json` content is a flat map or use `kotlinx.serialization` consistently.

- `File.deleteOnExit()` in `copyGraphToTempDir` is a JVM shutdown hook. If the test JVM is killed (OOM, SIGKILL), the temp dir will not be cleaned up. The `finally { tempDir.deleteRecursively() }` in the test is the real cleanup path and is sufficient; `deleteOnExit()` is a belt-and-suspenders addition but provides no guarantee under abnormal termination.

- The plan does not document what happens when `allPages` has fewer than 20 entries (e.g., CI synthetic graph with 200 pages where Action D already renamed 5). The shuffle + take(20) is safe (take returns up to N), but the latency table will report `n < 20` for navigation. This is fine but should be documented.

- Task 2.2.5a says "pick page with most blocks from allPages if block count is tracked" — `Page` data class likely does not carry a block count. The implementation will need to either navigate to each of a few candidate pages and check `uiState`, or pick a journal page (which tends to have many blocks). This is an implementation detail but the plan is vague.

---

## Specific Questions from Review Prompt

### 1. Is the navigation timing approach correct (await uiState)?

**Yes, with a caveat.** Awaiting `viewModel.uiState.first { it.currentScreen is Screen.PageView && (it.currentScreen as Screen.PageView).page.uuid == uuid }` is the correct pattern per pitfalls.md (section 5). The caveat is the infinite-hang risk when the page UUID is not found (see CONCERN above). The `navigateTo(Screen.PageView(page))` is only called inside the launch when `page != null` — if the page was renamed or the UUID is missing, the state never transitions to that PageView and the `first { }` hangs. This is a CONCERN, not a BLOCKER, because in practice with a real graph the pages will be valid.

### 2. Will renamePage work in a headless test with the given setup?

**Mostly yes, but fragile.** The three preconditions are all satisfied by the plan: `writeActor = repoSet.writeActor` (non-null, verified in 2.1.1a), `graphWriter.graphPath = tempDir.absolutePath` (explicit in 2.1.1a), and `loadGraph(tempDir.absolutePath)` sets `currentGraphPath`. The main fragility is the await condition — the plan's `state.pages.any { ... }` check requires the `getAllPages()` Flow to re-emit after the write, which depends on SQLDelight's reactive Flow emitting on the DB dispatcher. In practice this works, but the 2s timeout fallback `delay(500)` is too short for a graph with thousands of pages and many backlinks. Rename with BacklinkRenamer rewrites disk files for every page that references the renamed page, which can take several seconds. Recommendation: increase fallback to `delay(3000)`.

### 3. Are there any missing teardown steps that could cause test interference?

**One missing step**: the `GraphLoader` starts file-watching coroutines (per the architecture). The plan calls `viewModel.close()` and `scope.cancel()` but does not explicitly call `graphLoader.close()` or stop the file watcher. If `GraphLoader` has a `close()` method that stops internal watchers, it should be called in `finally` before the temp dir is deleted (deleting the watched directory while watchers are running can produce `WatchService` errors on some JVMs). Check whether `GraphLoader` implements `Closeable` and add `graphLoader.close()` to the `finally` block if so.

### 4. Is the span capture approach correct (enabled flag, drain timing)?

**Yes.** The architecture.md (section 4) confirms: (1) create `RingBufferSpanExporter` with `capacity = 10_000`, (2) set `.enabled = true` before construction, (3) pass to `StelekitViewModelDependencies.ringBuffer`, (4) construct `StelekitViewModel(deps)` — the ViewModel creates `SpanEmitter(deps.ringBuffer)` in its constructor. The drain timing is correct: call `ringBuffer.drain()` after all actions complete and before `viewModel.close()`. The thread-safety concern (see CONCERN above) is the only risk with this approach.
