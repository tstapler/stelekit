# Adversarial Review: disk-conflict-dialog-ux

**Date**: 2026-07-03
**Verdict**: CONCERNS

This is a re-review of the patched plan against the 4 prior BLOCKER findings. All source-file
grounding claims (line numbers, function signatures, field existence) were re-verified against
the actual current state of the repo, not trusted from the plan's prose.

## Prior Blocker Resolution

1. **"View full comparison" renders behind the still-open modal dialog** — RESOLVED (at plan
   level). Verified `GraphDialogLayer.kt` L274-282 still contains the unconditional
   `appState.diskConflict?.let { conflict -> DiskConflictDialog(...) }` block the original
   blocker was filed against. The patch adds Task 3.1.1e, which gates that block behind
   `if (!appState.diskConflictViewFullVisible)`, making `DiskConflictDialog` and the new
   `DiskConflictFullScreen` structurally mutually exclusive in composition — the standard, sound
   fix for Compose `AlertDialog`'s always-on-top `Dialog`/window-layer behavior. ADR-001's
   amendment correctly reframes "closing returns to the still-open dialog" as a *state* claim
   (`appState.diskConflict` stays non-null) rather than a *continuous rendering* claim. The plan
   also explicitly documents the residual gap: no automated Compose/screenshot test verifies
   mutual exclusivity in a real render pass (Roborazzi is Gradle-only, out of this project's test
   tier) — manual verification via `bazel run //kmp:desktop_app` is called out as required before
   shipping. This is an honest, acceptable residual gap, not a silent one.

2. **Ordinal-position block matcher can silently return the WRONG block's content on sibling
   reordering** — NOT RESOLVED. The patch adds a KDoc "documented limitation" comment on
   `matchDiskBlockContent` (Task 2.1.1c) and a new test, Task 6.1.3d, that explicitly asserts the
   matcher returns the *wrong* sibling's content on a same-count reorder and calls that "known,
   pinned behavior." Neither of these changes the actual runtime behavior a user experiences: the
   dialog can still show a user "your disk version" text that is, in fact, some other block's
   content, with **zero visual signal** that anything is wrong (Task 2.1.3a's fallback-note UI
   only fires when `diskBlockContent == null`, not on a low-confidence match). The original
   blocker's recommendation — "add a cheap sanity/confidence check... e.g., compare matched disk
   content against a similarity threshold vs. the block's last-known-good content or
   `contentHash`" — was not implemented, despite `Block.contentHash: String?`
   (`model/Models.kt` L116, "SHA-256 of normalised content; null until first save") already
   existing in the codebase as exactly the field the original recommendation named, requiring no
   new plumbing to use as a cheap heuristic ("does the matched content's hash correlate with any
   known-good historical hash" or even a simpler Levenshtein/line-overlap check against
   `conflict.localContent`'s prior known state). Converting a silent bug into a *documented and
   tested* silent bug is a legitimate, good-faith engineering triage decision — and the plan is
   honest about deferring the real fix (`SidecarManager` content-hash identity recovery) as
   out-of-proportion — but it does not resolve the blocker's core safety complaint: a user can
   still confidently click "Use disk version" against content that was never actually their
   block's disk counterpart, with the UI actively representing it as correct.

3. **`markdownParser` doesn't exist on `StelekitViewModel` — Task 2.1.2b's premise was factually
   wrong** — RESOLVED. Re-verified via grep: `StelekitViewModel.kt` still has zero references to
   `MarkdownParser` today, and `StelekitViewModelDependencies.kt` (read in full) still has no
   `markdownParser` field. `GraphLoader.kt` L82 confirms `private val markdownParser =
   MarkdownParser()` — a bare no-arg instantiation, not DI — and `parser/MarkdownParser.kt` L12
   confirms `class MarkdownParser` takes zero constructor arguments and is stateless. The new
   Task 2.1.2a-pre correctly mirrors this exact pattern (`private val markdownParser =
   MarkdownParser()` as a plain class-level field on `StelekitViewModel`, no constructor
   parameter, no `StelekitViewModelDependencies` change) — this is simpler and safer than the
   `journalService`-style nullable-injected-default pattern the original blocker suggested as a
   fallback, and correctly avoids touching the 17 existing `StelekitViewModelDependencies`
   call sites at all, since no DI is needed for a stateless zero-arg class.

4. **Unguarded `parsePage()` calls inside a standing collector risk permanently killing conflict
   detection** — RESOLVED (at plan level), with one new caveat (see Concerns).
   `parser/MarkdownParser.kt` L17-37 confirmed: `parsePage()` catches `CancellationException` and
   rethrows it, catches `Exception` and rethrows after logging — genuinely unsafe to call bare
   inside `observeExternalFileChanges()`'s standing `collect { }` (`StelekitViewModel.kt` L1370-1458,
   confirmed still has no try/catch around the collector body today). Tasks 2.1.2b/2.1.2c now wrap
   both call sites in `try { ... } catch (e: CancellationException) { throw e } catch (e:
   Exception) { logger.warn(...); null }`, degrading to `diskBlockContent = null` — exactly the
   existing Story 2.1.3 fallback path. This is the correct fix shape and directly matches this
   repo's own `CLAUDE.md` guidance on standing-collector exception safety. Task 6.1.5a adds a
   regression test intended to lock this in — see Concerns for a caveat on whether that specific
   test is constructible as written.

