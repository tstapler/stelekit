# Architecture research: app-owned-storage-clone

Research pass for `project_plans/app-owned-storage-clone/requirements.md` (Phase 2, Complexity 4).
Builds directly on three prior architecture docs — cited by file:line, not re-derived:
- `project_plans/android-git-saf-shadow-worktree/research/architecture.md` ("Android doc")
- `project_plans/web-git-writeback/research/architecture.md` ("write-back doc")
- `project_plans/web-local-folder-livesync/research/architecture.md` ("livesync doc")

**Scope note that changes the starting conditions for Phase 3**: both prior docs' *proposals* are
now *shipped code*. `GitShadowWorktree`/`GitShadowFlushActor`/`GitWriteBackQueue` (Android) and
`HostDirectorySync` (Web, 1871 lines, 15 dedicated test files) are complete, working
implementations, not designs. This project extends working machinery, not machinery-on-paper — the
Feasibility Risks section of requirements.md should be read with that upgrade in mind.

Verification performed this session: full reads of `DomainError.kt`, `RepositoryFactory.kt`
(`GraphBackend`), `AddGraphDialog` (`App.kt:2084-2116`), `GitShadowWorktree.kt`,
`GitShadowFlushActor.kt`, `GitWriteBackQueue.kt` (via a forked sub-agent); grep-targeted reads of
`GraphManager.kt`, `HostDirectorySync.kt`, `GitSetupScreen.kt`'s `Step2RepoPath`, `PlatformFileSystem.kt`
(both platforms) (via a second forked sub-agent); `mcp__kibitzer__run_checks` against the five
files named in the requirements' hotspot question; `git log --oneline` churn counts for the same
five files.

---

## 1. Modeling `StorageLocation` as a domain concept

**No `StorageLocation`/`GraphLocation`/`PathKind` concept exists anywhere in `commonMain` today** —
confirmed by grep across `kmp/src/commonMain/kotlin/dev/stapler/stelekit/`. This is genuinely new
domain surface, not an extension of an existing type.

Two candidate precedents were evaluated:

- **`GraphBackend`** (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/RepositoryFactory.kt:30-36`)
  — a flat `enum class { SQLDELIGHT, DATASCRIPT, KUZU, NEO4J, IN_MEMORY }` selecting a *data-engine*
  strategy with no per-case data. **Not a good fit**: a storage location needs to carry different
  fields per case (a SAF tree URI string vs. an OPFS path vs. a real filesystem path vs. a
  retained `FileSystemDirectoryHandle` reference), which a flat enum cannot express without an
  external side-table.
- **`DomainError`** (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt:1-163`) —
  a `sealed interface DomainError { val message: String }` with nested `sealed interface` families
  (`DatabaseError`, `FileSystemError`, `GitError`, `ConflictError`, ...), each family's members are
  `data class`/`data object` carrying exactly the fields that case needs (e.g.
  `GitError.FileTooLarge(path, sizeBytes, maxBytes)`, `GitError.MergeConflict(conflictCount,
  conflictPaths)`). **This is the idiomatic pattern to model `StorageLocation` after** — a common
  contract (`val graphId: String` in place of `val message: String`), with platform-specific
  data-carrying leaves, mirroring exactly how `GitError` already has 20 leaf variants without
  forcing them through one flat shape.

