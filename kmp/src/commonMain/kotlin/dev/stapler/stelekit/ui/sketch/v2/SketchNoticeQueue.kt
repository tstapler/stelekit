// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.diagnostics.BannerCandidate
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTier
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits

/**
 * A transient notice (a toast or a status message). [key] is its fixed id (`AddedConstraint`) and is what coalescing and
 * the log use; [text] is what is shown and announced and may name layers and elements, but never reaches a log (P5).
 * [action] is the label of the primary action, if any; [confirmsUndoableEdit] marks the one action notice that may time
 * out ("Constraint added." [Undo]), because its Undo stays the ordinary Undo command.
 */
data class SketchNotice(
    val key: String,
    val tier: BannerTier,
    val text: String,
    val action: String? = null,
    val confirmsUndoableEdit: Boolean = false,
)

/** What the live region should say: [assertive] only for an error that blocks work (UX-48). */
data class Announcement(val text: String, val assertive: Boolean)

/** A notice on screen with how many identical ones it stands for. */
data class VisibleNotice(val notice: SketchNotice, val count: Int)

/** What pauses a notice's countdown: the pointer over it, focus inside it, or a finger on it. */
enum class PauseCause { Hover, Focus, Touch }

/** The notice durations (UX-112, WCAG 2.2.1, 2.2.2, D-20) and the live-region rate, as the queue's constants. */
object NoticeTiming {
    /** A confirmation of an undoable edit stays at least this long. */
    const val CONFIRMATION_MS = 10_000L

    /** An Info notice with no action stays at least this long. */
    const val INFO_MS = 5_000L

    /** After a touch is released the countdown restarts no sooner than this (no hover on touch). */
    const val TOUCH_GRACE_MS = 3_000L

    /** At most one polite announcement per this interval. */
    const val POLITE_INTERVAL_MS = 1_000L

    /** How long a notice stays, or null when it stays until the user acts or dismisses it. */
    fun durationOf(notice: SketchNotice): Long? = when {
        notice.tier == BannerTier.Blocking || notice.tier == BannerTier.State -> null
        notice.action == null -> INFO_MS
        notice.confirmsUndoableEdit -> CONFIRMATION_MS
        else -> null
    }
}

/**
 * The single priority queue for transient notices, banner candidates and live-region announcements (Story 3.6.7, UX-91).
 * Pure and clock-free: every call that depends on time takes `now` in milliseconds from a source the host owns, so tests
 * run in virtual time.
 *
 * Order everywhere is Blocking, State, Warning, Info, Onboarding, then arrival. At most `SceneLimits.maxVisibleNotices`
 * notices are visible; the rest are a "+N more" count. Identical consecutive notices coalesce into one with a count.
 * Banner-shaped items share ONE slot: [bannerHead] is the lowest-ranked candidate (the rank of [BannerRowId]), and an
 * onboarding item reaches the slot only through [sequencer] and only when nothing above it is pending.
 */
