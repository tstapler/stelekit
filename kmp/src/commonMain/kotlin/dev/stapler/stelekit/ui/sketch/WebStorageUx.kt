// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.ui.sketch

import arrow.core.Either
import dev.stapler.stelekit.platform.PersistenceState
import dev.stapler.stelekit.platform.StorageReason
import dev.stapler.stelekit.platform.V2StorageStatus
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerCandidate
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRejection
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTextBudget
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTier
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveFailure
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.ui.sketch.v2.ChromeBudget
import dev.stapler.stelekit.ui.sketch.v2.ChromeRow
import dev.stapler.stelekit.ui.sketch.v2.SketchNotice
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConsentCopy
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchWebStorageStrings as Strings

/** What a web storage status shows (UX-141): nothing, the pending state, or the no-editor screen of one reason. */
sealed interface StorageNotice {
    /** The storage works; nothing to show. */
    data object Ready : StorageNotice

    /** `Checking` is a state, never a failure: the router shows the pending screen and the mode control disables its On options. */
    data class Pending(val text: String) : StorageNotice

    data class NoEditor(val reason: StorageReason, val text: String) : StorageNotice
}

/** Which Save failed banner row to raise: the generic one, or the quota variant (UX-144). */
enum class SaveFailedVariant { Generic, Quota }

/**
 * The pure web storage UX policy (Task 1.4.7l, ux.md 14.1 and 14.8): the status-to-notice and reason-to-text mapping, the
 * quota variant of Save failed and the durability wording of the four-row table. The rules of the `StillSaving` toast, the
 * cues and the Browser storage row are the sibling objects of this file. No composable and no clock: every text is a
 * [Strings] or [SketchConsentCopy] entry.
 */
object WebStorageUx {
    private const val BYTES_PER_MEGABYTE = 1024L * 1024L

    /** The banner details the saver raises for a full store; equal to [SaveFailure.Quota]'s log token. */
    val QUOTA_DETAIL: String = SaveFailure.Quota.token

    fun noticeOf(status: V2StorageStatus, iosWebKit: Boolean = false): StorageNotice = when (status) {
        V2StorageStatus.Checking -> StorageNotice.Pending(SketchReasonText.CHECKING_STORAGE)
        is V2StorageStatus.Ready -> StorageNotice.Ready
        is V2StorageStatus.Unavailable -> StorageNotice.NoEditor(status.reason, reasonText(status.reason, iosWebKit))
    }

    /** The text of one reason; the preload cap is named from [limits], so the sentence cannot drift from the cap. */
    fun reasonText(reason: StorageReason, iosWebKit: Boolean = false, limits: SceneLimits = SceneLimits.Default): String =
        if (reason == StorageReason.PreloadTooLarge) {
            Strings.preloadTooLarge(limits.maxPreloadBytes / BYTES_PER_MEGABYTE)
        } else {
            SketchReasonText.webStorage(reason, iosWebKit)
        }

    /** The variant of the `SaveFailed` row that [details] (what the saver raised it with) selects. */
    fun saveFailedVariant(details: String): SaveFailedVariant =
        if (details == QUOTA_DETAIL) SaveFailedVariant.Quota else SaveFailedVariant.Generic

    /** The Details of the quota variant, one step per line; the refusal sentence closes them when persistence was refused. */
    fun quotaDetails(persistence: PersistenceState): String {
        val lines = if (persistence == PersistenceState.Denied) {
            Strings.QuotaSaveFailed.detailLinesAfterRefusal
        } else {
            Strings.QuotaSaveFailed.detailLines
        }
        return lines.joinToString("\n")
    }

    /** The quota banner candidate; Left only if a string breaks the banner budget, which the unit test rules out. */
    fun quotaBanner(persistence: PersistenceState): Either<BannerRejection, BannerCandidate> =
        BannerCandidate.of(BannerRowId.SaveFailed, Strings.QuotaSaveFailed, quotaDetails(persistence))

