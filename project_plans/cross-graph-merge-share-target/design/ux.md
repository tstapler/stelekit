# UX Design: cross-graph merge and share target

**Date**: 2026-10-07
**Inputs**: `requirements.md`, `research/ux.md`, `implementation/plan.md` (stories 3.1-3.4, 4.1-4.5), ADR-002 (conflict model), ADR-003 (undo, closure).
**Status**: Proposed. Updated 2026-10-07 (Repair pass 6 triad: loading states, Stop wording, Mark resolved, Back toast, a11y/RTL/large text, journeys; Repair pass 5: iOS/Web pull-style copy; Repair pass 4) with the user's final decisions: unwritable copy targets are disabled (copies never queue), Desktop Esc asks / Android Back auto-saves, `merge_force_inbox` cut. Vocabulary: copy results are new / combined / unchanged (conflicts are inside combined); share failures are "queued". Mental-model claims (one verb "Copy pages to...", "combined" wording) are inferences from research, not user-tested.

## Design principles

1. One verb: **"Copy pages to..."**. The dry-run teaches the semantics ("combined", never "merged/overwritten").
2. Nothing is ever lost silently: every failure keeps the user's content visible and offers an action.
3. Reuse existing components: Material3 `AlertDialog`/`Dialog`, `FilterChip`, `Checkbox` (`AllPagesScreen.kt`), `StorageMoveProgressDialog` shape, `SearchDialog` key handling, snackbar plus a persistent result dialog.
4. Every state has text, not color or icon alone. Targets are 48dp. Counts that change are `liveRegion = Polite`.

## Surface inventory

| # | Surface | Kind | Plan story |
|---|---------|------|-----------|
| S1 | Entry points (sidebar, page menu) | Interactive | 3.4.1 |
| S2 | Page picker screen (search, filters, selection, linked-page options) | Interactive | 3.1.1, 3.2.1, 2.4.3 |
| S3 | Destination chooser (and, on iOS/Web, the source chooser "Copy from:") | Interactive | 3.1.1c, 2.3.3, 4.5.1, 4.5.3 |
| S4 | Dry-run dialog (incl. nothing-to-do, stale, confirm-large) | Interactive | 3.3.1 |
| S5 | Progress dialog | Interactive | 3.3.1 |
| S6 | Result dialog | Interactive | 3.3.1 |
| S7 | Undo flow and outcome | Interactive | 2.5.1 |
| S8 | Conflict review screen (Gate 1, committed; moved from Gate 2 in Repair pass 7) | Interactive | 3.3.2 |
| S9 | Interrupted-copy notice | Interactive | 3.3.1, 2.2.2 |
| S10 | Android share overlay destination row | Interactive | 4.2.1 |
| S11 | Desktop quick capture graph chooser | Interactive | 4.3.1 |
| S12 | Settings: capture target | Interactive | 4.1.1c |
| S13 | Queued shares indicator and rescue actions Copy text/Discard/Retry now (Gate 1, committed; rescue actions moved from Gate 2 in Repair pass 7) | Interactive | 4.4.1, 4.4.1d |
| S14 | Android Direct Share shortcuts (optional, first on the cut list; not in the token budget) | Interactive (system UI) | 4.2.2 |
| S15 | Merge and share log output | Non-interactive | Observability |

---

## S1. Entry points

```
Left sidebar                      Page overflow (...)
+-----------------------+         +-----------------------+
| ...                   |         | Favorite              |
| [Copy pages to...]    |         | Copy this page to...  |
+-----------------------+         +-----------------------+
```

Flow:
1. User taps "Copy pages to..." -> S2 opens with nothing selected. Source = current graph.
2. User taps "Copy this page to..." -> S2 opens with that page preselected (1 selected) and focus on the Destination control.
3. Replaces both "export pages for merge" and "Merge captured pages". No hidden mode survives a graph switch.
4. iOS/Web (pull direction, Stories 4.5.1-4.5.3): the sidebar item, the command palette entry and the graph-switcher row overflow read "Copy pages from..." / "Copy pages from <graph> to <current graph>" (the switcher entry is cuttable). Tapping opens S2 with the SOURCE chooser (S3 pull variant) first; the current graph is the destination and is shown in the header as "Copying into <current graph>". Push ("Copy pages to...") is not offered on these platforms.

Subtitle (Repair pass 7): the sidebar and command-palette entry shows the secondary line "Adds pages; combines with existing ones" under "Copy pages to..." (mitigates the verb implying a plain duplicate).

Errors: if fewer than 2 graphs are registered, the action stays visible but opens a one-screen empty state (see S3 "no other graphs").

## S2. Page picker screen

```
+--------------------------------------------------+
| <  Copy pages from "Personal"        [Esc/Close] |
+--------------------------------------------------+
| [ Search pages...                              ] |
| (Pages) (Journals) (Date range v) (Namespace v)  |
| (Tag v)                                          |
| 213 results, 12 selected                         |  <- live region
| [Select all 213 matching]  [Clear]               |
|--------------------------------------------------|
| [x] Roadmap                   14 blocks          |
| [ ] Reading list               3 blocks          |
| [x] 2026-10-07 (journal)       6 blocks          |
|  ... virtualized lazy list, 100-row pages ...    |
|--------------------------------------------------|
| [ ] Include linked pages  (adds 0 pages)         |
|     [ ] Include their assets                     |
|--------------------------------------------------|
| Copy to: [ Choose destination v ]                |
|                         [Cancel] [Review copy]   |
+--------------------------------------------------+
```

Gate 1 variant (Repair pass 7): the "Include linked pages" toggle, its assets sub-toggle and the preselected last-used destination are Gate 2. Gate 1 hides the two toggles entirely (not disabled) and starts the Destination control empty with the helper line "Choose a destination".

