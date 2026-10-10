# Research: Features & Feature Landscape — web-git-writeback

Agent 2 (Features). Sources: `docs/adr/ADR-013-wasm-rest-api-opfs-section-sync.md`,
`docs/adr/ADR-015-wasm-git-data-api-write-back.md`, `project_plans/web-git-writeback/requirements.md`,
`docs/bugs/open/BUG-005-wasm-git-manager-credential-store-stubs.md`, and direct code reading of the
desktop/Android git stack and the wasmJs platform layer.

## 0. Headline finding — the integration target named in requirements.md/ADR-015 is not what the UI actually consumes

**This changes the shape of the planning phase and needs to be resolved as a planning decision, not
discovered mid-implementation.**

`requirements.md` and `ADR-015` both frame this project as: implement `WasmGitWriteService`, wire it
into `JsGitManager` (`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/GitManager.kt`), which
implements the `commonMain` `GitManager` interface (`commit`, `push`, `pull`, `status`, `isDirty`).

That `GitManager`/`GitManagerFactory`/`JsGitManager` surface is **dead code**. It is defined
(`kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/GitManager.kt`) and given trivial/no-op
actuals on every platform (`JvmGitManager`, `AndroidGitManager`, `JsGitManager`), but grepping the
entire `kmp/src` tree for `GitManagerFactory.create` or any UI/viewmodel/repository consumer of
`GitManager` turns up **zero call sites outside the four actual-declaration files themselves**. No
screen, no `StelekitViewModel`, no `GraphManager` path ever instantiates or calls it.

The real, currently-shipping desktop/Android git sync stack is a completely separate, richer object
graph that the UI actually depends on:

- **`GitRepository`** (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitRepository.kt`) — the
  `expect`/`actual` interface with real actuals on `jvmMain` (`JvmGitRepository`, CLI/`ProcessBuilder`
  git), `androidMain` (`AndroidGitRepository`), `iosMain` (stub). **No `wasmJsMain` actual exists.**
  Its shape is git-working-tree/CLI-flavored: `isGitRepo`, `init`, `clone`, `fetch`, `status`,
  `stageSubdir`, `commit`, `merge` (returns `MergeResult` with line-level `ConflictFile`/`ConflictHunk`
  data), `push`, `log`, `abortMerge`, `checkoutFile(path, side)`, `markResolved`, `hasDetachedHead`,
  `removeStaleLockFile`.
- **`GitSyncService`** (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`) —
  orchestrates the full cycle (network check → commit local → fetch → merge → conflict handling →
  reload → push) on top of `GitRepository`. Exposes `syncState: StateFlow<SyncState>` and the methods
  the UI actually calls: `sync()`, `fetchOnly()`, `commitLocalChanges()`, `resolveConflict()` (hunk-level,
  unused by any current screen), **`resolveConflictBySide()`** (whole-file LOCAL/REMOTE — this is what
  the shipping UI uses), `applyJournalMerge()`, `abortActiveMerge()`.
- **UI consumers of `GitSyncService`/`SyncState`** (not `GitManager`): `StelekitViewModel.kt`,
  `App.kt`, `GraphDialogLayer.kt`, `GitSetupScreen.kt`, `SyncStatusBadge.kt`.

**Proof this is disabled on web today, not just theoretically parallel:** `App.kt` takes
`gitRepository: GitRepository? = null` as a parameter and explicitly comments "Requires a
platform-specific `GitRepository`; no-op when none is provided" (`App.kt:556-573`) — if
`gitRepository == null`, `gitConfigRepository` and `gitSyncService` are both `null`, and
`registerGitSyncService(null)` is called. `Main.kt` on `jvmMain`
(`kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt:127`) constructs
`JvmGitRepository()` and passes it in; `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`
**never passes a `gitRepository` argument at all** — it silently defaults to `null`. So on web today,
`GitSyncService` is never instantiated, and `SyncStatusBadge`/`GitSetupScreen`/`ConflictResolutionScreen`
are wired up in the UI tree but structurally inert (`SyncStatusBadge` renders "Set up sync" and nothing
past it can ever fire). This is a *second*, deeper form of the same BUG-005 gap that `JsGitManager`
addresses — and it's the one the real UI actually depends on.

