# ADR-019: Graph-Scoped Session Lifecycle for Per-Graph Mutable State

**Status**: Accepted
**Date**: 2026-09-01
**Project**: `web-host-sync-session-lifecycle`

## Context

`HostDirectorySync` and `PlatformFileSystem` (wasmJs) hold per-graph mutable state
(`hostDirHandle`, `hostGraphOpfsPath`, write-through queue maps, `dirtySet`/`baseSha`/
`pendingCommit`, the marker-write scheduler) as instance fields on singletons that outlive any
individual graph. `GraphManager.switchGraph()` has to re-point this state by hand on every graph
switch, and PR #293 (merged, released as v0.78.1) fixed a production bug caused by exactly this
shape:

1. The wiring to tell `HostDirectorySync`/`PlatformFileSystem` a switch happened was initially
   missing entirely — host-directory sync silently broke for any non-boot-time graph.
2. Once added, adversarial review found two more bugs in the manual clearing logic itself: a
   write in flight during a switch could tag its bookkeeping under the wrong graph's path, and the
   marker-write scheduler could persist one graph's dirty content under another graph's marker file
   if a switch raced it.

`GraphManager` itself already solves the equivalent problem correctly: `switchGraph()` creates a
fresh `CoroutineScope` + `RepositorySet` per graph and cancels/closes the previous one, so a
graph's in-flight database coroutines cannot outlive the switch. This ADR decides how to bring
`HostDirectorySync`, `PlatformFileSystem`, `GraphWriter`, and (by consideration, not migration)
`GitShadowWorktree` into line with that same structural guarantee.

## Decision

Introduce two small, hand-rolled `commonMain` primitives — `SessionLifecycle` (an interface: `val
scope: CoroutineScope`, `fun close()`) and `GraphScopedSession<Id, T : SessionLifecycle>` (a
generic holder mirroring `GraphManager.switchGraph()`'s proven sequencing: `Mutex`-guarded
idempotency guard, isolated old-close-failure handling, fresh-`SupervisorJob`-per-switch,
guaranteed-completion signal) — and apply them per class as follows:

- **`HostDirectorySync`**: the *instance itself* becomes the per-graph unit. A fresh instance is
  constructed per graph, owning its own `CoroutineScope`, implementing `SessionLifecycle`. No
  separate wrapper data class — there is no cross-graph state left in the class once the whole
  instance is per-graph. It depends on `HostDirectorySync.CacheAccess` (an interface); the
  composing `PlatformFileSystem` supplies an implementation via a private field it constructs once
  and reuses across every reconstruction, rather than implementing the interface on its own public
  type (see Alternatives Considered).
