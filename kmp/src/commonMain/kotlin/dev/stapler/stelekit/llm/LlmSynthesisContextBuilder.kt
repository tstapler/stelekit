// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import arrow.core.Either
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.repository.BlockRepository
import dev.stapler.stelekit.repository.PageRepository
import dev.stapler.stelekit.tags.WikiLinkExtractor
import kotlinx.coroutines.flow.first

/**
 * Builds bounded LLM synthesis context for a single page using **only** bounded/projected
 * reads — never a full-block-content scan across every page (CLAUDE.md's O(graph)-read
 * prohibition; this is the highest-risk spot in Epic 7 for that class of bug).
 *
 * **Candidate-selection heuristic (explicit — do not substitute a scan-based relevance
 * heuristic)**: the candidate page set fed to the LLM is exactly the union of:
 *  1. Pages that **link to** the current page — via [BlockRepository.getLinkedReferences]
 *     (bounded/paginated, capped at [BACKLINK_FETCH_LIMIT]).
 *  2. Pages the current page **itself links to** — `[[wiki-link]]` names extracted from the
 *     current page's own already-loaded block content via [WikiLinkExtractor], resolved
 *     through [PageRepository.getPagesByNames] (chunked `IN`-lookup, already bounded).
 *
 * If the union exceeds [MAX_CANDIDATE_PAGES], backlinks are kept in full (up to the cap) and
 * outbound links are truncated first — backlinks better indicate what other content already
 * treats this page as relevant.
 *
 * Per-page and total prompt character caps mirror [dev.stapler.stelekit.tags.LlmTagProvider]'s
 * `MAX_BLOCK_CHARS`/`MAX_VOCABULARY_SIZE` pattern.
 */
class LlmSynthesisContextBuilder(
    private val pageRepository: PageRepository,
    private val blockRepository: BlockRepository,
) {
    companion object {
        const val MAX_CANDIDATE_PAGES = 20
        const val MAX_CONTENT_CHARS_PER_PAGE = 500
        const val MAX_TOTAL_PROMPT_CHARS = 10_000
        private const val BACKLINK_FETCH_LIMIT = 20
    }

    enum class CandidateSource { BACKLINK, OUTBOUND_LINK }

    data class CandidatePage(
        val name: String,
        val contentPreview: String,
        val source: CandidateSource,
    )

    data class SynthesisContext(
        val currentPageName: String,
        val candidatePages: List<CandidatePage>,
        val promptText: String,
    )

    suspend fun build(currentPage: Page, currentPageBlocks: List<Block>): Either<DomainError, SynthesisContext> {
        // (a) Backlinks — bounded/paginated read, capped.
        val backlinkBlocksResult = blockRepository
            .getLinkedReferences(currentPage.name, BACKLINK_FETCH_LIMIT, 0)
            .first()
        val backlinkBlocks = when (backlinkBlocksResult) {
            is Either.Left -> return backlinkBlocksResult
            is Either.Right -> backlinkBlocksResult.value
        }

        // Resolve each distinct backlink source page's name via a bounded point lookup
        // (≤ MAX_CANDIDATE_PAGES lookups — same pattern as StelekitViewModel.refreshRecentPages()
        // resolving ≤10 recent-page UUIDs via point lookups, never a table scan).
        val backlinkPageUuids = backlinkBlocks.map { it.pageUuid }.distinct().take(MAX_CANDIDATE_PAGES)
        val backlinkNames = LinkedHashSet<String>()
        for (uuid in backlinkPageUuids) {
            val pageResult = pageRepository.getPageByUuid(uuid).first()
            val page = when (pageResult) {
                is Either.Left -> return pageResult
                is Either.Right -> pageResult.value
            } ?: continue
            if (page.name != currentPage.name) backlinkNames += page.name
        }

        // (b) Outbound links — extracted from already-loaded content, no read required.
        val outboundNames = currentPageBlocks
            .flatMap { WikiLinkExtractor.extractPageNames(it.content) }
            .filter { it != currentPage.name }
            .distinct()

        // (c) Union, capped at MAX_CANDIDATE_PAGES, backlinks prioritized on overflow.
        val selectedBacklinks = backlinkNames.toList().take(MAX_CANDIDATE_PAGES)
        val remainingCap = (MAX_CANDIDATE_PAGES - selectedBacklinks.size).coerceAtLeast(0)
        val selectedOutbound = outboundNames
            .filter { it !in selectedBacklinks }
            .take(remainingCap)

        val candidateOrder: List<Pair<String, CandidateSource>> =
            selectedBacklinks.map { it to CandidateSource.BACKLINK } +
                selectedOutbound.map { it to CandidateSource.OUTBOUND_LINK }

        if (candidateOrder.isEmpty()) {
            return SynthesisContext(
                currentPageName = currentPage.name,
                candidatePages = emptyList(),
                promptText = buildPrompt(currentPage, emptyList()),
            ).right()
        }

        // (d) Bounded chunked existence/metadata lookup for the capped candidate name set.
        val candidateNames = candidateOrder.map { it.first }
        val pagesResult = pageRepository.getPagesByNames(candidateNames)
        val pagesByName = when (pagesResult) {
            is Either.Left -> return pagesResult
            is Either.Right -> pagesResult.value.associateBy { it.name }
        }

        // (e) Per-candidate-page content: bounded to a single page's blocks each (never
        // graph-scale), capped at MAX_CANDIDATE_PAGES total pages.
        val candidatePages = mutableListOf<CandidatePage>()
        for ((name, source) in candidateOrder) {
            val page = pagesByName[name] ?: continue
            val blocksResult = blockRepository.getBlocksForPage(page.uuid).first()
            val blocks = when (blocksResult) {
                is Either.Left -> return blocksResult
                is Either.Right -> blocksResult.value
            }
            val content = blocks.joinToString(" ") { it.content }.take(MAX_CONTENT_CHARS_PER_PAGE)
            candidatePages += CandidatePage(name = name, contentPreview = content, source = source)
        }

        return SynthesisContext(
            currentPageName = currentPage.name,
            candidatePages = candidatePages,
            promptText = buildPrompt(currentPage, candidatePages),
        ).right()
    }

    private fun buildPrompt(currentPage: Page, candidatePages: List<CandidatePage>): String {
        val sb = StringBuilder()
        sb.append("Current page: ${currentPage.name}\n\n")
        sb.append("Related pages:\n")
        for (candidate in candidatePages) {
            sb.append("- ${candidate.name} (${candidate.source.name.lowercase()}): ${candidate.contentPreview}\n")
        }
        return sb.toString().take(MAX_TOTAL_PROMPT_CHARS)
    }
}
