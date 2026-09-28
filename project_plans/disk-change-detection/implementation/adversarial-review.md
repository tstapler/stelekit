# Adversarial Review: Disk Change Detection Implementation

**Verdict: CONCERNS**

The implemented epics are directionally correct and the major correctness gaps (subdirectory watcher, scope leak, per-graph freshness) are real fixes. However, three issues warrant explicit follow-up before the next milestone, ordered by severity.

---

## Concern 1 (HIGH): UUID→Path Resolution Silent Drop — Story 7.4 Is Not Low Priority

**Claim in the plan**: Story 7.4 (UUID→path null for in-flight DB writes) is labeled "low priority."

**Challenge**: The plan undersells this. The scenario is not exotic — it is the default new-page creation flow. Every time a user creates a page and immediately starts typing, the DB write is queued behind `DatabaseWriteActor`. The `setUnsavedPageUuids` collector runs immediately on the `parallelScope` when `dirtyPageUuids` emits, but `getPageByUuid(...).first()` will return `null` until the actor drains. Since `mapNotNull` silently drops the UUID, `unsavedPageFilePaths` is empty for that page.

If the user has a sync client running (the primary target user of this feature), the sync client may write the same file path within the actor-drain window. `GraphFileWatcher.checkDirectoryForChanges` sees the path is not in `unsavedPageFilePaths`, emits `onReloadFile`, and overwrites the just-typed content.

The plan proposes the fix (retry with `delay(50)` up to 5 attempts, or a raw-UUID fallback guard in `GraphFileWatcher`). The raw-UUID fallback is the cleaner approach because it doesn't add polling latency to the hot path and requires no retry: `GraphFileWatcher.checkDirectoryForChanges` already has access to `unsavedPageFilePaths`; a second set of raw dirty UUIDs (from `dirtyPageUuids` directly, passed separately from `setUnsavedPageUuids`) would catch the case where the UUID is dirty but the path hasn't resolved yet.

**What the test suite does NOT cover**: There is no test that verifies `onReloadFile` is suppressed when a block is dirty but the page's `getPageByUuid` returns null. TR-2 covers the happy-path dirty-page guard but assumes path resolution succeeds. The null-UUID gap is untested.

**Recommendation**: Promote Story 7.4 to a follow-up blocker milestone item (not a low-priority TODO). Add a test that injects a `PageRepository` stub returning `null` for `getPageByUuid` and asserts `onReloadFile` is never called when a block is dirty for that page. Ship the raw-UUID fallback guard alongside the promotion — it is a one-liner in `GraphFileWatcher`.

---

## Concern 2 (HIGH): `combine(_dirtyBlocks, _blocks)` Correctness Under Optimistic Insert

**Claim in the plan**: "combine cross-references block UUIDs from `_dirtyBlocks` with page→block lists from `_blocks`." The plan and pitfalls document assert this is correct.

**Challenge**: The combine is only correct when the dirty block is already present in `_blocks`. If the user starts editing a block that was just inserted optimistically — meaning `_blocks[pageUuid]` already contains the block (optimistic insert) — then the combine works correctly. But if `_dirtyBlocks` is updated before `_blocks` receives the optimistic insert (a reactive ordering race), the combine sees `_dirtyBlocks` has a block UUID that no page's block list contains, and the combine emits `emptySet()` incorrectly.

The plan asserts the ordering is guaranteed by `_dirtyBlocks` being declared before `dirtyPageUuids`. This only guarantees Kotlin initializer ordering within the class, not the order of emissions during the reactive chain. The `combine` operator emits on every update to either input; if `_dirtyBlocks.update { ... }` fires before `_blocks.update { ... }` in the optimistic-insert path, the combine sees the dirty block UUID but cannot find it in any page's block list, emits `emptySet()`, and the guard is incorrectly empty.

**Concrete scenario**:
1. User creates a new block. Code calls `_dirtyBlocks.update { it + (blockUuid to version) }`.
2. `combine` fires immediately (ReactiveCoroutines: combine fires on the latest value of both flows). At this instant, `_blocks` has not yet been updated with the new block.
3. combine iterates `_blocks.entries`, finds no page whose block list contains `blockUuid`, emits `emptySet()`.
4. Concurrently, `_blocks.update { ... }` fires — now the block appears in the page's list.
5. `combine` fires again, this time correctly emitting the page UUID.

The window between steps 3 and 5 is typically one coroutine dispatch (nanoseconds), but it is non-zero and observable if the watcher poll fires at exactly that moment. In practice this is extremely rare; however, the claim that the combine is unambiguously correct is false.

**The `distinctUntilChanged` fix (Story 7.3)** partially mitigates this by preventing the empty-set emission from propagating downstream *if* it equals the previous state — but if the previous state was also `emptySet()` (first edit), `distinctUntilChanged` does not help; the empty set propagates and clears `unsavedPageFilePaths` until the second combine fires.

**What the test suite does NOT cover**: No test exercises the ordering where `_dirtyBlocks` receives an entry for a block UUID that is not yet present in `_blocks`. TR-1 and TR-2 test steady-state.

**Recommendation**: The fix is to also include the dirty block UUIDs that have no page match as an explicit fallback: if `blockUuid in dirtyBlocks.keys` but not found in any `_blocks` page list, treat the *currently open page* (passed in or held separately) as dirty. Alternatively, ensure `_blocks` is always updated before `_dirtyBlocks` by swapping the update order in the block-creation path. This ordering fix is simpler and testable.

---

## Concern 3 (MEDIUM): `SafChangeDetector.stop()` / `start()` Concurrency — No List Guard

**Claim in the plan**: `startFileObservers` creates two subdirectory `FileObserver` instances. `stop()` clears `contentObservers` and `fileObservers` lists.

