// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.platform.PersistenceState
import dev.stapler.stelekit.platform.SketchSaveOutcome
import dev.stapler.stelekit.platform.StorageReason
import dev.stapler.stelekit.platform.V2StorageStatus
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTier
import dev.stapler.stelekit.sketch.v2.runtime.persist.SaveFailure
import dev.stapler.stelekit.sketch.v2.scene.SceneLimits
import dev.stapler.stelekit.ui.sketch.BrowserStorageUx
import dev.stapler.stelekit.ui.sketch.CueForm
import dev.stapler.stelekit.ui.sketch.CuePlacement
import dev.stapler.stelekit.ui.sketch.DurabilityCue
import dev.stapler.stelekit.ui.sketch.DurabilityWordingId
import dev.stapler.stelekit.ui.sketch.SaveFailedVariant
import dev.stapler.stelekit.ui.sketch.SketchReasonText
import dev.stapler.stelekit.ui.sketch.StillSavingTiming
import dev.stapler.stelekit.ui.sketch.StillSavingTiming.Lane
import dev.stapler.stelekit.ui.sketch.StorageNotice
import dev.stapler.stelekit.ui.sketch.WebSaveState
import dev.stapler.stelekit.ui.sketch.WebStorageUx
import dev.stapler.stelekit.ui.sketch.v2.ImportExportNotices
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConsentCopy
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchStrings
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchWebStorageStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Task 1.4.7l: the pure web storage UX policy against ux.md 14.1 and 14.8 (UX-139 to UX-153). */
class WebStorageUxTest {
    private val table = ChromeTableParser.parseFixture()

    private val ready = V2StorageStatus.Ready(sessionOnly = false, privateSuspected = false)

    // ---- status to notice and reason to text -------------------------------------------------------------------------

    @Test
    fun everyUnavailableReason_should_HaveItsOwnText() {
        for (reason in StorageReason.entries) {
            val notice = assertIs<StorageNotice.NoEditor>(WebStorageUx.noticeOf(V2StorageStatus.Unavailable(reason)))
            assertEquals(reason, notice.reason)
            assertTrue(notice.text.isNotBlank(), "$reason has no text")
            assertEquals(SketchReasonText.webStorage(reason, iosWebKit = false), notice.text, "$reason text drifted from the reason table")
        }
        val distinct = StorageReason.entries.map { WebStorageUx.reasonText(it) }.toSet()
        assertEquals(StorageReason.entries.size - 2, distinct.size, "only the three unsafe-browser reasons share a text (ux.md 14.1)")
    }

    @Test
    fun noOpfsOnIosWebKit_should_NameSafariEngine_AndNoBrowser() {
        val ios = WebStorageUx.reasonText(StorageReason.NoOpfs, iosWebKit = true)
        val other = WebStorageUx.reasonText(StorageReason.NoOpfs, iosWebKit = false)
        assertTrue("Safari's engine" in ios)
        assertTrue(listOf("Chrome", "Firefox", "Edge").none { it in ios || it in other }, "a reason names no browser it cannot know")
        assertFalse("Safari" in other)
    }

    @Test
    fun checking_should_NeverMapToTheNoEditorScreen() {
        val notice = WebStorageUx.noticeOf(V2StorageStatus.Checking)
        assertEquals(StorageNotice.Pending(SketchReasonText.CHECKING_STORAGE), notice)
        assertTrue(notice !is StorageNotice.NoEditor)
    }

    @Test
    fun persistDenied_should_ChangeNoAvailability() {
        val before = WebStorageUx.noticeOf(ready)
        for (state in PersistenceState.entries) {
            assertEquals(before, WebStorageUx.noticeOf(ready), "persistence $state never changes availability")
            assertEquals(StorageNotice.Ready, WebStorageUx.noticeOf(ready.copy(sessionOnly = true)))
        }
    }

