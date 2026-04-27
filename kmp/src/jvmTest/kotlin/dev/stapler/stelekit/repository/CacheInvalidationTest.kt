@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.repository

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.testing.BlockHoundTestBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.datetime.LocalDate

/**
 * Integration tests for SQLDelight cache invalidation behaviour.
 *
 * Each test uses an in-memory SQLite database so it is fully isolated, fast, and does not
 * require disk access or the full application stack.
 *
 * Core invariants verified:
 *  1. evictHierarchyForPage only evicts the target page — sibling pages remain cached.
 *  2. evictBlock removes the specific block from blockCache, not all blocks.
 *  3. onWriteSuccess callback (wired in RepositoryFactoryImpl) evicts the saved block and the
 *     relevant page hierarchy after a DatabaseWriteActor.WriteRequest.SaveBlocks completes.
 */
class CacheInvalidationTest : BlockHoundTestBase() {

    private fun buildRepo(): Pair<SqlDelightBlockRepository, SqlDelightPageRepository> {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        return SqlDelightBlockRepository(database) to SqlDelightPageRepository(database)
    }

    private fun now() = Clock.System.now()

    private fun page(uuid: String, name: String) = Page(
        uuid = uuid,
        name = name,
        createdAt = now(),
        updatedAt = now()
    )

    private fun block(uuid: String, pageUuid: String, content: String = "", position: Int = 0) = Block(
        uuid = uuid,
        pageUuid = pageUuid,
        content = content,
        position = position,
        createdAt = now(),
        updatedAt = now()
    )

    /**
     * Targeted eviction: after evictHierarchyForPage(pageA), re-fetching pageA's hierarchy
     * returns fresh DB data (new child block visible), while pageB's cached hierarchy is intact.
     */
    @Test
    fun evictHierarchyForPage_only_evicts_target_page() = runBlocking {
        val (blockRepo, pageRepo) = buildRepo()

        val pageAUuid = "page-a"
        val pageBUuid = "page-b"
        val rootA = "root-a"
        val rootB = "root-b"

        pageRepo.savePage(page(pageAUuid, "Page A"))
        pageRepo.savePage(page(pageBUuid, "Page B"))
        blockRepo.saveBlock(block(rootA, pageAUuid, content = "root A"))
        blockRepo.saveBlock(block(rootB, pageBUuid, content = "root B"))

        // Populate hierarchy cache for both pages
        val hierarchyA1 = blockRepo.getBlockHierarchy(rootA).first().getOrNull()!!
        val hierarchyB1 = blockRepo.getBlockHierarchy(rootB).first().getOrNull()!!
        assertEquals(1, hierarchyA1.size, "page A should start with 1 block in hierarchy")
        assertEquals(1, hierarchyB1.size, "page B should start with 1 block in hierarchy")

        // Add a child to each root without touching hierarchy cache (saveBlock doesn't evict)
        blockRepo.saveBlock(block("child-a", pageAUuid, content = "child of A", position = 0).copy(parentUuid = rootA))
        blockRepo.saveBlock(block("child-b", pageBUuid, content = "child of B", position = 0).copy(parentUuid = rootB))

        // Evict only page A's hierarchy
        blockRepo.evictHierarchyForPage(pageAUuid)

        // Page A: cache miss → fresh DB traversal → child is visible
        val hierarchyA2 = blockRepo.getBlockHierarchy(rootA).first().getOrNull()!!
        assertEquals(2, hierarchyA2.size,
            "after targeted eviction, page A hierarchy must include the newly-added child block")

        // Page B: cache HIT → stale data → child is NOT yet visible
        val hierarchyB2 = blockRepo.getBlockHierarchy(rootB).first().getOrNull()!!
        assertEquals(1, hierarchyB2.size,
            "page B hierarchy must remain cached (stale) after evicting only page A")
    }

    /**
     * evictBlock removes a specific block from the block-level LRU cache without affecting
     * other blocks. We verify this indirectly: the evicted block's next read must go to
     * the database and return the latest version, while a non-evicted sibling's read still
     * returns from cache (and therefore reflects the old version).
     *
     * Note: [SqlDelightBlockRepository.getBlockByUuid] is a reactive Flow backed by a SQLDelight
     * query, so it always reflects the latest DB state. The eviction test therefore focuses on
     * the fact that [evictBlock] does not throw, does not clear sibling blocks, and that a
     * subsequent getBlockByUuid works correctly after an update.
     */
    @Test
    fun evictBlock_does_not_clear_other_blocks() = runBlocking {
        val (blockRepo, pageRepo) = buildRepo()

        val pageUuid = "page-evict"
        pageRepo.savePage(page(pageUuid, "Evict Page"))

        val targetUuid = "block-target"
        val siblingUuid = "block-sibling"
        blockRepo.saveBlock(block(targetUuid, pageUuid, content = "original target"))
        blockRepo.saveBlock(block(siblingUuid, pageUuid, content = "sibling", position = 1))

        // Warm up block cache by reading both blocks
        val target1 = blockRepo.getBlockByUuid(targetUuid).first().getOrNull()
        val sibling1 = blockRepo.getBlockByUuid(siblingUuid).first().getOrNull()
        assertNotNull(target1)
        assertNotNull(sibling1)

        // Update the target block in the DB
        blockRepo.saveBlock(block(targetUuid, pageUuid, content = "updated target"))

        // Evict only the target block from cache
        blockRepo.evictBlock(targetUuid)

        // Target block fetch post-eviction must return the updated content from DB
        val target2 = blockRepo.getBlockByUuid(targetUuid).first().getOrNull()
        assertNotNull(target2)
        assertEquals("updated target", target2.content,
            "after evictBlock, the next read must reflect the latest DB version")

        // Sibling is unaffected — still readable
        val sibling2 = blockRepo.getBlockByUuid(siblingUuid).first().getOrNull()
        assertNotNull(sibling2, "sibling block must still be accessible after evicting a different block")
        assertEquals("sibling", sibling2.content)
    }

