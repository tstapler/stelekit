// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.llm

import arrow.core.Either
import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.voice.LlmResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Story 5.6b-1: the service only calls an explicit, acknowledged, long-form provider with gate-issued text. */
class ProjectAnalysisServiceTest {
    private class MapSettings : Settings {
        private val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, defaultValue: Boolean) = map[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getString(key: String, defaultValue: String) = map[key] as? String ?: defaultValue
        override fun putString(key: String, value: String) { map[key] = value }
        override fun containsKey(key: String) = map.containsKey(key)
    }

    private val feature = LlmFeature.PROJECT_ANALYSIS
    private val settings = LlmSettings(MapSettings())
    private val inbox = LlmSuggestionInbox()

    private fun gated(payload: String = "Project: proj-abc123\n", question: String = "What next?"): GatedPrompt =
        OutboundLlmGate.check(GatePromptParts("system", payload, question, setOf("proj-abc123")), PrivateTermList.EMPTY)
            .fold({ error(it.reason) }, { it })

    private fun service(vararg providers: LlmProvider) = ProjectAnalysisService(LlmProviderRegistry(providers.toList()), settings, inbox)

    private fun choose(id: String) {
        settings.setSelectedProviderId(feature, id)
        settings.acknowledge(feature, id, "")
    }

    private fun run(service: ProjectAnalysisService, prompt: GatedPrompt = gated()) =
        runBlocking { service.analyse("g1", "proj-abc123", prompt) }

    @Test
    fun unsetProviderNeverCalls() {
        val provider = FakeLlmProvider("anthropic", response = LlmResult.Success("answer"))

        val result = run(service(provider))

        assertIs<Either.Left<*>>(result)
        assertTrue(provider.calls.isEmpty(), "Auto must not pick a provider for project analysis")
        assertTrue(inbox.pending.value.isEmpty())
    }

    @Test
    fun disabledSentinelAndUnacknowledgedChoiceNeverCall() {
        val provider = FakeLlmProvider("anthropic", response = LlmResult.Success("answer"))
        settings.setSelectedProviderId(feature, LlmProviderRegistry.DISABLED_SENTINEL)
        assertIs<Either.Left<*>>(run(service(provider)))

        settings.setSelectedProviderId(feature, "anthropic")
        assertIs<Either.Left<*>>(run(service(provider)))

        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun anExplicitAcknowledgedProviderIsCalledAndTheTrimmedAnswerLandsInTheInbox() {
        val provider = FakeLlmProvider("anthropic", response = LlmResult.Success("  Check the shelf zone.\n"))
        choose("anthropic")

        val result = run(service(provider), gated(payload = "Project: proj-abc123\nChecks:\n", question = "What next?"))

        val answer = assertIs<Either.Right<PendingLlmSuggestion.ProjectAnalysisAnswer>>(result).value
        assertEquals("Check the shelf zone.", answer.text)
        assertEquals("proj-abc123", answer.projectSlug)
        assertEquals("anthropic", answer.sourceProviderId)
        assertEquals(listOf<PendingLlmSuggestion>(answer), inbox.pending.value.values.toList())
        val call = provider.calls.single()
        assertEquals("system", call.systemPrompt)
        assertEquals("Project: proj-abc123\nChecks:\n\n\nWhat next?", call.transcript)
    }

    @Test
    fun aShortFormOnDeviceProviderIsRefusedWithoutACall() {
        val provider = FakeLlmProvider("android-ondevice", supportsLongFormOutput = false, response = LlmResult.Success("answer"))
        choose("android-ondevice")

        assertIs<Either.Left<*>>(run(service(provider)))

        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun aBlankPromptOrABlankAnswerNeverBecomesASuggestion() {
        val blankAnswer = FakeLlmProvider("anthropic", response = LlmResult.Success("   \n"))
        choose("anthropic")

        assertIs<Either.Left<*>>(run(service(blankAnswer), gated(payload = "  ")))
        assertTrue(blankAnswer.calls.isEmpty(), "a blank payload must not reach the provider")

        assertIs<Either.Left<*>>(run(service(blankAnswer)))
        assertEquals(1, blankAnswer.calls.size)
        assertTrue(inbox.pending.value.isEmpty())
    }

    @Test
    fun providerFailuresAreSurfacedAndNothingIsProposed() {
        for (failure in listOf(
            LlmResult.Failure.ApiError(500, "down"),
            LlmResult.Failure.NetworkError,
            LlmResult.Failure.OnDeviceUnavailable("model missing", retryable = false),
            LlmResult.Failure.ContentRejected("nope"),
        )) {
            choose("anthropic")
            assertIs<Either.Left<*>>(run(service(FakeLlmProvider("anthropic", response = failure))))
        }
        assertTrue(inbox.pending.value.isEmpty())
    }

    @Test
    fun noPublicEntryPointAcceptsAnythingButAGatedPrompt() {
        val methods = ProjectAnalysisService::class.java.declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        assertTrue(methods.isNotEmpty())
        for (m in methods) {
            assertTrue(GatedPrompt::class.java in m.parameterTypes, "${m.name} must take a GatedPrompt, not raw text")
        }
        val constructors = ProjectAnalysisService::class.java.constructors
        assertTrue(constructors.none { c -> c.parameterTypes.any { it == String::class.java } })
    }
}