    @Test
    fun preloadTooLargeText_should_NameTheCap() {
        val megabytes = SceneLimits.Default.maxPreloadBytes / BYTES_PER_MEGABYTE
        val text = WebStorageUx.reasonText(StorageReason.PreloadTooLarge)
        assertTrue("over $megabytes MB" in text, text)
        assertEquals(SketchReasonText.webStorage(StorageReason.PreloadTooLarge, false), text, "the two copies of the sentence agree")
        val lowered = WebStorageUx.reasonText(StorageReason.PreloadTooLarge, limits = SceneLimits.Default.copy(maxPreloadBytes = 64 * BYTES_PER_MEGABYTE))
        assertTrue("over 64 MB" in lowered, lowered)
    }

    // ---- the quota variant -------------------------------------------------------------------------------------------

    @Test
    fun quotaVariantStrings_should_FitTheBannerBudgetOfTheChromeFixture() {
        for (persistence in PersistenceState.entries) {
            val candidate = assertNotNull(WebStorageUx.quotaBanner(persistence).getOrNull())
            assertEquals(BannerRowId.SaveFailed, candidate.row)
            assertEquals(BannerTier.Blocking, candidate.tier)
            assertEquals("Storage full. Keep tab open.", candidate.summary)
            assertEquals("Storage full", candidate.shortTitle)
            assertEquals("Save copy", candidate.primaryLabel)
            assertEquals(listOf("Retry", "Details"), candidate.moreItems)
            assertEquals("Save copy of this sketch", candidate.primaryAccessibleName)
            assertTrue(candidate.announcesAssertively)
        }
        val base = table.banners.single { it.id == "SaveFailed" }
        assertEquals(base.tier, "Blocking", "the variant keeps the rank and tier of the base row")
    }

    @Test
    fun saveFailedSummary_should_NotClaimTheWorkIsKeptAfterTheTabCloses() {
        val base = table.banners.single { it.id == "SaveFailed" }.summary
        for (summary in listOf(base, SketchWebStorageStrings.QuotaSaveFailed.SUMMARY)) {
            assertTrue("Keep tab open" in summary, summary)
            assertTrue(listOf("saved", "kept", "safe").none { it in summary.lowercase() }, summary)
        }
        val details = WebStorageUx.quotaDetails(PersistenceState.NotRequested)
        assertTrue("stays safe only while the tab stays open" in details)
    }

    @Test
    fun quotaDetails_should_ListTheStepsInOrder_AndEndWithTheRefusalOnlyAfterADenial() {
        val plain = WebStorageUx.quotaDetails(PersistenceState.NotRequested).lines()
        assertEquals(4, plain.size)
        assertTrue(plain[1].startsWith("1.") && plain[2].startsWith("2.") && plain[3].startsWith("3."))
        val denied = WebStorageUx.quotaDetails(PersistenceState.Denied).lines()
        assertEquals(plain + SketchWebStorageStrings.PERSISTENCE_REFUSED, denied)
    }

    @Test
    fun saveFailedVariant_should_FollowTheDetailsTheSaverRaises() {
        assertEquals("quota", SaveFailure.Quota.token)
        assertEquals(SaveFailedVariant.Quota, WebStorageUx.saveFailedVariant(SaveFailure.Quota.token))
        assertEquals(SaveFailedVariant.Generic, WebStorageUx.saveFailedVariant(""))
    }

    // ---- durability wording ------------------------------------------------------------------------------------------

    @Test
    fun durabilityWording_should_ReturnTheWinnerOfTheFourCombinations() {
        val expected = listOf(
            Triple(true, true, DurabilityWordingId.SessionOnly),
            Triple(true, false, DurabilityWordingId.SessionOnly),
            Triple(false, true, DurabilityWordingId.PrivateWindow),
            Triple(false, false, DurabilityWordingId.Conservative),
        )
        for ((session, private, id) in expected) {
            val wording = WebStorageUx.durabilityWording(session, private)
            assertEquals(id, wording.id, "sessionOnly=$session privateSuspected=$private")
            assertEquals(SketchConsentCopy.WebStorage.line(session, private), wording.dialogLine)
        }
        assertEquals("Saved", WebStorageUx.durabilityWording(false, false).savedName)
        assertNull(WebStorageUx.durabilityWording(false, false).cue)
    }

