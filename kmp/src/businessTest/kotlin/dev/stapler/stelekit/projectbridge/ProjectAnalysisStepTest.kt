// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.jobs.JobHandle
import dev.stapler.stelekit.jobs.JobKind
import dev.stapler.stelekit.jobs.JobTracker
import dev.stapler.stelekit.llm.FakeLlmProvider
import dev.stapler.stelekit.llm.LlmFeature
import dev.stapler.stelekit.llm.LlmProviderRegistry
import dev.stapler.stelekit.llm.LlmSettings
import dev.stapler.stelekit.llm.LlmSuggestionInbox
import dev.stapler.stelekit.llm.PendingLlmSuggestion
import dev.stapler.stelekit.llm.PrivateTermList
import dev.stapler.stelekit.llm.ProjectAnalysisService
import dev.stapler.stelekit.voice.LlmResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Story 5.6b: the LLM step sends nothing unless every condition holds, and a gate block is a hard stop. */
class ProjectAnalysisStepTest {
    private val graph = BRIDGE_GRAPH
    private val store = BridgeMapSettings()
    private val bridge = ProjectBridgeSettings(store)
    private val llmSettings = LlmSettings(store)
    private val inbox = LlmSuggestionInbox()
    private val provider = FakeLlmProvider("anthropic", response = LlmResult.Success("Everything adds up."))
    private val service = ProjectAnalysisService(LlmProviderRegistry(listOf(provider)), llmSettings, inbox)
    private val outputs = ClosetProjectOutputs.outputs()

    private fun step(terms: PrivateTermList = PrivateTermList.EMPTY, accepted: Boolean = true) =
        ProjectAnalysisStep(bridge, llmSettings, service, { terms }, { _, _ -> accepted })

    private fun enableAll() {
        bridge.setAnalyseAfterSync(graph, true)
        llmSettings.setSelectedProviderId(LlmFeature.PROJECT_ANALYSIS, "anthropic")
        llmSettings.acknowledge(LlmFeature.PROJECT_ANALYSIS, "anthropic", "")
    }

    @Test
    fun everyMissingConditionSkipsWithoutACall() = runTest {
        assertEquals(AnalysisStepOutcome.Skipped, step().run(graph, outputs), "opt-in off")

        bridge.setAnalyseAfterSync(graph, true)
        assertEquals(AnalysisStepOutcome.Skipped, step().run(graph, outputs), "provider unset")

        enableAll()
        assertEquals(AnalysisStepOutcome.Skipped, step(accepted = false).run(graph, outputs), "preview not accepted")
        assertEquals(
            AnalysisStepOutcome.Skipped,
            ProjectAnalysisStep(bridge, llmSettings, service).run(graph, outputs),
            "default preview provider is false until the dialog records acceptance",
        )
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun aGateViolationIsABlockAndNeverReachesTheProvider() = runTest {
        enableAll()

        val outcome = step(PrivateTermList(listOf("Opening"))).run(graph, outputs)

        assertIs<AnalysisStepOutcome.Blocked>(outcome)
        assertTrue(provider.calls.isEmpty())
        assertTrue(inbox.pending.value.isEmpty())
    }

    @Test
    fun whenEverythingHoldsTheAnswerIsFiledAndThePromptWasTheGatedPreviewText() = runTest {
        enableAll()

        assertEquals(AnalysisStepOutcome.Answered, step().run(graph, outputs))

        val answer = assertIs<PendingLlmSuggestion.ProjectAnalysisAnswer>(inbox.pending.value.values.single())
        assertEquals("Everything adds up.", answer.text)
        assertTrue(provider.calls.single().transcript.startsWith(OutboundPreview.payloadText(outputs.payload)))
    }

    @Test
    fun theStoredPreviewHashDrivesAcceptance() = runTest {
        enableAll()
        val real = ProjectAnalysisStep(bridge, llmSettings, service, previewAccepted = bridge::isOutboundPreviewAccepted)
        assertEquals(AnalysisStepOutcome.Skipped, real.run(graph, outputs))

        bridge.acceptOutboundPreview(graph, OutboundPreview.hash(OutboundPreview.payloadText(outputs.payload)))

        assertEquals(AnalysisStepOutcome.Answered, real.run(graph, outputs))
    }

    private class RecordingHandle : JobHandle {
        val events = mutableListOf<String>()
        override fun progress(percent: Int?) = Unit
        override fun succeed() { events += "succeed" }
        override fun fail(reason: String?) { events += "fail:$reason" }
        override fun cancelled() { events += "cancelled" }
    }

    @Test
    fun theJobReportsAGateBlockAsAFailureAndASkipAsSuccess() = runTest {
        enableAll()
        enableBridge(store)
        for ((terms, expected) in listOf(
            PrivateTermList(listOf("Opening")) to "fail:outbound text blocked: a private term in the payload",
            PrivateTermList.EMPTY to "succeed",
        )) {
            val handle = RecordingHandle()
            val tracker = object : JobTracker {
                override fun begin(kind: JobKind, title: String, onCancel: (() -> Unit)?): JobHandle = handle
            }
            val deps = bridgeDeps(store, tracker, router = { newBridgeTestRouter() })
            val job = PostSyncProjectJob(deps, analysisStep = step(terms), outputsProvider = { outputs })

            val outcome = job.run(graph) { false }

            assertEquals(listOf(expected), handle.events)
            assertEquals(expected == "succeed", outcome is PostSyncRunOutcome.Completed)
        }
    }
}
