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
 * Coverage for [CapturePopupContent] (the popup's rendered body, minus the real AWT [Window] —
 * see that composable's class doc for why tests exercise it directly rather than through
 * [CapturePopupWindow]). Uses a real `IN_MEMORY`-backed [GraphManager] via [CaptureController],
 * matching [CaptureControllerTest]'s construction pattern, since [CaptureController] is a
 * concrete class (not an interface) and its `save()` launches on its own internal scope.
 */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class CapturePopupWindowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Same override as `CaptureControllerTest` — a normal, writable (non-vault) graph. */
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
        val graphPath = Files.createTempDirectory("capture-popup-window-test").toString()
        val id = graphManager.addGraph(graphPath)
        graphManager.switchGraph(id)
        graphManager.awaitPendingMigration()

        val controller = CaptureController(PlatformFileSystem.withRoot(graphPath))
        controller.attachGraphManager(graphManager)
        controller
    }

    /** No graph attached at all — `show()` resolves straight to [CaptureResult.NoActiveGraph]. */
    private fun newUnavailableController(): CaptureController = CaptureController(PlatformFileSystem())

    private fun setPopupContent(controller: CaptureController) {
        composeTestRule.setContent {
            MaterialTheme {
                CapturePopupContent(controller)
            }
        }
    }

    @Test
    fun capturePopupWindow_should_AutofocusTextField_When_StateTransitionsToShown() {
        val controller = newActiveController()
        controller.show()
        setPopupContent(controller)

        composeTestRule.onNodeWithTag("captureTextField").assertIsFocused()
    }

    @Test
    fun capturePopupWindow_should_PreserveTypedTextAndShowRetryButton_When_SaveStateIsError() {
        val controller = newUnavailableController()
        controller.show()
        controller.updateText("Buy milk")
        setPopupContent(controller)

        composeTestRule.runOnIdle { controller.save() }
        composeTestRule.waitUntil(5_000) {
            (controller.state.value as? CapturePopupState.Shown)?.saveState == SaveState.Error
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("captureTextField").assertTextContains("Buy milk")
        composeTestRule.onNodeWithTag("retryButton").assertExists()
    }

    @Test
    fun capturePopupWindow_should_CallControllerDismiss_When_EscapePressed() {
        val controller = newActiveController()
        controller.show()
        controller.updateText("some draft")
        setPopupContent(controller)

        composeTestRule.onNodeWithTag("captureTextField").performKeyInput {
            keyDown(Key.Escape)
            keyUp(Key.Escape)
        }

        composeTestRule.waitUntil(5_000) { controller.state.value is CapturePopupState.Hidden }
        assertTrue(controller.state.value is CapturePopupState.Hidden, "Escape should dismiss the popup")
    }

    @Test
    fun capturePopupWindow_should_CallControllerSave_When_CtrlEnterPressed() {
        val controller = newActiveController()
        controller.show()
        setPopupContent(controller)

        composeTestRule.onNodeWithTag("captureTextField").performTextInput("Buy milk")
        composeTestRule.onNodeWithTag("captureTextField").performKeyInput {
            keyDown(Key.CtrlLeft)
            keyDown(Key.Enter)
            keyUp(Key.Enter)
            keyUp(Key.CtrlLeft)
        }

        composeTestRule.waitUntil(5_000) {
            (controller.state.value as? CapturePopupState.Shown)?.saveState == SaveState.Saved
        }
        composeTestRule.onNodeWithText("Saved to today's journal").assertExists()
    }
}
