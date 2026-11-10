# UX research: per-graph, per-task background scheduling

Date: 2026-11-10. Scope: Settings for interval, jitter, network policy, enabled; status ("behind"); restricted-background hint.

Evidence labels: **VERIFIED** = file opened in this repo (path cited). **INFERRED** = reasoned from prior knowledge of the named products/specs or from the code; no web fetch was done this session, so every external-product claim is INFERRED and should be spot-checked before it is cited in an ADR.

## 1. What exists today (VERIFIED)

- `ui/components/settings/ScheduledSyncSection.kt` is the whole UI: a `SettingsSection("Scheduled sync")` with one `toggleable` Switch row ("Sync automatically"), a helper line (`DESKTOP_HELPER` / `ANDROID_HELPER` / `RESTRICTED_HELPER`), a `selectableGroup` radio list of `SCHEDULED_SYNC_INTERVAL_OPTIONS = [5,15,30,60]` minutes, a status line, and a "View sync status" button.
- Accessibility patterns already in place: 48 dp min targets (`MIN_TARGET_DP`), `Role.Switch` / `Role.RadioButton` on the whole row with the inner control's `onClick = null` (one focus stop, one TalkBack node), `LiveRegionMode.Polite` on helper and status text, error-coloured "tap to fix" row.
- Disabled-with-reason is an established idiom: a disabled radio shows a secondary line `ANDROID_FIVE_MINUTE_REASON` ("Android runs at most every 15 minutes"); a clamped interval prints "Runs every N min on this device"; the switch is disabled when no remote (`NO_REMOTE_REASON`) or first sync unreviewed (`REVIEW_FIRST_SYNC_REASON`) and has a button that fixes the cause. An already-on switch stays operable so it can always be turned off (`switchEnabled`).
- `git/ScheduledSyncPresentation.kt`: pure `scheduledSyncLine(...)` sealed state (NotScheduled, Stopping, NeverChecked, CheckInProgress, Offline, Checked, FetchedOnly, Paused). Wording: "Last synced 12 min ago · next in 18 min", "Checked N min ago, updates not merged yet", "Paused after N failures - tap to fix", "Offline - will sync when you are back online".
- `git/SyncStalenessPresentation.kt`: `stalenessChipState` gives "last merged 2 h ago · behind 3" with `Healthy/Neutral/Amber`; stale = behind>0 and no progress for `STALE_FACTOR=2` x interval; failing = 3 consecutive errors; fetch-only chip "fetch-only (SAF) · behind n". Note: "behind" here means commits behind the remote, not "the schedule is late". The requirements' "sync is behind" (late/not run) is a different signal and today has no presentation; do not overload one word for both (see 4.3).
- `git/SyncStatusSheetModel.kt`: status sheet rows (Last merged / Last checked / Behind remote), a fetch-only explanation (SAF: "...can only check for updates in the background. Tap Sync now to merge."; AppClosed), recent outcomes list newest first, `canSyncNow`.
- `ScheduledSyncPolicy.kt` skip reasons: EditingInProgress, Busy, VaultLocked, ConflictPending, Backoff, Offline, RepoState, FirstSyncUnconfirmed; FetchOnly reasons Saf, AppClosed.
- `BackgroundRestrictionProbe` (`BackgroundSyncScheduler.kt:36`) with `AndroidBackgroundRestrictionProbe` (`WorkManagerSyncScheduler.kt:284`): true if power-save mode, `isBackgroundRestricted`, or standby bucket >= RESTRICTED (45). It is a single boolean; it cannot distinguish battery saver (transient, user can fix in seconds) from a restricted bucket (OS-decided, slow to change) or OEM killers (undetectable). Wired into the section as `backgroundRestricted`.
- `ui/screens/BackgroundJobsScreen.kt`: read-only list of jobs (title, status, Cancel, "Clear finished"). No scheduling settings, no per-graph grouping.
- Settings are one global flag/interval today (`git/ScheduledSyncPreferences.kt`, `Settings`). Per-graph x per-kind is new UI surface.
- The repo's `stelekit-ui-skill` (`~/.claude/skills/stelekit-ui-skill/SKILL.md`) is a desktop automation/screenshot skill (xdotool/scrot), not design guidance; there is no UI style guide under `.claude/skills` in this worktree (only `stelekit-bazel-perf`). Design rules were therefore taken from the existing Settings code above. VERIFIED.