**Recommendation for the planning phase (not decided here — flagging as the single most important open
question)**: the way to satisfy ADR-015's stated goal ("no new UI-layer branching... conflict resolution
screen, sync status badge, and commit flow are shared Compose UI") is almost certainly to write a
**`wasmJsMain` actual of `GitRepository`** (Git-Data-API/GitLab-commits-API-backed, no local git
checkout — an in-memory/OPFS-backed model standing in for git's index) and wire it into `Main.kt` the
same way `JvmGitRepository`/`AndroidGitRepository` are wired, so `GitSyncService` (unmodified) runs on
web. This reaches every real UI surface for free. Implementing the separate `WasmGitWriteService` →
`JsGitManager` path as literally described would produce a working REST client that **no screen calls**,
closing BUG-005's letter but not its actual symptom (web users still see "Set up sync" do nothing
meaningful for real sync UX, since the UI was never built against `GitManager`). Planning should decide
explicitly: (a) `GitRepository` wasmJs actual (recommended — reaches real UI, but must design how
CLI-shaped methods like `isGitRepo`/`init`/`clone`/`log`/`hasDetachedHead`/`removeStaleLockFile` map onto
a checkout-less REST model — several are plausibly harmless no-ops/false, `clone` overlaps with the
already-implemented `WasmSectionSyncService`), or (b) implement both `GitManager` (per ADR-015's letter)
*and* retrofit `App.kt`/`Main.kt` to also wire a `GitSyncService` for web (larger footprint, arguably
redundant object graphs), or (c) some hybrid. Either way, `JsGitManager`'s current dead-code status
should be called out explicitly in the plan so it isn't half-fixed a second time.

## 1. Desktop/Android conflict detection and resolution — exact shape

`GitSyncService.sync()` step 7: after `fetch`, if `fetchResult.hasRemoteChanges`, calls
`gitRepository.merge(config)` → `MergeResult(hasConflicts, conflicts: List<ConflictFile>, changedFiles)`.//
If `hasConflicts`: single-journal-file conflicts get a shot at algorithmic auto-merge via
`JournalMergeService` (out of scope here — journal-specific); everything else sets
`_syncState.value = SyncState.ConflictPending(mergeResult.conflicts)` and returns
`DomainError.GitError.MergeConflict(conflictCount, conflictPaths)` as the error.

`ConflictFile` (`git/model/ConflictModels.kt`):
```kotlin
data class ConflictFile(val filePath: String, val wikiRelativePath: String, val hunks: List<ConflictHunk>)
data class ConflictHunk(val id: String, val localLines: List<String>, val remoteLines: List<String>, ...)
```

**`ConflictResolutionScreen.kt` only consumes `filePath`/`wikiRelativePath`** for display and drives a
per-file `MergeSide` (`LOCAL`/`REMOTE`) picker ("Keep mine" / "Use remote" chips) — it does **not** render
`hunks` at all; that field exists for a different, unused-by-any-screen consumer
(`resolveConflict(resolution: ConflictResolution)`, hunk-level, `ConflictResolver.applyResolutions`).
The screen's "Finish Merge" button calls `onResolve(Map<filePath, MergeSide>)`, which desktop wires to
`GitSyncService.resolveConflictBySide(graphId, fileResolutions)` — this only needs, per conflicting file,
`gitRepository.checkoutFile(config, filePath, side)` (replace working-tree content with local or remote
version) + `markResolved` + a final commit + `graphLoader.reloadFiles(...)`.

**Important correction to the requirements/ADR text**: `DomainError.ConflictError.DiskConflict`
(`error/DomainError.kt:33`) is a real type but **is not what the git conflict flow uses or produces**.
Grepping `GitSyncService.kt`/`ConflictResolutionScreen.kt` shows the git merge-conflict path uses
`DomainError.GitError.MergeConflict` + `SyncState.ConflictPending(List<ConflictFile>)`, an entirely
separate error family from `ConflictError.DiskConflict` (which is the *page-level* concurrent-edit/disk
conflict type used elsewhere, e.g. `GraphLoader.externalFileChanges` → "DiskConflict" resolved in a
different UI flow per `CLAUDE.md`'s "External changes" data-flow note). Requirements.md's success metric
("surfaces the existing `DomainError.ConflictError.DiskConflict` / `ConflictResolutionScreen` flow")
conflates two different conflict systems that share only the word "conflict." Planning must use the
correct target: `DomainError.GitError.MergeConflict` + `SyncState.ConflictPending(List<ConflictFile>)` +
`resolveConflictBySide`.

**Is it achievable given what GitHub/GitLab return?** Yes, and arguably *more* achievable than the literal
ADR text suggests, because the real UI need is shallower than "hunk-level 3-way merge": it's a flat list
of conflicting file paths plus a binary local-or-remote choice per file. That maps cleanly onto ADR-015's
own stated conflict granularity ("file-path overlap, not content-level"):
- Web write-back's conflict detection (remote ref moved past local base + overlapping dirty paths) can
  produce `ConflictFile(filePath, wikiRelativePath, hunks = emptyList())` for each overlapping path —
  `hunks` can legitimately stay empty since the only consuming screen never reads it.
  - Do not use `resolveConflict()`/`ConflictResolution` (hunk-level) as the web target — it has no UI
    consumer today and would require synthesizing fake hunks for no benefit.
- Resolving "REMOTE" for a file means: write the remote blob's content (already fetched during
  conflict/compare) into OPFS for that path. Resolving "LOCAL" is a no-op (OPFS already has it). This is
  a plausible, scoped `checkoutFile`-equivalent for a `wasmJs` `GitRepository` actual.

## 2. Edge cases beyond ADR-015's enumeration

**a. File deleted locally, edited remotely (or vice versa).** Not covered by ADR-015's "file-path overlap"
conflict rule as written — a delete and an edit on the same path *do* overlap at the path level, so the
existing overlapping-file branch should naturally catch it (surface as a conflict), but the current
`ConflictFile`/`MergeSide` model has no representation for "this side is absent." `MergeSide.LOCAL` on a
locally-deleted file means "keep it deleted"; `MergeSide.REMOTE` means "resurrect the remote content."
Desktop's real git `checkoutFile` on a deleted-vs-modified pair already has defined git semantics (deleted
file + `git checkout --ours <path>` is a legitimate git operation); the web `GitRepository` actual should
mirror this deliberately rather than fail unexpectedly. Should be an explicit case in planning/validation,
not discovered when the first tester deletes a page while someone else edits it upstream.

