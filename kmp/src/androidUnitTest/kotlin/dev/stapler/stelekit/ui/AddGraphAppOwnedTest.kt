// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0

package dev.stapler.stelekit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.stapler.stelekit.platform.FileSystem
import dev.stapler.stelekit.platform.PlatformFileSystem
import dev.stapler.stelekit.ui.components.PLAIN_GRAPH_APP_OWNED_WARNING_ANDROID_COPY
import dev.stapler.stelekit.ui.components.PlainGraphAppOwnedWarningDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story 2.2.1 (Task 2.2.1f): the Android new-graph flow never launches a SAF intent when "App
 * storage" is selected, and the mandatory plain-graph warning (ADR-003) shows the exact copy with
 * its "Export as .zip" affordance. See `project_plans/app-owned-storage-clone/implementation/
 * validation.md`'s REQ-1 mapping.
 */
@RunWith(RobolectricTestRunner::class)
class AddGraphAppOwnedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Records every native-picker call so a test can assert it was never made. */
    private class RecordingFileSystem(
        override val supportsAppOwnedStorage: Boolean,
        override val supportsNativeDirectoryPicker: Boolean = true,
    ) : FileSystem {
        var pickDirectoryCalls = 0
        override fun getDefaultGraphPath() = "/fake"
        override fun expandTilde(path: String) = path
        override fun readFile(path: String): String? = null
        override fun writeFile(path: String, content: String) = true
        override fun listFiles(path: String) = emptyList<String>()
        override fun listDirectories(path: String) = emptyList<String>()
        override fun fileExists(path: String) = false
        override fun directoryExists(path: String) = false
        override fun createDirectory(path: String) = true
        override fun deleteFile(path: String) = true
        override fun pickDirectory(): String? {
            pickDirectoryCalls++
            return null
        }
        override fun getLastModifiedTime(path: String): Long? = null
    }

    // ── Task 2.2.1f: no SAF intent for AppOwned ─────────────────────────────────────────────

    @Test
    fun `addGraphFlowMode should ShowLocationPicker when fileSystem supportsAppOwnedStorage`() {
        val fileSystem = RecordingFileSystem(supportsAppOwnedStorage = true)

        val mode = addGraphFlowMode(fileSystem)

        assertEquals(
            AddGraphFlowMode.ShowLocationPicker,
            mode,
            "an AppOwned-capable platform must route through UnifiedLocationPicker, not straight to the native picker",
        )
        // onAddGraph's ImmediateNativePicker branch is the only one that ever calls
        // pickDirectoryAsync()/pickDirectory() — ShowLocationPicker mode never reaches it.
        assertEquals(0, fileSystem.pickDirectoryCalls)
    }

    @Test
    fun `addGraphFlowMode should ImmediateNativePicker when platform lacks AppOwned storage`() {
        val fileSystem = RecordingFileSystem(supportsAppOwnedStorage = false, supportsNativeDirectoryPicker = true)

        assertEquals(AddGraphFlowMode.ImmediateNativePicker, addGraphFlowMode(fileSystem))
    }

    @Test
    fun `real Android PlatformFileSystem supportsAppOwnedStorage is true`() {
        val fileSystem = PlatformFileSystem().apply { init(ApplicationProvider.getApplicationContext()) }

        assertTrue(fileSystem.supportsAppOwnedStorage, "Android must offer the App storage option with zero SAF grant")
        assertEquals(AddGraphFlowMode.ShowLocationPicker, addGraphFlowMode(fileSystem))
    }

    // ── Task 2.2.1d: plain-graph AppOwned warning ───────────────────────────────────────────

    private fun renderWarningDialog(
        onExportZip: (suspend () -> Boolean)? = null,
        onCreateAnyway: () -> Unit = {},
        onGoBack: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                PlainGraphAppOwnedWarningDialog(
                    bodyText = PLAIN_GRAPH_APP_OWNED_WARNING_ANDROID_COPY,
                    onExportZip = onExportZip,
                    onCreateAnyway = onCreateAnyway,
                    onGoBack = onGoBack,
                )
            }
        }
    }

    @Test
    fun `warningDialog should ShowExactAndroidCopy when PlainGraphAppOwnedSelected`() {
        renderWarningDialog()

        composeTestRule.onNodeWithText(PLAIN_GRAPH_APP_OWNED_WARNING_ANDROID_COPY).assertIsDisplayed()
    }

    @Test
    fun `warningDialog should ShowExportButton when onExportZip provided`() {
        renderWarningDialog(onExportZip = { true })

        composeTestRule.onNodeWithText("Export as .zip").assertIsDisplayed()
    }

    @Test
    fun `warningDialog should OmitExportButton when onExportZip null`() {
        renderWarningDialog(onExportZip = null)

        composeTestRule.onNodeWithText("Export as .zip").assertDoesNotExist()
    }

    @Test
    fun `warningDialog should InvokeOnCreateAnyway when that button tapped`() {
        var created = false
        renderWarningDialog(onCreateAnyway = { created = true })

        composeTestRule.onNodeWithText("Create anyway").performClick()

        assertTrue(created)
    }

    @Test
    fun `warningDialog should InvokeOnGoBack when that button tapped`() {
        var wentBack = false
        renderWarningDialog(onGoBack = { wentBack = true })

        composeTestRule.onNodeWithText("Go back").performClick()

        assertTrue(wentBack)
    }
}
