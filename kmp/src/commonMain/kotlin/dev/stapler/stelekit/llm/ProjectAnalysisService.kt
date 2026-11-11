// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.llm

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.stapler.stelekit.error.DomainError
import dev.stapler.stelekit.util.UuidGenerator
import dev.stapler.stelekit.voice.LlmResult
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException

/**
 * Asks the explicitly chosen provider about a project (Story 5.6b) and files the reply in the
 * [LlmSuggestionInbox] as a read-only [PendingLlmSuggestion.ProjectAnalysisAnswer].
 *
 * The only entry point takes a [GatedPrompt], so text that has not passed [OutboundLlmGate] cannot be
 * sent; there is deliberately no raw-String overload. An unset or disabled provider means no call
 * at all (never Auto), and on-device providers that cannot produce long-form output are refused.
 */
class ProjectAnalysisService(
    private val registry: LlmProviderRegistry,
    private val llmSettings: LlmSettings,
    private val inbox: LlmSuggestionInbox,
) {
    suspend fun analyse(
        graphId: String,
        projectSlug: String,
        prompt: GatedPrompt,
    ): Either<DomainError, PendingLlmSuggestion.ProjectAnalysisAnswer> {
        val providerId = llmSettings.resolveExplicitProvider(LlmFeature.PROJECT_ANALYSIS)
            ?: return failure(NOT_CONFIGURED)
        val provider = registry.find(providerId) ?: return failure(UNAVAILABLE)
        if (!provider.supportsLongFormOutput) return failure(SHORT_FORM_ONLY)
        if (prompt.payload.isBlank() || prompt.question.isBlank()) return failure(EMPTY_PROMPT)

        return try {
            when (val result = provider.formatter.format("${prompt.payload}\n\n${prompt.question}", prompt.systemPrompt)) {
                is LlmResult.Success -> answer(graphId, projectSlug, provider.id, result.formattedText)
                is LlmResult.Failure.ApiError -> DomainError.NetworkError.HttpError(result.code, result.message).left()
                is LlmResult.Failure.NetworkError -> failure("Network error")
                is LlmResult.Failure.OnDeviceUnavailable -> failure(result.reason)
                is LlmResult.Failure.ContentRejected -> failure("Content rejected: ${result.reason}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(e.message ?: "Project analysis request failed")
        }
    }

    private fun answer(
        graphId: String,
        projectSlug: String,
        providerId: String,
        text: String,
    ): Either<DomainError, PendingLlmSuggestion.ProjectAnalysisAnswer> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return failure(BLANK_ANSWER)
        val suggestion = PendingLlmSuggestion.ProjectAnalysisAnswer(
            id = UuidGenerator.generateV7(),
            graphId = graphId,
            sourceProviderId = providerId,
            proposedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            rationale = null,
            projectSlug = projectSlug,
            text = trimmed,
        )
        inbox.propose(suggestion)
        return suggestion.right()
    }

    private fun failure(message: String): Either<DomainError, Nothing> = DomainError.NetworkError.RequestFailed(message).left()

    companion object {
        const val NOT_CONFIGURED = "Project analysis is not configured: choose and acknowledge a provider in Settings"
        const val UNAVAILABLE = "The selected provider for project analysis is unavailable: choose another in Settings"
        const val SHORT_FORM_ONLY = "On-device models cannot answer project analysis: choose a remote provider"
        const val EMPTY_PROMPT = "Nothing to analyse"
        const val BLANK_ANSWER = "The provider returned an empty answer"
    }
}
