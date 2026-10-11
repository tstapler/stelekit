# UX Design: dev-console-git-sync-fix

**Date**: 2026-10-10
**Inputs**: `requirements.md`, `research/ux.md`, `implementation/plan.md` (Stories 2.1, 2.2, 2.3, 2.4, 3.2, 4.5, 5.5, 5.6, 5.7, 5.8, 6.1-6.6, 7.2). Phase 4 repair added S14-S16 and UX-75..UX-79; Triad repair 1 added S17 and UX-80..UX-92 (loading states, phone-input fit, backups list, precedence, live regions, notification permission, file hygiene, confirm-matching rules, plain-language copy). Triad repair 2 fixed the repair-versus-first-sync contradiction (S3, UX-02, UX-08) and added UX-93..UX-103 (repair-sheet concurrency, badge/chip fit and icons, badge announcements, empty and first-use states, symbol keys, write-statement hint, notification tap, copy-range selection, non-copyable mass-change confirm) plus the chip vocabulary in UX-27. Triad repair 4 (minor) added the confirmation hook in the common manual-sync success path, the persisted previous branch for `Change back` and the S14 `Open details` hand-off (UX-105..UX-107). Triad repair 3 moved the S14 review sheet and its consent into PR-A1 (plan Task 2.2d0; PR-A2's Task 2.4h only enforces it for scheduled sync), added the badge re-entry to the UX-86 precedence rule, and added UX-104 (`Sync without preview`).
**Status**: Proposal. No device run; every behavior below is a design target, not an observed one. Repo facts cited from `research/ux.md` are VERIFIED there (file:line); plan facts are cited by story number.
**User**: the owner, alone, on an Android phone with a soft keyboard (primary), Desktop (keyboard), Web (subset).
**Design principles** (from research): typing is the scarce resource; reading and copying are common. Every error names what happened and offers one next action ("<what> — <action>"). Color is never the only channel. Reuse existing surfaces (`SyncStatusBadge`, `GitDetectionBanner`, `SettingsDialog` Developer category, `rememberShareProvider`).

---

## 1. Surface inventory

| # | Surface | Kind | Plan story | Treatment |
|---|---------|------|-----------|-----------|
| S1 | Sync badge, `RemoteBranchNotFound` state (plus the other error variants and the staleness chip) | interactive | 2.1a, 2.1f, 2.1g, 2.4f | full |
| S2 | Remote-branch-not-found sheet (`BranchRepairSheet`; read-only `SyncDetailSheet` variants ship ungated) | interactive | 2.1g, 2.2d | full |
| S3 | Config-repair confirm + post-repair result | interactive | 2.2d | full |
| S4 | Mismatch banner (existing broken config) | interactive | 2.2d | full |
| S5 | Git setup wizard Step 4 / Step 5 (branch) | interactive | 2.2c | full |
| S6 | Settings > Developer: developer-mode toggle and "Dev console" row | interactive | 4.5b, 6.5a | full |
| S7 | Console screen (blocks, input, extra keys, chips, completion, history sheet) | interactive | 6.1, 6.2 | full |
| S8 | Confirm tiers: inline, SQL-write modal, no-WHERE typed confirm, post-write restore | interactive | 6.3a | full |
| S9 | Shell-armed second confirm and armed chip | interactive | 5.7, 6.3 | full |
| S10 | Export / share / spill | interactive | 5.8, 6.4 | full |
| S11 | SQL result grid | interactive | 6.4 | full |
| S12 | Logs overflow entry and sync-error cross-link | interactive | 6.5 | short (inside S6/S2) |
| S13 | Journal-repair confirm (`graph journals-repair`) | interactive | 3.2, 5.6 | short (inside S8) |
| N1 | Console text outputs (`git doctor`, `graph journals-diff`, `help`) | non-interactive | 5.2, 5.6 | condensed |
| N2 | Audit/log lines (console, sync WARN) | non-interactive | Observability | condensed |
| N3 | Sync result line ("Pulled N commits"), a persistent line, not a toast | non-interactive | 2.2d | condensed |
| S14 | First-sync preview sheet (read-only; "Sync now"; "Sync without preview" when the remote check fails) | interactive | 2.2d0 (PR-A1); 2.4h (PR-A2 enforcement) | full (below, UX-75, UX-81, UX-104) |
| S15 | Staleness chip, background-result notification and launch banner | interactive | 2.4f | full (below, UX-71, UX-72, UX-76) |
| S16 | Mass-change confirm and DB-only-rows confirm | interactive | 2.3f, 5.6d | short (below, UX-77, UX-78) |
| S17 | Backups list and restore (`backups` command, Settings > Developer > "Console backups") | interactive | 5.5g | full (below, UX-85) |

---

## 2. Interactive surfaces

### S1. Sync badge: `RemoteBranchNotFound`

Existing `SyncStatusBadge` maps `SyncState.Error` to a red `Icons.Default.Error` plus a one-line message (`error/DomainError.kt:320-342`). `AuthFailed` already routes tap to credentials (`SyncStatusBadge.kt:209-225`); the new case routes to S2.

```
 Idle/OK                       Error: branch missing
 +-----------------------+     +-------------------------------------------+
 | (check) Synced 2m ago |     | (!) Branch 'main' not found on remote --  |
 +-----------------------+     |     tap to fix                            |
                               +-------------------------------------------+
```

Flow:
1. Sync (manual or scheduled) fails with `RemoteBranchNotFound(remote, branch, available)`.
2. Badge turns red with the message above. TalkBack reads "Sync error — tap to fix" (not "tap to retry"; retry cannot fix a wrong branch).
3. Tap opens S2. No automatic dialog pops up on a background failure (a scheduled sync must not steal focus).
4. A non-tap path: badge stays until a successful sync or the user repairs.

Error/edge states:
| State | Badge text | Tap action |
|---|---|---|
| Branch missing, remote reachable | `Branch 'main' not found on remote — tap to fix` | S2 |
| Offline / ls-remote could not run | existing `Offline` message (never "branch missing") | retry |
| Remote empty (no branches) | `Remote is empty — push a commit first or check the URL` | opens the sheet in "empty remote" variant (Close only plus Copy details) |
| `SyncInvariantViolated` (remote still ahead after a sync) | `Sync finished but your phone is missing remote changes — tap for details`; **rendered amber, not red** (it is the same state as the amber "Remote is ahead by K but nothing was merged — see details" result line in N3; one mapping, plan Task 2.1g) | read-only sheet "invariant" variant: shows doctor-style summary, Copy details, Retry sync |
| Detached HEAD | `Your notes folder isn't on a branch — tap for details` (plain language; "detached HEAD" appears only in the sheet's Copy details text and in console output) | same variant as above |
| `RepairNeeded` (interrupted merge, unmerged paths) | `Repository needs repair (a merge was interrupted) — tap for details` | read-only sheet; routes to the existing conflict / abort flow (abort first snapshots unrelated edits) |
| `ConflictMarkersPresent` / `ScanIncomplete` | `Conflict markers found in N file(s) — nothing was pushed, tap for details` / `Couldn't finish checking your files for conflict markers — nothing was pushed, tap to retry` | read-only sheet listing the files (paths only) |
| `MassChangeBlocked` | `Sync paused: N files would be deleted — tap to review` | sheet with the first 50 paths; foreground only: typed-count confirm "delete N files" (UX-77); scheduled runs never offer it |
| `FirstSyncUnconfirmed` (in an A1-only build: a branch repair or `git set-branch` left the review pending; from A2 on: every unconfirmed config) | `Review first sync` | S14 preview sheet (always present, shipped in the same PR as the repair) |
| Fetch-only (Saf, or app closed) | `Updates fetched; open the app to merge` (amber) | opens the sync sheet; never green |

Exit path: swipe/back dismisses the sheet; badge remains red (the problem is not solved), so the user can come back.

**Badge and chip at 360 dp (UX-94).** The badge and the staleness chip share one status row (16 dp side gutters, so 328 dp usable at 360 dp):
- The row is a single touch target at least 48 dp tall and as wide as the content (never under 48 dp wide); badge and chip are separate targets with 8 dp between them, each at least 48 x 48 dp.
- Badge text wraps to at most **two lines** at the default font scale and **three** at 1.5x or more; it never truncates before the line limit, and if the third line would still not fit, the text is ellipsized at the end while the full string stays in the accessibility description and at the top of the opened sheet. The leading icon and the text never separate (icon stays on the first line).
- The chip sits **below** the badge (its own row) whenever the badge text wraps or the available width is under 400 dp; at 400 dp or wider and with a one-line badge they share a row. The chip text wraps to two lines (`last merged 3 h ago` / `behind 2`) rather than truncating; the separator dot is dropped when it wraps.
- Error and warning text is never reduced to an icon alone at any width or font scale.

| Variant | Icon (distinct shape, not only color) | Color token | Accessibility label (read once on appearance, polite) |
|---|---|---|---|
| Synced / up to date | check mark | success | `Synced <age> ago` |
| Syncing | circular progress (static `Working…` at reduce-motion) | neutral | `Syncing` |
| Review first sync | list-with-check | info | `Review first sync. Double tap to open.` |
| Amber: invariant, still-ahead, fetch-only, stale | **warning triangle** | warning | `Warning: <plain text>. Double tap for details.` |
| Red: branch missing, auth, repair needed, markers, mass change, other errors | **octagon-with-exclamation** (never the triangle) | error | `Error: <plain text>. Double tap to fix.` / `... for details.` for read-only sheets |
| Offline | cloud-off | neutral | `Offline. Sync will resume when you are connected.` |
The amber triangle and the red octagon differ in silhouette and in the spoken prefix (`Warning` versus `Error`), so neither color nor icon alone carries severity (UX-94). Every variant row above has its own `contentDescription`, a button role, and (for error and warning variants) a polite live-region announcement exactly once when the state is entered, not on every recomposition and not for background retries that keep the same state (UX-95). TalkBack reads the chip as one item: `Last merged 3 hours ago, 2 updates waiting`.

**Precedence among the four sync-status surfaces (UX-86).** At most one *banner* is visible at a time, chosen by this order (highest first); the badge and chip are always visible and never replaced by a banner:
1. **Mismatch banner** (S4, `RemoteBranchNotFound` observed): the sync cannot work at all, so it outranks everything.
2. **First-sync review** (S14 entry, badge text `Review first sync`): shown as the badge state and, when no mismatch banner is present, as a banner at launch; scheduled sync (PR-A2) is blocked until done. The badge is also the **re-entry** after `Not now` on the review or after dismissing its banner: it is never itself a banner, always opens S14, and keeps the review reachable when a higher banner is up.
3. **"Updates are waiting" launch banner** (S15): informational, only when `behind > 0` and nothing above applies.
4. **Staleness chip / amber fetch-only chip**: persistent, never a banner; it always shows the current numbers even while a banner above is up.
The full condition set is {mismatch, first-sync review pending, `behind > 0`, `Review first sync` badge}; for every one of its 16 subsets at most one banner shows (the highest of the first three present) and the badge/chip show their own state. A lower-ranked banner is *queued, not lost*: dismissing or resolving the higher one reveals the next on the following frame, and each banner's own dismiss rule is unchanged (S4 once per failed sync; "Updates are waiting" until the behind-count changes). The red/amber *badge* state always reflects the worst current error regardless of which banner shows; it never makes `Review first sync` unreachable, because the sheet it opens carries a `Review first sync` row while the review is pending (UX-105). Rationale: the owner should see "why nothing is syncing" before "how stale am I".

**Staleness chip (S15)**: next to the badge, always shown for a git-synced graph: `last merged 3 h ago · behind 2`. It turns amber when the last merge is older than twice the effective interval and `behind > 0` (or recent background fetches failed). A fetch-only graph reads `fetch-only (app closed)` or `fetch-only (SAF)`, never green. An Android notification `N remote commits are waiting — open SteleKit to merge` (low importance, toggle in Settings, cleared at `behind 0`) and, at launch, the banner `Updates are waiting — N remote commits not merged yet` with Review and Dismiss complement it (banner order: see the precedence rule above).

**Chip before any merge (UX-97).** A graph that has never merged (new clone, or just repaired) has no `lastMergedAt`. The chip then reads `no merge yet · behind <n>` (neutral, not amber) until twice the effective interval has passed since the graph was first seen, then follows the normal amber rule; while the first behind-count is unknown it reads `checking…` with no number. It is never blank and never says `last merged 0 min ago`.

**Notification tap (UX-101).** Tapping `N remote commits are waiting — open SteleKit to merge` opens the app on the graph the notification names (the notification carries the graph id) and goes to the **first-sync review sheet (S14) when `FirstSyncUnconfirmed`**, otherwise to the sync status sheet (the badge's detail sheet, which offers `Sync now`); it never starts a sync by itself and never opens the dev console. If the app is already open and in the foreground on another screen, it navigates (adds one back-stack entry, so Back returns to where the user was); if the app is open on a different graph it asks nothing and switches to the named graph only when that graph is open in the registry, otherwise opens the sync sheet for the current graph with the line `Updates are waiting for <graph name>`. If the sheet is already showing, the tap just focuses it. If `behind` is already 0 when the tap lands (cleared meanwhile), the sheet opens showing `Up to date` and the notification is dismissed. A notification for a typed fetch error opens the same sheet showing that error. Cold start: the app launches to the same destination after the normal graph load, without showing the launch banner a second time (the tap counts as seeing it).

**Notification permission (Android 13+, API 33; UX-89).** `POST_NOTIFICATIONS` is a runtime permission. It is requested **in context**, never at first launch: when the owner turns the Settings toggle "Notify me when updates are waiting" on (default on is applied only if the permission is already granted or the OS version is below 33). Flow: toggle on -> one-line rationale `SteleKit can tell you when your notes on the remote have changed. Allow notifications?` with buttons `Allow` and `Not now`, then the system dialog. States:
| State | Settings row | Behavior |
|---|---|---|
| Granted | toggle on, no extra text | notifications post per the rules above |
| Not asked yet / "Not now" | toggle off, helper `Off — the sync chip still shows waiting updates` | nothing posted; asked again only when the owner re-enables the toggle |
| Denied once (system may re-ask) | toggle off, helper as above | re-enabling re-shows the rationale, then the system dialog |
| Denied permanently ("don't ask again") | toggle disabled with `Notifications are blocked for SteleKit.` and an `Open system settings` button | chip and launch banner remain the only signals |
Denial is never an error state: the staleness chip and launch banner carry the same information, and nothing blocks sync.

### S14. First-sync preview

**Entry points.** (a) Badge `Review first sync`; (b) launch banner; (c) **after a branch repair** (S3): the repair sheet is replaced by this sheet with the heading `Branch set to master. Review before syncing.`; (d) notification tap (S15). Opening the sheet never syncs. **The `Sync now` tap on this sheet is the consent for the first sync** (repair or otherwise): the app never syncs silently after a repair.

**Empty states (UX-97).** Zero local-only commits: the commits section reads `Nothing here that isn't on the remote yet.` (no empty list, no header with a count of 0); zero files to delete: the line is hidden rather than showing `0 files would be deleted`; a remote with only the sync branch: the branch list shows that one branch with no stray tag and the line `No other branches on the remote.`

Read-only sheet opened from `Review first sync`. Shows: ahead/behind counts; every remote branch (strays such as `origin/main` tagged `not the sync branch`); the first 20 local-only commits (subject, date); `N files would be deleted` (0 normally). One primary button `Sync now` runs a single manual sync; a failed sync stores nothing and the sheet stays reachable. Scheduled sync (PR-A2) stays blocked until a manual sync succeeds for the current remote and branch. This sheet, the consent flag and the manual `Sync now` ship in PR-A1 with the repair flow (plan Task 2.2d0); an A1-only build never shows `Review first sync` without this sheet.

**Confirmation hook (UX-105).** The confirmation write sits in the one common manual-sync `Success` path, not in the sheet's button handler. Any manual sync of the current `(remote, branch)` that returns `Success` stores `git_first_sync_confirmed_<graphId>` and clears `git_first_sync_review_pending_<graphId>`: the S14 `Sync now`, the badge's sync action, the S14 "Sync now" error-state retry, the mass-change confirm run (typed count, UX-103) and the invariant sheet's `Retry`. An amber `SyncInvariantViolated` result (never a bare `Success`), `ConflictPending`, `RepairNeeded` and `Error` store nothing and leave the pending key set. **State precedence:** the badge shows the worst current error (red or amber) over `Review first sync`, but while the pending key is set the sheet that badge opens (status, mass-change, conflict or invariant detail) carries a `Review first sync` row that opens S14, so an error never hides the re-entry; once the error clears, the badge reads `Review first sync` again until a `Success` clears the key.

**Previous branch for `Change back` (UX-106).** The repair writes the branch it replaced (`from`) next to the pending key as `git_first_sync_previous_branch_<graphId>`; it is cleared with the pending key. S14 reads it on every open, so `Change back to '<from>'` is offered when S14 reopens from the badge, a launch banner or after process death, not only in the session that did the repair. If the value is absent (a pending key set by `git set-branch` without a recorded `from`, or a cleared key), `Change back` is hidden and no placeholder or empty-name button appears; `Copy details` and `Close` remain.

**Post-`Sync now` error state (UX-107).** When the sync started from S14 ends in `MassChangeBlocked`, `ConflictPending` or `RepairNeeded`, S14 stays open with that outcome's plain-language line and an `Open details` action that opens the matching sheet: the mass-change confirm (where the typed-count confirm lives, UX-103), the conflict sheet, or the repair sheet. S14 never hosts those confirms itself. Other errors keep `Change back` (when known), `Copy details` and `Close`. Returning from the detail sheet lands on S14, which re-reads state.

**Loading and timeout states (UX-81).** The sheet opens immediately with the local part (local-only commits, which need no network) and a section skeleton for the remote part:
| Phase | UI | Limit / exit |
|---|---|---|
| Local data | rendered instantly; local-only commits list | none |
| Remote data (branch list, ahead/behind) | section shows an indeterminate progress bar and `Checking the remote…` (text, not color only; announced once as a polite live region) | 15 s; `Cancel` stops the check and keeps the local part; `Sync now` is disabled while this phase runs because consent needs the remote numbers |
| Timed out / offline | section replaced by `Couldn't reach the remote (timed out after 15 s). Nothing was changed.` with `Retry`, `Copy details` and a secondary `Sync without preview`; **never** claims a branch is missing | `Close` always available |
| Loaded | counts and branches shown; `Sync now` enabled | — |
**Sync without preview (UX-104).** A slow or unreachable remote must not be a dead end for the first sync. `Sync without preview` appears only in the timed-out/offline state (never while loading, never after the numbers loaded). Tapping it opens a confirm dialog: title `Sync without a preview?`; body `SteleKit couldn't check the remote, so how many commits are ahead or behind, and which branches exist there, is unknown. Syncing can still pull commits and delete files on this device to match the remote. The delete guard still applies.`; a text field `Type sync to continue` (matching per UX-91: trimmed, case-insensitive; paste allowed since the word is not a secret); buttons `Cancel` (focus lands here, TalkBack announces the title once) and `Sync without preview` (disabled until the word matches, and disabled with `A sync is running. Wait for it to finish.` while the lock is held, UX-93). Confirm runs the same single manual sync as `Sync now`; the mass-change and invariant guards are unchanged, and only a `Success` stores the first-sync confirmation. Cancel or a mismatch changes nothing.

The `Sync now` button then runs the normal manual sync, which has its own progress (the badge's syncing state; a fetch is bounded by `GIT_TRANSPORT_TIMEOUT_SECONDS`, 300 s, and is cancellable from the badge sheet). Closing the sheet mid-check cancels the check.

### S16. Mass-change and DB-only-rows confirms

Mass change (foreground only): `Sync paused: 214 files would be deleted` with the first 50 paths listed and, below the list, a question and a numeric field: `Of the 50 files listed, how many are journals? Type the number.` Cancel is the default. **The expected answer is derived from the list and never printed anywhere in the prompt (UX-103), so copying text from the prompt cannot satisfy it.** Derivation: the candidates, in order, are (1) journals among the listed paths, (2) non-journals among the listed paths, (3) distinct parent folders among the listed paths; the first candidate whose decimal string differs from every number appearing in the headline and question text (`214`, `50`, ...) is used. If all three collide the typed confirm is withheld and the run stays blocked (fail closed) with `Can't confirm here. Run git status in the dev console to review.` The field accepts typed digits only: paste and drop are rejected (Ctrl+V, the IME clipboard action and drag-drop do nothing), and its mismatch hint is `Doesn't match — count the list again`, which never reveals the expected value. TalkBack reads the list and then the question; the answer is a count the user must make, which is the point (a reflexive paste or tap-through cannot pass). The other typed confirms (no-WHERE table name, DB-only rows) keep their echoed phrase because their expected text is the thing being acknowledged, not a count to re-derive. Matching rules for every typed confirm in this document (mass change, no-WHERE table name, DB-only rows): the typed text is **trimmed of leading/trailing whitespace and compared case-insensitively**; internal whitespace must match; a count must be the digits only (`214 files` does not match `214`); the destructive button enables only on an exact match and a mismatch shows `Doesn't match — type <expected>` under the field, except the mass-change confirm which shows `Doesn't match — count the list again` (no disabled-with-no-reason state). The numeric field uses a numeric keypad (UX-84). DB-only rows (console `graph reload` / `reindex` / `restore-backup`): `3 journals exist only in this database: 2026-10-07, 2026-10-09, 2026-10-10. Reload will erase them. They are exported to console-recovery/ first.` then a typed confirm `erase 3 DB-only journals` (console wording: "DB-only" is allowed here because this confirm appears only inside the dev console; outside the console the same fact reads "journals that exist only in this app's database"); if the export fails the command refuses.

### S2. Remote-branch-not-found sheet (`BranchRepairSheet`)

Bottom sheet on phone, centered dialog on Desktop. Modal; focus trapped; Escape/back closes.

```
+------------------------------------------------------+
| Branch not found on remote                      [X]  |
|------------------------------------------------------|
| Sync is set to branch  main                          |
| but the remote (origin) only has:  master            |
|                                                      |
| Nothing was pulled. Your phone may be missing        |
| recent changes.                                      |
|                                                      |
| Change that will be saved:                           |
|   remote_branch:  main  ->  master                   |
|                                                      |
| [ Use 'master' ]   (primary)                         |
| [ Choose another branch ]                            |
| [ Copy details ]   [ Open in dev console ]*          |
| [ Not now ]                                          |
+------------------------------------------------------+
* only when developer mode is on; opens console prefilled with `git doctor`
```

Variants by `available`:
| Case | Layout |
|---|---|
| Exactly one remote head, or symref default detected | As drawn; primary = "Use '<that>'" |
| Several heads, symref default detected | Primary = "Use '<default>' (remote default)"; secondary list shows the rest |
| Several heads, no symref (`Ambiguous`) | No primary button. Radio list of heads, nothing preselected, single button "Use selected branch" disabled until a choice is made. Never guess. |
| `available` empty | Heading "Remote is empty"; body "origin has no branches. Nothing to sync yet."; buttons Copy details, Close |
| Detection could not run (Unreachable) | Body "Couldn't read the remote's branch list (offline or unreachable)."; buttons Retry, Copy details, Not now. No "branch missing" claim. |

**Loading and timeout states (UX-80).** The sheet opens instantly from data already in the error (configured branch, `available` heads), so there is never a blank sheet. Default-branch detection (the remote's default-branch lookup, bounded at 15 s) runs in the background only when it can change the answer:
| Situation | UI while detecting | On completion / timeout / cancel |
|---|---|---|
| Exactly one remote head | no wait; primary `Use '<that>'` enabled at once | detection is skipped |
| Several heads | primary replaced by a disabled `Use…` button with an inline progress bar and `Checking which branch is the default…` (live region, announced once); radio list already usable | success: primary becomes `Use '<default>' (remote default)`; **timeout at 15 s or `Skip check`**: falls to the Ambiguous layout with the note `Couldn't check the remote's default (timed out). Pick one.`; offline: the Unreachable variant |
| Save in progress (S3) | `Saving…` | not cancellable (E3) |
The same bound applies to wizard Step 4/5 (S5): the branch dropdown shows `Checking the remote…` with a progress bar and a `Skip` text button; at 15 s it becomes the free-text field with the helper `Couldn't check the remote. Branch not verified.` and Next enabled (Step 5 verifies). Leaving the screen cancels the lookup. No state ever claims "branch missing" while a lookup is running or after it timed out.

"Choose another branch": replaces the body with a radio list (one row per remote head, 48dp rows, current default tagged "(remote default)"), a "Use selected branch" button, and Back (returns to the first view).

"Copy details" copies redacted text: configured branch, remote name, available heads, URL with userinfo removed. Confirms with an inline "Copied" label (not a timed snackbar only).

Exit paths: X, Back, Escape, or "Not now" all close with no change; stored value stays; red badge persists.

### S3. Config-repair confirm and result

The S2 sheet is the confirmation: the exact rewrite (`remote_branch: main -> master`) is shown before the primary button is pressed. No second dialog (avoid double-confirm fatigue).

```
Tap [Use 'master']
  -> button becomes "Saving..." (disabled), spinner
  -> saveConfig(remote_branch=master) -> read back from repository
       match   -> sheet is replaced by the first-sync review (S14, entry (c)):
                  "Branch set to master. Review before syncing."  [Sync now]  [Not now]
                  (the repair re-arms FirstSyncUnconfirmed, so NO sync runs yet)
       mismatch-> error state E1
Tap [Sync now] on S14 (this tap is the consent; the one and only sync trigger of the flow)
  -> a manual sync runs; only a Success stores the first-sync confirmation
  -> sync result (see N3):
       merged>0   -> S14 closes, badge green, persistent result line "Pulled 14 commits" (UX-87; no timed toast)
       merged==0 and behind==0 -> "Already up to date" (green)
       merged==0 and behind>0  -> amber "Sync finished but remote is still ahead — Details"  (never bare Success)
       error      -> S14 stays open with the new error text and [Change back to 'main' (only if the previous branch is persisted, UX-106)] [Copy details] [Close]
       MassChangeBlocked / ConflictPending / RepairNeeded -> S14 stays open with that line and [Open details] to the matching sheet (UX-107)
[Not now] on S14 -> closes; the branch stays saved, the badge reads "Review first sync", no sync runs, scheduled sync stays blocked until a confirmed manual sync.

One flow only: repair, then previewed first-sync review, then `Sync now`. There is no silent auto-sync after "Use 'master'" in the sheet, the console (`git set-branch` prints `Review and run: git fetch / Sync now`), or the wizard.
```

Error states:
| ID | Condition | Message | Actions |
|---|---|---|---|
| E1 | Read-back value differs from the saved value | "Couldn't save the new branch (stored value is still 'main')." | Retry, Copy details, Close |
| E2 | Save OK, the sync started from `Sync now` fails (auth, offline, other) | The normal sync error text for that cause | Change back to previous branch (re-runs the same read-back save; hidden if the previous branch is unknown, UX-106), Copy details, Close; the badge reflects the new error. For `MassChangeBlocked`, `ConflictPending` and `RepairNeeded`: `Open details` instead (UX-107) |
| E3 | User cancels while "Saving..." | Not cancellable once the write starts; Close is disabled for the short save only | Result shown after save |

**Concurrency rules for the repair sheet and the S14 sheet that follows it (UX-93).**
| Situation | Rule |
|---|---|
| Stale proposal | The sheet records `(remote name, configured branch, remote heads snapshot, config row version)` when it opens. Before saving, `BranchRepairService` re-reads the row and compares: if the stored branch no longer equals the sheet's `from` (changed elsewhere, for example the console `git set-branch`, a sync, or another window), or the chosen target is no longer in a freshly fetched head list, nothing is written and the sheet shows `This changed while the sheet was open. Review the new details.` with the refreshed content and the primary button re-enabled only after the refresh. A save never overwrites a value it did not display. |
| Git write lock held or a sync running | While the git write lock is held or `SyncState` is syncing (manual, timer or worker in the live process), **`Use <branch>`, `Use selected branch`, `Sync now` and `Change back` are disabled** with the helper line `A sync is running. Wait for it to finish.` (text, not color); `Copy details`, `Choose another branch`, `Not now` and Close stay enabled. Buttons re-enable on the sync's completion without closing the sheet. The check is made again at the moment of tap (a button that looks enabled but races a sync gets the same message instead of queuing a second write). |
| Resume (app backgrounded, process recreated, screen rotated) | On resume the sheet re-evaluates the error: it re-reads the current `SyncState` and config row. If the branch is now valid (sync succeeded, another path repaired it) the sheet closes itself and the badge shows the new state with the line `Already fixed.`; if the error changed type (offline, auth) the sheet switches to that error's variant; if still the same, nothing visible changes. State survives rotation (kept in the coordinator, not the composable). |
| Two taps / double submit | The primary button is disabled from the first tap until the read-back finishes (S3 `Saving…`); a second tap is ignored. |
| Dismissed while the lock is held | Closing is always allowed; nothing is queued. |

### S4. Mismatch banner (existing broken config)

Per plan 2.2d the repair is lazy: the banner appears only after a `RemoteBranchNotFound` is observed (no startup network call). It follows the `GitDetectionBanner` pattern (message + `TextButton` + Dismiss), not a modal.

```
+------------------------------------------------------------------+
| Sync is pointed at a branch that doesn't exist on the remote.    |
|                                 [ Fix ]   [ Dismiss ]            |
+------------------------------------------------------------------+
```

Flow: Fix opens S2. Dismiss hides the banner for this failure; it reappears only after the next failed sync produces the same error (one dismissal per failure). The red badge (S1) stays regardless, so a dismissed banner never hides the problem.

### S5. Setup wizard Step 4 (branch) and Step 5 (test connection)

Today Step 4 is a free-text field with no validation (`GitSetupStep4Branch.kt`). New:

```
Step 4 of 5: Branch
+------------------------------------------------+
| Remote branch                                  |
| [ master                      v ]              |
|   master  (remote default)                     |
|   dev                                          |
|   Other...                                     |
|                                                |
| Detected from origin. Change only if you sync  |
| a different branch.                            |
|                                  [Back] [Next] |
+------------------------------------------------+
```

States:
| State | UI |
|---|---|
| Looking up (up to 15 s) | Dropdown disabled with progress bar and `Checking the remote…`; `Skip` text button; Next disabled only while the lookup runs; at 15 s falls to the Unreachable row below (UX-80) |
| Detected(name) | Field prefilled with `name` and "(remote default)" tag; dropdown lists remote heads; "Other..." switches to free text |
| Ambiguous(candidates) | Field empty; dropdown lists candidates; Next disabled until one is chosen; helper "Several branches found — choose one" |
| EmptyRemote | Field editable, helper "Remote has no branches yet. The branch you enter will be created on first push."; Next enabled |
| Unreachable / not tested yet | Free-text field with helper "Couldn't check the remote. Branch not verified."; Next enabled but Step 5 will verify |
| User typed a name not in remote heads | Inline error under the field: "Branch 'nope' not found on remote. Available: master, dev"; Next disabled; the error offers tap-to-use chips for the available heads |

Step 5 "Test connection" additionally checks that the ref exists; failure blocks Save with the same `RemoteBranchNotFound` message and a Back button to Step 4. Exit path: Back at every step; Cancel returns to Settings without saving.

### S6. Settings > Developer

The category already exists (`SettingsCategory.DEVELOPER`, `SettingsDialog.kt:466`); visibility predicate changes so it is present whenever the platform supports the console (plan 4.5b).

```
Developer
+--------------------------------------------------------+
| Developer mode                                [  off ] |
| Lets you run SQL writes and shell commands. Your notes |
| can be changed or lost.                                |
|--------------------------------------------------------|
| (visible only when on)                                 |
| Dev console                                         >  |
| Console backups                                     >  |   (S17)
| Libsql driver                                 [ ... ]  |   (existing)
+--------------------------------------------------------+
```

Flow:
1. Toggle on: takes effect immediately; "Dev console" row appears; Logs overflow gains "Dev console".
2. Toggle off: row and overflow item disappear. If a console screen is open it closes and returns to the previous screen; any running command is cancelled; shell is disarmed; scrollback and history are dropped (in-memory only).
3. A plain labelled toggle, no hidden gesture (research recommendation).

Edge states: Web shows the toggle plus a console that lists git/fs/sh as disabled with reasons and offers only `help`, `settings`, `logs`, `export`, and `sql` if a read-only connection exists (G13, plan Task 4.1a). The setting key is `developer_mode_enabled`; a read failure defaults to off (plan Story 4.5).

### S7. Console screen

A full `Screen.Console` (survives rotation because `MainActivity` handles configuration changes, `androidApp/src/main/AndroidManifest.xml:44`, so the `remember {}`-hosted session is not recreated; not a dialog), opened from Settings > Developer > "Dev console" or the Logs overflow.

```
+--------------------------------------------------+
| <  Dev console            [Wrap] [Export] [Clear]|
+--------------------------------------------------+
| $ git status                    OK   120 ms      |
|   On branch master ... (14 lines)                |
|   [Copy] [Share] [Collapse]                      |
|--------------------------------------------------|
| $ git doctor                    ERR  340 ms      |
|   configured ref  : origin/main   (unresolved)   |
|   remote heads    : master                       |
|   suggestion      : git set-branch master        |
|--------------------------------------------------|
| $ sql select * from blocks   TRUNCATED  1.2 s    |
|   [ result grid, see S11 ]                       |
|                                  [v Jump to end] |
+--------------------------------------------------+
| Snippets: [git status][git refs][git doctor]     |
|           [git log -n 10][diag journals-diff]... |
| Completions: [git][graph][fs]                    |
+--------------------------------------------------+
| > git doc|                                [ Run ] |
+--------------------------------------------------+
| [Tab][^C][Up][Dn][/][-][_]['][\"][*][;][|][Paste]|
+==================== soft keyboard ================+
```

Behavior:
1. Each command makes one block: header `$ cmd`, duration, status chip (OK / ERR / CANCELLED / TRUNCATED / AWAITING CONFIRM / RUNNING / TIMEOUT, always text plus color; the full closed vocabulary, nothing else is shown), body, footer. RUNNING while a command streams, AWAITING CONFIRM while a confirm is pending (S8a), TIMEOUT when the command hit its time limit (distinct from ERR, partial output kept). Block actions: Copy, Share, Collapse, plus Select text (UX-102) and Copy selection while a range exists.
2. Output streams in; auto-follow tracks the tail and pauses when the user scrolls up; a "Jump to end" control appears while paused. No animated flourish.
3. Run: Enter on single-line input, or the Run button. A multi-line paste never auto-runs; it needs explicit Run (IME Enter is unreliable). `Ctrl+Enter` runs multi-line on keyboards.
4. While a command runs the Run button becomes Cancel; `^C` key and `Ctrl+C` also cancel. Input stays enabled so the next command can be typed.
5. Snippet chips insert text and place the cursor; completion chips (first token from registry, second from subcommands, tables/columns for `sql`) replace the current token; Tab accepts the first completion. Completion is a chip row, never a popup over output.
6. History: Up/Down (hardware) or the Up/Dn extra keys step through an in-memory ring; "History" opens a bottom sheet (tap = insert, long-press = run). History is redacted on store and empty after restart.
7. Scrollback is capped at 5,000 lines / 2 MB. Eviction inserts `[... N earlier lines dropped ...]` at the top. Lines over 2,000 chars are cut with `...(+N chars)`.
8. Block rendering: monospace, no-wrap with horizontal scroll per block, optional Wrap toggle, respects system font scale. Level prefix text (`ERR`, `WARN`) accompanies color.
9. Redaction: removed secrets show a visible `[redacted]` marker so absence is not read as empty.
10. **Write statement typed under plain `sql` (UX-100).** `sql` is read-only. When the typed statement's first keyword (after whitespace and comments) is a write or DDL keyword (`insert`, `update`, `delete`, `replace`, `create`, `alter`, `drop`, `vacuum`, a writing `pragma`), a one-line non-blocking hint appears under the input: `This changes data, and plain sql is read-only. Use sql! to run it (you'll be asked to confirm).` with a `Switch to sql!` chip that rewrites only the command prefix and keeps the cursor. The hint is not a live region while typing (no chatter); pressing Run anyway yields an ERR block `Denied: plain sql is read-only. Use sql! for writes.` announced like any chip. For statements on the deny list (S8b) the hint instead reads `This statement isn't allowed in the console.` and offers no switch chip, so the hint never suggests that `sql!` would permit a denied statement. Multi-line input is classified on its first statement only.
11. **Selecting part of a block's output (UX-102).** A block stays **one focusable accessibility node** (Accessibility rules, section 4). Partial selection is a transient mode of that block, not individually focusable lines: long-press in the block body (or mouse drag, or the block's `Select text` action) turns the body into a selectable region for that block only, with the platform selection handles; a block action `Copy selection` appears next to Copy only while a range exists, and Copy (whole block) is unchanged. TalkBack users reach the same thing through custom actions on the block node: `Select text` (opens a modal `Select text` sheet holding that block's text in one read-only selectable field with `Copy selection` and `Copy all`) and `Copy`. Selection is cleared on Escape, on tapping elsewhere, when new output arrives for that block, or when the block collapses. When the range changes, a polite announcement `<n> characters selected` fires once at the end of the gesture (not per handle move). Copied text is the redacted text on screen (never pre-redaction).

Exit paths: Back or the top-bar `<` leaves the screen. If a command is running, leaving does not kill the session (session is owned by the composition root), and returning shows the live block. Closing the app discards everything.

**Empty and first-use states (UX-96).**
- *Console, first open (no blocks yet)*: the output area shows a hint instead of a blank pane: `Type a command, or tap a chip below. Nothing here is saved after you close the app.` plus the seeded snippet chips (`git status`, `git refs`, `git doctor`, `git log -n 10`, `diag journals-diff`, `tables`) and `help`. The hint disappears when the first block is added and returns after Clear. TalkBack reads the hint once on screen open.
- *History sheet, no commands yet*: `No commands yet this session. History isn't saved after you close the app.` with a Close button; no empty list.
- *Completion row with no match*: row hidden (no empty strip).

Error and edge-case table:
| State | What the user sees | Exit / next action |
|---|---|---|
| Unknown command | `unknown command 'gti'. Did you mean 'git'?` plus up to 3 tap chips | tap a chip or edit input |
| Bad args | Usage line (`git set-branch <name>`) plus a `help <cmd>` chip | edit input |
| Developer mode off (restored route) | Screen closes with "Developer mode is off" message at the previous screen; dispatcher returns `Denied("developer mode off")` | enable in Settings |
| Capability unavailable (Web git/sh, iOS) | Command listed in `help` as "disabled: needs JGit; unavailable on this platform"; running it prints the same reason | none needed |
| SQL error | SQLite message verbatim, position if known, block chip ERR; input text kept | edit and rerun |
| Cancelled / timeout | Chip CANCELLED (or TIMEOUT text on ERR), partial output kept | rerun |
| Row cap | Footer `500 rows shown, more available -- truncated at row cap` and chip TRUNCATED | add LIMIT/WHERE, or Export CSV |
| Graph closed or switched mid-command | Block ends `graph closed during command` (ERR) | rerun on the new graph |
| Uncaught Throwable (incl. OOM) | ERR block with exception class and message; session stays alive | rerun |
| Output exceeds scrollback | Spill to a file; line `saved to <path>, tap to share` | tap to share (S10) |
| Another sync holds the git write lock | `sync in progress` (refuses or waits, shown as a progress line with Cancel) | cancel, or wait |
| Rotation / process kept alive | Input text, scrollback, pending confirm state preserved (state lives in `ConsoleSession`) | continue |
| Process death | Everything lost by design (in-memory only); empty console on next open | none |

Landscape/desktop: same layout; physical keys bind directly (Up/Down history, Tab completion, Ctrl+C cancel, Ctrl+L clear, Ctrl+Enter run, Esc closes completion or dialog); extra-keys row hidden when a hardware keyboard is attached; snippet row collapses into a "Snippets" menu.

**Extra-keys row fits a 360 dp phone (UX-82).** Thirteen 48 dp keys need 624 dp, so the row is split by role instead of shrunk below the touch minimum:
- *Row A (never scrolls)*: `Tab`, `^C`, `Up`, `Dn`, `Paste` = 5 x 48 dp = 240 dp, fits at 360 dp with margin and holds every action key.
- *Row B (symbols, two pages)*: page 1 `/ - _ ' " * ; |`, page 2 `! % = ( ) , < >` (every character a SQL `WHERE`, `LIKE` or arithmetic statement needs on a phone, UX-99). A pinned 48 dp page key at the row start (`1/2` shows `2/2`, label `More symbols`, description `Show more symbols, page 2 of 2`) flips the page; the choice persists while the console is open and the pinned key is not part of the scrolling strip. Each page = 8 x 48 dp = 384 dp. At widths below 440 dp (the strip plus the pinned page key) it is a horizontally scrollable strip with a visible trailing fade so a clipped key is never mistaken for the last one; at 440 dp and wider it fits statically. Scroll position resets to the start when the keyboard reopens.
- Keys are never smaller than 48 x 48 dp and never wrap. Key glyphs and the short labels (`Tab`, `^C`, `Paste`) are capped at 1.3x font scale; their `contentDescription` carries the full name ("Control C, cancel running command"), so larger system font scales change nothing about the layout. At font scale >= 1.5 the three text keys switch to icons with the same descriptions.
- The row is sticky above the IME (`imePadding`) and hidden when a hardware keyboard is attached.

**Soft-keyboard vertical space (UX-83).** The output area always keeps at least `max(96 dp, 4 lines)` of height above the input, measured with the keyboard open. As space shrinks (landscape phone, split screen, large font), rows give way in this fixed order, each restoring when space returns: (1) the completion chip row hides (Tab still accepts the first completion, shown as inline ghost text in the input); (2) the snippet chip row collapses into the same `Snippets` menu the desktop uses; (3) Row B folds into Row A behind a `Sym` toggle key (one row visible at a time). The input line and Row A are never hidden. In landscape the input requests `IME_FLAG_NO_EXTRACT_UI | IME_FLAG_NO_FULLSCREEN` so the keyboard cannot take over the whole screen (implementation hook chosen in Task 6.2a; UNVERIFIED which Compose hook exposes it, an `androidMain` wrapper that ORs the flags into `EditorInfo.imeOptions` is the fallback).

**Input IME flags (UX-84).** Per field: console command input and the snippet/history editors = no autocorrect, no auto-capitalization, no suggestion strip, ASCII-oriented text keyboard, IME action `Send` labelled Run for single-line input (multi-line input keeps newline); SQL text is the same; typed-confirm count fields (mass change `214`) = numeric keypad with IME action Done; typed-confirm name fields (table name, `erase 3 DB-only journals`) = the command-input flags above; the branch free-text field in S5 = no autocorrect, no auto-capitalization. Passwords/tokens are never typed in the console. The flags are asserted per field in a Robolectric test via `ImeOptions`/`KeyboardOptions`, with real-keyboard behavior in the manual device pass.

### S8. Confirm tiers

Tier mapping (plan: `Risk` -> `ConfirmTier`):
| Risk | Examples | Confirmation |
|---|---|---|
| READ | `sql select`, `git status/log/refs/ls-remote/doctor`, `fs ls/cat`, `diag`, `help`, `export` | none |
| NETWORK | `git fetch` | none; progress line and Cancel |
| STATE_CHANGE | `git set-branch`, `git merge`, `settings set`, `graph reindex`, `graph reload`, `graph journals-repair`* | inline confirm chip echoing the exact effect |
| DB_WRITE | `sql!` INSERT/UPDATE/DELETE | modal with statement, dry-run count, backup name |
| EXEC | `sh` | second confirm once per session, then "armed" chip (S9) |

*Plan gap G3: `journals-repair` writes files and is not classified; this design treats it as STATE_CHANGE with a longer echo (list of dates, see below).

**8a. Inline confirm (STATE_CHANGE)**
```
$ git set-branch master                 AWAITING CONFIRM
  remote_branch:  main -> master
  Run?   [ Confirm ]  [ Cancel ]
```
- Appears inside the command's block, so rotation keeps it. Cancel ends the block as CANCELLED with no effect. Confirm runs, then the block shows the read-back value (`remote_branch is now: master`).
- Enter does not confirm. The block waits indefinitely; leaving and returning shows it still pending. A new command can be typed; the pending confirm is auto-cancelled when a second command is submitted (stated in the block: `Cancelled: superseded`).
- **Live region and focus (UX-88).** When a block enters AWAITING CONFIRM, TalkBack announces once, assertively, `Awaiting confirmation: <exact effect>. Confirm or Cancel.` and accessibility focus moves to the block's `Cancel` button (the safe default; `Confirm` is the next focus stop). When the confirm resolves (Confirm, Cancel or superseded), the result is announced once (politely) and focus returns to the command input. On command start the status chip announces `Running <command>` (polite); on finish it announces the chip text with duration and line count (`git status, OK, 120 ms, 14 lines`). Streamed lines are never announced; the block summary description updates instead (UX-44). A superseded confirm announces `Cancelled: superseded`.
- `graph journals-repair` echo: `Will write 3 files: journals/2026_10_07.md, 2026_10_09.md, 2026_10_10.md. Existing files are never overwritten.` Result lists written and `skipped: file appeared`.

**8b. SQL write modal (DB_WRITE)**
```
+--------------------------------------------------+
| Update 12 rows?                                  |
|--------------------------------------------------|
| Statement                                        |
|  UPDATE pages SET is_favorite = 1                |
|  WHERE name LIKE 'Inbox%'                        |
| Tables affected: pages                           |
| Dry run: 12 rows would change (statement only)   |
| A backup is taken first:                         |
|  console-backups/personal-wiki-20261010-1702.db  |
| DB-only: markdown files are not changed.         |
|                                                  |
|            [ Cancel ]   [ Update 12 rows ]       |
+--------------------------------------------------+
```
- Statement shown verbatim, scrollable, selectable. Button label is the verb and count ("Delete 12 rows", "Insert 1 row"), never "OK". Default focus = Cancel; Escape/back cancels; focus is trapped.
- Dry-run count comes from the same WHERE evaluated read-only, and **counts rows of the statement's own table only** (UX-91). SQLite triggers and the FTS index maintenance that follow a write to `blocks` or `pages` can touch additional rows (`blocks_fts`, `pages_fts`) that the count does not include; the modal says so in one line under the count: `Search-index rows are updated by triggers and are not counted.` The backup (not the count) is the safety net. If the dry run fails (SQL error), the modal is not shown; the block shows the SQLite message and input text is kept.
- Statements on the deny list (`drop table`, `attach`, `pragma writable_schema`, FTS shadow tables, `vacuum`, multiple statements) never reach the modal: block ERR `Denied: <reason>`. Confirming does not override a deny.

**8c. No-WHERE typed confirmation**
```
| This statement has no WHERE clause and affects the whole |
| table "pages" (9,414 rows).                              |
| Type the table name to continue:  [ pages        ]       |
|            [ Cancel ]   [ Delete 9,414 rows ] (disabled) |
```
- The destructive button stays disabled until the typed text equals the table name after trimming surrounding whitespace and ignoring case (SQLite identifiers are case-insensitive), with the mismatch hint from S16 (`Doesn't match — type pages`). Applies to `DELETE` and `UPDATE` without WHERE. All typed confirms share one matcher (S16), one test table.

**8d. Progress and result**
- Between confirm and finish the modal shows "Taking backup..." then "Running..."; Cancel is available only until the backup finishes (the write itself goes through the actor and is not interruptible; Cancel is then hidden and the label reads "Writing, can't cancel").
- Result block: `12 rows affected -- DB-only: not written to markdown`, backup path, and a persistent **Restore backup** action (no timed snackbar; TalkBack-safe).
- Restore backup opens an inline confirm: `Restore console-backups/...db? Changes made after this backup are lost. The graph reloads.` [Restore] [Cancel]. Failure shows the error with the backup path and "Copy details".
- Write failure: `DomainError.DatabaseError.WriteFailed` message verbatim in an ERR block; nothing partially applied is claimed unless the read-back says so.
- Backup failure: the write does not run; block ERR `Backup failed: <reason>. Nothing was changed.`
- The scrollback `Restore backup` action is a convenience; the **durable** way back is S17 (a backups list that outlives the scrollback, the session and the console screen).
- **Focus after dialogs (UX-88).** Closing the SQL-write modal, the no-WHERE confirm, the shell-arm dialog (S9) or the Restore confirm returns focus to the element that opened it: the command input for a modal opened by running a command, the `Restore backup` button (or, once it is replaced, the result block header) for the Restore confirm. Dialog titles are announced on open (UX-61); the outcome of Confirm is announced once on close.

Exit path: every dialog and confirm has Cancel/Escape/back; none auto-confirms on timeout.

### S17. Backups list and restore

The scrollback block that follows a write disappears with the session. Two durable entry points list the snapshots in `console-backups/` (newest first, at most 5, ADR-002 A3):
- Console: `backups` (READ: lists), `backups restore <name>` (alias of `graph restore-backup`, STATE_CHANGE with the S8d restore confirm and the DB-only-rows guard).
- Settings > Developer > `Console backups` (visible only with developer mode on): a list screen, one row per snapshot: `20261010-1702 · personal-wiki · 48 MB · before UPDATE pages`, row actions `Restore…` and `Copy path`.
```
$ backups
  #  taken              size    before
  1  2026-10-10 17:02   48 MB   UPDATE pages (12 rows)
  2  2026-10-10 16:31   48 MB   session start
  Restore with:  backups restore 1
```
Empty state: `No console backups yet. One is taken automatically before the first database write.` The list marks a snapshot unreadable (`integrity check failed`) rather than hiding it, and `Restore…` is disabled for it with that reason. Restore uses the same confirm and failure states as S8d (`Changes made after this backup are lost. The graph reloads.`; failure shows the path and Copy details); the list stays reachable after a failed restore. The screen is keyboard-operable, rows and actions are 48 dp, and each row's description reads the full row text (UX-85).

### S9. Shell-armed second confirm

`sh` is **desktop-only** in v1 (plan ADR-005 amendment): it runs as the app user and therefore bypasses the `fs` deny-list and the best-effort redactor. On Android, wasm and iOS it is listed in `help` as disabled with the reason "shell bypasses the file deny-list; desktop only". On desktop, the first `sh ...` in a session shows:
```
+--------------------------------------------------+
| Run shell commands? (desktop)                    |
|--------------------------------------------------|
| Commands run as your user on this computer.      |
| They are NOT limited by the console's file       |
| deny-list and output redaction is best-effort.   |
| They can read and delete files.                  |
| Your first command:   sh ls -la                  |
|                                                  |
|           [ Cancel ]    [ Arm shell for session ]|
+--------------------------------------------------+
```
- Default focus = Cancel. After arming, the top bar shows a persistent chip **Shell armed [Disarm]**; subsequent `sh` commands run without a prompt.
- Disarm is also automatic when: developer mode is turned off, the console screen is left, or the app is closed (plan Task 4.4b, resolves G6).
- `sh --help` states the bypass and the platform limits.
- Running `sh` commands: stdout/stderr stream; a 1 MB flood never freezes the UI; Cancel destroys the process tree; default timeout shows `TIMEOUT` text.

### S10. Export, share, spill

```
Top bar [Export]  ->  menu:
   Share transcript...
   Save to Downloads
   Export last SQL result as CSV   (only if a grid block exists)
Block [Share] -> shares that block only
```
- File name `console-<yyyyMMdd-HHmmss>.txt` via `rememberShareProvider().saveToFile` / `shareText` (same path as `LogDashboard`). Content is post-redaction; no uploads.
- The `export` command does the same from the input line.
- Success: inline "Saved to <path>" with a Share action. Failure (no storage permission, share cancelled): inline error "Couldn't save the transcript: <reason>" with Retry and Copy to clipboard fallback.
- Empty transcript: Export is disabled with the reason "Nothing to export yet".
- While a command is running, export includes the partial output and marks the running block `RUNNING (partial)`.
- Spill: output beyond the scrollback cap goes to a file; the block shows `Output too large for the screen. Saved to <path>. [Share]`.
- **Spill files and CSV exports: redaction, location, cleanup (UX-90).** Every file the console writes for the user passes the same `ConsoleRedactor` sink as the scrollback *before* it is written (no unredacted temp copy, and CSV cells are redacted per cell), and the Export preview (pre-mortem #7) applies to CSV as well. Location: app-private `console-exports/` (never the graph folders, so nothing is committed or pushed by the sync), shared through the OS share sheet or copied to Downloads only on an explicit action. Cleanup: spill and CSV files in `console-exports/` are deleted when the console session ends (developer mode off, or the next app launch), and the folder is capped at 20 MB, oldest first; a spill/CSV the owner explicitly saved to Downloads is theirs and is not deleted. The folder is excluded from Android Auto Backup with `console-backups/` (Task 5.5f). A block that points at a deleted file shows `File removed when the session ended` instead of a dead Share button. Content warning shown once per export: `May contain note content.`

### S11. SQL result grid

```
$ sql select name, is_journal from pages limit 3     OK 45 ms
+----------------+------------+
| name  (sticky) | is_journal |
+----------------+------------+
| Inbox          | 0          |
| 2026-10-09     | 1          |
| (NULL dim)     | NULL       |
+----------------+------------+
3 rows.  [Export CSV]
```
- Sticky first column and header row; NULL dim italic with text `NULL`; cells ellipsized at ~80 chars, tap expands; cell content capped at 2 KB with `(+N chars)`.
- Footer states row count; at cap shows the truncation marker (see S7 table).
- Zero rows: `0 rows.` (not a blank block).
- Chips `tables` and `schema <table>` give discovery without typing SQL; `EXPLAIN QUERY PLAN` chip prefixes the current statement.
- Column widths computed off the UI thread on the capped rows.

### S12. Entry points and cross-link (short)
- Entry points exist only with developer mode on: Settings > Developer > "Dev console", and Logs overflow > "Dev console".
- S2 offers "Open in dev console" only with developer mode on; it opens the console with `git doctor` prefilled but not run (the user reviews, then Runs). Back from the console returns to the sync sheet's origin screen, not a deep stack.

### S13. Journal-repair confirm (short)
Covered by S8a. The dates come from the `graph journals-diff` output (N1); the 2026-10-07/09/10 DB-only journals are listed first.

---

## 3. Non-interactive surfaces (condensed)

### N1. Console text outputs
Sample (`git doctor`):
```
$ git doctor
configured ref   : origin/main        (UNRESOLVED)
remote heads     : master
remote HEAD      : refs/heads/master  (symref)
ahead/behind     : n/a
shallow          : yes
path mode        : app-owned
detached HEAD    : no
remote url       : https://github.com/o/r.git   (userinfo removed)
suggestion       : git set-branch master
```
Acceptance:
- Fixed-width `label : value` layout, at most 80 columns, readable without horizontal scroll on a phone in portrait.
- Every unresolved/failed field says why in the line itself (never a blank value).
- Remote URLs never show userinfo; a `[redacted]` marker appears where something was removed.
- The suggestion line, when present, is a copy-pasteable command.
- `graph journals-diff` splits "recent" (last 14 days) and "older: not loaded by design" and lists dates in descending order, capped at 50 per group with a `+N more` line.
- When nothing is missing the output says so instead of printing empty groups (UX-98): `Recent (last 14 days): all 14 journals are in the database.` and `Older: not loaded by design (N on disk).` When the DB has journals the disk lacks, only that group is listed; a group with zero entries is never printed as a bare header.

### N2. Audit and log lines
Sample: `INFO console cmd=git.doctor dur=340ms lines=12 outcome=OK`; `WARN console sql-write stmt="UPDATE ... [redacted]" rows=12 backup=...db`.
- One INFO per command, one WARN per SQL write; arguments pass the redactor.
- Lines appear in the existing Logs screen with its level filter.
- `WARN` on `RemoteBranchNotFound` lists remote, configured branch, available heads.

### N3. Sync result line
Sample: `Pulled 14 commits` (green) / `Already up to date` (green) / `Remote is ahead by 3 but nothing was merged — see details` (amber, not a success).
- Never shows green success when `behind > 0` after sync. The amber line is the badge's rendering of `Error(SyncInvariantViolated)` (S1), not a separate state.
- Message follows "<what happened> — <what to do>" when it is an error or warning.
- Shown as a **persistent result line** under the badge (and in the S2 sheet after a repair), never as a timed toast, so TalkBack and slow readers do not lose it (UX-87). The line stays until the next sync starts or the user dismisses it with a labelled "Dismiss" control, and it is a polite live region announced once.

---

## 4. Cross-cutting rules

**Accessibility** (applies to every surface above)
- Every block is one focusable item with a summary description ("Command git status, succeeded in 120 ms, 14 lines") and an expand action; individual lines are not focusable. Only the status chip is a polite live region. Partial text selection does not add focusable lines: it is a transient mode of the one block node, reached by custom accessibility actions `Select text` and `Copy selection` (S7 item 11, UX-102).
- Chips, extra keys, and dialog buttons: at least 48dp, `Role.Button`, descriptive labels ("Insert git ls-remote", "Control C, cancel running command").
- Keyboard: all actions reachable without touch; visible focus ring; dialogs trap focus; Escape cancels.
- Contrast 4.5:1 or better for text in light and dark using theme tokens (not the raw amber `0xFFF59E0B`); color is never the only channel.
- Respect system font scale and reduce-motion.

**Live regions and focus** (applies everywhere): one announcement per event, never per streamed line. Status changes (sync result line, chip, command start/finish) are polite live regions announced once; an action-blocking prompt (AWAITING CONFIRM) is announced once assertively and takes focus on its safe default. Focus always returns to the control that opened a sheet or dialog (UX-18), or to the command input when a command opened it (UX-88).

**Loading states** (applies to S2, S5, S14, and any control that waits on the network): the surface renders immediately from data in hand, shows an indeterminate progress bar with a text label (`Checking the remote…`, never color or motion alone), offers `Cancel` or `Skip` for any wait that can exceed 2 s, and has a hard bound (15 s for default-branch and `ls-remote` lookups, 300 s for fetch). A timeout is stated as a timeout (`timed out after 15 s`), never as a negative finding about the remote (never "branch missing"), and always offers `Retry`. Reduce-motion replaces the animated bar with a static `Working…` label.

**Plain-language rule for user-facing copy (UX-92).** Everywhere outside the dev console (badge, chip, sheets, banners, notification, Settings, wizard, result line) copy avoids git and database jargon. Banned words in those surfaces: `symref`, `ls-remote`, `invariant`, `DB-only`, `tracking ref`, `refspec`, `detached HEAD`, `shallow`, `OID`, `SQLite`, `WAL`. Preferred wording: "the remote's default branch", "your notes folder isn't on a branch", "journals that exist only in this app", "a merge was interrupted". "Branch", "remote", "sync", "commit" are allowed (they are the app's own sync vocabulary). Jargon is allowed inside the console (`git doctor` prints `symref`, `sql` talks SQLite) and inside the text produced by `Copy details` (for support). A single test renders each variant and scans the visible text for the banned words (Task 2.1g/6.6a).

**Theming**: use existing theme tokens; status colors plus text prefixes.

**Persistence**: nothing from the console persists (history, scrollback, armed state). Only `developer_mode_enabled`, `console-backups/` and `console-recovery/` (unencrypted, excluded from Android backup), `console-exports/` (spill and CSV files, session-scoped, excluded from backup, S10), `git-abort-recovery/`, the notification toggle, and the sync keys `git_first_sync_confirmed_<graphId>` and `git_sync_staleness_<graphId>` persist, plus the three mass-change threshold keys (plan Task 2.3f).

---

## 5. UX acceptance criteria

Each is testable by a human (device or desktop). Tags: [A] accessibility.

**Sync error and repair (S1-S5)**
1. UX-01: With stored branch `main` and a remote with only `master`, tapping Sync shows a red badge with exactly "Branch 'main' not found on remote — tap to fix" (no green success, no silent no-op).
2. UX-02: From the red badge, the user reaches the repaired state in at most 3 taps with no typing: badge, "Use 'master'" (saves and opens the first-sync review), "Sync now" (the consent; the sync runs only on this tap). "Not now" on the review leaves the branch saved and no sync run. Works in an A1-only build (no scheduler).
3. UX-03: The S2 sheet states the configured branch, the remote's actual branches, and "Nothing was pulled".
4. UX-04: The S2 sheet shows the exact rewrite `remote_branch: main -> master` before the primary button is pressed.
5. UX-05: When several remote branches exist and no default is detectable, no branch is preselected and the apply button is disabled until the user chooses.
6. UX-06: "Not now", X, Back, and Escape each close S2 without changing the stored branch; the red badge remains.
7. UX-07: After "Use 'master'", the stored value is read back and shown as `master`; if read-back differs, the error "Couldn't save the new branch" appears with Retry and Close.
8. UX-08: After a successful repair no sync runs by itself: the repair opens the first-sync review (S14), and the sync runs when the user taps "Sync now" there, after which the user sees a persistent result line "Pulled N commits" (or "Already up to date"); it is not a timed toast and stays until the next sync starts or it is dismissed (see UX-87). The review sheet ships in the same PR as the repair (PR-A1), so this holds on an A1-only build.
9. UX-09: If the sync started from the review still ends with remote ahead and nothing merged, the result is amber with details, never a bare success.
10. UX-10: If the sync started from the review fails, the review sheet stays open with the new error and offers "Change back to 'main'".
11. UX-11: When the remote cannot be reached, no message claims the branch is missing; the sheet or badge shows the offline/unreachable text and Retry.
12. UX-12: An empty remote shows "Remote is empty" with Copy details and Close, and no "Use" button.
13. UX-13: "Copy details" copies text that contains no credentials and no URL userinfo.
14. UX-14: The mismatch banner appears at most once per failed sync, Dismiss hides it, and the red badge stays after dismissal.
15. UX-15: Setup Step 4 prefills the remote default branch tagged "(remote default)" and lets the user pick another remote head or type one.
16. UX-16: Typing a nonexistent branch in Step 4 or failing Step 5 shows "Branch '<x>' not found on remote. Available: ..." and blocks Save; Back returns to Step 4.
17. UX-17: A badge tap on `RemoteBranchNotFound` opens the repair sheet; a tap on `AuthFailed` still opens credentials; other errors still retry.
18. UX-18 [A]: TalkBack reads the missing-branch badge as "Sync error — tap to fix", and focus moves into S2 when it opens and returns to the badge on close.
19. UX-19 [A]: S2 and S3 controls are keyboard-operable, have visible focus, are at least 48dp, and the radio list announces selection state.

**Developer mode and entry (S6, S12)**
20. UX-20: On a fresh install the Developer category shows only the toggle and the warning text "Lets you run SQL writes and shell commands. Your notes can be changed or lost."; no console row exists in Settings or the Logs overflow.
21. UX-21: Turning developer mode on reveals "Dev console" in Settings > Developer and the Logs overflow without restarting.
22. UX-22: Turning developer mode off while the console is open closes the screen, cancels any running command, disarms the shell, and clears scrollback.
23. UX-23: "Open in dev console" appears in S2 only when developer mode is on and opens the console with `git doctor` prefilled, not executed.
24. UX-24: From the Logs screen, the owner opens the console in at most 2 taps (overflow, Dev console) when developer mode is on.

**Console screen (S7)**
25. UX-25: The owner can answer "what branch/refs/log/status does this clone have?" by tapping the `git doctor` chip then Run (2 taps, no typing).
26. UX-26: The owner can answer "which journals are on disk but not in the DB?" by tapping the `diag journals-diff` chip then Run (2 taps, no typing).
27. UX-27: Every command renders as one block with a header (`$ cmd`), duration, and a text status chip from the closed vocabulary OK / ERR / CANCELLED / TRUNCATED / AWAITING CONFIRM / RUNNING / TIMEOUT (RUNNING while streaming, AWAITING CONFIRM while a confirm is pending, TIMEOUT on a time-limit stop).
28. UX-28: A running command shows a Cancel control; Cancel (button, `^C` key, or Ctrl+C) ends it as CANCELLED and keeps partial output.
29. UX-29: Scrolling up pauses auto-follow and shows "Jump to end"; tapping it resumes following.
30. UX-30: A multi-line paste does not run until the user taps Run (or Ctrl+Enter).
31. UX-31: The extra-keys row shows Tab, ^C, history up/down, `/ - _ ' " * ; |`, and Paste above the keyboard, and each inserts or acts as labelled.
32. UX-32: Tapping a snippet chip inserts its text with the cursor at the end; completion chips never cover the output area.
33. UX-33: Up/Down (or the extra keys) step through earlier commands, and the History sheet supports tap-to-insert and long-press-to-run.
34. UX-34: History and scrollback are empty after the app is restarted.
35. UX-35: Rotating the device (a configuration change the Activity handles without recreation) keeps input text, scrollback, running commands, and pending confirms. Process death does not (UX-34).
36. UX-36: Typing `gti status` shows "unknown command 'gti'. Did you mean 'git'?" with up to 3 tappable suggestions.
37. UX-37: A command with wrong arguments shows the usage line and a `help <cmd>` chip, and the input text is kept.
38. UX-38: A SQL error shows the SQLite message verbatim and keeps the input text for editing.
39. UX-39: When scrollback exceeds 5,000 lines the top shows "[... N earlier lines dropped ...]" and the UI stays responsive.
40. UX-40: A graph switch or close during a command ends the block with "graph closed during command" and the app does not crash.
41. UX-41: On Web (and `sh` on Android and iOS), git/fs/sh appear in `help` as disabled with a reason, and running them prints the same reason.
42. UX-42: Any secret removed from output appears as `[redacted]`; the owner can grep an exported transcript for the PAT and find nothing.
43. UX-43: A `git fetch` or `git merge` started while a sync holds the git lock shows "sync in progress" and does not run concurrently.
44. UX-44 [A]: Each block is a single focusable item whose description includes command, outcome, duration, and line count; streamed lines do not each steal focus.
45. UX-45 [A]: Chips, extra keys, and block actions are at least 48dp with descriptive labels (for example "Control C, cancel running command").
46. UX-46 [A]: Hardware keyboard shortcuts work: Up/Down history, Tab accept, Ctrl+C cancel, Ctrl+L clear, Ctrl+Enter run, Esc closes completion or dialog.
47. UX-47 [A]: Output text meets 4.5:1 contrast in light and dark themes, and ERR/WARN are conveyed by text prefix as well as color.

**Confirm tiers (S8, S9, S13)**
48. UX-48: Read-tier commands (`git status`, `sql select`, `diag`) run with no confirmation.
49. UX-49: `git set-branch master` shows an inline "Run? [Confirm] [Cancel]" echoing `remote_branch: main -> master`; Cancel makes no change; Confirm shows the read-back value.
50. UX-50: Pressing Enter or submitting another command never confirms a pending inline confirm (a second submit cancels it with "Cancelled: superseded").
51. UX-51: A SQL write shows a modal with the verbatim statement, tables affected, dry-run row count, and backup file name before anything runs.
52. UX-52: The SQL write confirm button is labelled with the verb and count (for example "Update 12 rows"), initial focus is Cancel, and Escape/back cancels.
53. UX-53: A write without WHERE requires typing the exact table name before the destructive button enables.
54. UX-54: Denied statements (`drop table`, `attach database`, `pragma writable_schema`, FTS tables, `vacuum`, multiple statements) show "Denied: <reason>" and never show a confirm.
55. UX-55: If the dry run or backup fails, the write does not run and the block says "Nothing was changed" with the reason.
56. UX-56: After a write, the result shows rows affected, "DB-only: not written to markdown", the backup path, and a persistent "Restore backup" action (not a timed snackbar).
57. UX-57: "Restore backup" asks for confirmation that states later changes are lost, and shows an error with the backup path if restore fails.
58. UX-58: On desktop, the first `sh` command in a session shows a second confirm that says commands run as the app user and bypass the console file deny-list; later `sh` commands run without prompting and a "Shell armed [Disarm]" chip stays visible.
59. UX-59: Disarm, leaving the console, turning developer mode off, or closing the app each return the shell to unarmed.
60. UX-60: `graph journals-repair` lists the files it will write, never overwrites an existing file, and reports `skipped: file appeared` for any that appeared.
61. UX-61 [A]: Every modal traps focus, is operable by keyboard, announces its title, and has an exit path (Cancel, Escape, or back); no confirm auto-accepts on timeout.

**Export, grid, outputs (S10, S11, N1-N3)**
62. UX-62: Export produces `console-<yyyyMMdd-HHmmss>.txt` containing the redacted transcript and offers Share and Save to Downloads.
63. UX-63: With an empty transcript Export is disabled with the reason "Nothing to export yet".
64. UX-64: An export failure shows the reason with Retry and a copy-to-clipboard fallback.
65. UX-65: Output too large for scrollback is saved to a file and the block shows its path with a Share action.
66. UX-66: SQL results show a sticky first column and header, NULL as dim "NULL", cells over ~80 chars ellipsized with tap-to-expand, and "Export CSV" in the footer.
67. UX-67: A 500-row cap shows "500 rows shown, more available -- truncated at row cap"; zero rows shows "0 rows."
68. UX-68: `git doctor` output fits 80 columns, states why any field is unresolved, strips URL userinfo, and ends with a copy-pasteable suggestion when one exists.
69. UX-69: `graph journals-diff` separates "recent" from "older: not loaded by design" and caps each list at 50 with a "+N more" line.
70. UX-70: A sync that finishes with remote commits still unmerged never shows green; it shows an amber message with details.

71. UX-71: A graph whose background work could only fetch (app closed, or SAF-backed) shows the amber badge "Updates fetched; open the app to merge" when `behind > 0` instead of green (plan Tasks 2.4e-g).
72. UX-72: When the app launches with remote commits not yet merged, the banner "Updates are waiting — N remote commits not merged yet" appears with "Review" (into the existing sync UI) and Dismiss; Dismiss hides it until the behind-count changes (plan Task 2.4f, resolves G8).
73. UX-73: A `RemoteBranchNotFound` badge is visible and tappable (opens the detail sheet with Copy details) even when the repair UI is not yet shipped; the sheet then has no "Use" button (plan Task 2.1a).
74. UX-74 [A]: Restore backup, confirm, and failure states are keyboard-operable and announce their titles (plan Task 5.5e, resolves G4).

**First sync, staleness, guards (S14-S16, S1 variants)**
75. UX-75: After a branch repair (PR-A1) and, from PR-A2 on, before the first scheduled sync for any remote and branch, the badge says "Review first sync" and opens the review sheet in the same build; the preview shows ahead/behind, all remote branches with strays tagged, the first 20 local-only commits and the files-to-delete count, is read-only, and only a successful manual sync from it unblocks scheduled sync.
76. UX-76: Every git-synced graph shows "last merged <age> · behind <n>"; it turns amber after twice the effective interval with `behind > 0` or repeated fetch errors, a fetch-only graph says why, and a notification appears at most once per behind-count change and clears at 0.
77. UX-77: A sync that would delete more than the threshold of files shows "Sync paused: N files would be deleted — tap to review" with the first 50 paths; only a foreground typed-count confirm lets that one run proceed (the expected number is derived from the listed paths and is not shown in the prompt, UX-103); a scheduled run never does.
78. UX-78: `graph reload`, `reindex` and `restore-backup` with DB-only rows present list them, state they are exported to `console-recovery/` first, and require a typed confirm; if the export fails they refuse.
79. UX-79: The invariant, detached-HEAD, empty-remote, repair-needed, conflict-marker, scan-incomplete and mass-change badges each open a read-only sheet with a summary and Copy details (no repair button, no credentials), even when the branch-repair UI is not shipped.

**Triad repair 1 (loading, fit, durability, precedence, accessibility, hygiene)**
80. UX-80: While the default-branch lookup runs (branch repair sheet with several heads, wizard Step 4/5), the surface is already usable, shows `Checking …` with a progress bar and a `Skip`/Cancel control, never claims a branch is missing, and at 15 s falls back to the ambiguous or unreachable layout with a timeout message and Retry. A single remote head needs no wait.
81. UX-81: The first-sync preview opens instantly with the local-only commits, shows `Checking the remote…` with Cancel for the remote part (15 s bound), keeps `Sync now` disabled until the remote numbers load, and on timeout shows `Couldn't reach the remote (timed out after 15 s). Nothing was changed.` with Retry, Copy details and `Sync without preview` (UX-104).
82. UX-82: On a 360 dp-wide screen the extra-keys area shows every action key (`Tab ^C Up Dn Paste`) without scrolling and offers the eight symbol keys in a horizontally scrollable strip with a visible clipping hint; no key is below 48 x 48 dp or wraps, and at font scale 1.5 or more the layout is unchanged (text keys become icons with the same descriptions).
83. UX-83: With the soft keyboard open the console output keeps at least `max(96 dp, 4 lines)`; as space shrinks the completion row hides first, then the snippet row folds into the `Snippets` menu, then the symbol row folds behind a `Sym` toggle; the input line and action keys never hide; in landscape the IME does not enter extract/fullscreen mode.
84. UX-84: Console and SQL input have autocorrect, auto-capitalization and suggestions off with a Run IME action; typed counts use a numeric keypad; typed names use the command-input flags.
85. UX-85: `backups` and Settings > Developer > `Console backups` list the retained snapshots (newest first, at most 5) with time, size and what preceded them, mark an unreadable snapshot instead of hiding it, restore through the confirm in UX-57, and survive leaving the console, a new session and an app restart; an empty list explains that a backup is taken before the first write.
86. UX-86: At most one banner shows at a time in this order: mismatch banner, then first-sync review, then `Updates are waiting`; the `Review first sync` badge is the always-available re-entry after `Not now` or a dismissed first-sync banner and is never a banner itself; the badge and staleness chip are always visible; a lower banner appears once the higher one is dismissed or resolved and is never lost. Holds for every subset of {mismatch, first-sync review, updates waiting, badge}.
87. UX-87 `[A]`: A sync result ("Pulled N commits", "Already up to date", the amber still-ahead line) is a persistent line announced once as a polite live region, not a timed toast, and stays until the next sync starts or the user dismisses it with a labelled control.
88. UX-88 `[A]`: An AWAITING CONFIRM block announces `Awaiting confirmation: <effect>` once and moves focus to Cancel; command start and finish are each announced once with the chip text; closing any dialog (SQL write, no-WHERE, shell arm, Restore) returns focus to the control or input that opened it; streamed lines are never announced.
89. UX-89: On Android 13+ the notification permission is requested only when the owner turns on "Notify me when updates are waiting", after a one-line rationale; Not now, denied and permanently denied states leave sync working with the chip and launch banner as the signal, and permanent denial shows a disabled toggle with `Open system settings`.
90. UX-90: Spill files and CSV exports are redacted before they are written, live only in app-private `console-exports/`, are removed when the session ends (capped at 20 MB), are excluded from backup and sync, carry a `May contain note content.` warning, and a block pointing at a removed file says so instead of offering a dead Share button.
91. UX-91: Every typed confirm trims whitespace and ignores case, a count must be digits only, a mismatch shows `Doesn't match — type <expected>` (the mass-change confirm shows `Doesn't match — count the list again` and never the expected value), and the SQL write modal states that the dry-run count covers the statement's own table and that trigger-maintained search-index rows are not counted.
92. UX-92: No banned jargon (`symref`, `ls-remote`, `invariant`, `DB-only`, `tracking ref`, `refspec`, `detached HEAD`, `shallow`, `OID`, `SQLite`, `WAL`) appears in any non-console user-facing text of the badge, chip, sheets, banners, notification, wizard, Settings or result line; console output and Copy details may use it.

**Triad repair 2 (concurrency, fit, empty states, symbols, notification tap, selection, non-copyable confirm)**
93. UX-93: The repair sheet and the first-sync review re-validate before saving (a changed stored branch or a head list that no longer contains the target shows `This changed while the sheet was open. Review the new details.` and writes nothing), disable `Use`, `Sync now` and `Change back` with the line `A sync is running. Wait for it to finish.` while the git write lock is held or a sync runs (Copy details, Choose another branch, Not now stay enabled), re-enable when the sync ends, ignore a second tap while saving, and on resume re-evaluate the error (closes with `Already fixed.` if the branch is now valid, switches variant if the error changed).
94. UX-94: At 360 dp the sync badge wraps to at most two lines (three at font scale 1.5 or more) before ellipsizing with the full text kept in the description and the sheet, the staleness chip moves below the badge when the badge wraps or width is under 400 dp, badge and chip are each at least 48 x 48 dp, and the amber warning-triangle and red error-octagon icons are distinct shapes in addition to color.
95. UX-95 `[A]`: Every badge variant (synced, syncing, review first sync, amber warning, red error, offline, fetch-only, read-only sheet variants) has its own contentDescription with a `Warning:` or `Error:` prefix where applicable, a button role, and a polite live-region announcement exactly once on entering the state (not on recomposition or same-state background retries); the chip reads as one item.
96. UX-96: The console's first open shows a hint (`Type a command, or tap a chip below. Nothing here is saved after you close the app.`) with the seeded chips instead of a blank pane, returns after Clear, and an empty History sheet reads `No commands yet this session. History isn't saved after you close the app.`
97. UX-97: A graph that has never merged shows the neutral chip `no merge yet · behind <n>` (`checking…` while the behind-count is unknown), amber only after twice the effective interval; the first-sync preview with zero local-only commits reads `Nothing here that isn't on the remote yet.`, hides the `0 files would be deleted` line, and shows `No other branches on the remote.` when only the sync branch exists.
98. UX-98: `graph journals-diff` with nothing missing prints `Recent (last 14 days): all 14 journals are in the database.` and the `Older: not loaded by design (N on disk).` line, never an empty group header.
99. UX-99: The characters `! % = ( ) ,` are typeable on a phone without the IME symbol layer: extra-keys Row B page 2 (`! % = ( ) , < >`) is reached through the pinned `More symbols` page key, which has a 48 dp target and a description of the current page.
100. UX-100: Typing a write or DDL statement under plain `sql` shows the non-blocking hint with a `Switch to sql!` chip (prefix-only rewrite), Run under plain `sql` is denied with `Denied: plain sql is read-only. Use sql! for writes.`, and a deny-listed statement shows `This statement isn't allowed in the console.` without a switch chip.
101. UX-101: Tapping the updates-waiting notification opens the named graph's first-sync review when `FirstSyncUnconfirmed` and the sync status sheet otherwise, never starts a sync, adds one back-stack entry when the app is already open (Back returns to the previous screen), focuses an already-open sheet, shows `Up to date` if `behind` is already 0, and on cold start lands on the same destination without a second launch banner.
102. UX-102 `[A]`: Part of a block's output can be selected and copied (long-press or mouse drag, `Copy selection` while a range exists; TalkBack custom actions `Select text` and `Copy selection`), the block remains a single accessibility node with no focusable lines, a polite `<n> characters selected` announcement fires once per gesture, and the copied text is the redacted on-screen text.
103. UX-103: The mass-change typed confirm asks for a number derived from the listed paths (journals among the listed files, else non-journals, else distinct folders) that never appears in the prompt text, rejects paste and drop, shows `Doesn't match — count the list again` without revealing the answer, and is withheld (the run stays blocked) when every candidate would collide with a printed number.
104. UX-104: When the first-sync review's remote check has failed or timed out, `Sync without preview` is offered (not while loading or after the numbers loaded); it opens a confirm that says ahead/behind and remote branches are unknown, requires typing `sync` (trimmed, case-insensitive), focuses Cancel, runs exactly one manual sync with the delete and invariant guards unchanged, stores the first-sync confirmation only on `Success`, and is disabled while a sync is running.

**Triad repair 4 (confirmation hook, persisted previous branch, detail-sheet hand-off)**
105. UX-105: Every manual sync of the current `(remote, branch)` that returns `Success` (S14 `Sync now`, badge sync, mass-change confirm run, invariant-sheet `Retry`) stores the first-sync confirmation and clears the pending key; amber `SyncInvariantViolated`, `ConflictPending`, `RepairNeeded` and `Error` store nothing; while the key is set and an error badge shows, the sheet the badge opens has a `Review first sync` row that opens S14.
106. UX-106: `Change back to '<previous>'` is offered whenever S14 opens with a persisted previous branch (from the badge, a banner or after process death) and a post-sync error, and is hidden, with no empty placeholder, when the previous branch is unknown.
107. UX-107: When the sync started from S14 ends in `MassChangeBlocked`, `ConflictPending` or `RepairNeeded`, S14 shows that outcome's line and `Open details`, which opens the mass-change confirm, conflict sheet or repair sheet respectively; S14 hosts no typed-count confirm itself.

Total: **107** UX acceptance criteria (UX-01 to UX-107).

---

## 6. Plan gaps found

| ID | Gap | Where | Proposed resolution | Plan resolution (repair, 2026-10-10) |
|---|---|---|---|---|
| G1 | Plan 2.2d/6.5 pre-fills `git doctor` for the cross-link; `research/ux.md` said `git refs`. | plan 6.5 vs research | Design uses `git doctor` (plan wins; it is a superset). | Unchanged (plan uses `git doctor`). |
| G2 | No task or acceptance criterion for the sync result line ("Pulled N commits", amber "remote still ahead"), or for badge success text; 2.2d only says "reporting Pulled N commits". | 2.2d, 2.3b | Add to Task 2.2d: badge/toast result states from N3 (UX-08, UX-09, UX-70). | Task 2.2d ACs (result line states) plus Task 2.3b. |
| G3 | `graph journals-repair`, `graph reindex`, `graph reload` have no `Risk` classification in Story 6.3. | 5.6, 6.3 | Classify all three as STATE_CHANGE with inline confirm; `journals-repair` echo lists dates. | Story 6.3 / Task 6.3a: all three classified STATE_CHANGE; echo lists dates. |
| G4 | "Restore backup" has no confirm, no failure state, and no definition of how a live DB is replaced (graph close and reload). | 5.5, 6.3 | Add AC to 6.3a: confirm, error state, graph reload after restore; may need an ADR-002 addendum. | Task 5.5e (Restore backup) + Task 6.3a ACs; ADR-002 amendment A3 defines the live-DB swap. |
| G5 | Plan 6.3 does not say whether a SQL write can be cancelled; the actor write is not interruptible. | 5.5, 6.3 | Cancel allowed only during dry run and backup; hidden once the write starts (S8d). | Task 6.3a AC: Cancel only during dry run and backup. |
| G6 | Shell arm lifetime only says "once per session"; no rule for leaving the screen or turning developer mode off. | 5.7, 4.5 | Disarm on leave, dev-mode off, app close (S9). Add to ConsoleSession AC (4.4). | Task 4.4b AC (disarm on leave / dev-mode off / app close); shell is desktop-only (ADR-005 amendment). |
| G7 | S2 behaviors not in 2.2d ACs: "Choose another branch" radio list, "Copy details", empty-remote and unreachable variants, "Change back" after a failed follow-up sync, read-back mismatch UI. | 2.2d | Add these as ACs on Task 2.2d (UX-05, 07, 10, 11, 12, 13). | Task 2.2d ACs (radio list, Copy details, empty/unreachable variants, Change back, read-back mismatch). |
| G8 | Plan 2.4d says the next launch "shows the conflict UI" for a cold-worker conflict abort, but no story designs that surface or how the pending-conflict marker is shown. | 2.4d | Out of this document's scope; reuse existing conflict UI, but add a launch-time banner (needs a task). | Reworked by owner decision (a): background work is fetch-only, so the banner reports waiting updates ("Updates are waiting — N remote commits not merged yet"); Task 2.4f and UX-72. |
| G9 | Disabling developer mode while the console is open (close, cancel, disarm, clear) is not specified; 4.5 only covers entry points and dispatcher. | 4.5, 6.5 | Add AC to 4.5b (UX-22). | Task 4.5b AC (UX-22). |
| G10 | Pending inline confirm behavior across rotation and a superseding command is unspecified. | 6.3 | State lives in `ConsoleSession`; a second submit cancels it (UX-35, UX-50). | Task 4.4b: pending confirm lives in `ConsoleSession`; Task 6.3a AC (UX-35, UX-50). |
| G11 | Research proposed long-press-to-edit/pin chips, a Desktop "Snippets" menu, and a hidden-gesture alternative; the plan covers none. | research vs 6.2 | Long-press edit/pin deferred (not in plan); Desktop Snippets collapse added to 6.2a as a small item. | Task 6.2a: Desktop Snippets menu added; long-press edit/pin deferred (recorded as follow-up). |
| G12 | Export failure and empty-export states are not in 5.8/6.4 ACs. | 5.8, 6.4 | Add UX-62..65 to Task 5.8a / 6.4a. | Tasks 5.8a and 6.4a ACs (UX-62..65). |
| G13 | `Web` console subset: plan says "whichever subset is feasible" but not which commands exist; entry points on Web unspecified. | requirements, 4.1 | Design: console available on Web with `help`, `settings`, `logs`, `sql` (if driver allows), and git/sh/fs disabled with reasons (UX-41). Confirm with the owner. | Task 4.1a AC: Web subset is `help`, `settings`, `logs`, `export`, `sql` only if a read-only connection exists; git/fs/sh disabled with reasons. Owner confirmation noted in Unresolved Questions. |
| G14 | The mismatch banner in research was "first launch after upgrade"; the plan (Migration Plan, 2.2d) repairs lazily on first error with no startup network call. | research vs plan | Design follows the plan (banner after the first failed sync, S4). | Unchanged (design follows the plan). |
| G15 | TalkBack and contrast checks cannot be done headless (plan acknowledges TalkBack in 7.2); no plan step records a contrast check against theme tokens in dark mode. | 6.6, 7.2 | Add a manual contrast pass to Task 7.2a runbook. | Task 7.2a runbook: manual dark/light contrast pass against theme tokens. |

All gaps are additions or clarifications to existing tasks; none contradicts the plan's architecture. G1 and G14 stay as the UX design resolved them.

### Triad repair 4 gaps (2026-10-10, minor)

| ID | Gap | Resolution |
|---|---|---|
| G37 | The confirmation was described only for the S14 `Sync now` tap; other manual syncs (badge, mass-change confirm, invariant `Retry`) left the pending key set, and an amber `SyncInvariantViolated` badge could hide `Review first sync` permanently | Confirmation hook in the common manual-sync `Success` path; error-badge sheets carry a `Review first sync` row; S14, S1 precedence, UX-105 |
| G38 | `Change back` needed the previous branch, which was only in memory | `git_first_sync_previous_branch_<graphId>` persisted beside the pending key; hidden if unknown; S14, UX-106 |
| G39 | S14 error state had no route to the mass-change, conflict or repair sheets | `Open details` action; S14, UX-107 |

### Triad repair 3 gaps (2026-10-10)

| ID | Gap | Resolution |
|---|---|---|
| G34 | The repair flow ends in S14, but S14 was a PR-A2 task (Task 2.4h), so an A1-only build showed `Review first sync` with no sheet | S14, its consent flag and manual `Sync now` ship in PR-A1 (plan Task 2.2d0); Task 2.4h keeps only scheduled enforcement; S14 table row, S1 variant table |
| G35 | Banner precedence ignored the `Review first sync` badge as the re-entry after `Not now` | S1 precedence rule (four conditions, 16 subsets), UX-86 |
| G36 | S14 had no path when the remote check failed or timed out | `Sync without preview` behind a typed `sync` confirm, S14, UX-81, UX-104 |

### Triad repair 2 gaps (2026-10-10)

| ID | Gap | Resolution |
|---|---|---|
| G25 | Repair-versus-first-sync contradiction: S3/UX-08 auto-synced after "Use 'master'" while the repair re-arms `FirstSyncUnconfirmed` (Task 2.4h) | One flow: repair saves, opens S14, `Sync now` is the consent and the only trigger (S3, S14 entry (c), UX-02 = 3 taps, UX-08) |
| G26 | No concurrency rules for the repair sheet | S3 concurrency table, UX-93 |
| G27 | Badge/chip behavior at 360 dp, 48 dp targets, per-variant labels and amber-versus-red icons unspecified | S1 fit rules and variant table, UX-94, UX-95 |
| G28 | Empty and first-use states missing | S7 empty states, S15 chip before any merge, S14 empty states, N1, UX-96..UX-98 |
| G29 | `! % = ( ) ,` not typeable; no hint for write statements under `sql` | S7 Row B page 2, S7 item 10, UX-99, UX-100 |
| G30 | Notification tap destination unspecified | S15 notification tap, UX-101 |
| G31 | Partial selection absent and its fit with the single-node a11y model | S7 item 11, a11y rule, UX-102 |
| G32 | Chip vocabulary lacked AWAITING CONFIRM / RUNNING / TIMEOUT | S7 item 1, UX-27 |
| G33 | Mass-change confirm answer was copyable from the prompt | S16 derived-count design, UX-103 |

### Triad repair 1 gaps (2026-10-10)

| ID | Gap | Resolution |
|---|---|---|
| G16 | S2, S5 and S14 had no loading, timeout or cancel states for the lookups the plan bounds at 15 s | S2/S5/S14 loading sections, "Loading states" rule, UX-80, UX-81 |
| G17 | Extra-keys row (624 dp of keys) did not fit 360 dp; no soft-keyboard vertical-space rule; no IME flags | S7 fit, vertical-space and IME-flag paragraphs, UX-82..84 |
| G18 | Backups were reachable only from a scrollback block | S17, plan Task 5.5g, UX-85 |
| G19 | Four status surfaces had no precedence rule | S1 precedence rule, UX-86 |
| G20 | "Pulled N commits" toast not TalkBack-safe; no live-region or focus rules for confirms/dialogs | N3 persistent line, S8a/S8d, cross-cutting live-region rule, UX-87, UX-88 |
| G21 | Android 13+ notification permission flow and denied copy missing | S15 permission paragraph, UX-89 |
| G22 | Spill and CSV files had no redaction, location or cleanup rule | S10 paragraph, UX-90 |
| G23 | Typed-confirm matching and dry-run side-effect caveat unspecified | S16 matching rules, S8b/S8c, UX-91 |
| G24 | User-facing copy used git/DB jargon | Plain-language rule, detached-HEAD badge copy changed, UX-92 |
