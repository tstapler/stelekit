# Architecture Research: journal-watch-fix

## Bug 1 — Detailed Code Path Trace: New-Day Journal Not Created

### Current flow (broken)

1. User opens app → `StelekitViewModel.loadGraph()` is called
2. Inside `loadGraph`, `graphLoader.loadGraphProgressive(...)` is called with an `onPhase1Complete` lambda
3. `onPhase1Complete` fires (either warm start or after loading 10 journals):
   ```kotlin
   // StelekitViewModel.kt line 431
   onPhase1Complete = {
       _uiState.update { it.copy(isLoading = false, statusMessage = "Ready") }
       scope.launch { journalService.ensureTodayJournal() }
   }
   ```
4. `journalService.ensureTodayJournal()` calls `Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date` to get today's date
5. If today's page doesn't exist, it creates one with `writeActor.savePage(newPage)`

**Missing piece:** There is no subsequent call to `ensureTodayJournal()` after the app is running. No recurring check. At 23:59 the current date is correct; at 00:01 the date is stale in the app's conceptual model.

### Proposed fix location

The midnight-boundary coroutine should be launched in the same `onPhase1Complete` callback (or a method called from it) on `deps.scope` (the ViewModel's long-lived scope). This ensures:
- It starts only after the graph is loaded (not before `ensureTodayJournal()` succeeds)
- It is tied to the ViewModel scope, which outlives any composable
- It is cancelled when `scope.cancel()` is called in `StelekitViewModel.close()`

**Alternative location:** Inside `GraphLoader.startWatching()`. This would scope the coroutine to `GraphLoader.parallelScope`, making it independent of the ViewModel. However, `GraphLoader` does not hold a reference to `JournalService` — this would require injecting it, adding a cross-layer dependency. The ViewModel approach is cleaner.

**Proposed implementation (ViewModel approach):**

```kotlin
// In StelekitViewModel.kt, called after onPhase1Complete
private fun startMidnightBoundaryWatcher(clock: Clock = Clock.System) {
    scope.launch(CoroutineName("midnight-boundary-watcher")) {
        while (isActive) {
            val delayMs = millisUntilNextMidnight(clock)
            delay(delayMs)
            logger.info("Day boundary crossed — ensuring today's journal exists")
            try {
                journalService.ensureTodayJournal()
                loadMoreJournalPages(reset = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("ensureTodayJournal failed at midnight boundary", e)
            }
        }
    }
}

private fun millisUntilNextMidnight(clock: Clock = Clock.System): Long {
    val tz = TimeZone.currentSystemDefault()
    val now = clock.now()
    val todayDate = now.toLocalDateTime(tz).date
    val tomorrowMidnight = todayDate.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz)
    return (tomorrowMidnight - now).inWholeMilliseconds.coerceAtLeast(1_000L)
}
```

The `coerceAtLeast(1_000L)` guards against edge cases where `now()` returns an instant exactly at midnight, causing a 0ms or negative delay.

**Clock injection for tests:** `JournalService.ensureTodayJournal()` already calls `Clock.System.now()` directly — this needs to be changed to accept a `Clock` parameter or a lambda for testability. Alternatively, `startMidnightBoundaryWatcher` can accept a `Clock` and pass it to a helper that is testable independently from `JournalService`.

### Test path (REQ-03, GraphLoaderProgressiveTest)

To test the midnight-boundary behavior without waiting for real time:
1. Inject a `FakeClock` set to 23:59:59
2. Inject a `TestCoroutineScheduler` (via `StandardTestDispatcher`)
3. Assert `ensureTodayJournal` was called once (at startup / onPhase1Complete)
4. Advance the scheduler by 2 seconds (crosses midnight)
5. Assert `ensureTodayJournal` was called again
6. Advance by 24 hours
7. Assert third call

The challenge: `JournalService.ensureTodayJournal()` calls `Clock.System.now()` directly. If we want to verify it creates tomorrow's page (not today's), we need the FakeClock to propagate into `JournalService`. Two options:
- Add `clock: Clock = Clock.System` parameter to `JournalService`
- Add a `clock: Clock = Clock.System` parameter to `ensureTodayJournal()` directly (simpler, does not change the public API signature in a breaking way)

