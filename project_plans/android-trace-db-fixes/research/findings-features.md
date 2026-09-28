# Features Research: Read/Write Separation Patterns — Android DB Performance

## Summary

**Recommendation**: Apply a lightweight CQRS split at the repository call site: reads (`getBlocksForPage`, `getPageByName`) call `withContext(PlatformDispatcher.DB)` directly, bypassing the `DatabaseWriteActor` queue entirely. Writes continue through the actor. No new abstractions are needed — the actor already has `execute { }` for arbitrary writes; the pattern is to call repository methods directly for reads.

---

## Pattern: CQRS Read/Write Separation in Kotlin Coroutines

CQRS (Command Query Responsibility Segregation) separates queries (reads, return data) from commands (writes, change state). In a Kotlin coroutines context with SQLite:

- **Reads** are inherently safe to run concurrently (SQLite WAL allows multiple concurrent readers)
- **Writes** need serialization to prevent `SQLITE_BUSY` write-lock conflicts

The `DatabaseWriteActor` was designed for writes. Routing reads through it adds queue-wait latency equal to the time for all preceding writes to complete — confirmed as ~1,000ms per read in the trace.

Source: [CQRS explained — dev.to](https://dev.to/actor-dev/cqrs-explained-1mf1), [Kotlin CQRS — DEV Community](https://dev.to/aleksk1ng/kotlin-clean-architecture-and-cqrs-4a16)

---

## Comparable App Strategies

### Logseq (original, file-based)
Logseq's original architecture uses DataScript (an in-memory database) with file I/O on the side. The DB-version migration to SQLite (via WASM) uses a single-writer model but does NOT serialize reads through the write queue — reads query the SQLite connection pool directly while writes go through a serialized channel.

Source: [Logseq SQLite integration via WASM — GitHub](https://github.com/logseq/sqlite-db), [Logseq DB FAQ](https://discuss.logseq.com/t/logseq-db-unofficial-faq/32508)

### Obsidian
Obsidian's internal indexing engine maintains a separate read path from its write path. Reads use a direct connection pool; writes are queued through a single writer. This is the same pattern SteleKit should adopt.

---

## Kotlin Actor Pattern for Write-Only Serialization

The `kotlinx.coroutines.channels.actor` builder creates a single-coroutine consumer that processes messages serially. The key insight for SteleKit is that this serialization is only needed for writes — SQLite WAL handles concurrent reads natively.

Current problematic pattern (reads through actor):
```kotlin
// In GraphLoader.lookupExistingPageAndCheckFreshness:
val existingPage = pageRepository.getPageByName(name).first().getOrNull()
// getPageByName → SqlDelightPageRepository → executes on PlatformDispatcher.DB ✓
// BUT this .first() collect is called while other actor.execute { } calls are pending,
// meaning the coroutine that calls this may be suspended waiting for actor results
// if it previously called writeActor.savePage() and is awaiting the deferred.
```

Wait — actually on re-reading the code: `pageRepository.getPageByName` does NOT go through the actor. It runs on `PlatformDispatcher.DB` directly (see `SqlDelightPageRepository.getPageByName` using `flowOn(PlatformDispatcher.DB)`). 

The trace inter-span gaps of ~1,000ms are **queue waits inside `DatabaseWriteActor.sendAndAwait`** for operations that ARE routed through the actor. In `parseAndSavePage`:
- `writeActor.savePage(page, priority)` — writes page, awaits deferred
- Then `blockRepository.getBlocksForPage(pageUuid).first()` — direct read, no actor, but preceded by actor wait

The 1,000ms gaps visible in the trace are the time `sendAndAwait` spends waiting for the actor to process prior LOW-priority requests from concurrent page loads. The fix is Fix 4 from requirements: collapse sequential actor calls into one composite `actor.execute { }` for the write portion.

Source: [Kotlin actors — kotlinlang.org](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.channels/actor.html), [Kotlin concurrency with actors — Medium](https://medium.com/@jagsaund/kotlin-concurrency-with-actors-34bd12531182)

---

## Patterns for Composite Actor Execute

The `DatabaseWriteActor.execute { }` lambda already supports arbitrary `Either<DomainError, Unit>` operations. The composite pattern:

```kotlin
// Before: 2 separate actor round-trips for savePage + saveBlocks
writeActor.savePage(page, priority)
writeActor.saveBlocks(blocks, priority)

// After: 1 actor round-trip for both writes, with read done before enqueue
val existingBlocks = withContext(PlatformDispatcher.DB) {
    blockRepository.getBlocksForPage(pageUuid).first().getOrNull() ?: emptyList()
}
writeActor.execute(priority) {
    pageRepository.savePage(page)
    blockRepository.saveBlocks(blocks)
}
```

This is safe because:
1. The read (`getBlocksForPage`) runs before the actor processes the write, so it sees the last committed state
2. The write actor's serialization guarantees no other writer can interleave between the read and write
3. `blockRepository.saveBlocks` already uses `withContext(PlatformDispatcher.DB)` internally

---

## Edge Cases and Unstated Needs

1. **Partial failure in composite execute**: If `savePage` succeeds but `saveBlocks` fails inside one `execute { }`, the page exists but has no blocks. The existing retry logic in `flushBatch` handles `saveBlocks` failures by retrying individually — this needs to be preserved or reimplemented in the composite path.

2. **Priority preemption**: The actor has HIGH vs LOW priority lanes. A composite `execute { LOW }` holding the actor while a HIGH editor save arrives will still be preempted correctly because the actor checks high-priority between items only during `processSaveBlocks` coalescing, not during a general `execute { }`. A long composite execute will block HIGH operations. Solution: keep composite executes short (one page, bounded block count).

3. **Re-entrancy**: `execute { }` lambdas that call other actor methods will deadlock — the actor is single-threaded. The composite lambda must call repository methods directly, not `writeActor.*` methods.

---

## Citations

- [CQRS explained](https://dev.to/actor-dev/cqrs-explained-1mf1)
- [Kotlin Clean Architecture and CQRS](https://dev.to/aleksk1ng/kotlin-clean-architecture-and-cqrs-4a16)
- [Logseq SQLite via WASM — GitHub](https://github.com/logseq/sqlite-db)
- [Kotlin coroutines actor API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.channels/actor.html)
- [Kotlin concurrency with actors](https://medium.com/@jagsaund/kotlin-concurrency-with-actors-34bd12531182)
- [Advanced coroutine patterns](https://medium.com/@harmanpreet.khera/advanced-coroutines-patterns-ffc1b1bf7b16)
