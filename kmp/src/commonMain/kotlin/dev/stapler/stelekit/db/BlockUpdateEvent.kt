package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.PageUuid

/**
 * Event emitted by [DatabaseWriteActor] after a successful block write.
 *
 * Phase 1: [PagesInvalidated] — triggers a DB re-query in BlockStateManager.
 * Phase 2: [BlocksWritten] — full post-write block list, no DB re-query needed.
 * Phase 3: Patch variants — carry only the changed field(s); BlockStateManager applies
 *          directly to its in-memory _blocks without any DB round-trip.
 */
sealed class BlockUpdateEvent {

    /**
     * One or more pages were written. Subscribers should re-query their page if its UUID
     * appears here, or if the set contains [WILDCARD_PAGE_UUID].
     */
    data class PagesInvalidated(val pageUuids: Set<PageUuid>) : BlockUpdateEvent()

    /**
     * An in-app write holds the full block list for a page — no DB re-query needed.
     * [source] discriminates between user edits, bulk imports, and external file changes,
     * following Logseq's outlinerOp metadata precedent.
     * Used for structural operations (delete, merge) where the post-write state must
     * be confirmed from the DB.
     */
    data class BlocksWritten(
        val pageUuid: PageUuid,
        val blocks: List<Block>,
        val source: WriteSource = WriteSource.UserEdit,
    ) : BlockUpdateEvent()

    // ---- Patch variants (Phase 3): zero DB reads ----
    // Applied by BlockStateManager directly to _blocks with dirty-set semantics.
    // Used for pure-update typed write arms where the full new state is already
    // known from the write request (no structural changes, no child re-parenting).

    /** Content of a single block was updated (WriteBlockContent arm). */
    data class BlockContentPatched(
        val pageUuid: PageUuid,
        val blockUuid: BlockUuid,
        val newContent: String,
    ) : BlockUpdateEvent()

    /** A block was replaced in full (WriteBlock arm — new block or structural field change). */
    data class BlockReplaced(
        val pageUuid: PageUuid,
        val block: Block,
    ) : BlockUpdateEvent()

    /** Properties of a single block were updated (WriteBlockProperties arm). */
    data class BlockPropertiesPatched(
        val pageUuid: PageUuid,
        val blockUuid: BlockUuid,
        val properties: Map<String, String>,
    ) : BlockUpdateEvent()
}

/** Discriminates the origin of a block write for smarter UI decisions. */
sealed interface WriteSource {
    /** User typed, split, merged, or deleted a block interactively. */
    object UserEdit : WriteSource
    /** Background indexing or graph import (GraphLoader path). */
    object BulkImport : WriteSource
    /** External file change detected on disk (watcher path). */
    object ExternalFileChange : WriteSource
}
