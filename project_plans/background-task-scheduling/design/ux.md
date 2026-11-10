# UX design: background-task-scheduling

Inputs: `../requirements.md`, `../research/ux.md`, UX stories 3.2.1, 3.2.2, 4.1.1, 4.1.2, 4.1.3 in `../implementation/plan.md`. Plan wording is authoritative where it is specific (copy strings, precedence, presets); this file adds layout, flows and error handling. Evidence labels from the research file apply: external-product comparisons are INFERRED.

Vocabulary (one word per concept): **unmetered** (network rule), **late** / "Hasn't run in" (schedule slipped), **behind** (commits behind remote only), **paused** (error stop), **off** (only the switch). "Never in the background" is not a separate control: the old `off` policy is `enabled = false`.

## Step 1: Surfaces

| # | Surface | Kind | Where it lives |
|---|---|---|---|
| S1 | Background tasks list | interactive | New section inside Settings (`BackgroundTasksSection.kt`); not a new top-level screen, so no `first-open-screens.txt` entry |
| S2 | Per-graph task settings (switch, interval, network, Advanced) | interactive | Extends `ScheduledSyncSection.kt` |
| S3 | Schedule status line (incl. Late, skipped, paused, fetch-only) | interactive (one action button per state) | Inside S2, extends `scheduledSyncLine` |
| S4 | Restricted-background hint row | interactive | Inside S2, below S3 |
| S5 | Run records in Logs screen | interactive (read, scroll) | Existing Logs screen |
| S6 | Model download "Download now anyway" | interactive | Existing model-download UI, no new screen |
| S7 | Run-log line (`TaskRun ...`) | non-interactive | `Logger` to `filesDir/logs/current.log` |
| S8 | LFS transfer label alignment | non-interactive (string change) | `LfsTransferSection.kt` |

States covered per surface: empty (S1 no graphs, S5 empty log), loading (S2 reading settings), error (settings read failure, launcher failure), disabled-with-reason (S2).

---

## S1: Background tasks list

Layout (Settings > Background tasks). One row per task kind, one-line summary; graph rows appear only after drilling into a kind.

```
Settings
 Background tasks
 +--------------------------------------------------+
 | Git sync                                       > |
 |   Every hour - any network - on for 2 of 3 graphs|
 +--------------------------------------------------+
 | Model download                                 > |
 |   Unmetered networks only - app-wide             |
 +--------------------------------------------------+

 Git sync   (drill-in)                    [< Back]
 +--------------------------------------------------+
 | Default for new graphs                           |
 |   About every hour - Any network                 |
 +--------------------------------------------------+
 | [search graphs]                         (>= 8)   |
 | Work notes            On  - Using default      > |
 | Personal              On  - Custom: every 6 h  > |
 | Archive               Off                      > |
 +--------------------------------------------------+
```

Flow:
1. User opens Settings > Background tasks. System shows kinds with summaries computed from the store (never expanded cards; plan AC "Scale", 25 graphs).
2. User taps a kind. System shows the default row plus one row per graph ("Using default" or "Custom: <summary>"). 8 or more graphs shows a search field.
3. User taps a graph row. System opens S2 for that (graph, kind). Back returns to the list with the summary refreshed. The "Default for new graphs" row is a per-kind record (`task_default_<kind>`), not a scope: it is edited through the same S2 controls but writes `TaskSettingsStore.writeDefaults`, so it can never collide with the app-wide model-download scope `""`. A graph row reads "Using default" when its entry has no overrides and follows later default changes; it reads "Custom: <summary>" once any control is changed (plan Story 1.1.4/4.1.1).
4. Model download row opens an app-wide S2 variant (no graph, no interval presets, network choice only; one-shot, so "interval" and "Spread out" are hidden).

Error and edge states:

| Situation | User sees | Exit |
|---|---|---|
| No graphs open or registered | "No graphs yet. Add a graph to schedule syncing." | Back; "Add graph" button |
| Settings read throws | Row summary "Couldn't read settings" and a Retry button; nothing is scheduled (fail closed) | Retry, or Back |
| Graph removed while list open | Row disappears; no dialog (tombstoned by `GraphManager.removeGraph`) | n/a |
| Graph search (>= 8 graphs) matches nothing | Labelled field "Search graphs" stays; list shows "No matches" | "Clear search" |
| Kind unsupported on platform (iOS/Web Noop) | Section hidden; on Desktop-only options see S2 | n/a |

### S1/S2 shared presentation rules (triad repair 1)

- **"Custom: <summary>" format**: `Custom: <interval phrase>[, <network phrase>]` listing only the values that differ from the kind default, in the order interval, network, spread. Examples: "Custom: every 6 h", "Custom: every 2 h, unmetered networks only", "Custom: spread out a lot". If more than two values differ the line reads "Custom: 3 changes". Lines are single-line with ellipsis at the end, and the full text is in the merged node description so nothing is lost when truncated.
- **Large font and RTL**: summary lines and radio labels wrap (no fixed height, no single-line clipping) up to 200% font scale; rows grow, controls keep a 48 dp minimum height. Chevron and layout mirror under RTL; numerals and unit abbreviations (min, h) stay inside the translated phrase so word order can change. Nothing relies on a left/right position to convey state.
- **Focus and title announcement**: drilling S1 to kind, kind to graph (S2), and back moves accessibility focus to the new screen's title ("Git sync" or "Git sync, Work notes") and announces it once; Back returns focus to the row that opened the screen and announces its refreshed summary.
- **Day granularity**: lateness shows hours up to 48 h, then days: "Hasn't run in 3 days" (floor of whole days, never "72 h"); under 1 h it reads "Hasn't run in 45 min".

## S2: Per-graph task settings

Layout (extends the existing section; preserves its row patterns).

```
 Git sync - Work notes
 +--------------------------------------------------+
 | Sync automatically                      [ switch ]|   <- master; gates rows below
 | Runs about every hour, unmetered networks only    |   <- summary, not setting name
 +--------------------------------------------------+
 | How often                                         |
 |  ( ) 15 min   ( ) 30 min   (o) 1 hour             |   radio list, whole row selectable
 |  ( ) 2 hours  ( ) 6 hours  ( ) Daily              |
 |  ( ) 5 min   (Desktop only; not listed on Android)|
 |  Android runs at most every 15 minutes.           |   (helper line on Android)
 +--------------------------------------------------+
 | Network                                           |
 |  ( ) Any network                                  |
 |  (o) Unmetered networks only                      |
 |      Usually Wi-Fi. Skips mobile data and         |
 |      hotspots that Android marks as metered.      |
 +--------------------------------------------------+
 | v Advanced                                        |
 |   Spread out start times: ( )Off (o)A little ( )A lot
 |   Avoids every device syncing at the same minute. |
 |   Runs 60-66 min apart                            |
 +--------------------------------------------------+
 | [status line - S3]                                |
 | [restricted hint - S4]                            |
 | [View sync status]                                |
 | [Reset to default]   (only on Custom rows)        |
 +--------------------------------------------------+
```

Loading state (S2 while `TaskSettingsStore.readResult` is pending): the section shows placeholder rows (skeleton bars in the shape of the switch, interval and network groups, no text values), every control is disabled with the one-line reason "Loading settings...", and the placeholder block is one polite live-region announcement ("Loading settings") that is replaced, not repeated, by the loaded state. If the read fails, the existing "Couldn't read settings" state replaces it. Controls never show stale or default values as if they were stored.