Interaction flow:
1. Typing in search (debounced) re-queries; selection is keyed by page uuid so off-screen and filtered-out selections persist. Counter announces "213 results, 12 selected".
2. Filter chips are toggles; date range, namespace, tag open a small menu. Filters AND together.
3. "Select all 213 matching" selects only the filtered set and says so in its label. A separate overflow item "All pages in graph" asks for confirmation ("Select all 8,030 pages?").
4. "Include linked pages" is off by default. Turning it on shows a live delta "adds N pages" (depth 1 only). If N > 200 the toggle needs an inline confirmation ("This adds 1,340 pages. Include them?"). If N > 1,000 the line reads "Adding the first 1,000 of 1,340. Run again for the rest." Assets are only offered when linked pages is on.
5. "Review copy" is disabled until >= 1 page is selected and a destination is chosen; a helper line states which is missing ("Select at least one page", "Choose a destination"). It opens S4.

Keyboard (desktop): Tab order search -> chips -> select-all -> list -> link options -> destination -> Review. Arrows move in list, Space toggles, Ctrl/Cmd+A selects all filtered, Esc cancels (confirm if selection > 0), Enter activates only the focused button, never while in search.

Loading states (plan Task 3.2.1f):
- Opening on an 8,000-page graph: 5 skeleton rows (no layout jump), header "Loading pages...", count line "Counting..." until the count query returns (polite live region). Further 100-row pages append with a footer spinner; scrolling is never blocked.
- Search/filter change: previous rows stay, dimmed, with "Updating..." until the new first page arrives; selection is untouched.
- Skeletons use no required animation (reduced-motion safe); every loading state has text, not just a spinner.
- Pull variant (iOS/Web): after choosing a source, "Reading <graph>... N files found"; the list is usable over what has loaded with a "Still reading..." note; Back stops the read (Task 4.5.3g).

Error and edge states:

| Condition | What the user sees | Exit / action |
|-----------|-------------------|---------------|
| Source graph read fails or DB closed mid-use | Inline banner "Couldn't read pages from Personal." | "Retry", "Close" |
| Search or filter has no results | "No pages match. Clear filters" | "Clear filters" button |
| Graph has no pages | "This graph has no pages to copy." | Close |
| Selection includes page being edited (target side, found at apply) | Handled at S6 as deferred item | Retry from S6 |
| Linked-page closure exceeds hard cap | Truncation line above | Proceed or untick |
| Esc/back with selection | "Discard selection of 12 pages?" Discard / Keep editing | both exit paths explicit |

## S3. Destination chooser

Opens as a menu anchored to "Copy to:" (<= 4 graphs shown as chips, otherwise a list). Selecting never clears the selection.

```
Copy to:  ( Work graph )  ( Archive )  [ Personal - current graph  (disabled) ]
          Personal is disabled: "current graph"
```

Rules and states:
- Current (source) graph is shown disabled with the reason "current graph".
- Each entry shows availability. A graph that cannot be written off-graph (encrypted, SAF grant lost or SAF atomic replace unavailable, platform cannot address the path per plan Story 4.5.1) is DISABLED WITH ITS REASON, never hidden and never selectable: "Can't write here: folder access was revoked" with an action "Re-grant access" where the platform supports it, otherwise "Open that graph to copy into it." Copies never use the share inbox and are never deferred: there is no "will be copied when you open it" state on any platform, so every result is definite. If a grant is lost mid-run, the remaining pages fail with the reason in S6 and "Retry failed" (S6), never queued.
- iOS/Web pull variant ("Copy from:", plan Story 4.5.1): the same chooser lists SOURCE graphs; the current graph is disabled "current graph" (it is the destination). A source whose files cannot be read is DISABLED WITH ITS REASON: "Can't read <graph>: folder access expired" with "Re-select folder" (iOS document picker / Web directory picker re-grant), "Can't read <graph> in this browser" (no folder access API; no action), "<graph> folder was moved or deleted" (action: "Re-select folder"), "<graph> is encrypted". If no source is readable the screen shows "No graph to copy from" with each graph's reason, "Re-select folder" where re-grantable, "Add a graph", and Close. Nothing is queued or deferred; the push chooser on these platforms shows "Can't copy into <graph> from here on this device. Open <graph> and use Copy pages from..." with that action.
- Pull-variant picker (S2) differences: the list is the source's name index (name, journal/page, size, modified date; no block counts until the dry run); filters are name search, Pages/Journals and journal date range; tag, property and backlink filters, "Include linked pages" and "Copy linked assets" are shown disabled with "Not available when copying from a graph that isn't open". Dry-run (S4) and result (S6) are the same dialogs titled "Copy 8 pages from "Work graph" into "Personal"?"; a page that cannot be read or parsed is counted `unreadable` with its reason; mid-run grant loss or switching away fails the remaining pages with "Retry failed" (reopens the destination first), never queued.
- Availability probing (Task 3.1.1e): each graph row appears at once as "Checking..." (spinner plus text) while its write/read capability and grant are probed (each probe bounded at 3 s, results arrive independently). A timed-out probe becomes a disabled row "Couldn't check <graph>: <reason>" with Retry. Review stays disabled until the chosen row is definitive; changes are announced in a polite live region.
- No other registered graph: screen shows "You need a second graph to copy pages." with "Add a graph" and "Close".
- Last-used destination for copy is preselected but always visible before Review (Gate 2, plan Task 3.1.1d, setting `copy_last_destination_graph_id`). A preselected destination that has become disabled is not preselected.

## S4. Dry-run dialog

