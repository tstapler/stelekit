// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.llm

import dev.stapler.stelekit.wiring.WiringInputs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 5.5b-2: a [GatedPrompt] can be minted by exactly one production file, and cannot be copied. */
class GateIssuedAuditTest {
    @Test
    fun `GateIssued opt-in appears in exactly one production file`() {
        val sites = WiringInputs.productionSources.filterValues { text -> text.lines().any { "OptIn(GateIssued" in it } }.keys
        assertEquals(1, sites.size, "opt-in sites: $sites")
        assertTrue(sites.single().endsWith("llm/OutboundLlmGate.kt"), sites.toString())
    }

    @Test
    fun `GatedPrompt constructor is internal and marked GateIssued`() {
        val source = WiringInputs.productionSources.entries.single { it.key.endsWith("llm/GatedPrompt.kt") }.value
        assertTrue("@GateIssued internal constructor" in source)
        assertFalse("data class" in source.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/") }.joinToString("\n"))
    }

    @Test
    fun `GatedPrompt has no copy method`() {
        assertFalse(GatedPrompt::class.java.declaredMethods.any { it.name.startsWith("copy") })
    }
}
