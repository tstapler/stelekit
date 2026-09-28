# Features Research: Disk Change Detection

## 1. Obsidian Mobile (Android) — External Change Detection

**Detection mechanism:** Obsidian mobile is built on Capacitor (a web-based mobile framework). On Android, Obsidian does **not** use `FileObserver` or `ContentObserver` for real-time external change detection. It relies on a **full vault reload on foreground re-entry** after a background timeout. When the app has been in the background for more than 10–15 minutes, Android kills or suspends the Capacitor WebView runtime, and Obsidian performs a complete graph reload when the user returns to the app.

**Polling:** No per-file polling is used by the core app during an active foreground session. The community plugin "Vault File Refresh" (third-party) adds an 8-second recursive-scan poller as a workaround for cases where Obsidian's chokidar-based filesystem watcher is unreliable (Flatpak installs, FUSE/network drives). This is a plugin, not part of the core app.

**Foreground behavior:** The core Obsidian Android app does not refresh file contents during an active editing session when an external process writes to the vault. There is no live-update of the open note. The user sees the external change only after re-opening the note post-foreground-reload.

**User-perceived latency:** Up to 10–15 minutes (or until explicit restart) for changes made while Obsidian is backgrounded; essentially zero for changes made after the vault reloads on re-entry.

**Key insight for SteleKit:** Obsidian's model is a full reload on re-entry — not incremental per-file detection. SteleKit's `SafChangeDetector` (foreground observer via `ProcessLifecycleOwner.onStart` + 30-second poller + FileObserver/ContentObserver fast path) is architecturally superior to Obsidian's approach and targets sub-5-second detection rather than the 10–15 minute reload cycle.

---

## 2. Logseq Mobile (Android/iOS) — External Change Detection

**Detection mechanism:** Logseq's mobile app is also Capacitor-based. Logseq has a separate `capacitor-file-sync` plugin for its proprietary Logseq Sync service (cloud-based sync between Desktop, Android, and iOS). For users relying on third-party sync (Syncthing, Google Drive, iCloud, git), there is **no active file-watching** on mobile. Changes written to the graph folder while Logseq is backgrounded are picked up only when the user manually syncs or restarts the app.

**Polling:** None in the core app. The Logseq DB version (new mobile app replacing the file-based version) replaces the file-based sync problem entirely with a database sync model, removing the need for per-file external-change detection on mobile.

**Manual refresh requirement:** Community guides for Logseq mobile + third-party sync (Syncthing, Drive) explicitly warn users that they must sync before opening Logseq and again before closing, to avoid conflicts. There is no auto-detect-and-reload for external changes during an active session.

**Key insight for SteleKit:** Logseq treats mobile file sync as a user-managed operation, not an automatic background process. SteleKit's requirements (live-refresh of unedited journal pages within 5 seconds) represent a considerably more ambitious and user-friendly target than the state of the art in either Obsidian or Logseq mobile.

---

## 3. Other KMP/Android Note Apps — SAF-Backed Directory Watching

**Open-source examples:** Public KMP note apps (e.g., technophilist/KMP-Notes-App, BillyMRX1/The-Notes-KMP) are simple CRUD apps targeting private app storage; none of them deal with SAF-backed external directories and thus do not implement `ContentObserver` or `FileObserver` for external change detection.

**Android documentation and known patterns:**
- `FileObserver` uses inotify under the hood but is **not recursive**: it watches a single directory inode only. Changes in `pages/` or `journals/` subdirectories are invisible to a `FileObserver` attached to the graph root. The only correct workaround is to create separate `FileObserver` instances for each subdirectory — exactly what SteleKit's `SafChangeDetector.startFileObservers` now does (one each for `$graphPath/pages` and `$graphPath/journals`).
- `ContentObserver` registered with `notifyForDescendants = true` receives notifications for URI descendants, but the URI prefix matching is **string-based**: registering on the root children URI (the tree root) does not match notifications for URIs under `pages/` or `journals/` document nodes, because their document IDs do not share a URI prefix with the root children URI. The correct fix is to register on `buildChildDocumentsUriUsingTree(treeUri, "$treeDocId/pages")` and `buildChildDocumentsUriUsingTree(treeUri, "$treeDocId/journals")` — which is what FR-6 specifies.
- Some SAF providers (particularly on older Android or non-Google OEMs) do not deliver `ContentObserver` notifications reliably even when registered correctly. This is why a 30-second polling fallback is essential (already implemented in `startContentObserversAndPoller`).