```
+--------------------------------------------------+
| Copy 8 pages to "Work graph"?                    |
|                                                  |
|  3   new - will be created                       |
|  5   already exist - blocks will be combined     |
|      (nothing removed)                           |
|  20  unchanged - nothing to do                   |
|  2   may conflict - both versions kept, flagged  |
|  0   couldn't be read                            |
|  2   assets renamed to avoid overwriting         |
|                                                  |
|  v Show details (lazy, 50 max conflicts listed)  |
|                                                  |
|  Nothing in the destination is deleted or       |
|  overwritten. The source graph is not changed.   |
|                                                  |
|                       [Back] [Copy 8 pages]      |
+--------------------------------------------------+
```

Flow:
1. Dialog opens with an indeterminate "Checking..." state while `plan()` streams (counters update live, live region announces completion). [Back] and Esc cancel the plan.
2. Button label restates the count: "Copy N pages" where N = new + combined (conflicts are inside combined). The label doubles as the screen-reader confirmation.
3. Confirm starts S5. If the plan is stale at commit, the dialog re-renders with the new counts and the banner "Things changed - review again"; the button becomes "Copy N pages" with the new N and the user must press it again (no auto-commit of a different set).
4. `onDismissRequest` is Back-equivalent (not destructive, unlike `DiskConflictDialog`), since nothing has been written yet.

States:

| Condition | Message | Action |
|-----------|---------|--------|
| Everything unchanged | "Nothing to copy - destination already has all selected content" | Commit disabled; [Back] |
| Plan failed (read error, target unreadable) | "Couldn't check what would change: <reason>" | "Retry", "Back" |
| Plan has unreadable pages | Line "N couldn't be read" expandable to names | Proceed (they are skipped and reported) or Back |
| Possible duplicate via alias (ADR-002.4) | Warning line "Page 'Q' may duplicate existing 'q-alias'" | Proceed or Back |
| Linked-page delta > 200 | Already confirmed in S2; repeated as a line | Back to change |
| Plan stale | "Things changed - review again" + new counts | Re-confirm or Back |
| Target went away between S3 and S4 | "Work graph is no longer available." | "Choose another destination" (returns to S3), "Close" |

## S5. Progress dialog

```
+--------------------------------------------------+
| Copying to "Work graph"                          |
| [=========>            ]  1,200 of 4,000 pages   |
| Current: Roadmap                                 |
| Pages already copied stay copied if you stop.    |
|                                          [Stop]  |
+--------------------------------------------------+
```

- Determinate bar with `progressBarRangeInfo`; "x of y" text is a polite live region updated at most every 2 seconds to avoid chatter.
- **Stop** (renamed from "Cancel", Repair pass 6: "Cancel" implies nothing happened, but pages already written stay written) is the initial focus target after the bar; reachable by Tab and TalkBack. Pressing it shows no extra confirm (it is non-destructive), stops after the page in flight, and moves to S6 with the title "Stopped after 1,200 of 4,000" and the body "1,200 pages were copied and kept. 2,800 were not copied. Run the copy again to continue, or undo." The word "Cancel" is reserved for actions where nothing has been written (dry-run Back, discarding a selection).
- Esc = Stop. The dialog is not dismissible by outside tap.
- Android: the run continues in the application scope if the activity is backgrounded; returning shows the live dialog. If the process dies, S9 appears on next launch.
- A fault in one page never aborts the run (plan 2.4.2); the final state is S6 with failures listed.
- Switching graphs during a run (Repair pass 7): push copy is never blocked and the destination does not change; the dialog (or, if the switch dismissed it, a persistent snackbar) reads "Copy to Work graph continues in the background" until the result appears. Pull copy (iOS/Web) asks "A copy into <graph> is running. Stop it and switch?" with Stop and switch / Keep copying.

## S6. Result dialog

```
+--------------------------------------------------+
| Copied to "Work graph"                           |
|  3 new   5 combined   20 unchanged               |
|  2 conflicts kept (both versions)                |
|  1 failed                                        |
|  v Failed pages (1)                              |
|      Budget 2026 - file write error  [Copy list] |
|                                                  |
| [Retry failed] [Review 2 conflicts]              |
| [Undo this copy]                      [Done]     |
+--------------------------------------------------+
```

Variants:
- All good: title "Copied to Work graph", no failure section, actions Undo / Done.
- Idempotent re-run: "0 new, 0 combined, 20 unchanged" with line "Nothing needed copying." Undo is hidden (nothing to undo); Done only.
- Stopped by the user (S5 Stop): title "Stopped after 1,200 of 4,000" (see S5); actions Continue (re-run, same selection), Undo this copy, Done.
- Deferred item (target page being edited): listed under a "Skipped for now (N)" group with "Retry" so it is not mixed with errors.
- Grant lost mid-run (or another refusal): the affected pages appear under "Failed pages" with the reason ("folder access was revoked"), Retry failed re-runs only them after access is restored; nothing is queued.
- Total failure (nothing written): title "Couldn't copy to Work graph", the reason, actions "Retry", "Choose another destination", "Done".

Rules:
- It is a dialog, not a snackbar, so it persists. A snackbar "Copied 8 pages to Work graph - Undo" also appears when the dialog is dismissed with Done, for the remainder of the standard duration.
- The dialog is dismissible only by its buttons or Esc (Esc = Done). Focus returns to the invoking control.
- Logs: counts written per S15.

## S7. Undo flow and outcome

Trigger: "Undo this copy" in S6 or the snackbar (last run only, 7 days, ADR-003).

```
Undo this copy?
Removes the 3 pages and 12 blocks this copy added to "Work graph".
Anything you edited since stays in place.
                              [Cancel] [Undo copy]
```