---

## Bug 2 — Detailed Code Path Trace: Shadow Stale-Read in detectChanges

### Current flow (broken)

**Setup:**
- Android SAF path (`saf://` URIs)
- External process (Logseq sync, iCloud, git pull) writes `journals/2026_05_29.md`
- SAF mtime for the file increases

**Step 1:** `GraphFileWatcher` polling loop fires every 5 seconds:
```kotlin
// GraphFileWatcher.kt line 95
checkDirectoryForChanges(journalsDir)
```

**Step 2:** `checkDirectoryForChanges` calls:
```kotlin
// GraphFileWatcher.kt line 164
val changeSet = fileRegistry.detectChanges(dirPath)
```

**Step 3:** Inside `FileRegistry.detectChanges` (line 116):
```kotlin
} else if (modTime > lastKnown) {
    // non-encrypted branch (line 123-134)
    val content = fileSystem.readFile(filePath) ?: continue   // ← BUG HERE
    val newHash = content.hashCode()
    if (contentHashes[filePath] == newHash) {
        modTimes[filePath] = modTime
        continue  // ← SILENT DROP
    }
    ...
}
```

**Step 4:** `fileSystem.readFile(filePath)` on Android SAF:
```kotlin
// PlatformFileSystem.kt line 269-286
actual override fun readFile(path: String): String? {
    if (!path.startsWith("saf://")) return legacyReadFile(path)
    // ...
    val shadow = shadowCache?.resolve(relativePath)
    if (shadow != null) {
        return shadow.readText()  // ← RETURNS STALE CONTENT
    }
    return safReadContent(path)
}
```

The shadow file for `journals/2026_05_29.md` exists because it was populated during startup (either from `invalidateStaleShadow` or from being read during `loadJournalsImmediate`). It was NOT invalidated because the external write happened AFTER startup.

**Step 5:** The stale content's `hashCode()` matches `contentHashes[filePath]` (which was set to the same stale content on last read). The guard fires: `continue`. The external change is silently dropped.

**Step 6:** `checkDirectoryForChanges` eventually processes `changeSet.changedFiles` (which is empty) and then calls:
```kotlin
// GraphFileWatcher.kt line 168 — for new files only
fileSystem.invalidateShadow(changed.entry.filePath)
```
And line 178:
```kotlin
// for changed files — but this is NEVER reached because changeSet.changedFiles is empty
fileSystem.invalidateShadow(changed.entry.filePath)
```

The `invalidateShadow` call in `checkDirectoryForChanges` is too late — it is only called AFTER reading the already-stale content inside `detectChanges`.

### Proposed fix — REQ-02

Inside `FileRegistry.detectChanges`, in the `modTime > lastKnown` branch for non-encrypted files, add `fileSystem.invalidateShadow(filePath)` BEFORE `fileSystem.readFile(filePath)`:

```kotlin
// FileRegistry.kt line 123-134 — CURRENT
} else {
    val content = fileSystem.readFile(filePath) ?: continue
    val newHash = content.hashCode()
    ...
}

// PROPOSED
} else {
    fileSystem.invalidateShadow(filePath)                  // NEW: clear stale shadow first
    val content = fileSystem.readFile(filePath) ?: continue
    val newHash = content.hashCode()
    ...
}
```

This is a two-line change. On JVM, `invalidateShadow` is a no-op in the default `FileSystem` implementation. On Android SAF, it calls `shadowCache?.invalidate(relativePath)` which deletes the shadow file. The subsequent `readFile` then falls through to `safReadContent` (real SAF Binder IPC) and returns the current content.

### Why this doesn't break own-write suppression

