# Requirements: app-owned-storage-clone

**Date**: 2026-09-12
**Type**: feature addition
**Complexity**: 4 — migration / cross-cutting change with Large appetite

## Problem Statement

On Android and Web, users creating a new graph or cloning one via git have no explicit, first-class way to choose "keep this entirely inside the app" as a storage destination, and no supported way to move a graph between storage backends once it exists.

- On Android, the git-clone wizard (`GitSetupScreen.kt`'s `Step2RepoPath`) always requires a SAF folder (`ACTION_OPEN_DOCUMENT_TREE`) or a typed path. App-specific storage (`filesDir`) is already used unconditionally under the hood for every graph's SQLite DB (`AndroidLibsqlDriver.kt:38`), a read-only shadow markdown cache (`ShadowFileCache.kt:23`), and — for most git-sync users who haven't granted `MANAGE_EXTERNAL_STORAGE` — a full JGit working tree in `filesDir/graphs/$id/gitshadow` (`GitShadowWorktree.kt:51`) that write-backs to the SAF folder asynchronously. Users never see this and cannot choose it directly; they're forced through the SAF flow regardless.
- On Web, new graphs already default to OPFS ("app-owned storage") via `getDefaultGraphPath()` (`kmp/src/wasmJsMain/.../PlatformFileSystem.kt:453`), but it's exposed as an explicit choice only in the plain new-graph dialog (`AddGraphDialog`, `App.kt:2084`), and only as a fallback when the File System Access API is unsupported. `GitSetupScreen`'s clone wizard has no app-storage option at all — its Browse button is wired unconditionally to `pickDirectoryAsync()`, with a bare text field as the only alternative and no guidance on what a valid OPFS path looks like.
- Neither platform lets a user relocate an existing graph's storage after the fact (e.g., "this graph is in a SAF folder, move it fully into the app" or the reverse). Web has a *related* but narrower mechanism — `FolderSyncSettings`'s livesync mirror between OPFS and a linked real folder — but it's a continuous bidirectional link set up at folder-connect time, not a general relocate/link picker usable from any storage state to any other.

## Baseline

Today:
- Android: every graph's markdown must live in a SAF-picked folder (or a typed path); app storage is invisible internal plumbing (DB + shadow cache + git shadow worktree), never a destination the user selects. To get "fully local, no external folder" behavior, there is no supported path — the user must pick *some* SAF folder even if they never intend to touch it outside the app.
- Web: app storage (OPFS) is the default for new graphs when the browser lacks the File System Access API, but is not offered as an explicit peer option to "pick a real folder" when the API *is* available, and is entirely absent as a choice in the git-clone wizard.
- On both platforms, moving a graph's storage backend after creation is unsupported outside web's narrower OPFS↔real-folder livesync link; there is no general "move this graph from A to B" flow, and no such capability exists for git-cloned graphs on either platform, or for any graph on Android at all.

## Users / Consumers

- SteleKit end users (KMP note-taking app, Logseq migration) on Android and Web who create a new graph or clone one via git sync.
- Indirectly: Desktop/JVM and iOS are out of scope (full native filesystem access already; no "app-owned storage" concept applies there).

## Success Metrics

- A user can create a new graph on Android using only app-owned storage, with zero SAF folder grant required.
- A user can clone a git repo into app-owned storage on both Android and Web, with zero SAF folder grant (Android) / zero File System Access API permission prompt (Web) required.
- An existing graph (git-cloned or plain) can be relocated from a SAF/real folder into app storage, or from app storage into a SAF/real folder, via a single guided flow — verified by: the destination is confirmed intact (file-count/hash check) before the source is offered for deletion, and the graph's registered path is correctly repointed with no data loss (manually verified via before/after file listing, since this is a single-developer app with no telemetry funnel for this flow specifically).
- The directory/location picker used across new-graph, git-clone, and "move storage" surfaces is the same component/pattern everywhere — no more than one picker implementation per platform, and "app-owned storage" is selectable as a row/entry inside that one picker on every surface, not via a separate control the user must find first.

## Appetite

Large (3–6 weeks)

*(Scope must fit the appetite. If it doesn't fit, cut scope — do not move the deadline. Phase 3 planning should sequence work so a usable slice — e.g. creation-time destination picker — ships before the full move/link matrix is complete, in case appetite runs out.)*

## Constraints

- No hard deadline. Single active developer (Tyler); no dedicated QA — verification is manual plus existing automated test suites (`businessTest`, `jvmTest`, Android unit tests, wasmJs tests where applicable).
- Must not regress the existing git-sync shadow-worktree mechanism (`GitShadowWorktree`, `GitShadowFlushActor`/`GitWriteBackQueue`) or web's `FolderSyncSettings` livesync mirror for users who don't opt into the new flows.
- Desktop (JVM) and iOS are unaffected — no app-owned-storage concept there; do not add UI surface for it on those platforms.

## Non-functional Requirements

- **Performance SLO**: not specified — relocate/move operations should not block the UI thread; large graphs (per existing bounded-read conventions, e.g. 8,000+ pages) must not be fully loaded into memory during a move, following this repo's existing "graph-scale reads must be paginated/chunked" discipline.
- **Scalability**: a move/relocate must handle graphs at the largest sizes already exercised in this repo's benchmarks (~8,000 pages) without OOM, consistent with existing Android crash-prevention work (see `LargeGraphWarmStartCrashTest`).
- **Security classification**: internal/personal use; no regulated data. Git credentials (SSH keys, OAuth tokens) already handled by existing git-setup infrastructure — this project must not weaken that handling when a repo's storage location changes.
- **Data residency**: not applicable (all storage is user-device-local).

## Scope

### In Scope

- A unified storage-location concept exposed consistently in:
  1. New-graph creation dialog (Android, Web)
  2. Git-clone wizard `Step2RepoPath` (Android, Web)
  3. A new (or extended) "move storage location" action for an existing graph (Android, Web)
- "App-owned storage" as a first-class, explicitly labeled destination option alongside "pick a folder," on both Android (backed by `filesDir`, replacing the invisible shadow-worktree-as-cache with shadow-worktree-as-primary-storage when chosen) and Web (backed by OPFS, already largely in place).
- Relocate (one-time copy + repoint + optional source cleanup) and link (continuous mirror, extending the existing write-back/livesync mechanisms) as two distinct, user-selectable move operations, in any direction between any two supported locations on a given platform — **except** Android `Link` for a plain (non-git) graph, which is explicitly out of scope (see Out of Scope below, per ADR-003).
- Safety net for moves: copy-then-verify-then-optionally-delete-source (never destructive by default), explicit confirmation dialog naming exact source/destination, and a way to keep the source intact after a move until the user is satisfied (undo window or manual "delete old copy" step — exact mechanism decided in Phase 3 planning).
- A shared/consistent directory-and-location picker component per platform used by all three surfaces above, so "richer path picker" work isn't duplicated three times.
- **"App-owned storage" must be a selectable entry inside the picker itself** (e.g. a pinned first row/shortcut alongside "Recent," "Browse…," or platform-native quick-access entries), not a separate radio button, toggle, or dialog the user has to notice *before* reaching the picker. On Android this means the SAF-folder browse UI and the app-storage option are reachable from the same picker surface; on Web, OPFS and "pick a real folder via File System Access API" are both choices within one picker, not a fallback that only appears when the API is missing.

### Out of Scope

- Desktop (JVM) and iOS — no app-owned-storage concept; not touched.
- Changing git credential storage/handling itself (SSH keys, OAuth device flow) — only how it's re-associated if a repo's location moves.
- Building a new sync engine from scratch — relocate/link should extend `GitShadowWorktree`/`GitShadowFlushActor`/`GitWriteBackQueue` (Android) and `FolderSyncSettings`/`HostDirectorySync` (Web), not replace them.
- Cross-device sync/cloud backup of app-owned storage — that's a separate concern (git remote already serves this purpose for cloned repos).
- **Android `Link` for a plain (non-git) graph.** Per ADR-003 (`decisions/ADR-003-plain-graph-app-owned-backup-mitigation.md`): no continuous-mirror primitive exists today for plain Android graphs — only the git-shadow-worktree's write-back mechanism does continuous mirroring, and it is git-specific. Building a plain-markdown continuous mirror from scratch is a new sync engine, which is out of scope per the "no new sync engine" exclusion above. Android `Link` is therefore scoped to **git-cloned graphs only**, reusing the existing shadow-worktree write-back mechanism (promoted from an invisible cache detail to a user-visible, explicitly-invoked continuous mirror). This is the one exception to this document's "any direction between any two supported locations" In-Scope language — it applies to Web (both graph kinds) and to Android's other three move combinations (Relocate for both graph kinds, in any direction), but not to Android Link for a plain graph. A future project could add a real Android file-sync mechanism to support it; re-scope as its own project rather than bolting it onto this one.

## Rabbit Holes

- **"Any location → any other location" is a combinatorial surface.** Android alone has: SAF folder (write-back on/off) × app storage × (eventually) direct-access via `MANAGE_EXTERNAL_STORAGE`. Web has: OPFS × real folder (linked/unlinked). Phase 3 planning must enumerate the actual state matrix per platform rather than assuming a single generic "move" function covers all of it — some transitions (e.g., Android SAF-without-all-files-access → app storage) already have working machinery to lean on (`GitShadowWorktree`); others (e.g., generalizing web's livesync mirror to be reconfigurable post-hoc rather than fixed at connect time) may require real new plumbing in `FolderSyncSettings`/`HostDirectorySync`.
- **Relocating a git-cloned graph's `.git` directory is not the same operation as relocating a plain markdown graph.** Object store integrity, remote tracking config, and in-flight `GitShadowFlushActor` writes must be quiesced/drained before/during a move — treat this as its own research question, not an afterthought of the plain-graph move path.
- **The SQLite DB is currently *always* in `filesDir` regardless of markdown location** (`DriverFactory.android.kt:202-209`). "Move to app storage" therefore already has the DB there; "move to SAF/external" only ever needs to move markdown files, never the DB. Get this asymmetry right in the design — the picker/move UI must not imply the DB moves when it doesn't.
- **Verification-before-delete needs a real integrity check**, not just a file count — partial writes, symlinks-via-SAF-URIs, and encoding differences (per the existing "phantom conflict auto-resolve for underscore/encoded page titles" bug fixed in `e087959cb1`) are exactly the kind of edge case that already caused a real bug in this codebase's path-handling.
- **Web's File System Access API permission model** (a picked directory handle can lose permission across browser sessions) interacts with "link" mode — a link that silently breaks because Chrome revoked directory access needs to be surfaced, not silently fail.

## Alternatives Considered

- **Do nothing / leave app storage as invisible plumbing** — rejected: user explicitly wants this as a real, discoverable option, and the underlying mechanisms (shadow worktree, OPFS) already exist and work, so the marginal cost is UI + move logic, not a new storage engine.
- **Ship creation-time picker only, defer all move/link work** — considered as a smaller Medium-appetite alternative; rejected by the user in favor of doing the full unification now (Large appetite), but Phase 3 planning should still sequence so the creation-time picker is usable on its own if the move/link work runs long.
- **Always-linked (no pure relocate)** — rejected: user wants both relocate and link as distinct, separately-selectable operations, not one merged behavior.

## Feasibility Risks

- `GitShadowWorktree`'s current design assumes it's an *acceleration cache* behind a SAF-of-record; promoting it to a *primary, standalone* storage mode may surface assumptions elsewhere in the git-sync pipeline (conflict detection, `GitShadowFlushActor` scheduling, orphan sweep at `GitShadowWorktree.kt:406`) that need auditing before this is safe.
- Web's `FolderSyncSettings`/`HostDirectorySync` livesync mirror was built for a fixed "connect once" flow (per `web-local-folder-livesync`'s design docs); making it a reconfigurable, user-invocable "link" action from an arbitrary starting state may require structural changes there, not just new UI.
- No existing regression test exercises a live storage-location *migration* for a graph with real content (git history, blocks, pages) — new test infrastructure will likely be needed before this can be trusted, given the repo's stated "no completion claim without proof" discipline.
- Android's `MANAGE_EXTERNAL_STORAGE` direct-access fast path (`isDirectAccess()`/`resolveToRealPath()` in `PlatformFileSystem.kt`) adds a third storage mode beyond plain-SAF and app-storage that the move matrix must account for, or explicitly scope out with a stated reason.

## Observability Requirements

Standard request/action logging is sufficient given this is a single-developer app with no analytics pipeline for this flow. At minimum:
- Log (locally, to the existing app log / telemetry span mechanism already used elsewhere in the codebase) the start, verification result, and completion/failure of every relocate or link operation, including source/destination location kind and graph id — this is the debugging trail if a user reports a move went wrong.
- No new alerting infra — not applicable to a personal-scale app.

## Risk Control

- **Never delete the source until the destination is verified.** Every relocate copies to the destination first, runs an integrity check (file count at minimum; consider content hash for the git object store given the rabbit hole above), repoints the graph's registered storage path only after verification passes, and leaves the source location physically intact afterward.
- **Explicit confirmation naming exact source/destination, plus an undo window.** Before starting, show the user precisely what will move where. After completion, keep the source available (not auto-deleted) until the user explicitly confirms cleanup, or for a defined grace period — exact UX decided in Phase 3 (`3-plan`'s UX design pass).
- No feature flag / staged rollout mechanism exists in this codebase for end-user features (single-developer, no experiment framework) — rely on the copy-verify-confirm-before-delete design itself as the safety net, plus the new test coverage called for above.

## Open Questions

- Exact integrity-check strength for verification-before-delete (file count vs. content hash vs. git object-count/fsck) — resolve in Phase 2 research or Phase 3 planning.
- Exact UX for the "undo window" (time-boxed auto-cleanup vs. manual-only cleanup action vs. keep-forever-until-storage-pressure) — resolve in Phase 3's UX design pass.
- Whether `MANAGE_EXTERNAL_STORAGE` direct-access mode needs its own entry in the location picker (as a third Android location kind) or can be treated as an invisible acceleration detail under "SAF/external folder" — resolve in Phase 2 research.
- Whether relocating a git-cloned graph needs to pause/drain `GitShadowFlushActor` mid-flight, and how to detect "safe to move now" — resolve in Phase 2 research into `GitShadowWorktree`/`GitShadowFlushActor` internals.
- Whether web's `FolderSyncSettings` link mechanism can be generalized in place or needs a parallel new implementation for the "link" move type — resolve in Phase 2 research into `HostDirectorySync`.