**b. Empty commit (dirty set became empty by the time sync runs).** `GitSyncService.sync()` step 5 already
handles this on desktop: `statusResult.value.hasLocalChanges` is checked before staging/committing at all
— if false, `localCommitsMade` stays 0 and the function proceeds straight to fetch/push with no commit
created. A wasmJs `GitRepository.status()`/dirty-set-derived equivalent must replicate this check
(dirty set is empty → skip the whole blob/tree/commit/ref sequence, go straight to a no-op "nothing to
push" success) rather than creating a vacuous commit. Concretely: a user reverts their own edit back to
the last-synced content, `PlatformFileSystem`'s dirty tracking still marks the path dirty (it doesn't
diff against the synced baseline), so the write-back layer must check "is the dirty set non-empty" *and*
arguably "does content actually differ from `base-sha`'s version," or it will create spurious empty-looking
commits. ADR-015 doesn't mention this at all — worth a line in the plan.

**c. Very large single file — GitHub blob size limit is real and relevant.**
GitHub's `POST /repos/{owner}/{repo}/git/blobs` is documented as supporting **blobs up to 100 MB**; GitHub's
general large-file policy separately warns at **50 MB** and hard-blocks any file **≥100 MB** anywhere in a
repo. Base64 encoding (required for the blob-create payload) inflates size ~33%, so a **~75 MB raw file is
the practical ceiling** before the base64 payload itself would exceed the 100 MB blob limit — this matters
specifically for encrypted `.md.stek` blobs, which per `requirements.md`'s Out-of-Scope note are explicitly
in scope for git write-back and can be arbitrary binary size. GitLab's commits API (`POST
/api/v4/projects/:id/repository/commits`, base64 `actions`) is governed by a **request-body cap defaulting
to 300 MB** (`GITLAB_COMMITS_MAX_REQUEST_SIZE_BYTES`, self-managed default; GitLab.com may enforce a lower
effective limit via its own infra), with an additional rate limit of 3 requests/30s for payloads over 20 MB
— a different failure mode (429/413) than GitHub's per-blob cap. Planning should define an explicit
"file exceeds host limit" `DomainError` and user-facing message; ADR-015 does not currently mention any
size ceiling.

**d. Rename detection — delete+create is provably fine, no client action needed.** Traced
`GraphWriter.renamePage`/`movePageToSection`: both call `fileSystem.renameFile(oldPath, newPath)` first,
but `FileSystem.renameFile` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt:39`)
defaults to `= false` and **`PlatformFileSystem.wasmJs.kt` does not override it** — so on web the default
always applies and both rename paths *always* fall into the copy+delete fallback
(`writeFile(newPath)`/`writeFileBytes(newPath)` then `deleteFile(oldPath)`). Since ADR-015's dirty-tracking
hook is specified on exactly `writeFile`/`writeFileBytes`/`deleteFile`, a page rename or section move on
web will unconditionally be captured as a delete+create pair in the dirty set with zero special-casing
required — confirms ADR-015's stated assumption is correct for this codebase specifically (it would *not*
be true if `renameFile` were ever implemented for wasmJs OPFS as a true move — flag that as a future
regression risk if someone "optimizes" `renameFile` for wasmJs later without also updating dirty tracking).

