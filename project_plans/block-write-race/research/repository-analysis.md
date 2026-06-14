# Agent 2: BlockRepository Interface & Implementations

## Summary

Analysis of `BlockRepository` interface (`GraphRepository.kt`) and
`SqlDelightBlockRepository.kt`, focused on `splitBlock`, `mergeBlocks`,
`deleteBlock`, `saveBlock`, and `updateBlockContentOnly` at the SQL level.

---

## Interface: `BlockRepository`
**Path**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/GraphRepository.kt` (lines 22–)

All mutating methods are annotated `@DirectRepositoryWrite` to enforce access through
`DatabaseWriteActor` or `RestrictedDatabaseQueries`. Key signatures:

```kotlin
@DirectRepositoryWrite
suspend fun updateBlockContentOnly(blockUuid: String, content: String): Either<DomainError, Unit>

@DirectRepositoryWrite
suspend fun splitBlock(
    blockUuid: String,
    cursorPosition: Int,
    newBlockUuid: String? = null,
): Either<DomainError, Block>

@DirectRepositoryWrite
suspend fun mergeBlocks(blockUuid: String, nextBlockUuid: String, separator: String): Either<DomainError, Unit>

@DirectRepositoryWrite
suspend fun deleteBlock(blockUuid: String, deleteChildren: Boolean = false): Either<DomainError, Unit>
```

**`splitBlock` contract** (line 164–176): accepts `newBlockUuid` — when provided by the
caller (optimistic path), the repository uses that UUID for the new block, eliminating a
post-split UUID correction pass in `BlockStateManager`. Returns `Either<DomainError, Block>`
(the newly created block).

---

## Implementation: `SqlDelightBlockRepository`
**Path**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/SqlDelightBlockRepository.kt`

All methods use `withContext(PlatformDispatcher.DB)`.

### `updateBlockContentOnly` (line 294–309)

```kotlin
override suspend fun updateBlockContentOnly(blockUuid: String, content: String): Either<DomainError, Unit> =
    withContext(PlatformDispatcher.DB) {
        try {
            val oldContent = blockCache.get(blockUuid)?.content
                ?: queries.selectBlockByUuid(blockUuid).executeAsOneOrNull()?.content ?: ""
            queries.updateBlockContent(content, Clock.System.now().toEpochMilliseconds(),
                ContentHasher.sha256ForContent(content), blockUuid)
            blockCache.remove(blockUuid)
            val changedPages = extractWikilinks(oldContent) + extractWikilinks(content)
            changedPages.forEach { queries.recomputeBacklinkCountForPage(it) }
            Unit.right()
        } catch (...) { ... }
    }
```

**Pattern**: Read-then-write (reads old content for wikilink diff). NOT transactional (the
read of `oldContent` and the `updateBlockContent` write are separate SQL calls within
`withContext`). Safe for content-only updates because there's no structural dependency, but
it does read before writing.

### `splitBlock` (line 718–789)

```kotlin
override suspend fun splitBlock(blockUuid: String, cursorPosition: Int, newBlockUuid: String?): Either<DomainError, Block> =
    withContext(PlatformDispatcher.DB) {
        try {
            var newBlock: Block? = null
            queries.transaction {
                val block = queries.selectBlockByUuid(blockUuid).executeAsOneOrNull() ?: return@transaction
                val content = block.content  // <-- READS CURRENT CONTENT FROM DB
                val firstPart = content.substring(0, cursorPosition).trim()
                val secondPart = content.substring(cursorPosition).trim()

                // Update original block content
                queries.updateBlockContent(firstPart, ..., block.uuid)
                // Insert new block
                val newUuid = newBlockUuid ?: UuidGenerator.generateV7()
                // ... shift siblings' positions ...
                queries.insertBlock(uuid = newUuid, content = secondPart, ...)
                // Chain repair
                val nextSibling = queries.selectBlockByLeftUuid(block.uuid).executeAsOneOrNull()
                queries.updateBlockLeftUuid(insertedBlock.uuid, nextSibling.uuid)
                newBlock = insertedBlock.toBlockModel()
            }
            newBlock?.right() ?: DomainError.DatabaseError.WriteFailed(...).left()
        } catch (...) { ... }
    }
```

**Critical finding**: `splitBlock` reads `block.content` FROM THE DATABASE inside the
transaction (line ~726: `val content = block.content`). If `updateBlockContentOnly` has
NOT yet committed to the DB (still queued in the actor), `splitBlock` reads stale content.
This is the exact mechanism of the race condition.

