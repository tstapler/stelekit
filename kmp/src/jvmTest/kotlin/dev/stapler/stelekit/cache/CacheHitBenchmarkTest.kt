@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.cache

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.SqlDelightBlockRepository
import dev.stapler.stelekit.repository.SqlDelightPageRepository
import dev.stapler.stelekit.testing.BlockHoundTestBase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Verifies that the hierarchy cache delivers hits on repeated reads.
 *
 * Each test uses an in-memory SQLite database and [LruCache.snapshotAndReset] via
 * [SqlDelightBlockRepository.cacheStats] to observe hit/miss counts without driver-level hooks.
 */
class CacheHitBenchmarkTest : BlockHoundTestBase() {

    private fun buildRepos(): Pair<SqlDelightBlockRepository, SqlDelightPageRepository> {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        return SqlDelightBlockRepository(database) to SqlDelightPageRepository(database)
    }

    private fun now() = Clock.System.now()

    private fun page(uuid: String) = Page(uuid = uuid, name = uuid, createdAt = now(), updatedAt = now())

    private fun block(uuid: String, pageUuid: String, parentUuid: String? = null) = Block(
        uuid = uuid, pageUuid = pageUuid, content = "c-$uuid",
        position = 0, parentUuid = parentUuid,
        createdAt = now(), updatedAt = now()
    )

    @Test
    fun hierarchy_cold_read_is_a_miss() = runBlocking {
        val (blockRepo, pageRepo) = buildRepos()
        pageRepo.savePage(page("p"))
        blockRepo.saveBlock(block("root", "p"))

        // Drain any counters from save operations
        blockRepo.cacheStats()

        // Cold read
        blockRepo.getBlockHierarchy("root").first()
        val stats = blockRepo.cacheStats()

        assertEquals(0, stats["hierarchy"]!!.hits, "cold hierarchy read must be a miss, not a hit")
        assertEquals(1, stats["hierarchy"]!!.misses)
    }

    @Test
    fun hierarchy_second_read_is_a_hit() = runBlocking {
        val (blockRepo, pageRepo) = buildRepos()
        pageRepo.savePage(page("p"))
        blockRepo.saveBlock(block("root", "p"))
        blockRepo.saveBlock(block("child", "p", parentUuid = "root"))

        blockRepo.cacheStats() // drain

        // First read: miss
        blockRepo.getBlockHierarchy("root").first()
        val afterCold = blockRepo.cacheStats()
        assertEquals(0, afterCold["hierarchy"]!!.hits)
        assertEquals(1, afterCold["hierarchy"]!!.misses)

        // Second read within TTL: hit
        blockRepo.getBlockHierarchy("root").first()
        val afterWarm = blockRepo.cacheStats()
        assertEquals(1, afterWarm["hierarchy"]!!.hits, "second hierarchy read within TTL must be a cache hit")
        assertEquals(0, afterWarm["hierarchy"]!!.misses)
    }

    @Test
    fun evictHierarchyForPage_forces_miss_on_next_read() = runBlocking {
        val (blockRepo, pageRepo) = buildRepos()
        pageRepo.savePage(page("p"))
        blockRepo.saveBlock(block("root", "p"))

        blockRepo.cacheStats() // drain

        // Warm the cache
        blockRepo.getBlockHierarchy("root").first()
        blockRepo.cacheStats() // drain the miss counter

        // Evict
        blockRepo.evictHierarchyForPage("p")

        // Next read must miss
        blockRepo.getBlockHierarchy("root").first()
        val stats = blockRepo.cacheStats()
        assertEquals(0, stats["hierarchy"]!!.hits)
        assertEquals(1, stats["hierarchy"]!!.misses, "read after targeted eviction must be a cache miss")
    }

    @Test
    fun cache_eviction_count_increments_when_capacity_exceeded() = runBlocking {
        // Small cache: weight 2 → holds at most 2 entries
        val smallCache = LruCache<String, String>(maxWeight = 2L)

        smallCache.put("a", "1")
        smallCache.put("b", "2")
        // "a" is LRU — adding "c" must evict "a"
        smallCache.put("c", "3")

        val stats = smallCache.snapshotAndReset()
        assertTrue(stats.evictions >= 1, "cache must record at least one eviction when capacity is exceeded")
    }
}
