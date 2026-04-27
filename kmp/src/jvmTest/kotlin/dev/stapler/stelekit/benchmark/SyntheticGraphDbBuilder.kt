package dev.stapler.stelekit.benchmark

import dev.stapler.stelekit.db.SteleDatabase
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SqlDelightBlockRepository
import dev.stapler.stelekit.repository.SqlDelightPageRepository
import kotlin.time.Clock

/**
 * Inserts synthetic pages and blocks directly into an already-open [SteleDatabase],
 * bypassing disk I/O and markdown parsing.
 *
 * Used by latency and JMH benchmark tests that need a pre-populated database
 * without the noise of [SyntheticGraphGenerator]'s disk-based approach.
 *
 * UUIDs are deterministic (sequential hex-padded) for reproducibility.
 */
@OptIn(DirectRepositoryWrite::class)
object SyntheticGraphDbBuilder {

    private val WORD_LIST = listOf(
        "programming", "notes", "project", "meeting", "tax", "philosophy",
        "economics", "history", "design", "learning", "kotlin", "database",
        "algorithm", "architecture", "performance", "testing", "refactoring",
        "documentation", "review", "planning", "research", "analysis",
    )

    fun generateUuid(index: Int): String {
        val hex = index.toString().padStart(8, '0')
        return "00000000-0000-0000-0000-$hex"
    }

    /**
     * Populates [db] with [pageCount] pages and [blocksPerPage] blocks each.
     * Page names and block content use word-list terms for realistic FTS5 indexing.
     */
    suspend fun populate(
        db: SteleDatabase,
        pageCount: Int,
        blocksPerPage: Int = 10,
        seed: Long = 42L,
    ) {
        val pageRepo = SqlDelightPageRepository(db)
        val blockRepo = SqlDelightBlockRepository(db)

        val now = Clock.System.now()
        val pages = (0 until pageCount).map { i ->
            val word1 = WORD_LIST[i % WORD_LIST.size]
            val word2 = WORD_LIST[(i + 7) % WORD_LIST.size]
            Page(
                uuid = generateUuid(i),
                name = "$word1 $word2 page $i",
                namespace = null,
                filePath = null,
                createdAt = now,
                updatedAt = now,
                properties = emptyMap(),
                isFavorite = false,
                isJournal = false,
            )
        }
        pageRepo.savePages(pages)

        val blocks = pages.flatMapIndexed { pi, page ->
            (0 until blocksPerPage).map { bi ->
                val blockIdx = pi * blocksPerPage + bi
                val word1 = WORD_LIST[blockIdx % WORD_LIST.size]
                val word2 = WORD_LIST[(blockIdx + 3) % WORD_LIST.size]
                val word3 = WORD_LIST[(blockIdx + 11) % WORD_LIST.size]
                Block(
                    uuid = generateUuid(pageCount + blockIdx),
                    pageUuid = page.uuid,
                    parentUuid = null,
                    leftUuid = null,
                    content = "$word1 $word2 $word3 notes from block $bi on page $pi",
                    level = 0,
                    position = bi,
                    createdAt = now,
                    updatedAt = now,
                    properties = emptyMap(),
                )
            }
        }
        // Insert in batches of 500 to avoid SQLite parameter limits
        blocks.chunked(500).forEach { batch ->
            blockRepo.saveBlocks(batch)
        }
    }
}
