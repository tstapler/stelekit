# UX Design: unwired-features-and-test-coverage

**Date**: 2026-10-27
**Inputs**: `requirements.md` (incl. Owner Decisions 2026-10-27), `research/ux.md`, `implementation/plan.md` (Stories 2.3, 3.1-3.5, 5.1, 5.5, 5.6, 6.2, 6.3), and the existing console design `project_plans/dev-console-git-sync-fix/design/ux.md` ("Console design", adopted, not redone).
**Status**: Proposal, revised in triad repair iteration 5 (2026-10-27; criteria UW-98..UW-106 appended for triad round-3 gaps, and UW-86, UW-89, UW-91 extended in place; iteration 4 appended UW-83..UW-97 for round-2 gaps GAP-1..GAP-14 and the interim Plugins row; UW-01..UW-82 keep their numbers). No device run; every behavior is a design target. Copy strings marked "plan" are quoted from `implementation/plan.md`; everything else is new here.
**User**: the owner, alone, Android phone with soft keyboard (primary), Desktop (keyboard), Web (subset).
**Principles** (inherited): typing is scarce, reading and copying are common; every error reads "<what happened> - <next action>"; color is never the only channel; disabled-with-reason, not hidden; background work never takes focus or opens a dialog; compact (<600 dp) is the primary layout; one polite announcement per event.

---

## 1. Surface inventory

| # | Surface | Kind | Plan story | Treatment |
|---|---------|------|-----------|-----------|
| C1 | Settings > Developer: developer-mode toggle, "Dev console" row | interactive | 3.1 | adopt Console design S6; deltas only |
| C2 | Console screen, first slice (`help`, `diag`, `logs`, `sql`) | interactive | 3.1, 3.2 | adopt S7; deltas only |
| C3 | SQL result grid, limits, cancel | interactive | 3.2 | adopt S11; deltas only |
| C4 | Guarded SQL write: confirm modal, result, restore action | interactive | 3.3 | adopt S8b-d; deltas only |
| C5 | Backups list and restore | interactive | 3.4 | adopt S17; deltas only |
| D1 | Settings > Git sync: "Scheduled sync" section (desktop and Android) | interactive | 2.3 | full |
| P1 | Settings > Plugins: on-device plugin list (interim single "Asset indexing" row from Task 4.1d in U7, full list in U10) | interactive | 4.1, 5.5 | full |
| P2 | CloudClaude consent dialog | interactive | 6.2 | full |
| P3 | Cloud plugin settings: key, caps, usage counter, revoke | interactive | 6.2, 6.3 | full |
| P4 | Pipeline progress and backfill row (pause, resume, cancel) | interactive | 5.1, 5.5 | full |
| A1 | Asset browser empty states and enable prompt | interactive | 5.6 | full |
| A2 | Extracted-text results in Assets search and global search | interactive | 5.6 | full |
| N1 | Scheduled-sync tick log line and badge line | non-interactive | 2.1b | condensed |
| N2 | Cloud egress log line and pipeline log lines | non-interactive | 6.3, 5.1 | condensed |
| N3 | Console audit lines | non-interactive | 3.1 | adopt Console design N2 |

Out of this document: Datalog, RTC, vector search (Epic 9/10: no new surface; research/ux.md section 4 recommends no UI, and a stub returning empty results is worse than no box). The small-wiring surfaces of Epics 7 and 8 (Wayback, desktop paste, Google Photos, Drive) are specified compactly in section 10a. Story 4.2 (`AssetMoveService`) adds no interactive surface: pending-move replay at graph open is silent, and a failed replay writes one WARN line to the Logs screen.

**Scope boundary for the console.** The plan builds the first slice only: toggle, console screen, `help`/`diag`/`logs`/`sql`, grid, CSV/transcript export, guarded writes with backup, restore. `git`, `fs`, `sh`, `graph` families and their surfaces (Console design S8a inline confirms, S9 shell arming, S13, N1 `git doctor`) stay in the Console design and are not built here. Every Console design UX number cited below keeps its meaning.

---

## 2. Adopted console surfaces (C1-C5)

Adopted verbatim from the Console design unless a delta is listed. Do not reopen layout, extra-keys split (UX-82), vertical-space order (UX-83), IME flags (UX-84), chip vocabulary (UX-27), live regions (UX-88).

| Surface | Adopted from | Delta in this project |
|---|---|---|
| C1 | S6, UX-20..22 | (1) Visibility predicate fix is required for the toggle to be reachable (today `SettingsDialog.kt:142` hides the category when both libsql and MCP callbacks are null; plan Story 3.1). (2) Warning copy exactly: "Lets you run SQL writes and shell commands. Your notes can be changed or lost." The words "shell commands" stay although `sh` is not built here, to avoid copy churn when it ships; the "Dev console" row shows only commands that exist. (3) Web: toggle shown, `sql` listed disabled with reason "disabled: no read-only connection on this platform" (plan Story 3.2). |
| C2 | S7, UX-25..47 | (1) First open shows the helper plus chips `tables`, `sql: newest journals`, `diag`, `help` (research/ux.md section 1), replacing the git-centric seed chips. (2) A write statement sent to plain `sql` yields ERR block `Denied: plain sql is read-only. Use sql! for writes.` (plan Story 3.3; the one error string for this case), never a silent no-op. (3) Write-hint under the input (UX-100) reads "This changes data, and plain sql is read-only. Use sql! to run it (you'll be asked to confirm)."; there is no Settings switch for writes (see C1 flow below). |
| C3 | S11, UX-66 | Row cap 500, cell cap 2 KB, 10 s `QueryDeadline` with Cancel from 0 s (plan Story 3.2). Timeout ends the block CANCELLED with the text `Stopped after 10 s. Partial rows kept.` There is no table-level credential denial. Only on drivers where Spike 0.3e = YES, reading a credential-alias column is denied by the engine authorizer (`Denied: column holds a credential alias`, error table below); where Spike 0.3e = NO nothing is denied and output passes through the single `Redactor` (aliases may be visible, no secret values are stored in the queried database). `CredentialColumnInventoryTest` guards against new credential-shaped columns (plan Story 3.2). |
| C4 | S8b-d, UX-51..57 | Modal appears only in `GuardedWrite`. Count text "7 rows will change" (plan) plus the triggers line from UX-91. Restore action persists (not a timed snackbar) in the scrollback block for the session only; after an app restart or developer mode off it is gone and the Backups list (C5) is the only entry. **Escalation**: when the dry-run count is at least 1,000 rows, or at least 50% of the target table **and at least 10 rows** (the absolute floor stops a 2-row table from training the owner to type through the prompt, GAP-8), the modal reads "This changes N of M rows in <table>" and the primary button stays disabled until the owner types the exact count N (a typed confirm, as C5 uses for DB-only rows); below that the standard modal applies. **Typed-confirm input spec (GAP-9, applies to this count field and the C5 `RESTORE` field)**: the match is exact after trimming leading and trailing spaces; `RESTORE` is case-sensitive (lower case does not enable the button, and the field shows the expected word as its hint, not as pre-filled text); autocorrect, suggestions and auto-capitalization are off; the count field opens the numeric keypad and accepts digits only (a pasted thousands separator is stripped, so `1,204` equals `1204`); IME action Done submits only when the match is exact; no clipboard auto-fill. |
| C5 | S17, UX-85 | Entry is Settings > Developer > "Console backups" and the command `backups`; plan Story 3.4 calls the restore command `graph restore-backup`, exposed here as `backups restore <name>` alias (S17). The DB-only-rows typed confirm (S16) applies. Deltas below: the confirm quantifies the loss, restore has a progress and outcome state, and the `*.pre-restore` copy is an undo. |

### C1 delta flow: choosing the write mode

Requirements and the plan make read-only the default and writes explicit. The Console design had no separate "guarded writes" switch, only the `sql!` command. To keep one mental model, no second switch is added: `ConsoleMode` is derived, not user-toggled.

```
Developer mode off  -> nothing visible
Developer mode on   -> ConsoleMode.ReadOnly by default (sql only reads)
sql! <statement>    -> enters GuardedWrite for that one statement: confirm modal (C4), backup, run
```

So `sql` is always read-only and `sql!` is the only write path, matching Console design S7 item 10 and the plan (Story 3.3). The hint above reads: "This changes data, and plain sql is read-only. Use sql! to run it (you'll be asked to confirm)." (verbatim UX-100). The single ERR string `Denied: plain sql is read-only. Use sql! for writes.` is used when a write reaches plain `sql` anyway (pasted multi-line, hint ignored). Decided with the plan (pre-mortem P1-1): write and restore code are excluded from release builds until the owner device pass 12.1, so in such a build `sql!` is listed in `help` as `disabled: writes not enabled in this build` and returns that text.

### Console error and exit coverage (delta only)