**Summary: 3 of 4 prior blockers are RESOLVED at the plan level (grounding is now factually
correct and the proposed fix shape is sound); 1 of 4 (the ordinal-matcher silent-wrong-match
blocker) is NOT RESOLVED — it was downgraded to a documented/tested known limitation rather than
mitigated, and a cheap mitigation path was available in the codebase (`Block.contentHash`) and not
used.**

## Blockers

- [x] **RESOLVED — see "Addendum: Verification of Task 2.1.1d" below.** Ordinal-position block
  matcher's silent-wrong-match failure mode was unmitigated in the actual dialog UX at the time
  this section was written. Fixed via plan.md's Task 2.1.1d, a `Block.contentHash`-based
  plausibility check, verified by the addendum as correctly closing this gap (one accepted,
  documented, fail-safe residual edge case remains — duplicate-content siblings). Original text
  preserved for record: add a cheap heuristic gate using `Block.contentHash` or a line-similarity
  check against `conflict.localContent` before trusting a positional match, falling back to `null`
  when the match looks implausible.

## Concerns

- [ ] **Task 6.1.5a's collector-survival regression test may not be constructible as written.**
  Grepped the entire `parsing/` directory (`OutlinerParser.kt` and all files it delegates to,
  including `InlineParser`, AST node files) for `throw` — zero matches. The only `throw`
  statements in the whole call path are the two rethrows inside `MarkdownParser.parsePage()`
  itself (`parser/MarkdownParser.kt` L30, L35), which only fire if `parser.parse(content, mode)`
  or `convertBlock()` throws — and nothing in the actual parsing implementation appears to throw
  on malformed/adversarial Markdown; it looks designed to be permissive rather than to reject
  input. The plan itself hedges on this ("if constructing genuinely malformed Markdown that throws
  is impractical, this test may instead need to be written against `DiskConflictBlockMatcher`/
  `markdownParser.parsePage` directly to confirm the exception shape") — i.e., the plan already
  anticipated this might not work but did not resolve it, and the chosen DI-free instantiation for
  `markdownParser` (Task 2.1.2a-pre, correctly resolving Blocker #3) forecloses injecting a
  throwing test double, since the field is a hardcoded private class member with no seam. This
  doesn't undermine the try/catch fix itself (defensive code is still correct to add per
  `CLAUDE.md`'s standing-collector rule even if presently unexercised), but the plan's regression
  coverage for it may end up empty or synthetic (e.g., testing `MarkdownParser.parsePage()`
  directly with a hand-crafted throw, decoupled from the actual collector) rather than proving the
  collector itself survives. — **Recommendation**: during implementation, confirm whether any
  realistic input reaches `parser.parse()` or `convertBlock()` and throws (try genuinely
  pathological input — extremely deep nesting, malformed UTF-8/surrogate pairs, huge single lines —
  during a spike before committing to Task 6.1.5a's approach); if no such input exists, either (a)
  temporarily inject a throwing fake by making `markdownParser` an internal `var` swappable in
  tests (a narrow, test-only seam, not full DI), or (b) honestly scope the test down to asserting
  the try/catch construct's *shape* (e.g. via a directly-called helper function extracted from the
  inline try/catch) rather than claiming full collector-survival coverage.

- [ ] **(Carried forward, unresolved) ADR-002's "will simply re-surface" claim for graph-switch
  divergence reconciliation remains asserted, not traced.** Not part of this patch's scope (ADR-002
  is unchanged from the prior review), but still an open verification gap: this review did not
  trace `FileRegistry`'s reconciliation path to confirm a conflict deferred, then made stale by a
  graph switch away-and-back, actually re-surfaces or reconciles correctly rather than silently
  discarding the divergence.

- [ ] **(Carried forward, addressed only partially) Double-fetch risk in Task 2.1.2c.** The patched
  task text now explicitly instructs reuse of the already-fetched block list, which is a real
  improvement over the original vague framing. However, the current code this task modifies
  (`StelekitViewModel.kt` L1473-1474) chains `getBlocksForPage(...).first().getOrNull()` directly
  into `?.minByOrNull { it.position }` without ever binding the full list to a variable — so
  "reuse that result" still requires the implementer to restructure this into two steps (bind the
  list, then derive `firstBlock` from it) rather than literally reusing an existing binding. Still
  worth an explicit code-review checkpoint at implementation time, as the original review asked.

## Minors

- The amber `Color(0xFFF59E0B)` reuse between `SyncStatusBadge`'s `ConflictPending` state
  (confirmed still present, `SyncStatusBadge.kt` L157-169, L241-250) and the new
  `PendingConflictsBanner` (Task 5.1.4a) plus the per-row icon (Task 5.1.1a) was not changed from
  the prior review's minor finding — ADR-003 now explicitly rationalizes this as intentional
  ("reusing the color here signals these are the same kind of thing, shown at two different
  granularities"), which is a reasonable, documented position rather than an oversight. Downgrading
  from "consider a distinct color" to a documented-and-accepted note; no action required, but
  flagging that the visual-confusion risk from the original VS Code pitfall citation is still
  live in the shipped UI regardless of the ADR's reasoning — a real user will still see the same
  amber warning color for two functionally different "conflict" concepts (git-merge vs.
  disk-write) with no adjacent text distinguishing them until they read the tooltip/label.
- All file/line grounding in the patched plan was spot-checked against current source and found
  accurate: `GraphDialogLayer.kt` L274-282, `DiskConflictDialog.kt` L45/L63-77/L88/L92-95,
  `StelekitViewModel.kt` L110-166 (class fields), L1370-1458 (`observeExternalFileChanges`),
  L1467-1487 (`checkAndShowPendingConflict`), L1535-1602 (four resolvers),
  `StelekitViewModelDependencies.kt` (full file, `journalService` pattern),
  `Sidebar.kt` L146-244 (`LeftSidebar` body) and L544-590 (`SidebarItem`), `App.kt` L1299
  (`LeftSidebar` call site), `AppState.kt` L130-212 (`AppState`/`DiskConflict`/`PendingConflict`),
  `GraphLoaderPort.kt` (confirmed no `emitExternalFileChange`), `DiskConflictResolutionTest.kt`
  L61-94 (`makeViewModel`, confirmed no `graphLoader` parameter today). No new factual-grounding
  errors were found in this pass — the earlier draft's "wrong line number" class of blocker
  (Blocker #3) has not recurred elsewhere in the patch.
- `Screen.AllPages` and `LeftSidebar`'s `onNavigate` callback are both confirmed to already exist
  and be wired (`Sidebar.kt` L167, L69) — Story 5.1.4's "no new navigation target" claim holds.
- `kotlin-multiplatform-diff:1.3.0` dependency confirmed still declared at `kmp/build.gradle.kts`
  L83 — no change from the prior review's positive verification of its API shape.

## Addendum: Verification of Task 2.1.1d (content-hash plausibility check)

**Date**: 2026-07-03
**Verdict**: CONCERNS (for this specific fix only)

This addendum verifies the coordinator's direct patch adding Task 2.1.1d (content-hash
plausibility check) in response to this file's sole remaining Blocker above. It does not
re-review any other part of the plan.

### Verified sound (compiles, correctly fires on the reorder case it targets)

- **Type-checking, against current source, not the plan's prose**: `Block.contentHash: String?`
  (`model/Models.kt` L116, confirmed by direct read); `Block.uuid: BlockUuid` where `BlockUuid` is
  `expect value class BlockUuid(val value: String)` (`model/Uuid.kt` L9), so `.value` yields
  `String`; `ContentHasher.sha256ForContent(content: String): String` — non-null return, confirmed
  by direct read of `util/ContentHasher.kt` L56-57. Task 2.1.1d's pseudocode type-checks cleanly:
  `sibling.uuid.value != targetUuid` is `String != String`; `sibling.contentHash == matchedHash` is
  `String? == String`, which in Kotlin calls `.equals()` and safely evaluates `false` (no NPE) when
  the left side is `null`.
- **`ParsedBlock` carries no contentHash field** (`model/ParsedModels.kt` L34-50, read in full) —
  hashing `matched.content` fresh via `ContentHasher.sha256ForContent()` is the only viable
  approach, and it exactly mirrors the codebase's existing pattern: `MarkdownPageParser.kt`
  L165/225 hashes a freshly-parsed `ParsedBlock.content` at `Block`-construction time the same way,
  and `SidecarManager.kt` L46 does the same for the sidecar write path. No new field, no new
  dependency. No circular-import risk: `db/` importing `dev.stapler.stelekit.util.ContentHasher` is
  an already-established pattern (`SidecarManager.kt`, same package as the new
  `DiskConflictBlockMatcher.kt`).
- **Reorder walk-through, against the actual `buildBlockPath`/`findAtPath` semantics specified in
  2.1.1a/2.1.1b**: 3 root-level siblings A/B/C at local positions 0/1/2 (each with a previously-saved
  `contentHash`); disk transposes A and B (disk order becomes B, A, C) with no addition/removal.
  For `target = A`: `buildBlockPath(A)` = `[0]` (local ordering is unchanged — the swap only
  happened on disk), `findAtPath(diskBlocks, [0])` returns disk-position-0's content, which is now
  B's content. `matchedHash = hash(B's content)`. The collision scan finds local sibling B
  (`uuid != targetUuid`, `contentHash == matchedHash`) → correctly returns `null`. Verified
  symmetrically for `target = B` (also correctly nulled, matches A's disk-position-1 content) and
  for `target = C` (untouched sibling, disk position unchanged, no collision, correctly returns the
  genuine match). The mitigation fires exactly on the scenario the blocker and Task 6.1.3d describe.
- **Task 6.1.3d's `contentHash == null` case is safe, not just "safe by luck"**: the explicit
  `sibling.contentHash != null` guard prevents comparing `null == null`, and independently,
  `matchedHash` is always a non-null `String` (confirmed via `ContentHasher.sha256ForContent`'s
  signature), so `null == "somehash"` evaluates to `false` in Kotlin regardless of the guard. Both
  the explicit guard and the underlying type system independently rule out a false-positive
  collision here — verified explicitly rather than assumed, as requested.

### Concerns

- [ ] **Undocumented false-positive fallback for duplicate-content siblings with no reorder at
  all.** A case not covered by the plan's walk-through or by Task 6.1.3d's two test cases: two
  root-level siblings A and B under the same parent with **identical content** (a realistic Logseq
  pattern — repeated checklist items, blank separator bullets, boilerplate/template text) and **no
  reorder on disk**. `buildBlockPath(A) = [0]`, `findAtPath` correctly returns A's own unchanged
  disk content, `matchedHash = hash(A's content)`. Because B has the same content, `sibling.contentHash
  == matchedHash` is *also* true for B (a genuinely different block, `uuid != targetUuid`), so
  `collidesWithADifferentSibling` fires and the plausibility check discards a **correct** match,
  falling back to the existing "no match" UX. This degrades fail-safe (it never shows wrong content
  — consistent with the fix's own stated intent) so it is **not** a safety regression and does not
  reopen the original blocker. But it is a real, previously-unidentified behavior change introduced
  by this fix: before Task 2.1.1d, this exact duplicate-content/no-reorder case matched correctly;
  after it, the same case now spuriously falls back to "no match" purely because of the new
  hash-collision check. Task 2.1.1c's KDoc only documents "reorder + content edit at the transposed
  position" as the residual limitation — it does not mention "duplicate-content siblings trigger
  unnecessary fallback even absent any reorder," and Task 6.1.3d's test coverage doesn't exercise
  this case either. **Recommendation**: add one sentence to Task 2.1.1c's KDoc noting this tradeoff,
  and optionally a third case in Task 6.1.3d pinning the fail-safe-but-lossy behavior so it isn't
  inadvertently "fixed" into a false-positive-content bug by a future refactor.

### Minors

- None found specific to Task 2.1.1d / Task 6.1.3d beyond the concern above.

**Bottom line**: This fix genuinely resolves the sole remaining Blocker from the prior pass —
`matchDiskBlockContent` no longer silently returns a misattributed sibling's content on a pure
same-count reorder, and it degrades safely to the pre-existing `null`/no-match UX rather than
showing wrong content with false confidence. One new, narrow, fail-safe (not safety-critical) false
positive mode was found — duplicate-content siblings with no actual reorder — which is worth a
one-line documentation fix but does not block shipping.
