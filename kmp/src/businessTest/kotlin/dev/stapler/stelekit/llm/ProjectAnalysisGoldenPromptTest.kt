// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.llm

import dev.stapler.stelekit.fixtures.ClosetFusion
import dev.stapler.stelekit.projectbridge.ClosetProjectOutputs
import dev.stapler.stelekit.projectbridge.DigestRenderer
import dev.stapler.stelekit.projectbridge.OutboundPreview
import dev.stapler.stelekit.voice.LlmResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Success metric 3, with a STUBBED model: the artifact that would be sent holds the answer (the
 * verdict line and every zone value), fits the budget and passes the gate. It does not show that a
 * real model reads it correctly; no live LLM runs in CI.
 */
class ProjectAnalysisGoldenPromptTest {
    private val outputs = ClosetProjectOutputs.outputs()
    private val digest = DigestRenderer.render(ClosetProjectOutputs.input())
    private val parts = ProjectAnalysisPromptBuilder.parts(ClosetProjectOutputs.SLUG, OutboundPreview.payloadText(outputs.payload))
    private val sent = parts.systemPrompt + parts.payload + parts.question
    private val zoneValues = listOf("33 7/16\"", "22 1/2\"", "24 3/16\"", "22 1/2\"", "33 3/8\"")

    @Test
    fun theClosetPromptCarriesThePassLineAndAllFiveZoneValues() {
        val passLine = parts.payload.lines().single { it.startsWith("- Zones sum to opening:") }
        assertTrue(passLine.startsWith("- Zones sum to opening: PASS "), passLine)
        zoneValues.forEach { assertTrue(it in passLine, "$it missing from $passLine") }
        assertTrue("= 136\"" in passLine)
    }

    @Test
    fun thePromptIsUnderTheTokenBudgetAndPassesTheGateWithNoHits() {
        assertTrue(ProjectAnalysisPromptBuilder.estimatedTokens(parts) < ProjectAnalysisPromptBuilder.TOKEN_BUDGET, sent.length.toString())
        val gated = OutboundLlmGate.check(parts, PrivateTermList.EMPTY)
        assertTrue(gated.isRight(), gated.leftOrNull()?.reason ?: "")
    }

    @Test
    fun aSeededConflictShowsUpAsFailAndAsANeedsAttentionLine() {
        val mutated = ClosetProjectOutputs.outputs(ClosetFusion.observations(tablesText = ClosetFusion.tables.replace("**33 7/16\"**", "**33 3/8\"**")))
        val text = OutboundPreview.payloadText(mutated.payload)
        assertTrue("- Zones sum to opening: FAIL " in text, text)
        assertTrue("Needs attention:" in text && "(conflict)" in text, text)
    }

    @Test
    fun theStubAnswerIsDerivableFromTheDigestText() {
        val stubAnswer = "The zones sum check is PASS: the five zones add up to 136\""
        val provider = FakeLlmProvider("stub", response = LlmResult.Success(stubAnswer))
        val reply = kotlinx.coroutines.runBlocking { provider.formatter.format(parts.payload + parts.question, parts.systemPrompt) }
        val text = (reply as LlmResult.Success).formattedText

        listOf("PASS", "136\"").forEach {
            assertTrue(it in text && it in digest && it in parts.payload, "$it is not derivable from the digest and prompt")
        }
        assertEquals(1, provider.calls.size)
    }
}