**No open-source reference implementation exists** for correctly combining `FileObserver` (subdirectory, MANAGE_EXTERNAL_STORAGE fast path) + `ContentObserver` (subdirectory children URIs, SAF fallback) + polling + foreground observer in a KMP app. SteleKit's `SafChangeDetector` is an original implementation.

---

## 4. Write-Behind / Flush-on-Background — Standard Patterns and Own-Write Suppression

**Standard write-behind pattern:** Apps that need low-latency writes to SAF (which involves Binder IPC on every call) mirror writes to an internal shadow directory first (fast, direct I/O), then flush to SAF asynchronously. This pattern is sometimes called "write-behind caching" or "shadow storage." The canonical places to flush are `Activity.onStop()` / `Application.onTrimMemory(TRIM_MEMORY_UI_HIDDEN)`.

**Own-write suppression problem:** After the app flushes a dirty file to SAF, the next poll cycle reads the updated SAF mtime and compares it to the FileRegistry's recorded mtime. If the FileRegistry was not updated after the flush, the new SAF mtime looks like an external change — a spurious own-write event. For plain `.md` files this is usually suppressed by content-hash comparison (the content on disk equals what the app wrote). For **encrypted `.md.stek` files**, content-hash comparison is disabled (comparing encrypted blobs is meaningless), so the only guard is mtime. If `markWrittenByUs` is not called after the flush, encrypted files will always generate a spurious own-write event.

**Correct suppression sequence:**
1. App writes to shadow (fast path, internal storage).
2. On `onStop`, `ShadowFlushActor.flush()` drains the write-behind queue to SAF.
3. After each successful SAF `writeFile()`, record the post-flush SAF mtime via `markFileWrittenByUs` (= `fileRegistry.recordWrite(path, safMtime)`).
4. On the next poll, `fileRegistry.detectChanges()` sees `recordedMtime == currentSafMtime` and skips the event.

**Scope leak pattern (anti-pattern):** Creating a `CoroutineScope` inside `ShadowFlushActor` that is never cancelled is a well-known Android leak pattern. If `ShadowFlushActor` holds a scope and is re-instantiated on every flush, the old scope is never cancelled and keeps alive any coroutines it launched. The correct fix (FR-9) is to make `flush()` a plain `suspend fun` with no owned scope — callers (e.g., `PlatformFileSystem.flushPendingWrites()`) provide the suspension context.

**Per-instance freshness flag:** Android apps that cache SAF content in internal storage typically use a process-singleton flag to indicate "first startup after crash/kill, purge the cache." This breaks if the app supports multiple graphs in the same process session: switching from graph A to graph B would not purge graph B's stale shadow, because the process-wide flag is already `false` after graph A's first access. The correct model (FR-10) is a per-`ShadowFileCache` instance flag (`AtomicBoolean(true)`), reset to false on first access, so each new graph open gets its own full-purge pass regardless of when the process started.

---

## 5. Journal Auto-Refresh UX — Industry Patterns

**The problem:** The user has the journal page open (scrolling, reviewing). An external sync client (Syncthing, iCloud, Termux) writes a new journal entry or appends to the current day's page. What should the app do?

**Industry approaches:**