    @Test
    fun temporaryAndPrivate_should_GetTheSessionOnlyLine() {
        val wording = WebStorageUx.durabilityWording(sessionOnly = true, privateSuspected = true)
        assertEquals(SketchConsentCopy.WebStorage.SESSION_ONLY, wording.dialogLine)
        assertEquals("Saved for this session only", wording.savedName)
        assertEquals("Temporary graph: lost on close.", wording.cue?.full)
    }

    @Test
    fun privateSuspected_should_UseThePrivateWindowLineAndSavedTooltip() {
        val wording = WebStorageUx.durabilityWording(sessionOnly = false, privateSuspected = true)
        assertEquals(SketchConsentCopy.WebStorage.PRIVATE_WINDOW, wording.dialogLine)
        assertEquals("Saved in this window. A private window may clear it when it closes.", wording.savedName)
        assertEquals("Saved in this window. A private window may clear it when it closes.", WebStorageUx.saveStatusName(WebSaveState.Saved, wording).accessibleName)
    }

    @Test
    fun privateSuspected_should_ShowTheHintRowCue() {
        val cue = assertNotNull(WebStorageUx.durabilityWording(false, true).cue)
        assertEquals(listOf("Private window: may be cleared.", "Private window: may clear.", "Private: may clear.", "Private"), cue.allForms)
        val session = assertNotNull(WebStorageUx.durabilityWording(true, false).cue)
        assertEquals(listOf("Temporary graph: lost on close.", "Temporary: lost on close.", "Temporary: not kept.", "Temporary"), session.allForms)
        for (forms in listOf(cue.allForms, session.allForms)) {
            assertEquals(forms.sortedByDescending { it.length }, forms, "each form is shorter than the one before")
        }
    }

    @Test
    fun saveStatusNames_should_MatchTheFiveRowsOfTheTable() {
        fun name(state: WebSaveState, session: Boolean, private: Boolean) =
            WebStorageUx.saveStatusName(state, WebStorageUx.durabilityWording(session, private))
        assertEquals("Saved" to "Saved", name(WebSaveState.Saved, false, false).let { it.visibleLabel to it.accessibleName })
        assertEquals("Saving" to "Saving...", name(WebSaveState.Saving, false, false).let { it.visibleLabel to it.accessibleName })
        assertEquals(
            "Saving" to "Saving... Still saving to this browser.",
            name(WebSaveState.StillSaving, false, false).let { it.visibleLabel to it.accessibleName },
        )
        assertEquals("Saved" to "Saved for this session only", name(WebSaveState.Saved, true, false).let { it.visibleLabel to it.accessibleName })
        assertEquals(
            "Saved" to "Saved in this window. A private window may clear it when it closes.",
            name(WebSaveState.Saved, false, true).let { it.visibleLabel to it.accessibleName },
        )
        for (state in WebSaveState.entries) {
            val n = name(state, false, false)
            assertTrue(n.accessibleName.startsWith(n.visibleLabel), "the visible label comes first in the name (WCAG 2.5.3)")
        }
    }

    @Test
    fun refusedPersistence_should_ShowTheInlineLine() {
        val denied = BrowserStorageUx.row(PersistenceState.Denied)
        assertEquals("Not guaranteed", denied.label)
        assertEquals(SketchWebStorageStrings.StorageRow.REFUSED, denied.statusLine)
        assertEquals("Ask the browser to keep it", denied.actionLabel)
        val line = WebStorageUx.dialogLine(WebStorageUx.durabilityWording(false, false), PersistenceState.Denied)
        assertTrue(line.endsWith("Your browser has not promised to keep this site's storage."), line)
        assertEquals(
            WebStorageUx.durabilityWording(false, false).dialogLine,
            WebStorageUx.dialogLine(WebStorageUx.durabilityWording(false, false), PersistenceState.NotRequested),
        )
    }

