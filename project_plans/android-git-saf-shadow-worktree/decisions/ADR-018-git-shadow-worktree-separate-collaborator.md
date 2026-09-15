# ADR-018: `GitShadowWorktree` as a Separate Collaborator, Not an Extension of `ShadowFileCache`

## Status

Proposed (part of `android-git-saf-shadow-worktree` planning; promote to `docs/adr/` at Phase 5
implementation kickoff, per the same convention `ADR-017` used for `web-git-writeback`).

## Context

The Android git-sync fix needs a real `java.io.File`-backed working tree for JGit, since JGit
cannot operate on SAF `content://` URIs (see `requirements.md`, options 1-4 already rejected).
The obvious reuse candidate is `ShadowFileCache`
(`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/ShadowFileCache.kt`), which already
mirrors SAF content to `context.filesDir/graphs/$graphId/shadow` for Phase 3 background indexing,
and requirements.md explicitly proposed "reusing/extending `ShadowFileCache`."

Research (`research/architecture.md` §4, `research/stack.md` §2, `research/pitfalls.md` §4.4)
found three concrete reasons this reuse cannot be a simple extension:

1. **Invariant conflict.** `ShadowFileCache`'s class doc states its invariant explicitly: "SAF is
   the only write target; shadow is a read cache derived from SAF"
   (`ShadowFileCache.kt:15-20`). A git working tree is inherently bidirectional (JGit writes
   checkouts/merges into it; the app must write resolved/merged content back out to SAF) and
   needs a freshness *precondition*, not just an eventually-consistent read cache.
2. **Purge-on-first-access is destructive to `.git`.** `ShadowFileCache.deleteAll()`
   (`ShadowFileCache.kt:163-165`) is invoked from `PlatformFileSystem.invalidateStaleShadow()` on
   every graph session's first access, as a correctness-over-performance choice for the
   pages/journals read cache. Applying that same policy to a directory containing `.git/` would
   destroy commit history and remotes on every cold start.
3. **Scope mismatch.** `ShadowFileCache`/`PlatformFileSystem.syncShadow()` are hardcoded to two
   subdirectories, `"pages"` and `"journals"` (`PlatformFileSystem.kt:848-857`). A git working
   tree needs the whole `repoRoot` subtree (any top-level files, non-wiki directories the
   repository happens to contain, `.git/` itself excluded from SAF sync since it has no SAF-side
   counterpart).

