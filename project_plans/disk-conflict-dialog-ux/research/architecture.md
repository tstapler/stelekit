# Architecture Research: disk-conflict-dialog-ux

Grounded entirely in the current code on branch `stelekit-editing`. File paths below are
absolute-relative to repo root.

## 1. End-to-end trace: watcher → dialog

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphFileWatcher.kt`
  - Owns `_externalFileChanges: MutableSharedFlow<ExternalFileChange>` (`externalFileChanges`
    is the public `SharedFlow`), fed by a 5s poll loop and a platform-native fast path.
  - `checkDirectoryForChanges()` emits `ExternalFileChange(filePath, content, suppress)` where
    `content` is the **raw, unparsed file text** read straight off disk (`changed.content` from
    `FileRegistry.detectChanges`, or `readFile()` for `.md.stek` encrypted files). No parsing
    happens before this event is emitted.
  - Subscribers can call `event.suppress()` within a 200ms window to prevent the watcher's own
    auto-reimport (`onReloadFile`).
- `GraphLoader` re-exports this same flow (`GraphLoaderPort.externalFileChanges`) — it adds no
  transformation, so `event.content` is still raw file text by the time it reaches the ViewModel.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`
  - `observeExternalFileChanges()` (line ~1370) is the single consumer. It branches on whether
    the changed file is the page currently open:
    - **Not open** → stores a `PendingConflict(filePath, pageName, diskContent)` in
      `AppState.pendingConflicts` (keyed by `filePath`) and fires a one-shot snackbar
      (`sendSnackbar`). `event.suppress()` is called so the DB keeps the user's edits.
    - **Open** → applies a four-tier "is the user actually mid-edit" guard (actively editing /
      dirty blocks / pending disk write / actor-pending-writes). If none apply, the watcher's
      normal reimport proceeds unshown. If any apply, it suppresses the reimport and builds
      `AppState.diskConflict: DiskConflict?`, which is what `DiskConflictDialog` renders.
  - `checkAndShowPendingConflict(screen)` (line ~1467, called from `navigateTo`, `goBack`,
    `goForward`) is how a `PendingConflict` becomes a shown `DiskConflict`: on navigating to a
    page with a pending conflict, it **removes the entry from `pendingConflicts` immediately**
    (before the user has resolved anything) and constructs a `DiskConflict` using the *first
    block by position* on the page (`localContent = firstBlock?.content`), not necessarily the
    block the user was editing when the conflict occurred.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/GraphDialogLayer.kt` (line 274) renders
  `DiskConflictDialog` whenever `appState.diskConflict != null`, wiring the four resolution
  callbacks to `viewModel.keepLocalChanges() / acceptDiskVersion() / saveAsNewBlock() /
  manualResolve()`.
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/DiskConflictDialog.kt` is a
  Material3 `AlertDialog` with `onDismissRequest = {}` (no-op — **the dialog is effectively
  modal**; there is no swipe/back/tap-outside escape). It always resolves through one of the
  four buttons.

## 2. Truncation and preview-granularity mismatch (gap #1 and #2)

