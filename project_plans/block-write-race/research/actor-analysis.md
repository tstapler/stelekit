# Agent 3: DatabaseWriteActor Extension Points

## Summary

Full analysis of `DatabaseWriteActor.kt` for the purpose of routing structural block
operations through the actor to serialize them after pending content writes.

---

## File Location
`kmp/src/commonMain/kotlin/dev/stapler/stelekit/db/DatabaseWriteActor.kt`

---

## Architecture Overview

The actor maintains two `Channel<WriteRequest>(capacity = Channel.UNLIMITED)` channels:
- `highPriority` — user-initiated writes (editor saves, deletes)
- `lowPriority` — bulk background loads

The actor loop (lines 124–157) uses `select { highPriority.onReceive { }; lowPriority.onReceive { } }`,
preferring high-priority. A single coroutine (`actorScope.launch`) processes all requests
sequentially.

---

## `WriteRequest` Sealed Class (lines 68–108)

```kotlin
sealed class WriteRequest {
    abstract val deferred: CompletableDeferred<Either<DomainError, Unit>>
    abstract val priority: Priority

    class SavePage(val page: Page, override val priority: Priority = Priority.HIGH, ...)
    class SavePages(val pages: List<Page>, override val priority: Priority = Priority.LOW, ...)
    class SaveBlocks(val blocks: List<Block>, override val priority: Priority = Priority.HIGH, ...)
    class DeleteBlocksForPage(val pageUuid: String, override val priority: Priority = Priority.HIGH, ...)
    class DeleteBlocksForPages(val pageUuids: List<String>, override val priority: Priority = Priority.LOW, ...)
    class Execute(
        val op: suspend () -> Either<DomainError, Unit>,
        override val priority: Priority = Priority.HIGH,
        override val deferred: CompletableDeferred<Either<DomainError, Unit>> = CompletableDeferred(),
        val enqueueMs: Long = HistogramWriter.epochMs(),
    ) : WriteRequest()
}
```

The `Execute` request type is the key hook for arbitrary structural ops.

---

## The `execute {}` Method (line 366–370)

```kotlin
suspend fun execute(priority: Priority = Priority.HIGH, op: suspend () -> Either<DomainError, Unit>): Either<DomainError, Unit> {
    val req = WriteRequest.Execute(op, priority)
    channelFor(priority).send(req)
    return req.deferred.await()
}
```

**This is the right hook for structural ops.** The lambda must be `suspend () -> Either<DomainError, Unit>`.

### Typing implication
`splitBlock` returns `Either<DomainError, Block>` (not `Unit`). The `execute {}` lambda
must return `Either<DomainError, Unit>`. Options:
1. Map the result: `execute { splitBlock(...).map { } }` — loses the new block return value
2. Capture the result via a `CompletableDeferred` or local `var` in the lambda closure:
   ```kotlin
   var newBlock: Block? = null
   val result = actor.execute { blockRepository.splitBlock(...).onRight { newBlock = it }.map { } }
   ```
3. Add a typed `splitBlock` method on the actor (like `saveBlock`, `updateBlockContentOnly`) that
   uses `execute {}` internally but returns `Either<DomainError, Block>`:
   ```kotlin
   suspend fun splitBlock(blockUuid: String, cursorPosition: Int, newBlockUuid: String?): Either<DomainError, Block> {
       var result: Block? = null
       val opResult = execute {
           blockRepository.splitBlock(blockUuid, cursorPosition, newBlockUuid)
               .onRight { result = it }
               .map { }
       }
       return opResult.flatMap { result?.right() ?: DomainError.DatabaseError.WriteFailed("no block returned").left() }
   }
   ```

**Option 3 is cleanest** and matches the existing pattern of `saveBlock`, `updateBlockContentOnly`,
`updateBlockPropertiesOnly`, `deleteBlock` — all of which are thin wrappers around `execute {}`.

---

## Exception Handling (lines 132–155)

```kotlin
try {
    processRequest(request)
} catch (e: CancellationException) {
    if (!request.deferred.isCompleted) {
        request.deferred.complete(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
    }
    throw e
} catch (e: Exception) {
    if (!request.deferred.isCompleted) {
        request.deferred.complete(DomainError.DatabaseError.WriteFailed(e.message ?: "unknown").left())
    }
}
```

For `WriteRequest.Execute` (line 205–224):
```kotlin
is WriteRequest.Execute -> {
    val waitMs = HistogramWriter.epochMs() - request.enqueueMs
    // ... latency logging ...
    request.deferred.complete(request.op())
}
```

The actor calls `request.op()` directly and completes the deferred with the result.
If `op()` throws, the outer `catch (e: Exception)` block in the loop completes the deferred
with a `WriteFailed` error. The actor loop SURVIVES exceptions (the outer loop continues).

---

## Coalescing Behavior and Impact on Structural Ops

