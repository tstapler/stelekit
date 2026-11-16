// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.sketch.v2.diagnostics.BannerCandidate
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.SketchLog

/**
 * Shows at most ONE onboarding item at a time, lowest banner rank first (UX-116, D-19). A priority class of
 * [SketchNoticeQueue]: the queue owns it, sets [yielding] while a Blocking, State or Warning notice is up (an onboarding
 * item then waits and is not counted as shown), and reads [visibleCandidate] for the banner slot.
 *
 * Counting follows [OnboardingPolicy]: an item is counted when it becomes visible, not when it is triggered. Per-sheet
 * retirement of the Upgrade chip is held here for the session only; the durable form of "Start" and "Keep this sketch
 * classic" is the sheet's own conversion mode (Story 1.4.6), which stops the host from triggering the chip again.
 *
 * Not thread-safe: the editor drives it from the main thread.
 */
class OnboardingSequencer(
    private val counters: OnboardingCounters,
    private val log: SketchLog? = null,
) {
    private val waiting = LinkedHashMap<OnboardingItem, BannerCandidate?>()
    private val hiddenForSession = HashSet<OnboardingItem>()
    private val retiredSheets = HashSet<String>()
    private var announce: String? = null

    /** The item on screen, or null. */
    var visible: OnboardingItem? = null
        private set

    private var visibleCandidate: BannerCandidate? = null
    private val sheetOf = HashMap<OnboardingItem, String?>()

    /** True while a higher-priority notice holds the banner slot: nothing new becomes visible. */
    var yielding: Boolean = false
        set(value) {
            field = value
            promote()
        }

    /** The banner candidate of the visible item; null for none, and for the empty state, which has no banner. */
    fun visibleCandidate(): BannerCandidate? = if (yielding) null else visibleCandidate

    /**
     * Offers [item] for display. Ignored when its policy row says it is retired, shown enough or hidden for the session.
     * [sheetKey] identifies the sheet for a per-sheet item. Returns whether the item is now waiting or visible.
     */
    fun trigger(item: OnboardingItem, candidate: BannerCandidate?, sheetKey: String? = null): Boolean {
        if (item == visible || item in waiting) return true
        if (!allowed(item, sheetKey)) return false
        waiting[item] = candidate
        sheetOf[item] = sheetKey
        promote()
        return true
    }

    /** The item went away in [how]; applies the retiring rules of its recurrence class and promotes the next waiting item. */
    fun dismiss(item: OnboardingItem, how: Dismissal) {
        if (item == visible) {
            visible = null
            visibleCandidate = null
        }
        waiting.remove(item)
        retire(item, how)
        promote()
    }

    /** The empty state shows only while nothing else does; it returns once the others are gone if the sheet is still empty. */
    fun emptyStateVisible(sheetIsEmpty: Boolean): Boolean = sheetIsEmpty && visible == null && waiting.isEmpty() && !yielding

    /** The text to announce for a newly visible item, once; null while nothing new appeared (nothing is announced under another item). */
    fun takeAnnouncement(): String? = announce.also { announce = null }

    /** A new session: "Not now" is forgotten, a visible item went away without being acted on. */
    fun newSession() {
        hiddenForSession.clear()
        retiredSheets.clear()
        visible?.let { dismiss(it, Dismissal.NewSession) }
    }

    private fun allowed(item: OnboardingItem, sheetKey: String?): Boolean {
        val rule = OnboardingPolicy.rule(item)
        val count = counters.read(item)
        return when (rule.recurrence) {
            Recurrence.Once, Recurrence.TwiceOrActed -> !count.retired && count.shown < (rule.maxShows ?: 0)
            Recurrence.UntilActed -> item !in hiddenForSession && (sheetKey == null || sheetKey !in retiredSheets)
            Recurrence.WhileEmpty -> false
        }
    }

    private fun retire(item: OnboardingItem, how: Dismissal) {
        when (OnboardingPolicy.rule(item).recurrence) {
            Recurrence.Once -> counters.retire(item)
            Recurrence.TwiceOrActed -> if (how == Dismissal.ActionButton || how == Dismissal.ChoseSetting) counters.retire(item)
            Recurrence.UntilActed -> retireUntilActed(item, how)
            Recurrence.WhileEmpty -> Unit
        }
    }

    private fun retireUntilActed(item: OnboardingItem, how: Dismissal) {
        when (how) {
            Dismissal.ActionButton -> sheetOf[item]?.let { retiredSheets += it }
            Dismissal.NotNow, Dismissal.Close, Dismissal.Esc -> hiddenForSession += item
            Dismissal.ChoseSetting, Dismissal.NavigateAway, Dismissal.NewSession -> Unit
        }
    }

    private fun promote() {
        if (visible != null || yielding) return
        val next = waiting.keys.minByOrNull { OnboardingPolicy.rank(it) ?: Int.MAX_VALUE } ?: return
        visibleCandidate = waiting.remove(next)
        visible = next
        counters.recordShown(next)
        announce = visibleCandidate?.summary
        log?.onboardingShown(next.name)
    }
}