**e. Network failure / offline mid-sync.** Desktop's `GitSyncService.sync()` checks
`networkMonitor.isOnline` once at the very start (`DomainError.GitError.Offline` if false) but has **no
retry or partial-progress logic** for a mid-sync drop — any `Either.Left` from `fetch`/`merge`/`push`
aborts the whole function immediately via `return@withContext err.left()`, leaving `_syncState.value =
SyncState.Error(err)`. There is no resumption state; the next `sync()` call starts over cleanly (local
git repo state — index, working tree — is authoritative and unaffected by a failed network call, so "start
over" is cheap and correct on desktop). For web, ADR-015's own Rabbit Holes note already prescribes the
right analogue: "Retry logic must correctly re-derive the dirty set from scratch rather than assuming
partial progress can be resumed from where it failed" — i.e., same policy as desktop (full restart, driven
off the OPFS dirty set instead of a git index), not a novel partial-retry design. The one new risk unique
to the REST-API model (that desktop's local-git approach doesn't have): a failure between blob/tree/commit
creation (steps 1-4) and ref update (step 5) leaves orphaned-but-harmless objects on the host (ADR-015
already notes GitHub GCs these); GitLab's single-call commit API doesn't have this partial-state risk at
all since it's atomic — one more point in favor of preferring the GitLab path's simplicity where possible,
though GitHub obviously can't use it.

## 3. Unstated user needs

- **`SyncStatusBadge` is a fully-built, currently-dead UI element on web** — confirmed by tracing `App.kt`
  (§0 above): the badge already renders `SyncState.Idle/Fetching/Merging/Pushing/Committing/
  MergeAvailable/ConflictPending/Error/Success` states with distinct icons/colors/copy, and already
  falls back to a "Set up sync" affordance when `isGitConfigured` is false. On web today it can only ever
  show "Set up sync" (or worse, be wired to a permanently-null `gitSyncService` if `isGitConfigured` is
  computed independently of `gitSyncService` — worth double-checking in planning). This directly answers
  the "does the user need to *know* which files are dirty" question: **yes, and the UI for it already
  exists** — `isDirty()`/dirty-set state doesn't need a new affordance built, it needs to be plumbed into
  the existing `SyncState` stream so `SyncStatusBadge` finally renders real data on web. This is a strong
  argument for the `GitRepository`-actual approach in §0 over a parallel `GitManager` implementation that
  the badge can't see.
