# UX Design: graph-mcp-server

**Date**: 2026-09-14
**Inputs**: `../requirements.md`, `../research/ux.md`, `../implementation/plan.md`
(Stories 3.2.2, 4.1.1–4.1.3, ADR-002 cap = 20/source).

This plan introduces exactly one new interactive screen edit (the Settings panel),
one modification to an existing shipped screen (provenance on
`LlmSuggestionReviewScreen`), one new small persistent affordance (sidebar badge),
and one new error banner. Everything else in the feature (the launcher shim, the
MCP-client-facing cap error, log lines) is machine-to-machine or external-process
output that no SteleKit user directly operates — those get condensed entries per
the task brief rather than full wireframes.

---

## 1. Surface Inventory

| # | Surface | Type | New or modified | Plan story |
|---|---------|------|------------------|------------|
| A | Settings → "MCP Server" panel | Interactive (toggle + text) | New | 4.1.1–4.1.2 |
| B | `LlmSuggestionReviewScreen` — MCP provenance badge + stale-suggestion error | Interactive (existing screen, modified) | Modified | ADR-002 (provenance field), reuses Epic 7 |
| C | Sidebar pending-MCP-suggestion badge | Interactive (click-through) | New | 3.2.2 |
| D | MCP-server-failed-to-start banner | Interactive (dismissible banner) | New | Observability Plan / ADR-001 |
| E | `McpLauncherMain` stdio failure output | Non-interactive (subprocess stderr) | New | 1.2.2 |
| F | `propose_*` "review queue full" tool error | Non-interactive (text returned into the external MCP host's chat) | New | 3.2.1, ADR-002 |
| G | MCP tool-call log lines | Non-interactive (log file) | New | Observability Plan | 

Four surfaces (A–D) get full wireframe + flow + edge-case treatment (Section 2).
Three (E–G) get condensed entries (Section 3). UX acceptance criteria are in
Section 4.

---

## 2. Interactive Surfaces

### A. Settings → "MCP Server" panel

**Where**: `SettingsDialog.kt`, new `SettingsCategory.MCP_SERVER` entry, always
visible in the left-hand category list (same tier as `GENERAL`, not gated behind a
null-checked optional like `DEVELOPER`/`TAG_SUGGESTIONS`). Follows the existing
`SettingsSection` / `SettingsToggleRow` shape used by `DeveloperSettings`
([`SettingsDialog.kt:444-464`](../../../kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/SettingsDialog.kt#L444-L464)).

#### Wireframe — toggle off (default state)

```
┌─ Settings ─────────────────────────────────────────────────┐
│ ┌────────────────┐  ┌──────────────────────────────────┐   │
│ │ General         │  │  MCP Server                      │   │
│ │ Editor          │  │  ─────────────────────────────── │   │
│ │ Plugins         │  │                                   │   │
│ │ AI Providers    │  │  Let an MCP client (Claude        │   │
│ │ Tag Suggestions │  │  Desktop, Claude Code) search      │   │
│ │▶MCP Server      │  │  and read this graph, and propose  │   │
│ │ Developer       │  │  edits for you to review here.     │   │
│ │ ...             │  │  Local-only — no network access.   │   │
│ │                 │  │                                   │   │
│ │                 │  │  Enable MCP server     [ ○────  ] │   │
│ │                 │  │                          off       │   │
│ │                 │  │                                   │   │
│ │                 │  │  Status: Not running               │   │
│ └────────────────┘  └──────────────────────────────────┘   │
│                                                   [ Close ]  │
└───────────────────────────────────────────────────────────┘
```

#### Wireframe — toggle on, graph open (connected state)

```
┌─ Settings ─────────────────────────────────────────────────┐
│ ┌────────────────┐  ┌──────────────────────────────────┐   │
│ │ ...             │  │  MCP Server                      │   │
│ │▶MCP Server      │  │  ─────────────────────────────── │   │
│ │ ...             │  │                                   │   │
│ │                 │  │  Enable MCP server     [────●  ]  │   │
│ │                 │  │                          on       │   │
│ │                 │  │                                   │   │
│ │                 │  │  Status: ● Running for "Personal"  │   │
│ │                 │  │                                   │   │
│ │                 │  │  Connect Claude Desktop/Code by    │   │
│ │                 │  │  pointing an MCP server entry at:  │   │
│ │                 │  │  ┌───────────────────────────────┐│   │
│ │                 │  │  │java -jar mcp-launcher.jar     ││   │
│ │                 │  │  │  --graph-id personal    [Copy]││   │
│ │                 │  │  └───────────────────────────────┘│   │
│ │                 │  │  Pending suggestions from MCP: 3   │   │
│ │                 │  │  [ Review suggestions ]            │   │
│ └────────────────┘  └──────────────────────────────────┘   │
│                                                   [ Close ]  │
└───────────────────────────────────────────────────────────┘
```

#### Interaction flow

1. User opens Settings → clicks "MCP Server" in the category list. Panel renders
   with the toggle reflecting `AppState.mcpServerEnabled` (persisted via
   `platformSettings`, default `false` — Story 4.1.1).
2. User flips the toggle on. `StelekitViewModel.setMcpServerEnabled(true)` fires
   immediately (Story 4.1.1); `McpServerController` (Story 4.1.3) starts
   `McpSocketServer` for the active graph within the same frame the toggle
   animates — no separate "Apply"/"Save" button, matching `DeveloperSettings`'s
   immediate-effect pattern.
3. Status line updates from "Not running" to "Running for \"<active graph
   name>\"" once the socket is bound. The launcher command block appears only
   when running, with a "Copy" action (copies the exact command a user pastes
   into their MCP host's config file).
4. If a suggestion is currently pending from an MCP source, a "Pending
   suggestions from MCP: N" line and a "Review suggestions" button appear,
   opening `LlmSuggestionReviewScreen` (Surface B) directly — this is the
   Settings-panel entry point into that screen, in addition to the sidebar
   badge (Surface C).
5. User flips the toggle off at any time. `McpSocketServer.stop()` runs
   immediately (Story 4.1.3); status reverts to "Not running." Existing pending
   suggestions are **not** discarded — the inbox is independent of the toggle,
   so a user who disables MCP mid-review can still open the review screen from
   the sidebar badge and finish reviewing what was already proposed.

#### Error / edge cases

| Condition | What the user sees | Exit path |
|---|---|---|
| Toggle on, but no graph is open | Status: "Waiting for a graph to open" (never "Running"); `McpServerController` never calls `.start()` per Story 4.1.3's guard | Opening any graph starts the server automatically; no user action required |
| Toggle on, socket bind fails (e.g. stale `.sock` file, permission error) | Top-of-app dismissible banner, Surface D | Banner's own "Dismiss"/retry-by-toggling-off-and-on path |
| User copies the launcher command before enabling | "Copy" button and command block are only rendered when `isMcpServerEnabled` is true — there is nothing to copy in the off state, preventing a copy of a command that won't work yet | N/A — state is unreachable by construction |

---

### B. `LlmSuggestionReviewScreen` — MCP provenance + stale-suggestion error

**Where**: existing, shipped screen
([`LlmSuggestionReviewScreen.kt`](../../../kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/llm/LlmSuggestionReviewScreen.kt)).
This plan does not rebuild it — it adds one visual element (a provenance chip next
to the existing type badge) and relies on the screen's existing, already-tested
staleness-error path (`LlmSuggestionWriter`'s re-validation, surfaced via
`sendSnackbar`) with no new mechanism, per `research/ux.md` §4a.

#### Wireframe — MCP-sourced suggestion, normal review

```
┌─ Review LLM Suggestions (2/5) ─────────────────── [ Close ]─┐
│                                                               │
│  ┌────────┐ ┌──────────────────────┐                         │
│  │  Edit  │ │ 🔌 via Claude Desktop │                         │
│  └────────┘ └──────────────────────┘                         │
│                                                               │
│  "Add a summary of the Q3 retro notes"          (rationale)  │
│                                                               │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ - Q3 retro: TBD                                        │  │
│  │ + Q3 retro: shipped on time, two blockers root-caused  │  │
│  │   to the deploy pipeline; see [[Q3 Retro Notes]]        │  │
│  └───────────────────────────────────────────────────────┘  │
│                                                               │
├───────────────────────────────────────────────────────────┤
│              ◀   2 of 5   ▶                                  │
│  [   Discard   ]              [     Apply       ]            │
│  [ Discard all ]              [   Apply all      ]            │
└───────────────────────────────────────────────────────────┘
```

The provenance chip (`🔌 via Claude Desktop`) sits beside the existing type badge
(`Edit`/`New Note`/`Tag Change`/`Link`). It is rendered whenever
`sourceProviderId` starts with `"mcp:"`, parsing the client name out of the
`"mcp:<client-name>:<call-id>"` convention (ADR-002); suggestions from the
internal page-scan matcher show no chip at all (unchanged from today), preserving
`research/ux.md` §5's finding that no "suggested by Claude" badge is needed beyond
this minimal distinction — the chip exists so the user can tell "my own scan
found this" from "an external conversation proposed this," not for attribution
theater.

#### Wireframe — stale suggestion, Apply fails

```
┌─ Review LLM Suggestions (2/5) ─────────────────── [ Close ]─┐
│  ┌────────┐ ┌──────────────────────┐                         │
│  │  Edit  │ │ 🔌 via Claude Desktop │                         │
│  └────────┘ └──────────────────────┘                         │
│  ...same diff view...                                        │
├───────────────────────────────────────────────────────────┤
│ ⚠ This block changed since the suggestion was made      ✕   │  ← snackbar
├───────────────────────────────────────────────────────────┤
│              ◀   2 of 5   ▶                                  │
│  [   Discard   ]              [     Apply       ]            │
└───────────────────────────────────────────────────────────┘
```

#### Interaction flow

1. User opens the review screen (from the sidebar badge, Settings panel button,
   or however Epic 7 already surfaces it).
2. For each item, the user sees the provenance chip, rationale, and diff/preview
   exactly as today, plus the new chip for MCP-sourced items.
3. **Accept, content unchanged since propose**: `acceptLlmSuggestion(id)` →
   `LlmSuggestionWriter` re-reads the live block, confirms it still matches
   `currentContentSnapshot`, writes via `GraphWriterPort`, item is removed from
   the queue, view advances to the next item automatically (existing behavior,
   `LaunchedEffect(pending.size)` re-clamps `currentIndex`).
4. **Accept, content changed since propose (stale)**: `LlmSuggestionWriter`
   detects the mismatch, returns
   `DomainError.ConflictError.ConcurrentWrite(pageUuid, "This block changed
   since the suggestion was made")`. `StelekitViewModel.acceptLlmSuggestion`
   surfaces it via `sendSnackbar(error.message)` (existing mechanism, no new
   code needed for MCP-sourced items — same path the page-scan matcher's stale
   suggestions already use). The item stays in the queue; the diff shown is now
   known-stale. User's next action is **Discard** (safe — nothing was written)
   or navigate away and re-request the edit from the MCP client in a fresh
   conversation turn.
5. **Discard**: fires immediately, no confirmation (existing behavior — nothing
   was ever written, so discard is non-destructive).
6. **Apply all / Discard all**: unchanged bulk actions, double-tap-confirmed for
   Apply all (existing mitigation for approval fatigue, `research/ux.md` §1).
   MCP-sourced and matcher-sourced items are not segregated in bulk actions —
   "Apply all" applies everything currently in the queue regardless of
   provenance, which is intentional (the provenance chip informs review, it does
   not partition the action).

#### Error / edge cases

| Condition | What the user sees | Exit path |
|---|---|---|
| Target block/page deleted since propose | Snackbar: `DomainError.DatabaseError.NotFound` message surfaces via the same `sendSnackbar` path | Discard the now-orphaned suggestion |
| Target block content changed since propose (stale) | Snackbar: "This block changed since the suggestion was made"; item remains in queue, diff view still shows the (now stale) before/after | Discard, or close and ask the MCP client to re-propose against current content |
| Queue empties while screen is open (last item accepted/rejected) | Screen auto-dismisses (`LaunchedEffect(Unit) { latestOnDismiss() }` — existing behavior, unchanged) | N/A — no dead end, screen closes itself |
| MCP client's `propose_new_page` sent >1 block, user wants only some | No partial acceptance exists today (`research/ux.md` §1, "documented pain point, not yet addressed") — user must Apply the whole `NewPage` proposal or Discard it entirely | Named as a v2 gap, not a v1 blocker per requirements.md scope; not fixed by this plan |

---

### C. Sidebar pending-MCP-suggestion badge

**Where**: sidebar composable (exact file confirmed via `Glob` at implementation
time per Story 3.2.2's file list), next to the existing settings/notifications
affordance.

#### Wireframe

```
┌─ Sidebar ──────────┐
│ ⭐ Favorites        │
│ 📄 All Pages        │
│ 📅 Journals         │
│ 🔍 Search           │
│                     │
│ ⚙ Settings          │
│ 🔌 MCP  (3) ←────── │  badge, only rendered when count > 0
└────────────────────┘
```

#### Interaction flow

1. `AppState.pendingMcpSuggestionCount` (derived in `StelekitViewModel` from
   `LlmSuggestionInbox.pending` filtered to `sourceProviderId.startsWith("mcp:")`,
   Story 3.2.2) drives badge visibility: hidden at 0, a small numeric badge
   otherwise.
2. Clicking the badge opens `LlmSuggestionReviewScreen` (Surface B) directly —
   this closes the exact gap `research/ux.md` §2 names ("user dismisses the
   review screen, has no reminder suggestions are still pending, MCP client
   thinks its work vanished").
3. Badge count updates live as the user accepts/discards items or as new MCP
   proposals arrive while the app is open, since it's driven by the same
   `StateFlow` the review screen reads.

#### Error / edge cases

| Condition | What the user sees | Exit path |
|---|---|---|
| Count includes only MCP-sourced items, matcher-sourced items also pending | Badge shows the MCP-only count (e.g. "3", not "5" if 2 matcher-sourced items also exist) — per Story 3.2.2's explicit acceptance criterion | Opening the review screen shows all pending items regardless of source; the badge is a *notice*, not a filter |
| MCP server disabled after suggestions were proposed | Badge persists (inbox is independent of the toggle, see Surface A flow step 5) | User can still review and act on already-proposed items |

---

### D. MCP-server-failed-to-start banner

**Where**: new, modeled directly on
[`HostReconnectBanner.kt`](../../../kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/HostReconnectBanner.kt)
— top-of-app `Surface` in `errorContainer` color, single-line message + action
button(s), whole-surface `.semantics { contentDescription = message }` per the
existing accessibility precedent.

#### Wireframe

```
┌───────────────────────────────────────────────────────────┐
│ ⚠ MCP server couldn't start: address already in use.        │
│   Graph editing still works normally.        [Retry] [Dismiss]│
├───────────────────────────────────────────────────────────┤
│  (rest of app UI, unaffected)                                │
```

#### Interaction flow

1. `McpServerController` attempts `McpSocketServer.start()` on toggle-on (or on
   graph-open, if the toggle was already on). If bind fails (stale socket file
   from an unclean shutdown, permission error on `~/.stelekit/mcp/`, etc.), the
   controller surfaces the failure — not as an exception, per `CLAUDE.md`'s
   "Uncaught coroutine Throwables" rule (guarded scope, `CoroutineExceptionHandler`)
   — as an `AppState` banner-visible flag with the causing message.
2. Banner appears at the top of the app, below the title bar, above normal
   content — same slot `HostReconnectBanner` and `BrowserOnlySyncBanner` use.
   The message explicitly states the feature (MCP) and reassures the user that
   core graph editing is unaffected (per the Observability Plan: "MCP
   connectivity is never required for the app's core read/write loop").
3. **Retry**: re-attempts `McpSocketServer.start()` (useful for the stale-socket
   case — a prior unclean shutdown can leave a `.sock` file that a fresh start
   attempt, which deletes-then-rebinds, resolves).
4. **Dismiss**: hides the banner; the Settings panel's "MCP Server" status line
   still shows "Not running" so the state remains discoverable, it's just not
   pinned to the top of the app anymore (matches `HostReconnectBanner`'s
   dismiss-is-non-destructive precedent).

#### Error / edge cases

| Condition | What the user sees | Exit path |
|---|---|---|
| Bind fails because another SteleKit instance already holds the socket for this graph | Banner message specific to that cause ("Another SteleKit window is already running the MCP server for this graph") | Close the other window, then Retry |
| Retry fails again | Banner reappears with the same message (idempotent — no infinite retry loop, no silent swallow) | Dismiss and use Settings toggle off/on as a manual reset, or file the issue — MCP is optional so the user is never blocked from normal use |
| User never notices the banner (app minimized when it appears) | No OS-level notification (consistent, documented limitation — `research/ux.md` §2: no system tray exists in this codebase) | Settings panel status line is the durable record; matches the accepted precedent for `PendingConflict`/`DiskConflict` |

---

## 3. Non-Interactive Surfaces (condensed)

### E. `McpLauncherMain` stdio failure output

Sample (`stderr`, when the launcher runs but SteleKit isn't running or MCP is
disabled for the requested graph):

```
SteleKit is not running or MCP is not enabled for graph g1
```
Exit code `1`, printed within 2 seconds (Story 1.2.2's own acceptance criterion).

Acceptance criteria:
- Message names the graph id so a user debugging their MCP host config can match
  it against `--graph-id`.
- Never hangs — exits within 2s whether or not a socket exists to connect to.
- Exit code is non-zero on failure, `0`+bidirectional-proxy on success, so the
  calling MCP host's own subprocess-health UI (if any) reflects the real state.
- No secrets/graph content in the message — only the graph id, which is already
  user-supplied on the command line.
- This is read by a developer/power-user configuring an MCP host's JSON config,
  not the average SteleKit user — clarity over friendliness is the right bar.

### F. `propose_*` "review queue full" tool error

Sample (returned as `CallToolResult(isError = true, content = [TextContent(...)])`
to the calling MCP host, e.g. rendered inline in a Claude Desktop conversation):

```
Error: Your review queue is full (20 pending suggestions from this
conversation). Ask the user to open SteleKit and review pending
suggestions before proposing more.
```

Acceptance criteria:
- Names the specific cap (20) so the message is self-explanatory without the
  reader needing to know `MCP_PENDING_CAP`'s value from source.
- Explicitly instructs the calling LLM to relay the situation to the human
  ("Ask the user to...") — this is the mechanism by which a silent SteleKit-side
  cap becomes a legible signal on the MCP-client side, per `research/ux.md` §4c
  option 1.
- Does not silently drop the proposal content — the MCP client (external LLM)
  still holds the content in its own conversation context and can retry after
  the user reviews, so no data is lost, only deferred.
- Per-source, not global: a full page-scan-matcher queue never triggers this
  message for an MCP client, and vice versa (Story 3.2.1's explicit multi-source
  test).

### G. MCP tool-call log lines

Sample (`FileLogSink`, INFO level):

```
INFO  mcp  tool=search_graph elapsedMs=12 result=ok
INFO  mcp  tool=propose_block_edit elapsedMs=4 result=ok
ERROR mcp  socket bind failed: address already in use (graph=g1)
```

Acceptance criteria:
- Tool arguments/results never appear at INFO (private graph content) — only
  `tool`, `elapsedMs`, `result` per the Observability Plan; full payloads gated
  to DEBUG.
- Socket bind/launcher-connect failures always log at ERROR, matching Surface
  D's banner trigger condition — the log line and the banner correspond to the
  same event, not two independent failure paths that could disagree.
- This surface has no direct human reader in the moment — it exists for
  post-hoc debugging (a user filing a bug, a maintainer reading `~/.stelekit/logs`
  per the existing convention in `MEMORY.md`'s "startup lockup" entry) rather
  than as a live UX surface.

---

## 4. UX Acceptance Criteria

Numbered for traceability; each is independently testable by a human tester
following the steps given.

1. **Enable MCP in ≤2 clicks.** From the main app window: Settings → MCP Server
   category → toggle. Two clicks total (open Settings is a single click from the
   sidebar's existing gear icon, category switch is the first click inside the
   dialog, toggle is the second). No modal confirmation, no restart required.
2. **Disable MCP in ≤2 clicks**, same path, toggle reflects off state
   immediately and `McpServerController` stops the socket within the same UI
   frame (no perceptible delay before the status line updates).
3. **Copy the launcher command in ≤1 click** once the panel shows it (toggle
   already on) — the "Copy" button copies the exact runnable command, verified
   by pasting into a terminal and confirming it matches the literal string
   containing `--graph-id`.
4. **Provenance is visually distinguishable without reading the rationale
   text.** A user shown two suggestions side by side — one matcher-sourced, one
   MCP-sourced — can correctly identify which is which by glancing at the badge
   row alone (chip present vs. absent), without reading the diff or rationale.
5. **No dead ends.** Every error state in Sections 2 and 3 has a named exit
   path in this document (see the "Exit path" column of each table) — verified
   by re-reading Section 2's tables and confirming no row has an empty or
   "none" exit path. `research/ux.md`'s own review-fatigue finding is
   specifically about *degraded* review quality, not a dead end — that risk is
   mitigated by the cap (Surface F), not eliminated, and is named as a
   residual risk rather than claimed solved.
6. **Stale-suggestion error is specific, not generic.** Accepting a suggestion
   whose target content changed since propose shows the literal message "This
   block changed since the suggestion was made" (or the equivalent
   `NotFound`/`ConcurrentWrite` message), never a bare "Something went wrong" —
   verified against `LlmSuggestionWriter`'s existing, shipped error strings
   (no new copy needed, this criterion confirms the existing strings remain
   wired through for MCP-sourced items too).
7. **Cap-reached is legible to the human, not just the calling LLM.** When the
   MCP-source cap is hit, the sidebar badge (Surface C) shows exactly 20 (its
   ceiling under normal operation, absent a race) and the Settings panel's
   "Pending suggestions from MCP" line matches — a user who opens SteleKit
   after the MCP client reports "queue full" sees a consistent number in both
   places, not a stale or divergent one.
8. **Socket-bind failure never blocks core app use.** With the banner (Surface
   D) visible, a user can still create/edit/save pages, search, and use every
   non-MCP feature with no additional clicks or dialogs required to route
   around it — verified by performing a normal edit-and-save cycle while the
   banner is on screen.
9. **Keyboard-only operability.** Every clickable control introduced by this
   plan (Settings toggle, Copy button, Review-suggestions button, sidebar
   badge, banner's Retry/Dismiss buttons, the new provenance chip if it's ever
   made interactive — it is not, per Section 2's design, so this is N/A for the
   chip) is reachable via Tab order and activatable via Enter/Space, verified
   with the same `hasClickAction && hasRequestFocusAction` assertion pattern
   `QrTransferAccessibilityUxTest.kt` already uses elsewhere in this codebase
   — apply that pattern to a new test covering the MCP Settings panel and the
   provenance-chip-augmented review screen.
10. **Screen-reader labels are complete.** The banner (Surface D) carries a
    single `.semantics { contentDescription = message }` over its full `Surface`
    (per `HostReconnectBanner`'s precedent) so a screen reader announces the
    whole message as one unit; the sidebar badge's count is announced (e.g.
    `contentDescription = "3 pending MCP suggestions"`, not a bare "3"); the
    provenance chip's `contentDescription` states the full client name (e.g.
    "Proposed via Claude Desktop"), not just the icon.
11. **Contrast ≥4.5:1.** The `errorContainer`/`onErrorContainer` color pair used
    by Surface D (inherited unchanged from `HostReconnectBanner`'s existing,
    presumably-already-audited Material3 theme tokens) and the provenance
    chip's default `AssistChip` colors both meet WCAG AA 4.5:1 for normal text
    — verified with a contrast-checker tool against the theme's actual resolved
    RGB values (both light and dark mode), not assumed from the token names
    alone.
12. **No new confirmation dialog for routine accept/discard.** Consistent with
    Epic 7's existing design (`research/ux.md` §1: single accept-click per item,
    double-confirm reserved for bulk "Apply all" only) — this plan adds a
    provenance chip and a cap, it does not add a new per-item confirmation step
    for MCP-sourced items specifically. A tester confirms accepting a single
    MCP-sourced suggestion takes exactly one click on "Apply," identical to a
    matcher-sourced one.

---

## 5. Explicitly Out of Scope for This Design Pass

Named so a reviewer doesn't read their absence as an oversight:

- **OS-level notifications / system tray.** `research/ux.md` §2 recommends
  against building this for v1 (real net-new platform surface, no existing
  hook). The sidebar badge + Settings panel status line are the complete v1
  notification surface.
- **Partial acceptance of multi-block `NewPage` proposals.** Named as a v2 gap
  in `research/ux.md` §1 and repeated in Section 2's error-case table here —
  not designed or scoped in this pass.
- **Thumbs-up/down feedback on suggestions.** Explicitly out of scope per
  `requirements.md` ("Out of Scope: agentic loop") and `research/ux.md` §1.
