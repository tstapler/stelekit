# Adversarial Review: android-git-saf-shadow-worktree

**Date**: 2026-08-28
**Verdict**: CLEAN
**Round**: 11

## Round-10 Blocker — Resolution Status

- [x] **Blocker (`GitShadowWorktree` internal class)**: RESOLVED.

  Verified directly against current plan text, not re-inferred from the round-10 description:
  - Task 1.1.1a (plan.md:257-266) now reads: *"Class visibility, corrected after round-10
    adversarial review: `class GitShadowWorktree(...)` (plain public, not internal) — an earlier
    draft declared it internal class..."* — the class declaration itself
    (`class GitShadowWorktree(context: Context, internal val shadowKey: String, private val safRoot: String, private val fileSystem: FileSystem)`,
    plan.md:266) carries no `internal`/`private` modifier before `class`, i.e. public by Kotlin's
    default. Grepped every `class GitShadowWorktree` occurrence in plan.md: this is the only
    declaration, and it is not walked back anywhere later in the document.
  - `sweepOrphans` (Task 6.1.1b, plan.md:1181-1182): `` `fun sweepOrphans(context: Context, maxAgeMillis: Long = 60L * 24 * 60 * 60 * 1000)` `` —
    no visibility modifier on the function itself, and it is described as a member of the same
    `companion object` Task 5.1.1a opens (plan.md:866, also unmodified — public by default). A
    public class with an implicitly-public companion function is reachable from any module that
    can see the class, which `:androidApp` now can (public class). Task 6.1.1c's
    `GitShadowWorktree.sweepOrphans(applicationContext)` call from `MainActivity.kt` (plan.md:1193,
    module `:androidApp`) now compiles under this signature.
  - **Checked every other member of `GitShadowWorktree` referenced from `:androidApp`** (not just
    `sweepOrphans`) — see the exhaustive sweep below. `shadowKeyForSafPath` (Task 5.1.1a) is never
    called from `:androidApp` anywhere in the plan; only `AndroidGitRepository.shadowWorktreeFor`
    (Task 1.2.3a) and `PlatformFileSystem`'s git-shadow-key-provider closure (Task 5.2.1c) call it,
    both inside `:kmp`. No member was missed by this fix.

  This blocker is genuinely closed.

## Exhaustive cross-module visibility sweep (item 2)