**The transaction is atomic**: All operations inside `queries.transaction { }` run as a
single SQLite transaction, so there's no partial split. However, the transaction reads
whatever is currently committed in the DB, not the pending actor queue.

### `mergeBlocks` (line 665–716)

```kotlin
override suspend fun mergeBlocks(blockUuid: String, nextBlockUuid: String, separator: String): Either<DomainError, Unit> =
    withContext(PlatformDispatcher.DB) {
        try {
            queries.transaction {
                val blockA = queries.selectBlockByUuid(blockUuid).executeAsOneOrNull() ?: return@transaction
                val blockB = queries.selectBlockByUuid(nextBlockUuid).executeAsOneOrNull() ?: return@transaction
                val mergedContent = blockA.content + separator + blockB.content  // <-- READS FROM DB
                queries.updateBlockContent(mergedContent, ..., blockA.uuid)
                // ... reparent children of B to A ...
                // ... chain repair (B deleted) ...
                queries.deleteBlockByUuid(blockB.uuid)
            }
            ...
        } catch (...) { ... }
    }
```

**Same issue**: `mergeBlocks` reads `blockA.content` and `blockB.content` from DB inside
the transaction. If either block has a pending `updateBlockContentOnly` in the actor queue,
the merge will use stale content. For the backspace-merge case: user types "Hello" in
block B, content write is queued, user hits Backspace at position 0 → `mergeBlocks` reads
"" (old content of B) instead of "Hello".

### `deleteBlock` (line 325–368)

```kotlin
override suspend fun deleteBlock(blockUuid: String, deleteChildren: Boolean): Either<DomainError, Unit> =
    withContext(PlatformDispatcher.DB) {
        try {
            val block = queries.selectBlockByUuid(blockUuid).executeAsOneOrNull()
            if (block != null) {
                // Chain repair
                val nextSibling = queries.selectBlockByLeftUuid(block.uuid).executeAsOneOrNull()
                if (nextSibling != null) queries.updateBlockLeftUuid(block.left_uuid, nextSibling.uuid)
                queries.deleteBlockByUuid(block.uuid)
                // recomputeBacklinkCountForPage for each wikilink
            }
            Unit.right()
        } catch (...) { ... }
    }
```

`deleteBlock` does NOT read content for the purpose of the operation (only for backlink
recomputation). The structural race here is: if the block was just optimistically created
(pending in actor), `deleteBlock` bypassing the actor would not find it in the DB at all.

### `saveBlock` (line 268–292)

Single INSERT (no read-then-write). Uses `insertBlock` which is a SQL UPSERT/INSERT OR
REPLACE. Does NOT read existing content. Returns `Either<DomainError, Unit>`.

### Read-then-write Summary

| Method | Reads DB inside op | Transactional | Race risk |
|---|---|---|---|
| `updateBlockContentOnly` | Yes (old content for wikilink diff) | No | Low (content-only) |
| `splitBlock` | Yes (reads `block.content` for split) | Yes (full transaction) | **HIGH** |
| `mergeBlocks` | Yes (reads both blocks' content) | Yes (full transaction) | **HIGH** |
| `deleteBlock` | Yes (for backlink recompute) | No | Medium |
| `saveBlock` | No | No | None |

---

## Caching Behavior

`SqlDelightBlockRepository` has an LRU block cache (`blockCache`). The
`updateBlockContentOnly` implementation calls `blockCache.remove(blockUuid)` AFTER the
update (line 301), so the cache is invalidated on write. However, `splitBlock` reads via
`queries.selectBlockByUuid(blockUuid)` — it does NOT check `blockCache` first. This means
even if the cache had a fresh entry, the transaction reads directly from SQLite. No cache
inconsistency for reads inside `splitBlock`, but it does mean the "pending content write"
is invisible to the transaction regardless of cache state.

---

## `InMemoryBlockRepository` (for tests)
**Path**: `kmp/src/commonMain/kotlin/dev/stapler/stelekit/repository/InMemoryBlockRepository.kt`

Used in `BlockStateManagerTest`. Its `splitBlock` implementation:
- Does NOT use `withContext` (pure in-memory, no concurrency constraint)
- Reads the block from the in-memory map — whatever was last written
- Is eligible for a `DelayedBlockRepository` wrapper that adds a delay

**Important for test design**: `InMemoryBlockRepository`'s `updateBlockContentOnly` writes
immediately to the in-memory store. So to reproduce the race, a test double must also
delay `updateBlockContentOnly` (not just `splitBlock`), so that `splitBlock` runs first
and reads old content. The existing `DelayedBlockRepository` only delays `splitBlock` — it
does not delay `updateBlockContentOnly`, so it does NOT reproduce the real race.
