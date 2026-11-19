// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.ChromeTableParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 3.6.7 (UX-122, D-19): the recurrence table of ux.md section 16, row by row, plus persistence across a restart. */
class OnboardingPolicyTest {

    private val implicit = listOf(Dismissal.Close, Dismissal.Esc, Dismissal.NavigateAway, Dismissal.NewSession)
    private val settings = InMemorySettings()

    /** A sequencer over [settings]; a new one is a restart (in-memory session state is gone, counters are not). */
    private fun session() = OnboardingSequencer(OnboardingCounters(settings))

    private fun shown(item: OnboardingItem) = OnboardingCounters(settings).read(item).shown

    /** Triggers [item] then dismisses it as [how] when it became visible; returns whether it showed. */
    private fun cycle(s: OnboardingSequencer, item: OnboardingItem, how: Dismissal, sheet: String? = null): Boolean {
        s.trigger(item, candidate = null, sheetKey = sheet)
        val visible = s.visible == item
        if (visible) s.dismiss(item, how)
        return visible
    }

    @Test
    fun rows_should_EqualTheTableOfUx_Section16() {
        val expected = listOf(
            Triple(OnboardingItem.EdgePanStopped, Recurrence.Once, 1),
            Triple(OnboardingItem.FingerHintOff, Recurrence.TwiceOrActed, 2),
            Triple(OnboardingItem.FingerHintOn, Recurrence.TwiceOrActed, 2),
            Triple(OnboardingItem.UpgradeChip, Recurrence.UntilActed, null),
            Triple(OnboardingItem.CoachMark, Recurrence.Once, 1),
            Triple(OnboardingItem.SingleKeyChip, Recurrence.Once, 1),
            Triple(OnboardingItem.EmptyState, Recurrence.WhileEmpty, null),
        )
        assertEquals(expected, OnboardingPolicy.rows.map { Triple(it.item, it.recurrence, it.maxShows) })
        assertEquals(
            mapOf(
                OnboardingItem.UpgradeChip to CounterScope.PerSheet, OnboardingItem.EmptyState to CounterScope.None,
                OnboardingItem.CoachMark to CounterScope.PerDevice,
            ),
            OnboardingPolicy.rows.associate { it.item to it.scope }.filterKeys { it in setOf(OnboardingItem.UpgradeChip, OnboardingItem.EmptyState, OnboardingItem.CoachMark) },
        )
    }

    @Test
    fun everyOnboardingItem_should_HaveExactlyOneRow() {
        assertEquals(OnboardingItem.entries.toSet(), OnboardingPolicy.rows.map { it.item }.toSet())
        assertEquals(OnboardingItem.entries.size, OnboardingPolicy.rows.size)
    }

    @Test
    fun ranks_should_BeTheBannerRanksOfTheChromeTable_NotRestatedHere() {
        val table = ChromeTableParser.parseFixture()
        for (item in OnboardingItem.entries) {
            val row = item.row
            if (row == null) {
                assertEquals(null, OnboardingPolicy.rank(item))
            } else {
                assertEquals(table.banners.single { it.id == row.name }.rank, OnboardingPolicy.rank(item), item.name)
            }
        }
        val ranks = OnboardingItem.entries.mapNotNull { OnboardingPolicy.rank(it) }
        assertEquals(ranks.sorted(), ranks, "the enum lists the items in banner rank order")
    }

    @Test
    fun once_should_ShowOneTime_Whatever_TheDismissal() {
        for (item in listOf(OnboardingItem.EdgePanStopped, OnboardingItem.CoachMark, OnboardingItem.SingleKeyChip)) {
            for (how in Dismissal.entries) {
                val fresh = InMemorySettings()
                val s = OnboardingSequencer(OnboardingCounters(fresh))
                assertTrue(cycle(s, item, how), "$item first trigger")
                repeat(THREE) { assertFalse(cycle(s, item, how), "$item shown again after $how") }
                assertEquals(1, OnboardingCounters(fresh).read(item).shown, "$item after $how")
            }
        }
    }

