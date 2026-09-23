# Graph Namespaces — Technology Stack Research

## 1. Git Sparse-Checkout (Desktop JVM)

### Current Integration

SteleKit uses **JGit 7.3.0** for all git operations on JVM (Desktop) and Android — no system `git` process is involved. The `JvmGitRepository` and `AndroidGitRepository` both import `org.eclipse.jgit.api.Git`. There is zero existing sparse-checkout usage in the codebase.

### JGit Sparse-Checkout API

**JGit 7.x has NO sparse-checkout API.** Eclipse Bug 383772 (filed 2012) requesting sparse-checkout support in JGit is still open with no resolution as of JGit 7.3. There is no `SparseCheckoutCommand` class. The Jenkins git-client plugin's `JGitAPIImpl` has an explicit TODO noting this gap.

The only viable option is to **shell out to system `git`** via `ProcessBuilder`. This means sparse-checkout on Desktop JVM requires system git to be installed (a soft dependency — FR-4.4 already says to silently skip if git is unavailable). On Android, system git is not reliably available, so Android sparse-checkout is out of scope for v1 (consistent with requirements that list only Desktop + WASM for sparse-checkout).

Note: Low-level JGit config manipulation can write the `info/sparse-checkout` file and set `core.sparseCheckout = true`, but JGit's `CheckoutCommand` does not read sparse patterns when applying the working tree — the patterns are ignored. System git is required to actually apply the sparse filter.

### Cone Mode Commands (system git reference)

```bash
git sparse-checkout init --cone --no-sparse-index   # --no-sparse-index avoids index format compat issues
git sparse-checkout set tech/ pages/Technology/ work/ pages/Work/
git sparse-checkout add journals/ private/   # add more paths
git sparse-checkout list                     # show active directories
git sparse-checkout disable                  # disable, restore full checkout
```

- **Cone mode** is the recommended mode (git ≥ 2.26). Only allows full-directory patterns, not file globs. Namespace paths map to directories — cone mode aligns perfectly with FR-1.1.
- **`set` vs `add`**: `set` atomically replaces all paths; `add` appends. After either, git updates the working tree automatically — no separate `git checkout` or `git read-tree` call needed.
- **`--no-sparse-index`**: Strongly recommended. The `--sparse-index` optimization changes the index format in ways that confuse JGit and other external tools. Omit it.
- Config lives in `.git/config` (`core.sparseCheckout = true`, `core.sparseCheckoutCone = true`) + `.git/info/sparse-checkout` file. Both are local to the clone; not pushed to the remote.

### Key Caveats

- **Cone mode always includes root-level files** and all parent directories of specified paths. Cannot exclude parent directory files — only the content of non-specified subdirectories is excluded.
- **Paths must be relative to repo root**, not the wiki subdirectory. If the wiki lives at `notes/` inside the repo, and a namespace covers `notes/tech/`, the sparse-checkout path argument must be `notes/tech`, not `tech`.
- Namespace paths as declared in `.stele-namespaces` are relative to the graph root. The sparse-checkout wrapper must prepend `config.wikiSubdir` when constructing the path arguments.

### Server Compatibility

Sparse-checkout is a **purely client-side** feature. The server sends the full packfile during clone/fetch; the client applies the filter locally. It works with GitHub, GitLab, Gitea, self-hosted git — no server-side support required. It is only partial clone (`--filter=blob:none`) that requires server support (see §4 below).

### Key Caveats

- **Expanding a namespace** (adding new paths): after updating `info/sparse-checkout`, run `git checkout` or `DirCacheCheckout.setFailOnConflict(false).checkout()`. The missing blobs are already in the object store from the original clone — no network fetch needed for expansion.
- **Re-expanding after git pull**: JGit's normal pull/merge respects sparse patterns. New blobs for in-scope files arrive via the packfile; out-of-scope files are not written to the working tree.
- **Non-sparse-checkout clients**: A full `git pull` from another machine that does NOT use sparse-checkout will work normally. The `.git/info/sparse-checkout` file is local to each clone and not pushed to the remote.
- **`.git/info/sparse-checkout` vs `.git/config`**: `core.sparseCheckout = true` goes in `.git/config`; the actual patterns go in `info/sparse-checkout`. Both are local and not tracked by git.

### Implementation Strategy for SteleKit

Add a new method to `GitRepository`:
```kotlin
suspend fun configureSparseCheckout(
    config: GitConfig,
    paths: List<String>,  // namespace directory patterns, relative to graph root
): Either<DomainError.GitError, Unit>
```

Implement in `JvmGitRepository` via `ProcessBuilder` shelling out to system `git`. The WASM and iOS stubs return `Unit.right()` (no-op). Android also returns no-op for v1 (system git not available on Android). If system git is not in PATH, return `Unit.right()` silently (per FR-4.4).

---

