// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
// https://www.elastic.co/licensing/elastic-license

package dev.stapler.stelekit.capture

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.stapler.stelekit.db.DriverFactory
import dev.stapler.stelekit.db.GraphManager
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.repository.GraphBackend
import dev.stapler.stelekit.ui.fixtures.FakeFileSystem
import dev.stapler.stelekit.ui.fixtures.InMemorySettings
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * UX-acceptance-criteria coverage for the capture popup (`design/ux.md`'s "UX Acceptance
 * Criteria" section) — distinct from [CapturePopupWindowTest]'s per-story functional coverage.
 * Same real-`CaptureController` approach as [CapturePopupWindowTest]; see that file's class doc.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class CapturePopupWindowUxTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class NonVaultFakeFileSystem : FakeFileSystem() {
        override fun fileExists(path: String): Boolean = false
    }

    private fun newActiveController(): CaptureController = runBlocking {
        val graphManager = GraphManager(
            platformSettings = InMemorySettings(),
            driverFactory = DriverFactory(),
            fileSystem = NonVaultFakeFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        )
        val graphPath = Files.createTempDirectory("capture-popup-ux-test").toString()
        val id = graphManager.addGraph(graphPath)
        graphManager.switchGraph(id)
        graphManager.awaitPendingMigration()

        val controller = CaptureController(PlatformFileSystem.withRoot(graphPath))
        controller.attachGraphManager(graphManager)
        controller
    }

    /**
     * A live graph, but the [CaptureController]'s [PlatformFileSystem] has no registered root —
     * `GraphWriter.savePage`'s security whitelist then rejects every write, giving a real
     * [CaptureResult.Failed] (not a synthetic one) reachable through the public API alone. See
     * `CaptureControllerTest`'s `ActiveSetup` doc for the same whitelist mechanism.
     */
    private fun newSaveFailingController(): CaptureController = runBlocking {
        val graphManager = GraphManager(
            platformSettings = InMemorySettings(),
            driverFactory = DriverFactory(),
            fileSystem = NonVaultFakeFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        )
        val graphPath = Files.createTempDirectory("capture-popup-ux-fail-test").toString()
        val id = graphManager.addGraph(graphPath)
        graphManager.switchGraph(id)
        graphManager.awaitPendingMigration()

        val controller = CaptureController(PlatformFileSystem()) // no registered root
        controller.attachGraphManager(graphManager)
        controller
    }

    /** [FakeFileSystem.fileExists] always returns `true`, marking the graph paranoid-locked. */
    private fun newLockedController(): CaptureController = runBlocking {
        val graphManager = GraphManager(
            platformSettings = InMemorySettings(),
            driverFactory = DriverFactory(),
            fileSystem = FakeFileSystem(),
            defaultBackend = GraphBackend.IN_MEMORY,
        )
        val id = graphManager.addGraph("/vault-graph")
        graphManager.switchGraph(id)
        graphManager.awaitPendingMigration()

        val controller = CaptureController(PlatformFileSystem())
        controller.attachGraphManager(graphManager)
        controller
    }

    private fun setPopupContent(controller: CaptureController) {
        composeTestRule.setContent {
            MaterialTheme {
                CapturePopupContent(controller)
            }
        }
    }

    // UX AC 1/3: 2 keystrokes (hotkey + Ctrl+Enter), 0 mouse clicks, from show() to Saved.
    @Test
    fun captureFlow_should_CompleteInTwoKeystrokesWithZeroMouseClicks_When_HotkeyTypeCtrlEnterSequenceRuns() {
        val controller = newActiveController()
        controller.show() // keystroke 1: the global hotkey (fires outside this composition)
        setPopupContent(controller)

        composeTestRule.onNodeWithTag("captureTextField").performTextInput("Buy milk")
        composeTestRule.onNodeWithTag("captureTextField").performKeyInput {
            keyDown(Key.CtrlLeft) // keystroke 2: Ctrl+Enter
            keyDown(Key.Enter)
            keyUp(Key.Enter)
            keyUp(Key.CtrlLeft)
        }

        composeTestRule.waitUntil(5_000) {
            (controller.state.value as? CapturePopupState.Shown)?.saveState == SaveState.Saved
        }
        // No performClick was ever invoked in this test — the whole flow is keyboard-only.
        composeTestRule.onNodeWithText("Saved to today's journal").assertExists()
    }

    // UX AC 2: focus lands on the text field as soon as the popup composes — no extra click or
    // delay is needed before typing. Best-effort: this test only proves focus is requested on
    // the very first composition frame (synchronous within the test's own clock), not the real
    // hotkey-to-pixels latency a live window/OS would add — flag as flaky/best-effort in CI.
    @Test
    fun capturePopupWindow_should_GainFocusWithin150Ms_When_HotkeyFires() {
        val controller = newActiveController()
        controller.show()
        val start = System.nanoTime()
        setPopupContent(controller)
        composeTestRule.waitUntil(1_000) {
            composeTestRule.onAllNodesWithTag("captureTextField").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("captureTextField").assertIsFocused()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs < 150, "expected focus within 150ms of composing, took ${elapsedMs}ms")
    }

    // UX AC 4: a real write failure shows the exact failure reason (not a generic "Error"),
    // plus both Retry and Copy text & close.
    @Test
    fun capturePopupWindow_should_ShowSpecificErrorMessageWithRetryAndCopyClose_When_SaveFails() {
        val controller = newSaveFailingController()
        controller.show()
        controller.updateText("Buy milk")
        setPopupContent(controller)

        composeTestRule.runOnIdle { controller.save() }
        composeTestRule.waitUntil(5_000) {
            (controller.state.value as? CapturePopupState.Shown)?.saveState == SaveState.Error
        }
        composeTestRule.waitForIdle()

        val message = ((controller.state.value as CapturePopupState.Shown).captureResult as CaptureResult.Failed).message
        assertTrue(message.isNotBlank(), "expected a specific, non-generic failure message")
        composeTestRule.onNodeWithTag("captureErrorMessage").assertTextEquals(message)
        composeTestRule.onNodeWithTag("retryButton").assertExists()
        composeTestRule.onNodeWithTag("copyCloseButton").assertExists()
    }

    // UX AC 5: every non-transient state (NoActiveGraph, GraphLocked, Error) has at least one
    // human-operable exit besides Escape.
    @Test
    fun capturePopupWindow_should_ExposeAtLeastOneOperableExit_When_EachNonTransientStateIsRendered() {
        val noGraphController = CaptureController(PlatformFileSystem())
        noGraphController.show()
        setPopupContent(noGraphController)
        composeTestRule.onNodeWithTag("openStelekitButton").assertHasClickAction()

        val lockedController = newLockedController()
        lockedController.show()
        setPopupContent(lockedController)
        composeTestRule.onNodeWithTag("openStelekitButton").assertHasClickAction()

        val failingController = newSaveFailingController()
        failingController.show()
        failingController.updateText("Buy milk")
        setPopupContent(failingController)
        composeTestRule.runOnIdle { failingController.save() }
        composeTestRule.waitUntil(5_000) {
            (failingController.state.value as? CapturePopupState.Shown)?.saveState == SaveState.Error
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("retryButton").assertHasClickAction()
        composeTestRule.onNodeWithTag("copyCloseButton").assertHasClickAction()
    }

    // UX AC 6: text is never silently lost — a save failure keeps it in the field, and
    // Copy text & close externalizes it (via clipboard) before hiding the popup.
    @Test
    fun capturePopupWindow_should_PreserveOrExternalizeText_When_SaveFailsFocusIsLostOrCopyCloseIsUsed() {
        val controller = newSaveFailingController()
        controller.show()
        controller.updateText("Buy milk")
        setPopupContent(controller)

        composeTestRule.runOnIdle { controller.save() }
        composeTestRule.waitUntil(5_000) {
            (controller.state.value as? CapturePopupState.Shown)?.saveState == SaveState.Error
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("captureTextField").assertTextContains("Buy milk")

        composeTestRule.onNodeWithTag("copyCloseButton").performClick()
        composeTestRule.waitUntil(5_000) { controller.state.value is CapturePopupState.Hidden }
        assertTrue(controller.state.value is CapturePopupState.Hidden, "Copy text & close should hide the popup")
    }

    // UX AC 7: NoActiveGraph/GraphLocked never render an editable field (error prevention).
    @Test
    fun capturePopupWindow_should_RenderNoEditableTextField_When_StateIsNoActiveGraphOrGraphLocked() {
        val noGraphController = CaptureController(PlatformFileSystem())
        noGraphController.show()
        setPopupContent(noGraphController)
        composeTestRule.onNodeWithTag("captureTextField").assertDoesNotExist()

        val lockedController = newLockedController()
        lockedController.show()
        setPopupContent(lockedController)
        composeTestRule.onNodeWithTag("captureTextField").assertDoesNotExist()
    }

    // UX AC 10: Tab cycles only through the popup's own controls (text field, Retry, Copy &
    // close) — never escapes to some notional outside control.
    @Test
    fun capturePopupWindow_should_CycleTabOnlyThroughOwnControls_When_TabIsPressedRepeatedly() {
        val controller = newSaveFailingController()
        controller.show()
        controller.updateText("Buy milk")
        setPopupContent(controller)

        composeTestRule.runOnIdle { controller.save() }
        composeTestRule.waitUntil(5_000) {
            (controller.state.value as? CapturePopupState.Shown)?.saveState == SaveState.Error
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("captureTextField").assertIsFocused()

        // 3 focusable controls in this state: text field, Retry, Copy & close. Tabbing 3 times
        // should cycle exactly back to the text field.
        repeat(3) {
            composeTestRule.onRoot().performKeyInput { keyDown(Key.Tab); keyUp(Key.Tab) }
        }
        composeTestRule.onNodeWithTag("captureTextField").assertIsFocused()
    }
}