Flow:
1. User toggles the master switch on. System persists `enabled=true` in `TaskSettingsStore`, calls `apply` once, status line moves to "Never checked" or "Check in progress", and a success line appears under the switch: "First check in about N min" (N = interval, or the platform-clamped interval; "about", never a clock time). The line is a polite announcement and is replaced by the normal status line after the first run.
2. User picks an interval. System persists and calls `apply` once; summary line and "Runs N-M min apart" update. If the platform clamps (Android under 15 min), the summary prints "Runs every 15 min on this device" (existing idiom).
3. User picks a network option. System persists and applies; no confirmation dialog (reversible).
4. User opens Advanced, picks a spread preset (Off / A little = max(1, 10% of interval) / A lot = max(2, 25% of interval)). System persists the resulting minutes, applies, and shows the effective window. When the interval later changes, the minutes are recomputed from the preset (60 min + "A little" = 6 min; changing to 2 h makes it 12 min and "Runs 120-132 min apart"); on reload the preset is the nearest one to the stored minutes.
5. User toggles the switch off. System persists `enabled=false`, cancels the work, and keeps interval/network values for later. The switch stays operable when already on, even if a precondition has since broken, so the user can always turn it off.

6. User taps "Reset to default" (shown only on a graph whose entry has overrides, i.e. a "Custom" row). System clears the overrides (the entry follows the kind default again, row reads "Using default"), keeps `enabled` unchanged, applies once, and shows "Back to the default schedule". No confirmation dialog (the previous custom values are not recoverable, so the action is labelled with the result, not "Undo"); a failed write shows the S2 "Couldn't save" error and leaves the values unchanged.

