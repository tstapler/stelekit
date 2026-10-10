# Requirements: web-git-writeback

**Date**: 2026-07-14
**Type**: feature addition
**Complexity**: 4 — high-stakes / cross-cutting

## Problem Statement
On the SteleKit web (Kotlin/Wasm) target, users see their edits persist in the SQLite DB (via OPFS) and reflected in the UI, but nothing reaches an "external" store outside the browser sandbox. Investigation this session found the team already has an *Accepted* architecture for this — `ADR-013` (read: fetch graph sections from a GitHub/GitLab/Gitea remote via REST API into OPFS as a cache) and `ADR-015` (write: push local edits back to that remote via the GitHub/GitLab Git Data API) — but only the read half (`WasmSectionSyncService.kt`) is implemented. The write half is not: `JsGitManager` (`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/GitManager.kt`) is still the intentional `BUG-005` "Phase 1 — fail visibly" stub, returning `NOT_SUPPORTED` for every operation (`commit`, `push`, `pull`, `status`, `isDirty`). This is the same root cause behind the original bug report ("pages save to SQLite but never reach an external markdown store"): for web users syncing against a git remote, there is currently no way to get local edits back out at all.

**Note on scope**: a *separate* mechanism exists for importing a real local OS folder via the File System Access API (`pickDirectoryAsync()` → one-time import into OPFS). That flow's current one-shot-import-then-silent-cache-only behavior is a related but distinct gap, explicitly deferred to a follow-up project (see Out of Scope) per user decision this session — this project focuses on finishing the git-remote write-back path the team already designed for.

## Baseline
Today, web users configuring git sync (remote URL + PAT) see a sync indicator that briefly shows "syncing" then returns to idle — `JsGitManager` accepts the call and returns a `NOT_SUPPORTED` error under the hood, but per `BUG-005`'s own reproduction notes this was, before Phase 1, silent fake success; Phase 1 made it fail *visibly* (surfaced as a platform-limitation message), which is where things stand now. No commit is ever created, no push ever happens. `WasmSectionSyncService` can pull sections from a remote (read-only), but there is no code path for getting local changes back out. Desktop/Android's `GitSyncService` (JVM/Android git sync) is unaffected — this gap is web-only.