- **Users will expect the "Set up sync" → configured → syncing → conflict flow to look identical to
  desktop**, since `GitSetupScreen.kt` is the same Compose screen on every platform. Any web-specific
  wrinkle (PAT re-entry every session, per `ADR-015`'s accepted v1 gap) needs a small platform-conditional
  string/tooltip, but the flow itself should not diverge — reinforces "no new UI branching" as achievable
  and worth protecting in review.
- **Silent partial failure is the worst outcome here, worse than an explicit "not supported" stub** —
  BUG-005's own history (silent fake success → Phase 1 visible-failure stub) shows the team has already
  been burned by this once. Any new failure mode introduced by the REST-API write path (e.g., blob created
  but ref update fails, §2e) must resolve to a `SyncState.Error` the badge can show, never a silent no-op.

## 4. Prior art — browser-only git REST write-back (brief; web research)

No project was found doing genuine **client-only, multi-writer conflict *merging*** the way ADR-015
specifies (file-path-level auto-merge for non-overlapping files, UI resolution for overlapping ones).
Every example found relies on the git host's own optimistic-concurrency primitive (SHA-mismatch → HTTP
409) as its *only* safety net, surfaced to the user rather than resolved automatically:

- **Prose.io** (legacy GitHub-backed browser CMS) — used the Contents API (`PUT .../contents/{path}`
  requires the current file `sha`); a stale `sha` returns `409`, which the app surfaced with limited
  recovery UX.
- **StackEdit** — syncs Markdown to GitHub via the Contents API from the browser; community reports
  (StackEdit GitHub issues) show raw `409 Conflict` surfaced to the user, no auto-merge.
- **github.dev / vscode.dev** — the built-in GitHub extension calls the Contents/Git Data API directly
  from the browser; same SHA-based `409` mechanism, surfaced as an editor refresh/merge prompt.
- **Decap CMS (GitHub backend)** — uses the REST API, almost always behind an OAuth proxy (Git Gateway);
  avoids conflicts structurally (one PR per unpublished entry) rather than diff-merging.

Takeaway: ADR-015's file-path-level auto-merge (not content-level) is more ambitious than any prior art
found here, which mostly punts to "let the host's version check fail and tell the user." This isn't a
reason to abandon the ADR's approach, but it does mean there's no existing implementation pattern to
crib the merge logic from — planning should treat the auto-merge path as novel and budget test/validation
time accordingly, and should confirm SHA-mismatch-as-safety-net (i.e., GitHub `PATCH refs/heads/{branch}`
already fails atomically if the ref moved, so a *broken* auto-merge implementation degrades to "surface a
conflict" rather than silently corrupting the remote — worth stating as an explicit safety property in the
plan).

## Summary of items planning must explicitly decide (not re-litigated here)

1. `GitRepository` wasmJs actual vs. `GitManager`/`WasmGitWriteService` per the ADR's literal text vs.
   hybrid (§0) — this determines whether the shipped feature actually reaches the real UI.
2. Correct the requirements.md success-metric reference from `DomainError.ConflictError.DiskConflict` to
   `DomainError.GitError.MergeConflict` + `SyncState.ConflictPending(List<ConflictFile>)` (§1).
3. Empty-commit guard equivalent to desktop's `hasLocalChanges` check (§2b).
4. Explicit file-size-limit `DomainError` + UX for the ~75 MB practical GitHub ceiling and GitLab's
   300 MB/413 behavior (§2c).
5. Deleted-vs-edited-remote representation in `ConflictFile`/`MergeSide` (§2a) — current model has no
   "absent" side.
