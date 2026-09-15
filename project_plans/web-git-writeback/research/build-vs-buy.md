# Research: Build vs. Buy — web-git-writeback

**Scope note**: `ADR-015`'s top-level decision (hand-rolled Git Data API calls over Ktor, vs. isomorphic-git / libgit2-wasm / server proxy) is settled and **not** re-litigated here. This covers the five sub-piece decisions below `WasmGitWriteService`.

## Codebase context found during research

Two different HTTP patterns already exist for talking to GitHub, and they matter for the recommendations below:

- `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/sync/WasmSectionSyncService.kt` — raw `js("fetch(...)")` interop, manual string-index JSON path extraction (`extractTreePaths`), hand-rolled 429 backoff. **No Ktor HttpClient at all.** This appears to predate `ktor-client-js` being added to `wasmJsMain` (see the comment at `kmp/build.gradle.kts:210-214`: the engine was added for LLM formatter providers, "every other source set already declares an engine ... wasmJsMain did not until now").
- `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/GitHubDeviceFlowClient.kt` — real Ktor `HttpClient` with `ContentNegotiation`/`json()`, `@Serializable` data classes in `git/model/GitHubDeviceFlow.kt` (`DeviceCodeResponse`, `TokenPollResponse`, `GitHubUser`), typed `response.body<T>()` calls. This is a **GitHub API client living in `commonMain`** that already compiles for `wasmJs` (it's wired into `GitSetupScreen`, confirmed via `GraphDialogLayer.kt:189`'s comment about giving it "a fresh HttpClient").

`GitHubDeviceFlowClient.kt` is the more mature, current pattern and the one `WasmGitWriteService` should follow — not `WasmSectionSyncService.kt`'s string-parsing approach, which looks like a pre-ktor-client-js stopgap.

`kotlin("multiplatform") version "2.3.21"` (`settings.gradle.kts:9`) — recent enough that `kotlin.io.encoding.Base64` (stabilized in Kotlin 2.0) is safely available with no experimental opt-in.

---

## 1. GitHub/GitLab API client libraries for Kotlin/Wasm or generic KMP

**Verdict: Not recommended — hand-rolled Ktor calls confirmed as the only viable option.**

- Web search found no Kotlin Multiplatform GitHub or GitLab REST client targeting `wasmJs`. GitHub's own libraries page and Octokit's org list only JS/Node, Ruby, .NET, Go, etc. — no Kotlin entry.
- The known JVM-world Kotlin/Java GitHub clients (`org.kohsuke:github-api` / `hub4j/github-api`, `gitlab4j-api`) are built on OkHttp/Retrofit and are JVM-only; they cannot be added to `wasmJsMain` regardless of API surface, since their transport has no `wasmJs` target.
- `JetBrains/kotlin-wrappers` wraps JS *libraries* (DOM, React, etc.), not GitHub's API — not applicable.
- **Pros of hand-rolling**: zero new dependency, full control over the exact 5 GitHub endpoints / 1 GitLab endpoint actually needed (this is a small, closed surface — not a general-purpose client), consistent with the `GitHubDeviceFlowClient.kt` pattern already in the codebase.
- **Cons**: no upstream maintenance for API drift; but the endpoint surface here (`git/blobs`, `git/trees`, `git/commits`, `git/refs/heads/{branch}`, GitLab `repository/commits`) is stable, low-churn API surface unlikely to break.

## 2. Base64 encoding for blob content

**Verdict: Recommended — `kotlin.io.encoding.Base64` (stdlib), no third-party lib or JS interop shim needed.**