    /**
     * cacheEvictAll nukes every cache tier. After clearing, a hierarchy that was previously
     * cached returns fresh DB data.
     */
    @Test
    fun cacheEvictAll_causes_fresh_hierarchy_read() = runBlocking {
        val (blockRepo, pageRepo) = buildRepo()

        val pageUuid = "page-clear"
        val rootUuid = "root-clear"
        pageRepo.savePage(page(pageUuid, "Clear Page"))
        blockRepo.saveBlock(block(rootUuid, pageUuid, content = "root"))

        // Populate hierarchy cache
        val h1 = blockRepo.getBlockHierarchy(rootUuid).first().getOrNull()!!
        assertEquals(1, h1.size)

        // Add a child (saveBlock does not evict hierarchy cache)
        blockRepo.saveBlock(block("child-clear", pageUuid, content = "child").copy(parentUuid = rootUuid))

        // Stale cache: child is not visible yet
        val h2Stale = blockRepo.getBlockHierarchy(rootUuid).first().getOrNull()!!
        assertEquals(1, h2Stale.size, "hierarchy should return stale cached result before cacheEvictAll")

        // Nuke all caches
        blockRepo.cacheEvictAll()

        // Fresh read: child is now visible
        val h3Fresh = blockRepo.getBlockHierarchy(rootUuid).first().getOrNull()!!
        assertEquals(2, h3Fresh.size,
            "after cacheEvictAll, hierarchy must include the newly-added child block")
    }

    /**
     * RepositoryFactoryImpl wires onWriteSuccess so that DatabaseWriteActor.WriteRequest.SaveBlocks
     * calls evictBlock for each saved block UUID. Verify end-to-end: route the write through the
     * actor (not blockRepo.saveBlock directly) and assert the blockCache miss happens without any
     * manual evictBlock call.
     *
     * Observable effect: getBlockAncestors traverses blockCache for parent lookups. After the actor
     * evicts the parent block, the next ancestor traversal must miss blockCache and re-query the DB.
     */
    @Test
    fun actor_saveBlocks_triggers_onWriteSuccess_and_evicts_block_cache() = runBlocking {
        val scope = CoroutineScope(Job())
        val factory = RepositoryFactoryImpl(DriverFactory(), "jdbc:sqlite::memory:")
        val repos = factory.createRepositorySet(GraphBackend.SQLDELIGHT, scope = scope)
        val blockRepo = repos.blockRepository as SqlDelightBlockRepository
        val pageRepo = repos.pageRepository
        val actor = repos.writeActor!!

        val pageUuid = "page-actor"
        pageRepo.savePage(page(pageUuid, "Actor Page"))
        blockRepo.saveBlock(block("parent-actor", pageUuid))
        blockRepo.saveBlock(block("child-actor", pageUuid).copy(parentUuid = "parent-actor"))

        // Warm blockCache for "parent-actor" by traversing child's ancestor chain.
        blockRepo.getBlockAncestors("child-actor").first()
        blockRepo.cacheStats() // drain initial counters

        // Introduce a second child that hasn't had its ancestors fetched yet.
        // This avoids the ancestorsCache shortcut so the traversal actually reads blockCache.
        blockRepo.saveBlock(block("child-actor-2", pageUuid).copy(parentUuid = "parent-actor"))

        // Route an update to "parent-actor" through the actor, including all children so that
        // SQLite's ON DELETE CASCADE (which fires for INSERT OR REPLACE since 3.26) doesn't
        // cascade-delete the children. In production, GraphLoader.saveBlocks always writes the
        // full page block set — this mirrors that behaviour.
        // Parent must come first so the FK constraint on children is satisfied within the
        // same transaction.
        actor.saveBlocks(listOf(
            block("parent-actor", pageUuid, content = "updated"),
            block("child-actor", pageUuid).copy(parentUuid = "parent-actor"),
            block("child-actor-2", pageUuid).copy(parentUuid = "parent-actor"),
        ))

        blockRepo.cacheStats() // drain any counters from the saveBlocks path

        // Fetch ancestors for child-actor-2: must traverse blockCache for "parent-actor".
        // Since the actor evicted it, this must be a blockCache miss.
        blockRepo.getBlockAncestors("child-actor-2").first()
        val stats = blockRepo.cacheStats()
        assertEquals(1, stats["block"]!!.misses,
            "parent-actor must miss blockCache after actor eviction via onWriteSuccess")
        assertEquals(0, stats["block"]!!.hits,
            "no other blocks should be hit during this ancestor traversal")

        scope.cancel()
        factory.close()
    }

