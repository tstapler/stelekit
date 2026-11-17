// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.platform.V2StorageStatus
import dev.stapler.stelekit.ui.sketch.CuePlacement
import dev.stapler.stelekit.ui.sketch.StillSavingTiming
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchWebStorageStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web storage UX as the editor host mounts it (Task 1.4.7l): the durability cue and the `StillSaving` toast driver. */
class WebStorageHostTest {
    private class RecordingToast(var laneFree: Boolean = true) : StillSavingToast {
        val shown = mutableListOf<Long>()
        val hidden = mutableListOf<Long>()
        override fun laneFree(now: Long) = laneFree
        override fun show(now: Long) {
            shown += now
        }

        override fun hide(now: Long) {
            hidden += now
        }
    }

    @Test
    fun stillSaving_should_ShowAfterThreeSeconds_AndStayAtLeastOneSecondAfterThePersist() {
        val toast = RecordingToast()
        val watcher = StillSavingWatcher(toast)
        assertEquals(StillSavingTiming.SHOWN_AFTER_MS, watcher.update(saving = true, now = 0))
        assertTrue(toast.shown.isEmpty())
        assertNull(watcher.update(saving = true, now = StillSavingTiming.SHOWN_AFTER_MS))
        assertEquals(listOf(StillSavingTiming.SHOWN_AFTER_MS), toast.shown)
        val early = StillSavingTiming.SHOWN_AFTER_MS + 100
        assertEquals(StillSavingTiming.MIN_VISIBLE_MS - 100, watcher.update(saving = false, now = early))
        assertTrue(toast.hidden.isEmpty(), "a shown toast stays its minimum")
        assertNull(watcher.update(saving = false, now = StillSavingTiming.ANNOUNCE_AT_MS))
        assertEquals(listOf(StillSavingTiming.ANNOUNCE_AT_MS), toast.hidden)
    }

    @Test
    fun stillSaving_should_NeverShow_WhenTheWritePersistsBeforeThreeSeconds() {
        val toast = RecordingToast()
        val watcher = StillSavingWatcher(toast)
        watcher.update(saving = true, now = 0)
        assertNull(watcher.update(saving = false, now = StillSavingTiming.SHOWN_AFTER_MS - 1))
        assertTrue(toast.shown.isEmpty())
        assertTrue(toast.hidden.isEmpty())
    }

    @Test
    fun stillSaving_should_NotDisplaceAnotherToast() {
        val toast = RecordingToast(laneFree = false)
        val watcher = StillSavingWatcher(toast)
        watcher.update(saving = true, now = 0)
        assertNull(watcher.update(saving = true, now = StillSavingTiming.SHOWN_AFTER_MS))
        assertTrue(toast.shown.isEmpty())
    }

    @Test
    fun cue_should_BeAbsent_ForAnOrdinaryStorage_AndPresentForATemporaryGraphOrAPrivateWindow() {
        assertNull(DurabilityCueHost.viewOf(V2StorageStatus.Ready(sessionOnly = false, privateSuspected = false)) {})
        assertNull(DurabilityCueHost.viewOf(V2StorageStatus.Checking) {})
        val temporary = assertNotNull(DurabilityCueHost.viewOf(V2StorageStatus.Ready(sessionOnly = true, privateSuspected = true)) {})
        assertEquals(SketchWebStorageStrings.Cue.SESSION_FULL, temporary.forms.full, "a temporary graph beats a private window")
        val private = assertNotNull(DurabilityCueHost.viewOf(V2StorageStatus.Ready(sessionOnly = false, privateSuspected = true)) {})
        assertEquals(SketchWebStorageStrings.Cue.PRIVATE_FULL, private.forms.full)
    }

    @Test
    fun cue_should_PassItsFullFormToTheActivationHandler() {
        var activated: String? = null
        val view = assertNotNull(DurabilityCueHost.viewOf(V2StorageStatus.Ready(sessionOnly = false, privateSuspected = true)) { activated = it })
        view.onActivate()
        assertEquals(SketchWebStorageStrings.Cue.PRIVATE_FULL, activated)
    }

    @Test
    fun hintText_should_LeadWithTheCue_OnTheHintRow_AndLeaveTheHintAloneOtherwise() {
        val view = assertNotNull(DurabilityCueHost.viewOf(V2StorageStatus.Ready(sessionOnly = false, privateSuspected = true)) {})
        val wide = DurabilityCueHost.shown(view, WIDE_DP, TALL_DP, 1.0)
        assertEquals(CuePlacement.HintRow, wide.placement)
        val text = DurabilityCueHost.hintText(wide, "Click to draw.", LINE_CHARS)
        assertTrue(text.startsWith(SketchWebStorageStrings.Cue.PRIVATE_FULL), text)
        assertEquals("Click to draw.", DurabilityCueHost.hintText(null, "Click to draw.", LINE_CHARS))
    }

    private companion object {
        const val WIDE_DP = 1200
        const val TALL_DP = 800
        const val LINE_CHARS = 120
    }
}
