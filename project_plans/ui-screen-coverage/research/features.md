# UI Screen Coverage — Feature & State Research

## Test infrastructure already in place

All existing UI screen tests use **Robolectric** (`@RunWith(RobolectricTestRunner::class)`) with
`createComposeRule()` in `androidUnitTest`. Tests exercise **stateless content composables**
directly (e.g., `CopyPagesContent`, `ConflictReviewContent`) rather than the stateful screen
hosts, making them headless and display-free. Key reusable fixtures live in:

- `kmp/src/jvmTest/…/ui/fixtures/FakeRepositories.kt` — `InMemorySettings`, `FakeFileSystem`,
  full in-memory `PageRepository` / `BlockRepository` implementations
- `kmp/src/androidUnitTest/…/platform/testsupport/FakeExternalStorageProvider.kt`

There are **no existing tests** for `AllPagesScreen`, `LibraryStatsScreen`, `ImportScreen`
(partial), or `GitSetupStep3Auth`.

---

## CopyPagesScreen.kt (447 lines, 0% uncovered)

### Public API
- Stateless entry point: `CopyPagesContent(state: CopyPagesState, actions: CopyPagesActions, modifier)`.
- `CopyPagesActions` — 22 lambdas (search, filters, toggles, selection, destination, review, etc.).
- `PageSelectionRow(row: PageRowState, checked: Boolean, onToggle)` — individually testable.

### Key states (`CopyPagesState`)
| Field | What to test |
|---|---|
| `listLoad = Initial` | Shows "Loading pages..." skeleton, "Counting..." count line |
| `listLoad = Updating` | Keeps existing rows (dimmed), "Updating..." in count line |
| `listLoad = Failed` | Shows error text + Retry button, Retry fires `onRetryLoad` |
| `listLoad = AppendingMore` | Footer spinner visible while appending |
| `rows = emptyList()` (Idle) | Empty state message ("No pages match your filters") |
| `picked` non-empty | Count line says "N selected"; Review button enabled |
| `picked` empty | Review button disabled; `reviewHelper` = "Select at least one page" |
| `destinationId` null | Review button disabled; helper = "Choose a destination" |
| `DestinationStatus.Checking` | Review helper = "Checking <graph>..." |
| `DestinationStatus.Available` | Destination row selectable, Review enabled |
| `DestinationStatus.Disabled(kind, text, action)` | Row shows disabled text; optional action button |
| `DestinationStatus.CouldntCheck` | Shows error + Retry probe button |
| `confirmAllPages != null` | "Select all N pages?" confirmation dialog shown |
| `discardPrompt = true` | Discard confirmation dialog shown; Keep / Discard callbacks fire |
| `gate2LinkedPages = true` | "Include linked pages" checkbox visible |
| `noOtherGraph = true` | "No other graphs" empty-destinations message |
| `review = ReviewState.Planning` | Review button disabled (in-progress) |
| Pull direction (`isPull`) | Source chooser / index state shown instead of destination list |
| `stillReading = true` | Streaming indicator visible; Stop reading fires `onStopReading` |

### Edge cases
- Multi-selection: toggle on/off, Select All (matching vs. whole graph), clear selection
- Keyboard: `Ctrl/Cmd+A` selects all matching; `Escape` fires `onRequestClose`
- RTL layout (LayoutDirection.Rtl) — back arrow mirrored
- `totalMatching = null` → "Counting..."
- `hasMore = true` → "Load more" footer
- Filter chips (Pages / Journals) toggle and AND together
- Namespace/tag filter fields show in dropdown and clear via "Clear filters"

---

## ConflictReviewScreen.kt (187 lines, partially covered via ConflictReviewTest)

### Public API
- `ConflictReviewScreen(viewModel, onOpenPage, onClose)` — stateful host (snackbar, Undo timer).
- `ConflictReviewContent(state: ConflictReviewState, …callbacks…)` — stateless, directly testable.