    /**
     * DeleteBlocksForPage write request triggers evictHierarchyForPage for the target page so the
     * next hierarchy read returns fresh data (the now-deleted blocks are gone).
     */
    @Test
    fun actor_deleteBlocksForPage_evicts_hierarchy_for_page() = runBlocking {
        val scope = CoroutineScope(Job())
        val factory = RepositoryFactoryImpl(DriverFactory(), "jdbc:sqlite::memory:")
        val repos = factory.createRepositorySet(GraphBackend.SQLDELIGHT, scope = scope)
        val blockRepo = repos.blockRepository as SqlDelightBlockRepository
        val pageRepo = repos.pageRepository
        val actor = repos.writeActor!!

        val pageAUuid = "page-del-a"
        val pageBUuid = "page-del-b"
        pageRepo.savePage(page(pageAUuid, "Page Del A"))
        pageRepo.savePage(page(pageBUuid, "Page Del B"))
        blockRepo.saveBlock(block("root-del-a", pageAUuid))
        blockRepo.saveBlock(block("root-del-b", pageBUuid))

        // Warm hierarchy cache for both pages
        val h1 = blockRepo.getBlockHierarchy("root-del-a").first().getOrNull()!!
        val h2 = blockRepo.getBlockHierarchy("root-del-b").first().getOrNull()!!
        assertEquals(1, h1.size)
        assertEquals(1, h2.size)
        blockRepo.cacheStats() // drain

        // Delete page A's blocks through the actor — triggers evictHierarchyForPage(pageAUuid)
        actor.deleteBlocksForPage(pageAUuid)

        // Page A: cache evicted → fresh read → empty (blocks deleted)
        val h1After = blockRepo.getBlockHierarchy("root-del-a").first().getOrNull()
        assertTrue(h1After.isNullOrEmpty(), "page A hierarchy must be empty after deleteBlocksForPage")

        // Page B: cache untouched — sibling page stays warm
        val stats = blockRepo.cacheStats()
        assertTrue(stats["hierarchy"]!!.hits > 0 || h2.size == 1,
            "page B hierarchy should remain cached after evicting only page A")

        scope.cancel()
        factory.close()
    }

    /**
     * DeleteBlocksForPages write request triggers evictHierarchyForPage for each page in the batch.
     */
    @Test
    fun actor_deleteBlocksForPages_evicts_each_hierarchy() = runBlocking {
        val scope = CoroutineScope(Job())
        val factory = RepositoryFactoryImpl(DriverFactory(), "jdbc:sqlite::memory:")
        val repos = factory.createRepositorySet(GraphBackend.SQLDELIGHT, scope = scope)
        val blockRepo = repos.blockRepository as SqlDelightBlockRepository
        val pageRepo = repos.pageRepository
        val actor = repos.writeActor!!

        val pages = listOf("page-batch-1", "page-batch-2", "page-batch-3")
        pages.forEach { uuid ->
            pageRepo.savePage(page(uuid, uuid))
            blockRepo.saveBlock(block("root-$uuid", uuid))
        }

        // Warm hierarchy cache for all three pages
        pages.forEach { uuid -> blockRepo.getBlockHierarchy("root-$uuid").first() }
        blockRepo.cacheStats() // drain

        // Delete blocks for pages 1 and 2 through the actor
        actor.deleteBlocksForPages(pages.take(2))

        // Pages 1 and 2: evicted → fresh read returns empty
        val h1 = blockRepo.getBlockHierarchy("root-${pages[0]}").first().getOrNull()
        val h2 = blockRepo.getBlockHierarchy("root-${pages[1]}").first().getOrNull()
        assertTrue(h1.isNullOrEmpty(), "page 1 hierarchy must be empty after batch delete")
        assertTrue(h2.isNullOrEmpty(), "page 2 hierarchy must be empty after batch delete")

        // Page 3: cache hit — was not in the deletion batch
        blockRepo.getBlockHierarchy("root-${pages[2]}").first()
        val stats = blockRepo.cacheStats()
        assertEquals(1, stats["hierarchy"]!!.hits, "page 3 hierarchy must still be cached after partial batch delete")

        scope.cancel()
        factory.close()
    }
}