| Flow | Error / edge | What the user sees | Exit |
|---|---|---|---|
| Open console, developer mode off after restore | route restored | closes, message "Developer mode is off" (S7 table) | enable in Settings |
| `sql` on DB closed by graph switch | mid-query | `graph closed during command` ERR (UX-40) | rerun |
| `sql` over 10 s | timeout | CANCELLED + `Stopped after 10 s. Partial rows kept.` + `EXPLAIN QUERY PLAN` chip | add LIMIT/WHERE |
| `sql` reading a credential-alias column (only on drivers where Spike 0.3e = YES) | denied by engine authorizer | `Denied: column holds a credential alias` | edit |
| Backup fails before a write | write not run | `Backup failed: <reason>. Nothing was changed.` (UX-55) | fix storage, retry |
| Restore fails | rollback to `*.pre-restore` | error names backup path, Copy details; backups list stays reachable | retry or close |
| Process killed, screen turned off or app backgrounded mid-restore (GAP-4) | the dialog keeps the screen on while visible; backgrounding does not cancel the restore while the process lives; a `RestoreJournal` marker is written before the first rename and cleared after the outcome | at the next launch, before the graph opens, a found marker rolls the half-swap back to `*.pre-restore` (or finishes a verified copy-in) and the first open after the recovery shows a one-time in-app banner, independent of developer mode, "A restore was interrupted - your previous data was put back. Nothing from the restore was kept." (or "...was finished.") with OK and Details (Settings > Developer > Console backups when developer mode is on, the Logs screen otherwise), then the same notice stays in Settings > Developer > Console backups; the banner is dismissed once and does not return; no foreground service or notification is used | read the banner, retry the restore |
| Restore runs longer than expected (UX gap 2) | the progress dialog shows an elapsed-time line ("Elapsed 2:10", updated every second, not announced) and, once a step passes twice its expected time (floor 3 minutes), adds "This is taking longer than expected. Keep SteleKit open. If it is interrupted, your previous data is put back the next time SteleKit starts." with a Backups link; nothing is cancelled automatically and Back stays disabled for the restore | wait, or open Backups |
| A restore fails and the automatic rollback fails too (UX gap 2) | outcome "Restore failed and your previous data could not be put back automatically. Your files are safe at the paths shown." with Copy details and Close SteleKit; the journal marker is kept so the next launch retries the rollback | Close SteleKit, restart |
| Process dies while the `sql!` confirm or the restore confirm is open (UX gap 11) | nothing ran: no backup, no write, no marker; the confirm is not restored on relaunch | start again |
| `sql!` count grew between confirm and write (UX gap 5) | the write is rolled back before commit: "More rows would change than you confirmed (N now, M when you confirmed). Nothing was changed. Run it again to re-confirm." | run it again |
| `sql!` statement whose rows cannot be counted, or that touches several tables or triggers (UX gap 4) | triggers or several tables: the confirm lists each table with its count and requires the typed count; uncountable: refused with "Can't count the rows this would change, so it won't run. Nothing was changed." | rewrite the statement |
| Rotate, fold, split-screen or font-scale change during the restore confirm or progress (GAP-6) | typed text, the progress step and scroll position are preserved; the restore is not restarted or cancelled; Back stays disabled | none needed | none |
| `sql!` in a build without write code (release before pass 12.1) | not available | `help` lists `sql!` as `disabled: writes not enabled in this build`; running it prints exactly that text; the Backups list and every Restore entry are absent or disabled with the same reason | use a dev build |

### C5 delta flow: restore, with loss stated and an undo

```
Backups                                         Restore confirm
+---------------------------------+    +-------------------------------------------+
| mygraph-2026-10-25 14:02  2.1 GB|    | Restore the 2-day-old backup?             |
|   9,402 pages · 61,880 blocks   |    | Backup (2 days ago): 9,402 pages          |
|   < Restore >   < Copy path >   |    | Now: 9,431 pages; 29 pages and 1,204      |
| Before restore 2026-10-27 09:10 |    | blocks differ and will be lost.           |
|   < Undo restore >  < Delete >  |    | 3 local-only journal rows will be saved   |
+---------------------------------+    | to console-recovery/ first.               |
                                       | Type RESTORE to continue: (          )    |
                                       | < Cancel >      < Restore 2-day-old backup>|
                                       +-------------------------------------------+
Progress (blocking, no Back)                   Outcome
+-------------------------------------+   +-------------------------------------+
| Restoring... Keep SteleKit open.    |   | Restored. 9,402 pages - matches the |
| Step 3 of 4: Swapping database      |   | backup. < Undo restore > < Done >   |
| (a few minutes for a large graph)   |   +-------------------------------------+
+-------------------------------------+
```

- The confirm states, from live data: the backup's age, page and block counts of the backup versus now, how many rows differ, and how many DB-only journal rows are exported first (and where). The destructive button carries the verb and the age. Default focus is Cancel.
- Progress is a blocking dialog (the `StorageMoveProgressDialog` precedent; this is a blocking operation), steps are named, Back is disabled, the step line is a polite live region. It is the one place in this design that blocks, because a half-finished swap is dangerous.
- Outcome states row counts versus the backup (success) or the failure text with the backup path (failure, with automatic rollback). Failure never leaves the owner without a way back: the Backups list stays reachable.
- **Undo**: the `*.pre-restore` files are kept as one set, shown as "Before restore <time>" in the Backups list with `Undo restore` and `Delete`; the set survives app restart; the next restore replaces it only after the confirm says so ("This replaces the earlier undo copy from <time>"). **The loss of the undo copy is surfaced in the restore confirm before the swap, never afterwards (GAP-5)**: if free space after the snapshot copy would fall under the 1.2x backup rule the confirm adds "Not enough space to keep an undo copy. Continue without undo?" with the destructive button relabelled to say so and Cancel still the default; nothing is deleted until the owner confirms. **Undo of an undo (GAP-14)**: `Undo restore` runs the same swap with the same confirm and exchanges the live database and the `*.pre-restore` set, so the set then holds the state that was live just before the undo; the Backups list always shows exactly one "Before restore <time>" entry (never two sets), and undoing again returns to the first state. **Confirms on the follow-ups (UX gap 7)**: `Delete` on that entry asks "Delete the undo copy from <time>? You won't be able to undo the restore afterwards." with Delete and Cancel (Cancel default); `Undo restore` asks the standard confirm (Cancel default) and does not need typed RESTORE, because it is reversible and returns a state the owner already had.

---

## 3. D1. Scheduled sync section

Home: a "Scheduled sync" section in the existing git sync settings (`FolderSyncSettings.kt` neighborhood, plan Story 2.3), not a new `SettingsCategory`. Built from `SettingsSection` and `SettingsToggleRow`.

**Copy truth rule.** Until U16 ships the pull step (plan Story 2.4) a scheduled tick only fetches: it changes no file in the working tree and no commit. All copy therefore says "check" and "checked", never "sync" or "synced". When U16 ships, the label becomes "Sync automatically" and the status verb "synced", in the same PR, and UW-17 and UW-65 are re-pointed then.

```
Settings > Git sync                                (compact, 360 dp)
+--------------------------------------------------------+
| Scheduled sync                                         |
| Check for updates automatically               ( off )  |
| Checks the remote while SteleKit is open.              |   desktop
| Also checks about every 15 minutes while SteleKit is   |   Android
| closed. Open the app to merge.                         |
| Last checked 3 min ago · next in 12 min                |
|--------------------------------------------------------|
| Check every                                            |
|  (o) 5 minutes   ( ) 15 minutes                        |   Android: 5 is disabled,
|  ( ) 30 minutes  ( ) 60 minutes                        |   "Android runs at most every 15 minutes"
| Runs every 15 min on this device.                      |   Android effective interval
+--------------------------------------------------------+
```

Behavior: the switch defaults off. The picker is a radio group with fixed values (no free text, so invalid values are impossible), rows at least 48 dp. **Android clamp**: WorkManager's floor is 15 minutes, so on Android the 5-minute option is disabled with the reason "Android runs at most every 15 minutes", and when a stored value below 15 exists the section shows the effective interval ("Runs every 15 min on this device") instead of a selected radio that is not true. Interval changes restart the loop. The status line updates as a polite live region on change only and never takes focus. A scheduled run never opens a dialog and reports only through the existing `SyncStatusBadge` and the staleness chip (Console design S1, S15, UX-86 precedence).

