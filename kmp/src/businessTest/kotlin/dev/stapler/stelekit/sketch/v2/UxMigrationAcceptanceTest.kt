// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.platform.PersistenceState
import dev.stapler.stelekit.platform.StorageReason
import dev.stapler.stelekit.projectbridge.V2Capabilities
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.projectbridge.V2Storage
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerRowId
import dev.stapler.stelekit.sketch.v2.diagnostics.BannerTier
import dev.stapler.stelekit.sketch.v2.diagnostics.InMemoryDiagnosticsPort
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionFailure
import dev.stapler.stelekit.sketch.v2.runtime.session.ClassicNoticeLedger
import dev.stapler.stelekit.sketch.v2.runtime.session.ProjectAckStore
import dev.stapler.stelekit.ui.sketch.ClassicNoticeReason
import dev.stapler.stelekit.ui.sketch.ClassicReason
import dev.stapler.stelekit.ui.sketch.NewSheetKind
import dev.stapler.stelekit.ui.sketch.RouteInput
import dev.stapler.stelekit.ui.sketch.SheetSchema
import dev.stapler.stelekit.ui.sketch.SketchEditorRouter
import dev.stapler.stelekit.ui.sketch.v2.migration.ClassicNoticeDecision
import dev.stapler.stelekit.ui.sketch.v2.migration.ClassicNoticePolicy
import dev.stapler.stelekit.ui.sketch.v2.migration.ClassicNoticeScope
import dev.stapler.stelekit.ui.sketch.v2.migration.EditorOpen
import dev.stapler.stelekit.ui.sketch.v2.migration.EngineChangeFlow
import dev.stapler.stelekit.ui.sketch.v2.migration.EngineChangeNotice
import dev.stapler.stelekit.ui.sketch.v2.migration.PostConversionNotice
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteAction
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteDialogModel
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteFlow
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteOutcome
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteVariant
import dev.stapler.stelekit.ui.sketch.v2.migration.NpoProjectState
import dev.stapler.stelekit.ui.sketch.v2.migration.WebDurability
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchConsentCopy
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchMigrationStrings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Story 1.4.6 acceptance rows that need no renderer (UX-64, UX-65, UX-66, UX-69, UX-77, UX-131, UX-139, UX-143). */
class UxMigrationAcceptanceTest {
    private val settings = MapSettings()
    private val acks = ProjectAckStore(settings) { "g1" }
    private val ledger = ClassicNoticeLedger(settings)
    private val open = V2Capabilities(true, true, V2Storage.Available)
    private val scope = ClassicNoticeScope("g1", "kitchen", setOf("kitchen"))

    private fun convert(checked: Boolean = false, web: WebDurability? = null) =
        FirstWriteDialogModel(FirstWriteVariant.Convert, "Kitchen", "kitchen/backup/", web = web, checked = checked)

    private fun newSheet(checked: Boolean = false) = FirstWriteDialogModel(FirstWriteVariant.NewSheet, "Kitchen", "kitchen/backup/", checked = checked)

    @Test
    fun firstWriteDialog_should_ResolveTheFourOutcomes() {
        assertEquals(FirstWriteOutcome.ConvertAndOpen, convert(checked = true).resolve(FirstWriteAction.Primary))
        assertEquals(FirstWriteOutcome.OpenClassic, convert(checked = true).resolve(FirstWriteAction.Classic))
        assertEquals(FirstWriteOutcome.OpenClassic, convert(checked = true).resolve(FirstWriteAction.Dismiss), "Esc and back are Open classic")
        assertEquals(FirstWriteOutcome.CreateAndOpen, newSheet(checked = true).resolve(FirstWriteAction.Primary))
        assertEquals(FirstWriteOutcome.CreateClassic, newSheet().resolve(FirstWriteAction.Classic))
        assertEquals(FirstWriteOutcome.Cancelled, newSheet(checked = true).resolve(FirstWriteAction.Dismiss), "Esc cancels a creation")
        assertEquals(setOf(FirstWriteOutcome.ConvertAndOpen, FirstWriteOutcome.CreateAndOpen), FirstWriteOutcome.entries.filter { it.acknowledges }.toSet())
    }

