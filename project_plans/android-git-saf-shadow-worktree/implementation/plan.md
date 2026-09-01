# Implementation Plan: android-git-saf-shadow-worktree

**Feature**: Make Android Git Sync work for SAF-only users (no `MANAGE_EXTERNAL_STORAGE`) via a
real `java.io.File`-backed shadow working tree that JGit operates on, bidirectionally mirrored to
the user's SAF folder.
**Date**: 2026-08-28
**Status**: Ready for implementation
**ADRs**: [ADR-018: `GitShadowWorktree` as a separate collaborator](../decisions/ADR-018-git-shadow-worktree-separate-collaborator.md)

---

## Corrections to prior documents

- **JGit core version**: requirements.md's "Android uses JGit 5.13.x" is wrong. Android's JGit
  **core** is `7.3.0.202506031305-r`, matching Desktop (`kmp/build.gradle.kts:169,322`). Only the
  **SSH transport module** (`org.eclipse.jgit.ssh.jsch`) is stale, at `5.13.3.202401111512-r`
  (`kmp/build.gradle.kts:325`) — see Phase 0. `AndroidGitRepository.kt:32`'s class doc ("JGit
  5.13.x + mwiede/jsch") is also stale and is corrected in Phase 0.
- **No `DiskConflict` sealed type exists.** `research/features.md` §1 confirms the codebase's
  external-change mechanism is `ExternalFileChange`/`suppress()`
  (`db/GraphEvents.kt:12-15`, `GraphFileWatcher.kt`) plus `FileRegistry`'s sentinel — there is no
  type literally named `DiskConflict`. This plan uses the real names throughout.

## Design decisions this plan makes (not previously settled by research)

