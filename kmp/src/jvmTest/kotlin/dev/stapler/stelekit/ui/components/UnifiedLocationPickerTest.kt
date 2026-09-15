// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stapler.stelekit.model.StorageLocation
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Story 2.1.1 (Task 2.1.1e): [UnifiedLocationPicker]'s default (no-selection) state, confirm-button
 * gating, accessibility, and cancel-leaves-caller-unchanged behavior — see
 * project_plans/app-owned-storage-clone/implementation/validation.md's Story 2.1.1 test mapping.
 */
class UnifiedLocationPickerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val appStorageSubtitle =
        "Kept inside SteleKit only — not visible in your device's file manager, and removed if you uninstall the app."

    private fun renderPicker(
        graphId: String = "graph-1",
        platformCapabilities: Boolean = true,
        onBrowseRequest: suspend () -> StorageLocation? = { null },
        onConfirm: (StorageLocation) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                UnifiedLocationPicker(
                    title = "Choose where to keep this graph",
                    graphId = graphId,
                    appStorageSubtitle = appStorageSubtitle,
                    platformCapabilities = platformCapabilities,
                    onBrowseRequest = onBrowseRequest,
                    onConfirm = onConfirm,
                    onDismiss = onDismiss,
                )
            }
        }
    }

    @Test
    fun unifiedLocationPicker_should_RenderAppStorageAsPinnedFirstRow_When_FirstOpened() {
        renderPicker()

        composeTestRule.onNodeWithTag(UNIFIED_LOCATION_PICKER_APP_STORAGE_ROW_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("App storage").assertIsDisplayed()
        composeTestRule.onNodeWithText(appStorageSubtitle).assertIsDisplayed()
    }

    @Test
    fun unifiedLocationPicker_should_ShowNoPreSelectedRow_When_FirstOpened() {
        renderPicker()

        composeTestRule.onNodeWithTag(UNIFIED_LOCATION_PICKER_APP_STORAGE_ROW_TAG).assertIsNotSelected()
        composeTestRule.onNodeWithTag(UNIFIED_LOCATION_PICKER_BROWSE_ROW_TAG).assertIsNotSelected()
    }

    @Test
    fun unifiedLocationPicker_should_KeepConfirmDisabled_When_NoRowSelected() {
        renderPicker()

        composeTestRule.onNodeWithText("Next").assertIsNotEnabled()
    }

    @Test
    fun unifiedLocationPicker_should_ConfirmInTwoTaps_When_AppStorageSelected() {
        var confirmed: StorageLocation? = null
        renderPicker(graphId = "graph-42", onConfirm = { confirmed = it })

        // Tap 1: select "App storage".
        composeTestRule.onNodeWithTag(UNIFIED_LOCATION_PICKER_APP_STORAGE_ROW_TAG).performClick()
        composeTestRule.onNodeWithText("Next").assertIsEnabled()

        // Tap 2: confirm.
        composeTestRule.onNodeWithText("Next").performClick()

        assertEquals(StorageLocation.AppOwned("graph-42"), confirmed)
    }

    @Test
    fun unifiedLocationPicker_should_SelectAppStorageRow_When_Tapped() {
        renderPicker()

        composeTestRule.onNodeWithTag(UNIFIED_LOCATION_PICKER_APP_STORAGE_ROW_TAG).performClick()

        composeTestRule.onNodeWithTag(UNIFIED_LOCATION_PICKER_APP_STORAGE_ROW_TAG).assertIsSelected()
        composeTestRule.onNodeWithTag(UNIFIED_LOCATION_PICKER_BROWSE_ROW_TAG).assertIsNotSelected()
    }

    @Test
    fun unifiedLocationPicker_should_OmitBrowseRow_When_PlatformCapabilitiesFalse() {
        renderPicker(platformCapabilities = false)

        composeTestRule.onNodeWithTag(UNIFIED_LOCATION_PICKER_APP_STORAGE_ROW_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(UNIFIED_LOCATION_PICKER_BROWSE_ROW_TAG).assertDoesNotExist()
    }

    @Test
    fun unifiedLocationPicker_should_ReturnHostFolder_When_BrowseResolvesOnWeb() {
        var confirmed: StorageLocation? = null
        renderPicker(
            graphId = "graph-web",
            onBrowseRequest = { StorageLocation.HostFolder("graph-web", "Documents") },
            onConfirm = { confirmed = it },
        )

        composeTestRule.onNodeWithTag(UNIFIED_LOCATION_PICKER_BROWSE_ROW_TAG).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(UNIFIED_LOCATION_PICKER_BROWSE_ROW_TAG).assertIsSelected()
        composeTestRule.onNodeWithText("Documents").assertIsDisplayed()

        composeTestRule.onNodeWithText("Next").performClick()

        assertEquals(StorageLocation.HostFolder("graph-web", "Documents"), confirmed)
    }

    @Test
    fun unifiedLocationPicker_should_RevertBrowseRowToUnselected_When_NativePickerCancelled() {
        renderPicker(onBrowseRequest = { null })

        composeTestRule.onNodeWithTag(UNIFIED_LOCATION_PICKER_BROWSE_ROW_TAG).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(UNIFIED_LOCATION_PICKER_BROWSE_ROW_TAG).assertIsNotSelected()
        composeTestRule.onNodeWithText("Next").assertIsNotEnabled()
    }

    @Test
    fun unifiedLocationPicker_should_MergeLabelAndSubtitleIntoOneSemanticsNode_When_Rendered() {
        renderPicker()

        val node = composeTestRule.onNodeWithTag(UNIFIED_LOCATION_PICKER_APP_STORAGE_ROW_TAG).fetchSemanticsNode()
        val mergedText = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
            .joinToString(" ") { it.text }

        assert(mergedText.contains("App storage")) { "merged text was: $mergedText" }
        assert(mergedText.contains(appStorageSubtitle)) { "merged text was: $mergedText" }
    }

    @Test
    fun unifiedLocationPicker_should_ReturnCallerUnchanged_When_CancelledBeforeNextTapped() {
        var confirmed: StorageLocation? = null
        var dismissed = false
        renderPicker(onConfirm = { confirmed = it }, onDismiss = { dismissed = true })

        composeTestRule.onNodeWithTag(UNIFIED_LOCATION_PICKER_APP_STORAGE_ROW_TAG).performClick()
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertNull(confirmed)
        assert(dismissed) { "onDismiss should be invoked when Cancel is tapped" }
    }
}