    @Test
    fun browserStorageRow_should_FollowTheFiveRowsOfTheTable() {
        val kept = BrowserStorageUx.row(PersistenceState.Granted)
        assertEquals(Triple("Kept", null, null), Triple(kept.label, kept.statusLine, kept.actionLabel))
        val idle = BrowserStorageUx.row(PersistenceState.NotRequested)
        assertEquals(Triple("Not guaranteed", null, "Ask the browser to keep it"), Triple(idle.label, idle.statusLine, idle.actionLabel))
        val asking = BrowserStorageUx.askingRow()
        assertEquals(Triple("Not guaranteed", "Asking the browser...", null), Triple(asking.label, asking.statusLine, asking.actionLabel))
        val unsupported = BrowserStorageUx.row(PersistenceState.Unsupported)
        assertEquals(Triple("Not guaranteed", "This browser can't promise to keep site storage.", null), Triple(unsupported.label, unsupported.statusLine, unsupported.actionLabel))
    }

    @Test
    fun exportSavedOnWeb_should_HaveNoOpenAction() {
        val notice = ImportExportNotices.exportDownloaded(SketchSaveOutcome.Saved("a.svg", null, null))
        assertNull(notice.action)
        assertEquals("ExportSavedWeb", notice.key)
    }

    // ---- the StillSaving contract ------------------------------------------------------------------------------------

    @Test
    fun stillSaving_should_BeVisibleAtLeastOneSecond_WhenTheWriteCompletesAt3Point2() {
        val plan = StillSavingTiming.plan(persistedAtMs = 3_200L, lane = Lane.Free)
        assertEquals(3_000L, plan.toastShownAtMs)
        assertEquals(4_000L, plan.toastRemovedAtMs)
        assertTrue(assertNotNull(plan.toastVisibleMs) >= StillSavingTiming.MIN_VISIBLE_MS)
    }

    @Test
    fun stillSaving_should_AnnounceOnce_AndNotWhenPersistedFirst() {
        val completionTimes = listOf(2_999L, 3_000L, 3_200L, 3_999L, 4_000L, 6_000L, null)
        val announced = completionTimes.map { StillSavingTiming.plan(it, Lane.Free).announcedAtMs }
        assertEquals(listOf(null, null, null, null, 4_000L, 4_000L, 4_000L), announced)
        val shown = completionTimes.map { StillSavingTiming.plan(it, Lane.Free).toastShownAtMs }
        assertEquals(listOf(null, 3_000L, 3_000L, 3_000L, 3_000L, 3_000L, 3_000L), shown)
        for (time in completionTimes.filterNotNull().filter { it >= StillSavingTiming.SHOWN_AFTER_MS }) {
            val visible = assertNotNull(StillSavingTiming.plan(time, Lane.Free).toastVisibleMs)
            assertTrue(visible >= StillSavingTiming.MIN_VISIBLE_MS, "persisted at $time was visible for $visible ms")
        }
    }

    @Test
    fun stillSaving_should_NotDisplaceAnotherToast() {
        val plan = StillSavingTiming.plan(persistedAtMs = null, lane = Lane.Occupied)
        assertNull(plan.toastShownAtMs)
        assertNull(plan.toastRemovedAtMs)
        assertEquals(StillSavingTiming.SHOWN_AFTER_MS, plan.listEntryAtMs, "the diagnostics-list entry and the status still carry it")
    }

    @Test
    fun stillSaving_should_AnnounceAtFourSeconds_EvenWhenTheLaneIsOccupied() {
        assertEquals(4_000L, StillSavingTiming.ANNOUNCE_AT_MS)
        assertEquals(4_000L, StillSavingTiming.plan(null, Lane.Occupied).announcedAtMs)
        assertEquals(StillSavingTiming.plan(null, Lane.Free).announcedAtMs, StillSavingTiming.plan(null, Lane.Occupied).announcedAtMs)
        assertNull(StillSavingTiming.plan(3_500L, Lane.Occupied).announcedAtMs, "a write that persisted first is silent in either lane state")
    }

