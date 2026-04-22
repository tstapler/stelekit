package dev.stapler.stelekit.db

import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Preview of a page rename operation — shown to the user before committing.
 */
data class RenamePreview(
    val oldName: String,
    val newName: String,
    val affectedBlockCount: Int,
    val affectedPageUuids: List<String>
)

/**
 * Result of a completed rename operation.
 */
sealed class RenameResult {
    data class Success(val oldName: String, val newName: String, val updatedBlockCount: Int) : RenameResult()
    data class Failure(val error: Throwable) : RenameResult()
}

/**
 * Replaces [[OldName]] and [[OldName|alias]] forms with [[NewName]] and [[NewName|alias]].
 * Exposed as `internal` so unit tests can verify rewrite logic without a full BacklinkRenamer instance.
 */
internal fun replaceWikilink(content: String, oldName: String, newName: String): String =
    content.replace(Regex("\\[\\[${Regex.escape(oldName)}(\\|[^\\]]*)?\\]\\]")) { match ->
        val alias = match.groupValues[1] // e.g. "|Display Text" or ""
        "[[$newName$alias]]"
    }

/**
 * Replaces hashtag references to [oldName] with [newName].
 * Handles both bracket form (#[[oldName]]) and simple form (#oldName).
 * Simple form uses a word-boundary anchor to avoid partial matches
 * (e.g. renaming "meeting" must NOT rewrite "#meetings").
 * Exposed as `internal` so unit tests can verify rewrite logic without a full BacklinkRenamer instance.
 */
internal fun replaceHashtag(content: String, oldName: String, newName: String): String {
    // Replace bracket form: #[[oldName]] → #[[newName]]
    var result = content.replace("#[[${oldName}]]", "#[[${newName}]]")
    // Replace simple form with word-boundary anchor (avoid partial matches)
    val simpleRegex = Regex("#${Regex.escape(oldName)}(?=[\\s,\\.!?;\"\\[\\]]|$)")
    result = result.replace(simpleRegex, "#${newName}")
    return result
}

/**
 * Orchestrates renaming a page with DB updates and disk file rewrites.
 *
 * Flow:
 * 1. Collect all blocks containing [[OldName]] wikilinks
 * 2. Update page name in DB
 * 3. Rewrite each block's content replacing [[OldName]] (and [[OldName|alias]]) with [[NewName]]
 * 4. Move the page file on disk via GraphWriter
 * 5. Rewrite affected pages' disk files with updated block content (up to 4 concurrent writes)
 */
@OptIn(DirectRepositoryWrite::class)
class BacklinkRenamer(
    private val pageRepository: PageRepository,
    private val blockRepository: BlockRepository,
    private val graphWriter: GraphWriter,
    private val writeActor: DatabaseWriteActor
) {
    private val logger = Logger("BacklinkRenamer")
    // Limit concurrent file writes to avoid overwhelming the file system on large graphs.
    private val fileWriteSemaphore = Semaphore(4)

    /**
     * Returns a preview of how many blocks and pages will be affected by the rename,
     * without making any changes.
     * Counts blocks with wikilink references ([[name]]) AND simple hashtag references (#name).
     */
    suspend fun preview(page: Page, newName: String): RenamePreview {
        val affectedBlocks = affectedBlocksForRename(page.name)
        return RenamePreview(
            oldName = page.name,
            newName = newName,
            affectedBlockCount = affectedBlocks.size,
            affectedPageUuids = affectedBlocks.map { it.pageUuid }.distinct()
        )
    }

    /**
     * Collects all blocks that reference [pageName] via wikilinks ([[name]]) or
     * simple hashtags (#name), deduplicating by UUID.
     * Used by both [preview] and [execute] so their counts always agree.
     */
    private suspend fun affectedBlocksForRename(pageName: String): List<dev.stapler.stelekit.model.Block> {
        val wikiBlocks = blockRepository.getLinkedReferences(pageName).first().getOrDefault(emptyList())
        // getLinkedReferences now includes hashtag matches, so no extra query needed.
        return wikiBlocks
    }

    /**
     * Executes the rename: updates the DB, rewrites block content, moves the file,
     * and rewrites all affected pages' disk files.
     */
    suspend fun execute(page: Page, newName: String, graphPath: String): RenameResult {
        return try {
            // 1. Snapshot affected blocks BEFORE any rename so we use the old name in queries.
            val affectedBlocks = affectedBlocksForRename(page.name)
            val affectedPageUuids = affectedBlocks.map { it.pageUuid }.distinct()

            // 2. Rename the page row in the DB.
            writeActor.execute { pageRepository.renamePage(page.uuid, newName) }.getOrThrow()

            // 3. Rewrite block content in DB: [[OldName]] → [[NewName]] (aliases included)
            //    and #OldName / #[[OldName]] → #NewName / #[[NewName]].
            affectedBlocks.forEach { block ->
                val updated = replaceHashtag(replaceWikilink(block.content, page.name, newName), page.name, newName)
                writeActor.saveBlock(block.copy(content = updated))
            }

            // 4. Move the page file on disk (old path → new path).
            val moved = graphWriter.renamePage(page, newName, graphPath)
            if (!moved) error("Failed to move page file for '${page.name}' — disk and DB may be out of sync")

            // 5. Rewrite all affected pages' files using up to 4 concurrent workers.
            coroutineScope {
                affectedPageUuids.map { pageUuid ->
                    async {
                        fileWriteSemaphore.withPermit {
                            rewritePageFile(pageUuid, renamedPageUuid = page.uuid, graphPath)
                        }
                    }
                }.awaitAll()
            }

            RenameResult.Success(page.name, newName, affectedBlocks.size)
        } catch (e: Exception) {
            logger.error("Rename failed: '${page.name}' → '$newName'", e)
            RenameResult.Failure(e)
        }
    }

    /**
     * Fetches the latest page and blocks from the DB, then writes the page file to disk.
     *
     * For the page that was renamed, passes `filePath = null` so [GraphWriter] recalculates
     * the file path from the new name rather than using the now-stale stored path.
     */
    private suspend fun rewritePageFile(pageUuid: String, renamedPageUuid: String, graphPath: String) {
        val page = pageRepository.getPageByUuid(pageUuid).first().getOrNull() ?: run {
            logger.error("Page not found for backlink file rewrite: $pageUuid")
            return
        }
        val blocks = blockRepository.getBlocksForPage(pageUuid).first().getOrDefault(emptyList())
        // For the renamed page, clear filePath so GraphWriter recalculates the new path.
        val pageForSave = if (pageUuid == renamedPageUuid) page.copy(filePath = null) else page
        graphWriter.savePage(pageForSave, blocks, graphPath)
    }
}
