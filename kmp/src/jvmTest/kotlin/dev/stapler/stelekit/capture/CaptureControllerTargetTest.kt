// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.model.GraphId
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.ui.NotificationManager
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** State logic of the desktop capture popup's graph chooser and Esc handling; needs no display. */
class CaptureControllerTargetTest {

    private class NonVaultFakeFileSystem : FakeFileSystem() {
        override fun fileExists(path: String): Boolean = false
    }

    private class RecordingRoute(var outcome: (GraphId) -> AppendOutcome) : OffGraphAppendRoute {
        val calls = mutableListOf<Pair<GraphId, String>>()
        override suspend fun append(graphId: GraphId, text: String, captureId: String?): AppendOutcome {
            calls += graphId to text
            return outcome(graphId)
        }
    }

    private class Setup(
        val controller: CaptureController,
        val settings: CaptureTargetSettings,
        val route: RecordingRoute,
        val activeId: GraphId,
        val workId: GraphId,
        val toasts: NotificationManager,
    )

    private fun newSetup(workIsDefault: Boolean = true): Setup = runBlocking {
        val gm = GraphManager(
            platformSettings = InMemorySettings(),
            driverFactory = DriverFactory(),
            fileSystem = NonVaultFakeFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        )
        val root = Files.createTempDirectory("capture-target-test")
        val personal = Files.createDirectories(root.resolve("Personal graph")).toString()
        val work = Files.createDirectories(root.resolve("Work graph")).toString()
        val personalId = gm.addGraph(personal)
        val workId = gm.addGraph(work)
        gm.switchGraph(personalId)
        gm.awaitPendingMigration()

        val settings = CaptureTargetSettings(InMemorySettings())
        if (workIsDefault) settings.defaultGraphId = workId
        val route = RecordingRoute { AppendOutcome.AppendedOffGraph(it, "journals/today.md") }
        val fs = PlatformFileSystem.withRoot(personal)
        val controller = CaptureController(fs)
        controller.attachGraphManager(gm)
        controller.attachTargetSettings(settings)
        controller.attachAppender(JournalAppender(gm, fs, route))
        val toasts = NotificationManager()
        controller.attachNotificationManager(toasts)
        Setup(controller, settings, route, personalId, workId, toasts)
    }

    private suspend fun CaptureController.awaitState(predicate: (CapturePopupState) -> Boolean): CapturePopupState =
        withTimeout(5_000) { state.first(predicate) }

    private fun Setup.shown(): CapturePopupState.Shown = assertIs(controller.state.value)

    private fun Setup.toastContents(): List<String> = toasts.history.value.map { it.content }

    @Test
    fun show_should_TargetResolverDefault_When_DefaultGraphIsRegistered() {
        val s = newSetup()
        s.controller.show()
        assertEquals(s.workId, s.shown().targetGraphId)
        assertEquals("Work graph", s.shown().targetGraphName)
        assertEquals(2, s.shown().graphChoices.size)
    }

    @Test
    fun show_should_FallBackToActiveGraph_When_NoDefaultIsSet() {
        val s = newSetup(workIsDefault = false)
        s.controller.show()
        assertEquals(s.activeId, s.shown().targetGraphId)
    }

    @Test
    fun save_should_AppendToNamedGraph_RecordLastUsed_AndLeaveDefaultUnchanged() = runBlocking {
        val s = newSetup()
        s.controller.show()
        s.controller.selectGraph(s.activeId)
        s.controller.selectGraph(s.workId)
        s.controller.updateText("idea")
        s.controller.save()

        s.controller.awaitState { it is CapturePopupState.Shown && it.saveState == SaveState.Saved }
        assertEquals(listOf(s.workId to "idea"), s.route.calls)
        assertEquals(s.workId, s.settings.lastGraphId)
        assertEquals(s.workId, s.settings.defaultGraphId)
        assertTrue("Saved to Work graph's journal" in s.toastContents(), "toasts: ${s.toastContents()}")
    }

    @Test
    fun selectGraph_should_KeepTypedTextAndNotChangeDefault() {
        val s = newSetup()
        s.controller.show()
        s.controller.updateText("typed")
        s.controller.setChooserOpen(true)
        assertTrue(s.shown().chooserOpen)

        s.controller.selectGraph(s.activeId)

        assertEquals("typed", s.shown().text)
        assertEquals(s.activeId, s.shown().targetGraphId)
        assertEquals(false, s.shown().chooserOpen)
        assertEquals(s.workId, s.settings.defaultGraphId)
        assertNull(s.settings.lastGraphId, "choosing is not saving")
    }

