# Implementation Plan: disk-conflict-dialog-ux

**Feature**: Fix the four concrete UX bugs in `DiskConflictDialog` (mismatched preview
granularity, hard truncation with no escape hatch, git-literate-only manual resolve, no
persistent indicator for deferred conflicts) without changing the underlying last-writer-wins
resolution model.
**Date**: 2026-07-03
**Status**: Ready for implementation
**ADRs**:
- ADR-001: Use `kotlin-multiplatform-diff` for "View Full" comparison, rendered as a full-screen route (not inline dialog expansion) — updated to specify that `DiskConflictDialog` is suppressed while the full-screen view is visible
- ADR-002: Persistent conflict indicator is session-scoped, not cross-restart-durable
- ADR-003: Always-visible sidebar conflict-count badge as the fallback indicator surface for pages outside Favorites/Recent

---

## Dependency Visualization

```
Phase 1: State Lifecycle Foundation
  Epic 1.1 (StelekitViewModel.kt: pendingConflicts lifecycle + stale-content race)
        │
        ├──────────────┬───────────────────────────────┐
        ▼              ▼                                ▼
Phase 2: Preview      Phase 5: Persistent Sidebar        Phase 6: Regression Tests
Granularity           Indicator                          (depends on ALL phases below
  Epic 2.1              Epic 5.1                          for full coverage, but 6.1.1/6.1.2
  (block matcher +        (Sidebar.kt, App.kt —           can start once Phase 1 lands)
   DiskConflict.          reads pendingConflicts,
   diskBlockContent)       needs correct lifecycle
        │                  from Phase 1)
        ▼
  Epic 2.2
  (manualResolve reuses
   diskBlockContent)
        │
        ├───────────────────────┐
        ▼                       ▼
Phase 3: View Full /      Phase 4: Manual Resolve
Diff Escape Hatch         Inline Help
  Epic 3.1                  Epic 4.1
  (DiskConflictFullScreen,  (dialog caption +
   uses diskBlockContent     post-action snackbar,
   ?? diskContent            reuses ConflictMarkerDetector)
   from Epic 2.1)
        │                       │
        └───────────┬───────────┘
                     ▼
         Phase 6: Regression Tests
         Epic 6.1 (extends DiskConflictResolutionTest.kt +
                    new DiskConflictBlockMatcherTest.kt)
```

Sequencing note: Phases 2–5 all touch `StelekitViewModel.kt`, `AppState.kt`, or
`DiskConflictDialog.kt`. Implement Phase 1 first (it edits the same functions that Phase 2
wires into), then Phase 2 before Phase 3/4 (both consume `DiskConflict.diskBlockContent`),
then Phase 5 (independent of 2–4, only needs Phase 1's corrected `pendingConflicts`
lifecycle), then Phase 6 last.

**Post-review patch note** (three-reviewer BLOCKED pass, this revision): no phase reordering was
needed to resolve the 6 blockers — all fixes landed as new/corrected tasks *within* their
existing epic, sub-lettered so numbering stays monotonic and unambiguous:
- Phase 2 gained Task 2.1.2a-pre (markdownParser field — blocker: wrong DI premise) and Task
  2.1.2b/2.1.2c were rewritten in place (try/catch guard — blocker: unguarded parse calls;
  block-list staleness — folded concern). Story 2.1.1/Task 2.1.1c gained a documented-limitation
  note (blocker: silent wrong match on reorder).
- Phase 3 gained Task 3.1.1e, sequenced immediately after 3.1.1d (blocker: full screen renders
  behind the still-open `AlertDialog`). Task 3.1.2a gained a `contentDescription` requirement
  (folded concern).
- Phase 5 gained Story 5.1.4 (Tasks 5.1.4a/5.1.4b), sequenced after 5.1.3 within the same epic —
  no new cross-phase dependency, since it consumes the same `pendingConflictFilePaths` Task 5.1.3a
  already produces (blocker: sidebar coverage gap for non-favorited/non-recent pages). Task
  5.1.1a gained a `contentDescription` requirement (folded concern).
- Phase 6 gained Task 6.1.3d (blocker: pin the reorder limitation as a tested case), Story 6.1.5 /
  Task 6.1.5a (regression coverage for the Phase 2 try/catch fix), and Task 6.1.2a was corrected
  in place to use the test-local `graphLoader` value instead of `vm.graphLoader` (blocker: did not
  compile).

