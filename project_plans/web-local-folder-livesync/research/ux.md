# UX Research: web-local-folder-livesync

**Date**: 2026-07-17
**Feature**: Live write-through + change-detection sync between the web/WASM build and a
user-picked local host directory, via the File System Access API.

---

## 0. Existing codebase conflict/sync UX (parity baseline)

Before looking outward, this is the pattern already shipped for desktop's disk-conflict flow and
git sync. **The new feature must slot into this vocabulary, not invent a new one.**

### The three-layer conflict surface

| Layer | File | Trigger | Interaction |
|---|---|---|---|
| 1. Blocking dialog | `ui/components/DiskConflictDialog.kt` | `GraphLoader.externalFileChanges` fires while a block is being edited | `AlertDialog`, `onDismissRequest = {}` (no dismiss-by-tapping-outside — forces an explicit choice). Four stacked full-width actions: **Keep my changes** (filled `Button`, primary), **Use disk version** (`OutlinedButton`), **Save my edit as a new block** (`TextButton`, only shown if local content is non-blank), **View full comparison** (`TextButton`). A fifth escape hatch, **Manual resolve**, inserts `<<<<<<<`/`=======`/`>>>>>>>` markers and explicitly tells the user *"This page won't sync with disk again until the markers are removed"* — i.e. it names the consequence of the escape hatch up front. |
| 2. Full diff screen | `ui/screens/DiskConflictFullScreen.kt` | "View full comparison" from the dialog | Full-screen `Scaffold`, real line-level diff (not just previews), three explicit states (`Identical`, `NoLocalEdit`, `Different`) each with distinct copy so the user is never staring at an ambiguous empty diff. Title bar subtext says *"Closing returns to the conflict dialog"* — orienting the user that this is a drill-down, not a dead end. Android predictive-back is intercepted so the back gesture can't silently strand `diskConflictViewFullVisible` state. |
| 3. Batch resolution screen | `ui/screens/git/ConflictResolutionScreen.kt` | Multi-file git merge conflict (`SyncState.ConflictPending`) | Per-file `FilterChip` toggle, **defaults to "Keep mine"** for every file, `Computer` icon for local vs `Cloud` icon for remote (a consistent local/remote iconography worth reusing for "this device" vs "on disk"). Finish button is disabled until every file has an explicit selection (`selections.size == conflicts.size`). A separate "Abort merge" flow requires its own confirm dialog and explicitly states what's preserved vs discarded. |