Outcomes:
- Success: snackbar "Undid copy: removed 3 pages, 12 blocks."
- Partial: dialog "Undid most of the copy. 1 block was edited since and was left in place." with a link "Show block", and [Done].
- Failure (write error, target unavailable): "Couldn't undo: <reason>." [Retry] [Close]. The manifest is retained so retry is possible.
- Expired or already undone: the "Undo this copy" button is absent; the Settings/result history is not offered (no history list in v1). If a user reaches it via an old snackbar: "This copy can no longer be undone (older than 7 days)." [Close]
- Keyboard: initial focus on Cancel, since the action is destructive.

## S8. Conflict review screen (Gate 1, committed: backs "true conflicts flagged")

```
+--------------------------------------------------+
| <  Review conflicts (2)                          |
|--------------------------------------------------|
| Page: Roadmap                                    |
|  Original:  Draft v1                             |
|  Copied:    Draft v2        from "Personal"      |
|  [Mark resolved]  [Remove this block] [Open page]|
|--------------------------------------------------|
| Page: Notes/Ideas  ...                           |
+--------------------------------------------------+
```

- Loading: 3 skeleton rows and "Loading conflicts..." (polite live region) until the first bounded page returns; failure -> banner with Retry/Close.
- Bounded lazy list; each row shows page name and the neighboring original block (monospace, 200-char ellipsis like `DiskConflictDialog`).
- **"Mark resolved"** (was "Keep both": both blocks were already kept, so the old label described no action) removes the `merge-conflict::` flag only. It is the durable choice: the block keeps its `src-id`, so copying the same source content again adds nothing (ADR-002 rev. 4).
- **"Remove this block"** (renamed from "Remove copy", Repair pass 7: "copy" now always means a whole run, e.g. "Undo this copy") deletes only the flagged block, after a confirmation: "Remove this block? It will come back, flagged again, if you copy this page from "Personal" again. To keep it from coming back, choose Mark resolved instead." [Remove this block] [Mark resolved instead] [Cancel]. The follow-up snackbar (10 s, with Undo) repeats "It may return if you copy this page again." Decision recorded in ADR-002 rev. 4: the model is kept (no tombstone), the UI discloses it.
- "Open page" navigates.
- After a row is handled the counter updates (live region) and the row leaves the list; focus moves to the next row, or to the empty-state text. The empty state: "No conflicts to review." [Close]
- Errors: action write fails -> inline row message "Couldn't update this block: <reason>" with Retry; list load fails -> banner with Retry/Close.
- Entry points: S6 "Review N conflicts". Also reachable later from the page where a `merge-conflict` block appears (the block shows a "Conflict" badge with text, not color-only).
- Accessibility: each row is ONE merged semantics node, "Conflict on page Roadmap: original 'Draft v1', copied 'Draft v2' from Personal", with `customActions` "Mark resolved", "Remove this block", "Open page" (labels include the page name so a screen-reader action list is unambiguous). Buttons stay individually focusable by keyboard (Tab order Mark resolved, Remove copy, Open page; Enter/Space activate). At 200% font scale rows wrap, nothing truncates without the full text in semantics; RTL mirrors the layout and the button order.

## S9. Interrupted-copy notice

Shown on next launch when an `InProgress` manifest exists.

```
+--------------------------------------------------+
| A copy to "Work graph" was interrupted           |
| 1,200 of 4,000 pages were copied.                |
| Resuming is safe: finished pages won't repeat.   |
|                       [Dismiss] [Resume]         |
+--------------------------------------------------+
```

- Resume re-opens S4 (re-plan, cheap because idempotent) then S5/S6. Dismiss keeps the manifest for undo but clears the notice, with a snackbar "You can run the same copy again any time."
- Errors: staging directory swept or source graph removed -> "Can't resume: the staged pages are gone. Start the copy again from Copy pages to..." [Open picker] [Dismiss]. Target gone -> "Work graph is no longer available." [Dismiss].

## S10. Android share overlay: destination row

Replaces the hard-coded "Today's Journal" label in `CaptureActivity` (bottom sheet over 40% scrim).

```
+--------------------------------------------------+
| Saving to  [ Personal graph - Today's Journal v ] |
| +----------------------------------------------+ |
| | shared text (editable, preserved)            | |
| +----------------------------------------------+ |
| (chips tray)                          [Save]     |
+--------------------------------------------------+
```

Tapping the row opens a menu (inline chips if <= 4 graphs):

```
( Work graph )  ( Personal graph  checked )  ( Archive )
```

Flow:
1. Open: destination = resolver result (last-used if remembered, else Settings default, else active graph). The row always shows the actual destination; a fallback is announced, never silent ("Work graph isn't available - saving to Personal graph" shown as a one-line note).
2. Changing the graph keeps the typed text and focus (focus returns to the text field after the menu closes); updates `capture_last_graph_id` only.
3. Save -> in-sheet state "Added to Work graph" (the existing `Saved` state) then auto-finish. TalkBack auto-finish pauses while the menu is open.
4. Zero-tap case: user can Save with no interaction with the row.
5. Back with unsaved text auto-saves to the shown destination (decided: Android Back saves; plan Story 4.2.1 / Task 4.2.1g). After the overlay finishes, a toast names where it went: "Saved to Personal graph" with **Undo** (removes the just-added block) and **Change** (re-opens the overlay with the same text and the destination menu) (plan Task 4.2.1h; Undo/Change are best-effort if the host activity is already gone, and the graph name is always shown; fallback, Repair pass 7: the next app start shows a one-line notice "Last share saved to Personal graph" with Undo and Change while that block is still the last one added, and a Back save routed to the inbox is covered by the persistent queued badge, S13). If that fails or the destination is unwritable, the text is queued in the inbox and the message reads "Couldn't save to Work graph. Queued for Work graph." (see S13). Back with empty text just closes.