    @Test
    fun stillSaving_should_BeRemovedFromTheListAtPersisted() {
        val plan = StillSavingTiming.plan(persistedAtMs = 7_500L, lane = Lane.Free)
        assertEquals(7_500L, plan.listEntryRemovedAtMs)
        assertEquals(7_500L, plan.toastRemovedAtMs)
        assertNull(StillSavingTiming.plan(persistedAtMs = null, lane = Lane.Free).listEntryRemovedAtMs)
    }

    @Test
    fun stillSaving_should_YieldToANewerToast() {
        val plan = StillSavingTiming.plan(persistedAtMs = null, lane = Lane.Free, displacedAtMs = 3_600L)
        assertEquals(3_600L, plan.toastRemovedAtMs)
        assertNull(plan.listEntryRemovedAtMs, "the list entry stays until Persisted")
        assertEquals(4_000L, plan.announcedAtMs)
    }

    @Test
    fun chip_should_NotBeALiveRegion() {
        assertFalse(StillSavingTiming.CHIP_IS_LIVE_REGION)
    }

    @Test
    fun stillSavingNotice_should_MatchTheToastRowOfTheChromeFixture() {
        val row = table.toasts.single { it.id == "StillSaving" }
        val notice = StillSavingTiming.notice()
        assertEquals(row.id, notice.key)
        assertEquals(row.summary, notice.text)
        assertEquals(row.shortTitle, notice.shortTitle)
        assertNull(row.primaryLabel)
        assertNull(notice.action)
        assertEquals(listOf("Details", "Dismiss"), row.moreItems)
        assertNotNull(notice.details)
        assertEquals(BannerTier.Info, notice.tier)
    }

    // ---- the cue -----------------------------------------------------------------------------------------------------

    private val privateCue get() = assertNotNull(WebStorageUx.durabilityWording(false, true).cue)
    private val sessionCue get() = assertNotNull(WebStorageUx.durabilityWording(true, false).cue)

    @Test
    fun hintCue_should_PickTheLongestFormThatFitsTheLine() {
        val cue = privateCue
        assertEquals(cue.full, DurabilityCue.hintLine(cue, null, null, charsPerLine = cue.full.length).cue)
        assertEquals(cue.mid, DurabilityCue.hintLine(cue, null, null, charsPerLine = cue.full.length - 1).cue)
        assertEquals(cue.mid, DurabilityCue.hintLine(cue, null, null, charsPerLine = cue.mid.length).cue)
        assertEquals(cue.compact, DurabilityCue.hintLine(cue, null, null, charsPerLine = cue.mid.length - 1).cue)
        assertEquals(cue.compact, DurabilityCue.hintLine(cue, null, null, charsPerLine = cue.compact.length).cue)
    }

    @Test
    fun hintCue_should_ShareTheLineAndLetTheHintTextYieldFirst() {
        val cue = privateCue
        val prefix = SketchStrings.Hints.TOOL_LOCK_ON
        val hint = SketchStrings.Hints.of(dev.stapler.stelekit.sketch.v2.tools.ToolId.Hand).keyboard
        val wide = DurabilityCue.hintLine(cue, prefix, hint, charsPerLine = 200)
        assertEquals("$prefix ${cue.full} $hint", wide.text)

        val noHint = DurabilityCue.hintLine(cue, prefix, hint, charsPerLine = "$prefix ${cue.full}".length)
        assertEquals("$prefix ${cue.full}", noHint.text)
        assertNull(noHint.hint, "the hint text yields first")

        val noPrefix = DurabilityCue.hintLine(cue, prefix, hint, charsPerLine = cue.full.length)
        assertNull(noPrefix.prefix, "the prefix leaves when it and the cue do not fit one line together")
        assertNull(noPrefix.hint)
        assertEquals(cue.full, noPrefix.text)
        for (chars in 1..200) {
            val line = DurabilityCue.hintLine(cue, prefix, hint, chars)
            assertTrue(line.text.length <= chars || line.text == cue.compact, "never wrapped at $chars: ${line.text}")
        }
    }