## 2. OPFS (Origin-Private File System) for WASM

### Current State in SteleKit

OPFS is already implemented and working in SteleKit's WASM build:
- `OpfsInterop.kt` — wraps `navigator.storage.getDirectory()`, `getDirectoryHandle()`, `getFileHandle()`, `createWritable()` via Kotlin/WASM JS interop
- `WasmOpfsSqlDriver.kt` — uses a Web Worker with SQLite compiled to WASM, backed by OPFS
- `PlatformFileSystem.kt` — wraps OPFS for markdown file I/O

The infrastructure for reading/writing markdown files to OPFS is **already present**. Namespace caching is an extension of the existing pattern.

### Browser Support

OPFS (`navigator.storage.getDirectory()`) is **Baseline Widely Available since March 2023** (MDN designation).

| Browser | OPFS Support | Since |
|---------|-------------|-------|
| Chrome / Edge | Full | 86+ |
| Firefox | Full | 111+ |
| Safari (macOS + iOS) | Full | 15.2+ |
| Chrome Android | Full | 86+ |

**Important**: The OPFS async API is distinct from the File System Access API (file picker dialogs). Firefox has objected to the File System Access API as harmful to privacy, but that is irrelevant — SteleKit only uses OPFS internals (`navigator.storage.getDirectory()`), which Firefox fully supports.

### Storage Quota

- **Quota is origin-scoped** (not per-namespace or per-file).
- **Chrome/Edge**: Up to ~60% of total disk. A 500 GB disk → ~300 GB limit. The 50 MB target is trivial.
- **Firefox**: Up to 10 GiB per origin (best-effort), or 50% of disk (persistent).
- **Safari**: ~60% of total disk for web apps; ~15% for embedded WebViews. iOS home-screen PWAs get the 60% limit.
- **Eviction**: LRU under storage pressure. OPFS data is NOT subject to ITP/7-day eviction (unlike cookies/localStorage). Data persists until user clears site data OR browser evicts under storage pressure (rare for small datasets).
- **`navigator.storage.persist()`**: Call on first namespace download. Prevents eviction without explicit user action. Chrome auto-approves based on engagement heuristics; Firefox shows a prompt. Safari honors it.
- **`navigator.storage.estimate()`**: Returns `{quota, usage}` — use this for the cache size display in FR-5.5.
- **`QuotaExceededError`**: Must be caught when writing. Surface to user as "Storage full" with instructions to free space.
- **50 MB / namespace**: Well within all quotas. SteleKit should call `persist()` on first OPFS write.

### Thread Model

- **Async API** (used by SteleKit) — available on main thread and in workers. This is what `OpfsInterop.kt` uses.
- **Sync API** (`FileSystemSyncAccessHandle`) — only available in **dedicated Web Workers**. SteleKit's SQLite worker already runs in a worker, which is why it can use OPFS sync handles internally.
- For markdown file I/O (non-SQLite), the async API on the main thread is fine for individual file reads/writes. For bulk import of 500 files, moving to a worker is preferable to avoid UI jank.

### Directory Enumeration

OPFS supports recursive directory listing via the async iterator API that SteleKit's `OpfsInterop.kt` already implements (`listOpfsEntries`). The existing implementation iterates `handle.values()`. Recursive traversal requires calling this on each subdirectory handle.

### Kotlin/WASM Considerations

- SteleKit already uses OPFS from Kotlin/WASM via `js(...)` interop. No known blockers.
- `navigator.storage.getDirectory()` requires a secure context (HTTPS or localhost). Satisfies the typical deployment scenario.
- OPFS cannot be accessed cross-origin (it is origin-private by definition).

### Namespace Caching Design

For namespace content caching, files should be stored at:
```
OPFS root/
  namespaces/
    {urlSafeRemoteUrl}/
      {namespaceName}/
        tech/
          MyPage.md
        pages/
          Technology/
            ResearchNote.md
        .namespace-meta.json   ← last synced commit SHA, file count, etc.
```

This aligns with the existing `opfsWriteFile` / `opfsDeleteFile` pattern.

---

## 3. TOML Parsing in KMP

### Current State

SteleKit has no TOML library. Dependencies include `kotlinx-serialization-json:1.10.0` for JSON. The namespace manifest (`.stele-namespaces`) is defined as TOML (FR-1.1).

### Library Options

#### ktoml (akuleshov7/ktoml — now orchestr7/ktoml)
- ~557 GitHub stars, latest release **v0.7.1 (Aug 22, 2025)**, actively maintained
- **WASM/JS target: explicitly listed as supported** in the project's supported-platforms documentation
- Pure Kotlin commonMain — no Java runtime dependencies
- Full kotlinx.serialization integration (`@Serializable` data classes, `Toml.decodeFromString<T>()`)
- Supports arrays of tables (`[[namespace]]`), strings, arrays — exactly what `.stele-namespaces` needs
- `ignoreUnknownNames = true` config option for forward compatibility
- Two modules: `ktoml-core` (string decoding) and `ktoml-file` (adds okio — not needed since SteleKit reads files via PlatformFileSystem/OPFS)
- Dependency: `implementation("com.akuleshov7:ktoml-core:0.7.1")`

