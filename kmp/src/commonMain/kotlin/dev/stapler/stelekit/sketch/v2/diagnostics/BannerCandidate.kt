// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2.diagnostics

import arrow.core.Either
import arrow.core.left
import arrow.core.right

enum class BannerTier { Blocking, State, Warning, Info, Onboarding }

/** The 14 rows of the banner table (ux.md chrome table), in rank order. [shortTitled] is false for the consent row. */
enum class BannerRowId(val tier: BannerTier, val shortTitled: Boolean = true) {
    SaveFailed(BannerTier.Blocking),
    SaveConflict(BannerTier.Blocking),
    ReadOnly(BannerTier.State),
    LeaseConflict(BannerTier.State),
    WarningNotice(BannerTier.Warning),
    PenDetected(BannerTier.Warning),
    LargeDrawing(BannerTier.Info),
    PostConversion(BannerTier.Info),
    EdgePanStopped(BannerTier.Onboarding),
    FingerHintOff(BannerTier.Onboarding),
    FingerHintOn(BannerTier.Onboarding),
    UpgradeChip(BannerTier.Onboarding, shortTitled = false),
    CoachMark(BannerTier.Onboarding),
    SingleKeyChip(BannerTier.Onboarding),
}

/** Where the FIXED strings of a row come from (string resources in `UI2`; the chrome fixture in tests). */
interface BannerStringSource {
    fun summary(row: BannerRowId): String

    /** Null for the consent row, whose short form is a sheet. */
    fun shortTitle(row: BannerRowId): String?

    fun primaryLabel(row: BannerRowId): String

    fun moreItems(row: BannerRowId): List<String>

    /** The accessible name of the primary button: the visible label plus its object (WCAG 2.5.3); the label itself by default. */
    fun primaryAccessibleName(row: BannerRowId): String = primaryLabel(row)
}

sealed interface BannerRejection {
    val row: BannerRowId

    /** The summary holds a `{placeholder}`: a banner row is fixed text, dynamic values go to details. */
    data class PlaceholderInSummary(override val row: BannerRowId) : BannerRejection

    /** [lines] is null when one word is wider than a line. */
    data class SummaryOverBudget(override val row: BannerRowId, val lines: Int?) : BannerRejection

    data class ShortTitleOverBudget(override val row: BannerRowId, val lines: Int?) : BannerRejection

    data class MissingShortTitle(override val row: BannerRowId) : BannerRejection

    data class LabelBreaksRule(override val row: BannerRowId) : BannerRejection
}

/**
 * One banner occupant: fixed strings (checked against [BannerTextBudget]) plus free-form [details], which is the
 * only place names and counts may appear. Built only by [of], so an over-budget row cannot exist.
 */
class BannerCandidate private constructor(
    val row: BannerRowId,
    val summary: String,
    val shortTitle: String?,
    val primaryLabel: String,
    val moreItems: List<String>,
    val details: String,
    val primaryAccessibleName: String = primaryLabel,
) {
    val tier: BannerTier get() = row.tier

    /** Assertive announcement only for an error that blocks work (UX-48). */
    val announcesAssertively: Boolean get() = tier == BannerTier.Blocking

    override fun equals(other: Any?): Boolean =
        other is BannerCandidate && row == other.row && summary == other.summary && details == other.details

    override fun hashCode(): Int = 31 * row.hashCode() + details.hashCode()

    override fun toString(): String = "BannerCandidate($row)"

    companion object {
        fun of(row: BannerRowId, strings: BannerStringSource, details: String = ""): Either<BannerRejection, BannerCandidate> {
            val summary = strings.summary(row)
            if ('{' in summary) return BannerRejection.PlaceholderInSummary(row).left()
            val summaryLines = BannerTextBudget.wrapLines(summary, BannerTextBudget.summaryCharsPerLine)
            if (summaryLines == null || summaryLines > BannerTextBudget.BANNER_LINES) {
                return BannerRejection.SummaryOverBudget(row, summaryLines).left()
            }
            val shortTitle = strings.shortTitle(row)
            if (row.shortTitled) {
                if (shortTitle == null) return BannerRejection.MissingShortTitle(row).left()
                val shortLines = BannerTextBudget.wrapLines(shortTitle, BannerTextBudget.shortTitleCharsPerLine)
                if (shortLines == null || shortLines > BannerTextBudget.BANNER_LINES) {
                    return BannerRejection.ShortTitleOverBudget(row, shortLines).left()
                }
            }
            val label = strings.primaryLabel(row)
            if (!labelFits(label)) return BannerRejection.LabelBreaksRule(row).left()
            return BannerCandidate(row, summary, shortTitle, label, strings.moreItems(row), details, strings.primaryAccessibleName(row)).right()
        }

        private fun labelFits(label: String): Boolean {
            val words = label.split(' ').filter { it.isNotEmpty() }
            return words.isNotEmpty() &&
                label.length <= BannerTextBudget.labelChars &&
                words.size <= BannerTextBudget.LABEL_WORDS &&
                words.all { it.length <= BannerTextBudget.labelWordChars }
        }
    }
}
