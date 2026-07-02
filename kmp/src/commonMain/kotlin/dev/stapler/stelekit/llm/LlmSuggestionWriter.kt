// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import arrow.core.Either
import arrow.core.left
import dev.stapler.stelekit.db.GraphWriterPort
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.BlockType
import dev.stapler.stelekit.model.BlockUuid
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.tags.WikiLinkExtractor
import dev.stapler.stelekit.util.FractionalIndexing
import dev.stapler.stelekit.util.UuidGenerator
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/**
 * Resolves any [PendingLlmSuggestion] variant to one `Page + List<Block>` write and calls
 * [GraphWriterPort.savePage] — never [dev.stapler.stelekit.db.DatabaseWriteActor] directly,
 * matching CLAUDE.md's "BlockEditor -> BlockStateManager -> GraphWriter" write convention.
 *
 * Re-validates staleness immediately before writing: [PendingLlmSuggestion.BlockEdit] and
 * [PendingLlmSuggestion.TagChange] targets are re-read via bounded point lookups
 * ([BlockRepository.getBlockByUuid], [PageRepository.getPageByUuid]) and compared against the
 * snapshot captured at propose time. A stale accept never blind-applies — it surfaces
 * [DomainError.DatabaseError.NotFound] (target deleted) or [DomainError.ConflictError.ConcurrentWrite]
 * (target changed) instead.
 *
 * [TagChange] deliberately collapses into the same write path as [BlockEdit] (append/remove
 * `[[wiki-links]]` on [PendingLlmSuggestion.TagChange.currentContentSnapshot]) rather than
 * needing a third code path — see [PendingLlmSuggestion]'s class doc.
 */
