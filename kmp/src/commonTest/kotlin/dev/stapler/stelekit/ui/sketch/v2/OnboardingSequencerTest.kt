// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.ChromeTableParser
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.6.7 (UX-116, D-19): at most one onboarding item visible, in banner rank order, nothing announced under another. */
class OnboardingSequencerTest {
    private val table = ChromeTableParser.parseFixture()

    private val strings = ChromeBannerStrings(table)

    private fun sequencer() = OnboardingSequencer(OnboardingCounters(InMemorySettings()))

    private fun OnboardingSequencer.offer(item: OnboardingItem) = trigger(item, item.row?.let(strings::candidate))

    @Test
    fun onlyOneItem_should_BeVisible_WhileTheOthersWait() {
        val s = sequencer()
        s.offer(OnboardingItem.SingleKeyChip)
        s.offer(OnboardingItem.CoachMark)
        s.offer(OnboardingItem.FingerHintOn)
        assertEquals(OnboardingItem.SingleKeyChip, s.visible, "the first offered is shown while nothing else was waiting")
        assertEquals(BannerRowId.SingleKeyChip, s.visibleCandidate()?.row)
    }

    @Test
    fun waitingItems_should_AppearInRankOrder_AfterTheVisibleOneIsDismissed() {
        val s = sequencer()
        s.yielding = true
        for (item in listOf(OnboardingItem.SingleKeyChip, OnboardingItem.CoachMark, OnboardingItem.FingerHintOn, OnboardingItem.EdgePanStopped, OnboardingItem.UpgradeChip)) s.offer(item)
        assertNull(s.visible)
        s.yielding = false
        val order = mutableListOf<OnboardingItem>()
        while (true) {
            val v = s.visible ?: break
            order += v
            s.dismiss(v, Dismissal.Close)
        }
        assertEquals(
            listOf(OnboardingItem.EdgePanStopped, OnboardingItem.FingerHintOn, OnboardingItem.UpgradeChip, OnboardingItem.CoachMark, OnboardingItem.SingleKeyChip),
            order,
        )
        assertEquals(order.mapNotNull { OnboardingPolicy.rank(it) }.sorted(), order.mapNotNull { OnboardingPolicy.rank(it) })
    }

    @Test
    fun announcement_should_ComeOnceWhenAnItemAppears_AndNeverWhileAnotherIsVisible() {
        val s = sequencer()
        s.offer(OnboardingItem.CoachMark)
        assertEquals(table.banners.single { it.id == "CoachMark" }.summary, s.takeAnnouncement())
        assertNull(s.takeAnnouncement())
        s.offer(OnboardingItem.SingleKeyChip)
        assertNull(s.takeAnnouncement(), "nothing is announced while another item is visible")
        s.dismiss(OnboardingItem.CoachMark, Dismissal.ActionButton)
        assertEquals(table.banners.single { it.id == "SingleKeyChip" }.summary, s.takeAnnouncement())
    }

    @Test
    fun emptyState_should_ShowOnlyWhileNothingElseDoes_AndReturnOnceTheOthersAreGone() {
        val s = sequencer()
        assertTrue(s.emptyStateVisible(sheetIsEmpty = true))
        s.offer(OnboardingItem.CoachMark)
        assertFalse(s.emptyStateVisible(sheetIsEmpty = true), "the coach mark is up")
        s.dismiss(OnboardingItem.CoachMark, Dismissal.Close)
        assertTrue(s.emptyStateVisible(sheetIsEmpty = true), "back once the others are dismissed and the sheet is still empty")
        assertFalse(s.emptyStateVisible(sheetIsEmpty = false), "never over a sheet with elements")
    }

    @Test
    fun yielding_should_HideTheVisibleCandidate_WithoutRetiringIt() {
        val s = sequencer()
        s.offer(OnboardingItem.CoachMark)
        assertEquals(BannerRowId.CoachMark, s.visibleCandidate()?.row)
        s.yielding = true
        assertNull(s.visibleCandidate())
        s.yielding = false
        assertEquals(BannerRowId.CoachMark, s.visibleCandidate()?.row)
    }

    @Test
    fun newSession_should_ClearAVisibleItem_And_RetireNothing() {
        val s = sequencer()
        s.offer(OnboardingItem.FingerHintOff)
        s.newSession()
        assertNull(s.visible)
        s.offer(OnboardingItem.FingerHintOff)
        assertEquals(OnboardingItem.FingerHintOff, s.visible, "twice-or-acted: a second showing is still allowed")
    }
}
