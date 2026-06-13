# Agent 3 — How Other Apps Solve This

## Subject
How do Obsidian, Standard Notes, Notesnook, and Joplin handle "file changed externally, show
current content on next open"? What does Logseq itself do for file sync? What patterns emerge?

---

## 1. Logseq Desktop

### File-watcher approach (Electron / Node.js)
Logseq desktop uses a dedicated `src/electron/electron/fs_watcher.cljs` module backed by Node.js
`fs.watch` (which uses `inotify` on Linux, `FSEvents` on macOS, `ReadDirectoryChangesW` on Windows).
This is an OS-level push notification — no polling required on desktop.

When `fs.watch` fires a change event for a `.md` file, Logseq's `watcher_handler.cljs` reloads
the page from disk and updates the in-memory block graph. The reload is conditional: it checks
whether the user is actively editing the changed page; if so, the external change is held or
shown as a conflict.

### Mobile fs-watcher (Android) — polling only
**PR #7501** (authored by `andelf`, merged 2022) completely rewrote Logseq's Android file watcher
from an event-based implementation to **pure polling**. Stated reasons:
- Fewer bugs across all Android API levels
- Multilevel directory support
- Avoids notifying too soon after file operations (debounce problem)

The polling watcher compares file metadata (mtime + size) on a timer. This is architecturally
identical to what SteleKit's `GraphFileWatcher` already does with its 5-second polling loop.

### Key finding on Logseq sync
Logseq's built-in sync (Logseq Sync) delegates change detection entirely to the server: the client
polls the sync server for changed items, not the local filesystem. On mobile, local file changes
from other apps (Syncthing, Termux, iCloud) are detected via the polling watcher. Issues #11675
and #11676 (Jan 2025) confirm that the Android app still misses external file changes in some
cases — indicating Logseq has not fully solved this problem.

