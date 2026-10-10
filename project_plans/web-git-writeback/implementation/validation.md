# Validation Plan: web-git-writeback

**Date**: 2026-07-14

## Happy Path Scenario
Given a web user has configured git sync against a GitHub repo (`GitConfig` saved, PAT entered this session, baseline: nothing reaches the remote today per `requirements.md`'s Baseline), when they edit a page and tap the sync badge, then `WasmGitWriteService` stages a commit via the GitHub 5-step Git Data API sequence, detects no remote conflict, pushes the ref update inside the per-remote `GitWriteLock`, clears the dirty set last, and the corresponding commit appears on GitHub with the correct author/message — while the sidebar badge transitions `LocalChangesPending(1) → Committing → Pushing → Success` (Success Metric #1).

---

## Requirement traceability — Success Metrics & Scope (walked first, then mapped to Stories below)

| ID | Requirement (requirements.md) | Primary Stories | Primary Test IDs |
|---|---|---|---|
| SM1 | GitHub edit → sync → commit appears on remote w/ correct author/message, verified E2E | 3.1.1, 3.1.2, 4.1.1, 4.3.1 | IT-3.1.1-A, IT-3.1.2-A/B, `WasmGitWriteServiceLiveTest.liveGitHubPushAndVerify` |
| SM2 | Same for GitLab, single-call commit API | 3.2.1 | IT-3.2.1-A, `WasmGitWriteServiceLiveTest.liveGitLabPushAndVerify` |
| SM3 | `isDirty()` reflects real local-vs-remote state, visible in UI (not stub) | 2.1.1, 4.2.1, 4.3.2, 5.1.1 | TC-2.1.1-A/B, IT-4.2.1-A, TC-4.3.2-A/B, UX-S1-1..6 |
| SM4 | Conflicting remote change → `MergeConflict` → `ConflictPending` → `ConflictResolutionScreen`, verified by scripted two-writer race | 3.2.3, 3.3.2 | TC-3.2.3-A/B, IT-3.2.3-A, IT-3.3.2-A, UX-S2-1..5 |
| SM5 | Non-overlapping concurrent changes auto-merge without user intervention | 3.3.1, 3.2.2 | TC-3.3.1-A/B, IT-3.3.1-A, TC-3.2.2-A/B, IT-3.2.2-A |
| SM6 | No regression to `WasmSectionSyncService` read path or JVM/Android `GitSyncService` | 4.3.2 | IT-4.3.2-A (JVM/Android `localChangesCountFlow == null` parity), existing `WasmSectionSyncServiceTest` suite unchanged (regression gate) |

| ID | Scope — In Scope item | Primary Stories | Primary Test IDs |
|---|---|---|---|
| SC1 | `WasmGitWriteService`: GitHub 5-step + GitLab 1-call via `GitHostAdapter` | 1.1.1, 1.1.2, 1.2.1, 1.2.2, 3.1.1, 3.1.2, 3.2.1 | see Epic 1 & 3 rows |
| SC2 | `wasmJsMain` `GitRepository` actual | 4.1.1 | TC-4.1.1-A/B, IT-4.1.1-A |
| SC3 | Dirty-file tracking inside `PlatformFileSystem` actuals, OPFS-checkpointed | 2.1.1, 2.1.2 | TC-2.1.1-A/B, IT-2.1.2-A |
| SC4 | Exclusive per-remote Web Lock around `push()`'s write-critical-section | 2.3.1 | TC-2.3.1-A/B, IT-2.3.1-A/B/C (covered **and** known-gap tests) |
| SC5 | Conflict detection + auto-merge / conflict-surface logic | 3.2.2, 3.2.3, 3.3.1, 3.3.2 | see Epic 3.2/3.3 rows |
| SC6 | Wire `commit`/`push`/`pull`/`status`/`isDirty` to `WasmGitWriteService` (`JsGitManager` + `GitRepository`) | 4.1.1, 4.2.1 | TC-4.1.1-A/B, TC-4.2.1-A/B, IT-4.2.1-A |
| SC7 | Distinct `Either<DomainError,T>` failure variants for the write-path fetch layer | 1.3.1, 3.4.1 | TC-1.3.1-A/B, TC-3.4.1-A/B, IT-3.4.1-A |
| SC8 | `LocalChangesPending` + `RateLimited` `SyncState` additions | 1.3.2, 1.3.3, 4.3.2, 5.1.1, 5.1.2 | TC-1.3.2-A, IT-1.3.3-A/B, TC-4.3.2-A/B, UX-S1/UX-S5 |
| SC9 | Test coverage: unit + ≥1 scripted E2E path against real/realistic API | all | Epic 6.1/6.2 (this doc), Epic 6.3 gated live test (below) |

---

## Requirement → Test Mapping (by Story)

### Epic 1.1 — `GitHostAdapter`

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| S1.1.1 (SC1, SC6) | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/GitHostAdapterTest.kt` | `` `TC-1.1.1-A detect classifies github.com HTTPS and SSH remotes as GITHUB` `` | Unit (happy) | `detect("https://github.com/tstapler/steno-wiki.git")` and the SSH form both return `GitHostType.GITHUB`. |
| S1.1.1 | same | `` `TC-1.1.1-B detect classifies an unknown host as UNSUPPORTED without throwing` `` | Unit (error/edge) | `detect("https://git.example.org/tstapler/wiki.git")` returns `UNSUPPORTED`, no exception. |
| S1.1.2 (SC1) | same | `` `TC-1.1.2-A authHeader returns Authorization Bearer for GITHUB and PRIVATE-TOKEN for GITLAB` `` | Unit (happy) | Distinct header **names**, not just prefixes, per host. |
| S1.1.2 | same | `` `TC-1.1.2-B apiBase percent-encodes the GitLab namespace/project path` `` | Unit (error/edge) | `apiBase(GITLAB, "tstapler-notes", "wiki")` → `.../projects/tstapler-notes%2Fwiki`; boundary case: owner/repo containing already-encoded characters is not double-encoded. |

No integration test — `GitHostAdapter` is a pure `commonMain` function with zero I/O (Pattern Decision table), so per the task's own criterion ("integration test if a data store or external call is involved") none applies here.

### Epic 1.2 — Serialization models

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| S1.2.1 (SC1) | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/model/GitDataApiModelsTest.kt` | `` `TC-1.2.1-A GitBlobRequest encodes content and encoding fields exactly` `` | Unit (happy) | `Json.encodeToString(GitBlobRequest(...))` matches GitHub's expected body shape byte-for-byte. |
| S1.2.1 | same | `` `TC-1.2.1-B GitCommitResponse and GitCompareResponse decode with ignoreUnknownKeys when GitHub adds an unmodeled field` `` | Unit (error/edge) | Decoding JSON with an extra unknown top-level key does not throw and yields the expected `sha`/`aheadBy`/`files`. |
| S1.2.2 (SC1) | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/model/GitLabCommitModelsTest.kt` | `` `TC-1.2.2-A GitLabCommitAction serializes an update action with last_commit_id under snake_case keys` `` | Unit (happy) | Confirms `@SerialName` mapping (`file_path`, `last_commit_id`). |
| S1.2.2 | same | `` `TC-1.2.2-B GitLabCommitRequest omits start_sha when null without breaking round-trip` `` | Unit (error/edge) | A request built for the "no known base" case still round-trips; `GitLabCommitResponse` decodes a body missing the optional `short_id`. |

No integration test — pure serialization, no I/O.

### Epic 1.3 — `DomainError` / `SyncState` additions

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| S1.3.1 (SC7, SC8) | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/error/DomainErrorTest.kt` (extend existing file/list) | `` `rate_limited_message_never_suggests_manual_retry` `` | Unit (happy) | `DomainError.GitError.RateLimited(42).toSyncErrorMessage()` mentions "rate limit" and asserts `!contains("tap to retry", ignoreCase = true)` — this is **the** DomainError-layer half of the "RateLimited never tap-to-retry" requirement; Story 5.1.2 below covers the UI-layer half. |
| S1.3.1 | same | `` `file_too_large_message_contains_path_and_both_byte_counts` `` | Unit (error/edge) | `FileTooLarge("assets/large.md.stek", 90_000_000, 75_000_000).message` contains the path and both byte counts. |
| S1.3.1 | same | `` `exhaustive_when_covers_all_variants` `` (extend the existing list with `RateLimited`/`FileTooLarge`) | Unit (regression/compile-safety) | `toUiMessage()`/`toSyncErrorMessage()` compile with no `else` fallback added — a missing branch fails the build, not just this test. |
| S1.3.2 (SC8) | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/model/SyncStateTest.kt` (new) | `` `TC-1.3.2-A LocalChangesPending is a distinct sealed variant requiring an explicit when branch` `` | Unit (happy) | A `when (syncState)` over all `SyncState` variants compiles only with an explicit `LocalChangesPending` branch (no `else`); `fileCount` round-trips through the constructor. |
| S1.3.3 (SC8) | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/git/GitSyncServiceTest.kt` (extend) | `` `sync when commit returns RateLimited emits SyncState RateLimited not Error` `` | Integration | Real `GitSyncService` + real `EditLock`/`GraphLoader`/`GraphWriter` + a stub `GitRepository` whose `commit()` returns `Either.Left(GitError.RateLimited(30))`; asserts `_syncState.value` is `SyncState.RateLimited(30)`. |
| S1.3.3 | same | `` `sync when fetch returns RateLimited during fetchOnly emits SyncState RateLimited` `` | Integration (error path) | Same pattern at the `fetchOnly()` call site — proves all 5 `.onLeft` sites (Task 1.3.3b) got the branch, not just one. |

### Epic 2.1 — `PlatformFileSystem` dirty-tracking hooks

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| S2.1.1 (SC3) | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/platform/DirtyTrackingAlgorithmsTest.kt` (new — pure-Kotlin double of `recordDirty`, per `WasmSectionSyncServiceTest` precedent since the real class is `wasmJsMain`-only) | `` `TC-2.1.1-A recordDirty derives repo-relative path and records a WRITE entry` `` | Unit (happy) | `recordDirty("/stelekit/default/pages/Foo.md", WRITE)` double → snapshot key `"pages/Foo.md"`. |
| S2.1.1 | same | `` `TC-2.1.1-B DOWNLOAD_PREFIX paths and DELETE-overwrites-WRITE are handled correctly` `` | Unit (error/edge) | `/_wasm_dl_/...` paths never recorded; a prior `WRITE` entry for a path is overwritten (not merged) by a subsequent `DELETE`. |
| S2.1.1 | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/PlatformFileSystemDirtyTrackingIntegrationTest.kt` (new) | `IT-2.1.1-A` `writeFile/deleteFile against the real wasmJs actual update dirtyFileCountFlow and getDirtySnapshot in a real browser | Integration | Runs in headless Chrome (existing `wasmJsTest` Karma runner) against the *real* `PlatformFileSystem` actual — not the double — exercising real OPFS-adjacent state, closing the gap a pure double alone leaves. |
| S2.1.2 (SC3) | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/PlatformFileSystemDirtyTrackingIntegrationTest.kt` | `IT-2.1.2-A` `debounced marker write coalesces 3 rapid writeFile calls into one OPFS write containing all 3 paths | Integration | Real OPFS (`navigator.storage.getDirectory()`), real debounce timer; asserts exactly one `.stele-dirty-set.json` write after the 2s window. |
| S2.1.2 | same file | `IT-2.1.2-B` `preload() restores the in-memory dirty set from a real OPFS marker after a fresh PlatformFileSystem instantiation | Integration (happy) | Simulates "reload" by constructing a **new** `PlatformFileSystem` instance pointed at the same OPFS root; `getDirtySnapshot()`/`dirtyFileCountFlow` match the persisted marker. |
| S2.1.2 | same file | `IT-2.1.2-C` `preload() with a malformed or absent marker starts empty and does not throw | Integration (error/edge) | Corrupt JSON and absent-file cases both crash-safe. |

### Epic 2.2 — `PendingCommit` sum type (trickiest piece #1: resume/cleanup across a mid-sequence browser close)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| S2.2.1 (SC1) | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/model/DirtySetMarkerTest.kt` | `` `TC-2.2.1-A DirtySetMarker with pendingCommit Staged round-trips through kotlinx.serialization's sealed-interface polymorphism` `` | Unit (happy) | `{"type":"staged","commitSha":"c0ffee1","treeSha":"7ea5e11"}` ⇄ `PendingCommit.Staged` exactly; `PendingCommit.None` ⇄ `{"type":"none"}`. |
| S2.2.1 | same | `` `TC-2.2.1-B PendingCommit has no constructor path producing commitSha-set-treeSha-null (illegal state is unrepresentable by the type)` `` | Unit (error/edge) | Enumerates `PendingCommit`'s only two constructors (`None`, `Staged(commitSha, treeSha)`) and asserts there is no third shape — a type-level check, not a runtime-guard check. |
| S2.2.1 | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/git/PendingCommitResumeIntegrationTest.kt` (new) | `IT-2.2.1-A` **`mid-sequence browser-close simulation: commit() stages PendingCommit.Staged, the tab "reloads" (new PlatformFileSystem instance reads the persisted marker), and the next commit() call re-derives a brand-new commit from the current dirty set rather than resuming or reusing the orphaned SHA`** | Integration (the named trickiest-piece test) | Real OPFS reload + MockEngine. Step 1: `commit()` runs steps 1–4, stages `Staged(c1, t1)`, marker persisted. Step 2: discard the service/`PlatformFileSystem` instance, construct fresh ones against the same OPFS root (`preload()`). Step 3: call `commit()` again (dirty set unchanged, simulating "user never got to push before closing"). Assert: a **new** `Staged(c2, t2)` is created (`c2 != c1`), the MockEngine records a *fresh* blob→tree→commit sequence (not a reuse of `c1`/`t1`), and no `push()`/ref-PATCH call ever references `c1`. This directly encodes the Rabbit Holes guidance: "retry must correctly re-derive the dirty set from scratch rather than assuming partial progress can be resumed." |
| S2.2.1 | same file | `IT-2.2.1-B` `push() success resets pendingCommit to None in the same checkpoint write as clearDirtySet — no stale Staged survives a successful push` | Integration | After a successful `push()`, reload from OPFS and assert `pendingCommit == PendingCommit.None`. |
| S2.2.1 | same file | `IT-2.2.1-C` `abortMerge resets pendingCommit to None without touching the dirty set` | Integration | Stages a commit, calls `abortMerge()`, asserts `pendingCommit == None` but `getDirtySnapshot()` is unchanged (full re-derive still possible). |

### Epic 2.3 — `GitWriteLock` (trickiest piece #2: what the narrowed Web Lock does and does NOT protect)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| S2.3.1 (SC4) | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/GitWriteLockNamingTest.kt` | `` `TC-2.3.1-A lockNameFor sanitizes a remote URL into a Locks-API-safe, deterministic name` `` | Unit (happy) | `lockNameFor("https://github.com/tstapler/steno-wiki.git")` → stable, ASCII, no reserved characters. |
| S2.3.1 | same | `` `TC-2.3.1-B lockNameFor produces distinct names for different remotes so unrelated pushes never contend` `` | Unit (error/edge) | Two different remote URLs (incl. one GitHub, one GitLab) never collide; same URL with/without trailing `.git` normalizes to the same name (documents the exact normalization rule so it isn't accidentally asymmetric). |
| S2.3.1 (**covered** case) | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/git/GitWriteLockIntegrationTest.kt` (new) | `IT-2.3.1-A` **`two concurrent push() calls for the SAME remote serialize: the second call's advanceRef+clearDirtySet does not begin until the first fully completes (success or failure) and releases the lock`** | Integration | Real `navigator.locks.request()` in headless Chrome; two coroutines racing `push()` against MockEngine with an artificial delay in the first; asserts strict ordering of the PATCH + checkpoint-clear pair, matching Story 2.3.1 and 3.1.2's own acceptance criteria. |
| S2.3.1 (**NOT covered** case #1 — documents the gap, doesn't "accidentally" pass) | same file | `IT-2.3.1-B` **`no lock is acquired around fetch()/merge()/commit() — two concurrent merge() calls for the same remote CAN both compute a partition from a stale compare-delta snapshot; the residual race is real and only push()'s server-side 409/422 (or GitLab's 400) catches it, never the Web Lock`** | Integration (known-gap characterization test) | Runs two concurrent `merge()` calls with an intentionally stale second snapshot; asserts **both** run without blocking each other (i.e. `GitWriteLock` demonstrably does not serialize this path) — this is the "what it does NOT protect" assertion from Pattern Decision "Web Lock scope," made concrete and CI-enforced so a future accidental behavior change (either direction) is visible. |
| S2.3.1 (**NOT covered** case #2) | same file | `IT-2.3.1-C` `commit() with no following push() in the same attempt never holds or leaks the lock — a subsequent, unrelated push() attempt for the same remote is not blocked by an abandoned commit()` | Integration (known-gap characterization test) | Calls `commit()` alone (as `commitLocalChanges()`/pre-resolution `resolveConflictBySide()` can), discards the attempt, then runs an independent `push()` for the same remote and asserts it acquires the lock immediately — proves the "only `push()` acquires the lock" design has no leak surface, per the Epic 2.3 guarantees-and-gaps note. |
| S2.3.1 (**NOT covered** case #3) | same file | `IT-2.3.1-D` `rapid double-invocation of push() in the same tab is not deduplicated by GitWriteLock — both attempts run (second serialized after the first), neither is rejected; same-tab reentrancy is explicitly out of scope for this lock` | Integration (known-gap characterization test) | Confirms Pattern Decision's explicit statement that same-tab double-tap protection belongs at the UI debounce layer, not here — prevents a future reviewer from assuming this lock does something it doesn't. |

### Epic 3.1 — GitHub 5-step sequence

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| S3.1.1 (SC1, SM1) | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/WasmGitWriteServiceAlgorithmsTest.kt` | `` `TC-3.1.1-A buildGitHubCommit issues exactly one blob, one tree, one commit call in order for a single dirty write` `` | Unit (happy) | Pure-Kotlin double of the orchestration order + payload shape (blob base64, tree `base_tree=baseSha`). |
| S3.1.1 | same | `` `TC-3.1.1-B a file over MAX_BLOB_BYTES fails fast with FileTooLarge before any blob POST is attempted` `` | Unit (error/edge) | Boundary test at 74,999,999 / 75,000,000 / 75,000,001 bytes — only the last triggers `FileTooLarge`. |
| S3.1.1 | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/git/WasmGitWriteServiceMockedIntegrationTest.kt` (new — see Test Stack note on why this file exists) | `IT-3.1.1-A` `commit() against a Ktor MockEngine posts blob → tree → commit in order against the REAL WasmGitWriteService (not a double) and stages PendingCommit.Staged | Integration | Exercises real Ktor client wiring, real JSON (de)serialization, real `PlatformFileSystem.setPendingCommit` — the layer the commonTest doubles cannot reach. |
| S3.1.2 (SC1, SM1, SM4) | `WasmGitWriteServiceAlgorithmsTest.kt` | `` `TC-3.1.2-A push() PATCHes the ref to the staged commit sha with force=false when there is nothing to merge` `` | Unit (happy) | Double of `advanceRef`'s request-shape logic. |
| S3.1.2 | same | `` `TC-3.1.2-B a 409/422 on the ref PATCH maps to MergeConflict, not a generic push failure, and the dirty set is not cleared` `` | Unit (error/edge) | Race between `fetch()`'s ref-check and the PATCH itself. |
| S3.1.2 | `WasmGitWriteServiceMockedIntegrationTest.kt` | `IT-3.1.2-A` `fetch() reports hasRemoteChanges=false when MockEngine's ref GET matches local baseSha; push() then PATCHes and clears the dirty set inside GitWriteLock | Integration (happy) | Full fetch→push happy path against MockEngine, real lock acquisition. |
| S3.1.2 | same | `IT-3.1.2-B` `fetch() reports remoteCommitCount via the compare API when the ref has moved | Integration | `GET compare/{base}...{head}` → `FetchResult(hasRemoteChanges=true, remoteCommitCount=2)`. |

### Epic 3.2 — GitLab path (SM2; conflict detection per SM4/Rabbit Holes)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| S3.2.1 (SC1, SM2) | `WasmGitWriteServiceAlgorithmsTest.kt` | `` `TC-3.2.1-A buildGitLabActions maps one WRITE and one DELETE dirty entry into update+delete actions with start_sha set` `` | Unit (happy) | Matches Story 3.2.1's exact JSON shape assertions. |
| S3.2.1 | same | `` `TC-3.2.1-B resolveActionType chooses create (no last_commit_id) for a path absent from the base-tree snapshot, update otherwise` `` | Unit (error/edge) | Boundary on the "existed at base" map. |
| S3.2.1 | `WasmGitWriteServiceMockedIntegrationTest.kt` | `IT-3.2.1-A` `pushViaGitLab against MockEngine sends exactly one POST with both actions, clears the dirty set on 201, and is wrapped in the same GitWriteLock as the GitHub path | Integration | Confirms same lock name (`stele-write-${urlSafeRemote}`) contract across hosts. |
| S3.2.2 (SC5, SM5) — **GitLab conflict detection, part 1** | `WasmGitWriteServiceAlgorithmsTest.kt` | `` `TC-3.2.2-A partitionConflicts applied to GitLab compare diffs returns non-conflicting for disjoint paths, matching GitHub's contract` `` | Unit (happy) | Reuses the shared `partitionConflicts` — proves host-agnosticism. |
| S3.2.2 | same | `` `TC-3.2.2-B partitionConflicts applied to GitLab compare diffs returns MergeConflict for an overlapping path, identical shape to GitHub's` `` | Unit (error/edge) | Same conflictPaths/conflictCount contract both hosts must produce. |
| S3.2.2 | `WasmGitWriteServiceMockedIntegrationTest.kt` | `IT-3.2.2-A` `fetch()/merge() (GitLab) against MockEngine's compare endpoint set SyncState.ConflictPending or auto-merge identically to the GitHub-wired path via the unmodified GitSyncService | Integration | Drives the real `WasmGitRepository`+`GitSyncService` (not a double) end to end for both the conflict and non-conflict GitLab cases. |
| S3.2.3 (SC5, SM4) — **GitLab conflict detection, part 2 (push-time race)** | `WasmGitWriteServiceAlgorithmsTest.kt` | `` `TC-3.2.3-A a 400 response naming a dirty path in its message classifies as MergeConflict for that path` `` | Unit (happy) | Message-parsing extraction logic. |
| S3.2.3 | same | `` `TC-3.2.3-B a 400 response with an unparseable message falls back to MergeConflict over the full touched-path set (fails toward prompting, never silently drops the write)` `` | Unit (error/edge) | Conservative-classification rule from Story 3.2.3's Context section. |
| S3.2.3 | `kmp/src/businessTest/kotlin/dev/stapler/stelekit/git/GitSyncServiceTest.kt` (extend) | `` `sync when push returns MergeConflict emits ConflictPending not generic Error, for both GitHub 409 and GitLab 400 shapes` `` | Integration | Real `GitSyncService.sync()` with a stub `GitRepository.push()` returning `MergeConflict` — proves Task 3.2.3b's new branch (which benefits GitHub's 409 case identically, per the plan's own note) actually fires instead of falling into `SyncState.Error`. |
| S3.2.3 | `WasmGitWriteServiceLiveTest.kt` (see Epic 6.3 below) | `liveGitLabStaleLastCommitIdRace` | Live/gated | Forces the real stale-`last_commit_id` race against a disposable GitLab project and asserts the *actual* response shape — confirms or corrects the `400`-status assumption in Story 3.2.3, per its own acceptance criteria. |

### Epic 3.3 — Conflict detection + auto-merge

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| S3.3.1 (SC5, SM5) | `WasmGitWriteServiceAlgorithmsTest.kt` | `` `TC-3.3.1-A partitionConflicts: zero-intersection local/remote path sets are fully non-overlapping` `` | Unit (happy) | `conflicting = emptySet()`. |
| S3.3.1 | same | `` `TC-3.3.1-B partitionConflicts: full-overlap and partial-overlap boundary cases` `` | Unit (error/edge) | Exercises both extremes plus the empty-set degenerate case named in the story. |
| S3.3.1 | `WasmGitWriteServiceMockedIntegrationTest.kt` | `IT-3.3.1-A` `mergeAndCommit() (GitHub) fetches remote content for non-overlapping paths via MockEngine raw-content GET, writes via applyRemoteContent (bypassing dirty-tracking), and stages a fresh PendingCommit.Staged layered on the new remote head | Integration | Verifies the auto-merge rebuild step end to end, including that `applyRemoteContent` does **not** re-mark the path dirty (would corrupt the "only push what changed" invariant). |
| S3.3.2 (SC5, SM4) | `WasmGitWriteServiceAlgorithmsTest.kt` | `` `TC-3.3.2-A buildConflictFiles maps each conflicting path to a ConflictFile with empty hunks` `` | Unit (happy) | Confirms Pattern Decision "Conflict representation." |
| S3.3.2 | same | `` `TC-3.3.2-B deleted-locally-but-edited-remotely: LOCAL resolution keeps it deleted (no-op), REMOTE resolution resurrects it via fetch+write` `` | Unit (error/edge) | The Task 3.3.2d edge case named explicitly in `research/features.md` §2a. |
| S3.3.2 | `WasmGitWriteServiceMockedIntegrationTest.kt` | `IT-3.3.2-A` `resolveConflictBySide(REMOTE) end-to-end via the unmodified GitSyncService + real WasmGitRepository — checkoutFile fetches over MockEngine and writes via the dirty-tracked path, commit() re-derives a fresh PendingCommit.Staged, and syncState ends at Idle (never Success — push is a separate later trigger)` | Integration | Directly verifies the transition `design/ux.md` Surface 2 criterion 3 flagged as **unverified in existing code paths** — this is the scripted two-writer-race verification Success Metric #4 explicitly asks for. |
| S3.3.2 | same file | `IT-3.3.2-B` **`scripted two-writer race: local edit to pages/Foo.md + a second, out-of-band API push to the same path before this attempt's push() — surfaces as ConflictPending, not a silent overwrite or a silent drop`** | Integration | This is the literal "scripted two-writer race in test" Success Metric #4 names — implemented against MockEngine (deterministic) here, and re-run against the real API in the gated live test (Task 3.2.3c) for GitLab specifically. |

### Epic 3.4 — Retry/backoff + rate-limit

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| S3.4.1 (SC7, SC8) | `WasmGitWriteServiceAlgorithmsTest.kt` | `` `TC-3.4.1-A retry double: 429 then 200 succeeds after one retry, honoring Retry-After via TestCoroutineScheduler` `` | Unit (happy) | Mirrors `WasmSectionSyncServiceTest`'s `TC-6.4-G` pattern exactly (same repo convention for wasmJs-only retry logic). |
| S3.4.1 | same | `` `TC-3.4.1-B retry double: 4 consecutive 429/403-with-Retry-After exhaust the budget and surface RateLimited(retryAfterSeconds), never PushFailed` `` | Unit (error/edge) | Exhaustion path — the DomainError variant that ultimately drives Story 1.3.3/5.1.2's UI. |
| S3.4.1 | `WasmGitWriteServiceMockedIntegrationTest.kt` | `IT-3.4.1-A` `the REAL configured HttpClient (HttpRequestRetry plugin) retries a 429-then-200 blob POST against MockEngine and commit() completes; a semaphore-bounded assertion confirms no more than 3 concurrent blob POSTs for an 8-file dirty set | Integration | The only place the actual Ktor plugin configuration (not a hand-rolled double) is exercised — catches config mistakes (e.g. wrong status codes wired into `retryIf`) a pure double can't catch. |

### Epic 3.5 — Observability (PAT-never-logged enforcement)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| S3.5.1 (Observability Requirements) | `WasmGitWriteServiceAlgorithmsTest.kt` | `` `TC-3.5.1-A success outcome log double contains outcome, file count, and commit sha` `` | Unit (happy) | Models the four named outcomes (`success`/`conflict-detected`/`auto-merged`/`failed-with-reason`) as a pure function of inputs → log-line content, so wording can be asserted without a real `Logger`. |
| S3.5.1 | same | `` `TC-3.5.1-B failure outcome log double names the failing step (e.g. step=ref-update) and the DomainError subtype, never the raw response body` `` | Unit (error/edge) | Ensures step-level diagnosability per the Observability Requirements. |
| S3.5.1 | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/git/PatLeakageAuditTest.kt` (new — turns Task 3.5.1b's manual grep into an enforced, automatic check, matching this repo's `MigrationRunnerSchemaSyncTest`-style structural-enforcement convention from `CLAUDE.md`) | `patLeakageAudit_noTokenVariableAppearsOutsideHeaderConstructionOrComments_underGitPackage` | Integration (whole-tree static scan, runs in `ciCheck`) | Reads every `.kt` file under `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/`, finds every line matching `token` (case-insensitive), and asserts each is one of: a parameter/property declaration, a header-construction call (`"Authorization" to "Bearer $token"` / `"PRIVATE-TOKEN" to token`), or a comment — fails the build if a `Logger.*`/`println`/`DomainError(...).message` call site interpolates it. This is the CI-enforced version of the acceptance criterion in Story 3.5.1 ("enforced by a repo-search task, not just review"). |

### Epic 4.1 — `WasmGitRepository` actual

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| S4.1.1 (SC2, SC6) | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/git/WasmGitRepositoryTest.kt` (new) | `IT-4.1.1-A` `isGitRepo returns true once a GitConfig exists; init/stageSubdir/removeStaleLockFile are no-op successes; hasDetachedHead is always false | Integration (happy — real class, no working tree concept to fake) | Since `WasmGitRepository` only exists on `wasmJsMain`, its own trivial-but-real behavior is exercised directly rather than doubled. |
| S4.1.1 | same | `IT-4.1.1-B` `clone() returns NotSupported("web") without making any network call | Integration (error/edge) | Asserts zero MockEngine requests recorded. |
| S4.1.1 | same | `IT-4.1.1-C` `log(config, maxCount) maps GitHub and GitLab commit-history responses to GitCommit identically via MockEngine | Integration | New small models added by Task 4.1.1e — first place they're actually exercised. |

### Epic 4.2 — `JsGitManager` delegate (BUG-005 close-out)

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| S4.2.1 (SC6, SM3) | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/JsGitManagerTest.kt` (new) | `IT-4.2.1-A` `with no GitConfig saved, all five JsGitManager methods keep returning the existing NOT_SUPPORTED error, byte-for-byte unchanged | Integration (happy — regression guard) | Explicit zero-regression assertion for BUG-005 Phase 1's existing behavior. |
| S4.2.1 | same | `IT-4.2.1-B` `with a configured GitConfig, JsGitManager.push() delegates to the SAME WasmGitWriteService.push() function WasmGitRepository.push() calls — not an independently re-implemented second path | Integration (error/edge on "two competing implementations") | Constructs both `JsGitManager` and `WasmGitRepository` sharing one `WasmGitWriteService` instance; asserts identical MockEngine call sequence from either entry point. |
| S4.2.1 | same | `IT-4.2.1-C` `isDirty() reflects PlatformFileSystem.dirtyFileCountFlow.value > 0 against a real dirty set | Integration | Real `PlatformFileSystem` instance, one `writeFile` call, `isDirty()` flips to `true`. |

### Epic 4.3 — `Main.kt`/`App.kt` wiring & `localChangesCountFlow`

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| S4.3.1 (SC2 — reachability) | *(no unit test — pure DI wiring with no branching logic to unit-test)* | — | — | Covered by UX Surface 1 Playwright tests (`SyncStatusBadge` actually renders on a real wasmJs build) rather than duplicated here — a wiring-only change has no meaningful unit-test surface; see `e2e/tests/git-sync-badge.spec.ts` below. |
| S4.3.2 (SC8, SM6) | `kmp/src/commonTest/kotlin/dev/stapler/stelekit/ui/StelekitViewModelSyncStateTest.kt` (new, or extend existing ViewModel test file if one exists — confirm before creating) | `` `TC-4.3.2-A syncState combine emits LocalChangesPending when raw state is Idle and count > 0` `` | Unit (happy) | Pure `combine` logic test with fakes for `activeGitSyncService`/`localChangesCountFlow`. |
| S4.3.2 | same | `` `TC-4.3.2-B syncState combine never overrides an in-progress state (Fetching/Merging/Pushing/Committing) with LocalChangesPending` `` | Unit (error/edge) | The exact non-interruption acceptance criterion from Story 4.3.2. |
| S4.3.2 | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/StelekitViewModelSyncStateIntegrationTest.kt` (new) | `IT-4.3.2-A` `with localChangesCountFlow == null (JVM/Android parity), syncState is byte-for-byte identical to the pre-feature activeGitSyncService.flatMapLatest output — zero LocalChangesPending emissions | Integration | Real `StelekitViewModel` + stub `GitSyncService` — the explicit **no-regression-to-JVM/Android** proof Success Metric #6 requires. |

### Epic 5.1 — `SyncStatusBadge` code-level state rendering

| Requirement | Test File | Test Name | Type | Scenario |
|---|---|---|---|---|
| S5.1.1 (SC8, SM3) | `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/SyncStatusBadgeTest.kt` (new — sibling of `TopBarTest.kt`, same `createComposeRule()` convention) | `syncStateBadge_rendersDistinctBadge_forLocalChangesPendingState` | Unit (happy) | `composeTestRule.setContent { SyncStateBadge(syncState = LocalChangesPending(2), ...) }`; asserts a node with `contentDescription` containing "unsynced" and label text `"2 unsynced"` is displayed, distinct from the `Idle`/`Error` renderings. |
| S5.1.1 | same | `syncStateBadge_tapInvokesOnSyncClick_forLocalChangesPendingState` | Unit (error/edge — "no dead end" check) | `performClick()` on the badge region invokes the passed `onSyncClick` lambda exactly once. |
| S5.1.2 (SC8) — **RateLimited "never tap-to-retry," UI layer** | same file | `syncStateBadge_neverContainsTapToRetryCopy_forRateLimitedState` | Unit (happy — the literal string-content acceptance criterion made concrete, per Task 5.1.2b) | Composes `RateLimited(5)`, reads the semantics tree's `contentDescription` + label text, asserts `!contains("tap to retry", ignoreCase = true)` **and** a neutral (non-`error`-color) tint is used — this is `design/ux.md` line 200's acceptance criterion as an executable test. |
| S5.1.2 | same | `syncStateBadge_tapIsNoOpAndDoesNotInvokeOnSyncClick_forRateLimitedState` | Unit (error/edge) | Confirms tapping the badge while `RateLimited` is active does **not** invoke `onSyncClick()` — the tap is inert, matching plan.md's corrected Story 5.1.2 (Task 5.1.2a has no `Modifier.clickable` on this branch at all): auto-backoff-retry runs regardless of taps, so a manual retry would just re-hit the same limit. Also asserts the no-op is scoped strictly to `RateLimited` — a later transition to another state restores normal tap behavior. |

No separate integration test for Epic 5.1 beyond the Compose semantics tree — the true cross-browser/accessibility verification is Playwright + manual (UX table below), since `ComposeTestRule` runs on JVM/Desktop Skiko, not the real wasmJs/Canvas renderer.

### Epic 6.1–6.2 (meta) — already itemized above
Every unit test in Epics 1–5's tables above **is** this plan's realization of `plan.md`'s Epic 6.1 (pure-Kotlin algorithm doubles) and Epic 6.2 (serialization round-trips). No separate rows are added here to avoid duplicating the same tests under two headings.

### Epic 6.3 — Gated live-API test (made concrete, per Step 5)

| Item | Value |
|---|---|
| Test file | `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/git/WasmGitWriteServiceLiveTest.kt` |
| Gate | Skipped unless `STELEKIT_LIVE_GIT_TEST=1` is set in the environment (checked as the first line of each `@Test`, matching this repo's existing skip-by-default convention for environment-gated tests — never fails CI, never counts against the gate). |
| Credentials | `STELEKIT_LIVE_GIT_TEST_PAT` env var only — never hardcoded, never committed, never interpolated into an assertion message or print statement (same rule as `PatLeakageAuditTest` above, applied manually here since this file never runs in CI). |
| Disposable target repos | GitHub: `tstapler/steno-wiki-livetest`, branch `livetest`. GitLab: an equivalent disposable project (name TBD — provisioning owned by Tyler per `plan.md`'s Unresolved Questions; blocks only this test, nothing else). |
| Reset mechanism | The **first action of every run**, before any assertion, force-resets the target branch to a fixed, hardcoded base SHA via `advanceRef(..., force = true)` (GitHub) / the equivalent GitLab ref reset — never relies on a previous run's cleanup having succeeded, so repeated manual triggers never accumulate unbounded commit history. |
| Test cases | `liveGitHubPushAndVerify` (SM1), `liveGitLabPushAndVerify` (SM2), `liveGitLabStaleLastCommitIdRace` (confirms/corrects Story 3.2.3's `400`-status assumption against the real API, per Task 3.2.3c). Each reads back the new ref SHA with a short retry for GitHub's eventual consistency (`research/pitfalls.md` §7), not a single immediate `GET`. |
| Run command | Documented in the test file's own KDoc header (no new `.md` file, per this repo's no-unrequested-docs convention): `STELEKIT_LIVE_GIT_TEST=1 STELEKIT_LIVE_GIT_TEST_PAT=*** ./gradlew :kmp:wasmJsBrowserTest --tests "*WasmGitWriteServiceLiveTest*"`. |
| CI relationship | Never runs in `bazel test //...` or `./gradlew ciCheck` — the env-var gate gives a hard `false` in both. Not scheduled automatically in this plan (no CI cron infrastructure named in Scope); run manually before a release that touches this code path, or on suspicion of upstream API drift. |

### Epic 7.1 — BUG-005 doc close-out
Not code — no unit/integration test applies. Verified by the acceptance criteria already in Story 7.1.1 (diff review: Fix Approach section no longer mentions isomorphic-git; bug moved to `docs/bugs/closed/` per the repo's existing convention). Recorded here as **N/A for automated testing**, confirmed manually during Phase 7 review.

---

## UX Acceptance Tests (22 criteria across 6 surfaces, per `design/ux.md`)

**Tooling note**: SteleKit web renders via Skiko/Compose onto a single `<canvas>` inside a shadow root attached to `document.body` (confirmed in `e2e/tests/demo.spec.ts`) — Playwright 1.27+ auto-pierces shadow roots for CSS selectors, but there is **no ordinary DOM accessibility tree** to query the way a normal web app has one; Compose's semantics tree (`contentDescription`, `liveRegion`, `role`) is exposed to assistive tech through Compose's own accessibility bridge, not free DOM `aria-*` attributes. Criteria about pixel/interaction state (badge appears, tap triggers sync, color/icon distinctness) are Playwright-testable via canvas pixel sampling / `page.evaluate` hooks into exposed test IDs. Criteria about screen-reader semantics (`contentDescription` text, `liveRegion`, contrast) are **not reliably Playwright-testable against the canvas renderer** and are flagged **Manual** (VoiceOver/NVDA + axe DevTools spot-check) below — this is a real tooling gap, not an oversight, and is called out explicitly rather than claiming false Playwright coverage.

### Surface 1 — Sync status badge (`LocalChangesPending`)

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| 1. Badge appears within one recomposition frame of a write, 0 extra steps | `e2e/tests/git-sync-badge.spec.ts` (new) | `UX-S1-1 badge becomes visible immediately after editing a page with git sync configured` | Playwright | Configure git sync via test fixture → navigate to a page → type in the editor → assert a badge-region locator (exposed via a stable test hook, e.g. `data-testid="sync-badge"` rendered into the canvas overlay or an off-canvas a11y node) becomes visible within a short `waitFor` timeout, no manual "check sync status" navigation. |
| 2. Visually distinct from `Idle`/`Error`/`ConflictPending`/`Success` by color AND icon shape | same | `UX-S1-2 LocalChangesPending badge differs in both pixel color and icon shape from Error and Success states` | Playwright (pixel sampling) | Drive the app through each `SyncState` via test fixtures/mocked responses; screenshot-diff the badge region against reference crops for each state — asserts redundant coding (not color-only). |
| 3. Tap badge or `⟳` triggers sync in exactly 1 click | same | `UX-S1-3 tapping the LocalChangesPending badge triggers exactly one sync attempt` | Playwright | Click the badge region; assert exactly one sync-attempt network call (MockEngine/mock server) fires, matching every other actionable state's click count. |
| 4. No dead end — always has the tap-to-sync exit path | same | `UX-S1-4 LocalChangesPending is never rendered without a clickable sync affordance` | Playwright | For every `LocalChangesPending(n)` fixture (n = 1, 2, 100), the badge region is clickable and dispatches the sync handler. |
| 5. Accessibility: `contentDescription` states count + action; `role=Button`; contrast ≥4.5:1 both themes | *(no automated file — see tooling note)* | `UX-S1-5 (manual) LocalChangesPending region announces "N unsynced changes — tap to sync", is keyboard/screen-reader reachable as a button, and passes 4.5:1 contrast in light and dark theme` | Manual (VoiceOver/NVDA walkthrough + axe DevTools contrast check, both themes) | Toggle theme, tab to the badge, confirm screen-reader announcement text and `Role.Button` semantics; run axe's contrast checker against `onSurfaceVariant` at `labelSmall` size in both themes (explicitly flagged in `design/ux.md` as *not* to be assumed passing). |
| 6. Live-region announcement of `Idle → LocalChangesPending → Committing → Pushing → Success/Error` | *(no automated file — see tooling note)* | `UX-S1-6 (manual) each sync-state transition is announced to a screen reader without requiring the user to re-focus the region` | Manual (VoiceOver/NVDA session) | Drive a full edit→sync cycle with a screen reader active; confirm each transition is spoken once, unprompted. |

### Surface 2 — Conflict resolution screen (git-merge conflicts on web)

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| 1. Resolve a single-file conflict in 2 clicks (1 selection + 1 "Finish Merge"); N files → N+1 | `e2e/tests/git-conflict-resolution.spec.ts` (new) | `UX-S2-1 single-file conflict resolves in exactly 2 clicks; a 3-file conflict resolves in exactly 4 with all defaults accepted` | Playwright | Force a `ConflictPending` state via mocked compare/push responses; count clicks to a successful "Finish Merge". |
| 2. "Abort merge" reachable at every point, with an exact-consequence confirmation dialog | same | `UX-S2-2 Abort merge is reachable mid-resolution and its dialog states local-preserved/remote-discarded before committing` | Playwright | Open the screen, trigger abort before selecting any file, assert dialog text matches the exact copy from `design/ux.md`. |
| 3. After "Finish Merge," badge does NOT show `Idle`/`Success` until the merge commit is actually pushed | same (cross-references `IT-3.3.2-A` above, which verifies the underlying `syncState` value — this row verifies the **visible** consequence) | `UX-S2-3 badge shows LocalChangesPending, not Idle or Success, immediately after Finish Merge completes` | Playwright | Complete a merge resolution; assert the badge region is NOT rendering the `Idle`(blank)/`Success`(green check) treatment on the next frame. |
| 4. Error state during resolution is specific/actionable; PAT-expiry routes to "tap to re-connect," not a raw exception string | same | `UX-S2-4 a PAT-expiry failure during Finish Merge shows re-connect framing, not a bare exception string` | Playwright | Mock a 401 on the follow-up `commit()` call during resolution; assert the error text matches the `CredentialExpired`-style copy, not raw exception text. |
| 5. Accessibility unchanged (reused screen) — decorative icon `contentDescription=null`, `FilterChip` selection semantics intact | *(no automated file)* | `UX-S2-5 (manual) reused conflict screen's existing accessibility semantics show no regression on the web trigger path` | Manual (spot-check only — the screen itself is unmodified per `design/ux.md`, so this is a regression check, not new coverage) | Open the screen via the new web-triggered path; confirm no different behavior from the already-shipped desktop path. |

### Surface 4 — Auto-merge disclosure (logging only, this project)

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| 1. Auto-merge outcomes appear in console/log output with affected file list, never silently dropped | *(covered by `TC-3.5.1-A`/`TC-3.5.1-B` above — no separate UX-layer test needed since there is no UI surface this project ships for this criterion)* | — | Developer-verifiable (console) | N/A as a UX/Playwright test — `design/ux.md` itself states this is verifiable by a developer, not an end user, for this project's scope. |
| 2. (Fast-follow, recorded not required this project) | — | — | — | Not committed — no test designed; `design/ux.md` explicitly defers this. |

### Surface 5 — Rate-limit / partial-failure / offline error copy

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| 1. `RateLimited` badge text never contains "tap to retry" / implies manual action is useful | `e2e/tests/git-sync-badge.spec.ts` | `UX-S5-1 RateLimited badge text never contains tap-to-retry language, verified against the real rendered canvas overlay` | Playwright (cross-references `syncStateBadge_neverContainsTapToRetryCopy_forRateLimitedState` above, which is the JVM-Compose-layer version of the identical assertion — this row is the real-browser confirmation) | Force `RateLimited` via mocked 429 exhaustion; read the exposed badge text/description; literal substring check. |
| 2. Partial-failure copy contains "nothing was changed on GitHub" (or host-neutral "on the remote"), no hedging language | same | `UX-S5-2 partial-failure copy states unambiguously that nothing changed on the remote, with no hedging words` | Playwright | Force a failure between commit-object-creation and ref-update (MockEngine drops the PATCH); assert exact reassurance phrase present, `"may have"`/`"possibly"` absent. |
| 3. No dead end: every row has an automatic-resolution or explicit-retry path except the documented `FileTooLarge` exception | same | `UX-S5-3 every Error-family state has a working tap target except FileTooLarge, which is a documented no-destructive-action case` | Playwright | Table-driven: for `RateLimited`/`Offline`/`PushFailed`/`CredentialExpired`, tap does something (or nothing harmful, for auto-resolving states); for `FileTooLarge`, tap causes no destructive action. |
| 4. Copy is host-neutral by construction — no hardcoded "GitHub" where a GitLab user could see it | same | `UX-S5-4 RateLimited/FileTooLarge/partial-failure copy contains no hardcoded GitHub-only wording` | Playwright + static string check | Run the same three scenarios against a GitLab-configured fixture; assert copy doesn't literally say "GitHub" for these three states specifically. |
| 5. Color contrast of the (unchanged) red `Error` treatment | *(no new test — `design/ux.md` explicitly states this is presumed already-passing, unchanged layout)* | — | — | Not re-verified per `design/ux.md`'s own scoping — only new copy strings are added to an existing, already-shipped visual treatment. |

### Surface 6 — "Don't close this tab" warning (recommended, not committed — flagged per `design/ux.md`'s own caveat)

| UX Criterion | Test File | Test Name | Tool | Steps |
|---|---|---|---|---|
| 1. `beforeunload` fires when `dirtyFileCountFlow.value > 0`, not when it's `0` | `e2e/tests/git-tab-close-warning.spec.ts` (new — **only build if product confirms this recommended surface is in scope, per `design/ux.md`'s explicit note that Surfaces 3/4-visual/6 are not committed Stories**) | `UX-S6-1 beforeunload confirmation fires only while there are unsynced changes` | Playwright | `page.on('dialog', ...)`-equivalent for `beforeunload` (Playwright's `page.evaluate` to install a listener that records `preventDefault`/return-value calls, since native `beforeunload` dialogs aren't directly interceptable cross-browser) — toggle the dirty flow value via a test hook and assert the listener's behavior flips accordingly. |
| 2. Gated on `PlatformFileSystem.dirtyFileCountFlow` directly — no second, driftable "hasUnsavedChanges" boolean | *(static/code review, not Playwright)* | `UX-S6-2 (structural) no second dirty-tracking boolean exists in the implementation` | Grep/code-review check (mirrors the `PatLeakageAuditTest` structural-enforcement pattern) — `grep -rn "hasUnsavedChanges" kmp/src/wasmJsMain/` expected to return zero matches outside the one canonical flow. | Add as a one-line assertion inside a small structural test, same style as `PatLeakageAuditTest`, if this surface is picked up. |
| 3. Reload after forced close restores `LocalChangesPending(N)` within one page-load cycle | `e2e/tests/git-tab-close-warning.spec.ts` | `UX-S6-3 reloading after a forced close with pending changes restores the correct unsynced count` | Playwright | Cross-references `IT-2.1.2-B` (the underlying `preload()` restore) — this row is the **visible** badge-count confirmation after a real page reload. |
| 4. Backstop only — Surface 1's badge must already have shown unsynced state before any close attempt | *(design-level criterion, not independently automatable beyond 1 and 3 above)* | — | — | Satisfied by construction once `UX-S1-1` and `UX-S6-1` both pass — no separate test needed. |

**Note**: Per `design/ux.md`'s own summary table, Surface 6 in its entirety (all 4 criteria) is a **recommended, not committed** acceptance item — build the `git-tab-close-warning.spec.ts` file only if product/planning picks this up before Phase 7 close-out, per the plan's Unresolved Questions framing. Listed here so the test design exists either way, not forced into the committed count.

---

## Test Stack

- **Unit** (deterministic, CI-blocking, no I/O): Kotlin `kotlin.test` under `commonTest` (pure `commonMain` logic: `GitHostAdapter`, serialization models, `DomainError`/`SyncState` variants) and `commonTest` pure-Kotlin algorithm doubles for `wasmJsMain`-only orchestration logic (`WasmGitWriteServiceAlgorithmsTest.kt`, `DirtyTrackingAlgorithmsTest.kt`), following the exact precedent already established by `WasmSectionSyncServiceTest.kt` — reimplementing the production branching as a double when the real class can't run under the JVM test runner.
- **Integration** (real collaborators, still CI-blocking): `businessTest` for anything touching `GitSyncService` + real `EditLock`/`GraphLoader`/`GraphWriter` with a stubbed `GitRepository` (matches `GitSyncServiceTest.kt`'s existing convention exactly); `jvmTest` `ComposeTestRule`-based tests for `SyncStatusBadge` (matches `TopBarTest.kt`'s convention); and — **new to this plan, not explicitly itemized in `plan.md`'s Epic 6** — `wasmJsTest` integration tests that exercise the **real** `WasmGitWriteService`/`WasmGitRepository`/`PlatformFileSystem`/`GitWriteLock` classes (not doubles) in a real headless-Chrome runtime (the existing `wasmJsTest` Karma-based runner already proven by `WasmBenchmarkTest.kt`), using:
  - **Real OPFS** (`navigator.storage.getDirectory()`) for dirty-set checkpoint tests — genuinely available in headless Chrome, so this is real integration coverage, not simulated.
  - **Real `navigator.locks.request()`** for `GitWriteLock` tests — same reasoning.
  - **Ktor `MockEngine`** (`io.ktor:ktor-client-mock:3.1.3`, already a dependency in `jvmTest` for `UrlFetcherJvm` per `kmp/build.gradle.kts:199` — **recommend adding it to `wasmJsTest`'s dependency block too**, since it is a pure-Kotlin multiplatform artifact with no native/JS-only constraint) for all GitHub/GitLab HTTP orchestration tests, so the real Ktor client configuration (retry plugin, JSON content negotiation) is exercised, not reimplemented as a double.
  - **Rationale for adding this layer**: `plan.md`'s Epic 6.1 (pure doubles) proves the *algorithms* are correct but never actually invokes the real `HttpClient`/OPFS/Locks wiring; Epic 6.3 (the gated live test) is the only place that happens today, and it's explicitly non-CI-blocking. Without a `wasmJsTest` MockEngine layer, a wiring bug (wrong URL template, wrong header name typo, forgotten `await()`) would only be caught by a human manually running the gated live test — this closes that gap while staying fully mocked/deterministic/CI-safe.
- **E2E / UX**: Playwright (`e2e/` — existing project, `@playwright/test`, chromium-only per `e2e/playwright.config.ts`), extending the existing `demo.spec.ts`/`benchmark.spec.ts` pattern with new spec files for the sync badge, conflict screen, and (if picked up) tab-close warning. Screen-reader/contrast criteria that the canvas-based renderer makes unreliable to automate are explicitly flagged **Manual** rather than falsely claimed as Playwright-covered.
- **Live/gated**: One manually-triggered `wasmJsTest` suite (`WasmGitWriteServiceLiveTest.kt`) against disposable GitHub/GitLab repos, gated on `STELEKIT_LIVE_GIT_TEST=1`, force-resetting its target branch on every run. Never runs in `bazel test //...` / `./gradlew ciCheck`.

## Coverage Targets and How to Measure

| Stack | Coverage command | Target |
|---|---|---|
| Kotlin/JVM (commonTest, businessTest, jvmTest) | `./gradlew jacocoTestReport` → check `kmp/build/reports/jacoco/` | ≥80% line coverage on all new/changed files under `git/` (commonMain), `platform/PlatformFileSystem.kt` dirty-tracking additions, and `ui/components/SyncStatusBadge.kt`'s new branches |
| Kotlin/Wasm (`wasmJsTest`) | No Jacoco agent support on `wasmJs` — track via the Requirement→Test Mapping table above as the coverage record instead (every `WasmGitWriteService`/`WasmGitRepository`/`GitWriteLock`/`PlatformFileSystem` public method appears in at least one `IT-*` row) | 100% of public methods on the 4 named classes have ≥1 integration test |
| Playwright (`e2e/`) | `npm --prefix e2e test` (existing script) | Every **committed** UX acceptance criterion (Surfaces 1, 2, 5 — 16 criteria) has a passing spec or an explicit `Manual` designation with a documented walkthrough; Surface 6 build-gated on product sign-off |
| Live/gated | Manual run before any release touching this code path; not part of a coverage percentage | 3 test cases (`liveGitHubPushAndVerify`, `liveGitLabPushAndVerify`, `liveGitLabStaleLastCommitIdRace`) all pass against the disposable repos |

- All public service methods (`WasmGitWriteService`, `WasmGitRepository`, `GitHostAdapter`, `GitWriteLock`, `JsGitManager`): happy path + error path covered — confirmed row-by-row in the Requirement → Test Mapping table above.
- All external integrations (GitHub Git Data API, GitHub compare/commits API, GitLab commits/compare API): unit-tested via doubles **and** integration-tested via `wasmJsTest` MockEngine **and** the gated live test — three layers, per the task's explicit ask ("at least one integration test... GitHub/GitLab API calls count").
- UX acceptance criteria: all 22 from `design/ux.md` have a corresponding test or an explicit `Manual`/`N/A` designation above — none silently unaddressed.
- Trickiest pieces (explicitly re-verified here):
  - `PendingCommit` resume/cleanup across a simulated browser close — `IT-2.2.1-A`.
  - Web Lock's exact covered/not-covered boundary — `IT-2.3.1-A` (covered) plus `IT-2.3.1-B/C/D` (three distinct known-gap characterization tests, so the narrowed scope is enforced, not just documented in prose).
  - GitLab conflict detection at both `merge()`-time (Story 3.2.2) and `push()`-time (Story 3.2.3) — `TC-3.2.2-*`/`IT-3.2.2-A` and `TC-3.2.3-*`/`IT-3.2.3-A`/live race test.
  - `SyncState.RateLimited` never-tap-to-retry — enforced at **three** layers: `DomainError.toSyncErrorMessage()` (`rate_limited_message_never_suggests_manual_retry`), the Compose semantics tree (`syncStateBadge_neverContainsTapToRetryCopy_forRateLimitedState`), and the real rendered browser (`UX-S5-1`).
  - Gated live-API test strategy — fully concretized above (file, gate, disposable-repo reset mechanism, run command).

## Migration Plan test
`plan.md`'s own Migration Plan section states: **"None — no SQLDelight schema changes. The dirty-set marker is a plain JSON file in OPFS (`.stele-dirty-set.json`), not a database table."** Confirmed by re-reading `requirements.md` (no schema changes named anywhere in Scope) and `plan.md` §"Migration Plan." Per Step 5's instruction, **no `migration_should_be_reversible` test is designed** — forcing one here would test a migration that does not exist. The closest analogous guarantee (an old/malformed `.stele-dirty-set.json` from a future schema version must degrade safely) is already covered by `IT-2.1.2-C` (`preload() with a malformed or absent marker starts empty and does not throw`), which is the correct test for a JSON-checkpoint file's forward-compatibility, not a database migration test.
