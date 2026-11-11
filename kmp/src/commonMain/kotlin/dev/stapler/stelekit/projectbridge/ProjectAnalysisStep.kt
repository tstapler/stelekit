// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import arrow.core.Either
import dev.stapler.stelekit.llm.LlmFeature
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.llm.OutboundLlmGate
import dev.stapler.stelekit.llm.PrivateTermList
import dev.stapler.stelekit.llm.ProjectAnalysisPromptBuilder
import dev.stapler.stelekit.llm.ProjectAnalysisService

sealed interface AnalysisStepOutcome {
    /** Opt-in off, no explicit provider, or no accepted preview: nothing was built or sent. */
    data object Skipped : AnalysisStepOutcome

    /** The outbound gate refused the text; no provider was called. */
    data class Blocked(val reason: String) : AnalysisStepOutcome

    data class Failed(val reason: String) : AnalysisStepOutcome

    data object Answered : AnalysisStepOutcome
}

/**
 * The gated LLM step of the post-sync job (Story 5.6b): payload text, then [OutboundLlmGate], then
 * [ProjectAnalysisService]. Every condition fails closed: it sends nothing without the per-graph
 * opt-in, an explicit acknowledged provider, an accepted outbound preview and a passing gate.
 */
class ProjectAnalysisStep(
    private val settings: ProjectBridgeSettings,
    private val llmSettings: LlmSettings,
    private val service: ProjectAnalysisService,
    private val privateTerms: () -> PrivateTermList = { PrivateTermList.EMPTY },
    /** Replaced by the stored-hash check once the preview dialog records acceptance (Story 5.6c). */
    private val previewAccepted: (graphId: String, previewText: String) -> Boolean = { _, _ -> false },
) {
    suspend fun run(graphId: String, outputs: ProjectOutputs): AnalysisStepOutcome {
        if (!settings.analyseAfterSync(graphId)) return AnalysisStepOutcome.Skipped
        if (llmSettings.resolveExplicitProvider(LlmFeature.PROJECT_ANALYSIS) == null) return AnalysisStepOutcome.Skipped
        val text = OutboundPreview.payloadText(outputs.payload)
        if (!previewAccepted(graphId, text)) return AnalysisStepOutcome.Skipped

        val slug = outputs.payload.project
        val gated = when (val checked = OutboundLlmGate.check(ProjectAnalysisPromptBuilder.parts(slug, text), privateTerms())) {
            is Either.Left -> return AnalysisStepOutcome.Blocked(checked.value.reason)
            is Either.Right -> checked.value
        }
        return when (val answer = service.analyse(graphId, slug, gated)) {
            is Either.Left -> AnalysisStepOutcome.Failed(answer.value.message)
            is Either.Right -> AnalysisStepOutcome.Answered
        }
    }
}
