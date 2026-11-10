// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.llm

/**
 * Assembles the three outbound texts for the project analysis step. The payload text arrives
 * already rendered (the projectbridge side owns that format, so this package stays free of a
 * dependency on it); the result is raw [GatePromptParts] that only [OutboundLlmGate] can turn into
 * a sendable [GatedPrompt].
 */
object ProjectAnalysisPromptBuilder {
    const val TOKEN_BUDGET = 2000
    private const val CHARS_PER_TOKEN = 4

    const val SYSTEM_PROMPT =
        "You review a home project's measurements. The checks below were computed by a solver: " +
            "trust their PASS or FAIL verdicts and do not recompute them. Use only the supplied data, " +
            "name each conflict or unknown dimension you rely on, and keep the answer short."

    const val DEFAULT_QUESTION = "Which measurements conflict or are still unknown, and what should I check next?"

    fun parts(slug: String, payloadText: String, question: String = DEFAULT_QUESTION): GatePromptParts =
        GatePromptParts(SYSTEM_PROMPT, payloadText, question, allowedSlugs = setOf(slug))

    fun estimatedTokens(parts: GatePromptParts): Int =
        (parts.systemPrompt.length + parts.payload.length + parts.question.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
}