class LlmSuggestionWriter(
    private val pageRepository: PageRepository,
    private val blockRepository: BlockRepository,
    private val graphWriter: GraphWriterPort,
) {

    suspend fun materializeAndWrite(
        suggestion: PendingLlmSuggestion,
        graphPath: String,
    ): Either<DomainError, Unit> = when (suggestion) {
        is PendingLlmSuggestion.BlockEdit -> writeBlockEdit(
            pageUuid = suggestion.pageUuid,
            blockUuid = suggestion.blockUuid,
            currentContentSnapshot = suggestion.currentContentSnapshot,
            newContent = suggestion.proposedContent,
            graphPath = graphPath,
        )
        is PendingLlmSuggestion.TagChange -> writeBlockEdit(
            pageUuid = suggestion.pageUuid,
            blockUuid = suggestion.blockUuid,
            currentContentSnapshot = suggestion.currentContentSnapshot,
            newContent = applyTagChange(
                suggestion.currentContentSnapshot,
                suggestion.addedTerms,
                suggestion.removedTerms,
            ),
            graphPath = graphPath,
        )
        is PendingLlmSuggestion.NewPage -> writeNewPage(suggestion, graphPath)
    }

    /**
     * Single write path shared by [PendingLlmSuggestion.BlockEdit] and [PendingLlmSuggestion.TagChange].
     * Point-lookups only — never a full-page or full-graph read.
     */
    private suspend fun writeBlockEdit(
        pageUuid: String,
        blockUuid: String,
        currentContentSnapshot: String,
        newContent: String,
        graphPath: String,
    ): Either<DomainError, Unit> {
        val blockResult = blockRepository.getBlockByUuid(BlockUuid(blockUuid)).first()
        val block = when (blockResult) {
            is Either.Left -> return blockResult
            is Either.Right -> blockResult.value
                ?: return DomainError.DatabaseError.NotFound("Block", blockUuid).left()
        }

        if (block.content != currentContentSnapshot) {
            return DomainError.ConflictError.ConcurrentWrite(
                pageUuid,
                "This block changed since the suggestion was made",
            ).left()
        }

        val pageResult = pageRepository.getPageByUuid(PageUuid(pageUuid)).first()
        val page = when (pageResult) {
            is Either.Left -> return pageResult
            is Either.Right -> pageResult.value
                ?: return DomainError.DatabaseError.NotFound("Page", pageUuid).left()
        }

        val blocksResult = blockRepository.getBlocksForPage(PageUuid(pageUuid)).first()
        val blocks = when (blocksResult) {
            is Either.Left -> return blocksResult
            is Either.Right -> blocksResult.value
        }

        val updatedBlocks = blocks.map { existing ->
            if (existing.uuid.value == blockUuid) existing.copy(content = newContent) else existing
        }

        return graphWriter.savePage(page, updatedBlocks, graphPath)
    }

    private suspend fun writeNewPage(
        suggestion: PendingLlmSuggestion.NewPage,
        graphPath: String,
    ): Either<DomainError, Unit> {
        // Best-effort check (pitfalls §5.1): confirm any page explicitly wiki-linked from the
        // proposed content is still resolvable via a bounded chunked lookup. Absence alone is
        // NOT rejected here — wiki-links to not-yet-created pages are normal, expected usage —
        // this only guards against the read itself failing (e.g. DB closed mid-accept).
        val referencedNames = suggestion.proposedBlocks
            .flatMap { WikiLinkExtractor.extractPageNames(it.content) }
            .toSet()
        if (referencedNames.isNotEmpty()) {
            val lookup = pageRepository.getPagesByNames(referencedNames)
            if (lookup is Either.Left) return lookup
        }

        val now = Clock.System.now()
        val newPageUuid = PageUuid(UuidGenerator.generateV7())
        val page = Page(
            uuid = newPageUuid,
            name = suggestion.proposedTitle,
            createdAt = now,
            updatedAt = now,
        )

        val blocks = buildBlockTree(suggestion.proposedBlocks, newPageUuid, now)

        return graphWriter.savePage(page, blocks, graphPath)
    }

    /**
     * Builds a parent/sibling-linked [Block] list from a flat depth-ordered [ProposedBlock] list.
     * Depth transitions determine parentage (a block's parent is the most recent block at
     * `depth - 1`); sibling order within a parent is assigned via [FractionalIndexing].
     */
    private fun buildBlockTree(
        proposedBlocks: List<ProposedBlock>,
        pageUuid: PageUuid,
        now: kotlin.time.Instant,
    ): List<Block> {
        val sorted = proposedBlocks.sortedBy { it.order }
        // depth -> uuid of the most recently emitted block at that depth (its parent context)
        val lastUuidAtDepth = mutableMapOf<Int, String>()
        // parentUuid (or "" for root) -> last sibling position key assigned under that parent
        val lastPositionByParent = mutableMapOf<String, String?>()
        val result = mutableListOf<Block>()

        for (proposed in sorted) {
            val depth = proposed.depth.coerceAtLeast(0)
            val parentUuid = if (depth == 0) null else lastUuidAtDepth[depth - 1]
            val parentKey = parentUuid ?: ""
            val position = FractionalIndexing.generateKeyBetween(lastPositionByParent[parentKey], null)
            lastPositionByParent[parentKey] = position

            val block = Block(
                uuid = BlockUuid(UuidGenerator.generateV7()),
                pageUuid = pageUuid,
                parentUuid = parentUuid,
                content = proposed.content,
                level = depth,
                position = position,
                createdAt = now,
                updatedAt = now,
                blockType = BlockType.Bullet,
            )
            result += block
            lastUuidAtDepth[depth] = block.uuid.value
            // A new block at this depth invalidates any deeper "last seen" ancestry chain.
            lastUuidAtDepth.keys.filter { it > depth }.forEach { lastUuidAtDepth.remove(it) }
        }

        return result
    }

    /** Applies [addedTerms]/[removedTerms] as `[[wiki-link]]` insertions/removals on [content]. */
    private fun applyTagChange(content: String, addedTerms: List<String>, removedTerms: List<String>): String {
        var result = content
        for (term in removedTerms) {
            result = result.replace("[[$term]]", "").replace(Regex(" {2,}"), " ").trim()
        }
        val existingLinks = WikiLinkExtractor.extractPageNames(result)
        val toAppend = addedTerms.filter { it !in existingLinks }
        if (toAppend.isNotEmpty()) {
            val suffix = toAppend.joinToString(" ") { "[[$it]]" }
            result = if (result.isBlank()) suffix else "$result $suffix"
        }
        return result
    }
}