#### tomlkt (eav-eav-eav/tomlkt)
- ~137 GitHub stars, release 0.6.0 (Jan 2026)
- WASM/JS support: **not confirmed** in README's platform list
- Not recommended over ktoml

#### kotlinx.serialization (native TOML)
- No TOML format. JSON, CBOR, ProtoBuf only. Not applicable.

#### Hand-rolled parser (fallback option)
A hand-rolled parser for the exact `.stele-namespaces` grammar (section headers, string values, string arrays, comments) is ~80-120 lines of commonMain Kotlin. Zero dependency risk. Suitable if ktoml introduces any WASM breakage.

**Recommendation**: Use **ktoml-core** (`com.akuleshov7:ktoml-core:0.7.1`). WASM support is confirmed; kotlinx.serialization integration makes parsing a 3-line operation. Add only `ktoml-core` to `commonMain` dependencies — do NOT add `ktoml-file`.

---

## 4. Git HTTP Smart Protocol for WASM File Fetching

### Problem

The WASM `JsGitManager` is currently a stub returning 501 errors for all operations. To implement FR-5.2 (fetching namespace content on first WASM load), the WASM build needs to retrieve markdown files from a git remote.

### Options Evaluated

#### Option A: Full git HTTP smart protocol (isomorphic-git)
`isomorphic-git` is a pure-JS git implementation that speaks the git HTTP smart protocol from a browser.

- **Partial clone support**: isomorphic-git does **NOT** support `--filter=blob:none` or `--filter=tree:0`. It fetches full packfiles only.
- **Sparse checkout**: isomorphic-git supports `checkout` with a `filepaths` array — it materializes only specified paths from an already-fetched pack. But since it has to fetch the full packfile first, this does not reduce network transfer.
- **CORS**: Direct browser→GitHub/GitLab git protocol requests are **blocked by CORS**. A thin proxy is required. This adds server infrastructure complexity.
- **Conclusion**: Not viable for selective namespace fetching — bandwidth reduction is the key goal.

#### Option B: Git partial clone + sparse-checkout (system git)
`git clone --filter=tree:0 --no-checkout <url>` + `git sparse-checkout set tech/ pages/Technology/` is the most efficient desktop approach. **Not runnable from a browser.** Server support: GitHub full, GitLab experimental, self-hosted git may not support it. This is viable for Desktop JVM via `ProcessBuilder`.

#### Option C: GitHub/GitLab/Gitea REST API (recommended for WASM v1)

**GitHub:**
- `GET /repos/{owner}/{repo}/git/trees/{sha}?recursive=1` — returns the full file tree (path + blob SHA + size) in one response, up to 100,000 entries
- Raw file content: `https://raw.githubusercontent.com/{owner}/{repo}/{branch}/{path}` — no API call needed; bypasses base64 encoding
- **Rate limits**: 60 req/hr unauthenticated, **5,000/hr with PAT** — a PAT is required for 500 files

**GitLab:**
- `GET /api/v4/projects/:id/repository/tree?recursive=true&path=tech/` (paginated)
- Raw file content: `GET /api/v4/projects/:id/repository/files/:file_path/raw?ref=main`

**Gitea:**
- `GET /api/v1/repos/{owner}/{repo}/git/trees/{sha}?recursive=true`
- Raw content: `GET /api/v1/repos/{owner}/{repo}/raw/{filepath}?ref=main`

**Workflow for 500 files (GitHub example):**
1. One call to `/git/trees/HEAD?recursive=1` → filter client-side to namespace paths → get list of paths + SHAs
2. Fetch raw content URLs in parallel (10 concurrent) → write to OPFS
3. Cache HEAD SHA in `.namespace-meta.json` for delta-sync

**Delta sync (FR-5.3):** Compare cached SHA vs current remote HEAD SHA. Use `GET /repos/{owner}/{repo}/commits?path=tech/&since=<date>` to identify changed files without re-fetching all 500.

#### Option D: SteleKit sync server (v2 future)
A SteleKit server that serves namespace content as a tarball. Eliminates per-platform API differences. Deferred.

### WASM Recommendation

For FR-5.2 (v1), use the **platform REST API** (GitHub/GitLab/Gitea):
1. Fetch `.stele-namespaces` from raw file URL to get namespace definitions
2. Fetch the git tree (`recursive=1`) for the remote HEAD
3. Filter tree to namespace paths
4. Download raw file contents in parallel (10 concurrent); write to OPFS via `opfsWriteFile`
5. Store HEAD SHA + file count in `.namespace-meta.json` in OPFS for delta-sync

