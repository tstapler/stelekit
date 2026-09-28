# Implementation Plan: web-local-folder-livesync

**Feature**: Retain a `FileSystemDirectoryHandle` for a user-picked local folder on the web/WASM
build so browser edits write through to the host folder, external edits to the host folder are
detected and surfaced through the existing `DiskConflict` machinery, and re-enabling sync on an
already-populated graph reconciles rather than overwrites.
**Date**: 2026-07-17
**Status**: Ready for implementation
**ADRs**:
- `project_plans/web-local-folder-livesync/decisions/ADR-001-indexeddb-handle-persistence.md` (hand-rolled IndexedDB interop, reject `JuulLabs/indexeddb`)
- `project_plans/web-local-folder-livesync/decisions/ADR-002-filesystemobserver-as-primary-detection-fast-path.md` (`FileSystemObserver` as accelerator, poll as permanent baseline)

---

## Step 0.5 — Creative Pass: alternatives for write-through + external-change detection

Three distinct high-level approaches were compared for the core mechanism before committing:

**(A) Retained handle + `FileSystemObserver` fast path + async host poller feeding the existing
synchronous `FileRegistry`/`GraphFileWatcher` contract.** *Strength*: reuses `GraphFileWatcher`'s
already-tested dirty-flag ordering, active-page suppression, and `DiskConflictDialog` pipeline
unchanged — zero new conflict-detection state machine. *Weakness*: requires two loosely-coupled
polling/observing loops (the new async host poller and the existing 5s `FileRegistry` poll) kept in
sync only through `PlatformFileSystem`'s read surface, an indirection with no other precedent in
this codebase.

**(B) Bespoke wasmJs-only watcher, built directly against async File System Access API calls,
bypassing `FileRegistry`/`GraphFileWatcher` entirely.** *Strength*: simpler mental model — one loop,
no sync/async bridging hack, no need to satisfy an interface designed around synchronous JVM file
I/O. *Weakness*: reimplements conflict-detection semantics (own-write suppression, active-page
guard, sticky git-merge suppression) bespoke for one platform, risking silent divergence from the
desktop/Android behavior users already rely on — flagged as the highest-risk option in
`research/build-vs-buy.md` §3a.

**(C) No retained handle; re-run a full directory reconciliation pass on a timer instead of
incremental polling.** *Strength*: one reconciliation algorithm serves both first-connect and every
steady-state check — no separate incremental-mtime-tracking code path. *Weakness*: a full-tree
hash walk every tick is an O(graph) scan per check, a direct violation of this codebase's standing
"must not become O(graph) scan" rule (`CLAUDE.md`) and the requirements' NFR — disqualified at the
8,000+-page scale this codebase is designed around.

**Chosen: (A)**, on the strength that it is the only option satisfying the O(graph)-per-tick NFR
*and* reusing tested conflict-detection semantics rather than risking divergence. (B) and (C) are
recorded as rejected alternatives in the Pattern Decisions table below. `FileSystemObserver`'s role
within (A) is further resolved by ADR-002 (accelerator, not sole mechanism).

---

## Domain Glossary
*(Ubiquitous language — every domain term that appears as a type, method, or variable name. Exact
names here must be used consistently in code, tests, and comments.)*

| Term | Definition | Notes |
|------|-----------|-------|
| `HostDirectorySync` | **New collaborator class** that owns all Phase 2–7 host-directory-sync state and behavior (handle lifecycle, reconciliation, write-through queue, poller, observer glue, locks, rename). `PlatformFileSystem` composes one instance and delegates only the seven `FileSystem`-interface touch points to it; no Phase 2–7 field or method is added to `PlatformFileSystem` itself. | `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (new). See Epic 1.6 and Pattern Decisions ("Overall class structure"). |
| `HostDirectorySync.CacheAccess` | Small constructor-injected interface (`get(path)`/`set(path, content)`/`remove(path)` over the OPFS-backed `cache`, `getBytes(path)`/`setBytes(path, data)`/`removeBytes(path)` over `bytesCache` for `.md.stek` paranoid-mode paths, plus a way to trigger an OPFS mirror write) that `HostDirectorySync` uses to read/write `PlatformFileSystem`'s cache without owning it. `PlatformFileSystem` implements it privately. | `platform/HostDirectorySync.kt`. Keeps `cache`/`bytesCache` themselves owned by `PlatformFileSystem` (they also back non-host-synced reads/writes) while giving `HostDirectorySync` the narrow access it needs — constructor injection per architecture-review.md Blocker 1 remediation. The bytes methods were added per adversarial-review.md Blocker 4 remediation, so reconciliation/poller can read/write encrypted `.md.stek` content without routing it through the string-typed `get`/`set`. |
| `navigator.storage.persist()` | Best-effort browser call requesting the origin's storage (including the OPFS mirror) be exempted from LRU eviction under storage pressure. | Interop wrapper `jsStoragePersist()`/`requestStoragePersistence()` in `HostDirectoryInterop.kt` (Epic 1.5), called (fire-and-forget, result logged not required) from `HostDirectorySync.connectHostDirectory`/`reconnectHostDirectory`'s success paths per adversarial-review.md Blocker 1 remediation. |
| `HostDirectoryHandle` | The `JsAny`-typed retained reference to the browser's `FileSystemDirectoryHandle` for the user-picked local folder backing a graph. | Stored in `HostDirectorySync.hostDirHandle: JsAny?`. Never null once a graph has live sync active. |
| `hostGraphOpfsPath` | The OPFS-absolute path (e.g. `/stelekit/<graphId>`) that a given `HostDirectoryHandle` backs. | Instance field on `HostDirectorySync`. |
| `HostAccessState` | Sealed interface: result of checking whether the app can currently read/write the retained host directory. Variants: `NotApplicable`, `Granted`, `PromptNeeded`, `Denied`, `Disconnected`. | New commonMain type, `platform/HostAccessState.kt`. |
| `hostDirectoryAccessState(graphPath)` | New optional `suspend` method on the `FileSystem` interface, default `NotApplicable`, returning `HostAccessState`. | Follows the seven-method SAF-write-behind precedent in `FileSystem.kt:73-145`. On wasmJs, `PlatformFileSystem`'s override is a one-line delegate: `= hostDirectorySync.hostAccessStateFlow.value`. |
| `reconnectHostDirectory(graphId)` | Suspend entry point run at startup: looks up a persisted `HostDirectoryHandle` in IndexedDB and resolves its `HostAccessState` without prompting. | `HostDirectorySync`, called from `browser/Main.kt` via `opfsFileSystem.hostDirectorySync.reconnectHostDirectory(...)` — not a `PlatformFileSystem` method, since it isn't part of the `FileSystem` interface contract. |
| `requestHostDirectoryAccess(graphId)` | Suspend entry point invoked from a real user click (transient activation required) that calls `requestPermission()` on the rehydrated handle. | `HostDirectorySync`, called directly by UI click handlers via `opfsFileSystem.hostDirectorySync.requestHostDirectoryAccess(...)`. |
| `connectHostDirectory(existingOpfsPath)` | New entry point, distinct from `pickDirectoryAsync()`, for attaching live sync to an **already-populated** graph. Triggers `ShowDirectoryPicker` + `RunHostReconciliation` instead of the unconditional `importUserDirToCache`. | `HostDirectorySync`. `PlatformFileSystem.pickDirectoryAsync()` remains the fresh-graph entry point and, on success, constructs/starts `HostDirectorySync` for the new handle. |
| `ReconciliationOutcome` | Sealed interface: per-path classification when reconciling host directory content against `cache`/OPFS. Variants: `Identical`, `HostChangedConflict`, `HostOnlyNew`, `BrowserOnlyNeedsPush`. | `platform/HostReconciliation.kt` (commonMain, pure). |
| `classifyReconciliation(hostContent, cacheContent)` | Pure function mapping a `(String?, String?)` content pair to a `ReconciliationOutcome`. | commonMain, unit-testable without wasmJs. |
| `classifyReconciliationBytes(hostBytes, cacheBytes)` | Bytes-aware sibling of `classifyReconciliation`, mapping a `(ByteArray?, ByteArray?)` pair (via `contentEquals`, not string equality) to the same `ReconciliationOutcome` variants. Used for `.md.stek` paranoid-mode paths, which must never be decoded as UTF-8 text. | `platform/HostReconciliation.kt` (commonMain, pure) — added per adversarial-review.md Blocker 4 remediation (classifier was previously `String?`-only, silently mis-classifying or corrupting encrypted content). |
| `runHostReconciliation(dirHandle, opfsPath)` | Walks the host directory tree once, classifies every path via `classifyReconciliation`, and applies the corresponding action. | `HostDirectorySync` (wasmJs), reading/writing `cache` only through `CacheAccess`. |
| `HostWriteEntry` | A `(path, DirtyEntry)` record in the write-through queue. Reuses the existing `DirtyEntry`/`DirtyOp` types from `git/model/DirtySetMarker.kt`, but never the same map instance as `dirtySet`. | See Pattern Decisions — structurally independent from git write-back, and structurally independent from `PlatformFileSystem`'s own fields (lives on `HostDirectorySync`). |
| `hostWritePending` | `MutableMap<String, DirtyEntry>` instance field on `HostDirectorySync` — the write-through queue. Deliberately **not** independently persisted to IndexedDB/OPFS. | Deliberately never aliases `dirtySet` (which stays on `PlatformFileSystem`). Its durability story is that it is fully reconstructable: `cache`'s content (browser edit) vs. the host directory's content is exactly what `runHostReconciliation`'s `BrowserOnlyNeedsPush`/`HostChangedConflict` classification already computes, and — per adversarial-review.md Blocker 3's remediation — reconciliation now runs on **every** `reconnectHostDirectory`/`connectHostDirectory`, not just the latter. A tab crash that loses the in-memory `hostWritePending` map therefore self-heals on next reconnect via reconciliation re-deriving the same set of pending pushes from `cache`-vs-host diff, per adversarial-review.md Blocker 2's second offered remedy ("fold pending-write recovery into the reconciliation pass") — chosen over a separate IndexedDB-backed queue store to avoid a second persisted-state schema for state that reconciliation already recomputes idempotently. See Epic 3.3's added recovery test. **Now unconditionally true, not just bounded (scope expansion, Epic 1.7)**: the previous caveat — that this claim held only for edits whose OPFS write already landed before a crash — is closed by Epic 1.7's awaited-write-before-enqueue fix. `scheduleHostWriteThrough` now awaits that path's in-flight OPFS-write `Deferred` (Task 1.7.1a/1.7.1b) before ever adding it to `hostWritePending`, so an edit that has not yet durably reached OPFS can no longer reach the host write-through queue either — see Epic 1.7 and the "OPFS-write durability" Pattern Decisions row. |
| `scheduleHostWriteThrough(path, content)` | Coalescing scheduler that enqueues a path for host write-through, mirroring the existing `scheduleMarkerWrite` "at most one flush in flight, trailing writes coalesce" idiom (`PlatformFileSystem.kt:147-161`). | New method on `HostDirectorySync`, called from `PlatformFileSystem.writeFile`/`writeFileBytes`/`deleteFile` (the delegation touch points). |
| `flushHostWrite(path)` | Performs the actual host-directory write: proactive `queryHandlePermission()` check → freshness (hash) check → `createWritable()` → `write()` → `close()` → dequeue. If the proactive permission check does not return `"granted"`, the write is never attempted — short-circuits through the same `PromptNeeded`/`Denied` handling Task 4.4.1a's reactive failure path uses. | New method on `HostDirectorySync`. See Story 4.2.3 (adversarial-review.md Concern remediation; research/pitfalls.md §1.1's "queryPermission() before each write-through batch, not just once at startup" requirement). |
| `HostDirectoryPoller` | The internal async `scope.launch` loop inside `HostDirectorySync` that periodically walks `hostDirHandle` and refreshes `hostModTimes`/`cache` (the latter via `CacheAccess`). Sleeps for `effectivePollIntervalMs()` (not the fixed `hostPollIntervalMs`) between ticks — see that term below. Required to carry a large-graph (8,000+ file) tick-cost benchmark before its default interval ships (Epic 5.5, adversarial-review.md Blocker 6), including the visibility-paused and observer-widened variants (Epic 5.5, Story 5.5.2, pre-mortem.md P1 remediation). | New. |
| `hostPollIntervalMs` | The **base** poll interval constant (default `10_000L`), used as-is only when the tab is visible *and* `FileSystemObserver` is not confirmed active. Never read directly by the timer loop — `effectivePollIntervalMs()` is. | `HostDirectorySync`. Renamed in role (base, not effective) by Epic 5.1's revision — see Pattern Decisions ("Poll cadence policy"). |
| `effectivePollIntervalMs()` | Computed function on `HostDirectorySync` returning the actual delay the `HostDirectoryPoller` timer loop sleeps for on its next tick: `hostPollIntervalMs * backoffMultiplier`, where `backoffMultiplier` is `HIDDEN_POLL_BACKOFF_MULTIPLIER` (default `6`) when `isTabHidden`, `OBSERVER_HEALTHY_POLL_BACKOFF_MULTIPLIER` (default `6`) when `observerConfirmedActive`, `maxOf` of the two when both hold, or `1` when neither holds. Recomputed fresh on every tick (not cached), so a visibility or observer-health change takes effect on the very next tick without restarting the loop. | New, `HostDirectorySync` — the fix for pre-mortem.md's remaining P1 (poll never backed off on visibility/observer health). |
| `isTabHidden` | `Boolean` instance field on `HostDirectorySync`, kept current by a dedicated `scope.launch` loop alternating `jsVisibilityHiddenPromise()`/`jsVisibilityVisiblePromise()` awaits (same interop shape as `PlatformFileSystem.kt:48-57`'s existing hidden-only loop and Story 5.3.1's existing visible-only loop, but tracking state rather than firing a one-shot side effect). Read by `effectivePollIntervalMs()`; also the visibility signal Story 5.3.1's immediate-poll-on-regain trigger already reacts to (that trigger and this backoff read the same underlying visibility transitions but serve different purposes — one fires an extra poll, the other widens/narrows the timer's own cadence). | New, `HostDirectorySync`. |
| `observerConfirmedActive` | `Boolean` instance field on `HostDirectorySync`, set `true` the instant `HostChangeObserver` construction + `observeHandle()` (Task 5.2.2a) complete without throwing, and left `true` for the life of the connection (per ADR-002's "fast path" framing — once confirmed active, the poller is treated as a safety net, not re-demoted to primary on a quiet period). Stays `false` when `fileSystemObserverSupported()` is `false` or `observeHandle()` throws. Read by `effectivePollIntervalMs()`. | New, `HostDirectorySync` — deliberately simpler than a "recently delivered an event" freshness heuristic (see Pattern Decisions "Poll cadence policy" for why). |
| `pollHostDirectoryOnce(dirHandle, opfsPath)` | Single walk function shared by the timer-based `HostDirectoryPoller` and the visibility-triggered immediate recheck. Branches on `.stek` suffix exactly as `runHostReconciliation`'s walk does (Epic 5.1, adversarial-review.md Blocker 4): text paths use `.text()` + `cacheAccess.set`, `.md.stek` paths use bytes read + `cacheAccess.setBytes`, never decoding encrypted bytes as UTF-8. | New, on `HostDirectorySync`. |
| `hostModTimes` | `MutableMap<String, Long>` instance field — synchronous-readable cache of host file mtimes, fed by `HostDirectoryPoller`, consumed by `PlatformFileSystem.getLastModifiedTime()`/`listFilesWithModTimes()` via delegation to `HostDirectorySync`. | New, on `HostDirectorySync`. |
| `HostChangeObserver` | Wraps `FileSystemObserver` (per ADR-002): on receiving change records, triggers an immediate `pollHostDirectoryOnce()` for affected paths instead of waiting for the next timer tick. | `HostDirectorySync`, gated on `'FileSystemObserver' in self`. |
| `IndexedDbHandleStore` | Grouping name (not a class) for the `HostDirectoryInterop.kt` functions wrapping `indexedDB.open`/`put`/`get`, keyed by `graphId`. | wasmJs only. |
| `HostHandleEnvelope` | `{ graphId, dirName, storedAtMillis }` metadata persisted alongside the handle in IndexedDB. | New `@Serializable` data class. |
| `WebLock` | Standalone, name-parameterized Web Locks utility (`platform/WebLock.kt`), written new for this feature. `GitWriteLock.kt` (a `web-git-writeback`-owned file) is **not modified, refactored, or extracted from** — `WebLock`'s `js()`/`.await()` machinery is a deliberate ~50-line duplicate of `GitWriteLock`'s existing acquire-now/release-later idiom, not a shared base the two features both depend on. | Duplication, zero blast radius on the sibling feature — see Pattern Decisions ("Cross-tab coordination") and Epic 1.1; resolves architecture-review Concern re: requirements.md's Out-of-Scope wording for `web-git-writeback`-owned files. |
| `FolderSyncLockNaming` | commonMain pure lock-name derivation object producing `stele-folder-sync-write-<graphId>-<pathHash>` / `stele-folder-sync-poll-<graphId>`-shaped names, analogous to `GitWriteLockNaming`. | New, `platform/FolderSyncLockNaming.kt`. |
| `HostWriteLockScope` | The narrow per-write lock held only for one file's `createWritable()`/`write()`/`close()` sequence. | Concept, not a type — a `WebLock.withLock(...)` call site inside `HostDirectorySync`. |
| `HostPollLockScope` | The coarser per-poll-tick lock held only for the duration of one `pollHostDirectoryOnce` walk; a tab that loses the race skips that tick (safe no-op — OPFS is cross-tab-shared). | Concept — a `WebLock.withLock(..., ifAvailable-style skip)` call site inside `HostDirectorySync`. |
| `SyncDegraded` | User-visible state indicating a host write is queued and stuck while permission is still `Granted` (e.g. a transient quota blip) — `hostAccessState == Granted && pendingWriteCount > 0 && hostWriteStuck` (Task 4.4.1c). Permission-shaped failures (revoked/`NotFoundError`) are routed to `Denied`/`PromptNeeded`/`Disconnected` instead (Task 4.4.1a), not `SyncDegraded`. Surfaced via the existing `GraphLoader.writeErrors: SharedFlow<WriteError>` channel using `DomainError.FileSystemError.WriteFailed`. | No new error channel. |
| `HostRenameOp` | The write-new-then-verify-then-delete-old two-phase protocol used to propagate in-app page renames to the host directory. | Implemented on `HostDirectorySync`; `PlatformFileSystem.renameFile` override delegates to it (`hostDirectorySync.renameHostFile(from, to)`). |
| `FolderSyncStatusBadge` | New Compose composable (mirrors `ui/components/SyncStatusBadge.kt`) rendering `HostAccessState`/write-through/poll status in the sidebar. | `ui/components/FolderSyncStatusBadge.kt`. Reads `hostAccessStateFlow`/`hostWritePendingCountFlow` passed in as nullable `StateFlow` parameters (see Task 2.3.1c), not by downcasting `FileSystem`. |

---

## Pattern Decisions

| Component | Pattern Chosen | Source | Alternative Rejected | Reason |
|-----------|---------------|--------|---------------------|--------|
| Overall class structure for Phase 2–7 orchestration | Extract a dedicated collaborator, `HostDirectorySync` (Epic 1.6), that owns handle lifecycle, reconciliation, the write-through queue, the poller, observer glue, both lock types, and the rename protocol. `PlatformFileSystem` composes one instance and delegates only the seven `FileSystem`-interface touch points (`writeFile`/`writeFileBytes`/`deleteFile`/`renameFile`/`getLastModifiedTime`/`listFilesWithModTimes`/`hostDirectoryAccessState`) to it, via a small constructor-injected `CacheAccess` interface for `cache` reads/writes | architecture-review.md Blocker 1 remediation; mirrors this codebase's existing `FileRegistry`/`GraphFileWatcher` split as separate single-purpose classes from `GraphLoader` on JVM/Android | Growing `PlatformFileSystem` itself by ~20+ new fields/methods (the original plan draft) | `PlatformFileSystem` is already 387 lines carrying three responsibilities (OPFS cache mirroring, git dirty-set tracking + `.stele-dirty-set.json` checkpointing, GitHub raw-fetch fallback); adding host-directory-sync as a fourth large responsibility on the same class is a Single Responsibility Principle violation / God Object, and forced seven wasmJsTest files to each exercise a different facet of one class — a symptom the extraction resolves by scoping each test file to `HostDirectorySync` directly |
| External-change detection architecture | Retained-handle + async poller feeding the existing synchronous `FileRegistry`/`GraphFileWatcher` contract (Gateway/Adapter) | PoEAA Gateway, GoF Adapter | (B) Bespoke wasmJs-only watcher bypassing `FileRegistry`/`GraphFileWatcher` entirely | Reimplementing dirty-flag ordering, active-page suppression, and sticky git-merge suppression bespoke risks silently diverging from tested desktop/Android behavior (`research/build-vs-buy.md` §3a) |
| External-change detection cadence | Incremental mtime/hash diff (existing `FileRegistry.detectChanges` shape), reused for both steady-state polling and connect-time reconciliation | — | (C) Full-tree re-import/re-hash on every tick | O(graph) per-tick scan violates `CLAUDE.md`'s "must not become O(graph) scan" rule at 8,000+-page scale |
| `FileSystemObserver` role | Fast-path accelerator on top of a mandatory poll baseline (ADR-002) | — | `FileSystemObserver` as the sole/primary mechanism | Makes the correctness-critical path depend on a young API's edge-case behavior (`errored` records, Windows move quirk) — too risky for a data-integrity feature |
| Poll cadence policy (`effectivePollIntervalMs`) | Dynamic, recomputed-per-tick backoff: base `hostPollIntervalMs` (10s) widened by a `6x` multiplier when the tab is hidden (`isTabHidden`), and independently by a `6x` multiplier when `FileSystemObserver` is confirmed active (`observerConfirmedActive`) — `maxOf` of the two multipliers when both hold, never stacked/multiplied together, to avoid an unboundedly large interval | `research/ux.md` §4 ("poll aggressively only while visible, back off or pause when hidden"); ADR-002's "fast path, not sole mechanism" framing | (1) Leaving the fixed 10s cadence unconditional regardless of visibility/observer state (this plan's original design); (2) Fully pausing (zero polls) while hidden or while the observer is healthy, relying solely on Story 5.3.1's visibility-regain trigger and the observer's own change records | (1) is pre-mortem.md's remaining P1: a backgrounded tab's `getFile()` calls run at full cadence forever, a slow-burn battery/fan-drain complaint (`research/ux.md` §4 explicitly warns against this). (2) was rejected because the poller's entire reason for existing under ADR-002 is to be a safety net for exactly the cases where the fast path silently fails (an `errored`-only observer session, a missed record, a hidden tab where `visibilitychange` itself fails to fire in some embedder) — a fully paused poller stops being a safety net the moment the thing it's meant to catch is the fast path itself failing. A wide-but-nonzero interval (6x ≈ 60s) preserves the safety-net property at a bounded, small fraction of the always-on cost |
| `observerConfirmedActive` semantics | "Construction + `observeHandle()` succeeded, sticky for the connection's lifetime" (a simple boolean latch) | ADR-002 "fast path" framing (task brief's second offered option) | A "recently delivered an event within the last N seconds" freshness heuristic requiring `HostChangeObserver` to track `lastObserverEventAtMillis` and re-derive health every tick | A freshness heuristic conflates "the observer is working" with "something changed recently" — a healthy observer on a quiet directory (the common case) would incorrectly read as unhealthy and the poller would un-back-off for no reason. A sticky "did construction/subscription succeed" latch matches what `observerConfirmedActive` is actually meant to answer (is the fast path live) without needing a second piece of decaying state to keep synchronized with the timer loop |
| Write-through queue | Coalescing write-behind queue (single-flight + trailing coalesce), mirroring the existing `markerWriteInFlight`/`markerWriteDirty` idiom | PoEAA lightweight Unit of Work | A fully synchronous write-through (block `writeFile()` until the host `write()` Promise resolves) | `writeFile()` is a synchronous `Boolean`-returning `FileSystem` method with no existing sync/async bridge; blocking it would stall the UI thread on every debounced save |
| Directory handle persistence | Hand-rolled `js()`/`external` IndexedDB interop matching `OpfsInterop.kt`'s idiom (Gateway) | `research/build-vs-buy.md` §1 | `JuulLabs/indexeddb` (Kotlin/Wasm coroutines wrapper) | First interop-wrapper-library precedent in a codebase with a deliberate zero-wrapper convention, for a 3-call surface too small to repay the ongoing dependency cost — see ADR-001 |
| Cross-tab coordination | Standalone, newly-written `WebLock` utility (name-parameterized `navigator.locks.request` "acquire-now, release-later"), scoped narrowly per-write and per-poll-tick | GoF Template Method (shared acquire/release skeleton, per-feature lock name) | (1) Full leader election (one tab elected sole owner of the whole feature for its lifetime); (2) Extracting the same machinery out of `GitWriteLock.kt` and making `GitWriteLock` delegate to it | (1) No existing precedent for whole-feature leader election in this codebase; per-write/per-tick locking suffices because OPFS is already cross-tab-shared — a losing tab sees the winner's result on its own next tick (`research/architecture.md` §3.2). (2) `GitWriteLock.kt` is a `web-git-writeback`-owned file; requirements.md's Out-of-Scope section says this project must not modify that feature's behavior. Even a claimed zero-behavior-change extraction-and-delegate refactor carries avoidable regression risk to a working, uninvolved feature for a ~50-line utility — duplicating the acquire/release machinery into this feature's own `WebLock.kt` gives zero blast radius on `web-git-writeback` at negligible ongoing cost (adversarial-review.md Concern remediation) |
| Write-through queue (`hostWritePending`) durability | No independent persistence (no new IndexedDB store for pending writes); recoverable by re-running `runHostReconciliation` on every reconnect, which now runs unconditionally on both `reconnectHostDirectory` and `connectHostDirectory` (see next row) | Idempotent recomputation over a second persisted-state store | A dedicated IndexedDB-backed pending-write-queue store, checkpointed on every enqueue/dequeue | A tab crash between a debounced edit and its host flush is exactly the same class of divergence reconciliation is already required to detect (`cache` differs from host content) — reusing that single, already-tested mechanism avoids a second persisted schema, a second recovery code path, and a second source of "what does the app think still needs pushing" truth (adversarial-review.md Blocker 2 remediation). **Now unconditional, not merely bounded**: Epic 1.7's awaited-write-before-enqueue fix (next row) closes the previous "only works for edits whose OPFS write already completed" caveat. |
| OPFS-write durability (pre-existing latent bug, predates this project — **scope explicitly expanded by the user to fix at the root, superseding this plan's original Option-B decision**) | **Option A — fix at the root (Epic 1.7).** `writeFile`/`writeFileBytes` remain synchronous, non-blocking, `Boolean`-returning `FileSystem`-interface methods (unchanged signatures — no platform-wide blocking-write contract change), but each call's `scope.launch { opfsWriteFile(...) }` is now tracked as a per-path awaitable `Deferred`. `HostDirectorySync.scheduleHostWriteThrough` awaits that `Deferred` before ever adding the path to `hostWritePending` — closing the crash window for this feature's host-push path at the source, not merely narrowing it. A `beforeunload`/`pagehide`-triggered best-effort flush loop is added to `PlatformFileSystem`'s `init` block, mirroring the existing `.stele-dirty-set.json` marker-flush idiom, applying platform-wide (not gated on a host directory being connected) as defense in depth for graceful-ish teardown paths (tab close, navigation, reload) — closing the crash window "for... the underlying platform" per the user's scope-expansion request, to the extent any client-side JS mechanism can (a true instant hard-kill — OOM, force-quit — remains fundamentally unclosable by any JS-level fix; this was never claimed to be 100% closeable, matching the existing marker-flush idiom's own honest best-effort framing). | — (user-directed scope expansion, not a literature pattern) | **Option B — explicitly scope out** (this plan's original decision, now reversed). Document as an accepted, pre-existing risk and leave `writeFile`/`writeFileBytes` unchanged. | The user explicitly expanded this project's scope to fix this at the root rather than merely document it. Choosing the narrower "await before enqueue" mechanism over a fully blocking `writeFile` preserves this plan's own already-established reasoning (the "Write-through queue" row above's rejection of a fully synchronous host write for exactly the "stall the UI thread on every debounced save" reason) while still closing the actual data-loss vector the Critical Finding and adversarial-review.md's sole remaining Blocker identified — a strict improvement over Option B's "narrows but does not close" outcome, at a cost (Epic 1.7, ~9 tasks) the user has explicitly accepted as in-scope. |
| `reconnectHostDirectory` reconciliation | `reconnectHostDirectory`'s success path (permission still `"granted"`) runs `runHostReconciliation` unconditionally, same as `connectHostDirectory` — **but, per Epic 3.4's later revision, non-blocking** (`scope.launch`, never awaited) on the reconnect path, whereas `connectHostDirectory`'s reconciliation remains awaited/blocking since ux.md Surface 8's progress UI only covers the one-time connect flow. "Unconditional" still holds for both entry points; "blocking" no longer does. | — | Leaving `reconnectHostDirectory` to just set `hostDirHandle` and start the poll/write-through loops directly (original plan draft) | Ordinary session resumption ("reopen the app") is the *more common* trigger for the exact host/cache divergence class the Critical Finding worries about (queued-but-unflushed browser edits, host-side changes made while the tab was closed, OPFS eviction — see next row) than the one-time `connectHostDirectory` entry point; the poller's cold-start diff-and-overwrite behavior is not an equivalent safety net because it never calls `classifyReconciliation`/`onHostConflict` (adversarial-review.md Blocker 3 remediation). Making the reconnect path non-blocking (Epic 3.4) avoids stalling every ordinary app open on a full reconciliation walk at large-graph scale (pre-mortem.md P1 remediation). |
| OPFS eviction recovery | No separate "cache emptier than expected" detection heuristic; `navigator.storage.persist()` called best-effort on connect, and the unconditional reconciliation-on-reconnect (previous row) is the actual recovery path — an evicted/empty `cache` simply causes every host file to reclassify as `HostOnlyNew` and be re-imported | — | A dedicated heuristic comparing current `cache` size/emptiness against a persisted "expected non-empty" flag | Once `reconnectHostDirectory` always reconciles, a bespoke eviction-detection heuristic would just be re-deriving a subset of what reconciliation already computes unconditionally — the corollary is free, and avoids inventing a second, narrower detector that could itself have false negatives (adversarial-review.md Blocker 1 remediation) |
| Interrupted-rename artifact handling | Drop the content-hash-based auto-delete heuristic entirely; an interrupted rename's stale old-path file is imported as an ordinary `HostOnlyNew` page (no special-cased deletion), with a non-destructive `println("[SteleKit] reconciliation: possible stale-rename duplicate...")` log line when its content hash coincidentally matches another `cache` path | — | Requiring a stronger correlation than content hash (e.g. a persisted rename-intent log recording old→new path pairs, consulted before deleting) | Both options were offered by adversarial-review.md Blocker 5. A rename-intent log is new persisted-state infrastructure (a third store, alongside handle persistence and reconciliation) purely to justify a *deletion*; dropping the heuristic needs no new infrastructure, cannot destroy a legitimate page (the worst case is two visible, user-cleanable duplicate pages — recoverable, not silently destructive), and keeps this project's established "surface divergence, don't auto-resolve it destructively" posture (matches the `DiskConflict`-reuse decision in this same table) |
| Reconciliation classification | Sealed interface `ReconciliationOutcome` (type-driven design — illegal states unrepresentable), reusing `FileRegistry.detectChanges`'s new/changed/deleted shape | Type-Driven Design | An untyped string/enum tag (`"identical"`/`"conflict"`/`"new"`/`"local"`) | A sealed interface forces every consuming `when` to be exhaustive at compile time — a missed branch (e.g. forgetting `BrowserOnlyNeedsPush`) fails the build instead of silently no-op'ing at runtime, on the exact code path this project's Critical Finding identifies as data-loss-prone |
| Host directory access state | New optional `suspend` `FileSystem` method (`hostDirectoryAccessState`), default `NotApplicable`; wasmJs override is a one-line delegate to `HostDirectorySync.hostAccessStateFlow.value` | Existing SAF write-behind convention (`FileSystem.kt:73-145`, seven precedent methods) | A wasmJs-only side channel that `App.kt` downcasts `PlatformFileSystem` to reach | Matches this codebase's established interface-growth convention — keeps JVM/Android/iOS at zero cost and zero new surface (`research/architecture.md` §1.3) |
| UI-layer access to `HostDirectorySync`'s reactive state (`hostAccessStateFlow`, `hostWritePendingCountFlow`) | Nullable `StateFlow<T>? = null` parameters threaded into `App(...)` from platform-specific `Main.kt`, defaulting to `null` on JVM/Android/iOS | Established precedent already in this codebase: `App(..., localChangesCountFlow: StateFlow<Int>? = null, ...)` (`App.kt:206`), wired from `browser/Main.kt:197` as `localChangesCountFlow = opfsFileSystem.dirtyFileCountFlow` | `expect`/`actual` members on `PlatformFileSystem`/`FileSystem` | An `expect class PlatformFileSystem` member (or an `expect`/`actual`-gated `FileSystem` property) would force JVM/Android/iOS `actual` implementations to also declare wasmJs-only members — an Interface Segregation violation; `localChangesCountFlow` is the exact precedent for this shape of platform-specific reactive state and this project should follow it, not fork a new mechanism (architecture-review.md Blocker 2 remediation) |
| Write-through vs. git dirty-set | Structurally independent third side-effect on `writeFile`/`writeFileBytes`/`deleteFile`; separate map instance; never calls `recordDirty`/`clearDirtySet` | PoEAA — avoid conflating two Unit-of-Work-shaped concerns behind one signal | Reusing/extending the existing `dirtySet` for both consumers | The two dirty-sets have different consumers, persistence, and clear-timing (`research/architecture.md` §1.2 table) — conflating them recreates the exact trap `web-git-writeback`'s own architecture doc warned against one layer up |
| Rename/move propagation | Write-new + delete-old, idempotent two-phase (verify-then-delete), mirroring `FileRegistry`'s existing `preMarkPendingWrite`/`clearPendingWrite` saga shape | Saga / compensating transaction | Relying on `FileSystemHandle.move()`/`.rename()` (Chrome 138+) as the primary mechanism | Directory move/rename is unimplemented in any browser (`research/features.md` §1.4); file-level `move()` is too new/narrow to be primary — write+delete is the only mechanism guaranteed available across the whole in-scope Chromium set |
| Conflict UX | Reuse `DiskConflict`/`DiskConflictDialog`/`DiskConflictFullScreen` unmodified | PoEAA — reuse existing presentation layer, introduce no new UI pattern | Building a distinct "folder livesync conflict" dialog, or routing through `ConflictResolutionScreen`/`SyncState.ConflictPending` (the original requirements.md draft's since-corrected reference) | `ConflictResolutionScreen` is a line-hunk git-merge UI with an incompatible data model (`ConflictHunk` vs. whole-file `DiskConflict`) — `research/architecture.md` §7.3 confirms `DiskConflict` is the correct, already-resolved mechanism |
| IndexedDB/permission/`FileSystemObserver` glue code | Transaction Script — small, procedural, hand-rolled `js()` functions grouped by concern in one new file | PoEAA Transaction Script (matches existing `OpfsInterop.kt` style) | A `HostDirectoryGateway` class wrapping the same calls in an OOP facade | No behavior in this surface needs encapsulated state or polymorphism — matching the codebase's existing flat top-level-function convention avoids an unnecessary abstraction layer |

---

## Migration Plan

This project has no SQL schema changes, but it has an equivalent-risk **data-shape migration at
the upgrade boundary**: a user who already has browser-only OPFS edits under the current
one-time-import behavior must not have them destroyed the first time they opt into live sync on an
already-populated graph. See Phase 3 (Epic 3.1–3.3) for the full required reconciliation-pass
implementation and test coverage; this section states the migration contract only.

- **Before state**: `cache`/OPFS holds whatever the last `importUserDirToCache()` produced, plus
  any subsequent in-browser edits (`writeFile`/`writeFileBytes`/`deleteFile`). No
  `HostDirectoryHandle` is retained (pre-upgrade builds never stored one).
- **Trigger**: user invokes `connectHostDirectory(existingOpfsPath)` (Phase 3, Epic 3.1) — a
  *different* entry point than `pickDirectoryAsync()`, reached only from an explicit "Enable live
  folder sync" affordance on an already-populated graph (`ui/components/settings/FolderSyncSettings.kt`,
  Phase 8). **Also triggered on every ordinary `reconnectHostDirectory` (Epic 2.2)** — not just this
  one-time opt-in — per adversarial-review.md Blocker 3: session resumption is the more common
  trigger for the same class of divergence (host-side edits made while the tab was closed, an
  OPFS-evicted `cache`, a crash-lost write-through queue), so the same migration step now runs there
  too, not only at initial opt-in.
- **Migration step**: `runHostReconciliation` (Phase 3, Epic 3.2) walks the picked host directory
  once, classifies every path via `classifyReconciliation`, and applies the four-way action table
  (no-op / surface conflict / import new / queue for push) — **never** an unconditional overwrite.
- **After state**: `cache`/OPFS and the host directory converge without any silent data loss — every
  divergence reconciliation detects is either a no-op (content already matches) or routed through an
  existing, human-visible mechanism (`DiskConflict` dialog or the write-through queue). The
  previously-accepted caveat (an edit whose OPFS write never completed before a crash being invisible
  to reconciliation) is closed by Epic 1.7's awaited-write-before-enqueue fix (scope expansion,
  Option A) — see the "OPFS-write durability" Pattern Decisions row and Epic 1.7 for the mechanism.
- **Rollback**: if `connectHostDirectory` is never invoked (i.e. the user never opts in), the graph
  behaves identically to pre-upgrade — `hostDirHandle` stays `null`, nothing new runs. Opting in is
  reversible in the sense that OPFS remains authoritative for in-app reads/writes throughout; there
  is no data format change to undo.
- **Test coverage**: `HostDirectorySyncReconciliationTest` (Phase 3, Epic 3.3) exercises all four
  classification branches against a non-empty OPFS graph with a divergent host directory, plus a
  regression assertion that the **old** `pickDirectoryAsync()` path (fresh, empty graph) is
  byte-for-byte unchanged.

## Observability Plan
- **Logs**: `println("[SteleKit] ...")`, matching the existing convention throughout
  `PlatformFileSystem.kt`/`OpfsInterop.kt`, continued in the new `HostDirectorySync.kt`. Log
  write-through attempts and outcomes (path + success/
  failure, never file content), reconciliation classification counts per outcome
  (`"[SteleKit] reconciliation: N identical, M conflict, K host-only, J browser-only"`), permission
  state transitions (`HostAccessState` old → new), and poll-tick skip-due-to-lock events. No PII, no
  file content, matching `requirements.md`'s Observability Requirements section verbatim.
- **Metrics**: no telemetry/analytics infrastructure is introduced (matches `web-git-writeback`
  precedent). The client-facing "metrics" surface is a set of new `StateFlow`s mirroring the
  existing `dirtyFileCountFlow` pattern: `hostWritePendingCountFlow: StateFlow<Int>` and
  `hostAccessStateFlow: StateFlow<HostAccessState>`, both hosted on `HostDirectorySync` (not
  `PlatformFileSystem`) and reaching `FolderSyncStatusBadge` via the nullable-`StateFlow`-parameter
  mechanism decided in Task 2.3.1c (`App(..., hostAccessStateFlow: StateFlow<HostAccessState>? = null, hostWritePendingCountFlow: StateFlow<Int>? = null)`, following the `localChangesCountFlow` precedent) — never via `expect`/`actual` or a downcast.
- **Alerts**: none. Purely client-side feature, no server-side component, no oncall surface —
  matches `requirements.md`'s Risk Control section.

## Risk Control
- **Feature flag**: none (no flag infrastructure exists in this codebase, matching
  `web-git-writeback`'s precedent). Scoped entirely to `wasmJs`, gated behind two explicit
  user-opt-in actions: (1) picking a directory for a new graph (`pickDirectoryAsync`, unchanged
  entry point) or (2) clicking "Enable live folder sync" on an existing graph
  (`connectHostDirectory`, new entry point, Phase 8). A user who does neither sees zero behavior
  change.
- **Rollback procedure**: revert the retained-handle/write-through wiring back to the current
  import-only `pickDirectoryAsync()`/`importUserDirToCache()` behavior. OPFS remains the source of
  truth either way — no data migration is needed to roll back, since nothing this project adds
  changes the on-disk OPFS schema (`.stele-dirty-set.json` is untouched by this project; see Phase 8
  Epic 8.2's dedicated non-regression test).
- **Staged rollout**: no flag infra means no server-controlled staged rollout is possible. The
  practical staging mechanism is **phase ordering**: Phases 1–3 (foundations, handle retention +
  resume, and the mandatory reconciliation pass) must ship and be verified before Phase 4
  (write-through) reaches any user-visible entry point — the reconciliation pass is the
  data-loss-prevention gate for the write-through this project's whole rationale depends on.
- **Pre-existing risk, closed by this project (scope expanded per explicit user direction, Epic
  1.7)**: `writeFile`/`writeFileBytes`'s previously-unawaited OPFS write — present in the codebase
  today, independent of this project's original scope — could cause silent, zero-record loss of a
  single edit if a hard crash landed inside that write's flight time. Rather than accepting this as
  a documented residual risk (this plan's original decision), the user explicitly expanded this
  project's scope to fix it at the root: the OPFS write is now tracked as a per-path awaitable
  operation, and this project's own write-through queue (Epic 4.3's `scheduleHostWriteThrough`)
  awaits it before ever enqueueing a host push, plus a `beforeunload`/`pagehide` best-effort flush as
  platform-wide defense in depth. See the "OPFS-write durability" Pattern Decisions row and Epic 1.7
  for the full mechanism. A true instant hard-kill (OOM, force-quit) remains fundamentally
  unclosable by any client-side JS mechanism — this was never claimed as 100% closed, only as fixed
  at the root for the race this project's Critical Finding actually identified.

## Unresolved Questions
1. **`FileSystemObserver`'s `"errored"` record recovery semantics** (`research/stack.md` §"Open
   questions to carry into planning", item 2) — this project defaults to "fall back to the next
   regular `HostDirectoryPoller` tick" (per ADR-002) if no better answer emerges from a short
   implementation-time spike at the start of Phase 5 (Epic 5.2). Must be resolved (or explicitly
   deferred to the default) before Story 5.2.1 starts.
2. **`HostDirectoryPoller`'s exact cadence** relative to `GraphFileWatcher`'s existing 5s
   `pollIntervalMs` — this plan proposes a coarser default (10s) to avoid doubling I/O pressure.
   **No longer left to ad hoc empirical tuning**: Epic 5.5's large-graph (8,000+ file) poller-tick-cost
   benchmark is now a required Phase 5 deliverable (adversarial-review.md Blocker 6) — the 10s default
   ships only if that benchmark shows an acceptable tick cost at that scale; otherwise the benchmark's
   measured numbers drive the actual default before Phase 5 is considered done. **This 10s number is
   now `hostPollIntervalMs`, the base interval, not the interval actually used every tick** — see
   Story 5.1.2's revised acceptance criteria and the "Poll cadence policy" Pattern Decisions row: the
   timer loop sleeps for `effectivePollIntervalMs()`, which widens to ~60s while the tab is hidden or
   while `FileSystemObserver` is confirmed active (pre-mortem.md P1 remediation).
3. **Whether to ship a PWA manifest** to unlock Chrome's zero-click persistent permissions (Chrome
   122+) — explicitly out of scope for this project per `requirements.md`'s accepted one-click
   baseline, but flagged by `research/stack.md`/`research/ux.md` as a cheap future upgrade. Not
   blocking; tracked as a follow-up, not resolved here.

## Dependency Visualization

```
Phase 1: Foundations
  (standalone WebLock — no GitWriteLock.kt changes, FolderSyncLockNaming, HostAccessState,
   ReconciliationOutcome + classifyReconciliation + classifyReconciliationBytes, HostWritePayload,
   HostDirectoryInterop.kt (incl. storage.persist()), HostDirectorySync class shell + bytes-aware
   CacheAccess (Epic 1.6), OPFS-write-durability await-before-enqueue fix + beforeunload/pagehide
   flush (Epic 1.7 — SCOPE EXPANSION, required before Epic 4.3 may enqueue any path))
        │
        ├──────────────┬──────────────────────┐
        ▼              ▼                      ▼
