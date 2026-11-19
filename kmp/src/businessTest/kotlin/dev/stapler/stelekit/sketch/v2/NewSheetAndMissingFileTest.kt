// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.sketch.v2

import dev.stapler.stelekit.model.ProjectUuid
import dev.stapler.stelekit.projectbridge.Device
import dev.stapler.stelekit.projectbridge.ProjectBridgeSettings
import dev.stapler.stelekit.projectbridge.ProjectFactory
import dev.stapler.stelekit.projectbridge.SheetPaths
import dev.stapler.stelekit.projectbridge.SidecarRead
import dev.stapler.stelekit.projectbridge.V2Mode
import dev.stapler.stelekit.repository.DirectRepositoryWrite
import dev.stapler.stelekit.repository.SketchTestSupport
import dev.stapler.stelekit.sketch.v2.runtime.session.ClassicNoticeLedger
import dev.stapler.stelekit.sketch.v2.runtime.session.ProjectAckStore
import dev.stapler.stelekit.sketch.v2.runtime.telemetry.QualitySignals
import dev.stapler.stelekit.sketch.v2.text.FixedAdvanceTextMetricsProvider
import dev.stapler.stelekit.ui.sketch.SketchReasonText
import dev.stapler.stelekit.ui.sketch.v2.EditorOpening
import dev.stapler.stelekit.ui.sketch.v2.OnboardingCounters
import dev.stapler.stelekit.ui.sketch.v2.OnboardingSequencer
import dev.stapler.stelekit.ui.sketch.v2.V2EditorDeps
import dev.stapler.stelekit.ui.sketch.v2.V2EditorOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Review findings B3 and B4: a v2 row never exists without its file, and a v2 row with no file never opens as a new sheet. */
@OptIn(DirectRepositoryWrite::class)
internal class NewSheetAndMissingFileTest {
    private val metrics = FixedAdvanceTextMetricsProvider()

    private class Harness(val device: Device) {
        val settings = RigSettings()
        val deps = V2EditorDeps(
            repos = device.repos,
            store = { device.store },
            leases = null,
            settings = ProjectBridgeSettings(settings).also { it.setV2Mode("g", V2Mode.On) },
            graphId = { "g" },
            acks = ProjectAckStore(settings) { "g" },
            classicNotices = ClassicNoticeLedger(settings),
            quality = QualitySignals(settings),
            onboarding = OnboardingSequencer(OnboardingCounters(settings)),
        )
        val opener = V2EditorOpener(deps, Dispatchers.Default)
    }

    private fun emptyProject(h: Harness): ProjectUuid {
        val project = ProjectFactory.create("Kitchen", "proj-0a1b2c", ProjectFactory.newUuid(), 1L)
        runBlocking { h.device.repos.projects.saveProject(project) }
        return project.uuid
    }

    private suspend fun rows(h: Harness, project: ProjectUuid) = h.device.repos.sheets.getSheets(project).first().getOrNull()!!.items

    @Test
    fun createSheet_should_LeaveNoRow_WhenASyncIsBusy_AndSucceedOnceItEnds() = runBlocking<Unit> {
        val h = Harness(Device())
        val project = emptyProject(h)
        h.device.busy = true

        assertEquals(EditorOpening.Legacy, h.opener.createAndOpen(project, metrics))

        assertTrue(rows(h, project).isEmpty(), "a deferred create is not a created sheet and leaves no v2 row")
        h.device.busy = false
        val session = assertIs<EditorOpening.Editor>(h.opener.createAndOpen(project, metrics)).session
        val row = rows(h, project).single()
        assertEquals(SheetPaths.SCHEMA_V2, row.schemaVersion)
        val files = assertIs<SidecarRead.Present>(h.device.store.read("proj-0a1b2c")).files
        assertTrue(SheetPaths.pathFor(row.uuid, SheetPaths.SCHEMA_V2) in files, "the row's file exists")
        session.close()
    }

    @Test
    fun createSheet_should_LeaveNoRow_WhenTheProcessDiesBeforeTheModelIsWritten() = runBlocking<Unit> {
        val h = Harness(Device())
        val project = emptyProject(h)
        h.device.fs.crashAtRename = true

        runCatching { h.opener.createAndOpen(project, metrics) }
        h.device.fs.revive()

        assertTrue(rows(h, project).isEmpty(), "the row is written last, so a crash leaves none")
    }

    @Test
    fun aV2RowWithNoFile_should_OpenReadOnly_NeverAsABlankEditableSheet() = runBlocking<Unit> {
        val h = Harness(Device())
        val project = emptyProject(h)
        val token = h.device.repos.projects.loadProject(project).getOrNull()!!.token
        h.device.repos.sheets.saveSheet(SketchTestSupport.sheet("p1-v2", project = project.value).copy(schemaVersion = SheetPaths.SCHEMA_V2), token)

        val opening = assertIs<EditorOpening.Editor>(h.opener.open(project, metrics))

        assertTrue(opening.session.controller.readOnly, "no saver can write over a file this device never read")
        assertTrue(h.device.fs.allFilePaths().none { it.contains("/stelekit-sketch/") }, "opening wrote nothing")
        assertTrue(SketchReasonText.FILE_MISSING.isNotBlank())
        opening.session.close()
    }
}
