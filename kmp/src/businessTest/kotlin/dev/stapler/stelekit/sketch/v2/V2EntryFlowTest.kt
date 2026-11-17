// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.model.SheetUuid
import dev.stapler.stelekit.projectbridge.Device
import dev.stapler.stelekit.projectbridge.ProjectBridgeSettings
import dev.stapler.stelekit.projectbridge.ProjectFactory
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.v2.codec.SheetCodecV2
import dev.stapler.stelekit.sketch.v2.runtime.convert.ConversionOutcome
import dev.stapler.stelekit.sketch.v2.runtime.convert.RestoreOutcome
import dev.stapler.stelekit.sketch.v2.runtime.session.ClassicNoticeLedger
import dev.stapler.stelekit.sketch.v2.runtime.session.ProjectAckStore
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.QualitySignals
import dev.stapler.stelekit.sketch.v2.scene.RectElement
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.sketch.v2.tools.PointerKind
import dev.stapler.stelekit.sketch.v2.tools.ToolId
import dev.stapler.stelekit.sketch.v2.view.ScreenPx
import dev.stapler.stelekit.ui.sketch.UpgradeAffordance
import dev.stapler.stelekit.ui.sketch.v2.EditorOpening
import dev.stapler.stelekit.ui.sketch.v2.OnboardingCounters
import dev.stapler.stelekit.ui.sketch.v2.OnboardingSequencer
import dev.stapler.stelekit.ui.sketch.v2.RawPhase
import dev.stapler.stelekit.ui.sketch.v2.RawPointer
import dev.stapler.stelekit.ui.sketch.v2.SketchEditorController
import dev.stapler.stelekit.ui.sketch.v2.V2EditorDeps
import dev.stapler.stelekit.ui.sketch.v2.V2EditorOpener
import dev.stapler.stelekit.ui.sketch.v2.V2EntryActions
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteAction
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteOutcome
import dev.stapler.stelekit.ui.sketch.v2.migration.FirstWriteVariant
import dev.stapler.stelekit.ui.sketch.v2.migration.StartDecision
import dev.stapler.stelekit.ui.sketch.v2.strings.SketchMigrationStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 6.2.0c end to end over the real sidecar store and SQLite repositories: a brand-new sketch project gets a v2 sheet only
 * through the first-write dialog, and a classic sheet converts, reopens as v2 and restores its backup only through the confirm flow.
 */
@OptIn(DirectRepositoryWrite::class)
internal class V2EntryFlowTest {
    private val metrics = FixedAdvanceTextMetricsProvider()

    private class Harness(val device: Device, mode: V2Mode) {
        val settings = RigSettings()
        val acks = ProjectAckStore(settings) { GRAPH }
        val quality = QualitySignals(settings)
        val deps = V2EditorDeps(
            repos = device.repos,
            store = { device.store },
            leases = null,
            settings = ProjectBridgeSettings(settings).also { it.setV2Mode(GRAPH, mode) },
            graphId = { GRAPH },
            acks = acks,
            classicNotices = ClassicNoticeLedger(settings),
            quality = quality,
            onboarding = OnboardingSequencer(OnboardingCounters(settings)),
        )
        val opener = V2EditorOpener(deps, Dispatchers.Default)
        val actions = V2EntryActions(deps)

        fun sketchFiles() = device.fs.allFilePaths().filter { it.contains("/assets/stelekit-sketch/") && !it.endsWith(".tmp") }
    }

    private fun emptyProject(h: Harness): ProjectUuid {
        val project = ProjectFactory.create("Kitchen", "proj-0a1b2c", ProjectFactory.newUuid(), 1L)
        runBlocking { h.device.repos.projects.saveProject(project) }
        return project.uuid
    }

    private fun SketchEditorController.draw(x0: Double, y0: Double, x1: Double, y1: Double) {
        var t = 0L
        fun p(phase: RawPhase, x: Double, y: Double) = tools.onPointer(RawPointer(1, PointerKind.Mouse, phase, ScreenPx(x, y), timestamp = t++))
        p(RawPhase.Down, x0, y0)
        p(RawPhase.Move, (x0 + x1) / 2, (y0 + y1) / 2)
        p(RawPhase.Move, x1, y1)
        p(RawPhase.Up, x1, y1)
    }

