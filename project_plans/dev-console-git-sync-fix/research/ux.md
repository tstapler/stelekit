# UX research: embedded dev console + sync-error UX

Method: repo conventions read via grep/targeted ranges (VERIFIED, cited below). Comparable-product patterns are from general knowledge of Chrome DevTools, Termux, Android Studio Logcat/App Inspection, not fetched this session (INFERRED/UNVERIFIED against current versions).

## Jobs to be done
1. **"Why is my phone missing what's on the remote?"** Ask git/DB/fs questions of the running app, without adb or a rebuild. Success: answer in under ~3 taps and one short command.
2. **"Fix it safely."** Apply a small correction (set branch, re-fetch, reindex) with a visible before/after, not a blind write.
3. **"Hand the evidence to Claude/myself."** Export or share output as a file. Output must be paste-safe (no secrets).
The owner is the sole user, on a phone, with a soft keyboard. Typing is the scarce resource; reading and copying are the common actions.

## Repo conventions found (VERIFIED)
- Settings: `SettingsCategory` enum has `DEVELOPER("Developer", BugReport)` (`ui/components/settings/SettingsDialog.kt:466`). Its content `DeveloperSettings` renders only when `onLibsqlDriverToggle != null` (`:136`, `:223`). The category is already gated by a callback and hosts a libsql toggle. The console entry belongs here; do not add a new top-level category.
- Logs: `Screen.Logs` -> `LogDashboard(diagnostics = graphDiagnostics)` (`ui/ScreenRouter.kt:228`). `LogDashboard.kt` has level filter, search, scroll-to/top/bottom, Share (`rememberShareProvider().saveToFile`), Save to Downloads, and a Graph Diagnostics info button. Reuse `rememberShareProvider` for console export; do not build a second export path.
- Sync status: `SyncStatusBadge.kt` maps `SyncState.Error` to a red `Icons.Default.Error` plus `DomainError.GitError.toSyncErrorMessage()` (`error/DomainError.kt:320-342`). Tap = retry, except `AuthFailed`, which routes to credentials (`SyncStatusBadge.kt:209-225`). Amber `0xFFF59E0B` is used for warn/needs-attention, emerald for success. Messages follow "<what happened> — <what to do>".
- Branch setup: `GitSetupStep4Branch.kt` is a plain text field "Remote branch" with no validation or detection. This is where the `main`-vs-`master` bug originates UX-wise.
- Existing banner pattern: `GitDetectionBanner` (message + `TextButton` "Set up sync" + "Dismiss"). Reuse for the branch-mismatch prompt.
- No existing `GitError` variant for "remote ref not found" (the list at `DomainError.kt:320-342` has none). Needs a new case, plus a `toSyncErrorMessage` branch (exhaustive `when`, so the compiler forces it).

## Comparable patterns and what to take
| Product | Pattern | Take / skip |
|---|---|---|
| Chrome DevTools console | History with up/down, autocomplete popup, `$_` last result, copy via context menu, "Preserve log", filter box, Clear | Take: history, filter, clear, per-entry copy. Skip: live expression preview. |
| Termux | Extra-keys row above keyboard (Tab, Ctrl, Esc, arrows, `/`, `-`, `\|`), long-press paste, volume-key shortcuts | Take: **extra-keys row** (`/ - _ ' " * ; \| Tab history-up/down`). This is the biggest phone win; the soft keyboard buries these symbols. |
| Android Studio Logcat | Level color + filter chips, saved filters, "scroll lock" auto-follow toggle, fold/unfold stack traces, copy line/selection | Take: auto-follow toggle that pauses when the user scrolls up; level colors reuse `getLevelColor` from `LogDashboard`. |
| App Inspection / Database Inspector | Table result grid, run-query button, "live updates" off by default, query history list, export CSV | Take: grid for SQL, query history list, CSV export. Skip: live updates (violates bounded-read rule). |
| Jupyter / psql / sqlite3 shell | `.tables`, `.schema`, `\d` meta-commands | Take: `tables`, `schema <t>` as chips so no one types SQL for discovery. |

## Soft-keyboard-first ergonomics
- **Command chips (snippets)** in a horizontally scrolling row above the input, each inserts text and places the cursor. Seed with the owner's real questions: `git status`, `git refs`, `git ls-remote`, `git log -n 10`, `diag journals-missing`, `sql: newest journals`, `sql: tables`. Long-press chip = edit/pin (in-memory unless opted in; requirements forbid persistent history by default).
- **Extra-keys row** directly above the IME: `Tab`, `^C` (cancel), history up/down, `/ - _ ' " * ; |`, and Paste. `^C` must map to cancel (NFR: every command cancellable).
- **Autocomplete**: first token from the command registry (a one-file addition adds it automatically); second token from per-command subcommands; table/column names for `sql`. Show as a chip row, not a popup, so it never covers output on a small screen. Accept on tap or Tab.
- **History**: in-memory ring, up/down keys and a "History" bottom sheet with tap-to-insert and tap-and-hold-to-run. Redact secrets before storing.
- **Paste**: accept multi-line paste; for `sql` execute as one statement block only after explicit Run (Enter in a multi-line paste must not auto-run). Single-line: Enter runs. Provide a Run button too; IME Enter is unreliable across keyboards (Gboard sends newline in multiline fields).
- Input: `KeyboardOptions(autoCorrect = false, capitalization = None, keyboardType = Ascii/Password-like "visible password" to suppress suggestions)`; monospace.
- Layout on phone: output fills the area; input pinned above the IME with `imePadding()`; chips + extra keys form a toolbar. Landscape/desktop: same layout, but physical keys (Up/Down/Tab/Ctrl+C/Ctrl+L) bind directly and the chip row collapses to a "Snippets" menu.

