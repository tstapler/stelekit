// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2

import dev.stapler.stelekit.platform.Settings
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId

/** The onboarding items of ux.md section 16, in banner rank order; [row] is null for the empty state, which is not a banner. */
enum class OnboardingItem(val row: BannerRowId?) {
    EdgePanStopped(BannerRowId.EdgePanStopped),
    FingerHintOff(BannerRowId.FingerHintOff),
    FingerHintOn(BannerRowId.FingerHintOn),
    UpgradeChip(BannerRowId.UpgradeChip),
    CoachMark(BannerRowId.CoachMark),
    SingleKeyChip(BannerRowId.SingleKeyChip),
    EmptyState(null),
}

/** once: one showing ever; twice-or-acted: a second showing unless acted on; until-acted: returns until its own action; while-empty: a state, never counted. */
enum class Recurrence { Once, TwiceOrActed, UntilActed, WhileEmpty }

enum class CounterScope { PerDevice, PerSheet, None }

/** How an item went away; the recurrence class decides which of these retire it. */
enum class Dismissal {
    /** The banner's primary button, or an action in its More menu that settles the item (Start, Keep classic, Draw, Turn off). */
    ActionButton,

    /** The item's own setting was changed elsewhere (finger drawing turned on or off, single-key shortcuts turned on). */
    ChoseSetting,

    /** "Not now" on the Upgrade chip. */
    NotNow,
    Close,
    Esc,
    NavigateAway,
    NewSession,
}

/** One row of the recurrence table; [maxShows] is null for the while-empty state. */
data class OnboardingRule(val item: OnboardingItem, val recurrence: Recurrence, val maxShows: Int?, val scope: CounterScope)

/**
 * The ONE recurrence table (ux.md section 16, UX-122, D-19), as a constant. The banner rank of each row is read from
 * [BannerRowId], never restated here. `OnboardingPolicyTest` asserts these rows and that every [OnboardingItem] has one.
 */
object OnboardingPolicy {
    private const val TWICE = 2

    val rows: List<OnboardingRule> = listOf(
        OnboardingRule(OnboardingItem.EdgePanStopped, Recurrence.Once, 1, CounterScope.PerDevice),
        OnboardingRule(OnboardingItem.FingerHintOff, Recurrence.TwiceOrActed, TWICE, CounterScope.PerDevice),
        OnboardingRule(OnboardingItem.FingerHintOn, Recurrence.TwiceOrActed, TWICE, CounterScope.PerDevice),
        OnboardingRule(OnboardingItem.UpgradeChip, Recurrence.UntilActed, null, CounterScope.PerSheet),
        OnboardingRule(OnboardingItem.CoachMark, Recurrence.Once, 1, CounterScope.PerDevice),
        OnboardingRule(OnboardingItem.SingleKeyChip, Recurrence.Once, 1, CounterScope.PerDevice),
        OnboardingRule(OnboardingItem.EmptyState, Recurrence.WhileEmpty, null, CounterScope.None),
    )

    const val SETTING_PREFIX = "sketch.onboarding."

    fun rule(item: OnboardingItem): OnboardingRule = rows.first { it.item == item }

    /** The banner rank (1 wins) of [item], or null for the empty state. */
    fun rank(item: OnboardingItem): Int? = item.row?.let { it.ordinal + 1 }

    /** The one per-device `Settings` key of [item]. */
    fun key(item: OnboardingItem): String = SETTING_PREFIX + item.name
}

/** How often an item has been shown on this device and whether it is retired. */
data class OnboardingCount(val shown: Int, val retired: Boolean)

/**
 * The per-device counters, one `Settings` key per item (`sketch.onboarding.<item>`), so the stored set is bounded by the
 * item list and never pruned. Items that are not counted (the empty state, a per-sheet until-acted item) are never written.
 */
class OnboardingCounters(private val settings: Settings) {
    fun read(item: OnboardingItem): OnboardingCount {
        val parts = settings.getString(OnboardingPolicy.key(item), "").split(SEPARATOR)
        val shown = parts.getOrNull(SHOWN_AT)?.toIntOrNull() ?: 0
        return OnboardingCount(shown.coerceAtLeast(0), parts.getOrNull(RETIRED_AT) == RETIRED)
    }

    fun recordShown(item: OnboardingItem) {
        if (!counted(item)) return
        write(item, read(item).let { OnboardingCount(it.shown + 1, it.retired) })
    }

    fun retire(item: OnboardingItem) {
        if (!counted(item)) return
        write(item, read(item).copy(retired = true))
    }

    private fun counted(item: OnboardingItem): Boolean = OnboardingPolicy.rule(item).scope == CounterScope.PerDevice

    private fun write(item: OnboardingItem, count: OnboardingCount) {
        settings.putString(OnboardingPolicy.key(item), "${count.shown}$SEPARATOR${if (count.retired) RETIRED else LIVE}")
    }

    private companion object {
        const val SEPARATOR = ","
        const val RETIRED = "retired"
        const val LIVE = "live"
        const val SHOWN_AT = 0
        const val RETIRED_AT = 1
    }
}
