# UX Design: web-local-folder-livesync

**Date**: 2026-07-17
**Status**: Ready for implementation review
**Inputs**: `requirements.md`, `research/ux.md`, `implementation/plan.md`
**Reused components (not redesigned)**: `ui/components/DiskConflictDialog.kt`,
`ui/screens/DiskConflictFullScreen.kt`, `ui/components/SyncStatusBadge.kt`,
`ui/onboarding/Onboarding.kt`

This document turns `research/ux.md`'s findings and `implementation/plan.md`'s Phase 2/3/4/8
stories into concrete wireframes, flows, and testable acceptance criteria. It does not re-derive
UX principles — see `research/ux.md` for the "why." It adds the "exactly what renders, in what
state, with what copy" layer the plan leaves as prose acceptance criteria.

---

## 0. Design principles carried forward (do not violate)

1. **No new conflict UI.** Every content conflict — folder-livesync or desktop-file-watcher —
   renders through the existing `DiskConflictDialog` → `DiskConflictFullScreen` pair, unchanged.
2. **"Reconnect" is never "conflict."** A stale/lost handle (`Disconnected`, `Denied`,
   `PromptNeeded`) is a *permission* problem with a *"grant access"*-shaped fix. A content mismatch
   (`HostChangedConflict`) is a *data* problem with a *"pick a version"*-shaped fix. These must
   never share copy or a resolution affordance.
2b. Folder/drive iconography for "this browser tab vs. the folder on disk" — never the existing
   `Computer`/`Cloud` icons, which already mean local-vs-remote-git in `SyncStatusBadge`.
3. **No broken affordances.** `supportsNativeDirectoryPicker == false` means the entire new surface
   (badge, settings entry point, reconnect prompts) renders nothing — not a disabled button.
4. **A persistent idle state is required, not optional.** "Synced to `<dirName>`" must be visible
   at rest — silence reads as "is this even connected?" per the Dropbox-icon mental model users
   bring.
5. **No dead ends.** Every error/edge-case state below names its exit path in the same breath as
   the problem.

---

## 1. Surface inventory

| # | Surface | New or reused | Plan reference |
|---|---|---|---|
| 1 | First-time directory pick (new graph) | Reused as-is | Onboarding.kt (unchanged) |
| 2 | Unsupported-browser fallback | Reused as-is | Onboarding.kt (unchanged), Epic 8.2 |
| 3 | `FolderSyncStatusBadge` (sidebar, all states) | New | Epic 2.3, 8.1 |
| 4 | Session-resume flow (silent + one-click) | New | Epic 2.2 |
| 5 | Permission denied / declined | New (badge state) | Epic 2.2, 2.3 |
| 6 | Directory moved/deleted externally (`Disconnected`) | New (badge state) | Epic 4.4 |
| 7 | `FolderSyncSettings` — enable on existing graph | New | Epic 3.1 |
| 8 | Upgrade reconciliation flow (Critical Finding) | New | Phase 3, Story 3.1.2 |
| 9 | External-change conflict (`DiskConflictDialog` reuse) | Reused, new trigger | Epic 3.2, Epic 4.2.1, Phase 5 |
| 10 | Full comparison screen (`DiskConflictFullScreen` reuse) | Reused, unmodified | — |
| 11 | Write failure / degraded sync | New (badge state + banner reuse) | Epic 4.4 |
| 12 | Cross-tab coordination (or lack of dedicated UI) | Explicit non-surface | Epic 6.1/6.2 |
| 13 | Rename/move propagation (mostly invisible) | Explicit non-surface | Phase 7 |

