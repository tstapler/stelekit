# Stack Research: web-git-writeback

Research Agent 1 (Stack). Confirms current API shapes, in-repo interop patterns, and build config for implementing `WasmGitWriteService` per ADR-015.

## 0. IMPORTANT — architecture context beyond requirements.md

`requirements.md` frames this as "implement `WasmGitWriteService` behind the existing `GitManager` interface" per ADR-013/ADR-015. However, **`docs/adr/ADR-016-sync-transport-abstraction.md` (Status: Accepted, same repo) supersedes this framing**:

- ADR-016 renames `GitManager` → `SyncTransport` (transport-neutral: `pushFiles`, `remoteVersion`, `hasRemoteChanges` instead of git-specific `commit`/`status`/`isDirty`).
- The WASM implementation becomes `GitHubRestTransport`, refactored from ADR-013 (read) + ADR-015 (write) — i.e., ADR-016 explicitly expects this project's write-back work to land, then be wrapped/renamed into the new interface.
- ADR-016 also states a `BastionTransport` (Epic 7) is planned to **replace** the 5-step GitHub Git Data API sequence entirely, precisely because it's non-atomic and complex — "ADR-015 remains the implementation until the bastion ships."
- Directly answers the "Rabbit Hole" open question about host-detection duplication: ADR-016's `SectionAwareSyncService` sits *above* the transport and the transport interface itself doesn't leak section logic — but it does NOT resolve GitHub-vs-GitLab host-detection duplication between `WasmSectionSyncService` (read, ADR-013) and the new write service. ADR-015 §"GitLab / Gitea compatibility" explicitly says `WasmGitWriteService` "will dispatch by host type (same host-detection logic as ADR-013's manifest fetch)" — implying host-detection should be extracted/shared, not duplicated.

**Recommendation for planning phase**: build `WasmGitWriteService` per ADR-015 now (that's this project's mandate and appetite), but name/shape it so the eventual ADR-016 rename (`GitManager`→`SyncTransport`) is a mechanical follow-up, not a rewrite — i.e., keep git-specific verbs (`commit`, `push`) out of the *internal* write-service API where possible, expose them only at the `JsGitManager` adapter boundary. This wasn't asked for in requirements.md and should be flagged to the planning phase, not silently decided here.

## 1. GitHub Git Data API — current shapes (verified against current GitHub REST API docs, API version 2022-11-28)

All confirm ADR-015's five-step sequence is still accurate. No breaking changes found.

