# UX Research: cross-graph merge and share target

Confidence labels: VERIFIED = opened in this repo. PRIOR-ART = recalled from general knowledge of the named products/specs; no web lookup was done in this pass, so re-check before quoting externally.

## 1. Existing SteleKit UI to match (VERIFIED)

- Merge entry points today: `GraphContentLeftSidebar.kt:87-88` wires `onExportPagesForMerge` / `onImportMergedPages`. `exportPagesForMerge` (line 185) snapshots the whole graph and only reports via snackbar: "Captured N pages - switch to the target graph, then tap 'Merge captured pages'". `importMergedPages` (line 195) reports only a one-line snackbar ("Merged N pages, skipped M already here, K failed"). No selection, no preview, no per-page detail, no undo. The state lives in a service (`pendingPageCount`), so the user must remember a hidden mode across a graph switch.
- Share overlay: `androidApp/.../CaptureActivity.kt` is a bottom-anchored `Surface` sheet over a 40% scrim (lines 382-414), with a hard-coded "Today's Journal" label (line 429) where the destination graph chooser belongs. It already has: `focusGroup` plus TalkBack-aware auto-finish timer (lines 321-357), `liveRegion = Polite` on the chip tray, `customAccessibilityAction` for dismiss, 48dp targets via `minimumInteractiveComponentSize()`, a `Saved` confirmation state, and save-failure snackbar ("Save failed - ..."). Back with unsaved text auto-saves (line 371). If no active repo set it shows `NoGraphPlaceholderContent` (line 124-127) - this is the existing "target unavailable" state and loses the shared text visually (text stays in the ViewModel but the user cannot see or rescue it).
- Dialog idioms: plain Material3 `AlertDialog` (`DiskConflictDialog.kt`: explicit choice, `onDismissRequest` is a no-op, monospace 200-char previews with ellipsis, scrollable body; `StorageMoveConfirmDialog.kt`: reassurance text with a matching `contentDescription`). Keyboard idioms exist in `SearchDialog.kt` (`onKeyEvent` handling `Key.Enter` at line 125 and `Key.Escape` at line 157). Selection idioms exist in `AllPagesScreen.kt` (`FilterChip` at lines 109/124, `Checkbox` at 140/316) - reuse these for the picker rather than inventing new components.
- Other relevant surfaces: `UnifiedLocationPicker.kt`, `StorageMoveProgressDialog.kt` (progress pattern for long operations), `SectionPickerDialog.kt`, `NotificationDisplay.kt`.

## 2. Comparable patterns (PRIOR-ART)

**Destination pickers in share flows**
- Android Sharesheet / Direct Share: ranks destinations by recency and frequency, shows a short row first, "more" beyond that. Takeaway: default to last-used graph, show at most ~3-4 graph chips inline, fall back to a full list.
- Bear/Notion/Obsidian clipper style overlays: destination is a single tappable line at the top of the capture sheet ("Saving to: Work graph v"), not a separate screen. Changing it does not discard typed text. Fits `CaptureActivity` exactly: replace the static "Today's Journal" label with a destination row.
- Gmail/Drive "Move to": a modal list with current location marked and a search field when the list is long. Graph counts are small (typically 2-5), so no search is needed for graphs; it IS needed for pages.

**Multi-select pickers with search and filters**
- Gmail/Files: long-press or checkbox enters selection mode, top bar becomes "N selected" with select-all and a contextual primary action. Filters as chips above the list; search narrows the list while preserving selections made earlier (selection must be keyed by page id, not list position).
- Material guidance: filter chips for scoped facets (journals vs pages, date range, namespace, tag); "Select all N results" should act on the filtered set and say so ("Select all 213 matching"), with a separate escape hatch for "all pages" to avoid accidental huge selections.
- Logseq's own All Pages / Obsidian "Merge" plugins: flat list, sort by name/date, tri-state header checkbox.

**Dry-run / preview-before-commit**
- git `--dry-run`, rsync `-n`, Terraform plan, macOS Migration Assistant, Dropbox/Drive conflict summaries: a categorized count summary, then expandable per-item detail, then a verb button that restates the counts ("Merge 12 pages"). Requirements already name the categories (new / merged / unchanged); add "conflicts" and "failed to read" as first-class lines because the Out-of-scope and Open Questions both admit conflicts exist.
- Good pattern: summary is the default, detail is one tap away and virtualized (lazy list), because 8k-page graphs make a full list heavy.