`DiskConflictDialog.kt` lines 45–77:
```kotlin
conflict.localContent.take(200)…   // "Your edit"
conflict.diskContent.take(200)…    // "Disk version"
```
Both previews use the identical 200-char `.take()` + `…` pattern — the truncation escape hatch
(gap #2) is a single, easy fix point common to both. But the **content sources differ in scope**:

- `conflict.localContent` — `StelekitViewModel.kt` line ~1440: read from
  `BlockStateManager`'s in-memory optimistic state (falling back to `blockRepository`), scoped
  to **exactly one block** (`conflict.editingBlockUuid`).
- `conflict.diskContent` — always `event.content`, the **entire raw markdown file**, e.g. every
  bullet/heading on the page concatenated with newlines. It is never parsed into blocks before
  reaching the dialog.

This confirms requirement gap #1 precisely: "Your edit" is one outline node; "Disk version" is
the whole file. `manualResolve()` (`StelekitViewModel.kt` lines 1574–1602) already has a crude,
undocumented workaround for this mismatch — it picks a single line via
`conflict.diskContent.lines().firstOrNull { it.startsWith("- ") } ?: conflict.diskContent.take(200)`
to approximate "the corresponding disk block," which is a heuristic, not a real match.

**Is disk content already parsed anywhere reachable?** No — `event.content`/`diskContent` is
raw text. The existing parse path is `MarkdownParser.parsePage(content): ParsedPage` (used at
`GraphLoader.kt` lines 1347 and 1708 inside `parseAndSavePage`), which the ViewModel already
calls indirectly via `graphLoader.parseAndSavePage(...)` in `acceptDiskVersion()` /
`saveAsNewBlock()`. So narrowing "Disk version" to block scope is achievable **with existing
infrastructure** — call `MarkdownParser.parsePage(conflict.diskContent)` to get
`ParsedPage.blocks: List<ParsedBlock>` — but with an important caveat:

**`ParsedBlock` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/ParsedModels.kt`) has
no UUID field.** Markdown files carry no stable per-block ID by default. `GraphLoader`'s own
reconciliation of parsed blocks against existing DB blocks (`parseAndSavePage`, lines
1733–1748) uses **position + a content-hash sidecar** (`sidecarManager.read(pageSlug)`) for
"content-hash → UUID recovery (e.g. after a git pull that reordered blocks)" — i.e. even the
production reconciliation path treats this as a best-effort match, not an exact one. Any
dialog-side "find the disk block matching `conflict.editingBlockUuid`" logic will inherit the
same fundamental limitation and should reuse the *same* heuristic (position index, or
content-hash-via-sidecar if available) rather than inventing a third one. A defensible minimal
approach: parse disk content, and match by ordinal position of the local block within its
parent's siblings (already implicitly assumed by the current `firstBlockSkipped` / root-block
indexing in `GraphLoader`), falling back to whole-file preview if the position no longer exists
(block count on disk shrank) — flag this as a fallback the UI copy must communicate ("could not
find a matching section — showing full file").

## 3. State ownership for the persistent pending-conflict indicator

**This already exists as `AppState.pendingConflicts: Map<String, PendingConflict>`**
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt` lines 136–138, 219–223), keyed
by `filePath`. It is:
- Purely in-memory — part of `StelekitViewModel._uiState` (`MutableStateFlow<AppState>`). **Not
  persisted anywhere; lost on process death / app restart.** No SQLDelight table, no DataStore.
  There is no `AppStateOptics` lens for it either (unlike `diskConflict`), so any new code
  touching it currently must use `_uiState.update { it.copy(pendingConflicts = …) }` directly.
- **Removed as soon as the user navigates to the affected page** (`checkAndShowPendingConflict`,
  `StelekitViewModel.kt` line 1471), *before* the conflict is actually resolved. This is the
  key architectural gap versus requirement gap #4: `pendingConflicts` currently means "not yet
  reviewed," not "not yet resolved." Once `checkAndShowPendingConflict` fires, the entry is gone
  from `pendingConflicts` but the conflict is still open (now as `diskConflict`) until one of
  the four resolution functions runs.

**Recommendation:** Do not introduce a second Set/StateFlow for "has pending conflict." Instead:
- Keep `pendingConflicts` as the source of truth for "conflict detected while not viewing the
  page" (badge case).
