# ADR-012: System Git via ProcessBuilder for Desktop Sparse Checkout

## Status
Accepted

## Context

The Graph Sections feature requires selective materialization of directory subtrees on desktop JVM: when a user deactivates a section, SteleKit should be able to restrict the git working tree to only the active sections' directories. This allows a work machine to sync only "tech" and "work" sections without materializing "personal" and "journals" on disk (FR-4).

SteleKit's JVM git integration uses **JGit 7.3.0** (`org.eclipse.jgit:org.eclipse.jgit`). JGit is already on the classpath for both Desktop JVM and Android for all existing git operations (commit, push, pull, remote detection).

### Why sparse checkout

Git sparse-checkout (introduced in Git 2.25, cone mode in Git 2.26) restricts which paths appear in the working tree via `CE_SKIP_WORKTREE` index bits. All remote operations (fetch, pull, push) continue to transfer full packfiles — sparse checkout is a purely client-side working-tree filter. For SteleKit:

- Section directories map 1:1 to cone-mode path prefixes. Cone mode requires full-directory prefix patterns, which is exactly what the section manifest provides (`paths = ["tech/", "pages/Technology/"]`).
- The `.stele-sections` manifest file lives at the repo root. Cone mode always includes root-level files, so the manifest is always accessible regardless of section selection.
- No server-side support is required: sparse checkout works with GitHub, GitLab, Gitea, and self-hosted git without any special server configuration.

### JGit sparse-checkout status

JGit has **no working sparse-checkout implementation**. Eclipse Bugzilla issue #383772 ("Add support for sparse checkout to JGit") was filed in 2012 and remains open (status: NEW) as of 2024 when the Bugzilla was archived. The Gerrit code review history shows draft patches from 2017–2018 that were never merged.

The specific failure mode that makes JGit unusable here: JGit's `CheckoutCommand.checkout()` **actively disables sparse-checkout state** on the repository it touches. A graph whose git repository was configured for sparse-checkout by system git would have its sparse state silently removed the next time JGit performed a checkout operation (e.g., during a pull/merge). This is the opposite of what SteleKit needs.

Low-level JGit config manipulation can write `.git/info/sparse-checkout` and set `core.sparseCheckout = true` in `.git/config`, but JGit's checkout logic does not respect these settings when updating the working tree. The sparse patterns are written but ignored.

### Alternative: system git via ProcessBuilder

System `git` (git 2.25+) fully supports sparse-checkout:

```bash
git sparse-checkout init --cone --no-sparse-index
git sparse-checkout set tech/ pages/Technology/ work/ pages/Work/
```

`git sparse-checkout set` atomically replaces the entire pattern set and immediately updates the working tree. No separate `git checkout` or `git read-tree` call is needed. After `set`, the working tree reflects the new pattern set within the same process invocation.

The `--no-sparse-index` flag is required: the `--sparse-index` optimization changes the git index format in a way that confuses JGit and other external tools. Since JGit is on the classpath for all git operations, the index format must remain compatible.

The `--cone` flag selects cone mode (directory-prefix matching), which is required for performance: O(1) hash-map lookups vs. O(N×M) gitignore-pattern matching in non-cone mode. Cone mode aligns exactly with SteleKit's directory-based section paths.

### Platform applicability

| Platform | Sparse checkout | Rationale |
|----------|----------------|-----------|
| Desktop JVM | Via `ProcessBuilder` + system git | System git is available on macOS (Git 2.39+ via Xcode tools) and Linux; silently skip if absent per FR-4.4 |
| Android | No-op (v1) | System git is not reliably available on Android; sparse checkout deferred |
| iOS | No-op (v1) | Out of scope per requirements |
| WASM | Not applicable | WASM uses OPFS + REST API for selective sync (see ADR-013) |

### Path translation requirement

Section paths in the manifest are relative to the graph root (e.g., `tech/`). Sparse-checkout patterns are relative to the repository root. If the graph lives in a `notes/` subdirectory of the repo, the sparse-checkout path must be `notes/tech/`, not `tech/`. The `wikiSubdir` field from existing git-smart-sync detection provides this prefix.

### Full recompute on every toggle

`git sparse-checkout` has no `remove` subcommand on Git < 2.37 (the `--stdin` flag for removals was added in 2.37). The implementation always calls `git sparse-checkout set <full-path-list>` with the complete set of paths for all currently-active sections. This is idempotent, safe, and correct. No incremental add/remove logic is needed.

