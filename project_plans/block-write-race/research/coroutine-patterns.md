# Agent 4: Kotlin Coroutine Patterns for Serialization

## Summary

Research on Kotlin coroutine patterns for the "flush pending work before structural op"
problem, with specific analysis of which patterns apply to the SteleKit context under
the KMP constraint (no JVM-only APIs).

---

## The Core Problem

Two operations compete for the same block's content in the DB:
1. **Content write**: `applyContentChange` → `writeActor.updateBlockContentOnly()` (queued in actor)
2. **Structural op**: `splitBlock` / `addNewBlock` / `mergeBlock` / `handleBackspace` → calls
   `blockRepository.splitBlock/mergeBlocks/deleteBlock` directly, bypassing the actor queue.

The structural op runs concurrently with the queued content write. If the content write is
still pending in the actor's channel, the structural op hits the DB first and reads stale content.

---

## Pattern Analysis

### Option A: Route Structural Ops Through the Same Actor (RECOMMENDED)

**Mechanism**: Enqueue structural ops as `WriteRequest.Execute` in `DatabaseWriteActor`.
Because the actor processes requests in FIFO order from the same channel, a structural op
enqueued after a content write is guaranteed to run only after the content write completes.

```kotlin
// In BlockStateManager.splitBlock:
// Before (bypasses actor):
blockRepository.splitBlock(blockUuid, clampedCursor, expectedNewUuid)

// After (routes through actor, guaranteed to run after pending content writes):
writeActor?.splitBlock(blockUuid, clampedCursor, expectedNewUuid)
    ?: blockRepository.splitBlock(blockUuid, clampedCursor, expectedNewUuid)
```

**Tradeoffs**:
- Pro: Correct by construction — no new synchronization primitives
- Pro: Consistent with existing `updateBlockContentOnly` pattern
- Pro: Works identically on all platforms (KMP safe)
- Pro: No changes to channel/actor internals
- Con: Structural ops must await content write completion (adds latency) — but this is
  correct behavior, not a defect
- Con: If `writeActor` is null (test/in-memory backend), fallback to direct call — this is
  already the existing pattern for `writeContentOnly`

**KMP compatibility**: `Channel`, `CompletableDeferred`, coroutine scopes — all in
`kotlinx.coroutines`, fully multiplatform.

**Actor channel ordering guarantee**: Kotlin channels (`Channel(capacity = UNLIMITED)`) are
FIFO. `send()` is a suspend function that adds to the channel buffer; `receive()` returns
items in insertion order. The actor's single-consumer coroutine processes items in order,
so content write before structural op = content write executes before structural op.

---

### Option B: Add a Flush/Await Mechanism

**Mechanism**: Add a special `WriteRequest.Barrier` type (or reuse `Execute`) that does
nothing but waits in the queue. The caller sends a barrier after content writes and awaits
it before calling the structural op.

```kotlin
suspend fun awaitPendingWrites() {
    execute { Unit.right() }  // no-op — just waits its turn in the queue
}
```

Then in `BlockStateManager`:
```kotlin
fun splitBlock(blockUuid: String, cursorPosition: Int): Job = scope.launch {
    writeActor?.awaitPendingWrites()  // drain pending content writes
    blockRepository.splitBlock(...)   // now safe to call directly
}
```

**Tradeoffs**:
- Pro: Minimal change to structural op code paths
- Con: Two-step: await + direct call — the window between await completing and the structural
  op being called could allow another content write to land (e.g., keystroke events on a fast
  keyboard). This is a TOCTOU (time-of-check-time-of-use) race.
- Con: The structural op still bypasses the actor, so it can interleave with writes queued
  AFTER the barrier but BEFORE the direct call.
- **Conclusion**: This pattern does NOT fix the race; it only reduces the window.

---

### Option C: Per-Block Mutex

**Mechanism**: Maintain a `Map<String, Mutex>` keyed by block UUID. Both content writes
and structural ops acquire the mutex for that block UUID before proceeding.

```kotlin
private val blockMutexes = ConcurrentHashMap<String, Mutex>()
private fun mutexFor(blockUuid: String) = blockMutexes.getOrPut(blockUuid) { Mutex() }

suspend fun applyContentChange(blockUuid: String, ...) {
    mutexFor(blockUuid).withLock {
        blockRepository.updateBlockContentOnly(blockUuid, content)
    }
}

suspend fun splitBlock(blockUuid: String, ...) {
    mutexFor(blockUuid).withLock {
        blockRepository.splitBlock(blockUuid, ...)
    }
}
```

**Tradeoffs**:
- Pro: Fine-grained (per-block), doesn't serialize unrelated blocks
- Con: Does NOT work with the actor queue model — the actor's FIFO guarantee is lost;
  the mutex and the actor's internal serialization would be separate systems that could
  deadlock if an actor-running coroutine waits for a mutex held by a caller waiting for
  the actor.
- Con: `ConcurrentHashMap` is JVM-only. KMP would need `mutableMapOf` + `Mutex` per
  access (which itself needs a global lock). Complex and error-prone.
- Con: Memory leak if map entries are never cleaned up (need to track block lifecycle).
- **Conclusion**: Not recommended for KMP. Too complex, risk of deadlock.

---

### Option D: `CompletableDeferred` as a Per-Write Ticket

**Mechanism**: Each call to `applyContentChange` creates a `CompletableDeferred<Unit>`.
Structural ops await the deferred before running.

