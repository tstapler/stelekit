package dev.stapler.stelekit.repository

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Page
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class ExactTitleMatchTest {

    private val repo: SqlDelightSearchRepository by lazy {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val db = SteleDatabase(driver)
        SqlDelightSearchRepository(db)
    }

    private fun fakePage(uuid: String, name: String): Page {
        val now = Clock.System.now()
        return Page(
            uuid = uuid,
            name = name,
            namespace = null,
            filePath = null,
            createdAt = now,
            updatedAt = now,
            properties = emptyMap(),
        )
    }

    private fun blockHit(pageUuid: String, score: Double): RankedSearchHit.BlockHit {
        val now = Clock.System.now()
        return RankedSearchHit.BlockHit(
            block = dev.stapler.stelekit.model.Block(
                uuid = "block-$pageUuid",
                pageUuid = pageUuid,
                content = "some content",
                level = 0,
                position = 0,
                createdAt = now,
                updatedAt = now,
            ),
            snippet = null,
            score = score,
        )
    }

    // ── TC-10: promoteExactTitleMatch handles leading/trailing whitespace ───

    @Test
    fun promoteExactTitleMatch_promotesWithWhitespaceTrimming() {
        val taxesPage = fakePage("taxes-uuid", "Taxes")
        val otherPage1 = fakePage("other-1", "Other Page")
        val otherPage2 = fakePage("other-2", "Another Page")

        // Taxes is at index 2 (behind two higher-scored block hits)
        val ranked = listOf(
            blockHit("other-1", 10.0),
            blockHit("other-2", 8.0),
            RankedSearchHit.PageHit(taxesPage, null, 3.0),
        )

        val result = repo.promoteExactTitleMatch(ranked, "  Taxes  ")

        assertEquals(3, result.size)
        val first = result[0]
        assertTrue(first is RankedSearchHit.PageHit, "First should be PageHit after promotion")
        assertEquals("Taxes", (first as RankedSearchHit.PageHit).page.name)

        // Remaining order must be preserved
        assertTrue(result[1] is RankedSearchHit.BlockHit)
        assertTrue(result[2] is RankedSearchHit.BlockHit)
    }

    @Test
    fun promoteExactTitleMatch_noopWhenAlreadyFirst() {
        val taxesPage = fakePage("taxes-uuid", "Taxes")
        val ranked = listOf(
            RankedSearchHit.PageHit(taxesPage, null, 10.0),
            blockHit("other-1", 5.0),
        )

        val result = repo.promoteExactTitleMatch(ranked, "Taxes")
        assertEquals(ranked, result, "Should return unchanged list when match is already first")
    }

    @Test
    fun promoteExactTitleMatch_noopWhenNoMatch() {
        val ranked = listOf(
            blockHit("other-1", 10.0),
            blockHit("other-2", 5.0),
        )

        val result = repo.promoteExactTitleMatch(ranked, "Taxes")
        assertEquals(ranked, result, "Should return unchanged list when no exact match exists")
    }
}
