# Stack Research: android-git-saf-shadow-worktree

Scope: technology/dependency findings only, feeding SDD Phase 3 (plan). No code
written. All file:line references are against the working tree at the time of
this research (commit `0e1b3419b9`, branch `main`).

## 1. Dependency versions in use

Read directly from `kmp/build.gradle.kts`.

| Artifact | Desktop (`jvmMain`) | Android (`androidMain`) |
|---|---|---|
| JGit core | `org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r` ([build.gradle.kts:169](../../../kmp/build.gradle.kts#L169)) | `org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r` ([build.gradle.kts:322](../../../kmp/build.gradle.kts#L322)) — **same major version as Desktop**, contradicts the requirements doc's "Android uses JGit 5.13.x" claim |
| SSH transport | `org.eclipse.jgit.ssh.apache:7.3.0...` — Apache MINA sshd ([build.gradle.kts:170](../../../kmp/build.gradle.kts#L170)) | `org.eclipse.jgit.ssh.jsch:5.13.3.202401111512-r` ([build.gradle.kts:325](../../../kmp/build.gradle.kts#L325)) — **pinned ~2 major JGit releases behind core**, `com.jcraft:jsch` excluded |
| SSH key/algorithm support | Whatever MINA sshd 2.x supports natively | `com.github.mwiede:jsch:0.2.21` fork, added purely for ED25519/ECDSA/OpenSSH key support ([build.gradle.kts:328-329](../../../kmp/build.gradle.kts#L328-L329)) |
| SAF | n/a | `androidx.documentfile:documentfile:1.0.1` ([build.gradle.kts:255](../../../kmp/build.gradle.kts#L255)) |

**Correction to requirements.md**: the requirements doc states "Desktop uses
JGit 7.x" vs. Android's "JGit 5.13.x". That is stale/inaccurate — Android's
JGit **core** artifact is already 7.3.0, matching Desktop (comment at
build.gradle.kts:321 confirms this was deliberate: "matches Bazel-resolved
version"). The real version-skew is narrower and specifically in the **SSH
transport layer**: Android is stuck on the `org.eclipse.jgit.ssh.jsch` 5.13.3
artifact because JGit dropped shipping new versions of that module — see §5.
`AndroidGitRepository.kt`'s own class doc comment ("JGit 5.13.x + mwiede/jsch",
[AndroidGitRepository.kt:32](../../../kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt#L32))
is itself stale and should be corrected in the same pass that touches this
file, since it will mislead the next reader.

**Relevance to shadow-tree design**: because JGit core is version-matched
across platforms, `FileRepositoryBuilder`/`Git.open(File)` semantics,
`DirCacheCheckout`, `WorkingTreeIterator`, and merge/status behavior are
identical between Desktop and the new Android shadow-tree path — no
version-specific working-tree quirks to design around. The only real
skew is the SSH session-factory API (`JschConfigSessionFactory` vs. MINA's
`SshdSessionFactory`), which is orthogonal to the shadow-tree question and
already isolated behind `configureTransport`/`configureAuth` in
`AndroidGitRepository.kt`.

## 2. Existing code read (not guessed)

### `AndroidGitRepository.kt`

- Constructor takes `pathResolver: (String) -> String? = { null }`
  ([AndroidGitRepository.kt:41](../../../kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt#L41)) —
  this is the injection point the shadow-tree design should extend or replace.
  It is currently wired (from call sites, not shown in this file) to
  `PlatformFileSystem.resolveSafToRealPath`, i.e. the `MANAGE_EXTERNAL_STORAGE`
  fast path only.
- `resolveForJGit(path)` ([AndroidGitRepository.kt:415-429](../../../kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt#L415-L429)):
  calls `pathResolver(path)`; if null and `path` starts with `saf://`, logs a
  warning and **falls through to returning the raw `saf://...` string**, which
  is what produces the cryptic JGit "repository not found" error described in
  the requirements doc. This is the exact function a shadow-tree resolver
  needs to hook — either by making `pathResolver` shadow-tree-aware, or by
  adding a second resolution tier here (`MANAGE_EXTERNAL_STORAGE` real path →
  shadow-tree path → raw saf:// as last resort, which should probably become
  a hard error instead of a silent fallthrough once a shadow path always
  exists).
- `config.repoRoot` is used as a raw string in `openGit()` (line 431),
  `removeStaleLockFile()` (line 395), and — critically — in `merge()` to build
  **absolute display/lookup paths for conflicts**: `"${config.repoRoot}/$filePath"`
  ([AndroidGitRepository.kt:217](../../../kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt#L217))
  and the wiki-subdir-relative path
  ([AndroidGitRepository.kt:218-223](../../../kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt#L218-L223)),
  plus the `changedFiles` diff paths
  ([AndroidGitRepository.kt:246,263](../../../kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/AndroidGitRepository.kt#L246)).
  **If `repoRoot` becomes the shadow directory**, every one of these
  `absolutePath`/`ConflictFile.filePath` values will be a shadow-tree path
  (e.g. `/data/data/.../files/graphs/<id>/shadow/pages/Foo.md`), not the
  user's SAF location. See §UI mapping below — this is not free, it needs an
  explicit remap step.
- `checkoutFile()` (line 353) and `markResolved()` (line 368) strip
  `"${config.repoRoot}/"` as a prefix from an incoming absolute path to get
  the git-relative path — same exposure: any caller building that absolute
  path from `config.repoRoot` will build a shadow-tree path once repoRoot is
  redirected.
- SSH auth: `buildJschSessionFactory()` (line 433) sets
  `StrictHostKeyChecking = "accept-new"` unconditionally (line 436) — no
  known_hosts persistence is visible in this file at all; jsch's default
  `HostKeyRepository` behavior (in-memory unless a known_hosts path is
  explicitly configured) means **host keys are not currently persisted across
  app runs** on Android. Out of scope to fix here, but worth flagging as an
  adjacent SSH-transport risk independent of the shadow-tree work.

### `ShadowFileCache.kt`

- Class doc explicitly states current invariants
  ([ShadowFileCache.kt:15-20](../../../kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ShadowFileCache.kt#L15-L20)):
  *"SAF is the only write target; shadow is a read cache derived from SAF."*
  This is the one-directional invariant requirements.md Open Question 1 asks
  about extending.
- `syncFromSaf(subdir, fileModTimes, readSafFile)` (line 66) is the existing
  SAF→shadow direction. It is **mtime-gated per file** (`shadowFile.lastModified() >= safMtime`
  skips the copy, line 75) and takes a pre-fetched `List<Pair<fileName, mtime>>`
  — i.e. it already assumes the caller did ONE batched SAF directory listing
  (`listFilesWithModTimes`) rather than per-file stats. This pattern is the
  one a git-aware "diff shadow vs SAF at the top of every git op" step should
  reuse rather than reinvent.
- Hardcoded to two subdirs: `"pages"` and `"journals"` (only these two are
  synced today, per `PlatformFileSystem.syncShadow()` at
  [PlatformFileSystem.kt:848-857](../../../kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt#L848-L857)).
  **A git shadow-tree needs the whole graph root** (any top-level files,
  `logseq/config.edn`-style dirs, `.git/` itself must NOT be synced from SAF
  since it doesn't exist there, assets, etc.) — the two-subdir scoping in both
  `ShadowFileCache` and `PlatformFileSystem.syncShadow`/`invalidateStaleShadow`
  is a real gap, not just a naming detail. This needs either a generalized
  "sync whole tree" method or a parallel mechanism scoped to the git working
  tree specifically (recommended — keep the existing narrow pages/journals
  cache, used for indexing performance, separate from a new full-tree git
  shadow mirror, since their staleness/consistency requirements differ).
- `invalidateStale()` (line 143) compares **both** mtime and size from a
  batched SAF cursor and documents *why* size is also checked: "the SAF
  provider returns a stale mtime — e.g. after Termux writes a file while the
  app is backgrounded" (lines 136-138). This same double-signal heuristic
  (mtime OR size mismatch ⇒ stale) is the right building block for detecting
  concurrent external SAF writes racing a git op — reuse it rather than
  designing a new diff heuristic.
- `deleteAll()` (line 163) nukes the whole shadow dir; currently invoked from
  `PlatformFileSystem.invalidateStaleShadow()` on first cache access per
  session (line 803 in PlatformFileSystem.kt) as a correctness-over-performance
  choice. A git shadow tree **cannot** reuse this as-is — deleting `.git/`
  on every cold start would nuke history/config/remotes. The "first access →
  full purge" policy needs to explicitly exclude `.git/` if the two caches
  are merged, or (recommended) stay a fully separate directory/instance from
  the pages/journals index cache.
- No write-behind / dirty-tracking concept exists in `ShadowFileCache` itself
  today (that lives in `WriteBehindQueue`/`ShadowFlushActor`, both scoped to
  the pages/journals editing flow, not general file sync) — a
  shadow→SAF-after-merge push is a new code path, not an extension of
  `ShadowFlushActor`.

### `PlatformFileSystem.kt`

- `resolveSafToRealPath()` (companion object,
  [PlatformFileSystem.kt:76-106](../../../kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt#L76-L106)):
  static, injectable (`isStorageManager` param for Robolectric testing),
  requires API 30+ and `Environment.isExternalStorageManager()`. Confirms
  requirements.md's framing — this is gated entirely on
  `MANAGE_EXTERNAL_STORAGE`, with no fallback.
- Instance method `resolveToRealPath()` (line 294) is the hot-path
  equivalent used throughout read/write/list methods, using the cached
  `treeRootDocId` instead of re-parsing the tree URI each call — a second,
  parallel implementation of the same resolution logic as the companion
  method. Any new shadow-tree resolver should be a **third** tier alongside
  these two, not a modification of either (both are exercised by existing
  tests, see §4).
- The SAF write primitives a shadow→SAF sync direction must reuse:
  `writeFile(path, content)` (line 435) and `writeFileBytes(path, data)`
  (line 387) — both already handle the create-vs-overwrite branch (via
  `knownExistingFiles`/`knownExistingDirs` caches to skip redundant
  existence-check IPC — see lines 406-425, 449-465) and both call
  `updateShadow()` after a successful **direct-access** write (line 442) to
  keep the pages/journals shadow cache in sync. **These are per-file, SAF-IPC
  round-trip calls** — there is no batch/bulk write primitive in
  `PlatformFileSystem` today. A shadow→SAF sync after a merge that touches N
  files means N sequential `writeFile`/`writeFileBytes` calls (see §3
  performance note).
- `listFilesWithModTimes()` (line 607) and the private `listFilesWithMetadata()`
  (line 820, returns `Triple<name, mtime, size>`) are the only batched-listing
  primitives, and both are **hardcoded to a single, non-recursive directory**
  query via `queryChildren()` (line 232) — one `ContentResolver.query()` per
  directory level, no recursive/tree-wide listing helper exists. A shadow↔SAF
  full-tree diff (needed at minimum for `pages/`, `journals/`, and any other
  top-level dirs/files in the graph) will need either N sequential calls (one
  per subdirectory, doesn't recurse into unknown-depth structures) or a new
  recursive-walk helper — neither exists today.
- `SafChangeDetector` (constructed at
  [PlatformFileSystem.kt:916-921](../../../kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt#L916-L921))
  is **also scoped to `pages/` and `journals/` only** — its FileObserver/
  ContentObserver registration targets exactly those two document nodes (see
  `SafChangeDetector.kt` class doc,
  [SafChangeDetector.kt:26-44](../../../kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/SafChangeDetector.kt#L26-L44)).
  It does not watch `.git/` or any other top-level directory a git working
  tree would contain. Reusing it for "detect external SAF write during an
  in-flight git op" (requirements.md Open Question 2) needs either broadening
  its watch scope or accepting that external writes to non-pages/journals
  paths (rare, since the wiki content lives there) are invisible to it.

### `GitRepository.kt` (commonMain interface)

- Confirms the `commit()`/`push()` split requirements.md's success metrics
  depend on: `commit()` (line 30) and `push()` (line 32) are separate suspend
  functions — no combined "commit and push" method exists, so nothing about
  a shadow-tree design threatens that separation.
- `MergeResult` (line 56) carries `hasConflicts`, `conflicts: List<ConflictFile>`,
  `changedFiles: List<String>`. `ConflictFile`
  ([ConflictModels.kt:8-12](../../../kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/ConflictModels.kt#L8-L12))
  already carries **both** `filePath` (absolute, built from `repoRoot`) and
  `wikiRelativePath` (relative to `wikiSubdir`) — and
  `ConflictResolutionScreen.kt` already prefers `wikiRelativePath` for display
  (`conflict.wikiRelativePath.ifBlank { conflict.filePath }`,
  [ConflictResolutionScreen.kt:190](../../../kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/ConflictResolutionScreen.kt#L190)).
  **This is good news for requirements.md Open Question 3**: the
  wiki-relative field already exists and is already what the UI shows: the
  remapping problem reduces to "make sure `wikiRelativePath` is computed
  correctly when `repoRoot` is a shadow path" (it already is, since it's
  computed purely from `wikiSubdir`, not from any SAF-specific logic) rather
  than inventing a new field. The `filePath` field, however, is genuinely
  shadow-relative once this change lands, and any code that treats
  `ConflictFile.filePath` as SAF-openable (there is none found in this repo
  today, but any future consumer must be warned in a doc comment) would
  break.
- `GitConfig.wikiRoot` extension (line 26,
  [GitConfig.kt](../../../kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/GitConfig.kt#L26))
  is `repoRoot` + optional `wikiSubdir` — purely string concatenation, so it
  transparently works whether `repoRoot` is a `saf://` string or a shadow
  `java.io.File` path, as long as callers don't assume the scheme.

## 3. SAF API constraints for bulk bidirectional sync

- **No batch/recursive listing API.** `DocumentsContract.buildChildDocumentsUriUsingTree`
  + a single `ContentResolver.query()` gets one directory level per IPC call
  (already how `queryChildren()` works in this codebase, `PlatformFileSystem.kt:232`).
  There is no `DocumentsContract` "list recursively" or "changes since
  cursor/token" API on stock Android SAF (this is unlike, e.g., Google Drive's
  `changes.list` API) — confirmed by web research: no evidence of a
  changes-token mechanism in `DocumentsContract`; some cloud-backed
  `DocumentsProvider` implementations (Google Drive) may support
  `Root.FLAG_SUPPORTS_RECENTS`/cursor notification extras, but that's
  provider-specific, not a documented cross-provider API SteleKit can depend
  on. **Recommendation**: the codebase's existing pattern — one `query()` per
  known subdirectory, mtime+size heuristic (`invalidateStale`) to skip
  unchanged files — is close to the best available primitive without a
  provider-specific extension; a shadow↔SAF sync should walk each **known**
  top-level directory of the graph (not attempt a generic recursive walk of
  an unknown-depth tree) to bound IPC cost.
- **SAF performance is well-documented as slow relative to `java.io.File`.**
  Community sources (CommonsWare "Scoped Storage Stories: DocumentsContract")
  cite complaints of ~100x slowdowns for SAF vs. direct file access, "though
  some of that is hyperbole" — no authoritative per-call latency number was
  found, so any number quoted in the plan phase should be measured on-device,
  not assumed. What's concretely knowable from the SAF architecture: each
  file **read or write is a Binder IPC round-trip through the
  `DocumentsProvider`**, and there is no bulk file-content transfer API — N
  files touched means N `openInputStream`/`openOutputStream` IPCs minimum,
  plus a query per directory for listing. This is exactly why the existing
  shadow cache exists for pages/journals reads ("avoids Binder IPC during
  Phase 3 background indexing", `ShadowFileCache.kt:12`) — the same
  motivation applies directly to a git-driven shadow→SAF write-back after
  merge.
- **Consequence for the "shadow → SAF after fetch/merge" direction**: a merge
  that changes K files means **at least K sequential SAF writes** (existing
  `writeFile`/`writeFileBytes` have no batch variant) plus whatever listing
  calls are needed to detect which shadow files actually changed vs. the last
  known SAF state (to avoid rewriting unchanged files). For a typical git
  pull with a handful of changed markdown files this is cheap; for a large
  initial reconciliation (open question 5) or a merge touching hundreds of
  files, this could be a multi-second-to-tens-of-seconds operation that must
  run off the main thread (already the pattern via `PlatformDispatcher.IO`)
  and should surface progress to the user rather than block silently.
- **No API-level way to get "just the diff since last sync" from a
  `DocumentsProvider`.** The mtime+size heuristic already in
  `ShadowFileCache.invalidateStale()` is the practical substitute — it is a
  full per-directory listing (batched IPC) followed by local comparison, not
  a true incremental-changes API. This should be explicitly designed as
  "always list, cheaply compare" rather than something claiming to be a true
  changes-since-token sync, so the plan phase doesn't assume push-notification
  style change delivery is available cross-provider.

## 4. Existing test infrastructure to extend

- **Zero existing tests for `AndroidGitRepository`** — confirmed by search
  (`find kmp/src/androidUnitTest -iname "*git*"` returns nothing under that
  package; `grep -rl AndroidGitRepository kmp/src --include="*.kt"` matches
  only the implementation file itself). A shadow-tree redesign of this class
  has no regression safety net today and should establish one as part of
  this project (Robolectric-based, following the pattern in
  `ShadowFileCacheTest.kt`/`ShadowFlushActorTest.kt` below), not just extend
  something existing.
- `ShadowFileCacheTest.kt` and `ShadowFlushActorTest.kt`
  (`kmp/src/androidUnitTest/kotlin/dev/stapler/stelekit/platform/`) — direct
  unit tests of the class whose invariants this project changes. Any change
  to `ShadowFileCache`'s one-directional contract must keep these green or
  deliberately revise them.
- `PlatformFileSystemSafResolveTest.kt` — tests
  `resolveSafToRealPath()`/`isStorageManager` injection specifically; the
  `MANAGE_EXTERNAL_STORAGE`-fast-path vs. new-shadow-path decision logic
  (Open Question 6) should add tests alongside this file since it already
  establishes the pattern for injecting `isStorageManager` in Robolectric
  (real `Environment.isExternalStorageManager()` can't be shadowed).
- `PlatformFileSystemSafTest.kt`, `PlatformFileSystemUriLogicTest.kt`,
  `PlatformFileSystemPickerTest.kt`, `SafPermissionPersistenceTest.kt`,
  `SafPermissionStateTransitionTest.kt`, `SafIndexingLagReproductionTest.kt` —
  broad existing SAF behavior coverage in `androidUnitTest`; a full-tree
  shadow sync will touch several of the same code paths (`writeFile`,
  `listFilesWithModTimes`, shadow invalidation) these already exercise, so
  new tests should sit in this same package and reuse
  `FakeExternalStorageProvider.kt` (`androidUnitTest/.../testsupport/`) rather
  than building new SAF fakes.
- `SafChangeDetectorFileObserverTest.kt` — covers the existing FileObserver/
  ContentObserver detector; if its watch scope is broadened for this
  project (per §2), this file needs corresponding new cases.
- `GitSyncServiceTest.kt` (`kmp/src/businessTest/.../git/`) — uses a
  hand-rolled `StubGitRepository : GitRepository` (line 70) rather than the
  real `AndroidGitRepository`/`JvmGitRepository`. This is the right level for
  testing `GitSyncService` orchestration logic platform-independently, and
  confirms the interface-level testing pattern already used for
  `GitRepository` consumers — but it means **no existing test exercises real
  JGit behavior on Android at all**, reinforcing the "zero coverage" finding
  above.
- `DiskConflictResolutionTest.kt` (jvmTest),
  `DiskConflictFullScreenStateTest.kt` (businessTest),
  `DiskConflictBlockMatcherTest.kt` (businessTest) — cover the **disk**
  conflict UI (external file changes vs. in-app edits), a different mechanism
  from git merge conflicts (`ConflictResolutionScreen`/`MergeResult`) despite
  similar naming — relevant only if requirements.md Open Question 2 concludes
  `DiskConflict` machinery should be reused for SAF-write-races-git-op
  detection; worth reading before deciding since they test a related but
  distinct conflict model.
- Representative large-graph sizing reference already in the repo:
  `SyntheticGraphGenerator.kt` documents an `XLARGE` config of "7978 pages,
  2930 journals, power-law topology (2× a real measured library)"
  ([SyntheticGraphGenerator.kt:22](../../../kmp/src/jvmTest/kotlin/dev/stapler/stelekit/benchmark/SyntheticGraphGenerator.kt#L22)),
  and `LargeGraphWarmStartCrashTest.kt` exercises an 8 030-page graph. These
  are the existing "large graph" fixtures the plan phase should reuse for
  storage-footprint sizing (Open Question 4) rather than inventing new
  synthetic data — but note these measure **markdown content volume**, not
  `.git` history size, so a `.git`-directory footprint number (tracked
  objects, pack size over N commits of real edit history) is not yet
  available anywhere in the repo and must be measured separately (e.g. by
  cloning a real user's history-bearing graph, or by simulating N days of
  edits against the XLARGE synthetic graph and running `git gc`).

## 5. Community best practices / known gotchas (2025-2026)

- **`org.eclipse.jgit.ssh.jsch` is community-confirmed deprecated/effectively
  unmaintained.** Its own README states it "should be considered deprecated"
  and warns the JGit project "may decide to remove [it] completely without
  further notice," with `org.eclipse.jgit.ssh.apache` (Apache MINA sshd) as
  the officially supported transport going forward. This matches what's in
  the codebase: Android is on `ssh.jsch:5.13.3` — the last (or near-last)
  published version — specifically because it's the module that still wires
  up `JschConfigSessionFactory`, which the mwiede/jsch fork is used to unlock
  ED25519/ECDSA/OpenSSH key support that plain jsch and (per search results)
  even MINA sshd historically lacked. **Risk**: this dependency could
  disappear from Maven Central futures/be frozen at 5.13.3 forever while
  JGit core moves forward — not a blocker for this shadow-tree project
  specifically, but worth flagging as a standing platform risk in the plan's
  risk register, independent of and orthogonal to the shadow-tree design
  itself (the shadow tree doesn't change SSH transport at all).
- **jsch host-key handling**: `buildJschSessionFactory()`'s unconditional
  `StrictHostKeyChecking = "accept-new"` (no persisted `known_hosts`) means
  host keys are accepted and trusted for the session but not persisted
  across app restarts by any code visible in `AndroidGitRepository.kt`. This
  is a pre-existing gap, not something introduced by shadow-tree work, and
  does not block this project — noting it here only because the two SSH
  keywords in the task prompt (host key handling) surfaced it.
- **Network-on-main-thread**: not a risk in this codebase — every
  `GitRepository` method in `AndroidGitRepository.kt` is wrapped in
  `withContext(PlatformDispatcher.IO)` (confirmed at every method,
  e.g. lines 51, 56, 72, 104), so JGit's synchronous network calls (fetch/
  clone/push) never run on the main thread today. A shadow-tree design adds
  more I/O (the bidirectional sync steps) but the existing dispatcher
  discipline already covers it as long as new sync methods are called from
  within the same `withContext(IO)` scopes (or their own `IO`-dispatched
  calls) — no new coroutine-context work needed here beyond following the
  existing pattern.
- **JGit on Android app-private storage is a standard, unremarkable pattern**
  — `FileRepositoryBuilder().setGitDir(File(context.filesDir, ".git"))` +
  `readEnvironment().findGitDir().build()` is the idiomatic JGit setup
  regardless of platform; no Android-specific JGit working-tree gotchas
  were found in community sources beyond the two already known and handled
  in this codebase: (1) `coreLibraryDesugaring` for Java 8+ APIs JGit needs
  on older API levels (`build.gradle.kts:1394`), and (2) the SSH-transport
  module skew covered above. This confirms the requirements doc's framing
  that a shadow `java.io.File` tree is the correct, unforced-move way to
  make JGit work on Android — there is no alternate "SAF-native JGit" pattern
  documented anywhere in the community that this project should consider
  instead of what's already scoped in.

## Risks and recommendations summary

1. **Keep the git shadow tree architecturally separate from
   `ShadowFileCache`'s existing pages/journals index cache**, even if the
   class is extended or a sibling class is added under the same package —
   their invalidation policies conflict (`deleteAll()`-on-first-access vs.
   "never touch `.git/`"), and their scope differs (two subdirs vs. whole
   graph root). Recommend a new `GitShadowSync` (or similar) collaborator
   rather than growing `ShadowFileCache` to cover both jobs.
2. **`resolveForJGit()`'s silent fallthrough to a raw `saf://` string
   (line 428) should become a hard error once a shadow path is always
   derivable** — today it's a legitimate "no resolution possible" case; once
   this project ships, `saf://` reaching JGit unresolved should be
   unreachable and worth an assertion/loud failure instead of a warn-and-fall
   through, since a shadow path should always exist.
3. **No batch write/read primitive exists in `PlatformFileSystem` for SAF** —
   the shadow→SAF direction will be N sequential IPC round-trips per synced
   file; size this explicitly in the plan phase (how many files does a
   typical merge touch?) rather than assuming it's negligible, and consider
   whether it needs a progress UI for large reconciliations.
4. **Reuse, don't reinvent, the mtime+size staleness heuristic** from
   `ShadowFileCache.invalidateStale()` for both directions of sync — it's
   the closest thing to a "changes since last sync" primitive SAF supports
   without a provider-specific extension.
5. **`ConflictFile.wikiRelativePath` already solves most of the UI remapping
   problem** (Open Question 3) — verify in the plan phase that
   `wikiRelativePath` computation and display are exercised by a Robolectric
   test using a shadow `repoRoot`, rather than assuming the existing
   computed-from-`wikiSubdir` logic "just works" untested.
6. **`.git` directory storage footprint is not measured anywhere in this
   repo today** — the existing XLARGE/8 030-page fixtures measure markdown
   volume only. Plan phase needs either a real measurement against a
   user's actual `.git` history or an explicit simulated-commit-history
   benchmark; do not estimate a number without running one of these.
7. **`SafChangeDetector`'s watch scope (pages/journals only) does not cover
   `.git/` or other top-level graph files** — if requirements.md Open
   Question 2 wants to reuse it for detecting concurrent external writes
   during a git op, its scope needs broadening (or the concurrency design
   needs to rely on the same mtime+size comparison used at sync time instead
   of a live watcher for non-pages/journals paths).