**Post-review patch note (2nd revision)**: the first patch pass resolved 3 of 4 original
adversarial blockers outright; the 4th (same-count sibling reorder can silently return the wrong
block's content) was only *documented and pinned as known behavior*, which a second adversarial
re-review correctly rejected as not actually resolving the safety concern. This revision adds a
real, cheap mitigation instead of a documentation-only fix:
- Story 2.1.1 gained Task 2.1.1d — a content-hash plausibility check (reusing the existing
  `Block.contentHash` / `ContentHasher.sha256ForContent`, no new field or dependency) that detects
  when a positional match's content hash collides with a *different* local sibling's known hash,
  and returns `null` instead of the misattributed content.
- Task 2.1.1c's KDoc and Story 2.1.1's acceptance criteria were updated to describe the narrower
  residual limitation (a reorder combined with a content edit at the transposed position is still
  undetected — out of proportion to fix here, per the same `SidecarManager`-migration tradeoff as
  before).
- Task 6.1.3d was rewritten from "assert the wrong match is pinned as expected" to "assert the
  plausibility check now returns `null` for a pure reorder," plus a second case confirming
  never-saved siblings (`contentHash == null`) don't produce a false-positive collision.

---

## Phase 1: State Lifecycle Foundation

### Epic 1.1: Fix `pendingConflicts` early-clear bug and the adjacent stale-content race
**Goal**: `AppState.pendingConflicts` entries survive navigation and are cleared only when the
user actually resolves the conflict via one of the four resolvers — this is the correctness
foundation the persistent sidebar indicator (Phase 5) depends on. Also fix the adjacent
stale-content race in the same function while it's already being edited.

Grounding: `StelekitViewModel.kt` L1467-1487 (`checkAndShowPendingConflict`) currently clears
the map entry at L1471 — before the `DiskConflict` is even constructed in the `scope.launch`
below it, and long before the user picks a resolution. The four resolvers
(`keepLocalChanges` L1535, `acceptDiskVersion` L1548, `manualResolve` L1575, `saveAsNewBlock`
L1609) never touch `pendingConflicts` at all today.

#### Story 1.1.1: Stop clearing `pendingConflicts` before resolution; re-validate stale content
**As a** user who navigates to a page with a deferred conflict, **I want** the conflict to
remain tracked as unresolved until I actually pick a resolution, **so that** a persistent
indicator (Phase 5) can accurately reflect unresolved conflicts.
**Acceptance Criteria**:
- `checkAndShowPendingConflict()` no longer removes the `pendingConflicts[filePath]` entry.
- If a second external change for the same file arrives while the async `DiskConflict` is being
  built, the dialog reflects the latest disk content, not the stale captured snapshot.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

##### Task 1.1.1a: Remove the premature `pendingConflicts` clear (~2 min)
- Delete the line `_uiState.update { it.copy(pendingConflicts = it.pendingConflicts - filePath) }`
  at `StelekitViewModel.kt` L1471 (inside `checkAndShowPendingConflict`, immediately after the
  `pending` null-check). Do not replace it with anything — removal now happens in the four
  resolvers (Story 1.1.2).
- Files: `StelekitViewModel.kt`

##### Task 1.1.1b: Re-validate disk content before building `DiskConflict` (~4 min)
- In `checkAndShowPendingConflict()`'s `scope.launch` block (`StelekitViewModel.kt` L1472-1485),
  immediately before constructing `DiskConflict`, re-read the latest snapshot instead of
  trusting the captured `pending.diskContent`:
  `val latestDiskContent = _uiState.value.pendingConflicts[filePath]?.diskContent ?: pending.diskContent`
- Use `latestDiskContent` (not `pending.diskContent`) for `DiskConflict.diskContent` at L1482.
- This closes the race where a second `ExternalFileChange` arrives for the same file between the
  synchronous map-read (`pending`, now un-cleared per Task 1.1.1a) and the async
  `DiskConflict` construction — without it, `acceptDiskVersion()` could later write the older
  snapshot back to disk, silently regressing past the newer external edit.
- Files: `StelekitViewModel.kt`

#### Story 1.1.2: Clear `pendingConflicts` at the tail of each of the four resolvers
**As a** user, **I want** the deferred-conflict tracking cleared only once I've actually chosen
how to resolve it, **so that** the persistent indicator (Phase 5) disappears exactly when the
conflict is handled — not before, not never.
**Acceptance Criteria**:
- Each of `keepLocalChanges()`, `acceptDiskVersion()`, `saveAsNewBlock()`, `manualResolve()`
  removes `conflict.filePath` from `pendingConflicts` after its resolution work completes.
- `manualResolve()`'s early-return branch (no `editingBlockUuid`) also clears the entry.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

##### Task 1.1.2a: Clear on `keepLocalChanges()` (~2 min)
- In `keepLocalChanges()` (`StelekitViewModel.kt` L1535-1542), inside the `scope.launch { bsm.queuePageSave(currentPage.uuid.value) }` block, add as the last statement:
  `_uiState.update { it.copy(pendingConflicts = it.pendingConflicts - conflict.filePath) }`
- `conflict` (captured from `_uiState.value.diskConflict` at the top of the function, L1536) is
  already in scope.
- Files: `StelekitViewModel.kt`

##### Task 1.1.2b: Clear on `acceptDiskVersion()` (~2 min)
- In `acceptDiskVersion()` (`StelekitViewModel.kt` L1548-1558), add the same
  `pendingConflicts - conflict.filePath` update as the last statement inside the `scope.launch`
  block, after `blockStateManager?.savePageNow(conflict.pageUuid)` (L1556).
- Files: `StelekitViewModel.kt`

##### Task 1.1.2c: Clear on `saveAsNewBlock()` (~2 min)
- In `saveAsNewBlock()` (`StelekitViewModel.kt` L1609-1636), add the same update as the last
  statement inside the `scope.launch` block, after `blockStateManager?.savePageNow(conflict.pageUuid)`
  (L1634). Note the early-return at L1611-1614 delegates to `acceptDiskVersion()`, which already
  clears via Task 1.1.2b — no separate handling needed there.
- Files: `StelekitViewModel.kt`

##### Task 1.1.2d: Clear on `manualResolve()`, both branches (~3 min)
- In `manualResolve()` (`StelekitViewModel.kt` L1575-1602):
  - Early-return branch (no `editingBlockUuid`, L1577-1581): add the update synchronously,
    right after `_uiState.update { it.copy(diskConflict = null) }` at L1579, before `return`.
  - Main branch: add the same update as the last statement inside the `scope.launch` block,
    after `requestEditBlock(conflict.editingBlockUuid, 0)` (L1600).
- Files: `StelekitViewModel.kt`

##### Task 1.1.2e (optional, low-risk consistency): Add `AppStateOptics.pendingConflicts` lens (~2 min)
- `AppStateOptics.kt` currently has zero call sites across the codebase (dead scaffolding), so
  this is not required for functionality, but keep parity with `diskConflict`'s existing lens
  (`AppStateOptics.kt` line with `val diskConflict: Lens<AppState, DiskConflict?> = ...`) in case
  it is wired up later:
  `val pendingConflicts: Lens<AppState, Map<String, PendingConflict>> = Lens(get = { it.pendingConflicts }, set = { s, v -> s.copy(pendingConflicts = v) })`
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppStateOptics.kt`

---

## Phase 2: Disk-Side Block-Scoped Preview (Gap #1)

### Epic 2.1: Parse disk content and match it to the local editing block by ordinal position
**Goal**: Replace the whole-file "Disk version" preview with a block-scoped preview at the same
granularity as the "Your edit" preview, with an explicit, tested fallback when no match exists.

Grounding: `DiskConflict.diskContent` (`AppState.kt` L211) is always the whole raw file, never
parsed (`research/stack.md`). `MarkdownParser.parsePage(content): ParsedPage`
(`parser/MarkdownParser.kt` L17) is already used for exactly this purpose elsewhere
(`GraphLoader.kt` L1708). `ParsedBlock` (`model/ParsedModels.kt` L34-50) has **no UUID field** —
block identity is derived from position/parent/index at reconciliation time
(`MarkdownPageParser.kt` L141, `generateUuid(...)`), not persisted. Local sibling ordering by
`position` (fractional-index string) is an established pattern elsewhere in the codebase
(`GraphWriter.kt` L574: `siblings.sortedBy { it.position }`).

#### Story 2.1.1: Build a pure, unit-testable ordinal-path block matcher
**As a** developer, **I want** a pure function that maps a local block's position within its
siblings to the equivalent position in a freshly-parsed disk tree, **so that** the matching
logic is independently testable and has one explicit no-match outcome.
**Acceptance Criteria**:
- `buildBlockPath` returns the ordinal-index path (root to leaf) for a given block UUID, or
  `null` if the UUID isn't found in the local block list.
- `findAtPath` walks a `List<ParsedBlock>` tree by that path and returns `null` if any index is
  out of bounds at any depth (block deleted, split, or reordered above it — the explicit
  no-match case called out in `research/pitfalls.md`).
- **Post-review correction (2nd adversarial pass)**: an earlier draft of this plan only
  *documented* the same-count-reorder risk (KDoc + a test pinning the wrong match as "known
  behavior") without actually mitigating it — correctly flagged as an unresolved blocker, since a
  user could be shown confidently-wrong "disk version" content with zero visual signal, in the
  app's single most anxiety-inducing moment. Task 2.1.1d below adds a cheap, real mitigation using
  `Block.contentHash` (`model/Models.kt` L116), which already exists in the codebase for exactly
  this kind of identity check (`ContentHasher.sha256ForContent`, used the same way by
  `SidecarManager.kt` L46 and `MarkdownPageParser.kt` L165/225) — no new field, no new dependency.
- **Residual, still-documented limitation**: the Task 2.1.1d check only catches a *pure* reorder
  (the matched disk content's hash equals some *other* local sibling's last-known-good
  `contentHash`). It cannot detect a reorder combined with an edit to the transposed block's
  content on disk — that case still falls through to a positional "match" with no signal. A full
  fix for that residual case would need `SidecarManager`'s content-hash-based identity recovery
  (`GraphLoader.kt` L1733-1748) wired into `StelekitViewModel` — out of proportion to this
  project's scope; deferred, not silently dropped (see "Deliberately Deferred" section). This
  residual gap is narrower than the one the blocker was originally filed over.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DiskConflictBlockMatcher.kt` (new)

##### Task 2.1.1a: Create `DiskConflictBlockMatcher.kt` with `buildBlockPath` (~4 min)
- New file, `object DiskConflictBlockMatcher`.
- `fun buildBlockPath(allBlocks: List<Block>, targetUuid: String): List<Int>?` — walk up the
  `parentUuid` chain from `targetUuid` (`Block.parentUuid`, `model/Models.kt` L106); at each
  level, compute the target's index among blocks sharing that `parentUuid`,
  `sortedBy { it.position }` (mirrors `GraphWriter.kt` L574's sibling-sort pattern). Prepend each
  index as you walk up, so the final list reads root-to-leaf. Return `null` if `targetUuid` is
  not found in `allBlocks`.
- Files: new file `db/DiskConflictBlockMatcher.kt`

##### Task 2.1.1b: Add `findAtPath` (~3 min)
- `fun findAtPath(diskBlocks: List<ParsedBlock>, path: List<Int>): ParsedBlock?` — walk
  `ParsedBlock.children` (`model/ParsedModels.kt` L42) one path segment at a time, starting from
  `diskBlocks` as the root-level list; return `null` immediately if any segment index is out of
  bounds (`getOrNull`).
- Files: `db/DiskConflictBlockMatcher.kt`

##### Task 2.1.1c: Add `matchDiskBlockContent` convenience wrapper (~2 min)
- `fun matchDiskBlockContent(localBlocks: List<Block>, targetUuid: String, diskBlocks: List<ParsedBlock>): String?`
  — composes `buildBlockPath` then `findAtPath`, then applies the plausibility check from Task
  2.1.1d before returning `.content` or `null`.
- Add a KDoc comment on `matchDiskBlockContent` stating the residual limitation after Task
  2.1.1d's mitigation: this is an ordinal-position heuristic, not an identity match — a plausibility
  check catches a pure same-count reorder (content hash collides with a *different* known sibling),
  but cannot catch a reorder combined with a content edit at the transposed position. Point to Task
  6.1.3d's test for the now-mitigated pure-reorder case.
- Files: `db/DiskConflictBlockMatcher.kt`

##### Task 2.1.1d: Add a content-hash plausibility check against sibling misattribution (~5 min)
- **Resolves the 2nd-pass adversarial blocker**: "Ordinal-position block matcher's silent-wrong-match
  failure mode remains unmitigated." Uses `Block.contentHash` (`model/Models.kt` L116) and
  `ContentHasher.sha256ForContent(content)` (`db/SidecarManager.kt` L46's usage pattern) — both
  already exist, no new plumbing.
- Inside `matchDiskBlockContent`, after `findAtPath` returns a non-null `ParsedBlock` match:
  ```kotlin
  val matchedContent = matched.content
  val matchedHash = ContentHasher.sha256ForContent(matchedContent)
  val collidesWithADifferentSibling = localBlocks.any { sibling ->
      sibling.uuid.value != targetUuid && sibling.contentHash != null && sibling.contentHash == matchedHash
  }
  if (collidesWithADifferentSibling) {
      // The positionally-matched disk content's hash equals a DIFFERENT local block's
      // last-known-good content — strong evidence of a same-count reorder, not a genuine
      // match for targetUuid. Treat as no-match rather than show misattributed content.
      return null
  }
  return matchedContent
  ```
- This does not require the target block's own `contentHash` (it's expected to differ — that's
  the whole point of the conflict); it only compares the *matched* content's hash against *other*
  local siblings' historical hashes, which is exactly the "did I just grab someone else's block"
  check the reorder scenario calls for.
- **Known false-positive case (fail-safe, not a safety regression)**: duplicate-content siblings
  under the same parent (e.g. repeated checklist items, blank separators, template text — common
  in Logseq-style outlines) with *no actual reorder* will trigger a hash collision against each
  other and needlessly fall back to `null` (the honest "no match" UX) even though the positional
  match was correct. This never shows wrong content — it only over-triggers the existing fallback
  — so it's an acceptable, documented trade-off, not deferred silently.
- Files: `db/DiskConflictBlockMatcher.kt`

#### Story 2.1.2: Populate `DiskConflict.diskBlockContent` at both conflict-construction sites
**As a** user, **I want** the dialog's "Disk version" preview to show the same block I was
editing, not the whole file, **so that** I can actually compare it against "Your edit."
**Acceptance Criteria**:
- `DiskConflict` gains a `diskBlockContent: String?` field (`null` = no positional match found,
  including when disk-content parsing itself throws — see Task 2.1.2b/2.1.2c).
- Both places that construct a `DiskConflict` populate it using `DiskConflictBlockMatcher`.
- A malformed/unparseable on-disk file degrades to `diskBlockContent = null` (the existing
  Story 2.1.3 fallback UX) instead of throwing. `MarkdownParser.parsePage()` rethrows on parse
  failure by design (`parser/MarkdownParser.kt` L17-37), and one of the two call sites
  (`observeExternalFileChanges()`) is a **standing** collector inside `StelekitViewModel.scope` —
  an uncaught throwable there would permanently kill disk-conflict detection for the rest of the
  session (this repo's `CLAUDE.md`: "Uncaught coroutine Throwables kill the process on Android...
  Standing `collect {}` bodies... are the unguarded vectors").
**Files**: `AppState.kt`, `StelekitViewModel.kt`

##### Task 2.1.2a-pre: Add a `markdownParser` field to `StelekitViewModel` (~2 min)
- **Correction to an earlier draft of this plan**: `markdownParser` does **not** exist today on
  `StelekitViewModel` or `StelekitViewModelDependencies` (verified via grep — zero references
  outside `GraphLoader.kt`). It exists only as a private field inside `GraphLoader`
  (`db/GraphLoader.kt` L82: `private val markdownParser = MarkdownParser()`), which is not
  exposed through `GraphLoaderPort` (the interface type `StelekitViewModel.graphLoader` is
  actually typed as, `db/GraphLoaderPort.kt`). Following the originally-planned instruction
  ("inject it the same way `graphLoader` is") would be wrong on both counts and would require
  adding a new required constructor parameter, breaking all 17 existing call sites that construct
  `StelekitViewModelDependencies`.
- `MarkdownParser` (`parser/MarkdownParser.kt` L12: `class MarkdownParser { ... }`) takes **no
  constructor arguments** and is stateless, so no DI is needed at all — mirror `GraphLoader`'s own
  pattern exactly.
- Add `private val markdownParser = MarkdownParser()` as a class-level field on
  `StelekitViewModel` (near `private val logger = Logger("StelekitViewModel")`, `StelekitViewModel.kt`
  L166). Add the import `dev.stapler.stelekit.parser.MarkdownParser` if not already present.
- Files: `StelekitViewModel.kt`

##### Task 2.1.2a: Add `diskBlockContent` field to `DiskConflict` (~2 min)
- In `AppState.kt` L205-212, add `val diskBlockContent: String? = null` to the `DiskConflict`
  data class (default `null` keeps existing test fixtures / `diskConflict_model_has_all_fields`
  test in `DiskConflictResolutionTest.kt` compiling unchanged).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/AppState.kt`

##### Task 2.1.2b: Wire matcher into `observeExternalFileChanges()` (~6 min)
- In `StelekitViewModel.kt` L1439-1455, before constructing `DiskConflict`:
  1. **Source the local block list the same way `localContent` is already sourced two lines
     above (L1440-1444)** — prefer
     `blockStateManager?.blocks?.value?.get(currentPage.uuid.value)` first, falling back to
     `blockRepository.getBlocksForPage(currentPage.uuid).first().getOrNull() ?: emptyList()` only
     if that's null/empty. This dialog only fires when the four-tier protection trips (dirty
     blocks / pending disk write / pending actor write) — exactly the scenarios where the DB can
     lag behind `BlockStateManager`'s current optimistic tree shape, so sourcing the matcher's
     block list from a fresh DB read alone risks a path computed against stale position/parent
     data. Do not issue a second DB query if the in-memory source already has the page's blocks.
  2. Parse the disk content **inside a try/catch**, using the `markdownParser` field from Task
     2.1.2a-pre:
     ```kotlin
     val diskBlockContent = try {
         val diskBlocks = markdownParser.parsePage(event.content).blocks
         DiskConflictBlockMatcher.matchDiskBlockContent(localBlocks, conflictBlockUuid, diskBlocks)
     } catch (e: CancellationException) {
         throw e
     } catch (e: Exception) {
         logger.warn("Failed to parse disk content for block-scoped conflict preview: ${e.message}")
         null
     }
     ```
     `MarkdownParser.parsePage()` rethrows on malformed content by design — an uncaught exception
     here would kill this **standing** collector coroutine for the rest of the session (Story
     2.1.2's acceptance criteria). Treat a parse failure identically to a structural no-match:
     `diskBlockContent = null`, which Story 2.1.3's existing fallback copy already covers.
  3. Pass `diskBlockContent` in the `DiskConflict(...)` construction at L1447-1454.
- Files: `StelekitViewModel.kt`

##### Task 2.1.2c: Wire matcher into `checkAndShowPendingConflict()` (~5 min)
- In `StelekitViewModel.kt` L1467-1487, the block list is already fetched
  (`blockRepository.getBlocksForPage(screen.page.uuid).first().getOrNull()` at L1473-1474) and
  `firstBlock` is the match target — reuse that result (do not re-query) as both `firstBlock` and
  the matcher's `localBlocks` argument.
- Parse `latestDiskContent` (from Task 1.1.1b) and compute the match **inside the same try/catch
  shape as Task 2.1.2b**, using the `markdownParser` field from Task 2.1.2a-pre:
  ```kotlin
  val diskBlockContent = try {
      val diskBlocks = markdownParser.parsePage(latestDiskContent).blocks
      DiskConflictBlockMatcher.matchDiskBlockContent(allBlocksForPage, firstBlock?.uuid?.value ?: "", diskBlocks)
  } catch (e: CancellationException) {
      throw e
  } catch (e: Exception) {
      logger.warn("Failed to parse disk content for block-scoped conflict preview: ${e.message}")
      null
  }
  ```
  A failure here is narrower in blast radius than Task 2.1.2b (only this one conflict check
  fails), but must still degrade to `diskBlockContent = null` rather than surface a fatal error —
  an uncaught exception in this one-shot `scope.launch` would otherwise convert into a
  full-screen `fatalError` UI state via `StelekitViewModel.scope`'s `CoroutineExceptionHandler`,
  directly contradicting this project's anxiety-reduction goal.
- Pass `diskBlockContent` in the `DiskConflict(...)` construction at L1476-1483.
- Files: `StelekitViewModel.kt`

#### Story 2.1.3: Render the block-scoped preview with explicit fallback copy
**As a** user, **I want** to be told plainly when the app couldn't find a matching disk-side
section, **so that** I understand why I'm seeing the whole file instead of a targeted preview.
**Acceptance Criteria**:
- "Disk version" preview shows `diskBlockContent` when present.
- When `diskBlockContent` is `null`, a visible note explains the fallback before showing the
  full-file preview.
**Files**: `DiskConflictDialog.kt`

##### Task 2.1.3a: Update the "Disk version" preview block (~3 min)
- In `DiskConflictDialog.kt` L63-77, change the preview source from `conflict.diskContent` to
  `conflict.diskBlockContent ?: conflict.diskContent` (same `.take(200)` truncation logic for
  now — Phase 3 adds the "view full" escape hatch on top of this).
- When `conflict.diskBlockContent == null`, render an additional
  `Text("Could not find a matching section on disk — showing the full file.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)`
  directly above the preview `Surface`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/DiskConflictDialog.kt`

---

## Phase 2 (cont.): Replace `manualResolve()`'s Crude Heuristic

### Epic 2.2: Use the shared matcher result instead of the `.lines().firstOrNull{...}` heuristic
**Goal**: Eliminate the second, independent, cruder heuristic in `manualResolve()` so there is
exactly one block-matching strategy in the codebase for this feature.

Grounding: `manualResolve()` (`StelekitViewModel.kt` L1589-1590) currently does
`conflict.diskContent.lines().firstOrNull { it.startsWith("- ") }?.removePrefix("- ") ?: conflict.diskContent.take(200)`
— a heuristic unrelated to the position-matcher built in Epic 2.1, called out explicitly in
`research/architecture.md` as something to replace, not leave in place alongside the new one.

#### Story 2.2.1: Consume `conflict.diskBlockContent` in the conflict-marker text
**As a** user resolving manually, **I want** the disk-side marker section to show the same
matched block content as the dialog preview, **so that** the two surfaces agree.
**Acceptance Criteria**:
- `manualResolve()` no longer contains the `.lines().firstOrNull { it.startsWith("- ") }` logic.
- When no positional match exists, the marker text falls back to a truncated excerpt with an
  explicit inline note (this note is *data about the conflict*, not the "what markers mean"
  chrome — see Epic 4.1's constraint against concatenating explanatory UI text into saved
  content).
**Files**: `StelekitViewModel.kt`

##### Task 2.2.1a: Swap the heuristic for `diskBlockContent` (~3 min)
- In `StelekitViewModel.kt` L1589-1590, replace:
  ```kotlin
  append(conflict.diskContent.lines().firstOrNull { it.startsWith("- ") }
      ?.removePrefix("- ") ?: conflict.diskContent.take(200))
  ```
  with:
  ```kotlin
  append(conflict.diskBlockContent
      ?: "${conflict.diskContent.take(200)} (no matching section found — showing file excerpt)")
  ```
- Files: `StelekitViewModel.kt`

---

## Phase 3: "View Full" Escape Hatch with Line Diff (Gap #2)

### Epic 3.1: Full-screen diff view using `kotlin-multiplatform-diff`
**Goal**: Give the user a way to see the complete, untruncated local vs. disk block content as
a real line diff, reachable from the dialog before they must pick a resolution — per ADR-001, as
a separate full-screen route, not an inline dialog expansion.

#### Story 3.1.1: Wire the visibility flag and the dialog's "View full" entry point
**As a** user, **I want** a "View full comparison" option in the conflict dialog, **so that** I
can inspect the complete content before committing to a resolution.
**Acceptance Criteria**:
- A new dialog button opens the full-screen view without dismissing the underlying conflict
  *state* (`appState.diskConflict` stays non-null).
- While the full-screen view is visible, `DiskConflictDialog` does not render — Compose's
  `AlertDialog` opens in its own platform `Dialog`/window layer that always draws above regular
  composed content, so an ungated dialog would render on top of the full screen and make "View
  full comparison" appear to do nothing (see Task 3.1.1e).
- Closing the full-screen view returns to the still-open (and now re-rendered) `DiskConflictDialog`.
**Files**: `AppState.kt`, `StelekitViewModel.kt`, `DiskConflictDialog.kt`, `GraphDialogLayer.kt`

##### Task 3.1.1a: Add `diskConflictViewFullVisible` to `AppState` (~2 min)
- Add `val diskConflictViewFullVisible: Boolean = false` near `conflictResolutionVisible`
  (`AppState.kt` L157).
- Files: `AppState.kt`

##### Task 3.1.1b: Add show/hide functions to `StelekitViewModel` (~3 min)
- Mirror the existing `conflictResolutionVisible` toggle pattern
  (`StelekitViewModel.kt` L225 sets it true, L284 `dismissConflictResolution()` sets it false):
  add `fun showDiskConflictFullView() { _uiState.update { it.copy(diskConflictViewFullVisible = true) } }`
  and `fun hideDiskConflictFullView() { _uiState.update { it.copy(diskConflictViewFullVisible = false) } }`.
- Files: `StelekitViewModel.kt`

##### Task 3.1.1c: Add the "View full comparison" button to the dialog (~2 min)
- In `DiskConflictDialog.kt`, add a new `onViewFull: () -> Unit` parameter to the composable
  signature (alongside `onKeepLocal`, etc., L24-27), and a
  `TextButton(onClick = onViewFull) { Text("View full comparison") }` in the `confirmButton`
  Column, after the existing buttons (after L92, before the "Manual resolve" button at L93-95, so
  the non-destructive option reads before the resolution options).
- Files: `DiskConflictDialog.kt`

##### Task 3.1.1d: Wire the callback and render the full screen (~3 min)
- At `GraphDialogLayer.kt` L275-281, add `onViewFull = { viewModel.showDiskConflictFullView() }`
  to the `DiskConflictDialog(...)` call.
- Immediately after that block (after L282), add:
  ```kotlin
  if (appState.diskConflictViewFullVisible) {
      appState.diskConflict?.let { conflict ->
          DiskConflictFullScreen(
              localContent = conflict.localContent,
              diskContent = conflict.diskBlockContent ?: conflict.diskContent,
              onDismiss = { viewModel.hideDiskConflictFullView() },
          )
      }
  }
  ```
  (modeled on the `appState.conflictResolutionVisible` block at `GraphDialogLayer.kt` L215).
- Files: `GraphDialogLayer.kt`

##### Task 3.1.1e: Gate `DiskConflictDialog`'s rendering on `!diskConflictViewFullVisible` (~2 min)
- **This task exists because of a BLOCKER finding from three independent reviews** (adversarial,
  architecture, UX): Compose's `AlertDialog` (used by `DiskConflictDialog`) is implemented via
  `Dialog`, which opens a separate platform window/Popup that always draws **above** the
  underlying composition on every target platform (Android, Desktop, iOS/Web). Task 3.1.1d keeps
  `appState.diskConflict` non-null while `diskConflictViewFullVisible = true` (by design, so
  state survives the round trip) — but nothing in Task 3.1.1d stops `DiskConflictDialog` itself
  from continuing to render. Without this task, opening the full screen would show it composed
  *underneath* the still-showing `AlertDialog` and its modal scrim — "View full comparison" would
  appear to do nothing, or show a confusing double-scrim with no way to reach the full-screen
  content.
- At `GraphDialogLayer.kt` L274 (`appState.diskConflict?.let { conflict -> DiskConflictDialog(...) }`),
  change the guard so the dialog only renders when the full screen is *not* visible:
  ```kotlin
  if (!appState.diskConflictViewFullVisible) {
      appState.diskConflict?.let { conflict ->
          DiskConflictDialog(
              conflict = conflict,
              onKeepLocal = { viewModel.keepLocalChanges() },
              onUseDisk = { viewModel.acceptDiskVersion() },
              onSaveAsNew = { viewModel.saveAsNewBlock() },
              onManualResolve = { viewModel.manualResolve() },
              onViewFull = { viewModel.showDiskConflictFullView() },
          )
      }
  }
  ```
  `appState.diskConflict` itself is untouched — only the dialog's *rendering* is suppressed, so
  closing the full screen (`hideDiskConflictFullView()`) makes the `AlertDialog` reappear with the
  same conflict state intact, matching ADR-001's "closing the full-screen view returns to the
  still-open `DiskConflictDialog`" language (state, not continuous rendering).
- This also resolves the architecture review's concern that "the new full-screen view and the
  existing `AlertDialog` are not mutually exclusive in composition" — confirmed resolved by this
  task; no separate action needed for that concern.
- Out of scope for this task (left as a documented residual gap, not a blocker): an automated
  Compose/screenshot test asserting the two surfaces are mutually exclusive in a real render pass.
  This repo's screenshot tests (Roborazzi) remain Gradle-only per `CLAUDE.md` and are out of this
  project's test-tier scope; verify this manually (or via `bazel run //kmp:desktop_app`) before
  shipping.
- Files: `GraphDialogLayer.kt`

#### Story 3.1.2: Build the diff-rendering full-screen composable
**As a** user, **I want** an add/remove/unchanged line diff of my edit vs. the disk version,
**so that** I can see exactly what differs instead of guessing from two truncated blobs.
**Acceptance Criteria**:
- Uses `DiffUtils.diff()` from the already-declared `kotlin-multiplatform-diff` dependency
  (`kmp/build.gradle.kts` L83).
- Gracefully handles the identical-content case (ShadowFlushActor mtime race,
  `research/pitfalls.md`) with a "no differences" message instead of an empty/confusing diff.
- Preserves the existing blank-`localContent` special case
  (`DiskConflictDialog.kt` L45, L88: `conflict.localContent.isNotBlank()`).
- The diff-state computation is a pure function, independently testable without Compose.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/DiskConflictFullScreen.kt` (new)

##### Task 3.1.2a: Scaffold shell (~4 min)
- New file. `@Composable fun DiskConflictFullScreen(localContent: String, diskContent: String, onDismiss: () -> Unit)`.
- `Scaffold` with a `TopAppBar` (title "Compare versions", a back/close `IconButton` wired to
  `onDismiss`) — model directly on `ConflictResolutionScreen.kt`'s `Scaffold` usage
  (`ui/screens/git/ConflictResolutionScreen.kt` L72).
- **Triad-review copy fold-in (UX lens)**: give the `TopAppBar` a subtitle/second line —
  `"Closing returns to the conflict dialog"` — so the user isn't left guessing whether closing
  this screen exits the whole flow or resolves anything; it does neither, it's read-only.
- Give the close `IconButton`'s `Icon` an explicit `contentDescription = "Close comparison"` (or
  similar) — every other meaningful icon in this app sets one; a screen-reader user must be able
  to tell what the button does.
- **Triad-review blocker fix (UX lens)**: add `BackHandler(enabled = true) { onDismiss() }`
  (Compose's platform back-gesture intercept, already used elsewhere in this codebase for
  full-screen routes — check `ConflictResolutionScreen.kt` / `AnnotationEditorScreen.kt` for the
  exact import/existing pattern to mirror) at the top of the composable body. Without this, the
  Android system back button/predictive-back gesture bypasses the `IconButton`'s `onDismiss` path
  entirely and can leave `AppState.diskConflictViewFullVisible` stuck `true` while the screen
  disappears — silently breaking the "closing returns to the still-open dialog" guarantee this
  whole feature depends on (ADR-001).
- Files: new file `ui/screens/DiskConflictFullScreen.kt`

##### Task 3.1.2b: Pure diff-state computation (~4 min)
- Above the composable, add a sealed result type and pure function so the logic is unit-testable:
  ```kotlin
  sealed class DiskDiffState {
      data object NoLocalEdit : DiskDiffState()          // localContent is blank
      data object Identical : DiskDiffState()             // localContent == diskContent
      data class Different(val patch: Patch<String>) : DiskDiffState()
  }
  fun computeDiskDiffState(localContent: String, diskContent: String): DiskDiffState = when {
      localContent.isBlank() -> DiskDiffState.NoLocalEdit
      localContent == diskContent -> DiskDiffState.Identical
      else -> DiskDiffState.Different(DiffUtils.diff(localContent.lines(), diskContent.lines()))
  }
  ```
  (`DiffUtils`/`Patch` from `io.github.petertrr.diffutils` — verify the exact package name
  against the `io.github.petertrr:kotlin-multiplatform-diff:1.3.0` artifact when implementing;
  research confirms the public API shape is `DiffUtils.diff(original, revised) -> Patch<T>`.)
- **Triad-review perf fold-in (Engineering lens)**: call this via
  `val diffState = remember(localContent, diskContent) { computeDiskDiffState(localContent, diskContent) }`
  inside the composable, not as a bare call in the composable body — otherwise the LCS diff
  recomputes on every recomposition (e.g. a theme/config change) instead of only when the actual
  content inputs change, wasteful against the whole-file fallback case in particular.
- Files: `ui/screens/DiskConflictFullScreen.kt`

##### Task 3.1.2c: Render `Identical` and `NoLocalEdit` states (~3 min)
- `Identical`: render a centered `Text("No differences — the disk version matches your edit.")`.
  This is the direct mitigation for the `ShadowFlushActor` mtime-race spurious-conflict case
  (`research/pitfalls.md`) — don't assume divergence just because the dialog is open.
- `NoLocalEdit`: render only the disk content pane with a label
  `Text("(no local edit to compare)")`, mirroring the existing blank-content special-case in
  `DiskConflictDialog.kt` L45, L88.
- Files: `ui/screens/DiskConflictFullScreen.kt`

##### Task 3.1.2d: Render the `Different` line diff (~4 min)
- Iterate `patch.deltas`; for each delta render the source lines with a
  removed-line background (e.g. `errorContainer` alpha) and target lines with an added-line
  background (e.g. `primaryContainer` alpha); lines outside any delta render with no highlight.
  Use `LazyColumn` (not a single `Text`) so large files don't force one synchronous layout pass.
- **Triad-review accessibility fold-in (UX lens, WCAG 1.4.1)**: don't distinguish added/removed
  lines by background color alone — prefix each rendered line with a leading `"+ "` / `"- "` /
  `"  "` marker (mirroring the plain-text convention every unified-diff-literate and
  screen-reader-only user already recognizes), in addition to the background tint. Color becomes
  a reinforcing cue, not the sole signal.
- Files: `ui/screens/DiskConflictFullScreen.kt`

#### Story 3.1.3: Confirm the call site passes block-scoped content by default
**As a** user, **I want** "view full" to default to the same block-scoped comparison as the
dialog preview (falling back to the whole file only when no match was found), **so that** the
two views stay consistent.
**Acceptance Criteria**: `DiskConflictFullScreen`'s `diskContent` param uses the same
`diskBlockContent ?: diskContent` fallback as the dialog preview (Task 2.1.3a).
**Files**: `GraphDialogLayer.kt`

##### Task 3.1.3a: Verify/align the fallback expression (~2 min)
- Confirm the wiring added in Task 3.1.1d already uses
  `diskContent = conflict.diskBlockContent ?: conflict.diskContent` — this task is a checkpoint,
  not new code, to keep Epic 2's and Epic 3's fallback semantics from silently diverging as both
  are implemented.
- Files: `GraphDialogLayer.kt`

---

## Phase 4: Manual Resolve Inline Help (Gap #3)

### Epic 4.1: Explain what the conflict markers mean without polluting saved content
**Goal**: Users choosing "Manual resolve" understand what the injected `<<<<<<<`/`=======`/`>>>>>>>`
markers mean and that the page won't re-sync until they're removed — without that explanation
ever becoming part of the persisted block content.

Grounding: `manualResolve()` (`StelekitViewModel.kt` L1574-1602) builds one `buildString{}` that
becomes the literal saved block content via `saveBlock(...)`. `GraphLoader.parseAndSavePage` has
a real, load-bearing guard: `ConflictMarkerDetector.hasConflictMarkers(content)` blocks reimport
of any file containing markers (`GraphLoader.kt` L1684-1693) — this is exactly what
`manualResolve()`'s output triggers until markers are removed. The explanation must be separate
Compose UI chrome, never concatenated into the `buildString{}`.

#### Story 4.1.1: Static explanatory text in the dialog, before the action
**As a** user considering "Manual resolve," **I want** to know upfront what it does, **so that**
I'm not surprised by conflict markers appearing in my note.
**Acceptance Criteria**: A caption near the "Manual resolve" button explains the markers and the
re-sync-blocking behavior, using plain Compose `Text` — never touching `conflict.localContent`
or `conflict.diskContent`.
**Files**: `DiskConflictDialog.kt`

##### Task 4.1.1a: Add the caption (~3 min)
- In `DiskConflictDialog.kt`, directly below the "Manual resolve" `TextButton` (after L95),
  add:
  ```kotlin
  Text(
      "Inserts <<<<<<< / ======= / >>>>>>> markers into this block for you to edit by hand. " +
          "This page won't sync with disk again until the markers are removed.",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  ```
- Files: `DiskConflictDialog.kt`

#### Story 4.1.2: Post-action confirmation, reusing `ConflictMarkerDetector`
**As a** user who just triggered manual resolve, **I want** a confirmation reminding me the
markers need removal, **so that** I don't lose the context after the dialog closes and I'm now
looking at the raw block editor.
**Acceptance Criteria**: A snackbar fires after markers are written, gated on
`ConflictMarkerDetector.hasConflictMarkers()` actually finding them (reused, not reimplemented).
**Files**: `StelekitViewModel.kt`

##### Task 4.1.2a: Add the post-write snackbar (~3 min)
- In `manualResolve()`'s `scope.launch` block, after `requestEditBlock(conflict.editingBlockUuid, 0)`
  (`StelekitViewModel.kt` L1600), add:
  ```kotlin
  if (ConflictMarkerDetector.hasConflictMarkers(updatedBlock.content)) {
      sendSnackbar("Conflict markers inserted — remove <<<<<<<, =======, >>>>>>> to let \"${conflict.pageName}\" sync again")
  }
  ```
  (`sendSnackbar` already used for the pending-conflict notice at L1392;
  `ConflictMarkerDetector.hasConflictMarkers` is `db/ConflictMarkerDetector.kt` L29 — add the
  import `dev.stapler.stelekit.db.ConflictMarkerDetector` if not already present in this file).
- Files: `StelekitViewModel.kt`

---

## Phase 5: Persistent Sidebar Indicator (Gap #4)

### Epic 5.1: Thread `hasPendingConflict` through the sidebar
**Goal**: A page with an unresolved deferred conflict shows a persistent, non-dismissible
(until resolved) indicator in the sidebar — replacing the one-shot snackbar-only signal. This is
a two-tier approach: a per-row marker for pages already visible in Favorites/Recent (Story 5.1.1)
plus an always-visible fallback count badge (Story 5.1.4) so a conflict on a page outside both
lists is never left with zero indicator anywhere in the app — see ADR-003.

Grounding: `Sidebar.kt`'s `LeftSidebar()` (L59) and `SidebarItem()` (L544) are the only wired
page-list UI (favorites + recent). `SyncStatusBadge.kt`'s `ConflictPending` state
(L157-169, amber `Icons.Default.Warning`, `Color(0xFFF59E0B)`) is the closest visual analog —
reuse the *treatment*, but keep it a **separate** indicator from that git-merge-conflict badge
(per the VS Code pitfall in `research/features.md`: don't conflate two different conflict-type
counts/badges).

#### Story 5.1.1: Render the indicator on `SidebarItem`
**As a** user browsing the sidebar, **I want** to see which pages have an unresolved conflict,
**so that** I don't have to remember or rediscover it via a dismissed snackbar.
**Acceptance Criteria**: `SidebarItem` accepts `hasPendingConflict: Boolean` and renders a small
amber warning icon when true, visually distinct from the favorite star.
**Files**: `Sidebar.kt`

##### Task 5.1.1a: Add the param and render the icon (~3 min)
- In `SidebarItem` (`Sidebar.kt` L544-551), add `hasPendingConflict: Boolean = false` to the
  parameter list.
- In the `Row` (L560-588), add a small
  `Icon(imageVector = Icons.Default.Warning, contentDescription = "Unresolved disk conflict", tint = Color(0xFFF59E0B), modifier = Modifier.size(14.dp))`
  between the title `Text` (L571-576) and the favorite `IconButton` (L577-587), shown only when
  `hasPendingConflict` is true. Do not touch or reuse `SyncStatusBadge`'s composable — this is a
  visually-similar but functionally separate indicator. The explicit `contentDescription` (unlike
  the decorative icons elsewhere in this row) matters here because, unlike the favorite star, this
  icon has no adjacent text label — without it, screen-reader users tabbing through the sidebar
  get no announcement that a row has a pending conflict.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt`

#### Story 5.1.2: Thread the file-path set through `LeftSidebar`
**As a** developer, **I want** `LeftSidebar` to know which pages currently have a pending
conflict, **so that** it can pass the right flag to each `SidebarItem`.
**Acceptance Criteria**: `LeftSidebar` accepts `pendingConflictFilePaths: Set<String>` and both
existing `SidebarItem` call sites (favorites, recent) pass the correct per-page boolean.
**Files**: `Sidebar.kt`

##### Task 5.1.2a: Add the param to `LeftSidebar` (~2 min)
- Add `pendingConflictFilePaths: Set<String> = emptySet()` to `LeftSidebar`'s parameter list
  (`Sidebar.kt` L59-87), placed near `activeGraphId`.
- Files: `Sidebar.kt`

##### Task 5.1.2b: Pass `hasPendingConflict` at both call sites (~3 min)
- Favorites loop (`Sidebar.kt` L210-218): add
  `hasPendingConflict = page.filePath in pendingConflictFilePaths` to the `SidebarItem(...)` call.
- Recent loop (`Sidebar.kt` L231-239): same addition.
- Files: `Sidebar.kt`

#### Story 5.1.3: Source the set at the `App.kt` call site
**As a** user, **I want** the indicator to cover both deferred conflicts and the conflict whose
dialog is currently open, **so that** the sidebar signal is never momentarily wrong while a
dialog is up.
**Acceptance Criteria**: `LeftSidebar(...)` in `App.kt` passes
`appState.pendingConflicts.keys + listOfNotNull(appState.diskConflict?.filePath)`.
**Files**: `App.kt`

##### Task 5.1.3a: Wire the set (~2 min)
- At the `LeftSidebar(...)` call in `App.kt` L1299-1330, add:
  `pendingConflictFilePaths = appState.pendingConflicts.keys + listOfNotNull(appState.diskConflict?.filePath),`
- This is an in-memory O(visible pages) set computation — does not violate the no-unbounded-reads
  rule (no new query).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

#### Story 5.1.4: Always-visible fallback badge for conflicts outside Favorites/Recent
**BLOCKER fix** (UX + Adversarial reviews). **Goal**: guarantee every page with a pending conflict
has *some* persistent indicator, not just favorited/recently-visited ones. See
ADR-003 for the full reasoning.

Grounding: Story 5.1.1/5.1.2 only wire `hasPendingConflict` into `SidebarItem` at the two loops
in `LeftSidebar` — Favorites (`Sidebar.kt` L210-218) and Recent (`Sidebar.kt` L231-239). A page
with a `pendingConflicts` entry that is neither favorited nor within the bounded Recent window
(the common case — most pages aren't favorited or recently visited, and a conflict can originate
from a tier such as a pending-actor-write on a page the user hasn't opened in a while) gets zero
visual indicator anywhere in the app under Story 5.1.1/5.1.2 alone. Per the architecture research
(`research/architecture.md`), `AppState.regularPages` (paginated all-pages list, `AppState.kt`
L113-115) exists but has no consuming screen today, so it is not a usable indicator surface as-is.
Building it into one, or building a full conflicts list/inbox, is explicitly out of this
project's scope (see "Deliberately Deferred"). The minimal fix is a small, always-visible
**count** badge in existing sidebar chrome — independent of favorites/recency — that the user can
click to reach the existing "All Pages" screen (`Screen.AllPages`, already wired via
`LeftSidebar`'s `onNavigate` callback), where they can find and open the affected page.

**As a** user with a pending conflict on a page that isn't favorited or recently visited, **I
want** some persistent signal that a conflict exists, **so that** I don't have zero way to
discover it besides re-triggering the four-tier protection check by accident.
**Acceptance Criteria**:
- A small badge/row renders in the sidebar's always-visible chrome (not gated by Favorites/Recent
  membership) whenever `pendingConflictFilePaths.isNotEmpty()`, showing the count.
- Clicking it navigates to `Screen.AllPages` using the existing `onNavigate` callback already
  passed into `LeftSidebar` — no new navigation callback or screen is added (matches the
  "Deliberately Deferred" scope boundary against building a full Conflicts inbox).
- The badge's icon has a `contentDescription`.
**Files**: `Sidebar.kt`

##### Task 5.1.4a: Add a `PendingConflictsBanner` composable (~3 min)
- In `Sidebar.kt`, add:
  ```kotlin
  @Composable
  fun PendingConflictsBanner(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
      Surface(
          color = Color(0xFFF59E0B).copy(alpha = 0.15f),
          shape = MaterialTheme.shapes.small,
          modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
      ) {
          Row(
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
              Icon(
                  imageVector = Icons.Default.Warning,
                  contentDescription = "Unresolved disk conflicts",
                  modifier = Modifier.size(16.dp),
                  tint = Color(0xFFF59E0B),
              )
              Spacer(modifier = Modifier.width(8.dp))
              Column {
                  Text(
                      text = if (count == 1) "1 page has an unresolved conflict" else "$count pages have unresolved conflicts",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Text(
                      text = "Tap to view pages · cleared on app restart",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                  )
              }
          }
      }
  }
  ```
  **Triad-review fold-in (Product + UX lenses)**: the second line makes ADR-002's session-scoped
  decision honest in the UI itself, not just in the ADR — the original UX review flagged that
  ADR-002's own stated concern ("avoid copy like 'will remind you next time'") was never actually
  reflected in any planned copy; this closes that gap with the opposite, accurate framing (does
  *not* imply durability it doesn't have).
  Reuses the same amber `Color(0xFFF59E0B)` treatment as Task 5.1.1a's per-row icon (same
  conflict type, consistent color — distinct from `SyncStatusBadge`'s unrelated git-merge-conflict
  amber, per the existing plan note, since this is a different composable in a different part of
  the sidebar, not the shared badge component itself).
  **Known, accepted trade-off (Engineering + UX triad review)**: clicking through navigates to the
  existing, unfiltered `Screen.AllPages` — it does not highlight *which* listed pages have the
  conflict, since `AllPages` has no per-row indicator slot today and wiring one is out of this
  project's proportionate scope (see "Deliberately Deferred"). This still strictly improves on the
  current zero-signal state for non-favorited/non-recent pages; a filtered/highlighted view is a
  natural follow-up, not required to close Gap #4 as scoped.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt`

##### Task 5.1.4b: Render the banner in `LeftSidebar`, wired to the existing set and `onNavigate` (~3 min)
- In `LeftSidebar` (`Sidebar.kt`), immediately after the `SyncStatusBadge(...)` call (L146-152)
  and before the "Navigation" section header (L155-160), add:
  ```kotlin
  if (pendingConflictFilePaths.isNotEmpty()) {
      PendingConflictsBanner(
          count = pendingConflictFilePaths.size,
          onClick = { onNavigate(Screen.AllPages) },
          modifier = Modifier.padding(vertical = 4.dp),
      )
  }
  ```
  Both `pendingConflictFilePaths` (Task 5.1.2a) and `onNavigate` are already `LeftSidebar`
  parameters — no new prop threading through `App.kt` is needed beyond what Task 5.1.3a already
  wires.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/Sidebar.kt`

---

## Phase 6: Regression Tests

### Epic 6.1: Cover the lifecycle fix, block matching, and diff edge cases
**Goal**: Lock in the three load-bearing behaviors this project introduces: (a)
`pendingConflicts` lifecycle correctness, (b) block-position-matching fallback correctness, (c)
graceful handling of the spurious-identical-content and blank-content diff states.

#### Story 6.1.1: `pendingConflicts` lifecycle — survives navigation, cleared only on resolution
**As a** developer, **I want** regression coverage for the Phase 1 fix, **so that** the
early-clear bug cannot silently regress.
**Acceptance Criteria**: Tests use the real `GraphLoader.emitExternalFileChange(filePath, content)`
test seam (`GraphLoader.kt` L422-424) to drive `observeExternalFileChanges()`, matching the
existing `makeViewModel()` fixture pattern in `DiskConflictResolutionTest.kt` (L61-94, which uses
`Dispatchers.Unconfined` so `scope.launch` bodies run eagerly — no `advanceUntilIdle()` needed).
**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/DiskConflictResolutionTest.kt`

##### Task 6.1.1a-pre: Expose the `GraphLoader` instance from `makeViewModel()` (~2 min)
- `makeViewModel()` (`DiskConflictResolutionTest.kt` L61-94) currently constructs `graphLoader`
  as a local `val` and returns only the `StelekitViewModel`. Change it to accept
  `graphLoader: GraphLoader = GraphLoader(FakeFileSystem(), pageRepo, blockRepo)` as a parameter
  (same pattern already used for `pageRepo`/`blockRepo`) so tests can construct it outside the
  function, hold a reference, and pass it in — mirroring how `pageRepo`/`blockRepo` are already
  handled as test-visible parameters.
- Files: `DiskConflictResolutionTest.kt`

##### Task 6.1.1a: Test pending conflict is created and survives navigation (~4 min)
- New test: construct a `GraphLoader` locally, pass it into `makeViewModel(graphLoader = ...)`,
  then with the viewmodel on a screen other than `testPage`'s `PageView` (the default
  `AppState.currentScreen`), call `graphLoader.emitExternalFileChange(testFilePath, "- disk content")`
  (the public test seam at `GraphLoader.kt` L422-424).
  Assert `vm.uiState.value.pendingConflicts[testFilePath]` is non-null.
- Then `vm.navigateTo(Screen.PageView(testPage))`. Assert `vm.uiState.value.diskConflict` is
  non-null AND `vm.uiState.value.pendingConflicts[testFilePath]` is **still** non-null (the
  Task 1.1.1a fix — this is the core regression assertion).
- Files: `DiskConflictResolutionTest.kt`

##### Task 6.1.1b: Test `keepLocalChanges()` clears the entry (~3 min)
- Continue from the state in 6.1.1a (or repeat setup); call `vm.keepLocalChanges()`; assert
  `vm.uiState.value.pendingConflicts[testFilePath]` is now `null`.
- Files: `DiskConflictResolutionTest.kt`

##### Task 6.1.1c: Test `acceptDiskVersion()` clears the entry (~3 min)
- Same shape as 6.1.1b, calling `vm.acceptDiskVersion()`.
- Files: `DiskConflictResolutionTest.kt`

##### Task 6.1.1d: Test `saveAsNewBlock()` clears the entry (~3 min)
- Same shape, calling `vm.saveAsNewBlock()`.
- Files: `DiskConflictResolutionTest.kt`

##### Task 6.1.1e: Test `manualResolve()` clears the entry (both branches) (~4 min)
- One test with a valid `editingBlockUuid` (main branch, async clear); one test forcing the
  early-return branch (empty `editingBlockUuid` — construct the pending/disk-conflict path such
  that `firstBlock` is null, e.g. an empty-block page) to confirm the synchronous clear also
  fires.
- Files: `DiskConflictResolutionTest.kt`

#### Story 6.1.1b: Close 4 coverage gaps found by validation review (~4 requirement-relevant scenarios)
**As a** developer, **I want** regression coverage for wiring/state logic that the original Phase
6 draft's isolation-focused tests missed, **so that** a DI/plumbing regression in the ViewModel
layer can't slip past every existing test while shipping broken.
**Acceptance Criteria**: each of the 4 gaps below (found by `implementation/validation.md`'s
requirement-to-test audit) has a corresponding test using the existing `makeViewModel()` /
`emitExternalFileChange` fixtures — no new test infrastructure.
**Files**: `DiskConflictResolutionTest.kt`

##### Task 6.1.1f: Test `diskBlockContent` wiring on the happy path (~4 min)
- **Closes REQ-1 gap**: Phase 6 as originally drafted only tested `DiskConflictBlockMatcher` in
  isolation (Story 6.1.3) and the *failure* path through the live ViewModel (Story 6.1.5) — nothing
  asserted the **success** path through `observeExternalFileChanges()`/`checkAndShowPendingConflict()`
  (Tasks 2.1.2b/2.1.2c) actually produces a correct, non-null `diskBlockContent`.
- Using the test-local `graphLoader` (Task 6.1.1a-pre), emit an external change whose content
  parses into blocks matching the local page's structure at the conflicting block's position.
  Assert `vm.uiState.value.diskConflict?.diskBlockContent` is non-null and equals the expected
  matched block's content — not just that the pure matcher returns the right value in isolation.
- Files: `DiskConflictResolutionTest.kt`

##### Task 6.1.1g: Test "view full" state-machine reachability (~4 min)
- **Closes REQ-2 gap**: Story 3.1.1's acceptance criteria ("a new dialog button opens the
  full-screen view without dismissing the underlying conflict") has zero test coverage — Phase 6
  as drafted only covers `computeDiskDiffState`'s rendering logic (Story 6.1.4), never the
  `AppState.diskConflictViewFullVisible` toggle itself.
- Two tests: (1) `vm.showDiskConflictFullView()` sets `diskConflictViewFullVisible = true` while
  `vm.uiState.value.diskConflict` remains unchanged/non-null; (2) `vm.hideDiskConflictFullView()`
  clears the flag back to `false` while `diskConflict` is still the same, non-null value as before
  — i.e. closing "view full" returns to the still-open conflict, never resolving it as a side
  effect.
- Files: `DiskConflictResolutionTest.kt`

##### Task 6.1.1h: Test manual-resolve snackbar gate (~4 min)
- **Closes REQ-3 gap**: Task 4.1.2a's `if (ConflictMarkerDetector.hasConflictMarkers(...))
  sendSnackbar(...)` gate has real conditional logic untested elsewhere. Not mandated by
  requirements.md's Success Metrics, but cheap and covers the one piece of Gap #3 with actual
  branching.
- Two tests using the existing `snackbarEvents` `Flow` (same pattern other snackbar call sites in
  this test file already use): (1) main branch of `manualResolve()` (markers written) emits a
  snackbar naming the page; (2) early-return branch (no `editingBlockUuid`, no markers ever
  written) emits **no** snackbar — confirms the gate isn't accidentally unconditional.
- Files: `DiskConflictResolutionTest.kt`

##### Task 6.1.1i: Test `pendingConflictFilePaths` set union (~3 min)
- **Closes REQ-4 gap**: Task 5.1.3a's derived set
  (`appState.pendingConflicts.keys + listOfNotNull(appState.diskConflict?.filePath)`) is the
  exact computation driving the sidebar indicator, but Story 6.1.1 only proves `pendingConflicts`
  and `diskConflict` are each individually correct — never their union.
- Construct a state with one deferred conflict (a different page's `PendingConflict` entry in
  `pendingConflicts`) **and** one currently-open conflict (`diskConflict` on the page just
  navigated to) coexisting simultaneously. Assert the derived set (compute it the same way
  `App.kt`'s call site does, reading `vm.uiState.value` directly — no new fixture) contains both
  file paths.
- Files: `DiskConflictResolutionTest.kt`

#### Story 6.1.2: Stale-content re-validation
**As a** developer, **I want** regression coverage for Task 1.1.1b, **so that** a second
external change arriving during the async window is never silently dropped in favor of a stale
snapshot.
**Acceptance Criteria**: Emitting two external changes for the same pending file before
navigating results in a `DiskConflict.diskContent` reflecting the second (latest) emission.
**Files**: `DiskConflictResolutionTest.kt`

##### Task 6.1.2a: Test latest-content wins (~4 min)
- **Correction to an earlier draft of this task**: `vm.graphLoader.emitExternalFileChange(...)`
  does not compile — `StelekitViewModel.graphLoader` is `private` and statically typed as
  `GraphLoaderPort` (`StelekitViewModel.kt` L117: `private val graphLoader: GraphLoaderPort = deps.graphLoader`),
  and `GraphLoaderPort` does not declare `emitExternalFileChange` (only the concrete `GraphLoader`
  class does, `db/GraphLoader.kt` L422). Use the **test-local `graphLoader` reference** introduced
  by Task 6.1.1a-pre instead (the same `val` passed into `makeViewModel(graphLoader = ...)`), not
  a field read off `vm`.
- `graphLoader.emitExternalFileChange(testFilePath, "- first version")`, then
  `graphLoader.emitExternalFileChange(testFilePath, "- second version")` (both while not on
  `testPage`, using the local `graphLoader` val — not `vm.graphLoader`), then
  `vm.navigateTo(Screen.PageView(testPage))`. Assert
  `vm.uiState.value.diskConflict?.diskContent == "- second version"`.
- Files: `DiskConflictResolutionTest.kt`

#### Story 6.1.3: Block-position-matching correctness and fallback
**As a** developer, **I want** unit coverage of `DiskConflictBlockMatcher` independent of the DB
and Compose, **so that** the position-matching heuristic's documented limitations (block
deleted/split above the target) are locked in as explicit, tested behavior rather than silent
misattribution.
**Acceptance Criteria**: Matcher returns correct content on stable position, `null` on structural
change above the target, and correct content for a nested (non-root-level) block.
**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/db/DiskConflictBlockMatcherTest.kt` (new)

##### Task 6.1.3a: Test successful match at stable position (~3 min)
- Construct a small `List<Block>` (root-level, 3 siblings, same `parentUuid = null`, ordered by
  `position`) and a parallel `List<ParsedBlock>` with matching structure; assert
  `matchDiskBlockContent` returns the expected content for the middle block.
- Files: new test file

##### Task 6.1.3b: Test `null` fallback when a block was deleted above the target (~3 min)
- Same local `List<Block>` as above, but the disk-side `List<ParsedBlock>` has one fewer block
  before the target's position (simulating a deletion). Assert `matchDiskBlockContent` returns
  `null` — the explicit no-match case from `research/pitfalls.md`, not a misattributed match.
- Files: new test file

##### Task 6.1.3c: Test nested (non-root) block path (~3 min)
- Construct a local block with `parentUuid` pointing at another local block, and a disk-side
  `ParsedBlock` tree with a matching parent/child shape; assert the path has length 2 and
  `matchDiskBlockContent` returns the nested child's content.
- Files: new test file

##### Task 6.1.3d: Test the content-hash plausibility check catches a pure sibling reorder (~4 min)
- **Resolves the 2nd-pass adversarial blocker** (Task 2.1.1d's mitigation): the ordinal-position
  matcher alone can silently return the *wrong* sibling's content when two same-level siblings are
  transposed on disk without any block being added or removed. Task 6.1.3b only exercised the
  count-*changed* case (a deletion); this test exercises the count-*unchanged*, order-changed case.
- Construct a local `List<Block>` with 2+ root-level siblings (stable `position` order), each with
  a non-null `contentHash` set to `ContentHasher.sha256ForContent(...)` of its own content (as it
  would be after a real save). Construct a disk-side `List<ParsedBlock>` with the **same count**
  but the first two transposed (same content set, swapped order, no addition/removal). Assert
  `matchDiskBlockContent` now returns **`null`** — the plausibility check in Task 2.1.1d detects
  that the positionally-matched content's hash collides with a *different* sibling's known
  `contentHash` and refuses the match, falling back to the existing "no match" UX (Story 2.1.3)
  instead of silently showing misattributed content.
- Add a second case in the same test: siblings with `contentHash == null` (never-saved blocks) —
  confirm the plausibility check degrades gracefully (no crash, no false-positive collision against
  `null == null`) and simply returns the positional match as before, since there's no historical
  hash to compare against.
- Files: new test file (`DiskConflictBlockMatcherTest.kt`)

#### Story 6.1.4: Diff-state edge cases (identical content, blank local content)
**As a** developer, **I want** unit coverage of `computeDiskDiffState` independent of Compose,
**so that** the spurious-identical-content case (`ShadowFlushActor` mtime race) is guaranteed to
render gracefully rather than an empty/confusing diff.
**Acceptance Criteria**: `computeDiskDiffState` returns `Identical` when contents match exactly,
`NoLocalEdit` when local is blank, and `Different` with a non-empty patch otherwise.
**Files**: a new test file colocated with `DiskConflictFullScreen.kt`'s test tier — place in
`kmp/src/businessTest/kotlin/dev/stapler/stelekit/ui/screens/DiskConflictFullScreenStateTest.kt` (new)
(pure function, no Compose dependency, so `businessTest` is appropriate per this repo's test
source-set conventions).

##### Task 6.1.4a: Test `Identical` and `NoLocalEdit` states (~3 min)
- `computeDiskDiffState("- same", "- same")` → `DiskDiffState.Identical`.
- `computeDiskDiffState("", "- disk content")` → `DiskDiffState.NoLocalEdit`.
- Files: new test file

##### Task 6.1.4b: Test `Different` state carries a non-empty patch (~2 min)
- `computeDiskDiffState("- local", "- disk")` → `DiskDiffState.Different` with
  `patch.deltas.isNotEmpty()`.
- Files: new test file

#### Story 6.1.5: `observeExternalFileChanges()` survives malformed disk content
**BLOCKER-adjacent regression coverage** (adversarial + architecture reviews) for Task 2.1.2b's
try/catch guard. **As a** developer, **I want** proof that a single malformed external file
cannot permanently disable disk-conflict detection for the rest of the session, **so that** the
standing-collector exception-safety fix (Task 2.1.2b) cannot silently regress.
**Acceptance Criteria**: Feeding `observeExternalFileChanges()` content that makes
`markdownParser.parsePage(...)` throw does not kill the collector — a subsequent, valid external
file change for a different (or the same) file is still processed correctly afterward.
**Files**: `DiskConflictResolutionTest.kt`

##### Task 6.1.5a: Test the collector survives a parse failure (~4 min)
- Using the test-local `graphLoader` reference (Task 6.1.1a-pre), emit an external file change
  whose content is crafted to make `MarkdownParser.parsePage()` throw (or, if constructing
  genuinely malformed Markdown that throws is impractical, this test may instead need to be
  written against `DiskConflictBlockMatcher`/`markdownParser.parsePage` directly to confirm the
  exception shape, then assert the try/catch in Task 2.1.2b converts it to `diskBlockContent =
  null` without the exception propagating out of `collect { }` — check `MarkdownParser.parsePage`'s
  actual throw conditions, `parser/MarkdownParser.kt` L17-37, when implementing).
- Assert the resulting `DiskConflict`/`PendingConflict` is still created (with `diskBlockContent
  == null`, not a crash), and that emitting one more, well-formed change afterward is still
  processed normally (proving the collector coroutine is still alive).
- Files: `DiskConflictResolutionTest.kt`

---

## Deliberately Deferred / Out of Scope

Per `requirements.md`'s explicit scope boundaries — noted here so they aren't silently dropped,
but not built in this project:

- **A full "Conflicts" list/inbox screen** across the whole graph (all pending conflicts at
  once, with bulk actions), modeled on `LlmSuggestionReviewScreen.kt` /
  `llm/LlmSuggestionInbox.kt`. The sidebar indicator (Phase 5) surfaces *that* a page has a
  pending conflict; clicking through to a dedicated review screen is a natural follow-up project,
  not built here — clicking a per-row sidebar indicator (Story 5.1.1) simply navigates to the page
  (existing `onPageClick` behavior), which then shows the per-page dialog via
  `checkAndShowPendingConflict()`, and clicking the always-visible fallback badge (Story 5.1.4,
  ADR-003) navigates to the existing "All Pages" screen rather than a filtered/dedicated view.
- **True cross-restart / cross-graph-switch persistence** for the indicator — see ADR-002.
  Session-scoped only, matching `LlmSuggestionInbox`'s existing documented limitation.
- **Coalescing behavior for a second external change arriving while the dialog is already
  open** (as opposed to while deferred/pending, which Story 1.1.1/6.1.2 does cover) — the
  `shouldProtect` dialog-open path (`StelekitViewModel.kt` L1401-1420) is part of the four-tier
  protection check, which `requirements.md`'s Out of Scope section explicitly excludes ("The
  four-tier protection check logic that decides *whether* to show the dialog").
- **Migrating `BlockDiffView.kt`** (the LLM-suggestion before/after panel) to use
  `kotlin-multiplatform-diff` — different feature/domain, not touched by this project (see
  ADR-001's Consequences).
- Command palette, slash commands, undo/redo, `EditorSettings`, or other cross-cutting gaps from
  the same journey-mapping pass — explicitly called out in `requirements.md` as separate SDD
  projects.