    @Test
    fun newProjectSketch_should_CreateDrawSaveAndReloadAsV2_When_TheModeIsOnAndTheDialogIsConfirmed() = runBlocking {
        val h = Harness(Device(), V2Mode.On)
        val project = emptyProject(h)

        val pending = assertIs<EditorOpening.NewSheetNeedsConfirm>(h.opener.open(project, metrics))
        assertTrue(h.sketchFiles().isEmpty(), "nothing is written before the dialog is confirmed")
        assertFalse(h.acks.isAcknowledged(pending.slug))

        val model = h.actions.firstWriteModel(FirstWriteVariant.NewSheet, pending.projectName, null)
        assertNull(model.resolve(FirstWriteAction.Primary), "the primary button is disabled until the box is checked")
        val outcome = assertNotNull(model.withChecked(true).resolve(FirstWriteAction.Primary))
        assertEquals(FirstWriteOutcome.CreateAndOpen, outcome)
        h.actions.confirmFirstWrite(outcome, pending.slug)
        assertTrue(h.acks.isAcknowledged(pending.slug))

        val session = assertIs<EditorOpening.Editor>(h.opener.createAndOpen(project, metrics)).session
        val sheet = assertNotNull(session.sheet)
        val row = h.device.repos.sheets.getSheets(project).first().getOrNull()!!.items.single()
        assertEquals(SheetPaths.SCHEMA_V2, row.schemaVersion)
        assertEquals(sheet, row.uuid)
        repeat(DRAWN) {
            session.controller.tools.chooseTool(ToolId.Rectangle)
            session.controller.draw(10.0 + it * 120, 10.0, 90.0 + it * 120, 80.0)
        }
        session.controller.flush()
        val onDisk = assertIs<SidecarRead.Present>(h.device.store.read(pending.slug)).files.getValue(SheetPaths.pathFor(sheet, SheetPaths.SCHEMA_V2))
        assertEquals(DRAWN, SheetCodecV2.decode(onDisk).getOrNull()!!.scene.elementsInOrder.count { it is RectElement })
        session.close()

        val reopened = assertIs<EditorOpening.Editor>(h.opener.open(project, metrics)).session
        assertEquals(DRAWN, reopened.controller.doc.scene.elementsInOrder.count { it is RectElement }, "the reload shows the saved scene")
        reopened.close()
        assertEquals(1L, h.quality.adoption().authored, "a sheet with 3 drawn elements is the metric-8 numerator")
        assertEquals(2L, h.quality.counts().v2Sessions)
    }

    @Test
    fun newProjectSketch_should_WriteNothing_When_TheDialogIsCancelledOrClassicIsChosen() = runBlocking {
        val h = Harness(Device(), V2Mode.NewProjectsOnly)
        val project = emptyProject(h)
        val pending = assertIs<EditorOpening.NewSheetNeedsConfirm>(h.opener.open(project, metrics))
        val model = h.actions.firstWriteModel(FirstWriteVariant.NewSheet, pending.projectName, null)

        assertEquals(FirstWriteOutcome.Cancelled, model.resolve(FirstWriteAction.Dismiss))
        assertEquals(FirstWriteOutcome.CreateClassic, model.resolve(FirstWriteAction.Classic))
        assertNull(h.actions.confirmFirstWrite(FirstWriteOutcome.CreateClassic, pending.slug))

        assertFalse(h.acks.isAcknowledged(pending.slug), "a classic choice stores no acknowledgement")
        assertTrue(h.sketchFiles().isEmpty())
        assertTrue(h.device.repos.sheets.getSheets(project).first().getOrNull()!!.items.isEmpty())
    }