**13 surfaces designed** (10 with dedicated wireframes below; 2 are explicit "no new UI" design
decisions with rationale — #12, #13; #10 is documented as "verify unchanged" only).

---

## 2. Surface 1 — First-time directory pick (new graph)

No change to `Onboarding.kt`'s `GraphSelectionStep`. What changes is *what happens after* the
click: today `pickDirectoryAsync()` is one-shot-import-and-forget; after this project it also
retains the handle and starts write-through/poll loops. No new pixels here — flagged only so the
review knows this entry point's *behavior* changed even though its *UI* didn't.

```
┌─────────────────────────────────────────┐
│         Where's your graph?              │
│                                           │
│  ┌─────────────────────────────────┐    │
│  │  /Users/tyler/notes               │    │
│  │                                   │    │
│  │  [ Select Graph Directory ]  ← native OS picker → browser permission prompt
│  │                                   │    │
│  │  [ Try Demo Graph ]               │    │
│  └─────────────────────────────────┘    │
└─────────────────────────────────────────┘
```

**Flow**: click → native OS folder picker → browser's own "wants to view files" permission prompt
(outside app control) → on allow, `pickDirectoryAsync()` imports + now *also* retains
`hostDirHandle`, persists it to IndexedDB, and (per Epic 8.1) the sidebar's `FolderSyncStatusBadge`
appears for the first time showing "Synced to `<dirName>`".

**Edge case**: user cancels the native picker (no directory chosen) → identical to today,
`pickDirectoryAsync()` returns null, onboarding stays on the same step, no partial state persisted.

---

## 3. Surface 2 — Unsupported-browser fallback

No change. `fileSystem.supportsNativeDirectoryPicker == false` still substitutes plain text; no
picker button, no badge, no settings entry point anywhere in the app (Epic 8.2 makes this a tested
guarantee, not just an assumption).

```
┌─────────────────────────────────────────┐
│  /Users/tyler/notes                      │
│                                           │
│  Graph stored in browser private storage.│   ← plain text, no button
│                                           │
│  [ Try Demo Graph ]                      │
└─────────────────────────────────────────┘
```

---

## 4. Surface 3 — `FolderSyncStatusBadge` (sidebar, all states)

Sits next to the existing `SyncStatusBadge` in the sidebar header. Same visual register (small
icon + label, `labelSmall` type, sidebar-header height) so it reads as a sibling, not a new
subsystem. Uses a **folder icon** (`Icons.Default.Folder`/`FolderOpen`), never `Computer`/`Cloud`.

```
Sidebar header:
┌───────────────────────────────────────────────────┐
│  MyGraph ▾        [🔃 Sync]      [📁 Synced to notes]│
│                    (git)          (folder livesync)  │
└───────────────────────────────────────────────────┘
```

State table (precedence order per plan Story 8.1.1 — first match wins):

| Precedence | `HostAccessState` | Pending writes | Rendered copy | Icon/tint | Clickable |
|---|---|---|---|---|---|
| 1 (highest) | `Disconnected(reason)` | any | **"Folder not found — Reconnect"** | warning-tint folder-off icon | Yes → re-runs picker (Surface 6) |
| 2 | `Denied` | any | **"Folder access declined — Grant access"** | warning-tint folder icon | Yes → `requestHostDirectoryAccess` |
| 2 | `PromptNeeded` | any | **"Reconnect folder"** | neutral folder icon | Yes → `requestHostDirectoryAccess` |
| 3 | `Granted` | > 0 (queue not draining, e.g. mid-permission-blip — this is `plan.md`'s `SyncDegraded` state) | **"N changes not yet synced to folder"** | warning-tint | Yes → same reconnect affordance |
| 4 | `Granted` | > 0 (normal in-flight) | **"N changes syncing to `<dirName>`"** | neutral, subtle progress | No (informational) |
| 5 | `Granted` | 0 | **"Synced to `<dirName>`"** | neutral folder icon, steady | No (informational — persistent idle state per Principle 4) |
| 6 (lowest) | `NotApplicable` | — | *(not rendered)* | — | — |

**Copy rationale** (ties to `research/ux.md` §0/§4):
- `Denied` vs. `PromptNeeded` share the *reconnect* action but get **different copy** — "declined"
  vs. "reconnect" — because a user who explicitly clicked "Don't allow" needs to understand *why*
  they're being asked again (they said no), not just "click here" with no acknowledgment of their
  prior choice.
- `Disconnected`'s copy ("not found") is deliberately never "reconnect" or "grant access" —
  matching Principle 2 — because clicking it re-runs `showDirectoryPicker()`, a different recovery
  path (re-locate) than a permission re-grant.

**Naming**: Row 3 above is `implementation/plan.md`'s Domain Glossary `SyncDegraded` state
(`hostAccessState == Granted && pendingWriteCount > 0 && hostWriteStuck`, per Task 4.4.1c) — named
explicitly here so both artifacts use the same ubiquitous language, not just "degraded sync"
generically.

**Accessibility** (Epic 2.3.1b, 8.3.1):
- The status *text* carries `liveRegion="polite"` — state transitions are announced without
  interrupting typing.
- The reconnect/grant-access/re-locate affordance is a real `clickable`/`Button`, Tab-reachable,
  Enter/Space-activatable — never a bare `Text` with a click modifier.
- After a click triggers the native browser permission prompt (which steals focus outside the
  DOM), focus explicitly returns to the badge via `FocusRequester` once the prompt resolves —
  never left dangling on a removed element.

---

## 5. Surface 4 — Session-resume flow (silent + one-click)

Two paths, both starting at app launch, both ending at the Surface 3 badge:

```
App launch (new tab/session)
        │
        ▼
reconnectHostDirectory(graphId)  — runs automatically, no UI yet
        │
        ├─ no handle in IndexedDB ─────────────────► Badge: not rendered (NotApplicable)
        │
        ├─ handle found, queryPermission()="granted" ─► Badge: "Synced to <dirName>" (SILENT — no
        │                                                prompt, no dialog; matches Excalidraw's
        │                                                "every subsequent save is one click" bar)
        │
        └─ handle found, queryPermission()="prompt" ──► Badge: "Reconnect folder"
                                                          (ONE click away, per requirements'
                                                           accepted "at most one click" metric)
```

**The one-click path, expanded** (VS Code "Open Recent" framing — name the moment before the OS
prompt fires, per `research/ux.md` §1):

```
User clicks "Reconnect folder" in the badge
        │
        ▼
requestHostDirectoryAccess(graphId) runs inside the click handler
        │
        ▼
Browser's native permission prompt appears
   "stelekit.app wants to view files in the folder 'notes'.  [Allow] [Don't allow]"
   (outside app control — but the click that triggered it was an in-app,
    plain-language "Reconnect folder" action, not a bare unexplained OS interruption)
        │
   ┌────┴────┐
   ▼         ▼
Allow      Don't allow
   │         │
   ▼         ▼
Badge →    Badge →
"Synced    "Folder access declined —
to X"      Grant access"  (Denied state,
                            no retry-loop — browsers
                            rate-limit repeated prompts
                            after a decline)
```

**Why no separate in-app "Resume editing MyGraph?" dialog before the OS prompt**: the badge click
*itself* is that framing — it's a labeled, plain-language action ("Reconnect folder") the user
initiates, not a background timer surprising them with an OS dialog. A modal-before-the-modal
would add a click without adding clarity. (This is a deliberate deviation from `research/ux.md`
§2's literal "Resume editing `MyGraph`?" copy suggestion — the badge label already carries that
framing at zero extra clicks, which better serves the "at most one click" success metric.)

---

## 6. Surface 5 — Permission denied / declined

Covered by the `Denied` row in Surface 3's table. Flow:

```
queryPermission()/requestPermission() → "denied"
        │
        ▼
Badge: "Folder access declined — Grant access"  (warning tint, persistent, clickable)
        │
        │  User's edits keep flowing to OPFS/cache as before (no data loss — this
        │  degrades to "OPFS-only" mode, not a blocked app)
        │
        ▼
User clicks "Grant access" whenever ready → same requestHostDirectoryAccess() flow as Surface 4
   → browser may or may not re-prompt (rate-limiting is a browser policy the app doesn't control;
     if the browser silently continues denying, state stays Denied — no infinite retry loop,
     no auto-nagging)
```

**No dead end**: the app remains fully usable in OPFS-only mode the entire time; the badge is the
sole, always-available, never-auto-dismissed exit path back to live sync.

---

## 7. Surface 6 — Directory moved/deleted externally (`Disconnected`)

```
Any host read/write throws NotFoundError (or a moved-directory-shaped failure)
        │
        ▼
hostAccessStateFlow → Disconnected("NotFoundError")
        │
        ▼
Badge: "Folder not found — Reconnect"  (distinct copy + distinct icon from Denied/PromptNeeded)
        │
        ▼
User clicks → re-runs showDirectoryPicker() (NOT requestPermission() — this is a re-locate,
              not a re-grant) → user picks the folder again (same location, or its new location
              if they moved it)
        │
        ▼
Re-running the picker on the SAME already-populated graph routes through
connectHostDirectory() (Surface 8's reconciliation pass) — NEVER a raw re-import — so a directory
that was simply renamed/moved and picked again reconciles rather than re-imports from scratch.
```

**Why this must never silently fall back to OPFS-only mode**: per `research/ux.md` §4, a user who
still has the physical folder (just renamed) would be surprised and alarmed if the app quietly gave
up on it. The badge stays in the warning state indefinitely until the user acts — this is
intentional persistence, not a bug.

---

## 8. Surface 7 — `FolderSyncSettings`: enable live sync on an existing graph

New settings panel entry, shown only when `supportsNativeDirectoryPicker == true` AND
`hostAccessState == NotApplicable` for the current graph (i.e., never shown once already
connected, and never shown at all on unsupported browsers — Principle 3).

```
Settings ▸ Sync
┌───────────────────────────────────────────────────────┐
│  Folder Sync                                            │
│  ─────────────────────────────────────────────────────  │
│  This graph is stored in your browser only. You can      │
│  connect it to a folder on your computer so edits made    │
│  here are written straight to your files — no export,     │
│  no git required.                                          │
│                                                             │
│              [ Enable live folder sync ]                    │
│                                                             │
│  Existing edits in this graph are kept — nothing is         │
│  overwritten when you connect.                               │
└───────────────────────────────────────────────────────┘
```

**Flow**: click "Enable live folder sync" → native OS picker → browser permission prompt → on
allow, `connectHostDirectory(existingOpfsPath)` runs (never `importUserDirToCache`) → transitions
into Surface 8 (reconciliation).

**The reassurance line ("nothing is overwritten") is load-bearing UI copy**, not decoration — it
directly targets the Critical Finding's failure mode (silent destruction of browser-only edits)
and should not be cut for space. This is the one place in the whole feature where the user is
told, in plain language, that the operation they're about to trigger is safe against the exact bug
`research/architecture.md` identified.

---

## 9. Surface 8 — Upgrade reconciliation flow (Critical Finding)

This is the highest-stakes surface in the feature and the plan under-specifies its *user-visible*
shape (Phase 3's stories cover the algorithm and data safety, not the loading/summary UI). This
design closes that gap.

```
User clicks "Enable live folder sync" and grants permission
        │
        ▼
┌───────────────────────────────────────────────────────┐
│  📁  Connecting to folder…                                │
│      Comparing your browser edits with the files on disk. │
│      [progress spinner]                                    │
└───────────────────────────────────────────────────────┘
   (transient — walks the picked directory once; on a large graph this may take a moment,
    so it must never be a silent freeze — a spinner + label is the minimum bar, matching
    the "checking for changes…" pattern research/ux.md §4 calls for rather than leaving
    the user staring at a static settings screen wondering if the click registered)
        │
        ▼
runHostReconciliation classifies every path
        │
        ▼
┌───────────────────────────────────────────────────────┐
│  ✓  Folder sync enabled                                   │
│                                                             │
│      142 files already match                              │
│      3 files differ — you'll be asked which version to     │
│        keep as you open each page                          │
│      5 new files found on disk — added to your graph        │
│      2 browser-only pages — will be written to the folder   │
│                                                              │
│                                    [ Done ]                  │
└───────────────────────────────────────────────────────┘
```

**Flow after the summary**:
- `Identical` (142) → nothing further, no per-file UI ever.
- `HostChangedConflict` (3) → **not** shown as three simultaneous dialogs. Per the existing
  `pendingConflicts` mechanism (`AppState.kt:138-140`, already used for desktop's disk-watcher
  conflicts), each is queued and surfaced as a `DiskConflictDialog` **the next time the user
  navigates to that page** — exactly like today's desktop external-change conflicts. The sidebar's
  conflict-count indicator (existing `pendingConflictFilePaths`) reflects all 3 immediately so the
  user knows they exist without being interrupted three times in a row.
- `HostOnlyNew` (5) → imported silently, appear in the graph/sidebar like any newly-created page —
  no dialog (matches `research/architecture.md`'s "no-op" framing — this is not a decision point).
- `BrowserOnlyNeedsPush` (2) → enter `hostWritePending`; the badge immediately shows "2 changes
  syncing to `<dirName>`" right after this summary closes, so the user sees the queue draining in
  real time rather than wondering if their browser-only edits were preserved.

**Error case — reconciliation itself fails partway** (e.g., permission revoked mid-walk, directory
unreadable): summary screen shows a plain-language failure state instead of the counts —
*"Couldn't finish comparing your files. [Try again]"* — and `hostDirHandle` is **not** set (state
stays `NotApplicable`), so the graph remains exactly as safe as it was before the click (no
partial reconciliation is treated as complete).

```
┌───────────────────────────────────────────────────────┐
│  ⚠  Couldn't finish comparing your files                  │
│      Nothing was changed — your graph is unaffected.      │
│                                    [ Try again ]  [ Cancel ]│
└───────────────────────────────────────────────────────┘
```

---

## 10. Surface 9 — External-change conflict (`DiskConflictDialog` reuse)

No new component. Reused exactly as `DiskConflictDialog.kt` exists today, triggered by four sources
now instead of one: desktop/Android's file watcher (unchanged), and three new sources from this
feature — `HostChangedConflict` classifications from Phase 3 reconciliation, Phase 4's
`flushHostWrite` pre-write freshness check (Epic 4.2.1 — a debounced write-through flush discovering
the host file changed underneath it since the edit was queued, also routed through `onHostConflict`,
not a silent overwrite), and Phase 5's steady-state poll/observer detections.

```
┌─────────────────────────────────────────────┐
│  Page modified on disk                          │
│                                                  │
│  "MyPage" was changed externally while you       │
│  were editing.                                    │
│                                                    │
│  Your edit:                                        │
│  ┌──────────────────────────────────┐              │
│  │ (local content preview, 200 chars) │              │
│  └──────────────────────────────────┘              │
│                                                       │
│  Disk version:                                        │
│  ┌──────────────────────────────────┐                  │
│  │ (disk content preview, 200 chars)  │                  │
│  └──────────────────────────────────┘                  │
│                                                            │
│  [ Keep my changes ]           (primary, filled)           │
│  [ Use disk version ]          (outlined)                   │
│  [ Save my edit as a new block ] (text, only if local≠blank)│
│  [ View full comparison ]       (text)                       │
│  [ Manual resolve (conflict markers) ] (text)                 │
│  "This page won't sync with disk again until the markers      │
│   are removed."                                                 │
└─────────────────────────────────────────────┘
```

**No wording change needed** — the dialog's copy ("changed externally," "disk version") is already
source-agnostic; it does not say "desktop file watcher" anywhere, so a folder-livesync-sourced
conflict reads identically correctly. This is confirmed by reading the component (no source-name
interpolation exists to update).

**One folder-livesync-specific nuance not covered by the existing dialog**: distinguishing "an
external editor/git changed this file" from "another browser tab changed this file" (both route
here per requirements.md scope). Per `research/ux.md` §3's accessibility note, if a future
iteration adds this distinction it must be a labeled text difference, not a color-only signal — but
per the plan's actual Phase 6 design (narrow per-write/per-poll locks, not whole-feature ownership),
a same-origin second tab's write should simply **not** produce a conflict at all in the common case
(the losing tab's poll tick is skipped, and it picks up the winning tab's already-applied result on
its own next tick) — so this dialog should rarely if ever need to say "another tab." Recommendation:
ship without the distinction; add it only if telemetry/bug reports show it's actually reached.

---

## 11. Surface 10 — Full comparison screen (`DiskConflictFullScreen` reuse)

No change. Verified: the screen takes raw `localContent`/`diskContent` strings and computes its own
diff — it has no knowledge of *why* the conflict exists (file watcher vs. folder livesync vs.
future sources), so it needs zero modification. "Closing returns to the conflict dialog" subtext
and the Android predictive-back interception both apply unchanged.

---

## 12. Surface 11 — Write failure / degraded sync

Two coordinated surfaces fire together on a host write failure:

```
flushHostWrite() throws (permission revoked mid-session, quota exceeded, NotFoundError)
        │
        ├──► hostAccessStateFlow → Disconnected(reason)   [if NotFoundError-shaped]
        │         → Badge flips to Surface 6's "Folder not found — Reconnect"
        │
        └──► onHostWriteFailed → GraphLoader.writeErrors channel (existing)
                  → StelekitViewModel.observeWriteErrors() → dismissable banner:

┌─────────────────────────────────────────────────────┐
│  ⚠ Failed to save page 'MyPage'. Tap to retry indexing.  [×]│
└─────────────────────────────────────────────────────┘
```

The path stays in `hostWritePending` (not silently dropped) — the badge's pending-count state
(Surface 3, row 3: "N changes not yet synced to folder") remains visible until the retry succeeds,
so the user always has a persistent, non-dismissable-by-accident signal of the actual sync debt,
even after they've dismissed the transient banner.

**UX note for implementers**: the reused banner copy ("Tap to retry indexing") is written for the
DB-indexing failure case this channel was originally built for, not host-write failures
specifically. It is not actively misleading (the underlying problem is still "this page didn't
save"), but a future pass should consider whether host-write failures deserve their own copy
variant on this shared channel — flagged as a nice-to-have, not a blocker, since the plan
explicitly chose to reuse the channel rather than add a new one.

---

## 13. Surface 12 — Cross-tab coordination: explicit "no dedicated UI" decision

`research/ux.md` §4 recommends: *"other tabs should visibly indicate 'syncing from another tab'
rather than attempting independent writes."* The plan's actual Phase 6 design (narrow per-write and
per-poll-tick `WebLock`s, a losing tab's tick is a **silent, debug-log-only skip**) does not surface
this to the user at all.

**Design decision: no dedicated cross-tab indicator ships in this project.** Rationale:
- OPFS is already cross-tab-shared, so a losing tab's *next* tick sees the winning tab's result —
  the divergence window is one poll interval (≤10s), not indefinite.
- The existing Surface 3 badge state (`Granted`, pending count) already reflects the *outcome*
  correctly regardless of which tab performed the write — a user watching either tab's badge sees
  consistent, correct status without needing to know which tab "owns" the write.
- Adding a distinct "another tab is syncing" state would require exposing tab identity across the
  lock boundary, which the plan's Web Locks approach deliberately avoids (Pattern Decisions table:
  "no existing precedent for whole-feature leader election").

**This is a recommendation to accept the plan's simpler behavior, not a UX gap to fix before
ship** — flagging it explicitly so the "reviewer sees a research recommendation the plan didn't
implement" question has a documented answer instead of looking like an oversight.

---

## 14. Surface 13 — Rename/move propagation: explicit "mostly invisible" decision

Renaming a page in-app already has its own rename dialog (unrelated to this feature). This
feature's write-new-then-delete-old host propagation (Phase 7) and its interrupted-rename recovery
heuristic (stale-content-match detection) are **deliberately invisible** — they run as a
consequence of the existing rename action, not as a new user-facing step.

**One edge case does need to surface**: if `verify-before-delete` (Task 7.1.1b) fails — the newly
written host file's content doesn't match what was just written — this is a write failure, and
routes through Surface 11 exactly like any other `flushHostWrite` failure (the old file is
deliberately left in place rather than deleted, so no data is lost, just a stale duplicate that a
future reconciliation pass will detect and clean up per Story 7.1.2).

No new wireframe — reuses Surface 11's error banner + badge state.

---

## 15. UX Acceptance Criteria

Each criterion is phrased to be checkable by a human tester without reading the implementation.

### Task completion

1. A first-time user can pick a directory and see the graph loaded in **1 click** (unchanged from
   today — Surface 1).
2. A returning user with an active browser grant sees their folder resume with **0 clicks** (silent
   resume — Surface 4).
3. A returning user whose grant needs re-confirmation resumes access in **exactly 1 click**
   ("Reconnect folder" → native Allow) — meets the requirements' explicit success metric.
4. A user enabling live sync on an already-populated graph completes the flow (click → pick →
   reconciliation summary → Done) in **≤ 3 clicks**, with the "nothing is overwritten" reassurance
   visible before they commit to the native picker.
5. From any error state (`Denied`, `Disconnected`, reconciliation failure, write failure), the user
   can return to a working state in **≤ 2 clicks** (one click to trigger recovery, at most one more
   to confirm a native browser prompt).

### Error states — specific message + specific action

6. Permission declined shows the exact text **"Folder access declined — Grant access"** and offers
   a click that re-attempts `requestHostDirectoryAccess` (Surface 6).
7. A moved/deleted directory shows the exact text **"Folder not found — Reconnect"** (not "grant
   access," not "conflict") and offers a click that re-runs the directory picker, not a permission
   re-request (Surface 7).
8. A reconciliation failure shows **"Couldn't finish comparing your files"** plus the explicit
   reassurance **"Nothing was changed — your graph is unaffected"** and offers "Try again" (Surface
   9).
9. A host write failure surfaces a dismissable banner naming the specific page that failed to save
   and offers a retry action (Surface 12) — the failure is never only a `println` in devtools.
10. A content conflict (host vs. browser edit) always routes through `DiskConflictDialog` with its
    existing four-choice + escape-hatch structure — never a bare "file changed" toast with no
    resolution path.

### No dead ends

11. Every error state listed in ACs 6–10 has a visible, always-available exit action — none require
    the user to reload the page, clear browser data, or find a hidden menu to recover.
12. Declining the browser's native permission prompt does not trigger an automatic retry loop (no
    repeated OS prompts without an explicit user click) and does not remove the recovery
    affordance from the badge — the "Grant access" state persists indefinitely until the user acts.
13. `NotApplicable` (never-connected or unsupported-browser) renders **zero** new UI anywhere in the
    app — verified by Epic 8.2's fallback regression test — so there is no broken/disabled
    affordance for a user who will never be able to use this feature to get confused by.
14. A user who cancels the native directory picker (at any entry point — Surface 1, 6, 7, or 8's
    connect step) returns to exactly the screen they were on before, with no partial/inconsistent
    state (no half-connected badge, no orphaned IndexedDB entry left presenting as connected).

### Trust / status visibility (per `research/ux.md` §2's "no spinners" / trust-signal finding)

15. When live sync is connected and idle, the badge shows a persistent **"Synced to `<dirName>`"**
    state at all times — never blank/absent while actually connected (Principle 4).
16. When writes are queued, the badge's pending count updates within one flush cycle of the queue
    changing — a user editing rapidly sees the count reflect reality, not a stale number.
17. The reconciliation summary (Surface 9) always shows counts for all four outcome categories that
    have at least one member — a user is never left wondering "did it find my browser-only edits?"
    because that count (`BrowserOnlyNeedsPush`) is always named explicitly when non-zero, never
    folded silently into another category.

### Accessibility (WCAG 2.1 AA baseline)

18. The badge's reconnect/grant-access/re-locate affordance is reachable via Tab in DOM order and
    activatable via Enter or Space, not only pointer click (WCAG 2.1.1).
19. The badge's status text uses `aria-live="polite"` (Compose `liveRegion` semantics) — state
    transitions are announced to screen readers without interrupting the user's current typing
    focus, and never use `assertive` (matching the existing `RateLimited` precedent's
    non-interruptive convention).
20. After a badge click triggers the browser's native permission prompt, keyboard focus lands back
    on a valid, visible, focusable element (the badge, showing its updated state) once the prompt
    resolves — never lost to `document.body` (Surface 3's `FocusRequester` requirement).
21. `FolderSyncSettings`'s "Enable live folder sync" button and the reconciliation summary's "Done"
    /"Try again"/"Cancel" buttons are all real `Button` composables — Tab-reachable,
    Enter/Space-activatable — not custom-styled `Text` with a bare click modifier.
22. All new status/error copy (badge states, reconciliation summary, write-failure banner) conveys
    its meaning through text, not color alone — every warning-tinted state pairs its tint with
    distinct wording (e.g., "not found" vs. "declined" vs. "N changes not yet synced"), consistent
    with `DiskConflictDialog`'s existing precedent of never signaling by color alone.
23. Text/icon contrast for all new badge states meets **4.5:1** minimum against the sidebar
    background in both light and dark theme (verify the chosen warning-tint color token against
    Material3's `errorContainer`/`onErrorContainer` or equivalent — do not hand-pick a new hex
    value outside the existing theme's token set).
24. The reconciliation "Connecting to folder…" progress state is announced to screen readers on
    entry (so a screen-reader user isn't left silent during what may be a multi-second wait on a
    large graph) and again on completion with the summary counts.

---

## 16. Summary of what's new vs. reused

| Component | Status |
|---|---|
| `FolderSyncStatusBadge` | New — 6 states, precedence-ordered, folder iconography |
| `FolderSyncSettings` | New — single-purpose settings panel, one entry point |
| Reconciliation progress + summary UI | New — not detailed in `implementation/plan.md`; specified here (Surface 9) |
| `DiskConflictDialog` | Reused unmodified — new trigger source only |
| `DiskConflictFullScreen` | Reused unmodified |
| `Onboarding.kt` fallback text | Reused unmodified |
| Write-error banner (`indexingError`) | Reused, copy caveat noted (Surface 12) |
| Cross-tab "another tab syncing" indicator | Explicitly **not built** — documented decision (Surface 12) |
