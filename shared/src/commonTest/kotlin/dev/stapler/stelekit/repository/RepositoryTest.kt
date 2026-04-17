package dev.stapler.stelekit.repository

import dev.stapler.stelekit.db.SteleDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull

class RepositoryTest {

    @Test
    fun testRepositoryInterfaces() {
        // Test that the repository interfaces are properly defined
        // Note: We can't test actual database operations without a test database setup
        assertNotNull(BlockRepository::class)
        assertNotNull(PageRepository::class)
        assertNotNull(BaseRepository::class)
    }

    @Test
    fun testPaginationDataClass() {
        val pagination = Pagination(offset = 10, limit = 50)
        assert(pagination.offset == 10)
        assert(pagination.limit == 50)
    }

    @Test
    fun testPageDataClass() {
        val items = listOf("item1", "item2", "item3")
        val page = Page(items, 100, true)
        assert(page.items == items)
        assert(page.totalCount == 100)
        assert(page.hasNextPage == true)
    }
}