// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.OperationLogger
import dev.stapler.stelekit.db.replaceWikilink
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.RepositorySet
import kotlinx.coroutines.flow.first

/**
 * Applies a [List] of [BlockChange] entries through [DatabaseWriteActor] so all writes
 * are serialized and safe from SQLITE_BUSY contention. Every change is also logged via
 * [OperationLogger] so it appears in the undo stack.
 *
 * [RenamePage][BlockChange.RenamePage] changes trigger a graph-wide wikilink rewrite
 * wrapped in a [DatabaseWriteActor.executeBatch] so the rename and all link rewrites
 * undo atomically as a single operation.
 */
@OptIn(DirectRepositoryWrite::class)
class ChangeApplier(
    private val writeActor: DatabaseWriteActor,
    private val opLogger: OperationLogger?,
) {

    suspend fun apply(changes: List<BlockChange>, repoSet: RepositorySet): ChangeSummary {
        var applied = 0
        var skipped = 0
        val failed = mutableListOf<String>()

        for (change in changes) {
            try {
                when (change) {
                    is BlockChange.UpsertProperty -> {
                        val block = repoSet.blockRepository.getBlockByUuid(change.blockUuid)
                            .first().getOrNull()
                        if (block == null) {
                            skipped++
                            continue
                        }
                        val updated = block.copy(properties = block.properties + (change.key to change.value))
                        val before = block
                        writeActor.execute {
                            val result = repoSet.blockRepository.saveBlock(updated)
                            if (result.isSuccess) opLogger?.logUpdate(before, updated)
                            result
                        }.getOrThrow()
                        applied++
                    }

                    is BlockChange.DeleteProperty -> {
                        val block = repoSet.blockRepository.getBlockByUuid(change.blockUuid)
                            .first().getOrNull()
                        if (block == null) {
                            skipped++
                            continue
                        }
                        val updated = block.copy(properties = block.properties - change.key)
                        val before = block
                        writeActor.execute {
                            val result = repoSet.blockRepository.saveBlock(updated)
                            if (result.isSuccess) opLogger?.logUpdate(before, updated)
                            result
                        }.getOrThrow()
                        applied++
                    }

                    is BlockChange.SetContent -> {
                        val block = repoSet.blockRepository.getBlockByUuid(change.blockUuid)
                            .first().getOrNull()
                        if (block == null) {
                            skipped++
                            continue
                        }
                        val updated = block.copy(content = change.newContent)
                        val before = block
                        writeActor.execute {
                            val result = repoSet.blockRepository.saveBlock(updated)
                            if (result.isSuccess) opLogger?.logUpdate(before, updated)
                            result
                        }.getOrThrow()
                        applied++
                    }

                    is BlockChange.DeleteBlock -> {
                        val block = repoSet.blockRepository.getBlockByUuid(change.blockUuid)
                            .first().getOrNull()
                        if (block == null) {
                            skipped++
                            continue
                        }
                        writeActor.execute {
                            val result = repoSet.blockRepository.deleteBlock(change.blockUuid)
                            if (result.isSuccess) opLogger?.logDelete(block)
                            result
                        }.getOrThrow()
                        applied++
                    }

                    is BlockChange.InsertBlock -> {
                        writeActor.execute {
                            val result = repoSet.blockRepository.saveBlock(change.block)
                            if (result.isSuccess) opLogger?.logInsert(change.block)
                            result
                        }.getOrThrow()
                        applied++
                    }

                    is BlockChange.UpsertPageProperty -> {
                        val page = repoSet.pageRepository.getPageByUuid(change.pageUuid)
                            .first().getOrNull()
                        if (page == null) {
                            skipped++
                            continue
                        }
                        val updated = page.copy(properties = page.properties + (change.key to change.value))
                        writeActor.execute {
                            repoSet.pageRepository.savePage(updated)
                        }.getOrThrow()
                        applied++
                    }

                    is BlockChange.DeletePageProperty -> {
                        val page = repoSet.pageRepository.getPageByUuid(change.pageUuid)
                            .first().getOrNull()
                        if (page == null) {
                            skipped++
                            continue
                        }
                        val updated = page.copy(properties = page.properties - change.key)
                        writeActor.execute {
                            repoSet.pageRepository.savePage(updated)
                        }.getOrThrow()
                        applied++
                    }

                    is BlockChange.RenamePage -> {
                        applyRenamePage(change, repoSet)
                        applied++
                    }

                    is BlockChange.DeletePage -> {
                        writeActor.execute {
                            repoSet.pageRepository.deletePage(change.pageUuid)
                        }.getOrThrow()
                        applied++
                    }
                }
            } catch (e: Exception) {
                failed.add("${change::class.simpleName} failed: ${e.message}")
            }
        }

        return ChangeSummary(applied = applied, skipped = skipped, failed = failed)
    }

    /**
     * Renames a page and rewrites all [[OldName]] wikilinks in the graph.
     * Wraps both operations in [DatabaseWriteActor.executeBatch] so they undo atomically.
     */
    private suspend fun applyRenamePage(change: BlockChange.RenamePage, repoSet: RepositorySet) {
        val batchId = "migration-rename-${change.pageUuid}"
        writeActor.executeBatch(batchId) {
            // 1. Rename the page row in DB.
            writeActor.execute {
                repoSet.pageRepository.renamePage(change.pageUuid, change.newName)
            }.getOrThrow()

            // 2. Find all blocks that reference [[OldName]] and rewrite them.
            val affectedBlocks = repoSet.blockRepository
                .getLinkedReferences(change.oldName)
                .first()
                .getOrDefault(emptyList())

            for (block in affectedBlocks) {
                val updatedContent = replaceWikilink(block.content, change.oldName, change.newName)
                val updated = block.copy(content = updatedContent)
                val before = block
                writeActor.execute {
                    val result = repoSet.blockRepository.saveBlock(updated)
                    if (result.isSuccess) opLogger?.logUpdate(before, updated)
                    result
                }.getOrThrow()
            }
        }
    }
}
