// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.projectbridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Story 5.6c (pure part): the text that would leave the device and its hash-bound acceptance. */
class OutboundPreviewTest {
    private val payload = ClosetProjectOutputs.outputs().payload

    @Test
    fun theTextShowsSlugLabelsAndValuesButNoSourceRef() {
        val text = OutboundPreview.payloadText(payload)
        assertTrue(text.startsWith("Project: proj-closet-1\nChecks:\n"), text)
        assertTrue("- Opening width: 136\" (confirmed)" in text, text)
        assertFalse(Regex("(table|svg|yaml|prose|user|derived):[a-z0-9]{6,16}").containsMatchIn(text))
    }

    @Test
    fun theHashChangesWhenALabelChanges() {
        val relabelled = payload.copy(dimensions = payload.dimensions.map { if (it.label == "Opening width") it.copy(label = "Opening width x") else it })
        assertEquals(OutboundPreview.hash(OutboundPreview.payloadText(payload)), OutboundPreview.hash(OutboundPreview.payloadText(payload)))
        assertNotEquals(OutboundPreview.hash(OutboundPreview.payloadText(payload)), OutboundPreview.hash(OutboundPreview.payloadText(relabelled)))
    }

    @Test
    fun anOversizedPayloadIsCappedWithAnOmissionLineAndKeepsChecksAndConflicts() {
        val many = (1..2000).map { PayloadDimension("d-$it", "Dimension number $it", PayloadStatus.CONFIRMED, "1\"") }
        val conflict = PayloadDimension("d-c", "Conflicted", PayloadStatus.CONFLICT, "1\" vs 2\"")
        val text = OutboundPreview.payloadText(payload.copy(dimensions = many + conflict))
        assertTrue(text.length <= OutboundPreview.MAX_CHARS, text.length.toString())
        assertTrue("- Zones sum to opening: PASS" in text && "- Conflicted: 1\" vs 2\" (conflict)" in text)
        assertTrue(Regex("- \\d+ more omitted").containsMatchIn(text), text.takeLast(80))
    }

    @Test
    fun acceptanceIsBoundToTheExactTextAndPerGraph() {
        val settings = ProjectBridgeSettings(BridgeMapSettings())
        val text = OutboundPreview.payloadText(payload)
        assertFalse(settings.isOutboundPreviewAccepted("g1", text), "nothing accepted yet")

        settings.acceptOutboundPreview("g1", OutboundPreview.hash(text))

        assertTrue(settings.isOutboundPreviewAccepted("g1", text))
        assertFalse(settings.isOutboundPreviewAccepted("g1", text + "- extra\n"), "a changed preview re-prompts")
        assertFalse(settings.isOutboundPreviewAccepted("g2", text))
    }
}
