# Pitfalls: disk-conflict-dialog-ux

Grounded in this codebase's `DiskConflictDialog.kt`, `StelekitViewModel.kt` (conflict-handling
section, lines ~1370–1620), `AppState.kt`, `GraphLoader.kt`, `MarkdownPageParser.kt`, and the
prior `disk-change-detection` project's adversarial review / pitfalls docs.

---

## 1. Known risks already on record from `disk-change-detection` (not re-litigated, but relevant)

The original feature's adversarial review (`project_plans/disk-change-detection/implementation/adversarial-review.md`)
flagged three issues, none directly about dialog preview/truncation/indicator UI, but two are load-bearing
context for this fix:

- **Concern 1 (HIGH, still open per the review)**: UUID→path resolution can silently drop a page whose DB
  write is still in flight (new-page creation). If this fix adds any *new* per-page state keyed by
  page UUID or file path (e.g. the persistent pending-conflict indicator), it inherits the same
  in-flight-write blind spot — a just-created page's conflict could fail to register in the new
  indicator for the same reason `unsavedPageFilePaths` can miss it.
- **`ShadowFlushActor` mtime race (pitfalls.md §3)**: on encrypted `.md.stek` graphs, a flush racing an
  `onStart` scan produces a **spurious** conflict dialog — meaning some conflicts this UX fix renders may
  have no real content difference. Preview rendering should tolerate `localContent == diskContent`
  gracefully (today it would just show two identical blocks — confirm the new UI doesn't assume
  divergence).

Nothing in the original plan/review discusses preview granularity, truncation, or a persistent indicator —
those are genuinely new surface area, not previously-solved problems.

---

## 2. "View full content" expansion inside the modal — risks

Current `DiskConflictDialog` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/DiskConflictDialog.kt`)
already wraps its `text = { }` slot in `Modifier.verticalScroll(rememberScrollState())` around **two**
truncated-preview `Surface` blocks. Adding a "view full" escape hatch inside this same dialog risks:

- **Scroll-within-scroll**: the dialog body is already one scrollable `Column`. If "view full" is
  implemented as an inline-expanding `Text` inside that same `Column` (rather than a separate full-screen
  view), Compose does not need nested scroll containers for that case — but if instead a full block/file
  preview is dropped into its own `verticalScroll` `Box` nested inside the outer scrollable `Column`
  (a common naive pattern for "expand in place"), you get competing scroll gesture consumption, which on
  Android reads as the outer dialog scroll "eating" swipes meant for the inner preview. Prefer: full
  content view as a **separate modal/screen** (push a new dialog or bottom sheet), not an expanding region
  inside the existing `AlertDialog` `text` slot.
- **Material3 `AlertDialog` has no explicit height cap in this codebase** — it will grow to fit content up
  to available screen height, then Compose enforces its own scrolling within the `text` slot's measured
  region. Rendering full page content (not just a block) for a large page inside this dialog blows out
  dialog height on mobile far more than the two 200-char previews do today; a full **file-scoped** disk
  version (this fix's stated diagnosis) could be tens of KB. Any "view full disk file" affordance should
  paginate/virtualize or route to a dedicated large-content viewer rather than rendering the entire string
  into a `Text` composable inside the dialog — `Text` has no virtualization, so a very large page will do a
  full-string layout pass synchronously on the UI thread on dialog open.
- **Performance**: `conflict.diskContent` is the raw file content (see §3 below — it's whole-file, not
  block-scoped) already held in memory by the time `DiskConflict` is constructed (`StelekitViewModel.kt:1453`,
  `event.content` from `ExternalFileChange`), so there's no extra I/O cost to show it in full — the risk is
  purely Compose layout/measurement cost of a single large `Text`, not a missing-data problem.

---

## 3. Narrowing "Disk version" to block-level scope — structural mismatch risk (the load-bearing finding)

**This is a real design risk, not hypothetical**, and the codebase's own reconciliation logic proves it:

- `DiskConflict.diskContent` today is `event.content` from `ExternalFileChange` (`GraphEvents.kt:12-16`) —
  the **entire file's text**, unparsed. `DiskConflict.localContent` is a **single block's** content, read
  either from `BlockStateManager`'s optimistic in-memory state or a DB fallback
  (`StelekitViewModel.kt:1439-1444`). There is currently **no code path** that parses `diskContent` into
  blocks and looks up "the block on disk corresponding to `editingBlockUuid`" — the mismatch the
  requirements doc describes is the literal, current behavior.
- To narrow the disk-side preview to block granularity, you'd need to parse the disk content and match it
  back to `conflict.editingBlockUuid`. **Block identity across a reparse is not stable in this codebase.**
  `MarkdownPageParser.processParsedBlocks` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/MarkdownPageParser.kt:141`)
  derives each parsed block's UUID via `generateUuid(parsedBlock, pagePath, index, parentUuid, ...)` — a
  function of **position/parent/index**, not a persisted stable ID read from the file. Recovery of the
  "real" UUID after reordering falls back to a **sidecar content-hash map**
  (`GraphLoader.kt:1744-1747`, `MarkdownPageParser.kt:51-53`) that is explicitly documented as a fix for
  "content-hash → UUID recovery (e.g. after a git pull that reordered blocks)" — i.e., even the production
  reconciliation path treats block-identity matching as a best-effort heuristic, not a guarantee.
