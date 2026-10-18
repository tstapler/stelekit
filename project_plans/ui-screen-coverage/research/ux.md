# UX Research: UI Screen Coverage

## 1. Copy-pages flow: screen sequence and transitions

The flow is hosted by `CopyFlowHost` (`ui/screens/copy/CopyFlowHost.kt`) and modelled by
`CopyFlowState.stage` (a `CopyStage` enum). The complete screen sequence:

```
Idle
  └─► Picking         (CopyPagesScreen fills the full window; user searches, filters,
  │                    selects pages and a destination graph)
  └─► DryRun dialog   (user reviews what will be copied; "Checking…" progress sub-state)
  └─► Running         (CopyProgressDialog, or BackgroundBanner if the user switched graphs)
  ├─► Finished        (CopyResultDialog: done / retry failed pages / review conflicts / undo)
  │     └─► ConfirmUndo → Undo applied → Finished (same dialog, different result)
  │     └─► Conflicts  (ConflictReviewScreen, rendered by ConflictsOfTarget)
  ├─► RunFailed       (CopyRunFailedDialog: retry / choose another destination / close)
  └─► Interrupted     (InterruptedCopyDialog: resume / dismiss / open picker again)
```

Pull direction adds one extra pre-Picking sub-stage: `PullSourceChooser` is rendered
inside `CopyPagesScreen` before any destination is chosen (guarded by
`state.isPull && state.destinationId == null`).

A "switch-graph confirm" dialog (`switchConfirm`) can appear at any stage and always
sits on top.

## 2. Error / empty states

### CopyPagesScreen
| State | Trigger | User-visible text |
|---|---|---|
| Still-reading banner | `state.stillReading == true` | "Still reading... results so far" |
| Load failure | VM emits load error | retry via `onRetryLoad` |
| No pages indexed yet | `state.rows.isEmpty()` on pull with source loading | shown alongside PullIndexStatus spinner |
| Select-all-in-graph confirm | >N pages match with none already selected | AlertDialog before bulk select |
| Discard-selection prompt | Esc/Back with ≥1 page selected | "Discard selection of N pages?" AlertDialog |

`state.countLine` is a `liveRegion` text that updates as the list filters; tests must
assert its content changes after typing in the search field.

### ConflictReviewScreen
Defined in `ConflictReviewText` (object inside `ConflictReviewScreen.kt`):
| Constant | String |
|---|---|
| `LOADING` | "Loading conflicts..." |
| `EMPTY` | "No conflicts to review." |
| `LOAD_FAILED` | "Could not load conflicts." |
| `remaining(n, hasMore)` | "N+ conflicts to review" / "1 conflict to review" / "N conflicts to review" |
| Undo snackbar | Shows for 10 000 ms (`UNDO_VISIBLE_MS`); action label = "Undo" |
| Row description | "Conflict on page {name}: original '{text}', copied '{text}' from {graph}" |

### CopyFlowHost overlays
- `RunFailed`: shows `state.failure` reason string; buttons: Retry, Choose another destination, Done.
- `Interrupted`: shows a notice string; buttons: Resume, Dismiss, Open picker.
- `ConfirmUndo`: "Removes the pages and blocks this copy added to \"{targetName}\". Anything you edited since stays in place."

### AllPagesScreen
- Empty list when no pages match the current filter (no dedicated empty-state composable visible in the first 120 lines; tests should assert the `LazyColumn` has 0 items).
- `conflictsOnly` toggle hides non-conflicting pages.
- Delete confirmation: "Delete N selected page(s)? This cannot be undone."

### LibraryStatsScreen / ImportScreen
These were not read in detail (too low priority given zero-coverage targets); stub tests
covering the composable entry point are sufficient for the first coverage pass.

## 3. Accessibility — what tests should validate

### CopyPagesScreen
- `semantics { contentDescription = … }` on page rows (at minimum the page title and
  selected state).
- `semantics { heading() }` on section headers.
- `semantics { liveRegion = LiveRegionMode.Polite }` on `state.countLine` text — tests
  should assert this node exists so TalkBack reads filter-result updates automatically.