States:

| State | Row and body | Actions |
|-------|-------------|---------|
| Destination unavailable (path missing, SAF grant lost, DB closed) | Row turns to error: "Work graph isn't available: folder access was revoked." Text stays visible and editable. | "Save to Personal graph" (last working), "Retry", "Queue for later" (inbox) |
| Save fails | Sheet stays open, snackbar "Save failed - <reason>" | Retry, change graph, Queue for later |
| Queued | Row "Queued for Work graph" with queued icon and text label | "Done"; indicator S13 |
| Duplicate redelivery (`onNewIntent`/rotation) | No double entry; shows "Already added" | Done |
| No graphs configured | Placeholder with message and "Create graph" / "Choose folder"; shared text is shown and kept | Both buttons, Close. DECIDED (ADR-004): on Close the text goes to the unassigned inbox slot with "Saved. It will be added to the first graph you create."; the indicator reads "1 share waiting for a graph"; it is re-keyed once to the first graph created |
| Resolving destination (grant/registry check pending) | Row "Saving to... (checking)" with spinner and text; typed text never blocked | Resolves within 2 s or shows the unavailable state |
| Image share | Same row; image copied to private storage if queued | as above |

A11y: the row is one button node, `contentDescription` "Saving to Personal graph. Double-tap to change", role menu, 48dp. State changes use a polite live region ("Added to Work graph").

## S11. Desktop quick capture chooser

```
+--------------------------------------------------+
| Quick capture                                    |
| [ idea text.....................................] |
| Graph: [ Work graph v]  (Alt+G)                  |
|                         [Esc Cancel] [Enter Save]|
+--------------------------------------------------+
```

- Default = resolver result. Alt+G opens the menu; Tab reaches it; picking a graph does not discard text and returns focus to the text field. Enter saves from the text field (existing); Esc closes.
- Success toast: "Saved to Work graph's journal". Last-used is updated; default unchanged.
- Failure: popup stays open; inline message "Couldn't save to Work graph: <reason>" with [Retry], [Save to <other graph>], [Queue for later].
- Unavailable destination at open: popup opens with the graph row in an error state and the same actions; typed text is not lost.
- Esc with empty or whitespace-only text closes immediately. Esc with any other text asks "Discard this note?" ([Discard] / [Keep editing]); Discard saves nothing, Keep editing returns focus to the text field with text intact. DECIDED (user): desktop asks, Android Back auto-saves (S10). Rationale for the difference: a hotkey popup is dismissed reflexively and a wrong auto-save into a graph is harder to notice than a prompt. Plan Story 4.3.1 / Task 4.3.1e.

## S12. Settings: capture target

```
Capture
  Default capture graph    [ Work graph v ]    "Used when sharing or quick-capturing"
  Remember last used       [toggle]            "Use the graph you picked last time"
```

- Both rows are labeled separately so the difference is clear; helper text under each.
- "Default capture graph" options: each registered graph plus "Whichever graph is open".
- If the saved default no longer exists: row shows "Work graph (removed)" in error text with the text "Falling back to the open graph", and the picker still lists valid options. Changing it clears the state.
- On iOS/Web the section appears with a note "Used by quick add. Sharing from other apps isn't available on this platform yet." ONLY if plan Task 4.5.1e confirms an iOS/Web quick-add entry point exists; otherwise the note reads "Used by sharing and quick capture on Android and Desktop." and nothing is wired on iOS/Web.
- Errors: save of the setting fails -> snackbar "Couldn't save setting." Retry.

## S13. Pending shares indicator and inbox actions

