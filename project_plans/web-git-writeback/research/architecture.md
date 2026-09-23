# Research: Architecture & Integration — web-git-writeback

Agent: Research Agent 3 (Architecture). Builds directly on `ADR-013` (read path) and
`ADR-015` (write path, Accepted) — those decisions are not re-derived here. This document
resolves the integration/data-flow details ADR-015 states but doesn't fully specify, and
corrects two factual gaps found in the code that planning needs to know about up front.

## 0. Corrections to ADR text — read before planning

These aren't re-litigations of the ADR's decision, just facts the code disagrees with:

1. **`WasmSectionSyncService` has no host-detection logic today.** ADR-015 says write-path
   host-detection should reuse "the same host-detection logic as ADR-013's manifest fetch."
   That logic doesn't exist yet — `WasmSectionSyncService.kt` is hardcoded to
   `api.github.com`, a `Bearer <token>` auth header, and `Accept: application/vnd.github+json`.
   There is no GitLab/Gitea branch anywhere in the file. "Reuse" is aspirational; planning
   should treat host-detection as **new work**, not extraction of existing logic.
2. **The conflict type ADR-015/requirements.md cite is the wrong one.** Both the ADR and
   `requirements.md` say the write service should "surface `DomainError.ConflictError.DiskConflict`
   / `ConflictResolutionScreen`." Reading `GitSyncService.kt` (the JVM/Android implementation
   `WasmGitWriteService` must produce parity with) shows this is **not** what actually happens:
   - `DomainError.ConflictError.DiskConflict(pageUuid: String, message: String)` is a *different*
     mechanism — it's for external-file-vs-editor-buffer conflicts (single page, matched via
     `DiskConflictBlockMatcher`, resolved via `DiskConflictDialog`/`DiskConflictFullScreen`). It
     has nothing to do with git merges.
   - The actual git-merge-conflict path is `DomainError.GitError.MergeConflict(conflictCount, conflictPaths: List<String>)`
     combined with `SyncState.ConflictPending(conflicts: List<ConflictFile>)`, consumed by
     `ConflictResolutionScreen` (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/screens/git/ConflictResolutionScreen.kt`).
   - `ConflictFile` (`git/model/ConflictModels.kt`) is `data class ConflictFile(filePath, wikiRelativePath, hunks: List<ConflictHunk>)`,
     and `ConflictHunk` is **line-level**: `id, localLines: List<String>, remoteLines: List<String>, resolution, manualContent`.
     This is a real 3-way-diff hunk model, not a file-path-level "these two files both changed" marker.

   **Consequence for planning**: to reuse `ConflictResolutionScreen` unmodified (the stated
   rationale in ADR-015 §"Shared GitManager interface preserves UI parity"), `WasmGitWriteService`
   must, for overlapping-file conflicts, actually compute line-level hunks (local content vs.
   base content vs. remote content) and populate `ConflictHunk.localLines`/`remoteLines` — not
   just detect that a path changed on both sides. This is real, non-trivial work (a text 3-way
   diff in Kotlin/Wasm) that ADR-015's file-path-granularity auto-merge discussion does not
   mention. It should be scoped explicitly in `plan.md`, not discovered mid-implementation.

## 1. Data-flow diagram: local edit → remote push → UI update

| # | Stage | Component | Detail |
|---|---|---|---|
| 1 | User edits a block | `BlockEditor` → `BlockStateManager` | Local state only, debounced 500ms |
| 2 | Debounced save | `GraphWriter.saveBlock` / `savePage` | commonMain; calls `fileSystem.writeFile(path, content)` (or `writeFileBytes` for paranoid mode, `deleteFile`/`renameFile` for delete/rename flows) — see exact call sites below |
| 3 | **Dirty-tracking hook (new)** | `PlatformFileSystem` (wasmJs actual) | Inside `writeFile`/`writeFileBytes`/`deleteFile`, *in addition* to the existing `cache[path] = content` + `scope.launch { opfsWriteFile(...) }`, record `path` into an in-memory dirty set and debounce-checkpoint it to OPFS as `.stele-dirty-set.json`. Purely internal to the wasmJs actual — no commonMain change (see §2). |
| 4 | User triggers sync | UI (sync button / auto-sync) | Calls into the same `GitManager.push()` / a sync orchestrator as JVM/Android — no new UI surface per ADR-015 rationale #3 |
| 5 | Dispatch | `JsGitManager` (wasmJs `GitManager` actual) | Currently stubbed to `NOT_SUPPORTED` for all 5 methods. Wired to call `WasmGitWriteService` when `PlatformFileSystem.githubToken`/owner/repo (or GitLab equivalents) are non-empty; otherwise unchanged `NOT_SUPPORTED` fallback (requirements.md "Out of Scope" / BUG-005 Phase 2 gate) |
| 6 | Read dirty set | `WasmGitWriteService` | Reads `.stele-dirty-set.json` from OPFS (or the in-memory set if already warm) — this *is* the local diff, no working-tree scan needed |
| 7 | Host dispatch | `GitHostAdapter` (new, see §3) | Detects GitHub vs. GitLab vs. unsupported from the configured remote URL |
| 8a | GitHub path | `WasmGitWriteService` | 5-step Git Data API sequence per ADR-015 §Decision: blob(s) → base tree fetch → new tree → **fetch current ref SHA (conflict check)** → commit → ref PATCH |
| 8b | GitLab path | `WasmGitWriteService` | Single `POST /api/v4/projects/{id}/repository/commits` with `actions` array + `start_branch` for conflict detection |
| 9a | No conflict | — | Ref/commit succeeds → clear dirty set in OPFS, update `base-sha` in sync marker → `SyncState.Success` |
| 9b | Non-overlapping conflict | `WasmGitWriteService` | Auto-merge: include both local dirty files and remote-changed files as tree entries against the new remote head as base; proceed as 9a |
| 9c | Overlapping conflict | `WasmGitWriteService` | Compute line-level `ConflictHunk`s per overlapping file (see §0.2) → `DomainError.GitError.MergeConflict` + `SyncState.ConflictPending(conflicts)` |
| 10 | UI update | `ConflictResolutionScreen` / sync-status badge | Same Compose UI as JVM/Android, driven by `SyncState` — no wasmJs-specific branch (ADR-015 rationale §3) |
| 11 | User resolves | `ConflictResolutionScreen` → `GitSyncService.resolveConflict`-equivalent | On wasmJs this must call back into `WasmGitWriteService` to re-run the write with resolved content — **note**: `GitSyncService.resolveConflict` itself is JVM/Android-specific (calls `gitRepository.markResolved`, a JGit/CLI concept with no Git-Data-API equivalent) — `WasmGitWriteService` needs its own resolution-retry method, not a literal call into `GitSyncService`. Flag for `plan.md`. |

**Exact `FileSystem` call sites in `GraphWriter.kt` that need dirty-tracking behind them**
(from `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphWriter.kt`): `writeFileBytes` (lines
271, 475, 494, 655), `writeFile` (281, 483, 497, 615, 661), `deleteFile` (288, 326, 495, 498, 665),
`renameFile` (648), and `markDirty` (481, Android-SAF-only write-behind path — see §2). All of
these funnel through the wasmJs `PlatformFileSystem` actual, so hooking `writeFile`/`writeFileBytes`/`deleteFile`
there catches every `GraphWriter` write path without touching `GraphWriter` itself.

**Pre-existing gaps this project inherits (not in scope to fix, but relevant to design)**:
`PlatformFileSystem` (wasmJs) does not override `writeFileBytes` (falls through to the
`FileSystem` default, which throws `UnsupportedOperationException`) or `renameFile` (falls
through to default `false`). Paranoid-mode saves and page renames are therefore currently
non-functional on web, independent of this project. The dirty-tracking hook design should
still add itself to `writeFileBytes`'s signature (for when it's eventually implemented) but
must not assume it's reachable today.

## 2. Where the dirty-tracking hook lives

**Recommendation: entirely inside the wasmJs `PlatformFileSystem` actual — not via `GraphWriter`
calling an explicit `fileSystem.markDirty()`, and not by extending the common `FileSystem.markDirty`.**

Why `FileSystem.markDirty` is not reusable here — it's a different contract, not just a
different call site:

- `markDirty(path, content): Boolean` (defined in `platform/FileSystem.kt`, used by
  Android's SAF write-behind path — see `GraphWriter.kt:481`) means *"I have queued this
  write to shadow storage myself; caller must NOT also call `writeFile`."* Its `true` return
  value suppresses the fallback `fileSystem.writeFile(...)` call at `GraphWriter.kt:483`.
- What web git-write-back needs is the opposite: *"the write already happened via the normal
  `writeFile` path; additionally remember that this path is now dirty for git purposes."*
  There is no "suppress the real write" semantic here at all.

Reusing `markDirty` for this would conflate two unrelated concerns (write-behind buffering vs.
git dirty-set bookkeeping) behind one boolean-returning method whose contract already means
something else on Android. That's exactly the kind of hidden coupling the requirements doc's
Rabbit Holes section warns against for host-detection — same principle applies here.

Concretely: no change to the common `FileSystem` interface or to `GraphWriter.kt` is needed at
all. Inside `kmp/src/wasmJsMain/.../platform/PlatformFileSystem.kt`:

```kotlin
actual override fun writeFile(path: String, content: String): Boolean {
    if (path.startsWith(DOWNLOAD_PREFIX)) { /* unchanged */ }
    cache[path] = content
    scope.launch { opfsWriteFile(path, content) }
    recordDirty(path, DirtyOp.WRITE)          // new
    return true
}

actual override fun deleteFile(path: String): Boolean {
    cache.remove(path)
    scope.launch { opfsDeleteFile(path) }
    recordDirty(path, DirtyOp.DELETE)         // new
    return true
}
// writeFileBytes: not currently overridden — add recordDirty() call when/if it is implemented
```

`recordDirty` updates an in-memory `MutableMap<String, DirtyEntry>` and launches a debounced
(e.g. 2s idle or every Nth write) checkpoint write of `.stele-dirty-set.json` to OPFS — reusing
the same `scope`/`opfsWriteFile` machinery already in the class. This is O(dirty-set size), not
O(graph), satisfying the NFR in requirements.md. `preload()` (already called at startup) reads
the marker back and seeds the in-memory set, satisfying "restore from OPFS checkpoint on page
reload."

This keeps the change fully contained to one wasmJs file, requires zero commonMain surface
change, and can't regress JVM/Android/iOS (they don't touch this code path at all).

## 3. `GitHostAdapter`-style abstraction — recommendation: extract a thin one

**Recommendation: yes, extract a small shared `GitHostAdapter`, scoped narrowly to host
detection + URL/header construction — not to fetch mechanics or response parsing.** Concrete,
not "it depends":

Evidence for extracting:
- The **read path today already duplicates** the same four config fields
  (`githubOwner`/`githubRepo`/`githubBranch`/`githubToken`) as **static companion vars in two
  separate classes** (`WasmSectionSyncService.Companion` and `PlatformFileSystem.Companion`),
  both set from the same four lines in `Main.kt`. This duplication already exists and already
  had to be kept in sync manually — adding a third copy for `WasmGitWriteService` compounds a
  problem that's already visible, not a hypothetical one.
- Per §0.1, host-detection does not exist yet anywhere in the codebase. Building it once,
  shared, is no more expensive than building it once for write-only and then building it again
  for read when GitLab read support eventually lands (ADR-013's host list already promises
  GitLab/Gitea for read; today only GitHub is implemented).
- Auth header format already differs between the one implemented caller and what ADR-015
  specifies: `WasmSectionSyncService` sends `Authorization: Bearer <token>`; ADR-015's write
  spec says `Authorization: token <token>` for GitHub Git Data API calls. GitHub currently
  accepts both for PATs, but a shared adapter makes this an explicit, tested decision instead
  of two files silently disagreeing.

Evidence for keeping duplication in the actual request/response logic:
- Read fetches are single GETs of raw content or a tree listing; write is a multi-step
  JSON-body POST/PATCH sequence with response-body parsing (blob SHA, tree SHA, commit SHA)
  and a fundamentally different retry/atomicity story (§ADR-015 "not atomic between steps
  4–6"). Forcing these through one abstraction would produce a leaky "one interface, two
  callers each ignoring half its surface" result — the anti-pattern the Rabbit Holes section
  is actually warning about.

**Concrete shape** (small, in `sync/` or a new `git/host/` package, `wasmJs`-only or
`commonMain` if JVM/Android host-detection is ever unified — out of scope to decide here):

```kotlin
enum class GitHostType { GITHUB, GITLAB, UNSUPPORTED }

data class GitHostConfig(
    val type: GitHostType,
    val owner: String,       // GitHub: org/user; GitLab: url-encoded namespace/project or numeric id
    val repo: String,
    val branch: String,
    val token: String?,
    val apiBase: String,     // e.g. "https://api.github.com" or "https://gitlab.com/api/v4"
)

object GitHostAdapter {
    fun detect(remoteUrl: String): GitHostType = /* github.com vs gitlab.com host match; else UNSUPPORTED per Gitea-out-of-scope */
    fun authHeader(type: GitHostType, token: String): Pair<String, String> =
        when (type) {
            GitHostType.GITHUB -> "Authorization" to "Bearer $token"   // match existing read-path convention
            GitHostType.GITLAB -> "Authorization" to "Bearer $token"
            GitHostType.UNSUPPORTED -> error("unsupported host")
        }
}
```

Both `WasmSectionSyncService` and `WasmGitWriteService` take a `GitHostConfig` (or call
`GitHostAdapter.detect`) instead of reading the four scattered companion vars directly. This
also gives planning a natural seam to eventually delete the `PlatformFileSystem.githubOwner`
et al. duplication, though that cleanup is optional/nice-to-have, not required for this
project's scope.

## 4. `.stele-dirty-set.json` schema

Modeled on the existing `.stele-sections-sync-complete` commit-flag marker pattern from
ADR-013 (`SectionOpfsSyncState(commitSha, fileCount, timestampMillis)`), extended with a
per-file dirty map and an explicit delete-vs-write op so the git write step doesn't need to
diff against `cache` to figure out which:

```json
{
  "version": 1,
  "graphId": "default",
  "baseSha": "8f3c1a9...",
  "checkpointedAtMillis": 1752500000000,
  "dirtyFiles": {
    "pages/Foo.md": { "op": "write", "updatedAtMillis": 1752499990000 },
    "pages/Bar.md": { "op": "delete", "updatedAtMillis": 1752499995000 }
  }
}
```

Field notes:
- `version`: schema version int, for forward compatibility (mirrors the versioning discipline
  already used for `MigrationRunner`/`SerializableConflictFile`-style persisted JSON in this
  codebase).
- `graphId`: matches `WasmSectionSyncService.graphId` / `GraphManager` per-graph scoping —
  needed because OPFS is one origin-wide store but multiple graphs can exist.
- `baseSha`: the remote commit SHA this dirty set was computed relative to. Required both for
  GitHub's step-5 conflict check (compare against current ref SHA) and as the base for the
  new-tree creation in step 4 — without persisting this, a page reload mid-edit-session would
  lose the base and force a full re-fetch to re-derive it.
- `dirtyFiles` keys: **repo-relative paths**, not OPFS-absolute (`/stelekit/{graphId}/...`).
  `PlatformFileSystem.readFileSuspend` already derives repo-relative paths from OPFS-absolute
  ones (`path.removePrefix("/stelekit/").substringAfter("/")` — the `graphId` component is
  stripped by the first `removePrefix`+`substringAfter`); reuse that exact derivation so the
  dirty-set format lines up with what the GitHub tree API and `WasmSectionSyncService`'s
  `path` variables already use, avoiding a third path-format convention in the codebase.
- `op`: `"write" | "delete"` — needed because GitHub tree construction (step 4) represents a
  delete as a tree entry with `sha: null`, distinct from a blob-referencing write entry;
  collapsing this into one undifferentiated dirty set would lose that distinction.
- `updatedAtMillis` per file: not required for push logic, but cheap and useful for the
  Observability Requirement in requirements.md ("debug 'my commit never showed up' reports").
- `checkpointedAtMillis` (top-level): last time this marker itself was flushed — diagnostic
  only.

Lifecycle: written debounced on every `recordDirty` (§2); **cleared** (`dirtyFiles = {}`,
`baseSha` updated to the new HEAD) as the *last* write after a successful ref update/commit —
same commit-flag-last ordering ADR-013 uses for crash safety, so a crash mid-push leaves the
marker still showing the pre-push dirty state and `baseSha`, which is exactly what's needed to
retry-from-scratch per ADR-015's "retry must re-derive dirty set from scratch" consequence.
Restored on `PlatformFileSystem.preload()` at startup, seeding the in-memory dirty map before
any new writes occur.

## 5. Event-Command-Policy table (EventStorming grammar)

| Domain Event | Policy (trigger) | Command | Actor / System |
|---|---|---|---|
| `FileEdited` | — (raw input) | `SaveBlock` / `SavePage` | User → `BlockEditor`/`GraphWriter` |
| `FileWritten` (OPFS write completed) | Whenever a file is written, then record it dirty | `RecordDirtyFile(path, op)` | `PlatformFileSystem` (system, internal hook) |
| `DirtySetCheckpointed` | Whenever dirty set changes (debounced), then persist | `WriteDirtySetMarker` | `PlatformFileSystem` → OPFS |
| `SyncTriggered` | User action (button) or auto-sync policy | `BeginPush` | User → `JsGitManager` |
| `CredentialsMissing` | Whenever `BeginPush` and no PAT/remote configured | `ReturnNotSupportedError` | `JsGitManager` (unchanged Phase-1 fallback) |
| `HostDetected` | Whenever `BeginPush` and credentials present | `DispatchByHost` | `GitHostAdapter` |
| `UnsupportedHostDetected` | Whenever host is Gitea/Forgejo/unknown | `ReturnUnsupportedHostError` | `WasmGitWriteService` (`DomainError.NetworkError.RequestFailed`) |
| `DirtySetRead` | Whenever `DispatchByHost` succeeds | `ReadDirtySetFromOpfs` | `WasmGitWriteService` |
| `BlobsCreated` (GitHub) | Whenever dirty set non-empty and host = GitHub | `CreateBlobsForDirtyFiles` | `WasmGitWriteService` → GitHub Git Data API |
| `CommitActionsPrepared` (GitLab) | Whenever dirty set non-empty and host = GitLab | `BuildActionsArray` | `WasmGitWriteService` |
| `BaseTreeFetched` | After `BlobsCreated` | `FetchBaseTree(baseSha)` | `WasmGitWriteService` → GitHub API |
| `NewTreeCreated` | After `BaseTreeFetched` | `CreateTree(blobs, baseTree)` | `WasmGitWriteService` → GitHub API |
| `RemoteRefChecked` | After `NewTreeCreated`, before commit | `FetchCurrentRefSha` | `WasmGitWriteService` → GitHub API |
| `RefUnchanged` | Whenever fetched ref SHA == local `baseSha` | `CreateCommitAndAdvanceRef` | `WasmGitWriteService` |
| `RefAdvancedRemotely` | Whenever fetched ref SHA != local `baseSha` | `FetchCompareDelta` | `WasmGitWriteService` → GitHub compare API |
| `RemoteDeltaComputed` | After `FetchCompareDelta` | `PartitionOverlappingVsNonOverlappingFiles` | `WasmGitWriteService` (file-path-level, per ADR-015 Rabbit Hole) |
| `ChangesNonOverlapping` | Whenever partition finds no path intersection | `AutoMergeTrees(localDirty, remoteChanged, newHead)` | `WasmGitWriteService` |
| `ChangesOverlapping` | Whenever partition finds path intersection | `ComputeConflictHunks(localContent, baseContent, remoteContent)` | `WasmGitWriteService` (new 3-way line diff, §0.2) |
| `ConflictHunksComputed` | After `ComputeConflictHunks` | `SurfaceConflict(ConflictFile[])` | `WasmGitWriteService` → `SyncState.ConflictPending` |
| `ConflictSurfacedToUser` | Whenever `SyncState.ConflictPending` emitted | `DisplayConflictResolutionScreen` | `ConflictResolutionScreen` (existing, unmodified) |
| `ConflictResolvedByUser` | Whenever user submits hunk resolutions | `RetryPushWithResolvedContent` | User → `WasmGitWriteService` (new resolution-retry entry point, §1 row 11) |
| `CommitCreated` | After `CreateCommitAndAdvanceRef` or `AutoMergeTrees` succeeds | `AdvanceRef(branch, commitSha)` | `WasmGitWriteService` → GitHub API (`PATCH refs/heads/{branch}`) or GitLab single-call |
| `RefAdvanceFailed` (race between ref-check and PATCH) | Whenever `AdvanceRef` returns 409/422 | `RetryFromScratch` | `WasmGitWriteService` (per ADR-015: do NOT resume from step 4, re-derive dirty set) |
| `PushSucceeded` | After `AdvanceRef` succeeds | `ClearDirtySet` + `UpdateBaseSha` | `WasmGitWriteService` → `PlatformFileSystem`/OPFS marker |
| `PushSucceeded` | — | `EmitSyncSuccess` | `WasmGitWriteService` → `SyncState.Success` → UI |
| `PushFailed` (network/auth/rate-limit) | Whenever any API call errors terminally | `EmitSyncError(DomainError.GitError.*)` | `WasmGitWriteService` → `SyncState.Error` → UI |
| `WriteBackAttemptLogged` | Whenever any terminal outcome (success/conflict/auto-merge/failed) occurs | `LogOutcome(step, domainError?)` | `WasmGitWriteService` → `Logger` (never logs PAT — Security NFR) |

## 6. Summary of concrete recommendations for `plan.md`

1. Treat host-detection as new shared code (`GitHostAdapter`), not reuse of existing logic —
   none exists yet (§0.1, §3).
2. Dirty-tracking hook lives entirely inside wasmJs `PlatformFileSystem.writeFile`/`writeFileBytes`/`deleteFile`;
   do not extend or reuse `FileSystem.markDirty` (§2) — zero commonMain/`GraphWriter` changes.
3. Extract a thin `GitHostAdapter` (host detection + auth header + API base URL) shared
   between `WasmSectionSyncService` and `WasmGitWriteService`; keep fetch/response mechanics
   separate (§3).
4. `.stele-dirty-set.json` schema as specified in §4 — repo-relative paths, per-file op,
   persisted `baseSha`, clear-last-after-push ordering.
5. Scope a real line-level 3-way diff (`ConflictHunk` generation) explicitly in `plan.md` —
   the ADR's file-path-level auto-merge granularity is correct for *deciding* auto-merge vs.
   surface-conflict, but the actual `ConflictResolutionScreen` UI needs hunk-level data for the
   overlapping-file case, which ADR-015 doesn't mention building (§0.2).
6. Do not build a common `GitWriteService` `expect`/`actual` interface unless a concrete
   second need for it emerges — `WasmGitWriteService` can be called directly from the `wasmJs`
   `JsGitManager` actual without one; ADR-015 mentions such an interface but nothing in the
   codebase requires it structurally today, and adding an unnecessary abstraction layer costs
   Large-appetite time better spent on the diff/conflict work in item 5.
7. `WasmGitWriteService`'s conflict-resolution retry path cannot literally call
   `GitSyncService.resolveConflict` (JVM/JGit-specific — `markResolved`, staged index) — it
   needs its own resolution-retry method that re-runs the Git Data API sequence with resolved
   content (§1 row 11).
