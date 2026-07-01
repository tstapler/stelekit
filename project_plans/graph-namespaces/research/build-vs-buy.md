# Build vs. Buy Research — Graph Namespaces

Research date: 2026-06-24

---

## Q1 — isomorphic-git for WASM namespace fetch

### What it is
`isomorphic-git` (v1.38.5, released 2026-06-16) is a pure-JavaScript reimplementation of git that runs in both Node.js and browsers. It has no native dependencies, making it viable anywhere JS can run.

### Maintenance status
- Snyk health score: **93/100** ("Key ecosystem project", "Healthy" maintenance)
- 1.7 M weekly npm downloads
- 3 active maintainers; releases ship automatically after every PR merge via semantic-release
- Last commit: 8 days ago (as of research date); 277 open issues, 37 open PRs
- Original author left but the project is actively stewarded by the community

### Sparse checkout / partial fetch
isomorphic-git does **not** support git sparse-checkout or `--filter=blob:none` partial clone (protocol v2 fetch filter). The FAQ explicitly acknowledges the missing wire-protocol v2 fetch-filter feature and says it would be added once GitHub supports it end-to-end. Without this, a WASM fetch using isomorphic-git downloads the **full packfile** for the cloned branch — unsuitable for fetching only a namespace subtree of a large graph.

### Kotlin/WASM interop feasibility
Kotlin/Wasm can call JS libraries via `external` declarations and `@JsModule`. The project already does this for `@sqlite.org/sqlite-wasm`. Wrapping isomorphic-git is technically the same pattern. However, isomorphic-git requires a filesystem abstraction (`@isomorphic-git/lightning-fs` is the canonical browser FS — an IndexedDB-backed VFS). Bridging this with OPFS (which SteleKit needs for SQLite) would require maintaining two parallel virtual filesystems in the WASM build.

### Verdict
**Do not use** isomorphic-git for namespace-selective fetching. Its missing partial-clone support means it would download entire repo packfiles. It is a viable candidate only if the design switches to full-clone + in-memory namespace filtering (which conflicts with NFR-1 / 10 s first-load target for a 500-page namespace on 10 Mbps). The JS interop wiring is non-trivial but achievable.

---

## Q2 — JGit sparse-checkout vs. system git ProcessBuilder

### JGit sparse-checkout status
JGit (already on classpath at v7.3.0) does **not** have a working sparse-checkout implementation. Eclipse Bugzilla issue #383772, open since 2012, is still in **NEW** status as of 2023-12-12 (the bugzilla site is now archived; the project has moved to GitHub but the feature remains unmerged). Draft Gerrit patches exist from 2017–2018 but were never landed. JGit's `CheckoutCommand` applies the full working tree without honoring `.git/info/sparse-checkout`.