The actor coalesces consecutive `WriteRequest.SaveBlocks` requests (lines 227–251):
```kotlin
private suspend fun processSaveBlocks(first: WriteRequest.SaveBlocks) {
    val batch = mutableListOf(first)
    while (true) {
        // While building low-priority batch, yield to high-priority
        val next = sourceChannel.tryReceive().getOrNull() ?: break
        if (next is WriteRequest.SaveBlocks) batch.add(next)
        else { flushBatch(batch); processRequest(next); return }
    }
    flushBatch(batch)
}
```

**Key finding**: Coalescing ONLY applies to `WriteRequest.SaveBlocks`. A `WriteRequest.Execute`
in the channel will break the SaveBlocks coalescing run and be processed immediately after the
current batch flushes. This is correct behavior for structural ops.

**Structural ops sent via `execute {}` will NOT be coalesced or reordered.** They execute in
strict FIFO order after any preceding requests complete. This is exactly what we need:
1. Content write arrives: `SaveBlocks` or `Execute(updateBlockContentOnly)` → queued
2. Structural op arrives: `Execute(splitBlock)` → queued AFTER
3. Actor processes content write first, THEN runs splitBlock
4. splitBlock reads content from DB — sees the committed content write

---

## Existing Typed Wrapper Methods on Actor (lines 372–394)

```kotlin
suspend fun saveBlock(block: Block): Either<DomainError, Unit> =
    execute { blockRepository.saveBlock(block) }

suspend fun updateBlockContentOnly(blockUuid: String, content: String): Either<DomainError, Unit> =
    execute { blockRepository.updateBlockContentOnly(blockUuid, content) }

suspend fun updateBlockPropertiesOnly(blockUuid: String, properties: Map<String, String>): Either<DomainError, Unit> =
    execute { blockRepository.updateBlockPropertiesOnly(blockUuid, properties) }

suspend fun deleteBlock(blockUuid: String): Either<DomainError, Unit> =
    execute {
        // Pre-logs the delete for op logger, then calls blockRepository.deleteBlock(blockUuid)
        blockRepository.deleteBlock(blockUuid)
    }
```

These are the model for new typed wrapper methods to add for structural ops.

---

## Can Structural Ops Break Without Adding Typed Methods?

Yes — `BlockStateManager` can call `actor.execute { blockRepository.splitBlock(...) }` inline,
but this loses the typed return value (the new `Block`). The cleaner path (and consistent with
existing code) is to add typed methods on `DatabaseWriteActor`:

```kotlin
// In DatabaseWriteActor.kt
suspend fun splitBlock(blockUuid: String, cursorPosition: Int, newBlockUuid: String?): Either<DomainError, Block> {
    var newBlock: Block? = null
    val opResult = execute {
        blockRepository.splitBlock(blockUuid, cursorPosition, newBlockUuid)
            .onRight { newBlock = it }
            .map { }
    }
    return opResult.fold(
        ifLeft = { it.left() },
        ifRight = { newBlock?.right() ?: DomainError.DatabaseError.WriteFailed("splitBlock returned no block").left() }
    )
}

suspend fun mergeBlocks(blockUuid: String, nextBlockUuid: String, separator: String): Either<DomainError, Unit> =
    execute { blockRepository.mergeBlocks(blockUuid, nextBlockUuid, separator) }
```

---

## `executeBatch` (lines 403–413)

```kotlin
suspend fun executeBatch(batchId: String, block: suspend DatabaseWriteActor.() -> Unit) {
    execute { opLogger?.logBatchStart(batchId); Unit.right() }
    try {
        this.block()
    } finally {
        execute { opLogger?.logBatchEnd(batchId); Unit.right() }
    }
}
```

This is for undo-log bracketing, not for serializing a sequence of writes atomically.
Structural ops should NOT use `executeBatch` — they should use typed `execute {}` wrappers.

---

## Priority for Structural Ops

Content writes (`updateBlockContentOnly`) use `Priority.HIGH` (the default for `execute {}`).
Structural ops should also use `Priority.HIGH` to maintain relative order in the high-priority
channel. Using `Priority.LOW` would allow structural ops to be preempted by other high-priority
content writes, which could cause unexpected ordering.

---

## `@DirectRepositoryWrite` Constraint

The `DatabaseWriteActor` class itself is annotated `@OptIn(DirectRepositoryWrite::class)` at
the top (line 54). New `splitBlock`, `mergeBlocks` methods on the actor can call
`blockRepository.splitBlock(...)` without additional opt-in at each call site.

---

## No `flush` / `barrier` API

The actor has no `flush()` method that drains the queue and waits for all pending writes to
complete. The only way to guarantee ordering is to enqueue the structural op THROUGH the
channel (via `execute {}`). There is no "check if pending content writes are done" API.
This confirms that the only correct fix is routing structural ops through the actor.

---

## `close()` Method (line 418–422)

```kotlin
fun close() {
    highPriority.close()
    lowPriority.close()
    actorScope.cancel()
}
```

Closing channels prevents new requests but in-flight requests in the channel buffer may not
complete. The caller (graph lifecycle) handles this via `Resource.onRelease`.