    @Test
    fun setChooserOpen_should_BeNoOp_When_OnlyOneGraphExists() = runBlocking {
        val gm = GraphManager(InMemorySettings(), DriverFactory(), NonVaultFakeFileSystem(), defaultBackend = GraphBackend.IN_MEMORY)
        val path = Files.createTempDirectory("capture-target-single").toString()
        gm.switchGraph(gm.addGraph(path))
        gm.awaitPendingMigration()
        val controller = CaptureController(PlatformFileSystem.withRoot(path))
        controller.attachGraphManager(gm)

        controller.show()
        controller.setChooserOpen(true)

        assertEquals(false, (controller.state.value as CapturePopupState.Shown).chooserOpen)
    }

    @Test
    fun save_should_ShowQueuedForGraph_AndKeepPopupOpen_When_AppendIsQueued() = runBlocking {
        val s = newSetup()
        s.route.outcome = { AppendOutcome.Queued("graph offline") }
        s.controller.show()
        s.controller.updateText("later")
        s.controller.save()

        val queued = assertIs<CapturePopupState.Shown>(
            s.controller.awaitState { it is CapturePopupState.Shown && it.saveState == SaveState.Queued },
        )
        assertEquals("Queued for Work graph", queued.statusMessage)
        assertEquals("later", queued.text)
        assertNull(s.settings.lastGraphId)
    }

    @Test
    fun save_should_KeepPopupOpenWithError_When_AppendFails() = runBlocking {
        val s = newSetup()
        s.route.outcome = { AppendOutcome.Failed("disk full") }
        s.controller.show()
        s.controller.updateText("oops")
        s.controller.save()

        val failed = assertIs<CapturePopupState.Shown>(
            s.controller.awaitState { it is CapturePopupState.Shown && it.saveState == SaveState.Error },
        )
        assertEquals(CaptureResult.Failed("disk full"), failed.captureResult)
        assertEquals("oops", failed.text)
        assertNull(s.settings.lastGraphId)
    }

    @Test
    fun selectGraph_should_ResetError_So_RetryTargetsTheNewGraph() = runBlocking {
        val s = newSetup()
        s.route.outcome = { AppendOutcome.Failed("disk full") }
        s.controller.show()
        s.controller.updateText("oops")
        s.controller.save()
        s.controller.awaitState { it is CapturePopupState.Shown && it.saveState == SaveState.Error }

        s.controller.selectGraph(s.activeId)

        assertEquals(SaveState.Idle, s.shown().saveState)
        assertNull(s.shown().captureResult)
    }

    @Test
    fun requestDismiss_should_CloseImmediately_When_TextIsBlank() {
        val s = newSetup()
        s.controller.show()
        s.controller.updateText("  \n ")
        s.controller.requestDismiss()
        assertIs<CapturePopupState.Hidden>(s.controller.state.value)
    }

    @Test
    fun requestDismiss_should_AskToDiscard_AndNeverSave_When_TextIsPresent() {
        val s = newSetup()
        s.controller.show()
        s.controller.updateText("keep me")
        s.controller.requestDismiss()

        val confirm = assertIs<CapturePopupState.ConfirmDiscard>(s.controller.state.value)
        assertEquals("keep me", confirm.draft.text)
        assertTrue(s.route.calls.isEmpty())
    }

    @Test
    fun keepEditing_should_RestoreDraftWithTextIntact() {
        val s = newSetup()
        s.controller.show()
        s.controller.updateText("keep me")
        s.controller.requestDismiss()

        s.controller.keepEditing()

        assertEquals("keep me", s.shown().text)
        assertEquals(SaveState.Idle, s.shown().saveState)
    }

    @Test
    fun confirmDiscard_should_HideWithoutSaving() {
        val s = newSetup()
        s.controller.show()
        s.controller.updateText("bin me")
        s.controller.requestDismiss()

        s.controller.confirmDiscard()

        assertIs<CapturePopupState.Hidden>(s.controller.state.value)
        assertTrue(s.route.calls.isEmpty())
    }

    @Test
    fun requestDismiss_should_ReturnToDraft_When_EscPressedOnTheConfirmPrompt() {
        val s = newSetup()
        s.controller.show()
        s.controller.updateText("x")
        s.controller.requestDismiss()
        s.controller.requestDismiss()
        assertEquals("x", s.shown().text)
    }
}