## 2. Comparable patterns (INFERRED, from memory of the products)

- **Syncthing-Android "Run conditions"**: a few plain switches ("Run on mobile data", "Run on Wi-Fi", "Run on specific Wi-Fi networks", "Run only when charging", "Respect Android battery saver", "Respect Android power-saving"), with a master "Run in background" toggle, and a persistent notification/status with explicit reasons ("Stopped because not charging"). Lesson: express network policy as independent, plainly worded conditions, and show the *reason it is not running* as status text. Lesson against: many switches per target does not scale to graphs x kinds.
- **Nextcloud auto-upload**: per-folder rows, each with "Only on Wi-Fi" and "Only when charging" switches, and a global sync interval owned by the OS. Lesson: per-item rows with 2 toggles is a recognised, learnable shape; users do not ask for jitter.
- **Android Settings conventions**: a master switch row at top that gates the rest (dependent rows greyed, with a summary line explaining why); summaries state current value ("Every hour · Wi-Fi only"), not the setting name; destructive/low-battery hints link to the system page (Battery > Unrestricted). Use `ACTION_APPLICATION_DETAILS_SETTINGS` or the battery-optimisation intent rather than describing a path through menus that differs per OEM.
- **Material 3**: a list item with supporting text for the summary; dialog with radio list for a single-choice of 4-6 options (current UI already does inline radios); **single-choice segmented button** for 2-5 short, mutually exclusive, peer options that fit on one line (good fit for network policy: "Any network | Unmetered | Off" is borderline; labels are long, so prefer radio list with supporting text on narrow widths); **Slider** for continuous ranges but poor for discrete, unevenly spaced values (5/15/30/60/360...) and hard for TalkBack/keyboard to land on exact values; discrete slider with `steps` plus a visible value label is acceptable only for jitter. Prefer a segmented/radio choice of named presets over a slider for interval.

## 3. Mental models and wording (INFERRED, design recommendations)

- **Interval**: say "About every hour", not "60 min" / "3600 s". The word "about" is honest because Android runs inexact work and jitter shifts it. Offer presets (15 min, 30 min, 1 h, 2 h, 6 h, daily), not free numbers; floor 15 min on Android is shown as disabled-with-reason using the existing idiom (VERIFIED idiom, `ANDROID_FIVE_MINUTE_REASON`). Desktop keeps 5 min.
- **Jitter**: users do not have a model for it. Recommendation: do not expose a jitter control by default; surface it as "Spread out start times" (`Off / A little / A lot`, mapping to 0 / 10% / 25% of the interval) inside an "Advanced" disclosure, with supporting text "Avoids every device syncing at the same minute". Show the *effective* window in the summary only in Advanced ("Runs 60-75 min apart"). A raw number field for jitter is the wrong abstraction. The requirement is "per-kind jitter setting", so keep the field in the model; the default and the placement are a UX decision.
- **Network policy wording**: "Wi-Fi only" is what users say but is inaccurate: `NetworkType.UNMETERED` includes some unmetered cellular plans and excludes metered Wi-Fi hotspots (requirements, Rabbit Holes). Recommendation: label **"Unmetered networks only"** with supporting text "Usually Wi-Fi. Skips mobile data and hotspots that Android marks as metered." and **"Any network"**, **"Never in the background"** (rather than "Off", which collides with the enabled switch: the policy value "off" and `enabled=false` mean the same thing to a user). Decision for planning: collapse the `off` policy into `enabled=false` in the UI, or keep both but never show both; two controls that do the same thing is the main confusion risk.
- **Per-graph x per-kind scale**: tens of graphs x few kinds. Recommended hierarchy: Settings > Background tasks > list of task kinds (Git sync, Model download, LFS transfer) with a one-line summary each ("Every hour · unmetered only · on for 2 of 3 graphs"); drill in to a per-graph list; each graph row has the master switch and summary, tap for interval/jitter/network. Defaults inherited from a global per-kind default ("Use default") so that 20 graphs do not need 20 configurations. Show "Using default" vs "Custom" in the summary.
- **Jobs-to-be-done** (INFERRED):
  1. "Keep my notes in sync without thinking about it, without spending mobile data." -> sane defaults, one switch, unmetered default.
  2. "Know that it actually happened (or that it didn't)." -> last-success text + a visible, calm "late" state.
  3. "Stop it right now for this graph (battery, hotel Wi-Fi, metered data)." -> master switch and a way to pause one graph without editing settings.
  4. "Fix it when Android is stopping it." -> restricted hint with one action.
  5. "Understand why a run was skipped." -> reason text from the existing skip enum.
  6. Large downloads (model, LFS): "don't surprise me with a 500 MB download on mobile data" -> unmetered default for download kinds, "Download now anyway" action.

