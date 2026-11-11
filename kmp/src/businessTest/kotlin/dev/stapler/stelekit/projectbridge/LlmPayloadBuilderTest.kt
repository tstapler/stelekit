// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import dev.stapler.stelekit.fixtures.ClosetFixtureText
import dev.stapler.stelekit.fixtures.ClosetFusion
import dev.stapler.stelekit.fusion.FusionInput
import dev.stapler.stelekit.fusion.fuseObservations
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 5.5b-1: the payload is a closed vocabulary of opaque ids, labels, values and opaque refs. */
class LlmPayloadBuilderTest {
    private val registry = ClosetFusion.registry
    private val observations = ClosetFusion.observations()
    private val result = fuseObservations(FusionInput(observations, ClosetFusion.constraints()), registry).fold({ error("$it") }, { it })
    private val checks = listOf(SumCheck("Zones sum to opening", 1, "niche.width", ClosetFusion.zones))
    private val payload = LlmPayloadBuilder.build("proj-closet-1", result, registry, observations, checks)
    private val json = LlmPayloadBuilder.toJson(payload)

    private fun names(d: SerialDescriptor) = (0 until d.elementsCount).map { d.getElementName(it) }

    @Test
    fun `payload json equals the golden`() {
        assertEquals(ClosetFixtureText.load("expected-llm-payload.json"), json)
    }

    @Test
    fun `serialised vocabulary is closed - adding a field fails here`() {
        assertEquals(listOf("project", "checks", "dimensions"), names(LlmPayload.serializer().descriptor))
        assertEquals(listOf("label", "verdict", "summary"), names(PayloadCheck.serializer().descriptor))
        assertEquals(listOf("kind", "ref"), names(PayloadSource.serializer().descriptor))
        assertEquals(
            listOf("id", "label", "status", "display", "num", "den", "sources"),
            names(PayloadDimension.serializer().descriptor),
        )
    }

    @Test
    fun `payload names only the opaque slug and no registry key or source locator`() {
        assertEquals("proj-closet-1", payload.project)
        val keys = result.dimensions.keys + observations.map { it.source.ref.id }
        keys.filter { it.contains('.') }.forEach { assertFalse(it in json, "payload leaks identifier $it") }
        assertTrue(payload.dimensions.all { it.id.matches(Regex("d-[0-9a-f]{8}")) })
        payload.dimensions.flatMap { it.sources }.forEach { assertTrue(it.ref.matches(Regex("(table|svg|yaml|prose|derived):[a-z0-9]{6,16}")), it.ref) }
    }

    @Test
    fun `payload carries the pass verdict and every zone value`() {
        assertEquals("PASS", payload.checks.single().verdict)
        listOf("33 7/16\"", "22 1/2\"", "24 3/16\"", "33 3/8\"", "136\"").forEach { assertTrue(it.replace("\"", "\\\"") in json, it) }
    }

    @Test
    fun `a ref outside the opaque shape is re-hashed rather than passed through`() {
        val risky = observations.map {
            it.copy(source = it.source.copy(ref = dev.stapler.stelekit.fusion.SourceRef(it.source.kind, "Some Page Title.md")))
        }
        val rebuilt = LlmPayloadBuilder.toJson(LlmPayloadBuilder.build("proj-closet-1", result, registry, risky, checks))
        assertFalse("Some Page Title" in rebuilt, "title text leaked")
    }
}