## Output rendering
- Monospace, no wrap by default with horizontal scroll per block (stack traces and SQL rows are column-aligned). Offer a "wrap" toggle. Font-scale respects system setting.
- Each command produces one **block**: header line (`$ cmd`, duration, exit state chip OK/ERR/CANCELLED/TRUNCATED), body, footer. Block-level actions: Copy, Share, Collapse. Long-press selects text on touch; the whole transcript can also be exported.
- **SQL**: result grid with sticky first column, column-header row, NULL shown as dim `NULL`, long cells ellipsized at ~80 chars with tap to expand. Footer: `500 rows shown, more available -- truncated at row cap` (explicit marker per NFR) plus "Export CSV". `EXPLAIN QUERY PLAN` available via chip.
- **Streaming**: append lines as they arrive; cap scrollback (e.g., 5k lines / 1 MB) with a visible `[... N earlier lines dropped ...]` marker. Render with `LazyColumn` of lines (not one giant `Text`) to keep Compose fast (risk in requirements).
- **Color** only as a second channel (ERR red + prefix `ERR`, WARN amber + `WARN`), never alone.
- **Redaction**: remote URLs show userinfo stripped; tokens never printed (requirement). Show a small "redacted" marker where something was removed so absence is not mistaken for empty.
- Copy/share: reuse `rememberShareProvider` (`saveToFile`, `saveToDownloads`) like `LogDashboard`. Filename `console-<yyyyMMdd-HHmmss>.txt`.

## Destructive actions
Tiering (matches requirements Risk Control):
| Tier | Examples | Confirmation |
|---|---|---|
| Read | `sql SELECT`, `git status/log/refs/ls-remote`, `fs ls/cat`, `diag` | none |
| Network/idempotent | `git fetch` | none; show progress and cancel |
| State change, reversible | `git set-branch`, `settings set`, `graph reindex` | inline confirm chip "Run? [Confirm] [Cancel]" echoing the exact effect ("remote_branch: main -> master") |
| DB write | `sql` INSERT/UPDATE/DELETE/DDL | modal dialog: shows the statement verbatim, tables affected, **dry-run row count** (run in a rolled-back transaction or `SELECT count(*)` with the same WHERE), backup name that will be taken first. Confirm button labeled with the verb ("Delete 12 rows"), not "OK". Default focus = Cancel. |
| Shell / process exec | `sh ...` | second confirm **once per session** (requirements), listing that it runs as the app uid; subsequent commands in session show a persistent "shell armed" chip with Disarm. |
- Statement classification must be by parsing/first-keyword plus a deny-by-default for multi-statement input (`;` splitting): any statement not provably SELECT/EXPLAIN/PRAGMA-read is treated as write.
- A write with no WHERE (`DELETE FROM x`, `UPDATE x SET`) gets an extra typed confirmation ("type the table name"). Cheap, and rare enough not to annoy.
- After a write: show result (`12 rows affected`), backup location, and an "Undo (restore backup)" action for the session. Audit line to the log at WARN (requirements).
- Avoid a time-limited "undo snackbar" as the only safety net; TalkBack users can miss it.

## Developer-mode entry
- Put a **Developer-mode toggle** in Settings > Developer (existing category). Today that category is hidden unless `onLibsqlDriverToggle != null` (`SettingsDialog.kt:136`); change its visibility predicate so the category is always present on supported platforms, with the toggle default off.
- When on: show a "Dev console" row in Settings > Developer and an overflow-menu item on the Logs screen (the Logs top bar already holds diagnostics/share actions). When off: neither appears (requirement: entry hidden). Optional discoverability trick used by Android: tap "Version" 7 times to reveal the toggle; the settings dialog already shows `versionLabel(...)`. Recommend **not** adding a hidden gesture: sole user, wants it findable; a plain labelled toggle is better and also more accessible.
- Warning text next to toggle: "Lets you run SQL writes and shell commands. Your notes can be changed or lost."
- Where it opens: a full `Screen` (like `Screen.Logs`), not a dialog; a dialog loses state on rotation and fights the IME. Desktop may optionally pin as a bottom drawer later (not required).

