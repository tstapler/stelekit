# ADR-013: WASM Section Sync via Git Hosting REST API + OPFS

## Status
Accepted

## Context

The Graph Sections feature requires the WASM build to fetch only selected sections from a git remote before `GraphLoader` starts (FR-5.2). The WASM build cannot shell out to system git; it runs in a browser. The requirements specify a 10-second first-load budget for a 500-page section on a 10 Mbps connection (NFR-1) and offline operation after OPFS cache population (NFR-6).

### Options for WASM section fetch

#### Option A: isomorphic-git (full packfile download)
`isomorphic-git` (v1.38.5) is a pure-JS git implementation that runs in browsers. It speaks the git HTTP smart protocol and can perform `clone` and `fetch` operations from browsers.

**Critical limitation:** isomorphic-git has **no partial-clone or sparse-fetch support**. It fetches full packfiles. For an 8 000-page graph, a full packfile could exceed 100 MB. This violates NFR-1 (10-second budget on 10 Mbps = 12.5 MB maximum for first load of 500 pages) and NFR-2 (200 ms overhead for 8 000 pages is not achievable if all 8 000 pages must be downloaded).

Additionally, isomorphic-git requires a CORS-compatible proxy for direct browser→GitHub HTTPS requests (browsers block non-CORS git smart protocol responses). This adds server infrastructure not currently in scope.

Finally, integrating isomorphic-git from Kotlin/WASM requires `external` JS declarations and a bridge to its `@isomorphic-git/lightning-fs` IndexedDB filesystem, which conflicts with SteleKit's existing OPFS-based `OpfsInterop.kt` infrastructure. Two parallel virtual filesystems would be required.

**Verdict: rejected.**

#### Option B: git partial clone + server-side filtering
`git clone --filter=blob:none --filter=tree:0` enables server-side content filtering. GitHub supports this for public repos; GitLab has experimental support. This requires the server to participate and cannot be initiated from a browser (requires system git). Not applicable to WASM.

**Verdict: rejected for WASM (remains applicable for desktop JVM, covered by ADR-012).**

#### Option C: Git hosting REST API + parallel raw fetches (chosen)

Major git hosting platforms (GitHub, GitLab, Gitea) provide REST APIs that allow:

1. **Manifest fetch**: raw file download for `.stele-sections` (a single lightweight request, typically < 1 KB).
2. **Tree listing**: `GET /repos/{owner}/{repo}/git/trees/{sha}?recursive=1` returns the full file tree (paths + blob SHAs + sizes) in a single response (up to 100 000 entries on GitHub). The response can be filtered client-side to the selected sections' path prefixes.
3. **Raw file download**: each page's `.md` file is fetched via its raw content URL (e.g., `https://raw.githubusercontent.com/{owner}/{repo}/{branch}/{path}`). These are plain HTTP GET requests, no API overhead, no base64 encoding.

For a 500-page section:
- 1 tree listing request (~2–5 s on 10 Mbps for a large graph response)
- 500 parallel raw file fetches (10 concurrent, ~0.5–1 s each, ~5–7 s total for 500 × ~5 KB = 2.5 MB of markdown)
- Total: ~8–10 s, within NFR-1.

#### Option D: SteleKit sync server (tarball per section)
A SteleKit server endpoint that serves a zip of a specific section's files. Eliminates per-platform API differences and rate limits. This is the optimal long-term design but requires server infrastructure outside the v1 scope.

**Verdict: deferred to v2.**

### OPFS as the local cache

OPFS (`navigator.storage.getDirectory()`) is already implemented in SteleKit (`OpfsInterop.kt`, `WasmOpfsSqlDriver.kt`, `PlatformFileSystem.kt`). It is Baseline Widely Available (Chrome 86+, Firefox 111+, Safari 15.2+, Chrome Android 86+) and is the correct storage backend for binary-scale offline data (outperforms IndexedDB for file I/O per Autodesk APS benchmarks and RxDB benchmarks).

Section content is stored at:
```
OPFS root/
  sections/
    {urlSafeRemote}/
      {sectionName}/
        tech/
          TypeSystems.md
          ...
        pages/
          Technology/
            ResearchNote.md
        .stele-sections-sync-complete   ← commit-flag marker (JSON)
```

### Atomicity and tab exclusion

OPFS has no cross-tab locking. Two tabs writing the same section simultaneously produce a corrupted cache. The Web Locks API (`navigator.locks.request()`) provides exclusive cross-tab locking for same-origin contexts (supported in Chrome 69+, Firefox 96+, Safari 15.4+).

The commit-flag pattern (writing `.stele-sections-sync-complete` as the **last** step after all page writes) provides crash recovery: a tab crash mid-sync leaves the marker absent; the next load detects this and re-syncs.

## Decision

Use the **git hosting REST API + OPFS** pattern for WASM section sync (v1). Specifically:

1. **Manifest fetch**: detect the git host from `remoteUrl`. Construct the appropriate raw file URL for `.stele-sections`:
   - GitHub: `https://raw.githubusercontent.com/{owner}/{repo}/{branch}/.stele-sections`
   - GitLab: `GET /api/v4/projects/{id}/repository/files/.stele-sections/raw?ref={branch}`
   - Gitea: `GET /api/v1/repos/{owner}/{repo}/raw/.stele-sections?ref={branch}`

2. **Tree listing** (first sync only): call the tree API with `?recursive=1`. Filter client-side to the selected sections' path prefixes. Extract path + SHA pairs for files within section boundaries.