Every `androidApp/` file the plan touches or references was enumerated first (`grep -n
"androidApp/" plan.md`): only two files are ever edited or created —
`androidApp/src/main/kotlin/dev/stapler/stelekit/MainActivity.kt` (edited, Tasks 5.1.2a and
6.1.1c) and `androidApp/src/test/kotlin/dev/stapler/stelekit/MainActivityGitRepositoryWiringTest.kt`
(new, Task 5.1.2a). `CaptureActivity.kt` is cited only as a naming/visibility precedent, never
modified. `WorkManagerSyncScheduler.kt` was re-verified to live at
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/WorkManagerSyncScheduler.kt` (confirmed in
the "Summary of files touched" section, plan.md:1447, and Story 5.1.2's own file list,
plan.md:892) — i.e. it is a `:kmp`-module file, same module as everything it calls, so its
references need no cross-module check.

Every symbol declared under `kmp/src/androidMain/` or `kmp/src/commonMain/` that plan.md shows
being referenced by name from `MainActivity.kt` or `MainActivityGitRepositoryWiringTest.kt`
(the only `:androidApp` files in scope), checked one by one:

| Symbol | Declared visibility (plan text) | Referenced from | Module boundary | Status |
|---|---|---|---|---|
| `GitShadowWorktree` (class) | public (fixed this round, Task 1.1.1a) | `MainActivity.kt` Task 6.1.1c | `:kmp` → `:androidApp` | OK — reachable |
| `GitShadowWorktree.sweepOrphans` (companion fun) | public (no modifier, Task 6.1.1b) | `MainActivity.kt` Task 6.1.1c | `:kmp` → `:androidApp` | OK — reachable now that the class is public |
| `GitShadowWorktree.shadowKeyForSafPath` (companion fun) | public (no modifier, Task 5.1.1a) | Only `AndroidGitRepository.shadowWorktreeFor` (1.2.3a) and `PlatformFileSystem`'s key-provider closure (5.2.1c) | both `:kmp`-internal call sites | N/A — never called from `:androidApp`; would be fine regardless since public |
| `GitShadowWorktree.shadowKey` (`internal val`) | internal (Task 1.1.1a, deliberately) | Only `GitShadowFlushActor` (3.1.2a) | `:kmp` → `:kmp` | Correctly scoped `internal` — no `:androidApp` reference exists |
| `GitShadowWorktree.worktreeRootPath` (public val) | public (no modifier, Task 1.1.1a) | `resolveForJGit` (1.2.3b), `StatFs` check (6.2.1a) | both `:kmp` | N/A for cross-module; fine either way |
| `GitWorktreeLocks` (`internal object`) | internal (Task 0.1.3a, deliberately) | `GitShadowFlushActor` (3.1.2a), `GitShadowWorktree.syncFromSafRoot` (5.2.1b), `PlatformFileSystem` flush call site (5.2.1c) | all `:kmp` | Correctly scoped `internal` — no `:androidApp` reference exists |
| `GitWorktreeLocks.lockFor` | member of the object above | same three call sites | all `:kmp` | Correctly scoped |
| `AndroidGitRepository` (class) | public (unmodified by this plan; pre-existing) | `MainActivity.kt`'s `buildGitRepository` (5.1.2a) | `:kmp` → `:androidApp` | OK — reachable |
| `AndroidGitRepository.fileSystem` (public val) | public (fixed rounds 8-9, Task 2.1.1a) | `MainActivityGitRepositoryWiringTest.kt` (5.1.2a) | `:kmp` → `:androidApp` | OK — reachable, previously verified, re-confirmed unaffected by this round's change |
| `AndroidGitRepository` constructor (`context`, `fileSystem`, `pathResolver` params) | public constructor (class has no modifier) | `buildGitRepository` (5.1.2a) | `:kmp` → `:androidApp` | OK — reachable |
| `AndroidGitRepository.shadowWorktreeFor` (`internal fun`, per Task 8.2.2a's note) | internal | Only `GitPathResolverChainTest.kt` (`kmp/src/androidUnitTest/`, Task 8.2.2a) | `:kmp` → `:kmp` | Correctly scoped — grepped, no `:androidApp` reference exists |
| `PlatformFileSystem` (class) | public (pre-existing; **verified directly against live source**, `kmp/src/androidMain/.../PlatformFileSystem.kt:14`: `actual class PlatformFileSystem actual constructor() : FileSystem`) | `buildGitRepository` (5.1.2a) | `:kmp` → `:androidApp` | OK — verified against real code, not just the plan's claim |
| `PlatformFileSystem.resolveSafToRealPath` (companion fun) | public (pre-existing; **verified directly against live source**, `PlatformFileSystem.kt:76`, no modifier) | `buildGitRepository` (5.1.2a); also already called live today from `MainActivity.kt:297` in the current, unmodified repo | `:kmp` → `:androidApp` | OK — verified against real code; already exercised in production today, so no regression risk from this plan |
| `buildGitRepository` (`internal fun`, new, declared **in** `MainActivity.kt` itself — an `:androidApp` file, not a `:kmp` symbol) | internal | `MainActivity.kt`'s own `remember {}` (same file) and `MainActivityGitRepositoryWiringTest.kt` (5.1.2a) | `:androidApp` main → `:androidApp` test, same module | Correctly scoped `internal` — Kotlin's test/main friend-path association applies here (verified against the same `CaptureActivity.kt`/`CaptureActivityTest.kt` precedent the plan cites) — included in this table for completeness even though it isn't a `:kmp`→`:androidApp` case, since the review scope was "every symbol referenced from androidApp code samples" |
| `FileSystem.listFilesRecursiveWithModTimes` (new interface member, Task 1.2.3c) | public (default body, no modifier) | Only `AndroidGitRepository.openGit` (`:kmp`) | `:kmp` internal use only | N/A — never referenced from `:androidApp` |
| `FileSystem.setGitShadowKeyProvider` (new interface member, Task 5.2.1c) | public (default body, no modifier) | Only `AndroidGitRepository.shadowWorktreeFor` (`:kmp`) | `:kmp` internal use only | N/A — never referenced from `:androidApp` |
| `DomainError.GitError.WorkingTreeSyncFailed`/`WorkingTreeWriteBackFailed`/`WorkingTreeConcurrentEditDetected` (Task 0.1.2a) | public sealed-interface members (`commonMain`) | Only within `:kmp` (`AndroidGitRepository`, `GitShadowFlushActor`) | `:kmp` internal use only | N/A — never referenced from `:androidApp` |
| `GitWriteBackQueue`, `GitShadowFlushActor` (new `:kmp` androidMain classes) | not given an explicit modifier in the plan (public by default) | Only within `:kmp` (`AndroidGitRepository`, `GitShadowWorktree`) | `:kmp` internal use only | N/A — never referenced from `:androidApp` |

**Result**: of the seventeen symbols enumerated, exactly two are actually crossed from `:kmp` into
`:androidApp` production code by this plan's own text (`GitShadowWorktree` and
`GitShadowWorktree.sweepOrphans`) plus two pre-existing ones already crossing that boundary today
unmodified (`AndroidGitRepository`, `PlatformFileSystem.resolveSafToRealPath`) plus one fixed in
rounds 8-9 (`AndroidGitRepository.fileSystem`). All five are public and reachable. Every symbol
this plan deliberately leaves `internal` (`shadowKey`, `GitWorktreeLocks`, `shadowWorktreeFor`) was
checked and confirmed to have zero `:androidApp` call sites anywhere in the plan text — `internal`
is correct for all three. No further instance of the rounds-8/9/10 defect class exists.

## New Blockers

None.

## Concerns

Carried forward from round 10 (re-verified against current plan.md line numbers; none were
touched by this round's fix, none are newly introduced, and none rise to blocker severity):

- [ ] No explicit handling for a mid-sync write failure (e.g. disk full) inside `syncFromSafRoot()`
  beyond the pre-clone `StatFs` guard (Task 6.2.1a checks once before first `clone()`/`init()`;
  `syncFromSafRoot()`'s own loop, Task 1.1.2a, has no stated behavior for a mid-loop `IOException`).
- [ ] SAF permission revocation mid-sync isn't discussed anywhere in the plan (low likelihood, low
  blast radius).
- [ ] Task 5.2.1c's "at the write-behind flush call site" phrasing (plan.md:1102) remains slightly
  ambiguous between wrapping `flushPendingWrites()`'s body vs. its two callers — worth tightening
  before implementation.
- [ ] Storage sizing remains deferred to implementation (Task 6.2.0a) — acceptable, plan provides a
  concrete measurement task with a defined output target.
- [ ] `applyJournalMerge()`'s coverage by the `markResolved()` fix (Task 4.1.1a) is still only
  comment-documented (Task 4.1.2a), not exercised by a dedicated regression test — low-risk gap.

New this round (found during the fresh full pass, item 3):

- [ ] **`GitWriteBackQueue` ownership is never explicitly assigned to `GitShadowWorktree`.** Task
  3.2.1a (plan.md:712-713) says to "enqueue its git-relative path into a `GitWriteBackQueue`
  instance held by `worktree`," but Task 1.1.1a's constructor skeleton
  (`class GitShadowWorktree(context: Context, internal val shadowKey: String, private val safRoot: String, private val fileSystem: FileSystem)`)
  never declares a `GitWriteBackQueue` field, and Task 3.1.1a (which defines `GitWriteBackQueue`)
  doesn't say who constructs/holds one. This is a real spec gap (an implementer has to infer that
  `GitShadowWorktree` should lazily construct and hold a `GitWriteBackQueue` keyed to its own
  `worktreeRoot`), but it's a same-file, mechanical addition with only one reasonable resolution —
  not the kind of ambiguity that has caused rework in this plan's history (unlike the visibility
  defect class, this doesn't cross a module boundary or change a public contract).

## Minors

- `GitShadowFlushActor` (new) and `ShadowFlushActor` (existing) remain near-identically-named
  classes in different packages — unchanged since round 2.
- Story 7.1.2 (new `JvmGitRepositoryTest.kt`) remains disclosed, reasonable scope creep.
- Task 5.1.1a's pseudocode doesn't show the `ContentHasher.` qualifier — trivial.
- **Narrow TOCTOU window in `syncFromSafRoot()`** (found this round, item 3): the method takes one
  `listRecursive()` snapshot, then reads/writes files one at a time; if an external SAF-aware app
  modifies a file between the listing call and that file's own read, the manifest records the
  listing-time mtime rather than the mtime of the content actually captured. This is strictly
  narrower than the write-back race Task 3.1.2a/b already detects and refuses (that check is
  symmetric and catches the write-back direction; this is the read direction) — worst case is a
  stale read gets treated as fresh until the *next* sync notices the mismatch, not silent data loss
  of the external edit (SAF remains the source of truth; the external write is never deleted, only
  possibly not yet reflected in one shadow-tree read). Worth a one-line implementation note, not a
  plan change.
- Task 8.2.2a's use of `internal` on `AndroidGitRepository.shadowWorktreeFor()` (tested from
  `kmp/src/androidUnitTest/`, same module as the `androidMain` declaration) remains correctly
  scoped — re-confirmed this round as part of the exhaustive sweep above.
- `buildGitRepository`'s `internal` visibility (MainActivity.kt, `:androidApp`) remains correctly
  scoped for the same-module main/test friend-path reason established in round 9 — re-confirmed
  this round.

## Convergence assessment

This round's exhaustive sweep (item 2) — every `:kmp`-declared symbol referenced by name from
either `:androidApp` file this plan touches, not just the two flagged across rounds 8-10 — found
**zero** further instances of the cross-module `internal`-visibility defect. The defect class that
recurred in rounds 8, 9, and 10 (on `AndroidGitRepository.fileSystem`, then `GitShadowWorktree`
itself) is now, by direct enumeration rather than spot-check, exhausted: there are exactly five
`:kmp`→`:androidApp` symbol crossings in the entire plan, and all five are public. The three
symbols this plan deliberately leaves `internal` (`GitShadowWorktree.shadowKey`, `GitWorktreeLocks`,
`AndroidGitRepository.shadowWorktreeFor`) were each individually confirmed to have no
`:androidApp` caller anywhere in the plan text.

Four consecutive rounds finding the same defect class on different declarations is a real signal
about the review method, independent of this round's clean result: manual line-by-line visibility
tracing across a 1450-line planning document is exactly the kind of mechanical, syntactic check a
compiler does exhaustively and a human review does by sampling. The fact that it took a
now-completed full enumeration (this round) rather than another targeted spot-check to reach
confidence is itself the evidence. **Recommendation for any future plan of comparable size with a
cross-module surface**: before or during Phase 5 (`sdd:5-implement`), have the implementer stub out
just the signatures (empty class bodies, `TODO()` function bodies) for every declaration the plan
places in `:kmp` and every reference to it the plan places in `:androidApp`, and run
`./gradlew :androidApp:compileDebugKotlin` (or equivalent) against that skeleton before writing any
real logic. A compile pass catches every instance of this defect class in seconds, which is strictly
more reliable than any number of additional adversarial-review rounds for this specific category of
bug — reserve manual review rounds for the things a compiler cannot check (race conditions, data
loss, requirements coverage, design coherence).

## Final readiness verdict

**READY FOR IMPLEMENTATION.** No new blockers. This round's exhaustive symbol sweep (item 2) is the
first in this plan's eleven-round history to enumerate every `:kmp`-declared symbol referenced from
`:androidApp` rather than spot-checking the previously flagged line, and it found no further
instance of the visibility defect that recurred across rounds 8, 9, and 10. The fresh full pass
(item 3) found one CONCERN-level spec gap (`GitWriteBackQueue` ownership left implicit — a
same-file, one-declaration addition an implementer will resolve trivially) and re-affirmed the five
pre-existing CONCERNs from round 10 (none blocking, none newly introduced, none touched by this
round's fix). Requirements.md's six open questions, five success metrics, and four constraints are
all traceable to specific plan sections (design decisions #1-#6, Phases 0-8) with no coverage gap
found. The plan is ready to proceed to `sdd:4-validate` / fresh-session implementation without a
twelfth review round being warranted by anything found here.
