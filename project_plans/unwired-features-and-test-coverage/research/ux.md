# UX research: unwired-features-and-test-coverage

Date: 2026-10-27. Scope: user-facing surfaces of in-scope features. Repo facts were read this session (VERIFIED, cited path:line). Comparable-product patterns are from general knowledge, not re-fetched (INFERRED). No device run; everything below is a proposal.

## 0. Headline

The dev console already has a complete UX design (`project_plans/dev-console-git-sync-fix/design/ux.md`, 781 lines, S6-S11, S17, UX-75..UX-104) and research (`research/ux.md`). Do not redesign it. This task's UX work is: (a) adopt that design as-is for the console, (b) design the two surfaces that have none (desktop-sync settings, asset-pipeline settings and progress), and (c) recommend no UI for dormant search code.

## 1. Dev console / SQL viewer

### Job to be done (decided; reuse)
Owner, alone, on an Android phone without adb: "ask the running app a read-only SQL/diagnostic question and hand the evidence to Claude without a rebuild." Typing is scarce; reading and copying are common (`research/ux.md` "Jobs to be done").

### Decisions already made (adopt; do not reopen)
| Decision | Source |
|---|---|
| Entry = plain labelled "Developer mode" toggle in the existing `SettingsCategory.DEVELOPER`; no hidden tap gesture; warning copy "Lets you run SQL writes and shell commands. Your notes can be changed or lost." | `design/ux.md` S6; category at `ui/components/settings/SettingsDialog.kt:477` |
| Opens as a full `Screen` (like `Screen.Logs`), not a dialog (rotation/IME) | `research/ux.md` Developer-mode entry |
| Second entry: Logs overflow item, visible only when developer mode is on | `design/ux.md` S6/S12 |
| One block per command with OK/ERR/CANCELLED/TRUNCATED chip; SQL results in a grid with explicit row-cap marker and CSV export | `research/ux.md` Output rendering; S11 |
| Soft-keyboard ergonomics: extra-keys row, command chips, in-memory history; chips not popups for completion | `research/ux.md` |
| Tiered confirmation; SQL writes behind a verbatim-statement modal with dry-run count and backup, deny-by-default classification | `research/ux.md` Destructive actions; S8; ADR-002 |
| Export reuses `rememberShareProvider` (as `LogDashboard` does), filename `console-<yyyyMMdd-HHmmss>.txt` | `LogDashboard.kt:160,170` |