    @Test
    fun newProjectSketch_should_StayClassic_When_TheModeIsOff() = runBlocking {
        val h = Harness(Device(), V2Mode.Off)
        val project = emptyProject(h)

        assertEquals(EditorOpening.Legacy, h.opener.open(project, metrics))
        assertTrue(h.sketchFiles().isEmpty())
    }

    @Test
    fun classicSheet_should_ConvertThroughTheConfirmFlow_ReopenAsV2AndRestoreItsBackup() = runBlocking {
        val device = ConversionFixtures.device()
        val h = Harness(device, V2Mode.On)
        val project = SketchTestSupport.P
        val s1 = SheetUuid(ConversionFixtures.S1)

        val classic = assertIs<EditorOpening.ClassicEditor>(h.opener.open(project, metrics))
        assertEquals(UpgradeAffordance.Chip, classic.affordance)
        h.actions.offer!!.offer(classic.affordance, s1.value)
        assertEquals(SketchMigrationStrings.Banners.UPGRADE_SUMMARY, h.deps.onboarding!!.visibleCandidate()?.summary)
        assertEquals(setOf(1L), ConversionFixtures.schemas(device).filterKeys { it == s1.value }.values.toSet())

        assertEquals(StartDecision.NeedsFirstWriteDialog, h.actions.start(classic.slug), "an unacknowledged project asks first")
        assertEquals(ConversionOutcome.RequiresConfirmation(classic.slug), h.actions.convert(project, s1), "the converter refuses without the acknowledgement")
        assertFalse(device.fs.allFilePaths().any { it.endsWith("p1-s1.v2.json") }, "nothing v2 was written before the confirm")
        val model = h.actions.firstWriteModel(FirstWriteVariant.Convert, classic.projectName, s1)
        assertTrue(model.blocks.any { it.contains("backup/p1-s1.v1.json") }, "the dialog names the backup")
        val outcome = assertNotNull(model.withChecked(true).resolve(FirstWriteAction.Primary))
        h.actions.confirmFirstWrite(outcome, classic.slug)

        assertIs<ConversionOutcome.Converted>(h.actions.convert(project, s1))
        assertEquals(2L, ConversionFixtures.schemas(device).getValue(s1.value))
        val session = assertIs<EditorOpening.Editor>(h.opener.open(project, metrics)).session
        assertEquals(s1, session.sheet)
        assertEquals(ConversionFixtures.S1_PRIMITIVES, session.controller.doc.scene.elementsInOrder.size)
        assertTrue(h.actions.postConversion(session, s1), "the post-conversion banner is raised once")
        assertFalse(h.actions.postConversion(session, s1))
        assertTrue(h.actions.hasBackup(classic.slug, s1))
        assertEquals(0L, h.quality.adoption().authored, "a conversion is never authored")
        assertEquals(1L, h.quality.adoption().converted)
        session.controller.flush()
        session.close()

        assertIs<RestoreOutcome.Restored>(h.actions.restore(classic.slug, s1))
        assertEquals(1L, ConversionFixtures.schemas(device).getValue(s1.value))
        val back = assertIs<EditorOpening.ClassicEditor>(h.opener.open(project, metrics))
        assertEquals(s1, back.sheet)
        assertTrue(device.fs.allFilePaths().none { it.endsWith("p1-s1.v2.json") }, "the v2 file is gone after the restore")
    }

    @Test
    fun classicSheet_should_OfferNoChip_When_ItIsDeclinedOrTheModeIsNotOn() = runBlocking {
        val declined = Harness(ConversionFixtures.device(), V2Mode.On)
        declined.actions.offer!!.keepClassic(ConversionFixtures.S1)
        assertEquals(EditorOpening.Legacy, declined.opener.open(SketchTestSupport.P, metrics))

        val npo = Harness(ConversionFixtures.device(), V2Mode.NewProjectsOnly)
        assertEquals(EditorOpening.Legacy, npo.opener.open(SketchTestSupport.P, metrics), "an existing classic project keeps its editor")
    }
}

private const val GRAPH = "g1"
private const val DRAWN = 3