## Users / Consumers
Web-target SteleKit users who have configured git sync (`GitSetupScreen`) against a GitHub or GitLab remote (Gitea/Forgejo explicitly out of scope per `ADR-013`'s host-detection limitation, which this project inherits) and expect edits made in the browser to reach that remote as commits.

**Correction from Phase 2 research**: `GitManager`/`JsGitManager` is *not* what the real UI depends on — `SyncStatusBadge`, `GitSetupScreen`, and `ConflictResolutionScreen` all call through `GitSyncService`/`GitRepository`, and on web today `gitRepository` is wired to `null` (confirmed in `App.kt` / `kmp/src/wasmJsMain/.../browser/Main.kt`), so that entire UI stack is currently inert on web regardless of what `JsGitManager` does. Wiring `WasmGitWriteService` only into `GitManager` would ship a feature nothing can trigger. A `wasmJsMain` `GitRepository` actual is therefore in scope alongside `WasmGitWriteService` — see Scope below.

## Success Metrics
- A web user with git sync configured against a GitHub repo can edit a page, trigger sync, and see the corresponding commit appear on the remote with correct author/message — verified end-to-end (not just unit-tested against mocked fetch).
- The same for a GitLab remote, using GitLab's single-call commit API path per `ADR-015`.
- `isDirty()` (surfaced through `GitRepository`, not just `JsGitManager`) reflects real local-vs-remote state (backed by the dirty-file tracking `ADR-015` specifies adding to `PlatformFileSystem.kt`), not a stub value, and is actually visible in the UI (see Users/Consumers correction above).
- A conflicting remote change (someone else pushed to the same branch) is detected before commit and surfaces via `DomainError.GitError.MergeConflict` → `SyncState.ConflictPending(List<ConflictFile>)` → `ConflictResolutionScreen`'s existing whole-file `resolveConflictBySide` (LOCAL/REMOTE) flow, matching desktop's conflict UX — verified by a scripted two-writer race in test. *(Corrected from an earlier, wrong reference to `DomainError.ConflictError.DiskConflict`, which is the external-file-vs-editor conflict type, not the git-merge one.)*
- Non-overlapping concurrent changes (different files changed locally vs. remotely) auto-merge without user intervention, per `ADR-015`'s decision.
- No regression to `WasmSectionSyncService`'s existing read path or to JVM/Android `GitSyncService` behavior.

## Appetite
Large (3–6 weeks)
*(Scope must fit the appetite. If it doesn't fit, cut scope — do not move the deadline.)*

## Constraints
- Must implement the architecture already decided in `ADR-013`/`ADR-015` — this is not an open architecture question. Any deviation (e.g. reaching for isomorphic-git) requires a new ADR explicitly superseding `ADR-015`, not a silent re-litigation during planning.
- PAT credential persistence stays out of scope, per `ADR-015`'s explicit acceptance of session-scoped credential entry as a known v1 UX gap (`CredentialStore.wasmJs.kt` remains a no-op store; users re-enter PAT each session). Do not attempt to fix credential persistence as a side effect of this work.
- Gitea/Forgejo hosts fall back to the existing `DomainError.NetworkError.RequestFailed("unsupported git host")`-style error, consistent with `ADR-013`'s stated limitation — do not scope-creep into building a third host adapter.
- `WasmGitWriteService` must be reachable behind the existing `GitManager` interface (`commonMain`) AND behind a new `wasmJsMain` `GitRepository` actual (see Users/Consumers correction — `GitManager` alone has no UI call sites on web) — do not change either interface's shape in a way that breaks JVM/Android/iOS actuals; extend additively if new capability is genuinely needed.
- Server-side proxy commit endpoint (Option C in `ADR-015`) is explicitly deferred to v2 — do not build server infrastructure as part of this project.

## Non-functional Requirements
- **Performance SLO**: Matches `ADR-013`'s NFR-1 spirit — a write-back session for a typical edit batch (a handful of dirty files, not a bulk import) should complete within a few seconds on a normal connection; GitHub's 5-step sequence and GitLab's single-call path should both be responsive enough not to make "sync" feel broken.
- **Scalability**: Dirty-file tracking must not become an O(graph) scan — reuse the bounded/chunked read patterns `CLAUDE.md` already mandates elsewhere in this codebase (dirty set is inherently small — only changed files — so this is a soft constraint, not a hotspot risk, but tree-merge logic for the auto-merge path must not require loading the full remote tree into memory unnecessarily).
- **Security classification**: Internal/local, but this is the highest-security-sensitivity piece of this session's investigation — it handles a user's PAT (bearer token with repo write access) over the network on every write. PAT must only ever be sent to the git host's own API domain (no accidental leakage to third-party endpoints), and must never be logged, including in error messages/telemetry.
- **Data residency**: Not applicable — user's own chosen git remote.

## Scope
### In Scope
- Implement `WasmGitWriteService` (wasmJs) per `ADR-015`'s decision: GitHub 5-step Git Data API sequence (blob → tree → commit → conflict-check → ref update) with GitLab single-call commit API fallback, dispatched by host type via a thin shared `GitHostAdapter` (host detection + auth header + API base URL only — not fetch/response mechanics) per Phase 2 architecture research's recommendation, reused across `WasmSectionSyncService` (read) and this new write path.
- A `wasmJsMain` `GitRepository` actual wiring `WasmGitWriteService` in — required for the feature to be reachable from any UI at all (see Users/Consumers correction).
- Dirty-file tracking implemented entirely inside the `wasmJs` `PlatformFileSystem.writeFile`/`writeFileBytes`/`deleteFile` actuals (not via `GraphWriter`/`markDirty` — `markDirty`'s existing Android-SAF-write-behind contract is semantically incompatible per architecture research), checkpointed to OPFS (`.stele-dirty-set.json`) so it survives reload, cleared last, after a successful push.
- An exclusive per-remote Web Lock (`navigator.locks.request()`) around `push()`'s write-critical-section (the final ref-update PATCH on GitHub, or the single commits POST on GitLab) plus the immediately-following checkpoint-clear write — building this from scratch, since Phase 2 research found ADR-013's Web Locks pattern was never actually implemented anywhere in this codebase despite being described. *(Corrected during planning: the scope originally described here — the full dirty-set-read → push → checkpoint-clear sequence — turned out to be unachievable given `GitSyncService.sync()`'s unmodified 4-independent-suspend-call structure, which `ADR-017` locks in for reachability reasons; a `navigator.locks.request()` callback's lifetime can't span 4 separately-invoked calls without a leak-prone acquire-now/release-later handle. See `ADR-017` §Consequences and `implementation/plan.md`'s "Web Lock scope" Pattern Decision — plus the "Web Lock — guarantees and known gaps" note under its Epic 2.3 — for the corrected, narrower scope and exactly which races it does and doesn't close.)*
- Conflict detection (remote ref moved since local base) and the auto-merge-non-overlapping / surface-conflict-for-overlapping logic `ADR-015` specifies, wired to `DomainError.GitError.MergeConflict` → `SyncState.ConflictPending` → `ConflictResolutionScreen`'s existing whole-file resolution flow (see Success Metrics correction).
- Wire `commit`/`push`/`pull`/`status`/`isDirty` (both `JsGitManager` and the new `GitRepository` actual) to `WasmGitWriteService` when credentials + remote URL are available; otherwise continue returning the existing `NOT_SUPPORTED` error (i.e., this closes out `BUG-005` Phase 2).
- Distinct `Either<DomainError, T>` failure variants for the write path's fetch layer (network / HTTP-status / rate-limit), rather than reusing `WasmSectionSyncService`'s existing `githubFetch` which collapses all failures to a bare `null` — needed for the Observability Requirements below.
- Minimum UX follow-through identified in Phase 2 research needed for the feature to be legible rather than just functional: a distinct "local changes pending" `SyncState` (the existing `Idle` state renders nothing, which would hide unsynced-on-web state from users — directly undermines the original bug report's core complaint), and a `RateLimited` `SyncState`/`GitError` variant.
- Enough test coverage (unit + at least one scripted end-to-end path per `sdd:6-verify`) to demonstrate the Success Metrics above against a real or realistically-mocked GitHub/GitLab API.

### Out of Scope
- **Local-folder live write-through** (File System Access API `FileSystemDirectoryHandle` retained + written on every save) — deferred to a follow-up project (working name: `web-local-folder-livesync`) per this session's explicit decision to sequence git-remote first.
- Paranoid/encrypted-mode (`writeFileBytes`/`readFileBytes`) support for the local-folder path — folds into the same deferred follow-up, since it's a File-System-Access-API concern, not a git-remote one. (Note: paranoid mode over git write-back, i.e. committing already-encrypted `.md.stek` blobs, is naturally in scope here since `WasmGitWriteService` just pushes whatever bytes are dirty — no new encryption work needed.)
- Credential persistence (`CredentialStore.wasmJs.kt` real implementation) — explicitly deferred per `ADR-015`.
- Gitea/Forgejo host support.
- Server-side proxy commit endpoint (`ADR-015` Option C, v2).
- Any change to `WasmSectionSyncService`'s read path beyond what's needed to share host-detection logic.

## Rabbit Holes
- **The 5-step GitHub sequence is not atomic** (`ADR-015` already calls this out): a failure between steps 4 and 6 leaves a partial state (orphaned blob/commit objects, no ref update). Retry logic must correctly re-derive the dirty set from scratch rather than assuming partial progress can be resumed from where it failed.
- **Auto-merge for non-overlapping files**: "non-overlapping" needs a precise definition (file-path-level, per `ADR-015`) — a naive implementation could silently merge two edits to the *same file* that happen not to conflict at a byte level per git's own 3-way merge, producing a worse UX than just asking the user. Stick to `ADR-015`'s stated granularity (file-path overlap, not content-level) rather than reinventing this in planning.
- **Host-detection duplication**: `WasmSectionSyncService` already has GitHub-specific fetch logic; naive host-detection reuse across read and write paths could create hidden coupling. Planning should decide explicitly whether to extract a shared `GitHostAdapter`-style abstraction or accept some duplication for now — don't let this become an unplanned refactor of the read path.
- **Testing against real GitHub/GitLab APIs**: rate limits and the need for a real PAT make CI testing awkward. Needs an explicit decision in planning (recorded fixtures / mock server vs. a gated live-API test) rather than being discovered mid-implementation.

## Alternatives Considered
Already resolved by `ADR-015` (isomorphic-git rejected — CORS proxy required, ~483KB bundle cost, dual virtual filesystem; server-side proxy deferred to v2; libgit2-via-wasm-pack rejected — 2-4MB bundle cost). Not re-litigated here. Note: `BUG-005`'s own "Fix Approach" section still describes the isomorphic-git plan and is **stale** relative to `ADR-015` — the ADR is authoritative; the bug doc's fix-approach text should be corrected as a side effect of closing it out in Phase 7.

## Feasibility Risks
- GitHub/GitLab API surface or auth requirements may have shifted since `ADR-015` was written — research phase should confirm current API shapes before implementation, not assume the ADR's endpoint list is still exactly correct.
- Conflict/auto-merge logic is the highest-complexity piece and the one most likely to hide edge cases (e.g. a file deleted locally and edited remotely, or vice versa) that `ADR-015` doesn't fully enumerate.
- Session-scoped PAT (no persistence) means every test/demo of the full flow requires re-entering a token — minor but real friction during implementation and verification.

## Observability Requirements
Log write-back attempts and outcomes (success / conflict-detected / auto-merged / failed-with-reason) without ever logging the PAT itself. Enough detail to debug "my commit never showed up" reports: which step of the 5-step (or GitLab single-call) sequence failed, and the resulting `DomainError`. No new alerting/oncall surface (client-side feature).

## Risk Control
No feature-flag infrastructure exists for gradual client-side rollout; this ships scoped to `wasmJs` only (naturally isolated from other platforms) behind the existing "configure git sync" opt-in UX — a user who hasn't set up git sync sees no behavior change. Rollback = revert the `WasmGitWriteService` wiring in `JsGitManager`, reverting to the Phase-1 `NOT_SUPPORTED` stub (current production state) — no data migration concern since nothing new is written until a user explicitly configures and triggers sync.

## Open Questions
*(all three original questions below were resolved by Phase 2 research — see `research/*.md`)*
- ~~Exact current GitHub/GitLab Git Data API and commits-API shapes~~ — resolved in `research/stack.md`: GitHub uses `Authorization: Bearer`, GitLab uses `PRIVATE-TOKEN`; endpoint shapes otherwise match ADR-015.
- ~~Whether to extract a shared host-detection/adapter abstraction~~ — resolved in `research/architecture.md`: yes, a thin `GitHostAdapter` (see Scope).
- ~~Concrete test strategy for exercising real GitHub/GitLab write APIs~~ — resolved in `research/pitfalls.md`: mocked-`fetch()` unit tests as the CI gate, plus one gated/manual live test against a disposable repo that force-resets its branch every run.

**Resolved**: `ADR-016` (Accepted, same date as `ADR-015`) plans to rename `GitManager` → `SyncTransport` and `GitSyncService` → `SyncCoordinator` across all platforms, explicitly triggered by "once ADR-013 and ADR-015 are implemented." No code implements this rename yet. Decision: **defer it**. This project builds against today's `GitManager`/`GitSyncService` interfaces, scoped to `wasmJs` only. The `SyncTransport` rename becomes a separate, purely-mechanical follow-up project (working name: `sync-transport-rename`) once this feature ships and is verified working — it should not expand this project's blast radius into currently-working JVM/Android git sync.