    @Test
    fun theCheckbox_should_StartUncheckedAndGateOnlyThePrimaryButton() {
        val model = convert()
        assertFalse(model.checked)
        assertFalse(model.canConfirm)
        assertEquals("Check I understand to continue.", model.disabledHint)
        assertNull(model.resolve(FirstWriteAction.Primary), "a disabled primary resolves to nothing")
        assertEquals(FirstWriteOutcome.OpenClassic, model.resolve(FirstWriteAction.Classic), "the left button is always enabled")
        val checked = model.withChecked(true)
        assertTrue(checked.canConfirm)
        assertNull(checked.disabledHint)
        assertFalse(convert().checked, "a new dialog is unchecked again")
    }

    @Test
    fun theDialogLabels_should_SayWhatTheButtonsDo() {
        assertEquals("Convert and open", convert().primaryLabel)
        assertEquals("Open classic", convert().classicLabel)
        assertEquals("Create and open", newSheet().primaryLabel)
        assertEquals("Create classic sketch", newSheet().classicLabel)
        assertEquals("Create a sketch with the new editor?", newSheet().title)
        assertEquals("Created with the classic editor because you chose it.", SketchMigrationStrings.FirstWrite.CREATED_CLASSIC_CHOSEN)
    }

    @Test
    fun onlyAConfirmingOutcome_should_AcknowledgeTheProject_AndAskTheBrowserToKeepStorage() = runTest {
        val persistence = CountingPersistence(PersistenceState.Denied)
        val flow = FirstWriteFlow(acks, persistence)
        for (outcome in listOf(FirstWriteOutcome.OpenClassic, FirstWriteOutcome.CreateClassic, FirstWriteOutcome.Cancelled)) {
            assertNull(flow.complete(outcome, "kitchen"))
        }
        assertEquals(0, persistence.requests, "no persist() call before the confirm click")
        assertFalse(acks.isAcknowledged("kitchen"))
        assertEquals(PersistenceState.Denied, flow.complete(FirstWriteOutcome.ConvertAndOpen, "kitchen"))
        assertEquals(1, persistence.requests)
        assertTrue(acks.isAcknowledged("kitchen"))
        assertFalse(acks.isAcknowledged("pantry"), "the acknowledgement is per project")
    }

    @Test
    fun theWebDialogs_should_GainTheDurabilityLine_AndPromptWordingOnlyWhereTheBrowserPrompts() {
        val persistent = convert(web = WebDurability(sessionOnly = false, privateSuspected = false, browserMayPrompt = false)).blocks
        assertTrue(persistent.any { it.startsWith("New-editor sketches are saved in this browser's private storage") })
        val temporary = convert(web = WebDurability(sessionOnly = true, privateSuspected = true, browserMayPrompt = true)).blocks
        assertTrue(temporary.any { it.startsWith("This graph is open temporarily") })
        assertTrue(temporary.any { it == "Your browser may ask to keep this site's storage." })
        assertTrue(convert().blocks.none { "browser" in it }, "a desktop dialog has no web sentence")
    }

    @Test
    fun theEngineChangeToast_should_OfferReopen_AndNameItsRowByKey() {
        val notice = EngineChangeNotice.notice
        assertEquals("EngineChange", notice.key)
        assertEquals("Applies next time you open it.", notice.text)
        assertEquals("Reopen", notice.action)
        assertEquals(BannerTier.Info, notice.tier)
    }