Recommended shape (platform-common, in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/model/`):

```kotlin
sealed interface StorageLocation {
    val graphId: String

    /** Android: filesDir/graphs/$id/gitshadow (GitShadowWorktree) or plain filesDir markdown.
     *  Web: OPFS. The only location kind requiring zero user-granted permission on either platform. */
    data class AppOwned(override val graphId: String) : StorageLocation

    /** Android only. Backed by ACTION_OPEN_DOCUMENT_TREE; treeUri persists across app restarts
     *  but write access can be revoked by the OS/user at any time. */
    data class SafFolder(override val graphId: String, val treeUri: String) : StorageLocation

    /** Android only, requires MANAGE_EXTERNAL_STORAGE. See §4's Open-Question answer on whether
     *  this needs to be a *distinct* picker entry — recommendation: model it internally as its own
     *  case (so relocate/link logic can special-case a same-filesystem zero-copy move) but do NOT
     *  surface it as a separate row in the picker UI (see requirements Open Question 3). */
    data class DirectAccessFolder(override val graphId: String, val realPath: String) : StorageLocation

    /** Web only. Backed by a retained FileSystemDirectoryHandle (HostDirectorySync); permission
     *  can be revoked/lost across browser sessions (see HostAccessState in HostDirectorySync.kt). */
    data class HostFolder(override val graphId: String, val displayName: String) : StorageLocation
}
```

**Reusable UI shape for the picker itself**: no shared directory/location picker component exists
today (confirmed — `SectionPickerDialog.kt` and `PerFeatureProviderPicker.kt` are the only
`*Picker*` composables under `ui/`, both unrelated to filesystem location). The closest existing
precedent to build the new shared picker from is `WikiSubdirBrowserDialog`
(`GitSetupScreen.kt:863+`) — an in-app segment-by-segment folder browser dialog (`fileSystem`,
current path segments, `onSelect`) already used to browse subfolders of a resolved `repoRoot`. The
new unified picker should follow this dialog's shape (in-app browsing UI, not just a native-picker
launch button) with an "App storage" row pinned above the browse tree, per requirements.md's
explicit UI requirement that app-owned storage be a row *inside* the picker, not a separate control.

A second, orthogonal sealed type is needed for the move operation itself (relocate vs. link are
distinct commands per requirements.md's "Alternatives Considered" — "always-linked" was explicitly
rejected):

```kotlin
sealed interface StorageMoveOperation {
    val graphId: String
    val source: StorageLocation
    val destination: StorageLocation

    data class Relocate(
        override val graphId: String,
        override val source: StorageLocation,
        override val destination: StorageLocation,
        val deleteSourceAfterVerify: Boolean,
    ) : StorageMoveOperation

    data class Link(
        override val graphId: String,
        override val source: StorageLocation,
        override val destination: StorageLocation,
    ) : StorageMoveOperation
}
```

Following `DomainError`'s convention, a matching `DomainError.StorageError` sealed family should
be added for move failures (e.g. `VerificationFailed(path, reason)`, `SourceInFlight(reason)`,
`DestinationNotWritable`, `PartialCopyDetected`) rather than throwing or returning a bare
`Boolean` — this keeps the new subsystem consistent with the "Either/Arrow at repository
boundaries" convention the Android doc already confirmed applies (§1 of that doc).

---

## 2 & 3. Integration points and data-flow sequence

### 2.1 Android

- **`GitShadowWorktree` promotion to standalone storage is structurally blocked today, not just
  conceptually**: its constructor requires `safRoot: String` (`GitShadowWorktree.kt:48`), and
  `syncFromSafRoot()`/`syncFromSafRootLocked()` (`GitShadowWorktree.kt:192-256`) and `ensureFresh()`
  (`GitShadowWorktree.kt:365-372`) all treat a live SAF root as ground truth — there is no
  "standalone, no SAF backing" code path. The orphan sweep (`sweepOrphans()`,
  `GitShadowWorktree.kt:405-423`) is already SAF-independent (age-based `.last-used` marker only)
  and needs no change. **The natural seam is `AndroidGitRepository.shadowWorktreeFor()`**
  (`AndroidGitRepository.kt:68-76`), which today decides "does this repoRoot need a shadow
  worktree" via `pathResolver(repoRoot) != null` (line 69) — a third branch here
  ("app-owned storage: no SAF resolution needed, no sync-from-SAF ever runs, `GitWriteBackQueue`/
  `GitShadowFlushActor` never invoked") is additive to this one call site, not a rewrite of
  `AndroidGitRepository`'s ~14 JGit-calling methods (all already centralize path resolution through
  `resolveForJGit()`, per the Android doc's §3 table).
- **`GraphManager.moveGraphFilesAndCredentials()` (`GraphManager.kt:600-653`, called from
  `updateGraphPath()` at `:543-593`) is a *related but narrower* primitive, not a relocate**: it
  only re-keys **derived artifacts** (SQLite DB + WAL/SHM sidecars, telemetry DB, `CredentialStore`
  entries) from `oldId` → `newId` when a graph's path pointer changes — it never copies markdown
  (`pages/`/`journals/`) at all. This is the exact DB/markdown asymmetry requirements.md's Rabbit
  Holes section already flags (`requirements.md:78`), now confirmed in code. It **does** have a
  reusable safety pattern: verify-then-**rollback**, not verify-then-commit — it moves the DB
  first, and on partial WAL/SHM rename failure explicitly rolls back what already succeeded
  (`GraphManager.kt:610-616`). A relocate primitive should adopt this rollback shape for its own
  final repoint step, generalized to include markdown.
- **Neither `moveGraphFilesAndCredentials()` nor `updateGraphPath()` quiesces anything** — no
  `DatabaseWriteActor` drain, no `GitShadowFlushActor`/`GitWriteBackQueue` drain. Concretely risky:
  `updateGraphPath()` calls `switchGraph(newId)` (which tears down the old driver via
  `tearDownActiveGraphResources()`, `GraphManager.kt:670-701`) **after** the file rename already
  happened (`GraphManager.kt:575-580`), meaning the JDBC connection pool is still open on the old
  path *during* the rename today. A relocate must close/reopen the driver **before** touching
  files, reversing this ordering.
- **"Safe to move now" signal for the write-back queue**: `GitWriteBackQueue.isEmpty()`
  (`GitWriteBackQueue.kt:74`) is a ready-made drain check (synchronous file read, no polling
  primitive — gate a relocate with `while (!queue.isEmpty()) { flushActor.flush(); delay(...) }`).
  `GitWorktreeLocks.lockFor(shadowKey)` (`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/GitWorktreeLocks.kt:16-21`)
  is the existing per-shadow-key `Mutex` both `syncFromSafRoot` and `GitShadowFlushActor.flush()`
  already acquire — a relocate's copy step should acquire this same mutex, for free exclusion
  against concurrent SAF↔shadow sync/flush. **Gap**: this mutex does **not** guard JGit's own
  `commit`/`merge`/`push` execution — no in-flight-JGit-operation detector exists. The closest
  precedent is `EditLock.awaitIdle()` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/EditLock.kt:39-41`,
  already used by `GitSyncService.sync()` at `GitSyncService.kt:214`) — a `MutableStateFlow<Int>`
  busy-counter pattern that would need to be added (incremented around `GitSyncService.sync()`'s
  body) for a relocate to safely wait out a concurrent sync.