- **Bear Pro:** Displays all conflicted versions in the Note List; the user manually picks which version to keep. No auto-merge, no in-place reload. (Bear stores notes in a proprietary SQLite DB, so conflicts are stored as separate note objects, not as file overwrites.)
- **Evernote:** Saves the conflict as a separate "conflicted note" alongside the original; does not auto-reload. User sees an explicit conflict note in their list.
- **Joplin:** Adds a "Detect changes based on any timestamp change" mode (filesystem sync) where a note is re-scanned when the sync target file's mtime changes. Conflicts are silently resolved by "last writer wins" with the losing version saved as a conflict copy. No in-place UI refresh.
- **Obsidian (desktop via Obsidian Sync):** On conflict, presents a diff dialog; user can choose local vs remote or view both.
- **Notion, Roam:** Cloud-first; no concept of local file conflicts.

**Consensus pattern for file-based apps:** For a page that is **not being edited** (no dirty blocks), the app should silently reload the on-disk version and replace the in-memory view — no prompt needed. For a page that **is being edited** (dirty blocks), the app must not auto-reload (would destroy user edits); it should either show a conflict dialog or queue the reload for after the edit is saved.

This maps directly to the SteleKit requirements:
- FR-1/FR-2: only dirty-block pages are guarded from auto-reload; clean open pages reload silently.
- The existing conflict dialog (`externalFileChanges` SharedFlow → `StelekitViewModel`) handles the dirty-page case.

**Expected user-perceived latency for journal live-refresh:** Based on the industry survey, no competitor product provides live journal refresh during an active session. The 5-second target in FR-1 (open-but-unedited journal page refreshes within 5 seconds of an external write) is **better than all surveyed alternatives**.

---

## Codebase Observations

**`SafChangeDetector.kt` (current state, already partially fixed):**
- Three-strategy detection: `FileObserver` per subdirectory (MANAGE_EXTERNAL_STORAGE path), `ContentObserver` on subdirectory children URIs + 30s poller (SAF path), and `ProcessLifecycleOwner.onStart` foreground observer.
- Both `FileObserver` instances (`pages/`, `journals/`) use `filter { it.isDirectory }` to skip missing subdirs gracefully.
- `ContentObserver` registration is wrapped in `try/catch` to handle providers that reject registration.
- The file already reflects the fixes specified in FR-5 and FR-6.

**`ShadowFlushActor.kt` (current state, already refactored):**
- Stateless `suspend fun flush()` — no owned scope (FR-9 satisfied).
- Calls `onFlushed?.invoke(safPath)` after each successful flush + mtime stamp (FR-7 satisfied).
- Correctly does NOT call `onFlushed` on write failure or missing shadow.

**`ShadowFileCache.kt` (current state, already refactored):**
- `private val freshInstance = AtomicBoolean(true)` per instance (FR-10 satisfied).
- `isFirstAccess()` returns `true` on first call only.

**`GraphFileWatcher.kt` (still needs fix for FR-1):**
- Line 254: `activePageFilePaths?.invoke()?.contains(changed.entry.filePath) == true` — this guard is driven by `activePageFilePaths` which currently includes ALL open pages, not just pages with dirty blocks. The fix (FR-1 through FR-4) is to replace this with a `unsavedPageFilePaths` set derived from `BlockStateManager.dirtyPageUuids`.

---

## Summary of Key Findings

1. **No competitor (Obsidian, Logseq) does live file-watching on Android mobile** — both rely on full vault reloads on foreground re-entry (10–15 minute latency). SteleKit's three-strategy detection (FileObserver/ContentObserver/polling/foreground) is architecturally best-in-class; the bugs being fixed are implementation correctness issues, not design flaws.

2. **FileObserver is not recursive and ContentObserver URI matching is string-prefix-based** — watching the graph root misses all subdirectory events on both code paths. The fix (FR-5/FR-6: separate instances per subdirectory) is the only correct approach, matching official Android documentation guidance and community consensus.

3. **Own-write suppression for write-behind SAF flush requires mtime recording after each flush** — content-hash comparison cannot be used for encrypted files. The `onFlushed` callback pattern (FR-7/FR-8) that records the post-flush SAF mtime in `FileRegistry` is the standard solution; combined with the per-instance `ShadowFileCache.freshInstance` flag (FR-10), it closes the spurious-event and stale-shadow gaps that caused incorrect behavior on graph switch.
