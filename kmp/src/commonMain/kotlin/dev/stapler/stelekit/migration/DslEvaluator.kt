// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.migration

import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.RepositorySet
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.flow.first

/**
 * Evaluates a [Migration]'s `apply` lambda against live repository state and produces a
 * [List] of [BlockChange] entries representing the minimal delta needed — without writing
 * anything to the database.
 *
 * Idempotency: changes whose postcondition is already satisfied (e.g. the property already
 * has the target value) are skipped so that re-running the evaluator on a post-migration
 * graph returns an empty list.
 *
 * Safety guard: if [Migration.allowDestructive] is false, any [BlockChange.DeleteBlock] or
 * [BlockChange.DeletePage] accumulated during evaluation throws [DestructiveOperationException].
 *
 * Note: [MigrationScope.forBlocks] and [MigrationScope.forPages] are non-suspend interface
 * methods. To avoid calling runBlocking inside a coroutine context (which can deadlock on
 * single-threaded dispatchers), all repository data is pre-fetched in [evaluate] before
 * the migration lambda is invoked. The scope impls then operate on that in-memory snapshot.
 */
class DslEvaluator(private val repoSet: RepositorySet) {

    /**
     * Runs [migration.apply] on a fresh [MigrationScopeImpl] and returns the accumulated
     * [BlockChange] list. Does NOT write to any repository.
     */
    suspend fun evaluate(migration: Migration): List<BlockChange> {
        // Pre-fetch all pages and their blocks so the sync forBlocks/forPages methods
        // don't need to call runBlocking themselves.
        val allPages = repoSet.pageRepository.getAllPages().first().getOrDefault(emptyList())
        val blocksByPage: Map<String, List<Block>> = allPages.associate { page ->
            page.uuid to repoSet.blockRepository.getBlocksForPage(page.uuid)
                .first().getOrDefault(emptyList())
        }

        val scope = MigrationScopeImpl(migration, allPages, blocksByPage)
        migration.apply.invoke(scope)
        return scope.changes.toList()
    }

    // ── Inner scope implementations ─────────────────────────────────────────────

    private inner class MigrationScopeImpl(
        private val migration: Migration,
        private val allPages: List<Page>,
        private val blocksByPage: Map<String, List<Block>>,
    ) : MigrationScope {

        val changes = mutableListOf<BlockChange>()

        override fun forBlocks(where: (Block) -> Boolean, transform: BlockScope.() -> Unit) {
            for ((_, blocks) in blocksByPage) {
                for (block in blocks) {
                    if (!where(block)) continue
                    val blockScope = BlockScopeImpl(block)
                    transform.invoke(blockScope)
                    for (change in blockScope.changes) {
                        appendChange(change)
                    }
                }
            }
        }

        override fun forPages(where: (Page) -> Boolean, transform: PageScope.() -> Unit) {
            for (page in allPages) {
                if (!where(page)) continue
                val pageScope = PageScopeImpl(page, blocksByPage)
                transform.invoke(pageScope)
                for (change in pageScope.changes) {
                    appendChange(change)
                }
            }
        }

        override fun findPage(name: String): Page? = allPages.firstOrNull { it.name == name }

        private fun appendChange(change: BlockChange) {
            if (!migration.allowDestructive) {
                when (change) {
                    is BlockChange.DeleteBlock -> throw DestructiveOperationException(
                        "Migration '${migration.id}' attempted destructive operation 'DeleteBlock' but allowDestructive = false"
                    )
                    is BlockChange.DeletePage -> throw DestructiveOperationException(
                        "Migration '${migration.id}' attempted destructive operation 'DeletePage' but allowDestructive = false"
                    )
                    else -> Unit
                }
            }
            changes.add(change)
        }
    }

    private inner class BlockScopeImpl(override val block: Block) : BlockScope {

        val changes = mutableListOf<BlockChange>()

        override fun setProperty(key: String, value: String) {
            // Idempotency: skip if the property already has the target value.
            if (block.properties[key] == value) return
            changes.add(BlockChange.UpsertProperty(block.uuid, key, value))
        }

        override fun deleteProperty(key: String) {
            // Idempotency: skip if the property doesn't exist.
            if (!block.properties.containsKey(key)) return
            changes.add(BlockChange.DeleteProperty(block.uuid, key))
        }

        override fun setContent(newContent: String) {
            // Idempotency: skip if content is already the target value.
            if (block.content == newContent) return
            changes.add(BlockChange.SetContent(block.uuid, newContent))
        }

        override fun deleteBlock() {
            changes.add(BlockChange.DeleteBlock(block.uuid))
        }
    }

    private inner class PageScopeImpl(
        override val page: Page,
        private val blocksByPage: Map<String, List<Block>>,
    ) : PageScope {

        val changes = mutableListOf<BlockChange>()

        override fun setProperty(key: String, value: String) {
            // Idempotency: skip if the property already has the target value.
            if (page.properties[key] == value) return
            changes.add(BlockChange.UpsertPageProperty(page.uuid, key, value))
        }

        override fun deleteProperty(key: String) {
            // Idempotency: skip if the property doesn't exist.
            if (!page.properties.containsKey(key)) return
            changes.add(BlockChange.DeletePageProperty(page.uuid, key))
        }

        override fun renamePage(newName: String) {
            // Idempotency: skip if the page already has the target name.
            if (page.name == newName) return
            changes.add(BlockChange.RenamePage(page.uuid, page.name, newName))
        }

        override fun deletePage() {
            changes.add(BlockChange.DeletePage(page.uuid))
        }

        override fun mergeIntoPage(targetPageUuid: String) {
            val blocks = blocksByPage[page.uuid] ?: emptyList()
            for (block in blocks) {
                if (block.content.isNotBlank()) {
                    changes.add(BlockChange.InsertBlock(block.copy(
                        uuid = UuidGenerator.generateV7(),
                        pageUuid = targetPageUuid,
                    )))
                }
                changes.add(BlockChange.DeleteBlock(block.uuid))
            }
            changes.add(BlockChange.DeletePage(page.uuid))
        }
    }
}