### Step 1 — Create a blob
`POST /repos/{owner}/{repo}/git/blobs`
```json
{ "content": "string (required)", "encoding": "utf-8 | base64 (optional, default utf-8)" }
```
Returns `201` with `{ sha, url }`. **Use `encoding: "base64"`** (avoids UTF-8 edge cases with GitHub's content negotiation) — base64-encode content client-side (see §5).

### Step 2 — Get a tree
`GET /repos/{owner}/{repo}/git/trees/{tree_sha}?recursive=true`
Unchanged from ADR-015.

### Step 3 — Create a tree
`POST /repos/{owner}/{repo}/git/trees`
```json
{
  "base_tree": "string (optional) — SHA of tree to layer on top of",
  "tree": [
    { "path": "string", "mode": "100644|100755|040000|160000|120000", "type": "blob|tree|commit", "sha": "string|null", "content": "string" }
  ]
}
```
Provide either `sha` (blob SHA from step 1) or `content` per entry, not both. `sha: null` on an existing path deletes that file — relevant for delete-locally handling.

### Step 4 — Create a commit
`POST /repos/{owner}/{repo}/git/commits`
```json
{ "message": "string", "tree": "sha-string", "parents": ["sha-string"], "author": { "name": "...", "email": "...", "date": "ISO 8601" } }
```

### Step 5 (conflict check) — Get a reference
`GET /repos/{owner}/{repo}/git/ref/{ref}` where `ref` is `heads/{branch}` (no leading `refs/`). Returns current head SHA to compare against the `base-sha`/`base_tree` used in step 3. `404` = branch doesn't exist; `409` = ambiguous/conflict.

### Step 6 — Update a reference
`PATCH /repos/{owner}/{repo}/git/refs/{ref}`
```json
{ "sha": "commit-sha", "force": false }
```
`force: false` (default) enforces fast-forward-only — this is GitHub's own conflict guard: if the branch moved since step 5's read, this PATCH itself returns `422` (non-fast-forward) or `409` (conflict). **This means conflict detection doesn't strictly require a separate pre-check GET** — the PATCH with `force:false` is atomic-enough for the common case — but ADR-015's explicit pre-check (step 5) is still correct because it lets the client fetch a compare-delta and attempt auto-merge *before* wasting a commit object, rather than just failing after the fact. Recommend keeping ADR-015's ordering but treat the `422`/`409` on the final PATCH as a **second, authoritative** conflict signal (race between check and update is possible — two tabs, etc.) and handle it the same way as the pre-check conflict path, not as an unhandled error.

### Auth header
Confirmed current GitHub REST API convention: **`Authorization: Bearer <token>`** for most endpoints (the legacy `Authorization: token <token>` form still works but `Bearer` is what GitHub's own docs now show as standard). Note: the existing `WasmSectionSyncService.kt` already uses `Bearer` (see §3) — the new write service should match, no change needed. ADR-015's prose still says `Authorization: token {pat}` — that's now stale relative to current docs; flag for the planning phase to use `Bearer` for consistency with the existing read path.

### Rate limits
Confirmed by ADR-015: unauthenticated 60 req/hr is not viable; authenticated (PAT) is 5,000 req/hr — same 429 + `Retry-After` handling pattern already implemented in `WasmSectionSyncService.githubFetch()` should be reused/shared for write calls (each push is 5–6 sequential calls × N dirty files for blobs, so retry/backoff matters more here than on read).

## 2. GitLab single-call commit API — current shape (verified against current GitLab API docs)

`POST /api/v4/projects/:id/repository/commits`

Required: `branch`, `commit_message`, `actions[]`. `:id` is numeric project ID or URL-encoded `namespace%2Fproject` path.

**Actions array** — each entry:
- `action` (required, enum): `create | delete | move | update | chmod`
- `file_path` (required)
- `content` — required for `create`/`update`; optional for `move` (omit to preserve existing content, i.e. pure rename)
- `encoding`: `text | base64` (default `text`) — **use `base64`** to match the GitHub path's encoding strategy and avoid text-mode edge cases
- `previous_path` — `move` only
- `execute_filemode` — `chmod` only
- **`last_commit_id`** — optional, considered only on `update`/`move`/`delete`. This is GitLab's **per-file** conflict-detection primitive: pass the blob's last-known commit SHA; if the file has changed remotely since, GitLab rejects the action. This is finer-grained than GitHub's whole-ref conflict check and maps well to the requirement's "non-overlapping files auto-merge, overlapping files conflict" logic — **per-action `last_commit_id` mismatches on individual files is a natural way to detect *which specific files* conflict**, rather than GitHub's need for a separate compare-delta step.

**Conflict detection overall**: `start_branch` (or `start_sha`) sets the *parent* the new commit is based on — if you omit it, GitLab commits directly onto the tip of `branch`, which is a race (last-write-wins, no conflict signal) unless you also supply per-file `last_commit_id`. **Recommendation**: always pass `start_sha` (the known base SHA) — this is GitLab's equivalent of GitHub's fast-forward-only ref update — combined with `last_commit_id` per touched file for granular conflict info.

### Auth header
GitLab's documented convention is `PRIVATE-TOKEN: <token>` (distinct from GitHub's `Authorization: Bearer`). GitLab also accepts OAuth2 `Authorization: Bearer <token>` for OAuth app tokens, but for a user-supplied PAT (as this project uses, session-scoped, same as GitHub), **`PRIVATE-TOKEN` is correct**. This is a real per-host difference the dispatch-by-host-type logic in `WasmGitWriteService` must account for (different header name entirely, not just a different prefix) — worth calling out explicitly since it's easy to miss if copy-pasting the GitHub auth helper.

## 3. Existing JS interop pattern — `WasmSectionSyncService.kt` (read this file, don't reinvent)

File: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/sync/WasmSectionSyncService.kt` (127 lines). Pattern to replicate exactly for the write service:

- **Top-level `js("...")` functions only** — Kotlin/Wasm requires `js()` calls to be top-level, not inside a class/companion object (comment on line 16 states this explicitly; this is a real Kotlin/Wasm constraint, not a style choice).
- Fetch wrappers return `kotlin.js.Promise<JsAny>`, e.g.:
  ```kotlin
  private fun jsFetchWithToken(url: String, token: String): kotlin.js.Promise<JsAny> =
      js("""fetch(url, { headers: { 'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json' } })""")
  ```
  For the write service this needs new variants that also set `method: 'POST'|'PATCH'`, `body: JSON.stringify(...)`, and `'Content-Type': 'application/json'` — no such POST/PATCH wrapper exists yet, must be added.
- Response inspection helpers: `jsResponseStatus(response): Int` via `js("response.status | 0")`, `jsResponseHeader(response, name): String?` via `js("response.headers.get(name) || null")`, `jsResponseText(response): Promise<JsAny>` via `js("response.text()")`, and `jsStringValue(v: JsAny): String` via `js("String(v)")`.
- Async calls use `.await<JsAny>()` from `kotlinx.coroutines.await` (kotlinx-coroutines-core's JS/Wasm interop), not a Kotlin-native suspend wrapper.
- Retry/backoff: `githubFetch()` companion function already implements 429 handling with `Retry-After` header parsing and exponential backoff (`1 shl retryCount`, capped, max 4 retries) — **reuse this exact function/logic for write calls** rather than duplicating; it's currently scoped to `WasmSectionSyncService.Companion` and would need to move to a shared location (supports the ADR-016 "shared host-detection/fetch abstraction" question — same conclusion applies to the fetch+retry helper).
- JSON parsing in this file is done with manual string-scanning (`extractTreePaths`), **not kotlinx.serialization** — this is a lightweight approach for the simple read case. For the write service, request *bodies* (blob/tree/commit JSON, GitLab actions array) are complex enough that kotlinx.serialization (already on classpath, see §4) should be used to build them via `@Serializable` data classes + `Json.encodeToString`, rather than hand-building JSON strings. Response parsing (extracting `sha` fields etc.) could go either way — recommend kotlinx.serialization there too for consistency, since malformed hand-parsing of nested JSON (tree/commit responses) is more failure-prone than the flat `path` extraction the read path does.
- No `Content-Type` / body-sending example exists yet in this file — new territory for this project (see §5 for the JSON-body `fetch()` gotcha).

## 4. Build config — Ktor, kotlinx.serialization, Kotlin version

From `kmp/build.gradle.kts` / `settings.gradle.kts`:
- **Kotlin: 2.3.21** (multiplatform, jvm, android, compose, serialization plugins all pinned to this version)
- **Ktor: 3.1.3** — `ktor-client-core`, `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json`, `ktor-client-js` (wasmJs/js engine), plus per-platform engines (okhttp for JVM/Android, darwin for iOS)
- **kotlinx-serialization-json: 1.10.0**
- SQLDelight 2.3.2, Compose Multiplatform 1.10.3, AGP 8.13.2 — not directly relevant here but confirms the toolchain is current as of project date (2026-07-14).

**Important nuance**: `WasmSectionSyncService.kt` does **not** use Ktor's `HttpClient` at all — it uses raw browser `fetch()` via `js()` interop (see §3). ADR-013 and ADR-015 both say "Ktor `HttpClient` (already on classpath...) handles all calls" but the actual implementation diverges from that — it went with manual `fetch()` interop instead. This is a real discrepancy between the ADRs and the shipped read-path code that the planning phase must resolve: either (a) follow the ADRs literally and use Ktor's wasmJs/js `HttpClient` engine for the new write service (more idiomatic, gets Ktor's content-negotiation/serialization plugin for free, but inconsistent with the existing read path in the same file family), or (b) follow the existing code's actual pattern (raw `fetch()` js interop, per §3) for consistency within `sync/`. Given the instruction "the new write service should follow the same pattern, not invent a new one" — recommend **(b)**, and flag the ADR text vs. reality mismatch to planning.

## 5. Kotlin/Wasm gotchas for authenticated POST/PATCH with JSON bodies

- **Base64 encoding**: `kotlin.io.encoding.Base64` (stdlib, `kotlin-stdlib-common`) has been **stable since Kotlin 2.0** (was `@ExperimentalEncodingApi` from 1.8.20–1.9.x). At Kotlin 2.3.21 it is fully stable and available on all targets including `wasmJs` — no opt-in annotation needed, no external JS interop required for base64-encoding blob content for the GitHub blobs endpoint. Use `Base64.Default.encode(byteArray)` / `.encode(string.encodeToByteArray())`. This avoids needing a `js("btoa(...)")` interop shim, which would otherwise mangle non-Latin1/UTF-8 content (a classic `btoa()` gotcha in raw JS).
- **`fetch()` with JSON body via `js()` interop**: no existing example in this codebase (see §3) — must set `method`, `headers['Content-Type'] = 'application/json'`, and `body: JSON.stringify(...)` inside the `js("...")` block, or (more robustly) pass an already-serialized Kotlin `String` into the JS template and call `JSON.stringify` isn't even needed if the Kotlin side does `Json.encodeToString(...)` and passes the resulting string directly as `body` (no need to double-encode through `JSON.stringify` on the JS side — the Kotlin string interpolated into the `js()` block via a function parameter is already valid JSON text). Passing Kotlin `String` parameters into `js()` blocks works the same way as the existing `url`/`token` parameters in `jsFetchWithToken`.
- **Kotlin/Wasm target maturity**: per current Kotlin docs (as of Kotlin 2.2.20+, applies to 2.3.21 too), **Kotlin/Wasm is Beta**, not Stable — worth noting as a general risk flag (API/tooling churn possible) even though the specific APIs used here (stdlib Base64, `js()` interop, `Promise.await()`) are all stable stdlib/coroutines APIs, not Wasm-target-experimental ones.
- **`JsAny` / external declarations**: this codebase already establishes the convention of typing all fetch results as `JsAny` and manually reading fields via small `js()` extraction functions (`jsResponseStatus`, `jsResponseHeader`, etc.) rather than using `external interface` declarations with typed properties. Either approach is valid Kotlin/Wasm JS interop; for consistency, continue the existing small-function-per-field style rather than introducing `external interface` types in the new write service.
- **CORS**: not a gotcha for the Git Data API / GitLab commits API themselves (both are plain authenticated REST endpoints that support CORS from browser origins, unlike git's smart-HTTP protocol which ADR-015 already rejected via isomorphic-git for exactly this reason) — no additional proxy needed, consistent with ADR-015's rationale.

## Summary of confirmed-vs-stale points for the planning phase

| Item | ADR-015 said | Current reality (this research) |
|---|---|---|
| GitHub auth header | `Authorization: token {pat}` | `Authorization: Bearer {pat}` is current GitHub convention **and already what the read path uses** |
| HTTP client | "Ktor `HttpClient`... handles all calls" | Read path actually uses raw `fetch()` js interop, not Ktor. Recommend write path matches read path (fetch interop), not the ADR text (Ktor). |
| GitLab conflict detection | "`start_branch` parameter" | Confirmed, but per-file `last_commit_id` on update/move/delete actions is the more precise mechanism for the "non-overlapping auto-merge" requirement — should be used in addition to `start_sha`/`start_branch`. |
| GitLab auth header | not specified in ADR-015 | `PRIVATE-TOKEN: <token>` — different header *name*, not just prefix, from GitHub's `Bearer`. |
| Base64 encoding | not specified | `kotlin.io.encoding.Base64`, stable since Kotlin 2.0, no interop shim needed. |
| Interface shape | `WasmGitWriteService` behind `GitManager` | ADR-016 (Accepted, not mentioned in requirements.md) plans to rename `GitManager`→`SyncTransport` and eventually replace this whole write-back approach with a `BastionTransport`. Build to ADR-015 now but keep git-specific verbs out of the internal service API where feasible. |