- Stabilized in Kotlin 2.0 (this project is on 2.3.21 — `settings.gradle.kts:9`), available on all KMP targets including `wasmJs` without `@OptIn(ExperimentalEncodingApi::class)` gymnastics (still technically opt-in-annotated in some 2.x point releases for `Base64.Default`, but stable/non-experimental behavior — confirm exact opt-in requirement against the 2.3.21 stdlib at implementation time, a one-line check).
- Only current in-repo Base64 usage is `java.util.Base64` in `JvmCredentialStore.kt` — JVM-only, not reusable for `wasmJs`. No existing multiplatform Base64 helper to reuse or conflict with.
- **Pros**: zero dependency, byte-for-byte control, UTF-8-safe when paired with `.encodeToByteArray()` first (needed for GitHub's blob content, which must be base64 of the UTF-8 bytes, not of the raw `String`).
- **Cons**: none identified for this use case (small file-sized blobs, not streaming-scale).

## 3. JSON request/response modeling

**Verdict: Recommended — `kotlinx.serialization` data classes, matching `GitHubDeviceFlowClient.kt` / `git/model/`, not `WasmSectionSyncService.kt`'s manual string parsing.**

- `kotlinx-serialization-json:1.10.0` and `ktor-serialization-kotlinx-json:3.1.3` are already on the classpath (`kmp/build.gradle.kts:96, 122, 148`) and already used this exact way for a GitHub API client in this codebase (`GitHubDeviceFlowClient.kt` + `git/model/GitHubDeviceFlow.kt`).
- The alternative — `WasmSectionSyncService.kt`'s hand-rolled `json.indexOf("\"path\"")` string scanning — is fragile (breaks silently on key reordering, nested objects with the same key name, escaped quotes in values) and does not scale to the richer payloads this service needs: tree entries with `path`/`mode`/`type`/`sha`, commit objects with nested `author`/`committer`/`tree`/`parents`, GitLab's `actions` array with `action`/`file_path`/`content`.
- **Pros**: type-safe, matches existing codebase convention for GitHub API calls, `@SerialName` handles GitHub/GitLab's snake_case field names cleanly (as `DeviceCodeResponse` already demonstrates), far less error-prone for the more complex tree/commit payloads than string scanning.
- **Cons**: requires writing ~6-10 new data classes (`GitBlobRequest`/`Response`, `GitTreeRequest`/`Response` + nested `GitTreeEntry`, `GitCommitRequest`/`Response`, `GitRefResponse`, GitLab `CommitAction`/`CommitRequest`) — a small, one-time, well-precedented cost, not a new-dependency decision since the library is already in use.
- Recommend `ignoreUnknownKeys = true` on the shared `Json` config (as `GitHubDeviceFlowClient.withDefaultClient()` already does) to tolerate GitHub/GitLab response field additions without breaking parsing.

## 4. 3-way merge / diff logic for non-overlapping-files auto-merge

**Verdict: Not needed — confirmed. Plain Kotlin set operations are sufficient.**

- Both `ADR-015` ("For non-overlapping files: auto-merge by including both change sets... For overlapping files: surface `DomainError.ConflictError.DiskConflict`") and `requirements.md`'s Rabbit Holes section explicitly scope this to **file-path-level** overlap detection, not content-level/byte-level 3-way merge. The requirements doc calls out the trap directly: a naive content-level merge could silently merge two edits to the *same file* that happen not to byte-conflict, which is explicitly the behavior to avoid — the correct behavior is path-level, "did both sides touch this file at all."
- This reduces to: `localDirtySet.paths intersect remoteChangedSet.paths` → non-empty intersection is the conflict set (surfaced to `ConflictResolutionScreen`); the symmetric difference is auto-mergeable by including both sides' blobs when building the new tree (step 3 of the GitHub sequence, using remote HEAD as the tree base per `ADR-015`'s merge-conflict-handling section).
- No diff library (e.g. `java-diff-utils`, a text-diff algorithm, or a git merge library) is needed — nothing here compares file *contents*, only *path sets*. Kotlin stdlib `Set` operations (`intersect`, `subtract`, `union`) are sufficient and are already idiomatic elsewhere in the codebase.
- One edge case flagged in `requirements.md`'s Feasibility Risks worth carrying into planning (not a library gap, a logic-completeness gap): file deleted locally vs. edited remotely (or vice versa) — still resolvable with plain set/map operations (track deletions as a distinguishable dirty-set entry type), no library required.

## 5. Retry/backoff for rate-limited requests

**Verdict: Recommended — Ktor's built-in `HttpRequestRetry` plugin, not a hand-rolled loop.**

- `HttpRequestRetry` ships as part of `io.ktor:ktor-client-core` (already a transitive dependency via `ktor-client-core:3.1.3`, `kmp/build.gradle.kts:120`) — **no new dependency**, and it is client-core-level (works identically across engines, including `ktor-client-js` used on `wasmJs`), not engine-specific.
- Its `exponentialDelay()` config directly matches what's needed here: exponential backoff with `respectRetryAfterHeader` (defaults to `true`), which honors GitHub's `Retry-After` header on `429` responses — the exact behavior `WasmSectionSyncService.githubFetch()` currently hand-rolls itself (`(1 shl retryCount).coerceAtMost(60)` + manual `Retry-After` header parsing + manual recursive retry).
- Nothing in the codebase currently uses `HttpRequestRetry` — both existing retry implementations (`WasmSectionSyncService.githubFetch`, `GitHubDeviceFlowClient.pollForToken`'s 5xx/network backoff) are hand-rolled. Adopting the plugin for the *new* service is a net simplification, not a new-dependency decision, and doesn't require touching the two existing hand-rolled call sites (out of scope here — `WasmSectionSyncService`'s read path is explicitly not to be refactored per `requirements.md`'s Rabbit Holes).
- **Pros**: declarative, well-tested, one-time `install(HttpRequestRetry) { ... }` config block vs. maintaining a custom recursive-retry function per call site across 5 GitHub endpoints + 1 GitLab endpoint; correctly composes with Ktor's request/response pipeline (headers, status handling) rather than re-parsing raw `Response` objects by hand as `WasmSectionSyncService` does.
- **Cons**: one caveat surfaced in research — a known Ktor issue (KTOR-4652) where the `Timeout` and `Retry` plugins have had interaction quirks in some versions; worth a smoke test against `ktor-client-js` specifically during implementation rather than assuming JVM-tested behavior transfers 1:1 to the JS/Wasm engine. Not a blocker, just a verify-don't-assume note for Phase 4/5.
- Recommend configuring `retryOnServerErrors(maxRetries)` + `exponentialDelay()` and treating GitHub's `403` (secondary rate limit, not just `429`) as retryable via `retryIf { _, response -> response.status.value == 403 && response.headers["Retry-After"] != null }`, since GitHub's abuse-rate-limit responses use `403` with a `Retry-After` header, not `429`, on some endpoints — a detail `ADR-015` doesn't mention and worth carrying into planning.

---

## Summary table

| # | Sub-piece | Verdict | Recommendation |
|---|---|---|---|
| 1 | GitHub/GitLab API client library | Not recommended | Hand-rolled Ktor calls (no wasmJs-compatible client exists) |
| 2 | Base64 encoding | Recommended | `kotlin.io.encoding.Base64` (stdlib) |
| 3 | JSON modeling | Recommended | `kotlinx.serialization` data classes, matching `GitHubDeviceFlowClient.kt` — not `WasmSectionSyncService.kt`'s string parsing |
| 4 | 3-way merge / diff library | Not needed (confirmed) | Plain Kotlin `Set` operations on file paths |
| 5 | Retry/backoff | Recommended | Ktor's built-in `HttpRequestRetry` plugin (`exponentialDelay`, already on classpath) |
