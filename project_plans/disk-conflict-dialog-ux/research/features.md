# Research: Feature Landscape for Disk Conflict Dialog UX

**Project**: disk-conflict-dialog-ux
**Date**: 2026-07-03

## 1. What other Markdown/outliner/git-aware editors do

### Obsidian Sync
- Two explicit modes, chosen by the user up front (not per-conflict): **"Automatically merge"**
  (diff-match-patch text merge, default — combines both edits into one file, may create
  duplicate/garbled text the user fixes manually) vs. **"Create conflict file"** (writes a
  sibling file, e.g. `Note (conflict 2026-07-03).md`, and leaves both versions on disk for the
  user to diff and merge manually at their leisure). Non-markdown files (canvases, PDFs, etc.)
  are always last-modified-wins.
  - Relevant pattern: conflicts are **not force-resolved synchronously**. The "create conflict
    file" mode defers resolution entirely — the user is not blocked with a modal at the moment
    of conflict. This maps to SteleKit's existing "page not open" deferred-conflict case,
    which today only shows a dismissible snackbar (Requirement Gap #4).
- Community sync tools built on Obsidian (e.g. obsidian-livesync) show a **merge diff view**
  highlighting only the differing lines/hunks, not the whole file — i.e., comparable
  granularity, addressing Gap #1.
  Sources: [Obsidian Sync troubleshooting](https://forum.obsidian.md/t/robust-sync-conflict-resolution/93544), [Obsidian conflict resolution feature thread](https://forum.obsidian.md/t/option-to-let-user-manually-resolve-sync-conflicts/94468), [obsidian-livesync conflict resolution](https://deepwiki.com/vrtmrz/obsidian-livesync/4.2-conflict-resolution)

### VS Code 3-way Merge Editor
- Directly relevant to Gap #3 (explaining conflict markers). VS Code evolved *away* from raw
  `<<<<<<<`/`=======`/`>>>>>>>` markers as the primary UI: the merge editor hides the raw
  markers and instead renders three labeled panes — **"Incoming"**, **"Current"**, and a live
  **"Result"** preview — with **CodeLens action links** directly above each conflicting hunk:
  "Accept Incoming", "Accept Current", "Accept Both", "Accept Combination" (when Git can infer
  a safe join). This is a direct answer to "explain conflict markers to non-technical users":
  don't explain the markers — replace them with a labeled, actioned UI and only fall back to
  raw markers for power users who explicitly want the text-editable form.
  - History note: VS Code's first iteration used bare checkboxes next to each hunk, which users
    found non-discoverable; they replaced it with textual CodeLens labels after user feedback.
    Useful precedent: **prefer explicit text labels over icon-only affordances** for a conflict
    resolution UI aimed at non-experts.
  Sources: [VS Code merge conflicts docs](https://code.visualstudio.com/docs/sourcecontrol/merge-conflicts), [3-way merge UX issue #146091](https://github.com/microsoft/vscode/issues/146091), [VS Code 1.72 merge editor release notes](https://code.visualstudio.com/updates/v1_72)

### VS Code Source Control badge (persistent indicator precedent)
- The Source Control icon in the Activity Bar carries a **numeric badge** = count of pending
  changes, visible from anywhere in the app regardless of which file/view is focused, and
  persists until the underlying change list is empty (not dismissible independent of resolving
  the underlying item). This is the canonical "persistent indicator for deferred/pending work"
  pattern requirement #4 is asking for — count badge on a fixed chrome element, not a toast.
  Known pitfall from VS Code's own bug tracker: badge count semantics got conflated with an
  unrelated "problems" count in one release and had to be reverted — **keep the conflict badge
  count strictly scoped to conflicts, not merged with other notification types**.
  Sources: [VS Code Source Control overview](https://code.visualstudio.com/docs/sourcecontrol/overview), [badge count issue #146238](https://github.com/microsoft/vscode/issues/146238)

### Google Docs "offline changes" / sync
- Google Docs mostly avoids user-facing conflicts via server-side OT (operational
  transformation) — edits are transformed against the current doc state rather than diffed
  after the fact, so most concurrent edits merge silently at the character/op level. When it
  truly cannot reconcile (e.g., long offline period, quota/permission change), the escape
  hatch is coarse: "copy your recent edits, then revert." No comparable-preview diff UI exists;
  the fallback is "make a copy of your version, then take theirs." This is a weaker pattern
  than SteleKit already has (SteleKit already offers 4 explicit choices) — noted mainly as a
  lower bound / anti-pattern to avoid regressing toward (don't reduce to "copy or lose it").
  Sources: [Google Docs offline sync explainer](https://medium.com/@tnale/the-invisible-engine-how-google-docs-syncs-your-offline-edits-28896ea0ab09), [Google Docs sync error thread](https://support.google.com/docs/thread/103535633/error-can-t-sync-your-changes-please-copy-your-recent-edits-then-revert-your-changes)

### Notion
- No offline CRDT-merge for structural conflicts (only within synced blocks). On conflicting
  concurrent edits it **duplicates the page** — `Project Brief` and `Project Brief (Conflict)`
  — leaving manual comparison entirely to the user, with no diff UI at all. Mobile silently
  prefers most-recent-edit-wins, which is flagged in community sources as a data-loss trap.
  Pattern takeaway: **duplicate-and-let-user-diff is the actual industry floor**, reinforcing
  that SteleKit's structured 4-choice dialog is already above baseline — the redesign should
  preserve those choices, not add complexity, while fixing the four concrete UX bugs.
  Sources: [Notion offline/sync guide](https://www.taskfoundry.com/2025/08/notion-offline-mode-setup-sync-conflict-guide.html), [AFFiNE comparison of Notion offline gaps](https://affine.pro/blog/notion-offline)

### Git CLI mergetool baseline
- Raw `<<<<<<< HEAD` / `=======` / `>>>>>>> branch-name` markers denote "your side" (top) vs
  "their side" (bottom) around a `=======` divider. This vocabulary ("ours"/"theirs",
  "HEAD"/branch name) assumes git literacy — exactly Gap #3. The fix pattern from VS Code
  (above) and general mergetool UX is to **relabel these positions with plain-language,
  context-specific terms** ("Your edit" / "Disk version" — which SteleKit already uses in the
  dialog's preview labels) and carry that same plain-language pairing into the manual-resolve
  view, rather than switching to git vocabulary once markers are injected.

## 2. Edge cases the redesigned dialog/indicator must handle

1. **Multiple pages with pending conflicts simultaneously.** Today `AppState.pendingConflicts`
   is already a `Map<String, PendingConflict>` keyed by filePath (see
   `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt:138`) — the data model
   already supports N simultaneous deferred conflicts. The gap is purely UI: there is no view
   that lists them. Any indicator design must show a **count**, not just a boolean, and the
   list view it opens into must let the user resolve them independently, in any order, or in
   bulk (see LlmSuggestionReviewScreen precedent, section 4).
2. **Conflict on the currently-open page's currently-open block vs. a different block on the
   same page.** `StelekitViewModel` already partially handles this: `conflictBlockUuid` prefers
   `editingBlockUuid` and falls back to "first dirty block on the page"
   (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt` around line 1420).
   The redesigned dialog should make this distinction visible in copy — "you were editing this
   block" vs. "you have unsaved changes elsewhere on this page" — so the user isn't confused
   when the previewed block isn't the one they were actively typing in.
3. **User navigates away mid-dialog.** The dialog's `onDismissRequest = { /* require explicit
   choice */ }` currently blocks all dismissal — confirm this is still the intended behavior,
   or decide whether navigating away should demote the open dialog's conflict into a
   `PendingConflict` (added to the persistent list) rather than trapping the user. The latter is
   more consistent with the "persistent indicator" model this project is adding — a conflict
   should never require the user to resolve it on the spot if they'd rather come back to it,
   as long as it's visibly tracked afterward.
4. **A second external change arrives while the dialog is still open for a prior conflict on
   the same page/block.** Currently undefined — `_uiState.update { it.copy(diskConflict = ...)
   }` would silently overwrite the in-flight `DiskConflict` state if the collector fires again
   before the user responds. Needs an explicit decision: queue it (show "2 more changes arrived
   while you were deciding") vs. coalesce (silently take the newest disk content) vs. block
   (suppress the watcher's re-check while a dialog is open, which the `event.suppress()` call
   already partially does for the *original* event chain, but a second independent watcher tick
   is a different code path). Recommend: **coalesce to latest disk content but surface a "the
   disk file changed again since this dialog opened" notice** so the user isn't shown stale
   disk content without knowing it moved again.
5. **Mobile vs. desktop layout for an expanded "view full content" comparison.** Desktop can
   afford true side-by-side (two `Surface` panes side by side); Android/mobile width forces a
   stacked or tabbed comparison ("Your edit" / "Disk version" as two swipeable/tabbed panes,
   not two columns). The existing dialog is a single `AlertDialog` with a `verticalScroll`
   `Column` — already stacked, which is mobile-safe, but at full-content scale (not
   truncated to 200 chars) a modal `AlertDialog` may not be the right container at all on
   desktop; consider a full-screen route (SteleKit already has this pattern for git conflicts
   via `ConflictResolutionScreen`, a `Scaffold`-based full screen rather than a dialog) for the
   "view full" escape hatch specifically, keeping the compact `AlertDialog` for the default case.
6. **Empty/whitespace-only content on either side.** The dialog already special-cases
   `conflict.localContent.isNotBlank()` to hide the "Your edit" preview and the "save as new
   block" button — the redesign must preserve this branch when reworking preview granularity so
   an empty local block doesn't render an awkward empty comparable-granularity diff.
7. **Very large disk-side content** (the requirement's "Disk version is closer to
   whole-file-scoped" bug means today's disk preview can be an entire page's markdown). Matching
   granularity to block-scope on the disk side needs a re-fetch/re-parse of the specific block
   from the new disk content, which may fail if the external edit **restructured** the file
   (e.g. deleted the block entirely, or split it). Handle "the block your edit corresponds to no
   longer exists in the disk version" as its own explicit state, not a silent empty diff.

## 3. Unstated needs beyond the explicit requirements

- **A "Conflicts" list/inbox across the whole graph**, not just resolve-one-at-a-time. The
  `Map<String, PendingConflict>` already models this at the data layer; the requirement's
  "persistent indicator" (#4) strongly implies users will want to click that indicator and see
  *all* pending conflicts, not just get routed to the next one blindly. SteleKit already has
  the exact shape of this pattern in production for a different domain — see section 4.
- **Distinguishing "conflict I can see the diff for" vs. "conflict where the block itself
  vanished."** Not explicit in requirements, but falls directly out of edge case 7 above; the
  UI needs a state for this that isn't just an empty/blank preview.
- **A "resolve all the same way" bulk action** when many pages changed at once (e.g. a large
  `git pull` while several pages were mid-edit) — mirrors the `LlmSuggestionReviewScreen`'s
  "Accept All" / "Reject All" affordance (section 4) and avoids forcing N individual modal
  interactions for what's often the same underlying event (one sync pulling many files).
- **Not re-litigating the same conflict twice.** If the user picks "Use disk version" for a
  pending conflict from the list, and then separately navigates to that page before the list
  update propagates, `checkAndShowPendingConflict` must not re-show a resolved conflict — needs
  a shared source of truth between the future list view and the existing per-navigation dialog
  trigger (both currently read `AppState.pendingConflicts`, so this should fall out naturally
  if both paths write through the same state removal, but is worth an explicit test).

## 4. Existing "needs attention" list patterns in this codebase to reuse

Two directly analogous patterns already exist in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/` and should be extended rather than reinvented:

### a) `LlmSuggestionInbox` + `LlmSuggestionReviewScreen` — closest structural match
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/llm/LlmSuggestionInbox.kt`: a plain class
  wrapping `MutableStateFlow<Map<String, PendingLlmSuggestion>>` — "shaped exactly like the
  existing `AppState.pendingConflicts: Map<String, PendingConflict>`" (per its own doc comment
  — the author already flagged the structural parallel to disk conflicts).
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/llm/LlmSuggestionReviewScreen.kt`:
  full-screen list view with per-item accept/reject **and** "Accept All"/"Reject All" bulk
  actions (`GraphDialogLayer.kt` ~line 254-268).
- Trigger wiring in `StelekitViewModel.kt` (~line 341-362): a collector flips
  `AppState.llmSuggestionReviewVisible = true` when the inbox becomes non-empty, and — per an
  explicit code comment — is **"NOT auto-dismissed when it becomes empty via accept/reject"**,
  i.e. the review screen stays open until the user explicitly closes it, even if the last item
  is cleared. This is a precedent worth deliberately deciding for/against for the conflicts list
  too.
- **Recommendation**: model the new "pending conflicts list" screen directly on
  `LlmSuggestionReviewScreen`, and consider promoting `AppState.pendingConflicts` into a small
  dedicated inbox class analogous to `LlmSuggestionInbox` if it needs richer operations
  (bulk-resolve, per-item dismiss) than a raw `Map` conveniently supports in `StelekitViewModel`.

### b) `SyncStatusBadge` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SyncStatusBadge.kt`) — closest visual/indicator match
- Already rendered persistently in `Sidebar.kt:146`, and **already has a dedicated state for
  conflicts**: `SyncState.ConflictPending` renders an amber warning icon with a "Conflict"
  label (see the doc comment enumerating all badge states, lines ~50-56). This is git-sync-level
  conflict (`ConflictResolutionScreen`), a different conflict type than disk-conflict, but it
  is the app's existing visual vocabulary for "there is an unresolved conflict, look here."
- **Recommendation**: the new persistent disk-conflict indicator (Gap #4) should either (a)
  reuse `SyncStatusBadge`'s existing amber-warning "Conflict" visual treatment for consistency,
  clicking through to the new list screen from 4a, or (b) sit adjacent to it in the sidebar
  header with a matching numeric badge (VS Code Source Control badge pattern, section 1) showing
  `pendingConflicts.size`. Do not merge the two conflict *types* (git-merge vs. disk-watcher)
  into one indicator/count — they have different resolution flows
  (`ConflictResolutionScreen` vs. `DiskConflictDialog`) and conflating counts risks the exact
  VS Code badge-semantics bug noted in section 1.

### c) `ConflictResolutionScreen` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/ConflictResolutionScreen.kt`) — closest "full-screen conflict list with per-item resolution" match
- Already a `Scaffold`-based full-screen (not a dialog) list of `ConflictFile`s, each with a
  `FilterChip`-based LOCAL/REMOTE selector defaulting to LOCAL, plus an abort-merge escape
  hatch. This is a proven full-screen pattern (as opposed to a stacked modal) for "resolve N
  conflicting items in one sitting" that the "view full content" expansion (Gap #2) and the
  new whole-graph conflicts list (section 3) can both structurally borrow from — particularly
  for the desktop side-by-side layout called out in edge case 5.

### d) `writeErrors` banner (`StelekitViewModel.observeWriteErrors`, ~line 1490) — weaker precedent, dismissible-only
- A DB-write-failure banner ("Failed to save N blocks... Tap to retry") — dismissible, no
  persistent count, no list of all failures. Documented here as the **pattern to avoid**
  repeating: this is exactly the "dismissible snackbar with no persistent marker" anti-pattern
  Gap #4 is asking to fix, just for a different error class. Useful negative example, not a
  model to extend.

## Existing conflict-marker infrastructure relevant to Gap #3

- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/ConflictMarkerDetector.kt` already knows
  how to detect git-style `<<<<<<<`/`>>>>>>>` markers (used to block *importing* a file that
  still has unresolved markers — a different use case than the disk-conflict dialog's "manual
  resolve" button, which *injects* markers per the requirements doc). Any inline
  marker-explanation UI added to the block editor should reuse this detector (or its regexes)
  to locate marker positions rather than re-implementing marker parsing, and should keep the
  vocabulary consistent with the dialog's existing "Your edit" / "Disk version" labels rather
  than introducing git's "HEAD"/"ours"/"theirs" terms at the marker-injection point.