## 4. Status and "behind" visibility

### 4.1 Reuse
Extend, do not replace, `scheduledSyncLine`: add states rather than strings. New pure states needed (names INFERRED): `Late(lastSuccessAgo, expectedEvery)` for "has not run in over N x interval" and `SkippedBecause(reason, nextIn)` for the last skip. The Logs screen carries the per-run record (requirements); the Settings status line carries only the latest.

### 4.2 Which status line wins (precedence proposal)
Paused (error, actionable) > Disabled-with-reason (policy blocks run) > Late > CheckInProgress > Checked/next. A single line at a time keeps the live region quiet.

### 4.3 Terminology collision
"behind" currently = commits behind remote (chip, sheet row "Behind remote", notification text). Use **"Late"/"Hasn't run since ..."** for schedule lateness, and keep "behind" for content. Suggested copy: "Hasn't run in 3 h. Android may be limiting background work." with a link to the restricted-background action. VERIFIED collision; wording is INFERRED.

### 4.4 Lateness semantics
Late when `now - lastSuccess > k x effectiveInterval` with k configurable (requirements) and default 2, matching `STALE_FACTOR=2` VERIFIED. Compute against the *effective* (clamped) interval and exclude time the policy deliberately skipped (vault locked, editing, offline) from "Android is limiting you" copy, otherwise the restricted hint blames Android for a locked vault.

## 5. Restricted-background hint

- Today: a static helper line `RESTRICTED_HELPER` replaces the Android helper when `isRestricted()` is true. VERIFIED. It is not actionable and not dismissible.
- Recommendation: an inline, non-modal info row (not a banner or dialog), shown only when `isRestricted()` is true **or** the schedule is Late while the probe is false (the OEM-killer case, which the probe cannot detect; wording then is suggestive: "Some phones stop background apps. Check battery settings."). Action button: "Open battery settings" -> system app-details/battery screen. No request for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Play policy restricts it; INFERRED, verify before any decision). Distinguish battery saver ("Battery saver is on. Background sync resumes when you turn it off or charge.") from restricted bucket/background restriction; this needs the probe to return a reason enum, not a Boolean (probe API change for planning).
- Show once per state change in the live region, not on every 30 s clock refresh (the section already refreshes `now` every 30 s; the status text live region must not re-announce unchanged text. INFERRED risk: Compose announces on text change only, so verify with the existing Robolectric tests).
- Honesty rule: never promise a time. Say "about", never "at 3:15".

## 6. Accessibility

- TalkBack: keep the existing pattern (whole row `toggleable`/`selectable` with `Role`, inner control `onClick = null`). VERIFIED pattern. For each graph row, set `contentDescription`/merged semantics to a full sentence: "Git sync, Work notes, on, about every hour, unmetered networks only". Group each task card with `semantics(mergeDescendants = true)`; keep the segmented/radio group a `selectableGroup`.
- Don't use colour alone for Late/Paused: pair with text and icon (existing Paused row uses error colour plus text; VERIFIED).
- Slider, if used for jitter: needs `stateDescription` ("up to 10 minutes"), discrete steps, and keyboard Left/Right; prefer the three-preset control.
- Touch targets >= 48 dp (existing constant). Segmented buttons fit 3 options at 360 dp only with short labels; otherwise fall back to radio list. INFERRED.
- Desktop keyboard: all rows reachable by Tab, Space toggles switch/radio, Arrow keys move within a `selectableGroup` (Compose Desktop: radio-group arrow navigation is not automatic; INFERRED, needs a test), visible focus indicator, Enter activates "Open battery settings". Dialog-scoped focus trap and Esc to close follow the existing SettingsDialog (not inspected in detail).
- Live regions: polite only; never assertive for status.
- Existing tests to extend rather than add new harnesses: `androidUnitTest/.../settings/ScheduledSyncSettingsTest.kt`, `SyncStatusSheetUiTest.kt` (Robolectric, per CLAUDE.md guidance that Compose behaviour tests go in androidUnitTest). New screen entries must go in `first-open-screens.txt` and `FirstOpenSmokeTest` (CLAUDE.md, VERIFIED).