Concurrent edit issues (#7197, #8074) show that when two devices edit the same page without
syncing first, Logseq's last-write-wins policy causes data loss. Logseq does not use content
hashing or conflict-free merge — it uses server mtime as the authoritative freshness signal.

---

## 2. Obsidian

### Desktop (Electron)
Obsidian desktop uses FSEvents / inotify via Electron's built-in file watcher API. When a `.md`
file in the vault changes externally, Obsidian fires `vault.on('modify', ...)` callbacks which
re-read the file and update the editor state.

**Conflict behaviour**: If the user has unsaved edits and the external change arrives, Obsidian
shows a "file changed on disk" modal prompting to keep local edits or accept the disk version.
This is the equivalent of SteleKit's `externalFileChanges` SharedFlow + conflict dialog.

### Mobile (iOS/Android)
Obsidian mobile does **not** expose the vault file watcher to plugins at all — the internal watcher
is proprietary and not in the public API. On iOS, Obsidian uses iCloud Drive and registers for
`NSMetadataQuery` notifications to detect iCloud-originated changes. On Android, Obsidian does
not support external sync natively (Obsidian Sync is required, or manual use of third-party sync
apps between app opens).

**The "Auto Refresh Explorer" plugin** was created specifically because Obsidian's built-in
watcher on some platforms fails to detect files created by external sync tools (Syncthing in
particular on Windows). The plugin polls every ~3 seconds and forces a vault index refresh.
This is direct evidence that even Obsidian's event-driven watcher has failure modes.

### Obsidian-iCloud plugin approach
The `obsidian-iCloud` plugin uses a **dual mechanism** that serves as an instructive pattern:
1. **FSEvents recursive watcher** on the iCloud base directory — fires immediately on
   file materialization events (when iCloud downloads a changed file).
2. **Periodic polling fallback** (configurable interval, e.g. every 60 seconds) — a full
   directory comparison that catches changes FSEvents may have missed (e.g. when iCloud was
   downloading files while Obsidian was closed).

The polling comparison preserves **original modification timestamps** — it does not treat the
iCloud download event's timestamp as the file modification time.

### Key pattern from Obsidian
- Two-tier: event-driven fast path + polling fallback.
- At plugin level, change detection is mtime-based (stat comparison in the fallback loop).
- No content hashing used.
- Active edit protection: if the user has unsaved local edits, external changes trigger a conflict
  prompt rather than silent overwrite.

---

## 3. Joplin

### Sync algorithm
Joplin uses a fundamentally different architecture from SteleKit: notes are stored in Joplin's own
database (SQLite), not as raw `.md` files on the filesystem. Synchronization is to a cloud backend
(Joplin Cloud, WebDAV, Dropbox, etc.), not to a local folder of markdown files.

**Change detection**: Joplin tracks each note with an `updated_time` timestamp (epoch ms) stored
in the local SQLite database. The sync algorithm compares `updated_time` between local and remote
for each item. If remote `updated_time` > local, the remote version is downloaded. If both changed
since last sync, a conflict is created.

**No content hashing**: Joplin does not use content hashing for change detection. It relies purely
on `updated_time`, which is set by the app on every note save — not by the filesystem. Because
Joplin controls all writes (no external edits to the raw file), the `updated_time` is authoritative.

**Conflict handling**: A conflict notebook is created; the local version is moved there; the remote
version replaces the local. This is simpler than SteleKit's use case because Joplin has a single
authoritative backend.

### Key finding for SteleKit
Joplin's model is not directly applicable because Joplin controls the file format and all writes.
SteleKit must interoperate with external editors (Logseq desktop, Termux) that write `.md` files
without going through SteleKit's `updated_time` tracking.

---

## 4. Notesnook

### v3 sync architecture (2024)
Notesnook v3 (released August 2023–April 2024) undertook a major sync engine rewrite. Pre-v3,
Notesnook detected changed items based on `modified_time` (similar to Joplin). v3 replaced this
with a new approach described as "detecting changed items is now as efficient as comparing their
modified time but more stable, with resumability built-in."

The specifics are not public in detail, but from context (migration to SQLite + new sync engine)
the approach appears to be a combination of:
- **Per-item sequence numbers / generation counters** — stable, survive server restarts
- **Resumable delta sync** — tracks a high-watermark of synced items

This is analogous to the "write-epoch" pattern in Agent 2 research. Notesnook's move away from
pure timestamp comparison is strong validation that mtime-based change detection is fragile in
production sync systems.

---

## 5. Standard Notes

Standard Notes uses an encrypted cloud backend and an `updated_at` timestamp for each note,
similar to Joplin. External file edits are not supported (notes are stored in the server, not as
raw files on the local filesystem). Standard Notes does not have a local-file-watcher component.

Not directly applicable to SteleKit's use case.

---

## 6. Synthesized Patterns

### Pattern A: Event + polling dual tier (Obsidian, Logseq desktop, SteleKit current)
All production note apps that support local filesystem sync use a two-tier approach: an OS event
(inotify/FSEvents/ContentObserver) as the fast path, plus a periodic polling loop as the fallback.
No app relies on either mechanism alone.

**SteleKit already implements this correctly** in `GraphFileWatcher`. The gap is not in the watcher
architecture but in the `loadFullPage` guard that ignores watcher results.

### Pattern B: Polling only on mobile (Logseq Android, Obsidian mobile via plugin)
On mobile, pure polling is the dominant approach. Native file-change events are unreliable across
Android API levels and are unavailable on iOS. All production apps fall back to polling on mobile.

**Implication for SteleKit**: The 5-second polling interval is consistent with industry practice.
ContentObserver is a bonus fast-path, not a replacement for polling.

### Pattern C: Active-edit guard (Obsidian, SteleKit current)
All apps protect actively-edited pages from silent external-change overwrite. The mechanism is a
"file changed on disk" prompt. SteleKit already implements this via `activePageUuids` and the
`externalFileChanges` SharedFlow. This guard must be preserved in any redesign.

### Pattern D: Content-hash change detection (not widely used in note apps)
No mainstream note app (Obsidian, Joplin, Logseq, Notesnook) uses content hashing as the primary
change detection signal. Most use mtime or app-controlled `updated_time`. However:
- SteleKit already uses `String.hashCode()` as a secondary guard in `FileRegistry.detectChanges()`
  to suppress own-write false positives.
- The Notesnook v3 move to sequence numbers over pure timestamps is the closest industry precedent
  for a more robust approach.

### Pattern E: Own-write suppression (all apps)
All apps implement some form of own-write suppression to avoid treating their own saves as external
changes. SteleKit uses `markWrittenByUs()` in `FileRegistry`. Logseq uses a debounce window.
Obsidian plugins use file-identity-based filtering.

---

## Sources

- [refactor(android): rewrite fs watcher — logseq/logseq PR #7501](https://github.com/logseq/logseq/pull/7501)
- [Android sync not updating all files — logseq/logseq #11675](https://github.com/logseq/logseq/issues/11675)
- [Concurrent edits overwrite — logseq/logseq #7197](https://github.com/logseq/logseq/issues/7197)
- [Auto Refresh Explorer plugin — obsidianstats.com](https://www.obsidianstats.com/plugins/auto-refresh-explorer)
- [Obsidian-iCloud plugin — mnott/Obsidian-iCloud](https://github.com/mnott/Obsidian-iCloud)
- [Joplin synchronisation spec — joplinapp.org](https://joplinapp.org/help/dev/spec/sync/)
- [What is a conflict? — joplinapp.org](https://joplinapp.org/help/apps/conflict/)
- [Introducing Notesnook v3 — blog.notesnook.com](https://blog.notesnook.com/introducing-notesnook-v3)
- [Sync issues finally drove me from Joplin — ctrl.blog](https://www.ctrl.blog/entry/joplin-notes-sync.html)
- [Discussion: Is git the only reliable sync for Logseq in 2025?](https://discuss.logseq.com/t/discussion-is-git-the-only-truly-reliable-self-hosted-sync-for-multiple-devices-in-2025/33502)
- SteleKit source: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphFileWatcher.kt`
