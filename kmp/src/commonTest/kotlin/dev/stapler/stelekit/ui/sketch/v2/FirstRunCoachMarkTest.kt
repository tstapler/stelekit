// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.ChromeTableParser
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerCandidate
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tasks 3.6.8b and 3.6.6c (ux.md section 16, rows 13 and the empty state): the first-run coach mark is a once-per-device banner
 * row scheduled through the onboarding sequencer, and the empty state shows only while the sheet is empty and nothing above it
 * is up.
 */
class FirstRunCoachMarkTest {
    private val table = ChromeTableParser.parseFixture()
    private val settings = InMemorySettings()

    private fun slot(sequencer: OnboardingSequencer = OnboardingSequencer(OnboardingCounters(settings))): Pair<BannerSlotState, SketchNoticeQueue> {
        val queue = SketchNoticeQueue(sequencer = sequencer)
        return BannerSlotState(queue) to queue
    }

    @Test
    fun coachMarkStrings_should_EqualRow13OfTheChromeTable() {
        val row = table.banners.single { it.id == "CoachMark" }
        assertEquals(row.summary, SketchStrings.CoachMark.SUMMARY)
        assertEquals(row.shortTitle, SketchStrings.CoachMark.SHORT_TITLE)
        assertEquals(row.primaryLabel, SketchStrings.CoachMark.PRIMARY)
        assertEquals(row.moreItems, listOf(SketchStrings.CoachMark.GOT_IT))
        assertTrue(BannerCandidate.of(BannerRowId.CoachMark, SketchStrings.CoachMark).isRight(), "the row fits the banner text budget")
    }

    @Test
    fun coachMark_should_ShowOnTheFirstOpen_WithItsFixedSummary() {
        val (slot, _) = slot()
        assertTrue(slot.offerCoachMark())
        assertEquals(BannerRowId.CoachMark, slot.head?.row)
        assertEquals("New here? Quick tips.", slot.head?.summary)
        assertEquals("Show me", slot.head?.primaryLabel)
    }

    @Test
    fun coachMark_should_NeverReturnOnThisDevice_AfterAnyDismissal() {
        for (how in listOf(Dismissal.Close, Dismissal.Esc, Dismissal.ActionButton, Dismissal.NavigateAway)) {
            val device = InMemorySettings()
            val (first, _) = slot(OnboardingSequencer(OnboardingCounters(device)))
            assertTrue(first.offerCoachMark())
            first.dismissCoachMark(how)
            assertNull(first.head, "$how dismissed it")
            assertFalse(first.offerCoachMark(), "$how: not offered again in the same session")
            val (later, _) = slot(OnboardingSequencer(OnboardingCounters(device)))
            assertFalse(later.offerCoachMark(), "$how: not offered again on the next open of the same device")
            assertNull(later.head)
        }
    }

    @Test
    fun coachMark_should_NotBeShownTwice_EvenWhenNeverDismissed() {
        val (first, _) = slot(OnboardingSequencer(OnboardingCounters(settings)))
        assertTrue(first.offerCoachMark())
        val (second, _) = slot(OnboardingSequencer(OnboardingCounters(settings)))
        assertFalse(second.offerCoachMark(), "the max shows of the once class is one")
    }

    @Test
    fun coachMark_should_WaitBehindABlockingNotice_AndAppearOnceItIsResolved() {
        val (slot, queue) = slot()
        val saveFailed = BannerCandidate.of(BannerRowId.SaveFailed, dev.stapler.stelekit.ui.sketch.v2.ChromeBannerStrings(table)).getOrNull()
        queue.submitBanner(assertNotNull(saveFailed))
        slot.offerCoachMark()
        slot.refresh()
        assertEquals(BannerRowId.SaveFailed, slot.head?.row, "a Blocking banner owns the one slot")
        queue.resolveBanner(BannerRowId.SaveFailed)
        slot.refresh()
        assertEquals(BannerRowId.CoachMark, slot.head?.row)
    }

    @Test
    fun emptyState_should_ShowOnlyWhileTheSheetIsEmpty_AndNothingHigherIsUp() {
        val (slot, _) = slot()
        slot.updateEmpty(true)
        assertTrue(slot.emptyStateVisible)
        slot.updateEmpty(false)
        assertFalse(slot.emptyStateVisible, "the first element removes it")
        slot.updateEmpty(true)
        assertTrue(slot.emptyStateVisible, "it returns when the sheet is emptied again")
    }

    @Test
    fun emptyState_should_WaitForTheCoachMark_AndComeBackAfterIt() {
        val (slot, _) = slot()
        slot.offerCoachMark()
        slot.refresh(sheetEmpty = true)
        assertFalse(slot.emptyStateVisible, "one onboarding item at a time: the coach mark first")
        slot.dismissCoachMark(Dismissal.Close)
        assertTrue(slot.emptyStateVisible, "then the empty state, which is a state and never counted")
    }
}
