# Requirements: journal-watch-fix

## Problem Statement

Two related regressions in the graph file-watching and journal lifecycle:

1. **New-day journal not created** — When the app is open across midnight, no new journal
   entry is generated for the new day. `ensureTodayJournal()` is only called once at startup
   (`onPhase1Complete`), so any day boundary crossed while the app is running is silently
   missed.

2. **External sync changes never reload** — When a file is modified by an external sync
   tool (e.g. Logseq sync, iCloud Drive, git pull) while the app is running, the watcher
   detects the mtime change but the updated content is never loaded into the DB. The stale
   shadow cache serves the old file content, causing the content-hash guard in
   `FileRegistry.detectChanges` to treat the change as "our own write" and skip it.

## Root Causes

### Bug 1 — New-day journal (both Desktop/JVM and Android)
`StelekitViewModel` calls `journalService.ensureTodayJournal()` inside the
`onPhase1Complete` callback and at a few user-action sites (`appendToToday`). There is
**no recurring check** for a day boundary crossing. Once the app is loaded, no code path
calls `ensureTodayJournal()` again unless the user explicitly navigates to the journal tab
or triggers an append.

### Bug 2 — External sync shadow stale-read (Android SAF path)
`FileRegistry.detectChanges()` calls `fileSystem.readFile(filePath)` when a file's mtime
has increased. On Android, `PlatformFileSystem.readFile()` checks the shadow cache first:

```
readFile → shadowCache.resolve() → returns stale File content
```

The stale content's hash matches `contentHashes[filePath]` (set when the file was last
cleanly read), so the guard at line 127 of `FileRegistry.kt` skips the file:

```kotlin
if (contentHashes[filePath] == newHash) {
    modTimes[filePath] = modTime
    continue  // ← externally-changed file silently dropped
}
```

`GraphFileWatcher.checkDirectoryForChanges` does call `fileSystem.invalidateShadow(path)`
but only **after** `detectChanges` has already read and cached the stale content — too late.
`invalidateStaleShadow` is called only at startup, not during the ongoing watch loop.

## Platforms Affected
- Bug 1: Desktop (JVM) and Android
- Bug 2: Android (SAF path only; JVM `readFile` has no shadow layer)

## Requirements

### REQ-01: Detect day boundary while app is running (Bug 1)
After a graph is fully loaded, launch a coroutine that:
- Computes the delay until the next local midnight using `Clock.System` + current timezone
- Sleeps until midnight
- Calls `journalService.ensureTodayJournal()` (already idempotent; mutex-protected)
- Loops indefinitely so every subsequent midnight is also handled

The coroutine must be tied to the `RepositorySet`/graph scope, not to a composable scope,
so it survives recomposition. It must be cancelled when the graph is closed or
`cancelBackgroundWork()` is called.

### REQ-02: Invalidate shadow before content read in detectChanges (Bug 2)
In `FileRegistry.detectChanges()`, when `modTime > lastKnown` for a non-encrypted file,
call `fileSystem.invalidateShadow(filePath)` **before** `fileSystem.readFile(filePath)`.

This ensures the stale shadow is deleted before we read, so `readFile` falls through to
SAF and returns the actual current content. On JVM, `invalidateShadow` is a no-op, so
this change is zero-cost on desktop.

### REQ-03: Tests
- `FileRegistry` unit test: externally-changed file is returned in `changedFiles` even
  when a stale shadow would have produced the same content hash (mocked `FileSystem`).
- `GraphLoader` integration test (JVM): verify that `ensureTodayJournal()` is eventually
  called after a simulated midnight boundary (inject a fake `Clock`).

## Acceptance Criteria

1. On desktop: leave app open across midnight (or advance system clock) → today's journal
   page is created automatically, visible in the journals list.
2. On Android: modify a markdown file via a file manager or external sync → within one
   polling interval (~5s), the app shows the updated content.
3. No regression: own writes (from `GraphWriter`) are still correctly suppressed by the
   watcher (existing `GraphLoaderWatcherTest` passes).
4. No regression: warm-start fast path still fires `onPhase1Complete` quickly
   (`GraphLoaderProgressiveTest` passes).

## Files Expected to Change

| File | Change |
|------|--------|
| `kmp/src/commonMain/.../db/FileRegistry.kt` | Add `invalidateShadow` call before `readFile` in `detectChanges` |
| `kmp/src/commonMain/.../ui/StelekitViewModel.kt` or new `JournalDayBoundaryWatcher` | Add midnight-crossing coroutine |
| `kmp/src/jvmTest/.../db/FileRegistryTest.kt` (new or existing) | Shadow stale-read regression test |
| `kmp/src/jvmTest/.../db/GraphLoaderProgressiveTest.kt` | Midnight-crossing test (fake clock) |

## Out of Scope

- Encryped (`.md.stek`) files — already handled separately in `detectChanges` (no
  content-hash guard, mtime alone is authoritative).
- The `syncShadow` / `invalidateStaleShadow` startup flow — correct as-is; bugs are in
  the ongoing-watch path only.
- Adding ContentObserver notifications for new-day file creation — the 5-second poller
  already handles externally-created journal files correctly.
