// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.domain

import dev.stapler.stelekit.llm.LlmFeature
import dev.stapler.stelekit.llm.LlmProviderRegistry
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.logging.Logger
import dev.stapler.stelekit.repository.PageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns one graph's [PageNameIndex] and a resolved [TopicEnricher], exposing budget/timeout-
 * bounded wrappers around [ImportService.scan] and [TopicEnricher.enhance] for the Capture
 * feature. `commonMain` (not `androidApp`) because [dev.stapler.stelekit.db.GraphManager] must
 * be able to construct and return it — `androidApp` depends on `kmp`, never the reverse.
 */
class CaptureEnrichmentCoordinator(
    pageRepository: PageRepository,
    scope: CoroutineScope,
    val topicEnricher: TopicEnricher,
    // Defaults to the real ImportService.scan — overridable only for tests, so scan()'s
    // Throwable guard is exercisable deterministically. There is no other fault-injection point
    // in the real call graph: AhoCorasickMatcher/TrieEntry validate on construction (can't be
    // built malformed), and PageNameIndex already guards its own matcher-build path separately.
    private val scanFn: (text: String, matcher: AhoCorasickMatcher, existingNames: Set<String>) -> ScanResult =
        ImportService::scan,
) {
    val pageNameIndex: PageNameIndex = PageNameIndex(pageRepository, scope)

    /**
     * `true` when a real (non-[NoOpTopicEnricher]) enricher is available — the external-facing
     * signal callers should use to decide whether calling [enhance] is worthwhile, without
     * leaking the [NoOpTopicEnricher] sentinel type itself across the domain/ViewModel boundary.
     */
    val supportsEnrichment: Boolean get() = topicEnricher !is NoOpTopicEnricher

    private val logger = Logger("CaptureEnrichmentCoordinator")

    /**
     * Scans [text] for known page-name matches, bounded to [budgetMs]. Returns
     * [ScanOutcome.MatcherNotReady] immediately if [PageNameIndex.matcher] isn't built yet,
     * [ScanOutcome.TimedOut] past [budgetMs], or [ScanOutcome.Success] otherwise. Any [Throwable]
     * (mirrors [enhance]'s guard) degrades to [ScanOutcome.MatcherNotReady] instead of
     * propagating, so one scan failure never kills the caller's collector.
     */
    suspend fun scan(text: String, budgetMs: Long = 500): ScanOutcome {
        val matcher = pageNameIndex.matcher.value ?: return ScanOutcome.MatcherNotReady
        return try {
            val outcome = withTimeoutOrNull(budgetMs) {
                withContext(Dispatchers.Default) {
                    val raw = scanFn(text, matcher, pageNameIndex.vocabularyNames().toSet())
                    val (autoApply, confirmFirst) = partitionForAutoApply(raw.matchedPageNames)
                    val adjusted = if (confirmFirst.isEmpty()) {
                        raw
                    } else {
                        raw.copy(
                            linkedText = ImportService.insertWikiLinks(text, autoApply),
                            matchedPageNames = autoApply,
                        )
                    }
                    ScanOutcome.Success(adjusted, confirmFirst)
                }
            }
            outcome ?: ScanOutcome.TimedOut
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn("Scan failed — degrading to MatcherNotReady: ${e::class.simpleName}: ${e.message}")
            ScanOutcome.MatcherNotReady
        }
    }

    // Pre-mortem.md P1 #2: a matched page name auto-applies only if multi-word or a single word
    // of reasonable length; short single-word matches ("Today") are confirm-first chips instead
    // of silently written. Policy lives here (not PageNameIndex/AhoCorasickMatcher) since it
    // governs what to do with an already-found match, not what counts as matchable.
    private fun partitionForAutoApply(names: List<String>): Pair<List<String>, List<String>> =
        names.partition { it.contains(' ') || it.length >= MIN_AUTO_APPLY_SINGLE_WORD_LENGTH }

    /**
     * Enhances [local] suggestions via the resolved [topicEnricher], bounded to an 8s timeout.
     * Any [Throwable] (including malformed-output-triggering exceptions) degrades to [local]
     * unchanged. Output that returns normally is [sanitize]d before it reaches the caller.
     */
    suspend fun enhance(text: String, local: List<TopicSuggestion>): List<TopicSuggestion> =
        if (topicEnricher is NoOpTopicEnricher) {
            local
        } else {
            try {
                sanitize(withTimeout(ENHANCE_TIMEOUT_MS) { topicEnricher.enhance(text, local) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn("Enrichment failed/timed out — using local suggestions only: ${e::class.simpleName}")
                local
            }
        }

    // A TopicEnricher that returns normally with malformed output (blank term, out-of-range
    // confidence, duplicate terms) must not pass garbage through to the chip tray or a
    // stub-page write — this is the same degrade-to-safe bar as the try/catch above, just for
    // the "no exception, bad data" failure mode.
    private fun sanitize(enriched: List<TopicSuggestion>): List<TopicSuggestion> {
        val seen = mutableSetOf<String>()
        return enriched.mapNotNull { s ->
            val term = s.term.trim()
            if (term.isEmpty() || !seen.add(term.lowercase())) return@mapNotNull null
            s.copy(term = term, confidence = s.confidence.coerceIn(0f, 1f))
        }
    }

    companion object {
        const val MIN_AUTO_APPLY_SINGLE_WORD_LENGTH = 6
        private const val ENHANCE_TIMEOUT_MS = 8_000L

        private val logger = Logger("CaptureEnrichmentCoordinator")

        /**
         * Resolves a [TopicEnricher] honoring [llmSettings]'s per-feature CAPTURE_ENRICHMENT
         * selection, mirroring `VoicePipelineFactory`'s VOICE_FORMATTING precedence:
         * [LlmProviderRegistry.DISABLED_SENTINEL] -> no enrichment; a specific id -> resolved via
         * [LlmProviderRegistry.find] only (no Auto fallback); unset -> first available provider.
         * Security fix: this previously ignored a user's explicit "Disabled" setting entirely.
         */
        suspend fun resolveTopicEnricher(
            registry: LlmProviderRegistry,
            llmSettings: LlmSettings? = null,
        ): TopicEnricher {
            val provider = when (val selectedId = llmSettings?.getSelectedProviderId(LlmFeature.CAPTURE_ENRICHMENT)) {
                LlmProviderRegistry.DISABLED_SENTINEL -> null
                null -> registry.availableForFeature(LlmFeature.CAPTURE_ENRICHMENT).firstOrNull()
                else -> registry.find(selectedId)
            }
            logger.debug(
                if (provider != null) {
                    "TopicEnricher resolved: provider '${provider.id}' available"
                } else {
                    "TopicEnricher resolved: no provider available for CAPTURE_ENRICHMENT " +
                        "(empty registry, disabled, unresolvable selected id, or configured " +
                        "provider not currently AVAILABLE)"
                },
            )
            return provider?.let { ClaudeTopicEnricher(it.formatter) } ?: NoOpTopicEnricher()
        }
    }
}

/**
 * Outcome of [CaptureEnrichmentCoordinator.scan]. A sealed type (not a nullable [ScanResult])
 * keeps "matcher not built yet" and "scan exceeded budget" distinguishable at the call site, so
 * a systematically-too-slow scan is logged distinctly from ordinary cold start.
 */
sealed interface ScanOutcome {
    data object MatcherNotReady : ScanOutcome
    data object TimedOut : ScanOutcome
    data class Success(
        val result: ScanResult,
        // Precision floor (pre-mortem.md P1 #2): single-word matches <6 chars, withheld
        // from `result.linkedText`/`result.matchedPageNames` and offered for review instead
        // of silently auto-linked.
        val confirmFirstNames: List<String> = emptyList(),
    ) : ScanOutcome
}