A fourth reason, found while writing this plan and not previously documented: **`ShadowFileCache`'s
directory-naming key is not `GraphManager`'s `GraphId`.** `ShadowFileCache` is always constructed
with `ShadowFileCache.graphIdFor(treeDocId)`
(`PlatformFileSystem.kt:138,673`) — a sanitized SAF tree-document-ID string — never with
`GraphManager`'s `GraphId` (`sha256(canonicalPath)`-derived, `GraphManager.kt:315-316`). These are
two different identifiers for "the same graph" that happen to coexist today only because
`ShadowFileCache` has no lifecycle coupling to `GraphManager` at all (nothing calls `deleteAll()`
on graph removal; see `research/architecture.md` §2). A git shadow tree, once it carries
history-bearing state, needs a cleanup story that doesn't depend on bridging this mismatch
precisely — plan Phase 6 (Epic 6.1) settled on a purely local, time-based orphan sweep (a shadow
directory stamps its own last-used marker on every real operation; the sweep deletes only
directories unused past a grace period) specifically *because* precisely bridging `GraphId` to
`shadowKey` at cleanup time turned out to require cross-graph config access `GraphManager` doesn't
safely expose today (see plan.md's Epic 6.1 rationale, added after round-4 adversarial review) —
not a design this ADR needs to resolve itself.

## Decision

Introduce a new collaborator, `GitShadowWorktree`
(`kmp/src/androidMain/kotlin/dev/stapler/stelekit/git/GitShadowWorktree.kt`), instead of extending
`ShadowFileCache`.

- **Disjoint storage root, disjoint key.** `context.filesDir/graphs/$shadowKey/gitshadow`, sibling
  to but never overlapping `ShadowFileCache`'s `context.filesDir/graphs/$shadowKey/shadow`.
  **Addendum (post-adversarial-review correction):** `$shadowKey` here is deliberately **not** the
  same value as `ShadowFileCache.graphIdFor(treeDocId)` — an earlier draft of this ADR proposed
  reusing that exact derivation, but `graphIdFor(treeDocId)` hashes only the SAF tree-URI, which is
  shared across every graph pointing anywhere under one SAF grant. Two SteleKit graphs configured
  against two different subfolders under the same grant (e.g. `.../Documents/personal-wiki` and
  `.../Documents/work-wiki`) would then collide on the same `gitshadow` directory — i.e. the same
  `.git` repository — silently mixing both graphs' commits, merges, and remote config. `plan.md`'s
  Task 5.1.1a fixes this: `shadowKeyForSafPath()` hashes the **full** `repoRoot` string (tree URI
  + relative subpath), matching `GraphManager.graphIdFromPath()`'s basis
  (`GraphManager.kt:315-316`) rather than `ShadowFileCache`'s tree-only basis. The two directory
  trees remain disjoint and never read/written by each other's code path either way.
- **Own invalidation policy.** No first-access `deleteAll()`. Freshness is tracked with an explicit
  manifest (per-file SAF mtime + size, written after every `syncFromSafRoot()`) and checked via
  `ensureFresh()` as a runtime precondition immediately before any JGit working-tree operation —
  not a documented calling convention, an enforced one (resolves requirements.md Open Question 1
  and pitfalls.md §4.3's highest-severity risk).
- **Own lifecycle, cleanup decoupled from `GraphManager.removeGraph()`.** No registered handler on
  `GraphManager` — that approach was tried and dropped (see Consequences below). Instead, each
  shadow directory tracks its own last-used marker (updated on every real git operation) and is
  deleted only by a startup orphan sweep once that marker is older than a grace period (60 days by
  default) — never as a matter of course, and never via a synchronous call from `removeGraph()`.
- **Independent derivation, no shared state.** `GitShadowWorktree.shadowKeyForSafPath()` uses its
  own full-`repoRoot` hash (see addendum above), not `ShadowFileCache.graphIdFor()` — the two
  classes hold no reference to each other and share no mutable state, and now also no key
  derivation, since sharing it was the source of the collision this addendum corrects.

## Consequences

- Two on-disk shadow trees exist per graph once git sync is configured in shadow-mirror mode
  (`.../shadow` for indexing, `.../gitshadow` for git) — a real, if modest, storage cost beyond
  what a single merged cache would use, but it removes the need for any cross-cache locking
  protocol between background indexing's `invalidateStale()` and JGit's working-tree
  checkout/status, which would otherwise be racing on the same files with incompatible
  assumptions.
- `PlatformFileSystem`'s write-behind flush (`WriteBehindQueue`/`ShadowFlushActor`) and
  `GitShadowWorktree`'s own sync/write-back still both ultimately write to the *same SAF documents*
  (just via two different local shadow copies), so mutual exclusion between the two subsystems is
  still required — this ADR does not eliminate that coordination need, it only prevents the two
  local shadow trees from corrupting each other. See the plan's mutual-exclusion story (Phase 5,
  Epic 5.2).
- Future maintainers should not "simplify" by merging the two caches without re-deriving all three
  reasons above, plus the `GraphId`/`shadowKey` identifier mismatch — that mismatch is orthogonal
  to this decision and is not fixed by merging the caches. Two identifiers remain for "the same
  graph" (`GraphId = sha256(fullPath)` in `GraphManager`, `shadowKey = sha256(repoRoot)` in
  `GitShadowWorktree` — same hash basis as of the addendum above, but computed independently, not
  looked up from one another). The **correctness** risk (two active graphs colliding on one
  `.git`) is closed by matching the hash basis. The **lifecycle** risk (mapping a `GraphId` back to
  its `shadowKey` at removal time) was originally going to be handled by a registered cleanup
  handler on `GraphManager` — this was dropped (plan.md, Phase 6 Epic 6.1) once round-4
  adversarial review traced `GraphManager.createGitConfigRepository()`
  (`GraphManager.kt:924-931`) and found it only exposes `GitConfigRepository` for the currently
  *active* graph, not every registered one, so no correct implementation of that handler (or an
  earlier per-graph-loop sweep variant) was reachable without a wider `GraphManager` API change.
  The shipped design instead sidesteps the identifier-mapping question entirely: cleanup is driven
  by each shadow directory's own local last-used marker (Phase 6, Story 6.1.1), not by any
  `GraphId`-to-`shadowKey` lookup against `GraphManager` at all.

## Alternatives Considered

1. **Extend `ShadowFileCache` in place** (add a `git/` subtree, relax the read-only doc comment,
   special-case `deleteAll()` to skip `.git/`). Rejected: every one of the three original
   conflicts would need a per-caller conditional (is this the indexing cache or the git cache?),
   turning one class into two classes wearing a trenchcoat, with strictly worse testability than
   two real classes.
2. **A single unified shadow-cache abstraction covering both use cases from the start.** Rejected
   as premature generalization — the two caches' consistency models (eventually-consistent read
   cache vs. a real git working tree with commit/merge semantics and a hard freshness
   precondition) are different enough that a shared abstraction would mostly be conditional logic,
   not shared logic.
3. **A single flat `context.filesDir` root shared ad hoc between both concerns with no dedicated
   class.** Rejected — no enforced isolation, and `invalidateStale()`'s existing dual-signal walk
   (`ShadowFileCache.kt:143-155`) would need to special-case every git-managed file to avoid
   deleting them, re-introducing the exact coupling this ADR avoids.