`markWrittenByUs` is called by `GraphWriter` immediately AFTER writing the file. At that point, it:
1. Reads the file content (via `fileSystem.readFile` — which after our own write would still hit the shadow since `markWrittenByUs` also calls `readFile`, but `updateShadow` was already called by `writeFile` so the shadow IS fresh)
2. Stores the content hash in `contentHashes[filePath]`
3. Stores the current mtime in `modTimes[filePath]`

The next `detectChanges` poll sees `modTime == lastKnown` (not `>`) for our own write, so the `modTime > lastKnown` branch is never entered, and the `invalidateShadow` addition is not triggered.

**Edge case: shadow is updated by writeFile, then invalidated by detectChanges before markWrittenByUs is called?**
This cannot happen: `detectChanges` is gated by `detectMutex`, and `markWrittenByUs` also acquires `detectMutex`. After `writeFile` returns and before `markWrittenByUs` sets the modTime, `detectChanges` could theoretically run and call `invalidateShadow`. But in that window, the shadow was just written by `updateShadow` in `writeFile`, so it IS fresh — invalidating it would force a SAF round-trip, but the returned content would be identical, the hash would match the cached one (from the prior `markWrittenByUs`), and the change would be correctly suppressed. So invalidating the shadow in the own-write detection window is harmless.

### Test path (REQ-03, FileRegistryTest)

The existing `FakeFs` in `FileRegistryTest` does NOT implement `invalidateShadow` — it inherits the no-op default. To test the new behavior, we need a `FakeFsWithShadow` that:
1. Maintains a separate `shadowMap: MutableMap<String, String>`
2. `readFile(path)` returns `shadowMap[path]` if present, else `files[path]`
3. `invalidateShadow(path)` removes `path` from `shadowMap`
4. `externalWrite(path, content)` updates `files[path]` but NOT `shadowMap` (simulating an external write that didn't go through our `updateShadow`)

Test scenario:
```
1. Write "old content" to shadow and files (simulates startup read)
2. scanDirectory / detectChanges to register baseline
3. External process writes "new content" to files only (NOT shadow)
   — mtime bumps, but shadowMap still has "old content"
4. WITHOUT the fix: detectChanges reads from shadow, gets "old content",
   hash matches, change is dropped
5. WITH the fix: detectChanges calls invalidateShadow first, then readFile
   returns "new content" from files, hash doesn't match, change is reported
```

### Integration: where invalidateShadow is currently called correctly

`GraphLoader.loadFullPage()` (line 699) already calls `fileSystem.invalidateShadow(filePath)` before reading a page that needs (re-)loading. This is the same pattern we're adding to `FileRegistry.detectChanges`. The precedent is established.

`GraphFileWatcher.checkDirectoryForChanges()` calls `invalidateShadow` for files in `changeSet.changedFiles` (line 178) and `changeSet.newFiles` (line 168). This handles the shadow cleanup for subsequent reads after detection — but is too late for the detection itself.

### Fake Clock injection for JournalService tests

Since `JournalService.ensureTodayJournal()` directly calls `Clock.System.now()` without injection, tests that need to control "today's date" must either:

**Option A:** Add `clock: Clock = Clock.System` parameter to `ensureTodayJournal()`:
```kotlin
suspend fun ensureTodayJournal(clock: Clock = Clock.System): Page = mutex.withLock {
    val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    ...
}
```

**Option B:** Add `clock: Clock = Clock.System` to `JournalService` constructor.

Option B is preferred because the midnight-boundary watcher also needs to call `clock.now()` (for computing `millisUntilNextMidnight`) and sharing the same `Clock` instance simplifies test setup.

The `GraphLoaderProgressiveTest` test structure (injecting a custom `FileSystem` via constructor) sets the pattern: tests construct `JournalService(pageRepo, blockRepo, clock = fakeClock)` and the same `fakeClock` is passed to `startMidnightBoundaryWatcher`.