Flow:
1. Owner opens Settings > Git sync. Switch off, status line reads "Not scheduled".
2. Owner turns the switch on. Takes effect at once; first tick runs shortly after load (plan Story 2.0 / Task 2.0b implements it; Task 2.1b proves it through the stack). Status reads "Waiting for first check".
3. Tick succeeds: "Last checked just now · next in 15 min".
4. Owner picks another interval: loop restarts, "next in N min" recomputed.
5. Owner turns the switch off: status "Not scheduled", loop stops, in-flight tick finishes or is cancelled at the next safe point (the plan's loop owns this; the UI shows "Stopping…" only if it lasts over 2 s).

States and errors:
| State | UI | Exit / next action |
|---|---|---|
| No git remote configured | Switch disabled, reason "Set up Git sync first", button "Set up Git sync" opens the wizard | complete wizard or leave |
| First sync not yet reviewed (Console design S14, UX-81) | **Deferred to U16 (UW-16).** This plan does not fund dev Task 2.2d0 and the review flag does not exist yet, so until U16 the switch is not gated on a review. The interim tick is fetch-only and changes no local files (UW-69), so no review is needed to protect notes. Once dev Task 2.2d0 ships: switch disabled, reason "Review first sync to enable", button opens S14 | review, or leave |
| Previous tick still running | tick skipped silently; status line "Check in progress"; never queued | wait |
| Offline | status "Offline - will resume when connected"; no error badge | none, resumes by itself |
| Auth failed / branch missing / conflicts / mass delete blocked | status "Needs attention - tap to fix"; tap routes to the existing badge action for that error (Console design S1 table); mass-delete confirm is never offered from a scheduled run | fix, or switch off |
| 3 consecutive failed ticks | loop pauses; row reads "Paused after 3 failures - tap to fix" with the last failure cause; a `Resume` button is **always visible while paused** (the UI cannot know whether the cause is fixed, so it does not guess): `Resume` runs exactly one check now; success clears the pause, failure keeps it paused and shows the new cause | fix then Resume, or switch off |
| Pause seen outside Settings | `SyncStatusBadge` shows "Sync paused" within one tick of the third failure (amber, an icon shape different from the error state, tap opens Settings > Git sync). This is the surface for an owner who is not looking at Settings (UW-68) | tap the badge |
| Android clamp (stored interval below 15) | 5 disabled with reason; effective interval line | none |
| Android battery saver, Doze or a restricted standby bucket (UX gap 10) | status line "Background checks are restricted by Android. Checks run while SteleKit is open." replaces the closed-app sentence of the helper line; informational, not an error (no badge change); polite live region on change; clears when the restriction ends; never shown on desktop (UW-103) | none, or lift the restriction in Android settings |
| Settings read failure | treated as off (plan Risk Control: flag unset is inert) | none |
| Process death / app closed (Android) | status shows last success time; WorkManager runs fetch-only; text "App closed: updates are fetched, open the app to merge" (Console design ADR-004 wording, amber, never green) | open app |

Accessibility: the switch row is one toggleable node labelled "Check for updates automatically" (verify `SettingsToggleRow` is not two nodes); radio group with selected-state announcements and, for the disabled option, the reason as its state description; Tab reaches every control, Space toggles; status text readable at 2.0x font scale without truncating numbers (wrap); the paused row and badge convey severity with text plus icon shape, not color alone.

Exit path: Back closes Settings with all values already saved (no Save button). Nothing here is a dead end: each disabled control names its reason and a button toward the fix.

---

## 4. P1. Settings > Plugins (on-device)

Replaces the "No plugins installed" placeholder (`PluginsSettings.kt:12-16`). No new category.

**Interim state (U7 to U10).** The full list below ships in U10. From U7 the page holds exactly one row so the owner can switch indexing on for pass 12.2a before the plugins exist:

```
Plugins                                   (U7 to U10)
+--------------------------------------------------------+
| Asset indexing                                < off > |
| Scans attached files so they appear in Assets.         |
| Off by default.                                        |
| (when on) Indexing files... / 340 of 2,100 indexed     |
+--------------------------------------------------------+
```

The row is bound to `asset_pipeline_enabled` and appears only in builds where `AssetBuildFlags.PIPELINE_TOGGLE_VISIBLE` is true (debug/internal builds; release builds only after the post-heap-gate flip, plan U7 row). In a build without the flag the page keeps the placeholder text. In U10 this row becomes the master switch above the plugin list (UW-97).

```
Plugins
+--------------------------------------------------------+
| Make images and PDFs searchable                        |
| Runs on this device. Nothing is uploaded.              |
|--------------------------------------------------------|
| Text recognition                              < off > |
| Finds text in images                                   |
| Image labels                                  < off > |
| Describes what is in a photo (model not installed)*    |
| PDF text                                      < off > |
| Reads text from PDFs                                   |
|--------------------------------------------------------|
| (progress row, section 7)                              |
|--------------------------------------------------------|
| Cloud description (Anthropic)                     >    |   -> section 6 (P3)
+--------------------------------------------------------+
* only on desktop when the labeling model file is absent
```

Flow: toggling a switch on enables the plugin and starts indexing only (plan Risk Control stage order); the progress row (section 7) appears. Toggling off stops queued work for that plugin and keeps already extracted text. The status bar reads "N plugins active" truthfully (plan Story 5.5), and nothing when 0. **Decision (GAP-2): the cloud plugin counts** in N while its switch is on and consent is granted for the active graph (the count is `PluginRegistry.enabledCount`, the single source), so the bar never says "0 plugins active" while images are leaving the device.

States and errors:
| State | UI | Exit / next action |
|---|---|---|
| Plugin unsupported on this platform (no ML Kit, Web) | Row disabled with reason, for example "Not available on this platform" | none |
| Labeling model absent (desktop ONNX) | Row disabled, "Model not installed" with a "How to install" row that expands one line of text and a Copy path button | install file, return |
| Plugin settings still loading (first render of the page) | rows show "Loading..." with their switches disabled, never a stale `off`; replaced by the real state when the read completes | wait |
| Plugin settings read fails | rows render off with "Couldn't load plugin settings" and `Retry` | retry or leave |
| Enable fails to initialize (native lib missing, `UnsatisfiedLinkError`) | switch returns to off, inline "Couldn't start text recognition - see log" with "View log" opening Logs; the inline line is a polite live region (focus stays on the switch, the message is announced once) | leave off |
| Plugin circuit breaker open (repeated failures, plan Task 5.1b) | row shows "Paused after repeated errors" with "Resume" and "View log" | resume or leave off |
| Settings write fails | switch snaps back to its stored value with "Couldn't save this setting" | retry by toggling |

Accessibility: every row is one toggleable node whose description includes the helper text; reasons are text, not color; all targets at least 48 dp; keyboard Tab/Space; the enable-failure message is a polite live region so a switch that snaps back is explained to TalkBack users (UW-76).

---

## 5. P2. CloudClaude consent dialog

Confidential data: images may contain anything. Three layers: off by default, explicit per-provider and per-graph consent, visible caps (plan Stories 6.2, 6.3; research/ux.md section 3).

```
Trigger: owner flips "Cloud description (Anthropic)" on
+------------------------------------------------------+
| Upload images to Anthropic?                          |
|------------------------------------------------------|   <- body scrolls
| Graph: "Work notes"                                  |
| Images attached to this graph's notes will be sent   |
| to Anthropic to get a text description.              |
|                                                      |
| Don't enable this if your images contain anything    |
| you wouldn't send to a third party.                  |
|                                                      |
| Which images:                                        |
|  (o) Only images you attach from now on              |
|  ( ) Also the 1,760 images already in this graph     |
|      (at most 100 a day, about 18 days)              |
|                                                      |
|  - Location data (EXIF) is removed first.            |
|    (or, when not shipped: Location and camera        |
|     details stored in the image are sent too.)       |
|  - Up to 20 images per app launch, 100 per day.      |
|  - Images over 8 MB are skipped.                     |
|  - Only on an unmetered network.                     |
|  - You can turn this off at any time.                |
|  How Anthropic handles uploaded images (link)        |
|------------------------------------------------------|
|          < Cancel >        < Enable uploads >        |   <- pinned
+------------------------------------------------------+
```

**Honest metadata disclosure (GAP-1).** The dialog always says what happens to the image's embedded metadata. When Task 6.1b (EXIF/GPS strip) is merged the bullet reads "Location data (EXIF) is removed first." When it is not merged the same slot reads "Location and camera details stored in the image are sent too." The absence of a protection claim is not disclosure: the dialog never stays silent about metadata, and it never claims removal unless the stripping ships (UW-34, UW-83).

**"Per app launch" is defined (GAP-3).** The owner-facing word is "launch", not "session": the launch counter starts at zero each time the app process starts, is not reset by switching graphs or by backgrounding, and is app-wide (not per graph). "Per day" is the local calendar day, resets at local midnight and survives a restart (plan Story 6.3). The P3 page uses the same words (UW-85).

**Layout at small sizes (GAP-7).** The title and the action row (Cancel, Enable uploads) are pinned: only the body between them scrolls, so both buttons are reachable at 360 dp and 2.0x font scale without scrolling. The "Don't enable this if..." warning sits directly under the introduction, above the scope choice and the bullets, so it is read before the owner reaches the buttons (UW-89).

**Scope is stated, not implied.** The dialog names the graph (the app is multi-graph; consent is per graph) and says exactly which images upload. The default is the safe one: only images attached after consent. The second option ("Also the N images already in this graph") shows the live count of undescribed images from the pipeline's unprocessed count and the day estimate from the live daily cap, so the owner who has a 9.5k-page graph sees the size of the backlog before choosing it. With the default, zero requests are made for older images (plan Story 6.2, `CloudBackfillScope`). The choice is stored with the consent and can be changed later on the P3 page (changing to "include existing" re-asks). When the live count of undescribed existing images is 0, the second option is not shown and the first is plain text, not a one-item choice (UX gap 9, UW-102); in both cases the dialog says "Descriptions are saved in this graph and can be searched."

**Network line is platform-true.** On Android the line reads "Only on an unmetered network". Desktop has no metered signal (`NetworkMonitor` exposes only online/offline), so there the line reads "Only while online" and the dialog never claims unmetered. There is no "upload anyway" override on a metered connection anywhere (UW-41, UW-64).

Numbers shown are read from the live config (`sessionCap`, daily cap, size cap), not hard-coded in copy, so the dialog cannot disagree with the pipeline.

Flow:
1. Flip switch: it does not move yet; the dialog opens. Default focus = Cancel; focus is trapped; the title is announced once on open.
2. Cancel, Escape, Back, or tap outside: dialog closes, switch stays off, `CloudConsent.NotAsked` unchanged, focus returns to the switch.
3. Enable uploads: `CloudConsent.Granted(anthropic, now)` is stored for this graph only. Dialog closes, switch turns on, focus returns to the switch. If no API key is stored yet, the P3 key section opens inline and uploads stay idle with the text "Add an API key to start" (consent without a key sends nothing).
4. Opening the app on a different graph: that graph shows the switch off until it is consented (plan: per graph and provider).

States and errors:
| State | UI | Exit |
|---|---|---|
| Consent store write fails | dialog stays, line "Couldn't save your choice - nothing will be uploaded", buttons Retry and Cancel | Cancel |
| Offline when enabling | consent can still be granted; row shows "Waiting for network" (Android on a metered network: "Waiting for unmetered network") | none |
| Process killed with dialog open | on restart switch is off, `NotAsked` | none |
| Double tap on Enable | second tap ignored after first | none |

Accessibility and state: the scope radio selection and scroll position survive rotation, fold/unfold, split-screen resize and font-scale change (UW-88); dialog `Role` and title announced; Cancel is first focus stop, the scope radio group next, Enable uploads last (the destructive-adjacent action is never the default); Escape cancels; no auto-accept on timeout; contrast at least 4.5:1 for text and 3:1 for control boundaries in both light and dark themes; severity or warning is conveyed by text and an icon shape, not color alone; wraps at 2.0x font scale; at 600 dp and wider the dialog is at most 560 dp wide and centered (UW-77, UW-78).

---

## 6. P3. Cloud plugin settings (key, caps, counter, revoke)

Reached from Settings > Plugins > "Cloud description (Anthropic)" (a sub-page, so the main list stays short).

```
Cloud description (Anthropic)
+--------------------------------------------------------+
| Upload images for descriptions                < off > |
| Consent: granted 2026-10-27 · this graph only          |
|--------------------------------------------------------|
| API key (shared with Settings > LLM > Anthropic)         |
| Using your Anthropic key  < Change in Settings > LLM >   |
| < Test key >   < Forget API key >                      |
| "Test key" sends no image.                             |
|--------------------------------------------------------|
| This launch: 12 of 20 uploaded                         |
| Today: 31 of 100                                       |
| Limit per launch < 20 v >   Skips images over 8 MB     |
|--------------------------------------------------------|
| Status: Uploading · Waiting for network ·              |
|         Paused: launch limit reached                   |
+--------------------------------------------------------+
```

Flow:
1. There is one Anthropic key: the one stored by `LlmCredentialStore` (`llm.anthropic.api_key`) via the Settings > LLM screen (plan ADR-005). This page shows "Using your Anthropic key" with a link to Settings > LLM, or "Add an API key in Settings > LLM" when none exists; it has no second key field.
2. "Test key" performs one call that uploads no image, then shows "Key works" or the typed error (below). Result is a persistent line, not a timed toast.
3. Launch limit picker ("Limit per launch"): fixed choices (10, 20, 50; default 20), never above the daily cap; the counter resets when the app starts, not on a graph switch, and "today" resets at local midnight (GAP-3). The counters read "This launch: 3 of 20, all graphs" and "Today: 12 of 100, all graphs", because they are app-wide while consent and this page are per graph (UX gap 8, UW-101). The scope choice from the consent dialog is shown here too ("Images: only new ones" or "Including 1,760 existing").
4. Revoke: switching the main switch off (or "Revoke consent") takes effect immediately. Queued cloud items are dropped without upload and the line reads "5 queued items dropped" (plan). **Revoke is immediate and has no confirm dialog** (it is the safe direction; there is no separate revocation confirmation, UX gap 6, UW-99). **Revoke copy (GAP-2)**, shown under the switch: "Descriptions already received are kept. Images already sent can't be recalled from Anthropic.", with the plan's "Delete AI descriptions" action beside it, which asks a standard confirm first ("Delete AI descriptions for <graph>? N descriptions will be removed from this graph's search. Images already sent can't be recalled.", Delete and Cancel, Cancel default, UW-100). Revoke cannot unsend anything and the copy says so. `CloudConsent.Revoked` is stored. Turning it on again re-asks consent (P2); a revoked state is not silently re-granted.
5. "Forget API key" shows an inline confirm "Delete the stored Anthropic key? Cloud descriptions and Claude voice formatting stop until you add one." buttons `Delete key` and `Cancel`; Cancel is default.

States and errors:
| State | UI | Exit / next action |
|---|---|---|
| No key | counter hidden, status "Add an API key to start" | enter key |
| Invalid key (401) | "Key rejected - check it in your Anthropic account" | edit key |
| Rate limited / 5xx (429, 5xx) | asset marked failed, no retry loop (plan); status "Anthropic is busy - failed items can be retried" with "Retry failed" button | retry or ignore |
| Offline | "Waiting for network"; no error badge | resumes by itself |
| Metered network (Android only; desktop has no metered signal) | same text "Waiting for unmetered network"; there is no override | switch networks or wait |
| "Test key" in flight | button disabled and relabelled "Testing..." (live region politely announces "Testing key"), other controls stay usable; result replaces it as a persistent line | wait |
| Launch limit reached | pipeline stops; status "Paused: 20 of 20 uploaded this launch" with "Raise limit" (opens picker) and "Continues after the next app start" (informational) | raise or wait |
| Daily cap reached | "Paused: 100 of 100 today. Resumes tomorrow." | none needed |
| Image over 8 MB | counted as skipped; "1 skipped (over 8 MB)" visible in the failures line | none |
| Key store read fails | treated as no key; "Couldn't read the stored key" with "Re-enter key" | re-enter |
| Settings opened on a graph without consent | switch off, line "No consent for this graph", tapping the switch opens P2 | cancel |

Accessibility: severity and state text (rejected key, paused, busy) carries an icon shape as well as color and meets 4.5:1 in light and dark themes; the counter is read as one phrase ("12 of 20 uploaded this launch") and updates as a polite live region throttled to every 5 uploads; key field has a label and "password" semantics; Show button has a stateful description; wraps rather than ellipsizes numbers at 2.0x.

**Where the owner sees what left the device (GAP-2).** After consent the egress surfaces are the P3 counters, the status bar count (above) and a persistent per-asset marker: an asset whose description came from the cloud shows "Described by Anthropic" (text plus an icon shape, not color alone) in the asset detail screen and as a small badge on its grid cell. The marker is read from the stored source (`ml_tags_source = CLOUD_CLAUDE`), so it stays after revoke and after a restart. It marks images whose description came back; an image that was uploaded but whose description failed is not marked, and the complete record of uploads is the N2 log line. The badge has a content description, not only a glyph (UW-84).

---

## 7. P4. Pipeline progress and backfill row

Backfill over a 9.5k-page graph can be long, so it is bounded, cancellable, and visible. A row in Settings > Plugins, not a modal (a modal is for blocking operations such as `StorageMoveProgressDialog`; this runs in the background).

```
Image processing
+--------------------------------------------------------+
| 340 of 2,100 done · 12 failed - view log               |
| |=========>                                        |   |
|                  < Pause >  < Cancel >                 |
+--------------------------------------------------------+
```

States derived from `PipelineProgress` (plan Story 5.1) and the charging gate (plan Story 5.5):
| State | Row text | Controls |
|---|---|---|
| Idle, nothing to do | hidden (no empty row) | none |
| Idle, unprocessed assets exist, plugin on | "2,100 images waiting" | "Process now" |
| Waiting for charger (Android) | "Waiting for charger" | "Process now", Cancel |
| Running | "340 of 2,100 done · 12 failed - view log" + determinate bar | Pause, Cancel |
| Paused by owner | "Paused at 340 of 2,100" | Resume, Cancel |
| Paused: memory pressure (`onTrimMemory`) | "Paused: low memory. 340 of 2,100 done" | Resume (re-checks pressure), Cancel |
| Paused: cap or network | text from P3 | per P3 |
| Cancelled | "Stopped at 340 of 2,100. Already processed items are kept." | "Start again" |
| Finished clean | "All 2,100 images processed" (persistent until next run; Dismiss) | Dismiss |
| Finished with failures | "2,088 done · 12 failed - view log" | "Retry failed", Dismiss |
| Plugin crashed (Throwable contained) | counts toward failed; no dialog | none |
| Graph switched / app backgrounded | run cancelled (plan); on return row reads "Stopped when you switched graphs" | Start again |

Rules: never takes focus or opens a dialog (inherited principle). "view log" opens the Logs screen already filtered to pipeline lines (`Logger` persisted sink) and is reachable with developer mode off (Logs is in the top-bar overflow menu; the filter is an argument of the route, not a Developer-category feature; GAP-13, UW-95); no dialog per failure. Pause takes effect after the current asset finishes; Cancel stops after the current asset and is not instantaneous, so the button shows "Stopping…" disabled until confirmed. After Cancel, nothing already extracted is deleted.

Accessibility: bar has `ProgressBarRangeInfo`; live-region announcement throttled to every 10% and on state change; with the system animation scale at 0 (reduced motion) neither the determinate nor any indeterminate indicator animates and the text carries the state (UW-80); Pause/Resume/Cancel are at least 48 dp with distinct labels; the counts wrap at 2.0x font scale.

Exit path: leaving Settings does not stop the run; the status bar's plugin count still shows. Cancel is always available.

**Background visibility decision (GAP-10).** No notification and no foreground service. Backfill runs only while the app process is alive and honors the charger gate; scheduled sync checks run through WorkManager while the app is closed and write only a log line. A backfill that paused (memory, caps, charger) and a sync loop that paused after 3 failures are therefore visible only in-app: the Plugins progress row, the sync badge outside Settings (UW-68) and the Logs screen. This is a deliberate choice for a single-owner app (a notification would also need a channel, a Cancel action and a permission on Android 13+), recorded so its absence is not mistaken for an oversight; it is revisited only if pass 12.2a shows the OS killing a charging backfill (UW-92).

---

## 8. A1. Asset browser empty states and enable prompt

Today `AssetBrowserEmptyState.kt` only says to attach a file. New states, in order of precedence:

```
No assets at all                         Assets exist, no plugin enabled
+-----------------------------------+    +------------------------------------+
|  No files yet                     |    | (normal grid)                      |
|  Attach an image or PDF to a      |    | +--------------------------------+ |
|  note and it will appear here.    |    | | Make images searchable -       | |
+-----------------------------------+    | | turn on text recognition       | |
                                         | | < Turn on >        < Not now > | |
                                         | +--------------------------------+ |
                                         +------------------------------------+
```

Behavior:
1. The prompt (plan Story 5.6: "Make images searchable - turn on text recognition") is a dismissible inline card above the grid, shown only when at least one image exists and no on-device plugin is enabled. "Turn on" opens Settings > Plugins scrolled to the row (it does not flip the switch itself). "Not now" hides the card and stores the dismissal; it never nags per launch. **Clearing it (GAP-12)**: Settings > Plugins has a row "Show the searchable-images suggestion again" (visible only while the card is dismissed) that clears the dismissal.
2. Search with no results while the pipeline is incomplete: "No matches. 1,760 images are still being processed" with a link to P4. Search with no results and nothing pending: "No matches".
3. No assets but a filter is active: "No files match this filter" with "Clear filter".

Errors and edges:
| State | UI | Exit |
|---|---|---|
| Asset index not yet built (first launch after update) | "Indexing files…" with indeterminate progress and count when known; under reduced motion the indicator is static and the text/count carry the state | wait, or leave |
| Pipeline running or waiting for charger (inline progress) | one line above the grid: "Processing 340 of 2,100 - details" (or "Waiting for charger - details"); "details" opens the P4 row; hidden when idle (UW-81) | open details, or ignore |
| Asset index failed | "Couldn't read your files - see log" with "Retry" and "View log" | retry or leave |
| Platform has no on-device plugins | card not shown | none |
| Plugin enabled but model missing | card replaced by "Image labels need a model file - see Settings > Plugins" | open settings, or leave |

Accessibility: card is one node with the full sentence; buttons at least 48 dp; the card is not a live region; empty states read once on screen open.

---

## 9. A2. Extracted text in Assets search and global search

Results must be visible or the pipeline is invisible (research/ux.md section 3).

```
Assets search bar: query "4821"
+--------------------------------------------------------+
| < thumb > invoice.png         "...Invoice 4821..."       |
| < thumb > scan-2.pdf          "...ref 4821 due..."       |
+--------------------------------------------------------+

Global search dialog, query "4821"
 Pages        (existing sections)
 Assets (2)   invoice.png  ...Invoice 4821...
              < Show all in Assets >
```

Behavior: the Assets search bar searches extracted text via `searchAssets` (limit 50) and shows a one-line match snippet with the matched term emphasized by bold weight on a tinted background, not color alone and never underline (underline is the tappable-link convention and would read as a link; GAP-11, UW-93). The global search "Assets" section uses a bounded query (limit 5, plan) placed after page and block sections; "Show all in Assets" opens the Assets browser with the query prefilled. Selecting a result opens the asset detail, or the note that references it when invoked from global search.

Errors and edges:
| State | UI | Exit |
|---|---|---|
| Assets query in flight (slower than 200 ms) | Assets bar: "Searching..." under the field; global dialog: a "Searching files..." row in the Assets position while the page and block sections stay interactive; a result or the failure row replaces it (UW-75) | wait |
| Assets query fails | section shows "Couldn't search files - try again" with Retry; other sections still render | Retry or ignore |
| No asset matches | section hidden in global search (no empty header); Assets bar shows "No matches" (A1) | none |
| Cloud description matches | snippet carries the label "AI description" (and the result's thumbnail carries the "Described by Anthropic" badge of section 6) so cloud-derived text is distinguishable from OCR (stored in the existing `asset_index.cloud_description` column with `ml_tags_source=CLOUD_CLAUDE`, plan Story 6.2 / Task 6.2d; never in `ocr_text`) | none |
| Very long snippet | cut at about 120 characters with ellipsis; full text in detail | open detail |
| Asset deleted after search | result removed on next refresh; tapping a stale row shows "This file is gone" | back |

Accessibility: each result is one node reading name plus snippet; keyboard Up/Down/Enter in the global dialog already exists (`SearchDialog.kt:110,125,157`) and the new section joins the same order.

---

## 10. Non-interactive surfaces (condensed)

### N1. Scheduled-sync tick line
```
INFO sync.tick graph=ab12 decision=fetch outcome=OK dur=840ms lastSuccess=2026-10-27T14:03:00Z next=15m
WARN sync.tick graph=ab12 decision=fetch outcome=AuthFailed consecutiveFailures=2
```
- One line per tick, via `Logger` to the persisted sink, secrets redacted by the shared `Redactor`.
- `decision=fetch` is the only value before U16 (the interim tick is fetch-only and changes no local file). `decision=full` appears only after U16 ships the pull step (plan Story 2.4); a `decision=full` line before then is a defect (UW-69).
- Uses wall-clock time for `lastSuccess` (not monotonic), matching the settings status line.
- A skipped tick (busy) logs `outcome=Skipped(busy)`, not an error.
- After 3 consecutive failures one `WARN sync.paused` line is written once.
- Appears in the existing Logs screen with its level filter.

### N2. Cloud egress and pipeline lines
```
INFO asset.cloud upload asset=7f3a bytes=412330 provider=anthropic
INFO asset.pipeline state=Paused reason="session cap" done=20 total=2100
```
- One INFO line per upload with asset uuid, bytes, provider; never image content, never the key.
- One line per pipeline state change and per revocation (`dropped=5`).
- Failures list asset uuid and typed error, no path of private notes beyond the asset uuid.

### N3. Console audit lines
Adopted from Console design N2: one INFO per command, one WARN per SQL write with arguments through the redactor.

---

### 10a. Small-wiring surfaces (Epics 7 and 8, compact)

| Surface | Plan story | Copy and states | Exit |
|---|---|---|---|
| Archive a URL (desktop right-click on a link) | 7.1 | snackbar "Archiving https://example.com/a…" (persistent until done), success "Archived - link added to the block", failure "Couldn't archive - Retry" (no silent failure) | Retry or dismiss |
| Rich-text paste (desktop) | 7.2 | plain text pastes as before; HTML becomes sibling blocks; if the clipboard HTML exceeds the converter cap, a notice "Pasted the first part - the rest was too large" appears once | none |
| "From Google Photos" in the attach menu (Android) | 8.1 | signed in: enabled; signed out: disabled with reason "Sign in to Google in Settings" and a button to that screen; picker loading shows "Opening Google Photos…"; import failure "Couldn't import 2 photos - Retry" | sign in, retry |
| "Export to Drive" on an annotated image | 8.2 | enabled when signed in, otherwise disabled with the same reason; in flight "Uploading…" with Cancel; success shows the Drive link; a zero-byte image is refused with "Couldn't encode the image - nothing was uploaded" | Retry or Cancel |
| "Browse Drive" (Settings > Google account) | 8.2 | first render "Loading…", empty "No files", failure "Couldn't load Drive - Retry" | Retry |

These follow the same rules as sections 3-9: disabled with a reason plus a button toward the fix, persistent result lines, no focus theft, 48 dp targets (UW-82).

---

## 11. Cross-cutting rules for this project

| Rule | Applies to |
|---|---|
| Disabled with reason, plus a button toward the fix | D1 (no remote, unreviewed first sync), P1 (platform, model), P3 (no key), C1 (Web) |
| Background work never takes focus or opens a dialog | D1, P4, A2 |
| Settings are saved immediately; no Save button | D1, P1, P3 |
| Persistent result lines, not timed toasts | D1 status, P3 "Test key", P4 finish |
| Consent is per provider and per graph, revocation is immediate and shows what was dropped | P2, P3 |
| Secrets masked, never logged | P3, N2 |
| Plain-language copy outside the console (Console design UX-92 banned words apply) | D1, P1-P4, A1, A2 |
| Console jargon (SQL, SQLite) allowed only inside the console | C2-C5 |
| Theme tokens; severity conveyed by text plus icon shape, not color alone; text 4.5:1 and control boundaries 3:1 in light and dark themes | all dialogs and states: C4, C5, D1, P1-P4, A1, A2 (UW-77) |
| Wide and foldable screens (600 dp and wider, landscape): content column at most 560 dp and centered, dialogs at most 560 dp, no stretched rows, nothing clipped; compact stays the primary layout | all Settings sub-pages, dialogs, the Assets browser, the console (UW-78) |
| A background change that explains a control's state (enable failure, pause, switch snapping back) is a polite live region and never moves focus | P1, D1, P4 (UW-76) |
| Slow work shows a named loading state after 200 ms, never a blank area | Assets search, global Assets section, Test key, Plugins first render, Drive browser (UW-75) |
| Reduced motion (system animation scale 0): no indicator animates and text carries the state | P4, A1 (UW-80) |
| Text fields: API-key field is a password field with autocorrect off, IME action Done; console input follows Console design UX-84 | P3, C2 (UW-79) |
| Typed confirms (`RESTORE`, row count): exact match after trimming, `RESTORE` case-sensitive, autocorrect and auto-capitalization off, numeric keypad for counts, no pre-fill (GAP-9); the dialog body uses imePadding so the action row stays visible with the keyboard open | C4, C5 (UW-91) |
| Dialog and progress state survives configuration change (rotation, fold/unfold, split-screen, locale, font scale): selection, typed text, scroll and restore progress are kept and nothing restarts (GAP-6) | P2, C4, C5 (UW-88) |
| Long dialogs pin their action row; only the body scrolls, also with the soft keyboard open (GAP-7) | P2, C4, C5 (UW-89) |
| Background work posts no notification; its paused state is visible in-app only (GAP-10) | D1, P4 (UW-92) |

---

## 12. UX acceptance criteria

Tags: (A) accessibility. All are human-testable on device or desktop; where a criterion has an automated assertion and a device step, the **criterion passes on the automated assertion** and the device step is tracked as an Epic 12 item (it never makes a criterion pass or fail by itself). UW-01..UW-62 keep their numbers from the first revision; UW-63..UW-82 were appended in triad repair iteration 3 and UW-83..UW-97 in iteration 4, UW-98..UW-106 in iteration 5. Criteria inherited from the Console design are referenced by number and not restated; the console block below lists only the ones this project's slice must demonstrate or changes.

**Console slice (C1-C5; inherited criteria in parentheses)**
1. UW-01: On a fresh install Settings > Developer shows only the toggle and the warning "Lets you run SQL writes and shell commands. Your notes can be changed or lost."; no Dev console row appears in Settings or the Logs overflow (UX-20).
2. UW-02: On a platform where libsql and MCP callbacks are both null (Web), the Developer category is still listed with the toggle.
3. UW-03: Turning developer mode on reveals "Dev console" in Settings and the Logs overflow without a restart; turning it off while the console is open closes it, cancels the running command and clears scrollback (UX-21, UX-22).
4. UW-04: The owner can run `sql select name from pages order by updated_at desc limit 3` and read a grid with header, three rows and an OK chip in at most 3 taps plus typing the statement, or 2 taps via the `sql: newest journals` chip.
5. UW-05: A plain `sql` write shows ERR `Denied: plain sql is read-only. Use sql! for writes.` and the database is unchanged; it is never a silent no-op.
6. UW-06: A query running over 10 s ends CANCELLED with "Stopped after 10 s. Partial rows kept." and the Cancel button works at any time before that.
7. UW-07: A query returning more than 500 rows shows the TRUNCATED chip, the row-cap footer and an "Export CSV" action (UX-66).
8. UW-08: A write via `sql!` shows the verbatim statement, row-count text, trigger line and backup name; initial focus is Cancel; the button is labelled with the verb and count (UX-51, UX-52).
9. UW-09: If the backup or dry run fails, the block says "Nothing was changed" with the reason and the write does not run (UX-55).
10. UW-10: After a write the result block shows a persistent "Restore backup" action; restoring asks for confirmation stating later changes are lost, and a failure shows the backup path (UX-56, UX-57).
11. UW-11: The backups list shows at most the retained snapshots (default 2 newest, configurable up to 5), marks an unreadable one with a reason, and offers Restore and Copy path per row (UX-85).
12. UW-12 (A): Pass when a Robolectric test of the console screen asserts all of: each finished console block is exactly one focusable semantics node with a summary description; the status chip is the only node with a live region; every chip and extra key has a touch target of at least 48 dp; at 360 dp width and 2.0x font scale no block, chip or key is clipped or moves outside the viewport (UX-44, UX-45). The owner's TalkBack pass is Epic 12 item 12.1; it is a separate named blocker, is not part of this criterion's pass/fail, and is not replaced by tests.
13. UW-13: On Web `sql` is listed in `help` as "disabled: no read-only connection on this platform" and running it prints the same reason.

**D1. Scheduled sync**
14. UW-14: With a configured remote, the owner can enable scheduled sync and choose 5, 15, 30 or 60 minutes in at most 3 taps, with no typing.
15. UW-15: The switch defaults off; with no remote it is disabled with the reason "Set up Git sync first" and a button that opens the wizard.
16. UW-16 (DEFERRED to U16; blocked on dev-plan Task 2.2d0, not funded by this plan; not required for U2 acceptance): Before the first-sync review is confirmed the switch is disabled with the reason "Review first sync to enable" and a button that opens the review.
17. UW-17: After a successful tick the section reads "Last checked <n> min ago · next in <m> min" ("synced" only after U16) and updates without taking focus.
18. UW-18: The platform helper is visible under the switch: desktop "Checks the remote while SteleKit is open."; Android "Also checks about every 15 minutes while SteleKit is closed. Open the app to merge."
19. UW-19: After 3 consecutive failed ticks the loop pauses and the row reads "Paused after 3 failures - tap to fix" with the last cause; tapping routes to the matching fix; a `Resume` button is visible for as long as the loop is paused (see UW-67).
20. UW-20: A scheduled run never opens a dialog, never moves focus, and reports failures only on the sync badge and chip; a mass-delete confirm is never offered from a scheduled run.
21. UW-21: While a tick is running the next one is skipped, shown as "Check in progress", and never queued.
22. UW-22: Offline shows "Offline - will resume when connected" without an error badge, and sync resumes by itself.
23. UW-23 (A): The switch row is one toggleable node labelled "Check for updates automatically"; the interval group is a radio group with selected state announced; every row is at least 48 dp; Tab and Space operate everything.
24. UW-24: Turning the switch off stops the loop within one tick and the status reads "Not scheduled".

**P1. Plugins list**
25. UW-25: Settings > Plugins lists Text recognition, Image labels and PDF text with switches defaulting off, and no longer shows "No plugins installed".
26. UW-26: A plugin unavailable on the platform or missing its model shows a disabled row with a text reason, never a hidden row.
27. UW-27: If a plugin fails to initialize, its switch returns to off and an inline message offers "View log".
28. UW-28: The status bar reads "N plugins active" matching the number of enabled switches and shows nothing at 0.
29. UW-29 (A): Each plugin row is one toggleable node whose description includes the helper text; targets are at least 48 dp; keyboard operable.

**P2/P3. Cloud consent and settings**
30. UW-30: Flipping the Cloud description switch opens the consent dialog first; the switch does not move until "Enable uploads".
31. UW-31: The dialog names the recipient (Anthropic), what is sent (images), the live per-launch, daily and size caps, and the sentence about third parties; its buttons are "Cancel" and "Enable uploads".
32. UW-32: Initial focus is Cancel; Escape, Back, tapping outside and Cancel all leave the switch off and consent unrecorded.
33. UW-33: Consent is stored per graph and provider; on another graph the switch is off until consent is given there.
34. UW-34: The dialog never mentions EXIF removal unless that protection is shipped (the positive disclosure for the not-shipped case is UW-83).
35. UW-35: With consent granted but no key, uploads stay idle and the page says "Add an API key to start".
36. UW-36: The key is the shared Anthropic key (no second field), "Test key" sends no image and shows a persistent "Key works" or a typed error, and the key appears in no log line, setting or diagnostics export.
37. UW-37: Counters "n of 20 uploaded this launch" and "n of 100 today" are visible on the page and the pipeline stops at the cap with a visible "Paused" message, not silently.
38. UW-38: Turning the switch off or revoking consent drops queued cloud items immediately and shows "N queued items dropped"; turning it on again asks for consent again.
39. UW-39: "Forget API key" asks to confirm with Cancel as default and, once confirmed, cloud descriptions stop with the message "Add an API key to start".
40. UW-40: On a 401 the page says "Key rejected - check it in your Anthropic account"; on 429 or 5xx the asset is marked failed with "Retry failed" available and no automatic retry loop runs.
41. UW-41: Offline (any platform) or on a metered network (Android) no upload starts and the status reads "Waiting for network" or "Waiting for unmetered network"; no control exists that overrides it.
42. UW-42 (A): The consent dialog traps focus, announces its title, and is operable by keyboard; the counter is announced as one phrase and throttled; numbers wrap rather than ellipsize at 2.0x font scale; contrast is at least 4.5:1.

**P4. Progress and backfill**
43. UW-43: With a backfill running, the row shows "<done> of <total> done · <failed> failed - view log" with a determinate bar and Pause and Cancel controls.
44. UW-44: Pause takes effect after the current asset and the row shows Resume; Cancel keeps all already extracted text and the row offers "Start again".
45. UW-45: On Android with the device not charging the row reads "Waiting for charger" and offers "Process now".
46. UW-46: Under memory pressure the run pauses and the row reads "Paused: low memory" with a Resume control; nothing crashes.
47. UW-47: Switching graphs stops the run and the row says it was stopped; it is not left showing stale progress.
48. UW-48: Failures are reachable via "view log" and "Retry failed"; no dialog appears per failure.
49. UW-49 (A): The bar exposes range semantics; announcements fire at most every 10% and on state change; controls are at least 48 dp with distinct labels.
50. UW-50: Leaving Settings does not stop the run, and the row, when no work remains, is hidden rather than blank.

**A1/A2. Asset browser and results**
51. UW-51: With images present and no plugin enabled, the Assets browser shows the card "Make images searchable - turn on text recognition"; "Turn on" opens Settings > Plugins at the row and "Not now" dismisses it persistently.
52. UW-52: With no assets the empty state says how to add one; with an active filter and no match it says "No files match this filter" and offers "Clear filter".
53. UW-53: Searching the Assets bar for text extracted from an image returns that asset with a snippet; the match is emphasized by more than color.
54. UW-54: The global search dialog shows an "Assets" section of at most 5 results with "Show all in Assets"; with no matches the section is hidden.
55. UW-55: A no-result Assets search while the pipeline is unfinished says how many images are still being processed and links to the progress row.
56. UW-56: Cloud-derived text is labelled "AI description" in results.
57. UW-57: A failed assets query shows "Couldn't search files - try again" with Retry and does not hide the page and block results.
58. UW-58 (A): Each result is one node reading name and snippet; the global dialog's Up/Down/Enter ordering includes the new section; empty states are read once.

**Cross-cutting**
59. UW-59: Every error state in this document names what happened and offers one next action or an explicit exit; there is no state with only a Close that hides the cause.
60. UW-60: No scheduled, pipeline or cloud action opens a dialog or steals focus; consent and confirm dialogs open only from an explicit owner action.
61. UW-61: Pass when a source audit of the string resources and Composable string literals outside the `console/` packages finds zero case-insensitive whole-word matches of `symref`, `ls-remote`, `invariant`, `DB-only`, `tracking ref`, `refspec`, `detached HEAD`, `shallow`, `OID`, `SQLite`, `WAL` (the list quoted from Console design UX-92, copied once into a test resource so this criterion no longer depends on another document at run time).
62. UW-62: Pass when, for each of these twelve screens (D1 section, Plugins list, Cloud page, Cloud consent dialog, Pipeline progress row, Assets browser with empty states, Assets search results, global search dialog, guarded-write confirm, Backups list, restore confirm, restore progress), a Robolectric test at 360 dp width and 2.0x font scale shows: no horizontal scroll container, no numeric text node ellipsized, and every interactive node's bounds inside the viewport after scrolling vertically.

**Revision 3 additions (triad repair iteration 3)**
63. UW-63: The consent dialog names the graph, states that by default only images attached after consent upload, and offers "Also the N images already in this graph" with N read from the live unprocessed count and a day estimate from the live daily cap; choosing the default makes zero requests for older images.
64. UW-64: The dialog's network line is true per platform: Android "Only on an unmetered network"; desktop "Only while online" (no metered signal exists there), never "unmetered"; no "Upload now anyway" control exists on any platform.
65. UW-65: The scheduled-sync switch label and helper match behavior: the label is "Check for updates automatically" until U16; the Android helper says checks also run while the app is closed and that opening the app merges; no copy says "synced" before U16.
66. UW-66: On Android the 5-minute option is disabled with the reason "Android runs at most every 15 minutes"; a stored interval below 15 shows "Runs every 15 min on this device"; no selected radio shows a value the device will not run.
67. UW-67: While the loop is paused `Resume` is visible and enabled; activating it runs exactly one check; success clears the pause, failure keeps it paused and shows the new cause.
68. UW-68: Within one tick of the third failure the sync badge outside Settings shows "Sync paused" (text plus an icon shape distinct from the error state) and tapping it opens Settings > Git sync.
69. UW-69: Before U16 a scheduled tick leaves the working tree and index byte-identical (hash before and after) and logs `decision=fetch`; no `decision=full` line is ever written.
70. UW-70: The restore confirm shows the backup's age, page and block counts of the backup versus now, the number of differing rows, and the number of local-only journal rows exported first with their path; the destructive button names the verb and the age; default focus is Cancel.
71. UW-71: During a restore a blocking progress dialog shows named steps and "Keep SteleKit open" with Back disabled and a polite step announcement; it ends in an outcome block (success with page count matching the backup, or failure naming the backup path after rollback).
72. UW-72: After a restore the `*.pre-restore` set appears as "Before restore <time>" with `Undo restore` and `Delete`; it survives an app restart; the in-block `Restore backup` action does not (the Backups list is then the only entry).
73. UW-73: A `sql!` statement whose dry-run count is at least 1,000 rows or at least 50% of the table shows "This changes N of M rows in <table>" and keeps the primary button disabled until the exact count is typed; below the threshold the standard modal applies.
74. UW-74: In a build with `ConsoleBuildFlags.WRITES_ENABLED` false, `help` lists `sql!` as "disabled: writes not enabled in this build", running it prints exactly that text, and no Restore entry is enabled.
75. UW-75: After 200 ms the Assets search shows "Searching...", the global dialog shows a "Searching files..." row while other sections stay interactive, "Test key" shows a disabled "Testing..." button, and Settings > Plugins shows "Loading..." with disabled switches on first render; none shows a blank area or a stale `off`.
76. UW-76 (A): A plugin enable failure that snaps the switch back is announced once through a polite live region while focus stays on the switch.
77. UW-77 (A): For every dialog and state in C4, C5, D1, P1-P4, A1 and A2, text contrast is at least 4.5:1 and control boundaries at least 3:1 in both the light and dark themes (token-pair assertions), and severity is carried by text plus an icon shape, not color alone.
78. UW-78 (A): At widths of 600 dp and wider and in landscape, Settings sub-pages and dialogs are at most 560 dp wide and centered, rows are not stretched, nothing is clipped, and the console keeps its compact layout rules (checked at 600 dp, 840 dp and a 720x540 dp landscape size).
79. UW-79 (A): The API-key field has password semantics, autocorrect off and IME action Done; the console input keeps the flags of Console design UX-84 (a keyboard-options assertion; the soft-keyboard behavior itself is Epic 12 item 12.1). The typed-confirm fields have their own spec in UW-91.
80. UW-80 (A): With the system animation scale at 0, the pipeline bar and every indeterminate indicator ("Indexing files...") do not animate and the text states progress.
81. UW-81: While the pipeline runs or waits for the charger the Assets browser shows one line above the grid ("Processing 340 of 2,100 - details") that opens the progress row, and hides it when idle.
82. UW-82: Each Epic 7 and 8 surface in section 10a shows its stated loading, success and failure copy, disabled entries give a reason plus a button toward the fix, and a zero-byte Drive export is refused before upload.

**Revision 4 additions (triad round 2: GAP-1..GAP-14 and the interim Plugins row)**
83. UW-83: When the EXIF/GPS strip (Task 6.1b) is not shipped, the consent dialog contains the line "Location and camera details stored in the image are sent too." and no claim of removal; when it is shipped the dialog says "Location data (EXIF) is removed first." and that line is absent. The dialog never omits both. (GAP-1)
84. UW-84: After consent and a returned description, the asset detail screen and the grid cell show "Described by Anthropic" (text plus an icon shape, with a content description); the marker persists after revoke and restart; the status bar "N plugins active" includes the cloud plugin exactly while consent is granted and its switch is on; the revoke copy reads "Descriptions already received are kept. Images already sent can't be recalled from Anthropic." beside a "Delete AI descriptions" action. (GAP-2)
85. UW-85: Owner-facing copy says "per app launch" and "per day", never "per session"; the launch counter resets when the app process starts and is not reset by a graph switch or backgrounding; the day counter resets at local midnight and survives restart. (GAP-3)
86. UW-86: If the process is killed at any step of a restore after the journal marker is written, the next launch (before the graph opens) rolls back to `*.pre-restore` or completes the verified copy-in, the opened database equals either the pre-restore state or the backup, and Settings > Developer > Console backups shows one notice naming what happened; the first open after the recovery also shows a one-time in-app banner ("A restore was interrupted - your previous data was put back. Nothing from the restore was kept.", or "...was finished.") that is independent of developer mode, offers OK and Details, and does not return once dismissed; the restore dialog keeps the screen on while visible and no foreground service or notification is used. (GAP-4, UX gap 1)
87. UW-87: When free space would force dropping the undo copy, the restore confirm says "Not enough space to keep an undo copy. Continue without undo?" before the swap with Cancel as default, and no `*.pre-restore` file is deleted before the owner confirms; when an earlier undo copy would be replaced the confirm says "This replaces the earlier undo copy from <time>". (GAP-5)
88. UW-88 (A): Rotation, fold/unfold, split-screen resize and font-scale change preserve the consent dialog's scope selection and scroll position, half-typed `RESTORE` and typed-count text, and the restore progress step; a restore in progress is not restarted or cancelled and Back stays disabled. (GAP-6)
89. UW-89 (A): At 360 dp width and 2.0x font scale the consent dialog's title and action row (Cancel, Enable uploads) stay on screen while the body scrolls, and the "Don't enable this if your images contain anything you wouldn't send to a third party." warning appears above the scope choice and the bullet list. The same pinned action row applies to the guarded-write confirm and the restore confirm, and in the typed-confirm dialogs the body uses imePadding so the action row stays visible and reachable with the soft keyboard open at 360 dp, in landscape and at 2.0x (the Robolectric check asserts the inset handling; the real keyboard is Epic 12 item 12.1). (GAP-7, UX gap 3)
90. UW-90: The typed-count escalation triggers for a dry-run count of at least 1,000 rows, or at least 50% of the table and at least 10 rows; a 2-row table with 100% changed uses the standard modal. (GAP-8)
91. UW-91 (A): The `RESTORE` and typed-count fields trim surrounding spaces and match exactly (`RESTORE` is case-sensitive; `restore` leaves the button disabled), have autocorrect and auto-capitalization off, no pre-filled text, and the count field requests the numeric keypad and ignores non-digits (pasting `1,204` equals `1204`); IME Done submits only on an exact match; with the keyboard open the field and the action row are both on screen. (GAP-9, UX gap 3)
92. UW-92: Neither the asset pipeline nor scheduled sync posts a notification or starts a foreground service (a source audit of `asset/`, `git/` scheduling and `androidMain` sync code finds no `NotificationManager`/`startForeground` use added by this project); a paused backfill and a sync paused after 3 failures are visible on the next in-app open through the progress row, the sync badge and the Logs screen. (GAP-10)
93. UW-93: Search-result snippets emphasize the matched term with bold weight on a tinted background and never underline it; the emphasis is distinguishable in high-contrast mode and is not color-only. (GAP-11)
94. UW-94: After "Not now" the suggestion card stays hidden across launches, and Settings > Plugins shows "Show the searchable-images suggestion again" (only while dismissed); activating it clears the dismissal and the card appears again when its conditions hold. (GAP-12)
95. UW-95: With developer mode off, "View log" on the progress row or a plugin failure opens the Logs screen already filtered to pipeline lines; no Developer-category setting is required to reach or filter the log. (GAP-13)
96. UW-96: `Undo restore` exchanges the live database and the `*.pre-restore` set; afterwards the Backups list shows exactly one "Before restore <time>" entry, and a second undo returns to the first state; each undo shows the standard confirm. (GAP-14)
97. UW-97: In a build with `AssetBuildFlags.PIPELINE_TOGGLE_VISIBLE` true, Settings > Plugins shows (from U7, before U10) one "Asset indexing" switch bound to `asset_pipeline_enabled`, off by default, with an "Indexing files... / N of M indexed" line when on; in a build without the flag the row is absent and the key is false. In U10 the same row remains as the master switch above the plugin list. (interim row, engineering G2)
98. UW-98: While a restore runs the progress dialog shows an elapsed-time line that updates every second without a screen-reader announcement; once a step has run past twice its expected time (floor 3 minutes) it adds "This is taking longer than expected. Keep SteleKit open. If it is interrupted, your previous data is put back the next time SteleKit starts." with a Backups link, and nothing is cancelled automatically. If a step fails and the automatic rollback also fails, the outcome says the previous data could not be put back automatically, shows the file paths, and offers Copy details and Close SteleKit; the journal marker is kept for a retry at the next launch. (UX gap 2)
99. UW-99: Revoking cloud consent (switch off or Revoke consent) takes effect immediately with no confirm dialog; the line under the switch carries the revoke copy; no screen or string refers to a "revocation confirmation"; turning the switch on again re-asks the consent dialog. (UX gap 6)
100. UW-100: Three destructive follow-ups ask a standard confirm with Cancel as the default: Delete on the "Before restore <time>" entry ("Delete the undo copy from <time>? You won't be able to undo the restore afterwards."), Delete AI descriptions (names the graph and the number of descriptions), and Undo restore, which does not require typing RESTORE because it is reversible. (UX gap 7)
101. UW-101: The launch and day counters on the cloud page and in the consent dialog are labelled "all graphs", and a second graph shows the same counters rather than its own budget. (UX gap 8)
102. UW-102: When the live count of undescribed existing images is 0 the consent dialog does not show the "Also the N images already in this graph" option, and in every case it says "Descriptions are saved in this graph and can be searched." (UX gap 9)
103. UW-103: On Android under battery saver, Doze or a restricted standby bucket the scheduled-sync status line reads "Background checks are restricted by Android. Checks run while SteleKit is open."; it is informational (no error badge), announced politely on change, cleared when the restriction ends, and never shown on desktop. (UX gap 10)
104. UW-104: If the process dies while the `sql!` confirm or the restore confirm is open, nothing ran: no backup file, no write, no journal marker and no `*.pre-restore` file exist after relaunch, and the confirm is not restored. (UX gap 11)
105. UW-105: If the real `sql!` write would change more rows than the owner confirmed in a way that crosses the next confirm tier (or differs from a typed count), it is rolled back before commit and the block reads "More rows would change than you confirmed (N now, M when you confirmed). Nothing was changed. Run it again to re-confirm."; the backup is kept. (UX gap 5; extends UW-73)
106. UW-106: For a `sql!` statement whose triggers or cascades change other tables, or that touches several tables, the confirm lists each table with its count and requires the typed count whatever the percentage; if no count can be obtained the write is refused with "Can't count the rows this would change, so it won't run. Nothing was changed." (UX gap 4; extends UW-90)

---

## 13. Open items (not decided here)

1. Charging-only backfill on Android is the plan's default (Story 5.5); the owner confirms on device.
2. The UI floor of 5 minutes for desktop interval and the failure-pause threshold of 3 follow the plan; the owner may lower them.
3. (Decided: `sql!` is listed as `disabled: writes not enabled in this build` in builds without write code; see section 2.)
4. Whether the API-key field can reuse the Settings > LLM pattern: `AddEditLlmProviderDialog.kt` was not opened (INFERRED fit).
5. TalkBack, soft-keyboard, font-scale, foldable-width and reduced-motion behavior are unverified on a device until the owner pass (plan Epic 12, items 12.1-12.2b); the criteria below are automated assertions where possible and name the device step otherwise.
6. The thresholds 1,000 rows or 50% of a table for the typed-count confirm are design defaults (INFERRED); the owner may change them.
7. (GAP-4) Whether the OS kills a backgrounded restore on the phone is unverified until pass 12.1; the next-launch recovery (UW-86) is the safety net and no foreground service is used. If 12.1 shows kills in practice, the decision to use a foreground service for restore is reopened.
8. (GAP-10) No notification for a paused backfill or sync is a deliberate choice (section 7); reopened only if pass 12.2a shows the OS killing a charging backfill.
9. (GAP-2) "Delete AI descriptions" removes stored cloud descriptions for the graph; there is no way to recall images already sent, and the copy says so.
10. (UX gap 12) Whether the "Described by Anthropic" badge on grid cells adds visual noise at 360 dp is checked on the device at pass 12.4 (a look check, not a design change).
