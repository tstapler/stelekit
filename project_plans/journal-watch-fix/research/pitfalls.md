# Pitfalls Research: journal-watch-fix

## Timezone Edge Cases

### DST spring-forward (clocks skip an hour)

**Scenario:** Local timezone springs forward from 01:59 → 03:00 at midnight.

`kotlinx.datetime.LocalDate.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz)` handles this correctly: `atStartOfDayIn` computes the first valid instant in the given timezone on the next date, which may be 01:00 AM UTC (or some other adjusted time). The `delay(millisUntilNextMidnight)` will be slightly longer on the spring-forward night (by ~1 hour), but will fire at wall-clock midnight correctly. No risk of skipping a midnight.

**Pitfall to avoid:** Never add exactly `24 * 60 * 60 * 1000` milliseconds to the current time as a "wait until tomorrow" shortcut. DST transitions make this wrong by ±1 hour. Always use `atStartOfDayIn` to get the actual next midnight instant.

### DST fall-back (clocks repeat an hour)

The fall-back transition makes `00:00` occur twice in the same night. `atStartOfDayIn` returns the FIRST occurrence (before the clock change). The coroutine will fire at that first `00:00`, call `ensureTodayJournal()` for the new date, then loop and compute the NEXT midnight. The second `00:00` period is not a "new day" in the calendar sense and will not trigger a second wakeup. This is correct.

**Risk:** If the system clock is adjusted backward during a fall-back transition and the coroutine's `delay()` is in-flight, the delay may complete earlier than expected (because `delay()` uses wall-clock time, not monotonic time in some implementations). `delay()` in Kotlin coroutines on JVM is backed by `DefaultExecutor` which uses `ScheduledThreadPoolExecutor` — this uses the system clock and CAN be affected by clock adjustments. However, since `ensureTodayJournal()` is idempotent, calling it spuriously early (even twice on the same date) is safe: the second call returns the existing page.

### High-resolution vs. low-resolution mtime

Android SAF `COLUMN_LAST_MODIFIED` has 1-second granularity on most providers (Google Drive, local files). If an external sync tool writes a file and the mtime doesn't change (e.g. the write completed within the same second as the previous write), `detectChanges` will miss it. This is a pre-existing limitation of the mtime-based approach and is NOT made worse by the REQ-02 fix. Out of scope for this fix.

---

## JVM vs Android Clock Injection Differences

### `Clock.System` on JVM

On JVM, `kotlin.time.Clock.System.now()` delegates to `java.time.Instant.now()` which uses `System.currentTimeMillis()`. This is affected by `System.setCurrentTimeMillis()` in tests but that API is not easily mockable in JVM 11+. The correct approach is injecting a `Clock` interface.

### `Clock.System` on Android

Same JVM implementation. However, `TimeZone.currentSystemDefault()` on Android reads from the Android timezone registry, which can differ from the JVM default timezone. In tests running on a JVM (jvmTest), `TimeZone.currentSystemDefault()` is the JVM default timezone, not Android's. For unit tests that need to test specific timezone behavior (e.g. DST edge cases), explicitly pass a fixed `TimeZone` parameter.

### `TestCoroutineScheduler` + `FakeClock` interaction

When using `runTest { }` with `advanceTimeBy()`, the scheduler controls `delay()` calls within the test coroutine. However, `Clock.System.now()` calls inside coroutines running under the test scheduler are NOT automatically advanced — they still return real wall-clock time unless you inject a `FakeClock`.

**Critical pitfall:** If the midnight-boundary watcher computes `millisUntilNextMidnight(Clock.System)` using real `Clock.System`, then calls `delay(ms)` and the test advances the scheduler, the `delay()` will complete but the next `ensureTodayJournal()` call will compute tomorrow's date using real `Clock.System.now()` — which hasn't changed. The test will see the journal created for today, not tomorrow.

**Solution:** The `FakeClock` must be passed both to `millisUntilNextMidnight()` AND to `ensureTodayJournal()`. If `JournalService` takes `clock: Clock` in its constructor, this is naturally satisfied.

---

## Coroutine Cancellation