### Key states (`ConflictReviewState`)
| State | What to test |
|---|---|
| `loading = true` | "Loading conflicts..." text shown |
| `rows = emptyList()` | "No conflicts to review." empty state |
| `loadFailed = true` | "Could not load conflicts." + Retry |
| `rows` populated | Each row shows page name, original, copied; Mark resolved + Remove actions present |
| `confirmRemove != null` | Remove confirmation dialog shown; Confirm fires `onConfirmRemove`, Cancel fires `onCancelRemove` |
| `actionError != null` | Error dialog shown; Retry fires `onRetryAction`, Dismiss fires `onDismissActionError` |
| `undo != null` | Undo snackbar shown for 10 s; action fires `undoRemove` |
| `hasMore = true` | "Show more" button present; fires `onLoadMore` |
| `remaining(count, hasMore)` | Header count string accurate |
| Keyboard: Escape | fires `onClose` when `confirmRemove == null` |
| RTL | row action buttons accessible from both directions |

### Accessibility
- Each row has a `contentDescription` from `ConflictReviewText.rowDescription(row)`.
- Row supports custom accessibility actions: "Mark resolved", "Remove this block", "Open page".

---

## AllPagesScreen.kt (251 lines, 0% uncovered)

### Public API
`AllPagesScreen(viewModel: AllPagesViewModel, onPageClick, onBulkDelete, conflictFilePaths, conflictsOnly)`

### Key ViewModel states
| ViewModel state | What to test |
|---|---|
| `isLoading = true` | Loading indicator shown |
| `pages = emptyList()` | Empty list (no crash) |
| `pages` populated | Rows appear; backlink count and timestamps shown |
| `filterQuery` non-blank | Visible rows filtered; filter field shows current text |
| `pageTypeFilter = ALL/JOURNALS/PAGES` | Correct FilterChip selected; list filtered accordingly |
| `sortColumn` / `sortAscending` | Column header shows arrow; click toggles direction |
| `isInSelectionMode = true` | Selection toolbar visible; Delete button shows count |
| `selectedUuids` populated | Count shown; clicking Delete opens confirmation dialog |
| Bulk delete confirmed | `onBulkDelete` called with selected UUIDs |
| `conflictsOnly = true` | Only pages with paths in `conflictFilePaths` shown |
| Long press a row | Enters selection mode |

### Edge cases
- Empty filter query → all pages shown
- Filter clears → previous rows restored
- Bulk delete cancelled → selection preserved

---

## LibraryStatsScreen.kt (192 lines, 0% uncovered)

### Public API
`LibraryStatsScreen(viewModel: LibraryStatsViewModel, modifier)`

### Key states (`LibraryStatsState`)
| State | What to test |
|---|---|
| `Idle` | "Tap refresh to scan your library." message; Refresh button present |
| `Loading` | CircularProgressIndicator shown (both in header and body), "Scanning library…" text |
| `Error(message)` | Error message shown in error color |
| `Loaded(report)` | Stats cards rendered; page/journal counts match report data |
| Refresh button click | `viewModel.load()` called; state transitions to `Loading` |
| `LaunchedEffect(Unit)` auto-load | load() called on first composition only when state is `Idle` |

### Stats content (StatsContent composable)
- Volume card: pageCount, journalCount, totalBlocks, avgBlocksPerPage, pagesWithNoContent, totalHashtags
- Date span: `firstJournalDate → lastJournalDate`; "(no dated journals)" when null
- Namespace stat rows if present

---

## ImportScreen.kt (30% covered, 2-stage flow)

### Public API
`ImportScreen(viewModel, onDismiss, onHtmlPaste?, qrTransferSettings?, onImportViaCamera?)`

### Stages
- **Stage.INPUT** → `InputStage(...)`: Paste / URL tab row, text field, "Next" button, optional HTML paste and camera import
- **Stage.REVIEW** → `ReviewStage(...)`: topic suggestions, confirm/back

### Key states (`ImportState`)
| State / condition | What to test |
|---|---|
| `activeTab = PASTE` | Paste tab selected, text input shown |
| `activeTab = URL` | URL tab selected, URL input shown |
| Empty input | Next / Import button disabled |
| `onHtmlPaste = null` | "Paste with formatting" button absent |
| `qrTransferSettings != null` | "More import options" overflow menu shown |
| `savedPageName != null && !showUndoSnackbar` | Screen auto-dismisses |
| Undo snackbar visible | dismisses after action or auto-expires |
| Review stage: suggestions present | Suggestion chips shown; accept/dismiss callbacks fire |
| Back from review | Returns to input stage |

