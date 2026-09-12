// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Coverage for [CaptureController] — the desktop popup's lifecycle and coroutine-scope owner.
 * Uses a real `IN_MEMORY`-backed [GraphManager] (same construction pattern as
 * `CaptureWriterTest`) rather than mocking [CaptureWriter], which is an `object`.
 */
class CaptureControllerTest {

    /**
     * [FakeFileSystem.fileExists] always returns `true`, which GraphManager.addGraph reads as
     * "vault marker present" and marks the graph paranoid-mode-locked (see the same override
     * in `CaptureWriterTest`). Override it so these tests get a normal, writable graph.
     */
    private class NonVaultFakeFileSystem : FakeFileSystem() {
        override fun fileExists(path: String): Boolean = false
    }

    /** A ready-to-save [CaptureController]: a live `IN_MEMORY` graph plus a matching, writable [PlatformFileSystem]. */
    private data class ActiveSetup(val controller: CaptureController, val graphManager: GraphManager)

    private suspend fun newActiveSetup(): ActiveSetup {
        val graphManager = GraphManager(
            platformSettings = InMemorySettings(),
            driverFactory = DriverFactory(),
            fileSystem = NonVaultFakeFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        )
        val graphPath = Files.createTempDirectory("capture-controller-test").toString()
        val id = graphManager.addGraph(graphPath)
        graphManager.switchGraph(id)
        graphManager.awaitPendingMigration()

        // CaptureWriter's GraphWriter.savePage() enforces a security whitelist on the real
        // PlatformFileSystem — registerGraphRoot() must match the graph path or every save
        // silently resolves to Failed (the DB write still lands, but the file write doesn't).
        val controller = CaptureController(PlatformFileSystem.withRoot(graphPath))
        controller.attachGraphManager(graphManager)
        return ActiveSetup(controller, graphManager)
    }

    private fun newFileSystem(): PlatformFileSystem = PlatformFileSystem()

    private suspend fun CaptureController.awaitState(
        predicate: (CapturePopupState) -> Boolean,
    ): CapturePopupState = withTimeout(5_000) { state.first(predicate) }

    @Test
    fun save_should_TransitionToSavedState_When_WriteCaptureSucceeds() = runBlocking {
        val controller = newActiveSetup().controller

        controller.show()
        controller.updateText("Buy milk")
        controller.save()

        val shown = assertIs<CapturePopupState.Shown>(
            controller.awaitState { it is CapturePopupState.Shown && it.saveState == SaveState.Saved },
        )
        assertEquals(SaveState.Saved, shown.saveState)
        assertTrue(shown.captureResult is CaptureResult.Saved, "expected a Saved captureResult, got ${shown.captureResult}")
    }

    @Test
    fun save_should_TransitionToErrorStateWithMessage_When_WriteCaptureFails() = runBlocking {
        // No graphManager attached exercises the same saveState=Error branch a mid-write
        // failure from CaptureWriter would; GraphManager's public API gives no way to inject
        // a throwing repository here, so CaptureWriterTest covers that path directly instead.
        val controller = CaptureController(newFileSystem())

        controller.show()
        controller.updateText("Buy milk")
        controller.save()

        val shown = assertIs<CapturePopupState.Shown>(
            controller.awaitState { it is CapturePopupState.Shown && it.saveState == SaveState.Error },
        )
        assertEquals(SaveState.Error, shown.saveState)
        assertEquals(CaptureResult.NoActiveGraph, shown.captureResult)
        assertEquals("Buy milk", shown.text, "failed save must leave the draft text unchanged")
    }

    @Test
    fun save_should_NotThrowForgottenCoroutineScopeException_When_CalledAfterFiveShowHideCycles() = runBlocking {
        val controller = newActiveSetup().controller

        repeat(5) {
            controller.show()
            controller.updateText("cycle $it")
            controller.hide()
        }

        controller.show()
        controller.updateText("final draft")
        controller.save()

        val shown = assertIs<CapturePopupState.Shown>(
            controller.awaitState { it is CapturePopupState.Shown && it.saveState == SaveState.Saved },
        )
        assertEquals(SaveState.Saved, shown.saveState)
    }

    @Test
    fun show_should_PreserveExistingText_When_CalledWhilePopupAlreadyShown() = runBlocking {
        val controller = newActiveSetup().controller

        controller.show()
        controller.updateText("partial thought")

        controller.show()

        val shown = assertIs<CapturePopupState.Shown>(controller.state.value)
        assertEquals("partial thought", shown.text)
        assertEquals(SaveState.Idle, shown.saveState)
    }

    @Test
    fun dismiss_should_PersistBlockViaCaptureWriter_Before_TransitioningToHidden_When_TextIsNonBlank() = runBlocking {
        val (controller, graphManager) = newActiveSetup()

        controller.show()
        controller.updateText("Draft idea")
        controller.dismiss()

        controller.awaitState { it is CapturePopupState.Hidden }

        val repoSet = requireNotNull(graphManager.getActiveRepositorySet())
        val journal = repoSet.journalService.ensureTodayJournal()
        val blocks = repoSet.blockRepository.getBlocksForPage(journal.uuid).first().getOrNull().orEmpty()
        assertTrue(
            blocks.any { it.content == "Draft idea" },
            "expected dismiss() to have persisted the draft via CaptureWriter before hiding",
        )
    }
}
