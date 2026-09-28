package dev.stapler.stelekit.db.libsql

import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.db.SteleDatabaseQueries
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests verifying FTS5 works end-to-end through the libsql JNI bridge.
 *
 * Uses a real temp-file database (not in-memory) so that the JNI bridge is exercised
 * for both schema creation and every query. All tests are silently skipped when the
 * native libsql library is not bundled in the classpath.
 */
class LibsqlFts5IntegrationTest {

    private lateinit var driver: JvmLibsqlDriver
    private lateinit var db: SteleDatabase
    private lateinit var queries: SteleDatabaseQueries

    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        LibsqlTestHarness.assumeNativeAvailable()
        driver = LibsqlTestHarness.createTempDriver()
        db = SteleDatabase(driver)
        queries = db.steleDatabaseQueries
    }

    @After
    fun tearDown() {
        if (::driver.isInitialized) runCatching { driver.close() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun insertPage(uuid: String, name: String) = runBlocking {
        queries.insertPage(
            uuid = uuid,
            name = name,
            namespace = null,
            file_path = null,
            created_at = now,
            updated_at = now,
            properties = null,
            version = 0L,
            is_favorite = 0L,
            is_journal = 0L,
            journal_date = null,
            is_content_loaded = 1L,
            section_id = "",
        )
    }

    private fun insertBlock(
        uuid: String,
        pageUuid: String,
        content: String,
        position: String = "0",
    ) = runBlocking {
        queries.insertBlock(
            uuid = uuid,
            page_uuid = pageUuid,
            parent_uuid = null,
            left_uuid = null,
            content = content,
            level = 0L,
            position = position,
            created_at = now,
            updated_at = now,
            properties = null,
            version = 0L,
            content_hash = null,
            block_type = "bullet",
        )
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Insert blocks with known text, search for a unique term, assert the correct block
     * is returned and that the highlight column contains the expected <em> tags.
     */
    @Test
    fun fts5_blockSearch_returnsMatchingBlocks() {
        val pageUuid = "page-fts-basic"
        insertPage(pageUuid, "FTS Basic Test Page")
        insertBlock("block-fox", pageUuid, "the quick brown fox jumps", position = "0")
        insertBlock("block-dog", pageUuid, "a lazy dog lies still", position = "1")

        val results = queries.searchBlocksByContentFts(
            query = "fox",
            limit = 10L,
            offset = 0L,
        ).executeAsList()

        assertEquals(1, results.size, "Only the fox block should match")
        assertEquals("block-fox", results[0].uuid)
        val highlight: String? = results[0].highlight
        assertNotNull(highlight, "highlight column must not be null")
        assertTrue(
            highlight.contains("<em>fox</em>", ignoreCase = true),
            "Expected <em>fox</em> in highlight, got: $highlight",
        )
    }

    /**
     * Blocks with higher term frequency should rank first (more negative BM25 score =
     * more relevant). Block A has "cat" three times; it must come before Block B ("cat" once).
     */
    @Test
    fun fts5_blockSearch_bm25Ordering() {
        val pageUuid = "page-fts-bm25"
        insertPage(pageUuid, "FTS BM25 Test Page")
        insertBlock("block-cat-many", pageUuid, "cat cat cat loves cats", position = "0")
        insertBlock("block-cat-one", pageUuid, "cat", position = "1")
        insertBlock("block-no-cat", pageUuid, "unrelated dog content", position = "2")

        val results = queries.searchBlocksByContentFts(
            query = "cat",
            limit = 10L,
            offset = 0L,
        ).executeAsList()

        // Block C (no match) must be absent
        assertEquals(2, results.size, "Only the two cat blocks should match")

        // BM25 scores are negative; more negative = more relevant = ranked first
        val firstUuid = results[0].uuid
        assertEquals(
            "block-cat-many",
            firstUuid,
            "Block with higher term frequency should rank first (lower/more-negative BM25)",
        )
    }

    /**
     * After updating a block's content, the FTS index must reflect the new text
     * (via the blocks_au trigger). Searching for the old term must return 0 results;
     * searching for the new term must return 1.
     */
    @Test
    fun fts5_blockUpdate_triggerKeepsIndexFresh() {
        val pageUuid = "page-fts-trigger"
        insertPage(pageUuid, "FTS Trigger Test Page")
        insertBlock("block-update", pageUuid, "original text content here", position = "0")

        // Verify the original term is indexed
        val beforeUpdate = queries.searchBlocksByContentFts(
            query = "original",
            limit = 10L,
            offset = 0L,
        ).executeAsList()
        assertEquals(1, beforeUpdate.size, "Should find block before update")

        // Update content — this fires the blocks_au trigger through the JNI bridge
        runBlocking {
            queries.updateBlockContent(
                content = "updated content here",
                updated_at = now + 1000L,
                content_hash = null,
                uuid = "block-update",
            )
        }

        val afterOriginal = queries.searchBlocksByContentFts(
            query = "original",
            limit = 10L,
            offset = 0L,
        ).executeAsList()
        assertEquals(0, afterOriginal.size, "Old term must not match after content update")

        val afterUpdated = queries.searchBlocksByContentFts(
            query = "updated",
            limit = 10L,
            offset = 0L,
        ).executeAsList()
        assertEquals(1, afterUpdated.size, "New term must match after content update")
        assertEquals("block-update", afterUpdated[0].uuid)
    }

    /**
     * Insert two pages with distinct names, search for a term that matches only one,
     * and assert only that page is returned by searchPagesByNameFts.
     */
    @Test
    fun fts5_pageSearch_returnsMatchingPages() {
        insertPage("page-kotlin", "Kotlin Tips")
        insertPage("page-android", "Android Guide")

        val results = queries.searchPagesByNameFts(
            query = "Kotlin",
            limit = 10L,
        ).executeAsList()

        assertEquals(1, results.size, "Only the Kotlin page should match")
        assertEquals("page-kotlin", results[0].uuid)
        assertEquals("Kotlin Tips", results[0].name)
    }

    /**
     * Insert 3 matching blocks and 2 non-matching blocks, then assert that
     * searchBlocksCountFts returns exactly 3.
     */
    @Test
    fun fts5_countQuery_returnsCorrectCount() {
        val pageUuid = "page-fts-count"
        insertPage(pageUuid, "FTS Count Test Page")
        // 3 matching blocks
        insertBlock("block-match-1", pageUuid, "elephant in the room", position = "0")
        insertBlock("block-match-2", pageUuid, "spotted elephant roaming", position = "1")
        insertBlock("block-match-3", pageUuid, "elephant stampede", position = "2")
        // 2 non-matching blocks
        insertBlock("block-no-match-1", pageUuid, "giraffe eating leaves", position = "3")
        insertBlock("block-no-match-2", pageUuid, "zebra crossing stripes", position = "4")

        val count = queries.searchBlocksCountFts(query = "elephant").executeAsOne()
        assertEquals(3L, count, "Count should be 3 matching blocks")
    }
}
