# ADR-015: WASM Git Write-Back via GitHub Git Data API

## Status
Accepted

## Context

ADR-013 established the read path for WASM section sync: GitHub REST API for manifest and tree listing, raw file URLs for content, OPFS for local cache. ADR-013 explicitly defers the write path: "write-back (commit/push) from browser to GitHub is not specified by this ADR."

The WASM platform cannot shell out to system git (browser sandbox) and cannot use JGit (JVM-only). The `JsGitManager` stub currently returns `GitResult.Error("Git sync is not available on the web platform")` for all operations (BUG-005 Phase 1 fix). The read path (ADR-013) is implemented; this ADR specifies how the browser writes changes back to the remote.

### Requirement

Users who edit notes in the WASM build must be able to push changes back to GitHub. The operation must:
- Work entirely from the browser (no server-side proxy in v1)
- Detect conflicts before committing
- Authenticate with the same PAT already used for read operations
- Fit within the existing `GitManager` interface contract

### Options for browser-side git write-back

#### Option A: GitHub Git Data API (chosen)

GitHub's Git Data API exposes the low-level git object model over HTTPS:

1. `POST /repos/{owner}/{repo}/git/blobs` — create a blob for each changed file; returns SHA
2. `GET /repos/{owner}/{repo}/git/trees/{base-sha}` — fetch current tree
3. `POST /repos/{owner}/{repo}/git/trees` — create new tree referencing new blobs
4. `POST /repos/{owner}/{repo}/git/commits` — create commit pointing at new tree; returns commit SHA
5. `PATCH /repos/{owner}/{repo}/git/refs/heads/{branch}` — advance the branch ref

All five steps are plain authenticated HTTPS requests. No git binary, no CORS proxy, no additional infrastructure. Ktor `HttpClient` (already on classpath from ADR-013) handles all calls.

**Conflict detection**: before step 5 (ref update), fetch the current remote ref SHA. If it differs from the `base-sha` used in step 3, abort and surface `DomainError.ConflictError.DiskConflict` to the UI. The user must pull and re-merge before pushing.

**Verdict: chosen.**

#### Option B: isomorphic-git write path

isomorphic-git supports `commit()` and `push()` via its own in-memory git object store. However:

- Push requires git smart HTTP protocol, which GitHub serves without CORS headers → browser blocks the response. A CORS proxy is required.
- Adds ~500 KB to the WASM bundle (measured: isomorphic-git v1.38.5 minified + gzipped = 483 KB).
- Requires a second virtual filesystem (lightning-fs over IndexedDB) in parallel with SteleKit's OPFS infrastructure. Two independent filesystems for the same repo state is an invariant violation.
- ADR-013 already rejected isomorphic-git for the read path on these grounds. The same constraints apply to write-back.

**Verdict: rejected.**

#### Option C: Server-side proxy commit endpoint

A SteleKit relay server receives changed files, constructs a git commit, and pushes to the remote on behalf of the browser. This would also accept GitLab and Gitea without per-platform REST API differences.

Requires server infrastructure outside v1 scope. Deferred to v2 (same timeline as the SteleKit sync server tarball endpoint deferred in ADR-013).

**Verdict: deferred to v2.**

### Auth and credential persistence