## Decision

Implement `JvmSparseCheckoutService` using `ProcessBuilder` to invoke system `git`. The implementation:

1. Detects system git via `which git` (or `where git` on Windows). Returns `Unit.right()` silently if git is not in PATH (FR-4.4).
2. Checks for a clean worktree via `git -C $repoRoot status --porcelain`. Returns `DomainError.GitError.UncommittedChanges` if the working tree is dirty, surfacing a UI prompt to commit or stash before changing section selection.
3. Translates section paths from graph-root-relative to repo-root-relative by prepending `wikiSubdir` (stripped of trailing `/`) when non-empty.
4. Runs (in order):
   - `git -C $repoRoot sparse-checkout init --cone --no-sparse-index` (idempotent)
   - `git -C $repoRoot sparse-checkout set $allActiveRepoPaths` (full recompute)
5. All `ProcessBuilder` calls run in `withContext(PlatformDispatcher.IO)`.
6. If all sections are re-activated (activeSectionSet becomes null), runs `git -C $repoRoot sparse-checkout disable` to restore the full working tree.

Non-JVM platforms return `Unit.right()` (no-op `actual` implementations).

## Rationale

1. **JGit has no viable sparse-checkout API and actively breaks sparse state**: this is not a missing feature — JGit's `CheckoutCommand` disables sparse-checkout, which means using JGit for any checkout operation on a sparse-configured repo would be destructive. ProcessBuilder is the only safe path.

2. **System git is the authoritative sparse-checkout implementation**: all git documentation, all CI tooling (GitHub Actions `actions/checkout` ships native `sparse-checkout` support), and production deployments (Microsoft VFS for Git) use system git for sparse-checkout. The feature is stable and widely tested.

3. **ProcessBuilder is already the pattern for JVM shell operations**: the codebase uses `ProcessBuilder` for other process-level operations. Adding sparse-checkout follows the same pattern.

4. **Silent skip is the correct fallback**: FR-4.4 explicitly requires that sparse checkout be skipped if system git is unavailable. Section filtering still applies in-memory (via `SectionFilter`). The user sees filtered pages; the working tree is simply not pruned on disk. This is a graceful degradation, not a failure.

5. **`--no-sparse-index` is mandatory**: the sparse-index optimization changes the index format in a way that confuses JGit. Since JGit performs pull/merge operations on the same repo, the index must remain in a format JGit can handle.

## Alternatives Rejected

### JGit low-level config manipulation
Writing to `.git/config` and `.git/info/sparse-checkout` via JGit's `StoredConfig` API is possible, but JGit's `CheckoutCommand` ignores these settings when materializing the working tree. The sparse patterns are written but not applied. Worse, JGit's next `checkout()` call (e.g., during a pull) will disable sparse-checkout entirely by rewriting `core.sparseCheckout = false`. This approach is counterproductive.

### JGit sparse-checkout when the bug is fixed
Eclipse Bug #383772 has been open for 14 years with no resolution. SteleKit cannot defer this feature on an indefinite wait. If JGit eventually adds a working `SparseCheckoutCommand`, the `JvmSparseCheckoutService` implementation can be replaced without changing the interface contract.

### go-git as a JVM sidecar process
go-git supports sparse-checkout in a Go binary. Running a Go binary as a sidecar process (via `ProcessBuilder`) would work technically but adds a native binary dependency, a separate build system, and cross-compilation complexity. System git is universally available on developer machines and serves the same purpose with zero additional build complexity.

## Consequences

- `SparseCheckoutService` is defined as a common interface with `expect`/`actual` factory dispatch. The JVM actual uses `ProcessBuilder`; all other platforms are no-ops.
- System git is a soft dependency on desktop JVM: absent = silent skip, not an error. This must be documented in the release notes.
- `--no-sparse-index` is always set, ensuring JGit compatibility for pull/merge operations on the same working tree.
- The dirty-worktree check adds a `git status` process invocation before each sparse-checkout operation. This is a blocking IO call on `PlatformDispatcher.IO` and is not in any hot path (section toggle is a rare user action).
- When all sections are re-activated, `git sparse-checkout disable` is called. This restores the working tree to the full checkout and removes the sparse-checkout configuration entirely, matching the primary-device experience.
