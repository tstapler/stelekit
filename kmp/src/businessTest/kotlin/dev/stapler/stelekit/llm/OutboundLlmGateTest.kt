// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.llm

import arrow.core.Either
import dev.stapler.stelekit.fixtures.ClosetFixtureText
import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 5.5b-2: structural, ref/slug and explicit-term checks; every violation is a hard block. */
class OutboundLlmGateTest {
    private val slugs = setOf("proj-closet-1")
    private val system = "You are a measurement assistant. Answer from the data only."
    private val question = "Do the zone widths sum to the opening?"
    private val terms = PrivateTermList(listOf("Zebediah", "Maple Lane Residence"))

    private fun check(
        payload: String = "{}",
        system: String = this.system,
        question: String = this.question,
        terms: PrivateTermList = this.terms,
    ) = OutboundLlmGate.check(GatePromptParts(system, payload, question, slugs), terms)

    private fun blocked(either: Either<GateViolation, GatedPrompt>): GateViolation =
        assertIs<Either.Left<GateViolation>>(either).value

    @Test
    fun `the closet golden payload passes with a term list that does not contain its labels`() {
        val ok = check(payload = ClosetFixtureText.load("expected-llm-payload.json"))
        assertIs<Either.Right<GatedPrompt>>(ok)
    }

    @Test
    fun `ordinary words pass whatever the graph holds`() {
        listOf("Rod", "Shelf", "Width", "Height", "Left", "Right").forEach {
            assertIs<Either.Right<GatedPrompt>>(check(payload = """{"label":"$it"}"""), it)
        }
    }

    @Test
    fun `a label containing a street address fails`() {
        listOf("412 Oak Ave", "9 north 4th", "1200 elm street").forEach {
            val v = blocked(check(payload = """{"label":"Closet at $it"}"""))
            assertEquals(LeakCategory.STREET_ADDRESS, assertIs<GateViolation.Structural>(v).category, it)
        }
    }

    @Test
    fun `a label equal to a listed private term fails, case-insensitively and as a whole token`() {
        assertIs<GateViolation.PrivateTerm>(blocked(check(payload = """{"label":"zebediah closet"}""")))
        assertIs<GateViolation.PrivateTerm>(blocked(check(payload = """{"label":"the maple lane residence plan"}""")))
        assertIs<Either.Right<GatedPrompt>>(check(payload = """{"label":"Zebediahs"}"""))
    }

    @Test
    fun `a path, a uri, an email, a phone number and a filename each fail`() {
        val cases = mapOf(
            "/home/someone/wiki/pages" to LeakCategory.PATH,
            "see ~/notes/closet/plan" to LeakCategory.PATH,
            "C:\\Users\\someone\\plan" to LeakCategory.PATH,
            "https://example.com/x" to LeakCategory.URI,
            "a.person@example.org" to LeakCategory.EMAIL,
            "call 555-123-4567" to LeakCategory.PHONE,
            "from Closet Plan.md" to LeakCategory.FILENAME,
            "from closet.svg" to LeakCategory.FILENAME,
            "[[Some Page Title]]" to LeakCategory.WIKI_LINK,
        )
        cases.forEach { (text, category) ->
            val v = assertIs<GateViolation.Structural>(blocked(check(payload = text)), text)
            assertEquals(category, v.category, text)
        }
    }

    @Test
    fun `the system prompt and the question are checked, not only the payload`() {
        assertEquals(PromptPart.SYSTEM_PROMPT, blocked(check(system = "Write to a.b@example.org")).part)
        assertEquals(PromptPart.QUESTION, blocked(check(question = "Is Zebediah's closet ok?")).part)
    }

    @Test
    fun `refs must be opaque and slugs must be in the allowed set`() {
        assertIs<Either.Right<GatedPrompt>>(check(payload = """{"ref":"table:a1b2c3d4e5f6"}"""))
        assertIs<GateViolation.MalformedRef>(blocked(check(payload = """{"ref":"table:Some_Title"}""")))
        assertIs<GateViolation.MalformedRef>(blocked(check(payload = """{"ref":"yaml:abc"}""")))
        assertIs<GateViolation.UnknownProject>(blocked(check(payload = """{"project":"proj-123abc"}""")))
        assertIs<GateViolation.UnknownProject>(blocked(check(payload = """{"project":"proj-my-real-house"}""")))
    }

    @Test
    fun `a violation reason never repeats the offending text`() {
        val reasons = listOf("Zebediah", "a.b@example.org", "412 Oak Ave", "/home/someone/x").map { blocked(check(payload = it)).reason }
        reasons.forEach { r -> listOf("Zebediah", "example.org", "Oak", "someone").forEach { assertTrue(it !in r, r) } }
    }

    @Test
    fun `an empty private-term list never matches`() {
        assertTrue(PrivateTermList.EMPTY.isEmpty)
        assertIs<Either.Right<GatedPrompt>>(check(payload = """{"label":"Zebediah"}""", terms = PrivateTermList.EMPTY))
    }

    @Test
    fun `the street-address patterns of the repo guard are a subset of the outbound patterns`() {
        val guard = WiringInputs.dataFile("private-string-patterns.txt").lines()
            .filter { it.startsWith("street-address-") }
            .map { it.substringAfter(" = ") }
        assertTrue(guard.isNotEmpty())
        val outbound = OutboundPatterns.all.map { it.source }.toSet()
        guard.forEach { assertTrue(it in outbound, "missing from OutboundPatterns: $it") }
    }
}
