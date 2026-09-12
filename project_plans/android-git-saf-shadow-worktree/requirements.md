# Requirements: android-git-saf-shadow-worktree

**Date**: 2026-08-28
**Type**: Feature addition (architectural fix to existing Android git sync)

## Problem Statement

Android Git Sync setup fails for users whose wiki graph lives in a folder they
picked via Android's Storage Access Framework (SAF) document picker — which is
the normal, default way graphs are opened on Android. SAF grants a
`content://` URI, not a real filesystem path. `AndroidGitRepository` is built
on JGit, which only operates on `java.io.File`. The only existing bridge,
`PlatformFileSystem.resolveSafToRealPath()`
(`kmp/src/androidMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt:76`),
depends on the user having granted `MANAGE_EXTERNAL_STORAGE` ("All files
access") in system settings — a permission the app never prompts for, that
Google Play scrutinizes/can reject for apps whose core function doesn't
strictly need it, and that almost no real user will have granted. When
resolution fails, `AndroidGitRepository.resolveForJGit()` (~line 415) silently
falls back to the unresolvable `saf://...` string, producing a cryptic JGit
error ("repository not found: /saf:/content%3A%2F%2F...").

Diagnosed and root-caused this session; a same-session diagnostic fix already
shipped to `main` (commit `d372e76b0e`: clearer warning logging in
`resolveForJGit()` + a share/export button on the in-app Logs screen). That
part is done. This project is the architectural fix that makes Git Sync
actually work for SAF-only users, not just fail more legibly.

## Users / Consumers

- End users running SteleKit on Android who selected their wiki graph via the
  SAF document picker (the default/typical path) and want to use Git Sync
  (pull/push/commit) against SSH remotes, HTTPS remotes, or any self-hosted
  git server — not just GitHub/GitLab.
- Indirectly, the existing `GitRepository` common interface and
  `GitSyncService` consumers (`kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/`),
  which must keep behaving identically for Desktop and for Android users who
  *have* granted `MANAGE_EXTERNAL_STORAGE`.

## Success Metrics

- A user with a SAF-only graph (no `MANAGE_EXTERNAL_STORAGE`) can configure
  Git Sync and successfully clone/pull/commit/push using SSH or HTTPS, against
  any git host, with no user-visible "repository not found" failure.
- Local commits can be made and reviewed before a separate push (existing
  `GitRepository.commit()` / `push()` split is preserved, not collapsed).
- Changes pulled from the remote become visible in the user's SAF-side files
  (and thus in the app's normal editor/graph) without manual intervention.
- No regression for Desktop (`JvmGitRepository`) or Android users with
  `MANAGE_EXTERNAL_STORAGE` granted — both continue to operate on direct file
  access with no added shadow-mirror overhead.
- Bug is gone with regression tests preventing recurrence (both the original
  cryptic-error case and new tests for the shadow-mirror sync paths).

## Constraints

- No hard deadline.
- Must preserve SSH transport (mwiede/jsch, already wired in
  `AndroidGitRepository`) and HTTPS, and must not restrict remotes to
  GitHub/GitLab only (rules out reusing the web's REST-API-only approach for
  this platform).
- Must not require `MANAGE_EXTERNAL_STORAGE` as the only path — it's an
  optional fast path, not the mechanism this feature can depend on.
- Storage is constrained: Android app-private storage (`context.filesDir`) is
  not unbounded; duplicating a full wiki + `.git` history there needs to be
  sized and bounded, though a full growth-bounding solution can be a
  documented fast-follow if out of scope for v1.
- Must not silently lose user edits made directly to the SAF folder (e.g. via
  another SAF-aware app, or a home-screen widget/share-target write) that race
  against a git pull/merge in flight.

## Scope

### In Scope

- Designing (not yet building — this is planning phases 1-4 only) the Android
  shadow-working-tree approach: JGit operates on a real,
  `java.io.File`-backed working tree at `context.filesDir/graphs/$graphId/shadow`
  (reusing/extending `ShadowFileCache`) instead of directly on an unresolvable
  SAF path.
- Bidirectional sync design between the shadow tree and the SAF folder:
  - SAF → shadow before any git operation (already exists via
    `ShadowFileCache.syncFromSaf()` — direction is not new, but its
    read-only/one-directional invariant and all callers that assume it must be
    re-audited).
  - Shadow → SAF after fetch/merge brings in remote changes (new direction —
    does not exist today; must reuse `PlatformFileSystem`'s existing SAF write
    primitives, e.g. `writeFile`/`writeFileBytes`).
- Race-condition handling between external SAF writes (widget, share-target,
  other SAF-aware apps) and in-flight git operations — investigating whether
  existing `SafChangeDetector` / `GraphLoader.externalFileChanges` /
  `DiskConflict` machinery can be reused or must be extended.
- Conflict-resolution UI path mapping: `ConflictResolutionScreen` and
  `MergeResult`/`ConflictFile` currently build paths from `config.repoRoot`;
  when `repoRoot` becomes the shadow directory, the mapping from
  shadow-relative conflict paths back to the user-facing SAF location must be
  explicit and correct.
- Storage cost / staleness sizing and bounding strategy (can defer full
  implementation of bounding to a fast-follow, but the plan must size the
  problem and propose an approach).
- Initial setup / first-clone reconciliation: deciding how a freshly
  configured Git Sync on an already-populated SAF-only graph reconciles
  pre-existing markdown files against a newly cloned (or newly initialized)
  git history.
- Decision logic for when the app uses: shadow-mirror path vs. direct
  JGit-on-real-path (SAF resolved via `MANAGE_EXTERNAL_STORAGE`) vs. Desktop's
  existing direct-file path.

### Out of Scope

- Building or designing the in-memory, multiplatform git engine (real
  blobs/trees/commits/refs in memory) that the user wants eventually as a
  generalization of the web's `WasmGitWriteService`
  (`kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/WasmGitWriteService.kt`,
  per `docs/adr/ADR-015-wasm-git-data-api-write-back.md`). Flag this as a
  documented future/adjacent epic and note where today's interfaces
  (shadow-mirror sync, `GitRepository` implementation selection) must stay
  decoupled so building it later doesn't require re-touching this Android
  work. No design effort on its internals in this pass.
- iOS git support (`IosGitRepository` is currently a stub, `NotSupported`) —
  unrelated to this effort.
- Any web-side (`wasmJs`) changes.
- Actual implementation (phase 5+) — this planning pass stops after phase 4
  (validate/pre-mortem). A fresh session is required before implementation per
  this repo's SDD convention.
- Options already evaluated and explicitly rejected this session (cite as
  decided, do not re-litigate):
  1. Forking JGit to add native SAF/`content://` support — JGit's
     working-tree layer constructs `java.io.File`/`FileInputStream`/
     `RandomAccessFile` directly throughout `WorkingTreeIterator`,
     `DirCacheCheckout`, `AddCommand`, `StatusCommand`, not cleanly behind the
     `FS` abstraction; would mean reimplementing JGit's working-tree
     diff/checkout logic wholesale.
  2. Writing a full homegrown git implementation for Android — rejected on
     the same cost/benefit grounds the team already applied to rejecting
     `libgit2`-via-`wasm-pack` for web (ADR-015): large ongoing maintenance
     burden for no benefit over option 3 (shadow tree + real JGit).
  3. Relying solely on `MANAGE_EXTERNAL_STORAGE` — kept as an "if granted"
     fast path (strictly better when available: no shadow-mirror sync
     overhead), but cannot be the only supported path since most users won't
     grant it.
  4. Routing Android through the web's REST-based write-back
     (`WasmGitWriteService`, GitHub/GitLab Git Data API only per ADR-015) —
     explicitly rejected by the user this session ("I want to be able to use
     git over ssh or direct file access, so it's not okay to just use the web
     direct version"); would drop SSH support and arbitrary self-hosted
     remotes.

## Open Questions (for research/plan/pre-mortem phases to resolve)

1. How should `ShadowFileCache`'s current one-directional, read-only
   invariant be safely extended to bidirectional without breaking existing
   callers (background indexing, `SafChangeDetector`, `WriteBehindQueue`)?
2. What is the concrete race-safe protocol for shadow ↔ SAF sync when an
   external SAF write happens concurrently with an in-flight
   fetch/merge/push? Can `SafChangeDetector`/`DiskConflict` be reused as-is,
   or does this direction need its own conflict model?
3. How should `ConflictResolutionScreen` / `MergeResult` / `ConflictFile`
   paths be remapped from shadow-relative to SAF-relative so users only ever
   see/edit SAF-side locations?
4. What's the actual storage footprint (markdown + `.git` history) for a
   representative large graph, and what bounding strategy (shallow-ish
   history, periodic gc, size cap) is appropriate for v1 vs. fast-follow?
5. For first-time Git Sync setup on an already-populated SAF-only graph, does
   `clone`/`init` target the shadow directory directly (and how does it
   reconcile with pre-existing SAF content), or is there a separate
   reconciliation step required?
6. What decision logic (and where in the codebase) selects between: direct
   JGit-on-SAF-real-path (`MANAGE_EXTERNAL_STORAGE` granted), the new
   shadow-mirror path, and Desktop's existing direct-file path — and how is
   this surfaced/tested?