    /** The first-write dialog line of [wording], with the refusal sentence appended once the browser refused persistence. */
    fun dialogLine(wording: DurabilityWording, persistence: PersistenceState): String =
        if (persistence == PersistenceState.Denied) "${wording.dialogLine} ${Strings.PERSISTENCE_REFUSED}" else wording.dialogLine

    /**
     * The one wording of the four-row table (UX-151): a temporary graph never reaches OPFS, so session-only wins over a
     * suspected private window; otherwise a suspected private window wins over the conservative line.
     */
    fun durabilityWording(sessionOnly: Boolean, privateSuspected: Boolean): DurabilityWording = when {
        sessionOnly -> DurabilityWording(
            DurabilityWordingId.SessionOnly,
            SketchConsentCopy.WebStorage.line(sessionOnly, privateSuspected),
            Strings.SaveStatus.NAME_SESSION_ONLY,
            CueForms(Strings.Cue.SESSION_FULL, Strings.Cue.SESSION_MID, Strings.Cue.SESSION_COMPACT, Strings.Cue.SESSION_CHIP),
        )
        privateSuspected -> DurabilityWording(
            DurabilityWordingId.PrivateWindow,
            SketchConsentCopy.WebStorage.line(sessionOnly, privateSuspected),
            Strings.SaveStatus.NAME_PRIVATE_WINDOW,
            CueForms(Strings.Cue.PRIVATE_FULL, Strings.Cue.PRIVATE_MID, Strings.Cue.PRIVATE_COMPACT, Strings.Cue.PRIVATE_CHIP),
        )
        else -> DurabilityWording(
            DurabilityWordingId.Conservative,
            SketchConsentCopy.WebStorage.line(sessionOnly, privateSuspected),
            Strings.SaveStatus.NAME_SAVED,
            cue = null,
        )
    }

    /** The visible label and the tooltip and accessible name of the save status (block `save-status-names`). */
    fun saveStatusName(state: WebSaveState, wording: DurabilityWording): SaveStatusName = when (state) {
        WebSaveState.Saved -> SaveStatusName(Strings.SaveStatus.LABEL_SAVED, wording.savedName)
        WebSaveState.Saving -> SaveStatusName(Strings.SaveStatus.LABEL_SAVING, Strings.SaveStatus.NAME_SAVING)
        WebSaveState.StillSaving -> SaveStatusName(Strings.SaveStatus.LABEL_SAVING, Strings.SaveStatus.NAME_STILL_SAVING)
    }
}

enum class DurabilityWordingId { SessionOnly, PrivateWindow, Conservative }

/** The four forms of a persistent cue, longest first; the chip form is the shortest (block `cue-forms`). */
class CueForms(val full: String, val mid: String, val compact: String, val chip: String) {
    /** The forms a one-line hint row may show; the chip form is for the chip row only. */
    val hintRowForms: List<String> get() = listOf(full, mid, compact)

    val allForms: List<String> get() = listOf(full, mid, compact, chip)
}

/** One row of the durability wording table: the dialog line, the Saved tooltip and accessible name, and the cue (null: none). */
class DurabilityWording(val id: DurabilityWordingId, val dialogLine: String, val savedName: String, val cue: CueForms?)

/** The save states of the top bar on the web build, where `StillSaving` is `Saving` after 3 s without `Persisted`. */
enum class WebSaveState { Saved, Saving, StillSaving }

class SaveStatusName(val visibleLabel: String, val accessibleName: String)

/**
 * The timing contract of the `StillSaving` toast (UX-145, UX-150, block `still-saving`), as a pure plan over one write's
 * timeline in milliseconds from the start of the write. The queue and the saver apply it; this object has no clock.
 */
object StillSavingTiming {
    const val SHOWN_AFTER_MS = 3_000L
    const val MIN_VISIBLE_MS = 1_000L
    const val ANNOUNCE_AT_MS = SHOWN_AFTER_MS + MIN_VISIBLE_MS

    /** The chip is not a live region: the toast is the one announcement source, so one event is never announced twice. */
    const val CHIP_IS_LIVE_REGION = false

