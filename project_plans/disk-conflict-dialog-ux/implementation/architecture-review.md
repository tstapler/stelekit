# Architecture Review: disk-conflict-dialog-ux

**Date**: 2026-07-03
**Verdict**: CONCERNS

## Prior Blocker Resolution

1. **Unguarded `markdownParser.parsePage()` calls could permanently kill disk-conflict detection.**
   — **RESOLVED**. Verified against current source: `StelekitViewModel.kt` has zero `MarkdownParser`
   references today (confirmed via grep), and `GraphLoader.kt` L82 shows the exact zero-arg
   self-construction pattern (`private val markdownParser = MarkdownParser()`) the patched Task
   2.1.2a-pre now correctly mirrors, instead of the original draft's factually-wrong "inject it the
   same way `graphLoader` is" premise (`graphLoader` is a required, non-default constructor param —
   would have broken 17 call sites). `MarkdownParser.parsePage()` (`parser/MarkdownParser.kt`
   L17-37) is confirmed to still rethrow on any non-cancellation exception. Tasks 2.1.2b/2.1.2c now
   wrap both call sites in `try { } catch (e: CancellationException) { throw e } catch (e:
   Exception) { logger.warn(...); null }`, degrading to `diskBlockContent = null` — the existing
   Story 2.1.3 fallback UX — instead of propagating into `StelekitViewModel.scope`'s
   `CoroutineExceptionHandler` (which would kill the standing collector for the session and paint a
   `fatalError` screen). This matches this repo's `CLAUDE.md` guidance on standing-collector
   exception safety. This is a plan-level fix (not yet in code) — its correctness is verified by
   inspection of the described diff, and should be re-confirmed once implemented.

2. **Task 6.1.2a's test called `vm.graphLoader.emitExternalFileChange(...)`, which doesn't
   compile.** — **RESOLVED**. Confirmed `StelekitViewModel.graphLoader` (`StelekitViewModel.kt`
   L117) is `private val graphLoader: GraphLoaderPort`, and `GraphLoaderPort.kt` declares no
   `emitExternalFileChange` member (only `externalFileChanges: SharedFlow<...>`,
   `setActivePageUuids`, `setUnsavedPageUuids`, `writeErrors`, `setCryptoLayer`,
   `closeAndClearCryptoLayer`, and the load/save surface) — `emitExternalFileChange` exists only on
   the concrete `GraphLoader` class (`db/GraphLoader.kt` L422). Confirmed the current (unpatched)
   `DiskConflictResolutionTest.kt` `makeViewModel()` (L61-94) constructs `graphLoader` as a
   function-local `val`, exactly matching the "before" state the patch describes. Task 6.1.1a-pre
   now threads `graphLoader: GraphLoader = GraphLoader(...)` in as a parameter, and Task 6.1.2a is
   corrected to read from that test-local `val` instead of `vm.graphLoader`. This now compiles
   against the real type surface.

## Blockers

None.

## Concerns