- **Sequence for a safe Android relocate** (markdown-only; DB never moves per the requirements
  Rabbit Hole, confirmed correct in code — `updateGraphPath` only re-keys the DB, never relocates
  its bytes):
  1. Acquire `GitWorktreeLocks.lockFor(shadowKey)` (blocks concurrent SAF↔shadow sync/flush).
  2. Await a new git-sync busy-counter hitting 0 (blocks concurrent JGit commit/merge/push) —
     **new** primitive, modeled on `EditLock`.
  3. Drain `GitWriteBackQueue` via `flushActor.flush()` until `isEmpty()`.
  4. Copy markdown tree source → destination (chunked, per this repo's bounded-read discipline;
     never a single unbounded directory listing for an 8,000+ page graph).
  5. Verify (see §5 for integrity-check strength) — content hash per file, not just count, per the
     phantom-conflict bug precedent (`e087959cb1`, cited in requirements.md's Rabbit Holes).
  6. Repoint: call `GraphManager.updateGraphPath()`-equivalent (extended to also move markdown,
     not just DB/credentials) — **close the driver before this step**, reversing today's ordering.
  7. `switchGraph()` to reopen at the new location.
  8. Release locks; optionally schedule source deletion (only after user confirms, per the Risk
     Control section's "never delete until verified + confirmed").

### 2.2 Web

- **`HostDirectorySync`'s connect flow is already reconfigurable, not fixed at creation time** —
  the livesync doc's §5 worry has been resolved in the shipped code:
  `connectHostDirectory(existingOpfsPath: String): HostAccessState` (`HostDirectorySync.kt:871`) is
  a user-invocable, mid-lifetime entry point that runs `runHostReconciliation` (`:1065`) *before*
  mutating any field, and rolls back to a clean `null` state on failure. `reconnectHostDirectory`
  (`:935`, session-resume) and `requestHostDirectoryAccess` (`:983`, explicit re-request) round out
  the three connect-family entry points. **This satisfies Open Question 5's premise directly**: no
  parallel implementation is needed for "link," it already exists in a reconfigurable form — the
  remaining gap is UI wiring (exposing it inside a picker instead of only via `FolderSyncSettings`'s
  single connect button, `FolderSyncSettings.kt`, 116 lines, gated on
  `hostAccessState == HostAccessState.NotApplicable`, i.e., today it is one-directional and only a
  settings action, never inside a location picker).
- **No disconnect/unlink command exists** — confirmed absent by grep. `close()` (`:171-177`) tears
  down the *entire instance* (graph-switch teardown), not a user-facing "detach from folder, stay
  on OPFS" action; a doc comment at `:1717-1718` explicitly admits `hostDirHandle` is "deliberately
  left set... no field-clearing 'disconnect' flow exists yet." Notably, a **field-by-field**
  disconnect (`disconnectForGraphSwitch()`) did exist once and was deliberately retired in favor of
  constructing a fresh instance per graph switch (`HostDirectorySync.kt:160`,
  `PlatformFileSystem.kt:260`, `HostDirectorySyncGraphSwitchTest.kt:32` — PR #293 round 2, cited as
  fixing shared-mutable-state bugs). **A new user-invoked unlink command should not resurrect that
  field-by-field pattern** — it solves a different problem (detach one folder, keep the instance
  and its OPFS-side state alive) and should be designed as its own explicit state transition, not
  as a partial reset of the retired shape. A relocate's "repoint to app-owned storage" step and a
  general "unlink" UI action both need this **new** command.
- **No copy/relocate primitive exists** — confirmed absent by grep across `HostDirectorySync.kt`,
  `GraphManager.kt`, and wasmJs `PlatformFileSystem.kt`. `runHostReconciliation`'s per-file
  walk-and-classify loop (`:1065+`) is architecturally the closest shape (walk one tree, compare
  each file, decide an action) but is built for *bidirectional reconciliation with conflict
  surfacing*, not *unidirectional bulk copy-then-verify* — a relocate needs new code: a tree copy
  (host↔OPFS, either direction, no conflict logic since the destination is fresh) plus a real
  content-hash verify (the reconciliation pass's `matchesBaseline` mtime/size shortcut,
  `:1085` area, is a cheap-path *skip*, not the integrity check requirements.md's rabbit hole asks
  for — reusing it as relocate's verify step would under-detect corruption).
- **`Step2RepoPath` (`GitSetupScreen.kt:742-853`) confirmed to have zero OPFS/app-storage option**:
  one `OutlinedTextField` for `repoRoot` with an optional `onBrowseRepoRoot` icon button wired to
  `pickDirectoryAsync()`, a second field for `wikiSubdir` with its own browse dialog. No app-storage
  row, shortcut, or branch anywhere in the composable or its caller
  (`GitSetupScreen.kt:326-339` passes callbacks straight through with no OPFS-specific case).
- **Sequence for a safe Web relocate (host → app-owned or app-owned → host)**:
  1. Acquire a **new**, distinctly-named Web Lock (see §5 — must not reuse `GitWriteLock`'s
     remote-URL-derived name nor `HostDirectorySync`'s existing per-write/per-poll-tick lock names,
     to avoid accidental cross-feature blocking or, worse, accidental non-exclusion via mismatched
     names).
  2. Pause the steady-state host poll loop for this graph (if currently linked) — reuse the
     existing pause/resume idiom already present for `visibilitychange`-driven poll suspension.
  3. Walk and copy (host→OPFS via `readOpfsFileAsBytes`/`opfsWriteFile`-equivalent helpers already
     in `HostDirectorySync.kt`, reused rather than reinvented, per fork B's finding that these
     primitives already exist for the reconciliation pass).
  4. Verify via content hash (not the reconciliation shortcut's mtime/size compare).
  5. Repoint: update the graph's registered `StorageLocation` (persisted alongside `graphId` per
     the existing `graphId`-keyed IndexedDB convention, `GraphManager.graphIdFromPath()`).
  6. If relocating **away from** a linked host folder, run the new "unlink" command (§2.2) to clear
     `hostDirHandle`/IndexedDB entry — otherwise the old link's poll loop would resurrect stale
     host-side state on next reconnect attempt.
  7. Optionally delete source after user confirmation.

### 2.3 `GraphManager` lifecycle — does a move need `switchGraph()`?

Yes, on both platforms, for the same reason: the DB driver / repository set is opened against a
specific canonical path (`GraphManager.graphIdFromPath()`, `GraphManager.kt:329-330` — `GraphId` is
`sha256(canonicalPath)`-derived per the Android doc's §2), so a relocate that changes the canonical
path **necessarily** changes `graphId`, which **necessarily** requires the existing
`moveGraphFilesAndCredentials`/`updateGraphPath`/`switchGraph` re-keying machinery to run — this is
not optional plumbing a relocate can bypass by "just moving files live." The gap is exclusively in
sequencing (quiesce before, not after, per §2.1) and in extending `moveGraphFilesAndCredentials` to
also move markdown, not just DB/credentials.

---

## 4. Hotspot disposition (per file)

Not run: the `code-hotspot-analysis` skill itself or a repo-wide `quality:architecture-review` (out
of scope for a single research pass at Complexity 4 appetite). Ran instead:
`mcp__kibitzer__run_checks` (per-file complexity/lint) against the five files requirements.md names,
plus `git log --oneline -- <file> | wc -l` for churn (all-time commit count touching each file).

| File | Lines | Commits (churn) | kibitzer findings | Disposition |
|---|---|---|---|---|
| `GitSetupScreen.kt` | 1493 | 14 | Outer `GitSetupScreen` composable at line 102 spans **521 lines**, nests **10 levels deep**, takes **17 parameters** (flag-argument smells on 5+ booleans). `Step2RepoPath` itself (742-853, ~111 lines) is comparatively well-scoped. | **Isolate via seam.** The 521-line wizard-shell function is a pre-existing God-function, but it is not this project's to fix (scope creep against Large-appetite budget). Add the new unified picker as its own new composable, invoked *from* `Step2RepoPath`'s existing browse-button callback, without touching the outer 521-line function. Flag the outer function as a separate future refactor candidate, out of this project's scope. |
| `HostDirectorySync.kt` | 1871 | 20 | File-size advisory; two functions at 1065/1091 span 193/127 lines at 7-level nesting; several 40-75 line functions; comment-quality advisories (over-commented, stale "this fix" references). | **Extend as-is.** Per the forked sub-agent's read: responsibilities (connect/reconnect orchestration, reconciliation, polling, write-through queue) are separated by cohesive suspend-function boundaries sharing only `hostDirHandle`/`hostGraphOpfsPath` state and a few read/write helpers — no evidence of tangled cross-concern coupling (e.g. reconciliation reaching into write-through's private queue). New relocate/unlink methods slot in next to the existing connect/reconnect/request-access trio using the same patterns. Flag a future file-split (reconciliation / write-through / connect-orchestration into separate files) as tech debt, not a blocker for this project. |
| `PlatformFileSystem.kt` (Android) | 1170 | 27 | File-size advisory; a 6-line block duplicated 5× (lines 1043, 1092, 1105, 1121, 1135). | **Extend as-is.** The existing `isDirectAccess()`/`resolveToRealPath()` capability-check-plus-resolver pattern (private check inlined per-method, per the Android doc's §6) is exactly the established idiom for the kind of location-branching this project needs — no new abstraction layer required. The 5× duplicate block is adjacent tech debt (candidate for extraction into a shared helper) but does not block adding a `StorageLocation`-aware branch. |
| `GraphManager.kt` | 1037 | **40 (highest of all five)** | File-size advisory; six functions over 40 lines (up to 141 lines at line 670, `switchGraph`'s region); one 5-level-deep nesting block. | **Isolate via seam.** This file is both large *and* the highest-churn file surveyed — the single riskiest place to add new inline logic for a Complexity-4 project. Recommend a **new** dedicated coordinator (e.g. `GraphRelocationCoordinator`, one per platform or commonMain-orchestrated) that calls into `GraphManager`'s existing public surface (`updateGraphPath`, `switchGraph`) rather than growing `moveGraphFilesAndCredentials`/`updateGraphPath` in place. This keeps the highest-churn file's own diff small and testable in isolation. |
| `GitShadowWorktree.kt` | 425 | 7 (lowest) | One 53-line function with a `force` flag-argument smell (line 200). Otherwise clean. | **Extend as-is.** Newest, smallest, cleanest, and lowest-churn file surveyed — no hotspot signal. The `safRoot`-required constructor is a real API constraint (§2.1) but not a complexity/SOLID problem; it's a narrow, additive change (a new construction path), not a refactor. |

**Overall Phase 3 signal**: `GraphManager.kt` (highest churn) and `GitSetupScreen.kt` (largest
single God-function) are the two files where new logic should be added *around* rather than *into*
— via a new coordinator class and a new composable respectively — while `HostDirectorySync.kt` and
Android's `PlatformFileSystem.kt`, despite their size, show no internal coupling that would force a
refactor-first approach before extending them.

---

## 5. Migration-specific failure modes

Distinct from ordinary feature-development risk, per the Complexity-4 handling requirement:

1. **Partial copy / crash mid-move (both platforms).** No existing relocate primitive to model
   directly, but the codebase's established crash-safety idiom — copy-to-temp-then-atomic-rename —
   is already used by `GitWriteBackQueue.dequeue()` for its own queue file
   (`GitWriteBackQueue.kt:43-55`, whose comment explicitly reasons about "process death mid-write
   leaves either the untouched old... or the fully-written new one") and should be the model for a
   relocate's final repoint step, not a novel design.
2. **Driver-open-during-copy (Android).** Renaming/moving the SQLite DB file while `DriverFactory`'s
   JDBC pool (8 pre-created connections, per this repo's dispatcher-matrix docs) holds open file
   descriptors is a real corruption/dangling-fd risk. `updateGraphPath()` currently reopens the
   driver *after* the file move (`GraphManager.kt:575-580`) — a relocate must invert this ordering.
3. **JGit open file handles on `.git` during copy (Android).** JGit holds `index.lock`/pack-file
   mmaps for the duration of any operation (this is precisely why `removeStaleLockFile()` exists,
   `AndroidGitRepository.kt:392-410`, per the Android doc). `GitWorktreeLocks` guards shadow-content
   sync/flush but not JGit's own command execution — copying `.git` mid-JGit-operation risks a
   torn/inconsistent object store. Needs the new busy-counter from §2.1, not just the existing
   mutex.
4. **Concurrent WorkManager background fetch racing a foreground relocate (Android).** Confirmed
   safe *today* for the existing design — `WorkManagerSyncScheduler.kt:100-135`'s comment notes
   `fetch()` only updates refs/objects, never the working tree, so no shadow write-back races a
   background fetch. **This safety does not extend to relocate**: if relocate copies the `.git`
   object/refs directory while a background fetch is mid-write to those same pack/ref files, there
   is no existing guard. A relocate should pause/cancel the graph's periodic WorkManager sync job
   before copying `.git`.
5. **Concurrent access from another tab (Web) / another process (Android n/a, single-process).**
   Web's existing lock namespacing (`GitWriteLock` for git push, `HostDirectorySync`'s own
   per-write/per-poll-tick locks) is narrowly scoped by design (per the livesync doc's §3.2) — a
   relocate needs its **own** lock namespace, distinct from both, or it either fails to exclude a
   concurrent link operation (wrong namespace) or over-blocks unrelated git pushes (borrowed
   namespace). Get this wrong and either data races or artificial contention result.
6. **File System Access API permission revoked mid-copy (Web).** A host directory handle's
   permission can be revoked by the browser at any point (this is the entire reason
   `HostAccessState` has a `Disconnected` state, per fork B). A relocate copying host→OPFS or
   OPFS→host must detect a mid-copy `NotFoundError`/permission failure and treat it as **the same
   "partial copy" case as #1** — leaving the destination in a known-incomplete state that the
   verify step correctly rejects, not a state that looks "mostly done" and gets accidentally
   confirmed by the user.
7. **Weak verification under-detects corruption.** Both platforms have an existing "cheap"
   comparison shortcut (Android: SAF batch-mtime cursors; Web: `runHostReconciliation`'s
   `matchesBaseline` mtime/size check) that exists for *performance* in steady-state sync, not for
   *integrity* in a one-time move. Reusing either as relocate's verify-before-delete step would
   silently under-detect the exact class of encoding/path bug this codebase has already shipped
   once (`e087959cb1`, the underscore/encoded-page-title phantom-conflict fix cited in
   requirements.md's Rabbit Holes) — relocate's verify step needs a real content hash per file
   (and, for a git-cloned graph, a git object/pack count or `git fsck`-equivalent check — full
   `fsck` cost should be weighed against appetite in Phase 3; a cheaper proxy is comparing JGit's
   own ref/object count before and after copy).
8. **`GitShadowWorktree.sweepOrphans()` becomes a data-loss bug the instant the shadow tree
   becomes primary storage — this is the single highest-severity finding in this section.**
   `GitShadowWorktree.kt:405-423` deletes any shadow directory whose `LAST_USED_FILE_NAME` marker
   is older than `maxAgeMillis` (default 60 days, `DEFAULT_MAX_AGE_MILLIS`), on the explicit
   documented rationale (`:395-399`) that this is safe *because* the shadow tree is always
   recoverable by re-syncing from the SAF folder of record. That rationale is exactly what this
   project invalidates for any graph using `AppOwned` storage: once the shadow tree **is** the only
   copy, `sweepOrphans()` silently deletes a user's sole data copy after 60 days of the app not
   being opened (or more precisely, of the marker not being touched — verify the marker's actual
   update triggers before relying on "opened" as a proxy). **This must be gated on storage mode
   before `AppOwned` promotion ships**: `sweepOrphans()` needs a way to know "is this shadow dir a
   cache (safe to sweep) or primary storage (never sweep)" — e.g. consulting the persisted
   `StorageLocation` for that `graphId` before deleting, or a sentinel file written only for
   `AppOwned` graphs that the sweep unconditionally skips. Flag as a blocking task in `plan.md`, not
   a follow-on hardening item — an unfixed sweep silently deletes exactly the users this project
   is meant to serve.
9. **No general "move in progress" primitive exists.** Today's exclusion mechanisms are
   per-mechanism and narrow: `GitWorktreeLocks` (Android shadow content), `EditLock` (git sync
   only), `GitWriteLock`/`HostDirectorySync`'s locks (Web, narrowly scoped by design). A relocate
   needs to exclude *all* of: block editing, background indexing, `GraphFileWatcher` polling, and
   git sync, simultaneously, for the duration of the move. Acquiring N separate existing locks in
   sequence risks ordering/deadlock bugs across two platforms' independently-evolved lock sets.
   **Recommendation for Phase 3**: introduce one new coarse-grained per-graph "move in progress"
   flag/state (checked by the existing mechanisms as an early-exit condition) rather than trying to
   acquire every existing lock from outside — cheaper to reason about and matches this codebase's
   existing preference for additive capability checks over cross-cutting lock composition.

---

## 6. EventStorming table

| Domain Event | Policy (whenever X, then…) | Command | Actor / System |
|---|---|---|---|
| `LocationPickerOpened` | User opens new-graph dialog, clone wizard Step 2, or "move storage" action | `ShowUnifiedLocationPicker` | User → new shared picker component |
| `AppOwnedStorageSelected` | User picks the pinned "App storage" row | `CreateOrTargetAppOwnedLocation` | User → picker |
| `FolderSelected` | User picks "Browse…" (SAF / real folder / File System Access API) | `ShowNativeFolderPicker` | User → platform (SAF `ACTION_OPEN_DOCUMENT_TREE` / `showDirectoryPicker()`) |
| `RelocateRequested` | User invokes "move storage location" and chooses Relocate | `ValidateMoveIsPossible(source, destination)` | User → new move UI |
| `LinkRequested` | User invokes "move storage location" and chooses Link | `ValidateMoveIsPossible(source, destination)` | User → new move UI |
| `MoveValidated` | Whenever `ValidateMoveIsPossible` passes (destination writable, not already linked, etc.) | `ShowMoveConfirmationDialog(exact source, exact destination)` | New move UI (per Risk Control's "explicit confirmation naming exact source/destination") |
| `MoveConfirmedByUser` | User confirms in the dialog | `BeginQuiesce(graphId)` | User → `GraphRelocationCoordinator` (new, §4) |
| `GraphQuiesced` | Whenever all of: `GitWriteBackQueue.isEmpty()`, git-sync busy-counter == 0, "move in progress" flag set (blocks editing/indexing/watcher) | `BeginCopy(source, destination)` | `GraphRelocationCoordinator` → `GitWorktreeLocks` / `EditLock`-style counter / new move-in-progress flag |
| `QuiesceTimedOut` | Whenever quiesce doesn't settle within a bounded wait (e.g. stuck flush) | `AbortMove(reason)` | `GraphRelocationCoordinator` → `DomainError.StorageError` |
| `CopyCompleted` | After `BeginCopy` finishes writing all files to destination | `RunVerification(source, destination)` | `GraphRelocationCoordinator` |
| `VerificationPassed` | Whenever content-hash comparison (and, for git graphs, object-count comparison) matches | `RepointGraphLocation(graphId, destination)` | `GraphRelocationCoordinator` → `GraphManager.updateGraphPath`-equivalent |
| `VerificationFailed` | Whenever any file's hash mismatches, or a partial-copy/permission-revoked signal was observed mid-copy (§5.6) | `AbortMove(reason)` + `LeaveSourceIntact` | `GraphRelocationCoordinator` → `DomainError.StorageError.VerificationFailed` → UI |
| `LocationRepointed` | After `RepointGraphLocation` succeeds | `SwitchGraph(newId)` | `GraphManager.switchGraph()` (existing) |
| `GraphReopenedAtNewLocation` | After `SwitchGraph` completes | `ReleaseQuiesceLocks` + `ShowCleanupPrompt` | `GraphRelocationCoordinator` → UI |
| `CleanupConfirmedByUser` (relocate only) | User explicitly confirms "delete old copy" (or grace-period expiry, per Phase 3 UX decision) | `DeleteSourceLocation` | User → `GraphRelocationCoordinator` |
| `CleanupDeclinedByUser` | User declines / undo-window still open | `KeepSourceIntact` (no-op, terminal for now) | `GraphRelocationCoordinator` |
| `LinkEstablished` (link only) | After `VerificationPassed` for a Link operation, instead of a one-time repoint | `AttachContinuousMirror(source, destination)` | `GraphRelocationCoordinator` → `HostDirectorySync.connectHostDirectory` (Web, existing) / new Android link-mode wiring |
| `LinkBrokenPermissionRevoked` | Whenever the linked folder's permission is lost (Web: `HostAccessState.Disconnected`; Android: SAF grant revoked) | `SurfaceLinkBrokenBanner` + `PausePolling` | `HostDirectorySync` (existing `Disconnected` state) / Android SAF-revoke handler (existing) → UI |
| `LinkUnlinkRequested` | User explicitly detaches a link (stay on current location, drop the mirror) | `UnlinkStorageLocation(graphId)` | User → **new** unlink command (does not exist today, per §2.2) |
| `MoveOutcomeLogged` | Whenever any terminal state above is reached (success, verification failure, abort, link broken) | `LogMoveOutcome(graphId, source, destination, outcome)` | `GraphRelocationCoordinator` → existing app log/telemetry span mechanism (per Observability Requirements) |

---

## Summary of concrete recommendations for `plan.md`

1. Model `StorageLocation` as a `sealed interface` with `AppOwned`/`SafFolder`/`DirectAccessFolder`/
   `HostFolder` leaves, following `DomainError`'s nested-sealed-interface convention, not
   `GraphBackend`'s flat-enum convention (§1).
2. Add a `DomainError.StorageError` family for move failures, consistent with the Either/Arrow
   convention already enforced at repository boundaries.
3. Promote `GitShadowWorktree` via a new constructor/branch at `AndroidGitRepository.shadowWorktreeFor()`
   (`AndroidGitRepository.kt:68-76`), not a rewrite of the class or of `AndroidGitRepository`'s
   JGit-calling methods (§2.1).
4. Extend `moveGraphFilesAndCredentials`'s existing rollback-on-partial-failure pattern to also
   cover markdown copy, and invert its current "reopen driver after move" ordering to "quiesce and
   close driver before move" (§2.1, §5.2).
5. Add one new busy-counter primitive (modeled on `EditLock`) to detect "JGit operation in flight,"
   since `GitWorktreeLocks` only guards shadow-content sync/flush, not JGit command execution
   (§2.1, §5.3).
6. Web's `connectHostDirectory`/reconciliation machinery is already reconfigurable and needs no
   redesign — the real Web gaps are a **new** unlink/disconnect command and **new** copy+verify
   code for relocate, neither of which exist today (§2.2).
7. Do not build relocate's verify step on either platform's existing "cheap" steady-state
   comparison shortcut (SAF batch-mtime cursors; `matchesBaseline` mtime/size) — use real content
   hashing, given this codebase's own history with the underscore/encoding phantom-conflict bug
   (§5.7).
8. Add one new coarse per-graph "move in progress" flag rather than composing every existing
   platform-specific lock from outside (§5.8).
9. Add new logic as new coordinator classes/composables that call into `GraphManager.kt` and
   `GitSetupScreen.kt`'s existing public surface, rather than growing either file in place — both
   are hotspots by churn/size respectively (§4).
10. Open Question resolutions this research can close:
    - **Integrity-check strength**: content hash per file, plus a git object/ref-count comparison
      for git-cloned graphs (cheaper than full `fsck`; weigh full `fsck` against appetite in
      Phase 3) — not file-count alone (§5.7).
    - **`MANAGE_EXTERNAL_STORAGE` picker entry**: model it internally as a distinct `StorageLocation`
      case (`DirectAccessFolder`) so relocate/link logic can special-case a same-filesystem
      zero-copy move, but do **not** surface it as a separate row in the picker UI — treat it as an
      invisible acceleration detail under "folder," consistent with `isDirectAccess()`'s existing
      role as an invisible capability check (§1).
    - **Draining `GitShadowFlushActor` mid-flight**: yes, required — reuse `GitWriteBackQueue.isEmpty()`
      plus the new busy-counter from recommendation 5 (§2.1).
    - **Generalizing `FolderSyncSettings`/`HostDirectorySync`'s link mechanism**: no parallel
      implementation needed — `connectHostDirectory` is already a reconfigurable, mid-lifetime
      entry point; only the disconnect/unlink command and picker-UI wiring are missing (§2.2, §6).