**Merge-conflict UX**
- Git GUIs/VS Code: three-way view; too heavy for a notes outliner. Notion/Google Docs version history: non-destructive, both versions kept. Syncthing: keeps `.sync-conflict` copies, user never loses data but gets clutter. Dropbox "conflicted copy" is widely disliked for the clutter.
- Fit for SteleKit given the additive-only rule: keep both sides as sibling blocks with a visible, filterable marker (e.g. a `merge-conflict:: true` property) and offer "Review N conflicts" after commit, not per-conflict prompts before commit. Per-page prompts were already rejected in the requirements.

## 3. Mental models: "copy" vs "merge" (PRIOR-ART plus inference, UNVERIFIED with users)

- Most users hold a file-manager model: "copy" = make a duplicate at the destination, same-name collision = a prompt (Replace / Skip / Keep both). Nobody expects a "union of contents" from the word copy. Notes users who say "merge" usually mean "combine two same-named pages without losing anything".
- Today's UI says "export pages for merge" / "Merge captured pages", but behavior is skip-existing, which silently matches neither mental model (INFERRED from the code at `GraphContentLeftSidebar.kt:195-203`: skipped pages are only a count).
- Recommendation: single flow with one verb, **"Copy pages to..."**, and let the dry-run summary teach the semantics line by line: "3 new - will be created", "5 already exist - blocks will be combined (nothing removed)", "20 identical - nothing to do". Use "Combine" in the per-line wording rather than "merge" to avoid git connotations of overwrite. Add one reassurance line modelled on `StorageMoveConfirmDialog`: "Nothing in the destination is deleted or overwritten. The source graph is not changed."
- Direction: "Copy to..." (push from the current graph) matches the share-target model and removes the hidden two-step state across a graph switch. Keep a "pull from..." alternative only if the open question about multi-open graphs resolves against push.

## 4. Accessibility

Targets: WCAG 2.2 AA; Android TalkBack; desktop keyboard-only. (WCAG numbers are PRIOR-ART.)

- Targets and focus: 48dp minimum (2.5.8 minimum is 24px, but the repo already standardizes on 48dp - match it). Visible focus indicators on desktop (2.4.7, 2.4.11). Dialog moves focus to the first control, traps it, and restores it to the invoking control on close (2.4.3); Compose `Dialog`/`AlertDialog` do most of this, a custom bottom sheet picker needs `focusGroup` like `CaptureActivity`.
- Selection semantics: rows use `toggleable(role = Role.Checkbox)` or `selectable` with merged descendants, so TalkBack reads "Page name, journal, 14 blocks, checked". Do not rely on checkbox color or a trailing icon alone (1.4.1). A selection counter and result count must be a `liveRegion = Polite` (pattern already used in `CaptureActivity` line 463) so "213 results, 12 selected" is announced after typing in search.
- Dry-run summary: each category line needs text, not just color or icon; announce completion with a live region; the Merge button label must include the count ("Copy 12 pages"), which doubles as screen-reader confirmation.
- Progress for long operations: determinate progress with `progressBarRangeInfo`, a Cancel action reachable by keyboard and TalkBack, and a completion announcement. Reuse the shape of `StorageMoveProgressDialog`.
- Destination selector in the share overlay: expose as a single button node with `contentDescription` "Saving to Work graph. Double-tap to change" and a menu role; ensure changing it does not move focus away from the text field unexpectedly (2.4.3, 3.2.2). The existing TalkBack-pauses-auto-finish logic must also cover this control, otherwise the sheet could close while the user is choosing.
- Desktop keyboard: Tab order search -> filters -> list -> actions; arrow keys move within the list, Space toggles, Ctrl/Cmd+A selects all filtered, Enter on the primary action only when focus is on the button (avoid Enter-submits-while-searching accidents), Esc cancels (pattern in `SearchDialog.kt`). For desktop quick capture, the graph choice should be reachable with a mnemonic (e.g. Alt+G) and also by Tab.
- Reduced motion and contrast: no required animation; conflict markers meet 4.5:1 contrast and are not color-only.

## 5. Error states and edge cases needing graceful UX