class SketchNoticeQueue(
    private val limits: SceneLimits = SceneLimits.Default,
    val sequencer: OnboardingSequencer? = null,
    private val log: SketchLog? = null,
) {
    private class Active(val id: Long, val notice: SketchNotice, now: Long) {
        var count = 1
        var deadline: Long? = NoticeTiming.durationOf(notice)?.let { now + it }
        private val causes = HashSet<PauseCause>()
        private var remainingWhenFrozen = 0L
        private var touchedWhilePaused = false

        val paused: Boolean get() = causes.isNotEmpty()

        fun refresh(now: Long) {
            count += 1
            deadline = NoticeTiming.durationOf(notice)?.let { now + it }
        }

        /** Starts or ends a pause; the countdown restarts from the full duration, or from the touch grace after a touch. */
        fun pause(cause: PauseCause, on: Boolean, now: Long) {
            val duration = NoticeTiming.durationOf(notice) ?: return
            val wasPaused = paused
            if (on) causes += cause else causes -= cause
            if (cause == PauseCause.Touch && on) touchedWhilePaused = true
            when {
                !wasPaused && paused -> {
                    remainingWhenFrozen = ((deadline ?: now) - now).coerceAtLeast(0L)
                    deadline = null
                }
                wasPaused && !paused -> {
                    val wait = if (touchedWhilePaused) maxOf(NoticeTiming.TOUCH_GRACE_MS, remainingWhenFrozen) else duration
                    touchedWhilePaused = false
                    deadline = now + wait
                }
            }
        }
    }

    private val active = ArrayList<Active>()
    private val banners = LinkedHashMap<BannerRowId, BannerCandidate>()
    private val gone = ArrayList<SketchNotice>()
    private val liveRegion = LiveRegion()
    private var seq = 0L

    // ---- notices ---------------------------------------------------------------------------------------------------

    /** Shows [notice] now; an identical one right after it coalesces into it. */
    fun post(notice: SketchNotice, now: Long) {
        advance(now)
        val last = active.lastOrNull()
        if (last != null && last.notice == notice) {
            last.refresh(now)
            return
        }
        active += Active(seq++, notice, now)
        liveRegion.say(notice.tier, seq++, notice.text, null)
        log?.noticeShown(notice.key)
        syncYield()
    }

    /** The notices on screen, in priority order, at most `maxVisibleNotices` of them. */
    fun visible(now: Long): List<VisibleNotice> {
        advance(now)
        return active.sortedWith(compareBy({ it.notice.tier.ordinal }, { it.id })).take(limits.maxVisibleNotices).map { VisibleNotice(it.notice, it.count) }
    }

    /** How many notices are waiting behind the visible ones ("+N more"). */
    fun hiddenCount(now: Long): Int {
        advance(now)
        return (active.size - limits.maxVisibleNotices).coerceAtLeast(0)
    }

    /** Notices that carried an action and have gone, newest first; they stay readable here and are capped. */
    val diagnosticNotices: List<SketchNotice> get() = gone.asReversed().toList()

    /** The user acted on the notice (its action ran): it leaves without being retained. */
    fun act(key: String, now: Long) {
        advance(now)
        active.removeAll { it.notice.key == key }
        syncYield()
    }

    /** Dismisses [key]; a notice that carried an action stays in [diagnosticNotices]. */
    fun dismiss(key: String, now: Long) {
        advance(now)
        active.filter { it.notice.key == key }.forEach(::retire)
        syncYield()
    }

    /** Esc on the newest toast (Esc row 12). Returns whether one was dismissed. */
    fun dismissNewest(now: Long): Boolean {
        advance(now)
        val newest = active.lastOrNull() ?: return false
        retire(newest)
        syncYield()
        return true
    }

    /**
     * Starts or ends a pause of [key]'s countdown. A hover or focus pause restarts from the full duration when it ends; a touch
     * freezes it until released and for the grace period after.
     */
    fun setPause(key: String, cause: PauseCause, on: Boolean, now: Long) {
        advance(now)
        active.filter { it.notice.key == key }.forEach { it.pause(cause, on, now) }
    }

    private fun advance(now: Long) {
        val expired = active.filter { a -> !a.paused && a.deadline?.let { now >= it } == true }
        expired.forEach(::retire)
        if (expired.isNotEmpty()) syncYield()
    }

    private fun retire(a: Active) {
        active.remove(a)
        if (a.notice.action == null) return
        gone += a.notice
        while (gone.size > limits.maxDiagnosticsEntries) gone.removeAt(0)
    }

    // ---- banner slot -----------------------------------------------------------------------------------------------

    /** Offers a banner candidate; a newer one of the same row replaces it. Onboarding rows should come through [sequencer] instead. */
    fun submitBanner(candidate: BannerCandidate) {
        banners[candidate.row] = candidate
        liveRegion.say(candidate.tier, seq++, candidate.summary, "banner.${candidate.row.name}")
        log?.noticeShown(candidate.row.name)
        syncYield()
    }

    fun resolveBanner(row: BannerRowId) {
        banners.remove(row)
        syncYield()
    }

    /** The one banner on screen: the lowest-ranked candidate, else the visible onboarding item. */
    fun bannerHead(): BannerCandidate? = banners.values.minByOrNull { it.row.ordinal } ?: sequencer?.visibleCandidate()

    /** Banner candidates waiting for the slot, in rank order (they stay in the diagnostics list too). */
    fun waitingBanners(): List<BannerCandidate> = banners.values.sortedBy { it.row.ordinal }.drop(1)

    /** The sequencer yields while a Blocking, State or Warning notice or banner is pending. */
    private fun syncYield() {
        val above = active.any { it.notice.tier.ordinal < BannerTier.Info.ordinal } || banners.values.any { it.tier.ordinal < BannerTier.Info.ordinal }
        sequencer?.yielding = above
    }

    // ---- live region -----------------------------------------------------------------------------------------------

    /** A status message for the live region ("Undid: move rectangle"); a newer one with the same [streamKey] replaces it (last wins). */
    fun announceStatus(text: String, streamKey: String, tier: BannerTier = BannerTier.Info) = liveRegion.say(tier, seq++, text, streamKey)

    /**
     * The next thing to say, or null. One at a time, in priority order; a Blocking entry is assertive and not rate limited,
     * every other is polite and at most one per [NoticeTiming.POLITE_INTERVAL_MS].
     */
    fun nextAnnouncement(now: Long): Announcement? {
        sequencer?.takeAnnouncement()?.let { liveRegion.say(BannerTier.Onboarding, seq++, it, null) }
        return liveRegion.next(now)
    }

    /** True while something waits for the live region (a polite one may be held back by the rate limit); the host polls again then. */
    fun hasPendingAnnouncement(): Boolean = liveRegion.hasPending()
}

/** The live region's backlog: priority order, last-wins streams and the one-per-second polite rate. */
private class LiveRegion {
    private class Pending(val tier: BannerTier, val seq: Long, val text: String, val replaceKey: String?)

    private val pending = ArrayList<Pending>()
    private var lastPolite: Long? = null

    fun say(tier: BannerTier, seq: Long, text: String, replaceKey: String?) {
        if (replaceKey != null) pending.removeAll { it.replaceKey == replaceKey }
        pending += Pending(tier, seq, text, replaceKey)
    }

    fun hasPending(): Boolean = pending.isNotEmpty()

    fun next(now: Long): Announcement? {
        val best = pending.minWithOrNull(compareBy({ it.tier.ordinal }, { it.seq })) ?: return null
        if (best.tier == BannerTier.Blocking) {
            pending.remove(best)
            return Announcement(best.text, assertive = true)
        }
        val last = lastPolite
        if (last != null && now - last < NoticeTiming.POLITE_INTERVAL_MS) return null
        pending.remove(best)
        lastPolite = now
        return Announcement(best.text, assertive = false)
    }
}
