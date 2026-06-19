# Push Architecture — Failure Modes and Pitfalls

## 1. Emission Before Subscription (Race at Startup)

**The problem.** `DatabaseWriteActor` initializes its actor loop in `init {}`, so it is ready to
accept and process writes immediately at construction time. `BlockStateManager.observePage` is
called later, from a Compose `LaunchedEffect` on the first composition frame. If the actor emits
a `blockInvalidations` event between these two points — e.g., because `GraphLoader` queued a bulk
import before `observePage` ran — the event is delivered to zero subscribers and silently dropped.

**SharedFlow replay configuration.** The ADR-012 design specifies:

```kotlin
MutableSharedFlow<Set<PageUuid>>(
    extraBufferCapacity = 64,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
```

`replay = 0` (the default when `replay` is not passed) means the flow has no history: a subscriber
that arrives after an emission never sees it. `extraBufferCapacity = 64` only helps in-flight
subscribers that are slow to collect; it does not help a subscriber that has not yet subscribed.

**The right solution.** The invalidation signal must not be the sole trigger for the initial
block load. The design in ADR-012 already handles this correctly: `observePage` calls
`pullBlocksForPage(pageUuid)` unconditionally before subscribing to the invalidation flow (the
"Initial load" step in the pseudocode). The subscription catches *subsequent* changes; it does not
need to replay the initial state. As long as the initial pull happens before the subscription
loop, no startup race exists. The pitfall to watch for is a future refactor that removes the
initial pull and relies solely on invalidation — that would reintroduce the race.

**Secondary concern.** `replay = 1` would solve the race but introduces a different hazard: every
new subscriber for page B would immediately receive an invalidation for page A (whatever was last
written), firing an unnecessary one-shot query. `replay = 0` with an explicit initial pull is the
correct pairing.

---

## 2. Graph Switch Race (Stale Events from Old Actor)

**The problem.** `GraphManager.switchGraph` tears down the old graph on a background IO coroutine
and creates a new `RepositorySet` (and therefore a new `DatabaseWriteActor`) asynchronously. The
timing window is:

1. `_activeRepositorySet.value = null` — sets null before closing the driver.
2. `currentFactory?.close()` — closes the old DB connection.
3. A new `graphScope` coroutine starts and eventually sets `_activeRepositorySet.value = repoSet`.

Between steps 1 and 3, the old actor's channel may still have queued requests that are being
processed. When those complete, the old actor calls `onWriteSuccess` and (under the proposed push
model) would emit to `_blockInvalidations`. If `BlockStateManager` is still subscribed to the old
actor's `blockInvalidations` (because `App.kt` passes `repos.writeActor?.blockInvalidations` and
that reference hasn't changed yet), `BlockStateManager` would receive stale invalidations from
old-graph data and trigger re-queries against a null or already-closed repository — either
silently returning empty results or throwing on the closed driver.

**How to prevent it.** Three interlocking mechanisms are needed:

- `BlockStateManager` must clear all observed-page state (`observationJobs`, `_blocks`,
  `_activePageUuids`) when the graph switches. The current code does not do this automatically;
  `App.kt` drives it via `key(activeGraphId) { ... }` which recreates `BlockStateManager` on
  graph switch. That recreation is the correct guard: the old instance (and its subscription to
  the old invalidation flow) is cancelled with `close()`, and the new instance starts fresh.
- The new `BlockStateManager` must not receive the old actor's `blockInvalidations` reference.
  Since `activeRepositorySet` is `StateFlow<RepositorySet?>` and the actor is obtained from it,
  `App.kt` must re-wire `invalidationSource` at the same composition point where it re-creates
  `BlockStateManager` — i.e., inside `key(activeGraphId)`.
- `DatabaseWriteActor.close()` closes its channels but does not cancel in-flight coroutines that
  already completed their write and are executing `onWriteSuccess`. Under the push model the
  emission must happen inside `processRequest`, before the channel close, or be guarded by a
  `@Volatile isShuttingDown` flag that suppresses post-close emissions.

**Current exposure.** In the existing (pre-push) code, `onWriteSuccess` is already called after
graph-switch writes. The `wireCacheCallbacks` handler in `RepositoryFactory` calls
`sqlBlockRepo.evictBlock()` — which is safe because the old `SqlDelightBlockRepository` instance
simply evicts from its in-memory cache with no DB access. Under the push model, any emission
to `_blockInvalidations` post-close needs the same safety: the `MutableSharedFlow.tryEmit` will
succeed even if there are zero subscribers (no-op), so stale emissions are dropped harmlessly as
long as `BlockStateManager` has already been recreated. The risk is real only if `BlockStateManager`
stays alive past the graph switch — which the `key(activeGraphId)` pattern prevents.

---

## 3. Process Kill During Bulk Import

