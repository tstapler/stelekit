# Adversarial Review: SAF Cache Redesign Plan

**Reviewer role**: Find every way this plan can fail.
**Source**: `plan.md` + `requirements.md` + source code inspection.

---

## Verdict: CONCERNS

The plan is implementable. No single flaw prevents the feature from shipping. However, there are
several risks that must be addressed before or during implementation to avoid regressions.

---

## Finding 1 — CRITICAL: Second mtime guard in `lookupExistingPageAndCheckFreshness` is not addressed

**Severity**: HIGH (regression risk, plan is silent on this)

The plan correctly targets the mtime guard in `loadFullPage` (lines 699–709). But there is a
**second mtime guard** in `lookupExistingPageAndCheckFreshness` (line 1159–1162 in GraphLoader.kt):

```kotlin
if (fileModTime != 0L &&
    existingPage.updatedAt.toEpochMilliseconds() >= fileModTime &&
    allBlocksLoaded) {
    return PageLookupResult(skip = true)
}
```

This guard runs inside `parseAndSavePage`, which is called:
1. By `onReloadFile` (from the watcher, after a dirty-set hit causes `loadFullPage` to proceed)
2. By `loadJournalsImmediate`, `loadDirectory`, `indexRemainingPages`

**Failure scenario**: External edit on SAF/FAT. `loadFullPage` correctly proceeds past the new
dirty-set guard. It calls `parseAndSavePage`. Inside `parseAndSavePage`, the second mtime guard
in `lookupExistingPageAndCheckFreshness` fires — mtime is stale/0 from SAF — and skips the
re-parse. The user still sees stale content.

**Required fix**: `lookupExistingPageAndCheckFreshness` must also receive a `force: Boolean`
parameter (or a `filePath in dirtyPaths` hint) to bypass this second guard, OR the guard logic
must be replaced with the same dirty-set + content-hash check.

The simplest approach: add a `forceReload: Boolean = false` parameter to
`lookupExistingPageAndCheckFreshness`. When `forceReload = true`, skip the mtime block entirely.
Pass `forceReload = true` from the `loadFullPage` code path when the dirty-set triggered the
reload.

**Risk if unaddressed**: The entire fix is bypassed by the inner guard. External edits still
produce stale reads. This is a complete functional regression — the feature does not work.

---

## Finding 2 — MEDIUM: `onDirtyFile` is a non-`suspend` lambda but `addDirty` is `suspend`

**Severity**: MEDIUM (compile error or silent thread unsafety)

The plan specifies `onDirtyFile: (filePath: String) -> Unit` as a non-suspend lambda. But in
section 2a, `addDirty` is defined as a `suspend` function (requires mutex). The wiring in 2c uses:

```kotlin
onDirtyFile = { filePath -> parallelScope.launch { addDirty(filePath) } },
```

This is valid Kotlin (launch bridges non-suspend to suspend), but it introduces a fire-and-forget
`launch` that:
- Is not cancellable from the watcher's perspective (the launched coroutine is in `parallelScope`,
  not in `watcherJob`)
- Adds potential ordering issues: if the watcher calls `onDirtyFile` then immediately calls
  `onReloadFile`, the `launch { addDirty(...) }` may not complete before `loadFullPage` checks
  `checkAndClearDirty`. The dirty entry may not be present in time.

**Failure scenario**: Watcher calls `onDirtyFile("/page.md")` → `parallelScope.launch { addDirty }`.
Before the launched coroutine runs, the ViewModel calls `loadFullPage("/page.md")`. `checkAndClearDirty`
returns false (entry not yet added). `fileWatcher.isRunning == true` → guard skips reload. Stale
read.

**Recommended fix**: Make `onDirtyFile: suspend (filePath: String) -> Unit` and call it directly
from within `checkDirectoryForChanges` (which is already a `suspend` function). Remove the
`parallelScope.launch` wrapper. This eliminates the ordering race entirely.

---

## Finding 3 — MEDIUM: `activePageFilePaths` collector leaks on `setActivePageUuids(null)`

**Severity**: MEDIUM (coroutine leak on graph close / vault lock)

The plan in section 2b launches a `parallelScope.launch { uuids.collect { ... } }` coroutine to
maintain `activePageFilePaths`. When `setActivePageUuids(null)` is called (e.g. on vault lock or
graph close), the collector from the previous call is **not cancelled** — it keeps running, holds
a reference to the old `uuids` StateFlow, and continues calling
`pageRepository.getPageByUuid(...)` on every update to the (now-gone) set.

There is no `cancel()` call on the previously-launched collector job.