## 7. Error and edge states (disabled-with-reason matrix)

Rule: a control that cannot take effect is disabled with a one-line reason under it, plus a fix action when one exists; the master switch stays operable if already on (existing rule, VERIFIED).

| Situation | Control behaviour | Copy (proposal) | Basis |
|---|---|---|---|
| Desktop, "unmetered only" | Option disabled (JVM has no metered signal) | "Not available on desktop: SteleKit can't tell if a network is metered" | requirements Rabbit Holes (VERIFIED); copy INFERRED |
| Desktop interval < app-open | Runs only while app is open | "Runs while SteleKit is open" (existing `DESKTOP_HELPER`, VERIFIED) | VERIFIED |
| Android interval < 15 min | Option disabled | existing `ANDROID_FIVE_MINUTE_REASON` | VERIFIED |
| Battery saver / restricted | Not disabled; hint row | see section 5 | VERIFIED probe |
| Vault locked | Run skipped; status "Waiting for unlock" | "Locked. Sync resumes after you unlock." | `SkipReason.VaultLocked` VERIFIED; copy INFERRED. Do not count toward "Late" or failures |
| Conflict pending | Run skipped; status with action | "Resolve the conflict to resume" + button to the review | `ConflictPending` VERIFIED |
| Editing in progress / Busy | transient; no user-visible warning | "Sync in progress" or nothing | VERIFIED states |
| Graph not app-owned / SAF | Fetch-only: scheduled runs fetch, never merge | Reuse sheet explanation (VERIFIED); in settings add "This graph can only check for updates in the background" and keep the "Sync now" path | `FetchOnlyReason.Saf` VERIFIED |
| No remote / first sync unreviewed | existing disabled switch + action | existing strings | VERIFIED |
| Settings read failure | Fail closed (no background run); show "Couldn't read settings" with retry | requirements constraint VERIFIED; UI copy INFERRED |
| Model download kinds on metered network | Default unmetered; manual "Download now" override | INFERRED |
| Paused after N failures | existing error row + Resume | VERIFIED |
| Per-graph cap (many graphs) | list with search, "Using default" collapsed | INFERRED; decision needed on a maximum count |

## 8. Recommendations (design-level, INFERRED unless cited)

1. Settings IA: Background tasks > task kind > per-graph rows; per-kind defaults with "Custom" override.
2. Defaults: enabled preserved from the old global flag; interval 1 h; network "unmetered only" for download kinds, "any" for git sync (matches requirement to preserve existing behaviour: today is any-network, VERIFIED in requirements); jitter "A little".
3. Controls: master switch row; interval as preset radio list; network as 3-option single choice; jitter as 3 presets under Advanced.
4. Status: add `Late` and `SkippedBecause` states; rename lateness copy away from "behind".
5. Probe: change `BackgroundRestrictionProbe` to return a reason (BatterySaver, AppRestricted, StandbyRestricted, None); keep the Boolean as a derived helper.
6. One word per concept: "unmetered", "late", "behind" (content), "paused" (errors), "off" only for the switch.

## 9. Open questions for planning

- Does "network policy = off" stay as a value, or fold into `enabled=false`? (UX recommends fold.)
- Per-graph cap and default inheritance model.
- Whether the Late threshold multiplier is user-visible (requirements say configurable; UX recommends hidden, with a fixed 2).
- Probe API change to a reason enum, and the Play-policy check for any battery-optimisation intent.
- External pattern claims in section 2 were not web-verified this session.
