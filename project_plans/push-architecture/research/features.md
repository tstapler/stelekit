# Research: Push-Based State Patterns in Comparable Apps

## Room + ViewModel (Android)

Room's `@Query + Flow` has the **exact same table-level notification problem** as SQLDelight. The underlying mechanism is `InvalidationTracker`, which uses SQLite triggers to detect changes. SQLite triggers only fire at the row level but Room's `InvalidationTracker.Observer.onInvalidated(Set<String> tables)` delivers only table names — not affected row IDs or predicates.

**Specific implications:**
- Every INSERT/UPDATE/DELETE on the `blocks` table wakes every active `@Query + Flow` subscriber, regardless of which `page_uuid` changed.
- There is no `WHERE page_uuid = ?` scoping at the invalidation layer; Room re-runs the full query after every table change.
- The `notifyObserversByTableNames()` method on `InvalidationTracker` explicitly accepts table names, confirming the granularity ceiling is "which tables changed."

**Room's workarounds:** Apps that hit this problem typically add debounce (`distinctUntilChanged()` + `conflate()`), scope queries to subsets, or move to a manual push pattern — none of which Room provides out of the box. SQLDelight is in the same position; there is no Row-ID-scoped reactive layer in either library.

**Sources:**
- [Room and Invalidation Tracking (CommonsWare)](https://commonsware.com/Room/pages/chap-processes-001.html)
- [InvalidationTracker API reference](https://developer.android.com/reference/android/arch/persistence/room/InvalidationTracker)
- [Room 🔗 Flow (Florina Muntenescu)](https://medium.com/androiddevelopers/room-flow-273acffe5b57)

---

## MVI / Redux-Style Patterns in KMP

MVI frameworks (MVIKotlin, Orbit MVI, Decompose) use an explicit **push model** — the intent handler (Executor) processes a command and emits new state, it does not trigger a re-query of the database. This is the architecture SteleKit's ADR-012 is converging toward.

**MVIKotlin specifics:**
- The `Executor` component accepts `Intent` and `Action`, performs async work (e.g., DB writes), then dispatches typed `Message` objects.
- The `Reducer` is a pure synchronous function that maps `(State, Message) → State`. It never queries the DB; the new state comes from the message itself.
- Importantly, the documentation explicitly warns: "By avoiding the Reducer and manipulating the State in the Executor you are abusing the library." The correct pattern is: *Executor does the write → dispatches a Message containing the new data → Reducer computes and emits the new State.* The data lives in the message, not a re-query.

**Orbit MVI:** Uses the same pattern — an `intent {}` block performs async work and calls `reduce { }` with the actual new state inline. No re-query step.

**How this maps to SteleKit's Phase 2:** The push model in ADR-012 Phase 2 (`BlocksWritten(pageUuid, List<Block>)`) is structurally identical to how MVIKotlin and Orbit work: the write path already has the data; it emits the data directly alongside the write event so the UI state is updated from the emitted payload rather than a DB re-query.

**Sources:**
- [MVIKotlin docs — store.md](https://arkivanov.github.io/MVIKotlin/)
- [MVIKotlin in Practice](https://medium.com/@mikhaltchenkov/mvikotlin-in-practice-a-modern-architecture-framework-for-android-and-kmp-ca68e58be94b)
- [Orbit MVI](https://orbit-mvi.org/)

---

## Logseq's Datascript Architecture

Logseq's frontend uses **DataScript** (in-memory Datalog database) with `listen!` / `tx-report-queue` for reactive updates. This is a **transaction-level push model**, not a table-level invalidation model.

**How it works:**
- Every DataScript transaction produces a `tx-report` containing the transaction data (`:tx-data`, `:db-before`, `:db-after`, datoms changed).
- Subscribers use DataScript's `listen!` callback or `tx-report-queue` to receive the full transaction payload after each write. They receive the actual changed datoms — essentially a changeset — not just "some table changed."
- Logseq's `onBlockChanged` plugin API exposes `outlinerOp` metadata per transaction, meaning the notification includes what kind of edit happened (split, merge, delete, etc.) as well as the affected entity IDs.
- The DB Worker (in the newer SQLite-backed DB version) runs in a dedicated Web Worker, manages the DataScript connection, and broadcasts transaction events back to the main thread — a worker-to-UI push model.

**Key contrast with SQLDelight/Room:** DataScript's tx-report carries the actual changed datoms, so subscribers can filter to only entities that match their interest (e.g., `(filter #(= (:e %) block-id) (:tx-data tx-report))`). There is no "re-query" step if the subscriber already has the data in the report. SteleKit's proposed `BlocksWritten` payload is the KMP equivalent of DataScript's tx-report for the block write path.

**Limitation Logseq did not escape:** Rum reactive components subscribe to Rum atoms and re-render on any state change to that atom. If a broad atom holds all blocks, all components re-render. Logseq scopes this by keeping fine-grained atoms per page/block.

**Sources:**
- [Logseq CODEBASE_OVERVIEW.md](https://github.com/logseq/logseq/blob/master/CODEBASE_OVERVIEW.md)
- [Logseq DeepWiki — Query System and Views](https://deepwiki.com/logseq/logseq/4.5-views-and-query-system)
- [DataScript GitHub (logseq-cldwalker fork)](https://github.com/logseq-cldwalker/datascript)

---

## CoreData + NSFetchedResultsController (iOS)

`NSFetchedResultsController` (NSFRC) operates at **object/row level**, not table level — a key difference from Room/SQLDelight. It subscribes to `NSManagedObjectContextObjectsDidChangeNotification` and filters the notification to only objects matching its `NSPredicate` and `NSManagedObjectContext`.

**How notifications are scoped:**
- The NSFRC delegate method `controller(_:didChange:at:for:newIndexPath:)` receives the specific changed *object*, its old index path, the change type (insert/update/delete/move), and the new index path.
- The NSFRC internally filters `NSManagedObjectContextObjectsDidChangeNotification` against its own predicate — objects that don't match the predicate are silently ignored.
- This means an update to a block on page B does NOT wake an NSFRC monitoring page A's blocks, as long as the predicates are scoped by page identifier.

**Limitation that still exists:** The NSFRC predicate filter runs *after* the notification fires. The notification itself is broadcast to all registered observers; each NSFRC then independently evaluates whether the changed objects match its predicate. For N NSFRC instances (N visible journal pages), N predicate evaluations still happen, though no re-query occurs for pages where no objects changed.

**What SteleKit can learn:** NSFRC's model — object-level change notification with predicate filtering at the subscriber — is more efficient than SQLDelight's table-level model. SteleKit's ADR-012 Phase 1 (`blockInvalidations: SharedFlow<Set<PageUuid>>`) achieves similar scoping: the subscriber filters on `pageUuid` containment before deciding to re-query, mirroring NSFRC's predicate evaluation without the N-re-query fan-out.

**Sources:**
- [NSFetchedResultsController Woes](https://medium.com/bpxl-craft/nsfetchedresultscontroller-woes-3a9b485058)
- [Apple Developer Forums — NSFRC predicate scoping](https://developer.apple.com/forums/thread/4999)

---

## Named Patterns for Write-Actor-Pushes-State

The pattern where a write path emits new state directly (rather than triggering a re-query) has several established names depending on the domain:

### 1. **CQRS with Push Read Model** (most precise)
In CQRS (Command Query Responsibility Segregation), the write side (Command) executes and then pushes an event containing the new state to the read side. The read model is updated from the event payload — no re-query of the write store. SteleKit's `BlocksWritten(pageUuid, List<Block>)` is structurally a CQRS push read model where `DatabaseWriteActor` is the Command handler and `BlockStateManager._blocks: MutableStateFlow` is the in-memory read model.

### 2. **Event-Carried State Transfer** (from enterprise integration patterns)
A specific variant of event-driven architecture where events carry the full new state of the entity (not just an ID or a change notification). Coined by Martin Fowler. Contrasts with "notification events" (tell observers something changed, they re-query) and "event sourcing" (store events to reconstruct state). `BlocksWritten` is an Event-Carried State Transfer payload.

### 3. **Reactive Store / Redux pattern**
Redux's reducer receives an action and returns the *complete new state* synchronously — no re-query. The Store then pushes the new state to all subscribers. Orbit MVI and MVIKotlin implement this in KMP: `intent { reduce { State(...updatedBlocks) } }` where `updatedBlocks` comes from the intent's async work, not a DB query.

### 4. **Write-Through Cache**
The write path simultaneously writes to the backing store (SQLite) and updates the in-memory cache (StateFlow/atom). Readers read from the cache. Differs from write-behind (async) and read-through (cache is populated on first miss). SteleKit Phase 2 is a write-through pattern: actor writes to SQLite, then emits the written block list to `BlockStateManager`, which holds it in `_blocks`.

### 5. **Actor + Outbox pattern**
The actor processes a write and, as part of the same serialized operation, publishes to an "outbox" (here, `SharedFlow`). Guarantees that the event is published exactly once and in write order. Matches `DatabaseWriteActor`'s design where `onWriteSuccess` / `_blockInvalidations.emit()` is called inside the actor's processing loop, after the DB write confirms success.

**Most applicable name for SteleKit's design:** "CQRS with Event-Carried State Transfer" or simply the "Reactive Store pattern" depending on audience. The distinction that matters in code: the event *carries the new block list* (Phase 2), not just a page UUID (Phase 1 invalidation signal).

---

## Implications for SteleKit

1. **SQLDelight's table-level invalidation is a fundamental SQLite constraint, not a SQLDelight design flaw.** Room has the same ceiling. No amount of SQLDelight API evolution will give row-level reactive notification without application-layer routing like `blockInvalidations`. The push model in ADR-012 is the correct escape hatch, not a workaround.

2. **The `BlocksWritten` Phase 2 payload is directly analogous to DataScript's tx-report and Redux's dispatched action.** These are mature patterns validated at scale. The correct implementation is: the actor emits the block list as-written (already held in memory at write time) rather than re-querying. This is what Logseq, Redux, MVIKotlin, and Orbit all do.

3. **NSFetchedResultsController shows that predicate-scoped filtering (Phase 1) has real cost at scale.** With N NSFRC instances, N predicate evaluations fire per notification — acceptable for small N, but the push payload (Phase 2) eliminates this evaluation entirely by making it selective at the emitter level. For SteleKit's journal view (typically 7 pages), Phase 1 is sufficient; Phase 2 matters most during bulk import when writes are high-frequency.

4. **MVIKotlin's explicit warning against "manipulating state in the Executor"** confirms the architecture risk: if `BlockStateManager` starts performing partial in-place mutations rather than receiving complete state from the actor, it becomes a correctness hazard. Phase 2 should emit the full block list for the page, not individual block deltas, to keep `BlockStateManager` a pure state-holder (analogous to the Redux Reducer receiving a complete new state).

5. **Logseq's `outlinerOp` metadata on tx-reports** is a useful precedent for Phase 2 extension: `BlocksWritten` could carry a `writeSource: WriteSource` discriminator (`UserEdit` vs `BulkImport` vs `ExternalFileChange`) to let `BlockStateManager` decide whether to animate, skip debounce, or merge with local edits in flight. This is not needed for Phase 1 or the initial Phase 2, but is a natural extension point that costs nothing to design for (sealed class).