The PAT from the `CredentialStore` (already required for ADR-013's authenticated reads) is reused in the `Authorization: token {pat}` header for all Git Data API write calls. The WASM `CredentialStore` is currently a no-op (BUG-005): credentials are not persisted across page loads. This means the user must re-enter their PAT on each session. That is acceptable for v1; a future ADR will specify a browser keychain (Web Crypto + sessionStorage or origin-bound key) for credential persistence.

### GitLab / Gitea compatibility

The Git Data API calls above are GitHub-specific. GitLab provides equivalent endpoints:
- `POST /api/v4/projects/{id}/repository/commits` — atomic multi-file commit in a single call (simpler than GitHub's five-step sequence)
- Conflict detection via `start_branch` parameter

Gitea provides a compatible subset. The `WasmGitWriteService` will dispatch by host type (same host-detection logic as ADR-013's manifest fetch). GitLab's single-call commit API is actually preferable to GitHub's five-step sequence; the service layer will use the simpler path when the host is GitLab.

### Merge conflict handling

When the remote ref has advanced past `base-sha`:

1. Fetch the new commits (tree listing delta via commits compare API — already used in ADR-013 delta sync).
2. Identify files changed both locally (OPFS dirty set) and remotely (compare response).
3. For non-overlapping files: auto-merge by including both change sets in step 3 (create new tree referencing remote head as base).
4. For overlapping files: surface `DomainError.ConflictError.DiskConflict` with the conflicting file list. The user resolves via the existing `ConflictResolutionScreen`.
5. After resolution: retry the write-back with the merged content.

This is the same logical flow as the JVM `GitSyncService.resolveConflict()`. The shared `GitManager` interface ensures UI conflict handling is reused without platform-specific branching.

## Decision

Implement `WasmGitWriteService` using the GitHub Git Data API (five-step sequence) with GitLab single-call fallback, dispatched by host type. The implementation:

1. Reads the dirty file set from OPFS (`PlatformFileSystem.kt` dirty tracking — added alongside this ADR).
2. For each dirty file: `POST .../git/blobs` with base64-encoded UTF-8 content; collects `{path, sha}` pairs.
3. Fetches the base tree: `GET .../git/trees/{local-base-sha}`.
4. Creates new tree: `POST .../git/trees` merging changed blobs into the base tree.
5. Fetches current remote ref SHA to detect conflicts before creating the commit.
6. If no conflict: `POST .../git/commits` → `PATCH .../git/refs/heads/{branch}`.
7. If conflict: fetch compare delta, attempt auto-merge, or surface `DomainError.ConflictError.DiskConflict`.
8. All calls use Ktor `HttpClient` with `Authorization: token {pat}` header.
9. All calls run in `withContext(PlatformDispatcher.IO)`.
10. On success: clear dirty set in OPFS, update `base-sha` in the sync marker.

`WasmGitWriteService` is the `wasmJs` actual behind a `common` `GitWriteService` interface. JVM/Android/iOS actuals delegate to the existing `GitSyncService.commitLocalChanges()`.

The `JsGitManager` methods (`commit`, `push`, `pull`, `status`, `isDirty`) are wired to `WasmGitWriteService` when credentials and remote URL are available; they continue to return `GitResult.Error(NOT_SUPPORTED)` until `WasmGitWriteService` is fully implemented (i.e., BUG-005 Phase 1 remains in effect until Phase 2 ships).

## Rationale

1. **Only option that satisfies the browser sandbox constraint without new infrastructure**: Git Data API is plain HTTPS — no git protocol, no CORS proxy, no sidecar process. The same PAT and Ktor client used for read operations (ADR-013) cover all write calls.

2. **Conflict detection before ref update is correct and atomic**: the five-step sequence means the commit object exists in GitHub's object store before the ref is updated. A failed ref update (due to a race between steps 5 and 6) leaves an orphaned commit object, not a corrupted branch. On retry, the orphaned object is simply not referenced by any new tree, and GitHub GC eventually collects it.

3. **Shared `GitManager` interface preserves UI parity**: the conflict resolution screen, sync status badge, and commit flow are shared Compose UI. Routing WASM writes through the same interface means no WASM-specific UI branches are needed.

4. **GitLab's single-call commit API is strictly simpler**: one `POST /api/v4/projects/{id}/repository/commits` with an `actions` array replaces the five-step GitHub sequence. Detecting the host once and using the simpler path reduces error surface for GitLab users.

5. **Deferred credential persistence is acceptable for v1**: re-entering a PAT on each page load is annoying but not data-loss. The session is browser-scoped; the user's edits are in OPFS before they attempt to push.

## Alternatives Rejected

### isomorphic-git
Requires CORS proxy for push (GitHub does not serve CORS headers on git smart HTTP). Adds 483 KB to WASM bundle. Requires a second virtual filesystem alongside OPFS. ADR-013 already rejected it for the read path; the same constraints apply here. Rejected.

### Server-side proxy commit
Architecturally cleanest long-term design (no per-platform API differences, supports GitLab/Gitea/Forgejo uniformly, enables future server-side merge). Requires server infrastructure outside v1 scope. Deferred to v2.

### Web-based git implementation (wasm-pack + libgit2)
`libgit2` compiled to WASM via `wasm-pack` provides a full git implementation. Bundle size impact is ~2–4 MB (libgit2 WASM + glue code), unacceptable for a web app targeting first-meaningful-paint under 3 seconds. Rejected.

## Consequences

- `WasmGitWriteService` requires dirty-file tracking in `PlatformFileSystem.kt` (WASM actual): every `writeFile` call records the path in an in-memory dirty set. The set is cleared on successful push and restored from an OPFS checkpoint on page reload (JSON file at `.stele-dirty-set.json`).
- GitHub's unauthenticated API rate limit (60 req/hr) is insufficient for a write session involving many files. PAT authentication is effectively mandatory for write-back, even for public repos (authenticated limit: 5 000 req/hr).
- The five-step GitHub sequence is not atomic from the client's perspective: a network failure between steps 4 and 6 leaves a partial state. On retry, the client must re-derive the dirty set from OPFS and restart from step 1. The commit SHA from step 5 is not reused (GitHub's `POST /git/commits` always creates a new object).
- PAT credentials are not persisted in WASM v1. Session-scoped credential entry is a known UX regression vs. desktop. A follow-on ADR will specify origin-bound credential storage via Web Crypto.
- Self-hosted Gitea/Forgejo instances on non-standard URL patterns will fall back to `DomainError.NetworkError.RequestFailed("unsupported git host")` in v1, consistent with ADR-013's host-detection limitation.
