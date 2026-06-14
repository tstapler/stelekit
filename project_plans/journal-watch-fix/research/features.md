# Features Research: journal-watch-fix

## How Similar Systems Handle Midnight Journal Creation

### Logseq (reference implementation)

Logseq's Electron app uses a `setInterval`-based recurring check. On the main process, it runs a timer every minute that compares the current date to the last-created journal date. When the day changes, it creates the journal page and optionally navigates to it. The interval runs for the lifetime of the Electron process.

Key observations:
- Logseq does NOT compute the exact delay to midnight; it polls every 60 seconds
- The current-date check uses `new Date().toDateString()` comparison
- On mobile (Logseq Mobile), the same logic is implemented via React Native's `AppState.addEventListener('change')` which re-checks the journal on foreground resume — effectively making "app foreground" the trigger rather than a timer

### Obsidian

Obsidian's "Daily Notes" plugin stores the last-opened note date in plugin settings. It checks for a new day on:
1. Plugin activation
2. `app.workspace.on('active-leaf-change')` — any tab switch
3. An explicit "Open today's daily note" command

There is no midnight coroutine in Obsidian's open-source portions. Mobile Obsidian uses the iOS/Android equivalent of foreground callbacks.

### iOS Apps (Bear, Day One, Drafts)

Bear and Day One use `NotificationCenter.default.addObserver(forName: .NSCalendarDayChanged, ...)` which is an OS-provided notification fired exactly at midnight (in the current timezone). This is the cleanest approach on iOS.

On Android there is an equivalent: `Intent.ACTION_DATE_CHANGED` broadcast. However, the requirements spec explicitly states this is out of scope; the coroutine-based approach is chosen.

### Implications for SteleKit

The spec chooses the "compute delay to next midnight + loop" approach over periodic polling. This is superior because:
- It wakes up at most once per day (vs. Logseq's 1440 wakeups/day)
- It correctly handles the exact boundary (polling can miss midnight if the interval fires 1ms early)
- The `delay()` is subject to `TestCoroutineScheduler.advanceTimeBy()` so it is fully testable without real time

## External Sync File Watching in Similar Systems

### Obsidian File Recovery

Obsidian's `live-sync` community plugin explicitly invalidates its "known checksum" cache whenever the file watcher fires an `'modify'` event. It does NOT rely on a content hash guard to distinguish own-writes from external writes — it uses a separate "local-edit-pending" flag instead.

This is the same architectural problem as Bug 2: using content hash as the ONLY own-write filter breaks when the hash store is stale.

### Notion Desktop

Notion does not expose source files; all sync goes through the API. Not relevant.

### iCloud Drive + Markdown Apps

Apps like iA Writer and Ulysses that use iCloud Drive rely on `NSMetadataQuery` or `FilePresenter` to receive fine-grained "file changed by another host" notifications. On Android, the SAF `ContentObserver` is the analogous mechanism. SteleKit already registers a `ContentObserver` via `FileSystem.startExternalChangeDetection`; the bug is downstream in the content-hash guard, not in the notification delivery.

### VSCode File Watcher

VSCode's file watcher uses `chokidar` which calls `fs.watch()`. The own-write suppression in VSCode is done by maintaining a "recently written" set keyed by `{path, mtime}` — after a write, the exact mtime of the written file is stored; the next `change` event for that `{path, mtime}` pair is suppressed. This is more robust than a content hash guard because mtime is always available without reading file content.

SteleKit's `markWrittenByUs` follows a similar pattern but augments it with a content hash because Android SAF does not guarantee that `getLastModifiedTime` after a write returns a stable value (the SAF provider may lag).

## Edge Cases Identified Beyond Explicit Requirements

### Day boundary during active edit

If the user is actively editing a block at midnight, `ensureTodayJournal()` will create the new journal entry in the DB but the user will remain on the current page. The journal list will update reactively via the Flow observation in the sidebar. No conflict resolution is needed because journal creation is additive.

### Timezone change while app is running

If the user changes their timezone (e.g. during travel) while the app is open, the midnight-boundary coroutine will wake at the wrong local midnight. After waking, it re-computes `TimeZone.currentSystemDefault()` for the NEXT iteration, so the error self-corrects within one day.

If the timezone changes such that "today" was already tomorrow's date (crossing midnight backward), `ensureTodayJournal()` will silently create a page for the new "today" — this is correct behavior.

### External sync writes today's journal file while midnight watcher fires

There is a benign race: the midnight watcher calls `ensureTodayJournal()` which creates a DB-only journal page (no file yet), and simultaneously the file watcher detects a newly synced `2026-05-29.md` file. Both paths write to the DB. `JournalService.ensureTodayJournal()` already handles this via the duplicate-merge logic (`mergeDuplicateJournalPages`).

### Shadow invalidation during write-behind queue flush

The write-behind queue (`WriteBehindQueue`) on Android schedules SAF writes in the background. If `invalidateShadow` is called for a file that is pending in the write-behind queue, the next `readFile` will go to SAF — but the SAF file may not yet have the latest content (the write is still queued). This scenario only affects the `detectChanges` path for own-writes (which are supposed to be marked via `markWrittenByUs` before `invalidateShadow` is relevant). For externally-changed files the write-behind queue has no entry, so `invalidateShadow` → SAF read is always safe.

### DST spring-forward: midnight doesn't exist

In some timezones, clocks spring forward from 01:59 to 03:00, meaning 02:00–02:59 don't exist. `LocalDate.plus(1, DateTimeUnit.DAY).atStartOfDayIn(tz)` correctly handles this: `atStartOfDayIn` starts from `00:00` and adjusts to the first valid instant in the timezone, which may be 03:00 UTC+offset after the spring-forward. The delay will be slightly longer than 24 hours that night, which is correct — there's no phantom midnight to fire at.

### DST fall-back: midnight occurs twice

Clocks fall back from 02:59 to 02:00, so `00:00` occurs twice. `atStartOfDayIn` returns the FIRST occurrence (before the clock change), so the midnight watcher fires at the expected wall-clock time. The second 00:00-02:00 period does not trigger a second wakeup because the loop computes the NEXT midnight after the just-fired one.
