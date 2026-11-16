// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.ChromeTableParser
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 3.6.4b (UX-48, UX-91, UX-119): the port's retained entries reach the notice queue; onboarding rows go through the sequencer. */
class DiagnosticsBridgeTest {
    private val strings = ChromeBannerStrings(ChromeTableParser.parseFixture())
    private val port = InMemoryDiagnosticsPort(strings)
    private val raw = InMemorySettings()
    private val queue = SketchNoticeQueue(sequencer = OnboardingSequencer(OnboardingCounters(raw)))
    private val bridge = DiagnosticsBridge(queue)

    @Test
    fun entryRaisedBeforeAnyHost_should_BeShownWhenTheBridgeAttaches() {
        port.raise(BannerRowId.SaveFailed, "disk full")
        assertNull(queue.bannerHead(), "nothing attached yet")
        bridge.sync(port.diagnostics.value)
        assertEquals(BannerRowId.SaveFailed, queue.bannerHead()?.row)
        val said = queue.nextAnnouncement(0L)
        assertEquals("Couldn't save. Keep tab open.", said?.text)
        assertTrue(said!!.assertive, "a save failure blocks work")
        bridge.sync(port.diagnostics.value)
        assertNull(queue.nextAnnouncement(NoticeTiming.POLITE_INTERVAL_MS), "announced once, however often the host syncs")
    }

    @Test
    fun warningRow_should_BePolite_And_NameConstraintTypesOnlyInDetails() {
        port.raise(BannerRowId.WarningNotice, "Horizontal and Distance conflict")
        bridge.sync(port.diagnostics.value)
        val said = queue.nextAnnouncement(0L)!!
        assertEquals(false, said.assertive)
        assertEquals("Constraint conflict.", said.text)
        assertEquals("Horizontal and Distance conflict", queue.bannerHead()?.details)
    }

    @Test
    fun resolvedRow_should_LeaveTheSlot_And_OnboardingRowsGoThroughTheSequencer() {
        port.raise(BannerRowId.SaveFailed)
        port.raise(BannerRowId.SingleKeyChip, "Single-key tool shortcuts are off.")
        bridge.sync(port.diagnostics.value)
        assertEquals(BannerRowId.SaveFailed, queue.bannerHead()?.row, "Blocking outranks onboarding")
        assertNull(queue.sequencer?.visibleCandidate(), "the sequencer yields while a blocking banner is up")

        port.resolve(BannerRowId.SaveFailed)
        bridge.sync(port.diagnostics.value)
        assertEquals(OnboardingItem.SingleKeyChip, queue.sequencer?.visible)
        assertEquals(BannerRowId.SingleKeyChip, queue.bannerHead()?.row)

        port.resolve(BannerRowId.SingleKeyChip)
        bridge.sync(port.diagnostics.value)
        assertNull(queue.bannerHead())
        assertNull(queue.sequencer?.visible)
    }
}