    @Test
    fun classicReason_should_NameOnlyTheFirstApplicableRow_InPrecedenceOrder() {
        assertEquals(
            listOf("Platform", "BuildCapped", "TechnicalData", "ModeOff", "NpoClassicSheets", "NpoEmptyProject", "NpoUnreadable", "UserChose"),
            ClassicNoticeReason.entries.map { it.name },
        )
        assertEquals(ClassicNoticeReason.Platform, ClassicNoticePolicy.firstApplicable(ClassicNoticeReason.entries.toSet()))
        assertEquals(
            ClassicNoticeReason.TechnicalData,
            ClassicNoticePolicy.firstApplicable(setOf(ClassicNoticeReason.NpoClassicSheets, ClassicNoticeReason.TechnicalData, ClassicNoticeReason.UserChose)),
        )
        assertNull(ClassicNoticePolicy.firstApplicable(emptySet()))
    }

    @Test
    fun aNewSheetCreatedClassic_should_ToastOncePerReasonWhereTheStoredModeIsNotOff() {
        // the stored mode is Off: no toast at all, only a silent Notices entry
        assertEquals(ClassicNoticeDecision.Silent, ClassicNoticePolicy.decide(V2Mode.Off, ClassicNoticeReason.ModeOff, open, ledger, scope))
        // NewProjectsOnly, an existing project with classic sheets: the toast with [Change], and its Details name the route
        val first = ClassicNoticePolicy.decide(V2Mode.NewProjectsOnly, ClassicNoticeReason.NpoClassicSheets, open, ledger, scope)
        val toast = assertIs<ClassicNoticeDecision.Toast>(first)
        assertEquals("ClassicEditor", toast.notice.key)
        assertEquals("Created as classic.", toast.notice.text)
        assertEquals("Change", toast.notice.action)
        assertTrue(toast.details.startsWith("Created with the classic editor because this project already has sketches"))
        assertTrue(toast.details.endsWith("and each classic sketch offers Upgrade."))
        // once per (graph, project, reason): the same reason is silent, a different reason shows once more
        assertEquals(ClassicNoticeDecision.Silent, ClassicNoticePolicy.decide(V2Mode.NewProjectsOnly, ClassicNoticeReason.NpoClassicSheets, open, ledger, scope))
        assertIs<ClassicNoticeDecision.Toast>(ClassicNoticePolicy.decide(V2Mode.NewProjectsOnly, ClassicNoticeReason.NpoEmptyProject, open, ledger, scope))
    }

    @Test
    fun aReasonSettingsCannotChange_should_BeTheFixedRowWithLearnMore() {
        val capped = V2Capabilities(false, true, V2Storage.Available)
        val platform = ClassicNoticePolicy.decide(V2Mode.NewProjectsOnly, ClassicNoticeReason.Platform, open, ledger, scope)
        assertEquals("ClassicEditorFixed", assertIs<ClassicNoticeDecision.Toast>(platform).notice.key)
        assertEquals("Learn more", platform.notice.action)
        val user = ClassicNoticePolicy.decide(V2Mode.NewProjectsOnly, ClassicNoticeReason.UserChose, open, ledger, scope)
        assertTrue(assertIs<ClassicNoticeDecision.Toast>(user).fixed)
        val modeOn = ClassicNoticePolicy.decide(V2Mode.On, ClassicNoticeReason.TechnicalData, open, ledger, scope)
        assertTrue(assertIs<ClassicNoticeDecision.Toast>(modeOn).fixed)
        val notSelectable = ClassicNoticePolicy.decide(V2Mode.NewProjectsOnly, ClassicNoticeReason.NpoUnreadable, V2Capabilities(true, false, V2Storage.Available), ledger, scope)
        assertTrue(assertIs<ClassicNoticeDecision.Toast>(notSelectable).fixed, "All sketches (Upgrade) is not selectable, so Change would not help")
        assertFalse(ClassicNoticePolicy.canChange(V2Mode.NewProjectsOnly, ClassicNoticeReason.NpoClassicSheets, capped))
    }