This requires a PAT for private repos (credential store already exists). For self-hosted Gitea without tree API support, fall back to isomorphic-git as a thin-proxy option. A new `NamespaceSyncService` in `wasmJsMain` handles this, using existing `opfsWriteFile` infrastructure.

---

## 5. KMP expect/actual for Git Operations

### Current Architecture

| Platform | Implementation | Git Library |
|----------|---------------|-------------|
| Desktop JVM | `JvmGitRepository` | JGit 7.3.0 |
| Android | `AndroidGitRepository` | JGit 7.3.0 (Jsch for SSH) |
| iOS | `IosGitRepository` | Stub (no JGit on iOS) |
| WASM | `JsGitManager` (separate interface) | Stub (501 returns) |

The git layer uses `expect`/`actual` via factory objects (`GitRepositoryFactory`, `GitManagerFactory`), not Kotlin's `expect`/`actual` keyword directly — it uses interface dispatch.

### Sparse-Checkout Integration Point

Sparse-checkout is only applicable to Desktop JVM (and potentially Android). The implementation plan:

1. Add `suspend fun setSparseCheckoutPaths(config: GitConfig, paths: List<String>): Either<DomainError.GitError, Unit>` to `GitRepository`
2. Implement in `JvmGitRepository` using JGit's `StoredConfig` API to write `info/sparse-checkout`
3. `AndroidGitRepository` can use the same JGit path — JGit is on the classpath for Android too
4. `IosGitRepository` returns `Unit.right()` (no-op)
5. The WASM build does not use `GitRepository` at all — it uses `JsGitManager` separately

### Implementation via ProcessBuilder (system git)

Since JGit has no sparse-checkout support, `JvmGitRepository.setSparseCheckoutPaths` must shell out:

```kotlin
override suspend fun setSparseCheckoutPaths(
    config: GitConfig,
    paths: List<String>,  // relative to graph root, e.g. ["tech/", "pages/Technology/"]
): Either<DomainError.GitError, Unit> = withContext(PlatformDispatcher.IO) {
    try {
        // Detect system git
        val gitExe = findGitExecutable() ?: return@withContext Unit.right()  // FR-4.4: silently skip

        // Prepend wikiSubdir prefix to translate graph-root paths to repo-root paths
        val repoPaths = paths.map { p ->
            if (config.wikiSubdir.isNotEmpty()) "${config.wikiSubdir}/${p.trimEnd('/')}"
            else p.trimEnd('/')
        }

        // Init cone mode (idempotent)
        ProcessBuilder(gitExe, "sparse-checkout", "init", "--cone", "--no-sparse-index")
            .directory(File(config.repoRoot))
            .start().waitFor()

        // Set active namespace paths
        ProcessBuilder(gitExe, "sparse-checkout", "set", *repoPaths.toTypedArray())
            .directory(File(config.repoRoot))
            .start().waitFor()

        Unit.right()
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        DomainError.GitError.CommitFailed("Sparse-checkout failed: ${e.message}").left()
    }
}

private fun findGitExecutable(): String? {
    for (cmd in listOf("git")) {
        if (runCatching { ProcessBuilder("which", cmd).start().waitFor() == 0 }.getOrDefault(false)) {
            return cmd
        }
    }
    return null
}
```

After `git sparse-checkout set`, the working tree is updated automatically. No `git checkout` or `git read-tree` call is needed. Missing blobs in a full clone are already in the object store and materialized immediately.

---

## Summary of Key Risks and Decisions

| Decision | Recommendation | Risk |
|----------|---------------|------|
| TOML parsing | `ktoml-core:0.7.1` (wasmJs confirmed, kotlinx.serialization integration) | Low — confirmed WASM support; v0.7.1 is recent |
| Desktop sparse-checkout | `ProcessBuilder("git", "sparse-checkout", ...)` — system git required | Medium — system git must be installed; silently skip if not present (FR-4.4); no JGit API exists |
| Android sparse-checkout | No-op stub for v1 — system git not reliably available on Android | None — v1 explicitly excludes Android |
| iOS sparse-checkout | No-op stub | None — iOS is out of scope for v1 |
| WASM file fetching | Platform REST API (GitHub/GitLab/Gitea) with PAT | Medium — PAT required for private repos; CORS-free via raw file URLs for public repos; rate-limited |
| OPFS namespace cache | Extend existing `opfsWriteFile` infrastructure with namespace key prefix | Low — OPFS and interop already proven; add `persist()` call on first write |
| OPFS storage persistence | Call `navigator.storage.persist()` on first namespace sync | Low — Chrome auto-grants; Firefox prompts user |
| Safari OPFS data retention | Without `persist()`, Safari may evict after 7 days of inactivity | Medium — requires `persist()` call; document in release notes |