**Challenge**: The plan does not specify synchronization for `contentObservers.clear()` / `fileObservers.clear()` in `stop()` relative to concurrent `start()`. The inotify callback lambda holds a reference to the `SafChangeDetector` instance and calls `onExternalChange()`. If `stop()` is called while `start()` is re-registering (e.g., graph switch during foreground):

1. `stop()` calls `fileObservers.clear()` — drops all references.
2. A concurrent `start()` call has already constructed a new `FileObserver` and is about to call `fileObservers.add(observer)`.
3. If both run on different threads (stop on `Main`, start on `IO`), the `CopyOnWriteArrayList` (or `mutableListOf`) is mutated from two threads simultaneously.

If `fileObservers` is a plain `mutableListOf()` (not `CopyOnWriteArrayList` or a mutex-guarded list), this is a data race. The plan does not specify the list type.

**Separate concern**: `fileObservers.forEach { it.stopWatching() }` followed by `fileObservers.clear()` is not atomic. If a `FileObserver.onEvent` callback fires between `stopWatching()` and `clear()`, the callback may still call `onExternalChange()` on a partially-stopped detector.

The plan's `stop()` implementation also calls `contentObservers.clear()` without first calling `contentResolver.unregisterContentObserver(observer)` — or at least the plan text does not mention it. If the `ContentObserver` callback fires after clear but before unregistration, `onExternalChange()` is called on a stopped detector. Depending on whether `GraphLoader` holds a reference to a cancelled scope at that point, this could cause a `CancellationException` to propagate into the `ContentResolver` callback thread.

**Recommendation**: Explicitly use a `Mutex` or `synchronized(this)` block around `start()` / `stop()` list mutations, or restructure so that `SafChangeDetector` is always created fresh (not restarted) on graph switch, eliminating the concurrent start/stop scenario entirely.

---

## Concern 4 (LOW, informational): TC-04 Regression Risk — Updated Test May Have Weakened the Original Guarantee

**Claim in the plan**: "The existing `TC-04` test was updated to reflect new semantics."

**Challenge**: The original TC-04 (per requirements context) tested that pages with dirty blocks are NOT auto-reloaded — the "no silent clobber" invariant. The updated TC-04 maps to TR-2 in the test plan. TR-2 asserts: page with unsaved edits does NOT trigger `onReloadFile`; `onDirtyFile` fires; `externalFileChanges` emits.

However, TR-2 as described tests the updated path where `unsavedPageFilePaths` is populated by UUID→path resolution. If the test passes the file path directly into `unsavedPageFilePaths` (bypassing the resolution step), it correctly tests the guard but does NOT test the full data flow from `blockStateManager.dirtyPageUuids` → `setUnsavedPageUuids` → `unsavedPageFilePaths`. The resolution step (UUID→path) is exactly where Concern 1 above fails.

This means TR-2 may pass even if the UUID→path resolution never populates `unsavedPageFilePaths` — because the test sets the guard directly. The original guarantee ("a page with dirty blocks is never silently reloaded") is only preserved if the full integration path from `dirtyPageUuids` to `unsavedPageFilePaths` is tested end-to-end. The current test structure does not appear to do this.

**Recommendation**: Add an integration-level test that wires a real `BlockStateManager` to a real `GraphLoader` stub (or use the existing `GraphFileWatcherTest` setup) and verifies that marking a block dirty via `BlockStateManager` prevents `onReloadFile` from firing — without directly injecting `unsavedPageFilePaths`.

---

## Two TODO Items Are Correctly Deferred But Need Time-Boxing

**Story 2.3 (FileObserver dead-inode)**: Correctly identified as the highest-severity open gap. The 5-second JVM poll and `onStart` scan are partial mitigations on Android MANAGE_EXTERNAL_STORAGE — but "up to 30 seconds of missed events" is user-visible for the target user. Should have a concrete ship date, not remain open-ended.

**Story 3.3 (ShadowFlushActor flush-pending set)**: The race is narrow (~5–50 ms) but deterministic for encrypted graphs. Users of `.md.stek` graphs will see spurious conflict dialogs after any write-behind flush that races with an `onStart` scan — which is every foreground transition while a flush is in progress. This is higher frequency than the plan's framing implies.

---

## Items Reviewed and Found Correct

- `isFirstAccess()` via `AtomicBoolean.getAndSet(false)`: thread-safe, correct for the single-instance-per-graph lifecycle. The double-purge race in tests is harmless (second `deleteAll()` finds nothing).
- `ShadowFlushActor` made stateless: removing the owned scope is correct; `flush()` as a plain `suspend fun` is the right design.
- `FileSystem.setOnFlushComplete` default no-op: correct non-breaking API extension; iOS/WASM compile safety is preserved.
- `onFlushed` threading model: the callback runs on `Dispatchers.IO` and takes `detectMutex` — correct serialization analysis in the plan.
- Per-instance `freshInstance` flag: strictly more correct than the process-wide companion field. Two-instance concurrency in tests is harmless.

---

## Summary Verdict

**CONCERNS** — safe to ship as-is with three mandatory follow-ups tracked:

| Priority | Issue | Action |
|---|---|---|
| HIGH | UUID→path null drop (Story 7.4) — new-page dirty content can be clobbered | Promote to next-milestone blocker; add raw-UUID fallback guard; add covering test |
| HIGH | `combine(_dirtyBlocks, _blocks)` ordering race — can briefly emit `emptySet()` on first dirty-block update | Fix update order in block-creation path; add ordering test |
| MEDIUM | `SafChangeDetector.stop()`/`start()` list mutation — no synchronization specified | Specify list type (CopyOnWriteArrayList or mutex); add unregister-before-clear in stop() |