## Accessibility
- TalkBack: each block is one focusable item with a `contentDescription`/`semantics` summary ("Command git status, succeeded in 120 ms, 14 lines"), and an action "Read output" that expands. Do not make every line focusable (hundreds of swipes). New output announced via `liveRegion = Polite` for the status chip only, not streamed lines.
- Chips and extra keys: >= 48dp targets, `Role.Button`, descriptive labels ("Insert git ls-remote", "Control C, cancel running command"), not glyphs.
- Keyboard nav (desktop and external keyboards): Up/Down history, Tab accepts completion, Ctrl+C cancel, Ctrl+L clear, Ctrl+Enter run multiline, Esc closes completion/dialog; visible focus ring; dialogs trap focus and Escape cancels.
- Contrast: WCAG AA 4.5:1 for output text in light and dark themes; level colors verified against surface (the amber `0xFFF59E0B` used in `SyncStatusBadge` is low-contrast on white at small sizes; prefer theme tokens for new UI); never color-only.
- Respect system font scale and reduce-motion; no animated autoscroll flourish.

## Error states (console)
| State | UX |
|---|---|
| Unknown command | `unknown command 'gti'. Did you mean 'git'?` plus top 3 registry matches as tap chips |
| Bad args | usage line inline (`git set-branch <name>`) plus a `help <cmd>` chip |
| SQL error | show SQLite message verbatim, caret/position if available, keep the input text so it can be edited (do not clear on error) |
| Timeout / cancelled | block chip `CANCELLED` (distinct from ERR); partial output retained |
| Row cap | explicit truncated marker (see above) |
| DB closed / graph switched mid-command | `graph closed during command` (matches `catchDbError` convention; never crash) |
| Not supported (Web: git/sh; iOS) | command is listed in `help` as disabled with reason ("needs JGit; unavailable on Web"), mirroring `TargetWriterCapabilities` "disabled with a reason, not hidden" convention from CLAUDE.md |
| Uncaught throwable | caught at the command runner, rendered as an ERR block with exception class and message; process must survive (OOM guard rule) |
| Output too large | auto-spill to a file with a "saved to ..., tap to share" line instead of rendering |

## Sync-error UX: remote branch missing/mismatched
Current behaviour: silent green success with 0 merged. Target: a distinct, actionable error that leads to the fix in one tap.

**Detection** (at setup, and after any fetch where `<remote>/<branch>` is unresolved): compare configured branch against `ls-remote --symref <remote> HEAD` and the list of remote heads.

**Message** (follows existing "<what> — <action>" style, one line in badge, expanded detail on tap):
- Badge (`toSyncErrorMessage`): `Branch 'main' not found on remote — tap to fix`
- Detail sheet: 
  - "Your sync is set to branch **main**, but the remote only has: **master**."
  - "Nothing was pulled. Your phone may be missing recent changes."
  - Primary button: **Use 'master'** (when exactly one candidate, or the symref default). Secondary: **Choose another branch** (list of remote heads, radio). Tertiary: **Copy details** (for support/console).
- Show the exact config rewrite before applying: `remote_branch: main -> master`. The requirements say auto-correct must be user-confirmed; the sheet is that confirmation.
- After applying: auto-run a sync and show the result including count (`Pulled 14 commits`), so the user sees the fix worked. If still 0 merged but remote is ahead, say so (never a bare "Success" when `behind > 0`).

**Prompts**
- Auto-detect prompt at setup (Step 4): replace the free-text field with a detected default prefilled (`master (remote default)`), keep free-text as override, and validate on "Test connection" (Step 5) by checking the ref exists; failure blocks Save with the same message.
- Existing broken configs (migration): on first launch after upgrade, if stored `remote_branch` is unresolvable, show the `GitDetectionBanner`-style banner once ("Sync is pointed at a branch that doesn't exist. Fix"), not a modal; dismiss persists until next sync failure.
- Ambiguity (multiple candidates, no symref): never guess; force explicit choice.
- Offline: reuse `Offline` message; do not report a missing branch when ls-remote could not run.
- Keep the badge red `Error` icon/semantics (`SyncStatusBadge.kt:209`) so TalkBack text is "Sync error — tap to fix" for this case (the current description says "tap to retry", which would be wrong: retry cannot fix a wrong branch). Add a `GitError.RemoteBranchNotFound(branch, available: List<String>)` variant; its tap action routes to the detail sheet like `AuthFailed` routes to credentials.
- Console cross-link: the detail sheet offers "Open in dev console" (only when developer mode is on) pre-filled with `git refs`.

## Recommendations (ordered)
1. Reuse existing surfaces: Settings>Developer toggle, Logs overflow entry, `rememberShareProvider`, `GitDetectionBanner`, `SyncStatusBadge` routing.
2. Ship the extra-keys row + chips + history before autocomplete; they carry most of the phone value.
3. Treat statement classification as deny-by-default; confirm dialogs show verbatim statement + dry-run count + backup.
4. Add `RemoteBranchNotFound` to `GitError`; the exhaustive `when` in `toSyncErrorMessage` will force all call sites to handle it.
5. Verify on-device with TalkBack once before calling the console done (cannot be verified from tests alone).

## Gaps
- Comparable-product details are from memory, not re-fetched.
- `DeveloperSettings` internals and `getLevelColor` contrast values were not opened.
- No device run of any of this; all UX is a proposal.