Persistent chip (host DECIDED in plan Task 4.4.1d, revised Repair pass 7: near the graph switcher on desktop; on Android a count-plus-text badge on the graph-switcher entry in the sidebar/drawer, plus Settings > Capture, plus the overlay's queued row, plus a once-per-cold-start notice "2 shares are queued for Work graph" with View) whenever the inbox has items. Both the indicator and the rescue actions below (Copy text, Discard, Retry now) are Gate 1 and COMMITTED, not on the cut list, because without them queued text cannot be rescued when its graph is gone and Gate 1 would dead-end (UX-30):

```
[ 2 shares queued for Work graph ]  ->  opens panel
Panel:  "Work graph needs to be open or reachable to add these."
        - "meeting notes..."  (Today 09:14)   [Discard]
        [Open Work graph]   [Retry now]   [Close]
```

- Items drain automatically when the graph is ready; the chip then shows "2 shares added to Work graph" briefly (polite live region) and disappears. Only SHARES are queued; copies never appear here.
- "Retry now" attempts an off-graph append; failure shows the reason in the panel.
- "Discard" removes one item after a confirm showing its first 80 characters; "Copy text" is available per item so content can be rescued before discard.
- Errors: drain fails after activation -> chip becomes "2 shares couldn't be added" with the panel reason; items are never dropped automatically. A corrupt item is quarantined, never deleted: chip "1 share couldn't be read" with Copy text where recoverable.
- Accessibility: each item is ONE merged node, "Queued share for Work graph: 'meeting notes...', Today 09:14", with `customActions` Copy text / Discard / Retry now (labels include the graph name); the count is a polite live region; after Discard focus moves to the next item; 200% font scale wraps; RTL mirrors; Web: Tab/Enter reach every action with a visible focus ring.

## S14. Android Direct Share shortcuts (optional)

- System chooser lists "Work graph" and "Personal graph". Choosing one opens S10 with that graph pre-set for that share only (does not change last-used or default).
- If the shortcut points to a deleted graph: S10 opens in its unavailable state (graph name shown from the shortcut label, actions Save to fallback / Retry / Queue for later). Shortcuts are republished on registry change; stale ones are removed.

## S15. Merge and share log output (non-interactive)

```
INFO PageMergeService mergeId=m-1f2 direction=push source=g-personal target=g-work new=3 combined=5 unchanged=20 conflicted=2 failed=1 assetsRenamed=2
WARN PageMergeService mergeId=m-1f2 page="Budget 2026" error=FileSystemError(...)
INFO MergeUndo mergeId=m-1f2 target=g-work removedPages=3 removedBlocks=12 leftInPlace=1
INFO JournalAppender target=g-work writer=markdown override=true outcome=Appended
```

Acceptance:
- One summary line per run with all counts and graph ids.
- Each failed page logs its name and error.
- Each share logs target id, writer (`active|markdown|inbox`), and `AppendOutcome`.
- No page bodies or shared text in logs.
- These lines are the LOCAL-ONLY source for the success metrics M1-M5 in requirements.md (no network); their key set is pinned by plan Story 5.1.3.

---

## Cross-cutting flows

**Happy path (copy)**: S1 -> S2 -> S3 -> S4 -> S5 -> S6. Minimum steps: open entry (1), select pages (1+), choose destination (1), Review (1), Copy (1) = 5 taps for one page from the page menu (destination only). UX-01's "<= 4 taps" counts the taps AFTER the page overflow menu is open (menu item, destination, Review, Copy); the 5-tap figure includes opening the menu. Both are consistent; neither has been measured with users.

**Happy path (share)**: share -> S10 -> Save = 1 interaction after the sheet opens (0 extra taps with default).

## End-to-end journeys and failure branches

Legend: [S#] = surface above; "->" happy path; "x" = failure branch with its exit. Every branch ends in a visible message and an exit (UX-30); none ends in silent loss.

| Journey | Happy path | Failure and edge branches (what the user sees, where they land) |
|---|---|---|
| J1 Copy pages (Android/Desktop push) | [S1] entry -> [S2] pick (skeleton -> list) -> [S3] destination (probing -> ready) -> [S4] dry run (checking -> counts) -> [S5] progress -> [S6] result -> Done | x S2 read fails: banner, Retry/Close. x S3 destination disabled (encrypted, grant lost, SAF no-atomic, platform): row disabled with reason, "Re-grant access" or "Open that graph to copy into it"; Review cannot be enabled. x S3 probe times out: "Couldn't check", Retry. x fewer than 2 graphs: "You need a second graph", Add a graph/Close. x S4 plan fails: reason, Retry/Back. x S4 plan stale at commit: "Things changed - review again", new counts, must re-confirm. x S4 target vanished: "no longer available", Choose another destination/Close. x S4 nothing to copy: commit disabled, Back. x S5 Stop: S6 "Stopped after X of Y", Continue/Undo/Done. x S5 page fails: run continues, S6 lists failed with Retry failed. x S5 grant lost mid-run: remaining pages failed with reason, Retry failed after re-grant, never queued. x process dies: next launch [S9] Resume/Dismiss; x S9 staging swept: "Can't resume", Open picker/Dismiss. x user switches graph mid-run: later pages re-route (no error) or fail with Retry. x Esc/Back in S2 with a selection: "Discard selection of N pages?" |
| J2 Review conflicts (Gate 1) | [S6] "Review N conflicts" -> [S8] list (skeleton -> rows) -> Mark resolved / Remove this block / Open page -> empty state | x load fails: banner, Retry/Close. x action write fails: inline reason, Retry. x Remove copy: confirm that it returns on the next copy, then 10 s Undo snackbar. x block edited meanwhile: row shows current content, action still safe (hash-checked) |
| J3 Undo | [S6] or snackbar "Undo this copy" -> [S7] confirm -> success snackbar | x partial: "1 block was edited since and was left in place", Show block. x write error: "Couldn't undo: reason", Retry/Close (manifest kept). x expired/already undone: button absent, or "can no longer be undone", Close |
| J4 Share on Android, default destination | share sheet -> [S10] overlay (resolving -> row shows graph) -> Save -> "Added to Work graph" -> auto-finish | x destination unavailable: row error, "Save to <fallback>", Retry, "Queue for later". x save fails: overlay stays, Retry/change graph/Queue. x queued: "Queued for Work graph", indicator [S13]. x redelivery/rotation: "Already added". x Back with text: auto-save, toast names the graph with Undo/Change; x Back save fails: queued + "Couldn't save to Work graph. Queued for Work graph." x no graphs configured: ADR-004 unassigned slot, "will be added to the first graph you create". x image to inactive graph: copied to target assets/ or queued with private copy; link suggestions hidden with a note |
| J5 Share on Android, override | J4 + open row -> pick graph -> Save | x picked graph unavailable: as J4. x TalkBack: auto-finish paused while menu open. Last-used updated only on success |
| J6 Desktop quick capture | hotkey -> [S11] popup (graph from resolver) -> Enter -> toast "Saved to Work graph's journal" | x unavailable destination at open: error row, Retry/Save to other/Queue. x save fails: popup stays, inline reason. x Esc with text: "Discard this note?" Discard/Keep editing; Esc empty closes |
| J7 Queued shares | indicator [S13] -> graph becomes ready -> drains -> "2 shares added to Work graph" | x drain fails: "2 shares couldn't be added", reason, items kept. x graph deleted: Copy text then Discard (Gate 2). x corrupt item: quarantined, "couldn't be read", Copy text if recoverable |
| J8 Pull copy (iOS/Web, Gate 3) | [S1] "Copy pages from..." -> [S3] source chooser (probing) -> [S2] pick (reading index -> list) -> [S4] -> [S5] -> [S6] | x no readable source: "No graph to copy from" with every reason, Re-select folder, Add a graph, Close. x grant expired: source disabled, Re-select folder, selection kept. x browser lacks folder API: "Can't read <graph> in this browser", no action. x page unreadable: counted `unreadable`, rest continue. x user switches away mid-run: later pages failed, Retry failed reopens the destination. Never queued |
| J9 Settings default | [S12] choose default graph / remember-last toggle | x saved default removed: "(removed)", "Falling back to the open graph". x setting write fails: snackbar, Retry |


## UX acceptance criteria

Task completion
- **UX-01** A user can copy a single page from the page menu to another graph in <= 4 taps after opening the menu (menu item, destination, Review, Copy).
- **UX-02** A user can copy a chosen subset (via search, filter, select) without the whole graph being captured; the counter shows the exact number selected.
- **UX-03** A share with the default destination lands in the chosen graph with 1 tap (Save) after the sheet opens; changing graph adds 2 taps (open row, pick graph).
- **UX-04** No step of the copy flow requires switching the active graph.

Wording and mental model
- **UX-05** Only one entry label "Copy pages to..." / "Copy this page to..." exists; "export pages for merge" and "Merge captured pages" no longer appear.
- **UX-06** The dry-run shows lines "N new - will be created", "N already exist - blocks will be combined (nothing removed)", "N unchanged - nothing to do", and the text "Nothing in the destination is deleted or overwritten. The source graph is not changed."
- **UX-07** The confirm button label contains the count ("Copy 8 pages") and equals new + combined.
- **UX-08** Re-running a completed copy shows 0 new, 0 combined, N unchanged and the message "Nothing to copy - destination already has all selected content" with the commit disabled.

Selection
- **UX-09** Selected pages stay selected when search text or filters change; the counter ("213 results, 12 selected") is announced after typing.
- **UX-10** "Select all N matching" selects exactly the filtered set; selecting the entire graph requires an explicit confirmation naming the page count.
- **UX-11** The current graph is shown in the destination list as disabled with the reason "current graph".
- **UX-12** "Include linked pages" is off by default; turning it on shows a live "adds N pages" delta; N > 200 requires confirmation; the hard cap of 1,000 is stated when hit.

Progress, result, undo
- **UX-13** Progress is determinate (x of y), has a **Stop** button (not "Cancel") reachable by keyboard and TalkBack, and completion is announced.
- **UX-14** Stopping reports "Stopped after X of Y", states in words how many pages were copied and kept and how many were not, and offers Continue and Undo.
- **UX-15** The result is a dialog (not only a snackbar) listing new, combined, unchanged, conflicts, and failed with a per-page failure list and "Retry failed".
- **UX-16** "Undo this copy" is available for 7 days after the last copy, removes only content that copy added, and reports edited-since blocks left in place.
- **UX-17** A stale plan never commits silently: the user sees "Things changed - review again" and the new counts first.
- **UX-18** An interrupted copy is surfaced on next launch with Resume and Dismiss; if resume is impossible the reason and a path to restart are shown.

Conflicts
- **UX-19** Conflicts never block the copy; after commit "Review N conflicts" lists each with page name and the original neighbor, and "Mark resolved", "Remove this block" and "Open page" work per row; Remove this block tells the user the block returns, flagged, if the same page is copied again.
- **UX-20** Conflict blocks carry a text label ("Conflict"), not color-only indication.

Share target
- **UX-21** The share overlay shows the actual destination before save, with the graph name and journal ("Saving to Personal graph - Today's Journal").
- **UX-22** Changing the destination keeps typed text and returns focus to the text field.
- **UX-23** The chosen override updates "last used" only; the Settings default is unchanged.
- **UX-24** If the resolver falls back (destination removed or unavailable), a one-line note names the actual destination; no silent redirect.
- **UX-25** When the destination is unavailable the shared text stays visible and the user is offered "Save to <fallback>", "Retry", and "Queue for later".
- **UX-26** The overlay never dismisses on a failed save; the error and actions remain on screen.
- **UX-27** A repeated delivery of the same share (rotation, `onNewIntent`) does not create a duplicate block.
- **UX-28** Desktop quick capture shows a graph chooser reachable by Alt+G and Tab, and the success toast names the graph.
- **UX-29** Queued shares are visible: a persistent "N shares queued for <graph>" indicator exists until drained; each item can be copied, discarded (with confirm), or retried (item actions are derived/optional). Copies are never queued.

Error states and exits
- **UX-30** Every error state in this document has a visible message that names the cause and at least one exit action (Retry, Back, Close, or an alternative); no dead ends.
- **UX-31** Copy destinations that cannot be written (encrypted, grant lost, platform cannot address) are disabled with a reason, never hidden, never selectable, never queued, and never crash; a grant lost mid-run fails the remaining pages with the reason and Retry.
- **UX-42** Desktop Esc in quick capture with non-whitespace text asks "Discard this note?"; Android Back in the share overlay auto-saves to the shown destination and queues on failure; Esc/Back in the picker with a selection asks "Discard selection of N pages?".
- **UX-32** Failure of Undo, a conflict action, or a setting write shows an inline message and a Retry.
- **UX-33** With no second graph, the copy flow explains it and offers "Add a graph".

Accessibility
- **UX-34** All interactive elements are keyboard navigable with a logical Tab order (search, filters, list, options, destination, actions) and visible focus.
- **UX-35** Page rows expose `toggleable(role=Checkbox)` with merged text "Roadmap, 14 blocks, checked"; selection state is not conveyed by color alone.
- **UX-36** Counters and progress use polite live regions; result and dry-run category lines are text, not color or icon only.
- **UX-37** Dialogs trap focus, move it to the first control (the Cancel button for destructive Undo), and restore it to the invoking control on close.
- **UX-38** Desktop shortcuts: Space toggles, Ctrl/Cmd+A selects all filtered, Esc cancels, Enter acts only when a button is focused, Alt+G opens the capture chooser.
- **UX-39** The share-overlay destination is a single button node with `contentDescription` "Saving to <graph>. Double-tap to change"; TalkBack auto-finish pauses while its menu is open.
- **UX-40** All touch targets are >= 48dp and all text and markers meet 4.5:1 contrast, in light and dark themes; no required animation.

Platform
- **UX-41** The copy flow, Settings capture section, and inbox indicator are present on Android, Desktop, Web, and iOS; platforms without an external share entry point say so in Settings instead of hiding the section. On iOS/Web the copy flow is the pull direction ("Copy pages from..."); push destinations are disabled with their reason (no deferred copy).

Pull-style copy (iOS/Web)
- **UX-43** On iOS/Web, "Copy pages from..." is offered in the sidebar and command palette (and the graph-switcher row overflow unless cut); "Copy pages to..." is not offered. The header states the destination ("Copying into <current graph>").
- **UX-44** In the source chooser, an unreadable source is disabled with its reason (expired folder access, unsupported browser, missing folder, encrypted), never hidden; "Re-select folder" is offered only where the platform can re-grant; with no readable source the screen shows every reason plus "Add a graph" and Close.
- **UX-45** In pull, filters not available without an open graph (tags, properties, backlinks, linked pages, assets) are visibly disabled with "Not available when copying from a graph that isn't open"; name search, Pages/Journals and journal date range work, and "Select all N matching" selects exactly the matching names.
- **UX-46** Every pull result is definite (New / Combined / Unchanged / Failed / Unreadable); nothing says "queued" or "will be copied when you open it"; a read failure or mid-run grant loss shows the reason and "Retry failed"; Undo works on the destination.
- **UX-47** Browsing and selecting in a graph of 8 000+ pages stays responsive (list paged <= 100 rows, no page bodies loaded until Review) and the dry run shows progress per chunk with a working Stop.
- **UX-48** Large text: at 200% font scale no label in the picker, dry-run, result, conflict review, queued-share panel or share overlay is clipped without its full text available in semantics; rows wrap and keep 48dp targets.
- **UX-49** RTL: all screens mirror (leading checkbox, button order, arrow-key semantics follow layout direction); verified with an RTL locale and an RTL page name.
- **UX-50** Web keyboard: the picker, source chooser and conflict list work with Tab/Shift+Tab, arrows (roving focus in the list), Space, Enter, Esc; Ctrl+A is captured only while the list has focus; focus ring always visible; no browser-shortcut collisions.
- **UX-51** Every loading state (picker first page, count, destination probing, dry-run chunks, conflict list, share resolver, pull name index) has text plus a spinner or skeleton, is announced politely, and never blocks typed text or Back.

---

## Gaps found against the plan (design adds, plan lacks)

| Gap | Where the plan is silent | Design answer |
|-----|--------------------------|---------------|
| Undo failure and expired-undo state | Story 2.5.1 only covers success, edited-since, expiry sweep | S7 failure and expired messages |
| Inbox item that never drains (graph deleted) | Story 4.4.1 has drain only | S13 Discard, Copy text, Retry now |
| Interrupted copy cannot resume (staging swept, graph gone) | Story 3.3.1 offers Resume only | S9 "Can't resume" state |
| Unwritable destination in picker for non-platform reasons (grant lost, encrypted) | Now Story 2.3.3 / 3.1.1 (disabled with reason) | S3 reasons and re-grant action |
| Esc/back with a selection or desktop text | Now specified (Repair pass 4): Stories 3.2.1, 4.2.1, 4.3.1 | S2 discard confirm; S10 Back auto-saves; S11 Esc asks (decided) |
| Settings default graph removed | Resolver falls back, UI not described | S12 "(removed)" state |
| Deferred page (target page being edited) in the result | 2.3.2 says "deferred and reported" without UI | S6 "Skipped for now" group |
| Stale/removed target between S3 and S4 | Not specified | S4 table row |
| No second graph | Not specified | S3 empty state |

## Open questions

Decided by the user (2026-10-07), kept for the record: desktop Esc with text asks, Android Back auto-saves; copies to unwritable targets are disabled and never use the inbox; `merge_force_inbox` is cut.

- Queued-shares chip host: DECIDED in plan Task 4.4.1d (Desktop near the graph switcher; Android sidebar badge plus Settings > Capture plus the overlay row plus an app-start notice); revisit after Gate 1 usage.
- Whether iOS/Web has a quick-add entry point at all (plan Task 4.5.1e).
- iOS/Web copy: DECIDED (Repair pass 5), pull-style copy in v1. Still open: whether users accept no assets/linked-page closure/tag filters in pull (untested), and the final wording of the unreadable-source reasons once Spike 0.1.5 shows which cases occur.
- All mental-model wording is untested and no usability session is scheduled in plan or validation; "Copy" for an operation that also combines into existing pages is the main risk (the dry run mitigates it only after the verb is chosen). ADOPTED (Repair pass 7): the sidebar/command-palette entry carries the subtitle "Adds pages; combines with existing ones" (S1, constants in one file so it is Robolectric-testable). Wording validation is scheduled in validation.md ("Wording validation step": owner = the repo owner, trigger = before the Gate 1 release, 3-5 informal user tries or the owner dogfood checklist). Metric M4 (undo rate above 25%) is the post-release UX-review trigger: when the owner's log summary shows it, reopen S1/S4 wording before building Gate 2.
