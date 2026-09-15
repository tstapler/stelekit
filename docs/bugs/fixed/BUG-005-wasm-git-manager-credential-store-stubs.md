# BUG-005: Git Features Unavailable on WASM — GitManager and CredentialStore are Stubs [SEVERITY: High]

**Status**: Fixed
**Discovered**: 2026-06-29 during WASM platform gap closure session
**GitHub Issue**: [#190](https://github.com/stapler/stelekit/issues/190)
**Impact**: Git commit, push, and pull silently fail or return fake success on the web
platform. Credentials entered by the user are never persisted. Users who configure git
sync on web receive no error and no indication that the feature is non-functional, which
risks data loss (no actual commits) and erodes trust.

## Problem Description

The `wasmJs` source set contains stub actuals for both git integration points:

- `GitManager.wasmJs.kt` — all methods return `Unit` or fake success `Either.Right`
  values without performing any git operations.
- `CredentialStore.wasmJs.kt` — `save()` is a no-op; `load()` always returns `null`.

No WASM-native git implementation exists in the project or in the Kotlin ecosystem.
System-process git (used on JVM via `ProcessBuilder`) is unavailable in a browser
sandbox. The JVM `libgit2` bindings (jgit, kgit) cannot target WASM.

The scope of a full fix is **Epic-level**: at discovery time it was assumed to require
porting a pure-JS git implementation into Kotlin/WASM via JS interop, wiring a
WASM-compatible credential store (e.g., the browser `localStorage` or `sessionStorage`
behind an encryption layer), and adapting the git-sync UI to an async, promise-based
git-library API surface, with the OPFS (Origin Private File System) storage layer exposing
a compatible file-system adapter. As shipped (see "Fix Approach" below), no such
pure-JS git library was needed: GitHub and GitLab's REST APIs support the same
commit/push/pull operations without a working tree, which avoided that entire interop
surface.

## Reproduction Steps

1. Open the web app (`bazel build //kmp:web_app`).
2. Open Git Sync settings and configure a remote URL and personal access token.
3. Trigger a manual sync (commit + push).
4. Expected: A commit is created in the local git repo and pushed to the remote.
5. Actual: The sync indicator briefly shows "syncing", then returns to "idle". No commit
   appears on the remote. No error is surfaced to the user.

## Root Cause

Browser-sandboxed environments have no access to system processes or native shared
libraries. All git operations must be reimplemented in JavaScript/WASM. The two stubs
are intentional placeholders from the initial platform scaffolding but were never
replaced with real implementations.

## Files Affected (2 files)

- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/GitManager.kt`
  — WASM actual; all operations silently succeed without performing git work
- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/CredentialStore.kt`
  — WASM actual; credentials are never stored or retrieved

## Fix Approach (as shipped)

No pure-JS git implementation was ported into the WASM target — none was needed, because
GitHub and GitLab both expose REST APIs capable of the same reads/writes without a working
tree. The shipped design (`web-git-writeback` project, ADR-015 + ADR-017) is a pure-Kotlin
REST client wired directly into the existing sync UI, rather than a WASM port of a JS git
library:

1. **`GitHostAdapter`** (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitHostAdapter.kt`,
   `commonMain`) — detects the remote host (GitHub vs. GitLab) from the configured remote
   URL, builds the correct auth headers (PAT-based) and API base URLs for each host, and
   backs the `@Serializable` Git Data API / GitLab commits API request/response models.
   Pure Kotlin, no `js()`/`JsAny` dependency, so it is unit-testable from `commonTest`
   (`GitHostAdapterTest.kt`).
2. **`WasmGitWriteService`** (`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`,
   `wasmJsMain`) — the actual write-back engine. Implements GitHub's 5-step Git Data API
   sequence (blob → tree → commit → ref update, split across `commit()`/`push()` per
   `GitSyncService`'s existing step ordering) and GitLab's single-call commits API,
   plus conflict detection/auto-merge against the dirty-file set, retry/rate-limit
   handling, and observability logging.
3. **`WasmGitRepository`** (`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitRepository.kt`,
   `wasmJsMain`) — the actual integration point. It is a new `wasmJsMain` actual of the
   existing `GitRepository` interface (`git/GitRepository.kt`), wired into `Main.kt`/`App.kt`
   exactly like `JvmGitRepository`/`AndroidGitRepository` are on their platforms. This is
   what makes the fix reach the real UI: `GitSyncService`, `SyncStatusBadge`,
   `GitSetupScreen`, and `ConflictResolutionScreen` already consume `GitRepository` and
   needed zero changes — ADR-017 documents why the original `GitManager`-only integration
   shape (below) would have shipped a working REST client with no UI call sites.
4. **`JsGitManager`** (`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/GitManager.kt`)
   — retained and wired as a thin secondary delegate to the same `WasmGitWriteService`
   instance `WasmGitRepository` uses internally, rather than left stubbed or deleted. This
   closes the bug's literal `GitManager` interface text (`interface GitManager` in
   `platform/GitManager.kt`) at near-zero marginal cost, since both callers share one
   underlying engine.
5. Credentials: the existing `CredentialStore`/`VaultCredentialStore` abstraction
   (`platform/security/CredentialStore.kt`) is used as-is; the `wasmJs` actual
   (`platform/security/CredentialStore.kt` in `wasmJsMain`) persists PATs, closing the
   second half of this bug's title.
6. Git working-tree-shaped `GitRepository` methods with no REST-API equivalent
   (`isGitRepo`, `init`, `hasDetachedHead`, `removeStaleLockFile`, `stageSubdir`) map to
   conservative no-op/constant implementations rather than `NotSupported` errors; only
   `clone()` returns `NotSupported` (out of scope — no working-tree clone is implemented).

**Design record:**
- ADR-015 (`docs/adr/ADR-015-wasm-git-data-api-write-back.md`) — accepted design for
  `WasmGitWriteService`'s write-back mechanics (GitHub Git Data API sequence, GitLab
  single-call path, conflict detection, dirty-file tracking).
- ADR-017 (`docs/adr/ADR-017-wasm-git-repository-actual-reachability.md`) — amends
  ADR-015's integration shape: documents the reachability gap (no UI consumed the
  originally-planned `GitManager`-only path) and the `WasmGitRepository`-as-`GitRepository`-actual
  correction that fixes it, with `JsGitManager` retained as a thin delegate rather than a
  second parallel implementation.

## Verification (as shipped)

1. Configure git sync settings on web (`WasmGitRepository` wired via `GitSetupScreen`).
2. Edit a page and trigger sync via `SyncStatusBadge`.
3. Confirm the commit appears on the remote (GitHub or GitLab) with correct author and
   message, produced via `WasmGitWriteService`'s host-specific write path.
4. Confirm the PAT is not visible in plaintext at rest (`CredentialStore` wasmJs actual).
5. Confirm pull/fetch reflects remote changes in the UI through the existing
   `GitSyncService` `SyncState` machine, with conflicts surfaced via
   `ConflictResolutionScreen`.
6. Confirm `JsGitManager`'s `commit`/`push`/`pull`/`status`/`isDirty` methods delegate to
   the same underlying `WasmGitWriteService` instance (no divergent second implementation).
7. Live, network-gated tests: `WasmGitWriteServiceLiveTest.kt` exercises the real GitHub
   and GitLab APIs end-to-end (see the test file's header comment for provisioning and run
   instructions).

## Related Tasks

- `docs/tasks/git-sync.md` — JVM/Android git sync implementation (web explicitly out of scope)
- `docs/tasks/git-sync-ux.md` — credential management and sync UX improvements
- `docs/adr/ADR-013-wasm-rest-api-opfs-section-sync.md` — OPFS storage model (Phase 2 prerequisite)