    @Test
    fun twiceOrActed_should_ShowOnceMoreAfterAnImplicitDismissal_ThenNeverAgain() {
        for (item in listOf(OnboardingItem.FingerHintOff, OnboardingItem.FingerHintOn)) {
            for (how in implicit) {
                val fresh = InMemorySettings()
                val s = OnboardingSequencer(OnboardingCounters(fresh))
                assertTrue(cycle(s, item, how), "$item first")
                assertTrue(cycle(s, item, how), "$item second after $how")
                repeat(THREE) { assertFalse(cycle(s, item, how), "$item third after $how") }
                assertEquals(2, OnboardingCounters(fresh).read(item).shown)
            }
        }
    }

    @Test
    fun twiceOrActed_should_NeverRepeat_AfterAnActionOrChoosingTheSetting() {
        for (item in listOf(OnboardingItem.FingerHintOff, OnboardingItem.FingerHintOn)) {
            for (how in listOf(Dismissal.ActionButton, Dismissal.ChoseSetting)) {
                val fresh = InMemorySettings()
                val s = OnboardingSequencer(OnboardingCounters(fresh))
                assertTrue(cycle(s, item, how))
                repeat(THREE) { assertFalse(cycle(s, item, how), "$item repeated after $how") }
                assertEquals(1, OnboardingCounters(fresh).read(item).shown)
            }
        }
    }

    @Test
    fun untilActed_should_ReturnOnEveryOpen_UntilStartOrKeepClassic_PerSheet() {
        val s = session()
        assertTrue(cycle(s, OnboardingItem.UpgradeChip, Dismissal.NavigateAway, "sheetA"))
        assertTrue(cycle(s, OnboardingItem.UpgradeChip, Dismissal.NavigateAway, "sheetA"))
        assertTrue(cycle(s, OnboardingItem.UpgradeChip, Dismissal.ActionButton, "sheetA"), "[Start] retires it for that sheet")
        assertFalse(cycle(s, OnboardingItem.UpgradeChip, Dismissal.Close, "sheetA"))
        assertTrue(cycle(s, OnboardingItem.UpgradeChip, Dismissal.NavigateAway, "sheetB"), "another sheet still gets it")
    }

    @Test
    fun untilActed_should_HideForTheSessionOnNotNow_AndReturnInTheNext() {
        val s = session()
        assertTrue(cycle(s, OnboardingItem.UpgradeChip, Dismissal.NotNow, "sheetA"))
        assertFalse(cycle(s, OnboardingItem.UpgradeChip, Dismissal.NotNow, "sheetB"), "not for any sketch of the project this session")
        assertTrue(cycle(session(), OnboardingItem.UpgradeChip, Dismissal.NotNow, "sheetA"), "a later session shows it again")
        assertTrue(cycle(session(), OnboardingItem.UpgradeChip, Dismissal.NotNow, "sheetA"), "unlimited showings")
    }

    @Test
    fun counters_should_SurviveARestart_AndUseExactlyOneKeyPerCountedItem() {
        val first = session()
        for (item in OnboardingItem.entries) cycle(first, item, Dismissal.Close, "sheet")
        val keys = settings.store.keys.filter { it.startsWith(OnboardingPolicy.SETTING_PREFIX) }
        assertEquals(keys.toSet().size, keys.size)
        assertEquals(
            OnboardingPolicy.rows.filter { it.scope == CounterScope.PerDevice }.map { OnboardingPolicy.key(it.item) }.toSet(),
            keys.toSet(),
        )
        val restarted = session()
        assertFalse(cycle(restarted, OnboardingItem.CoachMark, Dismissal.Close), "the coach mark is retired for good")
        assertEquals(1, shown(OnboardingItem.CoachMark))
    }

    @Test
    fun emptyState_should_NeverBeCounted() {
        val s = session()
        assertTrue(s.emptyStateVisible(sheetIsEmpty = true))
        assertTrue(settings.store.keys.none { it.endsWith(OnboardingItem.EmptyState.name) })
    }

    private companion object {
        const val THREE = 3
    }
}
