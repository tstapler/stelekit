// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.sketch.v2.runtime.session.ClassicNoticeLedger
import dev.stapler.stelekit.sketch.v2.runtime.session.ProjectAckStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 1.4.6e (D-96): the once-per-(graph, project, reason) ledger and the other device-local acknowledgement records. */
class ClassicNoticeLedgerTest {
    private val store = mutableMapOf<String, String>()

    private fun ledger() = ClassicNoticeLedger(MapSettings(store))

    @Test
    fun theLedger_should_SurviveARestart() {
        ledger().record("g1", "kitchen", "NpoClassicSheets", setOf("kitchen"))
        assertTrue(ledger().hasShown("g1", "kitchen", "NpoClassicSheets"), "a new ledger over the same settings is a restart")
        assertFalse(ledger().hasShown("g1", "kitchen", "NpoEmptyProject"), "another reason is a separate entry")
        assertFalse(ledger().hasShown("g1", "pantry", "NpoClassicSheets"))
        assertFalse(ledger().hasShown("g2", "kitchen", "NpoClassicSheets"), "another graph is separate")
    }

    @Test
    fun theLedger_should_HoldAtMostTwoHundredEntries_EvictingTheOldestFirst() {
        val ledger = ledger()
        val slugs = (1..ClassicNoticeLedger.MAX_ENTRIES + 3).map { "p$it" }
        slugs.forEach { ledger.record("g1", it, "ModeOff", null) }
        assertEquals(ClassicNoticeLedger.MAX_ENTRIES, ledger.size("g1"))
        assertFalse(ledger.hasShown("g1", "p1", "ModeOff"))
        assertFalse(ledger.hasShown("g1", "p3", "ModeOff"))
        assertTrue(ledger.hasShown("g1", "p4", "ModeOff"))
        assertTrue(ledger.hasShown("g1", slugs.last(), "ModeOff"))
    }

    @Test
    fun anEntryOfADeletedProject_should_BeDroppedOnTheNextWrite() {
        val ledger = ledger()
        ledger.record("g1", "gone", "ModeOff", setOf("gone", "kitchen"))
        ledger.record("g1", "kitchen", "ModeOff", setOf("kitchen"))
        assertFalse(ledger.hasShown("g1", "gone", "ModeOff"))
        assertTrue(ledger.hasShown("g1", "kitchen", "ModeOff"))
        ledger.record("g1", "pantry", "ModeOff", null)
        assertTrue(ledger.hasShown("g1", "kitchen", "ModeOff"), "an unreadable project list erases nothing")
    }

    @Test
    fun removingAGraph_should_RemoveItsWholeKey() {
        val ledger = ledger()
        ledger.record("g1", "kitchen", "ModeOff", null)
        ledger.record("g2", "kitchen", "ModeOff", null)
        ledger.removeGraph("g1")
        assertEquals(0, ledger.size("g1"))
        assertEquals("", store["sketch.classicNotices.g1"])
        assertTrue(ledger.hasShown("g2", "kitchen", "ModeOff"))
    }

    @Test
    fun theAcknowledgements_should_BePerGraphAndProject_AndPrunedWithTheProject() = runTest {
        val acks = ProjectAckStore(MapSettings(store)) { "g1" }
        acks.acknowledge("kitchen")
        acks.acknowledge("pantry")
        acks.recordConsentSheetDay("kitchen", 42L)
        assertTrue(ProjectAckStore(MapSettings(store)) { "g1" }.isAcknowledged("kitchen"), "survives a restart")
        assertFalse(ProjectAckStore(MapSettings(store)) { "g2" }.isAcknowledged("kitchen"), "another graph has its own")
        assertFalse(ProjectAckStore(MapSettings(store)) { null }.isAcknowledged("kitchen"), "no ready graph acknowledges nothing")
        acks.pruneProjects(setOf("kitchen"))
        assertFalse(acks.isAcknowledged("pantry"))
        assertEquals(42L, acks.consentSheetDay("kitchen"))
        acks.pruneProjects(emptySet())
        assertNull(acks.consentSheetDay("kitchen"))
        acks.removeGraph("g1")
        assertFalse(acks.isAcknowledged("kitchen"))
    }

    @Test
    fun theLevel_should_BeDeviceWide_AndSurviveARestart() {
        ProjectAckStore(MapSettings(store)) { "g1" }.raiseLevel(V2Mode.On)
        assertEquals(dev.stapler.stelekit.sketch.v2.runtime.session.AckLevel.On, ProjectAckStore(MapSettings(store)) { "g2" }.level)
    }
}