- **Concretely**: if the disk edit deleted the block outright, or split it into two blocks, there is no
  disk-side block whose derived UUID equals `conflict.editingBlockUuid`, and the sidecar's content-hash
  fallback will not find a match either (the content changed, that's the whole point of the conflict). Any
  "corresponding disk block" lookup must define an explicit **no-match fallback** — e.g., "block not found on
  disk (it may have been deleted or split) — showing the disk version of the surrounding page section" or a
  clean drop-back to the current whole-file preview. Silently matching by position index (e.g., "block N of
  the disk page") would misattribute content when blocks above have been added/removed on disk — worse than
  today's honest whole-file preview.
- **Recommendation for the plan phase**: treat "disk-side single-block preview" as a *best-effort
  enhancement* with a defined, tested fallback to file-scoped (or "surrounding blocks") preview when no
  confident match exists — do not assume a 1:1 mapping is achievable, because the existing sidecar mechanism
  in this exact codebase already had to be built to work around the same problem for a different purpose.

---

## 4. Persistent per-page conflict state vs. `GraphManager` multi-graph lifecycle

**`AppState.pendingConflicts: Map<String, PendingConflict>` (keyed by file path) already exists** and is
explicitly the mechanism requirement #4 is asking to extend/improve — this is not new state, so grounding
here is concrete rather than speculative:

- It is documented **elsewhere in the codebase** as the reference pattern for "session-scoped only, no
  persistence": `LlmSuggestionInbox.kt:11-14` explicitly models its own pending-state map "shaped exactly
  like the existing `AppState.pendingConflicts`... session-scoped only — discarded on app exit, no
  persistence." **Confirm this is an acceptable trade-off for the indicator fix too** — if requirement #4's
  "persistent" indicator is meant to survive an app restart or a graph switch-and-back, the existing
  `pendingConflicts` pattern does *not* do that today, and a naive copy of the pattern won't either.
- **Lifecycle**: `StelekitViewModel` (which owns `_uiState`/`pendingConflicts`) is **fully recreated** on
  graph switch. In `App.kt`, `viewModel = remember(fileSystem, repos, ...)` is keyed on `repos`, which
  changes per active graph, and the whole subtree lives inside a `key(activeGraphId) { }` block per the
  comment at `App.kt:1124-1126` ("Cancel all ViewModel scopes when GraphContent leaves composition
  (key(activeGraphId) re-keys)... Without this, orphaned scopes from the previous composition keep running
  concurrently"). `DisposableEffect(viewModel) { onDispose { viewModel.close() } }` (`App.kt:1127-1136`) then
  calls `StelekitViewModel.close()` → `scope.cancel()` (`StelekitViewModel.kt:1358-1360`) on the old instance.
  **Net effect: `pendingConflicts` is wiped to empty on every graph switch — it does not leak stale entries
  across graphs (good), but it also does not survive a switch-away-and-back for the *same* graph (a gap for
  "persistent" as the requirement uses the word).**
- **A naive `Set<PageUuid>` would behave identically** — same teardown boundary, same non-persistence. If the
  intent is a *true* persistent indicator (survives graph switches / app restarts), it needs to be backed by
  something outside `StelekitViewModel`'s per-graph-instance state — e.g., a DB-column-derived value queried
  fresh per graph load (consistent with this repo's "graph-scale reads must be paginated/projected" rule —
  a per-page boolean flag or a bounded `getPagesWithPendingConflicts()` query, not a full-table scan) — or
  explicitly scoped and documented as session-only like `pendingConflicts` already is, so expectations match
  reality.
- **No crash risk observed**: because the whole `StelekitViewModel` (and its `_uiState`) is discarded and
  rebuilt per graph, there's no closed-DB/stale-reference crash vector analogous to a raw `asFlow()` without
  `catchDbError()` — the state simply doesn't exist anymore, it isn't read from a dead source. The risk here
  is silent data loss of *indicator visibility*, not a crash.

---

## 5. Inline explanation of conflict markers — content/chrome separation risk (concrete interaction bug found)

`manualResolve()` (`StelekitViewModel.kt:1574-1602`) builds the git-style marker block via `buildString {
appendLine("<<<<<<< Your edit"); append(conflict.localContent); ...; appendLine("=======");
...; append(">>>>>>> Disk") }` and passes that string **directly** as `updatedBlock.content` to
`blockRepository.saveBlock(...)` (via `writeActor?.execute { ... }`). There is **no separation** between
marker text and persisted content today — the markers *are* the persisted block content by design (the user
is expected to hand-edit them out later).

This creates a concrete, already-latent bug that any "inline explanation" addition must not make worse:

- `GraphLoader.parseAndSavePage` has an explicit import guard:
  `ConflictMarkerDetector.hasConflictMarkers(content)` → if true, **the entire file's import is suppressed**
  and a `WriteError` ("Git conflict markers detected... resolve conflicts before importing") is emitted
  (`GraphLoader.kt:1683-1693`). Since `manualResolve()` writes literal `<<<<<<<`/`=======`/`>>>>>>>` lines
  into a block that (via `GraphWriter`'s normal debounced flow) eventually gets written back out to the
  page's `.md` file, **the very next external-change detection or reload cycle for that same file will hit
  this guard and refuse to re-import the page** until the user manually deletes the markers. This is not
  hypothetical — it's the documented purpose of `ConflictMarkerDetector`, and `manualResolve()`'s output is
  exactly the input that guard is designed to reject.
- **Risk for this UX fix specifically**: if "inline explanation" text (e.g. "Delete the lines you don't want,
  then remove these three marker lines") is implemented by concatenating that explanation into the same
  string that gets passed to `saveBlock(...)` — the most naive implementation path, since `manualResolve()`
  already builds one `buildString` — the explanation itself becomes permanent note content if the user
  doesn't fully clean it up, compounding the existing marker-cleanup burden and making it *more* likely a
  user leaves stray text (now including UI-authored prose, not just `<<<<<<<` sentinels) in their notes.
  **The explanation must be rendered as separate Compose UI chrome (e.g. a `Text`/`Card` in the dialog or a
  transient banner shown while the block is in edit mode) and never appended into the string that flows into
  `saveBlock`.**
- Secondary, smaller risk: since the conflict-marker guard blocks *reload*, not editing, the page remains
  editable and savable while markers are present — so the failure mode is "external sync stops working for
  this page silently" rather than a crash or visible error banner tied to the page itself (the `WriteError`
  surfaces as a generic "Failed to save page" snackbar via `observeWriteErrors()`, `StelekitViewModel.kt:1493-1505`,
  which doesn't obviously point back at the conflict-marker cause). Worth considering whether the "inline
  explanation" should also warn the user *while markers are present* that the page won't re-sync until they're
  removed — that's arguably the single most valuable thing "inline explanation" could add, and it's grounded
  in a real, already-shipped guard elsewhere in the codebase.

---

## 6. Concurrency/race: indicator cleared on resolution vs. a new external change for the same page

Found a concrete, already-latent race in the pending→active conflict handoff, independent of anything new
this fix adds — worth flagging because the fix will touch this exact code path:

- `checkAndShowPendingConflict()` (`StelekitViewModel.kt:1467-1487`) **synchronously** removes the entry from
  `pendingConflicts` (`_uiState.update { it.copy(pendingConflicts = it.pendingConflicts - filePath) }`,
  line 1471) the moment the user navigates to a page with a stored pending conflict, then **asynchronously**
  (inside `scope.launch`) reads the block list and constructs the `DiskConflict` using the **already-captured**
  `pending.diskContent` snapshot (the `pending` local val was captured *before* the removal, from
  `_uiState.value.pendingConflicts[filePath]` at line 1470).
- **Race window**: between the synchronous indicator removal (line 1471) and the `diskConflict` actually
  being set (inside the `scope.launch` body, lines 1472-1485), if a *new* `ExternalFileChange` arrives for
  the same `filePath`, `observeExternalFileChanges()`'s branch condition
  (`currentPage == null || currentPage.filePath != event.filePath`, line 1376) now evaluates to **false**
  (the user has already navigated to the page), so the new event is **not** re-added to `pendingConflicts`
  — it instead flows into the "actively editing" branch. But the `diskConflict` that ends up shown to the
  user still carries the **stale** `pending.diskContent` captured before that second change arrived, not the
  latest disk content.
- **Consequence beyond stale UI text**: `acceptDiskVersion()` (`StelekitViewModel.kt:1548-1558`) calls
  `graphLoader.parseAndSavePage(FilePath(conflict.filePath), conflict.diskContent, FULL)` followed by
  `blockStateManager?.savePageNow(conflict.pageUuid)` — which **writes the stale snapshotted `diskContent`
  back out to the actual file**, overwriting whatever newer content the second external change had already
  put there. This is a genuine (if narrow-window) data-loss vector: "Use disk version" can regress the disk
  file to an intermediate state that is itself stale, silently undoing the most recent external edit.
- This is pre-existing behavior, not introduced by this UX fix, but the fix's stated scope ("persistent
  pending-conflict indicator... related state additions") makes it very likely the implementation will touch
  this exact handoff path. **Recommend**: re-validate `pending.diskContent` against the latest known disk
  state (or re-read the file) immediately before constructing `diskConflict` in `checkAndShowPendingConflict`,
  rather than trusting the value captured at indicator-removal time — and/or keep the indicator entry present
  until `diskConflict` is actually confirmed set, so a second incoming event during the handoff window can
  still update or replace it instead of being dropped into a different branch.

---

## Summary of grounded, non-speculative findings

1. **Preview granularity mismatch is exactly as described** — `localContent` is one block's content,
   `diskContent` is the raw whole-file string (`StelekitViewModel.kt:1439-1453`); no reparsing/matching
   happens today.
2. **Block-level disk matching has no reliable identity to hook into** — parsed-block UUIDs are
   position/content-derived (`MarkdownPageParser.kt:141`), and the codebase's own sidecar-based recovery
   mechanism (`GraphLoader.kt:1744-1747`) is an explicit acknowledgment that block identity across a reparse
   is only heuristically recoverable, not guaranteed — deletion/split cases will have no match, and a
   fallback path must be designed and tested, not assumed away.
3. **`pendingConflicts` already exists, is documented elsewhere as session-scoped/non-persistent
   (`LlmSuggestionInbox.kt:11-14`), and is fully wiped on every graph switch** because `StelekitViewModel` is
   torn down and rebuilt per `key(activeGraphId)` (`App.kt:1124-1136`) — no cross-graph leak risk, but also
   no true persistence; any new indicator state should either accept the same session-only semantics
   explicitly or be backed by a per-graph DB query instead of in-memory `StelekitViewModel` state.
4. **A real interaction bug exists between `manualResolve()` and `ConflictMarkerDetector`**: markers written
   by manual-resolve will cause the app's own import guard to refuse to re-import that page's file until the
   user cleans them up (`GraphLoader.kt:1683-1693`) — the new "inline explanation" feature must render as UI
   chrome only, never get concatenated into the string passed to `saveBlock`, and ideally should warn the
   user that the page won't re-sync while markers are present.
5. **A real stale-content race exists in the pending→active conflict handoff** (`StelekitViewModel.kt:1467-1487`)
   that can cause "Use disk version" to overwrite a file with stale intermediate content if a second external
   change lands during the indicator-clear window — pre-existing, but directly adjacent to the code this fix
   will modify.