- `semantics { stateDescription = … }` on checkboxes (checked/unchecked label for
  TalkBack).
- Keyboard: Tab order covers search → filter chips → select-all button → list → link
  options → destination chooser → Review button. Tests need not re-test keyboard input
  but should assert the focusable order makes sense (e.g., Review button is present and
  enabled only when ≥1 page is selected).

### ConflictReviewScreen
- `semantics { contentDescription = … }` on each conflict row (built by
  `ConflictReviewText.rowDescription(row)`).
- `semantics { customActions = … }` on rows (Mark resolved, Remove, Open page actions —
  three custom accessibility actions imported from `CustomAccessibilityAction`).
- `semantics { heading() }` on the "Review conflicts" title.
- `semantics { liveRegion = LiveRegionMode.Polite }` on the remaining-count text.

### GitSetupStep3Auth
- Each auth-type option uses `selectable(…, role = Role.RadioButton)` — tests should
  assert the RadioButton semantics role is present so accessibility services announce it
  correctly.
- Token visibility toggle: `Icons.filled.Visibility` / `VisibilityOff` — the content
  description must change when toggled.
- Back/Next buttons: Next must be reachable and tappable regardless of selected auth type.

## 4. AllPagesScreen — user expectations for search and navigation

User types in the `OutlinedTextField` labelled "Filter by name" →
`viewModel.onFilterChange(query)` updates `filterQuery` StateFlow → `displayedPages` list
re-renders. Tests should assert:
- Typing a prefix filters the list to matching pages only.
- Clearing the field restores the full list.
- Page type chips (All / Journals / Pages) work independently of the text filter and
  combine with it.
- `showConflictsOnly` toggle (driven by `conflictsOnly` param and local state) further
  constrains the displayed list.
- Clicking a row calls `onPageClick(page)`.
- Long-clicking a row enters selection mode (`isInSelectionMode`).
- Clicking a column header changes `sortColumn` and `sortAscending`.

## 5. GitSetupStep3Auth — auth methods and validation states

Four auth methods (enum `GitAuthType`):
| Method | Shown section | Validation state to cover |
|---|---|---|
| `NONE` | (nothing extra) | Next enabled immediately |
| `SSH_KEY` | `SshKeySection`: path field + optional browse button + passphrase field | Next enabled when `sshKeyPath` is non-empty |
| `HTTPS_TOKEN` | `HttpsTokenSection`: token field + visibility toggle + saved-connection list | Next enabled when `httpsToken` is non-empty OR a saved connection is selected |
| `GITHUB_OAUTH` | `OAuthSection`: existing connections list + "Connect via browser" button (gated by `deviceFlowEnabled`) | Next enabled when `oauthConnectedAs != null` or a connection is selected |

Key validation states:
- `tokenVisible` toggle: field shows `PasswordVisualTransformation` (hidden) or plain text.
- `deviceFlowEnabled = false`: OAuth "Connect" button is disabled — test this renders but is disabled.
- `oauthConnectedAs != null`: section shows the connected account name.
- `showOAuthDialog = true`: an in-progress OAuth device flow dialog is displayed.

## 6. User-visible error messages to assert in tests

| Screen | Message / text | How triggered |
|---|---|---|
| ConflictReviewScreen | "Loading conflicts..." | ViewModel is in loading state |
| ConflictReviewScreen | "No conflicts to review." | Zero rows in loaded state |
| ConflictReviewScreen | "Could not load conflicts." | Load error state |
| ConflictReviewScreen | "Undo" snackbar (10 s) | `state.undo != null` |
| CopyFlowHost | "Undo this copy?" dialog text | `CopyStage.ConfirmUndo` |
| CopyFlowHost | "Removes the pages and blocks…" body | `CopyStage.ConfirmUndo` |
| CopyPagesScreen | "Still reading... results so far" | `state.stillReading` |
| CopyPagesScreen | "Discard selection of N pages?" | Esc/Back with selection |
| AllPagesScreen | "Delete N selected page(s)? This cannot be undone." | Bulk delete confirm |
| AllPagesScreen | "Delete" / "Cancel" in delete dialog | — |
