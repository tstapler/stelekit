# Implementation Plan: web-git-writeback

**Feature**: Implement the WASM write-back half of git sync (`WasmGitWriteService` + a `wasmJsMain` `GitRepository` actual) so web users' edits actually reach their configured GitHub/GitLab remote, closing BUG-005 Phase 2 per ADR-013/ADR-015.
**Date**: 2026-07-14
**Status**: Ready for implementation
**ADRs**: [ADR-017: WASM Git Write-Back Reachability via a `GitRepository` Actual (Amends ADR-015)](../decisions/ADR-017-wasm-git-repository-actual-reachability.md) — the reachability/integration-shape deviation from ADR-015's literal text (no common `GitWriteService` interface; `GitRepository` actual is the primary path; `JsGitManager` becomes a thin secondary delegate) is a genuine architectural amendment and gets its own ADR per the requirements' Constraints ("any deviation requires a new ADR explicitly superseding ADR-015, not a silent re-litigation"). `GitHostAdapter` placement and the `SyncState`/`GitError` additions are ordinary implementation decisions, captured in this plan's Pattern Decisions table instead.

---

## 0. Corrections and resolutions carried into this plan (read first)

This plan is downstream of `requirements.md`'s two corrected findings and adds one more, found while cross-referencing `research/architecture.md` against `research/features.md` and the actual `ConflictResolutionScreen.kt` / `GitSyncService.kt` source:

1. **Conflict type** (already corrected in requirements.md): the target is `DomainError.GitError.MergeConflict` + `SyncState.ConflictPending(List<ConflictFile>)` + `GitSyncService.resolveConflictBySide(Map<String, MergeSide>)` — never `DomainError.ConflictError.DiskConflict`.
2. **Reachability** (already corrected in requirements.md): `GitManager`/`JsGitManager` has zero UI call sites. The primary deliverable is a new `wasmJsMain` `GitRepository` actual wired into the *unmodified* `GitSyncService`/`App.kt`/`Main.kt` object graph. `JsGitManager` is wired too (cheap, closes BUG-005's literal text) but is secondary.
3. **New finding, resolved here**: `research/architecture.md` §0.2 and `research/features.md` §1 disagree about whether `WasmGitWriteService` must compute real line-level `ConflictHunk`s (a 3-way text diff) for the overlapping-file case. `research/features.md` traced the actual consumer (`ConflictResolutionScreen.kt` + `GitSyncService.resolveConflictBySide`) and confirmed `ConflictHunk.localLines`/`remoteLines` are **never read** — the screen only displays `filePath`/`wikiRelativePath` and drives a whole-file `MergeSide` (LOCAL/REMOTE) picker. This plan follows `features.md`'s verified conclusion: `ConflictFile.hunks` stays `emptyList()` for all web write-back conflicts. No 3-way diff engine is built. See Pattern Decision "Conflict representation" below.

---

## Domain Glossary

| Term | Definition | Notes |
|------|-----------|-------|
| `WasmGitWriteService` | wasmJs orchestration engine implementing ADR-015: reads the dirty set, dispatches to the GitHub 5-step or GitLab 1-call sequence, partitions conflicts, acquires the Web Lock around `push()`'s write-critical-section only (see `GitWriteLock` and Pattern Decision "Web Lock scope" for the exact scope and its limits), and reports outcomes. | `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt` |
| `WasmGitRepository` | wasmJsMain `actual` of the commonMain `GitRepository` interface. The reachability path — wired into unmodified `GitSyncService`. Internally delegates all network/OPFS work to `WasmGitWriteService`. | `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitRepository.kt` |
| `GitHostAdapter` | Pure-function object: `detect(remoteUrl): GitHostType`, `authHeader(type, token): Pair<String,String>`, `apiBase(type, owner, repo): String`. No I/O, no wasmJs dependency — lives in `commonMain` so it's directly unit-testable. | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitHostAdapter.kt` |
| `GitHostType` | `enum class { GITHUB, GITLAB, UNSUPPORTED }` | |
| `GitHostConfig` | `data class(type, owner, repo, branch, token, apiBase)` — resolved once per write-back attempt from `GitConfig` + `GitHostAdapter.detect`. | |
| `DirtyOp` | `enum class { WRITE, DELETE }` — which kind of change a dirty-set entry represents. | |
| `DirtyEntry` | `data class(op: DirtyOp, updatedAtMillis: Long)` — one dirty-set map value. | |
| `DirtySetMarker` | `@Serializable data class(version, graphId, baseSha, pendingCommit: PendingCommit, checkpointedAtMillis, dirtyFiles: Map<String, DirtyEntry>)` — persisted as `.stele-dirty-set.json` in OPFS. | See §4 schema below |
| `PendingCommit` | `@Serializable sealed interface { data object None; data class Staged(val commitSha: String, val treeSha: String) }` — replaces the earlier independently-nullable `pendingCommitSha`/`pendingTreeSha` pair (illegal "one set, one null" state was representable) with a single field that can only be "nothing staged" or "both SHAs staged together." Holds the GitHub commit/tree object SHAs created by `commit()` (steps 1–4) but not yet pointed at by the branch ref — set aside as `Staged` until `push()` (step 6) succeeds, then reset to `None`. | GitHub path only; GitLab's `commit()` is a local-only no-op so its `pendingCommit` stays `None` (see Pattern Decision "Commit/push split"). Lives in `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/DirtySetMarker.kt` alongside `DirtyOp`/`DirtyEntry`. |
| `GitBlobRequest` / `GitBlobResponse` | GitHub `POST git/blobs` request/response models. | `commonMain` `git/model/GitDataApiModels.kt` |
| `GitTreeEntry` / `GitTreeRequest` / `GitTreeResponse` | GitHub `POST git/trees` request/response models. | ″ |
| `GitCommitAuthor` / `GitCommitRequest` / `GitCommitResponse` | GitHub `POST git/commits` request/response models. | ″ |
| `GitRefResponse` | GitHub `GET`/`PATCH git/refs/heads/{branch}` response (`object.sha`). | ″ |
| `GitCompareResponse` / `GitCompareFile` | GitHub `GET compare/{base}...{head}` response (`ahead_by`, `files[].filename`). | ″ |
| `GitLabCommitAction` | GitLab commits-API action entry (`action`, `file_path`, `content`, `encoding`, `last_commit_id`). | `commonMain` `git/model/GitLabCommitModels.kt` |
| `GitLabCommitRequest` / `GitLabCommitResponse` | GitLab `POST projects/:id/repository/commits` request/response. | ″ |
| `GitWriteLock` | wasmJs wrapper around `navigator.locks.request()` — one exclusive lock per remote, held **only** around `push()`'s write-critical-section (the final ref-update PATCH on GitHub, or the single commits POST on GitLab) plus the `clearDirtySet` checkpoint write that immediately follows a successful push. **Not** held across `commit()`/`fetch()`/`merge()` — see Pattern Decision "Web Lock scope" and the "Web Lock — guarantees and known gaps" note under Epic 2.3 for why, and exactly what races this does/doesn't close. | `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/GitWriteLock.kt` |
| `SyncState.LocalChangesPending(fileCount: Int)` | New sealed `SyncState` variant: OPFS dirty set is non-empty and no sync operation is currently in progress. | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/SyncState.kt` |
| `DomainError.GitError.RateLimited(retryAfterSeconds: Int?)` | New `GitError` variant: primary or secondary rate limit hit; auto-retry-eligible (not a manual-retry error). | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt` |
| `SyncState.RateLimited(retryAfterSeconds: Int?)` | New sealed `SyncState` variant — the UI-facing counterpart to `DomainError.GitError.RateLimited`. requirements.md's Scope names both types together ("a `RateLimited` `SyncState`/`GitError` variant"); without this, a rate-limited push falls back to the generic `SyncState.Error`, which `SyncStatusBadge` renders as a red "tap to retry" — exactly the alarming, wrong-action UI `design/ux.md`'s highest-risk-string finding (line 200) and Story 1.3.1's own rationale warn against. `GitSyncService` routes `GitError.RateLimited` into this variant the same way it already routes `AuthFailed`+`GITHUB_OAUTH` into `CredentialExpired` (see Story 1.3.3). | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/SyncState.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt` |
| `DomainError.GitError.FileTooLarge(path, sizeBytes, maxBytes)` | New `GitError` variant: a dirty file (e.g. an encrypted `.md.stek` blob) exceeds the host's practical size ceiling (~75 MB GitHub base64-inflated, GitLab's request-body cap). | ″ |
| `DomainError.GitError.NetworkFailure(message: String)` | New `GitError` variant: a request-level failure where no HTTP response was ever received (connection refused, DNS failure, offline mid-request, CORS block) — distinct from a received-but-erroring HTTP response (`PushFailed`/`CommitFailed`/`FetchFailed`) and from `RateLimited` (which requires an actual `429`/`403` response). requirements.md's Scope explicitly requires "distinct `Either<DomainError, T>` failure variants for the write path's fetch layer (network / HTTP-status / rate-limit)" — this is the "network" variant that was missing. | Story 1.3.1, mapped in Task 3.4.1d |
| `localChangesCountFlow` | New optional `StateFlow<Int>?` (default `null`) threaded `Main.kt` (wasmJs) → `StelekitApp` → `GraphContent` → `StelekitViewModelDependencies` → `StelekitViewModel.syncState`. Null on JVM/Android/iOS — zero behavior change there. | |
| `ConflictFile` (existing, reused as-is) | `hunks` stays `emptyList()` for every web write-back conflict — see §0.3. | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/ConflictModels.kt` |
| `MergeSide` (existing, reused as-is) | `LOCAL` / `REMOTE` — drives `WasmGitRepository.checkoutFile`. | `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitRepository.kt` |
| overlapping / non-overlapping files | File-path-level `Set` intersection (conflict) vs. symmetric difference (auto-mergeable) between the local dirty-path set and the GitHub/GitLab compare-delta changed-path set. Content is never diffed. | Pattern Decision "Auto-merge granularity" |
| `PlatformFileSystem.dirtyFileCountFlow` | New public `StateFlow<Int>` on the wasmJs `PlatformFileSystem` actual — `dirtySet.size`, in-memory only, zero I/O. Source for `localChangesCountFlow`. | |
| `.stele-dirty-set.json` | OPFS checkpoint path: `/stelekit/{graphId}/.stele-dirty-set.json`, mirrors ADR-013's `.stele-sections-sync-complete` commit-flag-last pattern. Written via an immediate, coalesce-while-busy scheduler, **not** a fixed-delay debounce (corrected in Task 2.1.2b per pre-mortem P1 #1 — a fixed idle-debounce could leave a crash-loss window open for as long as the user kept actively editing). | §4 |

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Overall reachability structure | New `wasmJsMain` `GitRepository` actual (`WasmGitRepository`) wired into **unmodified** `GitSyncService`/`App.kt`/`Main.kt`; `JsGitManager` becomes a thin delegate to the same underlying `WasmGitWriteService` (PoEAA Adapter) | `research/features.md` §0 | (a) Implement only `WasmGitWriteService` behind `JsGitManager`/`GitManager` per ADR-015's literal text. (b) Build both independently ("dual-track"), each with its own dirty-set/conflict logic. | (a) ships to a UI dead end — `GitManager` has zero call sites, confirmed by grep. (b) doubles the surface area (two independent dirty-set readers, two conflict detectors) for a Large-but-bounded appetite and invites the two paths to silently disagree over time. |
| `WasmGitWriteService` internal structure | One orchestrator class owning dirty-set read, `GitWriteLock` acquisition, and conflict partition (host-agnostic per ADR-015), delegating the host-specific wire sequence to two private functions (`pushViaGitHub`, `pushViaGitLab`) in the same file | `research/build-vs-buy.md` §4, `research/architecture.md` §3 | Split into two full collaborator classes (`GitHubWriteExecutor`, `GitLabWriteExecutor`) behind a facade (GoF Strategy) | Only 2 hosts, fixed at compile time — Strategy's benefit (runtime-swappable, open for extension) doesn't materialize; the genuinely shared/complex logic (conflict partition) would still need a third home, adding indirection for ~400–600 total lines. |
| `GitHostAdapter` shape and location | Small `commonMain` object: host detection + auth header + API base URL only, no fetch/response mechanics (PoEAA thin Adapter) | `research/architecture.md` §3 (explicitly left the commonMain-vs-wasmJs placement open) | A unified `GitHostClient` abstraction spanning both fetch mechanics and host detection | Read fetches are single GETs; write is a multi-step JSON POST/PATCH sequence with response parsing and a different atomicity story — forcing both through one interface produces the "one interface, two callers each ignoring half its surface" anti-pattern the requirements' Rabbit Holes section warns against. |
| Serialization models location | `commonMain` `git/model/GitDataApiModels.kt` / `GitLabCommitModels.kt` — pure `@Serializable` data classes, no wasmJs dependency | `research/build-vs-buy.md` §3 (matches `GitHubDeviceFlowClient.kt` + `git/model/GitHubDeviceFlow.kt` precedent, also `commonMain`) | Keep models in `wasmJsMain` alongside `WasmGitWriteService` | Models have zero JS-interop dependency; placing them in `commonMain` makes them directly unit-testable via real (not reimplemented-as-doubles) serialization round-trip tests in `commonTest`, exactly like `GitHubDeviceFlow.kt` already is. |
| Host dispatch mechanism | Plain `when (hostType)` inside `WasmGitWriteService.pushDirtySet()` | `research/architecture.md` §3 | GoF Strategy: `GitWriteStrategy` interface with `GitHubWriteStrategy`/`GitLabWriteStrategy` implementations | Same reasoning as internal-structure decision above — 2 fixed cases, no runtime polymorphism need. |
| Conflict representation | Reuse existing `ConflictFile(filePath, wikiRelativePath, hunks = emptyList())` — no diff engine | `research/features.md` §1 (verified against `ConflictResolutionScreen.kt` source) | Compute real line-level `ConflictHunk`s via a 3-way text diff, as `research/architecture.md` §0.2 initially proposed | `ConflictResolutionScreen.kt` only renders `filePath`/`wikiRelativePath` and calls `resolveConflictBySide(Map<String,MergeSide>)` — `hunks` is read by zero UI consumers. Building a diff engine would be substantial dead-weight code, violating YAGNI. See §0.3. |
| Commit/push split (GitHub) | `commit()` performs Git Data API steps 1–4 (blobs → base tree → new tree → commit object) against the *local* `baseSha`, storing a `PendingCommit.Staged(commitSha, treeSha)` value in the marker; `push()` performs step 6 (ref PATCH) and treats 409/422 as a second, authoritative conflict signal | `research/stack.md` §1 (step 6 note) | Fuse commit+push into one call, made `commit()` a no-op | `GitSyncService.sync()` calls `commit()` and `push()` as separate steps with `fetch()`/`merge()` in between — collapsing them would force conflict-detection to happen inside `commit()` (wrong order) or make `push()` redo blob/tree/commit work. |
| Commit/push split (GitLab) | `commit()` is a local-only no-op (validates there is something to commit, stages nothing server-side); the entire GitLab single-call API request happens inside `push()` | `research/stack.md` §2 | Mirror the GitHub split exactly | GitLab's `POST .../repository/commits` has no server-side "create commit object without moving the branch" primitive — atomic by design, so there is nothing to stage in a separate `commit()` call. |
| Dirty-tracking hook placement | Entirely inside `PlatformFileSystem.wasmJs.kt`'s `writeFile`/`writeFileBytes`/`deleteFile` actuals (`writeFileBytes` did not exist on wasmJs at all before this plan — see Story 2.1.1's grounding note — this plan adds it, since without it there is nothing to hook the dirty-tracking call onto); a narrow `getDirtySnapshot()`/`clearDirtySet(newBaseSha)` API is exposed for `WasmGitWriteService` to call, but the dirty map itself stays a single source of truth inside `PlatformFileSystem` | `research/architecture.md` §2 | Reuse/extend `FileSystem.markDirty` | `markDirty`'s existing Android-SAF contract means "I already wrote this, suppress the real write" — the opposite of "the write already happened, remember it for git." Conflating them would corrupt Android's write-behind semantics. |
| Retry/backoff | Ktor `HttpRequestRetry` plugin (`exponentialDelay`, `respectRetryAfterHeader`, plus a `retryIf` clause for GitHub's 403-with-`Retry-After` secondary rate limit) | `research/build-vs-buy.md` §5, `research/pitfalls.md` §1 | Hand-rolled recursive retry matching `WasmSectionSyncService.githubFetch` | Already transitively on the classpath; composes with Ktor's pipeline instead of re-parsing raw `Response` objects by hand across 6 new endpoints. |
| JSON modeling | `kotlinx.serialization` `@Serializable` data classes with `ignoreUnknownKeys = true` | `research/build-vs-buy.md` §3 | Manual string-scanning matching `WasmSectionSyncService.extractTreePaths` | Nested tree/commit/actions payloads are too complex for reliable string scanning; matches the already-established `GitHubDeviceFlowClient.kt` convention. |
| HTTP transport | Ktor `HttpClient` (wasmJs/js engine, `ktor-client-js` already on classpath), matching `GitHubDeviceFlowClient.kt` | `research/stack.md` §4, `research/build-vs-buy.md` (codebase context) | Raw `fetch()` via `js()` interop, matching `WasmSectionSyncService.kt` | `WasmSectionSyncService.kt`'s pattern predates `ktor-client-js` being added to `wasmJsMain` (per `build.gradle.kts:210-214`'s own comment) and is a stopgap, not the current convention; `HttpRequestRetry`/content-negotiation only compose naturally through Ktor's client. |
| Auto-merge granularity | File-path `Set` operations (`intersect`/`subtract`) only — never content-level | ADR-015 (reconfirmed by `research/build-vs-buy.md` §4) | Content-level / byte-level 3-way merge | Settled by ADR-015; a content-level merge could silently combine two edits to the same file that happen not to byte-conflict — worse UX than asking. |
| Web Lock scope | One exclusive lock per remote (`navigator.locks.request("stele-write-${urlSafeRemote}")`), held **only** around `push()`'s write-critical-section — the final ref-update PATCH (GitHub) or the single commits POST (GitLab) — plus the immediately-following `clearDirtySet` checkpoint write. **Not** held across `commit()`/`fetch()`/`merge()`. Corrected during adversarial review: a lock spanning the full dirty-set-read → push → checkpoint-clear sequence is unachievable as originally specced, because `GitSyncService.sync()` stays unmodified (ADR-017) and calls `commit()`/`fetch()`/`merge()`/`push()` as 4 independent top-level suspend calls on `GitRepository` — a `navigator.locks.request()` callback is necessarily scoped to the single block passed to it, so it cannot span 4 separately-invoked calls without an explicit acquire-now/release-later handle this plan deliberately does not build (see Alternative Rejected). See the "Web Lock — guarantees and known gaps" note under Epic 2.3 for the exact race coverage. | `research/pitfalls.md` §4; scope corrected by adversarial review against the confirmed `GitSyncService.sync()` call structure (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`) | (a) An explicit lock-handle-holding mechanism spanning `commit()`→`push()`: `navigator.locks.request()`'s callback returns a `Promise` that is deliberately never resolved by the callback itself, but by a `resolve` function captured and exposed to Kotlin, so the lock stays held until something outside the callback calls `release()` — a real, well-known Web Locks idiom. (b) No locking at all, relying solely on GitHub/GitLab's server-side ref/commit CAS. | (a) is technically buildable but only safely releasable if every code path that can call `commit()` is guaranteed to later call `push()` (success or failure) in the same attempt — false here: `commitLocalChanges()` and `resolveConflictBySide()`'s pre-conflict-resolution `commit()` call can both complete without any following `push()` in that same "session," which would leak the lock (held forever until the tab closes) without extra reentrancy/leak-guard machinery this session judged not worth the complexity for a client-only feature. (b) gives up the one thing a `push()`-scoped lock cheaply buys: serializing two tabs' local `clearDirtySet` checkpoint writes for the same remote so they can't interleave. |

---

## Migration Plan
None — no SQLDelight schema changes. The dirty-set marker is a plain JSON file in OPFS (`.stele-dirty-set.json`), not a database table; `GitConfig`/`GitConfigRepository` (SQLDelight-backed) are reused unmodified.

