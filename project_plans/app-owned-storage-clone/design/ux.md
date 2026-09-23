# UX Design: app-owned-storage-clone

Phase 3 design for `app-owned-storage-clone`. Wireframes, interaction flows, error states, and
testable UX acceptance criteria for the surfaces defined in `implementation/plan.md`. Reuses
findings from `research/ux.md` verbatim where cited — this document does not re-derive them.

Grounded directly in current source: `GitSetupScreen.kt` (`Step1CloneMode` L708-739, `Step2RepoPath`
L741-853, `WikiSubdirBrowserDialog` L862-934), `Sidebar.kt` (Edit Graph dialog L576-650, Remove-graph
dialog L550-570), `DiskConflictDialog.kt` (full file), `FolderSyncStatusBadge.kt` (full file),
`FolderSyncSettings.kt` (`ReconciliationUiState`/`FolderSyncReconciliationProgress` wiring L44-110),
`ADR-001`/`ADR-002`/`ADR-003`.

---

## Surface index

| # | Surface | Type | Plan reference |
|---|---------|------|-----------------|
| 1 | `UnifiedLocationPicker` | Interactive | Epic 2.1 |
| 2 | New-graph dialog with picker embedded | Interactive | Epic 2.2 (Android), 2.3 (Web) |
| 3 | `Step2RepoPath` with picker embedded | Interactive | Epic 2.2, 2.3 |
| 4 | "Move storage location…" entry point | Interactive | Story 3.2.2, 3.3.3 |
| 5 | Relocate-vs-Link choice dialog | Interactive | Story 3.4.1, 4.2.1 |
| 6 | Move confirmation dialog | Interactive | Story 3.4.2 |
| 7 | Copy/verify progress | Interactive | Story 3.4.3 |
| 8 | Verification-failed error state | Interactive | Story 3.4.3, 3.1.5 |
| 8b | Reopen-failed error state | Interactive | Story 3.4.3 (Task 3.4.3c), 3.1.5 |
| 9 | Post-move cleanup prompt / undo window | Interactive | Story 3.1.3 step 10 |
| 10 | Link-broken / permission-revoked status badge | Interactive | Story 4.1.3 |
| 11 | Plain-graph `AppOwned` warning (+ zip export, both platforms) | Interactive | Story 2.2.1d/e, 2.3.3 |
| 12 | Insufficient-space pre-flight | Condensed | Task 3.1.1d |
| 13 | Interrupted-mid-move / staging-directory sweep | Condensed | Story 3.1.2 |
| 14 | Observability log lines | Condensed | Epic 5.1 |

14 surfaces total: 12 interactive (full treatment), 2 condensed (non-interactive/background), plus one wireframe-only entry point folded into #10. (Surface 8b — Reopen Failed — added in the Phase 4 adversarial-review repair pass to cover `StorageMoveUiState.ReopenFailed`, Story 3.1.5/3.4.3c; numbered as a sub-surface of #8 rather than renumbering 9-14.)

---

## Surface 1: `UnifiedLocationPicker`

The single shared component used by Surfaces 2, 3, and 4. Generalizes `WikiSubdirBrowserDialog`'s
breadcrumb-dialog shape (`GitSetupScreen.kt:862-934`).

### Wireframe

```
┌─ Choose where to keep this graph ─────────────────────┐
│                                                         │
│  ○  App storage                                        │
│     Kept inside SteleKit only — not visible in your    │
│     device's file manager, and removed if you          │
│     uninstall the app.                                 │
│                                                         │
│  ○  Browse…                                             │
│     Pick a folder on your device                        │
│                                                         │
│  ── Recent ──────────────────────────────────────────  │
│  ○  📁 Documents/notes                                  │
│  ○  📁 SDCARD/logseq-vault                              │
│                                                         │
│                                    [Cancel]  [Next] ⛔  │
└─────────────────────────────────────────────────────────┘
   ⛔ = disabled until a row is selected
```

No radio circle is pre-filled on open — per `research/ux.md` §5, neither "App storage" nor
"Browse…" is selected by default, and `Next`/`Confirm` stays disabled until the user taps a row.
"App storage" is always row 1, non-scrolling. "Browse…" is always row 2. Recents (if any) render
below a divider, oldest-truncated, capped at a fixed count (5) so the always-visible rows never
scroll off on a small phone screen.

- **Android**: "Browse…" launches `pickDirectoryAsync()` (SAF `ACTION_OPEN_DOCUMENT_TREE`) directly
  — no intermediate in-app folder browser, since SAF's own picker is already a full explorer.
- **Web (Chromium)**: "Browse…" calls `showDirectoryPicker()` synchronously from the click handler
  (no `await` gap before the call, preserving the browser's user-activation requirement — plan.md
  Task 2.3.2a).
- **Web (Firefox/Safari)**: "Browse…" row is **absent entirely** (not shown disabled) — only "App
  storage" renders, per Story 2.3.1's acceptance criteria.

### Interaction flow