    /** Whether another toast holds the toast lane when the `StillSaving` toast is due. */
    enum class Lane { Free, Occupied }

    /**
     * What happens to one write. [toastShownAtMs] and [toastRemovedAtMs] are null when no toast was visible (the write
     * persisted first, or the lane was occupied: a `StillSaving` toast never displaces another one); [announcedAtMs] is
     * null for a write that persisted before the announcement time; the diagnostics-list entry lives from
     * [SHOWN_AFTER_MS] to [listEntryRemovedAtMs] (null: still pending).
     */
    class Plan(
        val toastShownAtMs: Long?,
        val toastRemovedAtMs: Long?,
        val announcedAtMs: Long?,
        val listEntryAtMs: Long?,
        val listEntryRemovedAtMs: Long?,
    ) {
        val toastVisibleMs: Long? get() = if (toastShownAtMs != null && toastRemovedAtMs != null) toastRemovedAtMs - toastShownAtMs else null
    }

    /**
     * [persistedAtMs] is when the write reached `Persisted` (null: not yet); [lane] is whether another toast holds the
     * lane at [SHOWN_AFTER_MS]; [displacedAtMs] is when a newer toast displaced a shown `StillSaving` (null: never).
     */
    fun plan(persistedAtMs: Long?, lane: Lane, displacedAtMs: Long? = null): Plan {
        if (persistedAtMs != null && persistedAtMs < SHOWN_AFTER_MS) return Plan(null, null, null, null, null)
        val shown = if (lane == Lane.Occupied) null else SHOWN_AFTER_MS
        val atLeastVisible = persistedAtMs?.let { maxOf(it, SHOWN_AFTER_MS + MIN_VISIBLE_MS) }
        val removed = if (shown == null) null else listOfNotNull(atLeastVisible, displacedAtMs).minOrNull()
        val announced = if (persistedAtMs == null || persistedAtMs >= ANNOUNCE_AT_MS) ANNOUNCE_AT_MS else null
        return Plan(shown, removed, announced, SHOWN_AFTER_MS, persistedAtMs)
    }

    /** The toast: a fixed summary, no primary action, Details and Dismiss under More (the toast table). */
    fun notice(): SketchNotice = SketchNotice(
        key = Strings.StillSaving.KEY,
        tier = BannerTier.Info,
        text = Strings.StillSaving.SUMMARY,
        shortTitle = Strings.StillSaving.SHORT_TITLE,
        details = Strings.StillSaving.DETAILS,
    )
}

/** Where a cue is shown: on the hint row, or, in a window that collapses the hint row, as the first chip of the chip row. */
enum class CuePlacement { HintRow, ChipRow }

enum class CueForm { Full, Mid, Compact, Chip }

/** The cue chip at one window: the longest form that fits, and whether even the chip form fits ([fits] false fails the layout script). */
class CueChip(val form: CueForm, val text: String, val fits: Boolean)

/** The hint line built left to right (prefix, cue, hint text); [prefix] and [hint] are null when they yielded. */
class HintLine(val prefix: String?, val cue: String, val hint: String?) {
    val text: String get() = listOfNotNull(prefix, cue, hint).joinToString(" ")
}

/**
 * The persistent cue of a suspected private window or a temporary graph (UX-153, D-151, D-155): four forms of decreasing
 * length, the one-line sharing rule of the hint row, and the form chosen per window for the chip row.
 */
object DurabilityCue {
    /** The `chipPad` figure of the chrome table: horizontal padding of one chip. */
    const val CHIP_PAD_DP = 11

    /** The hint row when the window keeps it shown, so the cue joins its line; otherwise the chip row (UX-153). */
    fun placementFor(widthDp: Int, heightDp: Int, fontScale: Double): CuePlacement {
        val plan = ChromeBudget.plan(widthDp, heightDp, fontScale)
        val shown = ChromeRow.HintStatus in ChromeBudget.presentRows(plan.layout) && ChromeRow.HintStatus !in plan.collapsed
        return if (shown) CuePlacement.HintRow else CuePlacement.ChipRow
    }