Defaults: new graphs off; migrated users keep their prior effective on/off, interval and no spread (behaviour-preserving); interval 1 h; network "Any network" for git sync (preserves today's behaviour) and "Unmetered networks only" for download kinds; spread "A little" (6 min at 1 h) for NEW git-sync graphs (the `TaskSpec` default).

Spread summary copy is per adapter: on Desktop (in-process loop) it reads "Runs 60-66 min apart"; on Android, where WorkManager flex runs land in the last `flex` minutes of each period, the wording comes from the adapter's `describe` gap fields, finalised after Spike 0.1.1 (pending default: "About every hour; start times vary by up to 6 min"), plus "Takes effect after the next run" when an update keeps the existing next-run time.

Error and edge states (disabled-with-reason: one line under the control, plus a fix action when one exists):

| Situation | Control behaviour | Copy | Exit / action |
|---|---|---|---|
| Desktop, unmetered | "Unmetered networks only" disabled | "Not available on desktop: SteleKit can't tell if a network is metered" | Pick "Any network" |
| Android, 5 min | The 5 min radio is not listed (no permanently disabled control); a helper line shows instead | "Android runs at most every 15 minutes." | Pick 15 min or longer |
| Loading | All controls disabled, skeleton rows | "Loading settings..." | Wait; Back |
| Screen reader on any disabled control | The reason line is exposed through semantics (state description / `disabled` plus the reason as the node's supporting text) so TalkBack reads "<control>, disabled, <reason>" | same one-line reason | same fix action |
| Desktop, any interval | Helper | "Syncs with the remote while SteleKit is open." (the existing `DESKTOP_HELPER`) | Informational |
| No remote configured | Switch disabled | existing `NO_REMOTE_REASON` | Existing "set up remote" button |
| First sync unreviewed | Switch disabled | existing `REVIEW_FIRST_SYNC_REASON` | Existing "review first sync" button |
| Already on, then precondition lost | Switch operable | Reason shown under it | Turn off |
| Settings read fails | Whole section replaced by "Couldn't read settings. Background sync is paused until this loads." | | Retry button |
| Write/apply fails | Inline error under the changed control "Couldn't save. Your change was not applied." and the control reverts to the stored value (plan Story 4.1.1 AC and Robolectric test) | | Retry by choosing again; Back |
| Model download rows | Interval, Advanced and the sync switch hidden (one-shot; `enabled` is periodic-only and an explicit download always runs); only the network choice | | |

## S3: Schedule status line

Exactly one line shown at a time (quiet polite live region). Precedence (single source of truth; plan Story 4.1.2, UX-8 and `validation.md` use the same order): **Paused > Disabled-with-reason > Can't-read-settings > Late > CheckInProgress > Checked/next**. New pure states `Late(sinceMs, cause)` and `SkippedBecause(reason)` extend `scheduledSyncLine`; existing strings unchanged.

| State | Copy | Action |
|---|---|---|
| Paused (N failures) | "Paused after N failures - tap to fix" (existing, error colour plus icon plus text) | Tap opens sync status sheet; "Resume" |
| Disabled-with-reason | The S2 reason | The S2 fix action |
| Settings unreadable in a background process (cold Keystore failure newer than the last success) | "Can't read settings. Background sync may be paused." (never Late; precedence between Disabled-with-reason and Late) | "Retry" |
| Late, cause unknown/Android | "Hasn't run in 3 h. Android may be limiting background work." | "Open battery settings" (S4) |
| Late, cause DeliberateSkip(vault-locked) | "Locked. Sync resumes after you unlock." | "Unlock" |
| Skipped conflict-pending | "Resolve the conflict to resume." | Button to conflict review |
| Skipped editing/busy | You are editing: "Sync paused while you edit. It continues when you stop." Another sync or clone is running: "Another sync is running." The status falls back to the Checked/next line as soon as the cause clears; this line only appears while the most recent scheduled run was skipped for that reason | none (transient; the app retries on the next period) |
| Check in progress | existing | none |
| Checked | "Last synced 12 min ago - next in about 18 min" | none |
| Fetch-only (SAF) | "This graph can only check for updates in the background" | "Sync now" |

Flow: line is recomputed every 30 s from pure inputs; announced to TalkBack only when the text changes, never on an unchanged refresh. Never states a clock time ("about", not "at 3:15"). Late never uses the word "behind".

Edge states: no last-success yet = "Never checked" (not Late); clock moved backwards = OnTime; a foreground-only success (app open, `PeriodicSyncDriver` or "Sync now") counts as a success, so it is never shown as Late (last success is `max(TaskRunLog, SyncStalenessStore)`, plan Story 3.1.1/4.1.2); Desktop git keeps `PeriodicSyncDriver`, so the existing next-run line is unchanged (ADR-001, revised). Every non-informational state carries an action, so there are no dead ends.

## S4: Restricted-background hint row

Non-modal info row under the status line; never a banner, dialog, or toast.

```
 +--------------------------------------------------+
 | (i) Battery saver is on. Background sync resumes  |
 |     when you turn it off or charge.               |
 +--------------------------------------------------+
 | (i) Android is restricting this app.  [Open battery settings]
 +--------------------------------------------------+
 | (i) Some phones stop background apps. Check       |
 |     battery settings.            [Open battery settings]
 +--------------------------------------------------+
```

Show rules: BatterySaver gets text only; StandbyRestricted/AppRestricted show text plus button; Late with probe `None` shows the softer OEM wording plus button; hidden when Late cause is a deliberate skip. The row is hidden on Desktop and when the task is disabled.

Flow: user taps "Open battery settings"; system launches `ACTION_APPLICATION_DETAILS_SETTINGS` for the app; user returns, the probe is re-read on resume and the row updates or disappears. No `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

Error states (covered by a throwing-launcher test in plan Task 4.1.2e): intent has no handler or throws, so the row text changes to "Open Android Settings > Apps > SteleKit > Battery." (non-clickable, selectable) and a `Logger` warning is recorded; exit by Back or ignoring (row is dismissible state-wise: it disappears when the cause clears).

## S5: Run records in Logs screen

```
 Logs
 +--------------------------------------------------+
 | Scheduled runs                                    |
 | 14:02  Git sync / Work notes / skipped: metered   |   (today: time only, relative on tap target)
 | 13:02  Git sync / Work notes / success            |
 | Nov 9, 21:15  Git sync / Personal / failed: <msg> |   (older: date and time)
 | Model download / skipped: offline                 |
 +--------------------------------------------------+
 | Previous session ... (existing)                   |
```

Flow: user opens Logs; system reads `TaskRunLog.recent` for registered kinds x current graph list (no key scan) and lists newest first, up to 20 per key; each row shows start time and result with reason. Rows use display names, not ids: the task label ("Git sync", "Model download") and the graph's display name ("Work notes"); an app-wide task omits the graph segment. Time is "14:02" for today (screen-reader text "today at 2:02 PM", relative: "12 min ago" for the last hour) and "Nov 9, 21:15" (date plus time) for earlier days; the raw kind/scope ids stay only in the persisted `Logger` line (S7). Rows are text, selectable for copy. Rows come from two sources, background worker runs and foreground loop ticks (the Desktop in-app loop and the Android in-app loop), merged newest first, so Desktop users see their runs too.

Error and edge states (plan Story 4.1.3 AC and `TaskRunLogRowsTest`): empty log = section hidden or "No scheduled runs yet" (no crash); read failure = section shows "Couldn't read run history" with Retry; unknown graph id (removed graph) = row omitted. Exit: standard Back from Logs.

## S6: Model download "Download now anyway"

```
 Depth model
 +--------------------------------------------------+
 | Waiting for an unmetered network...               |
 | Large download. Skipped on mobile data.           |
 | [Download now anyway]   [Cancel]                  |
 +--------------------------------------------------+
```

Flow:
1. User requests the model. System enqueues `runOnce(..., Unmetered)`; if on an unmetered network it runs, else the row above shows.
2. User taps "Download now anyway". System replaces the request with `runOnce(..., Any)` and shows progress (user-initiated, so a foreground notification is allowed).
3. User may tap Cancel at any time; existing job cancel path.

Additional S6 states (triad repair 1):
- **In progress** (running): "Downloading the depth model... N%" with determinate progress when the size is known (indeterminate otherwise) and "Cancel"; progress text is a polite live region updated at most every few seconds.
- **Cancelled** (user tapped Cancel, or the request was cancelled from the jobs list): "Download cancelled." with "Download again" (reruns `runOnce` with the stored default); partial data is kept for resume.
- **Android 13+ notification permission denied**: a user-initiated download still runs, only its foreground notification is absent. The row shows one extra line: "Notifications are off, so you won't see download progress in the notification shade. The download still runs; progress shows here." with no permission re-prompt and no block. The download state, Cancel, and result are all available in this row, so the missing notification is never a dead end.

Error states: download fails = "Download failed. It will retry automatically." (retryable) or "Download failed after 5 tries" with "Try again" (button reruns `runOnce`); offline = "Offline - will download when you are online" with "Cancel"; the override is per request and is never persisted, so the default stays unmetered. Exit on every state: Cancel or Back.

## S7: Run-log line (non-interactive)

Sample (one line per run, `Logger`):
```
TaskRun kind=git-sync scope=g1 scheduledAt=1731240000000 startedAt=1731240312000 result=Skipped:metered online=true metered=true charging=false
```
Acceptance bullets:
- One line per run and per skip; skips make no network call.
- Contains kind, scope, scheduled vs actual start, result or reason, and constraint state.
- Written through `Logger` (reaches `filesDir/logs/current.log`), never `android.util.Log`.
- Contains no credentials, remote URLs, or note content.

## S8: LFS transfer label (non-interactive)

Sample: existing toggle label becomes "Unmetered networks only" with the same supporting line as S2's network option; key `git_lfs_defer_on_metered` unchanged.
- Same wording as S2 (one word per concept).
- Layout untouched; unknown network now defers, with the existing skip reason surfaced.
- Visible in a transfer deferral status as "Waiting for an unmetered network".

---

## Step 3: UX acceptance criteria

Task completion
- UX-1: A user can turn scheduled git sync on for a graph with the defaults in at most 3 steps (open Settings > Background tasks > kind/graph, toggle the switch; or 2 if opened from the graph's own Settings).
- UX-2: A user can change interval and network rule for one graph in at most 4 taps from Settings, without leaving the screen.
- UX-3: Jitter is reachable only under "Advanced" and uses the labels Off / A little / A lot; no numeric jitter field exists.
- UX-4: Turning a graph's sync off is a single tap on the switch and works even when a precondition has since failed.
- UX-5: With 25 graphs, the Background tasks list shows one summary line per kind and graph rows read "Using default" or "Custom", with no expanded cards.

Wording and state
- UX-6: Network options read exactly "Any network" and "Unmetered networks only"; the word "Wi-Fi only" never appears as a label.
- UX-7: Schedule lateness copy reads "Hasn't run in N h" and never uses "behind"; "behind" appears only for commits behind remote.
- UX-8: Exactly one status line shows at a time, following the precedence Paused > Disabled-with-reason > Can't-read-settings > Late > CheckInProgress > Checked (identical to S3, plan Story 4.1.2 and the `statusLine_should_followPrecedence...` test).
- UX-9: When the last runs were deliberately skipped (vault locked, conflict pending), the status names that reason and the restricted-background hint is not shown.
- UX-10: No status text states a clock time of the next run; it says "about" or "next in about N min".
- UX-11: The effective schedule is shown whenever it differs from the choice (clamp to 15 min on Android: "Runs every 15 min on this device"; the spread window in Advanced, derived per adapter: "Runs 60-66 min apart" on Desktop, the flex-based wording on Android; Desktop also shows the "while SteleKit is open" helper).

Disabled-with-reason and errors
- UX-12: On Desktop, "Unmetered networks only" is disabled and its reason reads "Not available on desktop: SteleKit can't tell if a network is metered".
- UX-13: On Android, the 5 min option is not listed and a helper line reads "Android runs at most every 15 minutes." (supersedes the earlier permanently-disabled radio).
- UX-14: Every disabled control shows a one-line reason, and a fix action when one exists (set up remote, review first sync).
- UX-15: If settings cannot be read, the user sees "Couldn't read settings" with a Retry button, and no background run starts.
- UX-16: If a change cannot be saved, the control reverts to the stored value and shows "Couldn't save. Your change was not applied."
- UX-17: "Open battery settings" launches the app's system settings page; if it cannot, the row shows the manual path text and a warning is logged.
- UX-18: A battery-saver hint shows no button; restricted-standby, app-restricted, and OEM-suspected Late show "Open battery settings".
- UX-19: On a metered network the model download shows "Waiting for an unmetered network" with "Download now anyway" and "Cancel"; the override applies to that request only and does not change the stored default.
- UX-20: Every failure and error state in S1-S6 offers at least one exit (Retry, Cancel, fix action, or Back); no dead ends.

Logs and observability
- UX-21: A skipped run appears in Logs as "<kind> / <scope> / skipped: <reason>" with its start time within one open of the Logs screen.
- UX-22: An empty or unreadable run log shows no crash; unreadable shows a Retry.
- UX-23: The persisted run-log line contains kind, scope, scheduled and actual start, result/reason and constraint state, and no credentials or URLs.

Accessibility
- UX-24: Every interactive row is reachable by keyboard (Tab), Space toggles a switch or radio, and Arrow keys move inside a radio group on Desktop; focus indicator is visible.
- UX-25: Each graph row announces as one merged node, for example "Git sync, Work notes, on, about every hour, unmetered networks only", and every control has a screen-reader label.
- UX-26: All touch targets are at least 48 dp; whole rows are selectable with the inner control `onClick = null` (one focus stop).
- UX-27: Status and hint use a polite live region only, and an unchanged 30 s refresh does not re-announce.
- UX-28: State (Late, Paused, hint) is never conveyed by colour alone; each pairs text with an icon, and text/background contrast is at least 4.5:1 in light and dark themes.
- UX-29: Esc closes the settings dialog and returns focus to the control that opened it.

Triad repair 1 additions
- UX-30: A graph row with overrides offers "Reset to default"; tapping it clears the overrides, keeps `enabled`, applies once, and the row reads "Using default".
- UX-31: Logs rows show the task label and the graph display name (never raw ids), with time-only for today (relative within the last hour) and date plus time for earlier days.
- UX-32: While settings are being read, S2 shows placeholder rows with every control disabled and the reason "Loading settings...", announced politely once; no default value is shown as if stored.
- UX-33: Drilling into a kind or graph and going back moves focus to the new screen title and announces it once; Back restores focus to the opener.
- UX-34: The graph search field has a visible label ("Search graphs") and an accessible name, and an empty result shows "No matches" with a "Clear search" action.
- UX-35: A disabled control's reason is exposed to screen readers through semantics, so the control announces as "<name>, disabled, <reason>".
- UX-36: At 200% font scale and under RTL, summary lines and radio labels wrap without clipping, controls stay at least 48 dp, and no state depends on left/right position. "Custom: <summary>" follows the format defined in S1/S2 shared presentation rules; lateness uses hours up to 48 h, then whole days ("Hasn't run in 3 days").
- UX-37: Enabling sync shows "First check in about N min" (N = effective interval), never a clock time.
- UX-38: Model-download S6 shows in-progress, cancelled and Android 13+ notification-permission-denied states, each with an exit; a denied notification permission never blocks the download and the row explains it.
- UX-39: The "Skipped: editing/busy" status uses the explicit copy defined in S3 and returns to Checked/next when the cause clears.

Owner device checklist items (not automatable; mirrored in plan Story 5.1.3 and `validation.md`): M-6 covers UX-29 (Esc closes the settings dialog and returns focus), M-7 covers UX-25 and UX-35 (TalkBack merged-node and disabled-reason wording), M-8 covers UX-28 (colour contrast and icon-plus-text pairing in light and dark on a device), M-9 covers UX-33/UX-34 (TalkBack focus and announcement on drill-in/back and search).

## Step 5: Summary

Surfaces: 8 (6 interactive with wireframes/flows/error tables, 2 non-interactive condensed). UX acceptance criteria: 39 (29 original plus UX-30..UX-39 from triad repair 1).

## Gaps and plan alignment notes (resolved in plan rev1)

- Launcher-failure fallback (S4), unreadable run log Retry (S5) and failed-save revert (S2) now have plan acceptance criteria and tests: Stories 4.1.2 (Task 4.1.2e), 4.1.3 (Task 4.1.3a) and 4.1.1 (Task 4.1.1a).
- Default row vs scope `""`: resolved by a distinct per-kind default record (`TaskDefaults`, key `task_default_<kind>`); graph rows are "Using default" (no overrides) or "Custom" (Story 1.1.4, Task 4.1.1d).
- S3 "Resume", "Unlock" and conflict-review actions reuse existing handlers; Task 4.1.2e names each handler once located and tests that each action invokes it once.
- "Spread out" labels are percentages of the interval; the UI recomputes `jitterMinutes` whenever the interval changes and derives the nearest preset on reload (Task 4.1.1b, round-trip property test).
- A "Cancel" for a queued model download reuses the jobs-list cancel path (Task 3.2.1e confirms against the existing jobs UI before wiring).
- Compose Desktop radio arrow-key navigation is not automatic: Story 4.1.1 adds a key handler and a jvmTest (`ScheduledSyncRadioKeyboardTest`, header `// jvmTest-reason: desktop-only`).
- Still not verified by a run: Compose Desktop focus-return after Esc (UX-29) and TalkBack merged-node wording on a device; both stay in the Robolectric/Gradle UI tests and the owner device checklist.