1. Caller (new-graph dialog, `Step2RepoPath`, or move-storage flow) opens the picker with a title
   appropriate to context (e.g. "Choose where to keep this graph" for creation, "Choose a new
   location" for a move).
2. User taps a row:
   - **App storage** → row shows filled radio, `Next` enables. No native OS dialog appears.
   - **Browse…** → the platform-native folder picker launches immediately (SAF intent or File
     System Access API). On success, the picker returns to the dialog with the "Browse…" row now
     showing the picked folder's display name as its subtitle and a filled radio. On user cancel of
     the native picker, the row reverts to unselected — no error, no dialog.
   - **A recent entry** → immediately selected (radio fills), no native dialog re-launches (this is
     the point of "recent" — reuse a prior grant without re-prompting for SAF/File System Access
     permission where the platform still honors it).
3. User taps `Next`/`Confirm` → picker returns the resolved `StorageLocation` (`AppOwned`,
   `SafFolder`, or `HostFolder`) to the caller and closes.
4. User taps `Cancel` or presses Escape/system-back → picker closes, caller's prior state
   (unopened dialog, or unmodified wizard step) is unchanged.

### Error and edge cases

| Case | UI response |
|---|---|
| Native folder picker (SAF/File System Access) is cancelled by the OS/user | "Browse…" row reverts to unselected; no toast, no dialog — this is a normal cancel, not an error. |
| A previously-granted "recent" folder's permission was since revoked | Selecting it re-triggers the native grant flow transparently (Android: SAF re-request; Web: `requestPermission()` on the retained handle) rather than silently failing; if the re-grant is declined, the row reverts to unselected and a one-line inline note appears under that row: "Access no longer available — pick again." |
| Browser lacks File System Access API entirely | "Browse…" row is omitted (see above), not shown disabled with an explanatory tooltip — an absent affordance beats a dead one per `research/ux.md` §1's cross-cutting pattern. |

### UX acceptance criteria

- AC1: A user can select "App storage" and confirm in **2 taps** (row, then Next) from any of the
  three call sites.
- AC2: No row shows a selected state on first render — verified by `UnifiedLocationPickerTest`
  (Task 2.1.1e) asserting the default (no-selection) state.
- AC3: `Next`/`Confirm` is disabled until exactly one row is selected; there is no keyboard/mouse
  path that leaves the picker in a "confirmed with nothing selected" state.
- AC4: Every row is keyboard-navigable (Tab reaches each row in visual order) and activatable via
  Enter/Space (`Role.Button` + `.clickable`, matching `GitSetupScreen.kt`'s `Step1CloneMode`
  convention).
- AC5: A screen reader announces each row's full label + subtitle text as one unit, never a bare
  icon name (per `research/ux.md` §3's existing-convention citation).
- AC6: Text/background contrast on both the row label and the subtitle meets WCAG AA (≥4.5:1) in
  both light and dark theme.
- AC7: No dead end — `Cancel` and system-back always return to the caller with no partial state
  change, at every point before the final `Next` tap.

---

## Surface 2: New-graph creation dialog with picker embedded

Extends `AddGraphDialog` (`App.kt:2084`, shared by Android and Web per Story 2.2.1/2.3.1's file
note) to render `UnifiedLocationPicker` in place of the current folder-only/OPFS-fallback control.

### Wireframe

```
┌─ New graph ─────────────────────────────────────────┐
│  Name: [___________________________]                │
│                                                       │
│  [ UnifiedLocationPicker embedded here — Surface 1 ] │
│                                                       │
│                              [Cancel]   [Create] ⛔   │
└───────────────────────────────────────────────────────┘
        │  (if App storage + plain graph selected)
        ▼
┌─ Before you continue ────────────────────────────────┐
│  "My Notes" will be kept inside SteleKit only — not   │
│  visible in your device's file manager, and           │
│  permanently deleted if you uninstall the app. There  │
│  is no automatic backup.                              │
│                                                       │
│  [Export as .zip]           (Android only, Surface 11)│
│                                                       │
│                       [Go back]      [Create anyway] │
└───────────────────────────────────────────────────────┘
```

### Interaction flow

1. User taps "New graph," types a name, and interacts with the embedded picker (Surface 1's flow).
2. `Create` stays disabled until both the name is non-blank and a location is selected (mirrors this
   repo's existing `AddGraphDialog` confirm-gating pattern, `App.kt:2104-2107`).
3. If the resolved location is `AppOwned` **and** this is a plain (non-git) graph, tapping `Create`
   is intercepted by the mandatory warning dialog (Surface 11) before graph creation proceeds — see
   ADR-003. Any other combination ( `SafFolder`/`HostFolder` selected, or `AppOwned` for a
   git-cloned graph reached via the clone wizard instead) skips the warning.
4. On confirm, the graph is created with zero SAF-intent/File-System-Access-prompt launched when
   `AppOwned` was chosen (Story 2.2.1/2.3.1's acceptance criteria).

### Error and edge cases

| Case | UI response |
|---|---|
| Graph name collides with an existing graph | Existing `AddGraphDialog` validation behavior is unchanged — this feature does not alter name-uniqueness handling. |
| User backs out of the plain-graph warning ("Go back") | Returns to the New-graph dialog with the picker's selection untouched — no graph is created, no partial state. |
| Firefox/Safari user reaches this dialog | Only "App storage" is ever selectable (no "Browse…" row) — the plain-graph warning always applies when a plain graph is created here, since `AppOwned` is the only option. |

### UX acceptance criteria

- AC8: A user can create a plain graph in app storage in **≤4 steps** on Chromium/Android
  (name → App storage → Create → acknowledge warning) — no SAF/File-System-Access prompt appears
  in that path.
- AC9: The warning dialog's body text matches ADR-003's copy exactly per platform ("...permanently
  deleted if you uninstall the app. There is no automatic backup." on Android; "...lost if you
  clear site data." on Web) — testable by string-equality assertion in `AddGraphAppOwnedTest`
  (Task 2.2.1f).
- AC10: The warning dialog is not dismissable by tapping outside it or pressing Escape without an
  explicit choice — matches `DiskConflictDialog`'s "require explicit choice" convention
  (`onDismissRequest = { /* require explicit choice */ }`), since silently discarding the warning
  must not silently proceed with graph creation either.
- AC11: No dead end — "Go back" always returns to an editable New-graph dialog state.

---

## Surface 3: `Step2RepoPath` with picker embedded

Replaces `Step2RepoPath`'s bare `OutlinedTextField` + browse-icon (`GitSetupScreen.kt:779-795`)
with `UnifiedLocationPicker`, on both platforms.

### Wireframe

```
┌─ Repository path ─────────────────────────────────────┐
│  Remote URL: [https://github.com/example/notes.git]   │
│                                                         │
│  Pick the folder that directly contains .git...        │
│                                                         │
│  [ UnifiedLocationPicker embedded here — Surface 1 ]   │
│    ○ App storage                                       │
│      Kept inside SteleKit only — not visible in your   │
│      device's file manager, and removed if you         │
│      uninstall the app.                                │
│    ○ Browse…                                           │
│                                                         │
│  Wiki subdirectory: [___________] [📁 browse subdirs]  │
│                                                         │
│                         [Back]          [Next] ⛔       │
└─────────────────────────────────────────────────────────┘
```

The wiki-subdirectory field and its existing `WikiSubdirBrowserDialog` browse button
(`GitSetupScreen.kt:824-840`) are unchanged — they operate relative to whatever `repoRoot` the
picker resolved, exactly as today. Only the repo-root control itself changes from a text field to
the shared picker.

### Interaction flow

1. User reaches Step 2 having chosen "Clone a remote repository" (or "Use existing clone") in
   Step 1 — unchanged (`Step1CloneMode`, `GitSetupScreen.kt:708-739`).
2. User selects a location via the embedded picker exactly as Surface 1 describes.
3. Selecting "App storage" clones directly into the promoted `GitShadowWorktree` directory
   (Android, Task 2.2.2a) or an OPFS path (Web, Task 2.3.2a) — no SAF intent / no
   `showDirectoryPicker()` call, matching Story 2.2.2/2.3.2's acceptance criteria.
4. Selecting "Browse…" preserves today's exact SAF-folder or real-folder clone behavior, byte for
   byte (Story 2.2.2's second acceptance criterion) — including the existing
   `existingRepoNeedsAllFilesAccess`/`detectionUnavailable` inline error copy
   (`GitSetupScreen.kt:797-822`), which is unmodified and still renders below the picker when
   relevant.
5. `Next` is gated on a location being resolved, in addition to any existing `cloneUrl`/`repoRoot`
   validation.

### Error and edge cases

| Case | UI response |
|---|---|
| SAF-picked folder has a `.git` SteleKit can't see as a real repo (needs all-files access) | Unchanged existing inline error (`GitSetupScreen.kt:797-811`) still renders below the picker — this feature does not touch that branch. |
| No `.git` detected in the picked folder | Unchanged existing `detectionUnavailable` copy (`GitSetupScreen.kt:812-822`) still renders. |
| User picks "App storage" for a git clone into a plain (non-git-history-bearing) fresh repo | No plain-graph warning applies here — the mandatory warning (Surface 11) is scoped to non-git graphs only, per ADR-003; a git-cloned repo already has its remote as an implicit backup. |

### UX acceptance criteria

- AC12: A user can clone into app storage in **≤3 steps** from Step 2 (App storage row → Next →
  confirm clone) with zero SAF/File-System-Access prompts.
- AC13: Choosing "Browse…" produces UI and clone behavior indistinguishable from the pre-feature
  wizard — verified by `AndroidGitRepositoryAppOwnedCloneTest`/wasmJs equivalent (Task 2.2.2d,
  2.3.2c) asserting no divergent code path is exercised.
- AC14: The wiki-subdirectory browse control continues to function against whatever root the
  picker resolved (`AppOwned`, `SafFolder`, or `HostFolder`), with no regression in its own
  accessibility (icon `contentDescription`s unchanged, per `research/ux.md` §3).

---

## Surface 4: "Move storage location…" entry point

Replaces the freely-editable path `OutlinedTextField` in `Sidebar.kt`'s "Edit Graph" dialog
(`Sidebar.kt:600-606`) with a guided action button (Story 3.2.2 Android, Story 3.3.3 Web via
`FolderSyncSettings`).

### Wireframe

```
┌─ Edit Graph ──────────────────────────────────────────┐
│  Name: [My Notes________________]                     │
│                                                         │
│  Storage: App storage                                  │
│  [ Move storage location… ]                            │
│                                                         │
│  Linked local folder: Documents/notes                  │
│  [ Change linked folder… ]        (if supportsHost...) │
│                                                         │
│                             [Cancel]         [Save]    │
└─────────────────────────────────────────────────────────┘
```

The current graph's resolved `StorageLocation` is shown as a plain read-only line ("Storage: App
storage" / "Storage: Documents/notes" / "Storage: SAF folder") above the button — this is new
copy, since today's dialog has no location-summary line at all (only the raw editable path). This
directly serves ADR-001's "the picker/move UI must not imply the DB moves when it doesn't" caution:
the summary line only ever describes the **markdown** location, never the DB.

### Interaction flow

1. User opens "Edit Graph" from the sidebar (unchanged entry point).
2. Taps "Move storage location…" → opens `UnifiedLocationPicker` (Surface 1), scoped to exclude the
   graph's current location as a selectable destination (moving to the same place is a no-op, not
   offered).
3. On selecting a destination, proceeds directly to Surface 5 (Relocate-vs-Link choice) — the Edit
   Graph dialog itself closes/steps aside for the guided flow rather than layering a second modal
   on top of it.
4. If the user cancels anywhere in the guided flow (picker, choice dialog, confirmation), control
   returns to the Edit Graph dialog unchanged — no path or name edit is lost mid-flow, since name
   editing and storage moves are independent actions that don't share unsaved state.

### Error and edge cases

| Case | UI response |
|---|---|
| Graph is currently mid-move (`MoveInProgressFlag` set) — e.g. dialog re-opened while a previous move is still running | "Move storage location…" button is disabled with inline text "A move is already in progress." — never a duplicate move launched. |
| Plain Android graph, user picks "Link" in the subsequent choice dialog | Handled in Surface 5 (Link disabled for plain Android graphs per ADR-003). |

### UX acceptance criteria

- AC15: The current storage location is legible at a glance (one line, no jargon — "App storage" /
  a folder display name) before the user commits to moving anything.
- AC16: The picker never offers the graph's current location as a destination (no confusing
  "move to where it already is" affordance).
- AC17: No dead end — cancelling at any step of the guided flow returns to a normal, editable Edit
  Graph dialog.

---

## Surface 5: Relocate-vs-Link choice dialog

New `StorageMoveChoiceDialog`, shaped like `DiskConflictDialog.kt`'s stacked-full-width-button
pattern (full file shown above — `confirmButton = { Column { Button(...); OutlinedButton(...) } }`).

### Wireframe

```
┌─ How should "My Notes" move? ─────────────────────────┐
│                                                         │
│  From: A folder on your device (Documents/notes)       │
│  To:   App storage                                     │
│                                                         │
│ ┌─────────────────────────────────────────────────┐   │
│ │  Relocate                                        │   │
│ │  Move and stop using the old location. The old   │   │
│ │  copy stays until you confirm it's safe to       │   │
│ │  remove.                                         │   │
│ └─────────────────────────────────────────────────┘   │
│ ┌─────────────────────────────────────────────────┐   │
│ │  Link                                            │   │
│ │  Keep both copies in sync. Nothing is ever       │   │
│ │  removed.                                        │   │
│ └─────────────────────────────────────────────────┘   │
│                                                         │
│                                       [Cancel]         │
└─────────────────────────────────────────────────────────┘
```

Both option cards render at equal visual weight (matching `DiskConflictDialog`'s
`Modifier.fillMaxWidth()` stacked buttons) — neither is a primary/filled `Button` over an
`OutlinedButton`; both are equally weighted large tap targets, since `research/ux.md` §1
(Android Files app precedent) and requirements.md both call for these as distinct, non-nested
operations, not a default-plus-alternative pair.

### Interaction flow

1. Opened after Surface 4's picker resolves a destination.
2. User taps "Relocate" or "Link" → proceeds to Surface 6 (confirmation), carrying the chosen
   `StorageMoveOperation` variant.
3. `Cancel` or Escape → returns to Surface 4's Edit Graph dialog, no operation started.

### Error and edge cases

| Case | UI response |
|---|---|
| Plain (non-git) Android graph | "Link" card is **hidden** entirely (not shown disabled), with a one-line note beneath the remaining "Relocate" card: "Continuous sync isn't available yet for graphs without git." — per Story 4.2.1's acceptance criterion (never a broken/no-op action). |
| Web graph, either direction | Both cards always available — `connectHostDirectory`/`unlinkHostDirectory` already generalize (Epic 4.1). |
| Destination is `AppOwned` and source is `HostFolder` (i.e., un-linking) | "Link" card's subtitle adapts to "Stop keeping both in sync — this graph becomes App-storage-only." (an unlink framed as the Link card's own reverse action, not a separate third card) when the source is already a Link. |

### UX acceptance criteria

- AC18: Both options render with equal visual weight — verified by a screenshot/`jvmTest` diff
  confirming neither card uses a filled/primary `Button` style over the other.
- AC19: Each card's one-line consequence text is present and non-truncated at 400px width (phone
  minimum).
- AC20: Screen reader announces each card as a single unit (label + consequence text), consistent
  with Surface 1's row-announcement convention.
- AC21: No dead end — Cancel/Escape always returns to Surface 4 with no move started.

---

## Surface 6: Move confirmation dialog

New `StorageMoveConfirmDialog`, modeled on `Sidebar.kt`'s graph-removal `AlertDialog`
(`Sidebar.kt:550-570`) — plain `AlertDialog`, `TextButton` actions, `onDismissRequest` closes
without side effects.

### Wireframe

```
┌─ Move "My Notes"? ─────────────────────────────────────┐
│                                                         │
│  Move "My Notes" from a folder on your device           │
│  (Documents/notes) to App storage?                      │
│                                                         │
│  Your files stay where they are until the copy is       │
│  verified. Nothing is deleted during this step.         │
│                                                         │
│                          [Cancel] ●        [Move]      │
│                          ^^^^^^^^                       │
│                    (default focus — not "Move")         │
└─────────────────────────────────────────────────────────┘
```

### Interaction flow

1. Opened immediately after Surface 5's choice.
2. Default keyboard focus lands on `Cancel`, never on `Move` — per `research/ux.md` §3's W3C
   WAI-ARIA-derived guidance (no existing in-repo precedent for a destructive-adjacent default-
   focus rule, so external guidance fills the gap, per that section).
3. `Move` starts Surface 7 (progress). `Cancel` or Escape closes with zero side effects — matches
   `Sidebar.kt`'s `graphToRemove = null` `onDismissRequest` convention exactly.

### Error and edge cases

| Case | UI response |
|---|---|
| User double-taps "Move" rapidly | Button disables itself the instant the coordinator starts (transitions immediately to Surface 7); a second tap cannot start a duplicate move. |
| `MoveInProgressFlag` somehow already set for this graph (race) | "Move" is disabled with inline text "A move is already in progress for this graph." rather than silently queuing a second coordinator run. |

### UX acceptance criteria

- AC22: The dialog body names the exact source and destination in human-readable form (never a
  raw `content://` URI or OPFS path) — testable by string-content assertion in
  `StorageMoveConfirmDialogTest` (Task 3.4.2b).
- AC23: Default focus is on `Cancel`, verified by the same test asserting focus state on render.
- AC24: Escape dismisses without starting a move — same test.
- AC25: The reassurance sentence ("Your files stay where they are until the copy is verified.") is
  present verbatim — this is both the safety-requirement text and the accessible description read
  by screen readers on dialog open (per `research/ux.md` §3, the two requirements collapse into one
  sentence).
- AC26: No dead end — Cancel/Escape always returns to Surface 5 or Surface 4 with the graph
  completely unchanged.

---

## Surface 7: Copy/verify progress

New `StorageMoveProgressDialog`, shaped like `FolderSyncSettings`'s `ReconciliationUiState` →
`FolderSyncReconciliationProgress` wiring (`FolderSyncSettings.kt:44-92`): a non-dismissable
in-progress screen that a `scope.launch` coroutine drives through terminal states.

### Wireframe

```
┌─ Moving "My Notes" ────────────────────────────────────┐
│                                                         │
│  Waiting for in-flight changes to finish…               │  ← Quiescing
│  [ ▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░░ ]  indeterminate         │
│                                                         │
│                    — or, once copying starts —          │
│                                                         │
│  Copying files… 4,200 of 8,030                          │  ← Copying(count,total)
│  [ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░ ]  52%                   │
│                                                         │
│                    — or, once copy finishes —           │
│                                                         │
│  Verifying copied files…                                │  ← Verifying
│  [ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░ ]  indeterminate          │
│                                                         │
│                                          [Cancel]       │
└─────────────────────────────────────────────────────────┘
```

`StorageMoveUiState` sequence: `Quiescing → Copying(count, total) → Verifying → Summary | Failed |
ReopenFailed` — exactly the shape `implementation/plan.md` Story 3.4.3 specifies, itself modeled on
`ReconciliationUiState`. `ReopenFailed` is a terminal state distinct from `Failed`, reached only when
the coordinator's post-move driver reopen (Story 3.1.5, step 6) itself fails to confirm — it is
never rendered by this screen; see Surface 8b for its own dedicated treatment, since it must not
share `Failed`'s Retry affordance or "your files were not touched" reassurance (Story 3.4.3, Task
3.4.3c). `Copying` renders a determinate progress bar with a live count (not a bare spinner) per
`research/ux.md` §1's Google-Drive-precedent recommendation — large graphs (8,000+ pages) must show
real progress, not an indefinite wait.

### Interaction flow

1. Dialog opens the instant Surface 6's "Move" is tapped; state starts at `Quiescing`.
2. As `GraphRelocationCoordinator.relocate()` emits each state, the dialog updates in place (same
   dialog instance, not a new one per state — matches `FolderSyncReconciliationProgress`'s
   single-composable-swapping-content pattern).
3. `Cancel` is available during `Quiescing`, `Copying`, **and** `Verifying` — at every point before a
   terminal state (`Summary`/`Failed`/`ReopenFailed`) is reached. Tapping it stops the coordinator,
   discards the destination copy (the staging directory, or — very briefly, between the staging→final
   atomic move and the driver reopen — the not-yet-repointed final-destination copy), reopens the
   driver at the original location using the exact `switchGraph(graphId, forceReinit = true)` +
   `awaitPendingMigration()` sequence `implementation/plan.md`'s Story 3.1.5 already uses for every
   other exit from the closed-driver region, and returns to Surface 4 with the source completely
   untouched and the graph's registered location unchanged.
4. Cancelling during `Verifying` is treated the same as cancelling during `Copying` for cleanup
   purposes: verification runs strictly after the copy has already completed and strictly before the
   destination is trusted, so "cancel now" and "verification would have failed" both resolve to the
   same safe outcome — abandon the destination, keep the original. This is not a new cancellation
   path; it reuses the coordinator's existing closed-driver-region cancellation handling (Story 3.1.5,
   Task 3.1.5l), since `Verifying` is rendered from inside the same `BulkCopyVerifier.copyAndVerify`
   call `Copying` is. Disabling Cancel here would assume verification is "a few seconds at most," but
   Task 3.1.1f content-hashes every markdown file **and** every git object/pack file, and
   `requirements.md`'s Rabbit Holes section flags exactly this as a real multi-minute concern at
   8,000+-page graph scale — a non-cancelable dialog spanning that phase is a dead end, not a
   simplicity choice.
5. On success, the dialog transitions to Surface 9 (cleanup prompt / Summary). On an ordinary
   copy/verification/quiesce failure, it transitions to Surface 8 (Failed). On the rarer case where
   the post-move driver reopen itself cannot be confirmed, it transitions to Surface 8b (Reopen
   Failed) instead of Surface 8 — see Surface 8b for why these are kept visually and textually
   distinct.

### Error and edge cases

See Surface 8 (Failed) and the condensed Surfaces 12/13 below for space/interruption edge cases —
this screen's own responsibility is only to render whichever `StorageMoveUiState` it's given.

### UX acceptance criteria

- AC27: `Copying` always shows a determinate count ("N of M"), never an indeterminate spinner, once
  the total file count is known — testable via `StorageMoveProgressDialogTest`.
- AC28: `Quiescing` and `Verifying` may show an indeterminate spinner (neither has a per-file count
  wired into `StorageMoveUiState` the way `Copying` does) but must show descriptive text naming the
  current step, never a bare spinner with no label. `Verifying`'s duration is **not** assumed to be
  short — content-hashing every markdown file and every `.git` object/pack file (Task 3.1.1f) can run
  for minutes on an 8,000+-page graph with a large git history (`requirements.md`'s Rabbit Holes) — so
  an indeterminate spinner here is a display-detail choice, not license to also disable `Cancel` (see
  AC48).
- AC29: `Cancel` during `Quiescing`, `Copying`, or `Verifying` always returns the graph to its
  pre-move state with no data loss — verified by `GraphRelocationCoordinatorCancellationTest`'s
  (Task 3.1.5l) cancel-path assertions, which cover a mid-`Copying` and a mid-`Verifying`
  cancellation point explicitly.
- AC30: The dialog cannot be dismissed by tapping outside it or pressing system-back while a move
  is in progress, in any of `Quiescing`/`Copying`/`Verifying` (Escape maps to the explicit Cancel
  action, not a silent dismiss) — prevents an accidental backgrounding from orphaning the coordinator
  with no UI attached.
- AC48: `Cancel` is enabled — not merely present but actually clickable — during `Verifying`, with the
  same visual treatment as during `Copying`/`Quiescing`; no build of this screen renders `Verifying`
  with a disabled or absent Cancel control — verified by `StorageMoveProgressDialogTest`'s `Verifying`
  -state assertion (Task 3.4.3d).

---

## Surface 8: Verification-failed error state

Terminal `StorageMoveUiState.Failed` rendering — the direct structural analog to
`ReconciliationUiState.Failed`'s `onRetry` callback (`FolderSyncSettings.kt:77,87`).

### Wireframe

```
┌─ Move couldn't be verified ────────────────────────────┐
│                                                         │
│  ⚠  Something didn't match after copying.               │
│                                                         │
│  Reason: hash mismatch on pages/foo.md                  │
│                                                         │
│  Your original files were not touched or deleted.       │
│                                                         │
│                              [Cancel]      [Retry]     │
└─────────────────────────────────────────────────────────┘
```

Per requirements.md's Risk Control section and `research/ux.md` §4a: **only** "Retry" and "Cancel"
are ever offered here — there is no "delete anyway" / "use it anyway" affordance, since offering
one would defeat the entire copy-verify-confirm safety net.

### Interaction flow

1. Reached automatically when `BulkCopyVerifier` returns `DomainError.StorageError` of any subtype
   (`VerificationFailed`, `PartialCopyDetected`, `DestinationNotWritable`, `InsufficientSpace`,
   `QuiesceTimedOut`) — the `reason` text is subtype-specific but the two available actions never
   change.
2. `Retry` re-invokes the coordinator from the top (re-quiesce → re-copy → re-verify) — it does not
   attempt to resume a partial copy, since the failed staging directory is discarded first
   (consistent with Surface 13's "discard partial destination copy" rule).
3. `Cancel` discards the staging directory and returns to Surface 4, source completely untouched.

### Error and edge cases

| Case | UI response |
|---|---|
| Retry fails again with the same reason | Same dialog re-renders with the same reason text — no retry-count limit is imposed (a user may retry as many times as they want; each attempt is independent and safe by construction). |
| Retry fails with a *different* reason (e.g. first attempt: hash mismatch; second: insufficient space) | Reason text updates to the new failure; the two-button affordance is unchanged. |

### UX acceptance criteria

- AC31: The only two interactive elements on this screen are "Retry" and "Cancel" — verified by
  `StorageMoveProgressDialogTest`'s Failed-state assertion (Task 3.4.3b) enumerating all buttons
  present.
- AC32: The reassurance line ("Your original files were not touched or deleted.") is present
  verbatim on every `Failed` render, regardless of which `DomainError.StorageError` subtype caused
  it.
- AC33: No dead end — both Retry and Cancel lead to a recoverable state (retry the move, or return
  to Surface 4 with nothing changed); there is no failure state this screen can reach that requires
  closing/force-quitting the app to escape.

---

## Surface 8b: Reopen Failed

Terminal `StorageMoveUiState.ReopenFailed` rendering (Story 3.1.5, Task 3.4.3c) — a **sibling** of
`Failed` (Surface 8), not a variant of it. Reached only when the coordinator's post-move step —
`GraphManager.switchGraph(graphId, forceReinit = true)` followed by `awaitPendingMigration()` — runs
after the closed-driver region (quiesce → close driver → copy/verify/move) and `awaitPendingMigration()`
itself returns `null`: the scheduled reopen failed. This can happen after an ordinary copy/verify
failure (the driver was already going to need reopening) **or** after an otherwise-successful
copy+verify+repoint, which is the more consequential case — the files may already be at the new
destination, but the app cannot currently open the graph to confirm it.

**This state must never reuse Surface 8's template.** Surface 8's "Retry" implies the coordinator
can restart `relocate()` from a known-good, closed-then-reopened driver — not established here.
Surface 8's "Your original files were not touched or deleted." reassurance implies the coordinator
can vouch for data state — also not established here, since the one thing that just failed is the
app's own ability to confirm the graph's current state. Rendering either affordance on this screen
would assert a guarantee the coordinator cannot back up.

### Wireframe

```
┌─ "My Notes" couldn't be reopened ──────────────────────┐
│                                                          │
│  ⚠  SteleKit can't confirm this graph's current state.  │
│                                                          │
│  The move may have completed, but reopening it just     │
│  failed. We can't tell you right now whether your files │
│  are at the old location, the new one, or both.          │
│                                                          │
│  Nothing else was deleted by this operation — the old    │
│  location (Documents/notes) was never removed. If you    │
│  need your files immediately, check there first using    │
│  your device's file manager.                             │
│                                                          │
│  There's no automatic fix for this yet. Returning to     │
│  your graph list and trying to open "My Notes" again      │
│  may succeed even though this attempt didn't.             │
│                                                          │
│                                              [OK]        │
└──────────────────────────────────────────────────────────┘
```

No "Retry" button — retrying `relocate()` presumes a driver state the coordinator cannot establish.
The single "OK" acknowledgment returns to the graph list, mirroring how this app already surfaces an
unavailable graph elsewhere (`PermissionRecoveryScreen`'s "can't access your notes" full-screen
pattern) rather than inventing a new severity tier or a bespoke retry loop plan.md does not define.

### Interaction flow

1. Reached automatically when `GraphRelocationCoordinator` emits `ReopenFailed` — i.e.
   `awaitPendingMigration()` returned `null` after the coordinator's reopen-and-confirm step
   (Story 3.1.5, step 6), regardless of whether the preceding copy+verify was itself successful or
   failed.
2. The body text names the one location this design can actually vouch for — the original source,
   which this feature's copy-verify-confirm-before-delete invariant (`requirements.md`'s Risk
   Control) guarantees was never deleted by this operation, since Surface 9's "Delete old copy" step
   (the only point in the entire flow that ever removes source data) is never reached on this path.
   It deliberately does **not** claim the destination is intact, and does not claim the graph overall
   is "safe" — only that this specific, known fact holds.
3. `OK` is the only interactive element. Tapping it dismisses the dialog and returns to the graph
   list (not back into the move wizard, and not a graph-open attempt automatically triggered by this
   screen) — the user re-initiates opening the graph themselves from there, going through the
   app's ordinary `openGraph()` path rather than any relocate-specific one.
4. There is no in-app "restart" action, because this app has no supported programmatic self-restart
   — the copy names the only thing a user can actually do (leave and reopen the graph, or restart
   the app process manually) without implying a button exists for it.

### Error and edge cases

| Case | UI response |
|---|---|
| User taps "OK" then immediately reopens "My Notes" from the graph list, and it opens fine | No special handling needed — the ordinary `openGraph()` path succeeded where the coordinator's forced reopen didn't; the graph behaves normally from here, per this app's existing open-graph flow. |
| User taps "OK" then reopens "My Notes" and it still fails to open | The app's existing graph-open failure handling takes over (e.g. `PermissionRecoveryScreen` if the cause is a lost folder grant, or another existing error path) — this screen's job ends at "OK"; it does not attempt to diagnose further failure modes itself. |
| The underlying failure was a copy/verification failure, not a happy-path repoint (i.e., ReopenFailed piggybacked on an existing `DomainError.StorageError` cause) | The body text stays generic (state-unknown, source-preserved) rather than surfacing the original cause's technical detail — `ReopenFailed` carries that cause internally (Story 3.1.5) for the log line (Surface 14), but this screen does not add a second technical reason line, to avoid implying a level of diagnosis ("we know exactly what went wrong") this state's whole premise contradicts. |

### UX acceptance criteria

- AC45: `ReopenFailed` never renders a "Retry" button — verified by `StorageMoveProgressDialogTest`'s
  `ReopenFailed`-state assertion (Task 3.4.3c) enumerating all buttons present (only "OK").
- AC46: `ReopenFailed`'s copy never includes Surface 8's verbatim reassurance line ("Your original
  files were not touched or deleted.") or any equivalent claim about the *destination's* or the
  *graph's overall* state — the same test asserts the reassurance string is absent from this
  screen's rendered text, while still asserting the narrower, load-bearing "old location was not
  removed" statement about the *source* is present (the one claim this design can actually back up).
- AC47: "OK" is reachable via keyboard (Enter/Space) and is the only focus stop on this screen; no
  dead end — tapping it always returns to the graph list, never to a state with no visible action.

---

## Surface 9: Post-move cleanup prompt / undo window

Rendered as the `Summary` terminal state, once verification passes and (for Relocate only) the
repoint has completed. Resolves requirements.md's Open Question on undo-window UX per
`implementation/plan.md`'s Unresolved-Questions note: **copy-verify-confirm before any deletion,
with an explicit "keep old copy" vs. "delete old copy" choice — never a silent time-boxed
auto-delete.**

### Wireframe

```
┌─ Move complete ─────────────────────────────────────────┐
│                                                          │
│  ✓  "My Notes" is now in App storage.                    │
│     8,030 files verified.                                │
│                                                          │
│  The old copy is still in Documents/notes.               │
│  You can delete it now, or keep it as a backup —          │
│  it won't be touched until you choose.                    │
│                                                          │
│                    [Keep old copy]  [Delete old copy]    │
└──────────────────────────────────────────────────────────┘
```

For **Link** operations, this screen has no delete option at all (nothing is ever removed) — it
simply confirms: `"My Notes" is now synced between App storage and Documents/notes.` with a single
`[Done]` button.

### Interaction flow

1. Reached after `GraphRelocationCoordinator` emits `Summary` (Relocate) with the repoint already
   applied — the graph's `storage_locations` row and app navigation already point at the new
   location by this point.
2. `Keep old copy` → dialog closes; the source location remains physically intact indefinitely
   (no auto-delete, no timer) until the user later reopens Surface 4 and performs cleanup manually
   from there, or never does.
3. `Delete old copy` → triggers the actual source deletion (Android: SAF folder delete +
   `releasePersistableUriPermission` per Epic 5.2; source is otherwise just removed) — this is the
   **only** point in the entire flow where source data is destroyed, and it happens strictly after
   verification succeeded and the user explicitly chose it here, never automatically.
4. Either choice logs `MoveCompleted` (Epic 5.1).

### Error and edge cases

| Case | UI response |
|---|---|
| User taps "Delete old copy" but the source folder is no longer accessible (e.g. SAF grant already revoked) | Dialog shows a brief inline note "Couldn't remove the old copy — you may need to delete it manually from your device's Files app." and still closes as complete; the **move itself** already succeeded and is not rolled back — a failed cleanup is not a failed move. |
| User closes the app / navigates away before choosing | Old copy is left in place (matches "Keep old copy" behavior) — an unanswered prompt defaults to the safe (non-destructive) outcome, never to delayed auto-delete. |

### UX acceptance criteria

- AC34: There is no time-boxed or silent auto-deletion of the source location under any
  circumstance — verified by `GraphRelocationCoordinatorTest` asserting the source is untouched
  after `Summary` unless "Delete old copy" is explicitly invoked.
- AC35: Both choices ("Keep old copy," "Delete old copy") are equally reachable with one tap; no
  nested confirmation-of-confirmation is required for "Keep" (the safe path is never harder to
  reach than the destructive one).
- AC36: A failed cleanup-delete never implies or triggers a rollback of the already-completed move
  — the summary screen still reports the move itself as successful.
- AC37: No dead end — dismissing this screen without a choice is equivalent to "Keep old copy,"
  never an error state.

---

## Surface 10: Link-broken / permission-revoked status indicator

**Reuses `FolderSyncStatusBadge` unchanged** (`FolderSyncStatusBadge.kt`, full file) — per
`research/ux.md` §4b and Story 4.1.3's explicit "no new component" acceptance criterion. This
section documents how a Link created via this feature's flow maps onto that existing component,
not a new design.

### Wireframe (existing component, for reference)

```
Sidebar footer / graph row:
  📂  Synced to Documents            (Granted, 0 pending)
  📂  3 changes syncing to Documents (Granted, pending > 0, not stuck)
  📂  3 changes not yet synced to folder   ⚠ (Granted, pending > 0, stuck — clickable)
  🚫  Folder not found — Reconnect   ⚠ (Disconnected — clickable)
  📁  Folder access declined — Grant access ⚠ (Denied — clickable)
```

### Interaction flow

1. A Link established via Surface 5/9 (Web `connectHostDirectory`, or Android's existing
   `GitShadowWorktree` write-back for git-cloned graphs) is represented by the same
   `HostAccessState`/`SyncStatusBadge` machinery every other linked folder already uses — no new
   state machine, per Story 4.1.3.
2. If the browser revokes the File System Access permission (or, on Android, the SAF grant is
   revoked externally), the badge transitions to `Disconnected`/`Denied` exactly as it does for a
   pre-existing `FolderSyncSettings` link — tapping the badge re-triggers the platform's native
   permission flow (Android: SAF re-request; Web: `requestPermission()`), never a bespoke in-app
   dialog first (per `research/ux.md` §3's "no modal-before-the-modal" citation).
3. The badge's status text carries `liveRegion = Polite` semantics, so a screen-reader user is
   told about the transition without an interrupting alert (`FolderSyncStatusBadge.kt:227`,
   unchanged).

### Error and edge cases

Fully covered by the existing component's documented behavior — see
`FolderSyncStatusBadge.kt:44-133`'s doc comments for the complete state → copy mapping. This
feature adds no new states to `HostAccessState`; Android SAF-grant revocation for a Link maps onto
the same `Disconnected`/`Denied` enum values used by Web today (per `research/ux.md` §4b's
recommendation, now confirmed adopted rather than re-litigated).

### UX acceptance criteria

- AC38: A Link created via this feature's flow renders through the exact same `FolderSyncStatusBadge`
  component and copy as a pre-existing `FolderSyncSettings` link — verified by
  `FolderSyncStatusBadgeLinkReuseTest` (Task 4.1.3a) confirming no divergent rendering path.
- AC39: The reconnect/grant-access tap target remains keyboard-reachable and screen-reader
  announced exactly as today (no regression from wiring a second creation path into the same
  badge).
- AC40: No dead end — every clickable badge state leads to a native permission re-prompt, never a
  state with no recovery action.

---

## Surface 11: Plain-graph `AppOwned` warning (+ zip export, both platforms)

Shared dialog shell (`PlainGraphAppOwnedWarningDialog`) invoked from Surface 2 (new-graph creation)
per ADR-003 and its 2026-09-12 Amendment.

### Wireframe

```
┌─ Before you continue ──────────────────────────────────┐
│                                                          │
│  "My Notes" will be kept inside SteleKit only — not      │
│  visible in your device's file manager, and              │
│  permanently deleted if you uninstall the app. There     │
│  is no automatic backup.                                 │
│                                                          │
│  [ Export as .zip ]                                      │
│                                                          │
│                       [Go back]        [Create anyway]  │
└──────────────────────────────────────────────────────────┘
```

Web renders the same shell with the alternate copy ("...lost if you clear site data.") and its own
"Export as .zip" button (per ADR-003's Amendment, which reversed the original decision to give Web
no export affordance — a Web-only user who never reaches a shipped relocate/link phase must still
have a way to get their files out). The two platforms' export buttons look and behave identically
from this dialog's perspective even though the underlying writer differs (Android:
`java.util.zip.ZipOutputStream`; Web: a hand-rolled stored-only ZIP writer — no compression, no new
dependency, per plan.md Task 2.3.3c).

### Interaction flow

1. Triggered the moment `AppOwned` + a plain graph is confirmed in Surface 2, before graph creation
   actually runs.
2. `Export as .zip` (both platforms) is available **before** committing — it operates on whatever
   markdown content already exists to export (for a brand-new empty graph, this produces a
   near-empty archive; the button's main value is for the "move an existing plain graph to app
   storage" path via Surface 4, where there's real content to protect). Tapping it triggers a
   platform-appropriate download: Android opens the existing share/save-file mechanism
   (`PlatformShareProvider.kt`), showing a brief system share-sheet confirmation; Web triggers a
   direct browser file download via the same file's `triggerBlobDownload`-style object-URL
   mechanism, extended to accept binary content (plan.md Task 2.3.3d) — no custom in-app toast on
   either platform.
3. `Create anyway` proceeds with graph creation as `AppOwned`.
4. `Go back` returns to Surface 2 with the picker's selection intact, allowing the user to instead
   pick "Browse…" without re-entering the graph name.

### Error and edge cases

| Case | UI response |
|---|---|
| Zip export fails (disk full, share sheet cancelled, or — Web only — the browser blocks the download) | Inline note "Export didn't complete." appears under the button; does not block `Create anyway`/`Go back` — export is a convenience, not a gate. |
| User taps "Export as .zip" then "Go back" | No graph was created; the exported zip (if it completed) remains on the user's device regardless — the two actions are independent. |

### UX acceptance criteria

- AC41: Warning copy matches ADR-003 verbatim per platform, testable by string assertion.
- AC42: "Export as .zip" appears on **both** Android and Web (per ADR-003's Amendment reversing the
  original Android-only decision) — testable by platform-conditional composable structure asserting
  the button is present on each platform's render path.
- AC43: The warning can never be silently bypassed — `Create anyway` requires an explicit tap; there
  is no "don't show again" checkbox that could suppress it for a future plain-graph creation
  (repeated exposure to this specific risk is intentional, matching `FolderSyncSettings`'s own
  "must never be cut for space" reassurance-copy convention at `FolderSyncSettings.kt:107-110`).
- AC44: No dead end — "Go back" always returns to an editable New-graph dialog.

---

## Surface 12 (condensed): Insufficient-space pre-flight

Non-interactive check run automatically before Surface 7's copy step begins — not a user-driven
screen, but its outcome renders through Surface 8 (Failed) using the `InsufficientSpace` reason, so
it needs no dialog of its own.

**Representative output** (the `DomainError.StorageError.InsufficientSpace` message surfaced
verbatim in Surface 8's "Reason:" line):

```
Reason: needs 340 MB, only 210 MB available at the destination
```

Acceptance criteria:
- The check runs **before** any file is copied (a pre-flight estimate: source size via bounded
  file-count/byte-total scan, destination free space via `StatFs` on Android or
  `navigator.storage.estimate()` on Web), not discovered mid-copy for the common case.
- Where free space can't be reliably reported (Web's `estimate()` is an approximation per
  `research/ux.md` §4c), a mid-copy shortfall is treated as an ordinary `VerificationFailed`/
  `PartialCopyDetected` case through Surface 8 — no separate UI path is built for "estimate was
  wrong."
- The message always states both numbers (required vs. available), never a bare "not enough
  space."
- This check never blocks `Cancel`/`Retry` — it's advisory input to the same Surface 8 error state,
  not a new dead end.

---

## Surface 13 (condensed): Interrupted-mid-move / staging-directory sweep

Background startup routine (`RelocationStagingDirectory`'s sweep, Story 3.1.2) — no user-facing
screen; included here because its *absence* of UI is itself a designed choice worth stating
explicitly.

**Representative log/marker sample**:

```
.stele-relocate-staging-g1/.marker
  { "graphId": "g1", "startedAtEpochMs": 1757600000000 }
```

Acceptance criteria:
- A staging directory whose marker is older than the grace period (7 days) is silently swept at
  next app startup — no user-visible notification, since the source was never touched and no data
  is at risk (mirrors `GitShadowWorktree.sweepOrphans()`'s existing silent-sweep precedent for the
  same reason).
- A staging directory with **no** marker file is never swept (ambiguous absence ≠ staleness) —
  matches the existing `sweepOrphans()` philosophy exactly.
- If a user reopens the app **during** the grace period after an interrupted move, reopening
  Surface 4's "Move storage location…" flow for that graph is unaffected — the stale staging
  directory is inert scratch space, not surfaced as a resumable operation (no "resume previous
  move?" prompt exists — Surface 8's guidance to always restart from the top via `Retry` extends
  here too: an app-relaunch-interrupted move is retried the same way a same-session failure is).
- This routine never touches the source or the final destination path — only the distinct staging
  path — so its complete absence of UI carries no data-safety risk to justify one.

---

## Surface 14 (condensed): Observability log lines

Non-interactive; structured log entries per Epic 5.1.

**Representative sample**:

```
MoveStarted   graphId=g1 source=SafFolder destination=AppOwned operation=Relocate
MoveVerified  graphId=g1 result=pass fileCount=8030 hashMismatches=0
MoveCompleted graphId=g1 sourceDeleted=false
MoveFailed    graphId=g1 reason=VerificationFailed detail="hash mismatch on pages/foo.md"
```

Acceptance criteria:
- Every relocate/link operation produces exactly one `MoveStarted` and exactly one terminal entry
  (`MoveCompleted` or `MoveFailed`) — never zero, never both.
- `MoveFailed` entries always include the `DomainError.StorageError` subtype name, not just a
  human-readable message, so a developer can `grep` by failure class.
- Every `MoveFailed` is also visible in the UI at the moment it happens (Surface 8) — per
  `research/pitfalls.md`'s backlink-count-migration-crash precedent cited in the plan, a failure
  that's only logged and not shown is treated as a bug, not an acceptable degraded mode.

---

## Cross-cutting UX acceptance criteria

These apply across all interactive surfaces above, restating and consolidating the per-surface
criteria into checks a reviewer can run once against the whole feature:

- **AC-X1 (task efficiency)**: creating a plain app-storage graph takes ≤4 taps total (Surface 2 +
  11); cloning into app storage takes ≤3 taps (Surface 3); moving an existing graph's storage takes
  ≤4 taps end-to-end excluding wait time (Surfaces 4→5→6, then the terminal choice in Surface 9).
- **AC-X2 (no dead ends)**: every error/edge state documented above (Surfaces 1, 2, 7, 8, 8b, 9, 10,
  11, 12) has at least one exit path that returns the user to a known-good, previously-reachable state
  — enumerated per-surface above, collected here as a single audit checklist. Surface 7's `Verifying`
  phase is called out here deliberately: content-hash verification (Task 3.1.1f) can run for minutes
  on an 8,000+-page graph with a large git history (`requirements.md`'s Rabbit Holes), so `Cancel`
  stays enabled through `Verifying` exactly as it is through `Quiescing`/`Copying` — a non-cancelable,
  non-dismissable dialog spanning a multi-minute phase is precisely the dead end this criterion exists
  to rule out. Surface 8b's exit path (return to the graph list) is a *known-good* state in the sense
  that it is a normal navigation target, not a claim that the graph itself is known-good — that
  distinction is the entire point of Surface 8b existing separately from Surface 8.
- **AC-X3 (never destructive by default)**: across the entire flow, exactly one user action can
  ever delete source data — "Delete old copy" on Surface 9 — and it is only reachable after
  verification has already passed (Surface 7 → Summary). No other button, timer, or automatic
  process in Surfaces 1-14 (including 8b) deletes graph content.
- **AC-X4 (accessibility baseline)**: every interactive row/button across Surfaces 1-11 (and 8b) is
  keyboard-reachable via Tab, activatable via Enter/Space, carries a non-generic accessible name
  (label text or a real `contentDescription`, never "icon" or "button"), and its text meets ≥4.5:1
  contrast in both light and dark theme — consistent with the existing conventions this feature
  extends (`GitSetupScreen.kt`'s radio rows, `FolderSyncStatusBadge.kt`'s live-region status text).
- **AC-X5 (consistency)**: "App storage" is the pinned first row in every picker instance
  (Surfaces 2, 3, 4) with identical subtitle copy per platform; the Relocate/Link choice (Surface 5)
  and confirmation (Surface 6) render identically regardless of which entry point (Android Edit
  Graph, Web `FolderSyncSettings`) launched them — there is exactly one picker implementation and
  one move-wizard implementation per platform, per requirements.md's explicit constraint.
- **AC-X6 (no silent failure)**: every terminal failure state (Surface 8, Surface 9's cleanup
  failure, Surface 12's space check) is paired with a log entry (Surface 14) — a developer
  debugging a user report can always correlate what the user saw with what was logged.