**Required fix**: Store the collector `Job` and cancel it before starting a new one:
```kotlin
private var activePageFilePathsJob: Job? = null

override fun setActivePageUuids(uuids: StateFlow<Set<String>>?) {
    activePageUuids = uuids
    activePageFilePathsJob?.cancel()
    activePageFilePathsJob = null
    if (uuids != null) {
        activePageFilePathsJob = parallelScope.launch {
            uuids.collect { ... }
        }
    } else {
        activePageFilePaths = emptySet()
    }
}
```

---

## Finding 4 — MEDIUM: `preMarkPendingWrite` with `Long.MAX_VALUE` sentinel breaks `markWrittenByUs`

**Severity**: MEDIUM (own-write suppression may fail for encrypted files)

`markWrittenByUs` calls `fileSystem.getLastModifiedTime(filePath)`. For encrypted files
(`.md.stek`), `detectChanges` uses the mtime comparison exclusively (content-hash guard is
disabled). If `getLastModifiedTime` returns a value less than `Long.MAX_VALUE` (which it always
will), the subsequent `detectChanges` call will see `modTime > Long.MAX_VALUE` → false, so the
file is correctly suppressed. This part is fine.

However: if `preMarkPendingWrite` is called for a **new file** (no existing entry in `modTimes`),
the sentinel sets `modTimes[filePath] = Long.MAX_VALUE`. When the write completes and
`markWrittenByUs` runs, it queries `getLastModifiedTime` and sets the real mtime — correct.

But consider: if the saga **compensation** runs (write fails, file is restored), `markWrittenByUs`
is never called. The sentinel `Long.MAX_VALUE` stays in `modTimes` permanently. On the next
polling tick, `detectChanges` checks `modTime > Long.MAX_VALUE` — always false — so the file is
**permanently suppressed** for any real mtime value. External changes to that file will never be
detected again for the lifetime of the `FileRegistry` instance.

**Required fix**: Add a `clearPendingWrite(filePath: String)` method to `FileRegistry` and call
it in the saga compensation for the write step:
```kotlin
compensation = { _ ->
    fileRegistry.clearPendingWrite(filePath)
    // ... existing restore logic ...
}
```
`clearPendingWrite` removes the sentinel entry from `modTimes` (reverting to `null`/unknown state),
which causes the next `detectChanges` to treat the file as new/unknown and re-scan it.

---

## Finding 5 — LOW: `isRunning` flag has a race on startup

**Severity**: LOW (narrow timing window; acceptable)

`fileWatcher.isRunning` returns `watcherJob?.isActive == true`. In `loadFullPage`, this is used
to decide whether to take the dirty-set path or the content-hash path. On iOS/WASM, `isRunning`
is always `false` (no watcher job is ever started). On JVM/Android, there is a window between
`loadGraphProgressive` returning from Phase 1 (calling `onPhase1Complete`) and `startWatching`
being called (which happens inside `loadGraphProgressive` after background reconcile completes).

During this window, `isRunning == false` on JVM/Android. If a user navigates to a page during
this window, `loadFullPage` takes the content-hash path instead of the dirty-set path.
The content-hash path is correct: it reads the file and compares hashes. This may add ~10–30ms
of latency compared to the fast-path, but it is not incorrect.

**Assessment**: Benign. The window is short (~seconds during startup). No action required, but
add a comment in the code noting the startup window.

---

## Finding 6 — LOW: Content-hash path double-reads on iOS/WASM

**Severity**: LOW (latency, within spec)

When `isRunning == false` (iOS/WASM) and a reload is triggered by hash mismatch, `loadFullPage`
reads the file **twice**: once for the hash comparison (`fileSystem.readFile(filePath)`), then
again inside `parseAndSavePage` via `readFileDecrypted`. On iOS with iCloud, this is two SAF-
equivalent reads of the same file.

The plan does not address this. The `content` from the first read could be reused in
`parseAndSavePage` directly, avoiding the second read. However, `parseAndSavePage`'s signature
takes `content: String`, so the caller already has it. The plan's code for `loadFullPage` on the
no-watcher path does the hash check read, but then falls through to the existing `readFileDecrypted`
call at line 720.

**Fix**: In the content-hash mismatch branch, pass the already-read `diskContent` directly to
`parseAndSavePage` instead of falling through to `readFileDecrypted`. This halves disk I/O on the
reload path for iOS/WASM.

This is an optimization, not a correctness issue. Within the <100ms latency budget regardless.

---

## Finding 7 — LOW: Test T11b makes an incorrect claim about `externalFileChanges`