---

## GitSetupStep3Auth.kt (16% covered)

### Public API
`Step3Auth(authType, onAuthTypeChange, sshKeyPath, …, onBack, onNext, httpsConnections, oauthConnections, …)`

Auth type radio buttons: `NONE`, `SSH_KEY`, `HTTPS_TOKEN`, `GITHUB_OAUTH`

### Key states / branches
| Condition | What to test |
|---|---|
| `authType = NONE` | No credential fields shown; Next enabled |
| `authType = SSH_KEY` | SSH key path field shown; passphrase field shown; Browse button if `onBrowseSshKey != null` |
| `authType = HTTPS_TOKEN` | Token field shown; toggle visibility button |
| `authType = GITHUB_OAUTH` | OAuth section shown; "Connect with GitHub" button; `deviceFlowEnabled = false` → button disabled |
| `httpsConnections` populated | Saved connections list shown; selecting one sets `selectedHttpsConnectionId` |
| `oauthConnections` populated | Saved OAuth connections shown |
| `oauthConnectedAs != null` | Shows connected account name |
| `tokenVisible` toggle | Shows/hides token text |
| `showOAuthDialog = true` | OAuth dialog shown |
| Selecting a different auth type | Previous type's fields collapse |
| Back / Next | callbacks fire |

---

## ConflictResolutionScreen.kt (327 lines, partially covered in ConflictResolutionScreenTest)

### Public API
`ConflictResolutionScreen(conflicts, onResolve, onDismiss, onAbortMerge?)`

### Key states / branches already tested
- Finish is disabled until all blocks resolved
- "Keep both" resolves with both sides in order
- Manual text edit override

### Remaining gaps to cover
| Condition | What to test |
|---|---|
| `conflicts = emptyList()` | Empty state or immediate dismiss |
| Multiple files | Each file shows in its own section; selecting one doesn't affect others |
| Whole-file mode (no hunks) | Only "Keep mine" / "Use remote" shown; no "Resolve line by line" |
| Binary / unparseable file | Only whole-file choices offered |
| `onAbortMerge != null` | Abort button shown; confirmation dialog gating it |
| `resolving = true` | Finish Merge button disabled while coroutine runs |
| `onResolve` returns `Left(error)` | Error string shown |
| Hunk manual text edit | Overrides checkboxes; final resolved content matches edited text |

---

## CopyFlowHost.kt (167 lines, ~1% covered)

### Public API
`CopyFlowHost(controller, binding, conflictReviewFactory, onOpenPage, onNotice, modifier)`

### Key states / composable behaviour
| Condition | What to test |
|---|---|
| `controller.state` = `Idle` | Nothing shown (or empty surface) |
| Progress dialog | `CopyProgressDialog` shown while copy running |
| Result dialog | `CopyResultDialog` shown on completion |
| Dry-run dialog | `DryRunDialog` shown when `ReviewState.Ready` |
| Interrupted dialog | `InterruptedCopyDialog` shown when interrupted session found |
| Conflict review | `ConflictReviewScreen` shown when `conflictReviewFactory` returns non-null |
| Notice flow | `onNotice` callback fires with toast message |
| `DisposableEffect` | `attachGraph` called on composition; `detachGraph` on disposal |

---

## Recommended test placement

All new tests go in `kmp/src/androidUnitTest/…` using `@RunWith(RobolectricTestRunner::class)` and `createComposeRule()` — consistent with `CopyPagesScreenTest` and `ConflictReviewTest`. Target the stateless `*Content` composables where they exist; for screens without a split, drive the top-level composable with a fake ViewModel backed by `MutableStateFlow`.

For `AllPagesScreen` and `LibraryStatsScreen`, create minimal fake ViewModels backed by `MutableStateFlow` fields (same pattern as existing `InMemorySettings` / `FakeFileSystem` in `FakeRepositories.kt`). No existing fake ViewModel classes cover these screens yet.