**Consequence:** calling `git.checkout()` through JGit on a sparse-checkout-configured repo will break the sparse state (Bug #383772's original report describes exactly this — JGit disables the sparse-checkout config on pull). Using JGit for FR-4 sparse-checkout management is not viable.

### system git via ProcessBuilder
The JVM platform already shells out to system `git` for several operations (FR-4.4 already anticipates this with the "if git is unavailable" fallback). Sparse-checkout via `ProcessBuilder` running:

```
git sparse-checkout init --cone
git sparse-checkout set <paths>
```

is straightforward, well-tested (git 2.25+), and avoids the JGit limitation entirely. The existing `JvmGitRepository` pattern using `withContext(PlatformDispatcher.IO)` wraps the blocking ProcessBuilder call cleanly.

**Risk:** system `git` must be in PATH. This is guaranteed on Linux/macOS developer machines but may not be present in CI Docker images. Mitigation: the requirements already make sparse-checkout optional (FR-4.4).

**go-git (JVM):** not applicable here — go-git is a Go library with no JVM bindings. The question of go-git is only relevant if the team were to build a separate sidecar process, which is out of scope.

### Verdict
**Build (ProcessBuilder + system git) for JVM sparse-checkout.** JGit's sparse-checkout is broken by design and has been for 14 years. ProcessBuilder with `git sparse-checkout` is the correct path. The existing `JvmGitRepository` architecture supports this cleanly.

---

## Q3 — TOML library for KMP (JVM + wasmJs)

### Options evaluated

| Library | Latest | Platforms | WASM/JS | License | Notes |
|---------|--------|-----------|---------|---------|-------|
| **ktoml** (`com.akuleshov7:ktoml-core`) | 0.7.1 (Aug 2025) | JVM, JS, Native (linux, mac, ios, mingw), **wasmJs, wasmWasi** | Yes (ktoml-core only) | MIT | 557 GitHub stars; TOML 1.0 compliance suite; uses kotlinx.serialization |
| **tomlkt** (`net.peanuuutz:tomlkt`) | — | JVM, JS, Native | Unclear | Apache 2.0 | Smaller community; no explicit wasmJs confirmation in public docs |
| **kotlinx.serialization + custom backend** | — | All | Yes | Apache 2.0 | No existing TOML backend in kotlinx; would require building from scratch |

### ktoml detail
- Explicitly lists `wasmJs` and `wasmWasi` as supported targets in the README (for `ktoml-core` only; `ktoml-file` uses okio and is JVM/Native only)
- `ktoml-file` (file reading) is not available for WASM — file I/O must go through the platform's own file system abstraction (OPFS in browser, `okio` on desktop)
- Fully implemented in Kotlin common; no Java code or Java dependencies
- Supports `@Serializable` data classes with `@SerialName`, inline tables, arrays of tables — covers the `.stele-namespaces` format exactly
- Supports `ignoreUnknownNames = true` for forward-compatibility as new manifest fields are added
- MIT license — compatible with SteleKit's Elastic-2.0 licensing

### Integration pattern for the manifest
On JVM: `ktoml-file` + `TomlFileReader.decodeFromFile<NamespaceManifest>(path)`.
On WASM: read the file bytes via OPFS `FileSystemFileHandle.getFile()` → `text()` → `ktoml-core` `Toml.decodeFromString<NamespaceManifest>(string)`.

### Verdict
**Buy: use `ktoml-core` on all targets and `ktoml-file` on JVM/Native.** It is the only KMP TOML library with confirmed `wasmJs` support, MIT license, and active maintenance (v0.7.1 released August 2025, Kotlin 2.2.0 support). No build required.

**Dependency to add:**
```kotlin
// commonMain
implementation("com.akuleshov7:ktoml-core:0.7.1")
// jvmMain + androidMain (for file reading)
implementation("com.akuleshov7:ktoml-file:0.7.1")
```

---

## Q4 — GitHub REST API as alternative to git-level namespace fetch (WASM)

### What the API offers
`GET /repos/{owner}/{repo}/contents/{path}` returns a file's base64-encoded content or a directory listing (up to 1,000 entries). `GET /repos/{owner}/{repo}/tarball/{ref}` downloads a full-repo tar archive. There is no endpoint to fetch a subdirectory as a tar.

### Feasibility as a namespace fetch mechanism

**Pros:**
- No git protocol implementation needed in WASM
- Works in any browser with `fetch()` — no SharedArrayBuffer / COOP headers required beyond what OPFS already needs
- Per-file fetching is straightforward with Ktor (already on classpath)
- The `.stele-namespaces` manifest can be fetched as a single file to bootstrap namespace selection

**Cons:**
- **GitHub-only**: locks users to GitHub-hosted repos; self-hosted Gitea/Forgejo/GitLab users get nothing
- **Per-file API calls**: a 500-page namespace = 500+ sequential or parallel REST calls. GitHub unauthenticated rate limit is 60 req/h; authenticated is 5,000 req/h. 500 files per namespace would consume 10% of the authenticated hourly budget on first sync — and the rate limit resets hourly, not per-session
- **No incremental sync**: the contents API returns content at a ref but there is no diff-since-commit primitive. Detecting which files changed since last sync requires listing the entire namespace tree and comparing SHAs, which is O(namespace size) on every refresh
- **1,000-file directory cap**: a namespace with more than 1,000 files in a single directory requires the Git Trees API instead
- **No offline commit**: the user cannot push back changes via REST without implementing the create/update/delete file endpoints and constructing commits manually — extremely complex vs. existing `JvmGitRepository` / future WASM git approach
- **100 MB file limit**: files over 100 MB are not accessible via the contents API at all

### Alternative: GitHub archive download
`GET /repos/{owner}/{repo}/tarball/{ref}` downloads the **entire repo** as a tar. This is simpler than per-file calls but downloads the whole graph — worse than even isomorphic-git for the bandwidth goal.

### Verdict
**Do not use** the GitHub REST API as the primary WASM sync mechanism. The rate limits, lack of incremental sync, GitHub lock-in, and no write-back capability make it unsuitable for the namespace sync requirement. It is acceptable as a **fallback for initial `.stele-namespaces` manifest discovery** (single file fetch to show namespace options before the user commits to a full sync), or as an escape hatch when git protocol is unavailable.

A better WASM approach is to use `isomorphic-git` for a full clone (accepting the current NFR-1 limitation) or to build a lightweight SteleKit sync API endpoint (a thin server that serves zip archives of specific namespace paths, outside scope of v1).

---

## Q5 — Per-namespace SQLite database vs. filtered single database

### Option A: Per-namespace SQLite DB
Each namespace is stored in a separate SQLDelight database file (`/stelekit/<graphId>/<namespace>.sqlite`).

**Pros:**
- Perfect isolation: activating/deactivating a namespace = opening/closing a database connection; no unbounded queries required
- OPFS cache maps naturally: one OPFS file per namespace, independently evictable
- Schema changes per-namespace are isolated
- Deleted namespace = delete one file

**Cons:**
- Cross-namespace link resolution (FR-3) requires queries across attached databases using SQLite's `ATTACH DATABASE` mechanism; SQLDelight does not generate cross-database queries — these must be hand-written raw SQL
- `DatabaseWriteActor` and `RepositoryFactory` must be instantiated per-namespace, increasing object count linearly with active namespace count
- `MigrationRunner` must run on every namespace database independently during upgrades
- `GraphLoader.indexRemainingPages` drain loop must iterate across all namespace databases separately
- The `PageNameIndex` (for wikilink resolution) must merge data from N databases; its current in-memory design assumed a single DB
- More complex `GraphManager` lifecycle: `addGraph` must open N databases for N active namespaces

### Option B: Single database, namespace-filtered queries
All namespaces share one SQLDelight database. A `namespace_name TEXT` column is added to `pages`. `GraphLoader` writes each page's namespace at index time. Repository queries accept an optional `activeNamespaces: Set<String>?` filter.

**Pros:**
- Zero cross-namespace query complexity: a join from `blocks` to `pages` filtered by namespace is trivial SQL
- No change to `DatabaseWriteActor`, `MigrationRunner`, `RepositoryFactory` architecture
- `PageNameIndex` continues to work as-is (just filter its snapshot by namespace)
- Single `ATTACH` migration for the new column
- Consistent with the existing multi-graph model: namespaces are sub-graph partitions within one DB per graph

**Cons:**
- Namespace deactivation does not free disk space (pages remain in the DB even if not loaded from disk)
- OPFS cache cannot be per-namespace: the entire graph DB must be cached even if only one namespace is active — but the graph DB only stores parsed content, not the raw `.md` files, so it is much smaller than the full git checkout
- A query for "all pages in namespace X" must add a WHERE clause; without a proper index on `namespace_name`, this could be slow at graph scale (mitigated with `CREATE INDEX`)

### Verdict
**Build with Option B (single database, namespace-filtered queries)** for v1. The cross-namespace query complexity of Option A would require significant new infrastructure and deviates from the established single-DB-per-graph architecture. Option B aligns with the existing `activeNamespaceSet` parameter already sketched in the requirements (`GraphManager.addGraph(path, activeNamespaceSet: Set<String>?)`). Add a `namespace_name TEXT NOT NULL DEFAULT 'default'` column to `pages`, index it, and filter all repository queries when an active namespace set is configured.

Option A becomes worth revisiting if per-namespace OPFS eviction granularity proves important in a v2 iteration.

---

## Summary Table

| Question | Decision | Rationale |
|----------|----------|-----------|
| isomorphic-git for WASM fetch | **Do not use** | No partial clone / sparse fetch; downloads full packfiles |
| JVM sparse-checkout | **ProcessBuilder + system git** | JGit sparse-checkout is broken (open bug since 2012, still unmerged) |
| TOML library | **ktoml-core 0.7.1** | Only KMP TOML lib with confirmed wasmJs support; MIT; active |
| GitHub REST API for WASM sync | **No (fallback only)** | Rate limits, GitHub lock-in, no incremental sync, no write-back |
| Namespace DB architecture | **Single DB, filtered queries** | Avoids cross-DB complexity; fits existing architecture |