**Severity**: LOW (test specification error)

Test T11b step 5 states: "Verify `externalFileChanges` IS emitted (conflict dialog gets a chance
to fire)."

Looking at `GraphFileWatcher.checkDirectoryForChanges`: `_externalFileChanges.tryEmit(...)` is
called for `changedFiles` but **not** for `newFiles`. The plan's pseudocode in section 1 shows:

```kotlin
if (pageUuidForPath(changed.entry.filePath) in activeUuids) {
    onDirtyFile(changed.entry.filePath)
    // still emit externalFileChanges so the conflict dialog can fire
    continue   // skip onReloadFile
}
```

This is intended to emit `externalFileChanges` before the `continue`. However, in the plan's
pseudocode the `continue` appears **after** `onDirtyFile` but the plan claims `externalFileChanges`
IS emitted. The current code emits `externalFileChanges` before the `withTimeoutOrNull` suppress
window. If the plan inserts the active-page guard BEFORE the existing emit block, then
`externalFileChanges` would NOT be emitted for active pages.

The plan needs to clarify the exact insertion point. The active-page guard must be placed AFTER
the `_externalFileChanges.tryEmit(...)` line, not before it, to preserve the conflict-dialog
notification for actively-edited pages.

**Required fix in plan**: Clarify that the `activePageFilePaths` check and `continue` (skip
`onReloadFile`) are inserted AFTER the `withTimeoutOrNull` suppress window block — specifically,
in the code path after `suppressed == false` check. The emit of `externalFileChanges` remains
unconditional for all changed files, including active-page ones.

---

## Finding 8 — LOW: `isSafPath` check in the suppress window is redundant after dirty-set adoption

**Severity**: LOW (not harmful, slight over-engineering)

Task T10 extends the suppress window for SAF paths to 2 seconds. This was motivated by the
mtime-based own-write race. With the dirty-set + `preMarkPendingWrite` approach:
- `preMarkPendingWrite` prevents `detectChanges` from emitting any change event during the write.
- `markWrittenByUs` updates the mtime after the write.

If `preMarkPendingWrite` works correctly, the 200ms suppress window is never needed for our own
writes. The 2-second extension adds latency to the conflict-dialog path (the `withTimeoutOrNull`
wait blocks the watcher loop for 2s on SAF paths even for genuine external changes).

**Assessment**: Keep the 200ms default and remove the SAF-specific extension. If `preMarkPendingWrite`
is correctly implemented, it is sufficient. If there is a known case where `preMarkPendingWrite`
doesn't fire in time (e.g., the saga is bypassed somehow), that should be fixed at the source,
not papered over with a 2-second window. Document the decision to not extend the window.

---

## Summary of Findings

| # | Severity | Blocking? | Summary |
|---|----------|-----------|---------|
| 1 | HIGH | Yes (functional) | Second mtime guard in `lookupExistingPageAndCheckFreshness` bypasses the fix |
| 2 | MEDIUM | No (but race) | `onDirtyFile` non-suspend lambda causes ordering race |
| 3 | MEDIUM | No (leak) | `activePageFilePaths` collector not cancelled on `setActivePageUuids(null)` |
| 4 | MEDIUM | No (edge case) | `preMarkPendingWrite` sentinel survives failed saga; permanent suppression |
| 5 | LOW | No | `isRunning` startup window on JVM/Android |
| 6 | LOW | No | Double file read on iOS/WASM content-hash path |
| 7 | LOW | No | Test T11b insertion-point ambiguity for `externalFileChanges` emit |
| 8 | LOW | No | 2-second SAF suppress window is redundant with `preMarkPendingWrite` |

---

## Required Actions Before Claiming Plan is Ready

1. **Finding 1**: Add `forceReload: Boolean` parameter to `lookupExistingPageAndCheckFreshness`
   and thread it through from `loadFullPage`. Update the plan's section 2d accordingly.

2. **Finding 2**: Change `onDirtyFile` to `suspend (filePath: String) -> Unit` and remove the
   `parallelScope.launch` wrapper in the wiring.

3. **Finding 3**: Add `activePageFilePathsJob?.cancel()` at the start of `setActivePageUuids`.

4. **Finding 4**: Add `clearPendingWrite(filePath)` to `FileRegistry` and call it in the saga
   compensation for the file-write step.

5. **Finding 7**: Clarify insertion point for active-page guard in `checkDirectoryForChanges`
   pseudocode — guard goes AFTER the `externalFileChanges` emit.

Findings 5, 6, 8 are noted and do not require plan changes.