### What needs updating for this project's scope
1. **Scope reduction is allowed by the requirements, not by the design.** Requirements metric 3 asks only for "read-only SQL query + fetch diagnostics, writes blocked by default". The design covers shell, backups, git commands, and write tiers (S8, S9, S17). Recommend a first slice of: toggle (S6), console screen (S7) with `sql`, `help`, `diag`/logs commands, SQL grid (S11), export (S10). Write-tier surfaces (S8), shell (S9), backups (S17) stay in the design but are later stories. In the read-only slice the write path must still be visibly refused: a `sql` write returns an ERR block `blocked: read-only mode (developer-mode writes not enabled)`, never a silent no-op.
2. **Design is unbuilt and unreviewed on a device.** `design/ux.md` header says "Proposal. No device run". The one TalkBack pass the research demands (`research/ux.md` Recommendations #5) is a named wall-clock blocker (owner device), not something tests can replace.
3. **Visibility predicate bug to carry over.** `DEVELOPER` is currently shown only when `onLibsqlDriverToggle != null || onMcpServerToggle != null` (`SettingsDialog.kt:142`); the design's plan task 4.5b changes it. Must be done or the toggle is unreachable on platforms without libsql.
4. **Web**: console has no read-only connection (requirements, feasibility risks). Follow the design: show the toggle, list `sql`/git/sh as disabled with a reason (mirrors the `TargetWriterCapabilities` "disabled, not hidden" convention in CLAUDE.md).

### Comparable patterns (INFERRED)
| Product | Take | Skip |
|---|---|---|
| DB Browser for SQLite / Android Studio Database Inspector | result grid, query history, export CSV, schema tree as discovery | live updates (violates the bounded-read rule in CLAUDE.md) |
| Chrome DevTools console | history, filter, clear, per-entry copy | live preview |
| Logseq advanced-query UI | results rendered inline under the query; error shown inline | Datalog syntax (SteleKit has SQL) |
| Termux | extra-keys row | -- |

Mental model to preserve: a **terminal transcript of blocks**, not a spreadsheet app. Schema discovery via `tables` / `schema <t>` chips so nobody types SQL to find a table name.

### Accessibility checklist (from the design; verify on device)
- TalkBack: one focusable item per block with summary `contentDescription` ("Command sql, succeeded in 120 ms, 14 rows"); polite live region on the status chip only, not on streamed lines.
- Targets >= 48dp for chips and extra keys; descriptive labels, not glyphs.
- 360dp width: output area full width, input pinned with `imePadding()`, grid scrolls horizontally with sticky first column; chip row scrolls horizontally. Test at 360dp and font scale 1.5x and 2.0x (the repo has `WindowSizeClass.COMPACT < 600dp`, `ui/WindowSizeClass.kt:22`).
- Desktop keyboard: Up/Down history, Tab accept, Ctrl+C cancel, Ctrl+L clear, Ctrl+Enter run multiline, Esc closes. `SearchDialog.kt` already handles `Key.DirectionDown/Enter/Escape` (`:110,125,157`), so key handling has a local pattern.
- Contrast: use theme tokens, not the hard-coded amber `0xFFF59E0B` (low contrast on white at small sizes per prior research).
- Gap: `LogDashboard.kt` has `contentDescription` on its icon buttons (e.g. `:102,160,170`) but no `semantics`/`liveRegion`/`Role` hits in the files grepped, so TalkBack conventions for block lists do not exist yet; the console sets the precedent.

### Error / empty / long-running states
Adopt the table in `research/ux.md` "Error states (console)". Additions specific to the read-only slice:
| State | UX |
|---|---|
| Empty (first open) | One-line helper plus chips: `tables`, `sql: newest journals`, `diag`. Never a blank screen. |
| Long query on a 451 MB DB | Block shows `running…` with elapsed time and a Cancel button; hard timeout yields CANCELLED, partial rows kept; result capped (design: 500 rows) with truncated marker. |
| Full-table scan hazard | Show `EXPLAIN QUERY PLAN` chip; no auto-run. |
| Graph switched / DB closed mid-query | `graph closed during command` (matches `catchDbError` convention). |

## 2. Desktop scheduled sync settings

### Facts
- `DesktopSyncScheduler` is a coroutine `delay` loop taking `intervalMinutes`, JVM-only, no consumer (`jvmMain/.../git/DesktopSyncScheduler.kt:20-40`). It has no status output, no last-success timestamp, no skip-while-busy logic. UX depends on the owner-facing semantics fixed in ADR-004 (below).
- ADR-004 (VERIFIED, `decisions/ADR-004-scheduled-full-sync.md`): the app-open timer does full commit+merge+push; Android clamps to `max(setting, 15 min)` and says so in settings text; a permanent staleness chip "last merged <age> · behind <n>" (amber after 2x interval); last-10 background outcomes visible in `diag`/`git doctor`.
- Existing sync settings home: `ui/components/settings/FolderSyncSettings.kt:54` (folder sync) and the Git setup wizard; the sync badge is `ui/components/SyncStatusBadge.kt`.

### Job to be done
"Keep this desktop copy current with the remote while the app is open, without me thinking about it, and tell me when it isn't working."

### Recommendation
- **Where**: a "Scheduled sync" section in the existing sync settings (git sync), not a new top-level `SettingsCategory`. Avoid growing the 13-entry category list (`SettingsDialog.kt:464-478`).
- **Controls**: Switch "Sync automatically while SteleKit is open" (default off, per requirements Risk Control) + interval picker with fixed choices (5, 15, 30, 60 min) instead of free text (typing is scarce; invalid values impossible). Helper under the picker: "Checked every N minutes while the app is open. Android: at most every 15 minutes." Desktop has no floor in the scheduler, so set a UI floor (5 min) to bound network and JGit load; the owner decides if lower is wanted.
- **Mental model**: "background pull" vs "sync" are different words to users. Use one verb: "Sync". Show `Last synced 3 min ago · next in 12 min` under the switch so the schedule is observable (requirements Observability: "last-success time").
- **Feedback**: reuse the existing `SyncStatusBadge` states; a scheduled run must never steal focus or open a dialog (design S1 step 3). Failures show on the badge, not as toasts.
- **States**:
  | State | UX |
  |---|---|
  | Enabled, no git remote configured | Switch disabled with reason "Set up Git sync first" and a button to the wizard (disabled-with-reason convention). |
  | Previous tick still running | Skip tick; show `Sync in progress`; never queue (INFERRED requirement; scheduler currently has none). |
  | Offline | Reuse the `Offline` message; resume silently. |
  | Auth failed / branch missing / conflicts | Existing routed badge actions; scheduled sync pauses after N consecutive failures and says so ("Paused after 3 failures — tap to fix"), instead of retrying forever. |
  | Mass delete blocked | Existing design S16/UX-77; scheduled runs never offer the confirm. |
- **Accessibility**: Switch row needs a label tied to the switch (use `SettingsToggleRow`, `SettingsComponents.kt:38`, which wraps `Switch` in `SettingsRow`; confirm the row is one toggleable semantic node, not two); interval picker as radio group with >= 48dp rows; status text in a polite live region only on change. Keyboard: all controls reachable by Tab; Space toggles.
- **First-sync gate**: per design S14/UX-81, scheduled sync must not run before the first-sync review is confirmed. The switch shows "Review first sync to enable" until then.

## 3. Asset pipeline (OCR, labeling, cloud plugins)

### Facts
- Services: `AssetPipelineService` has `processAsset`, `scheduleProcess`, `scheduleBackfill`, `shutdown` and only a `scope.cancel()` for cancellation (`asset/pipeline/AssetPipelineService.kt:38,98,110,141`). It exposes no progress `StateFlow`, so UI progress needs a new state holder.
- Cloud config: `CloudEnrichmentConfig(provider: GOOGLE_VISION|CLAUDE, apiKey, sessionCap = 20, enabled = false)` with the API key redacted in `toString` (`asset/pipeline/CloudEnrichmentConfig.kt:5-13`). The type already encodes: off by default, per-session cap, secret hygiene.
- Existing UI: Assets browser (`ui/assets/AssetBrowserScreen.kt`, empty states in `AssetBrowserEmptyState.kt`), Settings > Plugins which today is literally "No plugins installed" (`PluginsSettings.kt:12-16`), and a provider-key UI to copy conventions from (`AddEditLlmProviderDialog.kt`, `LlmProviderListScreen.kt`, `PerFeatureProviderPicker.kt`).

### Job to be done
"Make my attached images and PDFs searchable (text in images, labels) without sending my private notes' images anywhere I didn't approve, and without draining battery or heap on the phone."

### Consent for cloud plugins (security classification: confidential)
- On-device plugins (`OcrPlugin`, `PdfTextPlugin`, `OnDeviceLabelingPlugin`): one switch each, no extra consent. Default off until verified on device (requirements).
- Cloud plugins (`CloudVisionPlugin`, `CloudClaudePlugin`): **explicit per-provider consent**, three layers:
  1. Off by default (`enabled = false`).
  2. Enabling shows a plain-language dialog before the switch flips: "Images attached to your notes will be uploaded to <Google Cloud Vision | Anthropic> for text/label extraction. Up to N images per session. Do not enable if your images contain anything you would not send to a third party." Buttons labeled by verb: "Enable uploads" / "Cancel"; default focus = Cancel. Record the consent as a setting (provider, timestamp) so it can be shown and revoked.
  3. Per-session cap shown and editable (`sessionCap` default 20) and a visible running counter "12 of 20 uploaded this session"; at cap the pipeline stops and says so, not silently.
- Credentials: reuse the AI Providers key-entry pattern rather than a new one (INFERRED fit; `AddEditLlmProviderDialog.kt` not opened). Key field masked, never echoed to logs (the `toString` redaction supports this), with a "Test key" action that uploads nothing.
- Revocation: switching off takes effect immediately for queued work; show how many queued items were dropped. Provide "Forget API key".
- Per-image override: nice-to-have, not required. Skip unless the owner asks.
- Where: Settings > Plugins (currently an empty placeholder, so no existing layout to break) or a new "Assets" section inside it. Do not add a new category.

### Progress and cancel
- Backfill (`scheduleBackfill`) over a 9.5k-page graph can be long; it must be bounded and cancellable (requirements NFR).
- Recommended surface (fits existing conventions): a settings row `Image processing: 340 of 2,100 done · Pause`. Pattern precedent in repo: `FolderSyncReconciliationProgress.kt`, `StorageMoveProgressDialog.kt` (both exist under `ui/`). Use a determinate bar (count known via a `countUnprocessed` like `countUnloadedPages` in CLAUDE.md), a Pause/Resume button, and `Cancel`.
- Not a modal: processing is background; a modal like `StorageMoveProgressDialog` is for blocking operations only.
- Metered/battery: Android backfill runs only while charging or on user tap "Process now" (INFERRED default; decision for the plan stage, not a UX fact). Surface the condition as text: "Waiting for charger".
- Failure: per-asset failures count and are reviewable in Logs (`Logger` persisted sink per requirements); show `12 failed — view log` rather than a dialog per failure.
- Empty state: if no plugin enabled, the Assets browser shows a one-line prompt "Make images searchable — turn on text recognition" linking to the settings row. Existing empty states in `AssetBrowserEmptyState.kt` only say "Attach a file…".
- Result visibility: results are only useful if surfaced. Search and asset-browser filtering must show extracted text/labels, else the feature is invisible. Needs a decision on which surface (Assets search bar `AssetBrowserSearchBar.kt` vs global `SearchDialog`); flagged as open.

### Accessibility
Progress announced with `ProgressBarRangeInfo` semantics and a polite live region throttled (e.g. every 10%, not every item); consent dialog traps focus and Escape cancels; all controls >= 48dp; cap counter readable at 2.0x font scale at 360dp without truncation (wrap, do not ellipsize numbers).

## 4. Search / Datalog / visual query UI

### Facts (VERIFIED)
- Shipped search UI: `ui/components/SearchDialog.kt` (keyboard nav present) and `QueryBlock.kt` that renders `{{query ...}}` macros via `dev.stapler.stelekit.query.QueryExecutor` (`QueryBlock.kt:45-46`), gated by a `live_query_blocks` flag (`:56`).
- `DatalogEngine.execute` returns `emptyList()` always and `VisualQueryBuilder.buildFromSchema` is a TODO (`search/DatalogQuery.kt:69-80`). They are stubs, not wireable features.

### Recommendation
No new user-facing surface. A Datalog box returning empty results would be a worse failure than no box (silent empty = looks like "no matches"). The user-visible query story is `{{query}}` blocks (already wired) plus, for the owner, the console's SQL. Logseq users expect Datalog; SteleKit's `{{query}}` macro plus SQL covers the job. Recommend **delete** the stubs unless a later product decision wants Datalog parity; if kept, they must not appear in any UI. This supports the requirements' wire-or-delete record with a UX reason (no job-to-be-done not already met). `RTCManager`, `VectorSearch`, `JsPluginBridge` UX not assessed (outside this question's named surfaces); any wiring of vector/semantic search would need its own empty/loading design.

