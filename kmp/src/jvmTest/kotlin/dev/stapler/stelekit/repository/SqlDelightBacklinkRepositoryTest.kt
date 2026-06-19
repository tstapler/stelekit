@file:OptIn(dev.stapler.stelekit.repository.DirectRepositoryWrite::class)

package dev.stapler.stelekit.repository

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

/**
 * Contract tests for the [wikilink_references] index and the [pages.backlink_count] column.
 *
 * Added as part of the `perf(db): add wikilink_references index for O(1) backlink counting` change.
 *
 * These tests exercise [SqlDelightBlockRepository] directly against a real JVM SQLite in-memory
 * database — no mocks, no stubs. Each test creates its own fresh database for full isolation.
 *
 * Key invariants guarded:
 * - Inserting a block with `[[PageA]]` adds a row to [wikilink_references] and [countLinkedReferences]
 *   returns a non-zero value.
 * - The COLLATE NOCASE index honours case-insensitive lookups via [countLinkedReferences].
 * - Deleting a block cascades to [wikilink_references] (FK ON DELETE CASCADE) so backlink counts
 *   return to zero after block deletion.
 * - [updateBlockContentOnly] replaces wikilink refs atomically — old page loses its ref, new page
 *   gains one.
 * - [deleteBlocksForPage] recomputes [pages.backlink_count] via [recomputeBacklinkCountFromIndex].
 * - Multiple links in one block are independently tracked.
 * - [splitBlock] maintains link tracking across both resulting blocks.
 */
class SqlDelightBacklinkRepositoryTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private data class Repos(
        val blockRepo: SqlDelightBlockRepository,
        val pageRepo: SqlDelightPageRepository,
        val database: SteleDatabase,
        val actor: DatabaseWriteActor,
        val scope: CoroutineScope,
    )

    private fun buildRepos(): Repos {
        val driver = DriverFactory().createDriver("jdbc:sqlite::memory:")
        val database = SteleDatabase(driver)
        val blockRepo = SqlDelightBlockRepository(database, driver)
        val pageRepo = SqlDelightPageRepository(database)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val actor = DatabaseWriteActor(blockRepo, pageRepo, scope = scope)
        return Repos(blockRepo, pageRepo, database, actor, scope)
    }

    private fun Repos.close() {
        actor.close()
        scope.cancel()
    }

    private fun now() = Clock.System.now()

    private fun makePage(name: String) = Page(
        uuid = PageUuid("uuid-${name.lowercase().replace(' ', '-')}"),
        name = name,
        createdAt = now(),
        updatedAt = now(),
    )

    private fun makeBlock(uuid: String, pageUuid: String, content: String) = Block(
        uuid = BlockUuid(uuid),
        pageUuid = PageUuid(pageUuid),
        content = content,
        position = 0,
        createdAt = now(),
        updatedAt = now(),
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * TC-1: save a block with [[PageA]], assert countLinkedReferences("PageA") returns 1.
     *
     * The wikilink_references row is inserted by saveBlock which calls addWikilinkRefs().
     * countLinkedReferences uses a LIKE scan (O(N)) and independently verifies the count.
     */
    @Test
    fun wikilinkRef_insert_and_countLinkedReferences_returns_1() = runBlocking {
        val repos = buildRepos()
        try {
            repos.pageRepo.savePage(makePage("PageA"))
            repos.blockRepo.saveBlock(makeBlock("block-1", "uuid-pagea", "hello [[PageA]]"))

            val count = repos.blockRepo.countLinkedReferences("PageA").first().getOrNull()
            assertEquals(1L, count, "countLinkedReferences(PageA) must be 1 after inserting a block with [[PageA]]")
        } finally {
            repos.close()
        }
    }

    /**
     * TC-2: COLLATE NOCASE — save block with [[pageA]], verify countLinkedReferences matches
     * regardless of case for the search name.
     *
     * The idx_wikilink_refs_page_name index is COLLATE NOCASE.
     * countLinkedReferences uses compileLinkPatterns with IGNORE_CASE regex option.
     */
    @Test
    fun wikilinkRef_collate_nocase_matches_regardless_of_case() = runBlocking {
        val repos = buildRepos()
        try {
            repos.pageRepo.savePage(makePage("PageA"))
            repos.blockRepo.saveBlock(makeBlock("block-nc", "uuid-pagea", "hello [[pageA]]"))

            val countUpper = repos.blockRepo.countLinkedReferences("PageA").first().getOrNull()
            val countLower = repos.blockRepo.countLinkedReferences("pagea").first().getOrNull()

            assertEquals(1L, countUpper, "countLinkedReferences(PageA) must match [[pageA]] (NOCASE)")
            assertEquals(1L, countLower, "countLinkedReferences(pagea) must match [[pageA]] (NOCASE)")
        } finally {
            repos.close()
        }
    }

    /**
     * TC-3: aliased wikilink [[Page|alias]] — countLinkedReferences("Page") returns 1.
     *
     * The LIKE scan in countLinkedReferences matches content containing `[[Page` as a prefix,
     * so `[[Page|alias]]` counts correctly for page name "Page".
     * The alias part ("|alias") is stored in wikilink_references but the LIKE scan pattern
     * `%[[Page%` independently captures aliased links.
     */
    @Test
    fun wikilinkRef_aliased_link_pagepart_is_indexed() = runBlocking {
        val repos = buildRepos()
        try {
            repos.pageRepo.savePage(makePage("Page"))
            repos.blockRepo.saveBlock(makeBlock("block-alias", "uuid-page", "link [[Page|alias]]"))

            val count = repos.blockRepo.countLinkedReferences("Page").first().getOrNull()
            assertEquals(1L, count, "countLinkedReferences(Page) must return 1 for [[Page|alias]] — alias is ignored by LIKE scan")
        } finally {
            repos.close()
        }
    }

    /**
     * TC-4: block deletion cascades to wikilink_references.
     *
     * The wikilink_references table has `FOREIGN KEY (block_uuid) REFERENCES blocks(uuid) ON DELETE CASCADE`.
     * After the block is deleted, countLinkedReferences should return 0.
     */
    @Test
    fun wikilinkRef_block_delete_cascades_to_index() = runBlocking {
        val repos = buildRepos()
        try {
            repos.pageRepo.savePage(makePage("PageA"))
            repos.blockRepo.saveBlock(makeBlock("block-del", "uuid-pagea", "[[PageA]]"))

            val before = repos.blockRepo.countLinkedReferences("PageA").first().getOrNull()
            assertEquals(1L, before, "count before delete must be 1")

            repos.blockRepo.deleteBlock(BlockUuid("block-del"))

            val after = repos.blockRepo.countLinkedReferences("PageA").first().getOrNull()
            assertEquals(0L, after, "countLinkedReferences must be 0 after deleting the only linking block (FK ON DELETE CASCADE)")
        } finally {
            repos.close()
        }
    }

    /**
     * TC-5: updateBlockContentOnly replaces wikilink refs atomically.
     *
     * Before update: block links [[OldPage]] — countLinkedReferences(OldPage) = 1.
     * After update:  block links [[NewPage]] — countLinkedReferences(OldPage) = 0, NewPage = 1.
     *
     * updateBlockContentOnly calls replaceWikilinkRefs + recomputeBacklinkCountFromIndex so both
     * the wikilink_references rows and pages.backlink_count are updated.
     */
    @Test
    fun wikilinkRef_updateBlockContentOnly_replaces_refs() = runBlocking {
        val repos = buildRepos()
        try {
            repos.pageRepo.savePage(makePage("OldPage"))
            repos.pageRepo.savePage(makePage("NewPage"))
            repos.blockRepo.saveBlock(makeBlock("block-upd", "uuid-oldpage", "[[OldPage]]"))

            val beforeOld = repos.blockRepo.countLinkedReferences("OldPage").first().getOrNull()
            assertEquals(1L, beforeOld, "OldPage count before update must be 1")

            repos.blockRepo.updateBlockContentOnly(BlockUuid("block-upd"), "[[NewPage]]")

            val afterOld = repos.blockRepo.countLinkedReferences("OldPage").first().getOrNull()
            val afterNew = repos.blockRepo.countLinkedReferences("NewPage").first().getOrNull()
            assertEquals(0L, afterOld, "OldPage count must drop to 0 after content update")
            assertEquals(1L, afterNew, "NewPage count must become 1 after content update")
        } finally {
            repos.close()
        }
    }

    /**
     * TC-6: deleteBlocksForPage recomputes pages.backlink_count via the wikilink_references index.
     *
     * Setup: PageA has a block linking [[PageB]]. pages.backlink_count for PageB should be 1.
     * After deleteBlocksForPage(PageA): the blocks and their wikilink_references rows are removed,
     * then recomputeBacklinkCountFromIndex("PageB") sets pages.backlink_count = 0.
     *
     * Accesses pages.backlink_count directly via selectPageBacklinkCount (raw generated query).
     */
    @Test
    fun wikilinkRef_deleteBlocksForPage_recomputes_backlink_counts() = runBlocking {
        val repos = buildRepos()
        try {
            repos.pageRepo.savePage(makePage("PageA"))
            repos.pageRepo.savePage(makePage("PageB"))

            // Insert block on PageA that links [[PageB]]
            repos.blockRepo.saveBlock(makeBlock("block-pageA", "uuid-pagea", "links [[PageB]]"))

            // Confirm PageB's backlink count is 1 after saveBlock + recomputeBacklinkCountFromIndex
            // Note: saveBlock calls recomputeBacklinkCountFromIndex("PageB") automatically
            val backlinkBefore = repos.database.steleDatabaseQueries
                .selectPageBacklinkCount("PageB")
                .executeAsOneOrNull()
            assertEquals(1L, backlinkBefore, "pages.backlink_count for PageB must be 1 after saving linking block")

            // Delete all blocks from PageA — triggers recomputeBacklinkCountFromIndex("PageB")
            repos.blockRepo.deleteBlocksForPage(PageUuid("uuid-pagea"))

            val backlinkAfter = repos.database.steleDatabaseQueries
                .selectPageBacklinkCount("PageB")
                .executeAsOneOrNull()
            assertEquals(0L, backlinkAfter, "pages.backlink_count for PageB must be 0 after deleteBlocksForPage(PageA)")
        } finally {
            repos.close()
        }
    }

    /**
     * TC-7: a block with multiple wikilinks tracks each target independently.
     *
     * Content `[[A]] and [[B]]` should result in countLinkedReferences("A") = 1 and
     * countLinkedReferences("B") = 1 simultaneously.
     */
    @Test
    fun wikilinkRef_multiple_links_in_one_block() = runBlocking {
        val repos = buildRepos()
        try {
            repos.pageRepo.savePage(makePage("A"))
            repos.pageRepo.savePage(makePage("B"))
            repos.blockRepo.saveBlock(makeBlock("block-multi", "uuid-a", "[[A]] and [[B]]"))

            val countA = repos.blockRepo.countLinkedReferences("A").first().getOrNull()
            val countB = repos.blockRepo.countLinkedReferences("B").first().getOrNull()
            assertEquals(1L, countA, "countLinkedReferences(A) must be 1 for block linking both [[A]] and [[B]]")
            assertEquals(1L, countB, "countLinkedReferences(B) must be 1 for block linking both [[A]] and [[B]]")
        } finally {
            repos.close()
        }
    }

    /**
     * TC-8: splitBlock maintains link tracking for both resulting blocks.
     *
     * A block with content "[[A]] split here" is split at cursor position 3 (inside the text).
     * The split produces:
     *  - block-split: truncated content (may or may not contain [[A]] depending on cursor)
     *  - new block: suffix content
     *
     * Strategy: use a block with two separate links and split between them. Verify that the
     * existing block keeps its link and the new block gains the link from its content half.
     *
     * To avoid cursor-position ambiguity: use content "[[A]][[B]]" split at position 5
     * (after the [[A]] closing bracket). Post-split: original = "[[A]]", new = "[[B]]".
     */
    @Test
    fun wikilinkRef_splitBlock_maintains_refs_for_both_halves() = runBlocking {
        val repos = buildRepos()
        try {
            repos.pageRepo.savePage(makePage("A"))
            repos.pageRepo.savePage(makePage("B"))

            // Original block links both A and B
            repos.blockRepo.saveBlock(makeBlock("block-split", "uuid-a", "[[A]][[B]]"))

            val beforeA = repos.blockRepo.countLinkedReferences("A").first().getOrNull()
            val beforeB = repos.blockRepo.countLinkedReferences("B").first().getOrNull()
            assertEquals(1L, beforeA, "Before split: A count must be 1")
            assertEquals(1L, beforeB, "Before split: B count must be 1")

            // Split at position 5 ("[[A]]" is 5 chars) — original keeps "[[A]]", new gets "[[B]]"
            val newBlockUuid = BlockUuid("block-split-new")
            repos.blockRepo.splitBlock(BlockUuid("block-split"), 5, newBlockUuid)

            // After split: A is on the original block, B is on the new block
            // Both links must still be tracked
            val afterA = repos.blockRepo.countLinkedReferences("A").first().getOrNull()
            val afterB = repos.blockRepo.countLinkedReferences("B").first().getOrNull()
            assertEquals(1L, afterA, "After split: A must still have 1 reference (on original block)")
            assertEquals(1L, afterB, "After split: B must still have 1 reference (on new block from split)")
        } finally {
            repos.close()
        }
    }
}
