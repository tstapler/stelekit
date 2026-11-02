// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.db

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.domain.UrlLinkRepair
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.EffectiveNotesPath
import dev.stapler.stelekit.model.PageUuid
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * One-shot repair of blocks an older auto-linker corrupted as `https://[[GitHub]].com/x`
 * (see [UrlLinkRepair]).
 *
 * Candidates come from bounded, paged `searchBlocksByContent` lookups on `[[` directly after a
 * URL-ish separator, never a full-table read. Every rewrite goes through [DatabaseWriteActor] and
 * the page file is then re-rendered by [GraphWriterPort.savePage], so the DB and markdown stay in
 * step and the watcher's self-write suppression applies. Idempotent: a clean graph does no writes.
 */
class UrlLinkRepairService(
    private val blockRepository: BlockRepository,
    private val pageRepository: PageRepository,
    private val writeActor: DatabaseWriteActor,
    private val graphWriter: GraphWriterPort,
    private val batchSize: Int = BATCH_SIZE,
) {
    private val logger = Logger("UrlLinkRepairService")

    data class Report(
        val candidatesScanned: Int,
        val blocksRepaired: Int,
        val pagesRewritten: Int,
        val failures: Int,
    ) {
        val clean: Boolean get() = failures == 0
    }

    suspend fun run(graphPath: EffectiveNotesPath): Either<DomainError, Report> {
        return try {
            val candidates = collectCandidates()
            val fixes = candidates.mapNotNull { block ->
                val repaired = UrlLinkRepair.repair(block.content)
                if (repaired == block.content) null else block to repaired
            }
            var failures = 0
            var repaired = 0
            val touchedPages = LinkedHashSet<PageUuid>()
            val updated = fixes.map { (block, content) -> block.copy(content = content) }
            if (updated.isNotEmpty()) {
                // One actor call for the whole repair; each chunk is its own bounded repository write.
                @OptIn(DirectRepositoryWrite::class)
                val result = writeActor.execute {
                    updated.chunked(batchSize).fold(Unit.right() as Either<DomainError, Unit>) { acc, chunk ->
                        acc.flatMap { blockRepository.saveBlocks(chunk) }
                    }
                }
                if (result.isRight()) {
                    repaired = updated.size
                    updated.forEach { touchedPages += it.pageUuid }
                } else {
                    failures = updated.size
                    logger.warn("URL link repair: could not update ${updated.size} blocks")
                }
            }
            var rewritten = 0
            for (pageUuid in touchedPages) {
                if (rewritePageFile(pageUuid, graphPath)) rewritten++ else failures++
            }
            Report(candidates.size, repaired, rewritten, failures).right()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.DatabaseError.ReadFailed(e.message ?: "unknown").left()
        }
    }

    private suspend fun collectCandidates(): List<Block> {
        val byUuid = LinkedHashMap<String, Block>()
        for (needle in NEEDLES) {
            var offset = 0
            while (true) {
                val page = blockRepository.searchBlocksByContent(needle, batchSize, offset).first().getOrNull().orEmpty()
                page.forEach { byUuid.putIfAbsent(it.uuid.value, it) }
                if (page.size < batchSize) break
                offset += batchSize
            }
        }
        return byUuid.values.toList()
    }

    private suspend fun rewritePageFile(pageUuid: PageUuid, graphPath: EffectiveNotesPath): Boolean {
        val page = pageRepository.getPageByUuid(pageUuid).first().getOrNull() ?: return false
        val blocks = blockRepository.getBlocksForPage(pageUuid).first().getOrNull().orEmpty()
        return graphWriter.savePage(page, blocks, graphPath).fold(
            { e -> logger.warn("URL link repair: could not rewrite '${page.name}': ${e.message}"); false },
            { true },
        )
    }

    companion object {
        const val BATCH_SIZE = 200

        /** Settings key (per graph path) that records a clean run, so the repair happens once. */
        fun settingsKey(graphPath: String) = "url_link_repair_v1:$graphPath"

        // A corrupting `[[` always sat right after a non-word URL character (the matcher requires a
        // word boundary), so these literals find every candidate; UrlLinkRepair makes the real call.
        private val NEEDLES = listOf("/", ".", "-", "=", "?", "&", "#", "~", "+", ":", "@").map { "$it[[" }
    }
}
