// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.llm

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/** The three texts that would leave the device; the gate checks every one. */
data class GatePromptParts(
    val systemPrompt: String,
    val payload: String,
    val question: String,
    /** Project slugs the payload may name; any other `proj-...` token is a violation. */
    val allowedSlugs: Set<String>,
)

/** Owner-maintained terms (and the optional local denylist file's lines); matched as whole tokens, case-insensitively. */
class PrivateTermList(terms: Collection<String>) {
    private val normalised: List<String> = terms.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

    val isEmpty: Boolean get() = normalised.isEmpty()

    fun matchesIn(text: String): Boolean {
        val haystack = text.lowercase()
        return normalised.any { term -> hasWholeToken(haystack, term) }
    }

    private fun hasWholeToken(haystack: String, term: String): Boolean {
        var from = 0
        while (true) {
            val at = haystack.indexOf(term, from)
            if (at < 0) return false
            val before = haystack.getOrNull(at - 1)
            val after = haystack.getOrNull(at + term.length)
            if (!before.isWordChar() && !after.isWordChar()) return true
            from = at + 1
        }
    }

    private fun Char?.isWordChar() = this != null && isLetterOrDigit()

    companion object {
        val EMPTY = PrivateTermList(emptyList())
    }
}

enum class PromptPart { SYSTEM_PROMPT, PAYLOAD, QUESTION }

/** Carries only where and what kind, never the matched text: it is shown to the user and logged. */
sealed interface GateViolation {
    val part: PromptPart
    val reason: String

    data class Structural(override val part: PromptPart, val category: LeakCategory) : GateViolation {
        override val reason get() = "outbound text blocked: ${category.name.lowercase().replace('_', ' ')} found in the ${part.name.lowercase().replace('_', ' ')}"
    }

    data class MalformedRef(override val part: PromptPart) : GateViolation {
        override val reason get() = "outbound text blocked: a source reference in the ${part.name.lowercase().replace('_', ' ')} is not opaque"
    }

    data class UnknownProject(override val part: PromptPart) : GateViolation {
        override val reason get() = "outbound text blocked: an unknown project identifier in the ${part.name.lowercase().replace('_', ' ')}"
    }

    data class PrivateTerm(override val part: PromptPart) : GateViolation {
        override val reason get() = "outbound text blocked: a private term in the ${part.name.lowercase().replace('_', ' ')}"
    }
}

/**
 * Hard block (Story 5.5b): a violation means no provider call. Replaces any graph page-name
 * denylist, which would block ordinary labels; the controls are structural patterns, the opaque
 * ref and slug shapes, and the explicit [PrivateTermList].
 */
object OutboundLlmGate {
    fun check(parts: GatePromptParts, privateTerms: PrivateTermList): Either<GateViolation, GatedPrompt> {
        val texts = listOf(
            PromptPart.SYSTEM_PROMPT to parts.systemPrompt,
            PromptPart.PAYLOAD to parts.payload,
            PromptPart.QUESTION to parts.question,
        )
        for ((part, text) in texts) violation(part, text, parts.allowedSlugs, privateTerms)?.let { return it.left() }
        @OptIn(GateIssued::class)
        return GatedPrompt(parts.systemPrompt, parts.payload, parts.question).right()
    }

    private fun violation(part: PromptPart, text: String, slugs: Set<String>, terms: PrivateTermList): GateViolation? {
        OutboundPatterns.all.firstOrNull { it.regex.containsMatchIn(text) }
            ?.let { return GateViolation.Structural(part, it.category) }
        if (OutboundPatterns.refCandidate.findAll(text).any { !OutboundPatterns.ref.matches(it.value) }) return GateViolation.MalformedRef(part)
        if (OutboundPatterns.slugCandidate.findAll(text).any { it.value !in slugs || !OutboundPatterns.slug.matches(it.value) }) {
            return GateViolation.UnknownProject(part)
        }
        if (terms.matchesIn(text)) return GateViolation.PrivateTerm(part)
        return null
    }
}
