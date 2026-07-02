// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.llm

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.model.Block
import dev.stapler.stelekit.model.Page
import dev.stapler.stelekit.util.UuidGenerator
import dev.stapler.stelekit.voice.LlmResult
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException

/**
 * First real producer feeding [LlmSuggestionInbox] (Story 7.6). Builds bounded synthesis
 * context via [LlmSynthesisContextBuilder], calls the selected [LlmProvider], and parses the
 * response into [PendingLlmSuggestion.TagChange] proposals — the smallest, best-precedented
 * output shape ([dev.stapler.stelekit.tags.LlmTagProvider] already ships a per-block tag-tier).
 * `NewPage` generation is out of scope for this story.
 *
 * Excludes providers with `supportsLongFormOutput == false` by construction (on-device tiers
 * cannot reliably produce the structured multi-line output this contract requires).
 *
 * **Output contract** — free-text, line-based, tolerant to parse (does not depend on JSON-mode
 * being available from every provider, per pitfalls §3.2). One proposal per line:
 * ```
 * <blockIndex>|ADD:term1;term2|REMOVE:term3
 * ```
 * `blockIndex` is the 0-based index into the current page's block list (as enumerated in the
 * prompt). Either segment may be empty (e.g. `ADD:` with nothing after it). Malformed or
 * unparseable lines are skipped — a best-effort subset, never a crash.
 */
class LlmSynthesisService(
    private val registry: LlmProviderRegistry,
    private val contextBuilder: LlmSynthesisContextBuilder,
    private val inbox: LlmSuggestionInbox,
) {
    companion object {
        /** Caps proposals surfaced per synthesis run — reduces queue volume at the source
         * rather than relying on the review UI's bulk actions to absorb an unbounded queue
         * (pitfalls §5.2). */
        const val MAX_PROPOSALS_PER_RUN = 10
    }

    /** Runs synthesis for [currentPage] and proposes tag-change suggestions into the inbox.
     * Returns the number of proposals surfaced. */
    suspend fun synthesizeForPage(
        graphId: String,
        currentPage: Page,
        currentPageBlocks: List<Block>,
    ): Either<DomainError, Int> {
        val eligibleProviders = registry.availableForFeature(LlmFeature.GRAPH_EDIT_SYNTHESIS, excludeShortFormOnly = true)
        if (eligibleProviders.isEmpty()) {
            val anyAvailable = registry.availableProviders()
            val message = if (anyAvailable.isNotEmpty()) {
                "On-device models don't support synthesis — configure a remote provider for this feature"
            } else {
                "No LLM provider is configured — add one in Settings to use synthesis"
            }
            return DomainError.NetworkError.RequestFailed(message).left()
        }
        val provider = eligibleProviders.first()

        val contextResult = contextBuilder.build(currentPage, currentPageBlocks)
        val context = when (contextResult) {
            is Either.Left -> return contextResult
            is Either.Right -> contextResult.value
        }

        val systemPrompt = buildSystemPrompt(context, currentPageBlocks)
        val transcript = currentPageBlocks.joinToString("\n") { it.content }

        return try {
            when (val result = provider.formatter.format(transcript, systemPrompt)) {
                is LlmResult.Success -> {
                    val proposals = parseResponse(
                        responseText = result.formattedText,
                        currentPage = currentPage,
                        currentPageBlocks = currentPageBlocks,
                        graphId = graphId,
                        providerId = provider.id,
                    ).take(MAX_PROPOSALS_PER_RUN)
                    proposals.forEach { inbox.propose(it) }
                    proposals.size.right()
                }
                is LlmResult.Failure.ApiError -> DomainError.NetworkError.HttpError(result.code, result.message).left()
                is LlmResult.Failure.NetworkError -> DomainError.NetworkError.RequestFailed("Network error").left()
                is LlmResult.Failure.OnDeviceUnavailable -> DomainError.NetworkError.RequestFailed(result.reason).left()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DomainError.NetworkError.RequestFailed(e.message ?: "LLM synthesis request failed").left()
        }
    }

    private fun buildSystemPrompt(context: LlmSynthesisContextBuilder.SynthesisContext, currentPageBlocks: List<Block>): String {
        val relatedPages = context.candidatePages.joinToString("\n") { candidate ->
            "- ${candidate.name} (${candidate.source.name.lowercase()}): ${candidate.contentPreview}"
        }
        val blockList = currentPageBlocks.withIndex().joinToString("\n") { (index, block) -> "$index: ${block.content}" }
        return """
You are a knowledge-graph tagging assistant. Below is the current page's blocks (numbered)
and a bounded set of related pages (backlinks and outbound links). Suggest tag corrections
(page-name wiki-links to add or remove) for individual blocks on the current page, using the
related-page context to inform your suggestions.

Output ONLY lines in this exact format, one proposal per line, no other text:
<blockIndex>|ADD:term1;term2|REMOVE:term3

Either ADD or REMOVE may be empty (e.g. "ADD:"). Only reference block indices from the list
below. Output nothing if no corrections are warranted.

<blocks>
$blockList
</blocks>

<related_pages>
$relatedPages
</related_pages>
""".trimIndent()
    }

    /** Package-visible for direct unit testing of the parsing contract. */
    internal fun parseResponse(
        responseText: String,
        currentPage: Page,
        currentPageBlocks: List<Block>,
        graphId: String,
        providerId: String,
    ): List<PendingLlmSuggestion.TagChange> {
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val proposals = mutableListOf<PendingLlmSuggestion.TagChange>()

        for (rawLine in responseText.lines()) {
            val line = rawLine.trim()
            if (line.isBlank()) continue
            val parts = line.split("|")
            if (parts.size < 3) continue // malformed — skip, best-effort subset

            val blockIndex = parts[0].trim().toIntOrNull() ?: continue
            val block = currentPageBlocks.getOrNull(blockIndex) ?: continue

            val addSegment = parts[1].trim().removePrefix("ADD:").trim()
            val removeSegment = parts[2].trim().removePrefix("REMOVE:").trim()
            val added = addSegment.split(";").map { it.trim() }.filter { it.isNotBlank() }
            val removed = removeSegment.split(";").map { it.trim() }.filter { it.isNotBlank() }
            if (added.isEmpty() && removed.isEmpty()) continue

            proposals += PendingLlmSuggestion.TagChange(
                id = UuidGenerator.generateV7(),
                graphId = graphId,
                sourceProviderId = providerId,
                proposedAtEpochMs = nowMs,
                rationale = null,
                pageUuid = currentPage.uuid.value,
                blockUuid = block.uuid.value,
                currentContentSnapshot = block.content,
                addedTerms = added,
                removedTerms = removed,
            )
        }
        return proposals
    }
}
