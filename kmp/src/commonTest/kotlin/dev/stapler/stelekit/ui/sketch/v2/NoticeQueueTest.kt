// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.ChromeTableParser
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTier
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLogSink
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 3.6.7 (UX-91, UX-112, D-20): one priority queue, notice timing, throttled live regions; all in virtual time. */
class NoticeQueueTest {
    private val table = ChromeTableParser.parseFixture()

    private val strings = ChromeBannerStrings(table)

    private fun candidate(row: BannerRowId) = strings.candidate(row)

    private fun toast(key: String, tier: BannerTier = BannerTier.Info, text: String = key, action: String? = null, undoable: Boolean = false) =
        SketchNotice(key, tier, text, action, undoable)

    private fun queue(limits: SceneLimits = SceneLimits.Default) = SketchNoticeQueue(limits)

    @Test
    fun notices_should_BeShownAndAnnouncedOneAtATime_InTierOrder_When_FourArriveWithin500ms() {
        val q = queue()
        q.post(toast("AddedConstraint", BannerTier.Info, "Constraint added.", "Undo", undoable = true), now = 0)
        q.post(toast("SaveFailed", BannerTier.Blocking, "Couldn't save."), now = 100)
        q.announceStatus("Selection cleared", "status", BannerTier.Info)
        q.post(toast("Conflict", BannerTier.Blocking, "Sketch changed on disk."), now = 400)
        assertEquals(listOf("SaveFailed", "Conflict", "AddedConstraint"), q.visible(now = 450).map { it.notice.key })
        val said = mutableListOf<Announcement>()
        var t = 450L
        repeat(MAX_POLLS) { q.nextAnnouncement(t)?.let(said::add); t += NoticeTiming.POLITE_INTERVAL_MS }
        assertEquals(listOf("Couldn't save.", "Sketch changed on disk.", "Constraint added.", "Selection cleared"), said.map { it.text })
        assertEquals(listOf(true, true, false, false), said.map { it.assertive })
    }

    @Test
    fun blockingNotice_should_PreemptAWaitingPoliteOne_AndBeAssertive() {
        val q = queue()
        q.post(toast("Info", text = "Hello"), now = 0)
        q.post(toast("Boom", BannerTier.Blocking, "Couldn't save."), now = 10)
        val first = q.nextAnnouncement(10)
        assertEquals(Announcement("Couldn't save.", assertive = true), first)
        assertEquals(Announcement("Hello", assertive = false), q.nextAnnouncement(10))
    }

    @Test
    fun visible_should_CapAtMaxVisibleNotices_And_CountTheRest() {
        val q = queue()
        val cap = SceneLimits.Default.maxVisibleNotices
        for (i in 0..cap + 1) q.post(toast("n$i"), now = i.toLong())
        assertEquals(cap, q.visible(10).size)
        assertEquals(2, q.hiddenCount(10))
    }

    @Test
    fun identicalConsecutiveNotices_should_CoalesceIntoOneWithACount() {
        val q = queue()
        repeat(3) { q.post(toast("same", text = "Same."), now = it.toLong()) }
        q.post(toast("other"), now = 5)
        q.post(toast("other"), now = 6)
        val shown = q.visible(10)
        assertEquals(listOf("same" to 3, "other" to 2), shown.map { it.notice.key to it.count })
    }

    @Test
    fun politeAnnouncements_should_BeThrottledToOnePerSecond_AndTheLastStateWins() {
        val q = queue()
        val said = mutableListOf<Pair<Long, String>>()
        for (i in 0 until FIFTY) {
            val t = i * EVENT_GAP_MS
            q.announceStatus(if (i == FIFTY - 1) "Undid: move rectangle" else "Undid: step $i", "undo")
            q.nextAnnouncement(t)?.let { said += t to it.text }
        }
        var t = TWO_SECONDS
        while (t <= TWO_SECONDS * 2) {
            q.nextAnnouncement(t)?.let { said += t to it.text }
            t += TEN_MS
        }
        assertTrue(said.zipWithNext().all { (a, b) -> b.first - a.first >= NoticeTiming.POLITE_INTERVAL_MS }, "one per second: $said")
        assertEquals("Undid: move rectangle", said.last().second)
        assertEquals(3, said.size)
    }

    @Test
    fun durations_should_FollowUx112() {
        val q = queue()
        q.post(toast("plain", text = "Saved."), now = 0)
        q.post(toast("confirm", text = "Constraint added.", action = "Undo", undoable = true), now = 0)
        q.post(toast("action", text = "Locked layer.", action = "Unlock"), now = 0)
        assertEquals(setOf("plain", "confirm", "action"), q.visible(NoticeTiming.INFO_MS - 1).map { it.notice.key }.toSet())
        assertEquals(setOf("confirm", "action"), q.visible(NoticeTiming.INFO_MS).map { it.notice.key }.toSet())
        assertEquals(setOf("action"), q.visible(NoticeTiming.CONFIRMATION_MS).map { it.notice.key }.toSet())
        assertEquals(setOf("action"), q.visible(NoticeTiming.CONFIRMATION_MS * HOUR_FACTOR).map { it.notice.key }.toSet())
        assertEquals("Constraint added.", q.diagnosticNotices.single().text)
    }