    /** Width of the chip row in a window: the overlay width less the mic button and, when the chips sit beside it, the view cluster. */
    fun chipRowDp(widthDp: Int, heightDp: Int): Int {
        val layout = ChromeBudget.layout(widthDp, heightDp)
        val strips = ChromeBudget.edgeStrips(widthDp)
        val overlayWidth = ChromeBudget.canvasWidthDp(layout, widthDp) -
            (ChromeBudget.GESTURE_INSET_DP + ChromeBudget.TOOL_TARGET_DP) * strips
        val overlay = ChromeBudget.overlay(layout, widthDp)
        val micAndGap = ChromeBudget.TOOL_TARGET_DP + ChromeBudget.CLUSTER_GAP_DP
        if (overlay.chipsAbove) return overlayWidth - micAndGap
        return overlayWidth - ChromeBudget.clusterWidthDp(overlay.clusterRows) - micAndGap - ChromeBudget.CLUSTER_GAP_DP
    }

    /** Characters of chrome text that fit [widthDp] at [fontScale] (chrome text is held at the largest chrome scale). */
    fun charsThatFit(widthDp: Int, fontScale: Double): Int =
        ((widthDp - CHIP_PAD_DP) / (BannerTextBudget.CHAR_DP * minOf(fontScale, ChromeBudget.MAX_CHROME_SCALE))).toInt()

    /** The longest form of [cue] that fits a chip row [chipRowDp] wide; [CueChip.fits] is false when even the chip form does not. */
    fun chipFor(cue: CueForms, chipRowDp: Int, fontScale: Double): CueChip {
        val chars = charsThatFit(chipRowDp, fontScale)
        val forms = CueForm.entries.zip(cue.allForms)
        val chosen = forms.firstOrNull { (_, text) -> text.length <= chars } ?: forms.last()
        return CueChip(chosen.first, chosen.second, chosen.second.length <= chars)
    }

    /**
     * The hint line of [charsPerLine] characters: the cue shows the longest of its three forms that fits and is never cut;
     * the prefix leaves when it and the cue do not fit one line together, and the hint text yields first.
     */
    fun hintLine(cue: CueForms, prefix: String?, hint: String?, charsPerLine: Int): HintLine {
        val form = cue.hintRowForms.firstOrNull { it.length <= charsPerLine } ?: cue.compact
        val shownPrefix = prefix?.takeIf { it.length + 1 + form.length <= charsPerLine }
        val head = listOfNotNull(shownPrefix, form).joinToString(" ")
        val shownHint = hint?.takeIf { head.length + 1 + it.length <= charsPerLine }
        return HintLine(shownPrefix, form, shownHint)
    }
}

/** What Settings > Sketches > Browser storage shows (block `storage-row`, UX-147); a refusal changes no availability. */
class BrowserStorageRow(val label: String, val statusLine: String?, val actionLabel: String?)

object BrowserStorageUx {
    /** The row of a [PersistenceState] at rest. */
    fun row(state: PersistenceState): BrowserStorageRow = when (state) {
        PersistenceState.Granted -> BrowserStorageRow(Strings.StorageRow.KEPT, null, null)
        PersistenceState.NotRequested -> BrowserStorageRow(Strings.StorageRow.NOT_GUARANTEED, null, Strings.StorageRow.ASK)
        PersistenceState.Denied -> BrowserStorageRow(Strings.StorageRow.NOT_GUARANTEED, Strings.StorageRow.REFUSED, Strings.StorageRow.ASK)
        PersistenceState.Unsupported -> BrowserStorageRow(Strings.StorageRow.NOT_GUARANTEED, Strings.StorageRow.UNSUPPORTED, null)
    }

    /** The row while the click's request is in flight: a status line, no action, and a polite announcement of the line. */
    fun askingRow(): BrowserStorageRow = BrowserStorageRow(Strings.StorageRow.NOT_GUARANTEED, Strings.StorageRow.ASKING, null)
}