- When `checkAndShowPendingConflict` promotes a `PendingConflict` into a shown `DiskConflict`,
  **do not delete it from `pendingConflicts` at that point** — instead, either (a) leave it in
  `pendingConflicts` until one of `keepLocalChanges/acceptDiskVersion/saveAsNewBlock/
  manualResolve` explicitly removes it (rename semantically if desired, e.g. treat presence in
  `pendingConflicts` as "unresolved," and add the removal call — currently only
  `checkAndShowPendingConflict` clears it — to the tail of the four resolver functions instead),
  or (b) add clearing calls to all four resolvers keyed by `conflict.filePath` (all four already
  have `conflict.filePath` in scope) and stop clearing it early in
  `checkAndShowPendingConflict`. Option (b) is the minimal, most localized change: one-line
  `_uiState.update { it.copy(pendingConflicts = it.pendingConflicts - conflict.filePath) }`
  added to each of `keepLocalChanges()`, `acceptDiskVersion()`, `saveAsNewBlock()`, and
  `manualResolve()` (`StelekitViewModel.kt` lines ~1535–1636), and removing the early
  `pendingConflicts - filePath` update currently at line 1471.
- Because the dialog is modal (no dismiss-without-choosing path), every `DiskConflict` shown is
  guaranteed to eventually pass through exactly one of the four resolvers — so this is a
  reliable clearing point.
- Persisting across process death is out of scope per the requirements' emphasis on UX/display,
  and the underlying conflict-resolution model is explicitly out of scope — but worth flagging:
  if the app is killed while a `PendingConflict` is queued, it is silently lost and the disk
  file is never reconciled until the next watcher tick re-detects the (by-then-stale) mtime
  diff, which may or may not still show a diff depending on what changed. This is a pre-existing
  behavior, not a regression to fix here, but the persistent-indicator UI should not imply
  durability it doesn't have (e.g. avoid copy like "will remind you next time you open the app").