**The problem.** During bulk import (`GraphLoader.indexRemainingPages` / `loadDirectory`),
`DatabaseWriteActor` processes `SaveBlocks` requests in LOW priority batches. If the process is
killed mid-import (OOM kill, user force-stops), the DB contains whatever rows were committed
before the kill. The push model does not change this: blocks are always committed to the DB
before the actor emits any invalidation signal (emission only happens in `onWriteSuccess`, called
after `result.isRight()`). There is no in-memory-only state that diverges from the DB.

**The push model is safe here.** The `BlocksWritten` payload in Phase 2 would carry the same
block list that was just committed. On next startup, `observePage` performs an initial pull from
the DB (the fresh-start query), which reads exactly what was persisted. There is no "push payload
in flight" that could be lost — the actor is dead and the new instance starts from DB.

**The only genuine hazard is the resume path.** `BlockStateManager`'s in-memory `_blocks` is
populated from DB on startup. If Phase 2 allows `BlockStateManager` to accept pushed block lists
without verifying against the DB, and a bug causes a push to arrive before the initial DB pull
completes (unlikely but possible during rapid-startup unit tests with `UnconfinedTestDispatcher`),
the UI could show stale state from the last push instead of the current DB. This is prevented by
ensuring the initial pull always runs before the invalidation subscription loop starts collecting,
and that optimistic `_blocks` state is never written from a push payload unless the DB write has
already committed (the `onWriteSuccess` ordering guarantee).

---

## 4. iOS Single-Threaded Coroutine Concern

**The problem statement.** On iOS, `PlatformDispatcher.DB = Dispatchers.Default` (confirmed in
`kmp/src/iosMain/kotlin/dev/stapler/stelekit/coroutines/PlatformDispatcher.kt`). `DatabaseWriteActor`
runs its actor loop on `PlatformDispatcher.Default` (same value on iOS). `BlockStateManager` uses
`Dispatchers.Default` for its scope. The concern is whether emitting from a `SharedFlow` on the
actor's dispatcher and collecting on the main thread causes threading issues.

**Analysis.** Kotlin coroutines on iOS use a cooperative multithreaded runtime (since
`kotlinx.coroutines` 1.6.x with the new memory model). `Dispatchers.Default` on iOS is backed by
a thread pool (not a single thread), and `Dispatchers.Main` dispatches to the iOS main run loop.
`MutableSharedFlow.tryEmit` is thread-safe: it can be called from any thread and delivers to all
collectors asynchronously through their own collection coroutines. A collector running on
`Dispatchers.Main` will have the emission re-dispatched to the main thread via its own collector
coroutine's dispatcher — this is how all `StateFlow` and `SharedFlow` collection works in Compose.

**No special iOS hazard.** The proposed emission pattern (actor emits on its own `Default`
dispatcher → `BlockStateManager` collects on its `Default`-dispatched scope → Compose reads via
`collectAsState()` on the main thread) is the standard Kotlin coroutines cross-dispatcher pattern
and is safe on iOS. The old memory model (pre-1.6) had frozen-object restrictions that would have
made this problematic, but SteleKit targets the new memory model.

**One genuine iOS consideration.** All four `PlatformDispatcher` values on iOS resolve to
`Dispatchers.Default`. This means DB reads triggered by an invalidation signal fire on the same
pool as the actor write that emitted the signal. On JVM, `PlatformDispatcher.DB` is
`Dispatchers.IO` with a capped pool; concurrent reads are handled by the 8-connection JDBC pool.
On iOS, the single native SQLite driver serializes reads and writes through its own internal
locking — so concurrent dispatching to `Default` is fine; the driver handles contention. No
additional threading guards are needed.

---

## 5. Wildcard Sentinel Overuse

**The problem.** ADR-012 specifies that `WriteRequest.Execute` (arbitrary actor lambdas) emits a
`WILDCARD_PAGE_UUID` sentinel, waking all active `BlockStateManager` observers for a one-shot
re-query. If most writes go through `Execute`, the page-scoped invalidation benefit is lost.

**Actual `Execute` call count in `BlockStateManager`.** Eight write helpers in
`BlockStateManager` route through `writeActor.execute(...)`:

- `writeDeleteBulk` — `deleteBulk` (no page UUID on the typed request)
- `writeMoveBlock` — `moveBlock`
- `writeIndentBlock` — `indentBlock`
- `writeOutdentBlock` — `outdentBlock`
- `writeMoveBlockUp` — `moveBlockUp`
- `writeMoveBlockDown` — `moveBlockDown`
- `restorePageToSnapshot` — `deleteBlock` + `saveBlocks` (undo/redo)
- (Also: `saveBlock`, `updateBlockContentOnly`, `updateBlockPropertiesOnly`, `splitBlock`,
  `mergeBlocks`, `deleteBlockStructural` all ultimately call `execute` inside `DatabaseWriteActor`
  convenience methods.)

**From other callers.** `GraphLoader` uses `writeActor.execute(Priority.LOW)` for 5+ paths:
all `savePages`/`saveBlocks`/`deleteBlocksForPages` bulk import calls. `SqlDelightSearchRepository`
uses `execute` for FTS rebuild. `SqlDelightGitConfigRepository` uses `execute` for git config
writes.