    @Test
    fun collapsedHintRow_should_MoveTheCueToTheChipRow() {
        val window = table.windows.single { it.widthDp == 320 && it.heightDp == 480 && it.fontScale == 2.0 }
        assertEquals(CuePlacement.ChipRow, DurabilityCue.placementFor(window.widthDp, window.heightDp, window.fontScale))
        assertEquals(CuePlacement.HintRow, DurabilityCue.placementFor(360, 640, 1.0))
    }

    private data class Expected(val widthDp: Int, val heightDp: Int, val scale: Double, val rowDp: Int, val privateForm: String, val sessionForm: String)

    /** The "cue chip" line of the chrome-computed block of ux.md; the seven windows without a hint row. */
    private val expectedChips = listOf(
        Expected(320, 480, 2.0, 196, "Private", "Temporary"),
        Expected(600, 360, 1.0, 152, "Private: may clear.", "Temporary: not kept."),
        Expected(600, 360, 2.0, 152, "Private", "Temporary"),
        Expected(800, 360, 1.0, 208, "Private window: may clear.", "Temporary: lost on close."),
        Expected(800, 360, 2.0, 208, "Private", "Temporary"),
        Expected(320, 256, 1.0, 140, "Private", "Temporary"),
        Expected(320, 256, 2.0, 140, "Private", "Temporary"),
    )

    @Test
    fun windowsWithoutHintStatus_should_EqualCollapsedUnionPermanent() {
        val without = table.windows
            .filter { DurabilityCue.placementFor(it.widthDp, it.heightDp, it.fontScale) == CuePlacement.ChipRow }
            .map { Triple(it.widthDp, it.heightDp, it.fontScale) }
            .toSet()
        assertEquals(expectedChips.map { Triple(it.widthDp, it.heightDp, it.scale) }.toSet(), without)
    }

    @Test
    fun cueChip_should_PickTheLongestFormThatFitsTheChipRow_ForEveryWindowWithoutHintStatus() {
        for (e in expectedChips) {
            val row = DurabilityCue.chipRowDp(e.widthDp, e.heightDp)
            assertEquals(e.rowDp, row, "chip row of ${e.widthDp} x ${e.heightDp}")
            assertEquals(e.privateForm, DurabilityCue.chipFor(privateCue, row, e.scale).text, "private cue at ${e.widthDp} x ${e.heightDp} @${e.scale}")
            assertEquals(e.sessionForm, DurabilityCue.chipFor(sessionCue, row, e.scale).text, "session cue at ${e.widthDp} x ${e.heightDp} @${e.scale}")
        }
        assertEquals(CueForm.Chip, DurabilityCue.chipFor(privateCue, 140, 1.0).form)
        assertEquals(CueForm.Compact, DurabilityCue.chipFor(privateCue, 152, 1.0).form)
    }

    @Test
    fun cueChip_should_NotScroll_AtAnyWindowWithoutHintStatus() {
        for (e in expectedChips) {
            val row = DurabilityCue.chipRowDp(e.widthDp, e.heightDp)
            for (cue in listOf(privateCue, sessionCue)) {
                val chip = DurabilityCue.chipFor(cue, row, e.scale)
                assertTrue(chip.fits, "even the chip form does not fit ${e.widthDp} x ${e.heightDp} @${e.scale}")
                assertTrue(chip.text.length <= DurabilityCue.charsThatFit(row, e.scale))
            }
        }
    }

    @Test
    fun chipPad_should_EqualTheChromeTableParameter() {
        assertEquals(table.param("chipPad"), DurabilityCue.CHIP_PAD_DP)
    }

    private companion object {
        const val BYTES_PER_MEGABYTE = 1024L * 1024L
    }
}