| Case | Recommended behavior |
|---|---|
| Target graph unavailable at share time (folder permission revoked, SAF grant lost, DB not open, deleted folder, removable storage absent) | Never lose shared text. Show destination row in an error state with reason and two actions: "Save to <fallback/last-working graph>" and "Retry". Existing `NoGraphPlaceholderContent` path currently hides the text - replace for the overlay with a message plus preserved text. If a queued-inbox design is chosen, say "Will be added when <graph> next opens" and show the pending count somewhere persistent. |
| Last-used graph no longer exists | Fall back to the Settings default, then the active graph; show the actual destination before save (never silently redirect). |
| Share arrives while no graph is configured | Existing placeholder; add "Create graph" or "Choose folder" action rather than a dead end. |
| Partial failure in a multi-page copy | Result dialog (not just a snackbar), because snackbars vanish and the current one gives only counts: categories imported / combined / unchanged / conflicted / failed, with expandable failed list and a "Copy failure list" or "Retry failed" action. The source is never altered, so retry is safe if the merge is idempotent (a stated success metric). |
| Conflicts (same UUID, different content) | Keep both (additive-only), mark, summarize count in the result, offer "Review conflicts" jump list. Do not block the commit. |
| Huge selection (up to ~8,000 pages) | Show the dry-run before commit as counts only; time estimate or determinate progress; chunked work; cancel leaves already-committed chunks in place and says so ("Stopped after 1,200 of 4,000 - these are already copied; re-run to continue" - relies on idempotency). Warn when selection includes linked-page closure that expands the set ("Including linked pages adds 1,340 pages") and show the delta live; cap or require confirmation for transitive closure. |
| Dry-run becomes stale (target changed between preview and commit, e.g. file watcher or sync) | Recompute counts at commit; if they differ materially, show "Things changed - review again" instead of committing a different set than previewed. |
| Name collisions in assets | Show in the summary ("2 assets renamed"); never overwrite silently. |
| App backgrounded or killed mid-copy (Android) | Needs foreground service or resumable job; at minimum record a "last copy was interrupted" marker and surface it on next launch. Idempotent re-run is the recovery story. |
| Undo | Snackbar "Undo" is cheap only if the merge is journaled. Options for planning: (a) per-run manifest of created page ids and added block ids, enabling "Undo this copy" for a limited window (it removes only things this run added, so it is safe by construction); (b) pre-merge snapshot of the target graph (heavy for 8k pages). Recommend (a) and surface it in the result dialog and snackbar. For share capture, undo = delete the just-added journal block (mirrors existing save flow). |
| Source and target are the same graph | Disable that destination in the picker with a reason ("current graph"). |
| Empty selection or nothing to do | Disable commit; if everything is "unchanged", say "Nothing to copy - destination already has all selected content". |
| Silent loss risk on share | Per requirements Observability: a visible failure state; do not dismiss the overlay on failure (the current code keeps the sheet open and snackbars the error - keep that). |

## 6. Jobs to be done

1. When I am in the wrong graph while reading or capturing something, I want to send it to the right graph without switching, so I do not break my flow. (Share target; per-share override with remembered last-used.)
2. When I split or recover a graph, I want to bring over specific pages (a namespace, a date range, a tag) and be sure nothing is lost or duplicated, so I can consolidate confidently. (Selection filters, union merge, idempotency.)
3. When I move a project from personal to work (or back), I want to see exactly what will happen before it happens, so I trust the tool with my notes. (Dry-run.)
4. When something goes wrong halfway, I want to know what succeeded and be able to retry or undo, so I am never unsure of my data's state. (Result dialog, undo, idempotent retry.)
5. When I share to a default destination repeatedly, I want zero extra taps, so capture stays fast. (Default graph setting; overlay shows destination but needs no interaction.)

## 7. Recommendations (UX-level)

1. Replace the hidden two-step "export then switch then merge" with one "Copy pages to..." flow: pick pages (search, filter chips, select-all-filtered) -> pick destination -> dry-run summary (new / combined / unchanged / conflicts) -> confirm -> progress -> result dialog with retry and undo.
2. Reuse existing components: `AlertDialog`/`Dialog`, `FilterChip`, `Checkbox`, `StorageMoveProgressDialog` pattern, `SearchDialog` key handling, snackbar plus persistent result summary.
3. Share overlay: a destination row replacing the static "Today's Journal" label, default = Settings graph, override remembered, unavailable-graph state preserving text, TalkBack timer pause extended to the new control.
4. Make idempotency visible: re-running a copy should show "0 new, N unchanged", which doubles as user-facing proof of the success metric.
5. Pick the cross-graph write design with these UX constraints in mind: the user must see a definite outcome ("added to Work graph") rather than "queued" unless the inbox design is chosen, in which case the pending state needs a persistent, accessible indicator.

## 8. Open UX questions for the plan

- Conflict marker representation (property vs sibling-with-prefix) and where "Review conflicts" lives.
- Whether undo is scoped to the last run only, or a history list.
- Whether desktop quick capture gets an in-window destination chip or a global shortcut, and how Web/iOS (no share entry point) expose the destination choice.
- Whether "include linked pages" defaults off (recommended) and what cap triggers a confirmation.
- No user testing was done; the mental-model claims in section 3 are inferences and should be validated with a quick prototype or 3-5 user sessions.
