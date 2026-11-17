// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch.v2.migration

import dev.stapler.stelekit.sketch.v2.diagnostics.BannerCandidate
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTextBudget
import dev.stapler.stelekit.sketch.v2.runtime.convert.HasTechnicalData
import dev.stapler.stelekit.sketch.v2.runtime.session.ProjectAckStore
import dev.stapler.stelekit.ui.sketch.UpgradeAffordance
import dev.stapler.stelekit.ui.sketch.v2.Dismissal
import dev.stapler.stelekit.ui.sketch.v2.OnboardingItem
import dev.stapler.stelekit.ui.sketch.v2.OnboardingSequencer
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConsentCopy
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchMigrationStrings

/**
 * Row versus sheet for the consent row (Task 1.4.6i, U5): the sheet replaces the Upgrade chip row whenever its summary,
 * word-wrapped at the window's width and font scale, takes more than the row's 2 lines. At runtime the host measures the
 * real text ([sheetReplacesRow]); the greedy model of `check-plan.py` ([modelSheetReplacesRow]) is the conservative oracle.
 */
object UpgradeRowFit {
    fun modelLines(widthDp: Int, fontScale: Double): Int? =
        BannerTextBudget.wrapLines(SketchMigrationStrings.Banners.UPGRADE_SUMMARY, BannerTextBudget.charsPerLine(widthDp, fontScale))

    fun modelSheetReplacesRow(widthDp: Int, fontScale: Double): Boolean = sheetReplacesRow(modelLines(widthDp, fontScale))

    /** [measuredLines] is null when a word is wider than the line. */
    fun sheetReplacesRow(measuredLines: Int?): Boolean = measuredLines == null || measuredLines > BannerTextBudget.BANNER_LINES
}

/** What [UpgradeOfferController.start] decided. */
sealed interface StartDecision {
    /** The refusal appeared after the chip was shown (a dimension was added): nothing converts, the chip is withdrawn. */
    data class Withdrawn(val reason: HasTechnicalData) : StartDecision

    /** The project has no acknowledgement on this device: show the first-write dialog before any conversion. */
    data object NeedsFirstWriteDialog : StartDecision

    /** The project was acknowledged: convert with no modal (the consequence stays on the chip and the Details of the banner). */
    data object ConvertNow : StartDecision
}

/**
 * The classic editor's thin adapter over the onboarding sequencer for the Upgrade chip (Task 1.4.6g): the router decides
 * the [UpgradeAffordance]; this offers the chip, and turns [Start], Not now and Keep classic into their effects.
 * Opening a sketch never converts and writes nothing here except [keepClassic].
 */
class UpgradeOfferController(
    private val sequencer: OnboardingSequencer,
    private val acks: ProjectAckStore,
) {
    /** Offers the chip for an [UpgradeAffordance.Chip] affordance and withdraws it otherwise (a disabled entry has no chip). */
    fun offer(affordance: UpgradeAffordance, sheetKey: String) {
        if (affordance != UpgradeAffordance.Chip) {
            sequencer.dismiss(OnboardingItem.UpgradeChip, Dismissal.NavigateAway)
            return
        }
        val candidate = BannerCandidate.of(BannerRowId.UpgradeChip, SketchMigrationStrings.Banners, SketchConsentCopy.CHIP_DETAILS).getOrNull()
        sequencer.trigger(OnboardingItem.UpgradeChip, candidate, sheetKey)
    }

    /** [Start]: the refusal is re-read first, then the acknowledgement decides between the dialog and an immediate conversion. */
    suspend fun start(slug: String, refusal: () -> HasTechnicalData?): StartDecision {
        refusal()?.let {
            sequencer.dismiss(OnboardingItem.UpgradeChip, Dismissal.NavigateAway)
            return StartDecision.Withdrawn(it)
        }
        sequencer.dismiss(OnboardingItem.UpgradeChip, Dismissal.ActionButton)
        return if (acks.isAcknowledged(slug)) StartDecision.ConvertNow else StartDecision.NeedsFirstWriteDialog
    }

    /** [Not now]: gone for this session for every sketch of the project; the sheet stays `NotConverted`. */
    fun notNow() = sequencer.dismiss(OnboardingItem.UpgradeChip, Dismissal.NotNow)

    /** "Keep this sketch classic": remembered per sheet; the sheet menu "Upgrade to the new editor" lifts it. */
    fun keepClassic(sheetUuid: String) {
        acks.decline(sheetUuid)
        sequencer.dismiss(OnboardingItem.UpgradeChip, Dismissal.ActionButton)
    }
}

/** What forbids the consent sheet from opening now (UX-126): a gesture, text entry or another modal. */
data class InteractionState(val midGesture: Boolean = false, val textEntry: Boolean = false, val modalOpen: Boolean = false) {
    val busy: Boolean get() = midGesture || textEntry || modalOpen
}

/**
 * The open rule of the consent sheet: once per sketch project per day on this device, never while the user is busy, and
 * never again in a session in which it was dismissed for that project. All five dismissals mean Not now.
 */
class UpgradeConsentGate(private val acks: ProjectAckStore, private val epochDay: () -> Long) {
    private val dismissedThisSession = HashSet<String>()

    fun mayOpen(slug: String, state: InteractionState): Boolean =
        !state.busy && slug !in dismissedThisSession && acks.consentSheetDay(slug) != epochDay()

    /** Records the open; the day is persisted so a later session the same day does not open it again. */
    fun opened(slug: String) = acks.recordConsentSheetDay(slug, epochDay())

    fun dismissed(slug: String) {
        dismissedThisSession += slug
    }

    fun newSession() = dismissedThisSession.clear()
}