## 4. Integration points for a sidebar/page-list badge

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt` is the only page-list
  UI currently wired into the app. `LeftSidebar(...)` (line 58) takes `favoritePages: List<Page>`
  and `recentPages: List<Page>` and renders each via `SidebarItem(...)` (call sites at lines 210
  and 231; component defined at line 544).
  - `SidebarItem` currently takes `title, isSelected, icon, isFavorite, onFavoriteClick, onClick`
    — no conflict-awareness. Adding a badge means: (a) add a `hasPendingConflict: Boolean`
    param to `SidebarItem`, rendering e.g. a small warning dot/icon next to the star button; (b)
    at each of the two call sites in `LeftSidebar`, compute
    `page.filePath != null && page.filePath in pendingConflictFilePaths`; (c) thread a new
    `pendingConflictFilePaths: Set<String>` (or the raw `pendingConflicts` map) parameter into
    `LeftSidebar`, sourced from `appState.pendingConflicts.keys` at the single call site in
    `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt` (line ~1299).
  - Note `AppState.regularPages` / `hasMoreRegularPages` / `isLoadingMorePages` (paginated
    "all pages" list state) exist in `AppState.kt` / `AppStateOptics.kt` / `StelekitViewModel.kt`
    but **are not currently rendered by any composable** — no "All Pages" screen exists yet in
    `App.kt` or `ui/screens/`. The only real integration surface today is the Favorites/Recent
    sidebar lists. Any "Conflicts panel" (per the requirements' open question) would be new UI,
    not a retrofit of an existing screen.
- **No new repository method or query is required.** `pendingConflicts` is populated only by
  actual detected external changes (a small, naturally-bounded set — never all pages), and
  `favoritePages` / `recentPages` are already bounded per the CLAUDE.md "no unbounded reads"
  rule. Cross-referencing `page.filePath` against `pendingConflicts.keys` (or a derived
  `Set<String>`) at render time is an O(visible pages) in-memory lookup with no DB access —
  this does **not** risk the forbidden `getAllPages()`-style pattern. A naive alternative that
  *would* violate the rule: iterating all pages in the DB to build a global "which pages have
  conflicts" set instead of reusing the already-populated `pendingConflicts` map — avoid this;
  `pendingConflicts` already is that set, keyed by filePath instead of PageUuid. If a
  PageUuid-keyed lookup is preferred, derive it via `page.filePath` (already present on `Page`)
  rather than adding a join/query.
- One subtlety for a persistent indicator that must be visible **for the actively-conflicted
  page currently in the modal dialog**, not just deferred ones: since `diskConflict` (the
  modal-in-progress case) and `pendingConflicts` (the deferred case) are two different fields,
  a complete indicator (e.g. sidebar badge covering "this page has *any* unresolved conflict,
  dialog-open or deferred") should treat `appState.diskConflict?.filePath` as an implicit
  additional member of the "has conflict" set, e.g.
  `pendingConflictFilePaths = appState.pendingConflicts.keys + listOfNotNull(appState.diskConflict?.filePath)`.

## 5. Clearing on resolution — consistency requirements

All four resolution paths in `StelekitViewModel.kt` start by reading
`_uiState.value.diskConflict ?: return` and immediately do
`_uiState.update { it.copy(diskConflict = null) }` before any suspend work:
- `keepLocalChanges()` (line 1535) — re-queues `bsm.queuePageSave(...)`.
- `acceptDiskVersion()` (line 1548) — `graphLoader.parseAndSavePage(...)` then
  `blockStateManager?.savePageNow(...)`.
- `manualResolve()` (line 1575) — injects git-conflict-marker content into the single editing
  block via `blockRepository.saveBlock(...)`, then calls `requestEditBlock(...)` to focus it.
- `saveAsNewBlock()` (line 1609) — reloads disk content then appends local content as a new
  block.

None of these four currently touch `pendingConflicts` (only `checkAndShowPendingConflict` does,
and it does so too early — see §3). For the persistent indicator to behave correctly end-to-end
(appear when a conflict is detected, persist through navigation-away-and-back, disappear only
once genuinely resolved), the fix must move the `pendingConflicts` removal from
`checkAndShowPendingConflict` (line 1471) into each of these four resolvers, keyed by
`conflict.filePath`. `DiskConflictResolutionTest.kt`
(`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/DiskConflictResolutionTest.kt`) already covers
all four resolution paths and is the natural place to add assertions on `pendingConflicts`
state transitions.

## Summary of architecture-level implications for planning

1. **Preview scope fix (gap #1)**: reuse `MarkdownParser.parsePage()` (already used by
   `parseAndSavePage`) to parse `conflict.diskContent` on the fly in the dialog/ViewModel layer,
   then select the block at the same ordinal position as the local editing block — matching the
   best-effort reconciliation strategy `GraphLoader` itself already uses (position + optional
   sidecar content-hash), with an explicit fallback-to-full-file state the UI must express.
2. **Truncation escape hatch (gap #2)**: single shared fix point — both previews use identical
   `.take(200)` logic in `DiskConflictDialog.kt`; add an expand affordance there (open question:
   inline expand vs. separate view — either fits within this one file without new state beyond
   a local `remember { mutableStateOf(false) }` if inline).
3. **Manual-resolve inline help (gap #3)**: `manualResolve()`'s existing heuristic
   (`diskContent.lines().firstOrNull { it.startsWith("- ") }`) is fragile and worth replacing
   with the same parsed-block selection from point 1, plus adding explanatory copy in
   `DiskConflictDialog.kt` (or a follow-up screen) — no state changes required, purely
   copy/content.
4. **Persistent indicator (gap #4)**: no new state container needed — extend the *lifecycle* of
   the existing `AppState.pendingConflicts` map (stop early-clearing it in
   `checkAndShowPendingConflict`; clear it explicitly in each of the four resolvers instead),
   and thread `pendingConflicts.keys ∪ {diskConflict?.filePath}` into `LeftSidebar` /
   `SidebarItem` as a new `Set<String>` parameter for a badge. No repository or DB changes; no
   unbounded-read risk. Add an `AppStateOptics.pendingConflicts` lens for consistency with the
   rest of the file if the implementation touches it via optics elsewhere.
5. Add regression coverage in `DiskConflictResolutionTest.kt` for the `pendingConflicts`
   lifecycle change (entry survives from detection through dialog-open, is cleared only on
   resolution — not on mere navigation).