Epic 2.1: Handle   Phase 3 depends on     (interop primitives feed
Retention at Pick  Epic 2.1's retained     every later phase)
Time                handle + Phase 1's
        │           ReconciliationOutcome
        │                  │
        └──────► Phase 3: Upgrade Reconciliation Pass (REQUIRED, data-loss gate)
                  runHostReconciliation — also the recovery path for OPFS
                  eviction (Blocker 1) and lost hostWritePending state (Blocker 2).
                  Epic 3.4 adds the mtime/size pre-filter, non-blocking session-resume
                  launch, and required large-graph benchmark (pre-mortem.md P1 #1)
                           │
                           ▼
                Epics 2.2–2.4: Session Resume, Permission-Lost UX, OPFS
                persist() — reconnectHostDirectory now ALWAYS launches (non-blocking,
                Epic 3.4) runHostReconciliation before starting Phase 4/5 loops
                (Blocker 3 — this is why these Phase 2 epics now depend
                 on Phase 3, not the reverse)
                           │
                ┌──────────┴──────────┐
                ▼                     ▼
        Phase 4: Write-      Phase 5: External-Change
        Through to Host      Detection (poller + observer
        Directory            fast path + visibility recheck +
                              Epic 5.5 REQUIRED large-graph poller-cost
                              benchmark)
                │                     │
                └──────────┬──────────┘
                           ▼
                Phase 6: Cross-Tab Coordination
                (locks around Phase 4's flushHostWrite
                 and Phase 5's pollHostDirectoryOnce)
                           │
                           ▼
                Phase 7: Rename/Move Propagation
                (needs Phase 4's write path; interrupted-rename artifacts
                 are surfaced via a non-destructive log, not auto-deleted
                 — see Pattern Decisions)
                           │
                           ▼
                Phase 8: Status UX, Settings Entry Point,
                Fallback Regression Guard, Accessibility
```

**Sequencing note**: Epic 2.1 (handle retention at pick time) must land before Phase 3
(reconciliation needs a retained handle to walk). Phase 3 must in turn land before Epics 2.2–2.4
(silent resume, one-click resume, and eviction recovery all now call `runHostReconciliation`, per
Blocker 3's remediation below) — so within Phase 2, Epic 2.1 ships first, then Phase 3 in full,
then the remaining Phase 2 epics. This is a documented reordering from the plan's original
strictly-sequential Phase 1→2→3 story, not an oversight.

---

## Phase 1: Foundations — Shared Interop, Types & Utilities

### Epic 1.1: A standalone Web Locks utility for this feature only
**Goal**: Give this feature its own tested Web Locks implementation (per `research/architecture.md`
§3.2's "do not build a new acquire/release mechanism") **without** touching `GitWriteLock.kt`, a
`web-git-writeback`-owned file requirements.md's Out-of-Scope section says this project must not
modify. **Decision (supersedes the plan's original extract-and-delegate approach, per
adversarial-review.md Concern remediation)**: `WebLock.kt` is written new, duplicating
`GitWriteLock`'s ~50-line acquire-now/release-later `js()`/`.await()` idiom rather than extracting
it out of `GitWriteLock.kt` and making that file delegate. `GitWriteLock.kt` is not read, imported,
modified, or refactored by any task in this project — zero blast radius on the sibling feature. The
two implementations are permitted to drift independently; if a third consumer ever needs this
idiom, a future project can extract a shared utility then, with both existing call sites as
precedent, rather than this project taking on that risk for a sibling feature it does not own.

#### Story 1.1.1: Write `WebLock`, a standalone Web Locks utility (no `GitWriteLock.kt` changes)
**As a** SteleKit maintainer, **I want** the Web Locks `js()`/`.await()` machinery available as a
name-parameterized utility scoped to this feature, **so that** this project's cross-tab locking
(Phase 6) has a tested implementation without introducing any regression risk to
`web-git-writeback`'s working `GitWriteLock`.
**Acceptance Criteria**:
- A new `WebLock` object exposes `suspend fun <T> withLock(lockName: String, block: suspend () -> T): T` with the same acquire-now/release-later semantics as `GitWriteLock.withLock` (independently implemented, not shared code).
  - *Given* two calls to `WebLock.withLock("lock-a") { }` and `WebLock.withLock("lock-b") { }` from the same tab, *When* both are launched concurrently, *Then* both complete without blocking each other (distinct lock names never contend).
- `GitWriteLock.kt` has zero diff from this project, start to finish.
  - *Given* a `git diff` of this project's full implementation against `main`, *When* `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/git/GitWriteLock.kt` is checked, *Then* it does not appear in the diff at all.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/WebLock.kt` (new) — `GitWriteLock.kt` is explicitly **not** in this project's file list anywhere.

##### Task 1.1.1a: Create `WebLock.kt` with its own `jsRequestLockHandle`/acquire/release machinery (~6 min)
- Write `jsRequestLockHandle`, `jsHandleAcquiredPromise`, `jsHandleRelease`, `jsHandleDonePromise`, and the `withLock` body as a new top-level `object WebLock` in the new file, matching `GitWriteLock.kt:26-80`'s existing shape/semantics by reference (read it for the pattern) but typed out fresh in this file — not copy-pasted via a shared extraction, and not importing anything from `git/GitWriteLock.kt`. Include the same "acquire-now, release-later" explanation and "do not hold across multiple suspend calls" warning in this file's own KDoc (duplicated prose is fine; a shared dependency is what's being avoided).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/WebLock.kt`

##### Task 1.1.1b: Confirm `GitWriteLock.kt` is untouched (~1 min)
- After Task 1.1.1a, diff `git/GitWriteLock.kt` against its pre-project state and confirm it is byte-identical — this is the acceptance check for Story 1.1.1's second criterion, not a code change.
- Files: none (verification only)

##### Task 1.1.1c: `WebLockTest` covering `withLock`'s own basic semantics (~4 min)
- A focused wasmJsTest for `WebLock.withLock` (distinct-name non-contention, same-name sequential contention) run against the real browser Web Locks implementation, independent of `web-git-writeback`'s existing `GitWriteLock` test coverage (which is untouched and continues to test `GitWriteLock` directly). Non-blocking `tryWithLock` gets its own, more thorough test later in Epic 6.3 (`WebLockTest.kt`, extended not replaced).
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/WebLockTest.kt` (new — later extended by Task 6.3.1a)

### Epic 1.2: Folder-sync lock naming
**Goal**: A distinct, collision-safe Web Locks namespace for this feature, per
`research/architecture.md` §3.2 point 1 ("do not share `GitWriteLock`'s lock name").

#### Story 1.2.1: `FolderSyncLockNaming` pure logic, commonMain-testable
**As a** developer wiring cross-tab locks in Phase 6, **I want** deterministic, collision-safe lock
names derived from `graphId` (and, for write locks, the repo-relative path), **so that** two picked
directories for two different graphs in two tabs never contend on the same lock name.
**Acceptance Criteria**:
- `FolderSyncLockNaming.pollLockNameFor(graphId: String): String` and `FolderSyncLockNaming.writeLockNameFor(graphId: String, repoRelativePath: String): String` are pure functions producing deterministic, distinct names.
  - *Given* `graphId = "a1b2c3d4"`, *When* `pollLockNameFor("a1b2c3d4")` is called twice, *Then* both calls return the identical string `"stele-folder-sync-poll-a1b2c3d4"`.
  - *Given* `graphId = "a1b2c3d4"` and two different `repoRelativePath` values `"pages/Foo.md"` and `"pages/Bar.md"`, *When* `writeLockNameFor` is called for each, *Then* the two returned names are different strings.
- Names never collide with `GitWriteLockNaming.lockNameFor`'s `"stele-write-..."` prefix.
  - *Given* any `graphId`, *When* `pollLockNameFor(graphId)` and `writeLockNameFor(graphId, "x")` are compared against `GitWriteLockNaming.lockNameFor("https://github.com/a/b")`, *Then* none of the three strings share a prefix (`"stele-folder-sync-poll-"`/`"stele-folder-sync-write-"` vs. `"stele-write-"`).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FolderSyncLockNaming.kt` (new), `kmp/src/commonTest/kotlin/dev/stapler/stelekit/platform/FolderSyncLockNamingTest.kt` (new)

##### Task 1.2.1a: Implement `FolderSyncLockNaming` (~5 min)
- New `object FolderSyncLockNaming` in commonMain with `pollLockNameFor(graphId): String = "stele-folder-sync-poll-$graphId"` and `writeLockNameFor(graphId, repoRelativePath): String = "stele-folder-sync-write-$graphId-${sanitize(repoRelativePath)}"`, reusing `GitWriteLockNaming`'s `UNSAFE_CHARS`/`DASH_RUN` sanitization approach (copy the two regexes, or extract them to a shared `LockNameSanitizer` if a third consumer appears later — not required for this project).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FolderSyncLockNaming.kt`

##### Task 1.2.1b: `FolderSyncLockNamingTest` (~5 min)
- Table-driven test asserting determinism, path-based distinctness, and non-collision with `GitWriteLockNaming` prefixes, mirroring `kmp/src/commonTest/kotlin/dev/stapler/stelekit/git/GitWriteLockNamingTest.kt`'s structure.
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/platform/FolderSyncLockNamingTest.kt`

### Epic 1.3: `HostAccessState` type and `FileSystem` interface extension
**Goal**: A generic, JVM/Android/iOS-zero-cost way for `App.kt` and other commonMain UI to query
host-directory permission state without downcasting to the wasmJs actual, per
`research/architecture.md` §1.3.

#### Story 1.3.1: `HostAccessState` sealed interface
**As a** UI developer wiring the resume-access banner (Phase 2), **I want** an exhaustive,
type-safe representation of host-directory access state, **so that** every UI branch (silent
resume, one-click prompt, denied, disconnected) is compile-time-checked.
**Acceptance Criteria**:
- `HostAccessState` is a sealed interface with exactly five variants: `NotApplicable`, `Granted`, `PromptNeeded`, `Denied`, `Disconnected`.
  - *Given* a `when (state: HostAccessState)` expression in new UI code, *When* the compiler checks exhaustiveness, *Then* omitting any of the five variants (without an `else`) fails to compile.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/HostAccessState.kt` (new)

##### Task 1.3.1a: Define `HostAccessState` (~2 min)
- `sealed interface HostAccessState { data object NotApplicable : HostAccessState; data object Granted : HostAccessState; data object PromptNeeded : HostAccessState; data object Denied : HostAccessState; data class Disconnected(val reason: String) : HostAccessState }`. KDoc each variant per the Domain Glossary definitions above (`NotApplicable` = no host directory ever connected for this graph; `Disconnected` = handle went stale, e.g. `NotFoundError` from an external move/delete — distinct from `Denied`, matching `research/ux.md`'s "reconnect vs. conflict" distinction).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/HostAccessState.kt`

#### Story 1.3.2: `FileSystem.hostDirectoryAccessState` default no-op method
**As a** desktop/Android/iOS maintainer, **I want** this new interface method to cost nothing on
platforms without host-directory sync, **so that** JVM/Android/iOS are unaffected by this project.
**Acceptance Criteria**:
- `FileSystem` gains `suspend fun hostDirectoryAccessState(graphPath: String): HostAccessState = HostAccessState.NotApplicable`, overridden only in the wasmJs actual (Phase 2).
  - *Given* the JVM `FileSystem` implementation (no override added), *When* `hostDirectoryAccessState("/any/path")` is called, *Then* it returns `HostAccessState.NotApplicable` with no I/O performed.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt` (modified)

##### Task 1.3.2a: Add the default method to `FileSystem.kt` (~2 min)
- Insert `suspend fun hostDirectoryAccessState(graphPath: String): HostAccessState = HostAccessState.NotApplicable` next to the existing SAF write-behind methods (`FileSystem.kt:73-145` block), with a KDoc cross-referencing `HostAccessState` and this project.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/FileSystem.kt`

### Epic 1.4: `ReconciliationOutcome` type and pure classifier
**Goal**: The data-loss-prevention core of this project's Critical Finding, expressed as a type-safe,
independently unit-testable pure function before any wasmJs interop is wired to it.

#### Story 1.4.1: `ReconciliationOutcome` sealed interface + `classifyReconciliation`
**As a** developer implementing the reconciliation pass (Phase 3), **I want** the four-way
classification (identical / conflict / host-only-new / browser-only-needs-push) expressed as a
pure, exhaustively-typed function, **so that** the highest-severity finding in this project's
research is tested independently of any browser API mocking.
**Acceptance Criteria**:
- `classifyReconciliation(hostContent: String?, cacheContent: String?): ReconciliationOutcome` implements exactly the four-row table from `research/architecture.md` §5.2.
  - *Given* `hostContent = "# Foo\nbar"` and `cacheContent = "# Foo\nbar"` (byte-identical), *When* `classifyReconciliation(hostContent, cacheContent)` is called, *Then* it returns `ReconciliationOutcome.Identical`.
  - *Given* `hostContent = "# Foo\nedited on disk"` and `cacheContent = "# Foo\nedited in browser"` (both non-null, different), *When* called, *Then* it returns `ReconciliationOutcome.HostChangedConflict`.
  - *Given* `hostContent = "# NewPage"` and `cacheContent = null` (never imported), *When* called, *Then* it returns `ReconciliationOutcome.HostOnlyNew`.
  - *Given* `hostContent = null` (file does not exist on host) and `cacheContent = "# Created in browser"`, *When* called, *Then* it returns `ReconciliationOutcome.BrowserOnlyNeedsPush`.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/HostReconciliation.kt` (new), `kmp/src/commonTest/kotlin/dev/stapler/stelekit/platform/HostReconciliationTest.kt` (new)

##### Task 1.4.1a: Define `ReconciliationOutcome` (~2 min)
- `sealed interface ReconciliationOutcome { data object Identical : ReconciliationOutcome; data object HostChangedConflict : ReconciliationOutcome; data object HostOnlyNew : ReconciliationOutcome; data object BrowserOnlyNeedsPush : ReconciliationOutcome }` with KDoc mapping each variant to its required action (no-op / `GraphLoader.emitExternalFileChange` / import-as-new / enqueue `hostWritePending`), cross-referencing `research/architecture.md` §5.2's table.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/HostReconciliation.kt`

##### Task 1.4.1b: Implement `classifyReconciliation` (~3 min)
- Pure function in the same file: `null`/`null` case (neither side has the file) is unreachable by construction at call sites (the reconciliation walk only calls this for paths present on at least one side) — document this precondition in KDoc rather than adding a fifth variant for it.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/HostReconciliation.kt`

##### Task 1.4.1c: `HostReconciliationTest` covering all four branches (~5 min)
- Four test cases matching the acceptance criteria above, plus one edge case: `hostContent`/`cacheContent` both empty strings (not null) classify as `Identical`, not `HostChangedConflict` (guards against an off-by-one between "empty file" and "no file").
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/platform/HostReconciliationTest.kt`

##### Task 1.4.1d: Define `HostWritePayload` (~2 min)
- `sealed interface HostWritePayload { data class Text(val content: String) : HostWritePayload; data class Bytes(val data: ByteArray) : HostWritePayload; data object Delete : HostWritePayload }` in a new commonMain file, alongside `HostAccessState`/`ReconciliationOutcome` rather than deferred to a Phase 4 footnote — Task 4.2.2a's `flushHostWrite` dispatches on it exhaustively (compile-time-enforced, matching this project's `ReconciliationOutcome` rationale in the Pattern Decisions table).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/HostWritePayload.kt` (new)

##### Task 1.4.1e: `classifyReconciliationBytes` — bytes-aware sibling for `.md.stek` paths (~3 min)
- **Added per adversarial-review.md Blocker 4**: `classifyReconciliation(hostContent: String?, cacheContent: String?)` is `String?`-typed and would either throw or silently corrupt encrypted `.md.stek` content if used directly on paranoid-mode bytes (treating ciphertext as UTF-8). Add `fun classifyReconciliationBytes(hostBytes: ByteArray?, cacheBytes: ByteArray?): ReconciliationOutcome` in the same file, implementing the identical four-row table but comparing with `ByteArray.contentEquals` instead of `String.equals` (`Identical` when both non-null and `contentEquals`; `HostChangedConflict` when both non-null and not `contentEquals`; `HostOnlyNew`/`BrowserOnlyNeedsPush` follow the same null-side rule as the string version). Both functions delegate their shared decision structure to a private generic helper (`classifyByEquality(hostPresent: Boolean, cachePresent: Boolean, equal: Boolean): ReconciliationOutcome`) so the four-way logic is defined exactly once, not duplicated between the two public entry points.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/platform/HostReconciliation.kt`

##### Task 1.4.1f: `classifyReconciliationBytes` tests (~4 min)
- Four test cases mirroring Task 1.4.1c's coverage but with `ByteArray` fixtures (including a byte-identical-but-different-array-instance case, to confirm `contentEquals` semantics are used, not reference equality), plus one test confirming `classifyReconciliation` and `classifyReconciliationBytes` agree on outcome (not necessarily on the underlying value) for an equivalent text-vs-UTF8-encoded-bytes scenario.
- Files: `kmp/src/commonTest/kotlin/dev/stapler/stelekit/platform/HostReconciliationTest.kt`

### Epic 1.5: Browser interop primitives — IndexedDB, permissions, `FileSystemObserver`
**Goal**: All new `js()`/`.await()` glue this project needs, grouped in one new file matching
`OpfsInterop.kt`'s established idiom, per ADR-001.

#### Story 1.5.1: `HostDirectoryInterop.kt` — IndexedDB open/put/get
**As a** developer implementing handle persistence (Phase 2), **I want** the three IndexedDB
primitives this feature needs, in the same hand-rolled style as `OpfsInterop.kt`, **so that** no
new interop-wrapper dependency is introduced (ADR-001).
**Acceptance Criteria**:
- `idbOpenPromise`, `idbPutHandlePromise`, `idbGetHandlePromise` exist as private top-level `js()` functions, paired with `internal suspend fun` `.await()` callers, matching the exact shape sketched in `research/stack.md` §2.
  - *Given* a fresh browser profile with no `stelekit-host-handles` IndexedDB database, *When* `idbOpenHandleDb()` is called, *Then* it creates the database and object store on `onupgradeneeded` and resolves without throwing.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectoryInterop.kt` (new)

##### Task 1.5.1a: IndexedDB open/put/get functions (~5 min)
- Port the three functions sketched in `research/stack.md` §2 (`idbOpenPromise`, `idbPutHandlePromise`, `idbGetHandlePromise`) into `HostDirectoryInterop.kt`, plus `internal suspend fun idbOpenHandleDb(): JsAny`, `internal suspend fun idbPutHandle(db: JsAny, key: String, handle: JsAny)`, `internal suspend fun idbGetHandle(db: JsAny, key: String): JsAny?`, each wrapped in `try/catch (e: Throwable)` returning `null`/rethrowing per the existing `OpfsInterop.kt` convention (read paths return null on failure, write paths log-and-return per `opfsWriteFile`'s pattern).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectoryInterop.kt`

#### Story 1.5.2: `HostHandleEnvelope` serialization
**As a** developer persisting handles, **I want** a small metadata envelope alongside the raw
handle, **so that** debugging/display has `graphId`/`dirName`/`storedAtMillis` without deserializing
the opaque handle object.
**Acceptance Criteria**:
- `HostHandleEnvelope(graphId: String, dirName: String, storedAtMillis: Long)` is `@Serializable`.
  - *Given* `HostHandleEnvelope(graphId = "a1b2c3d4", dirName = "my-notes", storedAtMillis = 1752500000000)`, *When* encoded via `gitApiJson.encodeToString` and decoded back, *Then* the round-tripped value equals the original.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/HostHandleEnvelope.kt` (new — colocated with `DirtySetMarker.kt` since both are small persisted-JSON envelopes, though this one is IndexedDB- not OPFS-backed)

##### Task 1.5.2a: Define `HostHandleEnvelope` (~2 min)
- `@Serializable data class HostHandleEnvelope(val graphId: String, val dirName: String, val storedAtMillis: Long)`, reusing the existing `gitApiJson` `Json` instance from `git/model/DirtySetMarker.kt`'s package for encode/decode (no new `Json` configuration needed).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/git/model/HostHandleEnvelope.kt`

#### Story 1.5.3: Permission query/request interop
**As a** developer implementing the resume-access flow (Phase 2), **I want** `queryPermission()`/
`requestPermission()` wrappers, **so that** the one-click resume UX has a Kotlin-side entry point.
**Acceptance Criteria**:
- `internal suspend fun queryHandlePermission(handle: JsAny, mode: String = "readwrite"): String` and `internal suspend fun requestHandlePermission(handle: JsAny, mode: String = "readwrite"): String` return the raw `"granted"`/`"prompt"`/`"denied"` string.
  - *Given* a `HostDirectoryHandle` freshly rehydrated from IndexedDB with no prior grant in this session, *When* `queryHandlePermission(handle)` is called, *Then* it returns `"prompt"` (per `research/pitfalls.md` §1.1 — a rehydrated handle very commonly reports `'prompt'`).
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectoryInterop.kt` (modified)

##### Task 1.5.3a: Permission interop functions (~3 min)
- Add `queryPermissionPromise`/`requestPermissionPromise` `js()` functions (from `research/stack.md` §2's sketch) plus `.await()`-wrapping suspend callers, both `try/catch (e: Throwable)` returning `"denied"` on any thrown error (fail closed, not open).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectoryInterop.kt`

#### Story 1.5.4: `FileSystemObserver` construction + observe interop
**As a** developer implementing the Phase 5 fast path, **I want** the `FileSystemObserver` wrapper
functions available now, **so that** Phase 5 only needs to wire them, not write new `js()`.
**Acceptance Criteria**:
- `internal fun fileSystemObserverSupported(): Boolean` feature-detects `'FileSystemObserver' in self`; `internal fun newFileSystemObserver(callback: (JsAny) -> Unit): JsAny` and `internal suspend fun observeHandle(observer: JsAny, handle: JsAny, recursive: Boolean = true): Unit` exist.
  - *Given* a Chrome 133+ browser, *When* `fileSystemObserverSupported()` is called, *Then* it returns `true`.
  - *Given* a browser without `FileSystemObserver` (feature-detect returns `false`), *When* Phase 5 code checks this flag before constructing an observer, *Then* `newFileSystemObserver` is never called (verified by Phase 5's tests, not this story's).
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectoryInterop.kt` (modified)

##### Task 1.5.4a: `FileSystemObserver` interop functions (~5 min)
- Port the `newFileSystemObserver`/`observePromise` sketch from `research/stack.md` §2 plus a `fileSystemObserverSupported(): Boolean = js("typeof FileSystemObserver === 'function'")` feature-detect (mirroring `showDirectoryPickerSupported()`'s existing idiom in `OpfsInterop.kt:5`), and small accessors for `FileSystemChangeRecord` fields (`changeRecordType(record): String`, `changeRecordRelativePath(record): List<String>` via `relativePathComponents`).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectoryInterop.kt`

#### Story 1.5.5: `File.lastModified`/`size` and visibility-visible interop
**As a** developer implementing the poller (Phase 5), **I want** cheap mtime/size accessors and the
inverse of the existing `jsVisibilityHiddenPromise()`, **so that** the poller has a fast pre-filter
before falling back to content hashing (per `research/pitfalls.md` §4).
**Acceptance Criteria**:
- `internal fun fileLastModified(file: JsAny): Long` and `internal fun fileSize(file: JsAny): Long` return `File.lastModified`/`File.size`.
  - *Given* a `File` object from `handle.getFile()`, *When* `fileLastModified(file)` is called, *Then* it returns the same millisecond epoch value `file.lastModified` would in JS.
- `internal fun jsVisibilityVisiblePromise(): kotlin.js.Promise<JsAny?>` resolves the instant `document.visibilityState` becomes `"visible"` — the inverse of `OpfsInterop.kt:163`'s `jsVisibilityHiddenPromise()`.
  - *Given* a backgrounded tab (`visibilityState == "hidden"`), *When* the tab regains focus (`visibilityState` becomes `"visible"`), *Then* the promise resolves within one event-loop tick.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectoryInterop.kt` (modified)

##### Task 1.5.5a: mtime/size accessors + visibility-visible promise (~5 min)
- Add `fileLastModified`/`fileSize` `js()` accessors, and `jsVisibilityVisiblePromise()` as a near-copy of `OpfsInterop.kt:163-180`'s `jsVisibilityHiddenPromise()` with the `visibilityState === 'hidden'` check flipped to `=== 'visible'`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectoryInterop.kt`

#### Story 1.5.6: `navigator.storage.persist()` interop
**As a** web user with a host directory attached, **I want** the app to make a best-effort request
that my OPFS mirror not be LRU-evicted under storage pressure, **so that** the "OPFS is a disposable
cache once a host directory is attached" risk (`research/pitfalls.md` §1.3) is mitigated proactively,
not just recovered from after the fact. **Added per adversarial-review.md Blocker 1** — the plan
previously had no `storage.persist()` call anywhere.
**Acceptance Criteria**:
- `internal suspend fun requestStoragePersistence(): Boolean` wraps `navigator.storage.persist()` and returns whether the browser granted it, swallowing any thrown/rejected error as `false` (best-effort — a denial or unsupported browser must never fail the caller).
  - *Given* a browser that supports the Storage API, *When* `requestStoragePersistence()` is called, *Then* it resolves to `true` or `false` (the browser's actual grant decision) without throwing, even if the underlying permission is denied.
  - *Given* a browser without `navigator.storage.persist` at all (feature-detected via `'persist' in navigator.storage`), *When* `requestStoragePersistence()` is called, *Then* it returns `false` immediately with no thrown error.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectoryInterop.kt` (modified)

##### Task 1.5.6a: `requestStoragePersistence` interop function (~4 min)
- Add a `jsStoragePersistPromise(): Promise<JsBoolean>` `js()` function (feature-detected via `typeof navigator.storage?.persist === 'function'`, mirroring `showDirectoryPickerSupported()`'s existing idiom) plus the `.await()`-wrapping `internal suspend fun requestStoragePersistence(): Boolean`, wrapped in `try/catch (e: Throwable) { println("[SteleKit] storage.persist() request failed: ${e.message}"); false }` per this file's existing failure-tolerant convention.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectoryInterop.kt`

### Epic 1.6: Extract the `HostDirectorySync` collaborator (SRP)
**Goal**: Resolve architecture-review.md's Blocker 1 by giving Phase 2–7 a dedicated home, sized and
scoped independently of `PlatformFileSystem`'s three existing responsibilities (OPFS cache
mirroring, git dirty-set tracking, GitHub raw-fetch fallback). Every subsequent phase in this plan
builds *inside* `HostDirectorySync`, not by adding fields/methods to `PlatformFileSystem`. This
mirrors how `FileRegistry`/`GraphFileWatcher` are already kept separate from `GraphLoader` on the
JVM/Android side rather than folded into the loader.

#### Story 1.6.1: `HostDirectorySync` class shell + `CacheAccess` injection interface
**As a** SteleKit maintainer, **I want** host-directory-sync's ~20+ new fields/methods to live on
their own class from the start, **so that** `PlatformFileSystem` never grows a fourth
responsibility and each later phase's tests exercise `HostDirectorySync` directly instead of a
315-line-and-growing God Object.
**Acceptance Criteria**:
- `HostDirectorySync` is a standalone `class` (not a `PlatformFileSystem` inner/nested class) in its own file, constructor-injected with `graphId: String`, a `CacheAccess` implementation, and a `CoroutineScope`.
  - *Given* `HostDirectorySync(graphId = "g", cacheAccess = fakeCacheAccess, scope = testScope)` constructed directly in a test with no `PlatformFileSystem` instance involved, *When* any Phase 2–7 method on it is called, *Then* it operates correctly using only the injected `CacheAccess` and scope — proving the class has no hidden dependency back onto `PlatformFileSystem`.
- `PlatformFileSystem` holds exactly one `val hostDirectorySync: HostDirectorySync` field (constructed in `init`), and implements `HostDirectorySync.CacheAccess` privately to satisfy the constructor injection.
  - *Given* `PlatformFileSystem`'s source after this task, *When* it is inspected, *Then* it contains no new instance fields for `hostDirHandle`, `hostWritePending`, `hostModTimes`, or any other Phase 2–7 concept from the Domain Glossary — those exist only on `hostDirectorySync`.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (new), `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` (modified)

##### Task 1.6.1a: Define `HostDirectorySync.CacheAccess` and the class shell (~6 min)
- In the new file: `interface CacheAccess { fun get(path: String): String?; fun set(path: String, content: String); fun remove(path: String); fun getBytes(path: String): ByteArray?; fun setBytes(path: String, data: ByteArray); fun removeBytes(path: String); fun keysUnder(opfsPath: String): Set<String>; fun writeOpfsMirror(path: String, content: String); fun writeOpfsMirrorBytes(path: String, data: ByteArray) }` nested inside (or alongside) `class HostDirectorySync(private val graphId: String, private val cacheAccess: CacheAccess, private val scope: CoroutineScope)`. No Phase 2–7 fields yet — this task only establishes the shell and injection seam later stories build onto. `keysUnder` (used by Task 3.2.1b) returns the subset of `cache` keys under a given OPFS path prefix. The `*Bytes`/`writeOpfsMirrorBytes` methods (added per adversarial-review.md Blocker 4) mirror the text methods against `PlatformFileSystem`'s existing `bytesCache`, giving reconciliation (Epic 3.2) and the poller (Epic 5.1) a way to read/write `.md.stek` paranoid-mode content without ever routing encrypted bytes through the `String`-typed methods.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 1.6.1b: Compose `HostDirectorySync` into `PlatformFileSystem` (~5 min)
- Add `private val hostDirectorySync = HostDirectorySync(graphId = graphId, cacheAccess = object : HostDirectorySync.CacheAccess { override fun get(path: String) = cache[path]; override fun set(path: String, content: String) { cache[path] = content }; override fun remove(path: String) { cache.remove(path) }; override fun getBytes(path: String) = bytesCache[path]; override fun setBytes(path: String, data: ByteArray) { bytesCache[path] = data }; override fun removeBytes(path: String) { bytesCache.remove(path) }; override fun keysUnder(opfsPath: String) = (cache.keys + bytesCache.keys).filter { it.startsWith("$opfsPath/") }.toSet(); override fun writeOpfsMirror(path: String, content: String) { scope.launch { opfsWriteFile(path, content) } }; override fun writeOpfsMirrorBytes(path: String, data: ByteArray) { scope.launch { opfsWriteFileBytes(path, data) } } }, scope = scope)` to `PlatformFileSystem`, placed alongside the existing `dirtySet`/`graphId` fields. Expose it as `val hostDirectorySync: HostDirectorySync` (not `private`) so `Main.kt` and UI code can call its non-`FileSystem`-interface entry points (`reconnectHostDirectory`, `requestHostDirectoryAccess`, `connectHostDirectory`, its `StateFlow`s) directly, without `PlatformFileSystem` needing to re-expose every one of them as a passthrough.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

##### Task 1.6.1c: `HostDirectorySyncConstructionTest` — proves independence from `PlatformFileSystem` (~4 min)
- A wasmJsTest instantiating `HostDirectorySync` directly against a fake `CacheAccess` (no real `PlatformFileSystem`), confirming the class compiles and constructs standalone. This is the regression guard for Blocker 1: if a future change makes `HostDirectorySync` require a live `PlatformFileSystem` reference, this test's fake-only construction fails.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncConstructionTest.kt` (new)

### Epic 1.7: OPFS-write durability fix — await-before-durable, plus a best-effort teardown diagnostic (SCOPE EXPANSION, Option A)
**Goal**: Close the pre-existing `writeFile`/`writeFileBytes` OPFS-write durability gap at the root,
per an explicit user-approved scope expansion (supersedes this plan's original Option-B "document and
accept" decision — see the "OPFS-write durability" Pattern Decisions row above).
`PlatformFileSystem.writeFile`/`writeFileBytes` (`PlatformFileSystem.kt:267-305`) currently set
`cache`/`bytesCache` synchronously but persist to OPFS via an unawaited
`scope.launch { opfsWriteFile(...) }`; a hard crash inside that write's flight time silently loses
the edit, and `runHostReconciliation` (Epic 3.2) cannot detect it because `cache` itself never
diverges from a stale reload. Story 1.7.1 is the actual fix: it makes that OPFS write awaited before
Epic 4's host write-through queue is allowed to treat the edit as durable, closing the crash window
for this feature's host-sync path at the source. Story 1.7.2 adds a `beforeunload`/`pagehide` hook as
a diagnostic complement, not an independent mitigation — it only logs any still-in-flight writes at
teardown (see its own Acceptance Criteria); it does not attempt to force or await their completion,
so it closes no additional window beyond what Story 1.7.1 already closes. Neither story claims to
close the true instant-hard-kill case (OOM, force-quit), which is inherent to the browser execution
model and unclosable by any client-side JS mechanism.

#### Story 1.7.1: Track in-flight OPFS writes and await them before host write-through enqueue
**As a** SteleKit maintainer, **I want** `writeFile`/`writeFileBytes` to track their OPFS-persisting
write as an awaitable operation rather than pure fire-and-forget, **so that** Epic 4.3's host
write-through enqueue can await the OPFS write actually landing before treating the edit as safe to
push to the host directory, closing the crash window the Critical Finding and adversarial-review.md's
sole remaining Blocker identified.
**Decision**: `writeFile`/`writeFileBytes` remain synchronous, non-blocking, `Boolean`-returning
`FileSystem`-interface methods — the UI-thread-stall concern that already rejected a fully
synchronous host write (Pattern Decisions, "Write-through queue" row) applies equally here, so
`writeFile` itself is **not** made `suspend` and does **not** block its caller. Instead, each call's
`scope.launch { opfsWriteFile(...) }` is wrapped so its completion is independently observable: a
`private val opfsWriteInFlight = mutableMapOf<String, Deferred<Unit>>()` on `PlatformFileSystem`,
keyed by path, is populated when the write launches and self-clears when it resolves.
`HostDirectorySync.scheduleHostWriteThrough` (Task 4.3.1a/4.3.1b's call sites) awaits that path's
`Deferred` (via a new `CacheAccess.opfsWriteDeferredFor(path)` accessor) before adding the path to
`hostWritePending` — so a host push can never enqueue browser content that has not actually reached
OPFS yet.
**Acceptance Criteria**:
- `writeFile(path, content)` still returns synchronously (unchanged signature/behavior for every
  existing caller), but records a `Deferred` for `path`'s in-flight OPFS write, resolved only once
  `opfsWriteFile` actually completes.
  - *Given* `writeFile("/stelekit/g/pages/Foo.md", "content")` is called, *When* the call returns,
    *Then* `cache`/`bytesCache` and `dirtySet` already reflect the write (unchanged from today), and
    a `Deferred` for that path is retrievable and not yet completed (the OPFS write is still in
    flight).
- `HostDirectorySync.scheduleHostWriteThrough` awaits that `Deferred` before adding the path to
  `hostWritePending`.
  - *Given* `hostDirHandle` set and a slow-but-eventually-resolving `opfsWriteFile` test double
    (resolves after a short delay, not never), *When* `writeFile(...)` is called followed immediately
    by the existing one-line `scheduleHostWriteThrough` delegation, *Then* `hostWritePending` does
    not contain the path until the OPFS write's `Deferred` resolves — the enqueue call itself waits
    for it rather than racing ahead of it, and once the delay elapses, the path **is** present (the
    edit is not lost or forgotten during the wait).
  - *Given* an `opfsWriteFile` test double that throws instead of resolving, *When* `writeFile(...)`
    is called, *Then* the path never appears in `hostWritePending` — there is nothing durable to push
    to the host if the OPFS write itself failed, so the enqueue is correctly skipped rather than
    attempted against absent content.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` (modified
— `writeFile`/`writeFileBytes`), `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified — `scheduleHostWriteThrough`)

##### Task 1.7.1a: Add per-path in-flight `Deferred` tracking to `writeFile`/`writeFileBytes` (~6 min)
- Add `private val opfsWriteInFlight = mutableMapOf<String, Deferred<Unit>>()` to
  `PlatformFileSystem`. In `writeFile`/`writeFileBytes`, replace the bare
  `scope.launch { opfsWriteFile(...) }` with
  `opfsWriteInFlight[path] = scope.async { try { opfsWriteFile(path, content) } finally { opfsWriteInFlight.remove(path) } }`
  (bytes equivalent for `writeFileBytes`), so the map always holds the currently-in-flight write for
  a path and self-cleans on completion (success or failure). Add an internal accessor
  `fun opfsWriteDeferredFor(path: String): Deferred<Unit>? = opfsWriteInFlight[path]`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

##### Task 1.7.1b: `scheduleHostWriteThrough` awaits the in-flight OPFS write first (~4 min)
- At the top of `HostDirectorySync.scheduleHostWriteThrough`, before adding `path` to
  `hostWritePending`, call the injected `CacheAccess.opfsWriteDeferredFor(path)` (Task 1.7.1c); if
  non-null, `.await()` it (wrapped `try/catch (e: CancellationException) { throw e } catch (e: Throwable) { return }`
  — if the OPFS write itself failed, skip the enqueue rather than push stale/absent content). If no
  `Deferred` is present (write already settled, or this path was never freshly written this
  session), proceed immediately as before — this is the common case and adds no latency.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 1.7.1c: Wire the accessor into `HostDirectorySync.CacheAccess` (~2 min)
- Add `fun opfsWriteDeferredFor(path: String): Deferred<Unit>?` to the `CacheAccess` interface
  (Task 1.6.1a's shell) and its `PlatformFileSystem` implementation (Task 1.6.1b), following the same
  injection pattern already used for `writeOpfsMirror`/`writeOpfsMirrorBytes`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

#### Story 1.7.2: `beforeunload`/`pagehide`-triggered best-effort teardown diagnostic for in-flight OPFS writes
**As a** SteleKit maintainer, **I want** visibility into any OPFS write still in flight at tab
teardown, **so that** a real-world crash-window occurrence (rare, since Story 1.7.1 already closes
the window this feature's write-through path depends on) is at least observable in logs rather than
silent, matching the *shape* of the existing `.stele-dirty-set.json` marker-flush hook already in
`PlatformFileSystem`'s `init{}` block (an event-driven teardown hook) — **not** its behavior: unlike
the marker-flush loop, which performs a real write attempt, this hook only logs; it does not attempt
to force or await completion of any in-flight write, and closes no additional crash window beyond
what Story 1.7.1 already closes.
**Acceptance Criteria**:
- A new `jsPageHidePromise()` interop function (mirroring `jsVisibilityHiddenPromise()`'s shape)
  resolves on `pagehide`/`beforeunload`; `PlatformFileSystem`'s `init{}` gains a second loop,
  alongside the existing marker-flush loop, that awaits it and does a best-effort log of any
  still-in-flight `opfsWriteInFlight` entries — applying unconditionally (platform-wide), not gated
  on a host directory being connected.
  - *Given* one or more entries in `opfsWriteInFlight` when `pagehide` fires, *When* the new loop's
    handler runs, *Then* it logs the in-flight path count via
    `println("[SteleKit] pagehide: N OPFS writes still in flight")` and does not throw or block the
    unload (best-effort — browsers do not reliably await async work after these events fire, matching
    the existing marker-flush loop's own documented caveat).
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` (modified), `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/OpfsInterop.kt` (modified — new interop function)

##### Task 1.7.2a: Add `jsPageHidePromise()` interop (~3 min)
- New `js()` function in `OpfsInterop.kt`, near `jsVisibilityHiddenPromise()` (`OpfsInterop.kt:163-180`), resolving on the earlier of `pagehide`/`beforeunload` firing once.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/OpfsInterop.kt`

##### Task 1.7.2b: Wire the flush loop into `PlatformFileSystem`'s `init{}` (~3 min)
- Add a second `scope.launch { while (true) { jsPageHidePromise().await<JsAny?>(); ... log opfsWriteInFlight.size ... } }` loop alongside the existing marker-flush loop (`PlatformFileSystem.kt:44-58`), documented as best-effort defense in depth, not a guarantee — the actual durability fix is Story 1.7.1's await-before-enqueue, not this loop.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

#### Story 1.7.3: Dedicated test of the awaited-write mechanism itself
**As a** SteleKit maintainer, **I want** the awaited-write behavior directly tested on
`PlatformFileSystem`, not just observed indirectly through `HostDirectorySync`, **so that** this
epic's own new contract is verified independently.
**Acceptance Criteria**:
- A test using a slow-but-eventually-resolving `opfsWriteFile` double asserts `opfsWriteDeferredFor(path)` is non-null and pending immediately after `writeFile` returns, and completes once the double resolves.
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/PlatformFileSystemOpfsWriteDurabilityTest.kt` (new)

##### Task 1.7.3a: `PlatformFileSystemOpfsWriteDurabilityTest` (~5 min)
- Test per the acceptance criterion above, plus a regression check that `writeFile`'s return value/timing is otherwise unchanged (still synchronous, still returns `true` immediately, never awaits inline).
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/PlatformFileSystemOpfsWriteDurabilityTest.kt`

---

## Phase 2: Directory Handle Retention & Session Resume

### Epic 2.1: Retain the handle at pick time
**Goal**: Close the gap identified in `research/architecture.md` §0 — the `dirHandle` local
variable in `pickDirectoryAsync()` currently goes out of scope; this project adds the first field
that retains it, on `HostDirectorySync` (Epic 1.6), not on `PlatformFileSystem`.

#### Story 2.1.1: `HostDirectorySync` retains `hostDirHandle`/`hostGraphOpfsPath` and persists to IndexedDB
**As a** web user, **I want** the app to remember the folder I picked, **so that** it can write
through to it and doesn't need me to re-pick every time I edit.
**Acceptance Criteria**:
- After `PlatformFileSystem.pickDirectoryAsync()` succeeds and hands the picked handle to `hostDirectorySync.attachFreshHandle(dirHandle, opfsPath)`, `HostDirectorySync.hostDirHandle` is non-null and `hostGraphOpfsPath` equals the returned OPFS path.
  - *Given* a user with no prior graph, *When* `pickDirectoryAsync()` is called and the browser's native picker resolves with a directory named `"my-notes"`, *Then* `hostDirectorySync.hostDirHandle` is set to that `FileSystemDirectoryHandle` and `hostDirectorySync.hostGraphOpfsPath == "/stelekit/my-notes"`.
- The handle is persisted to IndexedDB keyed by `graphId` (via `GraphManager.graphIdFromPath`), wrapped in a `HostHandleEnvelope`.
  - *Given* the same pick as above with `graphId = GraphManager.graphIdFromPath("/stelekit/my-notes")`, *When* `pickDirectoryAsync()` completes, *Then* `idbGetHandle(db, graphId)` (called independently in a test) returns a non-null handle.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified), `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` (modified — one call site)

##### Task 2.1.1a: Add `hostDirHandle`/`hostGraphOpfsPath` instance fields to `HostDirectorySync` (~2 min)
- Add `private var hostDirHandle: JsAny? = null` and `private var hostGraphOpfsPath: String? = null` to `HostDirectorySync` (Epic 1.6's shell), per `research/architecture.md` §2's "instance fields, not another companion-object convention" guidance — applied here to the extracted collaborator instead of `PlatformFileSystem`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 2.1.1b: `attachFreshHandle` sets the fields, called from `pickDirectoryAsync` (~3 min)
- Add `suspend fun attachFreshHandle(dirHandle: JsAny, opfsPath: String)` to `HostDirectorySync` that sets `hostDirHandle`/`hostGraphOpfsPath` and calls Task 2.1.1c's persistence. In `PlatformFileSystem.pickDirectoryAsync`, after the existing `importUserDirToCache(dirHandle, opfsPath)` call (`PlatformFileSystem.kt:331`), add exactly one new line: `hostDirectorySync.attachFreshHandle(dirHandle, opfsPath)`. This is the only line Epic 2.1 adds to `PlatformFileSystem.kt` itself.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

##### Task 2.1.1c: Persist to IndexedDB (~4 min)
- Inside `attachFreshHandle`, call a new private `suspend fun persistHostHandle(graphId: String, dirName: String, handle: JsAny)` (also on `HostDirectorySync`) that opens the DB (`idbOpenHandleDb`), builds a `HostHandleEnvelope`, and calls `idbPutHandle`. Wrap in `try/catch (e: Throwable)` — a persistence failure must not fail the pick itself (log and continue, matching the existing `println("[SteleKit] ...")` failure-tolerant convention).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

### Epic 2.2: Resume access on startup
**Goal**: "At most one click to resume access" (requirements.md Success Metrics), driven from
`Main.kt`'s existing sequential startup steps.

#### Story 2.2.1: `reconnectHostDirectory(graphId)` — silent resume path, always reconciling
**As a** returning web user, **I want** the app to silently reattach my previously-picked folder
when the browser still trusts it, **so that** I never see a prompt if I don't have to — **and I want
that reattachment to be safe against whatever happened while the tab was closed** (host-side edits,
unflushed browser edits, an evicted OPFS mirror), not just an unconditional resume.
**Decision (supersedes the plan's original "just set the handle and start the loops" draft, per
adversarial-review.md Blocker 3, further revised by Epic 3.4 per pre-mortem.md P1 #1)**:
`reconnectHostDirectory`'s `"granted"` branch now calls `runHostReconciliation` (Epic 3.2) — the same
call `connectHostDirectory` (Epic 3.1) makes — and **launches it non-blocking** (`scope.launch`, Task
3.4.2a) rather than awaiting it, before starting the Phase 4/5 write-through/poll loops. Ordinary
session resumption ("reopen the app") is the far more common trigger for host/cache divergence than
the one-time `connectHostDirectory` entry point, and per the previous section's Pattern Decisions
rows, this same call is also the mechanism that self-heals an OPFS-eviction-emptied `cache` (Blocker
1) and recovers a crash-lost `hostWritePending` queue (Blocker 2) — three findings, one remediation.
Making it non-blocking (Epic 3.4) closes a fourth finding — pre-mortem.md P1 #1's concern that an
unconditional, awaited, full-content-read reconciliation on every reconnect risks making app startup
noticeably slower on an 8,000+-page graph — without weakening the other three: reconciliation still
runs unconditionally on every reconnect, it just no longer holds up `Main.kt`'s startup sequence
while it does. This introduces a build-order dependency: **Epic 3.2 (`runHostReconciliation`) and
Epic 3.4 (mtime/size pre-filter + non-blocking launch) must exist before this story is implemented**
— see the updated Dependency Visualization sequencing note above.
**Acceptance Criteria**:
- `reconnectHostDirectory(graphId)` looks up IndexedDB; if found and `queryHandlePermission` returns `"granted"`, sets `hostDirHandle`/`hostGraphOpfsPath`, **launches** `runHostReconciliation(handle, opfsPath)` non-blocking (Epic 3.4/Task 3.4.2a — the function itself does not await its completion), then starts the write-through/poll loops (Phases 4–5) — all with zero UI interruption and zero added startup latency (reconciliation's own outcomes route through the existing `onHostConflict`/`hostWritePending` machinery, applied asynchronously as they're discovered, not a blocking dialog and not a blocking startup step).
  - *Given* a graph previously connected via `pickDirectoryAsync()` in an earlier session, with the browser's underlying grant still active (same tab session never fully closed), and a host-side edit made to `pages/Foo.md` while the tab was closed, *When* `hostDirectorySync.reconnectHostDirectory(graphId)` runs at startup, *Then* `hostDirectorySync.hostDirHandle` is non-null, `hostDirectorySync.hostAccessStateFlow.value == HostAccessState.Granted`, no dialog/banner blocks startup, and `onHostConflict` was invoked for `pages/Foo.md` (or `cache` was updated directly, per whichever classification applies) rather than the host-side edit being silently dropped.
- If no handle is found in IndexedDB, resolves to `HostAccessState.NotApplicable` — identical to today's behavior, and `runHostReconciliation` is never called (nothing to reconcile).
  - *Given* a graph that has never had `pickDirectoryAsync()`/`connectHostDirectory()` called, *When* `reconnectHostDirectory(graphId)` runs, *Then* `hostDirHandle` stays `null` and `hostAccessStateFlow.value == HostAccessState.NotApplicable`.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified), `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt` (modified)

##### Task 2.2.1a: Implement `reconnectHostDirectory`, routed through `runHostReconciliation` (~7 min)
- New `suspend fun reconnectHostDirectory(graphId: String): HostAccessState` on `HostDirectorySync` (not `PlatformFileSystem` — this is a `HostDirectorySync`-only entry point, never part of the `FileSystem` interface contract): open IndexedDB, `idbGetHandle(db, graphId)`; if `null`, set `_hostAccessStateFlow.value = HostAccessState.NotApplicable` and return it; else `queryHandlePermission(handle)` → map `"granted"` to: set `hostDirHandle`/`hostGraphOpfsPath`, **launch** `scope.launch { runHostReconciliation(handle, opfsPath) }` non-blocking (Epic 3.2's method, Epic 3.4's non-blocking launch decision, Task 3.4.2a — implemented before this task per the updated build order), then start Phase 4/5 loops (forward-referenced; no-op stubs acceptable until those phases land) and return `Granted` without waiting on the launched reconciliation; `"prompt"`/`"denied"` to `PromptNeeded`/`Denied` without setting `hostDirHandle` yet (and without calling reconciliation — there's no handle to walk).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 2.2.1b: Add `hostAccessStateFlow` StateFlow (~2 min)
- `private val _hostAccessStateFlow = MutableStateFlow<HostAccessState>(HostAccessState.NotApplicable)`, `val hostAccessStateFlow: StateFlow<HostAccessState> = _hostAccessStateFlow.asStateFlow()` on `HostDirectorySync`, mirroring the existing `dirtyFileCountFlow` pattern (`PlatformFileSystem.kt:30-31`) but hosted on the new collaborator.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 2.2.1c: Wire `reconnectHostDirectory` into `Main.kt` startup (~3 min)
- After `opfsFileSystem.preload(opfsGraphPath)` (`Main.kt:128`), add `val hostState = opfsFileSystem.hostDirectorySync.reconnectHostDirectory(graphId)` as its own sequential step, matching the file's existing "config wiring → preload → driver → ..." step ordering (`research/architecture.md` §3.1). `Main.kt` calls `hostDirectorySync` directly, not a `PlatformFileSystem` passthrough method.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`

#### Story 2.2.2: `requestHostDirectoryAccess(graphId)` — one-click resume path
**As a** returning web user whose session-scoped grant expired, **I want** a single in-app click to
re-grant access, **so that** I never have to re-pick the folder from scratch.
**Acceptance Criteria**:
- `requestHostDirectoryAccess(graphId)`, called only from a real click handler, calls `requestHandlePermission` and on `"granted"` sets `hostDirHandle`/starts sync loops.
  - *Given* `hostAccessStateFlow.value == HostAccessState.PromptNeeded` and a user clicks the "Resume folder access" banner, *When* `hostDirectorySync.requestHostDirectoryAccess(graphId)` runs inside that click's coroutine, *Then* the browser's native permission prompt appears and, on the user clicking "Allow," `hostAccessStateFlow.value` becomes `HostAccessState.Granted`.
- Declining the browser prompt does not retry-loop; state becomes `Denied` and stays until the user clicks again.
  - *Given* the same setup, *When* the user clicks "Don't allow" on the browser's native prompt, *Then* `hostAccessStateFlow.value == HostAccessState.Denied` and no further automatic prompt is attempted.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified)

##### Task 2.2.2a: Implement `requestHostDirectoryAccess` (~4 min)
- New `suspend fun requestHostDirectoryAccess(graphId: String): HostAccessState` on `HostDirectorySync`: re-fetch the handle from IndexedDB (in case it wasn't cached from `reconnectHostDirectory`), call `requestHandlePermission(handle)`, map `"granted"`/`"denied"` to setting `hostDirHandle` + `_hostAccessStateFlow` accordingly. Must only ever be called from a UI click handler (KDoc warning per `research/pitfalls.md` §1.4's transient-user-activation requirement). Called directly as `opfsFileSystem.hostDirectorySync.requestHostDirectoryAccess(graphId)` from the click handler wired in Task 2.3.1a.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 2.2.2b: `hostDirectoryAccessState` `FileSystem` override (~2 min)
- On `PlatformFileSystem`: `override suspend fun hostDirectoryAccessState(graphPath: String): HostAccessState = hostDirectorySync.hostAccessStateFlow.value` — a one-line delegate satisfying the Phase 1 interface addition for commonMain callers that don't want to downcast. This is one of the seven `FileSystem`-interface touch points `PlatformFileSystem` is allowed to implement per Epic 1.6's delegation boundary.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

### Epic 2.3: Permission-lost mid-session UX
**Goal**: `queryPermission()` must be checked opportunistically, not only at startup (`research/pitfalls.md` §1.1), and the UI must distinguish "reconnect" from "conflict" (`research/ux.md` §4).

#### Story 2.3.1: `FolderSyncStatusBadge` renders `HostAccessState` with a distinct "reconnect" affordance
**As a** web user, **I want** a persistent, non-blocking indicator of my folder-sync status,
**so that** I trust my edits are actually reaching disk (per `research/ux.md` §2's "no spinners"/
trust-signal finding).
**Acceptance Criteria**:
- `FolderSyncStatusBadge` renders one of: idle/connected (folder icon, "Synced to `<dirName>`"), `PromptNeeded`/`Denied`/`Disconnected` (a single clickable "Reconnect folder" affordance, styled like `SyncStatusBadge`'s existing `CredentialExpired` "tap to re-connect" precedent), `NotApplicable` (badge not shown at all).
  - *Given* `hostAccessStateFlow.value == HostAccessState.PromptNeeded`, *When* `FolderSyncStatusBadge` recomposes, *Then* it renders a clickable element with text `"Reconnect folder"` that, on click, calls `requestHostDirectoryAccess`.
  - *Given* `hostAccessStateFlow.value == HostAccessState.NotApplicable`, *When* `FolderSyncStatusBadge` recomposes, *Then* nothing is rendered (matches the "no broken affordance" Onboarding convention, `research/ux.md` §0).
- `Disconnected` (stale handle — file/dir moved/deleted externally) renders distinct copy from `PromptNeeded`/`Denied` (permission-only) — "Folder not found — Reconnect" vs. "Grant access" — per `research/ux.md` §4's "reconnect vs. conflict" table.
  - *Given* `hostAccessStateFlow.value == HostAccessState.Disconnected("NotFoundError")`, *When* the badge renders, *Then* its text differs from the `PromptNeeded` case's text (asserted by distinct string constants in a screenshot/unit test, not a shared generic label).
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/FolderSyncStatusBadge.kt` (new)

##### Task 2.3.1a: Implement `FolderSyncStatusBadge` composable (~5 min)
- New Compose composable taking `state: HostAccessState`, `dirName: String?`, `pendingWriteCount: Int`, `onReconnect: () -> Unit`, structured like `SyncStatusBadge.kt`'s existing `when` dispatch over its state type, using a folder/drive icon (not `Computer`/`Cloud`, per `research/ux.md` §0's "false-friend reuse" warning) for the connected state and a warning-tinted clickable row for `PromptNeeded`/`Denied`/`Disconnected`.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/FolderSyncStatusBadge.kt`

##### Task 2.3.1b: Accessibility — `aria-live="polite"` + keyboard reachability (~4 min)
- Apply Compose's `liveRegion` semantics modifier to the badge's status text (not the reconnect button itself) so state transitions are announced without interrupting typing, and ensure the reconnect affordance is a real `clickable`/`Button` (Tab-reachable, Enter/Space-activatable) rather than a bare `Text` with a click modifier, per `research/ux.md` §3.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/FolderSyncStatusBadge.kt`

##### Task 2.3.1c: Wire the badge into the sidebar via a nullable `StateFlow` parameter (~4 min)
- **Mechanism, decided**: follow the existing `localChangesCountFlow: StateFlow<Int>? = null` precedent (`App.kt:206`, wired from `browser/Main.kt:197` as `localChangesCountFlow = opfsFileSystem.dirtyFileCountFlow`) — **not** `expect`/`actual`. `expect`/`actual` on `PlatformFileSystem`/`FileSystem` was considered and rejected: it would force JVM/Android/iOS `actual` implementations to also declare wasmJs-only members, an Interface Segregation violation on the `expect`/`actual` contract (architecture-review.md Blocker 2).
- Add two new nullable `StateFlow` parameters to `App(...)`'s (and `StelekitApp(...)`'s, matching the existing dual-signature shape at `App.kt:206`/`App.kt:407`) parameter list, next to `localChangesCountFlow`: `hostAccessStateFlow: StateFlow<HostAccessState>? = null` and `hostWritePendingCountFlow: StateFlow<Int>? = null`, each KDoc'd identically in style to `localChangesCountFlow`'s existing doc comment ("Pass `PlatformFileSystem.hostDirectorySync.hostAccessStateFlow`/`.hostWritePendingCountFlow` on web. When null (default — JVM/Android/iOS), the folder-sync badge renders nothing.").
- Thread both parameters down to `FolderSyncStatusBadge`'s call site in `App.kt`'s sidebar header composition (next to the existing `SyncStatusBadge` usage), calling `.collectAsState()` only when non-null (e.g. `hostAccessStateFlow?.collectAsState()?.value ?: HostAccessState.NotApplicable`) so the badge renders nothing on JVM/Android/iOS, matching Story 2.3.1's `NotApplicable` acceptance criterion.
- In `browser/Main.kt`, wire `hostAccessStateFlow = opfsFileSystem.hostDirectorySync.hostAccessStateFlow` and `hostWritePendingCountFlow = opfsFileSystem.hostDirectorySync.hostWritePendingCountFlow` (the latter added in Task 8.1.1b) alongside the existing `localChangesCountFlow = opfsFileSystem.dirtyFileCountFlow` line (`Main.kt:197`).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/App.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`

### Epic 2.4: OPFS durability — best-effort `storage.persist()` on connect
**Goal**: Address the first half of adversarial-review.md Blocker 1 — this plan previously never
called `navigator.storage.persist()` anywhere, despite `research/pitfalls.md` §1.3's explicit finding
that OPFS can be LRU-evicted under storage pressure with no app-visible error once a host directory
is attached. (The second half of Blocker 1 — recovery when eviction has already happened — is
addressed as a corollary of Epic 2.2's `runHostReconciliation`-on-every-reconnect fix, per the
Pattern Decisions table's "OPFS eviction recovery" row; no separate detection heuristic is added
here, since reconciliation already self-heals an emptied `cache` by reclassifying every host file as
`HostOnlyNew`.)

#### Story 2.4.1: Request storage persistence on every successful connect/reconnect
**As a** web user with a host directory attached, **I want** the app to proactively ask the browser
not to evict my OPFS mirror, **so that** the disposable-cache risk `research/pitfalls.md` §1.3
identifies is mitigated before it happens, not just recovered from afterward.
**Acceptance Criteria**:
- `requestStoragePersistence()` (Task 1.5.6a) is called, best-effort and fire-and-forget (result logged, never blocks or fails the connect/reconnect flow), from both `connectHostDirectory`'s and `reconnectHostDirectory`'s success paths.
  - *Given* `connectHostDirectory` or `reconnectHostDirectory` completes successfully (state becomes `Granted`), *When* the call resolves, *Then* `requestStoragePersistence()` has been invoked exactly once for that connect/reconnect, and its result (`true`/`false`) is logged via `println("[SteleKit] storage.persist(): granted=<bool>")` — never surfaced as a user-visible error or blocking prompt, since it's explicitly best-effort per `research/pitfalls.md` §1.3 ("itself requires a user gesture / heuristic engagement score and can be silently denied").
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified)

##### Task 2.4.1a: Wire `requestStoragePersistence()` into `connectHostDirectory`/`reconnectHostDirectory` (~3 min)
- Add `scope.launch { val granted = requestStoragePersistence(); println("[SteleKit] storage.persist(): granted=$granted") }` as a fire-and-forget call at the end of both success paths (Task 2.2.1a's `"granted"` branch and Task 3.1.1a), after reconciliation has been kicked off — this call must never be `await`-ed inline in a way that delays the user-visible resume/connect flow.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

### Epic 2.5: Session-resume & handle-persistence tests
**Goal**: Every other phase in this plan pairs its implementation epics with an explicit test epic
(Epic 3.3, 4.5, 5.4, 6.3, 7.2, 8.2/8.3) — Phase 2 (Epics 2.1–2.4) previously had zero "Files:" lines
pointing at any test file across all its tasks, even though `validation.md` already scopes tests for
it. This epic makes that coverage an explicit, plan-level deliverable (Consistency Blocker fix), not
just a validation-doc cross-reference.

#### Story 2.5.1: `HostDirectorySyncHandleRetentionTest` — IndexedDB round-trip persistence
**As a** SteleKit maintainer, **I want** automated proof that a picked handle survives an IndexedDB
round-trip, **so that** Story 2.1.1's persistence contract is verified, not just implemented.
**Acceptance Criteria**:
- `attachFreshHandle` persists a `HostHandleEnvelope` keyed by `graphId`; a subsequent `idbGetHandle(db, graphId)` (called independently, real IndexedDB, wasmJs browser test target) returns a non-null handle matching the envelope's `dirName`.
  - *Given* `pickDirectoryAsync()` resolves for a directory named `"my-notes"` with `graphId = GraphManager.graphIdFromPath("/stelekit/my-notes")`, *When* the test independently calls `idbGetHandle(db, graphId)` after the pick completes, *Then* it returns a non-null handle and the round-tripped `HostHandleEnvelope.dirName == "my-notes"`.
- A persistence failure (IndexedDB `put` throws) does not fail the pick itself — matches Story 2.1.1's second acceptance criterion.
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncHandleRetentionTest.kt` (new)

##### Task 2.5.1a: Implement `HostDirectorySyncHandleRetentionTest` (~6 min)
- Two tests per this story's acceptance criteria, run against real IndexedDB (wasmJs browser test target), following `HostDirectoryInteropIndexedDbLiveTest.kt`'s "Live" naming convention for real-API tests.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncHandleRetentionTest.kt`

#### Story 2.5.2: `HostDirectorySyncSessionResumeTest` — silent resume, one-click prompt, and permission-lost UX
**As a** SteleKit maintainer, **I want** automated proof of Epic 2.2/2.3's resume flows, **so that**
the "zero clicks when still granted, one click when not" success metric (requirements.md) is
enforced by a test, not just a manual walkthrough.
**Acceptance Criteria**:
- Silent resume: `queryHandlePermission` returning `"granted"` produces `hostAccessStateFlow.value == Granted` with zero prompts/dialogs invoked (Story 2.2.1's AC).
- No-handle / prompt / denied branches resolve to `NotApplicable`/`PromptNeeded`/`Denied` respectively without setting `hostDirHandle` on the non-granted branches (Story 2.2.1/2.2.2's ACs).
- One-click prompt: `requestHostDirectoryAccess` called from a simulated click resolves to `Granted` on allow, `Denied` (no retry loop) on decline (Story 2.2.2's ACs).
- `storage.persist()` is invoked exactly once per successful connect/reconnect and never blocks the flow (Story 2.4.1's ACs).
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncSessionResumeTest.kt` (new)

##### Task 2.5.2a: Silent-resume and no-handle/prompt/denied branch tests (~7 min)
- Per Story 2.2.1's acceptance criteria (the non-reconciliation-focused half — Epic 3.3/3.4 own the reconciliation-parity and non-blocking-launch assertions specifically).
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncSessionResumeTest.kt`

##### Task 2.5.2b: One-click resume (allow/decline) tests (~6 min)
- Per Story 2.2.2's acceptance criteria.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncSessionResumeTest.kt`

##### Task 2.5.2c: `storage.persist()` fire-and-forget tests (~4 min)
- Per Story 2.4.1's acceptance criteria — `requestStoragePersistence()` invoked exactly once per successful connect/reconnect, never blocking the flow.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncSessionResumeTest.kt`

#### Story 2.5.3: `FolderSyncStatusBadgeTest` — per-state copy assertions
**As a** SteleKit maintainer, **I want** each badge state's exact rendered copy asserted, **so that**
Story 2.3.1's "distinct copy, never color-only" requirement (design/ux.md AC22) is enforced.
**Acceptance Criteria**:
- Each of the six `HostAccessState`/pending-write-driven render branches (idle/connected, `PromptNeeded`, `Denied`, `Disconnected`, `SyncDegraded`, `NotApplicable`) produces distinct, non-empty text (or, for `NotApplicable`, renders nothing) — using the corrected `SyncDegraded` condition (Task 4.4.1c).
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/FolderSyncStatusBadgeTest.kt` (new — Compose UI test)

##### Task 2.5.3a: Implement per-state copy assertions (~6 min)
- Compose UI test per this story's acceptance criteria, following this codebase's existing wasmJsTest Compose-for-Web test harness pattern.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/FolderSyncStatusBadgeTest.kt`

#### Story 2.5.4: Interop primitive coverage — IndexedDB and permission query/request
**As a** SteleKit maintainer, **I want** Task 1.5.1a's IndexedDB primitives and Task 1.5.3a's
permission interop directly tested, **so that** these low-level building blocks have their own
coverage, not just indirect coverage via the higher-level flows above.
**Acceptance Criteria**:
- `idbOpenHandleDb`/`idbPutHandle`/`idbGetHandle` round-trip correctly against a real IndexedDB (wasmJs browser test target); `idbGetHandle` returns `null` for an absent key.
- `queryHandlePermission`/`requestHandlePermission` return the raw `"granted"`/`"prompt"`/`"denied"` string, and fail closed (`"denied"`) on a thrown error.
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectoryInteropTest.kt` (new — mocked), `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectoryInteropIndexedDbLiveTest.kt` (new — real IndexedDB)

##### Task 2.5.4a: `HostDirectoryInteropTest` — mocked IndexedDB/permission cases (~6 min)
- Per this story's acceptance criteria, mocked (no real browser API calls).
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectoryInteropTest.kt`

##### Task 2.5.4b: `HostDirectoryInteropIndexedDbLiveTest` — real IndexedDB round-trip (~5 min)
- Real-browser integration test, matching `WasmGitWriteServiceLiveTest.kt`'s "Live" naming convention.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectoryInteropIndexedDbLiveTest.kt`

---

## Phase 3: Upgrade Reconciliation Pass (Critical Finding — REQUIRED)

### Epic 3.1: `connectHostDirectory` — a distinct entry point for already-populated graphs
**Goal**: Ensure "enable live sync on an existing graph" never calls the unconditional,
overwrite-only `importUserDirToCache`, per `research/architecture.md` §5 (the single
highest-severity finding in the research).

#### Story 3.1.1: `connectHostDirectory(existingOpfsPath)` reconciles instead of importing
**As a** returning web user with existing browser-only edits, **I want** enabling live sync to
never destroy those edits, **so that** the exact scenario `research/architecture.md` §5.1 traces
(silent destruction at activation time) cannot happen.
**Acceptance Criteria**:
- `connectHostDirectory(existingOpfsPath)` calls `showDirectoryPicker()` then `runHostReconciliation` — **never** `importUserDirToCache`.
  - *Given* an OPFS graph at `/stelekit/my-notes` with a page `pages/BrowserOnly.md` that exists only in `cache` (created in-browser after the original one-time import, never on the host disk), *When* the user clicks "Enable live folder sync" and picks the same `my-notes` folder, *Then* after `hostDirectorySync.connectHostDirectory` completes, `pages/BrowserOnly.md`'s content is still present in `cache` (not deleted or overwritten, read/written only via `CacheAccess`) and is queued in `hostWritePending` for push to the host.
- `FolderSyncSettings.kt`'s panel (Task 3.1.1b) displays the exact reassurance copy **"Existing edits in this graph are kept — nothing is overwritten when you connect."** before the "Enable live folder sync" button is ever clicked — per design/ux.md Surface 7, this line is load-bearing UI copy directly targeting the Critical Finding's failure mode and must not be cut for space.
  - *Given* `FolderSyncSettings` renders (state `NotApplicable`, native picker supported), *When* a test inspects the composable's text content, *Then* the exact string above is present, verbatim, prior to any click.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified)

##### Task 3.1.1a: Implement `connectHostDirectory` (~5 min)
- New `suspend fun connectHostDirectory(existingOpfsPath: String): HostAccessState` on `HostDirectorySync` (not `PlatformFileSystem` — reachable only via `opfsFileSystem.hostDirectorySync.connectHostDirectory(...)`): call `showDirectoryPicker()` (via existing `OpfsInterop.showDirectoryPicker()`), then `runHostReconciliation(dirHandle, existingOpfsPath)` (Epic 3.2), then set `hostDirHandle`/`hostGraphOpfsPath`/persist to IndexedDB (reusing Task 2.1.1c's `persistHostHandle`), then return `HostAccessState.Granted`. Explicitly does **not** call `importUserDirToCache` anywhere in this path (that function stays on `PlatformFileSystem`, untouched by this project outside the one new call site in Task 2.1.1b).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 3.1.1b: `FolderSyncSettings.kt` — "Enable live folder sync" affordance for existing graphs (~5 min)
- New composable `FolderSyncSettings(hostAccessState: HostAccessState, onConnect: suspend () -> Unit, supportsNativeDirectoryPicker: Boolean)` in the settings package (mirroring `VaultSettings.kt`'s callback-driven, `SettingsSection`-wrapped structure), shown only when `supportsNativeDirectoryPicker == true` and `hostAccessState == HostAccessState.NotApplicable` (i.e. this graph has never had live sync enabled) — reuses the Onboarding "don't show a broken affordance" convention. Includes the exact reassurance copy **"Existing edits in this graph are kept — nothing is overwritten when you connect."** verbatim, per this story's added acceptance criterion and design/ux.md Surface 7 — not paraphrased, not cut for space.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/FolderSyncSettings.kt` (new)

##### Task 3.1.1c: Wire `FolderSyncSettings` into the settings dialog (~2 min)
- Add `FolderSyncSettings(...)` to `SettingsDialog.kt`'s panel composition, passing the `hostAccessState` value threaded down from `App.kt`'s nullable `hostAccessStateFlow` parameter (Task 2.3.1c — same mechanism, not a fresh downcast) and `onConnect = { opfsFileSystem.hostDirectorySync.connectHostDirectory(currentGraphPath) }` (wasmJs-only, wired the same way `Main.kt` wires the badge's flows).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/SettingsDialog.kt`

#### Story 3.1.2: Reconciliation progress/summary composable (per design/ux.md Surface 8)
**As a** user enabling live folder sync on an existing graph, **I want** to see a transient
"Connecting to folder…" state followed by a summary of what reconciliation found, **so that** I'm
never left staring at a static screen wondering if my click registered, and I can see that my
browser-only edits were preserved (per ux.md's "load-bearing reassurance" framing). This story closes
a gap ux.md itself flags: design/ux.md Surface 8 specifies this UI in full, but this plan's Phase 3
(Epics 3.1–3.3) previously never implemented it.
**Acceptance Criteria**:
- A new composable renders three states in sequence, matching design/ux.md Surface 8's wireframes
  verbatim: (1) transient **"Connecting to folder… Comparing your browser edits with the files on
  disk."** with a progress spinner, shown for the duration of `connectHostDirectory`'s awaited
  `runHostReconciliation` call; (2) on success, a summary screen showing counts for all four
  `ReconciliationOutcome` categories that have at least one member (`"N files already match"` /
  `"N files differ — you'll be asked which version to keep..."` / `"N new files found on disk —
  added to your graph"` / `"N browser-only pages — will be written to the folder"`), with a
  `[ Done ]` button; (3) on failure (reconciliation throws mid-walk), a failure screen reading
  **"Couldn't finish comparing your files"** + **"Nothing was changed — your graph is unaffected"** +
  `[ Try again ]`/`[ Cancel ]` buttons.
  - *Given* a user clicks "Enable live folder sync" and `connectHostDirectory` is in flight, *When* the composable renders, *Then* it shows the transient progress state with the exact copy above.
  - *Given* `connectHostDirectory` resolves with 142 `Identical`, 3 `HostChangedConflict`, 5 `HostOnlyNew`, 2 `BrowserOnlyNeedsPush`, *When* the summary renders, *Then* all four counts are shown explicitly (never folded together), matching design/ux.md AC17.
  - *Given* `runHostReconciliation` throws mid-walk, *When* the composable renders its failure state, *Then* it shows the exact two-line failure copy above and `hostDirHandle` remains unset (state stays `NotApplicable`), matching design/ux.md AC8.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/FolderSyncReconciliationProgress.kt` (new)

##### Task 3.1.2a: Implement the three-state composable (~8 min)
- New composable taking a sealed `ReconciliationUiState { Connecting; Summary(identical: Int, conflict: Int, hostOnly: Int, browserOnly: Int); Failed(message: String) }` (commonMain type, small enough to live alongside the composable rather than in `HostReconciliation.kt`), rendering the three wireframes from design/ux.md Surface 8 verbatim (copy strings match exactly, including the failure state's "Nothing was changed — your graph is unaffected" reassurance line).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/FolderSyncReconciliationProgress.kt`

##### Task 3.1.2b: Wire `connectHostDirectory`'s three outcomes into the composable's state (~5 min)
- `FolderSyncSettings.kt`'s (Task 3.1.1b) `onConnect` callback drives a local `ReconciliationUiState` through `Connecting` → `Summary`/`Failed`, sourcing the four counts from `runHostReconciliation`'s per-category tally (Epic 3.2's classification pass already computes this for the existing observability log line — `"[SteleKit] reconciliation: N identical, M conflict, K host-only, J browser-only"` — this task exposes those same counts to the UI rather than only to `println`).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/FolderSyncSettings.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (expose per-category counts from `runHostReconciliation`'s return value, not only a log line)

##### Task 3.1.2c: Screen-reader announcement on entry and completion (~3 min)
- Apply `liveRegion` semantics to both the progress spinner's label and the summary's heading, per design/ux.md AC24 — announced on entry (progress state) and again on completion (summary counts).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/settings/FolderSyncReconciliationProgress.kt`

### Epic 3.2: Reconciliation walk, classification, and action dispatch
**Goal**: The actual tree walk + four-way action table from `research/architecture.md` §5.2,
reusing the `FileRegistry.detectChanges`-shaped traversal rather than a bespoke algorithm.

#### Story 3.2.1: `runHostReconciliation` walks the host tree and classifies every path
**As a** developer implementing the reconciliation pass, **I want** one function that walks the
picked directory once and produces a classified action list, **so that** Epic 3.1's entry point and
any future re-reconciliation need (e.g. after a long-closed tab, per `research/features.md` §1.1)
share one implementation.
**Acceptance Criteria**:
- `runHostReconciliation(dirHandle: JsAny, opfsPath: String)` recursively walks `dirHandle` (reusing `listOpfsEntries`/`isFileEntry`/`isDirectoryEntry` from `OpfsInterop.kt`, the same traversal `importUserDirToCache` already uses), reads each file's content, and calls `classifyReconciliation(hostContent, cache[path])` for every path present on either side.
  - *Given* a host directory with 3 files (`A.md` identical to cache, `B.md` differing from cache, `C.md` present only on host) and a `cache` with those 2 plus a 4th (`D.md`, browser-only), *When* `runHostReconciliation` runs, *Then* it produces exactly 4 classified results: `A.md → Identical`, `B.md → HostChangedConflict`, `C.md → HostOnlyNew`, `D.md → BrowserOnlyNeedsPush`.
- **Added per adversarial-review.md Blocker 4**: for any path ending `.md.stek`, the walk reads raw bytes (not `.text()`) and calls `classifyReconciliationBytes(hostBytes, cacheAccess.getBytes(path))` instead of the string-typed `classifyReconciliation` — encrypted content is never decoded as UTF-8 anywhere in the reconciliation path.
  - *Given* a host directory containing `pages/Secret.md.stek` with encrypted bytes differing from `cacheAccess.getBytes("pages/Secret.md.stek")`, *When* `runHostReconciliation` runs, *Then* it classifies that path via `classifyReconciliationBytes` (asserted by a call-count/argument-type check in the test, not just outcome equality) and produces `HostChangedConflict`, without ever calling `.text()` on that file's `File` object.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified)

##### Task 3.2.1a: Implement the recursive walk, branching on `.stek` suffix (~7 min)
- Private `suspend fun runHostReconciliation(dirHandle: JsAny, opfsPath: String)` on `HostDirectorySync`: recursively enumerate host entries (mirroring `importUserDirToCache`'s traversal shape, `PlatformFileSystem.kt:341-363` — reused by reference, not duplicated, since `HostDirectorySync` doesn't own that function), building a `Set<String>` of host-visited paths; for each host file, branch on `path.endsWith(".md.stek")`: **true** → read raw bytes via `getFile()` + `arrayBuffer()` (the same accessor `flushHostWrite`'s `Bytes` branch, Task 4.2.2a, uses for writes), look up `cacheAccess.getBytes(path)`, call `classifyReconciliationBytes`; **false** → read `.text()`, look up `cacheAccess.get(path)`, call `classifyReconciliation` (unchanged from the original task).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 3.2.1b: Cover browser-only paths not visited by the host walk (~3 min)
- After the host walk, iterate paths reachable via `CacheAccess` (either a new `CacheAccess.keysUnder(opfsPath): Set<String>` method or a filtered read exposed for this purpose) `.filter { it !in hostVisitedPaths }` and classify each as `BrowserOnlyNeedsPush` (host content is implicitly `null` for these — `classifyReconciliation(null, cacheAccess.get(path))`).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` (add `keysUnder` to the `CacheAccess` implementation from Task 1.6.1a/1.6.1b)

#### Story 3.2.2: Apply each `ReconciliationOutcome`
**As a** developer, **I want** each classification to trigger its documented action, **so that** no
outcome is silently dropped (an unhandled `when` branch fails to compile, per the Pattern Decisions
table's type-driven-design rationale).
**Acceptance Criteria**:
- `Identical` → no state change. `HostChangedConflict` → `GraphLoader.emitExternalFileChange(path, hostContent)` is called (injected callback; `HostDirectorySync` does not import `GraphLoader` directly — see Task 3.2.2c). `HostOnlyNew` → `cacheAccess.set(path, hostContent)` + `cacheAccess.writeOpfsMirror(path, hostContent)` (same shape as `FileRegistry.detectChanges`'s new-file path). `BrowserOnlyNeedsPush` → `scheduleHostWriteThrough(path, cacheAccess.get(path)!!)` (forward-reference to Phase 4; acceptable to land as a `TODO`-free real call once Phase 4 exists, or as a stub queue write in this phase with Phase 4 supplying the flush — sequencing choice left to the implementer, but the enqueue call itself must exist here).
  - *Given* the 4-path scenario from Story 3.2.1's acceptance criterion, *When* `runHostReconciliation` applies its classifications, *Then* `A.md` is untouched, `B.md` triggers exactly one `emitExternalFileChange("pages/B.md", <host content>)` call, `C.md` is added to `cache` (via `CacheAccess`) and scheduled for an OPFS write, and `D.md` appears in `hostWritePending`.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified)

##### Task 3.2.2a: Wire `HostChangedConflict` → conflict callback (~3 min)
- Add a constructor-injected `private val onHostConflict: (String, String) -> Unit = { _, _ -> }` field to `HostDirectorySync` (set from `Main.kt` to `graphLoader::emitExternalFileChange`, matching this codebase's existing lambda-injection convention rather than a direct `GraphLoader` import into the platform layer — and keeping `HostDirectorySync`, like `PlatformFileSystem`, free of a `GraphLoader` dependency). Call it for every `HostChangedConflict` classification.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 3.2.2b: Wire `HostOnlyNew` → import, bytes-aware (~4 min)
- For each `HostOnlyNew` path: if `.md.stek`-suffixed, `cacheAccess.setBytes(path, hostBytes)` + `cacheAccess.writeOpfsMirrorBytes(path, hostBytes)`; otherwise `cacheAccess.set(path, hostContent)` + `cacheAccess.writeOpfsMirror(path, hostContent)` — matching `importUserDirToCache`'s existing per-file write shape exactly (reuse, don't reimplement) but routed through the injected `CacheAccess` rather than touching `PlatformFileSystem`'s `cache`/`bytesCache` fields directly. The bytes branch is required per adversarial-review.md Blocker 4 — without it, a paranoid-mode file newly present on the host would either crash on decode or be imported as corrupted text.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 3.2.2c: Wire `BrowserOnlyNeedsPush` → `hostWritePending` enqueue (~3 min)
- For each `BrowserOnlyNeedsPush` path, add an entry to `hostWritePending` directly (`hostWritePending[repoRelative] = DirtyEntry(DirtyOp.WRITE, now)`) rather than calling `scheduleHostWriteThrough` (which also mutates `cache`/schedules an OPFS write that's already correct here) — the reconciliation pass only needs to mark these paths for the Phase 4 flush loop to pick up once `hostDirHandle` is set. `hostWritePending` is a `HostDirectorySync` field (Epic 4.1), not a `PlatformFileSystem` field. This same enqueue is also the recovery mechanism for adversarial-review.md Blocker 2 (a crash-lost in-memory `hostWritePending`): since `reconnectHostDirectory` now always calls `runHostReconciliation` (Blocker 3's fix), any browser-only edit still sitting in `cache`/`bytesCache` from a prior session is re-discovered and re-enqueued here automatically, with no separate persisted queue needed.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 3.2.2d: Wire `onHostConflict` injection through `Main.kt` (~3 min)
- In `Main.kt`, after both `opfsFileSystem` (and its `hostDirectorySync`) and the `GraphLoader` instance exist, set the callback on `hostDirectorySync` (constructor param or a settable `var onHostConflict` — whichever fits construction order relative to `GraphLoader`'s, since `GraphLoader` is constructed after `PlatformFileSystem`/`HostDirectorySync` today): `opfsFileSystem.hostDirectorySync.onHostConflict = graphLoader::emitExternalFileChange`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`

### Epic 3.3: Reconciliation regression and safety tests
**Goal**: Dedicated test coverage for the Critical Finding, per requirements.md's explicit
"needs dedicated test coverage" instruction — not folded into general write-through tests. Scoped
to `HostDirectorySync` directly (constructed against a fake `CacheAccess`, per Task 1.6.1c), not to
`PlatformFileSystem`, so this test file exercises exactly one class's one responsibility.

#### Story 3.3.1: `HostDirectorySyncReconciliationTest`
**As a** SteleKit maintainer, **I want** automated proof that reconciliation never silently
destroys browser-only edits, **so that** this regression cannot reappear undetected.
**Acceptance Criteria**:
- Four tests, one per `ReconciliationOutcome` branch, plus one full-scenario integration test combining all four in a single `runHostReconciliation` call, plus one regression test proving the **old** fresh-empty-graph `pickDirectoryAsync()` path (still on `PlatformFileSystem`, unmodified by this project) is unaffected by this project's changes.
  - *Given* a mocked `dirHandle` (test double implementing the same `listOpfsEntries`/`getFile().text()` surface `runHostReconciliation` consumes) representing a host directory with `pages/Foo.md` containing `"host version"`, and a fake `CacheAccess` pre-seeded with `"/stelekit/g/pages/Foo.md" -> "browser version"`, *When* `hostDirectorySync.runHostReconciliation` runs, *Then* the fake `CacheAccess`'s entry for that path still equals `"browser version"` (not overwritten) and the injected `onHostConflict` callback was invoked exactly once with `("pages/Foo.md", "host version")`.
  - *Given* an empty `cache` (fresh graph, nothing imported yet) and a mocked `dirHandle` with 5 files, *When* `pickDirectoryAsync()` (not `connectHostDirectory`, on the real `PlatformFileSystem` — a separate test in this file, not routed through `HostDirectorySync`) runs, *Then* all 5 files land in `cache` exactly as `importUserDirToCache` produced before this project (byte-for-byte, verified against a snapshot captured before any Phase 3 code changes).
- **Added per adversarial-review.md Blocker 4**: one test proving a `.md.stek` path is classified via `classifyReconciliationBytes`, not the string classifier.
  - *Given* a mocked `dirHandle` with `pages/Secret.md.stek` (raw bytes) and a fake `CacheAccess` pre-seeded with different bytes via `setBytes`, *When* `runHostReconciliation` runs, *Then* the outcome is `HostChangedConflict`, `onHostConflict` was never called with a mis-decoded string, and the fake `CacheAccess`'s `getBytes`/`setBytes` (not `get`/`set`) were the methods exercised for that path.
- **Added per adversarial-review.md Blocker 3**: one test proving `reconnectHostDirectory`'s silent-resume path — not just `connectHostDirectory` — runs `runHostReconciliation`.
  - *Given* a fake IndexedDB pre-seeded with a persisted handle for `graphId = "g"`, `queryHandlePermission` stubbed to return `"granted"`, and a mocked host directory whose content diverges from a pre-seeded `cache` (same shape as this story's first acceptance criterion), *When* `hostDirectorySync.reconnectHostDirectory("g")` runs, *Then* `onHostConflict` is invoked exactly as it would be for an equivalent `connectHostDirectory` call — proving the two entry points share the same data-loss protection, not just the same handle-attachment mechanics.
- **Added per adversarial-review.md Blocker 2, fixed per Epic 1.7 (scope expansion, Option A —
  supersedes the original Option-B "accept and document" decision)**: three tests — the original
  resolved-half proof (unchanged), plus two proving the previously-accepted residual gap is now
  closed rather than merely documented.
  - *Given* a fake `CacheAccess` holding a browser-only edit (`cache["/stelekit/g/pages/Draft.md"] = "unsaved edit"`, no corresponding host file) but an **empty** `hostWritePending` map (simulating a tab crash that lost the in-memory queue *after* the edit's OPFS write had already completed), *When* `hostDirectorySync.reconnectHostDirectory("g")` runs (host directory has no `Draft.md`), *Then* `hostWritePending` contains `"pages/Draft.md"` afterward — the edit is rediscovered via reconciliation's `BrowserOnlyNeedsPush` classification, not lost. (Unchanged from the original remediation.)
  - *Given* a slow-but-eventually-resolving `opfsWriteFile` test double standing in for `writeFile`'s
    OPFS-persisting write (models a crash landing mid-flight, where the write nonetheless completes a
    moment later — the actual window Epic 1.7 closes, not a never-resolving double, which cannot
    meaningfully assert "data is not lost" since nothing can be awaited to a testable completion),
    *When* `writeFile("pages/Draft.md", "unsaved edit")` is called followed by the normal
    `scheduleHostWriteThrough` delegation (Task 4.3.1a's real call sequence), *Then* once the delayed
    write resolves, `hostWritePending` **does** contain `"pages/Draft.md"` — the edit is not silently
    dropped during the wait, replacing the pre-fix behavior where this race could lose it. This test
    now asserts correct/safe behavior; it must not be weakened back into a fixture that pre-seeds the
    edit as already durable, which would stop testing the actual race.
  - *Given* the same slow-but-eventually-resolving double, *When* `scheduleHostWriteThrough`'s call
    is inspected at two points — immediately after the call returns control, and after the delayed
    write resolves — *Then* `hostWritePending` does not yet contain the path at the first point and
    does at the second, proving the await mechanism itself (Task 1.7.1a/1.7.1b) is what makes the
    previous bullet's outcome correct, not a coincidence of timing.
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncReconciliationTest.kt` (new)

##### Task 3.3.1a: Test fixture — mocked `dirHandle`/`getFile().text()` + fake `CacheAccess` (~5 min)
- Build a small in-memory test double for the `listOpfsEntries`/`isFileEntry`/`getEntryName`/`readOpfsFile` surface `runHostReconciliation` depends on, plus a simple `FakeCacheAccess : HostDirectorySync.CacheAccess` backed by an in-memory `MutableMap`, following whatever mocking pattern `PlatformFileSystemDirtyTrackingIntegrationTest.kt` (existing wasmJs test) already established for OPFS interop testing — reuse that pattern rather than inventing a new one. `HostDirectorySync` under test is constructed directly with `FakeCacheAccess`, with no `PlatformFileSystem` instance involved (per Task 1.6.1c's independence guarantee).
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncReconciliationTest.kt`

##### Task 3.3.1b: Identical/conflict/host-only-new/browser-only-needs-push tests (~8 min)
- Four focused tests per the acceptance criteria's Given-When-Then shapes.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncReconciliationTest.kt`

##### Task 3.3.1c: Combined 4-path integration test (~4 min)
- The `A/B/C/D` scenario from Story 3.2.1's acceptance criterion, asserting all four outcomes simultaneously from one `runHostReconciliation` call.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncReconciliationTest.kt`

##### Task 3.3.1d: Fresh-empty-graph regression test (~4 min)
- Assert `PlatformFileSystem.pickDirectoryAsync()`'s behavior for an empty `cache` is unchanged by this project — no reconciliation logic runs on that path (it's a `HostDirectorySync` method, never called from `pickDirectoryAsync`), `importUserDirToCache` is still called exactly as before. This one test in the file legitimately targets `PlatformFileSystem` directly, since it is a regression guard on the pre-existing method this project must not touch.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncReconciliationTest.kt`

##### Task 3.3.1e: `.md.stek` reconciliation test (~4 min)
- Per this story's Blocker 4 acceptance criterion — construct a mocked `dirHandle` entry for a `.md.stek` path, assert the walk takes the bytes branch (via a call-count assertion on the fake `CacheAccess`'s `getBytes`/`setBytes` vs. `get`/`set`).
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncReconciliationTest.kt`

##### Task 3.3.1f: `reconnectHostDirectory` reconciliation-parity test (~5 min)
- Per this story's Blocker 3 acceptance criterion — reuses the first acceptance criterion's fixture but drives it through `reconnectHostDirectory` (with a stubbed IndexedDB lookup and `queryHandlePermission` returning `"granted"`) instead of `connectHostDirectory`, asserting identical `onHostConflict` behavior.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncReconciliationTest.kt`

##### Task 3.3.1g: `hostWritePending` crash-recovery tests — durable-edit recovery, and the closed data-loss window (~10 min)
- Three tests, per the corrected Story 3.3.1 acceptance criteria above:
  1. **Resolved-half check** (unchanged from the original remediation): pre-seed `cache` with a browser-only edit (representing an edit whose OPFS write had already completed before the crash) and an empty `hostWritePending`, run `reconnectHostDirectory`, assert the path is re-enqueued into `hostWritePending`.
  2. **Fix verification — replaces the former "residual gap" characterization test.** Per Epic 1.7's
     Option-A fix: inject a slow-but-eventually-resolving `opfsWriteFile` test double (resolves after
     a short delay, modeling the exact "crash landed mid-flight, but the write does complete a moment
     later" window the Critical Finding worried about — not a never-resolving double, which cannot
     meaningfully assert "data is not lost" since nothing can be awaited to completion). Call
     `writeFile("pages/Draft.md", "unsaved edit")` then the normal `scheduleHostWriteThrough`
     delegation, and assert that once the delayed OPFS write resolves, `"pages/Draft.md"` **is**
     present in `hostWritePending` — the edit is not lost or silently forgotten during the wait,
     unlike the pre-fix behavior where a race between the unawaited write and any subsequent read
     could drop it. This test now asserts correct/safe behavior, replacing the old test's "documents
     accepted loss" framing.
  3. **Second regression test — awaited-write behavior specifically** (kept per this fix pass's
     explicit instruction, distinct from #2's scenario-level assertion): using the same
     slow-but-eventually-resolving double, assert that `scheduleHostWriteThrough`'s call does **not**
     add the path to `hostWritePending` until the underlying `Deferred` (Task 1.7.1a) actually
     resolves — a call-timing/ordering assertion (the path is absent immediately after the call
     returns control to the coroutine but before the delay elapses, and present after), proving the
     *waiting* mechanism itself, not just its eventual outcome. Complements Task 1.7.3a's
     `PlatformFileSystem`-level test of the same mechanism from the write side.
- A true, un-awaitable hard crash (OOM kill, force-quit) remains outside what any client-side JS fix
  can close — this was never claimed to be fully closeable (see Epic 1.7's Goal). What Epic 1.7
  closes is the previously-real race where the write-through queue could enqueue, or reconciliation
  could silently miss, an edit that *was* going to complete, just not yet — the actual bug this
  project's Critical Finding and adversarial-review.md's sole remaining Blocker identified.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncReconciliationTest.kt`

### Epic 3.4: Reconciliation cost control — mtime/size pre-filter, non-blocking session resume, and required large-graph benchmark
**Goal**: Close pre-mortem.md P1 finding #1 — `runHostReconciliation` performs a full-content read
(`.text()`/`arrayBuffer()`) on every path on **every** `reconnectHostDirectory` (not just the
one-time opt-in), is unbenchmarked at this codebase's 8,000+-page scale, and was previously awaited
as a blocking sequential `Main.kt` startup step — a direct risk of making every app open on a large
graph noticeably slower, for exactly the large-graph users this feature targets. This epic brings
reconciliation's cost model in line with the existing `FileRegistry`/`GraphFileWatcher` cheap-diff-
then-hash pattern (mtime+size pre-filter before falling back to content read) and decides
reconciliation's blocking/non-blocking placement at startup.

#### Story 3.4.1: `runHostReconciliation` uses a cheap mtime/size pre-filter before falling back to content read
**As a** large-graph web user, **I want** reconciliation on session resume to skip a full content
read/hash for every unchanged file, **so that** reopening the app doesn't become noticeably slower
than today just because live sync is enabled.
**Acceptance Criteria**:
- `runHostReconciliation`'s walk (Task 3.2.1a) first compares each host file's `File.lastModified`/`size` (already available via `getFile()`'s returned `File` object, per `fileLastModified`/`fileSize` — Task 1.5.5a) against a persisted-per-graph "last known reconciled" baseline (reusing `hostModTimes`/`hostFileSizes` — Epic 5.1's fields — when available, or treated as "unknown, must read" on a graph's first-ever reconciliation); only when the pre-filter signals a possible change (or no baseline exists) does the walk fall back to a full content read + `classifyReconciliation`/`classifyReconciliationBytes`.
  - *Given* a host directory with 500 files whose `lastModified`/`size` are unchanged since the last successful reconciliation/poll, *When* `runHostReconciliation` runs, *Then* zero content reads (`.text()`/`arrayBuffer()`) occur for those 500 files, and each is classified `Identical` directly from the pre-filter match (no `classifyReconciliation` call needed when both mtime and size match a known-good baseline — matching `FileRegistry.detectChanges`'s mtime-first-then-hash idiom).
  - *Given* 10 of those 500 files have a changed `lastModified`/`size`, *When* `runHostReconciliation` runs, *Then* exactly 10 content reads occur, each routed through the normal `classifyReconciliation`/`classifyReconciliationBytes` four-way classification (the pre-filter is a short-circuit for the unchanged case only, never a substitute for classification when a change is possible).
- **First-ever reconciliation for a graph** (no baseline exists — e.g. fresh `connectHostDirectory`) falls back to the full content-read walk for every file, exactly as today — the pre-filter is purely an optimization for the steady-state repeat case (session resume), never a correctness shortcut on first connect.
  - *Given* a graph with no prior `hostModTimes`/`hostFileSizes` baseline (first-ever `connectHostDirectory`), *When* `runHostReconciliation` runs, *Then* every file is content-read and classified exactly as Task 3.2.1a already specifies (no behavior change for this case).
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified — `runHostReconciliation`'s walk)

##### Task 3.4.1a: Add the mtime/size pre-filter to the reconciliation walk (~7 min)
- Extend Task 3.2.1a's walk: before reading a host file's content, compare `fileLastModified(file)`/`fileSize(file)` against `hostModTimes[path]`/`hostFileSizes[path]` (Epic 5.1's existing fields — reused, not duplicated) if present; on a match, classify `Identical` directly (a path present in `cache` but absent from the pre-filter baseline is treated conservatively — falls through to a content read, since "no baseline" must never be silently treated as "unchanged"). Update `hostModTimes`/`hostFileSizes` for every visited path regardless of branch, exactly as `pollHostDirectoryOnce` (Task 5.1.1b) already does, so reconciliation and the poller share one up-to-date baseline instead of two independently-drifting ones.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 3.4.1b: Pre-filter test coverage (~5 min)
- Three tests per this story's acceptance criteria: zero content reads on an all-unchanged fixture; exactly N content reads on a partially-changed fixture; a no-baseline fixture falls back to full read. Added to `HostDirectorySyncReconciliationTest.kt` alongside Epic 3.3's existing tests.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncReconciliationTest.kt`

#### Story 3.4.2: `reconnectHostDirectory`'s reconciliation runs non-blocking, not as a blocking startup step
**As a** returning web user with a large graph, **I want** the app to become interactive immediately
on reopen, **so that** live sync being enabled never makes ordinary app startup perceptibly slower
than today, even before Story 3.4.1's pre-filter numbers are confirmed safe.
**Decision (resolves pre-mortem.md P1 #1)**: `reconnectHostDirectory`'s call to `runHostReconciliation` (Story 2.2.1, Blocker 3's original remediation) changes from an awaited, sequential `Main.kt` startup step to a **non-blocking background task**: `Main.kt`'s startup sequence still calls `reconnectHostDirectory`, but the reconciliation portion specifically is `scope.launch`-ed rather than awaited inline, so the app reaches its normal interactive state on today's already-loaded OPFS/`cache` content (exactly as it does today, pre-feature) while reconciliation runs in the background and its outcomes (`HostChangedConflict` → `onHostConflict`, `HostOnlyNew` → import, `BrowserOnlyNeedsPush` → enqueue) stream in as they're discovered, exactly like a live update. This is chosen over keeping it awaited-blocking because: (a) `design/ux.md` Surface 8's "Connecting to folder…" progress UI (Story 3.1.2) was designed for the one-time `connectHostDirectory` opt-in click, which *is* a deliberate, user-initiated wait the user is already primed for — it was never designed to cover an unattended, automatic-on-every-launch wait, and stretching it to block ordinary app open would contradict `research/ux.md`'s own "no spinners" trust-signal finding for steady-state usage; (b) Story 3.4.1's pre-filter makes the common (nothing changed) case cheap, but a non-blocking design is strictly safer regardless of the pre-filter's measured numbers — it removes reconciliation cost from the startup critical path entirely rather than merely shrinking it. `connectHostDirectory`'s reconciliation (the one-time opt-in, Epic 3.1) remains awaited-blocking, since Story 3.1.2's progress UI is purpose-built for exactly that wait.
**Acceptance Criteria**:
- `Main.kt`'s startup sequence (Task 2.2.1c) no longer awaits `runHostReconciliation`'s completion inline within `reconnectHostDirectory`; the app reaches its normal post-`preload()` interactive state without waiting on reconciliation, and `hostAccessStateFlow` transitions to `Granted` (permission-wise) before reconciliation itself has necessarily finished.
  - *Given* an 8,000+-file graph and a `runHostReconciliation` double that takes several seconds to complete, *When* `reconnectHostDirectory(graphId)` is called during `Main.kt` startup, *Then* the function returns (and the app proceeds to its interactive state) without waiting for the multi-second reconciliation to finish, and reconciliation's classifications (conflict/import/enqueue) apply asynchronously as they complete.
- `connectHostDirectory`'s reconciliation (Epic 3.1's one-time opt-in flow) remains awaited-blocking, unchanged — Story 3.1.2's progress UI still covers it.
  - *Given* a user clicks "Enable live folder sync," *When* `connectHostDirectory` runs, *Then* it still awaits `runHostReconciliation` to completion before returning `Granted` and closing the progress screen — no behavior change to this entry point.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified — `reconnectHostDirectory`)

##### Task 3.4.2a: Make `reconnectHostDirectory`'s reconciliation call non-blocking (~4 min)
- In `reconnectHostDirectory` (Task 2.2.1a), change `runHostReconciliation(handle, opfsPath)` from a direct suspend call to `scope.launch { runHostReconciliation(handle, opfsPath) }` before starting the Phase 4/5 loops and returning `Granted` — the function itself still returns promptly once permission is confirmed granted, without waiting on the walk. `connectHostDirectory` (Task 3.1.1a) is explicitly **not** changed by this task — it keeps its direct, awaited call, since Story 3.1.2's progress UI depends on that wait.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 3.4.2b: Update the `reconnectHostDirectory` reconciliation-parity test for non-blocking launch (~3 min)
- `HostDirectorySyncReconciliationTest.kt`'s `reconnectHostDirectory_should_RunHostReconciliationAndSetGranted_When_HandleFoundAndPermissionGranted` test (validation.md) is updated to assert reconciliation was *launched* (not necessarily completed) by the time `reconnectHostDirectory` returns, and that its outcomes (`onHostConflict`, etc.) still apply once the launched coroutine completes — a poll/write-through cycle mid-reconciliation is safe, since both operate on the same `cache`/`hostWritePending` state reconciliation is concurrently updating, with no new race introduced beyond what Epic 6's existing per-path/per-tick locks already handle.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncReconciliationTest.kt`

#### Story 3.4.3: Required large-graph (8,000+ file) reconciliation-cost benchmark
**As a** SteleKit maintainer, **I want** measured evidence of `runHostReconciliation`'s per-file and
total cost at this codebase's standard large-graph scale, **so that** Story 3.4.1's pre-filter and
Story 3.4.2's non-blocking decision are validated against real numbers, mirroring Epic 5.5's required
poller benchmark rather than left as an assumption.
**Acceptance Criteria**:
- A benchmark/regression test constructs a mocked host directory tree with 8,000+ files (reusing Task 5.5.1a's fixture generator) and measures `runHostReconciliation`'s wall-clock duration for: (a) a first-ever reconciliation (no baseline, full content-read walk — the `connectHostDirectory` case), and (b) a steady-state repeat reconciliation where nothing changed (the `reconnectHostDirectory` session-resume case, Story 3.4.1's pre-filter fully engaged).
  - *Given* 8,030 mocked host files with no prior baseline, *When* `runHostReconciliation` runs its first-ever pass, *Then* its wall-clock duration is recorded and asserted against an explicit upper bound chosen from the measurement itself (mirroring Story 5.5.1's "must complete within N seconds, not 'must be fast'" gate) — this is the bound `connectHostDirectory`'s progress UI (Story 3.1.2) is designed to cover.
  - *Given* the same 8,030 files, all unchanged since a prior reconciliation/poll established a baseline, *When* `runHostReconciliation` runs again, *Then* its wall-clock duration is asserted against a much tighter bound (this is the cost Story 3.4.2's non-blocking background task actually pays on every ordinary session resume) and zero content reads occur across all 8,030 files, matching Story 3.4.1's pre-filter guarantee.
- These measured numbers are recorded in this plan (mirroring Task 5.5.1d) before Phase 3 is considered done — if the steady-state cost is not comfortably cheap even as a background task (e.g. it meaningfully competes with the poller/UI thread for seconds), Story 3.4.2's non-blocking decision must be revisited, not silently shipped as originally drafted.
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncReconciliationBenchmarkTest.kt` (new)

##### Task 3.4.3a: First-ever (full-walk) reconciliation benchmark (~5 min)
- Per this story's first acceptance criterion, reusing Task 5.5.1a's 8,030-file fixture generator.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncReconciliationBenchmarkTest.kt`

##### Task 3.4.3b: Steady-state (pre-filtered) reconciliation benchmark (~5 min)
- Per this story's second acceptance criterion.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncReconciliationBenchmarkTest.kt`

##### Task 3.4.3c: Record the measured numbers and confirm/revise Story 3.4.2's decision (~3 min)
- Documentation/decision-recording step, required before Phase 3 is marked done, mirroring Task 5.5.1d.
- Files: none (plan/decision update only)

---

## Phase 4: Write-Through to Host Directory

### Epic 4.1: Write-through queue and coalescing flush scheduler
**Goal**: The structurally-independent third side-effect described in `research/architecture.md`
§1.2, mirroring the existing `markerWriteInFlight`/`markerWriteDirty` idiom, implemented entirely on
`HostDirectorySync` (Epic 1.6) — `PlatformFileSystem` only calls into it from `writeFile`/
`writeFileBytes`/`deleteFile` (Epic 4.3).

#### Story 4.1.1: `hostWritePending` + `scheduleHostWriteThrough`
**As a** web user, **I want** my edits to reach the host folder within roughly the existing 500ms
autosave latency budget, **so that** external tools (git, grep, my editor) see them promptly.
**Acceptance Criteria**:
- `scheduleHostWriteThrough(path, content)` adds `path` to `hostWritePending` and schedules a flush; a burst of calls to the same path within one flush cycle coalesces into one trailing write of the latest content (same shape as `scheduleMarkerWrite`'s coalescing).
  - *Given* `hostDirHandle` set for graph `g`, *When* `hostDirectorySync.scheduleHostWriteThrough("pages/Foo.md", "v1")` then `scheduleHostWriteThrough("pages/Foo.md", "v2")` are called in rapid succession (before the first flush completes), *Then* exactly one host write occurs, and its content is `"v2"` (not `"v1"`, and not two separate writes).
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified)

##### Task 4.1.1a: Add `hostWritePending` field and per-path in-flight tracking (~3 min)
- `private val hostWritePending = mutableMapOf<String, DirtyEntry>()` (repo-relative keys, matching `dirtySet`'s key convention — but on `HostDirectorySync`, a structurally different map instance from `PlatformFileSystem.dirtySet`) plus `private val hostWriteInFlight = mutableSetOf<String>()` and `private val hostWriteDirtyDuringFlush = mutableSetOf<String>()` for per-path coalescing (a set, not two scalars, since multiple paths can be mid-flush concurrently — unlike the single marker-write scheduler).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 4.1.1b: Implement `scheduleHostWriteThrough` (~5 min)
- Add `repoRelative` entry to `hostWritePending`; if `repoRelative in hostWriteInFlight`, mark `hostWriteDirtyDuringFlush` and return; else mark in-flight and `scope.launch { flushHostWrite(repoRelative, content); while (repoRelative in hostWriteDirtyDuringFlush) { hostWriteDirtyDuringFlush.remove(repoRelative); flushHostWrite(repoRelative, cacheAccess.get(fullPath) ?: continue-equivalent) }; hostWriteInFlight.remove(repoRelative) }`, matching `scheduleMarkerWrite`'s trailing-coalesce shape (`PlatformFileSystem.kt:147-161`) generalized to per-path and reading the latest content through `CacheAccess` rather than a direct `cache` field.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

### Epic 4.2: `flushHostWrite` — proactive permission check, freshness check + actual host write

#### Story 4.2.1: `flushHostWrite` performs a pre-write freshness check before overwriting
**As a** web user, **I want** a debounced write-through flush to never silently clobber a change an
external tool made during the debounce window, **so that** this matches `GraphWriter`'s existing
desktop pre-write conflict check (per `research/pitfalls.md` §2.2).
**Acceptance Criteria**:
- Before writing, `flushHostWrite` reads the current host file content and compares its hash against the last-known host hash (from `hostModTimes`/a parallel `hostContentHashes` map fed by the poller, Phase 5); on mismatch, routes through `onHostConflict` instead of overwriting.
  - *Given* `hostWritePending` contains `"pages/Foo.md"` with browser content `"browser edit"`, and between the edit and the flush an external tool wrote `"external edit"` to the actual host file (detected via a hash mismatch against the last poller-confirmed host hash), *When* `flushHostWrite("pages/Foo.md")` runs, *Then* the host file is **not** overwritten with `"browser edit"`, and `onHostConflict("pages/Foo.md", "external edit")` is called instead.
  - *Given* no external change occurred (host hash matches last-known), *When* `flushHostWrite("pages/Foo.md")` runs, *Then* the host file is written with the pending browser content, `hostWritePending` no longer contains the path, and `markWrittenByUs`-equivalent bookkeeping (Phase 5) is updated so the next poll tick doesn't self-trigger a conflict.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified)

##### Task 4.2.1a: Implement the pre-write freshness check (~5 min)
- Before writing, resolve the file handle from `hostDirHandle` (walk path segments via `getDirectoryHandle`/`getFileHandle`, reusing `opfsWriteFile`'s traversal shape but rooted at `hostDirHandle` instead of `getOpfsRoot()`), read its current content, compare `.hashCode()` against a `hostContentHashes: MutableMap<String, Int>` field on `HostDirectorySync` (populated by Phase 5's poller; if absent/never-polled, treat as "unknown, proceed" — the poller establishing a baseline on connect closes this gap in practice).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 4.2.1b: Perform the actual write (`createWritable`/`write`/`close`) (~5 min)
- On a fresh match, resolve/create the target `FileSystemFileHandle` under `hostDirHandle`, `createWritable()`, `write(content)`, `close()` — reusing `OpfsInterop.kt`'s `fileHandleCreateWritable`/`writableWrite`/`writableClose` functions (they operate on any `FileSystemFileHandle`, OPFS- or host-backed, per `research/stack.md` §5's confirmation these are API-identical). Wrap in `try/catch (e: Throwable)` → Epic 4.3.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 4.2.1c: Dequeue on success (~2 min)
- On successful write, `hostWritePending.remove(repoRelative)`, update `hostContentHashes[repoRelative] = writtenContent.hashCode()`, update `hostModTimes` optimistically (Phase 5 field, may be a no-op stub until Phase 5 lands). All fields are on `HostDirectorySync`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

#### Story 4.2.2: Paranoid-mode `writeFileBytes` write-through skips the hash guard
**As a** paranoid-mode user, **I want** encrypted `.md.stek` writes to reach the host folder too,
**so that** the requirements' explicit paranoid-mode scope note is satisfied.
**Acceptance Criteria**:
- `flushHostWrite` for a `.md.stek` path writes raw bytes via `createWritable()`/`write(buffer)`/`close()` and skips the content-hash freshness guard (binary content — same skip as `FileRegistry.markWrittenByUs`'s existing documented behavior for encrypted files).
  - *Given* `hostWritePending` contains `"pages/Secret.md.stek"` with pending bytes from `writeFileBytes`, *When* `flushHostWrite` processes it, *Then* the host write uses `writableWriteBuffer` (not `writableWrite`) and no hash comparison is performed beforehand — only a mtime-changed check, matching `FileRegistry.kt:120-125`'s documented "modTime change alone is sufficient signal" rule for encrypted files.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified)

##### Task 4.2.2a: `flushHostWrite` takes a sealed `HostWritePayload` (~5 min)
- **Payload shape, decided** (architecture-review.md Concern — Task 4.2.2a/4.3.1c): `flushHostWrite` takes `sealed interface HostWritePayload { data class Text(val content: String) : HostWritePayload; data class Bytes(val data: ByteArray) : HostWritePayload; data object Delete : HostWritePayload }`, defined in Phase 1 alongside `HostAccessState`/`ReconciliationOutcome` (Epic 1.3/1.4), not left as an ad hoc `.md.stek`-suffix string branch — matching the same type-driven-design rationale the Pattern Decisions table already applies to `ReconciliationOutcome`. `flushHostWrite(repoRelative: String, payload: HostWritePayload)`'s exhaustive `when` branches the freshness check to mtime-only for `Bytes`, uses `writableWriteBuffer`/`toJsArrayBuffer()` (existing `OpfsInterop.kt:150-154` extension) for `Bytes`'s actual write, and (Task 4.3.1c) dispatches `Delete` to `dirRemoveEntry`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (payload dispatch; type itself defined in Task 1.4.1d's `platform/HostWritePayload.kt`)

#### Story 4.2.3: Proactive `queryHandlePermission()` check before every flush attempt (adversarial-review.md Concern remediation)
**As a** web user, **I want** the app to check whether it still holds write permission *before*
attempting a host write, not only react after a write already failed, **so that** writes queued on a
soon-to-be-revoked grant get an early warning instead of each one discovering the revocation only via
its own thrown error. `research/pitfalls.md` §1.1 explicitly requires `queryPermission()` "before
each write-through batch... not just once at startup" — Task 4.4.1a's existing catch-block re-query
only fires *after* a write has already thrown, which is strictly weaker: it cannot prevent an
in-flight `createWritable()`/`write()` from being attempted in the first place.
**Acceptance Criteria**:
- `flushHostWrite` calls `queryHandlePermission(hostDirHandle!!)` as its first step, before the
  freshness check (Task 4.2.1a) or any `createWritable()`/`write()`/`close()` call; if the result is
  not `"granted"`, it short-circuits — no host write is attempted for that path — and routes through
  the same `PromptNeeded`/`Denied` mapping Task 4.4.1a's reactive catch-block already applies
  (`"prompt"` → `HostAccessState.PromptNeeded`, `"denied"` → `HostAccessState.Denied`), leaves the
  path in `hostWritePending` for retry once access is restored, and fires `onHostWriteFailed` once.
  - *Given* `hostWritePending` contains three independently-scheduled paths (`"pages/A.md"`,
    `"pages/B.md"`, `"pages/C.md"`, each queued via its own `scheduleHostWriteThrough` call) and the
    host directory's permission grant is revoked externally before any of their `flushHostWrite`
    invocations begins, *When* each path's flush runs, *Then* every one of the three independently
    calls `queryHandlePermission` first, observes a non-`"granted"` result, sets
    `hostAccessStateFlow.value` to `PromptNeeded`/`Denied` accordingly, and returns without ever
    calling `createWritable()` for that path — the revoked grant is caught by each flush's own
    proactive check, not discovered piecemeal via three separate thrown-error catches after writes
    already began.
  - *Given* permission is still `"granted"` at flush time, *When* `flushHostWrite` runs, *Then* the
    proactive check passes through with no observable side effect and the existing freshness-check
    → write → dequeue sequence (Tasks 4.2.1a–4.2.1c) proceeds exactly as before this story.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified)

##### Task 4.2.3a: Add the proactive `queryHandlePermission()` short-circuit to `flushHostWrite` (~5 min)
- At the top of `flushHostWrite`, before Task 4.2.1a's freshness check, add
  `val access = queryHandlePermission(hostDirHandle!!); if (access != "granted") { ...map access to
  PromptNeeded/Denied exactly as Task 4.4.1a's catch-block re-query already does, fire
  onHostWriteFailed, leave the path in hostWritePending, and return without writing... }`. Factor the
  `"prompt"`/`"denied"` → `HostAccessState` mapping used here and in Task 4.4.1a's catch block into
  one small private `mapPermissionResultToAccessState(result: String): HostAccessState` helper on
  `HostDirectorySync`, so the two call sites (proactive, pre-write; reactive, post-failure) cannot
  drift out of sync.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

### Epic 4.3: Wire write-through into `writeFile`/`writeFileBytes`/`deleteFile`
**Goal**: The third independent side-effect, exactly as specified in `research/architecture.md`
§1.2's code sketch.

#### Story 4.3.1: `writeFile`/`writeFileBytes`/`deleteFile` each call the new write-through path
**As a** web user, **I want** every save path (plain text, paranoid-mode bytes, delete) to
write through, **so that** no save path is silently excluded.
**Acceptance Criteria**:
- `writeFile` calls `hostDirectorySync.scheduleHostWriteThrough(path, HostWritePayload.Text(content))` after its existing `cache[path] = content; recordDirty(...); scope.launch { opfsWriteFile(...) }` lines, only when `hostDirectorySync.hostDirHandle != null`.
  - *Given* `hostDirHandle` set, *When* `writeFile("/stelekit/g/pages/Foo.md", "new content")` is called, *Then* `cache`, the git `dirtySet` (unchanged, both still `PlatformFileSystem` fields), the OPFS mirror, **and** `HostDirectorySync.hostWritePending` all reflect the write — four independent effects from one call, per `research/architecture.md` §1.2's code sketch, with the fourth now living on the extracted collaborator rather than a fourth `PlatformFileSystem` field.
  - *Given* `hostDirHandle == null` (no live sync connected for this graph), *When* `writeFile` is called, *Then* `hostWritePending` is untouched — behavior is byte-for-byte identical to pre-project `writeFile` (regression guard, verified in Phase 8 Epic 8.2).
- `deleteFile` calls a host-side `removeEntry` (via `dirRemoveEntry`, already used by `opfsDeleteFile`) against `hostDirHandle` when set.
  - *Given* `hostDirHandle` set and `pages/Old.md` present on the host, *When* `deleteFile("/stelekit/g/pages/Old.md")` is called, *Then* the host file is removed (verified via a subsequent `runHostReconciliation`-style read returning `null` for that path).
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` (modified — these three are `FileSystem`-interface touch points, one of the seven delegation call sites Epic 1.6 allows on `PlatformFileSystem`), `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (the delegated-to implementation, already built in Epic 4.1/4.2)

##### Task 4.3.1a: Wire `writeFile` (~2 min)
- Add `if (hostDirectorySync.hostDirHandle != null) hostDirectorySync.scheduleHostWriteThrough(path, HostWritePayload.Text(content))` as the last line of `writeFile`, after the existing three effects (`PlatformFileSystem.kt:267-276`). This one-line delegation is the entire diff `writeFile` needs — the coalescing/freshness-check/actual-write logic all lives in `HostDirectorySync`. Per Epic 1.7 (scope expansion), `scheduleHostWriteThrough`'s own internals now await that path's in-flight OPFS-write `Deferred` before enqueueing — this call site's one-line shape is unchanged, only `scheduleHostWriteThrough`'s body gained the await (Task 1.7.1b).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

##### Task 4.3.1b: Wire `writeFileBytes` (~2 min)
- Same one-line delegation pattern in `writeFileBytes` (`PlatformFileSystem.kt:299-305`): `if (hostDirectorySync.hostDirHandle != null) hostDirectorySync.scheduleHostWriteThrough(path, HostWritePayload.Bytes(bytes))`. Same Epic 1.7 note as Task 4.3.1a — the await happens inside `scheduleHostWriteThrough`, not at this call site.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

##### Task 4.3.1c: Wire `deleteFile` (~4 min)
- `flushHostWrite`'s `HostWritePayload.Delete` branch (decided in Task 4.2.2a) calls `dirRemoveEntry` against a handle resolved under `hostDirHandle`, all inside `HostDirectorySync`. Wire the one-line delegation into `PlatformFileSystem.deleteFile` (`PlatformFileSystem.kt:315-321`): `if (hostDirectorySync.hostDirHandle != null) hostDirectorySync.scheduleHostWriteThrough(path, HostWritePayload.Delete)`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 4.3.1d: Confirm `applyRemoteContent` (git auto-merge) deliberately does NOT write-through (~2 min)
- Add a code comment to `applyRemoteContent` (`PlatformFileSystem.kt:286-291`) explaining it intentionally never calls `hostDirectorySync.scheduleHostWriteThrough` for the same reason it skips `recordDirty` — merged-in remote git content is not a local edit needing push to the host folder either; the host folder should only ever receive content the user wrote in *this* browser tab. No behavior change, documentation only, but load-bearing for reviewers.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

### Epic 4.4: Write-through failure surfacing

#### Story 4.4.1: Host write failures become a first-class, user-visible error, never a silent `println`
**As a** web user, **I want** to know if my edit failed to reach the host folder, **so that** I
don't lose trust in the sync (per `research/pitfalls.md` §3's "silent-divergence" risk) — **and I
want a lost permission grant specifically to surface as a "reconnect" affordance, not to look like
normal in-progress syncing** (adversarial-review.md Concern remediation).
**Acceptance Criteria**:
- On `flushHostWrite` throwing (permission revoked, `NotFoundError`, quota), the path stays in `hostWritePending` (retried on next successful flush trigger) and a `DomainError.FileSystemError.WriteFailed(path, message)` is surfaced via the injected `onHostWriteFailed` callback (wired to `GraphLoader`'s existing `writeErrors` channel, not a new one).
  - *Given* `hostDirHandle` pointing at a now-deleted host directory, *When* `flushHostWrite("pages/Foo.md")` throws `NotFoundError`, *Then* `hostWritePending` still contains `"pages/Foo.md"`, `hostAccessStateFlow.value` transitions to `HostAccessState.Disconnected("NotFoundError")`, and the injected failure callback fires exactly once with a `DomainError.FileSystemError.WriteFailed`.
- **Permission-shaped failures (`NotAllowedError`, or any thrown error once a re-query confirms the grant is gone) transition `hostAccessStateFlow` to `PromptNeeded`/`Denied`, not left at `Granted`.** Previously only `NotFoundError` was reclassified, leaving a silently-revoked-permission user's badge indistinguishable from normal in-progress syncing (`research/pitfalls.md` §1.1's documented, expected revocation event).
  - *Given* `hostDirHandle`'s permission is revoked externally (e.g. via the browser's page-info UI) between two flushes, *When* `flushHostWrite("pages/Foo.md")` throws a `NotAllowedError`-shaped message, *Then* `HostDirectorySync` re-queries `queryHandlePermission(handle)` inside the catch block and sets `hostAccessStateFlow.value` to `HostAccessState.PromptNeeded` (if the query returns `"prompt"`) or `HostAccessState.Denied` (if `"denied"`) — never left at `Granted` — so `FolderSyncStatusBadge` (Story 2.3.1) immediately shows its "Reconnect folder"/"Grant access" affordance instead of a misleading "N changes syncing" state.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified), `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt` (modified)

##### Task 4.4.1a: Catch and classify host write failures, including permission loss (~7 min)
- Wrap Task 4.2.1b's write in `try/catch (e: Throwable)`; on `NotFoundError`-shaped messages, set `_hostAccessStateFlow.value = HostAccessState.Disconnected(e.message ?: "unknown")`; on `NotAllowedError`-shaped messages (or, defensively, any other thrown error — permission revocation is not guaranteed to surface a distinctly-named error per `research/pitfalls.md` §1.1), re-query `queryHandlePermission(hostDirHandle!!)` and map its result: `"prompt"` → `HostAccessState.PromptNeeded`, `"denied"` → `HostAccessState.Denied`; only if the re-query itself still returns `"granted"` (a genuinely transient failure — quota, brief I/O blip) does `hostAccessStateFlow` stay unchanged — **and this transient-failure branch also sets `_hostWriteStuckFlow.value = true`** (new `StateFlow<Boolean>` on `HostDirectorySync`, Task 4.4.1c consumes it), reset to `false` on the next successful `flushHostWrite`. This is the signal Task 4.4.1c's corrected `SyncDegraded` condition uses to distinguish "queue stuck while nominally still granted" from ordinary in-flight syncing. In every case, still surface the failure via the `onHostWriteFailed` callback. Never let the exception propagate uncaught (per this codebase's `CLAUDE.md` rule on uncaught coroutine `Throwable`s). All on `HostDirectorySync`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 4.4.1b: Add `onHostWriteFailed` injected callback + wire to `GraphLoader.writeErrors` (~4 min)
- `private var onHostWriteFailed: (DomainError.FileSystemError.WriteFailed) -> Unit = {}` on `HostDirectorySync`, set from `Main.kt` (`opfsFileSystem.hostDirectorySync.onHostWriteFailed = ...`) to a lambda that calls a new small forwarding method on `GraphLoader` (e.g. reuse the existing `_writeErrors.tryEmit(WriteError(path, 0, domainError))` pattern already used at `GraphLoader.kt:830` — add a public one-line forwarding function rather than making `_writeErrors` itself public, keeping the existing encapsulation).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`, `kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/GraphLoader.kt`

##### Task 4.4.1c: `SyncDegraded` indicator in `FolderSyncStatusBadge` (~4 min)
- **Corrected condition (was inverted relative to design/ux.md §4 Surface 3's state table — Consistency Blocker fix)**: `SyncDegraded` fires when `hostAccessState == HostAccessState.Granted && pendingWriteCount > 0 && hostWriteStuck` — matching ux.md's row 3 ("`Granted`, pending > 0, queue not draining, e.g. mid-permission-blip"), not this plan's previous inverted `pendingWriteCount > 0 && hostAccessState !is Granted` condition (which collided with `Denied`/`PromptNeeded`/`Disconnected`'s own unconditional top-precedence rows and violated ux.md Principle 2's "reconnect and sync-degraded must never share copy/affordance" rule). `hostWriteStuck` is Task 4.4.1a's new `StateFlow<Boolean>` on `HostDirectorySync`, set `true` by the transient-failure branch (a write fails but the permission re-query still confirms `"granted"` — exactly ux.md's "mid-permission-blip" case) and reset `false` on the next successful `flushHostWrite`. This distinguishes ux.md's row 3 (`SyncDegraded` — a write is failing while nominally still granted) from row 4 (`Granted`, pending > 0, `hostWriteStuck == false` — ordinary in-flight syncing, not degraded) using a signal Task 4.4.1a's write-failure handling already produces, rather than inventing new detection infrastructure.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/FolderSyncStatusBadge.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

### Epic 4.5: Write-through tests

#### Story 4.5.1: `HostDirectorySyncWriteThroughTest`
**As a** SteleKit maintainer, **I want** automated coverage of the write-through queue's coalescing,
freshness check, and failure handling, **so that** the mechanisms described in Epics 4.1–4.4 are
verified, not just implemented. Constructed against `HostDirectorySync` directly (fake
`CacheAccess`), except the one `hostDirHandle == null` regression check which targets
`PlatformFileSystem.writeFile`'s one-line delegation call site.
**Acceptance Criteria**:
- Tests cover: single write flushes to host; rapid coalescing collapses to one write of the latest content (Story 4.1.1's GWT); pre-write hash mismatch routes to conflict, not overwrite (Story 4.2.1's GWT); paranoid-mode bytes write skips the hash guard (Story 4.2.2's GWT); a proactive permission check short-circuits a flush before any write is attempted, once permission is no longer `"granted"` (Story 4.2.3's GWT, research/pitfalls.md §1.1); write failure keeps the path queued and surfaces via the failure callback (Story 4.4.1's GWT); `NotAllowedError`-shaped failures re-query permission and map to `PromptNeeded`/`Denied`, not left at `Granted` (Story 4.4.1's second GWT, adversarial-review.md Concern); `hostDirHandle == null` leaves `hostWritePending` untouched (Story 4.3.1's regression GWT).
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncWriteThroughTest.kt` (new)

##### Task 4.5.1a: Coalescing + successful flush tests (~6 min)
- Two tests per Story 4.1.1's acceptance criteria.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncWriteThroughTest.kt`

##### Task 4.5.1b: Freshness-check conflict test (~5 min)
- Per Story 4.2.1's first acceptance criterion.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncWriteThroughTest.kt`

##### Task 4.5.1c: Paranoid-mode bytes write-through test (~4 min)
- Per Story 4.2.2's acceptance criterion.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncWriteThroughTest.kt`

##### Task 4.5.1d: Failure-surfacing, permission-loss-mapping, and no-handle-regression tests (~7 min)
- Per Story 4.4.1's `NotFoundError` GWT, Story 4.4.1's `NotAllowedError`-re-query GWT (adversarial-review.md Concern remediation — asserts `hostAccessStateFlow` lands on `PromptNeeded`/`Denied` per the stubbed `queryHandlePermission` result, not left at `Granted`), both on `HostDirectorySync`, and Story 4.3.1's regression criterion (on `PlatformFileSystem`'s delegation call site — this one test targets `PlatformFileSystem` directly).
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncWriteThroughTest.kt`

##### Task 4.5.1e: Proactive permission-check short-circuit test (~5 min)
- Per Story 4.2.3's acceptance criteria: stub `queryHandlePermission` to return `"denied"`, enqueue
  three independently-scheduled paths via `scheduleHostWriteThrough`, and assert the mocked
  `createWritable` is never invoked for any of the three, `hostAccessStateFlow` transitions to
  `Denied`, and all three paths remain in `hostWritePending`. A second case stubs
  `queryHandlePermission` to return `"granted"` and asserts the existing freshness-check/write path
  (Task 4.5.1a's coverage) is unaffected — proving the proactive check is a pure pass-through on the
  happy path, not an added delay or side effect.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncWriteThroughTest.kt`

---

## Phase 5: External-Change Detection

### Epic 5.1: `HostDirectoryPoller` — async poll feeding the existing synchronous contract
**Goal**: Satisfy `FileRegistry`/`GraphFileWatcher`'s existing synchronous `getLastModifiedTime`/
`listFilesWithModTimes` contract from an inherently async source, per `research/architecture.md`
§1.1/§4 — the single load-bearing architectural decision of this sub-feature.

#### Story 5.1.1: `pollHostDirectoryOnce` walks the host tree and refreshes `hostModTimes`/`cache`
**As a** web user, **I want** external edits to my files to become visible in the app, **so that**
`git pull`, my text editor, or any other tool's changes show up without re-picking the folder.
**Acceptance Criteria**:
- `pollHostDirectoryOnce(dirHandle, opfsPath)` walks the host tree (cheap `File.lastModified`/`size` pre-filter per `research/pitfalls.md` §4, falling back to content read+hash only when the pre-filter signals a possible change), updates `hostModTimes[path]` and, for changed files, `cache[path]`.
  - *Given* `hostModTimes["/stelekit/g/pages/Foo.md"] == 1000` (last known) and the host file's actual `File.lastModified` is now `2000` with different content, *When* `pollHostDirectoryOnce` runs, *Then* `hostModTimes["/stelekit/g/pages/Foo.md"]` becomes `2000` and `cache["/stelekit/g/pages/Foo.md"]` is updated to the new content.
  - *Given* the host file's `File.lastModified`/`size` are unchanged since the last poll, *When* `pollHostDirectoryOnce` runs, *Then* no content read occurs for that file (verified via a call-count assertion on the mocked `getFile().text()` — the cheap pre-filter must short-circuit).
- **Added per adversarial-review.md Blocker 4**: for a `.md.stek` path whose pre-filter signals a possible change, the poller reads raw bytes (never `.text()`) and updates `bytesCache` via `cacheAccess.setBytes`, not `cache`/`cacheAccess.set`.
  - *Given* `hostModTimes["/stelekit/g/pages/Secret.md.stek"]` stale and the host file's `lastModified`/`size` changed, *When* `pollHostDirectoryOnce` runs, *Then* the poller calls `getFile().arrayBuffer()` (not `.text()`) for that path and `cacheAccess.setBytes` (not `cacheAccess.set`) is the method invoked.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified)

##### Task 5.1.1a: Add `hostModTimes`/`hostFileSizes` fields (~2 min)
- `private val hostModTimes = mutableMapOf<String, Long>()`, `private val hostFileSizes = mutableMapOf<String, Long>()` on `HostDirectorySync` (the size-based pre-filter needs its own map since `File.lastModified` alone can be coarse-grained per `research/pitfalls.md` §1's storage-backend caveat).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 5.1.1b: Implement the walk with mtime/size pre-filter, branching on `.stek` suffix (~10 min)
- Recursive walk (reuse the traversal from `runHostReconciliation`/`importUserDirToCache`); per file, `getFile()` once, compare `fileLastModified`/`fileSize` against `hostModTimes`/`hostFileSizes`; on a difference, branch on `path.endsWith(".md.stek")`: **true** → read raw bytes (`arrayBuffer()`) and update `bytesCache` via `cacheAccess.setBytes(...)`; **false** → read `.text()` and update `cache` via `cacheAccess.set(...)` (unchanged from the original task). Always update `hostModTimes`/`hostFileSizes` to current values regardless of whether content changed (so the next poll's pre-filter is accurate), for both branches.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 5.1.1c: Own-write suppression — skip paths currently in `hostWriteInFlight` (~3 min)
- Before comparing a path's mtime, skip it if it's in `hostWriteInFlight` (Epic 4.1's field, same `HostDirectorySync` instance) — the poller must not race a concurrent `flushHostWrite` for the same path and misclassify the app's own in-progress write as an external change.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

#### Story 5.1.2: Timer-based `HostDirectoryPoller` loop, visibility- and observer-health-aware
**As a** developer, **I want** the poller to run on its own cadence, independent of
`GraphFileWatcher`'s existing 5s poll, **so that** the two loops don't double I/O pressure on a
large graph. **As a** web user who leaves a SteleKit tab open in the background for hours, **I
want** the poller to back off while my tab is hidden and while `FileSystemObserver` is already
catching changes for me, **so that** SteleKit doesn't drain my battery/fan with `getFile()` calls
nobody is looking at (`research/ux.md` §4; pre-mortem.md P1).
**Acceptance Criteria**:
- A `scope.launch` loop calls `pollHostDirectoryOnce` every `effectivePollIntervalMs()` (base `hostPollIntervalMs`, default 10_000L, per Unresolved Question #2 — a provisional number, confirmed or revised by Epic 5.5's required benchmark before Phase 5 is done) while `hostDirHandle != null`, started by `reconnectHostDirectory`/`connectHostDirectory` and stopped when the handle is cleared (e.g. `Disconnected` state).
  - *Given* `hostDirHandle` set at time T, the tab visible, and no `FileSystemObserver` confirmed active, *When* 10 seconds elapse with no external change, *Then* `pollHostDirectoryOnce` has been called at least once and `hostModTimes` reflects the current (unchanged) state; the CPU cost is one directory walk, not a `FileRegistry`-triggered second walk (the two loops are loosely coupled, not synchronized to fire together).
- **Visibility backoff (pre-mortem.md P1, part 1)**: while `isTabHidden == true`, the loop's sleep uses `effectivePollIntervalMs()`'s widened value (base × `HIDDEN_POLL_BACKOFF_MULTIPLIER`, default 6x ≈ 60s) instead of the base interval, resuming the base cadence on the very next tick after the tab becomes visible again — this backoff is the loop's own steady-state cadence change, distinct from (and complementary to) Story 5.3.1's separate immediate-poll-on-regain trigger, which still fires its own extra poll the instant visibility is regained.
  - *Given* `hostDirHandle` set and the tab visible (ticking at the ~10s base cadence), *When* `document.visibilityState` becomes `"hidden"`, *Then* the very next scheduled tick sleeps for the widened interval (~60s), not 10s, and every subsequent tick continues at the widened cadence until visibility is regained.
  - *Given* the tab has been hidden for several widened-cadence ticks, *When* `document.visibilityState` becomes `"visible"` again, *Then* Story 5.3.1's trigger fires an immediate out-of-band poll, and the timer loop's own next scheduled tick reverts to sleeping for the base interval (~10s) rather than the widened one.
- **Observer-health backoff (pre-mortem.md P1, part 2)**: while `observerConfirmedActive == true`, the loop's sleep is likewise widened (base × `OBSERVER_HEALTHY_POLL_BACKOFF_MULTIPLIER`, default 6x ≈ 60s) — the poller is ADR-002's safety net, not an equally-frequent redundant mechanism, once the fast path is confirmed live.
  - *Given* a Chrome 133+ browser where `HostChangeObserver` construction + `observeHandle()` (Task 5.2.2a) succeeded for the current connection, *When* the timer loop computes its next sleep, *Then* it sleeps for the widened interval (~60s) even though the tab is visible and no external change has occurred.
  - *Given* both `isTabHidden == true` and `observerConfirmedActive == true` simultaneously, *When* the timer loop computes its next sleep, *Then* it uses `maxOf` of the two multipliers (still ~60s at the default 6x/6x), not their product (~360s) — the two backoff reasons do not compound.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified)

##### Task 5.1.2a: Implement the timer loop against `effectivePollIntervalMs()` (~5 min)
- `private var hostPollJob: Job? = null` on `HostDirectorySync`; a `startHostDirectoryPolling()` private method launching `scope.launch { while (isActive) { delay(effectivePollIntervalMs()); pollHostDirectoryOnce(...) } }` (delay is recomputed fresh each loop iteration, so a visibility/observer-health change mid-wait takes effect starting the *next* tick, not retroactively), called from `reconnectHostDirectory`/`connectHostDirectory`'s success paths (both already `HostDirectorySync` methods); `stopHostDirectoryPolling()` cancels the job, called when `hostDirHandle` is cleared.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 5.1.2b: `isTabHidden` tracking loop + `effectivePollIntervalMs()` (~6 min)
- Add `private var isTabHidden = false` and a dedicated `scope.launch { while (isActive) { jsVisibilityHiddenPromise().await<JsAny?>(); isTabHidden = true; jsVisibilityVisiblePromise().await<JsAny?>(); isTabHidden = false } }` loop in `HostDirectorySync`'s `init { }` (a state-tracking sibling to `PlatformFileSystem.kt:48-57`'s existing one-shot hidden-flush loop and Story 5.3.1's existing one-shot visible-poll loop — all three independently await the same two interop promises for different purposes, matching this codebase's established "narrow, single-purpose loop per concern" idiom rather than one shared dispatcher). Add `private fun effectivePollIntervalMs(): Long { val multiplier = maxOf(if (isTabHidden) HIDDEN_POLL_BACKOFF_MULTIPLIER else 1L, if (observerConfirmedActive) OBSERVER_HEALTHY_POLL_BACKOFF_MULTIPLIER else 1L); return hostPollIntervalMs * multiplier }` with both multiplier constants defined as `private const val HIDDEN_POLL_BACKOFF_MULTIPLIER = 6L` / `private const val OBSERVER_HEALTHY_POLL_BACKOFF_MULTIPLIER = 6L` at the top of the file.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

#### Story 5.2.1: `getLastModifiedTime`/`listFilesWithModTimes` read `hostModTimes` when connected
**As a** developer, **I want** the existing `FileRegistry.detectChanges` to see real data on web,
**so that** the entire `GraphFileWatcher` → `GraphLoader.externalFileChanges` →
`StelekitViewModel.observeExternalFileChanges` → `DiskConflictDialog` pipeline works unmodified.
**Acceptance Criteria**:
- `getLastModifiedTime(path)` returns `hostModTimes[path]` when `hostDirHandle != null` for that path's graph; falls back to `null` (today's behavior) otherwise.
  - *Given* `hostDirHandle` set and `hostModTimes["/stelekit/g/pages/Foo.md"] == 2000` (populated by a prior `pollHostDirectoryOnce`), *When* `FileRegistry.detectChanges("/stelekit/g/pages")` runs its next 5-second tick (existing, unmodified `GraphFileWatcher` poll), *Then* it calls `getLastModifiedTime` internally via `listFilesWithModTimes`, observes `2000 > <previously recorded 1000>`, reads the (already-updated) `cache` content, and emits an `ExternalFileChange` through the existing, unmodified `GraphLoader.externalFileChanges` flow.
  - *Given* `hostDirHandle == null` (no live sync), *When* `getLastModifiedTime` is called, *Then* it returns `null`, exactly as `PlatformFileSystem.kt:365` does today (regression guard).
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` (modified — these two overrides are `FileSystem`-interface touch points, delegation-only)

##### Task 5.2.1a: Override `getLastModifiedTime` (~3 min)
- Change `actual override fun getLastModifiedTime(path: String): Long? = null` (`PlatformFileSystem.kt:365`) to `= hostDirectorySync.hostModTimes[path]` — a one-line delegate; `hostDirectorySync.hostModTimes` itself already returns nothing meaningful (empty map) when `hostDirHandle == null`, since Task 5.1.1a's map is never populated until a handle is attached, so no separate null-check is needed at this call site.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

##### Task 5.2.1b: Override `listFilesWithModTimes` for a single-pass, non-per-file-call implementation (~4 min)
- Add `override fun listFilesWithModTimes(path: String): List<Pair<String, Long>>` on `PlatformFileSystem` delegating to a new `HostDirectorySync.listFilesWithModTimes(path)` method that returns `hostModTimes.entries.filter { it.key.startsWith("$path/") && !it.key.removePrefix("$path/").contains('/') }.map { it.key.removePrefix("$path/") to it.value }` when `hostDirHandle != null`, else `emptyList()`; `PlatformFileSystem`'s override falls through to the interface default (`listFiles(path).map { ... getLastModifiedTime ... }`) when the delegate returns empty — avoids N synchronous `getLastModifiedTime` calls when one map iteration suffices, mirroring why JVM already overrides this same method (`FileSystem.kt:28-29`'s KDoc).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

### Epic 5.2: `FileSystemObserver` fast path (per ADR-002)

#### Story 5.2.2: `HostChangeObserver` triggers an immediate targeted poll on change records
**As a** web user, **I want** external changes detected faster than the poll interval when the
browser supports it, **so that** the "same file state at the same time" mental model
(`research/ux.md` §2) holds as tightly as possible.
**Acceptance Criteria**:
- When `fileSystemObserverSupported() == true`, connecting a host directory also constructs a `FileSystemObserver` and calls `observe(hostDirHandle, { recursive: true })`; on receiving change records, `pollHostDirectoryOnce` is triggered immediately (not waiting for the next timer tick) for at least the changed paths.
  - *Given* Chrome 133+ with `hostDirHandle` connected and `HostChangeObserver` active, *When* an external process modifies `pages/Foo.md` on the host disk, *Then* a `"modified"` change record fires, and `hostModTimes["/stelekit/g/pages/Foo.md"]` is refreshed within roughly one event-loop tick — well under the 10s timer interval.
- An `"errored"` record falls back to a full `pollHostDirectoryOnce` for the whole tree (per Unresolved Question #1's default) rather than crashing or silently stopping detection.
  - *Given* an `"errored"` change record is received, *When* the observer callback processes it, *Then* `pollHostDirectoryOnce` runs for the entire `hostGraphOpfsPath` tree once, and the observer continues operating for subsequent records (does not tear down).
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified)

##### Task 5.2.2a: Construct and start `HostChangeObserver` on connect (~5 min)
- In `HostDirectorySync.reconnectHostDirectory`/`connectHostDirectory`'s success paths, if `fileSystemObserverSupported()`, call `newFileSystemObserver { records -> scope.launch { handleObserverRecords(records) } }` then `observeHandle(observer, hostDirHandle!!, recursive = true)`; store the observer reference for later feature-detect-gated teardown (not required this phase, but avoid a dangling reference leak — store in a field, e.g. `private var hostChangeObserver: JsAny? = null` on `HostDirectorySync`). On successful completion of both calls (no thrown exception), set `observerConfirmedActive = true` — this is the flag Task 5.1.2b's `effectivePollIntervalMs()` reads to widen the poll cadence (Story 5.1.2, pre-mortem.md P1 remediation); leave it `false` if `fileSystemObserverSupported()` is `false` or either call throws, and reset it to `false` when `hostDirHandle` is cleared (same place `stopHostDirectoryPolling()` is called).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

##### Task 5.2.2b: `handleObserverRecords` dispatch by record type (~5 min)
- Private `suspend fun handleObserverRecords(records: JsAny)` on `HostDirectorySync`: iterate records (interop helper from Task 1.5.4a), for `"appeared"`/`"modified"`/`"disappeared"`/`"moved"` call `pollHostDirectoryOnce` scoped to just that record's `relativePathComponents`-derived path (a targeted single-file variant of Task 5.1.1b's walk, or simply call the full walk if a targeted variant isn't worth the complexity for v1 — acceptable simplification, note in a code comment); for `"errored"`, call the full-tree `pollHostDirectoryOnce`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

### Epic 5.3: Visibility-triggered immediate recheck

#### Story 5.3.1: Tab regaining focus triggers an immediate poll tick
**As a** web user, **I want** switching back to the SteleKit tab after editing a file externally to
immediately show my changes, **so that** background-tab timer throttling (per
`research/pitfalls.md` §4) doesn't create a perceptible staleness window. **Scope note**: this
story is deliberately narrow — it adds one extra, immediate poll on the visibility-regain edge. It
does **not** own the baseline timer's steady-state cadence while hidden; that backoff is Story
5.1.2's `effectivePollIntervalMs()`/`isTabHidden` (Task 5.1.2b). The two are complementary, not
duplicative: 5.1.2 makes the *baseline* cheap while hidden, this story makes the *return* to
foreground instant.
**Acceptance Criteria**:
- A loop awaiting `jsVisibilityVisiblePromise()` (Task 1.5.5a) triggers `pollHostDirectoryOnce` immediately on each resolution, independent of the regular timer cadence.
  - *Given* the tab is backgrounded (`visibilityState == "hidden"`) for 30 seconds (3x the 10s poll interval) while an external edit occurs, *When* the user switches back to the tab (`visibilityState` becomes `"visible"`), *Then* `pollHostDirectoryOnce` runs within one event-loop tick of the visibility change, independent of whether a regular timer tick was also due.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified)

##### Task 5.3.1a: Wire the visibility-visible loop (~4 min)
- Add a `scope.launch { while (true) { jsVisibilityVisiblePromise().await<JsAny?>(); if (hostDirHandle != null) pollHostDirectoryOnce(hostDirHandle!!, hostGraphOpfsPath!!) } }` loop in `HostDirectorySync`'s `init { }` — separate from `PlatformFileSystem`'s existing `jsVisibilityHiddenPromise()`-based marker-flush loop (`PlatformFileSystem.kt:48-57`), which stays where it is since it's unrelated to host-directory sync (git dirty-marker flush on tab hide).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

### Epic 5.4: External-change detection tests

#### Story 5.4.1: `HostDirectorySyncExternalChangeTest`
**As a** SteleKit maintainer, **I want** end-to-end proof that a host-side change reaches
`DiskConflictDialog`'s trigger point without any `FileRegistry`/`GraphFileWatcher` code change,
**so that** Epic 5.1's core architectural bet is verified. This test necessarily wires a real
`PlatformFileSystem` (with its `hostDirectorySync`) since `FileRegistry`/`GraphFileWatcher` consume
the `FileSystem` interface, not `HostDirectorySync` directly — but the host-side state being
manipulated (`hostModTimes`, `pollHostDirectoryOnce`) lives on `hostDirectorySync`.
**Acceptance Criteria**:
- One test drives a simulated host mtime bump through `hostDirectorySync.pollHostDirectoryOnce` → asserts `FileRegistry.detectChanges` (real, unmodified instance, reading through `PlatformFileSystem.getLastModifiedTime`'s delegation) observes it on its next call → asserts `GraphFileWatcher.externalFileChanges` (real, unmodified instance) emits an `ExternalFileChange`. One test confirms own-write suppression: a `writeFile` immediately followed by a poll tick does not self-trigger a conflict. One test (per adversarial-review.md Blocker 4) confirms a changed `.md.stek` path updates `bytesCache` via the poller's bytes branch, not `cache`/text decode.
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncExternalChangeTest.kt` (new)

##### Task 5.4.1a: End-to-end mtime-bump-to-`ExternalFileChange` test (~8 min)
- Wire a real `FileRegistry`/`GraphFileWatcher` pair against the test's `PlatformFileSystem` instance (same construction `GraphLoader` uses internally), simulate a host poll updating `hostDirectorySync.hostModTimes`, call `fileRegistry.detectChanges` directly (bypassing the 5s timer for test speed), assert the emitted `ExternalFileChange`.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncExternalChangeTest.kt`

##### Task 5.4.1b: Own-write suppression test (~5 min)
- `writeFile` a path, then simulate the poller observing the resulting host mtime/content (same values just written); assert no `ExternalFileChange` is emitted (content-hash guard suppresses it, per `FileRegistry.detectChanges`'s existing unmodified logic).
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncExternalChangeTest.kt`

##### Task 5.4.1c: Paranoid-mode poller branch test (~4 min)
- Per this story's Blocker 4 acceptance criterion — mocked `.md.stek` file with changed `lastModified`/`size`; assert `getFile().arrayBuffer()` (not `.text()`) is called and `cacheAccess.setBytes` (not `set`) receives the update.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncExternalChangeTest.kt`

### Epic 5.5: Large-graph poller-cost benchmark (REQUIRED, not optional follow-up)
**Goal**: Close adversarial-review.md Blocker 6 — `pollHostDirectoryOnce` recursively walks the
entire host tree and calls `getFile()` (an async, IPC-costed call) once per file every tick,
regardless of graph size. This is a full-tree metadata walk, in direct tension with `CLAUDE.md`'s
standing "must not become O(graph) scan" rule and requirements.md's own NFR. This codebase has
standing precedent for exactly this class of risk (`LargeGraphWarmStartCrashTest`,
`QueryPlanAuditTest`, both cited in `CLAUDE.md`) — this epic is the equivalent regression coverage
for `HostDirectoryPoller`, and per the blocker's explicit recommendation, is a **required Phase 5
deliverable**, not deferred to an "empirical tuning" footnote (see the updated Unresolved Question
#2 above). **Also closes pre-mortem.md's remaining P1** (Story 5.5.2): a single-tick cost bound
alone doesn't prove the cumulative, hours-long background cost is actually bounded — Story 5.5.2
adds the cumulative-call-volume coverage for the visibility-paused and observer-widened cases that
the original version of this epic didn't cover.

#### Story 5.5.1: `HostDirectoryPollerBenchmarkTest` — tick cost at 8,000+ files
**As a** SteleKit maintainer, **I want** measured evidence of `pollHostDirectoryOnce`'s per-tick cost
at the same scale this codebase already stress-tests elsewhere, **so that** the shipped default poll
interval (10s) is validated against real numbers, not assumed safe.
**Acceptance Criteria**:
- A benchmark/regression test constructs a mocked host directory tree with 8,000+ files (matching the scale of `LargeGraphWarmStartCrashTest`'s 8,030-page graph) and measures `pollHostDirectoryOnce`'s wall-clock duration for a full tick where nothing changed (the steady-state, most-frequent case — every file's mtime/size pre-filter short-circuits).
  - *Given* a mocked `dirHandle` with 8,030 files (each with a stable, unchanged `lastModified`/`size` matching `hostModTimes`/`hostFileSizes`), *When* `pollHostDirectoryOnce` runs one full tick, *Then* its wall-clock duration is recorded and asserted against an explicit upper bound (a concrete number chosen from the measurement itself — e.g. "must complete within 2 seconds," not "must be fast" — so the test is a real regression gate, not a no-op assertion) and no per-file content read (`.text()`/`arrayBuffer()`) occurs for any of the 8,030 files (pre-filter short-circuit verified via a call-count assertion, the same style already used in Task 5.1.1's tests).
  - A second measurement covers the worst case: 100 of the 8,030 files (a plausible "just did a `git pull`" burst) have changed `lastModified`; the test asserts the tick still completes within a documented bound and that exactly 100 content reads occur (not 8,030) — proving the pre-filter, not the content read, is what keeps the walk cheap.
- The test's measured numbers are used to confirm or revise Unresolved Question #2's proposed 10s default **before Phase 5 is considered complete** — if the steady-state tick cost is not comfortably under the poll interval (e.g. exceeds ~10–20% of it), the default must be raised and that change recorded in this plan's Unresolved Questions section as resolved-with-a-different-number, not silently shipped as originally drafted.
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectoryPollerBenchmarkTest.kt` (new)

##### Task 5.5.1a: Build an 8,000+-file mocked `dirHandle` fixture (~6 min)
- Extend (or share) the mocked `dirHandle`/`listOpfsEntries` test double from Task 3.3.1a to synthesize 8,030 flat or nested file entries programmatically (not hand-authored), matching this codebase's existing `LargeGraphWarmStartCrashTest` fixture-generation convention where one exists, or a new lightweight generator otherwise.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectoryPollerBenchmarkTest.kt`

##### Task 5.5.1b: Steady-state (no-change) tick-cost benchmark (~5 min)
- Per this story's first acceptance criterion — asserts a wall-clock upper bound and a zero-content-read call count across all 8,030 files.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectoryPollerBenchmarkTest.kt`

##### Task 5.5.1c: Burst-change (100-of-8,030) tick-cost benchmark (~5 min)
- Per this story's second acceptance criterion — asserts exactly 100 content reads occur and the tick still completes within a documented bound.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectoryPollerBenchmarkTest.kt`

##### Task 5.5.1d: Record the confirmed (or revised) poll interval default (~3 min)
- Update this plan's Unresolved Question #2 and Task 5.1.2a's `hostPollIntervalMs` default in-line with whatever Task 5.5.1b/c's measured numbers support — this task is a documentation/decision-recording step, required before Phase 5 is marked done, not optional polish.
- Files: none (plan/decision update only, tracked alongside the implementation, not a code file)

#### Story 5.5.2: Visibility-paused and observer-widened cadence coverage (REQUIRED — pre-mortem.md P1 remediation)
**As a** SteleKit maintainer, **I want** measured evidence that the backgrounded-tab and
observer-healthy cases actually poll less often, not just that a single tick is cheap, **so that**
Story 5.1.2's `effectivePollIntervalMs()` backoff is a verified regression gate, not merely a claim
in a Given-When-Then. This directly closes pre-mortem.md's remaining P1: the original Epic 5.5 only
benchmarked raw per-tick cost, never cumulative call volume under the always-on-regardless cadence
this finding identified as the actual complaint (fan/battery drain over hours, not tick latency).
**Acceptance Criteria**:
- A test simulates an idle backgrounded tab (`isTabHidden = true`, `observerConfirmedActive = false`) for a simulated one-hour window (virtual time, not real `delay`) against the 8,030-file fixture and asserts the **cumulative** number of `pollHostDirectoryOnce` invocations (and therefore `getFile()` call volume) is bounded by the widened cadence, not the base one.
  - *Given* `isTabHidden = true` for a simulated 3,600 seconds with `hostPollIntervalMs = 10_000L` and `HIDDEN_POLL_BACKOFF_MULTIPLIER = 6L`, *When* the timer loop runs for that simulated duration, *Then* `pollHostDirectoryOnce` is invoked approximately 60 times (3,600s / 60s), not approximately 360 times (3,600s / 10s) — asserted with an explicit tolerance band, not an exact equality, since the loop's own `delay` scheduling isn't claimed to be sub-millisecond precise.
- A second test simulates the same one-hour window with the tab visible but `observerConfirmedActive = true`, asserting the same ~60-tick bound applies — proving the observer-health backoff is independently effective, not just the visibility one.
  - *Given* `isTabHidden = false`, `observerConfirmedActive = true` for a simulated 3,600 seconds, *When* the timer loop runs, *Then* `pollHostDirectoryOnce` is invoked approximately 60 times, matching the visibility-backoff case's bound.
- A third test asserts the combined-backoff case (both `isTabHidden = true` and `observerConfirmedActive = true`) still lands at the same ~60-tick bound, not a further-reduced ~10-tick bound — proving `effectivePollIntervalMs()`'s `maxOf` (not multiplicative) composition per Story 5.1.2's third acceptance criterion.
  - *Given* both flags `true` for a simulated 3,600 seconds, *When* the timer loop runs, *Then* the invocation count matches the single-backoff cases (~60), confirming the two backoff reasons do not compound.
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectoryPollerBenchmarkTest.kt` (modified — extends the fixture from Story 5.5.1, does not duplicate it)

##### Task 5.5.2a: Virtual-time harness for the timer loop (~6 min)
- Use `kotlinx.coroutines.test.runTest`'s virtual time (`TestScope`/`StandardTestDispatcher`) to advance simulated time across the 3,600s window without real wall-clock waiting, injecting the loop's `scope`/dispatcher as a test double the same way other `HostDirectorySync` tests already inject fakes for `scope.launch`-based loops (see Epic 5.1/5.2's existing test doubles for precedent).
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectoryPollerBenchmarkTest.kt`

##### Task 5.5.2b: Hidden-tab cumulative tick-count benchmark (~5 min)
- Per this story's first acceptance criterion.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectoryPollerBenchmarkTest.kt`

##### Task 5.5.2c: Observer-healthy cumulative tick-count benchmark (~5 min)
- Per this story's second acceptance criterion.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectoryPollerBenchmarkTest.kt`

##### Task 5.5.2d: Combined-backoff (`maxOf`, not multiplicative) regression test (~4 min)
- Per this story's third acceptance criterion.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectoryPollerBenchmarkTest.kt`

---

## Phase 6: Cross-Tab Coordination

### Epic 6.1: Per-write lock

#### Story 6.1.1: `flushHostWrite` is wrapped in a per-path `WebLock`
**As a** web user with the same graph open in two tabs, **I want** two tabs never to both write the
same file to the host folder simultaneously, **so that** `createWritable()`'s `'siloed'`-mode
last-write-wins race (`research/pitfalls.md` §1.5) never fires between tabs.
**Acceptance Criteria**:
- `flushHostWrite`'s freshness-check-through-close sequence (Task 4.2.1a–4.2.1c) runs inside `WebLock.withLock(FolderSyncLockNaming.writeLockNameFor(graphId, repoRelativePath)) { ... }`.
  - *Given* two tabs (T1, T2) both with `hostWritePending["pages/Foo.md"]` queued, *When* both call `scheduleHostWriteThrough` at nearly the same instant, *Then* only one tab's `flushHostWrite` body executes at a time for that path (verified via a shared mock lock counter never exceeding 1 concurrent holder), and the second tab's write, once it acquires the lock, re-checks freshness against the now-updated host state (Task 4.2.1a already does this check on every `flushHostWrite` call, so no additional logic is needed — the lock alone prevents interleaved `createWritable()` calls).
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified)

##### Task 6.1.1a: Wrap `flushHostWrite`'s write section in `WebLock.withLock` (~4 min)
- Wrap Task 4.2.1a–4.2.1c's body (freshness check through dequeue), inside `HostDirectorySync`, in `WebLock.withLock(FolderSyncLockNaming.writeLockNameFor(graphId, repoRelativePath)) { ... }`. Per `GitWriteLock`'s documented scope discipline (`GitWriteLock.kt:47-55`), this lock covers only this write's critical section — never held across multiple independent suspend calls.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

### Epic 6.2: Per-poll-tick lock

#### Story 6.2.1: `pollHostDirectoryOnce` is leader-for-one-tick, not a full leader election
**As a** web user with the same graph open in two tabs, **I want** only one tab's poll to actually
walk the directory each tick, **so that** two tabs don't double the host-directory read I/O and
both fire the same `DiskConflict` for the same external change.
**Acceptance Criteria**:
- Each `pollHostDirectoryOnce` invocation attempts `WebLock.withLock(FolderSyncLockNaming.pollLockNameFor(graphId), ...)`; a tab that cannot acquire it within the tick skips that tick's walk (safe no-op — OPFS is cross-tab-shared, so the losing tab's own next tick, or its next `cache` read, sees the winner's result).
  - *Given* two tabs both due for a poll tick at the same instant, *When* both call `pollHostDirectoryOnce`, *Then* exactly one tab performs the directory walk for that tick (the other's attempt to acquire the poll lock is skipped, not queued/blocked — matching the "skip this tick" semantics from `research/architecture.md` §3.2, not `GitWriteLock`'s blocking `withLock`).
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified), `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/WebLock.kt` (modified)

##### Task 6.2.1a: Add a non-blocking `WebLock.tryWithLock` variant (~5 min)
- New `suspend fun <T> tryWithLock(lockName: String, block: suspend () -> T): T?` using `navigator.locks.request(name, { ifAvailable: true }, callback)` (per `research/stack.md` §3's `ifAvailable: true` pattern) — returns `null` immediately if the lock is already held, rather than blocking. New `js()` variant of `jsRequestLockHandle` accepting the `ifAvailable` option and exposing whether the lock was actually granted (the callback receives `lock === null` when `ifAvailable: true` and the lock was busy — this must be surfaced to the Kotlin caller as the `null` return).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/WebLock.kt`

##### Task 6.2.1b: Wrap `pollHostDirectoryOnce`'s call sites in `tryWithLock` (~4 min)
- Both the timer loop (Task 5.1.2a) and the visibility-triggered loop (Task 5.3.1a), both on `HostDirectorySync`, call `WebLock.tryWithLock(FolderSyncLockNaming.pollLockNameFor(graphId)) { pollHostDirectoryOnce(...) }`; a `null` result (lock busy) is a silent skip (log at debug level only, not a user-visible event).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

### Epic 6.3: Cross-tab coordination tests

#### Story 6.3.1: `WebLockTest` — `tryWithLock` non-blocking semantics
**As a** SteleKit maintainer, **I want** the new non-blocking lock variant tested independently of
this feature's business logic, **so that** a future consumer (this feature or another) trusts its
contract.
**Acceptance Criteria**:
- `tryWithLock` returns the block's result when the lock is free; returns `null` immediately (does not block) when another `withLock`/`tryWithLock` call already holds the same name.
  - *Given* `WebLock.withLock("l") { delay(1000) }` is running (holds the lock), *When* `WebLock.tryWithLock("l") { "should not run" }` is called concurrently, *Then* it returns `null` well before the 1000ms delay completes (asserted via a wall-clock bound in the test, e.g. completes in <100ms).
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/WebLockTest.kt` (extended — created in Task 1.1.1c with basic `withLock` coverage; this story adds the `tryWithLock` cases to the same file)

##### Task 6.3.1a: Extend `WebLockTest` with `tryWithLock` cases (~6 min)
- Two cases per the acceptance criteria, added to the file Task 1.1.1c already created, run against a real browser Web Locks implementation (wasmJs test target, not mocked — Web Locks has no meaningful mock that preserves its actual contention semantics).
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/WebLockTest.kt`

#### Story 6.3.2: Two-"tab" simulation test for write and poll locking
**As a** SteleKit maintainer, **I want** a test simulating two `HostDirectorySync` instances
(same-origin, sharing the real `navigator.locks` registry within one test page) contending for the
same graph, **so that** Epic 6.1/6.2's actual integration (not just the raw lock primitive) is
verified.
**Acceptance Criteria**:
- Two `HostDirectorySync` instances, both configured with the same `graphId` and both calling `scheduleHostWriteThrough` for the same path near-simultaneously, produce exactly one host write (not two interleaved ones) — asserted via a write-count instrumentation hook on the mocked `createWritable`.
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncCrossTabTest.kt` (new)

##### Task 6.3.2a: Implement the two-instance contention test (~8 min)
- Instantiate two `HostDirectorySync`s directly in one test (each with its own fake `CacheAccess`, both real Web Locks, same browser context — this is what makes the simulation valid without a second literal tab, and requires no `PlatformFileSystem` instance per Task 1.6.1c's independence guarantee), both targeting the same mocked `hostDirHandle`/`graphId`; trigger concurrent `scheduleHostWriteThrough` calls; assert the write-through mock records exactly one `createWritable()` invocation for the contended path.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncCrossTabTest.kt`

#### Story 6.3.3: Losing-tab cache convergence is bounded, not immediate — the ~20s worst-case staleness claim is tested, not just asserted in prose (adversarial-review.md Concern remediation)
**As a** SteleKit maintainer, **I want** the Pattern Decisions table's "Cross-tab coordination" row
claim — "a losing tab sees the winner's result on its own next tick" — to have a concrete regression
test, **so that** the implied worst-case staleness window (up to ~2x the poll interval, ~20s at the
proposed 10s default) is a measured, enforced bound rather than an unverified assertion.
**Acceptance Criteria**:
- Two `HostDirectorySync` instances (same shape as Story 6.3.2 — same `graphId`, sharing the real
  `navigator.locks` registry and a shared mocked OPFS/host-directory state) are driven through
  simulated poll ticks (virtual time, same harness idiom as Task 5.5.2a) where instance A wins the
  per-poll-tick lock on tick N and applies a host-side change that instance B's tick N attempt is
  locked out of (`WebLock.tryWithLock` returns `null` for B on that tick, per Story 6.2.1). The test
  asserts B's own in-memory state (`cache`/`hostModTimes`, read via `CacheAccess`) converges to match
  A's by B's own next successful, un-contended tick (tick N+1 — the ~2x-poll-interval worst case the
  Pattern Decisions table describes) — **and not sooner**: a regression assertion confirms B's state
  has **not** yet converged immediately after tick N, before B's own next tick has run, proving
  convergence genuinely depends on B's own poll mechanism running again rather than happening to
  already match by coincidence of shared virtual-time scheduling.
  - *Given* instance A wins the poll lock on tick N and applies a change to the shared mocked host
    state that B does not itself observe on tick N (B's `tryWithLock` returned `null`), *When* the
    test reads B's `cache` state immediately after tick N, *Then* it does **not** yet reflect A's
    change (proving B does not silently share A's in-memory result out-of-band, and the mechanism is
    genuinely "next tick," not something that happens to work by test-scheduling coincidence).
  - *Given* the same setup, *When* the test advances virtual time through B's own next
    un-contended poll tick (tick N+1, at most one `effectivePollIntervalMs()` later), *Then* B's
    `cache`/`hostModTimes` now matches A's post-change state — confirming convergence happens via B's
    own polling mechanism, within the stated bound, and no later than it.
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncCrossTabTest.kt` (modified — extends Story 6.3.2's file, same fixture shape)

##### Task 6.3.3a: Build the two-instance, lock-contended poll-tick harness (~7 min)
- Extend Story 6.3.2's two-`HostDirectorySync`-instance setup with a virtual-time poll-tick driver
  (reusing Task 5.5.2a's `runTest`/`TestScope` idiom) and a shared mocked host-directory state both
  instances read from; force instance A to win `WebLock.tryWithLock(pollLockNameFor(graphId))` on a
  specific tick (e.g. by having A acquire the lock first in test setup, or by stubbing the mock lock
  registry's grant order deterministically) so the test is not flaky on real Web Locks' actual
  contention timing.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncCrossTabTest.kt`

##### Task 6.3.3b: Assert bounded, next-tick convergence — not immediate, not missed (~5 min)
- Per this story's two acceptance criteria: assert non-convergence immediately after tick N, then
  assert convergence by tick N+1, bounded by `effectivePollIntervalMs()` (i.e. the ~20s worst case at
  the proposed 10s base interval when neither backoff multiplier is active).
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncCrossTabTest.kt`

---

## Phase 7: Rename/Move Propagation

### Epic 7.1: `HostRenameOp` — write-new, verify, delete-old

#### Story 7.1.1: `renameFile` override propagates in-app renames to the host folder
**As a** web user, **I want** renaming a page in the app to rename the corresponding file on my
host folder, **so that** git/grep/my editor see the rename, not a duplicate.
**Acceptance Criteria**:
- `override fun renameFile(from: String, to: String): Boolean` (currently falling through to the interface default `false` on wasmJs, per `research/architecture.md` §1's "pre-existing gap" note) writes the content under the new host path, verifies the new file's content matches, then deletes the old host path — never relying on `FileSystemHandle.move()`.
  - *Given* `hostDirHandle` set and `pages/Old.md` present on the host with content `"body"`, *When* `renameFile("/stelekit/g/pages/Old.md", "/stelekit/g/pages/New.md")` is called, *Then* the host directory ends up with `pages/New.md` containing `"body"` and no `pages/Old.md`, and the operation is scheduled through the same `hostWritePending`/lock machinery as a normal write (Epic 4.1, Epic 6.1) — not a separate, unlocked code path.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt` (modified — `renameFile` is one of the seven `FileSystem`-interface delegation touch points), `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified — the actual two-phase protocol)

##### Task 7.1.1a: Implement `renameHostFile` on `HostDirectorySync`, delegated from `PlatformFileSystem.renameFile` (~6 min)
- On `HostDirectorySync`: `suspend fun renameHostFile(from: String, to: String, content: String)`: `scheduleHostWriteThrough(to, HostWritePayload.Text(content))`; after that flush succeeds (chain via the existing coalescing-then-dequeue completion, or a small explicit continuation), call the delete path (Task 4.3.1c's `HostWritePayload.Delete` dispatch for `from`) — sequenced so the new file is confirmed written before the old one is removed (Task 7.1.1b's verification step is what "confirmed" means concretely). On `PlatformFileSystem`: `override fun renameFile(from: String, to: String): Boolean`: read `cache[from]`, if null return `false` (nothing to rename); `cache[to] = content; cache.remove(from)` (the existing `cache`-mirroring responsibility stays on `PlatformFileSystem`, since it applies regardless of whether host sync is active); if `hostDirectorySync.hostDirHandle != null`, `scope.launch { hostDirectorySync.renameHostFile(from, to, content) }`.
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/PlatformFileSystem.kt`

##### Task 7.1.1b: Verify-before-delete step (~4 min)
- Inside `HostDirectorySync.renameHostFile`, after the new-path write succeeds (Task 4.2.1c's dequeue), read the new host file back and compare its hash against the just-written content before proceeding to delete the old path — closes the "crash between write-new and delete-old" window from `research/pitfalls.md` §2's item 3 as tightly as a browser sandbox allows (still not atomic, but never deletes the old file until the new one is confirmed present with matching content).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

#### Story 7.1.2: An interrupted rename leaves a visible, non-destructive duplicate — no auto-delete heuristic
**As a** web user whose tab crashed mid-rename, **I want** the app to never silently delete a file it
merely *guesses* is a stale rename artifact, **so that** two unrelated pages that happen to share
identical content (routine in a Logseq-style outliner — empty journal pages, template stubs,
boilerplate) are never destroyed by a heuristic that got it wrong.
**Decision (supersedes the plan's original content-hash-match auto-delete draft, per
adversarial-review.md Blocker 5)**: the content-hash-match heuristic that scheduled the "old" half of
an interrupted rename for **host deletion** is dropped entirely. `research/pitfalls.md` §2 item 3
already established the accepted fallback for this exact case: an interrupted rename leaves two files
on the host disk, "silently duplicating content rather than losing it" — a visible, recoverable
artifact, not data loss. This project chooses to accept that duplication outcome rather than risk
deleting a legitimate page, over building a stronger correlation signal (e.g. a persisted
rename-intent log) — see the Pattern Decisions table's "Interrupted-rename artifact handling" row for
the full trade-off. A `HostOnlyNew` path from an interrupted rename is therefore imported as an
ordinary new page by Task 3.2.2b, exactly like any other host-only file — **no special-cased
deletion path exists in this project at all.**
**Acceptance Criteria**:
- `runHostReconciliation`, on finding a `HostOnlyNew` path whose content hash coincidentally matches another path already present in `cache`, imports it as an ordinary new page (Task 3.2.2b's normal `HostOnlyNew` action) and emits a non-destructive observability log line — it never deletes any host file as a side effect of this coincidence.
  - *Given* a host directory where `pages/Old.md` and `pages/New.md` both exist with identical content (an interrupted rename that completed the write-new step but crashed before delete-old), and `cache` reflects the post-rename state (`pages/New.md` only, matching `web-git-writeback`'s already-completed in-app rename), *When* `runHostReconciliation` runs on next connect, *Then* `pages/Old.md` is classified `HostOnlyNew` and imported into `cache` as a normal page (both `pages/Old.md` and `pages/New.md` now present, on both host and in `cache`, byte-identical) — no host deletion call is made for either path — and a single log line is emitted: `println("[SteleKit] reconciliation: possible stale-rename duplicate: pages/Old.md matches content of pages/New.md")`.
  - *Given* two genuinely unrelated new pages that happen to share identical (e.g. empty) content, both `HostOnlyNew`, *When* `runHostReconciliation` runs, *Then* both are imported normally and neither is deleted — the log line fires (a false-positive "possible duplicate" note) but has zero destructive effect, which is the entire point of dropping the heuristic's delete action.
**Files**: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt` (modified)

##### Task 7.1.2a: Log-only stale-rename-duplicate detection in `runHostReconciliation` (~4 min)
- After (not instead of) Task 3.2.2b's normal `HostOnlyNew` import action, check (via `cacheAccess`) whether the newly-imported path's content hash matches any *other* path already present in `cache` under the same graph; if so, `println("[SteleKit] reconciliation: possible stale-rename duplicate: $path matches content of $otherPath")` and continue — **no deletion, no host mutation, no queue entry**. This is purely an observability aid for a maintainer/user grepping logs, not a corrective action. Document in a code comment why: a coincidental content match between two genuinely-unrelated pages is common enough in this domain (empty/boilerplate pages) that auto-deleting on this signal alone was assessed as a net-negative trade — worse than the interrupted-rename duplication it would "fix" (adversarial-review.md Blocker 5).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`

### Epic 7.2: Rename tests

#### Story 7.2.1: `renameFile` round-trip and interrupted-rename recovery tests
**As a** SteleKit maintainer, **I want** both the happy path and the crash-recovery path tested,
**so that** Epic 7.1's two-phase protocol is verified end to end.
**Acceptance Criteria**:
- One test per Story 7.1.1's and Story 7.1.2's Given-When-Then.
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncRenameTest.kt` (new)

##### Task 7.2.1a: Round-trip rename test (~5 min)
- Per Story 7.1.1's acceptance criterion, exercising `HostDirectorySync.renameHostFile` directly (fake `CacheAccess`), plus one thin test on `PlatformFileSystem.renameFile` confirming it delegates.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncRenameTest.kt`

##### Task 7.2.1b: Interrupted-rename leaves a non-destructive duplicate, log-only (~5 min)
- Per Story 7.1.2's (revised) acceptance criteria — asserts both old and new paths remain present in `cache` and on the mocked host after reconciliation (no deletion call made against either), and that the "possible stale-rename duplicate" log line fires exactly once. A second case asserts the same "import both, log, never delete" behavior for the coincidental-match (non-rename) scenario, proving the dropped heuristic cannot destroy an unrelated legitimate page.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncRenameTest.kt`

---

## Phase 8: Status UX, Settings Entry Point, Fallback Regression Guard, Accessibility

### Epic 8.1: Finish the settings entry point and status surfaces
**Goal**: Tie together Phases 2–7's plumbing into the two user-facing affordances requirements.md
calls for: the resume-access banner (Phase 2, already built) and the "enable on existing graph"
settings action (Phase 3, already built) — this epic is the final polish/wiring pass.

#### Story 8.1.1: `FolderSyncStatusBadge` reflects all states end-to-end
**As a** web user, **I want** one consistent status indicator across connect, resume, write-through,
detection, and error states, **so that** the trust-signal goal from `research/ux.md` §4 is met by a
single coherent surface, not several disconnected ones.
**Acceptance Criteria**:
- The badge correctly renders, in order of precedence: `Disconnected` (highest — needs action) → `Denied`/`PromptNeeded` (needs action, independent of pending-write count — these already own top precedence unconditionally per ux.md rows 1-2) → `SyncDegraded` (`Granted`, pending writes > 0, queue stuck — per Task 4.4.1c's corrected condition, **not** "not fully granted", which would collide with the previous two states) → pending-writes-count (`Granted`, writes in flight, not stuck) → idle/connected (steady state) → not rendered (`NotApplicable`).
  - *Given* `hostAccessStateFlow.value == HostAccessState.Granted` and `hostWritePendingCountFlow.value == 3`, *When* the badge renders, *Then* it shows "3 changes syncing to `<dirName>`" (not the idle/connected copy, since there are pending writes to report).
  - *Given* `hostAccessStateFlow.value == HostAccessState.Granted` and `hostWritePendingCountFlow.value == 0`, *When* the badge renders, *Then* it shows the idle/connected copy ("Synced to `<dirName>`").
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/FolderSyncStatusBadge.kt` (modified)

##### Task 8.1.1a: Implement the precedence-ordered `when` dispatch (~5 min)
- Combine `hostAccessStateFlow` and `hostWritePendingCountFlow` — both threaded into `App(...)` as the nullable `StateFlow` parameters decided in Task 2.3.1c (following the `localChangesCountFlow` precedent, not `expect`/`actual`), `collectAsState()` at the `App.kt` call site and passed down as plain params so `FolderSyncStatusBadge` itself stays platform-agnostic and testable — into the single ordered `when` described in the acceptance criteria.
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/FolderSyncStatusBadge.kt`

##### Task 8.1.1b: Add `hostWritePendingCountFlow` to `HostDirectorySync` (~2 min)
- `private val _hostWritePendingCountFlow = MutableStateFlow(0)`, `val hostWritePendingCountFlow: StateFlow<Int> = _hostWritePendingCountFlow.asStateFlow()` on `HostDirectorySync` (not `PlatformFileSystem`), updated alongside every `hostWritePending` mutation (enqueue in `scheduleHostWriteThrough`, dequeue in `flushHostWrite`'s success path), mirroring the existing `dirtyFileCountFlow` pattern exactly. Wired into `Main.kt`'s `App(...)` call as `hostWritePendingCountFlow = opfsFileSystem.hostDirectorySync.hostWritePendingCountFlow` (Task 2.3.1c).
- Files: `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/platform/HostDirectorySync.kt`, `kmp/src/wasmJsMain/kotlin/dev/stapler/stelekit/browser/Main.kt`

### Epic 8.2: Fallback and non-regression guard
**Goal**: Prove the two explicit "no regression" success metrics from requirements.md — the
unsupported-browser fallback, and `web-git-writeback`'s dirty-set independence.

#### Story 8.2.1: Unsupported-browser path is fully inert
**As a** Firefox/Safari user, **I want** the app to behave exactly as it does today, **so that**
this project introduces zero risk for browsers outside its scope.
**Acceptance Criteria**:
- With `supportsNativeDirectoryPicker == false` (feature-detect returns false), `hostDirHandle` is never set by any code path this project adds, `FolderSyncStatusBadge`/`FolderSyncSettings` render nothing, and `Onboarding.kt`'s existing text-substitute fallback (`"Graph stored in browser private storage."`) is unchanged.
  - *Given* `showDirectoryPickerSupported() == false`, *When* the app starts and `hostDirectorySync.reconnectHostDirectory` is called (it still runs unconditionally, since the IndexedDB lookup itself is browser-API-agnostic), *Then* it resolves `HostAccessState.NotApplicable` immediately (no `queryPermission()` call attempted, since no handle exists to query) and no UI element from this project appears anywhere in the app.
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncFallbackRegressionTest.kt` (new)

##### Task 8.2.1a: Implement the fallback regression test (~5 min)
- Simulate `showDirectoryPickerSupported() == false` (test double), run through `hostDirectorySync.reconnectHostDirectory`, `PlatformFileSystem.pickDirectoryAsync`, and a normal `writeFile`/`readFile` cycle; assert `hostDirHandle` stays `null` throughout and every new field/flow this project adds (`hostWritePending`, `hostModTimes`, `hostAccessStateFlow`, all on `HostDirectorySync`) remains at its default/empty state.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/HostDirectorySyncFallbackRegressionTest.kt`

#### Story 8.2.2: `web-git-writeback`'s `dirtySet`/`.stele-dirty-set.json` are untouched
**As a** SteleKit maintainer, **I want** proof that this project's write-through never mutates the
git dirty-set, **so that** `requirements.md`'s explicit "must not regress `web-git-writeback`'s
dirty-file tracking" constraint is enforced by a test, not just a code-review promise.
**Acceptance Criteria**:
- A `writeFile` call with `hostDirHandle` set produces identical `dirtySet` contents and an identical `.stele-dirty-set.json` on-disk marker to the same call with `hostDirHandle == null` — the only observable difference is the new `hostWritePending` entry (on `HostDirectorySync`, never on `PlatformFileSystem.dirtySet`).
  - *Given* two otherwise-identical `PlatformFileSystem` instances, one with `hostDirectorySync.hostDirHandle` set and one without, *When* `writeFile("/stelekit/g/pages/Foo.md", "content")` is called on both, *Then* `getDirtySnapshot()` returns byte-identical maps from both instances, and both instances' `.stele-dirty-set.json` OPFS writes (captured via a write-interception test hook) are byte-identical. This test targets `PlatformFileSystem` directly (its `dirtySet` field, untouched by the Epic 1.6 extraction), asserting the extraction changed *only* where host-sync state lives, not `PlatformFileSystem`'s own pre-existing git-write-back behavior.
**Files**: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/PlatformFileSystemDirtySetIndependenceTest.kt` (new — deliberately still a `PlatformFileSystem`-scoped test, since it is verifying `PlatformFileSystem`'s own field, not `HostDirectorySync`'s)

##### Task 8.2.2a: Implement the dirty-set independence test (~6 min)
- Two-instance comparison per the acceptance criterion, reusing whatever OPFS-write interception pattern `PlatformFileSystemDirtyTrackingIntegrationTest.kt` (existing) already uses to capture `.stele-dirty-set.json` writes without a real OPFS backend.
- Files: `kmp/src/wasmJsTest/kotlin/dev/stapler/stelekit/platform/PlatformFileSystemDirtySetIndependenceTest.kt`

### Epic 8.3: Accessibility final pass
**Goal**: Close the two accessibility requirements from `research/ux.md` §3 not yet covered by
Epic 2.3's initial badge implementation — focus management for a badge-triggered follow-up surface,
and keyboard reachability verification across the whole new surface (badge + settings entry point).

#### Story 8.3.1: Focus handling when the badge's click opens a follow-up surface
**As a** keyboard/screen-reader user, **I want** focus to move predictably when I click "Reconnect
folder," **so that** I'm not left in an ambiguous focus state (per `research/ux.md` §3's note that a
badge-triggered flow doesn't get Compose's dialog focus trap automatically).
**Acceptance Criteria**:
- Clicking "Reconnect folder" (which triggers the browser's native permission prompt, not an in-app dialog) does not leave keyboard focus on a now-stale/removed element; focus returns to the badge itself (or the next logical element) once the browser prompt resolves.
  - *Given* keyboard focus on the "Reconnect folder" button, *When* the user activates it via Enter and the browser's native prompt resolves (either granted or denied), *Then* keyboard focus is still on a valid, visible, focusable element (the badge, now showing the updated state) — not lost to the document body.
**Files**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/FolderSyncStatusBadge.kt` (modified)

##### Task 8.3.1a: Explicit `FocusRequester` on the reconnect affordance (~4 min)
- Add a `remember { FocusRequester() }` to the reconnect button; after `requestHostDirectoryAccess` completes (success or failure), call `focusRequester.requestFocus()` explicitly rather than relying on default DOM focus behavior after a native browser prompt closes (which is not guaranteed consistent across Chromium versions per `research/ux.md` §3's caution).
- Files: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/ui/components/FolderSyncStatusBadge.kt`

#### Story 8.3.2: Keyboard-reachability verification
**As a** keyboard-only user, **I want** every new interactive element (badge reconnect button,
`FolderSyncSettings`'s "Enable live folder sync" button) to be Tab-reachable and Enter/Space-activatable, **so that** WCAG 2.1.1 is satisfied for this project's entire new surface.
**Acceptance Criteria**:
- Both new interactive elements are real `Button`/`clickable` composables reachable via Tab in DOM order, matching `research/ux.md` §3's explicit warning that Compose Multiplatform's web target has historically had gaps here.
  - *Given* the app loaded in a Chromium browser with `FolderSyncStatusBadge` showing `PromptNeeded`, *When* a user presses Tab repeatedly from the top of the page, *Then* the "Reconnect folder" button receives focus at some reachable point in the tab order, and pressing Enter while focused activates it (equivalent to a click).
**Files**: none new — verification task against Phase 2/3's existing composables

##### Task 8.3.2a: Manual/automated keyboard-reachability check (~5 min)
- Using the `ui-playwright` skill or an existing screenshot/interaction test harness in this repo, drive Tab navigation to both new buttons and confirm Enter/Space activation, on the actual wasmJs web build (not a JVM/Android screenshot test, since Compose-for-Web's DOM focus behavior is the thing being verified).
- Files: none new (manual/exploratory verification; promote to an automated Playwright script under this repo's existing web-testing conventions if one doesn't already exist for similar UI)

---

## Summary of what ships

| Phase | Required for MVP? | Rationale |
|---|---|---|
| 1 — Foundations | Yes | Everything depends on it. Epic 1.7 (OPFS-write-durability fix, scope expansion) is also required before Phase 4's write-through queue may enqueue any path — see the "OPFS-write durability" Pattern Decisions row |
| 2 — Handle retention & resume | Yes | Core scope item. Epic 2.1 (handle retention at pick time) ships before Phase 3; Epics 2.2–2.4 (session resume, permission UX, OPFS `persist()`) ship after Phase 3, since they now call `runHostReconciliation` (Blocker 3 remediation) — see the Dependency Visualization sequencing note. Epic 2.5 provides this phase's dedicated test coverage (previously missing — see Consistency Blocker remediation) |
| 3 — Reconciliation pass | Yes, and before Epics 2.2–2.4 | Data-loss-prevention gate — must ship before Phase 4 reaches users, **and** before Phase 2's resume epics, per Risk Control and the Blocker 3 remediation above. Epic 3.4's mtime/size pre-filter, non-blocking session-resume launch, and required large-graph reconciliation-cost benchmark (mirroring Epic 5.5) are also required before this phase is done — see pre-mortem.md P1 #1 remediation |
| 4 — Write-through | Yes | Core scope item |
| 5 — External-change detection | Yes | Core scope item. Epic 5.5's large-graph poller-cost benchmark is a required deliverable of this phase, not optional follow-up (Blocker 6 remediation) — the shipped default poll interval is validated by that benchmark, not assumed |
| 6 — Cross-tab coordination | Yes | Explicitly in-scope per requirements.md, not deferrable |
| 7 — Rename/move propagation | Yes | Explicitly in-scope per requirements.md's Rabbit Holes. Interrupted-rename artifacts are surfaced via a non-destructive log, not auto-deleted (Blocker 5 remediation) |
| 8 — Status UX, fallback guard, a11y | Yes | Requirements' UX/accessibility/no-regression success metrics depend on it |

No phase in this plan is optional polish — `requirements.md`'s Scope section places all eight areas
in-scope explicitly, and the appetite (Large, 3–6 weeks) was sized against that full scope by the
requirements-gathering phase, not against a reduced MVP.
