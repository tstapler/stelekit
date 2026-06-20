package dev.stapler.stelekit.db

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.PageUuid

/**
 * Event emitted by [DatabaseWriteActor] after a successful block write.
 * Phase 1 uses [PagesInvalidated]; Phase 2 extends with [BlocksWritten].
 */
sealed class BlockUpdateEvent {

    /**
     * One or more pages were written. Subscribers should re-query their page if its UUID
     * appears here, or if the set contains [WILDCARD_PAGE_UUID].
     */
    data class PagesInvalidated(val pageUuids: Set<PageUuid>) : BlockUpdateEvent()

    /**
     * An in-app write already holds the full block list for a page — no DB re-query needed.
     * [source] discriminates between user edits, bulk imports, and external file changes,
     * following Logseq's outlinerOp metadata precedent.
     */
    data class BlocksWritten(
        val pageUuid: PageUuid,
        val blocks: List<Block>,
        val source: WriteSource = WriteSource.UserEdit,
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