1. **No new persisted `GitConfig`/DB column.** `repoRoot` defaults to `detectedRepoRoot` or
   `graphPath` and is thereafter a freely user-editable field (`GitSetupScreen.kt:125-130`) —
   it is **not** always equal to `graphPath` (an earlier draft of this plan overstated this
   citation; see the second addendum below, which traces a real bug back to conflating the two).
   The shadow-mirror fallback is resolved **deterministically at call time, from `config.repoRoot`
   specifically** (never from `graphPath`/`GraphId` — every JGit operation is keyed by
   `config.repoRoot`, so that is the only string the shadow-mirror machinery may ever derive from):
   given a `saf://<encodedTreeUri>/<relativePath>` string, `shadowKey` is derived by hashing the
   **full** `repoRoot` string (`sha256(repoRoot).take(16)`) — the same derivation basis
   `GraphManager.graphIdFromPath()` already uses for `GraphId` (`GraphManager.kt:315-316`), not
   just the tree-document-ID portion. (Revised from an earlier draft that hashed only the
   `encodedTreeUri`; see Blocker 3 fix below and ADR-018 addendum — hashing only the tree URI made
   two graphs sharing one SAF grant but pointing at different subfolders collide on the same
   shadow directory, corrupting both graphs' git state together.) The shadow root
   (`context.filesDir/graphs/$shadowKey/gitshadow`) follows deterministically from this key. This
   avoids any `MigrationRunner` schema change and keeps the whole feature invisible to
   `GitRepository`/`GitSyncService` (constraint: interfaces must not change, item 9).
2. **The path remap happens entirely inside `AndroidGitRepository`**, via a new
   `GitWorktreePathMapper` interface (`GitShadowWorktree` implements it directly — no separate
   field). `ConflictFile.filePath` and `MergeResult.changedFiles` are SAF-facing (never
   shadow-absolute) by the time they leave `AndroidGitRepository` — `GitSyncService`,
   `ConflictResolutionScreen`, and `GraphFileWatcher`'s suppression set need zero changes and stay
   unaware shadow-mirroring exists.
3. **The shadow→SAF write-back need is smaller and more targeted than "flush every changed file
   generically" implied.** Tracing the four write-producing code paths precisely:
   - `merge()` (clean or conflict-free after `checkoutFile`/`markResolved`): JGit itself checks
     out merged content into the shadow tree; **this is the one genuinely bulk case** — needs the
     durable write-back queue (Phase 3).
   - `checkoutFile()` (side-based conflict resolution, `resolveConflictBySide`): JGit checks out
     one file into the shadow tree; SAF never sees it today. Needs a single-file write-back right
     after checkout (Phase 3).
   - `resolveConflict()` (manual hunk resolution): `GitSyncService` already writes resolved
     content straight to SAF via `fileSystem.writeFile(filePath, ...)` *before* calling
     `gitRepository.markResolved(config, filePath)` — no new SAF write is needed here. The bug is
     the **opposite direction**: `markResolved()`'s `git.add()` stages whatever is currently in
     the *shadow* copy of that file, which still has the old content/conflict markers, since
     nothing has told the shadow tree about the just-written SAF content. Fix: `markResolved()`
     pulls the fresh SAF content into the shadow tree before `git.add()` (Phase 4). This also
     transparently fixes `applyJournalMerge()`, which has the identical write-then-`markResolved`
     ordering.
   - `abortMerge()` (`git reset --merge`): this **does** write to the working tree (resets tracked
     files that differ between pre-merge HEAD and merge state back to HEAD's content) and was
     originally miscategorized as a read-only op. See Blocker 4 fix (Phase 4, new Epic 4.2) — it
     needs a post-reset SAF reconciliation step, not a pre-op freshness check.
4. **`openGit()` becomes the single choke point for the freshness precondition.** All
   working-tree-touching methods already funnel through `openGit(config.repoRoot)` — **11 real
   call sites** in `AndroidGitRepository.kt` (corrected from an earlier miscount of 13: `isGitRepo`
   doesn't call `openGit` at all, and `push` was originally omitted from the enumeration below).
   Changing it to
   `private suspend fun openGit(config: GitConfig, requiresFreshWorkingTree: Boolean = true): Git`
   and calling `shadowWorktreeFor(config.repoRoot)?.ensureFresh(...)` there (see decision #6 below for what
   `shadowWorktreeFor` is) — defaulting to `true` — makes freshness a structural precondition for
   every method except the five that don't need a *pre-op* freshness check because they don't read
   stale working-tree content before acting (`fetch`, `log`, `push`, `abortMerge`,
   `hasDetachedHead`) — `abortMerge` still requires a *post-op* reconciliation step (design
   decision #3 above / Blocker 4), it just doesn't need the standard pre-op `ensureFresh`.
5. **`ensureFresh()` scans the whole `repoRoot` subtree, not a hardcoded subdir allowlist.** An
   earlier draft limited the freshness check to `listOfNotNull("pages", "journals",
   config.wikiSubdir?.substringBefore('/'))` — silently excluding top-level files (README,
   `.gitignore`), non-wiki directories, and anything nested more than one level under
   `wikiSubdir`, while `stageSubdir()`/`commit()` would still commit whatever stale content sat in
   those unchecked paths (Blocker 1). Fixed: freshness scanning and SAF→shadow sync are rooted at
   `config.repoRoot` as a whole and recurse through the actual directory structure returned by a
   single recursive SAF listing call, never a fixed name list — see the revised Epic 1.1/1.2 below.
   This is unavoidably a full-repo-tree operation (matching what JGit's own `status()`/`add()`
   already do against the working tree on every call), not a violation of `CLAUDE.md`'s
   graph-scale DB-read discipline, which governs bounded *database* reads for standing UI
   observers — a different problem from a git working-tree scan.
6. **`GitShadowWorktree` instances are resolved lazily, per-call, keyed off `config.repoRoot` —
   never constructed once at UI composition time keyed off `graphPath`.** (Second-round
   adversarial-review correction — this replaces an earlier draft's design, not an addition to
   it.) The earlier draft built a single `GitShadowWorktree` inside `MainActivity`'s
   `remember(graphPath)` block, at the same time `AndroidGitRepository` itself was constructed,
   and passed it in as fixed constructor arguments (`worktreePathMapper`/`shadowWorktree`). That
   block only has `graphPath` (the `GraphManager` identity string) synchronously available — not
   `config.repoRoot` (the actual, freely-user-edited git repo root every real JGit call uses),
   since loading `GitConfig` is itself async and wasn't gated before construction. Whenever
   `repoRoot != graphPath` (the ordinary "detected repo root above a nested wiki folder" case —
   see decision #1's correction, and Task 8.1.1c's own test for exactly this shape), the fixed,
   `graphPath`-keyed instance silently didn't match what `config.repoRoot` needed — reproducing
   the original cryptic-error bug (or, for `shadowKey`, causing two graphs sharing one configured
   `repoRoot` to wrongly get two separate shadow trees instead of one shared one). **Fix**:
   `AndroidGitRepository` takes a `context: Context` constructor parameter (always synchronously
   available at both construction sites — no config load needed) instead of pre-built
   `shadowWorktree`/`worktreePathMapper` instances, and internally maintains a small
   `ConcurrentHashMap<String /* shadowKey */, GitShadowWorktree>` cache. A private
   `shadowWorktreeFor(repoRoot: String): GitShadowWorktree?` method — called at the top of every
   method that needs shadow support — resolves (or lazily creates and caches) the worktree for
   *that call's* `config.repoRoot`, returning `null` when `pathResolver(config.repoRoot)` already
   resolves directly (fast path active, no shadow needed) or when `config.repoRoot` isn't a
   `saf://` path at all. This is correct by construction for any `repoRoot` value, including one
   that changes after the `AndroidGitRepository` instance was created (e.g. the user edits the
   repo-root field in `GitSetupScreen` after git sync is already configured) — something the
   construction-time-baked design could never have handled either. `GitWorktreePathMapper` usage
   (decision #2) now also always goes through `shadowWorktreeFor(config.repoRoot)`, never a stored field.
   See the rewritten Epic 2.1/5.1 below.

---

## Dependency Visualization

```
Phase 0 (groundwork: SSH bump, DomainError cases, GitWorktreeLocks holder)
   |  (independent — can run any time; GitWorktreeLocks has no dependency on Phase 1-4 output,
   |   placed here specifically so Phase 3's GitShadowFlushActor doesn't forward-reference Phase 5)
   v
Phase 1: GitShadowWorktree core (shadow root, batched SAF->shadow sync, freshness manifest,
         ensureFresh() choke point wired into AndroidGitRepository.openGit)
   |
   +--------------------------+-------------------------------+
   v                          v                               v
Phase 2: Path remapping    Phase 6: GraphManager           Phase 5 (Epic 5.1): pathResolver
  layer (GitWorktreePath-    lifecycle hooks + orphan         chain at both construction sites
  Mapper wired into merge/   sweep + storage-space guard      (needs GitShadowWorktree's
  checkoutFile/markResolved) (needs shadowKey scheme)          shadowKey derivation)
   |
   +--------------+---------------+
   v              v               v
Phase 3:        Phase 4:        Phase 5 (Epic 5.2):
 write-back      markResolved    mutual exclusion between
 queue + actor,  SAF->shadow     GitShadowWorktree ops and
 wired into      pull before     PlatformFileSystem's
 merge()/        git add         write-behind flush
 checkoutFile()
   |              |               |
   +--------------+---------------+
                  v
          Phase 7: Desktop non-regression (independent — can run in parallel with everything
                   above; only needs confirmation no jvmMain file was touched)
                  |
                  v
          Phase 8: Test infrastructure (unit tests per collaborator interleaved with Phases 1-6
                   as each lands; scale/crash-resilience and real-device validation last;
                   Epic 8.4 — real-transport/deep-integration gap closures — lands last of all,
                   since it needs Phase 0's Story 0.1.1 SSH bump plus every other phase's real
                   production code already in place)
```

---

## Phase 0: Groundwork

### Epic 0.1: Low-risk prep in files this project will touch anyway

**Goal**: Close the pre-existing SSH transport version gap and add the error taxonomy this
project's new failure modes need, before the shadow-tree changes land in the same files.

#### Story 0.1.1: Bump Android's stale JGit SSH transport module to match core
**As a** maintainer, **I want** `org.eclipse.jgit.ssh.jsch` on the same JGit release as core,
**so that** a 7.3.0-core / 5.13.3-transport version skew doesn't become a latent
`JschConfigSessionFactory`/`FS` API-incompatibility risk while this project is already editing
`AndroidGitRepository.kt`'s transport-adjacent code (finding #7, pitfalls.md §2.3).
**Acceptance Criteria**:
- `org.eclipse.jgit.ssh.jsch` resolves to `7.3.0.202506031305-r` in the Android dependency set.
- Existing SSH clone/fetch/push behavior is unchanged (verified in Phase 8's Android SSH tests —
  specifically Task 8.4.1b, `AndroidGitRepositorySshTransportTest.kt`, which exercises a real
  `clone()`/`fetch()`/`push()` round trip over this bumped transport against an embedded
  `SshTestGitServer`; Tasks 8.4.1a/8.4.1c give the same round-trip confirmation for local-file and
  HTTPS transports respectively).
**Files**: `kmp/build.gradle.kts`, `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 0.1.1a: Bump the SSH transport module version (~2 min)
- In `kmp/build.gradle.kts`, change the `org.eclipse.jgit.ssh.jsch` coordinate from
  `5.13.3.202401111512-r` to `7.3.0.202506031305-r` (keep the `exclude(group = "com.jcraft",
  module = "jsch")` block unchanged — the mwiede/jsch fork stays the sole jsch on the classpath).
  Update the adjacent comment to note the version now matches core.
- Files: `kmp/build.gradle.kts`

##### Task 0.1.1b: Fix the stale JGit-version class doc comment (~2 min)
- `AndroidGitRepository.kt:32`'s doc comment says "JGit 5.13.x + mwiede/jsch for SSH" — change to
  "JGit 7.3.0 (matches Desktop) + mwiede/jsch fork of jsch for SSH key format support (ED25519/
  ECDSA/OpenSSH)".
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

#### Story 0.1.2: Add shadow-worktree-specific `DomainError.GitError` cases
**As a** developer implementing Phases 1-6, **I want** dedicated, platform-neutral error variants
for shadow-sync failures, **so that** `GitSyncService` can react to them distinctly from a network
or auth failure, per the existing `Either`-at-boundaries convention (`CLAUDE.md`).
**Acceptance Criteria**:
- Three new `DomainError.GitError` subtypes exist, named platform-neutrally (not "SAF" or "shadow"
  in a way that would leak Android specifics into a shared sealed interface a future in-memory
  engine also uses, per `research/features.md` §4).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt`

##### Task 0.1.2a: Add `WorkingTreeSyncFailed`, `WorkingTreeWriteBackFailed`, `WorkingTreeConcurrentEditDetected` (~3 min)
- Add to the `GitError` sealed interface (alongside the existing `StaleLockFile`/`FileTooLarge`
  shape):
  ```kotlin
  data class WorkingTreeSyncFailed(val direction: String, val path: String, override val message: String) : GitError
  data class WorkingTreeWriteBackFailed(val path: String, override val message: String) : GitError
  data class WorkingTreeConcurrentEditDetected(val path: String) : GitError {
      override val message: String = "Local file changed during sync: $path"
  }
  ```
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt`

#### Story 0.1.3: `GitWorktreeLocks` — the shared per-graph mutex holder
**As a** developer, **I want** a single, dependency-free mutex-per-`shadowKey` holder available
from Phase 0 onward, **so that** both Phase 3's write-back actor and Phase 5's SAF→shadow sync can
acquire it without either phase creating a forward reference to the other (this object has no
dependency on `GitShadowWorktree`/`AndroidGitRepository`/anything else this project builds, so
there's no reason it needs to wait for those to land first — see Epic 5.2's note for why this was
moved here instead of staying inside Epic 5.2 where an earlier draft placed it).
**Acceptance Criteria**: `GitWorktreeLocks.lockFor(shadowKey)` returns the same `Mutex` instance
for the same `shadowKey` across calls.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/GitWorktreeLocks.kt` (new)

##### Task 0.1.3a: `GitWorktreeLocks` holder (~3 min)
- New tiny file:
  ```kotlin
  internal object GitWorktreeLocks {
      private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
      fun lockFor(shadowKey: String): kotlinx.coroutines.sync.Mutex =
          locks.getOrPut(shadowKey) { kotlinx.coroutines.sync.Mutex() }
  }
  ```
  Placed in `platform` (not `git`) since `PlatformFileSystem` (Task 5.2.1c) needs to reference it
  without a `platform` → `git` package dependency.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/GitWorktreeLocks.kt` (new)

---

## Phase 1: Shadow Worktree Core

### Epic 1.1: New `GitShadowWorktree` collaborator (finding #4, ADR-018)
**Goal**: A real `java.io.File`-backed working-tree root, disjoint from `ShadowFileCache`'s
indexing cache, that JGit can operate on directly.

#### Story 1.1.1: Shadow root, safe-path handling, and the path-mapper contract
**As a** developer, **I want** a dedicated shadow-worktree class with its own storage root and
path-traversal guard, **so that** the git working tree never collides with `ShadowFileCache`'s
pages/journals index cache (ADR-018).
**Acceptance Criteria**:
- `context.filesDir/graphs/$shadowKey/gitshadow` is used, never `.../shadow` (the existing
  `ShadowFileCache` root).
- Path-traversal is blocked the same way `ShadowFileCache.safeShadowFile` blocks it.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt` (new)

##### Task 1.1.1a: Create `GitShadowWorktree.kt` skeleton (~5 min)
- New file, package `dev.stapler.stelekit.git`. **Class visibility, corrected after round-10
  adversarial review**: `class GitShadowWorktree(...)` (plain public, not `internal`) — an earlier
  draft declared it `internal class`, which compiles fine for every call site *within* `:kmp`
  (`AndroidGitRepository`, `GitShadowFlushActor`, both same module) but breaks Task 6.1.1c, which
  has `MainActivity.kt` (in the separate `:androidApp` module) call
  `GitShadowWorktree.sweepOrphans(applicationContext)` by name — the exact same cross-module
  `internal`-visibility defect Tasks 2.1.1a/5.1.2a took rounds 8-10 to resolve for
  `AndroidGitRepository.fileSystem`, recurring here on the class declaration itself. Constructor:
  `class GitShadowWorktree(context: Context, internal val shadowKey: String, private val safRoot: String, private val fileSystem: FileSystem)`
  (`safRoot` — Task 1.1.1b's mapper basis — declared here, not left implicit; the sole caller,
  `AndroidGitRepository.shadowWorktreeFor(repoRoot)` at Task 1.2.3a, passes its `repoRoot`
  argument for both `shadowKey`'s derivation and this `safRoot` value. `shadowKey` itself stays
  `internal`, not `private` — `GitShadowFlushActor` (Task 3.1.2a) reads it, and that class lives in
  the *same* module, `:kmp`, so `internal` is correctly scoped there, unlike the class-level
  visibility issue this note corrects).
  `private val worktreeRoot = File(context.filesDir, "graphs/$shadowKey/gitshadow")`, `init { worktreeRoot.mkdirs() }`.
  Also add `val worktreeRootPath: String get() = worktreeRoot.absolutePath` (the public accessor
  Task 1.2.3b's `resolveForJGit` fallback and Task 6.2.1a's `StatFs` check both need — declaring it
  here, at the same place `worktreeRoot` itself is defined, rather than leaving it implicit).
  Port `ShadowFileCache.safeShadowFile`'s exact path-traversal-guard logic
  (`ShadowFileCache.kt:46-53`) as a private `safeWorktreeFile(relativePath: String): File?` method.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt` (new)

##### Task 1.1.1b: Define `GitWorktreePathMapper` + implement it on `GitShadowWorktree` (~5 min)
- In the same file, add:
  ```kotlin
  interface GitWorktreePathMapper {
      /** Maps a shadow-tree-absolute path (as AndroidGitRepository builds from config.repoRoot)
       *  to the SAF-facing path GitSyncService/UI/GraphFileWatcher must use. */
      fun toUserFacingPath(shadowAbsolutePath: String): String
      /** Inverse: maps a SAF-facing path back to the git-relative path JGit's checkout()/add() need. */
      fun toGitRelativePath(userFacingPath: String): String
  }
  ```
  `GitShadowWorktree` implements it: `toUserFacingPath` strips `worktreeRoot.absolutePath + "/"`
  and prepends `safRoot + "/"`; `toGitRelativePath` strips `safRoot + "/"` from the input (falling
  back to treating the input as already-relative if the prefix isn't present, matching the
  existing lenient `removePrefix` pattern at `AndroidGitRepository.kt:353,368`).
  `safRoot: String` is a constructor parameter (the SAF `repoRoot` string this worktree mirrors —
  see Task 1.2.3a's `shadowWorktreeFor(repoRoot)`, the sole place `GitShadowWorktree` instances are
  created, which passes its own `repoRoot` argument straight through). Construction is
  per-call-`repoRoot`-keyed, not baked in at UI composition time, per decision #6.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`

##### Task 1.1.1c: `core.fileMode = false` helper (~3 min, pitfall §2.4)
- Add `fun disableFileModeTracking(repo: org.eclipse.jgit.lib.Repository)` that sets
  `repo.config.setBoolean("core", null, "fileMode", false); repo.config.save()`. Not called yet —
  wired in Task 1.2.3e at `init()`/`clone()`.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`

#### Story 1.1.2: Batched, bounded SAF→shadow sync of the whole `repoRoot` subtree
**As a** developer, **I want** the SAF→shadow direction to reuse `ShadowFileCache`'s existing
mtime-skip pattern but scan the **entire** repo root (not a hardcoded subdir list), **so that**
freshness sync/commit correctness covers every file JGit could stage, not just `pages`/`journals`
(Blocker 1 fix — an allowlisted subdir approach silently missed top-level files, non-wiki dirs,
and deeply-nested `wikiSubdir` paths). Unchanged files are still skipped via mtime, so this stays
O(changed files) for the copy work itself even though the *listing* walk is O(repo-tree-size) —
inherent to correctness here, not a violation of `CLAUDE.md`'s bounded-DB-read discipline (see
design decision #5).
**Acceptance Criteria**:
- A single recursive SAF listing call enumerates every file under `repoRoot` (excluding `.git`).
- Unchanged files (mtime match) are not re-read/re-written.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`

##### Task 1.1.2a: `syncFromSafRoot()` (~5 min)
- `suspend fun syncFromSafRoot(listRecursive: suspend (String) -> List<Pair<String, Long>>, readSafFile: suspend (String) -> String?)`
  — `listRecursive(repoRoot)` returns every file's `(relativePath, mtime)` under `repoRoot`
  (relative paths use `/`, never include a leading `.git/` entry — the recursive walk must skip
  any directory literally named `.git` at the root, since that's JGit's own metadata, not mirrored
  wiki content). For each entry not under `.git`: skip if shadow mtime already `>=` SAF mtime,
  otherwise read + `writeText` + `setLastModified(safMtime)`. Also delete any shadow-tracked file
  no longer present in the `listRecursive` result (handles SAF-side deletions — a gap the earlier
  subdir-list draft didn't address either, now fixed as part of this rewrite). Runs on
  `PlatformDispatcher.IO`. The `listRecursive` implementation (walking `DocumentFile` children
  recursively via `PlatformFileSystem`) is a new primitive — `stack.md` §3 already flagged that no
  batch/recursive SAF listing primitive exists yet; this task is where it's introduced, scoped
  narrowly to what this feature needs rather than a general-purpose API.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`

##### Task 1.1.2b: Dual mtime+size staleness check (~4 min)
- Port `ShadowFileCache.invalidateStale`'s dual-signal logic (`ShadowFileCache.kt:143-155`) into a
  `private fun isEntryStale(shadowFile: File, safMtime: Long, safSize: Long): Boolean` used by
  `syncFromSafRoot` in place of the mtime-only skip check from 1.1.2a, so a SAF provider
  reporting a stale mtime (the Termux-while-backgrounded case documented in `ShadowFileCache.kt:136-138`)
  is still caught via size mismatch.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`

### Epic 1.2: Freshness manifest + runtime-checked precondition (finding #2)
**Goal**: Turn "shadow must be re-synced from SAF immediately before any JGit op" from a
documented calling convention into a structurally-enforced one (pitfalls.md §4.3, the
highest-severity risk identified in research).

#### Story 1.2.1: Manifest persistence
**As a** developer, **I want** a small on-disk record of the SAF mtime/size each shadow file was
last synced against, **so that** freshness can be checked cheaply without re-listing SAF on every
call.
**Acceptance Criteria**: manifest survives process restart; one file per shadow worktree.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`

##### Task 1.2.1a: Manifest data type + read/write (~5 min)
- Add `@Serializable data class SyncManifestEntry(val relativePath: String, val safMtime: Long, val safSize: Long)`
  and `@Serializable data class SyncManifest(val entries: List<SyncManifestEntry>)` (kotlinx.serialization,
  already on the classpath per other `@Serializable` usage in `ConflictModels.kt`). Store at
  `File(worktreeRoot, ".sync-manifest.json")` (dot-prefixed so it's never mistaken for tracked wiki
  content). Private `readManifest(): SyncManifest` / `writeManifest(m: SyncManifest)` using
  `kotlinx.serialization.json.Json`.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`

##### Task 1.2.1b: Write the manifest after every successful sync (~3 min)
- `syncFromSafRoot` (1.1.2a) accumulates the `(relativePath, safMtime, safSize)` triples it
  processed (including skipped-as-fresh ones) and calls `writeManifest` once at the end of the
  call, replacing the manifest wholesale (a root-wide sync is always a complete listing, unlike
  the earlier per-subdir draft — so there's no partial-update merge case to handle here).
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`

#### Story 1.2.2: `isFresh()` / `ensureFresh()`
**As a** developer, **I want** a single method that verifies (and if needed, restores) shadow
freshness across the whole `repoRoot` subtree, **so that** every JGit call site has one thing to
call instead of repeating sync logic, and no path under `repoRoot` is silently exempt (Blocker 1).
**Acceptance Criteria**: a stale shadow is detected and resynced before returning; a fresh shadow
short-circuits without extra SAF reads beyond the one recursive listing call.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`

##### Task 1.2.2a: `isFresh()` (~5 min)
- `suspend fun isFresh(listRecursive: suspend (String) -> List<Pair<String, Long>>): Boolean` —
  `listRecursive(repoRoot)` returns every `(relativePath, mtime)` under the root (same recursive
  walk 1.1.2a introduces), compared against the stored manifest (mtime AND, where available, size
  — size isn't in the listing callback's return type, so this check is mtime-only here; the size
  check happens inside `syncFromSafRoot` itself during the actual resync, per 1.1.2b). Also
  returns `false` if the listing's entry count differs from the manifest's entry count (catches
  SAF-side deletions/additions the mtime-only comparison alone would miss). Returns `false` on the
  first mismatch or missing manifest entry.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`

##### Task 1.2.2b: `ensureFresh()` (~4 min)
- `suspend fun ensureFresh(listRecursive: ..., readSafFile: ...)` calls `isFresh`; if `false`,
  calls `syncFromSafRoot` with the same arguments. This is the runtime-enforced precondition — no
  caller may skip it and proceed to a JGit working-tree op.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`

#### Story 1.2.3: Wire `ensureFresh()` into `AndroidGitRepository`'s single choke point
**As a** developer, **I want** every working-tree-touching JGit call to pass through a freshness
check automatically, **so that** no future call site can accidentally skip it (closes requirements.md
Open Question 1).
**Acceptance Criteria**: `status`, `stageSubdir`, `commit`, `merge`, `checkoutFile`, `markResolved`
all call `ensureFresh` before touching JGit; `fetch`, `log`, `push`, `abortMerge`, `hasDetachedHead`
do not run the pre-op check (`push`/`fetch`/`log`/`hasDetachedHead` don't read/write working-tree
content at all; `abortMerge` gets a *post-op* reconciliation step instead — Epic 4.2).
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 1.2.3a: Add `context`, the shadow-worktree cache, and `shadowWorktreeFor(repoRoot)` (~5 min)
- Add `private val context: Context` and
  `private val shadowWorktrees = java.util.concurrent.ConcurrentHashMap<String, GitShadowWorktree>()`
  as new fields (constructor param for `context` — see Phase 2's Story 2.1.1, which replaces the
  earlier `worktreePathMapper`/`shadowWorktree` constructor params with this single `context`
  param; stub `context` as a constructor param here with no default so this task compiles
  standalone against Story 2.1.1 landing first, or land them together). Add:
  ```kotlin
  private fun shadowWorktreeFor(repoRoot: String): GitShadowWorktree? {
      if (pathResolver(repoRoot) != null) return null // fast path resolves directly, no shadow needed
      if (!repoRoot.startsWith("saf://")) return null
      val key = GitShadowWorktree.shadowKeyForSafPath(repoRoot)
      return shadowWorktrees.getOrPut(key) {
          GitShadowWorktree(context, key, repoRoot, fileSystem)
      }.also { it.touchLastUsed() } // Task 6.1.1a — refreshes the orphan-sweep liveness marker on every real resolution
  }
  ```
  Deliberately takes a plain `repoRoot: String`, not a `GitConfig` — every value this function
  actually needs (`pathResolver` input, `shadowKeyForSafPath` input, `safRoot` constructor arg) is
  `config.repoRoot` itself, so there is no `GitConfig` field to accidentally substitute the wrong
  identity string for (this is precisely the class of bug — `graphPath` vs. `config.repoRoot` —
  the second-round adversarial review found; narrowing the parameter type makes it structurally
  unavailable rather than just documented against). This is the single place shadow-mirror
  resolution happens — keyed off *this call's* `repoRoot` argument, never a value captured once at
  construction time (decision #6's fix).
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 1.2.3b: Make `resolveForJGit` shadow-aware (~4 min — the actual fix that lets JGit
target the shadow directory; `shadowWorktreeFor` alone only helps `ensureFresh`, not the `Git`
instance's own working-tree location)
- The existing helper (per `research/stack.md` §2, `AndroidGitRepository.kt:~415`) resolves as
  `private fun resolveForJGit(repoRoot: String): String = pathResolver(repoRoot) ?: repoRoot` —
  falling back to the raw, unresolvable `saf://...` string when `pathResolver` fails, which is the
  literal original bug (`git.repository not found: /saf:/content%3A...`). Change it to also try
  the shadow worktree before giving up:
  ```kotlin
  private fun resolveForJGit(repoRoot: String): String =
      pathResolver(repoRoot) ?: shadowWorktreeFor(repoRoot)?.worktreeRootPath ?: repoRoot
  ```
  This single change is what makes `Git.open()`/`Git.init()`/`Git.cloneRepository()` — every JGit
  entry point that resolves a working-tree directory, not just `openGit`'s `ensureFresh` call —
  automatically target the shadow directory whenever `shadowWorktreeFor` says shadow-mirror mode
  is active, since all of those call sites already funnel through `resolveForJGit` (confirmed:
  `isGitRepo` calls it directly per Task 1.2.3d's note below; `clone()`/`init()` are expected to
  via the same helper — verify this at implementation time and route them through it if a call
  site currently resolves the directory a different way). Uses `GitShadowWorktree.worktreeRootPath`
  (the public accessor Task 1.1.1a declares alongside `worktreeRoot` itself).
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 1.2.3c: Make `openGit` suspend and freshness-aware (~5 min)
- Change `private fun openGit(repoRoot: String): Git = Git.open(File(resolveForJGit(repoRoot)))`
  to:
  ```kotlin
  private suspend fun openGit(config: GitConfig, requiresFreshWorkingTree: Boolean = true): Git {
      if (requiresFreshWorkingTree) {
          shadowWorktreeFor(config.repoRoot)?.ensureFresh(
              listRecursive = { root -> fileSystem.listFilesRecursiveWithModTimes(root) },
              readSafFile = { relPath -> fileSystem.readFile("${config.repoRoot}/$relPath") },
          )
      }
      return Git.open(File(resolveForJGit(config.repoRoot)))
  }
  ```
  `resolveForJGit` is now shadow-aware per 1.2.3b, so this correctly opens the shadow directory
  whenever shadow-mirror mode is active — this task only adds the `ensureFresh` call and the
  `requiresFreshWorkingTree` parameter; it does not need to duplicate 1.2.3b's path-resolution
  fallback itself. Requires `fileSystem: FileSystem` as a constructor param (already present) and
  1.2.3a's `shadowWorktreeFor`. `listFilesRecursiveWithModTimes` is the new recursive-listing
  primitive 1.1.2a's note introduces — **add it to the shared `commonMain` `FileSystem` interface
  (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt`) with a default body**
  (recursing via the interface's existing `listFiles`/`listDirectories`/`getLastModifiedTime`
  primitives — mirror whichever existing mod-time helper on this interface already follows this
  default-body-on-the-shared-interface pattern), so only Android's SAF-aware
  `PlatformFileSystem` override is new, non-default code. This matters because Phase 7's
  non-regression check (Task 7.1.1a) asserts a zero `jvmMain` diff — an interface member with no
  default would force `JvmFileSystem` (and the iOS/wasmJs actuals) to add a matching override just
  to keep compiling, which would break that acceptance criterion for a method Desktop never needs.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`,
  `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`,
  `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt`

##### Task 1.2.3d: Update the 11 `openGit(config.repoRoot)` call sites (~5 min)
- `status`, `stageSubdir`, `commit`, `merge`, `checkoutFile`, `markResolved` (6) → `openGit(config)`
  (default `requiresFreshWorkingTree = true`).
- `fetch`, `log`, `push`, `abortMerge`, `hasDetachedHead` (5) →
  `openGit(config, requiresFreshWorkingTree = false)`. (`push` was missing from the original
  enumeration — added here; it only touches refs/objects over the network, no working-tree read.)
- `isGitRepo` currently uses `File(resolveForJGit(path), ...)` directly, not `openGit` — leave it
  as-is (it only checks `.git` existence, no working-tree read; not counted in the 11).
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 1.2.3e: Initial SAF-overlay sync after `clone()`/`init()` (~5 min, resolves Open Question 5)
- **`init(repoRoot: String)` and `clone(url, localPath, auth, onProgress)` (`AndroidGitRepository.kt:55,67`)
  are `GitRepository` interface overrides that receive a raw path string, not a `GitConfig` — there
  is no `config` in scope here (`GitConfig` doesn't exist until the user saves the Git Sync setup
  form after a successful init/clone). Use the method's own parameter**: after a successful
  `Git.init()` call, resolve `val worktree = shadowWorktreeFor(repoRoot)` (using `init`'s own
  `repoRoot` parameter); after a successful `Git.cloneRepository()` call, resolve
  `val worktree = shadowWorktreeFor(localPath)` (using `clone`'s own `localPath` parameter). In
  either case, when `worktree` is non-null, call `worktree.syncFromSafRoot(...)` unconditionally
  (bypassing the freshness check — there is no prior manifest yet) so pre-existing SAF markdown
  lands in the freshly created/cloned shadow tree before the user's first `status()`/`commit()`
  (both of which *do* receive a `GitConfig` later, once the setup form is saved). This makes "SAF
  already had files, user picks clone" behave identically to "user already had a local clone with
  uncommitted local edits" — a case JGit already handles correctly (`status()` shows modifications,
  first commit captures them). Also call `disableFileModeTracking()` (1.1.1c) here.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

---

## Phase 2: Path Remapping Layer (finding #1)

### Epic 2.1: Remap `repoRoot`-derived paths to SAF-facing paths at the `AndroidGitRepository` boundary

#### Story 2.1.1: Wire `context` into `AndroidGitRepository`'s constructor (replaces a rejected
earlier draft that wired pre-built `worktreePathMapper`/`shadowWorktree` instances instead)
**As a** developer, **I want** `AndroidGitRepository` to hold only a `context: Context` and derive
everything shadow-related per-call via `shadowWorktreeFor(config.repoRoot)` (Task 1.2.3a), **so that**
shadow-mirror behavior is opt-in per call (not per instance) and Desktop/direct-access Android
stay byte-for-byte unchanged (constraint: no `GitRepository` interface change, finding #9) — and
so that `repoRoot != graphPath` (decision #6) can never desync a stored field from what a given
call actually needs, since nothing is stored per-instance in the first place.
**Acceptance Criteria**: when `pathResolver` always resolves directly (fast path) or `repoRoot`
never starts with `saf://`, `shadowWorktreeFor()` always returns `null` and every method's
behavior is identical to before this project (verified in Phase 8.2).
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 2.1.1a: Add the `context` constructor param, and remove `fileSystem`'s unsafe default
(~4 min — widened after round-6 adversarial review found the default let a construction site
silently wire a dead `PlatformFileSystem` instance without any compile-time signal)
```kotlin
class AndroidGitRepository(
    private val sshKeyProvider: (() -> ByteArray)? = null,
    credentialAccess: CredentialAccess = CredentialStore(),
    private val pathResolver: (String) -> String? = { null },
    val fileSystem: FileSystem,
    private val context: Context,
) : GitRepository {
    private val shadowWorktrees = java.util.concurrent.ConcurrentHashMap<String, GitShadowWorktree>()
    // shadowWorktreeFor(config.repoRoot): GitShadowWorktree? — see Task 1.2.3a
```
`context` has no default since it's always available at both real construction sites
(`MainActivity`, `WorkManagerSyncScheduler`) and only test fakes need to supply one explicitly (a
Robolectric `ApplicationProvider.getApplicationContext()` — see Phase 8.2). **`fileSystem`'s
default (`= PlatformFileSystem()`) is deliberately removed, not just documented against** — round 6
found this exact default let Task 5.1.2a's `MainActivity` wiring silently construct a fresh,
never-`.init()`-ed `PlatformFileSystem()` that reads/writes nothing real (see that task's fix
below); a compile error forcing every construction site to pass an explicit, real `FileSystem`
instance is a structural guardrail against this recurring, whereas a default value only
guards against forgetting to pass one until someone actually forgets. (`isGitRepo`, `merge`, etc.
already require the caller to have a real filesystem for anything to work at all, so this default
was never actually safe to fall back to in production — only convenient for pre-shadow-mirror-era
tests, which will need updating to pass an explicit fake either way.)

**`fileSystem` is plain `val` (public), not `private val`** (widened after round-8 adversarial
review, corrected after round 9 found the first attempt at this fix — `internal val` — was itself
wrong). Task 5.1.2a's regression test needs to read back the `fileSystem` instance a constructed
`AndroidGitRepository` was actually given, to assert it's `app.fileSystem` and not a disconnected
instance. **`internal` does not work for this**: Kotlin's `internal` visibility scopes to a single
Gradle module's own compilation, not to modules that merely depend on it — `internal` in `:kmp`
is *not* visible from `:androidApp` even though `:androidApp` depends on `:kmp`
(confirmed against Kotlin's own visibility-modifier documentation; `:kmp` and `:androidApp` are
separate Gradle modules per `settings.gradle.kts`, with no `friendPaths`/`associate` compiler
config anywhere in either module's build script that would extend `internal` visibility across
that boundary — the earlier round-8 fix's claim that "internal declarations in `:kmp` are visible
to `:androidApp`'s test sources" was simply incorrect, and no existing code in this repo crosses
the `:kmp`/`:androidApp` boundary with an `internal` member to contradict that). Making
`fileSystem` a plain, public `val` is the minimum change that actually compiles across the module
boundary — `AndroidGitRepository` is itself a public class already, so this doesn't newly expose
anything at a coarser grain than the class's own existing public surface.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

#### Story 2.1.2: Remap `merge()`'s outputs
**As a** user resolving a merge conflict, **I want** the conflict list and changed-files list to
show my SAF-side paths, **so that** the UI and every downstream consumer (`GraphFileWatcher`
suppression, `GitSyncService.reloadFiles`) operate on paths that actually resolve.
**Acceptance Criteria**: `ConflictFile.filePath` and `MergeResult.changedFiles` are SAF-facing
strings whenever `shadowWorktreeFor(config.repoRoot) != null`; unchanged (repoRoot-absolute) otherwise.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 2.1.2a: Map `ConflictFile.filePath` (~4 min)
- In `merge()` (`AndroidGitRepository.kt:216-228`), compute `val worktree = shadowWorktreeFor(config.repoRoot)`
  once at the top of the method (same instance `openGit`'s `ensureFresh` call already resolved —
  `GitShadowWorktree`'s `ConcurrentHashMap` cache in 2.1.1a guarantees this is the same object, not
  a second freshness-check side effect), then change
  `val absolutePath = "${config.repoRoot}/$filePath"` to also apply the mapper:
  `val absolutePath = worktree?.toUserFacingPath("${config.repoRoot}/$filePath") ?: "${config.repoRoot}/$filePath"`
  (`GitShadowWorktree` implements `GitWorktreePathMapper` directly, per Task 1.1.1b — no separate
  mapper instance). `wikiRelPath` computation is untouched (it's already purely `wikiSubdir`-relative,
  per `research/stack.md` §2's finding that this field already works correctly).
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 2.1.2b: Map `changedFiles` (~3 min)
- In `merge()`'s diff-scan block (`AndroidGitRepository.kt:244-249`), apply the same `worktree`
  (2.1.2a's local val) to each entry:
  `.map { worktree?.toUserFacingPath("${config.repoRoot}/${it.newPath}") ?: "${config.repoRoot}/${it.newPath}" }`.
  The subsequent `wikiChangedFiles` filter (`AndroidGitRepository.kt:262-266`) still filters on the
  `${config.repoRoot}/${config.wikiSubdir}/` prefix — since the mapper only changes the *root*
  segment, not the relative structure, this filter continues to work unchanged as long as it runs
  **before** mapping (reorder if needed so the wiki-subdir filter sees repoRoot-absolute paths,
  then map only the filtered result).
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

#### Story 2.1.3: Remap `checkoutFile()`/`markResolved()` inputs
**As a** developer, **I want** these two methods to accept the same SAF-facing paths `merge()` now
produces, **so that** the round trip (`ConflictFile.filePath` out → user resolves → same path back
in via `resolveConflictBySide`) works.
**Acceptance Criteria**: passing a SAF-facing path into either method resolves to the correct
git-relative path for `git.checkout()`/`git.add()`.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 2.1.3a: `checkoutFile()` (~4 min)
- At the top of `checkoutFile()`, compute `val worktree = shadowWorktreeFor(config.repoRoot)`. Replace
  `filePath.removePrefix("${config.repoRoot}/")` (`AndroidGitRepository.kt:353`) with
  `worktree?.toGitRelativePath(filePath) ?: filePath.removePrefix("${config.repoRoot}/")`.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 2.1.3b: `markResolved()` (~3 min)
- Same pattern (`val worktree = shadowWorktreeFor(config.repoRoot)` at the top, same replacement) at
  `AndroidGitRepository.kt:368`.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

#### Story 2.1.4: Verify the shared interfaces truly didn't change (finding #9)
**As a** reviewer, **I want** explicit confirmation that `GitRepository`/`GitSyncService` are
untouched, **so that** the future in-memory git engine's integration surface (per requirements.md's
explicit decoupling ask) isn't quietly widened by this project.
**Acceptance Criteria**: zero diff in `GitRepository.kt`/`GitSyncService.kt`; `ConflictModels.kt`
gains only a doc comment, no field/type changes.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/ConflictModels.kt`

##### Task 2.1.4a: Doc-comment the `ConflictFile` path contract + diff review (~4 min)
- Add a doc comment above `ConflictFile` in `ConflictModels.kt`: `filePath` is a
  platform-internal working-tree path (may be shadow-absolute on Android in shadow-mirror mode —
  never assume it is directly SAF/filesystem-openable without going through the platform's
  `GitRepository` implementation) and `wikiRelativePath` is always safe to display/write against
  the user-facing wiki root, regardless of platform. Then run
  `git diff -- kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitRepository.kt kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`
  and confirm it is empty before closing this task.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/ConflictModels.kt`

---

## Phase 3: Shadow → SAF Write-Back (finding #5)

### Epic 3.1: Durable write-back queue + actor (models `ShadowFlushActor`/`WriteBehindQueue`, fixes their non-atomic dequeue)

#### Story 3.1.1: `GitWriteBackQueue`
**As a** developer, **I want** a persistent, crash-safe queue of pending shadow→SAF write-backs,
**so that** a process death mid-flush is resumable (pitfalls.md §3.2, §4.2), improving on
`WriteBehindQueue.dequeue()`'s non-atomic full-file rewrite (`WriteBehindQueue.kt:31-40`).
**Acceptance Criteria**: `dequeue()` never leaves the backing file in a partially-written state
even if the process dies mid-write (temp-file + atomic rename).
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitWriteBackQueue.kt` (new)

##### Task 3.1.1a: Create `GitWriteBackQueue.kt` (~5 min)
- Model directly on `WriteBehindQueue.kt`'s shape (`enqueue`/`dequeue`/`getAll`/`isEmpty`, same
  `ReentrantLock`-guarded plain-text-file backing), but implement `dequeue` as: write the filtered
  line list to `File(queueFile.parentFile, "${queueFile.name}.tmp")`, then
  `tmpFile.renameTo(queueFile)` (atomic on the same filesystem) instead of
  `queueFile.writeText(...)` directly.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitWriteBackQueue.kt` (new)

#### Story 3.1.2: `GitShadowFlushActor`
**As a** developer, **I want** a per-file drain routine that detects concurrent SAF edits before
overwriting, **so that** a widget/share-target/other-app write racing the write-back is never
silently clobbered (pitfalls.md §4.1, the hard constraint from requirements.md).
**Acceptance Criteria**: writing back a file whose live SAF mtime is newer than the manifest's
recorded mtime is refused, not overwritten.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowFlushActor.kt` (new)

##### Task 3.1.2a: Create `GitShadowFlushActor.kt` (~5 min)
- Model on `ShadowFlushActor.kt`'s `flush()`/`flushPage()` structure. `flush()`'s body is wrapped
  in `GitWorktreeLocks.lockFor(shadowKey).withLock { ... }` (Task 0.1.3a's holder — this is the
  write-back direction's lock coverage; see Task 5.2.1b, which acquires the lock for the
  SAF→shadow direction inside `syncFromSafRoot()` itself, not here). For each queued relative
  path: read shadow content (from the `GitShadowWorktree` instance it's constructed against),
  fetch the live SAF mtime via
  `fileSystem.getLastModifiedTime(safPath)`, compare against the manifest's recorded `safMtime`
  for that path (1.2.1a) — if the live mtime is newer, return early without writing (handled by
  3.1.2b). Otherwise `fileSystem.writeFile(safPath, content)`, dequeue on success, update the
  manifest entry for that path with the post-write mtime (mirrors `ShadowFlushActor.kt:91-93`'s
  `stampMtime` pattern).
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowFlushActor.kt` (new)

##### Task 3.1.2b: Concurrent-edit detection returns a typed error (~4 min)
- When the mtime-race check in 3.1.2a fires, `flushPage` returns
  `DomainError.GitError.WorkingTreeConcurrentEditDetected(safPath).left()` instead of silently
  skipping. `flush()` collects these into a `List<Either<...>>` so the caller (Phase 3.2) can route
  them into `ConflictPending` rather than reporting a bare sync failure.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowFlushActor.kt`

##### Task 3.1.2c: Non-race write failure returns `WorkingTreeWriteBackFailed`, not a silent retry
(~3 min — closes a construction-site gap validation.md's Gap #7 found: `WorkingTreeWriteBackFailed`
was defined in Task 0.1.2a and referenced by Task 3.2.1b's *caller*-side handling, but 3.1.2a never
actually specified where it gets constructed)
- `flushPage`'s write step is modeled on `ShadowFlushActor.flushPage()`'s real shape (confirmed at
  `ShadowFlushActor.kt:78-103`): `val ok = fileSystem.writeFile(safPath, content)`, then
  `if (ok) { ... } else { /* log + leave queued for retry */ }`. That existing sibling class's
  `else` branch only logs (`Log.w(TAG, "flushPage: SAF write failed for $relativePath — will
  retry")`) — silent from the caller's perspective. `GitShadowFlushActor.flushPage` must not repeat
  that silence: when `ok` is `false` (a real SAF write failure, distinct from 3.1.2b's mtime-race
  check, which fires *before* this write is even attempted), return
  `DomainError.GitError.WorkingTreeWriteBackFailed(safPath, "SAF write failed for $safPath").left()`.
  Do **not** call `queue.dequeue()` on this path (matches `ShadowFlushActor`'s existing
  retry-by-leaving-queued semantics — the item is retried on the next `flush()` call) — only the
  *caller* now additionally learns about the failure via `flush()`'s collected `List<Either<...>>`
  (3.1.2b's mechanism, which this task's `Either` also flows through), so `merge()`/`checkoutFile()`
  (Task 3.2.1b) can surface it distinctly instead of a write failure being invisible until the next
  successful drain.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowFlushActor.kt`

### Epic 3.2: Wire write-back into `merge()` and `checkoutFile()`

#### Story 3.2.1: Bulk write-back after a clean merge
**As a** user, **I want** files a pull merged in to actually appear in my SAF folder, **so that**
the editor/graph I see reflects what was just synced (closes the live bug in
`research/features.md`'s edge case 1 / `research/architecture.md` §3's flow (a) step 7.5).
**Acceptance Criteria**: `MergeResult.changedFiles` are written to SAF before `sync()`'s
`reloadFiles()` step runs (order matters — see `research/architecture.md` §3's flow (a)).
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 3.2.1a: Enqueue + flush changed files before returning from `merge()` (~5 min)
- After computing `wikiChangedFiles` (pre-mapping, shadow-relative form) in `merge()`, reuse the
  `worktree = shadowWorktreeFor(config.repoRoot)` already resolved earlier in the method (2.1.2a): when
  non-null, for each file, enqueue its git-relative path into a `GitWriteBackQueue` instance held
  by `worktree`, then call `GitShadowFlushActor(...).flush()`. Only after this completes, build the
  mapped `MergeResult`
  (2.1.2b) and return it — this ordering is what guarantees `GitSyncService.sync()`'s subsequent
  `reloadFiles()` (which runs on the caller's mapped/SAF paths) reads content that is actually on
  disk in SAF.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 3.2.1b: Surface write-back failures distinctly (~3 min)
- If `flush()` reports any `WorkingTreeWriteBackFailed`/`WorkingTreeConcurrentEditDetected`
  entries, `merge()` returns that error (concurrent-edit case) or logs+continues-with-warning
  (transient write-back failure case, since the queue retains the entry for the next sync's
  drain-at-start — see Task 3.2.1a's queue persisting across calls) rather than masking it as a
  generic `FetchFailed`.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

#### Story 3.2.2: Single-file write-back after side-based conflict checkout
**As a** user who resolves a conflict by picking "keep mine"/"keep theirs", **I want** that choice
to land in my SAF file, **so that** `resolveConflictBySide()` doesn't leave SAF at the pre-resolution
state (pitfalls.md §4.1's second concrete instance).
**Acceptance Criteria**: after `checkoutFile()` succeeds, the checked-out content is in SAF before
the method returns.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 3.2.2a: Write back after `git.checkout()` (~4 min)
- In `checkoutFile()`, after `git.checkout()...call()` succeeds, reuse the `worktree =
  shadowWorktreeFor(config.repoRoot)` already resolved earlier in the method (2.1.3a): when non-null,
  enqueue the single relative path and call `GitShadowFlushActor.flush()` (same mechanism as
  3.2.1a, one-item batch — no bespoke single-file path) before returning `Unit.right()`.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

---

## Phase 4: Conflict-Resolution Correctness (manual hunk path)

### Epic 4.1: `markResolved()` pulls fresh SAF content into shadow before staging

#### Story 4.1.1: SAF→shadow single-file refresh before `git add`
**As a** user who manually resolves conflict hunks, **I want** my resolution to actually be what
gets committed, **so that** `resolveConflict()`'s `git.add()` doesn't stage stale shadow content
that still has conflict markers (design decision #3 above; `research/features.md` edge case 1).
**Acceptance Criteria**: after `GitSyncService.resolveConflict()`'s write-then-`markResolved`
sequence, the shadow tree's file matches the resolved content, not the pre-resolution state.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 4.1.1a: Pull-then-stage in `markResolved()` (~5 min)
- Before `git.add().addFilepattern(...).call()` in `markResolved()`, reuse the `worktree =
  shadowWorktreeFor(config.repoRoot)` already resolved earlier in the method (2.1.3b): when non-null,
  compute the git-relative path via `worktree.toGitRelativePath(filePath)`, read the
  current SAF content via `fileSystem.readFile(filePath)` (the caller — `GitSyncService.resolveConflict`
  — already wrote the resolved content here before calling `markResolved`, so this read picks it
  up), write it into the shadow tree at that relative path, **then** `git.add()`. If the SAF read
  returns `null`, return `DomainError.GitError.CommitFailed("Cannot refresh shadow before staging: $filePath")`
  rather than proceeding with a stale `git.add()`.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 4.1.1b: Regression test for the write-then-stage ordering (~5 min)
- New test asserting: given a `ConflictResolver`-produced resolution written to a fake SAF path,
  then `markResolved()` called, the shadow tree's file content equals the resolved content (not the
  original conflict-marker content) before `git.add()` runs. (Full test placement/infra: Phase 8.2.)
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryShadowWorktreeTest.kt` (new — created in Phase 8, this task adds one test method to it once that file exists; if Phase 4 lands before Phase 8's skeleton task, create the file here with just this one test)

#### Story 4.1.2: Confirm `applyJournalMerge()` needs no separate fix
**As a** reviewer, **I want** explicit confirmation (not an assumption) that the algorithmic
journal-merge path is covered by 4.1.1a, **so that** it isn't silently missed.
**Acceptance Criteria**: a code comment documents the shared code path; no `GitSyncService.kt`
changes are made.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`

##### Task 4.1.2a: Add a one-line cross-reference comment (~3 min)
- Above `applyJournalMerge()`'s `gitRepository.markResolved(config, filePath)` call
  (`GitSyncService.kt`, near line 505), add: `// filePath here is written to SAF just above via
  fileSystem.writeFile — markResolved() (AndroidGitRepository, shadow-mirror mode) pulls that SAF
  content into the shadow tree before staging; same ordering as resolveConflict().` No logic
  change.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`

### Epic 4.2: `abortMerge()` reconciles shadow to SAF after reset (Blocker 4)

#### Story 4.2.1: Post-reset SAF reconciliation, not a pre-op freshness check
**As a** user who cancels a merge mid-resolution, **I want** the shadow tree to end up consistent
with what's actually in my SAF folder, **so that** an abort doesn't silently discard
already-write-back'd resolution content without any error (traced sequence: `resolveConflictBySide()`
loops `checkoutFile()` — which writes back to SAF immediately per Phase 3.2.2 — then
`markResolved()` for each file; if a later file's `markResolved`/`commit` fails, the loop returns
early leaving earlier files' SAF-side resolutions already applied but uncommitted; if the user
then aborts, `git reset --merge` resets those files' **shadow** content back to pre-merge HEAD,
but nothing tells the freshness manifest those files changed — since the manifest only tracks SAF
mtimes, which the JGit reset didn't touch, the next `ensureFresh()` call wrongly concludes the
shadow is still fresh, and the next commit silently uses the reverted shadow content instead of
the user's resolution). `abortMerge()` was originally miscategorized as one of the four methods
that don't touch working-tree content — it does (`git reset --merge` rewrites tracked files that
differ from HEAD) — but the fix is a **post-op** reconciliation, not the standard pre-op
`ensureFresh` (a pre-op check would resync shadow *from* SAF, which is backwards for an op whose
whole purpose is resetting the working tree; the shadow content after `abortMerge` must reconcile
back *to* SAF, i.e. re-run the same SAF→shadow sync direction as always, so SAF — not the JGit
reset — remains the source of truth for any uncommitted work).
**Acceptance Criteria**: after `abortMerge()` returns, the shadow tree and freshness manifest are
consistent with the current SAF content (a subsequent `ensureFresh()` call is a no-op unless SAF
changed since); no divergence between "what `abortMerge` reset the shadow to" and "what the
manifest believes is fresh" persists across the call.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 4.2.1a: Re-sync from SAF immediately after `git.reset(MERGE)` (~4 min)
- In `abortMerge()`, immediately after `git.reset().setMode(ResetCommand.ResetType.MERGE).call()`
  succeeds, resolve `val worktree = shadowWorktreeFor(config.repoRoot)`: when non-null, call
  `worktree.syncFromSafRoot(listRecursive = ..., readSafFile = ...)` (same primitive as
  1.2.3e's initial-sync call, unconditional — not gated by `isFresh()`, since the reset itself just
  changed shadow content out from under the manifest's assumptions). This pulls any still-on-SAF
  resolution content (from `checkoutFile()`'s earlier write-backs) back over whatever the JGit
  reset just wrote, and refreshes the manifest to match — so SAF, not the mid-abort JGit state,
  wins. `abortMerge()`'s own `openGit(config, requiresFreshWorkingTree = false)` call (1.2.3d)
  stays unchanged — this re-sync is a distinct, explicit post-op step, not a switch to
  `requiresFreshWorkingTree = true`.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 4.2.1b: Regression test for the abort-mid-resolution sequence (~5 min)
- New test in `AndroidGitRepositoryShadowWorktreeTest.kt` (Phase 8.2.1): reproduce the traced
  sequence — partial `resolveConflictBySide()` (one file `checkoutFile()`'d and write-backed to
  fake SAF, second file's `markResolved` forced to fail) — then call `abortMerge()`, then assert
  (a) the fake SAF still has the first file's resolved content (untouched by the abort — SAF was
  never reset, only shadow was), and (b) the shadow tree's copy of that file also matches the
  resolved SAF content (post-reconciliation), not the pre-merge HEAD content JGit's reset alone
  would have left it at.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryShadowWorktreeTest.kt`

---

## Phase 5: Git-Path Selection & Construction Wiring

### Epic 5.1: `shadowKeyForSafPath()` primitive + trivial construction-site updates (finding #3,
revised after second-round adversarial review — see design decision #6)

This epic is much smaller than originally planned: since resolution now happens per-call inside
`AndroidGitRepository.shadowWorktreeFor(config.repoRoot)` (Task 1.2.3a) rather than being pre-built at
construction time, **neither `MainActivity` nor `WorkManagerSyncScheduler` needs any
shadow-mirror-aware logic at all** — they only need to pass a `context: Context` into the
constructor (Task 2.1.1a) and leave `pathResolver` exactly as simple as it already is today (an
attempt at direct SAF resolution; `AndroidGitRepository` itself decides whether to fall back to a
shadow worktree, per-call, per-`config.repoRoot`). This structurally eliminates the entire
`graphPath`-vs-`config.repoRoot` class of bug the previous draft introduced — there is no longer
any UI-layer code that needs to know the difference.

#### Story 5.1.1: `GitShadowWorktree.shadowKeyForSafPath()` (Blocker 3 fix)
**As a** developer, **I want** the `shadowKey` derivation to hash the full `repoRoot` string, not
just the SAF tree-document-ID, **so that** two graphs sharing one SAF grant but pointing at
different subfolders never collide on the same shadow `.git` directory.
**Acceptance Criteria**: `shadowKeyForSafPath(repoRootA) != shadowKeyForSafPath(repoRootB)`
whenever `repoRootA != repoRootB`, even when both share the same SAF tree URI.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`

##### Task 5.1.1a: Add the companion derivation functions (~4 min)
- Add to `GitShadowWorktree.kt`:
  ```kotlin
  companion object {
      /** Derived from the FULL repoRoot string (tree URI + relative subpath), matching
       *  GraphManager.graphIdFromPath()'s basis — NOT just the tree-document-ID portion.
       *  Two graphs sharing one SAF grant but pointing at different subfolders must get
       *  different shadowKeys, or their git shadow trees would collide (Blocker 3). Callers
       *  MUST always pass config.repoRoot here — never graphPath/GraphManager's identity path,
       *  which can legitimately differ from repoRoot (see plan.md design decision #1/#6). */
      fun shadowKeyForSafPath(repoRoot: String): String =
          sha256(repoRoot).take(16) // same primitive GraphManager.graphIdFromPath() uses
  }
  ```
  (`safRootForSafPath`/`forSafPath` from an earlier draft are removed — `GitShadowWorktree`'s
  constructor already takes `safRoot: String` directly per Task 1.1.1b, and `shadowWorktreeFor()`
  — Task 1.2.3a, the sole call site that constructs a `GitShadowWorktree` — passes `config.repoRoot`
  as both the hash input and `safRoot` in one call, so no separate factory wrapping both is needed.)
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`

#### Story 5.1.2: Add `context: Context` at both `AndroidGitRepository` construction sites
**As a** SAF-only user, **I want** git sync to work without granting "All files access", on both
the foreground UI path and the background `WorkManager` path, **so that** finding #3's "already
silently broken for SAF-only users" bug class (the background path had no SAF resolution at all)
doesn't survive this project.
**Acceptance Criteria**: both construction sites pass `context`; neither needs to know
`repoRoot`/`graphPath`/shadow-mirror concepts — that's entirely internal to
`AndroidGitRepository` now (decision #6).
**Files**: `androidApp/src/main/kotlin/dev/stapler/stelekit/MainActivity.kt`,
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/WorkManagerSyncScheduler.kt`

##### Task 5.1.2a: `MainActivity.kt:295-298` — add `context`, wire the REAL `fileSystem`, and
extract construction into a test-callable function (~6 min — widened twice: round 6 found the
original sample constructed a disconnected, dead `PlatformFileSystem()` instead of reusing the
app's real one; round 8 found the regression test meant to guard that fix had no way to read back
what it needed to assert on, since nothing exposed the constructed instance for inspection)
- **Critical correction, verified against real source**: `MainActivity.kt:199` already captures
  `app` (the `SteleKitApplication` instance) in the surrounding `onCreate()` scope, and
  `SteleKitApplication.fileSystem` (`SteleKitApplication.kt:47`, `lateinit var`, `.init()`-ed at
  `SteleKitApplication.kt:74`) is the **one** `PlatformFileSystem` instance that actually has
  `setWriteBehindQueue(...)` called on it (`SteleKitApplication.kt:81`) and that `GraphWriter`'s
  writes flow through (it's the instance `GraphManager` is constructed with,
  `SteleKitApplication.kt:92-97`). `MainActivity`'s own composable-local `fileSystem`
  (`MainActivity.kt:226-249`) is a **second**, separate instance that never gets the write-behind
  queue wired — using it here would silently defeat both real SAF reads *and* the round-5/6
  lock-coordination fix a second way. **Neither of those is what an earlier draft of this task
  used** — it constructed a bare `PlatformFileSystem()`, a **third**, permanently-`.init()`-less
  instance whose SAF reads silently return `null` and whose `setGitShadowKeyProvider` calls
  (Task 5.2.1c) land on an object nothing else ever touches.
- **Extract the construction into a top-level `internal fun`, not an inline `remember { }`
  expression** — this is the mechanism that makes the regression test possible at all (round-8
  finding: `MainActivity.kt`'s `gitRepository` never escaped its `remember { }` scope more
  specifically than the closed `GitRepository` interface, so nothing outside `MainActivity` could
  ever observe what it was constructed with). Mirror the established precedent already in this
  exact file/module — `CaptureActivity.kt` deliberately widens composables like `CaptureScreen`
  from `private` to `internal` "specifically so [`CaptureActivityTest.kt`] can exercise them
  directly instead of only through the full `CaptureActivity`" (confirmed:
  `androidApp/src/main/kotlin/dev/stapler/stelekit/CaptureActivity.kt`'s `internal fun`
  declarations at top level, same `dev.stapler.stelekit` package as `MainActivity.kt`, exercised
  directly by `CaptureActivityTest.kt` via a bare `createComposeRule()` rather than driving the
  full Activity). Add, at the top level of `MainActivity.kt` (same package, outside the `Activity`
  class body):
  ```kotlin
  internal fun buildGitRepository(context: Context, fileSystem: FileSystem): AndroidGitRepository =
      AndroidGitRepository(
          pathResolver = { path -> PlatformFileSystem.resolveSafToRealPath(path, context) },
          fileSystem = fileSystem, // caller passes app.fileSystem — see rationale above; this
                                    // function does not choose which instance, only wires it
          context = context,
      )
  ```
  Then change the existing `MainActivity.kt:295-298` construction to call it:
  ```kotlin
  val gitRepository = remember { buildGitRepository(applicationContext, app.fileSystem) }
  ```
  `pathResolver`'s behavior is unchanged from what `MainActivity.kt:295-298` already does today
  (the existing `MANAGE_EXTERNAL_STORAGE` fast-path attempt) — it just moved inside the extracted
  function. No `remember(graphPath)` keying is needed either, since nothing shadow-related is
  captured here anymore.

  **Why `buildGitRepository` stays `internal` here while `AndroidGitRepository.fileSystem` (Task
  2.1.1a) had to become fully `public`, not `internal` — these are two different kinds of
  boundary, easy to conflate (round 9 caught exactly this conflation in an earlier draft)**:
  `buildGitRepository` and its test (`MainActivityGitRepositoryWiringTest.kt`) both live in the
  **same** Gradle module, `:androidApp` — one in its `main` source set, one in its `test` source
  set. The Kotlin Gradle plugin automatically grants a module's `test` source set friend-path
  access to `internal` declarations in that *same* module's `main` source set, so `internal` is
  sufficient and correctly scoped here (matches the `CaptureActivity.kt`/`CaptureActivityTest.kt`
  precedent exactly — also same-module). `AndroidGitRepository.fileSystem`, by contrast, is
  declared in `:kmp`, a **different** Gradle module that `:androidApp` merely *depends on* —
  Kotlin's `internal` modifier does not extend across a `project(":kmp")` dependency edge the way
  it does across a same-module main/test split, so `internal` would not compile there, which is
  why that one specific field needed to go all the way to public instead.
- **Regression test, now actually writable**: with `buildGitRepository` extracted and
  `AndroidGitRepository.fileSystem` made public (Task 2.1.1a), the test calls
  `buildGitRepository(context, app.fileSystem).fileSystem` directly and asserts it's
  reference-identical (`===`) to `app.fileSystem` — exercising the *same* production code path
  `MainActivity`'s real `remember { }` block calls, not a parallel/tautological construction, while
  needing no Compose test rule or live Activity composition at all (a plain JVM/Robolectric unit
  test is sufficient, since `buildGitRepository` is a plain function, not a composable). **Module
  placement** (confirmed correct in round 7, re-verified this round): lives in `:androidApp`'s own
  test source set — `kmp/src/androidUnitTest/` (the `:kmp` module) cannot import `MainActivity.kt`
  or this new top-level function at all, since `:androidApp` depends on `:kmp`, not the reverse
  (`androidApp/build.gradle.kts`) — and `:androidApp`'s test source set already has the needed
  deps (`androidApp/build.gradle.kts`'s `testImplementation("org.robolectric:...")` etc.), though
  this particular test doesn't even need them given it's testing a plain function.
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/MainActivity.kt` (new top-level
  `buildGitRepository` function + the `remember { }` call site updated to use it),
  `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt` (`fileSystem`
  visibility, Task 2.1.1a),
  `androidApp/src/test/kotlin/dev/stapler/stelekit/MainActivityGitRepositoryWiringTest.kt` (new)

##### Task 5.1.2b: `WorkManagerSyncScheduler.kt:115` — add `context` and an explicit `fileSystem`
(~3 min — widened since Task 2.1.1a's `fileSystem` default was removed; this call site's own
`fileSystem` value doesn't need the `MainActivity` fix, see rationale below)
- Change `val gitRepository = AndroidGitRepository()` to
  ```kotlin
  val gitRepository = AndroidGitRepository(
      context = applicationContext,
      fileSystem = PlatformFileSystem(), // safe here (unlike MainActivity's construction site,
                                          // Task 5.1.2a) — see rationale below
  )
  ```
  (a `Context` is already available in this class — it's an Android `Worker`/`CoroutineWorker`
  subclass with `applicationContext` on its base class). **Why a throwaway, uninitialized
  `PlatformFileSystem()` is actually fine here, unlike Task 5.1.2a's `MainActivity` call site**:
  `shadowWorktreeFor()` passes `fileSystem` straight into `GitShadowWorktree`'s constructor without
  reading from it — it's only *read* from by `syncFromSafRoot()`/`ensureFresh()`, which `fetch()`
  never calls (see below). So even though this instance would return `null`/empty for any real SAF
  read, nothing on this call site's actual code path (`fetch()` only) ever performs one. This alone
  is sufficient to make background `fetch()`
  work correctly for SAF-only users too: `fetch` is one of the five methods that pass
  `requiresFreshWorkingTree = false` (1.2.3d), which only skips the pre-op `ensureFresh()` sync —
  `openGit`'s `Git.open(File(resolveForJGit(config.repoRoot)))` call still runs unconditionally
  regardless of that flag, and `resolveForJGit` is shadow-aware for *every* caller as of Task
  1.2.3b's fix (it tries `pathResolver` first, then `shadowWorktreeFor(repoRoot)?.worktreeRootPath`
  — `fetch()` doesn't need its *own* call to `shadowWorktreeFor`, since `resolveForJGit` already
  makes that call internally on its behalf). Fetch only skips the working-tree-content freshness
  check (correct — it doesn't read/write working-tree files), not shadow-directory targeting.
  Add a code comment at this call site noting that `fetch` only updates remote-tracking refs — it
  doesn't touch the working tree, so no `ensureFresh`/shadow write-back happens here even though
  the shadow directory is correctly targeted; the user's next foreground `merge()` call is what
  surfaces the fetched changes into SAF. This is `fetch`'s intended scope boundary (decision #4),
  documented so a future reader doesn't mistake the missing `ensureFresh` call for an oversight.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/WorkManagerSyncScheduler.kt`

### Epic 5.2: Mutual exclusion between git worktree ops and write-behind flush (finding #5, pitfalls §4)

#### Story 5.2.1: Per-graph `Mutex` shared across the two subsystems
**As a** developer, **I want** `GitShadowWorktree`'s sync/write-back and `PlatformFileSystem`'s
existing write-behind flush to never mutate the same underlying SAF files concurrently, **so that**
neither subsystem's shadow copy goes stale mid-operation (`research/architecture.md` §4, "Integration
points at risk of double-detection" #3).
**Acceptance Criteria**: a git sync in progress blocks a concurrent write-behind flush for the same
graph's SAF files, and vice versa.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`,
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`,
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowFlushActor.kt` (write-back direction's
lock acquisition — see Task 3.1.2a, not a separate task here)

`GitWorktreeLocks` itself (the holder both 5.2.1b and Task 3.1.2a acquire against) is created in
**Task 0.1.3a**, not here — it has no dependency on anything Phase 1-4 builds, and Task 3.1.2a
(Phase 3) needs it to already exist, which would be a forward reference to Phase 5 if it stayed
here (Phase 3 precedes Phase 5 in the dependency graph below). Moving this one 3-line object to
Phase 0's groundwork — already documented as "independent, can run any time" — resolves that
ordering conflict with zero cost, since the object has no dependents to break. (It's placed in
`platform`, not `git`, since `PlatformFileSystem` — Task 5.2.1c — needs to reference it without a
`platform` → `git` package dependency.)

##### Task 5.2.1b: `GitShadowWorktree` acquires the lock inside `syncFromSafRoot()` itself, not
just `ensureFresh()` (~4 min, closes a concurrency gap the second-round adversarial review found)
- An earlier draft wrapped only `ensureFresh()`'s body in `GitWorktreeLocks.lockFor(shadowKey).withLock { ... }`.
  Since `ensureFresh()` internally calls `syncFromSafRoot()` when stale, that path was covered —
  but Task 1.2.3e (post-init/clone sync) and Task 4.2.1a (post-abort reconciliation) both call
  `shadowWorktree.syncFromSafRoot(...)` **directly**, bypassing `ensureFresh()` and thus the lock
  entirely — leaving both as unguarded races against `PlatformFileSystem`'s write-behind flush
  (the post-abort case is the direct continuation of Blocker 4's fix, so this closes the one
  remaining unguarded race in that fix too). **Fix**: acquire the lock inside `syncFromSafRoot()`
  itself (wrapping its whole body in `GitWorktreeLocks.lockFor(shadowKey).withLock { ... }`) —
  this covers the SAF→shadow direction for all three callers (`ensureFresh`, and the two direct
  `syncFromSafRoot` callers, 1.2.3e and 4.2.1a) automatically, with no new call site able to forget
  to wrap itself. The **shadow→SAF write-back direction** (the enqueue+flush sequence in
  `merge()`/`checkoutFile()`, 3.2.1a/3.2.2a) does not go through `syncFromSafRoot` at all, so it
  needs its own, separate lock acquisition — that's specified in Task 3.1.2a, inside
  `GitShadowFlushActor.flush()` itself (not here, and not in `GitShadowWorktree`), for the same
  reason: one acquisition point that every write-back caller inherits automatically, rather than
  requiring 3.2.1a/3.2.2a to each remember to wrap their own call.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`

##### Task 5.2.1c: `PlatformFileSystem`'s write-behind flush acquires the *actual* git-side lock key
(~6 min — revised after round-5 adversarial review found the original text asserted, incorrectly,
that `PlatformFileSystem` already shares a key with the git side)

**What round 5 found**: an earlier draft of this task said to lock using "the same
`ShadowFileCache.graphIdFor(treeDocId)`-derived key the git side uses" — but the git side stopped
using that derivation as part of the Blocker-3 fix (Task 5.1.1a): `GitShadowWorktree`'s real key is
`shadowKeyForSafPath(repoRoot) = sha256(repoRoot).take(16)`, hashed from the **full
`config.repoRoot`** string, specifically *because* `graphIdFor(treeDocId)` (tree-URI-only) was
found to collide across two graphs sharing one SAF grant. `PlatformFileSystem.flushPendingWrites()`
(`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt:782-792`) has no
`GitConfig`/`repoRoot` in scope at all — it's a lower layer with no notion of `GitConfig` anywhere
in the file — so there is no value at that call site to even compute the correct key from, and the
two subsystems would silently lock on two different keys, never actually contending, defeating
Story 5.2.1's entire purpose against requirements.md's explicit hard constraint on not losing
concurrent SAF edits.

**Fix — reuse `PlatformFileSystem`'s existing cross-layer callback pattern** (it already exposes
`setOnFlushComplete`/`setOnFlushPreWrite`/`setOnFlushFailed`/`setSpanEmitter` at
`PlatformFileSystem.kt:740-771` for exactly this kind of "a higher layer needs to inject behavior
this layer can't compute itself" case — add a new settable provider rather than inventing a new
cross-layer mechanism):
- Add `private var gitShadowKeyProvider: (() -> String?)? = null` and
  `fun setGitShadowKeyProvider(provider: (() -> String?)?) { gitShadowKeyProvider = provider }` to
  `PlatformFileSystem`.
- In `AndroidGitRepository.shadowWorktreeFor(repoRoot)` (Task 1.2.3a), whenever a
  `GitShadowWorktree` is resolved (i.e. `shadowWorktreeFor` is about to return non-null), also call
  `fileSystem.setGitShadowKeyProvider { GitShadowWorktree.shadowKeyForSafPath(repoRoot) }`.
  **Placement, pinned down (not left as an implementation-time choice)**: add
  `setGitShadowKeyProvider` as a member of the shared `commonMain` `FileSystem` interface itself,
  matching the existing precedent of `setOnFlushComplete`/`setOnFlushPreWrite`/`setOnFlushFailed`/
  `setSpanEmitter` (all real `override fun` members on `PlatformFileSystem`, confirmed at
  `PlatformFileSystem.kt:741,751,760,769` — i.e. the interface already declares them, so this
  follows the file's own established pattern rather than diverging into a one-off downcast). Give
  it a no-op default body (`fun setGitShadowKeyProvider(provider: (() -> String?)?) {}`) so
  `JvmFileSystem`/iOS/wasmJs actuals don't need a matching override, the same default-body
  discipline Task 1.2.3c's `listFilesRecursiveWithModTimes` already applies for the same reason
  (protecting Phase 7's zero-`jvmMain`-diff claim).
- **Known accepted limitation — a brief staleness window across a graph switch, not fixed by this
  task**: `gitShadowKeyProvider` is a closure re-set on every real `shadowWorktreeFor()`
  resolution, which self-corrects the moment the *new* graph's git repository is used — but since
  `app.fileSystem` (Task 5.1.2a) is a single, app-wide instance shared across graph switches, there
  is a window between switching graphs and that new graph's first real git operation during which
  the provider still reports the *previous* graph's key. A write-behind flush for the new graph's
  SAF files during that narrow window would lock on the wrong key. This is accepted as within this
  plan's risk tolerance (narrower and shorter than the round-5/6 bugs this task fixes, and the
  pre-existing write-behind/shadow-cache machinery already has its own per-graph-identity
  assumptions this plan doesn't fully resolve either — see ADR-018's Consequences on the
  `GraphId`/`shadowKey` mismatch) — stated explicitly here rather than left silent, matching how
  Blocker 4's `abortMerge` fix documents its own ordering reasoning elsewhere in this plan.
- At the write-behind flush call site (`flushPendingWrites()`'s caller, located the same way the
  original task described), call `gitShadowKeyProvider?.invoke()`; when non-null, wrap the flush in
  `GitWorktreeLocks.lockFor(key).withLock { ... }`; when null (git sync never configured for this
  graph, or no shadow-mirror resolution has happened yet), skip the lock entirely — no behavior
  change for users who never enable Git Sync, matching the original task's fallback intent.
- Add a Phase 8 regression test (new, since nothing in the existing Phase 8 test list would have
  caught this — each subsystem's lock usage was only tested in isolation) that constructs both a
  `GitShadowWorktree`-driven lock acquisition and a `PlatformFileSystem.flushPendingWrites()` lock
  acquisition for the *same* configured graph and asserts they contend on the same `Mutex`
  instance from `GitWorktreeLocks` — not just that each acquires *some* lock independently.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt` (interface member
  + no-op default), `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`,
  `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`,
  `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitWorktreeLocksSharedKeyTest.kt` (new)

---

## Phase 6: GraphManager Lifecycle Hooks & Storage Bounding (finding #6)

### Epic 6.1: Shadow-tree cleanup — startup orphan sweep is the sole mechanism (revised after
round-3 adversarial review; the registered-handler design from earlier drafts is dropped, not
patched — see rationale below)

**Rationale for dropping the registered-handler approach, and for round 4's further revision to a
time-based sweep**: an earlier draft added a `registerShadowCleanupHandler` on `GraphManager`
(mirroring `registerGitSyncService`), invoked synchronously from `removeGraph()`
(`GraphManager.kt:400`, which is **not** `suspend`). But resolving a `GraphId` to its `shadowKey`
requires reading `GitConfig.repoRoot`, and the only real lookup —
`GitConfigRepository.getConfig(graphId: String): Either<DomainError, GitConfig?>`
(`GitConfigRepository.kt:16`) — is `suspend`-only. There is no synchronous path from `GraphId` to
`repoRoot` today. A round-3 revision moved to a startup orphan sweep that computed `activeShadowKeys`
by looping `gitConfigRepository.getConfig()` over every registered graph — but round 4's review
traced this against the real `GraphManager.createGitConfigRepository()`
(`GraphManager.kt:924-931`) and found it is scoped to only the **currently active** graph's
SQLite database (`currentFactory`/`_activeRepositorySet` are singular fields, not a per-graph
map), so looping it over every registered graph silently returns `null` for any graph that isn't
currently active — which would drop that graph's `repoRoot` out of `activeShadowKeys` and cause
`sweepOrphans()` to delete its **live** shadow git tree (including unpushed commits) on next
startup. This is a real constraint of the existing multi-graph architecture, not something this
project can casually work around by touching `GraphManager`'s public surface.

**Round-4 fix: drop the `GitConfigRepository` dependency from the sweep entirely.** Instead of
asking "which `repoRoot`s currently belong to a registered graph" (a question this plan cannot
safely answer without cross-graph config access), the sweep asks a self-contained question a
single shadow directory can answer about itself: "has this shadow tree been used recently?" Each
`GitShadowWorktree` stamps its own directory's last-used marker every time `shadowWorktreeFor()`
resolves it (i.e. on every real git operation against that graph) — a purely local, per-directory
signal requiring no `GraphManager`/`GitConfigRepository` involvement at all. The sweep then deletes
only shadow directories that have gone unused for a generous grace period (e.g. 60 days), which by
construction can never include an actively-used graph's shadow tree (its marker gets refreshed
every time the user syncs), sidestepping the "is this graph currently active in `GraphManager`"
question entirely. This is the same class of mtime-based staleness signal already used elsewhere
in this plan (Task 1.1.2b's dual mtime+size check) and in the existing `ShadowFileCache`.

#### Story 6.1.1: Time-based startup orphan sweep — no cross-graph lookup required
(pitfalls §4.4 — `GraphId`/`shadowKey` mismatch makes precise tracking fragile; round 4 replaces
the round-3 `GitConfigRepository`-based sweep, which was found to be unsafe for inactive graphs)
**As a** developer, **I want** a startup sweep that removes only long-unused shadow directories,
**so that** graph removal (via any path) doesn't leak shadow storage indefinitely, without
requiring any `GraphManager` public API change or per-graph config lookup.
**Acceptance Criteria**: a `gitshadow` directory whose last-used marker is older than the grace
period is deleted at next app startup; a shadow directory used within the grace period —
regardless of whether its owning graph happens to be the currently *active* one in `GraphManager`
— is never deleted.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`,
`kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`,
`androidApp/src/main/kotlin/dev/stapler/stelekit/MainActivity.kt`

##### Task 6.1.1a: Stamp a last-used marker on every real resolution (~4 min)
- In `GitShadowWorktree`, add `fun touchLastUsed()` that updates a dedicated marker file's mtime
  — `File(worktreeRoot.parentFile, ".last-used").apply { if (!exists()) createNewFile(); setLastModified(System.currentTimeMillis()) }`
  (a sibling of `worktreeRoot`, i.e. `context.filesDir/graphs/$shadowKey/.last-used`, not inside
  the git working tree itself, so it's never accidentally tracked/committed). Call this from
  `AndroidGitRepository.shadowWorktreeFor(repoRoot)` (Task 1.2.3a) immediately after resolving or
  constructing the cached instance, so every real call site that uses a shadow worktree refreshes
  its liveness signal for free, with no per-call-site opt-in required.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`,
  `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 6.1.1b: `sweepOrphans()` — pure local mtime check, no `GraphManager` dependency (~5 min)
- Companion function: `fun sweepOrphans(context: Context, maxAgeMillis: Long = 60L * 24 * 60 * 60 * 1000)`
  (60-day default grace period) — lists `context.filesDir/graphs/*/gitshadow` directories, reads
  each parent's `.last-used` marker's mtime (Task 6.1.1a; a directory with no marker yet — e.g.
  from before this task shipped, or a genuinely fresh clone that crashed before its first real
  operation — is treated as "just created," i.e. exempt from deletion this pass, never
  eagerly deleted on ambiguous absence), deletes (`deleteRecursively()`) any whose marker is older
  than `maxAgeMillis`. Runs on `Dispatchers.IO`. Entirely self-contained — no `GitConfigRepository`,
  no `GraphManager` registry access, no suspend function required at all.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`

##### Task 6.1.1c: Wire the sweep call at startup (~3 min)
- In `MainActivity.kt`'s app-startup path, call `GitShadowWorktree.sweepOrphans(applicationContext)`
  once, off the main thread (e.g. inside a `LaunchedEffect(Unit)` launching on `Dispatchers.IO`) —
  no `GraphManager`/`GitConfigRepository` reference needed at this call site at all, a direct
  simplification versus every prior round's design.
- Files: `androidApp/src/main/kotlin/dev/stapler/stelekit/MainActivity.kt`

### Epic 6.2: Storage-space guard before first shadow clone (pitfall §3.1)

#### Story 6.2.1: `StatFs` pre-check
**As a** user on a low-storage device, **I want** a clear error instead of a raw JGit I/O exception
when there isn't enough space for the initial clone, **so that** the failure is diagnosable.
**Acceptance Criteria**: `clone()`/`init()` in shadow-mirror mode checks available space before
calling JGit and returns a typed error if insufficient.
**Files**: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

##### Task 6.2.0a: Measure actual shadow storage footprint before setting the threshold (~5 min,
addresses adversarial-review Concern "storage sizing was not actually done")
- Reuse `SyntheticGraphGenerator`'s `XLARGE` config (`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/SyntheticGraphGenerator.kt:22`,
  7 978 pages — the same fixture Phase 8.3.1a uses) to materialize a synthetic markdown tree,
  `git init` + commit it via a throwaway `JvmGitRepository` (real JGit, real `.git` directory, run
  on JVM test infra rather than a device — the on-disk footprint is platform-independent), and
  record: markdown content size, `.git` size immediately after the first commit, and `.git` size
  after 20 synthetic incremental commits (a rough proxy for accumulated history growth). Write the
  three numbers into this plan's Phase 6.2 section (replacing the "rough heuristic" language in
  6.2.1a below with an actual measured basis) and into `research/stack.md`'s open item. This
  directly answers requirements.md's "the plan must size the problem" ask rather than deferring it
  silently.
- Files: none (a throwaway local measurement script/test run — its numbers get written back into
  this plan.md and stack.md, not committed as a permanent test)

##### Task 6.2.1a: Pre-check via `StatFs` (~5 min)
- Same scope note as Task 1.2.3e: `init(repoRoot: String)`/`clone(url, localPath, ...)` have no
  `GitConfig` in scope, only their own path parameter. At the top of `init()`, resolve
  `val worktree = shadowWorktreeFor(repoRoot)`; at the top of `clone()`, resolve
  `val worktree = shadowWorktreeFor(localPath)`. In either case, when non-null:
  `android.os.StatFs(worktree.worktreeRootPath).availableBytes`
  compared against a threshold derived from Task 6.2.0a's measurement (with headroom for growth —
  e.g. 3-5x the measured single-commit `.git` size, not an arbitrary guess). If insufficient,
  return `DomainError.GitError.WorkingTreeSyncFailed("clone", repoRoot ?: localPath, "Insufficient
  storage for git shadow clone").left()` (using whichever of the two parameters is in scope for
  the method actually being checked) before calling JGit. Document the chosen multiplier's
  rationale (growth headroom, not just the measured floor) directly in the code comment.
- Files: `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt`

---

## Phase 7: Desktop Non-Regression

### Epic 7.1: Explicit non-regression verification

#### Story 7.1.1: Confirm zero `jvmMain` changes
**As a** reviewer, **I want** positive confirmation Desktop's git path is untouched, **so that** "no
regression for Desktop" (requirements.md success metric) isn't just assumed.
**Acceptance Criteria**: no diff under `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/git/` or
`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/` for this project's full changeset.
**Files**: none (verification only)

##### Task 7.1.1a: Diff audit (~3 min)
- Run `git diff --stat main -- kmp/src/jvmMain/` against the full feature branch and confirm empty
  output. Record the confirmation in the PR description at ship time (Phase 7 of SDD, not this
  planning doc).
- Files: none

#### Story 7.1.2: New Desktop smoke coverage (closes a pre-existing gap while verifying)
**As a** reviewer, **I want** an actual passing test exercising `JvmGitRepository`, not just "we
didn't touch it," **so that** the non-regression claim has evidence behind it — `kmp/src/jvmTest`
currently has **zero** files under a `git/` package at all.
**Acceptance Criteria**: a new test passes against `JvmGitRepository` unmodified by this project.
**Files**: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/git/JvmGitRepositoryTest.kt` (new)

##### Task 7.1.2a: Create a basic init/commit/status/log roundtrip test (~5 min)
- New file: `Git.init()` a temp dir via `JvmGitRepository`, write a file, `stageSubdir`/`commit`,
  assert `status().hasLocalChanges == false` post-commit and `log()` returns one entry. Asserts
  `config.repoRoot` is used as a plain real path throughout — no shadow/mapper concepts touched
  (this file has none, by construction, since `JvmGitRepository` gained no new constructor params).
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/git/JvmGitRepositoryTest.kt` (new)

##### Task 7.1.2b: Run the JVM test suite and confirm existing git tests stay green (~3 min)
- `bazel test //kmp:jvm_tests` (or `./gradlew jvmTest`). Confirm
  `GitSyncServiceTest.kt`/`GitSyncServiceRateLimitRetryTest.kt` (businessTest, `StubGitRepository`-based)
  pass unmodified, plus the new `JvmGitRepositoryTest.kt` from 7.1.2a.
- Files: none (verification only)

---

## Phase 8: Test Infrastructure & Validation (finding #8)

### Epic 8.1: Unit coverage for the new Android collaborators

#### Story 8.1.1: `GitShadowWorktree`
**Files**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowWorktreeTest.kt` (new)

##### Task 8.1.1a: Sync copy/skip-fresh coverage (~5 min)
- `@RunWith(RobolectricTestRunner::class) @Config(sdk = [29])`, matching `ShadowFileCacheTest.kt`'s
  pattern exactly. Test `syncFromSafRoot` copies missing files, skips fresh ones, deletes
  shadow-tracked files no longer present in the recursive listing, and correctly excludes `.git/`
  from the walk even when it's the root's only "hidden" entry (mirroring `ShadowFileCacheTest`'s
  `"syncFromSaf copies missing files"` / `"skips fresh files"` cases, plus new deletion/`.git`-
  exclusion cases this root-wide rewrite adds). Also cover a top-level file (e.g. `.gitignore`)
  and a `wikiSubdir` nested more than one level deep — the exact cases Blocker 1 found missing
  from the earlier hardcoded-subdir draft.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowWorktreeTest.kt` (new)

##### Task 8.1.1b: `isFresh`/`ensureFresh` staleness detection (~5 min)
- Same file. Cases: manifest matches → `isFresh` true, no resync; manifest mtime stale → `isFresh`
  false, `ensureFresh` resyncs; entry count differs (deletion/addition) → `isFresh` false even if
  all present entries' mtimes match; SAF size differs but mtime doesn't (the Termux case) → caught
  during the actual `syncFromSafRoot` re-copy via 1.1.2b's dual-signal check.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowWorktreeTest.kt`

##### Task 8.1.1c: Path-mapper round trip (~4 min)
- Same file. `toUserFacingPath`/`toGitRelativePath` round-trip for both a top-level `repoRoot`
  and a `repoRoot` pointing at a nested subdir within the SAF tree (the "detected repo root above
  a nested wiki folder" case from `GitSetupScreen.kt`'s doc comment).
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowWorktreeTest.kt`

#### Story 8.1.2: `GitWriteBackQueue` + `GitShadowFlushActor`
**Files**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitWriteBackQueueTest.kt` (new),
`kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowFlushActorTest.kt` (new)

##### Task 8.1.2a: `GitWriteBackQueue` atomicity (~5 min)
- Enqueue/dequeue/getAll happy path; simulate a mid-dequeue crash (kill the process between
  temp-file write and rename by testing the two steps aren't interleaved with a partial state
  visible to a concurrent `getAll()`).
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitWriteBackQueueTest.kt` (new)

##### Task 8.1.2b: `GitShadowFlushActor` happy path + concurrent-edit detection (~5 min)
- Mirrors `ShadowFlushActorTest.kt`'s structure. Happy-path flush lands content in the fake SAF
  target and dequeues. Concurrent-edit case: bump the fake SAF mtime for a path between manifest
  write and flush, assert `WorkingTreeConcurrentEditDetected` is returned and the fake SAF content
  is unchanged.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowFlushActorTest.kt` (new)

##### Task 8.1.2c: Non-race write failure returns `WorkingTreeWriteBackFailed` (~4 min, closes
validation.md Gap #7 for this variant — `WorkingTreeConcurrentEditDetected` was already covered by
8.1.2b, `WorkingTreeSyncFailed` is covered by Task 8.2.3a below; this is the third of the three)
- Same file. Configure the fake `FileSystem`'s `writeFile` to return `false` for a queued path
  (**not** a bumped mtime — that's 8.1.2b's distinct case) and call `flush()`. Assert: (a) the
  collected result for that path is `Either.Left` wrapping
  `DomainError.GitError.WorkingTreeWriteBackFailed(safPath, ...)` (Task 3.1.2c), and (b) the path
  is still present in `queue.getAll()` afterward (retry-preserving — same queue-state outcome as
  8.1.2b's case, so the assertion that actually distinguishes this test is the returned error
  *type*, not the queue state).
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowFlushActorTest.kt`

### Epic 8.2: `AndroidGitRepository` regression suite (closes finding #8's zero-coverage gap)

#### Story 8.2.1: Core JGit operations against a shadow tree
**Files**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryShadowWorktreeTest.kt` (new)

##### Task 8.2.1a: init/commit/status/merge against a Robolectric shadow dir (~5 min)
- Construct `AndroidGitRepository` with `context = ApplicationProvider.getApplicationContext()`
  (a Robolectric temp `context.filesDir`), a `pathResolver` that always returns `null` (forcing
  shadow-mirror mode to activate internally via `shadowWorktreeFor`, per decision #6 — no
  `GitShadowWorktree` is ever passed in directly, since that constructor param no longer exists),
  and a `FakeExternalStorageProvider`-backed `FileSystem` fake for a `saf://...` `config.repoRoot`.
  Exercise `init`/`stageSubdir`/`commit`/`status` end to end.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryShadowWorktreeTest.kt` (new)

##### Task 8.2.1b: `merge()` conflict path produces SAF-facing paths (~5 min)
- Same file. Force a merge conflict (two divergent commits touching the same file), assert
  `ConflictFile.filePath` in the returned `MergeResult` is the SAF path (`saf://...`), never a
  shadow-absolute (`/data/.../gitshadow/...`) path.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryShadowWorktreeTest.kt`

##### Task 8.2.1c: `checkoutFile()`/`markResolved()` round trip via `FakeExternalStorageProvider` (~5 min)
- Same file. Resolve a conflict via `checkoutFile(LOCAL/REMOTE)`, assert the fake SAF provider's
  content for that path matches what was checked out (closes Phase 3.2.2's coverage gap). Also
  covers Task 4.1.1b's assertion if not already added there.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryShadowWorktreeTest.kt`

##### Task 8.2.1d: Regression test for the original bug's literal symptom (~4 min, closes
validation.md Gap #2 — 8.2.1a's ops-succeed test implies this but never asserts it directly)
- Same file. `` `resolveForJGit never returns unresolvable saf scheme string when shadow worktree
  is active` `` (name taken directly from validation.md's proposed test). Mark `resolveForJGit`
  `internal` (not `private`) on `AndroidGitRepository` — the same internal-for-testability pattern
  Task 8.2.2a already establishes for `shadowWorktreeFor`. Construct `AndroidGitRepository` with
  `pathResolver = { null }` (forcing shadow-mirror mode) and a `saf://...` `repoRoot`; call `init()`
  (cheapest op that reaches `resolveForJGit`), then assert directly against
  `shadowWorktreeFor(repoRoot)!!.worktreeRootPath`: `resolveForJGit(repoRoot)` (a) never starts with
  `"saf://"`, and (b) equals that real `java.io.File` path exactly. This is deliberately narrower
  than 8.2.1a's "the ops all succeed" test — it isolates and names the literal original-bug
  regression (`resolveForJGit()` falling through to the raw unresolvable string,
  `AndroidGitRepository.kt:~415` pre-fix) so a future change that broke only this fallback, while
  some other path still happened to make the broader op succeed, would still fail loudly here.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryShadowWorktreeTest.kt`,
  `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt` (`resolveForJGit`
  visibility only)

#### Story 8.2.2: `shadowWorktreeFor()` decision logic and orphan-sweep tests
**Files**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitPathResolverChainTest.kt` (new)

##### Task 8.2.2a: Direct-access-wins-when-available (~4 min)
- Tests `AndroidGitRepository.shadowWorktreeFor(repoRoot)` (Task 1.2.3a) directly — mark it
  `internal` rather than `private` so this test module can call it, following the same
  visibility pattern other `internal`-for-testability members in this codebase use. Asserts it
  returns `null` (no shadow worktree constructed) whenever `pathResolver(repoRoot)` returns
  non-null (using the injectable `isStorageManager` pattern `PlatformFileSystemSafResolveTest.kt`
  already establishes for Robolectric-testing `MANAGE_EXTERNAL_STORAGE` state), and returns a
  non-null, cached (same instance on repeat calls with the same `repoRoot`) `GitShadowWorktree`
  when `pathResolver` returns `null` for a `saf://...` `repoRoot`. Also asserts two different
  `repoRoot` strings sharing the same tree URI but different subpaths produce two different
  instances/`shadowKey`s (Blocker 3 regression coverage).
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitPathResolverChainTest.kt` (new)

##### Task 8.2.2b: Orphan sweep preserves recently-used, deletes stale (~4 min, description
corrected after round-5 review flagged it as describing the dropped `activeShadowKeys`-parameter
design rather than the shipped time-based marker design)
- Create two `gitshadow` directories under a fake `filesDir` (Task 6.1.1a/b's real design, not an
  `activeShadowKeys` argument — `sweepOrphans(context: Context, maxAgeMillis: Long)` takes no
  key-set parameter at all). Stamp one directory's sibling `.last-used` marker with the current
  time (`setLastModified`) and backdate the other's marker past a short test `maxAgeMillis`. Call
  `sweepOrphans(context, shortMaxAgeMillis)` and assert only the backdated (stale) directory is
  deleted, the recently-touched one survives. Also cover the "no marker file at all" case
  separately: assert a `gitshadow` directory with no `.last-used` sibling yet is *not* deleted on
  this pass (Task 6.1.1b's "ambiguous absence is never eagerly deleted" rule).
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitPathResolverChainTest.kt`

#### Story 8.2.3: `StatFs` storage-space guard coverage (closes validation.md Gap #3)
**As a** developer, **I want** automated coverage of Task 6.2.1a's pre-clone storage check, **so
that** both its error path and its happy path are regression-tested, not just implemented (Gap #3:
neither existed anywhere in the original Phase 8 test list).
**Acceptance Criteria**: `clone()`/`init()` in shadow-mirror mode returns
`DomainError.GitError.WorkingTreeSyncFailed` before any JGit call when available space is below
Task 6.2.1a's threshold; proceeds and succeeds exactly as before when space is ample.
**Files**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryStorageGuardTest.kt` (new)

##### Task 8.2.3a: Insufficient-storage error path (~4 min)
- `@RunWith(RobolectricTestRunner::class) @Config(sdk = [29])`, same as this file's siblings. Fake
  `android.os.StatFs(worktree.worktreeRootPath).availableBytes` below Task 6.2.1a's threshold for
  the worktree's path via Robolectric's `StatFs` shadow (Robolectric ships a shadow for
  `android.os.StatFs` under `org.robolectric.shadows` — **confirm the exact shadow class name and
  registration API at implementation time**, e.g. via `Shadows.shadowOf(StatFs(path))` or an
  equivalent `ShadowStatFs.registerStats(path, ...)`-style call; no test in this codebase uses it
  yet, so there's no existing local precedent to copy verbatim the way `ShadowFileCacheTest.kt` is
  a precedent for the Robolectric/`@Config(sdk=[29])` harness itself). Construct
  `AndroidGitRepository` with `pathResolver = { null }` and call
  `clone(url = ..., localPath = "saf://...", auth = GitAuth.None, onProgress = {})`. Assert: (a)
  the result is `Either.Left` wrapping `DomainError.GitError.WorkingTreeSyncFailed(direction =
  "clone", ...)`, and (b) no `.git` directory exists under the shadow root afterward — confirming
  the guard runs *before* `Git.cloneRepository()`, not that JGit itself later happens to fail for
  an unrelated reason.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryStorageGuardTest.kt` (new)

##### Task 8.2.3b: Sufficient-storage happy-path confirmation (~3 min)
- Same file/technique, `StatFs` faked to report ample space. Assert `clone()` proceeds and succeeds
  exactly as Task 8.2.1a's baseline test expects — this closes the "happy path, unaffected by the
  new check" half of Gap #3 that the original Phase 8 test list omitted entirely alongside the
  error path.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryStorageGuardTest.kt`

### Epic 8.3: Scale, crash-resilience, and real-device validation (pitfalls §1.1, §1.2, §2.5)

#### Story 8.3.1: Large-graph regression test
**Files**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowWorktreeLargeGraphTest.kt` (new)

##### Task 8.3.1a: Full-cycle test against an 8000+ file synthetic tree (~5 min)
- Reuse `SyntheticGraphGenerator`'s `XLARGE` config (`kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/SyntheticGraphGenerator.kt:22`)
  to seed a fake SAF tree, run a full `syncFromSafRoot` → `openGit`/`merge` (or `commit`) →
  write-back cycle, wrapped in a `CoroutineScope` with a recording `CoroutineExceptionHandler`
  (per `LargeGraphWarmStartCrashTest`'s pattern) — assert no uncaught `Throwable` escapes, and that
  the single recursive `listRecursive` walk (Task 1.1.2a) completes and returns the expected entry
  count for the full synthetic tree (this is the one deliberately O(repo-size) operation in the
  design — see design decision #5 — so this test's job is confirming it stays *correct and
  non-crashing* at scale, not asserting it's sub-linear).
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowWorktreeLargeGraphTest.kt` (new)

##### Task 8.3.1b: Assert bounded SAF read/write IPC for the copy work (~5 min)
- Same file. Instrument the fake `readSafFile`/`writeFile` callbacks with call counters (the
  `listRecursive` callback is expected to be called once per sync — see 8.3.1a — so it's excluded
  from this bound). Assert a merge touching K files (out of the full XLARGE tree) results in O(K)
  SAF read/write calls, not O(graph size) — i.e. `syncFromSafRoot`'s mtime-skip logic (1.1.2a)
  actually skips re-reading/re-writing the unchanged files the single listing call enumerated, so
  only the copy *work*, not the listing *call*, stays bounded — the filesystem-layer analogue of
  `CLAUDE.md`'s bounded-read discipline (pitfalls.md §1.2).
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowWorktreeLargeGraphTest.kt`

#### Story 8.3.2: Manual validation checklist (not automatable)
**Files**: none (feeds into `sdd:4-validate`'s pre-mortem/validation docs, not implementation)

##### Task 8.3.2a: Write the real-device validation checklist (~3 min)
- A short checklist for Phase 4 (`sdd:4-validate`) / pre-ship manual QA: full clone → local edit →
  commit → fetch → merge (conflict and non-conflict) → push cycle, executed on **physical Android
  hardware**, not emulator-only — to catch any JGit `FS` auto-detection surprise emulators can mask
  (pitfalls.md §2.5). Include: SAF-only device (no `MANAGE_EXTERNAL_STORAGE`), a low-storage device
  or simulated low-storage condition (6.2.1's `StatFs` guard), and a widget/share-target write
  racing an in-flight sync (4.1/3.1.2's concurrent-edit path).
- Files: none (documented as part of `sdd:4-validate` output, referenced here so it isn't dropped)

### Epic 8.4: Real-Transport & Deep-Integration Coverage (closes validation.md Gaps #1, #4, #5, #6)

**Goal**: The four remaining Phase 4 gaps that don't fit as incremental additions to Epics 8.1-8.3's
existing files — real end-to-end transport coverage (Gap #1, the highest-priority gap overall),
first-clone reconciliation (Gap #4), true concurrent-execution ordering (Gap #5), and path-mapper
adversarial input (Gap #6). Placed last in Phase 8 (alongside Epic 8.3) since it depends on every
earlier phase's real production code (`shadowWorktreeFor`, `configureAuth`/`configureTransport`,
`GitWorktreeLocks`, `GitWorktreePathMapper`) already existing, plus — specifically for Gap #1 —
Story 0.1.1's SSH transport version bump (Phase 0) having already landed, since testing the
pre-bump `5.13.3` transport against a `7.3.0`-core embedded test server is not the scenario this
epic is meant to prove out.

#### Story 8.4.1: Real JGit transport round trips — local, SSH, HTTPS (closes Gap #1, the highest-
priority gap: Story 0.1.1's acceptance criteria promised this and nothing in the original Phase 8
delivered it)
**As a** developer, **I want** `AndroidGitRepository.clone()`/`fetch()`/`push()` exercised against
real embedded git servers over each transport this project's constraint C-2 requires preserving,
**so that** SSH-over-`mwiede/jsch` and arbitrary-host HTTPS have actual automated coverage of the
real `configureAuth`/`configureTransport`/`buildJschSessionFactory` code paths, not just the
manual device checklist (Task 8.3.2a).

**Research basis (verified against Maven Central and JGit's own published API docs this planning
pass, not assumed)**: JGit publishes three dedicated test-support artifacts under its own
`org.eclipse.jgit` Maven group, each confirmed to exist at **exactly** `7.3.0.202506031305-r` —
the same version already pinned for JGit core/Desktop's SSH transport and (per Task 0.1.1a) Android's
SSH transport module, so there is no version-skew risk in adding these:
- `org.eclipse.jgit:org.eclipse.jgit.junit:7.3.0.202506031305-r` — core test harness. Provides
  `org.eclipse.jgit.junit.LocalDiskRepositoryTestCase` (abstract, JUnit4-compatible — confirmed via
  its published Javadoc: `public abstract class LocalDiskRepositoryTestCase extends Object`, using
  `@Before`/`@After`-annotated `setUp()`/`tearDown()`, **not** a JUnit3 `TestCase` — so it composes
  with this codebase's `@RunWith(RobolectricTestRunner::class)` classes by extension, though this
  specific composition has no existing precedent in this repo and should be confirmed the first
  time it's actually compiled) and `org.eclipse.jgit.junit.TestRepository` (a thin wrapper for
  seeding commits into a `Repository` from test code). Its own POM declares `junit:junit` as
  `provided` scope, which Gradle does not pull transitively — already satisfied by this project's
  existing `junit:junit:4.13.2` `androidUnitTest` dependency, so no extra action needed there.
- `org.eclipse.jgit:org.eclipse.jgit.junit.ssh:7.3.0.202506031305-r` — an embedded, Apache
  MINA-SSHD-based SSH git server for tests, `org.eclipse.jgit.junit.ssh.SshTestGitServer`.
  Confirmed real constructor (from its published source): `SshTestGitServer(@NonNull String
  testUser, @NonNull Path testKey, @NonNull Repository repository, @NonNull KeyPair hostKey)`
  (also overloaded for a `PublicKey testKey` / `Path`-or-`KeyPair` `hostKey`), plus `public int
  start()` (starts on a random port, returns it) and `public void stop()`. Since SSH is a wire
  protocol, not a client-library-specific handshake, this MINA-SSHD-based *server* is fully
  interoperable with Android's real *client* stack (`org.eclipse.jgit.ssh.jsch` +
  `com.github.mwiede:jsch`, exercised via `AndroidGitRepository.buildJschSessionFactory` /
  `configureAuth`'s `GitAuth.SshKey` branch) — this is the actual production code path the test
  exercises, not a substitute. **Not fully pinned down this pass**: the exact key-file format
  `SshTestGitServer`'s `testKey: Path` parameter expects, and the precise host-key generation
  call — flagged explicitly below in Task 8.4.1b rather than guessed at.
- `org.eclipse.jgit:org.eclipse.jgit.junit.http:7.3.0.202506031305-r` — an embedded Jetty-based
  smart-HTTP git server, `org.eclipse.jgit.junit.http.AppServer`. Confirmed real methods (from its
  published Javadoc): `void setUp()` (starts on a random local port), `void tearDown()`,
  `ServletContextHandler addContext(String path)`, `ServletContextHandler authBasic(ServletContextHandler
  ctx, String... methods)`, `URI getURI()`. Its POM declares a real (non-`provided`) dependency on
  `org.eclipse.jgit:org.eclipse.jgit.http.server` (provides `GitServlet`) and
  `org.eclipse.jetty.ee10:jetty-ee10-servlet`/`org.eclipse.jgit:org.eclipse.jgit.junit`, all pulled
  transitively by declaring just the one Gradle dependency above. **Not fully pinned down this
  pass**: the exact `ServletContextHandler`/`GitServlet` wiring call shape (which `addServlet(...)`
  overload, how `RepositoryResolver` is set) — JGit's own test suite has the canonical example at
  `org.eclipse.jgit.http.test/tst/org/eclipse/jgit/http/test/SmartClientSmartServerTest.java` in
  the JGit source tree; Task 8.4.1c cites this as the implementation-time reference rather than
  inventing the wiring from the Javadoc alone.
- A plain local-disk `file://` round trip (`LocalDiskRepositoryTestCase`/`TestRepository`, no
  embedded server needed at all) is the lowest-effort, already-proven complement — Task 8.4.1a —
  distinct from, not a replacement for, the SSH/HTTPS-specific tests, since it doesn't exercise
  `configureAuth`/`configureTransport` at all.
- All three artifacts are plain-JVM (Jetty/MINA-SSHD have no Android-framework dependency), so they
  run fine under `androidUnitTest`'s Robolectric/JVM process — no emulator/device needed, matching
  this plan's existing `androidUnitTest` placement for every other new collaborator.
- **One-line note, not in scope here**: Desktop/JVM (`JvmGitRepository`) has zero SSH transport test
  coverage either (Story 7.1.2's existing scope stops at a plain init/commit/status/log roundtrip,
  Task 7.1.2a) — since `org.eclipse.jgit.junit.ssh`'s `SshTestGitServer` is plain JVM, `jvmTest`
  could reuse the identical embedded-server pattern this epic introduces for Android's
  `org.eclipse.jgit.ssh.apache` transport. Worth a future small follow-up; not one of the 7 gaps
  this task is scoped to close, and not added here to avoid scope creep beyond what was asked.

**Acceptance Criteria**: a real `clone()` → local `commit()` → `fetch()` → `push()` cycle succeeds
against a real embedded server for each of: local `file://`, SSH (`mwiede/jsch` client → MINA-SSHD
test server), HTTPS with basic auth (`UsernamePasswordCredentialsProvider` → Jetty test server) —
each assertion confirmed both by the JGit-level result and by inspecting the *remote* bare
repository afterward (new commit present), not just a non-error return value.
**Files**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitTransportLocalRoundTripTest.kt` (new),
`kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositorySshTransportTest.kt` (new),
`kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryHttpsTransportTest.kt` (new),
`kmp/build.gradle.kts` (three new `androidUnitTest` test dependencies)

##### Task 8.4.1a: Baseline local `file://` round trip (~5 min)
- Add `implementation("org.eclipse.jgit:org.eclipse.jgit.junit:7.3.0.202506031305-r")` to
  `androidUnitTest`'s dependency block in `kmp/build.gradle.kts` (placed near the existing
  `org.robolectric:robolectric` line). New file, `@RunWith(RobolectricTestRunner::class)
  @Config(sdk = [29])`. Use `LocalDiskRepositoryTestCase.createBareRepository()` to make a "remote"
  bare repo, seed one commit via `TestRepository(bareRepo)`. Construct `AndroidGitRepository(context
  = ApplicationProvider.getApplicationContext(), fileSystem = <FakeExternalStorageProvider-backed
  fake>, pathResolver = { null })`. Call `clone(url = "file://${bareRepo.directory.absolutePath}",
  localPath = "saf://test-tree/repo", auth = GitAuth.None, onProgress = {})`; assert success and
  that the seeded file's content landed in the shadow tree. Then: write a new file via the fake SAF,
  `stageSubdir`/`commit`/`push` back to the bare repo; assert (via a fresh `TestRepository(bareRepo)`
  walk of the bare repo's ref) the pushed commit is actually present remotely — the same
  round-trip shape 8.4.1b/8.4.1c repeat per-transport.
  `` `clone fetch and push round trip content unmodified over a local file transport shadow worktree` ``
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitTransportLocalRoundTripTest.kt` (new),
  `kmp/build.gradle.kts`

##### Task 8.4.1b: SSH transport round trip against an embedded `SshTestGitServer` (~8 min — the
highest-priority single task in this entire gap-closing pass; widened budget reflects genuine setup
complexity flagged above, not padding)
- Add `implementation("org.eclipse.jgit:org.eclipse.jgit.junit.ssh:7.3.0.202506031305-r")` to
  `androidUnitTest`. New file, same Robolectric harness and bare-repo seeding as 8.4.1a. Generate a
  throwaway RSA (or ED25519) test-user key pair via `java.security.KeyPairGenerator` and a separate
  host key pair for `SshTestGitServer`'s `hostKey: KeyPair` constructor parameter; **confirm the
  exact expected on-disk format for the `testKey: Path` parameter and the precise host-key
  generation call against JGit's own SSH transport test suite usage at implementation time** (search
  the JGit source tree, e.g. `org.eclipse.jgit.junit.ssh`'s own consumers under
  `org.eclipse.jgit.internal.transport.sshd.*Test`/`org.eclipse.jgit.transport.sshd.*Test`, for the
  canonical `new SshTestGitServer(...)` construction pattern — this was not fully pinned down this
  research pass, flagged rather than guessed at per this plan's established convention). Start the
  server (`val port = sshTestGitServer.start()`). Construct `AndroidGitRepository(sshKeyProvider = {
  testPrivateKeyBytes }, pathResolver = { null }, context = ..., fileSystem = ...)` — supplying the
  private key directly via `sshKeyProvider` (which `buildJschSessionFactory` prefers over
  `keyPath`-based loading, `AndroidGitRepository.kt:441-444`) sidesteps needing a real filesystem
  key path. `buildJschSessionFactory` already sets `StrictHostKeyChecking=accept-new`
  (`AndroidGitRepository.kt:436`), so no client-side known-hosts setup is needed. Call `clone(url =
  "ssh://$testUser@localhost:$port/repo", localPath = "saf://...", auth = GitAuth.SshKey(keyPath =
  "unused-sshKeyProvider-is-set", passphraseProvider = { null }), onProgress = {})`; assert success.
  **`fetch()`/`push()` need their own auth wiring, distinct from `clone()`'s `GitAuth`** (verified
  via `AndroidGitRepository.kt:103,281`: both call `configureTransport(it, config)`, never
  `configureAuth` — a real, pre-existing asymmetry in the production interface, not something this
  test can route around). Build the `GitConfig` used for the repeat round trip with
  `authType = GitAuthType.SSH_KEY` and any non-null placeholder `sshKeyPath` (e.g.
  `"unused-sshKeyProvider-is-set"`, matching the `auth` value above) — the actual path never needs
  to resolve to a real file, since `buildJschSessionFactory` still prefers the constructor's
  `sshKeyProvider` closure over `keyPath`-based loading (`AndroidGitRepository.kt:441-444`); the
  `GitConfig.sshKeyPath` field only needs to be non-null to pass `configureTransport`'s
  `SSH_KEY` branch's `config.sshKeyPath != null` gate (`AndroidGitRepository.kt:466-472`). Then
  repeat the commit → `fetch(config)` → `push(config)` → remote-inspection round trip from 8.4.1a,
  passing this `GitConfig`. Stop the server in teardown.
  `` `clone fetch and push succeed over ssh transport against an embedded jsch-compatible test server` ``
  This is the test Story 0.1.1's acceptance criteria was forward-referencing.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositorySshTransportTest.kt` (new),
  `kmp/build.gradle.kts`

##### Task 8.4.1c: HTTPS transport round trip against an embedded smart-HTTP `AppServer` (~6 min)
- Add `implementation("org.eclipse.jgit:org.eclipse.jgit.junit.http:7.3.0.202506031305-r")` to
  `androidUnitTest`. New file, same harness. `val server = AppServer(); server.setUp()`. Wire a
  `org.eclipse.jgit.http.server.GitServlet` (from the transitively-pulled
  `org.eclipse.jgit.http.server` artifact) with a `RepositoryResolver` resolving to the seeded bare
  repo, registered on a context added via `server.addContext("/git")` — **confirm the exact
  `ServletContextHandler`/`addServlet(...)` wiring call against
  `org.eclipse.jgit.http.test/tst/org/eclipse/jgit/http/test/SmartClientSmartServerTest.java` in
  JGit's own source tree at implementation time** (the canonical example for this exact setup;
  Javadoc alone doesn't show the wiring shape, flagged rather than guessed at). Call
  `server.authBasic(ctx, "GET", "POST")` so the test actually exercises `configureAuth`'s
  `GitAuth.HttpsToken` → `UsernamePasswordCredentialsProvider` branch, not an unauthenticated
  shortcut. Call `clone(url = "${server.uri}/git/repo", localPath = "saf://...", auth =
  GitAuth.HttpsToken(username = "testuser", tokenProvider = { "testpass" }), onProgress = {})`;
  assert success. **`fetch()`/`push()` need separate, explicit credential wiring — they do not
  reuse `clone()`'s `auth` parameter at all** (same asymmetry as 8.4.1b:
  `AndroidGitRepository.kt:103,281` route through `configureTransport(it, config)`, whose
  `HTTPS_TOKEN` branch does `config.httpsTokenKey?.let { credentialAccess.retrieve(it) } ?: return`
  — i.e. it needs a real `credentialAccess.retrieve()` call to resolve "testpass", and the
  constructor's default `credentialAccess: CredentialAccess = CredentialStore()`
  (`AndroidGitRepository.kt:38-40`) would hit the real Android-Keystore-backed
  `AndroidCredentialStore`, which that class's own doc comment (`AndroidCredentialStore.kt:19-24`)
  flags as fighting Robolectric's Keystore shadow behavior — do not rely on the default here.
  Construct this test's `AndroidGitRepository` with an explicit fake `credentialAccess: CredentialAccess`
  (a trivial in-memory `retrieve(key) = "testpass"` stub) instead, and build the `GitConfig` used
  for the repeat round trip with `authType = GitAuthType.HTTPS_TOKEN` and `httpsTokenKey` set to
  whatever key the fake's `retrieve()` recognizes. Then repeat the commit → `fetch(config)` →
  `push(config)` → remote-inspection round trip, passing this `GitConfig`. `server.tearDown()` in
  teardown.
  `` `clone fetch and push succeed over https transport with basic auth against an embedded smart http test server` ``
  This closes the constraint-C-2 half of Gap #1 (arbitrary self-hosted/non-GitHub HTTPS remotes) —
  `AppServer` is a generic Jetty smart-HTTP server, not GitHub/GitLab-shaped, so a passing test here
  is direct evidence the implementation doesn't implicitly assume a specific host.
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryHttpsTransportTest.kt` (new),
  `kmp/build.gradle.kts`

#### Story 8.4.2: Initial-clone reconciliation against a pre-populated SAF-only graph (closes Gap
#4 / Open Question 5, Task 1.2.3e)
**As a** user who already had markdown files in my SAF folder before configuring Git Sync, **I
want** those files to show up as my first set of local changes once I clone/init, **so that**
enabling Git Sync doesn't silently ignore or discard pre-existing content.
**Acceptance Criteria**: pre-populating the fake SAF provider before calling `clone()`/`init()`
results in those files appearing in `status()`'s `untrackedFiles`/`modifiedFiles` on the very first
call afterward, with no separate reconciliation step required from the caller.
**Files**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryShadowWorktreeTest.kt`
(reuses Task 8.2.1a's file — this scenario is a direct extension of that file's existing
init/clone/status coverage, not a new concern warranting its own file)

##### Task 8.4.2a: Pre-existing SAF content survives `clone()`/`init()` and reaches first `status()` (~5 min)
- Same file as 8.2.1a. Two cases: (1) populate the fake SAF provider with markdown files under
  `repoRoot` *before* calling `clone(url = <bare repo with different/no content>, localPath =
  repoRoot, ...)`; assert the first `status(config)` call afterward (once `config.repoRoot ==
  repoRoot`) reports the pre-existing files as untracked/modified — proving Task 1.2.3e's
  unconditional `worktree.syncFromSafRoot(...)` call after `Git.cloneRepository()` actually ran. (2)
  Same setup but with `init(repoRoot)` instead of `clone()`, same assertion — Task 1.2.3e's text
  covers both call sites symmetrically, so both need coverage, not just the clone case named
  explicitly in Open Question 5.
  `` `clone reconciles pre-existing SAF-only markdown into the shadow tree so first status shows it as uncommitted` ``;
  `` `init reconciles pre-existing SAF-only markdown into the shadow tree so first status shows it as uncommitted` ``
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryShadowWorktreeTest.kt`

#### Story 8.4.3: True concurrent-execution ordering for cross-subsystem mutual exclusion (closes
Gap #5 — extends Task 5.2.1c's structural-only test)
**As a** developer, **I want** proof that the shared `Mutex` actually serializes a real concurrent
git-sync and write-behind-flush, not just that both happen to acquire the same instance, **so that**
the property ADR-018 relies on for avoiding shadow/SAF corruption is behaviorally verified.
**Acceptance Criteria**: two coroutines racing for the same `shadowKey`'s lock — one simulating
`GitShadowWorktree.syncFromSafRoot`'s critical section, one simulating `PlatformFileSystem`'s
write-behind flush — never execute inside the locked section concurrently, verified by an ordering
assertion (not just "both acquired *a* lock").
**Files**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitWorktreeLocksSharedKeyTest.kt`
(Task 5.2.1c's file — this is a genuine extension of that file's existing scope, not a new concern)

##### Task 8.4.3a: Interleaved-execution ordering assertion (~5 min)
- Same file. In addition to 5.2.1c's same-`Mutex`-instance assertion, add a test that launches two
  coroutines concurrently via `async { }` against `GitWorktreeLocks.lockFor(sharedKey)`: one holds
  the lock for a short simulated delay while appending `"git-enter"`/`"git-exit"` markers (with the
  delay in between) to a shared, lock-external `MutableList<String>`; the other, launched
  concurrently, attempts to acquire the same lock and appends `"flush-enter"`/`"flush-exit"` the
  same way. Await both. Assert the recorded marker sequence never shows `"flush-enter"` appearing
  between `"git-enter"` and `"git-exit"` (or vice versa) — i.e. the two critical sections are
  strictly non-overlapping in the recorded order, not just eventually-both-ran. This is the
  behavioral property 5.2.1c's structural (same-instance) assertion alone cannot prove.
  `` `GitShadowWorktree sync and PlatformFileSystem flushPendingWrites never execute inside the shared Mutex's critical section concurrently` ``
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitWorktreeLocksSharedKeyTest.kt`

#### Story 8.4.4: Path-mapper adversarial-input coverage (closes Gap #6)
**As a** developer, **I want** confirmation that a malformed or path-traversal input to the
path-mapper can never cause a file operation outside `worktreeRoot`, **so that**
`safeWorktreeFile`'s ported traversal guard (Task 1.1.1a) actually has a test proving the two
mechanisms (lenient path mapping + strict traversal guard) compose safely together.
**Acceptance Criteria**: a `../`-containing, empty, or unrelated-prefix input to
`toGitRelativePath`/`toUserFacingPath` never produces a path that `safeWorktreeFile` accepts as
pointing outside `worktreeRoot`.
**Files**: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowWorktreeTest.kt` (Task
8.1.1c's file — direct extension of its existing path-mapper round-trip coverage)

##### Task 8.4.4a: Adversarial inputs to `toGitRelativePath`/`toUserFacingPath` (~5 min)
- Same file. Beyond 8.1.1c's happy-path (top-level and nested-subdir `repoRoot`) round trips, add
  cases for: a path-traversal input (`"../../etc/passwd"`), an empty string, and an unrelated
  absolute path sharing no prefix with `safRoot`/`worktreeRoot`. For each, feed the mapped result of
  `toGitRelativePath` into the private `safeWorktreeFile` helper (Task 1.1.1a — mark it `internal`
  for this test if not already, following the same testability-visibility pattern used elsewhere in
  this plan) and assert it returns `null`/rejects rather than resolving to a `File` outside
  `worktreeRoot` — this is the test that actually exercises the traversal guard `safeWorktreeFile`
  was ported specifically to provide, which no existing test in Phase 8's original list touched.
  `` `toGitRelativePath and toUserFacingPath on path-traversal and malformed input never resolve outside worktreeRoot once passed through safeWorktreeFile` ``
- Files: `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowWorktreeTest.kt`,
  `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt` (`safeWorktreeFile`
  visibility only, if needed)

---

## Summary of files touched (new vs. edited)

**New files (20)**:
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitWriteBackQueue.kt`
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowFlushActor.kt`
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/GitWorktreeLocks.kt`
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowWorktreeTest.kt`
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitWriteBackQueueTest.kt`
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowFlushActorTest.kt`
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryShadowWorktreeTest.kt`
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitPathResolverChainTest.kt`
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitShadowWorktreeLargeGraphTest.kt`
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitWorktreeLocksSharedKeyTest.kt` (Task 5.2.1c)
- `androidApp/src/test/kotlin/dev/stapler/stelekit/MainActivityGitRepositoryWiringTest.kt` (Task
  5.1.2a — lives in `:androidApp`, not `:kmp`, since it must import `MainActivity`)
- `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/git/JvmGitRepositoryTest.kt`
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryStorageGuardTest.kt`
  (Story 8.2.3 — gap-closing)
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/GitTransportLocalRoundTripTest.kt` (Task
  8.4.1a — gap-closing)
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositorySshTransportTest.kt`
  (Task 8.4.1b — gap-closing, highest priority)
- `kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/git/AndroidGitRepositoryHttpsTransportTest.kt`
  (Task 8.4.1c — gap-closing)

**Edited files (9, unchanged list — the three gap-closing new files above account for the new-file
growth; no additional edited files were needed)**:
- `kmp/build.gradle.kts` (SSH transport version bump, **plus three new `androidUnitTest` test
  dependencies added by Epic 8.4**: `org.eclipse.jgit:org.eclipse.jgit.junit`,
  `org.eclipse.jgit:org.eclipse.jgit.junit.ssh`, `org.eclipse.jgit:org.eclipse.jgit.junit.http`, all
  pinned to `7.3.0.202506031305-r`)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt` (new `GitError` cases)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/ConflictModels.kt` (doc comment only)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt` (comment only)
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt` (two new interface
  members with no-op/default bodies: `listFilesRecursiveWithModTimes`, `setGitShadowKeyProvider`)
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt` (bulk of the change;
  **plus `resolveForJGit` visibility `private` → `internal`, Task 8.2.1d**)
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/WorkManagerSyncScheduler.kt` (`context` +
  explicit `fileSystem` args added)
- `kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` (mutex around
  write-behind flush, `setGitShadowKeyProvider` override)
- `androidApp/src/main/kotlin/dev/stapler/stelekit/MainActivity.kt` (construction — wired to
  `app.fileSystem`, not a fresh `PlatformFileSystem()` — + orphan sweep; `GraphManager.kt`/`App.kt`
  are no longer touched — the registered-handler approach originally planned for graph-removal
  cleanup was dropped in favor of the orphan sweep alone, see Epic 6.1's rationale)

**Untouched by design (verified in Phase 7)**: everything under `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/git/`
and `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/`; `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitRepository.kt`.
