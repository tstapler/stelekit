package dev.stapler.stelekit.repository

import dev.stapler.stelekit.db.DatabaseWriteActor
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * SAM interface used by GraphLoader to resolve journal pages by date.
 * Decouples the db package from the repository package to avoid circular dependencies.
 */
fun interface JournalDateResolver {
    suspend fun getPageByJournalDate(date: LocalDate): Page?
}

/**
 * Owns all journal-domain logic: querying, ensuring today's entry exists, etc.
 * Delegates storage to PageRepository and BlockRepository.
 *
 * This is the single place that knows about journal-page semantics.
 * All callers (GraphLoader, JournalsViewModel, StelekitViewModel) should depend on this
 * rather than on PageRepository's journal methods.
 */
class JournalService(
    private val pageRepository: PageRepository,
    private val blockRepository: BlockRepository,
    private val writeActor: DatabaseWriteActor? = null
) : JournalDateResolver {

    private val logger = Logger("JournalService")
    private val mutex = Mutex()

    // ---- JournalDateResolver (for GraphLoader) ----

    override suspend fun getPageByJournalDate(date: LocalDate): Page? {
        // Check by date first, then by both name formats to catch duplicates
        val byDate = pageRepository.getJournalPageByDate(date).first().getOrNull()
        if (byDate != null) return byDate

        // Fallback: name-based lookup for cases where journalDate column isn't set
        val hyphenName = date.toString()
        val underscoreName = hyphenName.replace('-', '_')
        return pageRepository.getPageByName(hyphenName).first().getOrNull()
            ?: pageRepository.getPageByName(underscoreName).first().getOrNull()
    }

    // ---- Journal queries ----

    fun getJournalPageByDate(date: LocalDate): Flow<Result<Page?>> =
        pageRepository.getJournalPageByDate(date)

    fun getJournalPages(limit: Int, offset: Int): Flow<Result<List<Page>>> =
        pageRepository.getJournalPages(limit, offset)

    // ---- Today's journal creation ----

    /**
     * Idempotently ensures today's journal page exists in the repository.
     * Guarded by a Mutex so concurrent calls from multiple coroutines create exactly one page.
     *
     * If duplicate pages exist for today (e.g. from a prior race between GraphLoader and
     * an eager ensureTodayJournal), they are merged: the page with real content is kept,
     * and empty duplicates are deleted.
     *
     * @return the canonical journal [Page] for today.
     */
    suspend fun ensureTodayJournal(): Page = mutex.withLock {
        val today = Clock.System.now()
            .toLocalDateTime(TimeZone.currentSystemDefault()).date
        val hyphenName = today.toString()
        val underscoreName = hyphenName.replace('-', '_')

        // Gather all candidate pages for today across date and both name formats
        val byDate = pageRepository.getJournalPageByDate(today).first().getOrNull()
        val byHyphen = pageRepository.getPageByName(hyphenName).first().getOrNull()
        val byUnderscore = pageRepository.getPageByName(underscoreName).first().getOrNull()
        val candidates = listOfNotNull(byDate, byHyphen, byUnderscore).distinctBy { it.uuid }

        if (candidates.size > 1) {
            return@withLock mergeDuplicateJournalPages(candidates, today)
        }
        if (candidates.size == 1) return@withLock candidates.first()

        // No page exists — create one
        val pageUuid = UuidGenerator.generateV7()
        val newPage = Page(
            uuid = pageUuid,
            name = hyphenName,
            createdAt = today.atStartOfDayIn(TimeZone.currentSystemDefault()),
            updatedAt = Clock.System.now(),
            isJournal = true,
            journalDate = today
        )
        if (writeActor != null) {
            writeActor.savePage(newPage)
        } else {
            @OptIn(DirectRepositoryWrite::class)
            pageRepository.savePage(newPage)
        }

        val initialBlock = Block(
            uuid = UuidGenerator.generateV7(),
            pageUuid = pageUuid,
            content = "",
            position = 0,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now()
        )
        if (writeActor != null) {
            writeActor.saveBlock(initialBlock)
        } else {
            @OptIn(DirectRepositoryWrite::class)
            blockRepository.saveBlock(initialBlock)
        }

        newPage
    }

    /**
     * Appends a new block with [content] to today's journal page.
     * Creates the journal page if it does not yet exist.
     */
    @OptIn(DirectRepositoryWrite::class)
    suspend fun appendToToday(content: String) {
        val page = ensureTodayJournal()
        val blocks = blockRepository.getBlocksForPage(page.uuid).first().getOrNull() ?: emptyList()
        val nextPosition = (blocks.maxOfOrNull { it.position } ?: -1) + 1
        val newBlock = Block(
            uuid = UuidGenerator.generateV7(),
            pageUuid = page.uuid,
            content = content,
            position = nextPosition,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )
        if (writeActor != null) {
            writeActor.saveBlock(newBlock)
        } else {
            blockRepository.saveBlock(newBlock)
        }
    }

    /**
     * Merges duplicate journal pages for the same date.
     * Keeps the page with real (non-empty) content; deletes the others and
     * re-parents any orphaned non-empty blocks to the winner.
     */
    private suspend fun mergeDuplicateJournalPages(candidates: List<Page>, date: LocalDate): Page {
        logger.info("Found ${candidates.size} duplicate pages for $date — merging")

        // Score each candidate: prefer the one with non-empty blocks
        var keeper: Page = candidates.first()
        var keeperHasContent = false

        for (page in candidates) {
            val blocks = blockRepository.getBlocksForPage(page.uuid).first().getOrNull() ?: emptyList()
            val hasContent = blocks.any { it.content.isNotBlank() }
            if (hasContent && !keeperHasContent) {
                keeper = page
                keeperHasContent = true
            }
        }

        // Delete losers, salvaging any non-empty blocks
        for (page in candidates) {
            if (page.uuid == keeper.uuid) continue

            val blocks = blockRepository.getBlocksForPage(page.uuid).first().getOrNull() ?: emptyList()
            for (block in blocks) {
                if (block.content.isNotBlank()) {
                    // Re-parent to keeper
                    if (writeActor != null) {
                        writeActor.saveBlock(block.copy(pageUuid = keeper.uuid))
                    } else {
                        @OptIn(DirectRepositoryWrite::class)
                        blockRepository.saveBlock(block.copy(pageUuid = keeper.uuid))
                    }
                    logger.info("Re-parented block ${block.uuid} to keeper page ${keeper.uuid}")
                } else {
                    if (writeActor != null) {
                        writeActor.deleteBlock(block.uuid)
                    } else {
                        @OptIn(DirectRepositoryWrite::class)
                        blockRepository.deleteBlock(block.uuid)
                    }
                }
            }
            if (writeActor != null) {
                writeActor.deletePage(page.uuid)
            } else {
                @OptIn(DirectRepositoryWrite::class)
                pageRepository.deletePage(page.uuid)
            }
            logger.info("Deleted duplicate page ${page.uuid} (name=${page.name})")
        }

        return keeper
    }
}