## Observability Plan
- **Logs**: `WasmGitWriteService` logs every write-back attempt's terminal outcome (`success`, `conflict-detected`, `auto-merged(n files)`, `failed-with-reason`) via `Logger("WasmGitWriteService")`, including which of the 5 GitHub steps (or the single GitLab call) failed and the resulting `DomainError` subtype name. **The PAT is never interpolated into any log call** — enforced by a grep-based check task (Story 3.5.2) that scans the final diff for the token variable name outside `Authorization`/`PRIVATE-TOKEN` header construction.
- **Metrics**: None — client-side feature, no metrics pipeline exists for wasmJs (consistent with requirements.md's "no new alerting/oncall surface").
- **Alerts**: None (client-side feature).

## Risk Control
- **Feature flag**: None needed — the feature is naturally gated behind "user has configured git sync" (`GitConfigRepository.getConfig(graphId)` returning non-null) and scoped to `wasmJs` only, per requirements.md's Risk Control section.
- **Rollback procedure**: Revert the `Main.kt` wiring that passes a non-null `WasmGitRepository` into `StelekitApp` (Story 4.3.1) — this alone reverts `gitRepository`/`gitConfigRepository`/`gitSyncService` back to `null` on web, restoring today's inert-UI state with zero data-migration concern (nothing new is written to OPFS beyond the dirty-set marker, which is additive and harmless if abandoned). Reverting `JsGitManager`'s wiring (Story 4.2.1) independently restores the Phase-1 `NOT_SUPPORTED` stub.
- **Staged rollout**: None — no server-side flag infrastructure exists for wasmJs; ship-behind-opt-in-UX is the only staging mechanism, per requirements.md.

## Unresolved Questions
- [ ] `GitSetupScreen`'s "Clone and Add" affordance becomes active for web once `gitRepository != null` is passed into `App.kt` (its `onCloneAndAdd` callback is gated only on that null-check). `WasmGitRepository.clone()` is scoped in this plan to return `DomainError.GitError.NotSupported("web")` (Story 4.1.6) rather than implement a full-tree clone (out of this project's stated Scope). Should `GitSetupScreen` additionally hide/disable "Clone and Add" specifically on wasmJs so users don't hit a dead button? — blocks Story 4.1.6's UX polish, not its core correctness — owner: product/UX follow-up, can ship Phase 4 without this and file a fast-follow bug if real users hit it.
- [ ] The gated live-API test (Story 6.3.1) needs a disposable throwaway GitHub repo + GitLab project dedicated to this purpose, with a CI secret scoped to only those repos. Provisioning these is an infrastructure/account-ownership action outside this repo's source tree — owner: Tyler (repo/token creation), blocks Story 6.3.1 only (all other testing proceeds without it).
- [ ] `ux.md`'s recommendation to enrich `SyncState.Success` with an `autoMergedCount` field (so a successful auto-merge is visibly disclosed, not just logged) is **not** in requirements.md's explicit Scope list (only `LocalChangesPending` and `RateLimited` are named). This plan implements the Observability-Requirements-mandated *logging* of auto-merge outcomes (Story 3.5.1) but does **not** add the UI enrichment, to avoid unrequested scope growth — owner: product, decide whether to fast-follow after this ships; does not block anything in this plan.

## Deferred Technical Debt (explicitly accepted, not blocking)

These three items were raised during review (architecture review and pre-mortem) and are **explicitly accepted as known risk for this pass**, not silently dropped. None block Phase 5 implementation kickoff. Each has a named owner-signal for a future fast-follow if it manifests in practice.

- **PAT typed as bare `String`, not a redacting `GitToken` value class.** `architecture-review.md` flags this as a Concern (not a Blocker): `GitHostConfig.token` and every function threading it through (`authHeader`, `resolve`, the HTTP client calls) use plain `String`, so a future `logger.info("...$token...")` typo would compile and leak the token to logs with nothing catching it until a human notices. The only current protection is Task 3.5.1b's manual/CI-grep leak audit (`PatLeakageAuditTest`, Epic 3.5) — a process check, not a structural guarantee. Accepted for this pass given appetite constraints. **Recommended follow-up**: a `GitToken` value class wrapping the string and overriding `toString()` (and any log-relevant representation) to redact, used everywhere except the single header-construction call site that needs the raw value — this converts "PAT never appears in a log line" from a one-time-verified promise into a property that holds for every future accidental interpolation. See `architecture-review.md`'s Concerns list for the full remediation sketch.

- **Pre-mortem P2 items #2 and #5 have no dedicated story/task.** Both were classified "likely but recoverable" (P2, not P1) in `pre-mortem.md` and are accepted as known risk rather than fixed in this pass:
  - **#2 — stale-`baseSha` false conflict.** If `push()`'s ref-update PATCH succeeds server-side but the response is lost client-side (network drop after the write, before the client sees it), the local `baseSha` stays stale and the dirty set stays populated (correct per the crash-safety "clear-last" design). The *next* sync attempt then sees the remote-changed paths as 100%-overlapping with the still-populated local dirty paths and surfaces a spurious `MergeConflict`/`ConflictPending` for content the user already successfully pushed — confusing but not data-lossy. This is the same issue `adversarial-review.md`'s Concerns list still tracks as open ("Ambiguous push outcome... produces a false conflict on retry, not a clean resume").
  - **#5 — GitLab recurring-conflict escape hatch.** GitLab's conservative `400`-as-`MergeConflict` classification (Story 3.2.3) means a genuine request-shape bug (encoding edge case, unusual filename, or the unguarded aggregate-body-size case `adversarial-review.md`'s Minors flags) could misclassify as a conflict that recurs on every retry regardless of which side (LOCAL/REMOTE) the user picks, with no escape hatch out of the loop.
  - See `pre-mortem.md`'s Failure Modes table (rows #2 and #5) for full descriptions, first-symptom framing, and the specific prevention each row already recommends. Flagged here for a fast-follow if either manifests in practice (real users hitting a spurious post-push conflict, or a recurring un-resolvable GitLab conflict) rather than pre-built speculatively.

- **Architecture review's remaining Concerns are accepted as-is for this pass.** `architecture-review.md`'s verdict is CONCERNS, not BLOCKED — its own framing is that these are structural/maintainability issues, not correctness bugs, and this plan explicitly does not restructure around them this pass:
  - **God Object risk in `WasmGitWriteService`.** One class owns HTTP+JSON per-endpoint mechanics (8+ GitHub/GitLab endpoints), conflict partitioning/tree-rebuild, retry/backoff policy, `GitWriteLock` coordination, dirty-set orchestration, and observability logging — five-plus genuinely distinct concerns bundled into one file, independent of the (reasonable, already-justified) 2-host dispatch decision.
  - **`PlatformFileSystem`/git-domain coupling via the dirty-tracking hooks.** A Files-layer class (`PlatformFileSystem`) now understands git commit-staging concepts (`dirtySet`, `pendingCommit`, OPFS marker persistence), and `WasmGitWriteService` reaches back into `PlatformFileSystem` internals (`applyRemoteContent`) — a bidirectional coupling between the Files layer and the git domain.
  - **General primitive obsession beyond what was already fixed.** The `PendingCommit` illegal-state Blocker is resolved (see `architecture-review.md`'s Blockers section), but `baseSha`/`pendingCommitSha`/`pendingTreeSha`/`treeSha`/`parentSha`/`commitSha` remain bare `String`s threaded through multiple functions with different SHA "roles" (base/tree/parent/head) that the compiler cannot distinguish — a real parameter-swap risk at named call sites like `createCommitObject(hostConfig, treeSha, parentSha, message)`.
  - See `architecture-review.md`'s Concerns list for each item's full remediation sketch (collaborator extraction, a `GitDirtyTracker` collaborator, and `CommitSha`/`TreeSha`/`BlobSha` value classes respectively) if a future refactor pass wants to address them.

## Dependency Visualization
```
Phase 1: Shared Foundation
  Epic 1.1 GitHostAdapter ──────────────┐
  Epic 1.2 Serialization models ────────┼──► Phase 3 (WasmGitWriteService)
  Epic 1.3 DomainError/SyncState adds ──┘         │
                                                   │
Phase 2: Dirty-File Tracking & Web Lock            │
  Epic 2.1 PlatformFileSystem hooks ────┐          │
  Epic 2.2 .stele-dirty-set.json ───────┼──────────┤
  Epic 2.3 GitWriteLock ────────────────┘          │
                                                    ▼
Phase 3: WasmGitWriteService engine
  Epic 3.1 GitHub 5-step ───────────┐
  Epic 3.2 GitLab 1-call ───────────┼──► Epic 3.3 Conflict+auto-merge ──► Epic 3.4 Retry/rate-limit ──► Epic 3.5 Observability
                                    │                                                                          │
                                    └──────────────────────────────────────────────────────────────────────────┤
                                                                                                                 ▼
Phase 4: Reachability                                                                                  Phase 6: Testing
  Epic 4.1 WasmGitRepository actual ──► Epic 4.2 JsGitManager delegate ──► Epic 4.3 Main.kt/App.kt wiring    Epic 6.1 Unit tests (parallel with 1–5)
                                                        │                                                     Epic 6.2 Serialization round-trips (parallel with 1.2)
                                                        ▼                                                     Epic 6.3 Gated live-API test (after Phase 4)
Phase 5: UX Surfacing (after Phase 4)
  Epic 5.1 LocalChangesPending/RateLimited badge + StelekitViewModel wiring + beforeunload guard (Story 5.1.3)

Phase 7: Docs close-out (after everything else)
  Epic 7.1 BUG-005 close-out text correction
```

---

## Phase 1: Shared Foundation

### Epic 1.1: `GitHostAdapter`
**Goal**: A single, tested, host-detection + auth-header + API-base helper shared by `WasmGitWriteService` (write path) **and** `WasmSectionSyncService` (read path, Story 1.1.3). requirements.md's Scope requires `GitHostAdapter` be "reused across `WasmSectionSyncService` (read) and this new write path," and its Out-of-Scope list explicitly carves out exactly this: "Any change to `WasmSectionSyncService`'s read path beyond what's needed to share host-detection logic." Story 1.1.3 below is that bounded change — it replaces `WasmSectionSyncService`'s hardcoded GitHub URL/header construction with calls to `GitHostAdapter`, without adding GitLab (or any other host) read support or any other read-path capability.

#### Story 1.1.1: `GitHostAdapter.detect` classifies a remote URL by host
**As a** `WasmGitWriteService` caller, **I want** to classify a configured remote URL as GitHub, GitLab, or unsupported, **so that** the write engine can dispatch to the correct API sequence.
**Acceptance Criteria**:
- Detects `github.com` HTTPS and SSH-style remote URLs as `GitHostType.GITHUB`.
  - *Given* a `GitConfig` with `remoteName = "origin"` resolved to the URL `https://github.com/tstapler/steno-wiki.git`, *When* `GitHostAdapter.detect(url)` is called, *Then* it returns `GitHostType.GITHUB`.
- Detects `gitlab.com` remote URLs as `GitHostType.GITLAB`.
  - *Given* the URL `https://gitlab.com/tstapler-notes/wiki.git`, *When* `GitHostAdapter.detect(url)` is called, *Then* it returns `GitHostType.GITLAB`.
- Detects any other host (e.g. self-hosted Gitea) as `GitHostType.UNSUPPORTED`.
  - *Given* the URL `https://git.example.org/tstapler/wiki.git`, *When* `GitHostAdapter.detect(url)` is called, *Then* it returns `GitHostType.UNSUPPORTED`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitHostAdapter.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/GitHostType.kt`

##### Task 1.1.1a: Create `GitHostType` enum (~2 min)
- Add `enum class GitHostType { GITHUB, GITLAB, UNSUPPORTED }`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/GitHostType.kt`

##### Task 1.1.1b: Implement `GitHostAdapter.detect(remoteUrl: String)` (~4 min)
- Object `GitHostAdapter` in new file; `detect()` does substring match on `"github.com"` / `"gitlab.com"`, else `UNSUPPORTED`. Strip `.git` suffix and SSH `git@host:` prefix before matching.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitHostAdapter.kt`

#### Story 1.1.2: `GitHostAdapter.authHeader` and `apiBase` produce host-correct request metadata
**As a** `WasmGitWriteService` caller, **I want** the correct `Authorization`/`PRIVATE-TOKEN` header and API base URL per host, **so that** GitHub and GitLab requests authenticate correctly (they use different header conventions — confirmed by `research/stack.md`).
**Acceptance Criteria**:
- GitHub uses `Authorization: Bearer <token>`.
  - *Given* `GitHostType.GITHUB` and token `"ghp_abc123"`, *When* `GitHostAdapter.authHeader(GitHostType.GITHUB, "ghp_abc123")` is called, *Then* it returns `"Authorization" to "Bearer ghp_abc123"`.
- GitLab uses `PRIVATE-TOKEN: <token>` (a different header **name**, not just a different prefix).
  - *Given* `GitHostType.GITLAB` and token `"glpat-xyz789"`, *When* `GitHostAdapter.authHeader(GitHostType.GITLAB, "glpat-xyz789")` is called, *Then* it returns `"PRIVATE-TOKEN" to "glpat-xyz789"`.
- `apiBase` resolves the correct API root per host.
  - *Given* `GitHostType.GITHUB`, owner `"tstapler"`, repo `"steno-wiki"`, *When* `GitHostAdapter.apiBase(GitHostType.GITHUB, "tstapler", "steno-wiki")` is called, *Then* it returns `"https://api.github.com/repos/tstapler/steno-wiki"`.
  - *Given* `GitHostType.GITLAB`, owner `"tstapler-notes"`, repo `"wiki"`, *When* `GitHostAdapter.apiBase(GitHostType.GITLAB, "tstapler-notes", "wiki")` is called, *Then* it returns `"https://gitlab.com/api/v4/projects/tstapler-notes%2Fwiki"` (URL-encoded namespace/project path).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitHostAdapter.kt`

##### Task 1.1.2a: Implement `authHeader()` (~2 min)
- `when (type)` branch returning the correct header name/value pair per host.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitHostAdapter.kt`

##### Task 1.1.2b: Implement `apiBase()` with GitLab URL-encoding (~3 min)
- Use `kotlin.text` percent-encoding (`/` → `%2F`) for the GitLab namespace/project path; GitHub path is plain `owner/repo`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitHostAdapter.kt`

##### Task 1.1.2c: Wire `GitHostConfig` construction helper (~3 min)
- Add `fun GitHostAdapter.resolve(config: GitConfig, remoteUrl: String, token: String): GitHostConfig` combining `detect`+`authHeader`+`apiBase` into one call site for `WasmGitWriteService`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitHostAdapter.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/GitHostConfig.kt`

---

#### Story 1.1.3: `WasmSectionSyncService`'s hardcoded GitHub host/auth logic routes through `GitHostAdapter`
**As a** maintainer, **I want** the read path's GitHub-only host detection and `Authorization` header construction to route through the same `GitHostAdapter` the write path uses, **so that** host-detection/auth-header logic has one implementation, not two independently-evolving copies (requirements.md Scope: `GitHostAdapter` "reused across `WasmSectionSyncService` (read) and this new write path").
**Grounding** (`WasmSectionSyncService.kt`, current state, 127 lines — read before implementing): the read path hardcodes GitHub in exactly two places, both far simpler than "host detection" — there is no detection at all today, just literals:
1. `jsFetchWithToken(url, token)` (lines 17-18): a top-level `js()` interop function whose JS template literally contains `'Authorization': 'Bearer ' + token` — GitHub's exact header, with no host parameter.
2. `syncSection()` (line 49): builds the tree-fetch URL as the literal string `"https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1"`.

This story replaces both literals with calls to `GitHostAdapter`, using `GitHostType.GITHUB` explicitly (the read path's `companion object` fields — `githubOwner`/`githubRepo`/`githubBranch`/`githubToken` — are GitHub-specific today and stay that way; adding GitLab *read* support is a real capability expansion, not "sharing host-detection logic," and is therefore excluded by requirements.md's Out-of-Scope carve-out). This is a pure refactor — the HTTP request sent is byte-for-byte identical to today's before and after.
**Acceptance Criteria**:
- `jsFetchWithToken` is generalized to accept a header name/value pair instead of hardcoding `'Authorization': 'Bearer ' + token`.
  - *Given* `githubFetch(url, token)` with a non-null `token`, *When* it builds the request, *Then* it computes `GitHostAdapter.authHeader(GitHostType.GITHUB, token)` and passes the resulting `(headerName, headerValue)` pair into a new `jsFetchWithHeader(url, headerName, headerValue)` interop function — no header string is hardcoded in `WasmSectionSyncService.kt` anymore.
  - *Given* the same call, *When* compared to today's behavior, *Then* the actual HTTP request is unchanged: header name `"Authorization"`, value `"Bearer <token>"`.
- The tree-fetch URL is built via `GitHostAdapter.apiBase`, not a literal `api.github.com` prefix.
  - *Given* `owner = "tstapler"`, `repo = "steno-wiki"`, `branch = "main"`, *When* `syncSection()` builds the tree URL, *Then* it is `"${GitHostAdapter.apiBase(GitHostType.GITHUB, owner, repo)}/git/trees/$branch?recursive=1"`, which resolves to the identical literal URL produced today.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/sync/WasmSectionSyncService.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitHostAdapter.kt`

##### Task 1.1.3a: Generalize `jsFetchWithToken` → `jsFetchWithHeader(url, headerName, headerValue)` (~3 min)
- Replace the hardcoded-header `js()` template with one that sets the header via bracket property access (`[headerName]: headerValue`) so the header name itself is a parameter, not a literal; keep `jsFetchAnon` unchanged (no auth case).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/sync/WasmSectionSyncService.kt`

##### Task 1.1.3b: Route `githubFetch`'s header construction through `GitHostAdapter.authHeader` (~2 min)
- In `githubFetch`, when `token != null`, call `GitHostAdapter.authHeader(GitHostType.GITHUB, token)` to get `(name, value)` and pass to `jsFetchWithHeader`; `token == null` still calls `jsFetchAnon` unchanged.
- Files: same

##### Task 1.1.3c: Route the tree-fetch URL construction through `GitHostAdapter.apiBase` (~2 min)
- In `syncSection()`, replace the literal `"https://api.github.com/repos/$owner/$repo/git/trees/$branch?recursive=1"` with `"${GitHostAdapter.apiBase(GitHostType.GITHUB, owner, repo)}/git/trees/$branch?recursive=1"`.
- Files: same

---

### Epic 1.2: Git Data API / GitLab Commits API serialization models
**Goal**: Type-safe `@Serializable` request/response bodies for every endpoint in ADR-015's sequence, replacing string-scanning with `kotlinx.serialization`.

#### Story 1.2.1: GitHub Git Data API models round-trip correctly
**As a** `WasmGitWriteService` implementer, **I want** typed request/response models for blob/tree/commit/ref/compare, **so that** request bodies are built correctly and response fields (`sha`, `ahead_by`, etc.) are extracted without manual string scanning.
**Acceptance Criteria**:
- `GitBlobRequest` serializes with base64 encoding field.
  - *Given* `GitBlobRequest(content = "IyBGb28=", encoding = "base64")`, *When* `Json.encodeToString(it)` is called, *Then* the result is `{"content":"IyBGb28=","encoding":"base64"}`.
- `GitCommitResponse` deserializes GitHub's actual field shape.
  - *Given* the JSON `{"sha":"a1b2c3","url":"https://api.github.com/repos/tstapler/steno-wiki/git/commits/a1b2c3"}`, *When* `Json.decodeFromString<GitCommitResponse>(json)` is called, *Then* `result.sha == "a1b2c3"`.
- `GitCompareResponse` extracts `ahead_by` and changed file paths.
  - *Given* the JSON `{"ahead_by":2,"files":[{"filename":"pages/Foo.md"},{"filename":"journals/2026_07_14.md"}]}`, *When* decoded, *Then* `result.aheadBy == 2` and `result.files.map { it.filename } == listOf("pages/Foo.md", "journals/2026_07_14.md")`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/GitDataApiModels.kt`

##### Task 1.2.1a: `GitBlobRequest`/`GitBlobResponse` (~3 min)
- `@Serializable data class GitBlobRequest(val content: String, val encoding: String = "base64")`; `GitBlobResponse(val sha: String, val url: String? = null)`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/GitDataApiModels.kt`

##### Task 1.2.1b: `GitTreeEntry`/`GitTreeRequest`/`GitTreeResponse` (~4 min)
- `GitTreeEntry(path, mode, type, sha: String? = null, content: String? = null)`; `GitTreeRequest(baseTree: String? = null, tree: List<GitTreeEntry>)` with `@SerialName("base_tree")`; `GitTreeResponse(sha: String)`.
- Files: same

##### Task 1.2.1c: `GitCommitAuthor`/`GitCommitRequest`/`GitCommitResponse` (~4 min)
- `GitCommitAuthor(name, email, date)`; `GitCommitRequest(message, tree, parents: List<String>, author: GitCommitAuthor)`; `GitCommitResponse(sha: String)`.
- Files: same

##### Task 1.2.1d: `GitRefResponse` and `GitRefUpdateRequest` (~3 min)
- `GitRefResponse` mirrors GitHub's `{ "object": { "sha": "..." } }` shape — needs a nested `GitRefObject(sha: String)`. `GitRefUpdateRequest(sha: String, force: Boolean = false)`.
- Files: same

##### Task 1.2.1e: `GitCompareResponse`/`GitCompareFile` (~3 min)
- `GitCompareFile(filename: String)`; `GitCompareResponse(@SerialName("ahead_by") aheadBy: Int, files: List<GitCompareFile> = emptyList())`.
- Files: same

##### Task 1.2.1f: `ignoreUnknownKeys` shared `Json` config (~2 min)
- Add `val gitApiJson = Json { ignoreUnknownKeys = true }` (or reuse `GitHubDeviceFlowClient`'s existing instance if one is already exposed — check before duplicating).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/GitDataApiModels.kt`

#### Story 1.2.2: GitLab commits API models round-trip correctly
**As a** `WasmGitWriteService` implementer, **I want** typed models for GitLab's single-call commits API, **so that** the `actions` array and `last_commit_id`-based conflict signal are built/parsed correctly.
**Acceptance Criteria**:
- `GitLabCommitAction` serializes an `update` action with `last_commit_id`.
  - *Given* `GitLabCommitAction(action = "update", filePath = "pages/Foo.md", content = "IyBGb28=", encoding = "base64", lastCommitId = "9f8e7d6c")`, *When* encoded, *Then* the JSON contains `"action":"update"`, `"file_path":"pages/Foo.md"`, `"last_commit_id":"9f8e7d6c"`.
- `GitLabCommitRequest` includes `start_sha` for conflict detection.
  - *Given* `GitLabCommitRequest(branch = "main", commitMessage = "SteleKit: 2026-07-14", startSha = "8f3c1a9", actions = listOf(...))`, *When* encoded, *Then* the JSON contains `"start_sha":"8f3c1a9"`.
- `GitLabCommitResponse` deserializes the created commit's `id`.
  - *Given* the JSON `{"id":"a1b2c3d4","short_id":"a1b2c3d","title":"SteleKit: 2026-07-14"}`, *When* decoded, *Then* `result.id == "a1b2c3d4"`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/GitLabCommitModels.kt`

##### Task 1.2.2a: `GitLabCommitAction` (~3 min)
- Fields per `research/stack.md` §2: `action`, `@SerialName("file_path") filePath`, `content: String? = null`, `encoding: String = "base64"`, `@SerialName("last_commit_id") lastCommitId: String? = null`, `@SerialName("previous_path") previousPath: String? = null`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/GitLabCommitModels.kt`

##### Task 1.2.2b: `GitLabCommitRequest`/`GitLabCommitResponse` (~4 min)
- `GitLabCommitRequest(branch, @SerialName("commit_message") commitMessage, @SerialName("start_sha") startSha: String? = null, actions: List<GitLabCommitAction>)`; `GitLabCommitResponse(id: String, @SerialName("short_id") shortId: String? = null)`.
- Files: same

##### Task 1.2.2c: `GitLabCompareResponse` for conflict pre-check (~3 min)
- `GitLabCompareResponse(commits: List<GitLabCommitResponse> = emptyList(), diffs: List<GitLabDiffEntry> = emptyList())`; `GitLabDiffEntry(@SerialName("new_path") newPath: String)`.
- Files: same

---

### Epic 1.3: `DomainError`/`SyncState` model additions
**Goal**: Add the new error/state variants requirements.md's Scope explicitly names — `GitError.RateLimited`, `GitError.FileTooLarge`, `SyncState.LocalChangesPending`, and `SyncState.RateLimited` (the `SyncState` counterpart requirements.md names in the same breath as the `GitError` one: "a distinct 'local changes pending' `SyncState`... and a `RateLimited` `SyncState`/`GitError` variant") — keeping every exhaustive `when` in the codebase compiling.

#### Story 1.3.1: `DomainError.GitError.RateLimited`, `FileTooLarge`, and `NetworkFailure` exist and render correctly
**As a** web user hitting a GitHub/GitLab rate limit, an oversized file, or a dropped connection, **I want** a distinct, correctly-worded error for each, **so that** I'm not shown a generic "tap to retry" for something that isn't manually retryable, a silent failure for a file that structurally cannot be pushed, or a misleading "push failed" for a request that never reached the host at all.
**Acceptance Criteria**:
- `RateLimited` is a new `GitError` subtype with an optional retry-after hint.
  - *Given* `DomainError.GitError.RateLimited(retryAfterSeconds = 42)`, *When* `.toSyncErrorMessage()` is called, *Then* it returns a string mentioning "rate limit" (e.g. `"Rate limited by GitHub/GitLab — retrying automatically"`), never "tap to retry".
- `FileTooLarge` names the offending file and limits.
  - *Given* `DomainError.GitError.FileTooLarge(path = "assets/large-export.md.stek", sizeBytes = 90_000_000, maxBytes = 75_000_000)`, *When* `.message` is read, *Then* it contains `"assets/large-export.md.stek"` and both byte counts.
- `NetworkFailure` is a new `GitError` subtype distinct from a received HTTP error response and from `RateLimited`, closing requirements.md's Scope requirement for a "network" failure category (Rabbit Hole 3 / fix identified during Phase 4 cross-artifact review: Epic 3.4's original scope only handled retry-triggering HTTP statuses, with no variant for a request that never got a response at all — DNS failure, offline mid-request, CORS block).
  - *Given* `DomainError.GitError.NetworkFailure(message = "Failed to fetch")`, *When* `.toSyncErrorMessage()` is called, *Then* it returns a host-neutral string not implying a server-side rejection (e.g. `"Network error — sync will retry"`), distinguishable from `PushFailed`/`CommitFailed`/`FetchFailed`'s copy.