3. **Parallel raw fetch + OPFS write**: fetch up to 10 files concurrently via Ktor `HttpClient` (already on classpath). Write each file to OPFS at the section-keyed path using `opfsWriteFile` from `OpfsInterop.kt`.

4. **Commit-flag marker**: write `SectionOpfsSyncState(commitSha, fileCount, timestampMillis)` to `.stele-sections-sync-complete` as the final write after all page files succeed.

5. **Web Locks**: acquire `navigator.locks.request("stele-section-${urlSafeRemote}-${sectionName}")` (exclusive) before entering the write phase. Release on completion or error.

6. **Delta sync** (subsequent syncs): compare the cached `commitSha` in the marker against the current remote HEAD SHA (fetched via the commits API or tags API). If equal: skip sync, use OPFS directly. If different: call the compare API to get the changed file list; fetch/update only those files; update the marker with the new SHA.

7. **Storage persistence**: call `navigator.storage.persist()` on first successful OPFS write per origin.

8. **`QuotaExceededError` handling**: catch during any `opfsWriteFile` call; surface `DomainError.StorageFull` to the UI; stop sync.

### PAT requirement for private repos

GitHub's unauthenticated rate limit (60 req/hr) is insufficient for a 500-file initial sync (501+ requests). Private repos require authentication regardless. The implementation uses the existing credential store (from git-smart-sync) to supply a Personal Access Token (PAT) or OAuth token in the `Authorization` header for all API requests.

Public repos can use unauthenticated raw file URLs for content fetches (raw.githubusercontent.com serves without auth), reducing API call count to 2 (manifest + tree listing).

### WASM-only implementation

`SectionSyncService` is defined as a `common` interface with `expect`/`actual` dispatch:
- **wasmJs actual**: `WasmSectionSyncService` — full REST + OPFS implementation.
- **jvm / android / ios actual**: no-op, returns `Unit.right()` immediately (files already on disk).

## Rationale

1. **Only option that satisfies NFR-1**: isomorphic-git downloads full packfiles (rejects NFR-1). Tarball download (Option D) also downloads everything. REST API + parallel raw fetch delivers selective section content with O(section size) network transfer.

2. **Builds on existing OPFS infrastructure**: `OpfsInterop.kt`, `WasmOpfsSqlDriver.kt`, and `PlatformFileSystem.kt` are already implemented and proven in the WASM build. The section sync reuses `opfsWriteFile` directly — no new OPFS primitives required.

3. **Commit-flag pattern is correct for crash safety**: writing the marker last means a crashed sync leaves a detectable incomplete state. On next load, the marker is absent → re-sync. This is simpler and more reliable than a two-phase commit or journal.

4. **Web Locks prevent tab-race corruption**: `navigator.locks.request()` is supported in all target browsers (Chrome 69+, Firefox 96+, Safari 15.4+). The lock is keyed per (remote, section) pair, allowing different sections to sync in parallel across tabs if the user has separate section tabs open.

5. **Delta sync avoids full re-download on subsequent loads**: the compare API allows fetching only changed files, keeping subsequent sync times well within the 10-second budget even for frequently-updated sections.

6. **No new infrastructure required for v1**: the REST API approach requires only Ktor `HttpClient` (already on classpath) and `OpfsInterop.kt` (already implemented). No proxy server, no CORS workarounds for raw file URLs on public GitHub repos.

## Alternatives Rejected

### isomorphic-git
No partial-clone support → downloads full packfiles for the entire repo. On an 8 000-page graph at ~5 KB/page = 40 MB, this exceeds the 10-second/10 Mbps budget by 4× even before tree-listing overhead. Additionally, isomorphic-git requires a second virtual filesystem (lightning-fs IndexedDB) alongside SteleKit's OPFS, and it requires a CORS proxy for non-public repos. Rejected.

### Single GitHub REST API (contents endpoint per file)
`GET /repos/{owner}/{repo}/contents/{path}` returns one file per call, base64-encoded, with a 1 000-item directory listing cap. For 500 files: 500 API calls, each consuming the authenticated rate limit (5 000/hr). This would consume 10% of the hourly budget per initial sync. The raw file URL approach (used in this decision) bypasses the contents API entirely for file content, using it only for the tree listing. Rejected as the primary fetch mechanism; the tree + raw URL pattern is strictly better.

### SteleKit sync server (tarball per section)
Architecturally optimal: a custom endpoint serves a zip/tar of exactly the selected section's current state. No rate limits, no per-platform API differences, supports write-back. This is the v2 design. Deferred because it requires server infrastructure outside v1 scope.

## Consequences

- `WasmSectionSyncService` requires host-detection logic for GitHub/GitLab/Gitea URL patterns. Self-hosted Gitea/Forgejo instances that use non-standard URL patterns will fall back to a generic "unsupported host" error in v1. This is a known limitation.
- PAT credentials must be available in the WASM credential store before section sync. The existing git-smart-sync credential flow handles this; no new credential UI is required.
- `navigator.storage.persist()` is called on first write. Chrome auto-approves based on engagement heuristics; Firefox shows a prompt. The app must handle the case where `persist()` is denied (OPFS data may be evicted under storage pressure on iOS Safari).
- Delta sync correctness depends on the remote's compare API being available and returning accurate changed-file lists. If the compare API is unavailable (e.g., very old Gitea version), the implementation falls back to a full re-sync.
- The commit-flag pattern means a successful partial sync that stops before the marker is written (e.g., network drops at file 490 of 500) triggers a full re-sync on next load. This is correct behavior but adds latency for users on unreliable connections. A future optimization could checkpoint the marker with a `partial: true` flag at intervals.