**Non-blocking status layer**: `ui/components/SyncStatusBadge.kt` renders `SyncState` in the
sidebar header as a small icon+label combo, not a dialog — `Idle` (greyed sync icon), in-progress
states (spinning), `MergeAvailable(n)` (blue cloud-download, `"↓ n"`), `ConflictPending` (amber
warning, `"Conflict"`), `Error` (red, routes to `onAuthError` if the error is an auth failure),
`Success` (green check that auto-fades after 3s), `LocalChangesPending(n)` (neutral cloud-upload,
`"n unsynced"`) — importantly documented as *"tappable, same as every other actionable state"* —
and `RateLimited` (neutral, `"Retrying…"`, explicitly documented as *never* clickable and *never*
phrased as "tap to retry" since it's automatic).

**Session-scoped credential precedent** (directly cited in the requirements as the accepted
pattern to mirror for directory permission re-grant): `SyncState.CredentialExpired` renders as a
clickable badge with copy *"GitHub authentication expired — tap to re-connect"*
(`error/DomainError.kt:201,233`) — non-blocking, single tap, no modal interrupts the user's typing.
`SyncState.CredentialVaultLocked` similarly resolves via a single click that re-shows the unlock
screen (`ui/App.kt:1351`).

**Onboarding fallback precedent**: `ui/onboarding/Onboarding.kt` already branches on
`fileSystem.supportsNativeDirectoryPicker` — when true, a "Select Graph Directory" button calls
`pickDirectoryAsync()`; when false, it silently substitutes the text *"Graph stored in browser
private storage."* with no picker button at all. This is the existing feature-detection UX
convention: **don't show a broken affordance, replace it with a plain-language statement of what
will happen instead.**

### What this implies for the new feature
- The directory-picker re-grant on session resume should be a **single click surfaced as a
  `SyncState`-style badge/banner**, worded like `CredentialExpired`'s "tap to re-connect" — not a
  blocking modal on every launch.
- External changes to synced-folder files should route through the **existing three-layer
  `DiskConflict` surface**, unchanged — this is explicitly in scope per the requirements
  ("Conflict UX should reuse the existing ConflictResolutionScreen/SyncState.ConflictPending
  machinery"). No new conflict dialog should be built.
- Use **Computer vs Cloud** iconography sparingly reused as **"this browser tab" vs "the folder on
  disk"** would be a false-friend reuse — those icons mean local-vs-remote-git in the existing UI.
  For folder livesync, a closer semantic match is a folder/drive icon, to avoid implying git
  remote semantics that don't apply here.
- Unsupported-browser fallback should reuse the Onboarding pattern: replace the picker affordance
  with plain text, don't grey out a broken button.

---

## 1. Comparable UX patterns in similar products

### VS Code for the Web (vscode.dev)
- `showDirectoryPicker()` triggers a native OS folder picker; the browser then shows its own
  permission prompt ("...wants to view files in the folder ___") — **this permission prompt is
  outside the app's control**, non-customizable, but the app **frames the moment before it** by
  making "Open Folder" an explicit, well-labeled menu action so the OS prompt doesn't feel
  unexpected.
- **Persistent access via "Open Recent"**: rather than auto-resuming access silently, VS Code
  stores the handle and surfaces it as a normal "recently opened" list item. Selecting it re-runs
  `requestPermission()`, which (with Chrome's persistent-permissions feature) can now show a
  three-way prompt: **"Allow this time" / "Allow on every visit" / "Don't allow"**. This reframes
  "re-grant access" as a natural continuation of a familiar recent-files pattern instead of a
  standalone permission chore.
- Chrome's persistent-permission grant only appears after the *previous* visit's handle was
  properly persisted to IndexedDB and retrieved on the new visit — and the prompt only fires on a
  fresh navigation to the origin (not a soft reload), which matters for the "reopening in a new
  tab/session requires at most one click" success metric: the resume action must be triggered from
  a full page load path, not assumed to fire automatically.

### Excalidraw ("Fugu" file-system integration)
- Stores the `FileSystemHandle` directly on the in-memory file object once granted
  (`file.handle = handle`) so **every subsequent save is a single click with no dialog at all** —
  the picker is only shown once per file per session. This is the model to match for "write-through
  within ~500ms, no further user action" — once permission is live, writes should be invisible.
- Drag-and-drop files retain their handle via `getAsFileSystemHandle()`, so re-dropping a
  previously-picked file resumes editing it in place — a pattern worth considering for "resume a
  previously synced graph" flows (dropping the folder back onto the app icon/window).
- Cross-browser degradation: on browsers without the API, every save becomes a literal file
  download, which **visibly clutters the user's Downloads folder** — cited by users as a genuine
  friction point. This is a cautionary example for SteleKit's own fallback path (import-only OPFS
  mode) — it must be framed as a distinct, understood mode, not a degraded version of live sync
  that silently produces surprising side effects.

### General File System Access API apps (Squoosh, Photopea-style tools)
- The common convention across FSA-based apps is: **picker happens once, an in-app affordance
  (title bar, filename chip, or status badge) shows the currently-open file/folder name**, and
  clicking that affordance is the natural place to trigger re-picking or checking permission —
  this maps directly onto SteleKit's existing `SyncStatusBadge` real estate.

### Chrome's own documented three-way persistent-permission prompt
This is the concrete browser-native building block for the "resume access" flow:
1. **"Allow this time"** — session-only grant (today's default behavior).
2. **"Allow on every visit"** — indefinite grant until the user manually revokes it via site
   settings.
3. **"Don't allow"** — deny.

The requirements accept session-scoped grants as sufficient ("acceptable UX"), but the app should
still let a user opt into "Allow on every visit" when the browser offers it — that reduces the
"one click per session" cost to **zero clicks** for users who opt in, which is strictly better than
the baseline the requirements ask for. The app-level UX should not force a re-prompt if the browser
already silently re-granted permission (i.e. always call `queryPermission()` first, only escalate
to a user-visible "resume access" affordance if `queryPermission()` returns anything other than
`granted`).

---

## 2. User mental models and expectations

Drawing on Ink & Switch's "local-first software" framing (the seven ideals: multi-device sync,
offline capability, real-time collaboration, longevity, privacy, user control, and — the one most
relevant here — **"no spinners": the client is a peer with its own copy of the data, not a thin
view waiting on a remote source of truth**):

- **The core mental model users will bring**: "this folder is now the truth, and the browser tab
  is just a window onto it" — closer to Obsidian's vault model (a plain folder any tool can touch)
  than to a traditional web app's "cloud is truth, browser is a client" model. Confirmed by the
  broader plain-text-notes ecosystem research (Obsidian, GitJournal, NotesHub) — users choosing
  this class of tool specifically want **the folder, not the app, to be the durable artifact**.
- **What will confuse users**:
  - Any delay between typing and the write hitting disk that's long enough to notice (hence the
    ~500ms target) — because the mental model is "this folder is live," any perceptible lag reads
    as a bug, not "still syncing."
  - Silence after granting the picker permission. Users coming from Dropbox/Google Drive-style
    sync icons expect *some* persistent visual confirmation that "yes, this is actively connected"
    — an idle badge state is necessary, not optional, even when there's nothing to report.
  - The browser's *own* permission UI (the OS-level prompt) is indistinguishable in origin from
    the app's UI — users may not realize *why* they're being asked again in a new tab, especially
    since nothing else about the app changed. The app should proactively label the moment ("Resume
    editing `MyGraph`? SteleKit needs permission to access this folder again" as in-app copy
    *before* the browser prompt fires), matching VS Code's "Open Recent" framing.
  - Users will expect the browser tab and their file manager/git/editor to see **the same file
    state at the same time** — any staleness window (from polling-based external-change detection,
    since there's no native OS filesystem-watch API in browsers) needs to be either invisible (fast
    enough polling) or explicitly surfaced (a "checking for changes…" indicator), never silently
    stale.
- **What will pleasantly surprise users** (and is worth calling out in first-run copy, per the
  JTBD section below): that `git status`, `git diff`, or any other local tool pointed at the same
  folder sees changes made in the browser tab in near-real-time, with no export/sync step. This is
  the actual differentiator versus every other browser note app.

---

## 3. Accessibility requirements

Applies to: the (re-)grant affordance, the polling/sync-status indicator, and any new UI chrome
around the folder picker (note: the native OS folder-picker dialog itself is outside the page's
DOM and not subject to WCAG — but everything the app renders around it is).

- **Focus management for the resume-access prompt**: if implemented as a dialog (matching
  `DiskConflictDialog`'s `AlertDialog` pattern), it must follow the same rules Compose
  Material3's `AlertDialog` already gives for free (focus moves into the dialog on open, Tab/Shift+Tab
  cycle within it, focus returns to the triggering element on close) — verify this holds for a
  *non-blocking* banner/badge variant too, since a badge-triggered flow won't get Compose's dialog
  focus trap automatically and needs explicit focus handling when a click opens a follow-up
  surface.
- **Live region for sync status changes**: the `SyncStatusBadge`-equivalent for folder livesync
  (write-through succeeded / external change detected / permission lost) should use an
  `aria-live="polite"` announcement (via Compose's semantics `liveRegion` modifier on wasmJs, where
  supported) so screen-reader users learn about a state change without needing to poll the badge —
  this matters more here than for git sync because writes are frequent and largely silent by
  design.
  - Do **not** mark a "Retrying…"/rate-limited-equivalent state as `aria-live="assertive"` — match
    the existing convention (`RateLimited` is explicitly non-interactive, low-urgency) to avoid
    interrupting screen reader users on every poll tick.
- **Keyboard reachability**: the resume-access badge and any "grant access" button must be
  reachable via Tab in DOM order and activatable via Enter/Space, not only pointer click — this is
  a plain `WCAG 2.1.1 (Keyboard)` requirement, and matters specifically for wasmJs since Compose
  Multiplatform's web target has historically had gaps in exposing custom composables as real
  focusable/keyboard-operable DOM nodes; verify semantics are wired through, don't assume Compose
  parity with Android/desktop here.
- **Conflict UI accessibility is already covered** by reusing `DiskConflictDialog`/
  `ConflictResolutionScreen` as-is (per requirements) — no new accessibility surface to design
  there, but any *new* fields specific to folder-livesync conflicts (e.g. distinguishing "external
  process changed this file" from "another tab changed this file") must carry accessible labels
  consistent with the existing dialog's `Text`/`Surface` structure, not just color coding (the
  existing dialog already avoids color-only signaling by pairing the error-colored "could not find
  matching section" text with explicit wording, not a color chip alone — new additions should hold
  that bar).
- **Focus trap best practice** (from WCAG/APG research): prefer the native `<dialog>` element's
  built-in focus trap semantics where the underlying Compose-for-Web rendering allows it, since
  browser-native trapping is more robust than hand-rolled JS focus-cycling and automatically wires
  Escape-to-close and `::backdrop`.

---

## 4. Error states and edge cases requiring graceful UX

| Scenario | Detection | Suggested UX |
|---|---|---|
| Browser doesn't support File System Access API | `'showDirectoryPicker' in window` feature-detect, surfaced today as `fileSystem.supportsNativeDirectoryPicker` | Reuse the Onboarding pattern exactly: no broken picker button, plain-language substitute text. Livesync badge should not appear at all — the graph silently stays in OPFS-import mode, matching the "no regression to fallback behavior" success metric. |
| Permission prompt declined ("Don't allow") | `requestPermission()` resolves `'denied'` | Do not retry-loop the browser prompt (browsers rate-limit/block repeated prompts after a decline). Fall back to read-only or OPFS-only mode with a persistent, clearly-worded badge ("Folder access declined — [Grant access] to resume live sync") that re-attempts only on explicit user click. |
| Directory moved/deleted externally while app has it open | Writes/reads start throwing `NotFoundError`. Per spec-compat research, behavior differs across browser handle implementations (path-based vs reference-based), so **do not assume a specific recovery is automatically possible** | Surface a distinct (non-`DiskConflict`) error state — this is not a content conflict, it's a broken link. Offer "Re-locate folder" (re-run the picker) rather than silently reverting to OPFS, since reverting would surprise a user who still has the physical folder just renamed/moved. |
| Directory permission silently revoked (OS-level, browser settings, or handle simply stale) | `queryPermission()` returns non-`'granted'` on any operation, not just at startup | Check `queryPermission()` opportunistically before writes, not only at launch — a session can outlive the grant (user revokes via Chrome's site settings mid-session). Downgrade to the same "Grant access" badge state used for the startup re-grant flow, so there is exactly one visual vocabulary for "we need you to click something," not two. |
| Concurrent write races (app write vs. external process write to the same file) | Hash/timestamp mismatch detected at write time or poll time | This is explicitly in scope to route through the existing `DiskConflict`/`ConflictResolutionScreen` machinery — treat exactly like today's desktop file-watcher race, no special-casing. |
| Cross-tab conflict (two tabs, same graph, same folder) | `BroadcastChannel`/`Web Locks API` coordination missing or a lock held by another tab | Web Locks API is same-origin, same-browser only (not cross-browser, not cross-device) — appropriate scope match for "cross-tab coordination" as listed in-scope. One tab should hold write ownership; other tabs should visibly indicate "syncing from another tab" rather than attempting independent writes and creating spurious conflicts. |
| Polling detects nothing changed, indefinitely | N/A — steady state | Should be invisible; per rabbit-hole note, tune poll interval against Page Visibility API (`document.visibilityState`) — poll aggressively only while the tab is visible/focused, back off or pause entirely when hidden, and force one re-check-on-focus when the tab regains visibility (covers the common "edited the file in an external editor while the tab was backgrounded" case cheaply). |
| `FileSystemObserver` API availability | Currently Chrome-only origin trial (not yet stable) | Cannot be relied on as the primary mechanism today — must ship the polling/visibility-based fallback as the real implementation, with `FileSystemObserver` treated as a pure enhancement behind a feature-detect (`'FileSystemObserver' in self`), never a hard dependency. |
| Reopening in a new tab before the browser's persistent-permission grant exists | `requestPermission()` triggers the OS prompt again every time (no persistent grant) | This is the expected baseline per requirements ("one-click resume access... acceptable") — the in-app "Resume access to `MyGraph`" affordance handles this uniformly regardless of whether the browser grants a session grant or a persistent one underneath. |

---

## 5. Jobs-to-be-done lens

**Functional jobs**
- "Let me use this graph directly from a folder on my machine, without adopting a git remote or a
  cloud account, and have my browser-tab edits actually land in that folder."
- "Let git, ripgrep, my text editor, my backup tool, or any other local tool operate on these files
  with zero export/import step and zero staleness."
- "Pick the folder once per browser install, not once per note."

**Emotional jobs**
- "I don't want to trust a hosted service with my private notes" — the entire feature exists to let
  a distrustful-of-cloud user get real-time sync *without* a server in the loop; the UX must never
  quietly introduce a step that looks like data leaving the machine (no accidental network calls,
  no ambiguous "syncing to cloud" iconography reused from the git-sync feature for this very
  different, purely-local mechanism).
- "I want confidence that what I see in the browser matches what's actually on disk" — this is the
  emotional core the ~500ms write-through and the visible sync-status badge both serve; silence or
  lag directly undermines the trust this feature is supposed to build.
- "I don't want to be surprised by a browser permission wall interrupting my flow" — the one-click
  resume, framed as a normal in-app action (à la VS Code's "Open Recent") rather than a bare OS
  permission dialog, is what keeps this from feeling adversarial.

**Social jobs**
- "I want to be the kind of user who owns their data and isn't locked into a vendor" — this is the
  same social signal Obsidian/git-notes users already send by choosing plain-text-in-a-folder tools;
  SteleKit's web build reaching UX parity with that expectation (folder as source of truth, not the
  browser's private storage) lets a web-only user credibly make the same claim as a desktop user.
- "I want to show/hand off my notes to someone else (or another tool) without an export dialog" —
  the folder being genuinely live and genuinely a normal folder (openable in Finder/Explorer,
  committable to git, greppable) is itself the social deliverable — it lets a user say "here's my
  notes folder" without caveats about what's actually synced versus stuck in browser storage.

---

## Sources

- [File System Access — WICG spec](https://wicg.github.io/file-system-access/)
- [The File System Access API: simplifying access to local files — Chrome for Developers](https://developer.chrome.com/docs/capabilities/web-apis/file-system-access)
- [Persistent permissions for the File System Access API — Chrome for Developers](https://developer.chrome.com/blog/persistent-permissions-for-the-file-system-access-api)
- [file-system-access EXPLAINER.md — WICG GitHub](https://github.com/WICG/file-system-access/blob/main/EXPLAINER.md)
- [File System Access API: How VSCode.dev Edits Local Files in the Browser](https://nasserspace.hashnode.dev/vscode-file-system-access-api)
- [How vscode.dev Interacts With User's Local Filesystem](https://www.amitmerchant.com/how-vscode-dev-interacts-with-user-local-filesystem/)
- [vscode.dev(!) — VS Code blog](https://code.visualstudio.com/blogs/2021/10/20/vscode-dev)
- [Excalidraw blog: Excalidraw and Fugu — Improving Core User Journeys](https://plus.excalidraw.com/blog/excalidraw-and-fugu)
- [Excalidraw blog: The browser-fs-access library](https://plus.excalidraw.com/blog/browser-fs-access)
- [Local-first software: You own your data, in spite of the cloud — Ink & Switch](https://www.inkandswitch.com/essay/local-first/)
- [Mastering Dialog Accessibility — Vispero](https://vispero.com/resources/mastering-dialog-accessibility/)
- [How to Build Accessible Modals with Focus Traps — UXPin](https://www.uxpin.com/studio/blog/how-to-build-accessible-modals-with-focus-traps/)
- [Modal Dialog Example — W3C WAI-ARIA APG](https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/examples/dialog/)
- [The File System Observer API origin trial — Chrome for Developers](https://developer.chrome.com/blog/file-system-observer)
- [FileSystemObserver — MDN](https://developer.mozilla.org/en-US/docs/Web/API/FileSystemObserver)
- [fs/proposals/FileSystemObserver.md — WHATWG](https://github.com/whatwg/fs/blob/main/proposals/FileSystemObserver.md)
- [Cross-tab Synchronization with the Web Locks API — SitePen](https://www.sitepen.com/blog/cross-tab-synchronization-with-the-web-locks-api)
- [web-locks EXPLAINER.md — W3C GitHub](https://github.com/w3c/web-locks/blob/main/EXPLAINER.md)
- [What is a FileSystemHandle? — whatwg/fs GitHub issue #59](https://github.com/whatwg/fs/issues/59)
- [I ditched my note-taking app's cloud sync for Git and I'm never going back — MakeUseOf](https://www.makeuseof.com/dont-need-cloud-subscription-sync-notes-free-tool/)
- [I replaced my note-taking apps with a cross-platform, easy-to-use plain-text stack — XDA Developers](https://www.xda-developers.com/replaced-note-taking-apps-with-plain-text-stack/)

## Codebase references (parity baseline)

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/DiskConflictDialog.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/DiskConflictFullScreen.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/ConflictResolutionScreen.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SyncStatusBadge.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/SyncState.kt`
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt` (lines ~96, 201, 233 — `CredentialExpired` copy precedent)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt` (lines ~134, 182-202 — session-scoped credential state transitions)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/onboarding/Onboarding.kt` (lines 107-176 — `supportsNativeDirectoryPicker` fallback pattern)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt` (line 433 — `externalFileChanges: SharedFlow<ExternalFileChange>`)
- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` (existing OPFS-based dirty-tracking/marker-write scheduler — the write-behind pattern the new write-through mechanism will extend or replace)