    @Test
    fun aCapLoweringTheModeToEffectiveOff_should_StillToast_BecauseTheStoredModeIsWhatCounts() {
        val storage = V2Storage.Browser(StorageReason.NoOpfs)
        val noOpfs = V2Capabilities(true, true, storage)
        val kind = SketchEditorRouter.newSheetKind(RouteInput(V2Mode.NewProjectsOnly, noOpfs, SheetSchema.V1), projectIsNew = true)
        val reason = ClassicNoticePolicy.reasonOf((kind as NewSheetKind.Classic).reason)
        assertEquals(ClassicNoticeReason.Platform, reason)
        assertIs<ClassicNoticeDecision.Toast>(ClassicNoticePolicy.decide(V2Mode.NewProjectsOnly, reason, noOpfs, ledger, scope))
    }

    @Test
    fun theRouterReasons_should_MapOntoTheClosedToastSet() {
        assertEquals(ClassicNoticeReason.ModeOff, ClassicNoticePolicy.reasonOf(ClassicReason.ModeOff))
        assertEquals(ClassicNoticeReason.BuildCapped, ClassicNoticePolicy.reasonOf(ClassicReason.BuildCapped))
        assertEquals(ClassicNoticeReason.TechnicalData, ClassicNoticePolicy.reasonOf(ClassicReason.TechnicalData))
        assertEquals(ClassicNoticeReason.NpoEmptyProject, ClassicNoticePolicy.reasonOf(ClassicReason.ProjectNotNew, NpoProjectState.Empty))
        assertEquals(ClassicNoticeReason.NpoUnreadable, ClassicNoticePolicy.reasonOf(ClassicReason.ProjectNotNew, NpoProjectState.Unreadable))
    }

    @Test
    fun theFailureDialogCopy_should_SayTheReasonInWordsThenTheCode() {
        assertEquals("Your original sketch wasn't changed.", SketchMigrationStrings.Failure.BODY)
        ConversionFailure.entries.forEach {
            val line = SketchMigrationStrings.Failure.reason(it)
            assertTrue(line.startsWith("Reason: ") && line.endsWith("(${it.token})"), line)
        }
    }

    @Test
    fun engineChange_should_OfferReopen_AndReopenOnlyOnExplicitActivation() = runTest {
        val calls = mutableListOf<String>()
        val flow = EngineChangeFlow(savePending = { calls += "save" }, reopenSketch = { calls += "reopen" })
        assertNull(flow.onModeChanged(EditorOpen.No), "no editor open: nothing to say")
        assertEquals(EngineChangeNotice.notice, flow.onModeChanged(EditorOpen.Yes))
        assertTrue(calls.isEmpty(), "the open editor is neither swapped nor closed by the change")
        flow.reopen()
        assertEquals(listOf("save", "reopen"), calls, "pending edits are saved before the reopen")
    }

    @Test
    fun postConversionBanner_should_BeRaisedOncePerSheet_WithTheBackupAndWarningInItsDetails() {
        val diagnostics = InMemoryDiagnosticsPort(SketchMigrationStrings.Banners)
        val notice = PostConversionNotice(diagnostics, acks)
        assertTrue(notice.onConverted("sheetA", "kitchen/backup/"))
        val entry = diagnostics.diagnostics.value.entries.single { it.row == BannerRowId.PostConversion }
        assertEquals("Upgraded. Backup saved.", entry.summary)
        assertTrue(SketchConsentCopy.backupSentence("kitchen/backup/") in entry.details)
        assertTrue(SketchConsentCopy.WHOLE_PROJECT_WARNING in entry.details)
        notice.dismiss()
        assertFalse(notice.onConverted("sheetA", "kitchen/backup/"), "not again after dismissal or reopen")
        assertTrue(diagnostics.diagnostics.value.entries.none { it.row == BannerRowId.PostConversion })
        assertTrue(PostConversionNotice(diagnostics, ProjectAckStore(settings) { "g1" }).onConverted("sheetB", "kitchen/backup/"), "another sheet gets its own")
    }
}