## 5. Cross-cutting conventions to follow
| Convention | Where | Applies to |
|---|---|---|
| Error text "<what happened> — <what to do>" | `SyncStatusBadge`, design S1 | sync, pipeline, console |
| Disabled with reason, not hidden | CLAUDE.md (`TargetWriterCapabilities`) | cloud plugins without key, sync without remote, console on Web |
| Settings rows via `SettingsSection` / `SettingsToggleRow` | `SettingsComponents.kt:38` | all new toggles |
| Optional settings params null = hide section | `DeveloperSettings.kt:13-22` | wiring new settings through `SettingsDialogHost` |
| Share/export via `rememberShareProvider` | `LogDashboard.kt` | console, pipeline failure report |
| Color never the only channel; theme tokens | design UX-94 | all |
| Background work never takes focus / opens dialogs | design S1 | scheduled sync, pipeline |
| Compact (<600dp) is the primary layout | `WindowSizeClass.kt:22` | all |

## 6. Test implications (feed to the coverage plan)
- Robolectric (`androidUnitTest`) is the right home for compose-behavior tests of: developer-mode toggle visibility, consent dialog gating (default focus Cancel, no flip on cancel), disabled-with-reason states, 360dp / font-scale layouts. Precedent: `NewGraphFlowTest.kt` (CLAUDE.md "Testing best practices"). Real Compose renderer (`jvmTest`) only for screenshots.
- TalkBack and soft-keyboard behavior cannot be covered by tests; one owner device pass is a named blocker.

## 7. Open questions for the plan
1. Console first slice: read-only only, or include the write tier? (Recommended: read-only first; matches metric 3.)
2. Desktop sync: interval floor (5 min recommended) and failure-pause threshold (3 suggested).
3. Asset pipeline: charging-only backfill on Android? Where extracted text surfaces in search?
4. Delete the Datalog stubs (recommended) or keep?

## 8. Gaps
- Comparable-product behaviors are from memory.
- Not opened: `AddEditLlmProviderDialog.kt`, `FolderSyncSettings.kt` internals, `SettingsDialogHost.kt`, `PerformanceDashboard.kt` internals (only listed; has 8 accessibility-related hits by count, content not read). Conventions inferred from file names and the prior UX design.
- `project_plans/image-pipeline/implementation/plan.md` is about the image-loading pipeline (`ImageLoaderCore`, `SteleKitImage`), a different thing from `AssetPipelineService` plugins; no prior UX design exists for the plugin pipeline.
- No device, TalkBack or font-scale run.