**The scope of wildcard emission.** In the production hot path (user typing), the critical write
is `updateBlockContentOnly` → `writeActor.execute { blockRepository.updateBlockContentOnly(...) }`.
This would emit `WILDCARD_PAGE_UUID`. With 5 journal pages open, all 5 would fire a one-shot
re-query on every keypress. This is worse than Phase 1's goal of O(1) re-queries per write.

**Recommendation.** The `Execute` wrapper should not be the primary path for user-edit writes in
Phase 2. Instead, `updateBlockContentOnly`, `saveBlock`, `splitBlock`, `mergeBlocks`, and
`deleteBlock` should be promoted to typed `WriteRequest` subclasses that carry the `pageUuid`
field, allowing precise invalidation. The `execute` escape hatch remains for structural ops
(indent/outdent/move) and non-block writes (search, git config) where wildcard emission is
acceptable because they are low-frequency. For the hot typing path specifically, Phase 2 should
bypass the invalidation entirely and push block lists directly (the `BlocksWritten` signal),
making the wildcard question moot for that path.

**Count summary.** Of the ~40 `writeActor.execute()` call sites in `commonMain`:
- ~6 are in `BlockStateManager` for structural ops (indent, outdent, move, undo/redo) — these are
  user-initiated but low-frequency; wildcard is acceptable.
- ~5 are in `DatabaseWriteActor` convenience methods that wrap single-block ops
  (`saveBlock`, `updateBlockContentOnly`, etc.) — these are the hot path; they need typed requests.
- ~7 are in `GraphLoader` for bulk import — wildcard is acceptable (these pages are not in the
  active journal view during cold import).
- ~2 are in `SqlDelightSearchRepository` — non-block writes; wildcard is fine.
- ~2 are in `SqlDelightGitConfigRepository` — non-block writes; wildcard is fine.

The wildcard problem is real but concentrated: promoting 5 single-block convenience methods to
typed requests eliminates the hot-path wildcard entirely.

---

## 6. Test Isolation: InMemoryBlockRepository Without a Write Actor

**The problem.** `BlockStateManager` unit tests use `InMemoryBlockRepository` and do not wire a
`DatabaseWriteActor`. The proposed push model introduces a new signal path:
`actor.blockInvalidations → BlockStateManager.observePage → pullBlocksForPage`. Without an actor,
no invalidation events arrive. If tests verify that blocks update after a write but rely on the
push path, they will produce false positives (the update never happens but the test does not
assert it).

**How the current code degrades.** `BlockStateManager` already has a two-path design:
writes fall through to `blockRepository.saveBlock(block)` when `writeActor == null`. The
invalidation source would similarly be `null` (since `invalidationSource: SharedFlow<Set<PageUuid>>?`
is nullable per ADR-012). In `observePage`, the invalidation subscription would simply not start:

```kotlin
invalidationSource?.filter { ... }?.collect { ... }
```

The initial pull (`pullBlocksForPage`) still runs, so the initial state is correct. But no
subsequent invalidation events arrive — the in-memory test path receives updates only through
direct `_blocks.update` calls (optimistic updates in `updateBlockContent`, `addBlockToPage`, etc.),
not through invalidation-driven re-queries.

**Test isolation implications.**

1. Tests that call `blockRepository.saveBlock()` directly (simulating an external write) and then
   assert `blocksForPage()` reflects the new content will silently pass today (because the reactive
   `asFlow()` re-emits), but will silently fail after Phase 1/2 (because the reactive flow is
   removed from `observePage`). These are false negatives, not false positives. They will surface
   as test regressions.

2. Tests that verify the optimistic-update path (`updateBlockContent` → immediate `_blocks` update)
   are unaffected — the optimistic path does not use invalidation.

3. Tests that simulate "actor writes during background indexing" to verify that only the affected
   page re-queries are the acceptance-criterion tests (AC-1 from requirements.md). These require a
   real `DatabaseWriteActor` and cannot be implemented with `InMemoryBlockRepository`. They belong
   in `businessTest` or `jvmTest` with `SqlDelightBlockRepository`.

**Required test patterns.**

- Existing `BlockStateManager` unit tests with `InMemoryBlockRepository` should be audited to
  identify any that expect reactive re-emission after a direct repository write. These must either:
  (a) be converted to integration tests with a real actor, or
  (b) be rewritten to simulate the push by calling the same `_blocks.update` path that
  `pullBlocksForPage` would call (exposing a test helper `forcePushBlocksForPage`).
- AC-1 ("5 journal pages open, only page A written, 0 re-queries for pages B–E") requires
  injecting a `TestCoroutineScope` into a real `DatabaseWriteActor` + `BlockStateManager` pair
  with `SqlDelightBlockRepository` (or a `FakeBlockRepository` that records `getBlocksForPage`
  call counts). `InMemoryBlockRepository` is insufficient for this test.
- Requirement AC-4 ("all existing `BlockStateManager` tests pass without modification to test
  setup") is achievable only for tests that do not rely on reactive re-emission. Tests that do
  must be updated — this is not optional.