- **`PlatformFileSystem`**: stays a singleton (non-graph-aware callers hold one long-lived
  reference across the app's life). Its per-graph state — the composed `HostDirectorySync`
  instance plus a nested `GitWriteState` value object (`dirtySet`/`baseSha`/`pendingCommit`/
  marker-scheduler *state*, i.e. the `markerWriteInFlight`/`pendingMarkerWrite` flags) — moves into
  a new `GraphSyncSession` bundle, implementing `SessionLifecycle`, held by one
  `GraphScopedSession<OpfsGraphSlug, GraphSyncSession>` field and swapped wholesale on
  `switchActiveGraph()`. `GraphSyncSession` itself holds only 4 fields — `graphId`, `scope`,
  `hostDirectorySync`, `gitWriteState` — keeping its two distinct concerns (host-sync composition
  vs. git dirty-tracking bookkeeping) separated into one nested object each with a single reason to
  change, rather than 6+ fields of two concerns flattened onto one class. Both `HostDirectorySync`
  and `GraphSyncSession` are keyed by a new `OpfsGraphSlug` value type — the OPFS folder-name path
  segment, **not** the canonical hash-based `GraphId` used by `GraphManager`/`GraphInfo` — because
  these wasmJs classes only ever have the folder-name segment in scope, never the canonical
  (un-hashed) path needed to re-derive `GraphId`'s sha256 hash; see Alternatives Considered.
  Crucially, `scheduleMarkerWrite()`'s launched coroutine keeps running on `PlatformFileSystem`'s
  own pre-existing, immortal `scope` field — never `GraphSyncSession.scope` — because Story 2.1.3
  requires a marker write scheduled under one graph to still complete after a switch to another
  graph, using values captured at schedule time; if that coroutine ran on `GraphSyncSession.scope`,
  `close()`'s `scope.cancel()` on switch would abort it mid-flight. `GraphSyncSession.scope`
  therefore hosts no coroutine of substance — it exists to satisfy `SessionLifecycle`'s interface
  and as a defensive cancellation point for any future per-graph coroutine added directly to the
  bundle, not because any current call site launches work on it. `FolderSyncLockNaming` stays
  standalone, keyed on `OpfsGraphSlug`; `GraphRootedPath` was investigated and found to have no
  graph-identifier parameter at all (it validates OPFS-path shape, not identity), so it is
  explicitly out of scope for this migration rather than silently skipped. `HostDirectorySync`'s
  own `hostAccessStateFlow`/`hostWritePendingCountFlow` and its `onHostConflict`/
  `onHostBytesConflict`/`onHostWriteFailed` callbacks are single-source-of-truth: each is written
  from exactly one place inside `HostDirectorySync` (`setHostAccessState()`, etc.), which both
  updates the instance's own read-only-projected `StateFlow` and invokes the injected callback into
  `PlatformFileSystem` in the same call — never two independently-mutable copies that could
  diverge.
- **`GraphWriter`**: no scope-owning session — its bug surface is one field (`graphPath`), and its
  autosave `CoroutineScope` lifetime is already independently gated by `startAutoSave`/
  `stopAutoSave`, not graph identity. `graphPath` becomes `GraphEpoch(graphId, graphPath,
  sequence)`, held in a **nullable** `currentEpoch: GraphEpoch?` (`null` = uninitialized, not an
  empty-string/empty-`GraphId` sentinel), swapped atomically; `renamePage`/`deletePage` capture it
  once at entry instead of re-reading the live field after a suspension point (the same
  entry-snapshot discipline already used by `HostDirectorySync.flushHostWrite` for
  `handle`/`opfsPath`).
- **`GitShadowWorktree`**: **not migrated.** It owns no `CoroutineScope` and launches no
  coroutines — every operation runs inline under a lock and returns before another graph could
  possibly interleave. Its per-graph isolation already comes from a different, equally valid
  mechanism: `AndroidGitRepository.shadowWorktreeFor`'s content-hash-keyed lookup cache, resolved
  fresh per call. This ADR documents that mechanism as the second valid shape of "session
  lifecycle" this codebase now has, rather than forcing a scope onto a class that structurally
  doesn't need one.

Every new `GraphId(...)` construction site this migration introduces (limited to Phase 3,
`GraphWriter`/`App.kt`) must be sourced from `GraphManager.getActiveGraphInfo()?.id` — `GraphId`
has no validating smart constructor, so this is a stated review discipline, checked explicitly in
each epic's PR, not a compiler-enforced invariant.

## Alternatives Considered

### Reusing the canonical `GraphId` newtype for `HostDirectorySync`/`PlatformFileSystem`'s internal id

An earlier draft of this plan wrapped these wasmJs classes' `graphId: String` field directly in
`GraphManager`/`GraphInfo`'s existing `GraphId` value class, describing it as "reuse." **Rejected**
on review (architecture-review.md Blocker 1): `GraphInfo.id` is
`GraphId(ContentHasher.sha256(canonicalPath).take(16))` (`GraphManager.kt:316`), a one-way hash of
the graph's canonical path, while `HostDirectorySync`/`PlatformFileSystem` only ever have the OPFS
folder-name path segment in scope (`graphPath.removePrefix("$homeDir/").substringBefore("/")`,
`PlatformFileSystem.kt:174`/`:209`) — never the canonical path needed to re-derive the hash. Wrapping
both in the same newtype would let a `GraphId` produced by one part of the codebase silently fail to
equal the `GraphId` for the same graph produced elsewhere, defeating "type as proof." This project
instead introduces a distinct `OpfsGraphSlug` value type for the OPFS-folder-derived identifier
(Domain Glossary, plan.md), used exclusively by `HostDirectorySync`, `GraphSyncSession`, and
`FolderSyncLockNaming`. Actually unifying the two — e.g. by re-keying OPFS folders on the sha256
hash instead of the picked directory name — was considered and rejected as out of scope: it is a
user-visible behavior change (OPFS folder names would stop being human-readable) with no bug this
project needs it to fix, since the folder-name string already satisfies every guarantee
`HostDirectorySync`/`PlatformFileSystem` actually need (cross-tab-stable addressing, stable
`WebLock` naming).

### Actor pattern (message-driven, à la `DatabaseWriteActor`)

Model the poller/write-through/reconciliation responsibilities as a channel-consuming actor.
Rejected: `DatabaseWriteActor`'s structural-cancellation property is actually delivered by the
*outer* pattern — a fresh actor is created per graph because a fresh `RepositorySet` is, not
because the actor idiom itself scopes state to a graph. Its real value is serializing concurrent
writers against one shared resource (SQLite), a different problem than state ownership across a
switch. Retrofitting a channel-actor onto `HostDirectorySync` would add message-passing overhead
(request types for "poll tick," "write-through enqueue," etc.) without addressing the actual
problem — you'd still need to create a new actor per graph and cancel the old one, at which point
it's a session object with a channel bolted on.

### State/Memento-object wrapper over otherwise-unchanged singletons

Bundle the fields into a `Session` data class but keep mutating that one instance's fields in
place rather than replacing the whole object on switch. Rejected: this is cosmetic restructuring,
not a fix. If the underlying fields are still mutated in place instead of the object being
replaced wholesale, the exact bug class this project exists to close (a live field read after a
suspension boundary sees the *new* graph's value) reappears one layer deeper, just harder to spot.

### DI framework (Koin / Kotlin-Inject scoped components)

The user explicitly raised this option during scoping: "Perhaps we need to implement a DI
framework? if that would make this sort of central management and delegation more easy." **Rejected.**
DI frameworks solve object-graph *construction/wiring* — deciding what gets instantiated with
what dependencies, and in what scope of the *object graph*. They do not solve resource *lifetime
scoping* — deciding when a `CoroutineScope` and the coroutines running on it get cancelled — which
is what this project is actually about. Concretely:

- This codebase has no DI framework today (`kmp/build.gradle.kts`/`MODULE.bazel` grepped clean for
  `koin`/`kotlin-inject`/`dagger`/`hilt`; confirmed in `build-vs-buy.md`). Introducing the first one
  for this single project would be a much bigger, separate architectural bet — spanning
  `commonMain`/wasmJs/Android — than the problem warrants.
- Even a DI framework's own "scope" concept (Koin scopes, Kotlin-Inject component-scoped graphs)
  models the *object graph's* lifetime, not `CoroutineScope` cancellation semantics — adopting one
  would still leave the coroutine-cancellation half of this problem to be hand-written on top,
  meaning the actual bug class (live-field reads after suspension, forgotten per-graph state) would
  remain unaddressed by the DI framework itself.
- The `CoroutineScope`-per-session pattern this ADR adopts is already validated in this exact
  codebase (`GraphManager.switchGraph()`, in production, survived its own idempotency-guard bug fix)
  — a stronger, more directly applicable precedent than any generic external framework.

### Structured-concurrency scope-per-resource with no named session type at all

Per-graph state as private fields closed over a bare `CoroutineScope`, with no separate `Session`
data class — this is, in fact, what was chosen for `HostDirectorySync` (the instance itself is the
"session"). It was considered and rejected as the *universal* answer for all four classes,
specifically because `PlatformFileSystem` cannot itself become per-graph (see Decision above) —
for that class, a named `GraphSyncSession` value object is necessary because the class instance
holding it must stay a singleton.

### `PlatformFileSystem : HostDirectorySync.CacheAccess` (public interface implementation)

`HostDirectorySync` depends on a `CacheAccess` abstraction it doesn't own; `PlatformFileSystem`
today supplies one as an anonymous object built inline at construction time. Making
`PlatformFileSystem` implement `CacheAccess` directly on its own public type was considered as the
alternative to a private field (architecture-review.md Concern, "Task 1.1.2b — `cacheAccess =
this`"). **Rejected**: it widens `PlatformFileSystem`'s public surface to expose an interface that
exists solely for `HostDirectorySync`'s benefit — no other caller of `PlatformFileSystem` has any
use for `CacheAccess`. A `private val cacheAccess: HostDirectorySync.CacheAccess` field, constructed
once and reused across every `HostDirectorySync` reconstruction, keeps the dependency direction
correct (`HostDirectorySync` depends on the abstraction) without leaking that abstraction onto a
class that doesn't need to expose it (interface segregation).

### `GraphScopedSession<Id, T>` documented as single-caller-thread-only instead of `Mutex`-guarded

The alternative to adding a real `Mutex` around `switchTo`'s critical section (adversarial-
review.md Concern, "`GraphScopedSession<T>` has no stated thread-safety guarantee") was to leave it
unsynchronized and simply document the constraint, matching `GraphManager.switchGraph()`'s actual
(undocumented) contract. **Rejected**: requirements.md's Success Metrics explicitly scope this
primitive as reusable "for any other per-graph state in this codebase" — a stated goal, not a
hypothetical — and JVM/Android's `Dispatchers.Default` is genuinely multi-threaded, unlike wasmJs's
single-JS-thread model where today's only consumer happens to be safe by accident. **Reconciling
note (pre-mortem.md P1-5)**: "wasmJs is a single JS thread" and requirements.md's Feasibility Risks
citation of a real, unpredictable Kotlin/Wasm `Dispatchers.Default` test failure during PR #293 are
two different claims, not a contradiction — wasmJs genuinely has no true parallelism (no multi-core
data race in the classic sense), but its `Dispatchers.Default` scheduling granularity is
macrotask/`setTimeout`-based, not microtask-based, so code cannot assume a synchronous-looking,
predictable resumption order across suspension points even without true parallelism. The PR #293
failure broke because a test assumed *ordering*, not because of a true race — which is exactly why
this project's tests are gate-based (`CompletableDeferred`) rather than timing-based. plan.md's
Task 0.1.3f records an empirical check of this on the real wasmJs runtime rather than leaving the
question resolved by reasoning alone. Documenting a
constraint the primitive doesn't enforce is exactly the kind of implicit invariant this project
exists to replace with a structural one; a small `Mutex` around the bookkeeping (not the factory's
own construction work, which stays unlocked so a slow session build never blocks unrelated
switches) costs little and removes the landmine for the next contributor who reuses this type
outside wasmJs.

## Consequences

- Adding new per-graph state to any of the three migrated classes requires no addition to a manual
  clear/reset list — it becomes a field on the relevant session type, and a graph switch replaces
  the whole object.
- **Structural cancellation is narrower than "aborts in-flight I/O."** Kotlin coroutine
  cancellation is cooperative: cancelling a session's scope stops new work and prevents a resumed
  coroutine from touching post-switch session state at its next suspension checkpoint — it does
  **not** reach into an already-dispatched JS `Promise`/OPFS write and abort it. This project's
  regression tests are phrased to match this reality: "no NEW work touches old-graph state after
  cancellation," never "in-flight I/O is aborted." Any future reader of this ADR should not
  overclaim the guarantee beyond that.
- Lock-name derivation (`FolderSyncLockNaming`) stays keyed on the caller-visible `OpfsGraphSlug`
  string (not the canonical `GraphId` — see Alternatives Considered above), never on any
  per-session/per-instance identity, preserving cross-tab `WebLock` correctness. `GraphRootedPath`
  is explicitly untouched by this migration — it has no graph-identifier parameter at all.
- `GraphScopedSession<Id, T>`'s `switchTo` is `Mutex`-guarded across its check-current/register-
  pending/swap critical section, so any future consumer of this primitive outside wasmJs (the
  stated reuse goal) inherits a real concurrency guarantee, not an unenforced single-thread
  assumption.
- `GraphSyncSession`'s dirty-tracking/marker-scheduler fields live in a nested `GitWriteState`
  value object, not flattened directly onto `GraphSyncSession` — each of the two composed concerns
  (host-sync vs. git write bookkeeping) has one reason to change.
- `HostDirectorySync`'s `hostAccessStateFlow`/`hostWritePendingCountFlow` and its
  `onHostConflict`/`onHostBytesConflict`/`onHostWriteFailed` callbacks are each written from
  exactly one call site inside the class, which updates the instance's own (read-only-projected)
  flow and invokes the injected `PlatformFileSystem`-bound callback together, in the same
  statement — never two independently-mutable copies of the same fact.
- `GraphId` and `OpfsGraphSlug` are two distinct, non-interchangeable identifier types after this
  project ships. `GraphWriter`/`App.kt`'s two switch call sites (Phase 3) use the canonical
  `GraphId`, sourced from `GraphManager.getActiveGraphInfo()?.id`; `HostDirectorySync`/
  `GraphSyncSession`/`FolderSyncLockNaming` (Phases 1-2) use `OpfsGraphSlug`, sourced from the OPFS
  folder-name path segment. A future contributor adding a new `GraphId(...)`/`OpfsGraphSlug(...)`
  construction site must source it from the already-existing canonical identifier appropriate to
  that layer, never re-derive one from the other or from a raw path/string in the wrong layer.
- `onHostConflict`/`onHostBytesConflict`/`onHostWriteFailed` are re-supplied to every freshly
  constructed `HostDirectorySync` from values `PlatformFileSystem` stores itself (Story 2.2.2),
  exactly like `hostAccessStateFlow`/`hostWritePendingCountFlow` (Story 2.2.1) — neither depends on
  `App.kt`'s independently-triggered callback-wiring `remember` block re-running in any particular
  order relative to a graph switch.
- `GitShadowWorktree` needs no follow-up work from this project — its existing pattern is accepted
  as-is, documented here for future reference rather than replaced.
- **Task 0.1.3f empirical check — not executed, environment-blocked (recorded 2026-09-02).**
  `WasmDispatchersDefaultInterleavingDiagnosticTest.kt` was written (two real
  `Dispatchers.Default` coroutines each incrementing an unguarded shared `var counter` 1000
  times, real-runtime, not `TestScope`) but **could not actually be run** in the environment this
  Phase 0 implementation happened in — no headless Chrome/Chromium was available (`google-chrome`,
  `google-chrome-stable`, `chromium`, `chromium-browser` all absent from `PATH`), and per Epic
  0.0's own finding immediately above, `./gradlew :kmp:wasmJsBrowserTest` is not yet wired into
  CI either. So the open question this test exists to close — whether real wasmJs
  `Dispatchers.Default` ever exhibits a true lost-update interleaving on an unguarded shared
  variable — remains **unresolved by empirical evidence**; only the reconciling *reasoning* in
  this ADR's Alternatives Considered section (macrotask-granularity scheduling nondeterminism vs.
  true multi-core data races) stands today. A future contributor with real browser access should
  run this test (`./gradlew :kmp:wasmJsBrowserTest --tests
  "dev.stapler.stelekit.coroutines.WasmDispatchersDefaultInterleavingDiagnosticTest"`) and update
  this bullet with the actual observed result, then delete the test per its own KDoc if the
  question is considered closed.