- All three new variants are exhaustively handled in `toUiMessage()` and `toSyncErrorMessage()` — the file compiles with no `else` branch added to either `when`.
  - *Given* the edited `DomainError.kt`, *When* `bazel build //kmp:jvm_tests` (or `./gradlew jvmTest` compile step) runs, *Then* it compiles with no "missing branch" warning treated as error.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt`

##### Task 1.3.1a: Add `RateLimited`, `FileTooLarge`, and `NetworkFailure` to the `GitError` sealed interface (~4 min)
- Insert all three `data class`es into `sealed interface GitError` alongside the existing variants: `data class RateLimited(val retryAfterSeconds: Int?) : GitError`, `data class FileTooLarge(val path: String, val sizeBytes: Long, val maxBytes: Long) : GitError`, `data class NetworkFailure(override val message: String) : GitError`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/error/DomainError.kt`

##### Task 1.3.1b: Add all three branches to `toUiMessage()` (~3 min)
- `is DomainError.GitError.RateLimited -> "Rate limited — retrying automatically"`; `is DomainError.GitError.FileTooLarge -> "File too large to sync: ${path}"`; `is DomainError.GitError.NetworkFailure -> "Network error — sync will retry"`.
- Files: same

##### Task 1.3.1c: Add all three branches to `toSyncErrorMessage()` (~3 min)
- Same copy, phrased for the sync badge context (no "tap to retry" wording for `RateLimited` or `NetworkFailure` — both are auto-resolving, matching the existing `Offline` variant's "sync will resume" framing).
- Files: same

#### Story 1.3.2: `SyncState.LocalChangesPending` exists and is distinguishable from `Idle`
**As a** web user who edited a page but hasn't synced yet, **I want** the sidebar to show something other than nothing, **so that** I don't mistake "saved to this browser" for "safely backed up" (the core failure mode of the original bug).
**Acceptance Criteria**:
- `LocalChangesPending(fileCount: Int)` is added to the `SyncState` sealed class.
  - *Given* `SyncState.LocalChangesPending(fileCount = 3)`, *When* pattern-matched in a `when (syncState)` block, *Then* the compiler requires (or a lint/test enforces) an explicit branch — no silent fallthrough into `else`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/SyncState.kt`

##### Task 1.3.2a: Add the sealed class variant (~2 min)
- `data class LocalChangesPending(val fileCount: Int) : SyncState()`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/SyncState.kt`

---

#### Story 1.3.3: `SyncState.RateLimited` exists and `GitSyncService` routes `GitError.RateLimited` into it
**As a** web user hitting a GitHub/GitLab rate limit, **I want** the sidebar badge to show a distinct, non-alarming "retrying automatically" state, **so that** I don't see the same red "tap to retry" a real, manually-retryable failure would show — resolves the gap adversarial review flagged: Story 1.3.1 added `DomainError.GitError.RateLimited` (the error type) but nothing produced the corresponding `SyncState` the UI actually renders from, so a rate-limited push previously fell back to generic `SyncState.Error`.
**Design**: `GitSyncService` is the sole writer of `_syncState`, and it is not otherwise being rewritten (ADR-017) — but it already contains precedent for exactly this kind of mapping: the `fetch()` `.onLeft` block special-cases `AuthFailed` + `GITHUB_OAUTH` into `SyncState.CredentialExpired` rather than the generic `SyncState.Error` (`GitSyncService.kt` lines ~153–157). This story adds the same style of `if (err is DomainError.GitError.RateLimited) ... else ...` branch at every `.onLeft`/error-producing site in `sync()` and `fetchOnly()` that currently sets `SyncState.Error(err)` unconditionally: the commit, fetch, merge, and push sites in `sync()`, and the fetch site in `fetchOnly()`. This is a small, additive, precedent-consistent change to `GitSyncService.kt` — not a violation of ADR-017's "zero changes to `GitSyncService`" framing, which describes the *integration shape/call structure* staying unmodified (so the existing UI works with no wasmJs-specific branching), not a prohibition on ever editing the file for a legitimate, requirements-mandated error-to-state mapping.
**Acceptance Criteria**:
- `SyncState.RateLimited(retryAfterSeconds: Int?)` is added to the sealed class.
  - *Given* `SyncState.RateLimited(retryAfterSeconds = 5)`, *When* pattern-matched in `SyncStateBadge`'s exhaustive `when (syncState)`, *Then* the compiler requires an explicit branch (same enforcement mechanism as `LocalChangesPending`, Story 1.3.2).
- Every `.onLeft` site in `sync()`/`fetchOnly()` that currently sets `SyncState.Error(err)` unconditionally gains a `RateLimited` branch alongside the existing `AuthFailed`/`CredentialExpired` and (per Story 3.2.3's `MergeConflict` fix at the push site) `MergeConflict` branches.
  - *Given* `gitRepository.commit(config, message)` returns `Either.Left(DomainError.GitError.RateLimited(retryAfterSeconds = 30))` during `sync()`'s step 5, *When* the `.onLeft` handler runs, *Then* `_syncState.value = SyncState.RateLimited(30)`, not `SyncState.Error(...)`.
  - *Given* the same for `fetch()`, `merge()`, and `push()` within `sync()`, and for `fetch()` within `fetchOnly()`, *When* each returns `RateLimited`, *Then* each sets `SyncState.RateLimited(retryAfterSeconds)` at its respective call site.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/SyncState.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`

##### Task 1.3.3a: Add the sealed class variant (~2 min)
- `data class RateLimited(val retryAfterSeconds: Int?) : SyncState()`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/SyncState.kt`

##### Task 1.3.3b: Add the `RateLimited` branch to every relevant `.onLeft` site in `GitSyncService.sync()`/`fetchOnly()` (~5 min)
- Five call sites total (commit/fetch/merge/push in `sync()`, fetch in `fetchOnly()`); each becomes a small `when`/`if` chain checking `RateLimited` (this task) and, at the push site only, also `MergeConflict` (Task 3.2.3b) before falling through to the existing generic `SyncState.Error(err)`/`CredentialExpired` logic. Sequence this task after or alongside Task 3.2.3b, Task 3.4.2b (schedules the `RateLimited` auto-retry — must land in the same edit as this task's `RateLimited` branch), and Task 3.4.3b (adds the `CredentialExpired` branch) so all four land in one coherent edit to the same functions rather than four colliding ones.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`

---

## Phase 2: Dirty-File Tracking & Web Lock

### Epic 2.1: `PlatformFileSystem` dirty-tracking hooks
**Goal**: Record every `writeFile`/`writeFileBytes`/`deleteFile` call as a dirty-set entry, entirely inside the wasmJs actual, with zero `commonMain`/`GraphWriter` changes (Pattern Decision).

#### Story 2.1.1: `writeFile`/`writeFileBytes`/`deleteFile` record dirty entries in-memory
**As** `WasmGitWriteService`, **I want** to know exactly which repo-relative paths changed since the last successful push, **so that** I only push what actually changed (no full-graph scan) — and this must cover paranoid-mode's encrypted writes too, or requirements.md's claim that "paranoid mode over git write-back... just works" is false.
**Grounding** (found during Phase 4 cross-artifact review, read before implementing): `PlatformFileSystem.wasmJs.kt` has **no `writeFileBytes` override at all** today — it inherits `FileSystem`'s default (`FileSystem.kt` lines 69-70), which `throw`s `UnsupportedOperationException`. This is a deeper gap than "the hook is missing" — paranoid-mode writes on web currently crash, full stop. Task 2.1.1e below both implements real byte-level OPFS I/O (reusing the already-existing `opfsWriteFileBytes(path, data: JsAny)` interop in `OpfsInterop.kt`, which `WasmMediaAttachmentService.kt` already uses for attachment uploads) and wires the same `recordDirty` hook `writeFile` uses.
**Acceptance Criteria**:
- Writing a file records a `WRITE` dirty entry keyed by repo-relative path.
  - *Given* an empty dirty set and OPFS path `/stelekit/default/pages/Foo.md`, *When* `PlatformFileSystem.writeFile("/stelekit/default/pages/Foo.md", "# Foo")` is called, *Then* `getDirtySnapshot()` contains the key `"pages/Foo.md"` with `op = DirtyOp.WRITE`.
- Writing raw bytes (paranoid mode) records a `WRITE` dirty entry the same way.
  - *Given* an empty dirty set and OPFS path `/stelekit/default/pages/Foo.md.stek`, *When* `PlatformFileSystem.writeFileBytes("/stelekit/default/pages/Foo.md.stek", encryptedBytes)` is called, *Then* `getDirtySnapshot()` contains the key `"pages/Foo.md.stek"` with `op = DirtyOp.WRITE` — the same dirty set `writeFile` writes into, not a separate one.
- Deleting a file records a `DELETE` dirty entry, overwriting any prior `WRITE` entry for the same path.
  - *Given* a dirty set already containing `"pages/Foo.md" -> DirtyEntry(WRITE, t1)`, *When* `PlatformFileSystem.deleteFile("/stelekit/default/pages/Foo.md")` is called, *Then* `getDirtySnapshot()["pages/Foo.md"].op == DirtyOp.DELETE`.
- The `DOWNLOAD_PREFIX` export path (`/_wasm_dl_/...`) is never recorded as dirty (it's a browser file-save, not a graph write).
  - *Given* `writeFile("/_wasm_dl_/export.md", "...")`, *When* called, *Then* `getDirtySnapshot()` is unchanged (still empty).
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

##### Task 2.1.1a: Add `DirtyOp`/`DirtyEntry` and the in-memory dirty map (~3 min)
- `private val dirtySet = mutableMapOf<String, DirtyEntry>()` field on `PlatformFileSystem`; reuse `DirtyOp`/`DirtyEntry` from `git/model/DirtySetMarker.kt` (Task 2.2.1a — sequence this after that task, or stub the two types here first).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

##### Task 2.1.1b: Add `recordDirty(path, op)` private helper with repo-relative path derivation (~4 min)
- Reuse the exact derivation already used in `readFileSuspend`: `path.removePrefix("/stelekit/").substringAfter("/")`. Guard against `DOWNLOAD_PREFIX` paths.
- Files: same

##### Task 2.1.1c: Call `recordDirty` from `writeFile` and `deleteFile` (~3 min)
- One-line call added inside each existing actual override, after the `cache[...]`/`scope.launch { opfs... }` lines.
- Files: same

##### Task 2.1.1d: Expose `getDirtySnapshot(): Map<String, DirtyEntry>` and `dirtyFileCountFlow: StateFlow<Int>` (~4 min)
- `getDirtySnapshot()` returns an immutable copy. `dirtyFileCountFlow` is a `MutableStateFlow<Int>(0)` updated alongside `recordDirty` and on marker load/clear.
- Files: same

##### Task 2.1.1e: Implement the missing `writeFileBytes` override for wasmJs and call `recordDirty` from it (~6 min)
- Add `private val bytesCache = mutableMapOf<String, ByteArray>()` alongside the existing `cache`. Add a small new top-level `js()` interop function converting a Kotlin `ByteArray` to the `JsAny`/`ArrayBuffer` shape `opfsWriteFileBytes` (`OpfsInterop.kt`) already accepts — reuse that existing function, don't duplicate its OPFS-write logic.
- `override fun writeFileBytes(path: String, data: ByteArray): Boolean { if (path.startsWith(DOWNLOAD_PREFIX)) return false /* bytes export not in scope */; bytesCache[path] = data; recordDirty(path, DirtyOp.WRITE); scope.launch { opfsWriteFileBytes(path, data.toJsArrayBuffer()) }; return true }` — same shape and same `DOWNLOAD_PREFIX` guard as `writeFile`.
- **Explicitly out of scope, not silently dropped**: `readFileBytes` (paranoid-mode decryption on load) has the identical "throws `UnsupportedOperationException`" gap on wasmJs and is **not** fixed by this task — nothing in the sync/push path calls `readFileBytes` (`WasmGitWriteService` reads dirty content from `bytesCache`/`cache` directly, not through the `FileSystem` interface), so it's not required for this project's Scope. It remains a real, separate gap (paranoid-mode *read* on web) for a future bug report — not implied fixed by this task.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

#### Story 2.1.2: `.stele-dirty-set.json` checkpoint persists and restores across reload
**As** a returning web user, **I want** my dirty-file tracking to survive a page reload, **so that** a mid-session refresh doesn't lose track of what still needs to sync.
**Acceptance Criteria**:
- The marker is written to OPFS promptly on every dirty-set change — **not** on a fixed multi-second idle debounce (corrected during Phase 4 review, see Task 2.1.2b's redesign note: pre-mortem P1 #1 found the original ~2s trailing debounce could leave a "content safely in OPFS, but nothing records it needs syncing" window open indefinitely during active editing, not just for 2 seconds).
  - *Given* three consecutive `writeFile` calls in rapid succession (all launched before the first call's marker write has completed), *When* the writes settle, *Then* exactly one additional `opfsWriteFile(".../.stele-dirty-set.json", ...)` call is made beyond the one already in flight (coalesced, not three separate writes) — the *last* call's write is launched immediately once the in-flight one completes, not after a fixed idle period.
- A crash/tab-close immediately after a single `writeFile`/`writeFileBytes`/`deleteFile` call does not lose that path's dirty-tracking record.
  - *Given* no marker write was already in flight, *When* `writeFile(...)` is called, *Then* the marker write for that change is `scope.launch`-ed synchronously within the same call (not scheduled behind any timer/delay) — so the crash-loss window is bounded by a single OPFS write's duration (milliseconds), not by how long the user keeps actively editing. This is the acceptance criterion for pre-mortem P1 #1's fix, made concrete and testable (see Task 2.1.2b's test).
- `preload()` restores the in-memory dirty set from the marker.
  - *Given* an OPFS marker containing `{"dirtyFiles": {"pages/Foo.md": {"op":"write","updatedAtMillis":1752499990000}}, "baseSha": "8f3c1a9", ...}`, *When* `PlatformFileSystem().preload("/stelekit/default")` runs, *Then* `getDirtySnapshot()` contains `"pages/Foo.md"` and `dirtyFileCountFlow.value == 1`.
- A malformed or absent marker starts with an empty dirty set (crash-safe default), not a thrown exception.
  - *Given* no `.stele-dirty-set.json` exists in OPFS, *When* `preload()` runs, *Then* `getDirtySnapshot()` is empty and no exception propagates out of `preload()`.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/DirtySetMarker.kt`

##### Task 2.1.2a: Define `DirtySetMarker`/`DirtyOp`/`DirtyEntry` as `@Serializable` (~4 min)
- Schema exactly as specified in §4 below (`version`, `graphId`, `baseSha`, `pendingCommit: PendingCommit`, `checkpointedAtMillis`, `dirtyFiles: Map<String, DirtyEntry>`).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/DirtySetMarker.kt`

##### Task 2.1.2b: Immediate, coalesced (not fixed-delay-debounced) marker write on dirty-set change — redesigned to close pre-mortem P1 #1 (~6 min)
- **Problem with the original design**: a fixed ~2s *trailing* debounce (cancel-and-relaunch on every `recordDirty` call) never fires the write until 2s after the *last* call in a burst. During an active editing session touching multiple files, that reset-on-every-call behavior keeps the window open indefinitely — not just "2 seconds" — so a crash/tab-close mid-session can lose the dirty-set record for content already safely in OPFS (pre-mortem P1 #1, the exact failure mode this whole project exists to close, one layer deeper).
- **Redesign**: replace the fixed-delay debounce with an immediate-start, coalesce-while-busy scheduler for the *same* single `.stele-dirty-set.json` marker — no second file, no schema change. Two fields: `private var markerWriteInFlight = false`, `private var markerWriteDirty = false`. On every `recordDirty` call: if no write is in flight, immediately `scope.launch` a marker write (serializing the *current* `dirtySet`/`baseSha`/`pendingCommit` at launch time) and set `markerWriteInFlight = true`; if a write is already in flight, just set `markerWriteDirty = true`. On write completion: if `markerWriteDirty` is set, clear it and immediately launch one more write with the now-current state; otherwise clear `markerWriteInFlight`. This bounds the "record lost on crash" window to the duration of a single in-flight OPFS write (typically single-digit milliseconds) instead of up to 2+ seconds of active editing, while still guaranteeing at most one `createWritable()` is ever open for this file at a time — the actual reason the original design serialized writes at all, preserved here without the artificial delay.
- Also register a `visibilitychange` listener (fires reliably on tab-hide/close — more reliable than `beforeunload`/`pagehide` for this purpose per the Page Lifecycle API) that, when `document.visibilityState === "hidden"`, invokes the same write-scheduler immediately as a belt-and-suspenders best-effort flush, per the pre-mortem's explicit recommendation — even though the primary redesign above already bounds the loss window to milliseconds in the common case.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

##### Task 2.1.2b-test: Crash-before-write-completes regression test, per pre-mortem P1 #1 (~4 min)
- Per the pre-mortem's own prescribed test ("add a test that calls `writeFile()` then simulates reload *before* the debounce timer fires and asserts the dirty entry survives"): call `writeFile()`/`writeFileBytes()`, assert the marker write was `scope.launch`-ed within the same call stack (i.e., `markerWriteInFlight` flips `true` synchronously, not after any `delay()`), then simulate a fresh `PlatformFileSystem().preload()` reading back the marker once that single launched write completes, and assert the dirty entry is present. Also assert the coalescing behavior: three `recordDirty` calls fired back-to-back while a write is in flight produce exactly one trailing write (not three), containing all three paths' latest state.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/PlatformFileSystemDirtyTrackingTest.kt` (new file — confirm this module's exact `wasmJsTest` directory convention against an existing wasmJs-only test before creating)

##### Task 2.1.2c: Restore marker in `preload()` (~4 min)
- Read `.stele-dirty-set.json` via existing `readOpfsFile`-style helper (or reuse `loadOpfsDirectory`'s file-read path), `try/catch` decode, seed `dirtySet`/`dirtyFileCountFlow`/`baseSha` field. Absent or malformed file → empty defaults, no throw.
- Files: same

##### Task 2.1.2d: Expose `clearDirtySet(newBaseSha: String)` for `WasmGitWriteService` (~3 min)
- Clears `dirtySet`, resets `dirtyFileCountFlow.value = 0`, updates `baseSha`, resets `pendingCommit` to `PendingCommit.None` (always — `clearDirtySet` is only ever called after a successful push, so there is never a reason to pass a replacement staged value), writes the marker immediately (not debounced — this is the crash-safety-critical "clear last" write per ADR-013's commit-flag pattern).
- Files: same

---

### Epic 2.2: `.stele-dirty-set.json` OPFS checkpoint schema
**Goal**: Formalize and document the exact on-disk schema (already designed in `research/architecture.md` §4; this epic is the implementation of that design, covered by Story 2.1.2 above and its dedicated schema story below for the `PendingCommit` extension architecture.md's version didn't anticipate).

#### Story 2.2.1: Marker schema includes a `PendingCommit` sum type for the GitHub commit/push split
**As** `WasmGitWriteService`, **I want** to persist the GitHub commit object created by `commit()` before `push()` runs, **so that** a page reload between `commit()` and `push()` doesn't lose the already-created (but not yet ref-pointed) commit, and a retry can decide whether to reuse or re-derive it — **and I want that persisted state to make the "exactly one SHA set, the other missing" corruption case unrepresentable**, since that's exactly the kind of partially-completed 5-step-sequence state ADR-015/ADR-017 exist to guard against.
**Design**: `pendingCommitSha`/`pendingTreeSha` as two independently-nullable `String?` fields would let a hand-edited or corrupted OPFS file (or a future bug) construct a marker with one set and the other `null` — semantically illegal, since both are always set together by `commit()` and cleared together by `push()`/`clearDirtySet`. Replace both with a single `PendingCommit` sealed type field:
```kotlin
@Serializable
sealed interface PendingCommit {
    @Serializable @SerialName("none") data object None : PendingCommit
    @Serializable @SerialName("staged") data class Staged(val commitSha: String, val treeSha: String) : PendingCommit
}
```
`kotlinx.serialization`'s polymorphic/sealed support (class-discriminator `type` field) handles the `@Serializable sealed interface` round-trip directly, consistent with the plan's existing `kotlinx.serialization` convention — no hand-written serializer needed.
**Acceptance Criteria**:
- The marker schema (§4) has a single `pendingCommit: PendingCommit` field, defaulting to `PendingCommit.None`.
  - *Given* a fresh marker with no in-flight commit, *When* serialized, *Then* `pendingCommit` is `{"type":"none"}`.
  - *Given* `commit()` has run and created commit SHA `"c0ffee1"` on tree SHA `"7ea5e11"`, *When* the marker is next checkpointed, *Then* `pendingCommit` is `{"type":"staged","commitSha":"c0ffee1","treeSha":"7ea5e11"}`.
  - *Given* the `PendingCommit` sealed interface itself, *When* inspected, *Then* there is no constructor path that produces "commitSha present, treeSha absent" or vice versa — the illegal state is unrepresentable by the type, not just avoided by convention.
- `clearDirtySet` (called only after a successful `push()`) resets `pendingCommit` to `None` — never leaves a stale `Staged` value.
  - *Given* a marker with `pendingCommit = PendingCommit.Staged("c0ffee1", "7ea5e11")`, *When* `clearDirtySet(newBaseSha = "c0ffee1")` is called, *Then* the persisted marker has `pendingCommit = PendingCommit.None` and `baseSha = "c0ffee1"`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/DirtySetMarker.kt`

##### Task 2.2.1a: Add the `PendingCommit` sealed interface and the `pendingCommit` field to `DirtySetMarker` (~4 min)
- `@Serializable sealed interface PendingCommit { @SerialName("none") data object None : PendingCommit; @SerialName("staged") data class Staged(val commitSha: String, val treeSha: String) : PendingCommit }`; `DirtySetMarker.pendingCommit: PendingCommit = PendingCommit.None`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/DirtySetMarker.kt`

##### Task 2.2.1b: `PlatformFileSystem.setPendingCommit(commitSha, treeSha)` setter used by `commit()` (~3 min)
- Constructs `PendingCommit.Staged(commitSha, treeSha)` and writes it into the marker's `pendingCommit` field. Immediate (non-debounced) marker write — crash safety requires this land on disk before `push()` is ever attempted.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

---

### Epic 2.3: Web Lock around `push()`'s write-critical-section
**Goal**: Build `navigator.locks.request()` exclusion from scratch (confirmed by `research/pitfalls.md` §4: no working precedent exists anywhere in this codebase despite ADR-013 describing it for the read path).

**Web Lock — guarantees and known gaps** (corrected during adversarial review; read before implementing Epic 3.1/3.2's push wiring below):

`GitSyncService.sync()` is unmodified (ADR-017) and calls `gitRepository.commit()`, `.fetch()`, `.merge()`, `.push()` as 4 separate top-level suspend calls, with real work in between (e.g. `graphLoader.reloadFiles(...)` between `merge()` and `push()`). A `navigator.locks.request()` lock's lifetime is scoped to the single callback passed to it — it cannot span those 4 independently-invoked calls without an explicit acquire-now/release-later handle. Building that handle safely requires guaranteeing every `commit()` call is eventually followed by a `push()` call in the same attempt so the lock is always released — false here (`commitLocalChanges()` and the pre-resolution `commit()` inside `resolveConflictBySide()` can both complete with no following `push()`), so an attempt-spanning lock would leak indefinitely on those paths without additional reentrancy/leak-guard machinery this plan does not build (judged not worth the complexity for a client-only feature — see Pattern Decision "Web Lock scope").

**Corrected design**: the lock is acquired only inside `WasmGitWriteService.push()`, wrapping just the write-critical-section — the final ref-update PATCH (GitHub, Task 3.1.2d) or the single commits POST (GitLab, Task 3.2.1c) — plus the `clearDirtySet` checkpoint write that immediately follows a successful call. This is a single, non-suspending-across-other-calls block, so the original callback-scoped `withLock` design works correctly for it with no leak/reentrancy risk.

- **Covered**: two tabs' final server-side write calls (ref PATCH / commits POST) for the *same remote* cannot race each other from this client's perspective, and the local `.stele-dirty-set.json` checkpoint-clear that follows a successful push cannot be clobbered by another tab's concurrent checkpoint-clear for the same remote.
- **Not covered**: the read-modify-write window between a tab's `fetch()`/`merge()` (computing the local dirty-path vs. remote-changed-path partition) and that same tab's later `push()` is *not* serialized against another tab doing the same thing concurrently — two tabs can both compute an auto-merge decision from a stale view of each other's in-flight state. This plan continues to rely on GitHub's ref-PATCH `409`/`422` and GitLab's `last_commit_id`/`start_sha` staleness check (Pattern Decision "Commit/push split") as the *authoritative* server-side conflict signal for that window — a losing tab's `push()` will fail with `MergeConflict` rather than corrupt the remote, but it may have already computed an auto-merge partition against stale data and shown the user an incorrect "no conflict" outcome briefly before the push-time check catches it. This residual race is inherited from "no full-attempt lock is achievable" and is not newly introduced by the corrected design.
- **Not covered**: same-tab double-invocation of a write-back attempt (e.g. rapid double-tap of the manual sync button before the first attempt's `push()` returns) is not addressed by this lock at all — that is a same-JS-runtime reentrancy question, not a cross-tab one, and is out of scope for `GitWriteLock`. If this becomes a real problem, it belongs behind a UI-level debounce/disable-while-syncing guard, not the Web Lock.
- **Covered, structurally**: a tab that crashes or reloads mid-attempt (after `commit()` staged a `PendingCommit.Staged` but before `push()` ran) never held the Web Lock in the first place under this design (only `push()` acquires it), so there's nothing to leak on crash; the next `commit()` call re-derives from the dirty set per the "always re-derive" principle (Rabbit Holes, requirements.md), consistent with the Blocker-1 `PendingCommit` fix above.

#### Story 2.3.1: `GitWriteLock` serializes the final push write across tabs, for a given remote
**As** a user with two tabs open on the same graph, **I want** two tabs' final ref-update/commit calls and checkpoint-clear writes to never interleave, **so that** the local `.stele-dirty-set.json` checkpoint can't be corrupted by two tabs finishing a push at the same moment — **not** a guarantee that only one tab's entire write-back sequence runs at a time (see the guarantees-and-gaps note above for what this does and doesn't close).
**Acceptance Criteria**:
- The lock name is derived deterministically and safely from the remote URL.
  - *Given* remote URL `"https://github.com/tstapler/steno-wiki.git"`, *When* `GitWriteLock.lockNameFor(url)` is called, *Then* it returns a URL-safe string (e.g. `"stele-write-github.com-tstapler-steno-wiki"`) containing no characters `navigator.locks.request()` would reject.
- `withLock` runs the block exclusively and releases on both success and failure.
  - *Given* two concurrent calls to `GitWriteLock.withLock(sameLockName) { ... }` from different coroutines in the same tab (simulating cross-tab contention within a single-threaded JS runtime test), *When* both run, *Then* the second block does not start executing until the first has returned or thrown.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/GitWriteLock.kt`

##### Task 2.3.1a: `js()` interop for `navigator.locks.request()` (~4 min)
- Top-level `js()` functions only (Kotlin/Wasm constraint, per `research/stack.md` §3): a Promise-returning wrapper that takes a lock name and a callback, matching the pattern already used in `OpfsInterop.kt`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/GitWriteLock.kt`

##### Task 2.3.1b: `lockNameFor(remoteUrl)` sanitizer (~2 min)
- Strip protocol, replace `/`/`:`/`.` runs conservatively, prefix `"stele-write-"`.
- Files: same

##### Task 2.3.1c: `suspend fun <T> withLock(lockName: String, block: suspend () -> T): T` (~4 min)
- Wraps the JS lock acquisition + `block()` + release using `.await<JsAny>()`, matching `WasmSectionSyncService`'s async-interop style. This callback-scoped shape is correct and sufficient for the corrected design — it is only ever called around `push()`'s write-critical-section (see Task 3.1.2d/3.2.1c below), never across `commit()`→`push()`.
- Files: same

---

## Phase 3: `WasmGitWriteService` — write-back engine

### Epic 3.1: GitHub 5-step sequence

#### Story 3.1.1: `commit()` creates blob/tree/commit objects without moving the ref
**As** `WasmGitRepository.commit()` (called by `GitSyncService.sync()` step 5), **I want** to create the GitHub commit object for the current dirty set, **so that** a subsequent conflict check (fetch/merge) can run before the branch ref is ever touched.
**Acceptance Criteria**:
- One dirty file produces one blob create call, one tree create call, one commit create call.
  - *Given* `GitHostConfig(GITHUB, "tstapler", "steno-wiki", "main", token)` and a dirty set `{"pages/Foo.md": WRITE}` with cached content `"# Foo\n"`, *When* `WasmGitWriteService.buildGitHubCommit(...)` runs, *Then* exactly one `POST .../git/blobs` (base64 of `"# Foo\n"`), one `POST .../git/trees` (referencing the new blob SHA + `base_tree = baseSha`), and one `POST .../git/commits` call are made, in that order.
- A `DELETE`-op dirty entry produces a tree entry with `sha = null`.
  - *Given* a dirty set `{"pages/Bar.md": DELETE}`, *When* the tree is built, *Then* the `GitTreeEntry` for `"pages/Bar.md"` has `sha = null` and no corresponding blob-create call is made for that path.
- An empty dirty set skips the whole sequence and returns success with no commit created (desktop parity — `research/features.md` §2b).
  - *Given* an empty dirty set, *When* `commit()` is called, *Then* zero blob/tree/commit API calls are made and the function returns `Unit.right()` with `pendingCommit` left as `PendingCommit.None`.
- A file exceeding the size ceiling fails fast with `FileTooLarge`, without attempting the upload.
  - *Given* a dirty set containing `"assets/large.md.stek"` whose cached byte length is `90_000_000`, *When* `commit()` runs, *Then* it returns `DomainError.GitError.FileTooLarge("assets/large.md.stek", 90_000_000, 75_000_000).left()` before any `POST .../git/blobs` call for that path.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`

##### Task 3.1.1a: `createBlob(hostConfig, content): Either<DomainError, String>` (~4 min)
- Base64-encode via `kotlin.io.encoding.Base64.Default.encode(content.encodeToByteArray())`; POST via Ktor `HttpClient`; parse `GitBlobResponse`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`

##### Task 3.1.1b: Size-ceiling guard before blob creation (~3 min)
- Check UTF-8 byte length against a `MAX_BLOB_BYTES = 75_000_000` constant before calling `createBlob`; short-circuit with `FileTooLarge`.
- Files: same

##### Task 3.1.1c: `buildTree(hostConfig, baseSha, blobShas, deletedPaths): Either<DomainError, String>` (~4 min)
- Assembles `GitTreeRequest(baseTree = baseSha, tree = ...)` mixing write entries (`sha = blobSha`) and delete entries (`sha = null`); POST; parse `GitTreeResponse.sha`.
- Files: same

##### Task 3.1.1d: `createCommitObject(hostConfig, treeSha, parentSha, message): Either<DomainError, String>` (~4 min)
- POST `GitCommitRequest`; parse `GitCommitResponse.sha`. `message` built via the same `buildCommitMessage`-equivalent template used on desktop (reuse `GitConfig.commitMessageTemplate`).
- Files: same

##### Task 3.1.1e: `commit()` orchestrator wiring the above + empty-set skip + `setPendingCommit` (~5 min)
- Reads `getDirtySnapshot()`; if empty, return success no-op; else run 3.1.1a–d in sequence, then call `PlatformFileSystem.setPendingCommit(commitSha, treeSha)`.
- Files: same

#### Story 3.1.2: `fetch()` and `push()` detect and apply the ref update
**As** `WasmGitRepository.fetch()`/`push()`, **I want** to check the remote ref and, if unchanged, advance it to the pending commit, **so that** the push completes when there's no conflict.
**Acceptance Criteria**:
- `fetch()` reports no remote changes when the ref SHA matches the local `baseSha`.
  - *Given* local `baseSha = "8f3c1a9"` and `GET .../git/ref/heads/main` returning `{"object":{"sha":"8f3c1a9"}}`, *When* `fetch(config)` is called, *Then* it returns `FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right()`.
- `fetch()` reports remote changes with an accurate commit count via the compare API.
  - *Given* local `baseSha = "8f3c1a9"` and the remote ref now at `"c0ffee2"`, and `GET .../compare/8f3c1a9...c0ffee2` returning `{"ahead_by":2,"files":[...]}`, *When* `fetch(config)` is called, *Then* it returns `FetchResult(hasRemoteChanges = true, remoteCommitCount = 2).right()`.
- `push()` PATCHes the ref to the staged commit SHA when there's nothing to merge.
  - *Given* `pendingCommit = PendingCommit.Staged(commitSha = "c0ffee1", treeSha = "7ea5e11")` and no remote changes (per the prior `fetch()`), *When* `push(config)` is called, *Then* `PATCH .../git/refs/heads/main` is sent with `{"sha":"c0ffee1","force":false}`, and on `200`, `PlatformFileSystem.clearDirtySet(newBaseSha = "c0ffee1")` is called (which also resets `pendingCommit` to `PendingCommit.None`).
- A `409`/`422` on the final PATCH (race between fetch's ref-check and this PATCH) is treated as a conflict, not a generic push failure.
  - *Given* the PATCH returns HTTP `409`, *When* `push(config)` handles the response, *Then* it returns `DomainError.GitError.MergeConflict(conflictCount = 1, conflictPaths = listOf("<unknown — remote advanced during push>")).left()` and does **not** clear the dirty set.
- The ref-update PATCH and the follow-up checkpoint-clear are covered by the per-remote `GitWriteLock` — two tabs pushing the same remote at the same moment cannot interleave the PATCH with the checkpoint-clear write.
  - *Given* two simulated concurrent `push(config)` calls for the same `lockNameFor(remoteUrl)` (same style of same-tab-concurrency test as Story 2.3.1's), *When* both run, *Then* the second call's `advanceRef`/`clearDirtySet` sequence does not begin until the first has fully completed (success or failure) and released the lock.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`

##### Task 3.1.2a: `fetchRefSha(hostConfig): Either<DomainError, String>` (~3 min)
- `GET .../git/ref/heads/{branch}`; parse `GitRefResponse.object.sha`; `404` maps to a distinct "branch not found" `DomainError.GitError.FetchFailed`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`

##### Task 3.1.2b: `fetchCompareDelta(hostConfig, baseSha, headSha): Either<DomainError, GitCompareResponse>` (~3 min)
- `GET .../compare/{base}...{head}`; parse into `GitCompareResponse`.
- Files: same

##### Task 3.1.2c: `fetch()` orchestrator (~4 min)
- Calls 3.1.2a, compares to local `baseSha`; if different, calls 3.1.2b for `remoteCommitCount`; returns `FetchResult`.
- Files: same

##### Task 3.1.2d: `advanceRef(hostConfig, commitSha): Either<DomainError, Unit>` with 409/422 handling (~4 min)
- `PATCH .../git/refs/heads/{branch}` with `force=false`; `409`/`422` → `MergeConflict`; other non-2xx → `PushFailed`.
- Files: same

##### Task 3.1.2e: `push()` orchestrator, wrapping the write-critical-section in `GitWriteLock.withLock` (~5 min)
- Reads `pendingCommit` from the marker; if `PendingCommit.None` (nothing committed), no-op success **without acquiring the lock** (nothing to serialize against another tab). If `PendingCommit.Staged(commitSha, treeSha)`: call `GitWriteLock.withLock(GitWriteLock.lockNameFor(hostConfig.remoteUrl)) { advanceRef(hostConfig, commitSha) then PlatformFileSystem.clearDirtySet(newBaseSha = commitSha) on success }` — the ref PATCH (3.1.2d) and the checkpoint-clear are the two operations the lock must cover together, per Epic 2.3's "guarantees and known gaps" note. The lock is released (by `withLock`'s own `finally`) whether `advanceRef` succeeds, returns `MergeConflict`, or returns `PushFailed` — never held past this single `push()` call.
- Files: same

---

### Epic 3.2: GitLab single-call path

#### Story 3.2.1: `push()` (GitLab) builds and sends the `actions` array in one call
**As** `WasmGitRepository.push()` on a GitLab-hosted graph, **I want** a single atomic commit API call, **so that** GitLab users never hit the GitHub sequence's partial-failure window at all.
**Acceptance Criteria**:
- A dirty set with one write and one delete produces one `POST .../repository/commits` call with two actions.
  - *Given* `GitHostConfig(GITLAB, "tstapler-notes", "wiki", "main", token)` and dirty set `{"pages/Foo.md": WRITE, "pages/Bar.md": DELETE}`, *When* `push(config)` runs (GitLab path), *Then* exactly one `POST` is made with `actions = [{"action":"update","file_path":"pages/Foo.md","content":"...","encoding":"base64","last_commit_id":"..."}, {"action":"delete","file_path":"pages/Bar.md","last_commit_id":"..."}]` and `start_sha = baseSha`.
- On success, the dirty set clears and `baseSha` advances to the new commit id.
  - *Given* the API returns `{"id":"a1b2c3d4",...}` with HTTP `201`, *When* `push()` handles the response, *Then* `PlatformFileSystem.clearDirtySet(newBaseSha = "a1b2c3d4")` is called.
- A `create`-vs-`update` action is chosen correctly based on whether the path existed at `baseSha`.
  - *Given* `pages/NewPage.md` is a dirty `WRITE` entry for a path that did **not** exist in the base tree, *When* the action is built, *Then* `action = "create"` (no `last_commit_id`); for a path that did exist, `action = "update"` with `last_commit_id` set.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`

##### Task 3.2.1a: `resolveActionType(path, existedAtBase): String` helper (~2 min)
- `"create"` if the path wasn't in the last-known remote tree snapshot, else `"update"`; `"delete"` for `DirtyOp.DELETE`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`

##### Task 3.2.1b: `buildGitLabActions(dirtySet, baseFileShas): List<GitLabCommitAction>` (~4 min)
- Maps each dirty entry to a `GitLabCommitAction`, base64-encoding write content, setting `last_commit_id` from a per-file "known at base" map (see Story 3.3.2 for how that map is populated).
- Files: same

##### Task 3.2.1c: `pushViaGitLab(hostConfig): Either<DomainError, Unit>`, wrapping the write-critical-section in `GitWriteLock.withLock` (~6 min)
- Empty-dirty-set skip (parity with GitHub path) **without acquiring the lock**. Otherwise: `GitWriteLock.withLock(GitWriteLock.lockNameFor(hostConfig.remoteUrl)) { build GitLabCommitRequest; POST; on 201 clear dirty set; on conflict-shaped 4xx map to MergeConflict (see Story 3.2.3 for the exact detection rule) }` — same lock name (`stele-write-${urlSafeRemote}`) and same coverage as the GitHub path's Task 3.1.2e, so a GitHub-hosted and a GitLab-hosted push for two *different* remotes never contend with each other, but two tabs pushing the *same* GitLab remote do.
- Files: same

##### Task 3.2.1d: `commit()` (GitLab) as local-only no-op (~2 min)
- When `hostConfig.type == GITLAB`, `commit()` only validates the dirty set is non-empty and returns success — no network call (Pattern Decision "Commit/push split (GitLab)").
- Files: same

##### Task 3.2.1e: Explicitly verify GitLab write-path CORS/credentials-mode (~2 min config + verification deferred to Story 6.3.1)
- `research/pitfalls.md` §2 flags GitLab's write-path CORS as the "bigger open risk" for this project: token-authenticated requests (no cookies) should land in GitLab's permissive wildcard-origin CORS bucket, but only if the request does **not** send `credentials: 'include'` — a wildcard origin plus credentials is mutually exclusive per the CORS spec, and GitLab has a history of write-endpoint-specific CORS bug reports. Configure/confirm the shared Ktor `HttpClient` (js/wasmJs engine) does not set `credentials: 'include'` on any request (Ktor's default JS engine fetch config does not opt into credentialed requests, so this task's job is to confirm that default holds for this client instance, not to add new config unless the confirmation fails). This task does not itself perform network verification against the real API — that is Story 6.3.1's new acceptance criterion below, which is the actual, concrete verification point (not left to live only in `research/pitfalls.md`).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt` (HttpClient config)

---

#### Story 3.2.2: GitLab `fetch()`/`merge()` detect remote changes and conflicts before commit, at the same point in the flow as GitHub's
**As** a web user syncing against a GitLab remote, **I want** conflict detection to happen at the same `fetch()`/`merge()` point in the sync flow that GitHub's does, **so that** non-overlapping remote changes on GitLab auto-merge (Success Metric e) and overlapping changes surface `ConflictPending` *before* a doomed push attempt, not only as a post-hoc push failure.
**Context (resolves the gap flagged in adversarial review)**: Epic 3.2 previously specced only `push()` (Story 3.2.1) for GitLab, leaving `fetch()`/`merge()` GitHub-only (Task 3.1.2a–c). `Task 1.2.2c` already builds `GitLabCompareResponse`/`GitLabDiffEntry` for exactly this purpose but nothing called them. This story wires them in, reusing the same host-agnostic `partitionConflicts(localPaths, remotePaths)` pure function (Task 3.3.1a) GitHub uses — the auto-merge/conflict partition logic itself was already written in terms of `Set<String>` file paths with no GitHub-specific assumptions; only the "get the remote's changed-path set" step was missing a GitLab implementation.
**Sequencing note**: this story's Task 3.2.2c consumes `partitionConflicts` (Task 3.3.1a) and `buildConflictFiles` (Task 3.3.2a), both defined under Epic 3.3, which the Dependency Visualization diagram places *after* Epic 3.2. In implementation order, pull those two specific tasks (both pure, host-agnostic functions with no GitHub-specific dependencies) forward alongside Epic 3.2, or implement Epic 3.1/3.2/3.3's pure-logic tasks (3.1.1a's `partitionConflicts`-adjacent pieces, 3.3.1a, 3.3.2a) as a shared first pass before either host's `merge()` orchestrator — this plan does not force a strict Epic-numbered execution order for same-phase epics that share primitives.
**Acceptance Criteria**:
- `fetch()` (GitLab) reports remote changes via GitLab's compare API, matching `FetchResult`'s existing contract.
  - *Given* local `baseSha = "8f3c1a9"` and `GET /projects/:id/repository/compare?from=8f3c1a9&to=main` returning `{"commits":[{"id":"c0ffee2"}],"diffs":[{"new_path":"pages/Bar.md"}]}`, *When* `fetch(config)` is called against a `GitHostConfig(GITLAB, ...)`, *Then* it returns `FetchResult(hasRemoteChanges = true, remoteCommitCount = 1).right()` — the same return shape GitHub's `fetch()` produces, so `GitSyncService.sync()` (unmodified) branches into `Merging` identically for both hosts.
  - *Given* `from == to` (no remote changes), *When* `fetch(config)` is called, *Then* it returns `FetchResult(hasRemoteChanges = false, remoteCommitCount = 0).right()`.
- `merge()` (GitLab) partitions conflicting vs. non-overlapping paths using the same `partitionConflicts` function GitHub uses, against `GitLabCompareResponse.diffs.map { it.newPath }` as the remote-changed-path set.
  - *Given* local dirty set `{"pages/Foo.md"}` and GitLab compare diffs `[{"new_path":"pages/Bar.md"}]`, *When* `merge(config)` is called, *Then* it returns a non-conflicting `MergeResult(hasConflicts = false, changedFiles = listOf("pages/Bar.md"))`, matching GitHub's non-overlapping-case contract (Story 3.3.1).
  - *Given* local dirty set `{"pages/Foo.md"}` and GitLab compare diffs `[{"new_path":"pages/Foo.md"}]`, *When* `merge(config)` is called, *Then* it returns `Either.Left(DomainError.GitError.MergeConflict(conflictCount = 1, conflictPaths = listOf("pages/Foo.md")))`, matching GitHub's overlapping-case contract (Story 3.3.2) — so `GitSyncService.sync()` sets the identical `SyncState.ConflictPending(listOf(ConflictFile(...)))` for both hosts.
- Unlike GitHub, GitLab's non-overlapping auto-merge requires **no local tree rebuild** — a remote-only-changed path that the local push isn't touching needs no `action` entry in the GitLab commits request at all (Task 3.2.1b already only builds actions for locally-dirty paths); GitLab's own `start_sha`-based commit creation preserves untouched files. `mergeAndCommit()`'s GitLab branch is therefore a no-op beyond re-confirming the partition (no equivalent of GitHub's Task 3.3.1b/c raw-content-fetch-and-rebuild).
  - *Given* the non-overlapping case above, *When* `WasmGitRepository.merge(config)` (GitLab) completes, *Then* no `pages/Bar.md` content fetch occurs and `pendingCommit` (if any was staged) is left as computed by `commit()` — untouched.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitRepository.kt`

##### Task 3.2.2a: `fetchGitLabCompareDelta(hostConfig, baseSha, headBranch): Either<DomainError, GitLabCompareResponse>` (~3 min)
- `GET /projects/:id/repository/compare?from={baseSha}&to={branch}`; parse into `GitLabCompareResponse` (Task 1.2.2c).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`

##### Task 3.2.2b: `fetch()` (GitLab) orchestrator (~3 min)
- Mirrors Task 3.1.2c's structure: calls 3.2.2a, derives `hasRemoteChanges`/`remoteCommitCount` from `commits.isNotEmpty()`/`commits.size`, returns `FetchResult`.
- Files: same

##### Task 3.2.2c: `merge()` (GitLab) orchestrator reusing `partitionConflicts` (~4 min)
- Calls `partitionConflicts(getDirtySnapshot().keys, compareResponse.diffs.map { it.newPath }.toSet())`; on any conflicting paths, builds `ConflictFile`s via the same `buildConflictFiles` helper (Task 3.3.2a) and returns `MergeConflict`; on none, returns `MergeResult(hasConflicts = false, changedFiles = nonOverlapping.toList())` with no tree-rebuild step.
- Files: same

##### Task 3.2.2d: `WasmGitRepository.fetch`/`merge` host dispatch (~2 min)
- `when (hostConfig.type) { GITHUB -> ...existing...; GITLAB -> ...3.2.2b/c... }` — same dispatch shape Task 4.1.1c already uses for `commit`/`push`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitRepository.kt`

---

#### Story 3.2.3: GitLab `push()`-time conflict responses route to the same `ConflictPending` path as GitHub's
**As** a web user whose GitLab remote changed in the narrow window between `merge()`'s pre-check and `push()`'s actual write, **I want** that race to still surface as a conflict I can resolve, **not** a generic push failure, **so that** GitLab users get the same conflict UX guarantee GitHub users do (Success Metric d is host-agnostic, not GitHub-only).
**Context**: Story 3.2.2 makes `merge()` the *primary* GitLab conflict-detection point (matching GitHub's timing), but per-file `last_commit_id` + `start_sha` on the commits POST itself (Task 3.2.1b/c) remains a *second, authoritative* check for this race window — exactly the same "push()'s 409/422 is a second, authoritative conflict signal" role GitHub's `advanceRef` plays (Pattern Decision "Commit/push split (GitHub)"). **Response shape** (per `research/stack.md` §2 — confirm exact status/body against a live GitLab instance in Story 6.3.1, since this is not yet verified end-to-end): GitLab's commits API returns HTTP `400 Bad Request` (not `409` — GitLab does not use 409 on this endpoint) with a JSON body `{"message": "<string>"}` for both a stale `last_commit_id` on a touched file and other validation failures (e.g. malformed `encoding`). Because a bare status code can't distinguish "conflict" from "malformed request," detection is conservative: any `400` from the commits POST that is **not** independently attributable to a request-shape bug caught by local validation (e.g. every dirty file has a well-formed `GitLabCommitAction`) is treated as a conflict — fail toward prompting the user to resolve rather than silently dropping the write.
**Acceptance Criteria**:
- A `400` response from the commits POST maps to `MergeConflict`, not `PushFailed`.
  - *Given* the commits POST returns HTTP `400` with body `{"message": "pages/Foo.md: file has changed since last retrieval"}`, *When* `pushViaGitLab` handles the response, *Then* it returns `DomainError.GitError.MergeConflict(conflictCount = 1, conflictPaths = listOf("pages/Foo.md")).left()` (path extracted from the message when present; falls back to the full set of touched paths when the message doesn't name one) and does **not** clear the dirty set or release-then-forget the lock without returning the conflict.
- The resulting `MergeConflict` reaches `SyncState.ConflictPending` via the same unmodified `GitSyncService` path GitHub's does.
  - *Given* `GitSyncService.sync()` calls `gitRepository.push(config)` (GitLab) and it returns `Either.Left(MergeConflict(...))`, *When* `sync()`'s `push().onLeft { err -> _syncState.value = SyncState.Error(err) }` runs — **note**: unlike the `merge()`-time conflict path (which sets `SyncState.ConflictPending` explicitly, `GitSyncService.kt` line ~205), a push-time `MergeConflict` currently falls into the generic `SyncState.Error(err)` branch, same as any other push failure, because `sync()`'s push `.onLeft` doesn't special-case `MergeConflict` the way it special-cases `AuthFailed`. *Then* this plan explicitly specs the fix: `GitSyncService.sync()`'s `push()` `.onLeft` block (line ~222) gains the same kind of type-check branch already used for `AuthFailed`/`CredentialExpired`: `if (err is DomainError.GitError.MergeConflict) { _syncState.value = SyncState.ConflictPending(...) } else { _syncState.value = SyncState.Error(err) }` — this is a small, additive, precedent-consistent change to `GitSyncService.kt` (not a violation of ADR-017's "zero changes to `GitSyncService`" framing, which was about the *call structure*/*integration shape*, not a prohibition on ever touching the file; see Story 1.3.3 below, which needs the identical kind of branch for `RateLimited`). Since `ConflictFile` needs `filePath`/`wikiRelativePath` and `push()`'s `MergeConflict` only carries `conflictPaths: List<String>`, this branch constructs `ConflictFile(filePath = p, wikiRelativePath = p, hunks = emptyList())` per path — same shape Task 3.3.2a already produces for `merge()`-time conflicts.
- Story 6.3.1's gated live GitLab test explicitly forces this race (stale `last_commit_id`) and asserts the real response shape.
  - *Given* the live test pre-modifies the target file via a second, out-of-band API call to advance its `last_commit_id` before the test's own push, *When* the test's push runs, *Then* it asserts the actual HTTP status/body GitLab returns and that `pushViaGitLab` correctly classifies it as `MergeConflict` — confirming or correcting this story's `400`-status assumption against the real API rather than only a mocked one.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`

##### Task 3.2.3a: Conflict-vs-other-400 classification in `pushViaGitLab` (~4 min)
- Parse the `{"message": string}` body; extract a file path if the message contains one of the dirty paths as a substring, else fall back to `conflictPaths = <all touched paths>`; treat every other-than-clearly-a-different-status-code failure conservatively as `MergeConflict` per the rationale above.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`

##### Task 3.2.3b: `GitSyncService.sync()` push `.onLeft` gains a `MergeConflict` branch (~3 min)
- Mirrors the existing `AuthFailed`/`CredentialExpired` branch structure at the `push()` call site (line ~222): `if (err is DomainError.GitError.MergeConflict) SyncState.ConflictPending(err.conflictPaths.map { ConflictFile(it, it, emptyList()) }) else SyncState.Error(err)`. This benefits GitHub's push-time 409/422 conflicts identically (they were previously also mis-routed into generic `SyncState.Error`) — a latent gap this story's GitLab investigation surfaced for both hosts, not just GitLab.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`

##### Task 3.2.3c: Live-test race scenario for Story 6.3.1 (~4 min)
- Add the stale-`last_commit_id` forcing step described in the acceptance criteria above to the GitLab live test case (Task 6.3.1d).
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/git/WasmGitWriteServiceLiveTest.kt`

---

### Epic 3.3: Conflict detection + file-path auto-merge

#### Story 3.3.1: Non-overlapping remote changes auto-merge without user intervention
**As** a web user whose remote changed in a different file than the one I edited, **I want** my push to succeed automatically, **so that** I'm not interrupted for a conflict that isn't real.
**Acceptance Criteria**:
- Local dirty paths and remote compare-delta paths with zero intersection auto-merge.
  - *Given* local dirty set `{"pages/Foo.md"}` and remote compare delta changed paths `{"pages/Bar.md"}`, *When* `partitionConflicts(localPaths, remotePaths)` runs, *Then* it returns `conflicting = emptySet()`, `nonOverlapping = setOf("pages/Bar.md")`.
- For GitHub, auto-merge fetches the remote content for non-overlapping paths and rebuilds the tree against the new remote head as base, before creating a new commit.
  - *Given* the case above with new remote head `"c0ffee2"`, *When* `mergeAndCommit()` runs, *Then* it fetches `pages/Bar.md`'s content at `c0ffee2` via a raw-content GET, writes it into `PlatformFileSystem` cache/OPFS (so `graphLoader.reloadFiles(["pages/Bar.md"])` — called next by `GitSyncService`, unmodified — picks up real content), and creates a **new** commit object whose tree layers `{pages/Foo.md (local blob), pages/Bar.md (remote blob)}` on top of base tree `c0ffee2`, replacing `pendingCommit` with a fresh `PendingCommit.Staged` value for the new commit/tree SHAs.
- `merge()` returns `MergeResult(hasConflicts = false, conflicts = emptyList(), changedFiles = listOf("pages/Bar.md"))` for the non-overlapping case, matching `GitRepository.merge`'s contract so `GitSyncService.sync()` (unmodified) proceeds straight to `graphLoader.reloadFiles(...)` then `push()`.
  - *Given* the same scenario, *When* `WasmGitRepository.merge(config)` is called, *Then* it returns exactly that `MergeResult`.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitRepository.kt`

##### Task 3.3.1a: `partitionConflicts(localPaths: Set<String>, remotePaths: Set<String>)` pure function (~2 min)
- `data class ConflictPartition(val conflicting: Set<String>, val nonOverlapping: Set<String>)`; `conflicting = localPaths intersect remotePaths`, `nonOverlapping = remotePaths - conflicting`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`

##### Task 3.3.1b: Fetch remote content for non-overlapping changed paths (~4 min)
- Reuse the raw-content-URL pattern from `PlatformFileSystem.readFileSuspend`/`WasmSectionSyncService` (GitHub raw URL or GitLab `repository/files/{path}/raw`), write results into `PlatformFileSystem`'s cache via a new small internal `applyRemoteContent(path, content)` method (bypasses dirty-tracking — this is a merge write, not a user edit).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

##### Task 3.3.1c: Rebuild tree/commit against new remote head for auto-merge (~5 min)
- Re-run Task 3.1.1c/d with `baseTree = newRemoteHeadTreeSha` and an expanded blob set (local dirty blobs already created + newly-fetched remote blobs); call `PlatformFileSystem.setPendingCommit(newCommitSha, newTreeSha)` to overwrite `pendingCommit` with the new `PendingCommit.Staged` value (the marker never holds two staged commits — the new one supersedes the old).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`

##### Task 3.3.1d: `WasmGitRepository.merge(config)` wiring (~3 min)
- Calls `WasmGitWriteService`'s partition + auto-merge, returns the `MergeResult` shape `GitSyncService` expects.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitRepository.kt`

#### Story 3.3.2: Overlapping files surface as `ConflictPending` and resolve via `resolveConflictBySide`
**As** a web user whose edit collides with someone else's remote edit to the same file, **I want** to see the existing conflict-resolution UI and pick "Keep mine" or "Use remote" per file, **so that** I never silently lose or silently overwrite content.
**Acceptance Criteria**:
- Overlapping paths produce a `ConflictFile` per path with empty hunks (Pattern Decision "Conflict representation").
  - *Given* local dirty set `{"pages/Foo.md"}` and remote changed paths `{"pages/Foo.md"}`, *When* `merge(config)` is called, *Then* it returns `Either.Left(DomainError.GitError.MergeConflict(conflictCount = 1, conflictPaths = listOf("pages/Foo.md")))` and (via `WasmGitRepository`) the underlying `MergeResult` carries `conflicts = listOf(ConflictFile(filePath = "pages/Foo.md", wikiRelativePath = "pages/Foo.md", hunks = emptyList()))`.
  - *Given* that `merge()` call, *When* `GitSyncService.sync()` (unmodified) observes `mergeResult.hasConflicts == true`, *Then* it sets `_syncState.value = SyncState.ConflictPending(listOf(ConflictFile(...)))`, which `ConflictResolutionScreen` renders using only `filePath`/`wikiRelativePath` — no code path reads `hunks`.
- `checkoutFile(config, "pages/Foo.md", MergeSide.REMOTE)` overwrites local content with the remote version.
  - *Given* the conflict above and the user picks "Use remote" for `pages/Foo.md`, *When* `WasmGitRepository.checkoutFile(config, "pages/Foo.md", MergeSide.REMOTE)` is called, *Then* it fetches `pages/Foo.md`'s content at the new remote head and writes it into `PlatformFileSystem` cache/OPFS via the normal `writeFile` path (so it's correctly re-marked dirty for the follow-up commit).
- `checkoutFile(config, ..., MergeSide.LOCAL)` is a no-op (OPFS already has the local content).
  - *Given* the user picks "Keep mine" for `pages/Foo.md`, *When* `checkoutFile(config, "pages/Foo.md", MergeSide.LOCAL)` is called, *Then* no OPFS write occurs and the function returns `Unit.right()` immediately.
- After `resolveConflictBySide` (unmodified `GitSyncService` method) calls `commit()` again, the resolved content is included in a fresh commit object built from the *current* dirty-set snapshot (per ADR-015's "always re-derive" principle) — no `push()` happens yet (matches desktop's `resolveConflictBySide` behavior, which also doesn't push).
  - *Given* `resolveConflictBySide(graphId, mapOf("pages/Foo.md" to MergeSide.REMOTE))` is called, *When* it internally calls `gitRepository.commit(config, message)`, *Then* `WasmGitWriteService.commit()` re-derives the dirty set (now containing `pages/Foo.md`'s remote content) and stages a fresh `PendingCommit.Staged` value, and `_syncState.value` ends at `SyncState.Idle` (not `Success` — push is a separate, later sync trigger).
- The user-visible badge state after merge-commit-without-push is `LocalChangesPending`, not `Idle` — closes `design/ux.md` Surface 2 step 5's explicitly-flagged-as-unverified transition ("the badge must keep showing unsynced state, not falsely imply completion").
  - *Given* the raw `_syncState` above ends at `Idle` immediately after `resolveConflictBySide`'s `commit()` call, and `PlatformFileSystem.dirtyFileCountFlow` is still nonzero (the resolved file(s) remain dirty until the next successful push clears them), *When* `StelekitViewModel.syncState` (Task 4.3.2c's `combine`) recomputes on the next emission, *Then* it emits `SyncState.LocalChangesPending(fileCount)`, not `SyncState.Idle` — this is Task 4.3.2c's generic "nonzero-dirty-set `Idle` upgrades to `LocalChangesPending`" derivation, confirmed here to actually cover the merge-resolution path specifically, not just the plain-edit path it was originally written for. See Task 4.3.2e for the dedicated regression test.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitRepository.kt`

##### Task 3.3.2a: `buildConflictFiles(conflictingPaths): List<ConflictFile>` (~2 min)
- Maps each conflicting path to `ConflictFile(filePath = path, wikiRelativePath = path, hunks = emptyList())`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`

##### Task 3.3.2b: `WasmGitRepository.checkoutFile` REMOTE-side fetch + write (~4 min)
- Reuses Task 3.3.1b's remote-content-fetch helper, but writes via the **normal** `writeFile` (dirty-tracked) path this time, not `applyRemoteContent`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitRepository.kt`

##### Task 3.3.2c: `WasmGitRepository.checkoutFile` LOCAL-side no-op + `markResolved`/`abortMerge` stubs (~3 min)
- `checkoutFile(..., LOCAL)` returns `Unit.right()` immediately. `markResolved` is a no-op bookkeeping call (returns `Unit.right()` — no separate "staged" concept to update). `abortMerge` resets `pendingCommit` to `PendingCommit.None` (clearing any in-progress set-aside-for-merge commit/tree SHAs) without touching the dirty set, per architecture.md's re-derive-from-scratch guidance.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitRepository.kt`

##### Task 3.3.2d: Deleted-vs-edited-remote edge case handling (~4 min)
- When a conflicting path was locally `DELETE`d: `MergeSide.LOCAL` keeps it deleted (no-op, same as the general LOCAL case); `MergeSide.REMOTE` fetches and re-creates the file (resurrection) — same code path as the general REMOTE case, since `writeFile` re-adds the path to cache regardless of its prior deleted state. Add an explicit unit test (Story 6.1) for this, per `research/features.md` §2a.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitRepository.kt`

---

#### Story 3.3.3: Lock in the "Cancel leaves git in `MERGING` state" fix now that `ConflictResolutionScreen` is web-reachable
**As** a web user who cancels merge-conflict resolution, **I want** the abandoned merge to actually be aborted, **so that** the next sync doesn't fail silently — `docs/tasks/git-sync-ux.md`'s Critical/data-integrity finding ("`ConflictResolutionScreen` 'Cancel' leaves git in `MERGING` state; next sync fails silently") was previously JVM/Android-only; this project makes the screen reachable by a new population of web users for the first time (per requirements.md's Success Metrics), so it belongs in this plan even though the underlying fix is cross-platform.
**Grounding (verified against current source — this is a confirm-and-test story, not a re-implementation)**: the fix already landed, in `533fbbae03` ("fix(git-sync): triad review fixes — schema, nav, UX, accessibility"), before this project. `ConflictResolutionScreen.kt`'s "Cancel" button (line ~78-82) no longer leaves the merge hanging — it opens an "Abort merge?" confirmation dialog, whose confirm action (line ~164-170) invokes the screen's `onAbortMerge` callback. `GraphDialogLayer.kt` (line ~232-238) wires that callback to `gitSyncService.abortActiveMerge(id)`, which (`GitSyncService.kt` line ~455-469) calls `gitRepository.abortMerge(config)` and sets `_syncState.value = SyncState.Idle` only on success. What's actually missing, and what this story closes: (a) `docs/tasks/git-sync-ux.md` line 7's checklist item is still unchecked, which reads as "still open" to anyone who hasn't traced the source; (b) `GitSyncServiceTest.kt`'s only `abortActiveMerge` test (`` `abortActiveMerge with no config is a no-op and returns Right Unit` ``, line ~270) covers exclusively the no-config edge case — the actual happy path (a `GitConfig` exists, `gitRepository.abortMerge` is invoked, `_syncState` resolves to `Idle`) and the abort-failure path have no regression test today, on any platform. Since this project is what makes that gap newly reachable by web users, closing the test gap belongs here rather than being left as ambient risk.
**Acceptance Criteria**:
- A regression test exercises the full happy path, not just the no-config edge case.
  - *Given* a `GitConfig` exists for a graph and a stub `GitRepository.abortMerge(config)` returns `Unit.right()`, *When* `GitSyncService.abortActiveMerge(graphId)` is called, *Then* `abortMerge(config)` is invoked exactly once and `syncState.value` becomes `SyncState.Idle`.
  - *Given* the same setup but `abortMerge(config)` returns `Either.Left(DomainError.GitError.CommitFailed("..."))`, *When* `abortActiveMerge(graphId)` is called, *Then* `syncState.value` does **not** transition to `Idle` — a failed abort must never falsely tell the user the merge state was cleared.
- `docs/tasks/git-sync-ux.md` line 7 is checked off with a pointer to the fixing commit and the new regression test, so the checklist stops implying an open data-integrity bug that has, in fact, already shipped.
**Files**: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/git/GitSyncServiceTest.kt`, `docs/tasks/git-sync-ux.md`

##### Task 3.3.3a: Add the happy-path and failed-abort regression tests to `GitSyncServiceTest.kt` (~5 min)
- Extend the existing test-double `GitRepository` pattern already used by the no-config test (line ~270) with a variant that has a resolvable `GitConfig` and a scriptable `abortMerge` result.
- Files: `kmp/src/businessTest/kotlin/dev/stapler/stelekit/git/GitSyncServiceTest.kt`

##### Task 3.3.3b: Check off `docs/tasks/git-sync-ux.md` line 7 (~1 min)
- Mark `- [x]` and append a short note: "fixed in `533fbbae03`; happy-path regression-tested by Task 3.3.3a."
- Files: `docs/tasks/git-sync-ux.md`

---

### Epic 3.4: Retry/backoff + rate-limit handling

#### Story 3.4.1: All `WasmGitWriteService` HTTP calls retry correctly and surface `RateLimited`
**As** a web user pushing several files, **I want** transient rate-limit responses handled automatically, **so that** a normal edit session doesn't fail just because GitHub briefly throttled a rapid sequence of writes.
**Acceptance Criteria**:
- The shared Ktor `HttpClient` is configured with `HttpRequestRetry` honoring `Retry-After` and treating GitHub's 403-with-`Retry-After` as retryable.
  - *Given* a `POST .../git/blobs` call that returns `403` with header `Retry-After: 2` and `documentation_url` indicating abuse detection, *When* the client's `retryIf` clause evaluates it, *Then* the request is retried after ~2 seconds (not treated as a terminal `AuthFailed`).
- Retry exhaustion (after the configured max) surfaces `DomainError.GitError.RateLimited(retryAfterSeconds)`, not a generic `PushFailed`.
  - *Given* 4 consecutive `429` responses each with `Retry-After: 5`, *When* `WasmGitWriteService`'s retry budget is exhausted, *Then* the failing call returns `DomainError.GitError.RateLimited(retryAfterSeconds = 5).left()`.
- Blob creation for multiple dirty files is sequential (or lightly throttled), not fired with the read path's 10-way concurrency.
  - *Given* a dirty set of 8 files, *When* `commit()` creates blobs, *Then* no more than 3 `POST .../git/blobs` calls are in flight simultaneously (verified via a test double counting concurrent in-flight calls).
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`

##### Task 3.4.1a: Configure `HttpRequestRetry` on the shared `HttpClient` (~4 min)
- `install(HttpRequestRetry) { retryOnServerErrors(maxRetries = 4); exponentialDelay(); retryIf { _, response -> response.status.value == 429 || (response.status.value == 403 && response.headers["Retry-After"] != null) } }`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`

##### Task 3.4.1b: Map post-retry-exhaustion failures via the shared `mapHttpFailure` classifier (~3 min)
- Wrap each API call's non-2xx/exception handling with `mapHttpFailure` (Task 3.4.3a): if the last observed status was `429`/`403`-with-`Retry-After`, map to `RateLimited`; if `401`/`403`-**without**-`Retry-After`, map to `CredentialExpired` (Task 3.4.3a); if `409`/`422` at a ref-update/GitLab-commit site, map to `MergeConflict` (unchanged, Task 3.1.2d/3.2.3a); else to the step-appropriate `GitError` (`PushFailed`, `CommitFailed`, `FetchFailed`). A caught `Throwable` where no HTTP response was ever received maps to `NetworkFailure` instead (Task 3.4.1d) — never lumped into the step-appropriate `GitError` case, which is reserved for a *received* erroring response.
- Files: same

##### Task 3.4.1c: Bounded-concurrency blob creation (~4 min)
- Use a small `Semaphore(3)` (kotlinx.coroutines) around each `createBlob` call when iterating the dirty set, replacing any naive `map { async { ... } }` pattern.
- Files: same

##### Task 3.4.1d: Map network-level exceptions (no HTTP response received) to `DomainError.GitError.NetworkFailure` (~3 min)
- Requirements.md's Scope requires distinct network / HTTP-status / rate-limit failure variants; before this task, a rejected-request exception (connection refused, DNS failure, offline mid-request, CORS block — none of which produce an HTTP response to inspect) had no distinguishable mapping and would fall through to a generic step-appropriate `GitError`, indistinguishable from a real server-side rejection.
- Wrap each call site's request-dispatch step in `try/catch (e: Throwable)` (rethrowing `CancellationException` unchanged, per this codebase's coroutine-cancellation convention): `catch (e: Throwable) { if (e is CancellationException) throw e; DomainError.GitError.NetworkFailure(e.message ?: "network request failed").left() }`. This `catch` wraps the request-issuing call *before* any response/status inspection — a response that *was* received (even a `5xx`) is handled by `mapHttpFailure` (Task 3.4.1b), not this catch.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`

---

#### Story 3.4.2: `RateLimited` auto-retry actually reschedules `sync()`/`fetchOnly()` after the backoff delay — resolves pre-mortem P1 #2
**As** a web user whose sync hit a rate limit, **I want** the "Retrying…" badge text to be true, **so that** I don't have to notice and manually re-trigger sync myself.
**Context (resolves pre-mortem P1 #2)**: Story 1.3.3 makes `GitSyncService` set `SyncState.RateLimited(retryAfterSeconds)` when a `GitError.RateLimited` is returned, but nothing schedules a re-invocation of `sync()`/`fetchOnly()` after that delay — the only retry that already happens is Ktor's fixed-budget in-request retry (Task 3.4.1a, 4 attempts inside a single HTTP call), which is already exhausted by the time `RateLimited` is set. Without this story the badge is stuck showing "Retrying…" indefinitely — the UI actively promises self-healing behavior that doesn't exist.
**Design**: `GitSyncService` already owns a private `scope: CoroutineScope` (`SupervisorJob() + PlatformDispatcher.IO`, `GitSyncService.kt` line ~69) that `shutdown()` cancels (line ~496) — exactly the "properly-scoped long-lived class, never `rememberCoroutineScope()`" pattern `CLAUDE.md` requires, and it's already used for `startPeriodicSync`'s `periodicSyncJob` field (lines ~477-486). This story reuses the identical pattern for a new `rateLimitRetryJob: Job?` field, so cancellation on graph-close/tab-navigation is inherited for free from the existing `scope.cancel()` in `shutdown()`.
**Acceptance Criteria**:
- Every `.onLeft` site that sets `SyncState.RateLimited` (the same five call sites Task 1.3.3b touches) also calls a new `scheduleRateLimitRetry(graphId, retryAfterSeconds, retryOperation)` function.
  - *Given* `gitRepository.push(config)` returns `RateLimited(retryAfterSeconds = 30)` during `sync()`, *When* the `.onLeft` handler sets `_syncState.value = SyncState.RateLimited(30)`, *Then* it also calls `scheduleRateLimitRetry(graphId, 30) { g -> sync(g) }`.
- `scheduleRateLimitRetry` cancels any previously-scheduled retry, then launches a new one on `GitSyncService`'s own `scope`.
  - *Given* a prior `rateLimitRetryJob` is still pending, *When* `scheduleRateLimitRetry` is called again (a second rate-limit hit), *Then* the prior job is cancelled before the new one is launched — never two overlapping scheduled retries for the same `GitSyncService` instance.
  - *Given* `retryAfterSeconds = 30` from `sync()`'s push step, *When* the scheduled job fires (after `delay(30_000L)`), *Then* it calls `sync(graphId)` again, re-deriving the dirty set from scratch (per the Rabbit Holes "always re-derive" principle — no partial-resume logic needed).
  - *Given* the call instead originated from `fetchOnly()`'s fetch step, *When* the scheduled job fires, *Then* it calls `fetchOnly(graphId)` again, not `sync(graphId)` — matching the operation that actually hit the limit.
  - *Given* `retryAfterSeconds == null` (header absent), *When* `scheduleRateLimitRetry` computes the delay, *Then* it falls back to a fixed default (`DEFAULT_RATE_LIMIT_RETRY_SECONDS = 60`), consistent with the existing fallback precedent in `WasmSectionSyncService.githubFetch`'s `(1 shl retryCount).coerceAtMost(60)`.
- A manual sync trigger supersedes (cancels) any pending scheduled retry, rather than double-firing.
  - *Given* a `rateLimitRetryJob` is pending, *When* the user taps the sync badge (calling `sync(graphId)` directly — note this is a *different*, non-`RateLimited` state's tap, since Story 5.1.2 makes the `RateLimited` badge itself a tap no-op), *Then* `sync()`'s first action cancels `rateLimitRetryJob` before proceeding — the manual attempt and the scheduled one never both fire for the same graph.
- The scheduled retry is cancelled when the service shuts down (graph closed/switched).
  - *Given* a pending `rateLimitRetryJob`, *When* `GitSyncService.shutdown()` is called (e.g. `GraphManager` tearing down the graph), *Then* `scope.cancel()` cancels `rateLimitRetryJob` as a child coroutine — no retry fires after the graph is gone, no leaked timer, and no `ForgottenCoroutineScopeException` risk (this scope was never a `rememberCoroutineScope()`).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`

##### Task 3.4.2a: Add `rateLimitRetryJob: Job?` field and `scheduleRateLimitRetry(graphId, retryAfterSeconds, retryOperation)` (~5 min)
- `private var rateLimitRetryJob: Job? = null`; `private fun scheduleRateLimitRetry(graphId: String, retryAfterSeconds: Int?, retryOperation: suspend (String) -> Unit) { rateLimitRetryJob?.cancel(); rateLimitRetryJob = scope.launch { delay((retryAfterSeconds ?: DEFAULT_RATE_LIMIT_RETRY_SECONDS) * 1000L); retryOperation(graphId) } }` — `retryOperation` is passed as an adapter around `::sync` (discarding the `Either` return) from `sync()`'s call sites, and around `::fetchOnly` from `fetchOnly()`'s call site, so one helper serves both without duplicating scheduling logic.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`

##### Task 3.4.2b: Call `scheduleRateLimitRetry` from every `RateLimited`-setting branch added in Task 1.3.3b (~3 min)
- Sequence directly alongside Task 1.3.3b (same five call sites) so both land as one coherent edit.
- Files: same

##### Task 3.4.2c: Cancel `rateLimitRetryJob` at the top of `sync()` and `fetchOnly()` (~2 min)
- One-line `rateLimitRetryJob?.cancel()` at the start of each function, before any network call — ensures a manual trigger always supersedes a pending scheduled retry.
- Files: same

##### Task 3.4.2d: Test — scheduled retry fires after the stated delay and is cancelled by `shutdown()`/a manual call (~5 min)
- Using `TestCoroutineScheduler`/virtual time (matching the `TC-6.4-G`-style pattern this plan already uses in Task 6.1.1b), assert: (a) no re-invocation before `retryAfterSeconds` elapses, (b) exactly one re-invocation at/after that point, (c) `shutdown()` before the delay elapses prevents any re-invocation, (d) a manual `sync()` call before the delay elapses cancels the pending one (no double-fire).
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/GitSyncServiceRateLimitRetryTest.kt`

---

#### Story 3.4.3: 401/403-without-`Retry-After` responses map to `CredentialExpired`, not a generic step failure
> **Note for reviewers**: this story deliberately routes web's 401/403 handling to `CredentialExpired`, not `AuthFailed` — see **Design** below for why. Searching this story for the literal string `AuthFailed` will not find it; that's intentional, not a missing branch.

**As** a web user whose session-scoped PAT has expired or was entered incorrectly, **I want** a distinguishable "please re-enter your PAT" state instead of a generic push/commit/fetch failure, **so that** I know what action to take.
**Context (CONCERN #7 from Phase 4 cross-artifact review)**: `design/ux.md` assumes write-path `401`s route to the existing `CredentialExpired` state, but before this story no task specs it — Task 3.1.2d's `advanceRef` handler only special-cases `409`/`422` (conflict), falling through to a generic `PushFailed` otherwise, and every other call site (blob/tree/commit create, ref fetch, compare, GitLab commits POST) has the same gap. This is also pre-mortem finding #3's root cause: a missing/expired token surfaces as a generic error with no actionable message.
**Design**: map straight to `DomainError.GitError.CredentialExpired(message)` (the existing variant, `DomainError.kt`) rather than `AuthFailed`. `AuthFailed` only routes to `SyncState.CredentialExpired` in `GitSyncService` when `config.authType == GitAuthType.GITHUB_OAUTH` (`GitSyncService.kt` line ~153) — web's session-scoped PAT auth is `GitAuthType.HTTPS_TOKEN`, which would never match that branch, silently falling through to generic `SyncState.Error` exactly as pre-mortem finding #3 describes. Returning `CredentialExpired` directly sidesteps that auth-type mismatch instead of trying to extend the existing `AuthFailed`-plus-auth-type special case to a second auth type.
**Acceptance Criteria**:
- A `401` or `403`-**without**-`Retry-After` response (distinguished from the 403-with-`Retry-After` secondary-rate-limit case Task 3.4.1a already retries) at any `WasmGitWriteService` HTTP call site maps to `DomainError.GitError.CredentialExpired(message)`.
  - *Given* a `POST .../git/blobs` call returning `401` with no `Retry-After` header, *When* the response is handled, *Then* it returns `DomainError.GitError.CredentialExpired("Your git host rejected the configured token").left()` — host-neutral copy, not hardcoded to "GitHub," since this same classifier (Task 3.4.3a) is shared by the GitLab commits POST (Task 3.2.1c) and routes both hosts' `401`s to the identical `CredentialExpired` state.
  - *Given* a `403` response **with** a `Retry-After` header, *When* handled, *Then* it is still treated as the existing rate-limit-retryable case (Task 3.4.1a) — never conflated with the credential-expired case.
- `GitSyncService`'s five `RateLimited`-checking branches (Task 1.3.3b) also gain a `CredentialExpired` check, routing to `SyncState.CredentialExpired(graphId)` — the same UI surface already used by JVM/Android's OAuth-expiry case and by `design/ux.md`'s Surface 5 table ("PAT invalid/expired mid-session" row).
  - *Given* `gitRepository.commit(config, message)` returns `Either.Left(DomainError.GitError.CredentialExpired(...))`, *When* the `.onLeft` handler runs, *Then* `_syncState.value = SyncState.CredentialExpired(graphId)`, not `SyncState.Error(...)`.
- A test asserts a missing/empty token produces `CredentialExpired`, not a generic error (per pre-mortem finding #3's own prescribed test).
  - *Given* `PlatformFileSystem.githubToken == null` (or empty) and GitHub responds `401`, *When* a `WasmGitWriteService` call is attempted, *Then* the resulting `SyncState` is `CredentialExpired`, not `Error(PushFailed(...))`.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`

##### Task 3.4.3a: `mapHttpFailure(status, headers, stepName): DomainError.GitError` classifier shared by every HTTP call site (~4 min)
- One helper used by every non-2xx branch across Tasks 3.1.1a/c/d, 3.1.2a/b/d, 3.2.1c: `401`, or `403` without `Retry-After` → `CredentialExpired`; `429`, or `403` with `Retry-After` → handled by Task 3.4.1b's retry-exhaustion path (`RateLimited`); `409`/`422` at the ref/GitLab-commit sites → `MergeConflict` (Task 3.1.2d/3.2.3a, unchanged); else → the step-appropriate `PushFailed`/`CommitFailed`/`FetchFailed`. Network-level exceptions (no response at all) are handled separately by Task 3.4.1d's `NetworkFailure` catch, not by this classifier. Retrofit this into the per-call-site handlers built in Epic 3.1/3.2 rather than duplicating the branch six times.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`

##### Task 3.4.3b: `GitSyncService`'s five `.onLeft` branches (Task 1.3.3b) gain a `CredentialExpired` check (~3 min)
- Sequenced alongside Tasks 1.3.3b/3.2.3b/3.4.2b so all four checks (`RateLimited`, `MergeConflict` at the push site, `CredentialExpired`, the existing `AuthFailed`+`GITHUB_OAUTH` case) land as one coherent edit per call site rather than four colliding ones.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitSyncService.kt`

##### Task 3.4.3c: Missing/empty-token test (~3 min)
- Per this story's acceptance criteria's prescribed test.
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/WasmGitWriteServiceAlgorithmsTest.kt` (or the equivalent double-based test file, matching Epic 6.1's convention)

---

### Epic 3.5: Observability

#### Story 3.5.1: Every write-back attempt logs a terminal outcome with step-level detail, never the PAT
**As** a developer debugging a "my commit never showed up" report, **I want** a log line per attempt naming the outcome and failing step, **so that** I can diagnose without asking the user to reproduce with network tracing.
**Acceptance Criteria**:
- Success logs the outcome and file count, not content.
  - *Given* a successful GitHub push of 3 files with no conflicts, *When* `push()` completes, *Then* `Logger("WasmGitWriteService").info(...)` is called with a message containing `"success"`, `"3 file(s)"`, and the new commit SHA — and does not contain the PAT.
- Auto-merge logs which files were auto-merged.
  - *Given* the non-overlapping auto-merge scenario from Story 3.3.1, *When* it completes, *Then* a log line contains `"auto-merged"` and the list of auto-merged paths.
- Failure logs the specific step and `DomainError` subtype.
  - *Given* `advanceRef` fails with `409`, *When* the error is logged, *Then* the message contains `"step=ref-update"` (or equivalent step marker) and `"MergeConflict"` — never the raw HTTP response body if it could contain echoed request headers.
- **No log call anywhere in the new files interpolates the token variable outside header construction** — enforced by a repo-search task, not just review.
  - *Given* the final diff for this project, *When* `grep -rn "token" kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/` is run and every match is manually classified, *Then* every match is either a parameter name, a header-construction call site (`"Authorization" to "Bearer $token"` / `"PRIVATE-TOKEN" to token`), or a comment — none is inside a `Logger.*`/`println` call or a `DomainError(...).message` string.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`

##### Task 3.5.1a: Add `Logger("WasmGitWriteService")` calls at each terminal branch (~4 min)
- One `logger.info`/`logger.error` call at: success, conflict-detected, auto-merged, failed-with-reason — matching the Observability Requirements' four named outcomes exactly.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`

##### Task 3.5.1b: Manual PAT-leakage grep audit as a documented verification step (~3 min)
- Run the grep from the acceptance criterion above against the finished diff; fix any match that isn't header construction/parameter/comment. Record the check as done in the PR description (Phase 7).
- Files: (verification only, no file changes unless a leak is found)

---

## Phase 4: Reachability — `GitRepository` actual & wiring

### Epic 4.1: `WasmGitRepository` actual

#### Story 4.1.1: `WasmGitRepository` implements the full `GitRepository` interface, mapping working-tree-shaped methods onto the checkout-less REST model
**As** `GitSyncService` (unmodified), **I want** a `wasmJsMain` `GitRepository` it can call exactly like `JvmGitRepository`/`AndroidGitRepository`, **so that** the entire existing sync orchestration, conflict flow, and UI work on web without any wasmJs-specific branching.
**Acceptance Criteria**:
- `isGitRepo(path)` always returns `true` once a `GitConfig` exists for the graph (there is no working-tree concept to check on web).
  - *Given* a `GitConfig` has been saved for graph `"default"`, *When* `WasmGitRepository.isGitRepo(config.repoRoot)` is called, *Then* it returns `true`.
- `init(repoRoot)` and `stageSubdir(config)` are no-ops that always succeed.
  - *Given* any `repoRoot`, *When* `init(repoRoot)` is called, *Then* it returns `Unit.right()` with no network/OPFS side effect.
- `hasDetachedHead(config)` always returns `false` (no detached-HEAD concept over a REST API).
  - *Given* any `config`, *When* `hasDetachedHead(config)` is called, *Then* it returns `false`.
- `removeStaleLockFile(config)` is a no-op success (no filesystem lock file exists on web).
  - *Given* any `config`, *When* `removeStaleLockFile(config)` is called, *Then* it returns `Unit.right()`.
- `log(config, maxCount)` fetches real commit history from the host.
  - *Given* `GitHostConfig(GITHUB, "tstapler", "steno-wiki", "main", token)`, *When* `log(config, maxCount = 10)` is called, *Then* it issues `GET .../commits?sha=main&per_page=10` and maps each entry to `GitCommit(sha, shortMessage, authorName, timestamp)`.
- `clone(url, localPath, auth, onProgress)` returns `NotSupported` on web (Unresolved Question notes the UI-hiding follow-up).
  - *Given* any arguments, *When* `clone(...)` is called, *Then* it returns `DomainError.GitError.NotSupported("web").left()` without making any network call.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitRepository.kt`

##### Task 4.1.1a: Class skeleton + trivial no-op methods (~4 min)
- `class WasmGitRepository(private val configResolver: suspend (GitConfig) -> GitHostConfig?) : GitRepository` (or similar DI shape); implement `isGitRepo`, `init`, `stageSubdir`, `hasDetachedHead`, `removeStaleLockFile` per the acceptance criteria above.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitRepository.kt`

##### Task 4.1.1b: `status(config)` from `getDirtySnapshot()` (~3 min)
- `GitStatus(hasLocalChanges = snapshot.isNotEmpty(), untrackedFiles = emptyList(), modifiedFiles = snapshot.keys.toList())`.
- Files: same

##### Task 4.1.1c: `commit`/`fetch`/`merge`/`push` delegate to `WasmGitWriteService` (~4 min)
- Thin pass-through calls into the corresponding `WasmGitWriteService` methods built in Phase 3, resolving `GitHostConfig` from `config` via `GitHostAdapter`.
- Files: same

##### Task 4.1.1d: `checkoutFile`/`markResolved`/`abortMerge` delegate (already built in Story 3.3.2) (~2 min)
- Wire the methods built in Task 3.3.2b/c into the interface.
- Files: same

##### Task 4.1.1e: `log(config, maxCount)` (~4 min)
- GitHub: `GET .../commits?sha={branch}&per_page={maxCount}`; GitLab: `GET .../repository/commits?ref_name={branch}&per_page={maxCount}`. New small serializable response models added to `GitDataApiModels.kt`/`GitLabCommitModels.kt`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitRepository.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/GitDataApiModels.kt`

##### Task 4.1.1f: `clone` returns `NotSupported` (~2 min)
- Single-line stub per acceptance criterion.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitRepository.kt`

---

### Epic 4.2: `JsGitManager` thin delegate wiring (BUG-005 Phase 2 close-out)

#### Story 4.2.1: `JsGitManager` delegates to `WasmGitWriteService` when configured, else keeps the `NOT_SUPPORTED` fallback
**As** any caller still using the `GitManager` interface (none exist in the UI today, but the interface is public and BUG-005's text names it explicitly), **I want** `commit`/`push`/`pull`/`status`/`isDirty` to actually work when git sync is configured, **so that** BUG-005 Phase 2 is genuinely closed, not just reachable via a different path.
**Acceptance Criteria**:
- With no `GitConfig` saved for the active graph, all five methods keep returning the existing `NOT_SUPPORTED` error (unchanged behavior).
  - *Given* no `GitConfig` exists, *When* `JsGitManager().commit("msg")` is called, *Then* it returns `GitResult.Error("Git sync is not available on the web platform")`, identical to today.
- With a `GitConfig` + PAT configured, `push()` delegates to the same `WasmGitWriteService.push()` used by `WasmGitRepository`.
  - *Given* a saved `GitConfig` for graph `"default"` with a valid PAT, *When* `JsGitManager().push()` is called, *Then* it internally resolves the same `GitHostConfig` and calls the same `WasmGitWriteService.push()` function `WasmGitRepository.push()` calls — not a second, independent implementation.
- `isDirty()` reflects `PlatformFileSystem.dirtyFileCountFlow.value > 0`.
  - *Given* one dirty file, *When* `JsGitManager().isDirty()` is called, *Then* it returns `GitResult.Success(true)`.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/GitManager.kt`

##### Task 4.2.1a: Thread a `GitConfig` resolver + `WasmGitWriteService` instance into `JsGitManager` (~4 min)
- `GitManagerFactory.create()` needs access to the active graph's `GitConfigRepository`/`PlatformFileSystem` — these aren't currently reachable from this file's simple `object`/`class` shape; add the minimal plumbing (e.g. a `JsGitManager(private val graphId: () -> String?, private val configRepository: GitConfigRepository, private val fileSystem: PlatformFileSystem)` constructor) and update `GitManagerFactory.create()`'s call site accordingly.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/GitManager.kt`

##### Task 4.2.1b: Implement `commit`/`push`/`pull`/`status`/`isDirty` delegation with the `NOT_SUPPORTED` fallback preserved (~5 min)
- Each method: resolve config; if absent, return the existing stub error; else delegate to `WasmGitWriteService`/`PlatformFileSystem` and map results to `GitResult`/`Either`.
- Files: same

---

### Epic 4.3: `Main.kt` / `App.kt` wiring

#### Story 4.3.1: web `Main.kt` constructs and passes a real `GitRepository` into `StelekitApp`
**As** a web user, **I want** `SyncStatusBadge`/`GitSetupScreen`/`ConflictResolutionScreen` to actually work, **so that** the feature this whole project exists to build is reachable at all (the single most important wiring change in this plan, per `research/features.md` §0).
**Acceptance Criteria**:
- `Main.kt` no longer omits the `gitRepository` argument.
  - *Given* `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt` after this change, *When* `StelekitApp(...)` is invoked, *Then* it passes `gitRepository = WasmGitRepository(...)` (non-null), where today it passes nothing (defaulting to `null`).
- `App.kt`'s existing null-gated wiring (`gitConfigRepository`, `gitSyncService`, `registerGitSyncService`) activates unmodified.
  - *Given* the above change alone, *When* the web app loads with a configured `GitConfig`, *Then* `GraphManager.activeGitSyncService` emits a non-null `GitSyncService`, and `SyncStatusBadge` in `Sidebar.kt` stops permanently rendering "Set up sync" once configuration exists.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`

##### Task 4.3.1a: Construct `WasmGitRepository` in `Main.kt` (~3 min)
- After `opfsFileSystem` is constructed and GitHub config fields are wired (existing lines 42-59), instantiate `val wasmGitRepository = WasmGitRepository(configResolver = { config -> GitHostAdapter.resolve(config, /* remote URL from config */, PlatformFileSystem.githubToken ?: "") })` or equivalent.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`

##### Task 4.3.1b: Pass `gitRepository = wasmGitRepository` into `StelekitApp(...)` (~2 min)
- One-line addition to the existing `StelekitApp(fileSystem = ..., graphPath = ..., graphManager = ..., attachmentService = ...)` call.
- Files: same

#### Story 4.3.2: `localChangesCountFlow` threads from `PlatformFileSystem` to `StelekitViewModel` with zero JVM/Android behavior change
**As** a web user, **I want** the sidebar to distinguish "saved to this browser" from "safely backed up," **so that** the original bug's core failure mode (looks-idle-but-isn't-synced) can't recur silently.
**Acceptance Criteria**:
- `StelekitApp`/`GraphContent`/`StelekitViewModelDependencies` gain an optional `localChangesCountFlow: StateFlow<Int>? = null` parameter, threaded through unchanged on every existing call site.
  - *Given* `kmp/src/jvmMain/kotlin/dev/stapler/stelekit/desktop/Main.kt`'s existing `StelekitApp(...)` call (which doesn't pass this new parameter), *When* it's built after this change, *Then* it compiles unmodified and `localChangesCountFlow` defaults to `null`.
- `StelekitViewModel.syncState` combines the raw `GitSyncService` state with the dirty count only when non-null.
  - *Given* `localChangesCountFlow == null` (JVM/Android), *When* `syncState` is observed, *Then* it is byte-for-byte identical to today's `activeGitSyncService.flatMapLatest { ... }` output — no `LocalChangesPending` ever appears.
  - *Given* `localChangesCountFlow` emits `3` and the raw `GitSyncService.syncState` is currently `Idle`, *When* `StelekitViewModel.syncState` recomputes, *Then* it emits `SyncState.LocalChangesPending(fileCount = 3)`.
  - *Given* the raw state is `Fetching` (a sync is actively in progress) and `localChangesCountFlow` emits `3`, *When* `syncState` recomputes, *Then* it emits `Fetching` unchanged — `LocalChangesPending` only overrides `Idle`, never an in-progress state.
- web `Main.kt` passes `opfsFileSystem.dirtyFileCountFlow` as this parameter.
  - *Given* the wasmJs wiring, *When* `StelekitApp(...)` is called, *Then* `localChangesCountFlow = opfsFileSystem.dirtyFileCountFlow`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModelDependencies.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`

##### Task 4.3.2a: Add `localChangesCountFlow` param to `StelekitApp` and `GraphContent` (~3 min)
- Optional `StateFlow<Int>? = null` param on both composables (lines ~150 and ~393 of `App.kt`), threaded from the former into the latter's call.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

##### Task 4.3.2b: Add the field to `StelekitViewModelDependencies` and the `GraphContent` construction call (~3 min)
- New `val localChangesCountFlow: StateFlow<Int>? = null` field; pass it through at the `StelekitViewModel(StelekitViewModelDependencies(...))` call site (~line 625).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModelDependencies.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`

##### Task 4.3.2c: Combine in `StelekitViewModel.syncState` (~5 min)
- Replace the current `val syncState: StateFlow<SyncState> = activeGitSyncService.flatMapLatest { ... }.stateIn(...)` with a version that additionally `combine`s `deps.localChangesCountFlow ?: flowOf(0)`, mapping `(rawState, count) -> if (rawState is SyncState.Idle && count > 0) SyncState.LocalChangesPending(count) else rawState`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/StelekitViewModel.kt`

##### Task 4.3.2d: Pass `opfsFileSystem.dirtyFileCountFlow` from web `Main.kt` (~2 min)
- Add to the same `StelekitApp(...)` call updated in Task 4.3.1b.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`

##### Task 4.3.2e: Test — merge-resolution-without-push derives `LocalChangesPending`, not `Idle` (~4 min)
- Dedicated regression test for Story 3.3.2's new acceptance criterion: construct `StelekitViewModel` with a fake `activeGitSyncService` flow that emits `SyncState.Idle` (simulating `resolveConflictBySide`'s post-commit, pre-push state) combined with a `localChangesCountFlow` stub emitting a nonzero count; assert the combined `syncState` StateFlow emits `SyncState.LocalChangesPending(count)`, never `SyncState.Idle`. Complements Task 4.3.2c's existing acceptance criteria (which cover the generic "any nonzero dirty count upgrades `Idle`" case) by pinning the specific merge-resolution scenario so a future refactor of either `resolveConflictBySide` or the `combine` logic can't silently regress this path.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/StelekitViewModelSyncStateTest.kt` (or wherever this plan's Epic 6 testing convention places `StelekitViewModel` unit tests — confirm exact location before adding)

---

## Phase 5: UX Surfacing

### Epic 5.1: `SyncStatusBadge` renders `LocalChangesPending`

#### Story 5.1.1: The badge shows a distinct "N unsynced" indicator instead of nothing
**As** a web user with unsaved-to-remote edits, **I want** a visible, non-alarming indicator, **so that** "no badge" never again means "everything's safe" when it isn't.
**Acceptance Criteria**:
- `LocalChangesPending(fileCount = 2)` renders a visible icon + count, distinct from both `Idle` (nothing) and `Error` (red).
  - *Given* `syncState = SyncState.LocalChangesPending(fileCount = 2)`, *When* `SyncStateBadge` composes, *Then* it renders an icon with `contentDescription` containing `"unsynced"` or `"pending"` and label text `"2 unsynced"` (not blank, not red/error-colored).
- Tapping the badge triggers a manual sync, same as every other non-idle state.
  - *Given* the same state, *When* the badge region is tapped, *Then* `onSyncClick()` is invoked.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SyncStatusBadge.kt`

##### Task 5.1.1a: Add the `is SyncState.LocalChangesPending` branch to `SyncStateBadge`'s `when` (~4 min)
- New `Row` with an existing Material icon (e.g. `Icons.Default.CloudUpload` or reuse `Icons.Default.Sync` with a neutral tint distinct from the spinning-progress states), `contentDescription = "N unsynced changes"`, `Modifier.clickable { onSyncClick() }`, label `"$fileCount unsynced"`.
- Apply a minimum touch-target size to the clickable region — `Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)` (or equivalent padding) around the `Row`, not just the visible icon — per `design/ux.md` Surface 1's touch-target accessibility criterion (the region is reachable from mobile browsers).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SyncStatusBadge.kt`

##### Task 5.1.1b: Update the file's doc comment listing all rendered `SyncState` variants (~2 min)
- Add `LocalChangesPending(n)` to the existing bullet list at the top of the file (lines ~46-51) documenting each state's rendering.
- Files: same

---

#### Story 5.1.2: The badge shows a distinct, non-alarming "retrying automatically" indicator for `RateLimited` — never "tap to retry"
**As a** web user hitting a rate limit, **I want** the badge to visibly differ from a real error, **so that** I don't tap "retry" on something that's already auto-retrying (per `design/ux.md`'s highest-risk-string finding, line 200: "Rate-limited state's badge text never contains 'tap to retry' or implies manual action is useful").
**Acceptance Criteria**:
- `RateLimited(retryAfterSeconds = 5)` renders a visible, neutral-tinted (not red/error-colored) icon + label, distinct from `Error`.
  - *Given* `syncState = SyncState.RateLimited(retryAfterSeconds = 5)`, *When* `SyncStateBadge` composes, *Then* it renders with a `contentDescription` containing "retrying" or "rate limit" — explicitly **not** containing the string "tap to retry" — and label text such as `"Retrying…"` (not the raw retry-after seconds as the primary label; the countdown is a nice-to-have, not a requirement), using a tint distinct from `MaterialTheme.colorScheme.error` (e.g. the same neutral `onSurfaceVariant` tint `CredentialVaultLocked` uses).
  - *Given* the same state, *When* a literal string-content test runs against the composed content description and label, *Then* it asserts `!contains("tap to retry", ignoreCase = true)` — this is the `design/ux.md` line-200 acceptance criterion made concrete and testable, not just a style guideline.
- Tapping the badge while `RateLimited` is active is a **no-op** — it does **not** trigger a manual sync. *(Corrected during Phase 4 cross-artifact review: this bullet previously said tapping "still triggers a manual sync," directly contradicting `design/ux.md`'s Surface 5 table, which states plainly: "No manual retry framing — tapping does not re-trigger an immediate retry (would just re-hit the limit); auto-backoff-retry runs regardless of taps." Resolved in favor of `ux.md`'s framing — it's the safer, better-reasoned position, and is exactly the failure mode the `RateLimited` design exists to prevent.)*
  - *Given* `syncState = SyncState.RateLimited(retryAfterSeconds = 5)`, *When* the badge region is tapped, *Then* `onSyncClick()` is **not** invoked — the tap is inert (a nice-to-have "already retrying automatically" tooltip/toast is acceptable but not required; simplicity wins per the fix's own guidance).
  - *Given* the same state, *When* `syncState` later transitions away from `RateLimited` (e.g. to `Committing`/`Success`/a genuine `Error`), *Then* tapping the badge resumes its normal per-state behavior — this no-op is scoped strictly to the `RateLimited` state, not a permanent change to the badge's click handling.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SyncStatusBadge.kt`

##### Task 5.1.2a: Add the `is SyncState.RateLimited` branch to `SyncStateBadge`'s `when`, with no click handler (~4 min)
- New `Row` mirroring the `CredentialVaultLocked` branch's neutral styling (not `Error`'s red), `contentDescription = "Rate limited — retrying automatically"`, label `"Retrying…"` — **no** `Modifier.clickable { onSyncClick() }` (unlike every other branch in this `when`); tapping this row does nothing, per the corrected acceptance criteria above.
- Even though this row is non-interactive, size it to the same minimum region as Task 5.1.1a's `LocalChangesPending` `Row` (`Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)`) so the badge slot doesn't jump size between states and any assistive-tech targeting of the region stays consistent — per `design/ux.md` Surface 1/5's touch-target accessibility criterion.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SyncStatusBadge.kt`

##### Task 5.1.2b: Add a literal "never contains 'tap to retry'" string-content test, plus a no-op-tap test (~4 min)
- Asserts against the composed `contentDescription`/label text for `RateLimited`, matching `design/ux.md` line 200's acceptance criterion. Also asserts the corrected tap behavior: given `syncState = RateLimited(...)`, when the badge region is tapped/clicked in the test, then `onSyncClick` is never invoked (use a recording lambda / mock and assert zero invocations) — this is the regression test for the contradiction this fix resolves.
- Files: `kmp/src/jvmTest/kotlin/dev/stapler/stelekit/ui/components/SyncStatusBadgeTest.kt` (or wherever this file's existing Roborazzi/Compose tests for other `SyncState` branches already live — confirm exact location before adding)

##### Task 5.1.2c: Update the file's doc comment listing all rendered `SyncState` variants (~2 min)
- Add `RateLimited` to the same bullet list Task 5.1.1b updates.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/SyncStatusBadge.kt`

---

#### Story 5.1.3: `beforeunload` warns before closing/navigating away with unsynced changes
**As** a web user with `LocalChangesPending` showing, **I want** the browser to warn me before I close the tab, **so that** I can't lose track of unsynced work by accidentally navigating away. Closes `design/ux.md` Surface 6 — committed per triad UX review (previously "recommend product decide before Phase 7, not committed"; now committed because it's cheap and it's `design/ux.md`'s own stated closing piece for this project's core "can I tell my data is safe" goal).
**Design**: a `window.addEventListener("beforeunload", ...)` guard, gated strictly on `PlatformFileSystem.dirtyFileCountFlow.value > 0` — the same flow Surface 1's badge already reads, never a second, independently-maintained boolean (per `design/ux.md`'s explicit warning against staleness). `beforeunload` does not support custom UI — this triggers only the browser's own native "Leave site?" dialog; SteleKit does not control its copy or layout.
**Acceptance Criteria**:
- Closing/navigating away from the tab while `dirtyFileCountFlow.value > 0` triggers the browser's native unload-confirmation dialog; closing while it's `0` does not.
  - *Given* `dirtyFileCountFlow.value == 2`, *When* the `beforeunload` event fires, *Then* the listener calls `event.preventDefault()` and sets `event.returnValue = ""` (the cross-browser idiom), causing the browser's native dialog to appear.
  - *Given* `dirtyFileCountFlow.value == 0`, *When* `beforeunload` fires, *Then* the listener does neither — no dialog appears.
- The gating flag is read directly from `PlatformFileSystem.dirtyFileCountFlow` at unload time, not a second "hasUnsavedChanges"-shaped field that could drift from the real dirty set.
- The listener is registered once, scoped to `wasmJs` only — zero JVM/Android/iOS behavior change (`beforeunload` has no equivalent on those platforms, and `localChangesCountFlow` is `null` there per Task 4.3.2c).
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`

##### Task 5.1.3a: Add a `js()` interop function registering the `beforeunload` listener (~4 min)
- `window.addEventListener("beforeunload", (event) => { if (dirtyCount > 0) { event.preventDefault(); event.returnValue = ""; } })`, where `dirtyCount` is a small Kotlin→JS-mirrored variable updated whenever `dirtyFileCountFlow` emits (the JS callback cannot suspend to read a `StateFlow` directly).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`

##### Task 5.1.3b: Wire listener registration into `Main.kt`'s startup sequence, keyed off `opfsFileSystem.dirtyFileCountFlow` (~3 min)
- Collect `dirtyFileCountFlow` in a small coroutine scope to keep the JS-side mirror variable current; register the listener once at startup, alongside Task 4.3.1a/4.3.2d's existing `Main.kt` wiring.
- Files: same

##### Task 5.1.3c: Test — gating decision is correct at the 0/1 boundary (~4 min)
- `beforeunload`/`window` interop cannot run under `commonTest`/`jvmTest`; following Epic 6.1's pure-Kotlin-double precedent, extract the gating decision as a directly-testable pure function (`fun shouldWarnOnUnload(dirtyCount: Int): Boolean = dirtyCount > 0`) and test it at `0`/`1`. A DOM-level assertion of `preventDefault`/`returnValue` behavior is out of scope for this test tier — no headless-browser harness exists in this project's Testing Infrastructure.
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/WasmGitWriteServiceAlgorithmsTest.kt` (co-located with Epic 6.1's other pure-Kotlin-double tests) or a small dedicated file if that one is already large

---

## Phase 6: Testing

### Epic 6.1: Unit tests — pure-Kotlin algorithm doubles (CI gate)
**Precedent**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/sync/WasmSectionSyncServiceTest.kt` — wasmJs `js()`-interop code cannot run under the JVM-based `commonTest`/`jvmTest` compilation, so tests reimplement the pure-Kotlin algorithm as a "test double" that mirrors the production logic exactly, and assert against that. This plan follows the same style for every algorithm in `WasmGitWriteService`/`WasmGitRepository` that isn't already extracted into a directly-testable pure function.

#### Story 6.1.1: Conflict partition, retry/backoff, and size-ceiling logic are unit tested
**Acceptance Criteria**:
- `partitionConflicts` unit tests cover: no overlap, full overlap, partial overlap, empty sets.
  - *Given* `localPaths = setOf("pages/Foo.md", "pages/Baz.md")`, `remotePaths = setOf("pages/Baz.md", "pages/Qux.md")`, *When* `partitionConflicts(localPaths, remotePaths)` runs, *Then* `conflicting == setOf("pages/Baz.md")` and `nonOverlapping == setOf("pages/Qux.md")`.
- A pure-Kotlin retry-backoff double (mirroring Task 3.4.1a/b's branching) is tested for: 429-then-success, 403-with-Retry-After-then-success, exhaustion-after-4-retries → `RateLimited`.
  - *Given* a scripted response sequence `[429, 429, 200]` with `Retry-After: 1` each time, *When* the retry double runs, *Then* it returns success after 2 retries and the total simulated delay is ≥2 seconds (via `TestCoroutineScheduler`, matching `WasmSectionSyncServiceTest`'s `TC-6.4-G` pattern).
- The size-ceiling check is tested at the boundary.
  - *Given* byte lengths `74_999_999`, `75_000_000`, `75_000_001` against `MAX_BLOB_BYTES = 75_000_000`, *When* the guard runs, *Then* only the last value triggers `FileTooLarge`.
**Files**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/WasmGitWriteServiceAlgorithmsTest.kt`

##### Task 6.1.1a: `partitionConflicts` tests (~3 min)
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/WasmGitWriteServiceAlgorithmsTest.kt`

##### Task 6.1.1b: Retry/backoff pure-Kotlin double + tests (~5 min)
- Reimplement Task 3.4.1a/b's branching as a double taking a `suspend (attempt: Int) -> Int?` (status code or null) lambda, matching `WasmSectionSyncServiceTest`'s `TC-6.4-G` shape.
- Files: same

##### Task 6.1.1c: Size-ceiling boundary tests (~2 min)
- Files: same

#### Story 6.1.2: `GitHostAdapter` and dirty-tracking algorithms are unit tested directly (no double needed — pure `commonMain`)
**Acceptance Criteria**: covered by Story 1.1.1/1.1.2's acceptance criteria (these are already directly testable since `GitHostAdapter` lives in `commonMain` per the Pattern Decision — no reimplementation-as-double needed).
**Files**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/GitHostAdapterTest.kt`

##### Task 6.1.2a: Write `GitHostAdapterTest` directly against `GitHostAdapter` (~5 min)
- Direct instantiation/calls — this is real production code under test, not a double, since `GitHostAdapter` has zero wasmJs dependency.
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/GitHostAdapterTest.kt`

#### Story 6.1.3: `checkoutFile`/deleted-vs-edited-remote edge case is unit tested
**Acceptance Criteria**: covered by Story 3.3.2's acceptance criteria, specifically the deleted-vs-edited-remote case.
**Files**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/WasmGitWriteServiceAlgorithmsTest.kt`

##### Task 6.1.3a: Deleted-locally + REMOTE-resolution test double (~3 min)
- Model the resolution decision as a pure function of `(wasDeletedLocally: Boolean, side: MergeSide) -> ResolutionAction` and assert `Resurrect` vs `KeepDeleted` vs `NoOp`.
- Files: same

---

### Epic 6.2: Serialization round-trip tests (real production code, not doubles)

#### Story 6.2.1: Every new `@Serializable` model round-trips correctly
**Acceptance Criteria**: covered by Story 1.2.1/1.2.2's acceptance criteria — these run directly against the production `commonMain` model classes since serialization has no wasmJs/js() dependency.
**Files**: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/model/GitDataApiModelsTest.kt`, `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/model/GitLabCommitModelsTest.kt`

##### Task 6.2.1a: GitHub model round-trip tests (~5 min)
- One test per model class from Story 1.2.1: encode-then-decode identity, plus the exact-field-shape assertions already specified in that story's acceptance criteria.
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/model/GitDataApiModelsTest.kt`

##### Task 6.2.1b: GitLab model round-trip tests (~4 min)
- Same for Story 1.2.2's models.
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/model/GitLabCommitModelsTest.kt`

##### Task 6.2.1c: `DirtySetMarker` round-trip test including the new `PendingCommit` sealed field (~4 min)
- Covers both variants: a marker with `pendingCommit = PendingCommit.None` round-trips to `{"type":"none"}`; a marker with `pendingCommit = PendingCommit.Staged("c0ffee1", "7ea5e11")` round-trips to `{"type":"staged","commitSha":"c0ffee1","treeSha":"7ea5e11"}` and back to an equal `Staged` instance — exercises `kotlinx.serialization`'s sealed-interface polymorphic round-trip, not just a single-shape data class.
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/model/DirtySetMarkerTest.kt`

---

### Epic 6.3: Gated live-API test

#### Story 6.3.1: One manually-triggered live test verifies real GitHub and GitLab API drift
**As** the team, **we want** a non-CI-blocking test that exercises the real GitHub and GitLab write APIs against a disposable repo, **so that** API-shape drift (the risk `requirements.md`'s Feasibility Risks flags explicitly) is caught before a user hits it, without polluting CI with commit history or requiring a PAT in every CI run.
**Acceptance Criteria**:
- The test is skipped by default and only runs when an explicit env var is set.
  - *Given* CI's normal `bazel test //...`/`./gradlew ciCheck` run with `STELEKIT_LIVE_GIT_TEST` unset, *When* the test suite executes, *Then* this test is skipped (not failed, not counted against the CI gate).
- When run manually with `STELEKIT_LIVE_GIT_TEST=1` and real credentials, it resets the test branch to a known base commit before asserting anything.
  - *Given* the env var set and a real PAT for a disposable repo `tstapler/steno-wiki-livetest`, *When* the test starts, *Then* its first action force-resets `livetest` branch to a fixed base SHA (via the same ref-PATCH-with-`force:true` primitive, used only in this test) before pushing new content — so repeated runs never accumulate unbounded history.
  - *Given* that reset, *When* the test pushes one file and asserts the commit landed, *Then* it reads back the new ref SHA (with a short retry for eventual consistency, per `research/pitfalls.md` §7) and confirms it matches the pushed commit.
- The PAT is read from an environment variable, never hardcoded or committed, and the `Authorization`/`PRIVATE-TOKEN` header value is never printed by the test's own assertions/logging.
  - *Given* the test's source, *When* reviewed, *Then* the token is only referenced via `System.getenv("STELEKIT_LIVE_GIT_TEST_PAT")` (or the wasmJs-test equivalent) and never appears in an `assertEquals` failure message or print statement.
- The GitLab live push (Task 6.3.1d) confirms the write-path POST does not hit the CORS-credentials risk `research/pitfalls.md` §2 names as the "bigger open risk" for this project (Task 3.2.1e's config point).
  - *Given* the GitLab live test case runs `POST gitlab.com/api/v4/projects/{id}/repository/commits` via the configured Ktor `HttpClient`, *When* the request completes, *Then* it succeeds without a CORS-preflight rejection — confirming the client is not sending `credentials: 'include'`. *If* it fails with a CORS error instead, the documented fallback is to explicitly set the Ktor JS engine's `fetch` `credentials` option to `"omit"` (revisit Task 3.2.1e) and re-run before this project ships the GitLab path.
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/git/WasmGitWriteServiceLiveTest.kt`

##### Task 6.3.1a: Env-var gate + skip-by-default scaffold (~3 min)
- `@Test fun liveGitHubPush() { if (System.getenv("STELEKIT_LIVE_GIT_TEST") != "1") return }` (or the platform-appropriate `Assumptions`/skip mechanism for the wasmJs test runner).
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/git/WasmGitWriteServiceLiveTest.kt`

##### Task 6.3.1b: Branch-reset-before-run helper (~4 min)
- Small helper calling the real `advanceRef(..., force = true)` against the fixed base SHA, reused at the top of every live test case.
- Files: same

##### Task 6.3.1c: GitHub live push-and-verify case (~4 min)
- Files: same

##### Task 6.3.1d: GitLab live push-and-verify case (~4 min)
- Also asserts the CORS/credentials-mode acceptance criterion added above (the request completes without a CORS-preflight rejection) — this is the concrete, live verification of the risk `research/pitfalls.md` §2 flags and Task 3.2.1e configures for.
- Files: same

##### Task 6.3.1e: Document the manual run command and secret provisioning in the test file's header comment (~2 min)
- Not a separate doc file (per user's CLAUDE.md — no new `.md` docs unless requested) — a KDoc block at the top of the test file itself, covering: which repos are used, how to provision the PAT locally, and the exact command to run it.
- Files: same

---

## Phase 7: Docs close-out

### Epic 7.1: BUG-005 fix-approach correction
**Goal**: `requirements.md`'s Alternatives Considered section already flags that BUG-005's own "Fix Approach" text still describes the stale isomorphic-git plan. Correcting it is explicitly named as an in-scope side effect of closing the bug out.

#### Story 7.1.1: `BUG-005`'s Fix Approach section reflects the shipped design
**Acceptance Criteria**:
- The bug doc's Fix Approach section describes `WasmGitWriteService` + `WasmGitRepository` + `GitHostAdapter` (this plan's actual shape), not isomorphic-git.
  - *Given* `docs/bugs/open/BUG-005-wasm-git-manager-credential-store-stubs.md` before this task, *When* compared after, *Then* the Fix Approach section no longer mentions isomorphic-git as the planned mechanism, and instead references ADR-015 and the `WasmGitRepository`/`WasmGitWriteService` classes actually shipped.
- The bug is moved from `docs/bugs/open/` to `docs/bugs/closed/` (or marked resolved per this repo's existing bug-tracking convention — confirm the exact convention by checking a previously-closed bug doc before making this edit).
**Files**: `docs/bugs/open/BUG-005-wasm-git-manager-credential-store-stubs.md` (or its moved location)

##### Task 7.1.1a: Check the repo's closed-bug convention (~2 min)
- `ls docs/bugs/closed/` (or equivalent) to confirm the exact file-move/status-field convention before editing.
- Files: (read-only)

##### Task 7.1.1b: Rewrite the Fix Approach section and close the bug per that convention (~4 min)
- Files: `docs/bugs/open/BUG-005-wasm-git-manager-credential-store-stubs.md` → closed location

---

#### Story 7.1.2: Promote ADR-017 from `Proposed` to `Accepted` in `docs/adr/`
**As** a maintainer, **I want** `ADR-017` to live alongside `ADR-013`/`ADR-015`/`ADR-016` in the repo's canonical ADR location once this feature ships, **so that** the reachability amendment it documents isn't left stranded in the planning-only `project_plans/` tree after implementation — ADR-017's own `Status` line already says "promote to `docs/adr/` on Phase 5 implementation kickoff," but no task in this plan actually does it.
**Acceptance Criteria**:
- `ADR-017-wasm-git-repository-actual-reachability.md` exists at `docs/adr/ADR-017-wasm-git-repository-actual-reachability.md`, matching where `ADR-013`/`ADR-015`/`ADR-016` already live.
  - *Given* the feature has shipped (Phase 7 close-out, after Story 7.1.1), *When* the ADR is promoted, *Then* the file is copied (not symlinked) from `project_plans/web-git-writeback/decisions/` into `docs/adr/`, and its `Status` line changes from `Proposed (part of web-git-writeback planning; promote to docs/adr/ on Phase 5 implementation kickoff)` to `Accepted`.
  - *Given* the promoted copy, *When* compared to the planning-tree original, *Then* the body (Context/Decision/Rationale/Alternatives/Consequences) is otherwise unchanged — this is a status promotion, not a rewrite.
**Files**: `project_plans/web-git-writeback/decisions/ADR-017-wasm-git-repository-actual-reachability.md` → `docs/adr/ADR-017-wasm-git-repository-actual-reachability.md`

##### Task 7.1.2a: Copy the ADR into `docs/adr/` and update its `Status` line to `Accepted` (~2 min)
- Files: `docs/adr/ADR-017-wasm-git-repository-actual-reachability.md`

---

## §4 — `.stele-dirty-set.json` schema (reference)

Path: `/stelekit/{graphId}/.stele-dirty-set.json` (OPFS, same root as graph content — mirrors ADR-013's `.stele-sections-sync-complete` placement convention).

```json
{
  "version": 1,
  "graphId": "default",
  "baseSha": "8f3c1a9d2e7b4c1a0f9e8d7c6b5a4938271605f4",
  "pendingCommit": { "type": "none" },
  "checkpointedAtMillis": 1752500000000,
  "dirtyFiles": {
    "pages/Foo.md": { "op": "write", "updatedAtMillis": 1752499990000 },
    "pages/Bar.md": { "op": "delete", "updatedAtMillis": 1752499995000 }
  }
}
```

Mid-write-back-attempt, after `commit()` has run but before `push()`, `pendingCommit` looks like:
```json
"pendingCommit": { "type": "staged", "commitSha": "c0ffee1", "treeSha": "7ea5e11" }
```

- `dirtyFiles` keys are **repo-relative** paths (`PlatformFileSystem.readFileSuspend`'s existing derivation: `path.removePrefix("/stelekit/").substringAfter("/")`), never OPFS-absolute.
- `pendingCommit` is a `PendingCommit` sealed value (`none` or `staged`, see Story 2.2.1) — deliberately a single field rather than two independently-nullable strings, so "commit SHA set but tree SHA missing" (or vice versa) cannot be represented, whether by application bugs or hand-edited/corrupted OPFS files. Set to `staged` by `commit()` (GitHub path only) after step 4 succeeds, and reset to `none` only by `push()` after a successful ref update (Story 2.2.1).
- Cleared (`dirtyFiles = {}`, `baseSha` = the new HEAD, `pendingCommit` = `{"type":"none"}`) as the **last** write after a successful ref update/commit — same commit-flag-last ordering as ADR-013, so a crash mid-push leaves the marker showing the pre-push state, correct for "retry re-derives from scratch."