- [ ] **Task 6.1.2a's regression test still does not exercise the race Task 1.1.1b claims to
  fix.** The patch fixed the *compile* error (Blocker #2) but left the test's structure completely
  unchanged: it emits both `"- first version"` and `"- second version"` via
  `graphLoader.emitExternalFileChange(...)` **before** calling `vm.navigateTo(Screen.PageView(testPage))`,
  then asserts `diskConflict?.diskContent == "- second version"`. As established in the prior
  review, that sequence is handled correctly by the pre-existing `pendingConflicts` map-merge logic
  in `observeExternalFileChanges()` (`StelekitViewModel.kt` L1385-1394,
  `existing == null || existing.diskContent != event.content`) with or without Task 1.1.1b's fix —
  the actual race Task 1.1.1b targets is a second `ExternalFileChange` arriving *after*
  `navigateTo()` has already flipped `currentScreen`, during `checkAndShowPendingConflict()`'s async
  gap (`StelekitViewModel.kt` L1467-1480, between the synchronous `pending` read and the
  `scope.launch` that builds `DiskConflict`). The plan's own "post-review patch note" documents this
  task as corrected only for the compile issue ("Task 6.1.2a was corrected in place to use the
  test-local `graphLoader` value instead of `vm.graphLoader` (blocker: did not compile)") — there is
  no mention of restructuring the test to hit the actual async window. This test will pass whether
  or not Task 1.1.1b's fix exists, giving false confidence in a regression suite that Story 6.1.2
  claims covers it. — **Recommendation**: either add a test that emits the second change after
  `navigateTo()` but before `checkAndShowPendingConflict()`'s `scope.launch` resolves (this needs a
  controllable/pausable test dispatcher — `Dispatchers.Unconfined`, used throughout this fixture,
  runs the launch body eagerly and cannot represent an in-flight async gap), or explicitly narrow
  Task 1.1.1b's stated scope/acceptance criteria to the race it actually closes and drop the
  implication that 6.1.2a covers it.

- [ ] **Story 6.1.5's regression test for the Blocker #1 fix may not be constructible as
  specified.** Task 6.1.5a needs content that makes `MarkdownParser.parsePage()` throw a
  non-`CancellationException` `Exception`, to prove the new try/catch keeps the standing collector
  alive. Grepping `kmp/src/commonMain/kotlin/dev/stapler/stelekit/parsing/` (the package
  `OutlinerParser`/`InlineParser`/AST types live in, which `parsePage()` delegates to) turns up zero
  explicit `throw` statements anywhere in that package — the outliner parser appears defensively
  coded to avoid throwing on malformed markdown, which is exactly why "genuinely malformed Markdown
  that throws" may be impractical to construct, a risk the task text itself already flags
  ("if... impractical, this test may instead need to be written against
  `DiskConflictBlockMatcher`/`markdownParser.parsePage` directly"). If no real throwing input is
  found during implementation, there is no fallback plan more concrete than "figure it out" — the
  regression coverage that exists specifically to lock in this project's most important fix (the
  one that was Blocker #1) is the task most likely to be silently weakened, skipped, or replaced
  with a synthetic mock that doesn't exercise the real exception path. — **Recommendation**: during
  implementation, if a genuinely parse-throwing string cannot be found, introduce a small test seam
  (e.g. an injectable `MarkdownParser`-compatible interface, or a test-only subclass whose
  `parsePage` throws) rather than dropping or diluting the assertion that the collector survives.

- [ ] **The documented same-count-reorder mismatch remains an accepted, unmitigated data-integrity
  gap that now feeds three UI surfaces and one persisted-content path.** This was raised by the
  adversarial review as a BLOCKER and folded into the plan only as documentation (Task 2.1.1c's
  KDoc + Task 6.1.3d's pinned "known wrong" test) rather than a runtime mitigation. Architecturally,
  `DiskConflictBlockMatcher.matchDiskBlockContent` is a pure ordinal-position heuristic with no
  confidence signal — when disk-side siblings are transposed without an add/remove, it returns
  **non-null, wrong** content indistinguishable at the type level from a correct match. That
  `String?` return value now flows unguarded into: the dialog preview (Task 2.1.3a), the full-screen
  diff default content (Task 3.1.3a), and — most importantly — `manualResolve()`'s literal,
  persisted conflict-marker text (Task 2.2.1a), which a user can save to disk believing it reflects
  their actual block's disk counterpart. Documenting a known limitation is reasonable scope
  discipline for *this* project, but "accepted and tested" is doing more work here than it should:
  a match with zero confidence signal silently participates in a write path. — **Recommendation**:
  not a blocker for this project's stated scope, but flag for a fast-follow: at minimum, thread a
  boolean/confidence flag out of `matchDiskBlockContent` so call sites *could* differentiate "matched
  with high confidence" from "matched positionally, unverified" without waiting on the deferred
  `SidecarManager` content-hash identity work.

## Minors

- Task 1.1.2e (`AppStateOptics.pendingConflicts` lens) still adds scaffolding to a file with zero
  call sites codebase-wide (reconfirmed via grep). Unchanged from the prior review — still not
  worth blocking on.
- `DiskConflictBlockMatcher.kt`'s placement under `db/` (vs. a `parser/`- or `conflict/`-scoped
  package) is unchanged from the prior review — still debatable, still not worth blocking on. The
  file does not yet exist in the tree, consistent with this still being a pre-implementation plan.
- ADR-001's diff-library API uncertainty (flagged as a minor in the prior review) is now resolved:
  the adversarial review's own `javap` inspection of the cached
  `kotlin-multiplatform-diff-jvm-1.3.0.jar` confirms `DiffUtils.diff(List<T>, List<T>): Patch<T>`,
  `Patch<T>.deltas`, and `Delta<T>.source`/`target` match ADR-001 and Task 3.1.2b's assumed shape
  exactly. No action needed on this item going forward.
- `GraphLoader.kt`'s own two `parsePage()` call sites (L1347, in a page-loading path, and L1708,
  inside `parseAndSavePage()`) remain unguarded by a local `try/catch` — `parseAndSavePage()`
  (`GraphLoader.kt` L1660-1776) wraps its body in `try { ... } finally { ... }` with no `catch`
  clause, so a throwing parse still propagates out of that function today. This weakens the plan's
  grounding claim that `parsePage()` is "already used for exactly this purpose elsewhere" as
  evidence of established safe practice — it is used there, but not safely. Out of this project's
  scope (per `requirements.md`'s scope boundary against touching the four-tier protection / reload
  path), but worth a follow-up ticket since `GraphLoader`'s own file-watcher-triggered call sites
  could have the same class of standing-collector risk this project is fixing in
  `StelekitViewModel`.

## New-Issue Sweep (Story 5.1.4 / `PendingConflictsBanner`)

Checked specifically for the no-unbounded-reads rule and dispatcher correctness per this repo's
`CLAUDE.md`: **clean, no issues found.** `pendingConflictFilePaths` is computed once at the `App.kt`
call site (Task 5.1.3a) as `appState.pendingConflicts.keys + listOfNotNull(appState.diskConflict?.filePath)`
— a pure, O(visible-conflicts) in-memory `Set` derived from state already held in `AppState`
(`pendingConflicts: Map<String, PendingConflict>`, already in `_uiState`). No new SQLDelight query,
no new repository method, no `Flow` collection, nothing DB-adjacent at all — it is Compose
state-derivation, not a data-access pattern, so the dispatcher matrix does not apply. `LeftSidebar`
and `PendingConflictsBanner` (Tasks 5.1.2a/5.1.4a/5.1.4b) consume the set as a plain parameter with
no scope ownership of their own (no `remember { }`-held long-lived class, no
`rememberCoroutineScope()` misuse) — consistent with the rest of `Sidebar.kt`'s existing
stateless-composable pattern.