### Midnight watcher coroutine not properly cancelling

The midnight watcher must `throw CancellationException` on cancellation, not swallow it. The pattern:
```kotlin
while (isActive) {
    delay(millisUntilNextMidnight())
    try {
        journalService.ensureTodayJournal()
    } catch (e: CancellationException) {
        throw e   // ← REQUIRED
    } catch (e: Exception) {
        logger.error(...)
    }
}
```

Using `while (isActive)` means the loop exits cleanly when the scope is cancelled. The `delay()` inside also responds to cancellation — it throws `CancellationException` immediately, which propagates up through the `while` loop. The `try/catch` around `ensureTodayJournal()` must re-throw `CancellationException` to avoid turning a structured cancellation into a swallowed exception.

### `CoroutineScope` tied to wrong lifecycle

If the midnight watcher is accidentally launched inside `onPhase1Complete` via a lambda that captures a composable's scope (e.g. if the ViewModel's `scope` is incorrectly set to a `rememberCoroutineScope()` result), the watcher will be cancelled when the composable leaves composition. The CLAUDE.md explicitly forbids this pattern. The fix: always launch on `deps.scope` (ViewModel's own `CoroutineScope`).

### Graph scope cancellation on graph close

`StelekitViewModel.close()` calls `scope.cancel()`, which cancels all coroutines including the midnight watcher. This is correct behavior — when the graph is closed, the watcher should stop. If the user reopens the same graph, `loadGraph()` is called again, `onPhase1Complete` fires again, and `startMidnightBoundaryWatcher` relaunches. There is no risk of duplicate watchers because the scope was cancelled and `scope.launch` on a cancelled scope does nothing (the new coroutine immediately completes with `CancellationException`).

**Actual risk:** If `close()` is called but the scope is reused (not typical in this codebase — the ViewModel creates a new scope per instance), a second `startMidnightBoundaryWatcher` call could launch a duplicate watcher. Guarding with `val midnightWatcherJob: Job?` and cancelling the previous job before launching a new one is defensive but probably unnecessary given the ViewModel architecture.

---

## Shadow Invalidation Side Effects on Write-Behind Queue

### Write-behind queue + invalidateShadow race

The write-behind queue (`WriteBehindQueue` on Android) enqueues SAF write paths. The `ShadowFlushActor` reads the shadow file content and writes it to SAF. If `invalidateShadow` deletes the shadow file for a path that is currently in the write-behind queue, `ShadowFlushActor.flush()` will try to read that shadow file and find it deleted — it will skip that file's SAF write.

**When does this occur with the REQ-02 fix?**
`FileRegistry.detectChanges` calls `invalidateShadow(filePath)` when `modTime > lastKnown` for a non-encrypted file. An externally-changed file is never in our write-behind queue (the queue only contains files that WE wrote). Own-written files are guarded by `markWrittenByUs` which sets `modTimes[filePath]` to the current mtime — so the next `detectChanges` sees `modTime == lastKnown` and does NOT enter the `modTime > lastKnown` branch. The new `invalidateShadow` call is therefore never triggered for own-written files that are in the write-behind queue.

**Edge case: mtime advances between markWrittenByUs and the next detectChanges poll**

If the write-behind queue has not yet flushed the file to SAF, but `listFilesWithModTimes` returns an mtime that is HIGHER than what `markWrittenByUs` stored (e.g. because the SAF provider reports a different mtime for the in-flight document), `detectChanges` could enter the `modTime > lastKnown` branch and call `invalidateShadow`. This would delete the shadow that the write-behind queue needs to flush.

**Mitigation:** `markWrittenByUs` calls `fileSystem.getLastModifiedTime(filePath)` (which on Android SAF queries the actual SAF mtime) and stores that value. If this value is lower than what `listFilesWithModTimes` will return on the next poll, the mtime comparison will still trigger. However, this edge case is pre-existing (the content-hash guard handles it: the content matches, so the change is suppressed). After the REQ-02 fix, the content-hash check still runs AFTER `invalidateShadow`, so the sequence is:
1. `invalidateShadow` — deletes shadow
2. `readFile` — falls through to SAF, reads content
3. Hash check — matches, change suppressed, `modTimes` updated

The write-behind queue still has the path queued; however, the shadow was deleted. When `ShadowFlushActor` tries to flush, it finds no shadow file. This could cause the write-behind write to be skipped.

**Assessment of actual risk:** In practice, `markWrittenByUs` queries the SAF mtime synchronously right after the write. The `listFilesWithModTimes` in the polling loop also queries SAF mtime. These should return the same value for the same file. The risk is theoretical and limited to mtime granularity / provider lag. The existing content-hash guard already handles the case where the hash matches; the shadow deletion in that path is the only new behavior, and the write-behind queue risk exists only in this very narrow window.

**To be safe:** Consider adding a guard: only call `invalidateShadow` if the content hash does NOT match (i.e., only when we are about to emit a changed file). This way, own-writes whose hash matches are never shadow-invalidated.

Revised fix:
```kotlin
} else {
    fileSystem.invalidateShadow(filePath)  // clear stale shadow before reading
    val content = fileSystem.readFile(filePath) ?: continue
    val newHash = content.hashCode()
    if (contentHashes[filePath] == newHash) {
        modTimes[filePath] = modTime
        continue
    }
    ...
}
```

This is still correct because `markWrittenByUs` updates `contentHashes` right after writing. If the hash matches, it means either it was our own write (safe to skip) or a truly identical external write (content-equivalent, safe to skip). The shadow was already fresh (we wrote it). Deleting it and re-reading from SAF just wastes one IPC round-trip but does not corrupt the write-behind queue because the content re-read will be identical.

The safer alternative is to check if the hash matches FIRST and only invalidate shadow if it does NOT:
```kotlin
} else {
    // Two-phase: try hash check without invalidating shadow first.
    // If hash is unknown (null), we must read fresh from source anyway.
    val shadowContent = fileSystem.readShadowOnly(filePath)
    val shadowHash = shadowContent?.hashCode()
    if (shadowHash != null && contentHashes[filePath] == shadowHash) {
        // Content hasn't changed — own-write case. Safe to skip.
        modTimes[filePath] = modTime
        continue
    }
    // Shadow content differs or shadow is absent — invalidate and read from source
    fileSystem.invalidateShadow(filePath)
    val content = fileSystem.readFile(filePath) ?: continue
    val newHash = content.hashCode()
    ...
}
```

This is more complex but eliminates the write-behind risk entirely. However, `readShadowOnly` was added specifically for cases where you want to check shadow without falling through to SAF — this is the exact use case.

---

## Test Isolation

### InMemoryPageRepository state leaks between tests

`GraphLoaderProgressiveTest` instantiates `InMemoryPageRepository()` and `InMemoryBlockRepository()` fresh per test class instance. If the midnight-boundary test adds a journal page for "today" and a subsequent test checks for "no journal pages", the state leaks unless repositories are re-instantiated. The existing test pattern creates repos per test class, not per test method — acceptable as long as each test method uses a fixed/distinct date.

### `runTest` vs `runBlocking` for midnight watcher test

The watcher uses `delay()`. In `runTest`, `delay()` is automatically resolved by `TestCoroutineScheduler.advanceTimeBy()`. In `runBlocking`, `delay()` uses real time. The `GraphLoaderProgressiveTest` already mixes both (it uses `runBlocking` for some tests). For the midnight-boundary test, `runTest` is required to avoid a 1-day actual wait.

### Clock `advance` vs. `TestCoroutineScheduler.advanceTimeBy` coordination

`delay(ms)` is controlled by the test scheduler (virtual time). `clock.now()` is controlled by `FakeClock.advance()` (separate from virtual time). To test the loop correctly, you must advance both:
1. `fakeClock.advance(1.days + 1.seconds)` — makes `ensureTodayJournal()` see tomorrow's date
2. `testScheduler.advanceTimeBy(millisUntilNextMidnight + 1000)` — makes `delay()` complete

These must be done in the right order or the test will deadlock (the coroutine is suspended in `delay()` and the scheduler won't advance until the test body calls `advanceTimeBy`).