```kotlin
private val pendingContentWrites = mutableMapOf<String, CompletableDeferred<Unit>>()

suspend fun applyContentChange(blockUuid: String, ...) {
    val ticket = CompletableDeferred<Unit>()
    pendingContentWrites[blockUuid] = ticket
    writeContentOnly(blockUuid, content) // queued in actor
    ticket.complete(Unit) // after actor returns
    pendingContentWrites.remove(blockUuid)
}

suspend fun splitBlock(blockUuid: String, ...) {
    pendingContentWrites[blockUuid]?.await()  // wait for pending content write
    blockRepository.splitBlock(...)
}
```

**Tradeoffs**:
- Pro: Correct in serial use cases
- Con: Race between `pendingContentWrites[blockUuid]?.await()` returning and the actor
  completing the DB write — `writeContentOnly` suspends in the actor queue, but the
  deferred is completed AFTER `writeContentOnly` returns, which is after the DB write.
  So if `applyContentChange` is `suspend` and awaits `writeContentOnly`, the deferred
  completion IS after the DB write. This could work.
- Con: Complex state management — `mutableMapOf` is not thread-safe; multiple rapid
  keystrokes create multiple entries; cleanup on rollback is tricky.
- Con: Still doesn't cover the case where a structural op arrives BEFORE `applyContentChange`
  has added the deferred to the map.
- **Conclusion**: Fragile. Not recommended.

---

## KMP Constraint Analysis

All Kotlin coroutines APIs used in `DatabaseWriteActor` are KMP-compatible:
- `Channel` — `kotlinx.coroutines.channels.Channel` — KMP
- `CompletableDeferred` — `kotlinx.coroutines` — KMP
- `Mutex` — `kotlinx.coroutines.sync.Mutex` — KMP
- `select {}` — `kotlinx.coroutines.selects.select` — KMP

JVM-only APIs to avoid:
- `java.util.concurrent.ConcurrentHashMap` — JVM only (use `mutableMapOf` + Mutex)
- `java.util.concurrent.atomic.*` — JVM only (use `AtomicReference` from kotlinx if needed,
  or better: avoid shared mutable state)
- `ReentrantLock`, `synchronized {}` — JVM only

---

## Why Option A Is the Correct Fix

The actor already implements the correct serialization primitive: FIFO channel + single
consumer. Content writes (`updateBlockContentOnly`) are already routed through the actor.
Structural ops just need to join the same queue.

**The ordering guarantee works as follows**:

1. User types → `applyContentChange` → `actor.updateBlockContentOnly(blockUuid, "typed text")`
   → `highPriority.send(Execute(op))` → returns deferred
2. User hits Enter → `addNewBlock(currentBlockUuid)` → `actor.splitBlock(blockUuid, ...)` or
   `actor.execute { blockRepository.splitBlock(...) }` → `highPriority.send(Execute(op))`
3. Actor processes item 1: calls `updateBlockContentOnly`, DB now has "typed text" committed
4. Actor processes item 2: calls `splitBlock`, reads "typed text" from DB — correct!

If instead `splitBlock` bypasses the actor:
1. User types → `highPriority.send(Execute(updateBlockContentOnly))` — pending in queue
2. User hits Enter → `blockRepository.splitBlock()` called directly — reads "" from DB
3. Actor processes step 1: updates content to "typed text" — too late, split already happened

**The fix is minimal and surgical**: in `BlockStateManager.splitBlock`, `addNewBlock`,
`mergeBlock`, `handleBackspace`, replace `blockRepository.splitBlock(...)` with
`writeActor?.splitBlock(...) ?: blockRepository.splitBlock(...)`, and add the corresponding
typed methods to `DatabaseWriteActor`.

---

## Handling the `writeActor == null` Fallback

`BlockStateManager` constructor has `private val writeActor: DatabaseWriteActor? = null`.
The `null` case covers:
1. Tests that use `InMemoryBlockRepository` (no actor needed — all writes are synchronous
   in-memory, no race possible)
2. Potentially some legacy code paths

In the `null` case, direct `blockRepository.splitBlock(...)` calls are fine because
`InMemoryBlockRepository` has no asynchronous write queue — writes complete synchronously.

---

## Existing Precedent in the Codebase

`BlockStateManager` already uses this dual-path pattern for content writes:

```kotlin
// Line 67-78 of BlockStateManager.kt
private suspend fun writeBlock(block: Block) =
    writeActor?.saveBlock(block) ?: blockRepository.saveBlock(block)

private suspend fun writeContentOnly(blockUuid: String, content: String) =
    writeActor?.updateBlockContentOnly(blockUuid, content)
        ?: blockRepository.updateBlockContentOnly(blockUuid, content)

private suspend fun writePropertiesOnly(blockUuid: String, properties: Map<String, String>) =
    writeActor?.updateBlockPropertiesOnly(blockUuid, properties)
        ?: blockRepository.updateBlockPropertiesOnly(blockUuid, properties)
```

The fix for structural ops is exactly the same pattern — introduce `writeSplitBlock`,
`writeMergeBlocks`, `writeDeleteBlock`, etc., using the same actor-or-direct fallback.

---

## Summary Table

| Pattern | Correctness | KMP Safe | Complexity | Recommended |
|---|---|---|---|---|
| A: Route through same actor | Correct by construction | Yes | Low | YES |
| B: Flush/await barrier | TOCTOU race remains | Yes | Medium | No |
| C: Per-block Mutex | Deadlock risk | No (JVM only) | High | No |
| D: CompletableDeferred ticket | Fragile, race conditions | Yes | High | No |
