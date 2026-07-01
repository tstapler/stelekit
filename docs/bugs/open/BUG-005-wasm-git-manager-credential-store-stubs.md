# BUG-005: Git Features Unavailable on WASM — GitManager and CredentialStore are Stubs [SEVERITY: High]

**Status**: Open
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

The scope of a full fix is **Epic-level**: it requires integrating
[isomorphic-git](https://isomorphic-git.org/) (a pure-JS git implementation) via Kotlin/
WASM JS interop, wiring a WASM-compatible credential store (e.g., the browser
`localStorage` or `sessionStorage` behind an encryption layer), and adapting the git-sync
UI to the async, promise-based isomorphic-git API surface. The OPFS (Origin Private File
System) storage layer would also need to expose a file-system adapter compatible with
isomorphic-git's `fs` plugin interface.

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

## Fix Approach (Epic — significant effort)

**Phase 1 — Fail visibly (immediate, 1–2h):**
Return `Either.Left(DomainError.OperationNotSupported("Git sync is not available on web"))` from all `GitManager` methods. Surface this in the git sync UX as a platform limitation message rather than silent fake success. This eliminates the data-loss risk while the full implementation is built.

**Phase 2 — isomorphic-git integration (Epic):**

1. Add `isomorphic-git` as a JS dependency via the Kotlin/WASM `@JsModule` / npm interop.
2. Implement an OPFS-based `fs` adapter so isomorphic-git can read and write files in the
   same storage layer the rest of the WASM app uses.
3. Replace the `GitManager` stub with a Kotlin wrapper around isomorphic-git's
   `clone`, `commit`, `push`, and `pull` APIs using Kotlin coroutine / `Promise`
   bridging.
4. Implement `CredentialStore.wasmJs.kt` using the Web Crypto API to encrypt PATs at
   rest in `localStorage` (key derivation from a user-supplied passphrase or the browser's
   origin-bound key store).

**Phase 2 dependencies:**
- ADR-013 (WASM REST API + OPFS section sync) — establishes the OPFS storage model
  that Phase 2 must extend with a git-compatible fs adapter
- `docs/tasks/git-sync.md` — JVM/Android git sync (out of scope there, explicitly
  deferred; this bug tracks the WASM-specific gap)

## Verification

**Phase 1 (fail visibly):**
1. Configure git sync settings on web.
2. Trigger sync.
3. Confirm a user-visible error message appears explaining web git is unsupported.
4. Confirm no fake "success" is shown.

**Phase 2 (full implementation):**
1. Clone a fresh remote into a WASM graph via the git setup wizard.
2. Edit a page and trigger sync.
3. Confirm the commit appears on the remote with correct author and message.
4. Confirm PAT is not visible in `localStorage` in plaintext.
5. Confirm pull fetches remote changes and reflects them in the UI.

## Related Tasks

- `docs/tasks/git-sync.md` — JVM/Android git sync implementation (web explicitly out of scope)
- `docs/tasks/git-sync-ux.md` — credential management and sync UX improvements
- `docs/adr/ADR-013-wasm-rest-api-opfs-section-sync.md` — OPFS storage model (Phase 2 prerequisite)