    @Test
    fun hoverAndFocus_should_PauseTheCountdown_AndRestartFromTheFullDuration() {
        val q = queue()
        q.post(toast("confirm", text = "Constraint added.", action = "Undo", undoable = true), now = 0)
        q.setPause("confirm", PauseCause.Hover, on = true, now = 9_000)
        assertEquals(1, q.visible(NoticeTiming.CONFIRMATION_MS * HOUR_FACTOR).size)
        q.setPause("confirm", PauseCause.Hover, on = false, now = 100_000)
        assertEquals(1, q.visible(100_000 + NoticeTiming.CONFIRMATION_MS - 1).size)
        assertEquals(0, q.visible(100_000 + NoticeTiming.CONFIRMATION_MS).size)
        q.post(toast("info", text = "Hi."), now = 200_000)
        q.setPause("info", PauseCause.Focus, on = true, now = 201_000)
        assertEquals(1, q.visible(500_000).size)
        q.setPause("info", PauseCause.Focus, on = false, now = 500_000)
        assertEquals(0, q.visible(500_000 + NoticeTiming.INFO_MS).size)
    }

    @Test
    fun touch_should_FreezeTheCountdown_UntilReleasedAndThreeSecondsAfter() {
        val q = queue()
        q.post(toast("info", text = "Hi."), now = 0)
        q.setPause("info", PauseCause.Touch, on = true, now = 4_000)
        assertEquals(1, q.visible(60_000).size)
        q.setPause("info", PauseCause.Touch, on = false, now = 60_000)
        assertEquals(1, q.visible(60_000 + NoticeTiming.TOUCH_GRACE_MS - 1).size)
        assertEquals(0, q.visible(60_000 + NoticeTiming.TOUCH_GRACE_MS).size)
    }

    @Test
    fun escOnTheNewestToast_should_DismissItAndKeepAnActionNoticeInTheList() {
        val q = queue()
        q.post(toast("a", text = "One."), now = 0)
        q.post(toast("b", text = "Two.", action = "Undo"), now = 1)
        assertTrue(q.dismissNewest(now = 2))
        assertEquals(listOf("a"), q.visible(3).map { it.notice.key })
        assertEquals(listOf("Two."), q.diagnosticNotices.map { it.text })
        assertTrue(q.dismissNewest(now = 4))
        assertFalse(q.dismissNewest(now = 5))
    }

    @Test
    fun diagnosticNotices_should_BeCapped() {
        val q = queue(SceneLimits.Default)
        for (i in 0 until SceneLimits.Default.maxDiagnosticsEntries + 3) {
            q.post(toast("k$i", text = "t$i", action = "Open"), now = i.toLong())
            q.dismiss("k$i", now = i.toLong())
        }
        assertEquals(SceneLimits.Default.maxDiagnosticsEntries, q.diagnosticNotices.size)
        assertEquals("t${SceneLimits.Default.maxDiagnosticsEntries + 2}", q.diagnosticNotices.first().text)
    }

    @Test
    fun bannerSlot_should_HoldTheLowestRankedCandidate_AndOthersWait() {
        val q = queue()
        q.submitBanner(candidate(BannerRowId.LargeDrawing))
        q.submitBanner(candidate(BannerRowId.ReadOnly))
        q.submitBanner(candidate(BannerRowId.SaveFailed))
        assertEquals(BannerRowId.SaveFailed, q.bannerHead()?.row)
        assertEquals(listOf(BannerRowId.ReadOnly, BannerRowId.LargeDrawing), q.waitingBanners().map { it.row })
        q.resolveBanner(BannerRowId.SaveFailed)
        assertEquals(BannerRowId.ReadOnly, q.bannerHead()?.row)
    }

    @Test
    fun onboardingItem_should_YieldToBlockingAndWarning_AndAppearWhenTheyClear() {
        val sequencer = OnboardingSequencer(OnboardingCounters(InMemorySettings()))
        val q = SketchNoticeQueue(sequencer = sequencer)
        q.submitBanner(candidate(BannerRowId.WarningNotice))
        sequencer.trigger(OnboardingItem.CoachMark, candidate(BannerRowId.CoachMark))
        assertEquals(BannerRowId.WarningNotice, q.bannerHead()?.row)
        assertNull(sequencer.visible, "an onboarding item waits while a Warning is up")
        q.resolveBanner(BannerRowId.WarningNotice)
        assertEquals(BannerRowId.CoachMark, q.bannerHead()?.row)
        assertEquals(OnboardingItem.CoachMark, sequencer.visible)
    }

    @Test
    fun queue_should_LogTheKeyAndNeverTheText() {
        val lines = mutableListOf<String>()
        val q = SketchNoticeQueue(log = SketchLog(SketchLogSink { lines += it }))
        q.post(toast("LayerLocked", text = "Locked layer: Secret Walls."), now = 0)
        assertEquals(listOf("sketch.notice.shown key=LayerLocked"), lines)
        assertTrue(lines.none { "Secret" in it })
    }

    private companion object {
        const val MAX_POLLS = 6
        const val FIFTY = 50
        const val TWO_SECONDS = 2_000L
        const val TEN_MS = 10L
        const val EVENT_GAP_MS = 40L
        const val HOUR_FACTOR = 360L
    }
}
